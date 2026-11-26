/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * What a held comment offers (#607). Read from 588, 2026-11-26, arm64; the obfuscated names here
 * are for reviewers, the code never writes one down.
 *
 * Holding a comment runs one handler (588 `LX/0DoQ;->A1F`, the method loading [GLIMPSE_BUBBLE])
 * that builds Facebook's glimpse: the comment lifted over the screen, a row of reactions above it
 * and a list of options under it. Each option goes in through one static helper (588
 * `LX/0yPQ;->A01`), handed the option's kind, an enum whose constants keep their names
 * (`REPLY`, `COPY`, `FIND_SUPPORT` for Report comment, `REPORT_TO_ADMIN`, `HIDE` and the rest),
 * and the LinkedHashMap it puts the option in. The options are read back out of that map by kind
 * and nulls dropped, so a kind the helper never puts in is simply missing from the list.
 *
 * The reactions are the comment's reaction list wrapped with what a pick does (588 `LX/0Msl;`,
 * newed with a List and a Function1), and the glimpse's builder takes a null wrapper as no row of
 * reactions. Before it's built, the handler checks what's left: no options and no wrapper opens
 * Facebook's older comment sheet instead (588 `LX/0gq1;->A00`, handed the GraphQLFeedback), then
 * returns true like the glimpse does.
 *
 * So three hooks, each found and left out on its own with a warning:
 * - first thing in the helper, the extension is handed the kind and on a yes the helper returns;
 * - right after the wrapper is built, the extension hands back the wrapper or null;
 * - where the older sheet would open for a comment left with nothing, the extension says whether
 *   the switches emptied it, and on a yes the handler skips the sheet and returns true.
 *
 * The flag-off paths that open the older sheet without building a glimpse jump to the sheet's first
 * instruction, and code put in front of a jump target stays behind those jumps, so only the
 * glimpse's own "nothing left" check reaches the third hook.
 */

internal const val GLIMPSE_BUBBLE = "comment_glimpse_bubble"

/** Two of the option kinds' kept names, which together pick their enum out. */
internal const val OPTION_REPORT_TO_ADMIN = "REPORT_TO_ADMIN"
internal const val OPTION_FIND_SUPPORT = "FIND_SUPPORT"

internal const val DROPS_HELD_OPTION = "$EXTENSION_PACKAGE/comments/HeldComment;->dropsOption(Ljava/lang/Object;)Z"
internal const val HELD_REACTIONS = "$EXTENSION_PACKAGE/comments/HeldComment;->reactions(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val HELD_LEFT_EMPTY = "$EXTENSION_PACKAGE/comments/HeldComment;->leftEmpty()Z"

private const val LINKED_HASH_MAP = "Ljava/util/LinkedHashMap;"
private const val GRAPHQL_FEEDBACK = "Lcom/facebook/graphql/model/GraphQLFeedback;"
private const val WRAPPER_INIT = "(Ljava/util/List;Lkotlin/jvm/functions/Function1;)V"

/** The option kinds' enum: an enum among [holders] that also loads [OPTION_FIND_SUPPORT]. */
internal fun optionKinds(holders: List<ClassDef>): Set<String> =
    holders.filter { kind ->
        kind.superclass == "Ljava/lang/Enum;" && kind.methods.any { holdsString(it, OPTION_FIND_SUPPORT) }
    }.map { it.type }.toSet()

private fun Instruction.methodCall() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.isStaticCall() = opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE

private fun Instruction.isBranch() = when (opcode) {
    Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32, Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH,
    Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.THROW,
    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
    -> true
    else -> false
}

private fun MethodReference.takes(type: String) = parameterTypes.any { it.toString() == type }

/** The static calls in [method] adding an option: void, handed one of [kinds] and a LinkedHashMap. */
private fun optionCalls(method: Method, kinds: Set<String>): List<MethodReference> =
    method.implementation?.instructions?.toList().orEmpty().filter { it.isStaticCall() }.mapNotNull { it.methodCall() }.filter { call ->
        call.returnType == "V" && call.takes(LINKED_HASH_MAP) && call.parameterTypes.any { it.toString() in kinds }
    }

/**
 * The handler of a held comment, from [holders], the classes loading [GLIMPSE_BUBBLE]: the one
 * method loading it that adds options through a static helper handed one of [kinds]. Null when
 * there isn't exactly one.
 */
internal fun glimpseHandler(holders: List<ClassDef>, kinds: Set<String>): Method? =
    holders.flatMap { it.methods }.filter { holdsString(it, GLIMPSE_BUBBLE) && optionCalls(it, kinds).isNotEmpty() }
        .distinctBy { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        .singleOrNull()

/** The helper [handler] adds each option through, or null when it calls more than one. */
internal fun optionHelper(handler: Method, kinds: Set<String>): MethodReference? =
    optionCalls(handler, kinds).distinctBy { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        .singleOrNull()

/**
 * The glimpse's "nothing left" check in [handler]: `isEmpty()` on the options, the `if-eqz` on its
 * answer, then an `if-nez` on the reactions' register, falling through to the older sheet, which
 * is one call handed a GraphQLFeedback with no branch on the way, followed by `const/4 vX, 1` and
 * `Boolean.valueOf`. [first] is the sheet's first instruction, where the hook goes in front, and
 * [done] the `const/4`, where the hook jumps on a yes.
 */
internal class EmptyGlimpse(val first: Int, val done: Int, val reactions: Int)

internal fun emptyGlimpse(handler: Method): EmptyGlimpse? {
    val code = handler.implementation?.instructions?.toList() ?: return null
    val found = code.indices.filter { at ->
        at + 3 < code.size &&
            code[at].methodCall()?.let { it.name == "isEmpty" && it.returnType == "Z" && it.parameterTypes.isEmpty() } == true &&
            code[at + 1].opcode == Opcode.MOVE_RESULT && code[at + 2].opcode == Opcode.IF_EQZ &&
            code[at + 3].opcode == Opcode.IF_NEZ
    }.mapNotNull { at ->
        val first = at + 4
        val sheet = (first until code.size).firstOrNull { code[it].isBranch() || code[it].methodCall()?.takes(GRAPHQL_FEEDBACK) == true }
            ?: return@mapNotNull null
        if (code[sheet].isBranch() || code[sheet].methodCall()?.returnType != "V") return@mapNotNull null
        val done = sheet + 1
        val one = code.getOrNull(done) ?: return@mapNotNull null
        if (one.opcode != Opcode.CONST_4 || (one as NarrowLiteralInstruction).narrowLiteral != 1) return@mapNotNull null
        val boxed = code.getOrNull(done + 1)?.methodCall()
        if (boxed?.definingClass != "Ljava/lang/Boolean;" || boxed.name != "valueOf") return@mapNotNull null
        EmptyGlimpse(first, done, (code[at + 3] as OneRegisterInstruction).registerA)
    }
    return found.singleOrNull()
}

/**
 * The reactions' wrapper in [handler]: the one type newed there with a `(List, Function1)` init
 * that a static call in the handler also takes. [init] is the index of its init, and [register]
 * where the wrapper sits.
 */
internal class ReactionWrapper(val type: String, val init: Int, val register: Int)

internal fun reactionWrapper(handler: Method): ReactionWrapper? {
    val code = handler.implementation?.instructions?.toList() ?: return null
    val taken = code.filter { it.isStaticCall() }.mapNotNull { it.methodCall() }.flatMap { it.parameterTypes.map(CharSequence::toString) }.toSet()
    val found = code.indices.mapNotNull { at ->
        val init = code[at].methodCall() ?: return@mapNotNull null
        if (code[at].opcode != Opcode.INVOKE_DIRECT || init.name != "<init>" || init.definingClass !in taken) return@mapNotNull null
        if ("(${init.parameterTypes.joinToString("")})${init.returnType}" != WRAPPER_INIT) return@mapNotNull null
        val register = (code[at] as FiveRegisterInstruction).registerC
        val made = code.getOrNull(at - 1)
        if (made?.opcode != Opcode.NEW_INSTANCE || (made as OneRegisterInstruction).registerA != register ||
            ((made as ReferenceInstruction).reference as TypeReference).type != init.definingClass
        ) return@mapNotNull null
        ReactionWrapper(init.definingClass, at, register)
    }
    return found.singleOrNull()
}

/**
 * The three hooks of what a held comment offers, each found and put in on its own. A build where
 * one can't be found keeps the others and the rest of [patch], with a warning naming the switches
 * left doing nothing.
 */
internal fun BytecodePatchContext.holdHeldCommentParts(patch: String) {
    fun skip(what: String, why: String) = patchLog.warning("$patch: $why. $what does nothing in this build")
    val all = "Hide reactions, Reply, Copy, Report and Hide comment on a held comment"
    val kinds = optionKinds(
        classDefByStrings(OPTION_REPORT_TO_ADMIN, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
    )
    if (kinds.isEmpty()) return skip(all, "this build has no enum naming both $OPTION_REPORT_TO_ADMIN and $OPTION_FIND_SUPPORT")
    val holders = classDefByStrings(GLIMPSE_BUBBLE, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
    val found = glimpseHandler(holders, kinds)
        ?: return skip(all, "expected one method loading $GLIMPSE_BUBBLE that adds comment options, found none or several")
    val handler = mutableClassDefBy(found.definingClass).findMutableMethodOf(found)

    val options = "Hide Reply, Copy, Report and Hide comment on a held comment"
    optionHelper(handler, kinds)?.let { call ->
        val helper = classDefByOrNull(call.definingClass)?.methods?.singleOrNull {
            it.name == call.name && it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
                it.returnType == call.returnType
        }
        val kind = call.parameterTypes.indexOfFirst { it.toString() in kinds }
        when {
            helper == null || helper.implementation == null -> skip(options, "${call.definingClass}->${call.name} has no body here")
            !AccessFlags.STATIC.isSet(helper.accessFlags) -> skip(options, "${call.definingClass}->${call.name} isn't static")
            helper.localRegisterCount() < 1 -> skip(options, "${call.definingClass}->${call.name} has no local register to borrow")
            else -> {
                val method = mutableClassDefBy(helper.definingClass).findMutableMethodOf(helper)
                val register = helper.parameterRegister(kind)
                method.addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range { $register .. $register }, $DROPS_HELD_OPTION
                        move-result v0
                        if-eqz v0, :add
                        return-void
                    """,
                    ExternalLabel("add", method.getInstruction(0)),
                )
            }
        }
    } ?: skip(options, "the held comment's handler adds its options through more than one helper")

    // Both of the handler's hooks are read from it untouched, then go in from the bottom up.
    val empty = emptyGlimpse(handler)
    val wrapper = reactionWrapper(handler)
    val hooks = mutableListOf<Pair<Int, () -> Unit>>()
    val reactions = "Hide reactions on a held comment"
    when {
        wrapper == null -> skip(reactions, "expected one reaction list wrapped with what a pick does in the held comment's handler")
        empty != null && empty.reactions != wrapper.register ->
            skip(reactions, "the held comment's handler checks v${empty.reactions} for reactions, not v${wrapper.register}")
        else -> hooks += wrapper.init + 1 to {
            handler.addInstructions(
                wrapper.init + 1,
                """
                    invoke-static/range { v${wrapper.register} .. v${wrapper.register} }, $HELD_REACTIONS
                    move-result-object v${wrapper.register}
                    check-cast v${wrapper.register}, ${wrapper.type}
                """,
            )
        }
    }
    val sheet = "the held comment's handler has no one check opening the older comment sheet for a comment left with nothing"
    val free = empty?.let {
        try {
            handler.freeLocalsAt("held comment left empty", it.first, 1, listOf(it.done), highest = 255).single()
        } catch (failure: PatchException) {
            null.also { patchLog.warning("$patch: ${failure.message}") }
        }
    }
    if (empty == null || free == null) {
        patchLog.warning("$patch: $sheet. A held comment with everything hidden opens Facebook's comment sheet in this build")
    } else {
        hooks += empty.first to {
            handler.addInstructionsWithLabels(
                empty.first,
                """
                    invoke-static { }, $HELD_LEFT_EMPTY
                    move-result v$free
                    if-nez v$free, :done
                """,
                ExternalLabel("done", handler.getInstruction(empty.done)),
            )
        }
    }
    hooks.sortedByDescending { it.first }.forEach { it.second() }
}
