/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.reels.CAN_VIEWER_COMMENT
import app.morphe.util.addInstructionsAtControlFlowLabel
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
 * - That check is only asked when a post comes without its own button list. The one static method
 *   calling it (LX/039B;->A05, returning a List) first takes the list the server sent with the post,
 *   if any, and builds the footer from that alone, asking only whether each item exists in this
 *   build. A post you can't comment on, like the reels on the profile in #299, comes with a list
 *   that has no Comment. The items are constants of a GraphQL enum whose toString is the server's
 *   name (COMMENT, REACT for the Like button, SHARE, ...), in the order Like, Comment, Share when the
 *   client picks them. So the builder asks the can-comment check first thing, the way the footer
 *   check does (the post field the check loads before calling, on the builder's parameter of that
 *   field's class), and hands the answer to the extension; every list it returns goes to the
 *   extension too, and Comment goes back after Like. The answer has to be read at the start: the
 *   builder lines its arguments up for a range call on the way and copies the next one over the
 *   post's register, which made the first try at reading it by the return crash on a null.
 */

/** The footer's Comment item. Its class name is kept, since Facebook's plugin registry names it by its Java path. */
internal const val COMMENT_BUTTON_PLUGIN =
    "Lcom/facebook/feedback/feedufi/plugins/footeritem/commentbutton/CommentButtonFeedUFIFooterItemPlugin;"

internal const val SHOW_POST_COMMENT_BUTTON = "$POST_PROMPTS->showsCommentButton(Z)Z"
internal const val FOOTER_POST = "$POST_PROMPTS->footerPost(Z)V"
internal const val FOOTER_WITH_COMMENT = "$POST_PROMPTS->withCommentButton(Ljava/util/List;)Ljava/util/List;"

private const val CACHED_BOOLEAN = "Lcom/facebook/graphql/modelutil/BaseModelWithTree;->getCachedBoolean(I)Z"
private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"
private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

/** [method] as a reference string, `Lowner;->name(params)return`. */
internal fun referenceOf(method: Method): String =
    "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

/** The field [method] reads the post from before its call at [call]: the nearest Object-typed iget-object before it. */
internal fun storyField(method: Method, call: Int): FieldReference? =
    method.implementation?.instructions?.take(call)?.asReversed()?.firstNotNullOfOrNull { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? FieldReference)
            ?.takeIf { instruction.opcode == Opcode.IGET_OBJECT && it.type == OBJECT }
    }

/** Whether [method] builds the footer's item list: static, answering a List, and calling the method [check] names. */
internal fun buildsFooterList(method: Method, check: String): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == LIST && method.implementation?.instructions?.any {
        it.opcode in STATIC_CALLS && ((it as ReferenceInstruction).reference as? MethodReference)?.toString() == check
    } == true

private fun width(type: String) = if (type == "J" || type == "D") 2 else 1

/** The N of the pN register holding [method]'s one parameter of [type], or null unless exactly one has it. */
internal fun parameterNumber(method: Method, type: String): Int? {
    var next = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    val found = mutableListOf<Int>()
    for (parameter in method.parameterTypes.map(CharSequence::toString)) {
        if (parameter == type) found += next
        next += width(parameter)
    }
    return found.singleOrNull()
}

/**
 * What goes first in the builder: the post read off parameter p[story]'s [field], asked
 * [canComment], and the answer handed to the extension, a yes without a post. The builder moves
 * its parameters around before it returns (588 copies the next one over the post's), so this is the
 * one place the post can be read. Uses v0, which holds nothing at the start.
 */
internal fun footerPostHook(story: Int, field: FieldReference, canComment: MethodReference): String =
    """
        move-object/from16 v0, p$story
        if-eqz v0, :no_post
        iget-object v0, v0, $field
        if-eqz v0, :no_post
        check-cast v0, ${canComment.parameterTypes.single()}
        invoke-static { v0 }, $canComment
        move-result v0
        goto :asked
        :no_post
        const/4 v0, 0x1
        :asked
        invoke-static { v0 }, $FOOTER_POST
    """

/** What goes before the builder's return of the list in v[list]: the list handed to the extension, whose list is returned instead. */
internal fun footerListHook(list: Int): String =
    """
        invoke-static/range { v$list .. v$list }, $FOOTER_WITH_COMMENT
        move-result-object v$list
    """

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
 * yes while the switch is on, and every item list the one builder calling that check returns, which
 * gets Comment back while the switch is on. Refuses unless one static (…) -> Z method loads the
 * plugin's field and calls a can-comment check, calls it once, reads the post off an Object field
 * before it, and one static List builder calls the check and takes one parameter of that field's
 * class, with a register below its parameters.
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
    val canComment = (method.implementation!!.instructions.elementAt(call) as ReferenceInstruction).reference as MethodReference
    val field = storyField(method, call)
        ?: throw PatchException("$PATCH: ${check.definingClass}->${check.name} reads no post off an Object field before asking whether you can comment")
    val result = (method.implementation!!.instructions.elementAt(call + 1) as OneRegisterInstruction).registerA
    method.addInstructions(
        call + 2,
        """
            invoke-static/range { v$result .. v$result }, $SHOW_POST_COMMENT_BUTTON
            move-result v$result
        """,
    )

    val checkName = referenceOf(check)
    val builders = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(check.definingClass)) { classDef ->
        classDef.methods.filterTo(builders) { buildsFooterList(it, checkName) }
    }
    val builder = builders.singleOrNull()
        ?: throw PatchException("$PATCH: expected one footer list builder calling $checkName, found ${builders.size}")
    val story = parameterNumber(builder, field.definingClass)
        ?: throw PatchException("$PATCH: ${builder.definingClass}->${builder.name} takes no one parameter of ${field.definingClass}")
    val list = mutableClassDefBy(builder.definingClass).methods.single { MethodUtil.methodSignaturesMatch(it, builder) }
    val implementation = list.implementation!!
    val parameters = builder.parameterTypes.sumOf { width(it.toString()) }
    if (implementation.registerCount - parameters < 1) {
        throw PatchException("$PATCH: ${builder.definingClass}->${builder.name} has no register below its parameters")
    }
    val returns = implementation.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) throw PatchException("$PATCH: ${builder.definingClass}->${builder.name} has no return-object")
    returns.sortedByDescending { it.index }.forEach { (at, instruction) ->
        list.addInstructionsAtControlFlowLabel(at, footerListHook((instruction as OneRegisterInstruction).registerA))
    }
    list.addInstructionsWithLabels(0, footerPostHook(story, field, canComment))
}
