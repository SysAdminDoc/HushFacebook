/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.tabbadges

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.navigation.reelstabdot.FB_USER_SESSION
import app.morphe.patches.facebook.navigation.reelstabdot.jewelCountHookPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PATCH = "Hide tab badges"

internal const val TAB_BADGES = "Lapp/morphe/extension/facebook/navigation/TabBadges;"
internal const val ICON_COUNT = "$TAB_BADGES->iconCount(I)I"

internal const val TRI_STATE = "Lcom/facebook/common/util/TriState;"

/** The name the launcher badge writer for every other launcher gives itself. */
internal const val GENERIC_BADGER = "GenericLauncherBadgesInterface"

/** What each launcher badge writer's getAnalyticsName() answers ends with. */
internal const val BADGER_SUFFIX = "LauncherBadgesInterface"

/**
 * The dot and count on the tabs you pick, and Facebook's count on its app icon, go.
 *
 * A tab's count comes from the tab bar's jewel controller, and the count hook Hide the Reels tab dot
 * shares ([jewelCountHookPatch]) answers 0 for every tab whose switch is on. The extension knows each
 * tab by the TabTag class Facebook keeps the name of.
 *
 * Facebook puts its own count on the launcher icon through one writer per launcher family: Generic
 * (the `BADGE_COUNT_UPDATE` broadcast), Samsung, Honor, HTC, Huawei, Motorola, Oppo, Sony,
 * Transsion, Vivo, Xiaomi and ZTE, each answering getAnalyticsName() with its own kept literal.
 * They share one interface, (FbUserSession, int) -> TriState plus that name (581 `LX/NY0;`), and
 * the badge updater hands each the count in turn. The extension goes first in every writer and
 * swaps the count for 0 when the icon switch is on, so the launcher gets a cleared badge.
 * Notifications themselves, and the in-app Notifications list, aren't touched.
 */
@Suppress("unused")
val hideTabBadgesPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide tab badges",
    description = "Takes the dot and count off the tabs you pick, and Facebook's count off its app icon, so the " +
        "app nags you less. Notifications still arrive and still show in the Notifications tab. Starts off. Turn " +
        "on the ones you want in Hushfacebook settings > Tabs and navigation.",
) {
    category("Navigation")
    dependsOn(settingsPatch)
    dependsOn(jewelCountHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        requireStatusMethod("tabBadges")
        findBadgeWriters().forEach { applyIconCount(it) }
        enableStatus("tabBadges")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Whether [method] is a launcher badge write: (FbUserSession, int) -> TriState. */
internal fun isBadgeWrite(method: Method): Boolean =
    method.returnType == TRI_STATE &&
        method.parameterTypes.map(CharSequence::toString) == listOf(FB_USER_SESSION, "I")

/** The literal [writer]'s getAnalyticsName() answers, or null when it has no such method or literal. */
internal fun badgerName(writer: ClassDef): String? =
    writer.methods.singleOrNull { it.name == "getAnalyticsName" && it.parameterTypes.isEmpty() }
        ?.let { method -> method.implementation?.instructions?.firstNotNullOfOrNull { stringOf(it) } }
        ?.takeIf { it.endsWith(BADGER_SUFFIX) }

private fun stringOf(instruction: Instruction): String? =
    ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string

/**
 * The badge writers' interface: the one interface of the class naming itself [GENERIC_BADGER], held
 * to one abstract badge write and getAnalyticsName.
 */
internal fun BytecodePatchContext.badgeWriterInterface(): ClassDef {
    val generic = classDefByStrings(GENERIC_BADGER, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter { holder -> holder.methods.any { it.name == "getAnalyticsName" && holdsString(it, GENERIC_BADGER) } }
    val writer = generic.singleOrNull()
        ?: refuse("expected one class naming itself \"$GENERIC_BADGER\", found ${generic.size}")
    val contract = writer.interfaces.singleOrNull()
        ?: refuse("${writer.type} implements ${writer.interfaces.size} interfaces, expected one")
    val writerInterface = classDefByOrNull(contract) ?: refuse("$contract isn't in this APK")
    if (!AccessFlags.INTERFACE.isSet(writerInterface.accessFlags)) refuse("$contract isn't an interface")
    val writes = writerInterface.methods.filter(::isBadgeWrite)
    if (writes.size != 1 || writerInterface.methods.none { it.name == "getAnalyticsName" }) {
        refuse("$contract has ${writes.size} badge writes and no getAnalyticsName, expected one of each")
    }
    return writerInterface
}

/**
 * Each launcher badge writer's write, every class implementing [badgeWriterInterface] that names
 * itself `*LauncherBadgesInterface`. The generic writer must be among them. Changes nothing.
 */
internal fun BytecodePatchContext.findBadgeWriters(): List<Method> {
    val contract = badgeWriterInterface().type
    val writers = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES) && contract in classDef.interfaces) writers += classDef
    }
    val unnamed = writers.filter { badgerName(it) == null }
    if (unnamed.isNotEmpty()) refuse("badge writers naming no launcher: ${unnamed.joinToString { it.type }}")
    if (writers.none { badgerName(it) == GENERIC_BADGER }) refuse("the $GENERIC_BADGER class doesn't implement $contract")
    return writers.map { writer ->
        writer.methods.singleOrNull { isBadgeWrite(it) && it.implementation != null }
            ?: refuse("${badgerName(writer)} has no single badge write")
    }
}

/**
 * The extension goes first in [write] and hands back the count to write, which replaces the int
 * parameter. The parameter register is overwritten in place, so no local is borrowed. The call is
 * a range: some writers have more than 16 registers (Htc's on 581 has 18), where the count's
 * register is past what a plain invoke can name.
 */
internal fun BytecodePatchContext.applyIconCount(write: Method) {
    val method = mutableClassDefBy(write.definingClass).findMutableMethodOf(write)
    method.addInstructions(
        0,
        """
            invoke-static/range { p2 .. p2 }, $ICON_COUNT
            move-result p2
        """,
    )
}
