/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Watch again at the end of a feed video (#403). Read from 585 (arm64 and armv7) and its Android 9
 * build, 2026-11-06; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - Each end screen's Watch again tap (logged as one of [WATCH_AGAIN_MARKERS]) asks one boolean
 *   method whether the video opens somewhere else (585 LX/08Fc;'s A05, a9 LX/08GG;'s A05, taking the
 *   session, the story, its attachment, the player origin, a String and three booleans). A yes opens
 *   the video in the Reels viewer; a no jumps to the replay, which reads one static field first
 *   (585 LX/02xO;'s A1g, a9 LX/02y7;'s A1g) a few instructions after the branch lands.
 * - That field is the anchor that tells Watch again's asks from the others: the More videos button
 *   asks the same method, and its no reads another field (LX/02lW;'s A0h). So a site is any ask whose
 *   no lands within [REPLAY_WINDOW] instructions of a read of the replay field, found in every class that
 *   calls the method: on 585 the feed's end screen, its overlay button and the Shorts end screen.
 * - Two of those sites first ask whether the video opens in the video deep dive viewer instead
 *   (static, (Context, FbUserSession, the video, PlayerOrigin)Z), and a no there falls straight
 *   into the Watch again ask. That ask gets the same hook, so a yes there doesn't skip the one above.
 * - Each hook hands the ask's answer to the extension right after Facebook takes it, and the branch
 *   reads what comes back: false while the switch is on, so the video replays where it is.
 */

internal const val WATCH_AGAIN = "$EXTENSION_PACKAGE/reels/WatchAgain;"
internal const val OPENS_ELSEWHERE = "$WATCH_AGAIN->opensElsewhere(Z)Z"

/** What Facebook logs a Watch again tap under, one per end screen. */
internal val WATCH_AGAIN_MARKERS = listOf("video_player_control_end_screen_replay_tap", "end_screen_replay_tap", "WATCH_AGAIN_CLICK")

/** What Facebook logs a More videos tap under, for the test's control. */
internal const val MORE_VIDEOS_MARKER = "end_screen_more_videos_tap"

/** How many instructions from where a no lands the replay field is read, the branch's first nine. */
internal const val REPLAY_WINDOW = 9

private const val PATCH = "Clean up Reels"
private const val STORY = "Lcom/facebook/graphql/model/GraphQLStory;"
private const val ATTACHMENT = "Lcom/facebook/graphql/model/GraphQLStoryAttachment;"
private const val CONTEXT = "Landroid/content/Context;"
private val ASK_PARAMS = listOf(FB_USER_SESSION, STORY, ATTACHMENT, PLAYER_ORIGIN, "Ljava/lang/String;", "Z", "Z", "Z")
private val ENDS_STRAIGHT_RUN = setOf(
    Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.THROW,
)

/** A boolean call whose answer the next two instructions take and branch on: false jumps to [whenFalse]. */
internal data class Answer(val call: Int, val register: Int, val whenFalse: Int)

/** [reference] as one string, for telling methods apart. */
internal fun described(reference: MethodReference) =
    "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"

/** Whether [reference] has the shape of the opens-elsewhere ask. */
internal fun isOpensElsewhereAsk(reference: MethodReference) =
    reference.returnType == "Z" && reference.parameterTypes.map(CharSequence::toString) == ASK_PARAMS

/** Whether [reference] has the shape of the deep dive ask: (Context, FbUserSession, an object, PlayerOrigin)Z. */
internal fun isDeepDiveAsk(reference: MethodReference): Boolean {
    val params = reference.parameterTypes.map(CharSequence::toString)
    return reference.returnType == "Z" && params.size == 4 && params[0] == CONTEXT && params[1] == FB_USER_SESSION &&
        params[2].startsWith("L") && params[3] == PLAYER_ORIGIN
}

/**
 * The calls in [method] that [wanted] picks whose answer is taken by a move-result and branched on by
 * an if-eqz on that register right after.
 */
internal fun branchedAnswers(method: Method, wanted: (MethodReference) -> Boolean): List<Answer> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    val indexAt = addresses.withIndex().associate { (index, address) -> address to index }
    return code.indices.mapNotNull { at ->
        val reference = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        if (!wanted(reference) || at + 2 >= code.size) return@mapNotNull null
        val result = code[at + 1]
        val branch = code[at + 2]
        if (result.opcode != Opcode.MOVE_RESULT || branch.opcode != Opcode.IF_EQZ) return@mapNotNull null
        val register = (result as OneRegisterInstruction).registerA
        if ((branch as OneRegisterInstruction).registerA != register) return@mapNotNull null
        val target = indexAt[addresses[at + 2] + (branch as OffsetInstruction).codeOffset] ?: return@mapNotNull null
        Answer(at, register, target)
    }
}

/** The static object fields [method] reads in the [REPLAY_WINDOW] instructions from [from]. */
internal fun staticReads(method: Method, from: Int): Set<String> {
    val code = method.implementation?.instructions?.toList() ?: return emptySet()
    return (from until minOf(from + REPLAY_WINDOW, code.size)).mapNotNullTo(HashSet()) { at ->
        if (code[at].opcode != Opcode.SGET_OBJECT) return@mapNotNullTo null
        ((code[at] as ReferenceInstruction).reference as FieldReference).toString()
    }
}

/** The opens-elsewhere asks in [method] whose no reads [replay] first: the Watch again sites. */
internal fun watchAgainSites(method: Method, ask: String, replay: String): List<Answer> =
    branchedAnswers(method) { described(it) == ask }.filter { replay in staticReads(method, it.whenFalse) }

/**
 * The deep dive asks in [method] whose no runs straight into one of [sites]: lands at or before the
 * site's call with no branch, switch, return or throw on the way.
 */
internal fun deepDiveAsks(method: Method, sites: List<Answer>): List<Answer> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    fun straight(from: Int, to: Int) = (from until to).none { at ->
        code[at] is OffsetInstruction || code[at].opcode in ENDS_STRAIGHT_RUN
    }
    return branchedAnswers(method, ::isDeepDiveAsk).filter { deep ->
        sites.any { site -> deep.call < site.call && deep.whenFalse <= site.call && straight(deep.whenFalse, site.call) }
    }
}

/** The opens-elsewhere ask Watch again's taps make, read from the methods logging one of [WATCH_AGAIN_MARKERS]. */
internal fun BytecodePatchContext.watchAgainTaps(): List<Pair<Method, Answer>> =
    WATCH_AGAIN_MARKERS.flatMap { marker ->
        classDefByStrings(marker, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
            .flatMap { classDef -> classDef.methods.filter { holdsString(it, marker) } }
    }.distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}${it.returnType}" }
        .flatMap { method -> branchedAnswers(method, ::isOpensElsewhereAsk).map { method to it } }

/** Watch again's ask and its replay field, from [taps]: each must be the only one they agree on. */
internal fun watchAgainAnchors(taps: List<Pair<Method, Answer>>): Pair<MethodReference, String> {
    val asks = taps.map { (method, answer) -> askAt(method, answer) }.distinctBy(::described)
    val ask = asks.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one opens-elsewhere ask in the methods logging Watch again, found ${asks.size}",
    )
    val replays = taps.map { (method, answer) -> staticReads(method, answer.whenFalse) }.reduce { all, next -> all intersect next }
    val replay = replays.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static field every Watch again replay reads first, found ${replays.size}",
    )
    return ask to replay
}

private fun askAt(method: Method, answer: Answer) =
    (method.implementation!!.instructions.elementAt(answer.call) as ReferenceInstruction).reference as MethodReference

/** Watch again replays the video where it is when the switch is on, instead of opening Reels (#403). */
internal fun BytecodePatchContext.replayWatchAgainInPlace() {
    val (ask, replay) = watchAgainAnchors(watchAgainTaps())
    val asked = described(ask)
    val owners = mutableListOf<Pair<String, Method>>()
    classDefForEachReferencing(types = listOf(ask.definingClass)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filter { watchAgainSites(it, asked, replay).isNotEmpty() }.mapTo(owners) { classDef.type to it }
    }
    if (owners.isEmpty()) throw PatchException("$PATCH: no call of $asked replays through $replay")
    for ((owner, method) in owners) {
        val mutable = watchAgainMethod(owner, method)
        val sites = watchAgainSites(mutable, asked, replay)
        (sites + deepDiveAsks(mutable, sites)).map { it.call + 1 }.distinct().sortedDescending()
            .forEach(mutable::answerThroughExtension)
    }
}

private fun BytecodePatchContext.watchAgainMethod(owner: String, method: Method): MutableMethod {
    val params = method.parameterTypes.map(CharSequence::toString)
    return mutableClassDefBy(owner).methods.first {
        it.name == method.name && it.returnType == method.returnType && it.parameterTypes.map(CharSequence::toString) == params
    }
}

/** Hands the answer the move-result at [result] took to [OPENS_ELSEWHERE] and puts its answer back. */
private fun MutableMethod.answerThroughExtension(result: Int) {
    val register = getInstruction<OneRegisterInstruction>(result).registerA
    addInstructions(
        result + 1,
        """
            invoke-static/range { v$register .. v$register }, $OPENS_ELSEWHERE
            move-result v$register
        """,
    )
}
