/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.addressed
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The UP NEXT bar under a video opened from the feed, "UP NEXT: <author> · 0:27" with the next
 * video's thumbnail and an X (#367). Read from 585, 2026-11-02, arm64; the obfuscated names here are
 * for reviewers, the code never writes one down.
 *
 * - ModernFeedUpNextController (LX/0dTg;) holds the next video for each video by its id. Its static,
 *   synchronized lookup (A00) is the one method holding the ModernFeedUpNextFeedUnitFeedQuery
 *   GraphQL query, and the two maybeScheduleFetchRunnable runnables (LX/0cj1;, LX/0cj2;) call it
 *   with the controller and the video's id. 586 (2026-11-09, LX/0lEF;->A01) hands it the video's
 *   model, the controller and an Integer instead, from the scheduleLegacyFetch runnables and three
 *   other callers; it's still the one method holding the query.
 * - The bar's components (LX/0Jom;, LX/0JpU;, built in LX/0cah;'s render) read what came back
 *   through the controller's synchronized (String)List getter (A03), which answers Facebook's own
 *   empty list when nothing was stored for that id.
 * - Both hooks go in first thing, before the lock: the lookup returns before it queries, and the
 *   getter answers an empty list, the answer Facebook gives a video with nothing up next. The getter
 *   catches a video whose next one was looked up before the switch was turned on.
 *
 * The bar's room (#375). The feed's video attachment (LX/03D0;'s A18) measures the bar and hands its
 * height to a static setter (LX/086O;'s A00, (…FI)V) that writes it into the video's Up next state
 * (LX/059K;'s A00 Float). The full-screen player keeps that many pixels empty under the seek bar,
 * from its first frame and whether or not the bar has a video to show, and the end screens get it
 * as ModernFeedUpNextBottomInsetPx. The setter is the one method calling the kept
 * [OVERLAID_ASPECT_RATIO]. It returns first thing when the switch is on, so the Float stays null:
 * Facebook's own state for a video with no inset, which keeps the seek bar at the bottom.
 *
 * The viewer most feed videos open in (#375 again, 2026-11-03). On 585 a video tapped in the feed
 * opens the Shorts viewer (ImmersiveActivity, FB_SHORTS_VIDEO_DEEP_DIVE), which none of the hooks
 * above reach, and it has its own UP NEXT pill. WatchMetadataPeekPillController (LX/0AqK;) shows
 * each of its pills through one static (View, controller, Integer)V method (A06) whose log label
 * switch names each kind: 1 [PEEK_PILL], the UP NEXT pill with the next video's thumbnail, 2
 * [NEXT_PILL], the same over a card that isn't a video, 3 Playback2xSpeedPill, 4 ReelsAutoAdvanceBar,
 * then AutoAdvanceIdleTimer. The two kinds are read from that switch, and the method hides the
 * container and returns first thing for those two when the switch is on. The 2x speed pill and the
 * auto-advance ones still show. The black band under that viewer's videos is the comment bar's, and
 * goes with Hide the video comment box (comments/options/VideoCommentBand.kt).
 */

internal const val SKIP_UP_NEXT_FETCH = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->skipUpNextFetch()Z"
internal const val HIDE_UP_NEXT_VIDEOS = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideUpNextVideos()Z"
internal const val SKIP_UP_NEXT_ROOM = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->skipUpNextRoom()Z"
internal const val HIDE_UP_NEXT_PILL =
    "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideUpNextPill(Landroid/view/View;Ljava/lang/Integer;II)Z"

/** The pill controller's log labels for the two UP NEXT pill kinds, read from its label switch. */
internal const val PEEK_PILL = "MetadataPeekPill"
internal const val NEXT_PILL = "NextVideoPill"

/** The GraphQL query the Up next lookup sends, a name Facebook's server reads, so it stays. */
internal const val UP_NEXT_QUERY = "ModernFeedUpNextFeedUnitFeedQuery"

/** A Kotlin internal function the inset setter calls, kept by name with its module suffix on 585. */
internal const val OVERLAID_ASPECT_RATIO = "getOverlaidVideoAspectRatioMaybe\$fbandroid_java_com_facebook_feedplugins_video_video"

private const val PATCH = "Clean up Reels"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val VIEW = "Landroid/view/View;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val FLOAT = "Ljava/lang/Float;"

/**
 * The lookups among [methods]: static, answering nothing, taking a [controller], holding
 * [UP_NEXT_QUERY]. 585's also takes the video's id; 586's takes the video's model and an Integer.
 */
internal fun <M : Method> upNextLookups(controller: String, methods: Iterable<M>): List<M> = methods.filter { method ->
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        controller in method.parameterTypes.map(CharSequence::toString) && holdsString(method, UP_NEXT_QUERY)
}

/** The getters among [methods]: instance methods with a body that answer a List for a String. */
internal fun <M : Method> upNextGetters(methods: Iterable<M>): List<M> = methods.filter { method ->
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null && method.returnType == LIST &&
        method.parameterTypes.map(CharSequence::toString) == listOf(STRING)
}

/**
 * The inset setters among [methods]: static, answering nothing, ending in a Float and an int, and
 * calling [OVERLAID_ASPECT_RATIO].
 */
internal fun <M : Method> upNextInsetSetters(methods: Iterable<M>): List<M> = methods.filter { method ->
    val params = method.parameterTypes.map(CharSequence::toString)
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        params.size >= 2 && params.takeLast(2) == listOf("F", "I") &&
        method.implementation?.instructions?.any { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.name == OVERLAID_ASPECT_RATIO
        } == true
}

/**
 * Where a method that the inset setter is inlined into (586's Android 9 build, the feed video
 * attachment's lambda LX/03jK;->invoke) skips storing the room: [read] reads the Up next state's
 * Float into [register], and the `if-nez` after it jumps to [skipTo] when it's already set.
 */
internal data class UpNextRoomGuard(val read: Int, val register: Int, val skipTo: Int)

/**
 * The room guard in [method], when it calls [OVERLAID_ASPECT_RATIO] once without being an inset
 * setter: the Float field stored right after that call is read once before it and tested with
 * `if-nez`, the read isn't a branch target, and the skip lands on a write of the read's register
 * that doesn't read it, so the hook can leave its answer there. Null otherwise.
 */
internal fun upNextRoomGuard(method: Method): UpNextRoomGuard? {
    if (upNextInsetSetters(listOf(method)).isNotEmpty()) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val call = code.indices.singleOrNull {
        ((code[it] as? ReferenceInstruction)?.reference as? MethodReference)?.name == OVERLAID_ASPECT_RATIO
    } ?: return null
    val store = (call + 1 until minOf(code.size, call + 8)).firstOrNull {
        code[it].opcode == Opcode.IPUT_OBJECT && ((code[it] as ReferenceInstruction).reference as FieldReference).type == FLOAT
    } ?: return null
    val room = (code[store] as ReferenceInstruction).reference.toString()
    val read = (0 until call).singleOrNull {
        code[it].opcode == Opcode.IGET_OBJECT && (code[it] as ReferenceInstruction).reference.toString() == room
    } ?: return null
    val register = (code[read] as OneRegisterInstruction).registerA
    val test = code[read + 1]
    if (test.opcode != Opcode.IF_NEZ || (test as OneRegisterInstruction).registerA != register) return null
    if (read in branchTargets(code)) return null
    val skipTo = branchTarget(code, read + 1) ?: return null
    val landing = code[skipTo]
    if (!landing.opcode.setsRegister() || (landing as? OneRegisterInstruction)?.registerA != register ||
        (landing as? TwoRegisterInstruction)?.registerB == register || (landing as? ThreeRegisterInstruction)?.registerC == register
    ) return null
    return UpNextRoomGuard(read, register, skipTo)
}

/**
 * The pill showers among [methods]: static, answering nothing, taking a View, an [owner] and an
 * Integer, and naming both [PEEK_PILL] and [NEXT_PILL].
 */
internal fun <M : Method> upNextPillShowers(owner: String, methods: Iterable<M>): List<M> = methods.filter { method ->
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(VIEW, owner, INTEGER) &&
        holdsString(method, PEEK_PILL) && holdsString(method, NEXT_PILL)
}

/**
 * The keys of [method]'s switches on the pill's kind whose case starts by loading [label], the kinds
 * Facebook logs under it. A switch on anything but the kind names no kind.
 */
internal fun pillKinds(method: Method, label: String): Set<Int> {
    val byAddress = addressed(method)
    val kinds = HashSet<Int>()
    for ((index, entry) in byAddress.entries.withIndex()) {
        val (address, instruction) = entry
        if (instruction.opcode != Opcode.PACKED_SWITCH && instruction.opcode != Opcode.SPARSE_SWITCH) continue
        if (!switchesOnKind(method, index)) continue
        val payload = byAddress[address + (instruction as OffsetInstruction).codeOffset] as? SwitchPayload ?: continue
        payload.switchElements.filter { element ->
            ((byAddress[address + element.offset] as? ReferenceInstruction)?.reference as? StringReference)?.string == label
        }.mapTo(kinds) { it.key }
    }
    return kinds
}

/**
 * Whether the switch at [at] in [method] switches on the kind: its last parameter, an Integer, as it
 * was passed, through intValue(), with nothing else written into either register on the way.
 */
private fun switchesOnKind(method: Method, at: Int): Boolean {
    val code = method.implementation?.instructions?.toList() ?: return false
    val kind = method.implementation!!.registerCount - 1
    val unboxed = method.writersReaching(at, (code[at] as OneRegisterInstruction).registerA).singleOrNull() ?: return false
    if (unboxed < 1 || code[unboxed].opcode != Opcode.MOVE_RESULT) return false
    val call = code[unboxed - 1] as? FiveRegisterInstruction ?: return false
    val callee = (call as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.opcode == Opcode.INVOKE_VIRTUAL && callee.name == "intValue" && callee.returnType == "I" &&
        callee.parameterTypes.isEmpty() && call.registerCount == 1 && call.registerC == kind &&
        method.writersReaching(unboxed - 1, kind) == listOf(-1)
}

/** The controller's lookup skips its query and its getter answers an empty list when the switch is on. */
internal fun BytecodePatchContext.hideUpNext() {
    val controllers = classDefByStrings(UP_NEXT_QUERY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter { upNextLookups(it.type, it.methods).isNotEmpty() }
    val controller = controllers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one class whose lookup holds \"$UP_NEXT_QUERY\", found ${controllers.size}")
    val mutable = mutableClassDefBy(controller.type)
    val lookup = upNextLookups(controller.type, mutable.methods).singleOrNull()
        ?: throw PatchException("$PATCH: ${controller.type} has more than one Up next lookup")
    val getter = upNextGetters(mutable.methods).singleOrNull() ?: throw PatchException(
        "$PATCH: expected one (String)List getter in ${controller.type}, found ${upNextGetters(mutable.methods).size}",
    )
    lookup.returnVoidWhen(SKIP_UP_NEXT_FETCH)
    getter.returnEmptyListWhen(HIDE_UP_NEXT_VIDEOS)
    keepNoUpNextRoom()
}

/**
 * The setter of the bar's room returns before it writes the height when the switch is on (#375).
 * Where the setter is inlined, the code that holds it skips the store the way it does when the
 * room is already set.
 */
private fun BytecodePatchContext.keepNoUpNextRoom() {
    val setters = mutableListOf<Pair<String, Method>>()
    val inlined = mutableListOf<Pair<String, Method>>()
    classDefForEachReferencing(names = listOf(OVERLAID_ASPECT_RATIO)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        upNextInsetSetters(classDef.methods).mapTo(setters) { classDef.type to it }
        classDef.methods.filter { upNextRoomGuard(it) != null }.mapTo(inlined) { classDef.type to it }
    }
    setters.singleOrNull()?.let { (owner, setter) -> return mutableMethod(owner, setter).returnVoidWhen(SKIP_UP_NEXT_ROOM) }
    val (owner, holder) = inlined.takeIf { setters.isEmpty() }?.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static (…FI)V method calling $OVERLAID_ASPECT_RATIO or one method it's inlined into, " +
            "found ${setters.size} and ${inlined.size}",
    )
    val method = mutableMethod(owner, holder)
    val guard = upNextRoomGuard(method)
        ?: throw PatchException("$PATCH: $owner->${holder.name} lost its Up next room read before it was hooked")
    method.addInstructionsWithLabels(
        guard.read,
        """
            invoke-static { }, $SKIP_UP_NEXT_ROOM
            move-result v${guard.register}
            if-nez v${guard.register}, :hush_no_room
        """,
        ExternalLabel("hush_no_room", method.getInstruction(guard.skipTo)),
    )
}

/** The Shorts viewer's pill shower hides the container and returns for the two UP NEXT kinds when the switch is on (#375). */
internal fun BytecodePatchContext.hideShortsUpNext() {
    val owners = classDefByStrings(NEXT_PILL, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter { upNextPillShowers(it.type, it.methods).isNotEmpty() }
    val owner = owners.singleOrNull()
        ?: throw PatchException("$PATCH: expected one class whose pill shower names \"$NEXT_PILL\", found ${owners.size}")
    val shower = upNextPillShowers(owner.type, owner.methods).singleOrNull()
        ?: throw PatchException("$PATCH: ${owner.type} has more than one pill shower")
    val peek = pillKinds(shower, PEEK_PILL).singleOrNull()
        ?: throw PatchException("$PATCH: no single \"$PEEK_PILL\" kind in ${owner.type}->${shower.name}'s switch")
    val next = pillKinds(shower, NEXT_PILL).singleOrNull()
        ?: throw PatchException("$PATCH: no single \"$NEXT_PILL\" kind in ${owner.type}->${shower.name}'s switch")
    if (peek == next) throw PatchException("$PATCH: \"$PEEK_PILL\" and \"$NEXT_PILL\" are one kind, $peek, in ${owner.type}->${shower.name}")
    mutableMethod(owner.type, shower).returnVoidForPills(peek, next)
}

private fun BytecodePatchContext.mutableMethod(owner: String, method: Method): MutableMethod {
    val params = method.parameterTypes.map(CharSequence::toString)
    return mutableClassDefBy(owner).methods.first {
        it.name == method.name && it.returnType == method.returnType && it.parameterTypes.map(CharSequence::toString) == params
    }
}

/**
 * Hands [HIDE_UP_NEXT_PILL] the container (p0), the kind (p2) and the [peek] and [next] kinds first
 * thing, and returns when it says so. v0 to v3 are free there: the parameters are copied down so the
 * call reads registers under v16 whatever the method's size.
 */
private fun MutableMethod.returnVoidForPills(peek: Int, next: Int) {
    requireLocals(PATCH, 4)
    addInstructionsWithLabels(
        0,
        """
            const v0, $peek
            const v1, $next
            move-object/from16 v2, p0
            move-object/from16 v3, p2
            invoke-static { v2, v3, v0, v1 }, $HIDE_UP_NEXT_PILL
            move-result v0
            if-eqz v0, :facebooks_pill
            return-void
        """,
        ExternalLabel("facebooks_pill", getInstruction(0)),
    )
}

/** Asks [hook] first thing and answers an empty list when it says so. v0 is free there, as in [returnVoidWhen]. */
internal fun MutableMethod.returnEmptyListWhen(hook: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_list
            invoke-static { }, Ljava/util/Collections;->emptyList()Ljava/util/List;
            move-result-object v0
            return-object v0
        """,
        ExternalLabel("facebooks_list", getInstruction(0)),
    )
}
