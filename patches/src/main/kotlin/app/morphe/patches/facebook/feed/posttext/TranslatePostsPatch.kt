/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val TRANSLATE_PATCH = "Translate posts"

internal const val POST_TRANSLATION = "Lapp/morphe/extension/facebook/feed/PostTranslation;"
internal const val TRANSLATION_TEXT = "$POST_TRANSLATION->text($SPANNABLE)$SPANNABLE"
internal const val TRANSLATION_SHOWN = "$POST_TRANSLATION->shown($SPANNABLE)$SPANNABLE"
internal const val TRANSLATION_TOUCH = "$POST_TRANSLATION->touch($MOTION_EVENT)V"

/**
 * Translates posts in the feed through Google Translate (issue #332), with a Translate line under a
 * post, or by themselves with See original under them. Show full post text's method builds a post's
 * text and nothing else, so comments stay Facebook's: the post's text goes through the extension on
 * the way in, and what the post shows goes through it again at every return, which also puts the
 * line under a cut text's See more.
 *
 * A post opened to its whole text skips that method. Facebook builds the whole text and runs it
 * through the static step the method uses before its cut, so each call of that step outside the
 * method takes the text through the extension on the way in and the answer on the way out too.
 *
 * Litho's text takes a press near the line and calls the line when the finger lifts, held or not,
 * so the extension also reads every touch first thing in FbFragmentActivity.dispatchTouchEvent and
 * hands a held press to the post's own long press. It only reads the event, through the range form.
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
        val activity = classDefByOrNull(FRAGMENT_ACTIVITY)
            ?: throw PatchException("$TRANSLATE_PATCH: this build has no $FRAGMENT_ACTIVITY")
        val dispatch = touchDispatches(activity).singleOrNull() ?: throw PatchException(
            "$TRANSLATE_PATCH: expected $FRAGMENT_ACTIVITY to declare one dispatchTouchEvent($MOTION_EVENT)Z",
        )
        mutableClassDefBy(FRAGMENT_ACTIVITY).findMutableMethodOf(dispatch)
            .addInstruction(0, "invoke-static/range { p1 .. p1 }, $TRANSLATION_TOUCH")
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
        val step = wholeTextStep(builder)
        val hooked = if (step == null) 0 else wholeTextCallers(builder, step).sumOf { caller ->
            hookWholeText(mutableClassDefBy(caller.definingClass).findMutableMethodOf(caller), step)
        }
        if (hooked == 0) {
            patchLog.warning(
                "$TRANSLATE_PATCH: found no place a post's whole text is built outside ${builder.definingClass}->" +
                    "${builder.name}. The patch goes on, and a post opened to its whole text stays in its own language.",
            )
        }
        enableStatus("translatePosts")
    }
}

private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

private fun callsStep(instruction: Instruction, step: MethodReference) =
    instruction.opcode in STATIC_CALLS && (instruction as ReferenceInstruction).reference.toString() == step.toString()

/**
 * The static step the builder runs a post's text through before its cut (585 `LX/03Bn;->A00(Spannable,
 * FbUserSession, 02YE, 03Bn)Spannable`, which adds what a shared link's title brings): the text first,
 * the builder's own class last, a Spannable back.
 */
internal fun wholeTextStep(builder: Method): MethodReference? = builder.implementation!!.instructions
    .filter { it.opcode in STATIC_CALLS }
    .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
    .filter { step ->
        step.definingClass == builder.definingClass && step.returnType == SPANNABLE && step.parameterTypes.size >= 2 &&
            step.parameterTypes.first().toString() == SPANNABLE && step.parameterTypes.last().toString() == builder.definingClass
    }
    .distinctBy { it.toString() }
    .singleOrNull()

/**
 * Every method besides [builder] that calls [step]: where a post shows its whole text without the
 * builder. A tap that opens a cut post takes 585's `LX/03Bf;->A00` past the builder, and the feed's
 * `LX/0cQv;->render` builds the whole text through `LX/03Bn;->A07` next to the cut one (#332).
 */
internal fun BytecodePatchContext.wholeTextCallers(builder: Method, step: MethodReference): List<Method> {
    val found = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(step.definingClass)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(found) { method ->
            // Signatures, not the parameter lists: a mutable class's parameters aren't Strings, so `==` misses.
            val isBuilder = method.definingClass == builder.definingClass && MethodUtil.methodSignaturesMatch(method, builder)
            !isBuilder && method.implementation?.instructions?.any { callsStep(it, step) } == true
        }
    }
    return found
}

/**
 * Runs the text [step] is handed through the extension first, and what it answers through it after,
 * as the builder's own text and returns are. Answers how many calls it hooked.
 */
private fun hookWholeText(method: MutableMethod, step: MethodReference): Int {
    val code = method.implementation!!.instructions.toList()
    var hooked = 0
    // From the last one up, so each index still holds when its turn comes.
    for ((index, call) in code.withIndex().filter { callsStep(it.value, step) }.reversed()) {
        val answer = code.getOrNull(index + 1)
        if (answer?.opcode != Opcode.MOVE_RESULT_OBJECT) continue
        val result = (answer as OneRegisterInstruction).registerA
        val text = when (call) {
            is RegisterRangeInstruction -> call.startRegister
            is FiveRegisterInstruction -> call.registerC
            else -> continue
        }
        method.addInstructions(
            index + 2,
            """
                invoke-static/range { v$result .. v$result }, $TRANSLATION_SHOWN
                move-result-object v$result
            """,
        )
        method.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$text .. v$text }, $TRANSLATION_TEXT
                move-result-object v$text
            """,
        )
        hooked++
    }
    return hooked
}
