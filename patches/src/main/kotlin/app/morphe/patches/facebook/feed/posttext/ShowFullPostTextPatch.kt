/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PATCH = "Show full post text"

/** The key Facebook reads a post's line limit under (message_truncation_line_limit, 584). */
internal const val LINE_LIMIT_KEY = 0x32d58eaeL

internal const val CHARACTER_STYLE = "Landroid/text/style/CharacterStyle;"
internal const val SPANNABLE = "Landroid/text/Spannable;"
internal const val SPANNABLE_STRING = "Landroid/text/SpannableString;"

internal const val FULL_POST_TEXT = "Lapp/morphe/extension/facebook/feed/FullPostText;"
internal const val CUT = "$FULL_POST_TEXT->cut($SPANNABLE_STRING)$SPANNABLE_STRING"

/**
 * A post's text shows whole in the feed (issue #268). Facebook cuts a long post's text to the line
 * limit it reads under [LINE_LIMIT_KEY] and adds See more after it. 584's `LX/31a;->A05` takes the
 * post's text (a Spannable) and the See more style (a CharacterStyle), asks a helper for the cut
 * text, and gets null back when the text already fits, in which case the whole text goes out as it
 * is. Right after that answer, the extension swaps the cut text for null while the switch is on, so
 * every post takes the path for text that fits.
 */
@Suppress("unused")
val showFullPostTextPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Show full post text",
    description = "Shows all of a post's text in the feed, so you don't have to tap See more. Starts off. " +
        "Turn it on in Hushfacebook settings > News feed.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val builder = findPostTextBuilder(PATCH)
        val method = mutableClassDefBy(builder.definingClass).findMutableMethodOf(builder)
        // Translate posts adds to this method too, and may have run first, so the place is read from it as it is now.
        val cut = textCut(method)
        method.addInstructions(
            cut.hookIndex,
            """
                invoke-static/range { v${cut.register} .. v${cut.register} }, $CUT
                move-result-object v${cut.register}
            """,
        )
        enableStatus("fullPostText")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The feed's text cut: its method, where the hook goes (right after the cut's answer lands), and the answer's register. */
internal class TextCut(val method: Method, val hookIndex: Int, val register: Int)

private fun cutsText(reference: MethodReference) =
    reference.returnType == SPANNABLE_STRING && reference.parameterTypes.firstOrNull()?.toString() == CHARACTER_STYLE

/** A method shaped like the cut's caller: Spannable back, the text and the See more style in. */
internal fun cutShaped(method: Method) =
    method.returnType == SPANNABLE && method.parameterTypes.size >= 2 &&
        method.parameterTypes[0].toString() == SPANNABLE && method.parameterTypes[1].toString() == CHARACTER_STYLE &&
        method.implementation != null && method.containsLiteralInstruction(LINE_LIMIT_KEY)

/**
 * Reads the cut out of [method]. Refuses unless it calls the helper once (a static call answering a
 * SpannableString from a CharacterStyle first), the answer lands right after it, and the very next
 * instruction branches on that answer being null: the split between text that fits and cut text.
 */
internal fun textCut(method: Method): TextCut {
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    val calls = code.indices.filter { index ->
        val instruction = code[index]
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            ((instruction as ReferenceInstruction).reference as? MethodReference)?.let(::cutsText) == true
    }
    val call = calls.singleOrNull() ?: refuse("expected one call cutting the text in $where, found ${calls.size}")
    val answer = code.getOrNull(call + 1)
    if (answer?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("the cut text in $where isn't kept right after its call")
    val register = (answer as OneRegisterInstruction).registerA
    val branch = code.getOrNull(call + 2)
    if (branch?.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != register) {
        refuse("$where doesn't branch on the cut text in v$register right after it")
    }
    return TextCut(method, call + 2, register)
}

/** The one method shaped like the cut's caller that reads the line limit: Facebook's builder of a post's text. */
internal fun BytecodePatchContext.findPostTextBuilder(patch: String): Method {
    val methods = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(CHARACTER_STYLE)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(methods, ::cutShaped)
    }
    return methods.singleOrNull()
        ?: throw PatchException("$patch: expected one method building a post's text to its line limit, found ${methods.size}")
}
