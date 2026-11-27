/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.downloads.photo.COMMENT_SHEET_SOCKET
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.confirm.SHEET_REACTION_TRACE
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The older comment sheet a held comment opens when the glimpse is off for the account (#607).
 * Read from 588, 2026-11-26, arm64, on a group post's comment; the obfuscated names here are for
 * reviewers, the code never writes one down.
 *
 * The sheet is the component whose render loads [COMMENT_SHEET_SOCKET] (588 `LX/0gDG;->render`).
 * Render builds two lists:
 * - The reaction row ("React to this comment"), one component newed only when a flag in a local
 *   says so, handed a pick handler whose class also handles the sheet's reaction pick (it loads
 *   [SHEET_REACTION_PICKED]).
 * - The rows. First the plugins: render walks a list of plugin ids, asks a static check on its own
 *   class (588 `A0F`, a switch over the ids) whether each one shows for this comment, and builds the
 *   ones that do. A plugin's name comes from another static on the class (588 `A0E`), and the names
 *   keep Facebook's package, so `...commentreply.CommentReplyBottomSheetMenuPlugin` is Reply and
 *   `...reporttoadmin.ReportToAdminPlugin` and `...commentfrx.CommentFRXBottomSheetMenuPlugin` are
 *   the two Reports. Then Facebook's own rows, each through one static row builder (588 `A0B`)
 *   handed the click, the component context, the title, a subtitle and an icon. Its answer goes in
 *   the list only when it isn't null. The icons keep their resource names (`fb_ic_copy_outline_24`
 *   and the rest), which tell the rows apart in every language.
 *
 * So three hooks, each found and left out on its own with a warning:
 * - first thing in the plugin check, the extension is handed the plugin's name and on a yes the
 *   check answers false;
 * - first thing in the row builder, the extension is handed the icon and on a yes the builder
 *   answers null (Save photo, Save GIF and Save video go through it with a photo icon and stay);
 * - right after the reaction row's flag is tested, the extension is asked, and on a yes render goes
 *   where a false flag goes.
 */

internal const val SHEET_PLUGIN_PACKAGE = "com.facebook.feedback.comments.plugins.commentbottomsheet.commentbottomsheetmenu."

/** What the sheet's reaction pick is traced as, which Ask before liking a comment hooks too (#443). */
internal const val SHEET_REACTION_PICKED = SHEET_REACTION_TRACE

internal const val DROPS_SHEET_PLUGIN = "$EXTENSION_PACKAGE/comments/HeldComment;->dropsSheetPlugin(Ljava/lang/String;)Z"
internal const val DROPS_SHEET_ROW = "$EXTENSION_PACKAGE/comments/HeldComment;->dropsSheetRow(I)Z"
internal const val DROPS_SHEET_REACTIONS = "$EXTENSION_PACKAGE/comments/HeldComment;->dropsSheetReactions()Z"

private const val STRING = "Ljava/lang/String;"

private val ENDS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.THROW)

private fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.methodCall() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.isStaticCall() = opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

private fun Method.loadsStringStarting(prefix: String) = code().any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string?.startsWith(prefix) == true
}

/** The static calls in [method] shaped like the sheet's row builder: (..., String, String, int) to an object. */
private fun rowBuilderCalls(method: Method): List<MethodReference> =
    method.code().filter { it.isStaticCall() }.mapNotNull { it.methodCall() }.filter { call ->
        val types = call.parameterTypes.map(CharSequence::toString)
        types.size >= 3 && types.takeLast(3) == listOf(STRING, STRING, "I") && call.returnType.startsWith("L")
    }.distinctBy { it.smali() }

/** The sheet's render among [holders], the classes loading [COMMENT_SHEET_SOCKET]: the one method loading it that builds rows. */
internal fun sheetRender(holders: List<ClassDef>): Method? =
    holders.flatMap { it.methods }.filter { holdsString(it, COMMENT_SHEET_SOCKET) && rowBuilderCalls(it).isNotEmpty() }
        .distinctBy { it.smali() }.singleOrNull()

/** The one row builder [render] calls, or null when there isn't exactly one. */
internal fun sheetRowBuilder(render: Method): MethodReference? = rowBuilderCalls(render).singleOrNull()

/**
 * The static on [owner], render's class, that names a plugin of the sheet from its id: `(I)String`,
 * loading names in [SHEET_PLUGIN_PACKAGE].
 */
internal fun sheetPluginName(owner: ClassDef): Method? =
    owner.methods.filter {
        it.isStatic() && it.returnType == STRING && it.parameterTypes.map(CharSequence::toString) == listOf("I") &&
            it.loadsStringStarting(SHEET_PLUGIN_PACKAGE)
    }.singleOrNull()

/**
 * The static on render's class that [render] asks whether a plugin shows: it answers a boolean, takes
 * the plugin's id last and switches over it.
 */
internal fun sheetPluginCheck(render: Method, owner: ClassDef): Method? {
    val called = render.code().filter { it.isStaticCall() }.mapNotNull { it.methodCall() }
        .filter { it.definingClass == owner.type }.map { it.smali() }.toSet()
    return owner.methods.filter { method ->
        method.isStatic() && method.returnType == "Z" && method.parameterTypes.lastOrNull()?.toString() == "I" &&
            method.smali() in called && method.code().any { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
    }.singleOrNull()
}

/** The index of the instruction [code]'s branch at [at] jumps to. */
private fun targetOf(code: List<Instruction>, at: Int): Int? {
    val branch = code[at] as? OffsetInstruction ?: return null
    val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
    val target = addresses[at] + branch.codeOffset
    return addresses.indexOf(target).takeIf { it in code.indices }
}

/**
 * Where render decides on the reaction row: [test] is the `if-eqz` on its flag, the last branch
 * before the row's pick handler (a class among [pickers]) is newed, and [skip] where it goes on a
 * false flag.
 */
internal class SheetReactions(val test: Int, val skip: Int)

internal fun sheetReactions(render: Method, pickers: Set<String>): SheetReactions? {
    val code = render.code()
    val made = code.indices.filter { at ->
        code[at].opcode == Opcode.NEW_INSTANCE &&
            ((code[at] as ReferenceInstruction).reference as TypeReference).type in pickers
    }
    val found = made.mapNotNull { at ->
        val test = (at - 1 downTo 0).firstOrNull { code[it] is OffsetInstruction || code[it].opcode in ENDS }
            ?: return@mapNotNull null
        if (code[test].opcode != Opcode.IF_EQZ) return@mapNotNull null
        val skip = targetOf(code, test) ?: return@mapNotNull null
        if (skip <= at) return@mapNotNull null
        SheetReactions(test, skip)
    }
    return found.singleOrNull()
}

/**
 * The three hooks of what the older comment sheet offers a held comment, each found and put in on
 * its own. A build where one can't be found keeps the others, with a warning naming the switches
 * left doing nothing there.
 */
internal fun BytecodePatchContext.holdHeldCommentSheet(patch: String) {
    fun skip(what: String, why: String) = patchLog.warning("$patch: $why. $what does nothing on the older comment sheet in this build")
    val all = "Hide reactions, Reply, Copy, Report and Hide comment on a held comment"
    val holders = classDefByStrings(COMMENT_SHEET_SOCKET, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
    val render = sheetRender(holders)
        ?: return skip(all, "expected one comment sheet render loading $COMMENT_SHEET_SOCKET that builds rows, found none or several")
    val owner = holders.first { it.type == render.definingClass }
    val options = "Hide Reply, Copy, Report and Hide comment on a held comment"

    // Plugins: the check answers false for one the switches take away.
    val name = sheetPluginName(owner)
    val check = sheetPluginCheck(render, owner)
    when {
        name == null -> skip(options, "the comment sheet has no one static naming its plugins")
        check == null -> skip(options, "the comment sheet has no one static check on its plugins")
        check.localRegisterCount() < 1 -> skip(options, "${check.definingClass}->${check.name} has no local register to borrow")
        else -> {
            val method = mutableClassDefBy(owner.type).findMutableMethodOf(check)
            val id = check.parameterRegister(check.parameterTypes.size - 1)
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { $id .. $id }, ${name.smali()}
                    move-result-object v0
                    invoke-static/range { v0 .. v0 }, $DROPS_SHEET_PLUGIN
                    move-result v0
                    if-eqz v0, :check
                    const/4 v0, 0x0
                    return v0
                """,
                ExternalLabel("check", method.getInstruction(0)),
            )
        }
    }

    // Facebook's own rows: the builder answers null for one the switches take away.
    val builderCall = sheetRowBuilder(render)
    val builder = builderCall?.let { call ->
        classDefByOrNull(call.definingClass)?.methods?.singleOrNull { it.smali() == call.smali() }
    }
    when {
        builder == null || builder.implementation == null -> skip(options, "the comment sheet has no one row builder with a body")
        !builder.isStatic() -> skip(options, "${builder.definingClass}->${builder.name} isn't static")
        builder.localRegisterCount() < 1 -> skip(options, "${builder.definingClass}->${builder.name} has no local register to borrow")
        else -> {
            val method = mutableClassDefBy(builder.definingClass).findMutableMethodOf(builder)
            val icon = builder.parameterRegister(builder.parameterTypes.size - 1)
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { $icon .. $icon }, $DROPS_SHEET_ROW
                    move-result v0
                    if-eqz v0, :build
                    const/4 v0, 0x0
                    return-object v0
                """,
                ExternalLabel("build", method.getInstruction(0)),
            )
        }
    }

    // The reaction row: on a yes render goes where a false flag sends it.
    val reactions = "Hide reactions on a held comment"
    val pickers = classDefByStrings(SHEET_REACTION_PICKED, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.map { it.type }.toSet()
    val sheetRender = mutableClassDefBy(owner.type).findMutableMethodOf(render)
    val point = sheetReactions(sheetRender, pickers)
        ?: return skip(reactions, "the comment sheet has no one flag deciding its reaction row")
    val after = point.test + 1
    if (after in branchTargets(sheetRender.code())) {
        return skip(reactions, "something jumps into the comment sheet's reaction row past its flag")
    }
    val free = try {
        sheetRender.freeLocalsAt("comment sheet reaction row", after, 1, listOf(point.skip), highest = 255).single()
    } catch (failure: PatchException) {
        return skip(reactions, failure.message ?: "no free register past the reaction row's flag")
    }
    sheetRender.addInstructionsWithLabels(
        after,
        """
            invoke-static { }, $DROPS_SHEET_REACTIONS
            move-result v$free
            if-nez v$free, :skip
        """,
        ExternalLabel("skip", sheetRender.getInstruction(point.skip)),
    )
}
