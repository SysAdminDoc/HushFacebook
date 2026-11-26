/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.menu.passListThrough
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/*
 * People you may know in the Friends tab (#600). Read from 588, 2026-11-26; the obfuscated names are
 * for reviewers, the code never writes one down.
 *
 * The tab is one section (arm64 LX/0Ar7) naming itself "FriendingJewelContentSection". Its rows come
 * from one paginated GraphQL list ("dynamic_friending_tab_paginating") through a connection section
 * (LX/0ArH), which hands each page to the section's event method (A0m) as a load result (LX/0ArJ: a
 * status, the ImmutableList of rows so far, and a Throwable). The section reads the list off it, runs
 * its own pass that keeps the rows it can draw (by each row's getTypeName(): SectionHeaderFriendingTabRow,
 * FriendRequestsFriendingTabRow, PeopleYouMayKnowFriendingTabRow and others), and maps each kept row's
 * id to its place. A header says which section it opens in its "type" field, read with
 * getCachedNullableStringDef("type".hashCode(), "GraphQLFriendingTabSectionType", ...), which is
 * TreeJNI.getString(key) underneath; the suggestions' header says PEOPLE_YOU_MAY_KNOW.
 *
 * The hook goes right after that read: the list goes through the extension, which leaves out the
 * suggestions' header and the rows under it, and back through ImmutableList.copyOf into the same
 * register, so Facebook's own pass and its id map see only what's drawn. The load result class is
 * shared by other surfaces' sections, so only this section's read is hooked.
 */

/** The name the Friends tab's section gives itself. */
internal const val FRIENDS_TAB_SECTION = "FriendingJewelContentSection"

/** The type name of a section header row, which the section's event method compares rows with. */
internal const val FRIENDS_TAB_HEADER = "SectionHeaderFriendingTabRow"

internal const val FRIENDS_TAB_ROWS =
    "$EXTENSION_PACKAGE/feed/FriendsTabSuggestions;->rows(Ljava/util/List;)Ljava/util/List;"

private const val ROW_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val THROWABLE = "Ljava/lang/Throwable;"

/** Where the Friends tab's section reads its rows: the `iget-object` at [index] in [method], into [register]. */
internal class FriendsTabRowsRead(val method: Method, val index: Int, val register: Int)

/**
 * Each read, in a method of the section naming itself [FRIENDS_TAB_SECTION] that holds
 * [FRIENDS_TAB_HEADER], of an ImmutableList field off a load result: a class that also keeps a
 * Throwable in a field of its own.
 */
internal fun BytecodePatchContext.friendsTabRowsReads(): List<FriendsTabRowsRead> =
    classDefByStrings(FRIENDS_TAB_SECTION, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { methodsHolding(it, FRIENDS_TAB_HEADER) }
        .flatMap { method -> rowsReads(method) }

private fun BytecodePatchContext.rowsReads(method: Method): List<FriendsTabRowsRead> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { index ->
        val read = code[index]
        if (read.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        val field = (read as ReferenceInstruction).reference as? FieldReference ?: return@mapNotNull null
        if (field.type != ROW_LIST) return@mapNotNull null
        val owner = classDefByOrNull(field.definingClass) ?: return@mapNotNull null
        val loadResult = owner.fields.any { it.type == THROWABLE && !AccessFlags.STATIC.isSet(it.accessFlags) }
        if (!loadResult) return@mapNotNull null
        FriendsTabRowsRead(method, index, (read as TwoRegisterInstruction).registerA)
    }
}

/**
 * Hands the Friends tab's rows to the extension right after the section reads them, and copies the
 * answer back into an ImmutableList in the same register. Refuses unless there's one such read.
 */
internal fun BytecodePatchContext.hookFriendsTabSuggestions() {
    val reads = friendsTabRowsReads()
    val read = reads.singleOrNull()
        ?: throw PatchException("expected one read of the Friends tab's rows in \"$FRIENDS_TAB_SECTION\", found ${reads.size}")
    mutableClassDefBy(read.method.definingClass).findMutableMethodOf(read.method)
        .passListThrough(read.index, read.register, FRIENDS_TAB_ROWS)
}
