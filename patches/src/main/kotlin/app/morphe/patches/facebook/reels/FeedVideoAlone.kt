/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Open feed videos on their own (#161). Read from 583, 2026-10-18, arm64; the obfuscated names here
 * are for reviewers, the code never writes one down.
 *
 * - A video tapped in the feed (arm64 LX/86Q;->A04, the in-feed video click) goes on to Facebook's
 *   "Warion" step (LX/86Q;->A02), the instance method answering nothing that takes the tap's Context
 *   and View first and asks its own class's kept shouldOpenInChannelFeed. Up to 585 it was also the
 *   one method holding the onWarionEligibleStoryClick trace, which 586 drops (2026-11-09, both
 *   phone builds: LX/092Y;->A02). It seeds the full-screen player with that one video and launches
 *   it. Its other callers (LX/8By; and a posted LX/9hU;) are the same hop from elsewhere in a feed video.
 * - The player's reels feed asks for more through the method holding the
 *   VideoHomeDataControllerImpl.fetchMoreDataInternally trace (LX/5Kv;->A00(LX/5cy;String)Z), an
 *   instance method answering whether a fetch went out. Deep links to the viewer
 *   (fb://fb_shorts/viewer) don't pass through the Warion step at all.
 * - The player a feed reel opens (ImmersiveActivity with WatchFeedOrWarionFragment, S22 2026-10-18)
 *   passes neither: its reels come through the client-side ranking loader, whose fetch more
 *   (LX/5Xw;->A0X(LX/1jT;II)Z, AbstractVideoHomeCSRDataLoaderAdapter.onFetchMore) already answers
 *   false for a seed video when Facebook's own data source says so, logging "onFetchMore skipped:
 *   suppressing tail load for seed video".
 *
 * - A reel in the feed opens the Warion viewer inside the main screen instead (S22 2026-10-18:
 *   FbMainTabActivity's VideoHomeRootFragment with is_warion_state, holding a WatchFeedOrWarionFragment
 *   with is_in_warion), and its loader's first fetch more comes only after the swipe, past the
 *   reels its first page already put after the video. WatchFeedOrWarionFragment keeps its name: its
 *   onFragmentCreate reads "is_in_warion" from its arguments into a field, and onDestroy ends it.
 * - The loader asks its data source (LX/6hZ;->F8d()Z, one implementer, LX/5T6;) whether to show the
 *   seed video alone, both before it puts the first page's reels after the video ("EmitResult
 *   skipped: suppressing CSR vend for seed video") and in its fetch more. Facebook answers yes only
 *   on a load error behind server flags.
 *
 * - On some accounts a feed reel skips the Warion step (S25 2026-10-18, a reel shared to the home
 *   feed): it goes straight to the reels viewer's entry (LX/7uc;->A01(Context,FbUserSession,LX/96i;I)V,
 *   the one method logging "Start new transition for vid[%s] failed"), and the viewer's data source
 *   first asks its seed check inside that call, with the main screen in front and no viewer open.
 *   The entry reads the video's PlayerOrigin ("surface::subsurface", a kept class) from its params
 *   near the top, before anything else runs. The Reels tab, a profile's reels and the split screen
 *   launcher use the same entry with their own surfaces. 586 (LX/0Cbi;->A01) answers a boolean and
 *   first drops a repeat launch of the same video a few seconds after the last ("Dropping duplicate
 *   Warion launch"), so a dropped repeat never reaches the origin read.
 * - With its more reels held back, that viewer's list still ends in a loading page that spins for
 *   good (S25 2026-10-18). The list's builder (LX/97F;->A2b(LX/1tr;)LX/2rh;, the one method of its
 *   class holding "wearables_consumption_midcard") reads the viewer's params (the entry's LX/96i;)
 *   from a field near the top, and only past a null check on them reads whether more are coming
 *   (LX/96I;->A0O:Z) to put the loading page (LX/97F;->A0E) last. The page the S25 showed is the
 *   other one (a build logging each of the builder's calls, 2026-10-18): its pagination page
 *   (LX/9hA;->A00, a static method holding "pagination_component" that answers a full-page item),
 *   which it adds when a data source flag says so (LX/5KL;->A1b()Z, compared with a register set to
 *   1) and otherwise goes on to its normal list.
 * - 584 (2026-10-22) moved the builder (LX/8cW;->A2R(LX/1ts;)LX/2nH;, params LX/8NH;) and put a
 *   check for a similar-videos ad between the null check and the read of whether more are coming
 *   (LX/8Ms;->A0P:Z, before LX/8cW;->A0F), so the read is the first one every way on from the check
 *   comes to rather than where it jumps.
 * - On 585 (emulator 2026-10-30) the data source (LX/05l9;, its check LX/07iz;->JhE()Z) first asks
 *   its own seed check as the viewer opens, from a start method (LX/05l9;->HYl(LX/08Wt;)V) that on a
 *   no hands its loaders the work that loads the video. A yes there left the viewer black for a
 *   moment until the first page brought the video in (#237, a group's reel and a home feed reel).
 *   That method asks again a second later, and a no then brings in the next reel, which Facebook
 *   shows as an "Up next" pill under the video.
 * - The 585 Android 9 build (#102) names "wearables_consumption_midcard" through a string table, makes
 *   the builder static (LX/0BZp;->A0M) with the params as an argument it null checks and then copies
 *   to a low register, and inlines the pagination page: a const-string of "pagination_component"
 *   handed straight to a void call that takes the name last. It also inlines the tail load error's
 *   when (585 `LX/08ZJ;->A00`), whose own way onto that page lands on its first instruction.
 * - The loader's vend, the void method beside its fetch more holding "EmitResult skipped" (585 arm64
 *   LX/0625;->Iyb, armv7 LX/062B;->IyZ, A9 LX/065j;->IzH), builds the page it hands the viewer, asks
 *   the same seed check, and on a no reads the page's size and hands it over. A shared reel's first
 *   page is the linked reel with Facebook's next reels after it, and it comes before the reel is on
 *   screen, while the seed checks still get Facebook's answer (#421, S22 2026-11-08: "Up next: Gb
 *   Flowers" under the reel). The extension gets the data source and the page ahead of the branch on
 *   the answer: code added where the branch lands goes in front of its label, so the branch skips it.
 *
 * The Warion step tells the extension a feed video is opening, the reels viewer's entry tells it
 * where the video opens from, and the fragment tells it when a Warion viewer opens and closes.
 * Both fetch mores ask it first, with the object asking, and answer false (nothing fetched) when
 * the extension says so, and the data source's seed check answers yes from its second ask on,
 * except for a feed's ordinary video ("<surface>::feed_story", or "group::multimedia_post" for one
 * opened from its group), whose first page that answer would hold back (#237, 584 emulator
 * 2026-10-27: the viewer stood empty up to a second longer). The list builder hands it
 * whether more are coming, and separately whether to add its pagination page, each with the viewer's
 * params, whose origin the extension reads through a stub the patch fills, and takes its answers.
 */

internal const val FEED_VIDEO_ALONE = "$EXTENSION_PACKAGE/reels/FeedVideoAlone;"
internal const val FEED_VIDEO_TAPPED = "$FEED_VIDEO_ALONE->feedVideoTapped()V"
internal const val HOLDS_BACK = "$FEED_VIDEO_ALONE->holdsBack(Ljava/lang/Object;)Z"
internal const val WARION_OPENING = "$FEED_VIDEO_ALONE->warionOpening(Ljava/lang/Object;Z)V"
internal const val WARION_CLOSED = "$FEED_VIDEO_ALONE->warionClosed(Ljava/lang/Object;)V"
internal const val SEED_CHECK = "$FEED_VIDEO_ALONE->seedCheck(Ljava/lang/Object;)Z"
internal const val VIEWER_OPENING = "$FEED_VIDEO_ALONE->reelsViewerOpening(Ljava/lang/Object;)V"
internal const val MORE_REELS_COMING = "$FEED_VIDEO_ALONE->moreReelsComing(ZLjava/lang/Object;)Z"

/** The extension's stub the patch fills with the viewer params' origin read. */
internal const val VIEWER_ORIGIN = "viewerOrigin"

/** A string the reels list's builder holds, along with a few methods that aren't it. */
internal const val LOADING_PAGE = "wearables_consumption_midcard"

/** What the reels list's pagination page, a full page that's only a loading circle, is named in its builder. */
internal const val PAGINATION_PAGE = "pagination_component"

/** The line only the reels viewer's entry logs. */
internal const val VIEWER_ENTRY = "Start new transition for vid[%s] failed"

/** Where a video says it opened from. Kept by name. */
internal const val PLAYER_ORIGIN = "Lcom/facebook/video/common/playerorigin/PlayerOrigin;"

/** Facebook's video fragment, a Warion viewer when its arguments say so. Kept by name. */
internal const val WARION_FRAGMENT = "Lcom/facebook/video/watch/fragment/WatchFeedOrWarionFragment;"

/** The argument WARION_FRAGMENT reads to know it's a Warion viewer. */
internal const val IN_WARION = "is_in_warion"

/** What WARION_FRAGMENT reads [IN_WARION] with. */
internal const val GET_BOOLEAN = "Landroid/os/BaseBundle;->getBoolean(Ljava/lang/String;)Z"

/** The kept name of the Warion step's class's check whether a feed video opens in the channel feed instead. */
internal const val CHANNEL_FEED_CHECK = "shouldOpenInChannelFeed"

/** What the Warion step, handed the tap, takes first. */
private val TAP_PARAMS = listOf("Landroid/content/Context;", "Landroid/view/View;")

/** The trace only the reels feed's fetch more holds. */
internal const val FETCH_MORE = "VideoHomeDataControllerImpl.fetchMoreDataInternally"

/** The line only the client-side ranking loader's fetch more logs, when Facebook keeps a seed video alone. */
internal const val SEED_TAIL = "onFetchMore skipped: suppressing tail load for seed video"

/** The line the same loader's vend logs, when Facebook keeps a seed video alone and hands its viewer no page. */
internal const val SEED_VEND = "EmitResult skipped: suppressing CSR vend for seed video"

internal const val VENDING = "$FEED_VIDEO_ALONE->vending(Ljava/lang/Object;Ljava/lang/Object;)V"

private const val PATCH = "Clean up Reels"

private const val OBJECT = "Ljava/lang/Object;"

/**
 * Whether [method] can be the Warion step: an instance method answering nothing that takes the tap's
 * Context and View first and asks its own class's [CHANNEL_FEED_CHECK].
 */
internal fun isWarionStep(method: Method): Boolean =
    method.returnType == "V" && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.take(2).map(CharSequence::toString) == TAP_PARAMS &&
        method.implementation?.instructions?.any { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                it.definingClass == method.definingClass && it.name == CHANNEL_FEED_CHECK
            } == true
        } == true

/** Whether [method] can be the reels viewer's entry: it holds the line and answers nothing, or (586) a boolean. */
internal fun isViewerEntry(method: Method): Boolean = method.returnType in setOf("V", "Z") && holdsString(method, VIEWER_ENTRY)

/** Where the viewer's entry has read the video's origin: the index right after, and the register holding it. */
internal data class OriginRead(val index: Int, val origin: Int)

/**
 * Where [method] first reads a [PLAYER_ORIGIN] field: the index after that read and its register.
 * Null when it reads none, or into a register too high for a four-bit call.
 */
internal fun originRead(method: Method): OriginRead? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val at = code.indexOfFirst {
        it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == PLAYER_ORIGIN
    }
    if (at < 0) return null
    val origin = (code[at] as TwoRegisterInstruction).registerA
    return if (origin > 15) null else OriginRead(at + 1, origin)
}

/** Where the reels list's builder reads whether more are coming: the index right after, and the registers of that answer and of the params. */
internal data class LoadingPageRead(val index: Int, val more: Int, val params: Int)

/** How far past a null check on the params the read of whether more are coming may sit. */
private const val MAX_REACH = 48

/**
 * Where [method] puts the viewer's params in a register a four-bit call can name: the instruction
 * at [at] writing [register]. [argument] is the params' own parameter register when the method
 * takes them as one, which its null checks test, or null.
 */
private data class ParamsLoad(val at: Int, val register: Int, val argument: Int?)

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * Where [method] gets the params: the first [paramsType] it loads from a field (583 to 585), or,
 * in Android 9's builder, a static method taking them as a parameter, its first copy of that
 * parameter, while the register still holds it, into a low register. Null when neither lands in a
 * register a four-bit call can name.
 */
private fun paramsLoad(method: Method, paramsType: String): ParamsLoad? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val field = code.indexOfFirst {
        it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == paramsType
    }
    if (field >= 0) {
        val register = (code[field] as TwoRegisterInstruction).registerA
        return if (register > 15) null else ParamsLoad(field, register, null)
    }
    val parameter = method.parameterTypes.map(CharSequence::toString).indexOf(paramsType)
    if (parameter < 0) return null
    val argument = method.parameterRegisterNumber(parameter)
    val copy = code.indexOfFirst {
        it.opcode in OBJECT_MOVES && (it as TwoRegisterInstruction).registerB == argument && it.registerA <= 15
    }
    if (copy < 0 || method.writersReaching(copy, argument) != listOf(-1)) return null
    return ParamsLoad(copy, (code[copy] as TwoRegisterInstruction).registerA, argument)
}

/**
 * Where [method] reads whether more reels are coming: the boolean field read, followed by its own
 * check, that every way on from where a null check on the params ([paramsLoad]) jumps to comes to
 * first (583 reads it right there, 584 works out an ad flag first). A check counts on the register
 * the params were put in while it holds only them, or on their parameter register while it's still
 * the parameter. Null unless there's exactly one, every way into it still holds the params from
 * that load, nothing jumps past it to its check, and both registers are small enough for a
 * four-bit call.
 */
internal fun loadingPageRead(method: Method, paramsType: String): LoadingPageRead? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val load = paramsLoad(method, paramsType) ?: return null
    val params = load.register
    val flow = ControlFlow.of(method)
    val reads = code.indices
        .filter { at ->
            code[at].opcode == Opcode.IF_EQZ && (code[at] as OneRegisterInstruction).registerA.let { tested ->
                tested == params && method.writersReaching(at, tested) == listOf(load.at) ||
                    tested == load.argument && method.writersReaching(at, tested) == listOf(-1)
            }
        }
        .flatMap { check -> flow.normal[check].filter { it != check + 1 } }
        .distinct()
        .mapNotNull { firstBooleanTest(code, flow, it) }
        .distinct()
    val read = reads.singleOrNull() ?: return null
    val more = (code[read] as TwoRegisterInstruction).registerA
    if (more > 15 || more == params) return null
    if (method.writersReaching(read, params) != listOf(load.at)) return null
    val into = code.indices.filter { read + 1 in flow.normal[it] || read + 1 in flow.exceptional[it] }
    return if (into == listOf(read)) LoadingPageRead(read + 1, more, params) else null
}

/** Whether [at] reads a boolean field that the next instruction tests for zero. */
private fun isBooleanTest(code: List<Instruction>, at: Int): Boolean {
    val gate = code.getOrNull(at + 1)
    return code[at].opcode == Opcode.IGET_BOOLEAN && gate?.opcode == Opcode.IF_EQZ &&
        (gate as OneRegisterInstruction).registerA == (code[at] as TwoRegisterInstruction).registerA
}

/**
 * The boolean test ([isBooleanTest]) every way on from [from] comes to first, going forward only.
 * Null when a way turns back, ends, runs [MAX_REACH] instructions past [from], or the ways come to
 * different tests.
 */
private fun firstBooleanTest(code: List<Instruction>, flow: ControlFlow, from: Int): Int? {
    val seen = mutableSetOf<Int>()
    val tests = mutableSetOf<Int>()
    val ways = ArrayDeque(listOf(from))
    while (ways.isNotEmpty()) {
        val at = ways.removeFirst()
        if (!seen.add(at)) continue
        if (isBooleanTest(code, at)) {
            tests += at
            continue
        }
        val next = flow.normal[at]
        if (at - from >= MAX_REACH || next.isEmpty() || next.any { it <= at }) return null
        ways += next
    }
    return tests.singleOrNull()
}

/**
 * Whether the instruction at [at] of [code] builds the pagination page: a static call [buildsPage]
 * says yes to, or, where Android 9 inlined that builder into the list's, the page's name loaded
 * and handed straight to the call naming the page, which takes nothing else but the page's own
 * builder and answers nothing. The list's other use of the name, an argument among many to a
 * logging call, isn't that.
 */
internal fun buildsPaginationPageAt(code: List<Instruction>, at: Int, buildsPage: (MethodReference) -> Boolean): Boolean {
    val instruction = code[at]
    if (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) {
        return buildsPage((instruction as ReferenceInstruction).reference as MethodReference)
    }
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) return false
    if (((instruction as ReferenceInstruction).reference as StringReference).string != PAGINATION_PAGE) return false
    val name = (instruction as OneRegisterInstruction).registerA
    val naming = code.getOrNull(at + 1) as? FiveRegisterInstruction ?: return false
    val call = (naming as ReferenceInstruction).reference as? MethodReference ?: return false
    if (naming.registerCount !in 1..2 || call.returnType != "V") return false
    if (call.parameterTypes.lastOrNull()?.toString() != "Ljava/lang/String;") return false
    return name == if (naming.registerCount == 2) naming.registerD else naming.registerC
}

/**
 * Where [method] reads whether to add its pagination page, a full page that's only a loading
 * circle: the boolean a call answers right before the branch that, answered false, skips the
 * page's builder ([buildsPaginationPageAt]). Null unless exactly one call reads that way, nothing
 * but the instruction before it leads into the answer, the branch or the straight run from the
 * branch to the builder past its first instruction, the params from their load ([paramsLoad]) still reach the branch, and
 * the answer's register is small enough for a four-bit call. The index is the branch's. Where only
 * the params' own parameter still holds them there (Android 9), that parameter is the params
 * register, and the hook copies it into a free local first.
 */
internal fun paginationRead(method: Method, paramsType: String, buildsPage: (MethodReference) -> Boolean): LoadingPageRead? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val load = paramsLoad(method, paramsType) ?: return null
    val params = load.register
    val flow = ControlFlow.of(method)
    val into = Array(code.size) { mutableSetOf<Int>() }
    for (at in code.indices) for (next in flow.normal[at] + flow.exceptional[at]) into[next].add(at)
    fun onlyFromAbove(at: Int) = into[at] == setOf(at - 1)
    val reads = code.indices.filter { at -> buildsPaginationPageAt(code, at, buildsPage) }.mapNotNull { call ->
        var top = call
        while (top > 0 && onlyFromAbove(top) && flow.normal[top - 1] == listOf(top)) top--
        val branch = top - 1
        // Android 9 also jumps onto the page's first instruction from its tail load error's when, which
        // 585 makes in LX/08ZJ;->A00 and builds its own page there. The branch's way in is still its own.
        if (branch < 2 || branch !in into[top] || !onlyFromAbove(branch) || !onlyFromAbove(branch - 1)) return@mapNotNull null
        if (code[branch - 1].opcode != Opcode.MOVE_RESULT) return@mapNotNull null
        val answer = (code[branch - 2] as? ReferenceInstruction)?.reference as? MethodReference
        if (answer?.returnType != "Z") return@mapNotNull null
        val more = (code[branch - 1] as OneRegisterInstruction).registerA
        val test = code[branch]
        val skipsOnFalse = when (test.opcode) {
            Opcode.IF_EQZ -> (test as OneRegisterInstruction).registerA == more
            // if-ne against a register only ever set to 1 skips on false too.
            Opcode.IF_NE -> {
                test as TwoRegisterInstruction
                val other = when (more) {
                    test.registerA -> test.registerB
                    test.registerB -> test.registerA
                    else -> null
                }
                other != null && other != more && method.writersReaching(branch, other).let { writers ->
                    writers.isNotEmpty() && writers.all { at ->
                        at >= 0 && code[at].opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST) &&
                            (code[at] as NarrowLiteralInstruction).narrowLiteral == 1
                    }
                }
            }
            else -> false
        }
        // Android 9's builder has put other values in the params' low copy by now, but not in their parameter.
        val held = when {
            method.writersReaching(branch, params) == listOf(load.at) -> params
            load.argument != null && method.writersReaching(branch, load.argument) == listOf(-1) -> load.argument
            else -> return@mapNotNull null
        }
        if (!skipsOnFalse || more > 15 || more == held) return@mapNotNull null
        LoadingPageRead(branch, more, held)
    }
    return reads.singleOrNull()
}

/** Whether [call] answers an object from a method holding [PAGINATION_PAGE]: the pagination page's builder. */
private fun BytecodePatchContext.buildsPaginationPage(call: MethodReference): Boolean =
    call.returnType.startsWith("L") && classDefByOrNull(call.definingClass)?.methods?.any {
        it.name == call.name && it.returnType == call.returnType &&
            it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
            holdsString(it, PAGINATION_PAGE)
    } == true

/**
 * Whether [method] is shaped like the reels list's builder: a method with a body answering an
 * object, either an instance method or, like Android 9's static one, one taking [paramsType].
 */
internal fun listBuilderShape(method: Method, paramsType: String): Boolean =
    method.returnType.startsWith("L") && method.implementation != null &&
        (!AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.any { it.toString() == paramsType })

/**
 * Whether [method] can be the reels list's builder: shaped like it, with its read. Whether it names
 * [LOADING_PAGE] is the caller's to check, since Android 9's asks a string table for it.
 */
internal fun isListBuilder(method: Method, paramsType: String): Boolean =
    listBuilderShape(method, paramsType) && loadingPageRead(method, paramsType) != null

/** The one reels list builder naming [LOADING_PAGE], as a literal or through a string table, for its read. */
private fun BytecodePatchContext.listBuilder(paramsType: String): MutableMethod {
    val found = methodsNaming(LOADING_PAGE) { listBuilderShape(it, paramsType) }
        .filter { loadingPageRead(it, paramsType) != null }
        .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("")}${it.returnType}" }
    val builder = found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one reels list builder naming \"$LOADING_PAGE\", found ${found.size}")
    return mutableClassDefBy(builder.definingClass).methods.single {
        it.name == builder.name && it.returnType == builder.returnType &&
            it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
    }
}

/** Whether [method] can be the fetch more: an instance method holding the trace and answering a boolean. */
internal fun isFetchMore(method: Method): Boolean =
    method.returnType == "Z" && !AccessFlags.STATIC.isSet(method.accessFlags) && holdsString(method, FETCH_MORE)

/** Whether [method] can be the loader's fetch more: an instance method holding its line and answering a boolean. */
internal fun isLoaderFetchMore(method: Method): Boolean =
    method.returnType == "Z" && !AccessFlags.STATIC.isSet(method.accessFlags) && holdsString(method, SEED_TAIL)

/** The one method in the app, outside the extension, that [matches] among the classes holding [anchor]. */
private fun BytecodePatchContext.oneMethodHolding(
    anchor: String,
    what: String,
    matches: (Method) -> Boolean,
    patch: String = PATCH,
): MutableMethod {
    val found = classDefByStrings(anchor, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { owner -> owner.methods.filter(matches).map { owner to it } }
    val (owner, method) = found.singleOrNull()
        ?: throw PatchException("$patch: expected one $what holding \"$anchor\", found ${found.size}")
    return mutableClassDefBy(owner.type).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }
}

/** The one method in the app, outside the extension, that [matches] among the classes declaring a method named [name]. */
private fun BytecodePatchContext.oneMethodBeside(
    name: String,
    what: String,
    matches: (Method) -> Boolean,
    patch: String,
): MutableMethod {
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE) || classDef.methods.none { it.name == name }) return@classDefForEach
        classDef.methods.filter(matches).mapTo(found) { classDef.type to it }
    }
    val (owner, method) = found.singleOrNull()
        ?: throw PatchException("$patch: expected one $what beside a $name, found ${found.size}")
    return mutableClassDefBy(owner).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }
}

/**
 * Answers [answer] before the method runs when the extension says so. At the first instruction no
 * local holds anything yet, so v0 is free, and `this` is copied down to it for the call, which names
 * its operand in four bits.
 */
private fun MutableMethod.askWithThis(hook: String, answer: Boolean) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            invoke-static { v0 }, $hook
            move-result v0
            if-eqz v0, :facebooks_answer
            const/4 v0, ${if (answer) "0x1" else "0x0"}
            return v0
        """,
        ExternalLabel("facebooks_answer", getInstruction(0)),
    )
}

/** Answers false (nothing fetched) before the fetch runs when the extension says so. */
internal fun MutableMethod.holdBackWhen(hook: String) = askWithThis(hook, false)

/** Where onFragmentCreate reads [IN_WARION]: its move-result, and the fragment and value registers. */
internal data class WarionRead(val index: Int, val fragment: Int, val value: Int)

/**
 * Where [method] reads [IN_WARION]: the index of the move-result after the one getBoolean of that
 * string, and the fragment and value registers of the field store right after it. Null unless the
 * method reads it exactly once that way, into a field of [WARION_FRAGMENT], with both registers
 * small enough for a four-bit call.
 */
internal fun warionRead(method: Method): WarionRead? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val strings = code.indices.filter { ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == IN_WARION }
    val string = strings.singleOrNull() ?: return null
    val call = code.getOrNull(string + 1) ?: return null
    if (call.opcode != Opcode.INVOKE_VIRTUAL) return null
    val read = (call as ReferenceInstruction).reference as MethodReference
    if ("${read.definingClass}->${read.name}(${read.parameterTypes.joinToString("")})${read.returnType}" != GET_BOOLEAN) {
        return null
    }
    val result = string + 2
    if (code.getOrNull(result)?.opcode != Opcode.MOVE_RESULT) return null
    val value = (code[result] as OneRegisterInstruction).registerA
    val store = code.getOrNull(result + 1) ?: return null
    if (store.opcode != Opcode.IPUT_BOOLEAN) return null
    if (((store as ReferenceInstruction).reference as FieldReference).definingClass != WARION_FRAGMENT) return null
    store as TwoRegisterInstruction
    if (store.registerA != value || store.registerB > 15 || value > 15) return null
    return WarionRead(result, store.registerB, value)
}

/** The data source's seed check [loaderFetchMore] asks: its first no-argument boolean interface call. */
internal fun seedCheck(loaderFetchMore: Method): MethodReference? =
    loaderFetchMore.implementation?.instructions?.firstOrNull { it.opcode == Opcode.INVOKE_INTERFACE }
        ?.let { (it as ReferenceInstruction).reference as MethodReference }
        ?.takeIf { it.returnType == "Z" && it.parameterTypes.isEmpty() }

/** Where the loader's vend has the seed check's answer: the branch on it, and the data source and reels page registers. */
internal data class VendAsk(val index: Int, val source: Int, val reels: Int)

/** Whether [instruction] is an interface call of [check]. */
private fun asks(instruction: Instruction, check: MethodReference): Boolean {
    if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) return false
    val called = (instruction as ReferenceInstruction).reference as? MethodReference ?: return false
    return called.definingClass == check.definingClass && called.name == check.name &&
        called.parameterTypes.isEmpty() && called.returnType == "Z"
}

private val LIST_SIZES = mapOf(
    "Ljava/util/List;" to setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
    // 586 knows the page is an ArrayList and calls it straight (AbstractCollection.size on arm64).
    "Ljava/util/AbstractCollection;" to setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
    "Ljava/util/ArrayList;" to setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
)

/** Whether [instruction] is a call of List.size(), through the interface or on the list's own class. */
private fun isListSize(instruction: Instruction): Boolean {
    val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (instruction.opcode !in LIST_SIZES[called.definingClass].orEmpty()) return false
    return called.name == "size" && called.parameterTypes.isEmpty() && called.returnType == "I"
}

/** The one register a call names, or null when it names more or none. */
private fun onlyRegister(instruction: Instruction): Int? = instruction.namedRegisters().singleOrNull()

/** Whether [instruction] writes [register], or a wide pair that covers it. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister()) return false
    val written = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (instruction.opcode.setsWideRegister() && written + 1 == register)
}

/**
 * Where [method], the loader's vend, branches on the data source's [check]: the index of the if-eqz
 * on the answer, the register of the data source it asked, and the register of the reels page it
 * vends past the jump (what its first List.size() there reads, with nothing between the jump's
 * target and that call writing it). Null unless it asks [check] once, branches on the answer right
 * after, vends forward of the branch, and neither register is the answer's.
 */
internal fun vendAsk(method: Method, check: MethodReference): VendAsk? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val ask = code.indices.filter { asks(code[it], check) }.singleOrNull() ?: return null
    val source = onlyRegister(code[ask]) ?: return null
    val result = code.getOrNull(ask + 1) ?: return null
    if (result.opcode != Opcode.MOVE_RESULT) return null
    val answer = (result as OneRegisterInstruction).registerA
    val branch = code.getOrNull(ask + 2) ?: return null
    if (branch.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != answer) return null
    val vend = branchTarget(code, ask + 2)?.takeIf { it > ask + 2 } ?: return null
    val size = (vend until code.size).firstOrNull { isListSize(code[it]) } ?: return null
    val reels = onlyRegister(code[size]) ?: return null
    if (source == answer || reels == answer || (vend until size).any { writes(code[it], reels) }) return null
    return VendAsk(ask + 2, source, reels)
}

/** Whether [method] of [classDef] is a concrete [check] of a class implementing its interface. */
internal fun implementsSeedCheck(classDef: ClassDef, method: Method, check: MethodReference): Boolean =
    check.definingClass in classDef.interfaces && method.name == check.name && method.returnType == "Z" &&
        method.parameterTypes.isEmpty() && method.implementation != null

/** What the feed video opening hooks are called in a refusal or warning of their own. */
private const val OPENING = "Feed video opening"

/** The Warion step, the reels viewer's entry, and where the entry has read the video's origin. */
internal data class FeedVideoOpening(val warion: MutableMethod, val entry: MutableMethod, val origin: OriginRead)

/** Finds where a feed video opens its player, refusing as [patch] unless each anchor finds one method. */
private fun BytecodePatchContext.findFeedVideoOpening(patch: String): FeedVideoOpening {
    val warion = oneMethodBeside(CHANNEL_FEED_CHECK, "Warion step taking the tap and answering void", ::isWarionStep, patch)
    val entry = oneMethodHolding(VIEWER_ENTRY, "reels viewer entry answering void or a boolean", ::isViewerEntry, patch)
    val origin = originRead(entry)
        ?: throw PatchException("$patch: the reels viewer's entry reads no $PLAYER_ORIGIN field into a low register")
    return FeedVideoOpening(warion, entry, origin)
}

/**
 * Tells the extension a feed video is opening its player: first thing in the Warion step, and in the
 * reels viewer's entry right after it reads the video's origin. Tap to play lets a tapped feed video
 * that starts late play on these, and Clean up Reels keeps the video alone on them, so they're a
 * patch of their own both bring (#393: with Tap to play alone a slow start was held). Changes
 * nothing unless both anchors find one method.
 */
internal fun BytecodePatchContext.hookFeedVideoOpening() {
    val (warion, entry, origin) = findFeedVideoOpening(OPENING)
    warion.addInstruction(0, "invoke-static { }, $FEED_VIDEO_TAPPED")
    entry.addInstruction(origin.index, "invoke-static { v${origin.origin} }, $VIEWER_OPENING")
}

/**
 * The feed video opening hooks ([hookFeedVideoOpening]). Nameless, so Tap to play and Clean up Reels
 * carry it. A build missing an anchor goes on without it, with a warning, so Tap to play's other
 * parts still apply; Clean up Reels refuses then.
 */
internal val feedVideoOpeningPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        try {
            hookFeedVideoOpening()
        } catch (missing: Exception) {
            // A missing anchor is a PatchException with its reason; anything else is a bug, named as one.
            val why = if (missing is PatchException) missing.message else "$OPENING: ${missing::class.java.name}: ${missing.message}"
            patchLog.warning("$why. Feed videos open without telling the extension.")
        }
    }
}

/** Whether [instruction] calls [hook]. */
private fun calls(instruction: Instruction, hook: String) =
    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == hook

/**
 * Hooks the reels list's loading page, the Warion viewer's opening and closing, both reels fetch
 * mores and the data source's seed check, and fills the extension's origin stub. Refuses unless each
 * anchor finds one method and [feedVideoOpeningPatch] has hooked the Warion step and the entry.
 */
internal fun BytecodePatchContext.keepFeedVideosAlone() {
    val (warion, entry, origin) = findFeedVideoOpening(PATCH)
    if (!calls(warion.getInstruction(0), FEED_VIDEO_TAPPED) || !calls(entry.getInstruction(origin.index), VIEWER_OPENING)) {
        throw PatchException("$PATCH: a feed video opening doesn't tell the extension, see the warning before this")
    }
    val originField = (entry.getInstruction(origin.index - 1) as ReferenceInstruction).reference as FieldReference
    val paramsType = originField.definingClass
    if (paramsType !in entry.parameterTypes.map(CharSequence::toString)) {
        throw PatchException("$PATCH: the reels viewer's entry reads its origin from $paramsType, not its params")
    }
    // The stub reads the origin from the extension's package.
    val params = classDefByOrNull(paramsType) ?: throw PatchException("$PATCH: this build has no $paramsType")
    val originDef = params.fields.singleOrNull { it.name == originField.name && it.type == PLAYER_ORIGIN }
    if (!AccessFlags.PUBLIC.isSet(params.accessFlags) || originDef == null ||
        !AccessFlags.PUBLIC.isSet(originDef.accessFlags) || AccessFlags.STATIC.isSet(originDef.accessFlags)
    ) {
        throw PatchException("$PATCH: $paramsType or its origin field isn't a public instance field the extension can read")
    }
    val list = listBuilder(paramsType)
    val gate = loadingPageRead(list, paramsType)
        ?: throw PatchException("$PATCH: the reels list builder doesn't read whether more are coming past its params check")
    val paging = paginationRead(list, paramsType) { buildsPaginationPage(it) }
        ?: throw PatchException("$PATCH: the reels list builder doesn't read whether to add its pagination page")
    if (classDefByOrNull(FEED_VIDEO_ALONE) == null) throw PatchException("$PATCH: the extension has no $FEED_VIDEO_ALONE")
    val stub = mutableClassDefBy(FEED_VIDEO_ALONE).methods.singleOrNull {
        it.name == VIEWER_ORIGIN && it.returnType == OBJECT && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
    } ?: throw PatchException("$PATCH: $FEED_VIDEO_ALONE has no static Object $VIEWER_ORIGIN(Object)")

    stub.addInstructions(
        0,
        """
            check-cast p0, $paramsType
            iget-object p0, p0, $paramsType->${originField.name}:$PLAYER_ORIGIN
            return-object p0
        """,
    )
    // The later read first, so the earlier one's index still holds.
    for (read in listOf(gate, paging).sortedByDescending { it.index }) {
        // A params parameter past v15 goes through a local that nothing reads from the read on.
        val params = if (read.params <= 15) read.params else list.freeLocalsAt(PATCH, read.index, 1).single()
        val copy = if (params == read.params) "" else "move-object/from16 v$params, v${read.params}\n"
        list.addInstructions(
            read.index,
            copy + """
                invoke-static { v${read.more}, v$params }, $MORE_REELS_COMING
                move-result v${read.more}
            """,
        )
    }
    oneMethodHolding(FETCH_MORE, "instance fetch more answering a boolean", ::isFetchMore)
        .holdBackWhen(HOLDS_BACK)
    val loader = oneMethodHolding(SEED_TAIL, "instance loader fetch more answering a boolean", ::isLoaderFetchMore)
    val check = seedCheck(loader)
        ?: throw PatchException("$PATCH: the loader's fetch more asks no boolean interface method first")
    loader.holdBackWhen(HOLDS_BACK)
    val vends = mutableClassDefBy(loader.definingClass).methods.filter { it.returnType == "V" && holdsString(it, SEED_VEND) }
    val vend = vends.singleOrNull()
        ?: throw PatchException("$PATCH: expected one void vend holding \"$SEED_VEND\" beside the loader's fetch more, found ${vends.size}")
    val ask = vendAsk(vend, check)
        ?: throw PatchException("$PATCH: the loader's vend doesn't branch on $check right after asking it, past a reels page")
    // Ahead of the branch, so only the way on from the answer reaches the call; a jump to the branch lands past it.
    val (asker, page) = vend.freeLocalsAt(PATCH, ask.index, 2)
    vend.addInstructions(
        ask.index,
        """
            move-object/from16 v$asker, v${ask.source}
            move-object/from16 v$page, v${ask.reels}
            invoke-static { v$asker, v$page }, $VENDING
        """,
    )

    val fragment = mutableClassDefByOrNull(WARION_FRAGMENT)
        ?: throw PatchException("$PATCH: $WARION_FRAGMENT is missing")
    val create = fragment.methods.singleOrNull {
        it.name == "onFragmentCreate" && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;")
    } ?: throw PatchException("$PATCH: $WARION_FRAGMENT has no onFragmentCreate(Bundle)")
    val read = warionRead(create)
        ?: throw PatchException("$PATCH: $WARION_FRAGMENT->onFragmentCreate doesn't read \"$IN_WARION\" once into a field")
    create.addInstruction(read.index + 1, "invoke-static { v${read.fragment}, v${read.value} }, $WARION_OPENING")
    val destroy = fragment.methods.singleOrNull { it.name == "onDestroy" && it.returnType == "V" && it.parameterTypes.isEmpty() }
        ?: throw PatchException("$PATCH: $WARION_FRAGMENT has no onDestroy()")
    destroy.requireLocals(PATCH, 1)
    destroy.addInstructions(
        0,
        """
            move-object/from16 v0, p0
            invoke-static { v0 }, $WARION_CLOSED
        """,
    )

    val sources = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE) || check.definingClass !in classDef.interfaces) return@classDefForEach
        classDef.methods.filter { implementsSeedCheck(classDef, it, check) }.mapTo(sources) { classDef.type to it }
    }
    val (owner, source) = sources.singleOrNull()
        ?: throw PatchException("$PATCH: expected one class answering $check, found ${sources.size}")
    mutableClassDefBy(owner).methods.single { it.name == source.name && it.returnType == "Z" && it.parameterTypes.isEmpty() }
        .askWithThis(SEED_CHECK, true)
}
