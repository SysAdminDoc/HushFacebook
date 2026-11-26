/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.profile

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Your friends list, read from 588 on 2026-11-26 (#597). The obfuscated names are for reviewers; the
 * code finds the places by the section's own strings and Facebook's GraphQL list type.
 *
 * Profile > Friends > All friends is one section (arm64 LX/0F9H) naming itself
 * "AllFriendListContentSection", the same section for your own list and for anyone else's. Its
 * build method, the one holding "AllFriendListContentSectionSpec", reads one boolean of the section:
 * whether the list is your own (A07). Its event method (A0m) handles the list's load in the case
 * holding "all_friends_list": it casts the section, reads whether filters are picked (A06, which
 * leads to "filtered_friends_list" instead), then asks the load for the PaginableList of everyone
 * loaded so far. The event method reads one more boolean of the section, which is true while the
 * list's search box is empty (A08).
 *
 * Two hooks: right after that cast the section goes to the extension, and right after the list is
 * read the list does. The extension keeps the list only when the section says it's your own with an
 * empty search, and only once the list has no next page left. Facebook's own code keeps going as
 * it was, with the same list.
 */

private const val PATCH = "Friends no longer on your list"

/** The name the All friends section gives itself, in its own class. */
internal const val ALL_FRIENDS_SECTION = "AllFriendListContentSection"

/** The name of the section's spec, in its build method. */
internal const val ALL_FRIENDS_SPEC = "AllFriendListContentSectionSpec"

/** The name of the list's load, in the case of the event method handling it. */
internal const val ALL_FRIENDS_LIST = "all_friends_list"

internal const val PAGINABLE_LIST = "Lcom/facebook/graphservice/interfaces/PaginableList;"

internal const val FRIEND_LIST = "$EXTENSION_PACKAGE/profile/FriendList;"
internal const val SECTION_HOOK = "$FRIEND_LIST->section(Ljava/lang/Object;)V"
internal const val PAGE_HOOK = "$FRIEND_LIST->allFriendsPage(Ljava/lang/Object;)V"

/**
 * Where the hooks go, in [event], the section's event method.
 *
 * @property cast the index of the check-cast to the section the list's case starts with.
 * @property section the register holding the section after it.
 * @property read the index of the call asking the load for its PaginableList.
 * @property page the register its result moves into, at [read] + 1.
 * @property ownField the name of the section's flag that's true for your own list.
 * @property noSearchField the name of the section's flag that's true while its search is empty.
 */
internal class FriendListSites(
    val event: Method,
    val cast: Int,
    val section: Int,
    val read: Int,
    val page: Int,
    val ownField: String,
    val noSearchField: String,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.booleanFieldOf(type: String): FieldReference? {
    if (opcode != Opcode.IGET_BOOLEAN) return null
    val field = (this as ReferenceInstruction).reference as? FieldReference ?: return null
    return field.takeIf { it.definingClass == type }
}

/**
 * The All friends section and, in its event method, the places the hooks go. Refuses a build with
 * no such section or more than one, a build method reading anything but one flag of the section, an
 * event method not reading the list the way 588 does, and a place something jumps straight to,
 * which would go around a hook. Reads only.
 */
internal fun BytecodePatchContext.findFriendListSites(): FriendListSites {
    val sections = classDefByStrings(ALL_FRIENDS_SECTION, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .filter { methodsHolding(it, ALL_FRIENDS_SPEC).isNotEmpty() && methodsHolding(it, ALL_FRIENDS_LIST).isNotEmpty() }
    val section: ClassDef = sections.singleOrNull()
        ?: refuse("expected one section naming itself \"$ALL_FRIENDS_SECTION\", found ${sections.size}")
    val type = section.type

    val build = methodsHolding(section, ALL_FRIENDS_SPEC).singleOrNull()
        ?: refuse("expected one method of $type holding \"$ALL_FRIENDS_SPEC\"")
    val buildFlags = build.implementation!!.instructions.mapNotNull { it.booleanFieldOf(type)?.name }.distinct()
    val ownField = buildFlags.singleOrNull()
        ?: refuse("expected $type's build method to read one flag of the section, found ${buildFlags.size}")

    val event = methodsHolding(section, ALL_FRIENDS_LIST).singleOrNull()
        ?: refuse("expected one method of $type holding \"$ALL_FRIENDS_LIST\"")
    val where = "$type->${event.name}"
    val code = event.implementation!!.instructions.toList()
    val named = code.indices.filter { code[it].string() == ALL_FRIENDS_LIST }
    val name = named.singleOrNull() ?: refuse("expected $where to name \"$ALL_FRIENDS_LIST\" once, found ${named.size}")
    val cast = (name - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.CHECK_CAST && ((code[it] as ReferenceInstruction).reference as TypeReference).type == type
    } ?: refuse("$where never casts the section before \"$ALL_FRIENDS_LIST\"")
    val sectionRegister = (code[cast] as OneRegisterInstruction).registerA
    val read = (name + 1 until code.size).firstOrNull {
        val call = (code[it] as? ReferenceInstruction)?.reference as? MethodReference
        (code[it].opcode == Opcode.INVOKE_INTERFACE || code[it].opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
            call?.returnType == PAGINABLE_LIST
    } ?: refuse("$where never asks for the list's $PAGINABLE_LIST after \"$ALL_FRIENDS_LIST\"")
    val result = code.getOrNull(read + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where drops the list it asked for")
    val page = (result as OneRegisterInstruction).registerA

    // The event method's other flag of the section: not the own-list one, nor the filters' one read
    // between the cast and the list's name.
    val eventFlags = code.mapNotNull { it.booleanFieldOf(type)?.name }.toSet()
    val filterFlags = code.subList(cast + 1, name).mapNotNull { it.booleanFieldOf(type)?.name }.toSet()
    val searchFlags = eventFlags - ownField - filterFlags
    val noSearchField = searchFlags.singleOrNull()
        ?: refuse("expected $where to read one more flag of the section, found ${searchFlags.sorted()}")

    val targets = branchTargets(code)
    if (cast + 1 in targets) refuse("$where jumps straight past where it casts the section")
    if (read + 2 in targets) refuse("$where jumps straight past where it reads the list")
    if (sectionRegister > 15 || page > 15) refuse("$where holds the section or the list in a register a call can't name")
    return FriendListSites(event, cast, sectionRegister, read, page, ownField, noSearchField)
}

/** Hands the list to [PAGE_HOOK] right after it's read, and the section to [SECTION_HOOK] right after its cast. */
internal fun BytecodePatchContext.hookFriendList(sites: FriendListSites) {
    val event = mutableClassDefBy(sites.event.definingClass).findMutableMethodOf(sites.event)
    // The later place first, so the earlier one's index still holds.
    event.addInstruction(sites.read + 2, "invoke-static { v${sites.page} }, $PAGE_HOOK")
    event.addInstruction(sites.cast + 1, "invoke-static { v${sites.section} }, $SECTION_HOOK")
}

/** Fills in the extension's names for the section's own-list and empty-search flags. */
internal fun BytecodePatchContext.fillFriendListFields(sites: FriendListSites) {
    val extension = mutableClassDefBy(FRIEND_LIST)
    fun stub(name: String) = extension.methods.singleOrNull {
        it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: refuse("the extension has no $FRIEND_LIST->$name()")
    stub("ownListField").returnEarly(sites.ownField)
    stub("noSearchField").returnEarly(sites.noSearchField)
}

@Suppress("unused")
val friendListPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Friends no longer on your list",
    description = "Keeps a copy of your friends list on your phone each time you scroll Profile > Friends > All " +
        "friends to its end, and tells you who's no longer on it since the time before. Someone who unfriended " +
        "you, someone you unfriended and a closed account all show the same way. Starts off. Turn it on in " +
        "Hushfacebook settings > News feed, where the list of who dropped off is.",
) {
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val sites = findFriendListSites()
        requireStatusMethod("friendList")
        fillFriendListFields(sites)
        hookFriendList(sites)
        enableStatus("friendList")
    }
}
