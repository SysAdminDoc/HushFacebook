/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val TRANSLATE_PATCH = "Translate posts"

internal const val POST_TRANSLATION = "Lapp/morphe/extension/facebook/feed/PostTranslation;"
internal const val TRANSLATION_TEXT = "$POST_TRANSLATION->text($SPANNABLE)$SPANNABLE"
internal const val TRANSLATION_SHOWN = "$POST_TRANSLATION->shown($SPANNABLE)$SPANNABLE"

/**
 * Translates posts in the feed through Google Translate (issue #332), with a Translate line under a
 * post, or by themselves with See original under them. Show full post text's method builds a post's
 * text and nothing else, so comments stay Facebook's: the post's text goes through the extension on
 * the way in, and what the post shows goes through it again at every return, which also puts the
 * line under a cut text's See more.
 */
@Suppress("unused")
val translatePostsPatch = bytecodePatch(
    // The README table check reads this literal.
    name = "Translate posts",
    description = "Translates posts in another language through Google Translate, from a Translate line under " +
        "the post or by themselves. Starts off. Turn it on in Hushfacebook settings > News feed.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val builder = findPostTextBuilder(TRANSLATE_PATCH)
        val method = mutableClassDefBy(builder.definingClass).findMutableMethodOf(builder)
        val returns = method.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        if (returns.isEmpty()) throw PatchException("$TRANSLATE_PATCH: ${builder.definingClass}->${builder.name} has no return")
        // From the last one up, so each index still holds when its turn comes.
        for ((index, instruction) in returns.reversed()) {
            val register = (instruction as OneRegisterInstruction).registerA
            method.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static/range { v$register .. v$register }, $TRANSLATION_SHOWN
                    move-result-object v$register
                """,
            )
        }
        method.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $TRANSLATION_TEXT
                move-result-object p1
            """,
        )
        enableStatus("translatePosts")
    }
}
