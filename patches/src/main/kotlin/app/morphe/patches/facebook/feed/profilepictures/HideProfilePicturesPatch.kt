/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.profilepictures

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Profile pictures at the top of posts and beside comments (#400). Read from 585, 2026-11-06,
 * arm64; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - A post header is built by the one static holding the FeedStoryBaseHeader.shell.fds trace name
 *   (LX/02mf;->A0F). It first switches on the header's actor type (ACTOR, DUAL_ACTOR, ACTOR_CHAT and
 *   the rest) to build the model the picture is drawn from (LX/0ctA;), and every case jumps to one
 *   merge with the model in one register. The switch's default sets that register to null and falls
 *   into the same merge, Facebook's own header with no picture. From there the model goes into each
 *   header shell's constructor (ContextChain first, the model second). Right at the merge the model
 *   goes through the extension, which answers null with the switch on.
 * - A comment row is built by the one render holding the comment_profile_picture test tag
 *   (LX/0A8d;->A17). The tag names the builder of the picture's wrapper, whose one build call (an
 *   instance call with no arguments answering the render's own component type) is added to the row
 *   before the bubble. The add skips null, and the row already hands it null on a path of its own.
 *   Right after the build the built picture goes through the extension, which answers null with the
 *   switch on, so the margin the wrapper carries goes with it.
 */

private const val PROFILE_PICTURES_PATCH = "Hide profile pictures"

internal const val PROFILE_PICTURES = "$EXTENSION_PACKAGE/feed/ProfilePictures;"
internal const val FEED_PICTURE = "$PROFILE_PICTURES->feedPicture(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val COMMENT_PICTURE = "$PROFILE_PICTURES->commentPicture(Ljava/lang/Object;)Ljava/lang/Object;"

/** The trace name the post header builder opens its design system shell with. */
internal const val HEADER_TRACE = "FeedStoryBaseHeader.shell.fds"

/** The test tag the comment render gives its picture. */
internal const val COMMENT_PICTURE_TAG = "comment_profile_picture"

/** The first argument of each header shell's constructor. */
internal const val CONTEXT_CHAIN = "Lcom/facebook/common/callercontext/ContextChain;"

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
private val CONST_STRINGS = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)

/**
 * Takes the profile picture off the top of posts and off comments, with a switch for each (#400).
 *
 * In the default selection with both switches off, so nothing changes until one is turned on.
 */
@Suppress("unused")
val hideProfilePicturesPatch = bytecodePatch(
    name = "Hide profile pictures",
    description = "Switches that take the profile picture off the top of posts and off comments, so the name " +
        "starts where the picture was. Both start off. Turn them on in Hushfacebook settings > News feed and " +
        "Comments and writing.",
) {
    category("Feed")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("profilePictures")
        hideProfilePictures()
        enableStatus("profilePictures")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PROFILE_PICTURES_PATCH: $detail")

private fun Method.code() = implementation?.instructions?.toList() ?: emptyList()

/** Each goto's index in [code] with the index it jumps to. */
internal fun gotoTargets(code: List<Instruction>): Map<Int, Int> {
    val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
    val indexOfAddress = addresses.withIndex().associate { (index, address) -> address to index }
    return code.withIndex().filter { it.value.opcode in GOTOS }.associate { (index, goto) ->
        index to (indexOfAddress[addresses[index] + (goto as OffsetInstruction).codeOffset]
            ?: refuse("a goto lands inside an instruction"))
    }
}

/** Where the post header's picture model is ready: the merge index, its register and its type. */
internal data class HeaderSite(val merge: Int, val register: Int, val type: String)

/** Where the comment's picture is built: the index right after the build, its register and its type. */
internal data class CommentSite(val at: Int, val register: Int, val type: String)

/** Finds the merge after the header builder's actor switch, from Facebook's own null default. */
internal fun headerSite(method: Method): HeaderSite {
    val code = method.code()
    val switchAt = code.indexOfFirst { it.opcode == Opcode.SPARSE_SWITCH }
    if (switchAt < 0) refuse("the post header builder has no actor switch")
    val gotos = gotoTargets(code)
    val fallback = gotos[switchAt + 1] ?: refuse("the actor switch has no default jump")
    val none = code[fallback]
    if (none.opcode != Opcode.CONST_4 || (none as NarrowLiteralInstruction).narrowLiteral != 0) {
        refuse("the actor switch's default doesn't set null")
    }
    val register = (none as OneRegisterInstruction).registerA
    if (register > 255) refuse("the picture model is in v$register, out of reach of a cast")
    val merge = fallback + 1
    val arrivals = gotos.filter { (from, to) ->
        to == merge && from > 0 && code[from - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (code[from - 1] as OneRegisterInstruction).registerA == register
    }
    if (arrivals.size < 5) refuse("only ${arrivals.size} actor types bring a picture model to the merge")
    val types = code.filter { it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE }
        .map { (it as ReferenceInstruction).reference as MethodReference }
        .filter { it.name == "<init>" && it.definingClass != CONTEXT_CHAIN && it.parameterTypes.size >= 2 && it.parameterTypes[0].toString() == CONTEXT_CHAIN }
        .map { it.parameterTypes[1].toString() }
        .toSet()
    val type = types.singleOrNull() ?: refuse("the header shells take ${types.size} kinds of picture model, not one")
    return HeaderSite(merge, register, type)
}

/** Whether a path of the row's own sets [register] to null and jumps to [add]. */
private fun rowsOwnNull(code: List<Instruction>, gotos: Map<Int, Int>, add: Int, register: Int) =
    gotos.any { (from, to) ->
        to == add && from > 0 && code[from - 1].opcode == Opcode.CONST_4 &&
            (code[from - 1] as NarrowLiteralInstruction).narrowLiteral == 0 &&
            (code[from - 1] as OneRegisterInstruction).registerA == register
    }

/**
 * Finds the one build of the comment picture's wrapper, from the builder the test tag names. The
 * render reuses that register for other wrappers further on (585 armeabi-v7a), so the build is the
 * one whose result the row adds, where the row's own null picture also lands.
 */
internal fun commentSite(method: Method): CommentSite {
    val code = method.code()
    val tags = code.indices.filter { index ->
        code[index].opcode in CONST_STRINGS &&
            ((code[index] as ReferenceInstruction).reference as StringReference).string == COMMENT_PICTURE_TAG
    }
    val tagAt = tags.singleOrNull() ?: refuse("the comment render loads its picture tag ${tags.size} times, not once")
    val tagRegister = (code[tagAt] as OneRegisterInstruction).registerA
    val naming = code.getOrNull(tagAt + 1) as? FiveRegisterInstruction
    if (naming == null || naming.opcode != Opcode.INVOKE_VIRTUAL || naming.registerCount != 2 || naming.registerD != tagRegister) {
        refuse("the comment picture tag isn't handed to a builder right away")
    }
    val builder = naming.registerC
    val gotos = gotoTargets(code)
    val builds = (tagAt + 2 until code.size - 1).filter { index ->
        val call = code[index]
        call.opcode == Opcode.INVOKE_VIRTUAL && (call as FiveRegisterInstruction).registerCount == 1 &&
            call.registerC == builder && code[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            ((call as ReferenceInstruction).reference as MethodReference).let {
                it.parameterTypes.isEmpty() && it.returnType == method.returnType
            } && rowsOwnNull(code, gotos, index + 2, (code[index + 1] as OneRegisterInstruction).registerA)
    }
    val build = builds.singleOrNull() ?: refuse("the comment picture is built ${builds.size} times, not once")
    val register = (code[build + 1] as OneRegisterInstruction).registerA
    return CommentSite(build + 2, register, method.returnType)
}

/** The one method of the app holding [string]. */
private fun BytecodePatchContext.methodHolding(string: String, what: String): Method {
    val found = classDefByStrings(string, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef -> classDef.methods.filter { holdsString(it, string) } }
    return found.singleOrNull() ?: refuse("expected one $what holding \"$string\", found ${found.size}")
}

/** The post header builder and the comment render. */
internal fun BytecodePatchContext.profilePictureMethods(): Pair<Method, Method> =
    methodHolding(HEADER_TRACE, "post header builder") to methodHolding(COMMENT_PICTURE_TAG, "comment render")

/** Finds both places first, so a build without one refuses before any code changes. */
internal fun BytecodePatchContext.hideProfilePictures() {
    val (header, comment) = profilePictureMethods()
    val headerSite = headerSite(header)
    val commentSite = commentSite(comment)

    // Every actor type jumps to the merge, so the hook goes on the merge's own label.
    val r = headerSite.register
    mutableClassDefBy(header.definingClass).findMutableMethodOf(header).addInstructionsAtControlFlowLabel(
        headerSite.merge,
        """
            invoke-static/range { v$r .. v$r }, $FEED_PICTURE
            move-result-object v$r
            check-cast v$r, ${headerSite.type}
        """,
    )

    // The row's own null path jumps past the hook to the add, which is what it should do.
    val c = commentSite.register
    mutableClassDefBy(comment.definingClass).findMutableMethodOf(comment).addInstructions(
        commentSite.at,
        """
            invoke-static/range { v$c .. v$c }, $COMMENT_PICTURE
            move-result-object v$c
            check-cast v$c, ${commentSite.type}
        """,
    )
}
