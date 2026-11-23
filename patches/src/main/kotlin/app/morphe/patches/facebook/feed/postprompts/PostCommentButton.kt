/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.reels.CAN_VIEWER_COMMENT
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/*
 * The Comment button under a post you can't comment on (#299). Read from 588 arm64, 2026-11-23;
 * the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - One static check (LX/039B;->A07, (props, int) -> Z) answers, per footer item, whether a post
 *   gets it, through a packed-switch on the item. The Comment case opens by loading a static field
 *   of CommentButtonFeedUFIFooterItemPlugin, a kept class name, and says yes when the post's
 *   feedback lets you comment (LX/02mO;->A02, a static (story) -> Z that reads can_viewer_comment
 *   from GraphQLFeedback's cached booleans) or for one other kind of post. A post whose author
 *   turned comments off or limited who can comment answers no, so its footer has Like and Share
 *   only, comments or not. The profile, the feed and a post's own page all build their footer here.
 * - The patch finds the check by that field load and the one call in it to such a can-comment
 *   check, and hands the call's answer to the extension right after its move-result. The other
 *   footer items, and the feedback check's other callers (the Like button, the comment composer),
 *   keep Facebook's answer.
 */

/** The footer's Comment item. Its class name is kept, since Facebook's plugin registry names it by its Java path. */
internal const val COMMENT_BUTTON_PLUGIN =
    "Lcom/facebook/feedback/feedufi/plugins/footeritem/commentbutton/CommentButtonFeedUFIFooterItemPlugin;"

internal const val SHOW_POST_COMMENT_BUTTON = "$POST_PROMPTS->showsCommentButton(Z)Z"

private const val CACHED_BOOLEAN = "Lcom/facebook/graphql/modelutil/BaseModelWithTree;->getCachedBoolean(I)Z"

/** Whether [method] is a can-comment check: static, one argument, answering Z, reading can_viewer_comment from a model's cached booleans. */
internal fun isCanCommentCheck(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z" || method.parameterTypes.size != 1) {
        return false
    }
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.any { it is NarrowLiteralInstruction && it.narrowLiteral == CAN_VIEWER_COMMENT } && code.any {
        it.opcode == Opcode.INVOKE_VIRTUAL && ((it as ReferenceInstruction).reference as? MethodReference)?.toString() == CACHED_BOOLEAN
    }
}

/** Whether [method] loads a static field of the Comment item's plugin class. */
internal fun loadsCommentPlugin(method: Method): Boolean = method.implementation?.instructions?.any {
    it.opcode == Opcode.SGET_OBJECT && ((it as ReferenceInstruction).reference as? FieldReference)?.definingClass == COMMENT_BUTTON_PLUGIN
} == true

/** The indexes in [method] of static calls that [classOf] resolves to a can-comment check and whose answer is moved into a register. */
internal fun canCommentCalls(method: Method, classOf: (String) -> ClassDef?): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        code[at].opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) && call.returnType == "Z" &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT &&
            classOf(call.definingClass)?.methods?.firstOrNull { MethodUtil.methodSignaturesMatch(it, call) }
                ?.let(::isCanCommentCheck) == true
    }
}

/**
 * Hands the footer check's can-comment answer for the Comment item to the extension, which answers
 * yes while the switch is on. Refuses unless one static (…) -> Z method loads the plugin's field and
 * calls a can-comment check, and calls it once.
 */
internal fun BytecodePatchContext.showPostCommentButton() {
    val checks = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(COMMENT_BUTTON_PLUGIN)) { classDef ->
        classDef.methods.filterTo(checks) { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && loadsCommentPlugin(method) &&
                canCommentCalls(method) { classDefByOrNull(it) }.isNotEmpty()
        }
    }
    val check = checks.singleOrNull()
        ?: throw PatchException("$PATCH: expected one footer check loading $COMMENT_BUTTON_PLUGIN and asking whether you can comment, found ${checks.size}")
    val method = mutableClassDefBy(check.definingClass).methods.single { MethodUtil.methodSignaturesMatch(it, check) }
    val call = canCommentCalls(method) { classDefByOrNull(it) }.singleOrNull()
        ?: throw PatchException("$PATCH: ${check.definingClass}->${check.name} asks whether you can comment more than once")
    val result = (method.implementation!!.instructions.elementAt(call + 1) as OneRegisterInstruction).registerA
    method.addInstructions(
        call + 2,
        """
            invoke-static/range { v$result .. v$result }, $SHOW_POST_COMMENT_BUTTON
            move-result v$result
        """,
    )
}
