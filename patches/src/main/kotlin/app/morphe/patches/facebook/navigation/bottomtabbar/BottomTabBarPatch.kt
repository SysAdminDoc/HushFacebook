/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import app.morphe.util.literalReads
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.morphe.util.namedRegisters

internal const val PATCH = "Tab bar at the bottom"

/** The name of Facebook's own preference that overrides where the tab bar goes. */
internal const val OVERRIDE_KEY = "fb4a_bottom_tabs_override_enabled"

internal const val FB_SHARED_PREFERENCES = "Lcom/facebook/prefs/shared/FbSharedPreferences;"
internal const val TRI_STATE = "Lcom/facebook/common/util/TriState;"

internal const val BOTTOM_TAB_BAR = "Lapp/morphe/extension/facebook/navigation/BottomTabBar;"
internal const val OVERRIDE = "$BOTTOM_TAB_BAR->override(I)I"

/**
 * The tab bar goes to the bottom. Facebook gives some accounts the bar at the top and others at
 * the bottom, by a server setting, and keeps a preference of its own, [OVERRIDE_KEY], that
 * overrides that setting either way. The preference's key is a static field its class sets from
 * that name (580 `LX/1rL;->A01`, 577 `LX/1u6;->A01`). Each place that decides where the bar goes
 * (580 `LX/1rJ;->A06` and `A09`, 577 `LX/1qY;->A06` and `A0A`) reads the key from FbSharedPreferences
 * as a TriState and branches on its ordinal. The extension goes right after each ordinal and
 * answers YES's while the switch is on. Facebook's own tab menu writes the key without reading it,
 * and that's left alone. A load of the key that's neither is refused, since a read the patch
 * can't see would leave that place deciding the bar on its own.
 *
 * On a tablet or an unfolded phone Facebook's big screen layout puts the bar at the top before
 * the override is read. A further switch, off by default, answers the gate of that layout no, so
 * the big screen gets the phone layout and the override decides there too (see BigScreenLayout.kt).
 *
 * See-through bars, one more switch off by default, needs no hook: the extension finds both bars
 * by their kept classes as the main screen draws (TranslucentBars.java, started from the settings
 * entry's onApplicationCreate while this patch is in). Its frost can't blur a video on its own
 * SurfaceView, so while it's on, Turn off HDR brightness's player hook, when that patch is in too,
 * puts feed videos on their TextureView (#250, TextureVideo.kt). Hide the status bar works the same way, and
 * one hook closes the strip Facebook draws behind the bar once it's gone (see StatusBarStrip.kt).
 * Light bars on the Reels tab keeps that tab's bars light in light mode (#423, LightReelsTabBars.kt).
 * Hide the tab bar on the Reels tab takes the bar off that tab alone (ReelsTabBarHidden.kt).
 */
@Suppress("unused")
val bottomTabBarPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Tab bar at the bottom",
    description = "Moves Facebook's tab bar to the bottom of the screen on accounts that have it at the top, " +
        "where it's easier to reach. `Tab bar position` can also put it at the top, as older Facebook had it, or " +
        "leave it where Facebook puts it. `Tab bar while scrolling` lets it slide away as you scroll or keeps it on " +
        "screen, and another switch keeps the top bar on screen. One gives " +
        "tablets and unfolded phones the phone layout so the bar can move there too, and one makes the top bar and " +
        "the tab bar see-through. Another hides the phone's status bar on the main screen until you swipe down " +
        "from the top, and another keeps the Reels tab's bars light in light mode, like the other tabs. One hides " +
        "the tab bar on the Reels tab alone, so the reel gets the whole screen. Three more " +
        "take the Messenger button, its unread count or the Facebook logo out " +
        "of the top bar. Starts off. Pick it in " +
        "Hushfacebook settings > Tabs and navigation, then restart Facebook. The see-through, status bar and " +
        "Reels tab switches sit under Appearance.",
) {
    category("Navigation")
    // The other switches: Facebook's own scroll-away for both bars, in TabBarScrollAwayPatch.kt.
    dependsOn(settingsPatch, tabBarScrollAwayPatch, topBarButtonsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val key = overrideKeyField(
            classDefByStrings(OVERRIDE_KEY, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val reads = mutableListOf<OverrideRead>()
        val paddings = mutableListOf<Method>()
        // A read loads the key's field, and a system bar padding answers Insets.
        classDefForEachReferencing(types = listOf(key.definingClass, ANDROIDX_INSETS)) { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
            classDef.methods.forEach { method ->
                reads += overrideReads(method, key)
                if (takesSystemBarPadding(method)) paddings += method
            }
        }
        if (reads.isEmpty()) refuse("nothing reads $key from FbSharedPreferences")
        hookBigScreenLayout(reads)
        hookStatusBarStrip(paddings)
        hookLightReelsTabBars()
        hookReelsTabBarHidden()
        reads.groupBy { it.method }.forEach { (method, sites) ->
            mutableClassDefBy(method.definingClass).findMutableMethodOf(method).hookOverrideReads(sites)
        }
        enableStatus("bottomTabBar")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** One read of the override: its method, and the move-result that keeps the ordinal in [register]. */
internal class OverrideRead(val method: Method, val resultIndex: Int, val register: Int)

private fun loadsKeyName(instruction: Instruction) =
    instruction.opcode.name.startsWith("const-string") &&
        ((instruction as ReferenceInstruction).reference as StringReference).string == OVERRIDE_KEY

private fun fieldOf(instruction: Instruction) = (instruction as? ReferenceInstruction)?.reference as? FieldReference

/**
 * The static field Facebook keeps the override's key in: what the first sput-object after the
 * load of [OVERRIDE_KEY] in a class's `<clinit>` stores, when that's a field of the same class.
 * Refuses unless [holders] have exactly one.
 */
internal fun overrideKeyField(holders: List<ClassDef>): FieldReference {
    val fields = holders.flatMap { holder ->
        holder.methods.filter { it.name == "<clinit>" }.mapNotNull { clinit ->
            val code = clinit.implementation?.instructions?.toList().orEmpty()
            val name = code.indexOfFirst(::loadsKeyName)
            if (name < 0) return@mapNotNull null
            code.drop(name + 1).firstOrNull { it.opcode == Opcode.SPUT_OBJECT }?.let(::fieldOf)
                ?.takeIf { it.definingClass == holder.type }
        }
    }.distinct()
    return fields.singleOrNull()
        ?: refuse("expected one static field set from \"$OVERRIDE_KEY\", found ${fields.size}")
}

/**
 * The reads of [key] in [method]. A load of the key that goes straight into a call on
 * FbSharedPreferences is a read, and that call has to answer a TriState whose ordinal() is taken
 * right away, by a plain or a range call, the ordinal kept by a move-result and the TriState read
 * nowhere else. A load that goes straight into a call answering nothing, which also takes a
 * boolean or a TriState, is a write, the way Facebook's tab menu stores the key, and left alone.
 * Refuses a load that's neither, and one whose key anything past that call reads again, since a
 * read there would go unseen. Refuses a read that anything but the key's load leads to, and an
 * ordinal that anything but that read's TriState leads to, since another key's answer arriving
 * there would go through the hook too.
 */
internal fun overrideReads(method: Method, key: FieldReference): List<OverrideRead> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val where = "${method.definingClass}->${method.name}"
    return code.indices.filter { code[it].opcode == Opcode.SGET_OBJECT && fieldOf(code[it]) == key }.mapNotNull { load ->
        val keyRegister = (code[load] as OneRegisterInstruction).registerA
        val call = code.getOrNull(load + 1)
        val named = (call as? ReferenceInstruction)?.reference as? MethodReference
        val takesKey = named != null && keyRegister in call.namedRegisters()
        if (takesKey && named.definingClass != FB_SHARED_PREFERENCES && named.returnType == "V" &&
            named.parameterTypes.any { it.toString() == "Z" || it.toString() == TRI_STATE }
        ) {
            method.keyReadOnce(load, where)
            return@mapNotNull null
        }
        val read = named?.takeIf { takesKey && it.definingClass == FB_SHARED_PREFERENCES }
            ?: refuse("$where hands the override's key at $load to neither a read nor a write the patch knows")
        if (read.returnType != TRI_STATE) refuse("$where reads the override with $read, which doesn't answer a TriState")
        val triState = code.getOrNull(load + 2)
        val ordinal = code.getOrNull(load + 3)
        val result = code.getOrNull(load + 4)
        val ordinalCall = (ordinal as? ReferenceInstruction)?.reference as? MethodReference
        val takesOrdinal = triState?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            (ordinal?.opcode == Opcode.INVOKE_VIRTUAL || ordinal?.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            ordinalCall?.name == "ordinal" && ordinalCall.parameterTypes.isEmpty() && ordinalCall.returnType == "I" &&
            ordinal.namedRegisters() == listOf((triState as OneRegisterInstruction).registerA) &&
            result?.opcode == Opcode.MOVE_RESULT
        if (!takesOrdinal) refuse("$where doesn't take the ordinal of the override's TriState right after reading it")
        // In a read like prefs.read(other ? OTHER_KEY : KEY), the other key's way jumps to the
        // read call and its TriState would come out of the hook as the override's.
        val flow = ControlFlow.of(method)
        fun into(at: Int) = flow.normal.indices.filter { at in flow.normal[it] } +
            flow.exceptional.indices.filter { at in flow.exceptional[it] }
        if (into(load + 1) != listOf(load)) {
            refuse("in $where the read of the override at ${load + 1} can be reached from ${into(load + 1)}, not only from the key's load")
        }
        if (into(load + 3) != listOf(load + 2)) {
            refuse("in $where the override's ordinal at ${load + 3} can be reached from ${into(load + 3)}, not only from its read")
        }
        // Anything else reading the TriState would decide the bar from Facebook's answer, past the hook.
        if (method.literalReads(load + 2) != listOf(load + 3)) {
            refuse("$where uses the override's TriState past its ordinal, where the hook can't change it")
        }
        method.keyReadOnce(load, where)
        OverrideRead(method, load + 4, (result as OneRegisterInstruction).registerA)
    }
}

/** Refuses a load of the key at [load] that anything past the call right after it reads again, a read the patch wouldn't see. */
private fun Method.keyReadOnce(load: Int, where: String) {
    if (literalReads(load) != listOf(load + 1)) refuse("$where uses the override's key loaded at $load past the call it goes into")
}

/**
 * Puts the extension right after each read's ordinal. A range call names the register whatever
 * its number, and the extension's answer lands back where Facebook's branches read it. Nothing
 * may jump to the instruction after the ordinal, since code arriving there would skip the call.
 */
internal fun MutableMethod.hookOverrideReads(reads: List<OverrideRead>) {
    for (read in reads.sortedByDescending { it.resultIndex }) {
        val next = implementation!!.instructions[read.resultIndex + 1] as BuilderInstruction
        if (next.location.labels.isNotEmpty()) {
            refuse("$definingClass->$name has a jump to the instruction after the override's ordinal")
        }
        addInstructions(
            read.resultIndex + 1,
            """
                invoke-static/range { v${read.register} .. v${read.register} }, $OVERRIDE
                move-result v${read.register}
            """,
        )
    }
}
