/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharesheet

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.upsells.enumConstant
import app.morphe.patches.facebook.misc.upsells.isEnumNaming
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The share sheet's group buttons, read from 581 on 2026-10-09. The obfuscated names here are for
 * reviewers; the code finds each by a kept name.
 *
 * Pick two or more people in a post's or reel's share sheet and its footer (581 LX/YyP;->render)
 * builds a Send to group FDSButton beside the send button, which then reads Send separately. The
 * footer builds that button only when every pick is a person rather than a group chat, and keeps it
 * in one register where its guards meet. Its tap is SharesheetMessagingActionsImpl's
 * onSendToGroupCtaButtonClicked, which makes a new group chat of the picks. The footer is the one
 * render that loads "private_sharing_forward_super_share_sheet", asks the kept ThreadKey whether a
 * pick is a group chat (a static boolean over one ThreadKey, `A0V` on 583 and 584) and builds
 * FDSButtons. The Send to group button is the first FDSButton it builds (581 string 0x7f1443a2); the
 * send button is the second. Through 583 the footer also named SharesheetMessagingActionsImpl, but
 * 584 renamed that class (`LX/I9d;`), so the pick question is what tells the footer apart.
 *
 * The sheet also offers a new group of its own, each place behind its own MobileConfig flag and
 * drawn with the FDS icon GROUP_PLUS: a "Send to group" row above the people and an icon on their
 * header, both in the sheet's body (581 LX/Z5P;->render, the one render loading
 * "messenger_suggested_recipients_hscroll"), and a button beside the search box (581
 * LX/cOR;->render, the one loading "search_bar_and_create_group_row"). Each GROUP_PLUS read sits
 * after an if-eqz on one boolean, a MobileConfig answer or the search row's own field, and the
 * branch skips the icon when it's false, which is the path Facebook takes with the flag off.
 *
 * The people picker's own Send to group, in the broadcast flow (581 LX/bNA), isn't touched here: its
 * footer slots are shared with group creation and message forwarding.
 */

/** The extension class both hooks ask. */
internal const val SHARE_SHEET_GROUPS = "$EXTENSION_PACKAGE/misc/ShareSheetGroups;"
internal const val SEND_TO_GROUP_BUTTON = "$SHARE_SHEET_GROUPS->sendToGroupButton(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val OFFER_NEW_GROUP = "$SHARE_SHEET_GROUPS->offerNewGroup(Z)Z"

internal const val FDS_BUTTON = "Lcom/facebook/fds/FDSButton;"

/** Kept name. Each pick in the share sheet is one, a person's chat or a group's. */
internal const val THREAD_KEY = "Lcom/facebook/messaging/model/threadkey/ThreadKey;"

/** A literal only the share sheet footer's render loads among renders. */
internal const val SHARE_FOOTER_ANCHOR = "private_sharing_forward_super_share_sheet"

/** The test id of the people row in the share sheet's body, which only its render loads. */
internal const val SHARE_BODY_ANCHOR = "messenger_suggested_recipients_hscroll"

/** The test id of the share sheet's search row. */
internal const val SHARE_SEARCH_ROW_ANCHOR = "search_bar_and_create_group_row"

/** The FDS icon every new-group entry in the share sheet is drawn with, and its neighbour in the enum. */
internal const val GROUP_PLUS = "GROUP_PLUS"
internal val GROUP_ICON_NAMES = listOf(GROUP_PLUS, "GROUPS")

/** A Litho component's `render`, a name the framework keeps. */
private const val RENDER = "render"

private const val PATCH = "Share sheet items (group buttons)"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The share sheet's group buttons answer through the extension's ShareSheetGroups: the footer's
 * Send to group button, and the new-group row, header icon and search button. Nameless, so Share
 * sheet items carries it and its fixture tests run it on its own. Everything is found before
 * anything changes, and it refuses unless each place is found once.
 */
internal val shareSheetGroupsPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        hookShareSheetGroups()
    }
}

/** Where a new-group entry's boolean goes through the extension: the index after it's set, and its register. */
internal data class GroupGuard(val insertAt: Int, val register: Int)

/** Where the footer's Send to group button goes through the extension: the index after it's kept, and its register. */
internal data class GroupButton(val insertAt: Int, val register: Int)

internal fun BytecodePatchContext.hookShareSheetGroups() {
    val icons = classDefByStrings(GROUP_PLUS, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter { isEnumNaming(it, GROUP_ICON_NAMES) }
    val groupPlus = enumConstant(icons.singleOrNull() ?: refuse("expected one icon enum naming GROUP_PLUS, found ${icons.size}"), GROUP_PLUS)

    val footer = singleRender(SHARE_FOOTER_ANCHOR) { isShareFooter(it) }
    val button = sendToGroupButton(footer) ?: refuse("the share sheet footer builds no Send to group button it keeps")
    val body = singleRender(SHARE_BODY_ANCHOR) { true }
    val bodyGuards = newGroupGuards(body, groupPlus)
    if (bodyGuards.size != 2) refuse("expected the share sheet body's new-group row and header icon, found ${bodyGuards.size}")
    val search = singleRender(SHARE_SEARCH_ROW_ANCHOR) { true }
    val searchGuards = newGroupGuards(search, groupPlus)
    if (searchGuards.size != 1) refuse("expected the share sheet search row's new-group button, found ${searchGuards.size}")

    mutableClassDefBy(footer.definingClass).findMutableMethodOf(footer).addInstructionsAtControlFlowLabel(
        button.insertAt,
        """
            invoke-static/range { v${button.register} .. v${button.register} }, $SEND_TO_GROUP_BUTTON
            move-result-object v${button.register}
            check-cast v${button.register}, $FDS_BUTTON
        """,
    )
    listOf(body to bodyGuards, search to searchGuards).forEach { (render, guards) ->
        val method = mutableClassDefBy(render.definingClass).findMutableMethodOf(render)
        guards.sortedByDescending { it.insertAt }.forEach { guard ->
            method.addInstructions(
                guard.insertAt,
                """
                    invoke-static/range { v${guard.register} .. v${guard.register} }, $OFFER_NEW_GROUP
                    move-result v${guard.register}
                """,
            )
        }
    }
}

/**
 * The one render naming [anchor] that [wanted] takes, loading it or, as on 582 (`LX/mDc;->A00`),
 * asking a string table for it ([methodsNaming]).
 */
private fun BytecodePatchContext.singleRender(anchor: String, wanted: (Method) -> Boolean): Method {
    val renders = methodsNaming(anchor) { it.name == RENDER && wanted(it) }
    return renders.singleOrNull() ?: refuse("expected one render loading \"$anchor\", found ${renders.size}")
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/**
 * Whether [method] is the share sheet footer's render: it asks [THREAD_KEY] a static boolean
 * question about one ThreadKey, whether a pick is a group chat, and builds FDSButtons.
 */
internal fun isShareFooter(method: Method): Boolean {
    val code = method.code()
    return code.any { instruction ->
        val call = instruction.reference() as? MethodReference
        instruction.opcode == Opcode.INVOKE_STATIC && call?.definingClass == THREAD_KEY && call.returnType == "Z" &&
            call.parameterTypes.map { it.toString() } == listOf(THREAD_KEY)
    } && code.any { it.isFdsButtonInit() }
}

private fun Instruction.isFdsButtonInit(): Boolean {
    val called = reference() as? MethodReference ?: return false
    return (opcode == Opcode.INVOKE_DIRECT || opcode == Opcode.INVOKE_DIRECT_RANGE) &&
        called.definingClass == FDS_BUTTON && called.name == "<init>"
}

/**
 * The footer's Send to group button: the first FDSButton it builds. Up to 582 it's copied into the
 * register its guards meet in by the move right after the constructor, and the hook goes at the
 * instruction after that move. 583 builds it straight into that register: the `new-instance`
 * register, which the constructor call reaches through a copy, is the one a later null test reads
 * (nothing writes it in between), so the hook goes right after the constructor and takes that
 * register. Either way it sits at its control flow label, so the paths that skip the button run it
 * too, with the register still null (584's skip sets it null further down and jumps back there).
 * Null when the first FDSButton isn't kept either way.
 */
internal fun sendToGroupButton(footer: Method): GroupButton? {
    val code = footer.code()
    val built = code.indexOfFirst { it.isFdsButtonInit() }
    if (built < 0 || built + 2 >= code.size) return null
    val created = (built - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.NEW_INSTANCE && (code[it].reference() as? TypeReference)?.type == FDS_BUTTON
    } ?: return null
    val made = (code[created] as OneRegisterInstruction).registerA
    val move = code[built + 1]
    if (move.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)) {
        if ((move as TwoRegisterInstruction).registerB != made) return null
        return GroupButton(built + 2, (move as OneRegisterInstruction).registerA)
    }
    val next = (built + 1 until code.size).firstOrNull {
        writesRegister(code[it], made) ||
            ((code[it].opcode == Opcode.IF_EQZ || code[it].opcode == Opcode.IF_NEZ) && (code[it] as OneRegisterInstruction).registerA == made)
    } ?: return null
    if (code[next].opcode != Opcode.IF_EQZ && code[next].opcode != Opcode.IF_NEZ) return null
    return GroupButton(built + 1, made)
}

/**
 * Each new-group entry [method] draws with [groupPlus]: the nearest branch before the icon is read
 * has to be an if-eqz that skips past it, and the boolean it tests has to be set last by a
 * move-result of a call answering a boolean or by an iget-boolean. The hook goes right after that,
 * so every later read of the boolean sees the answer, the search row's padding included.
 */
internal fun newGroupGuards(method: Method, groupPlus: FieldReference): List<GroupGuard> {
    val code = method.code()
    val offsets = IntArray(code.size)
    var at = 0
    code.forEachIndexed { index, instruction -> offsets[index] = at; at += instruction.codeUnits }
    val reads = code.indices.filter {
        code[it].opcode == Opcode.SGET_OBJECT && code[it].reference()?.toString() == groupPlus.toString()
    }
    return reads.map { read ->
        val branch = (read - 1 downTo 0).firstOrNull { code[it] is OffsetInstruction }
            ?: refuse("${method.definingClass}->${method.name} reads $GROUP_PLUS with no branch before it")
        val guard = code[branch]
        if (guard.opcode != Opcode.IF_EQZ || offsets[branch] + (guard as OffsetInstruction).codeOffset <= offsets[read]) {
            refuse("${method.definingClass}->${method.name}: the branch before $GROUP_PLUS isn't an if-eqz past it")
        }
        val register = (guard as OneRegisterInstruction).registerA
        val set = (branch - 1 downTo 0).firstOrNull { writesRegister(code[it], register) }
            ?: refuse("${method.definingClass}->${method.name}: nothing sets v$register before its $GROUP_PLUS guard")
        val setter = code[set]
        val boolean = when (setter.opcode) {
            Opcode.IGET_BOOLEAN -> true
            Opcode.MOVE_RESULT -> set > 0 && (code[set - 1].reference() as? MethodReference)?.returnType == "Z"
            else -> false
        }
        if (!boolean) refuse("${method.definingClass}->${method.name}: v$register isn't a boolean before its $GROUP_PLUS guard")
        GroupGuard(set + 1, register)
    }
}
