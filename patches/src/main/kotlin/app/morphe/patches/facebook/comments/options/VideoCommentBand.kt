/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.classDefForEachReferencing
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
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The band the Shorts viewer keeps for the video comment bar (#375), read from 585 on 2026-11-03 and
 * 2026-11-04. The obfuscated names are for reviewers; the code finds each spot by a kept name or
 * string and a shape.
 *
 * On 585 a video tapped in the feed opens the Shorts viewer (ImmersiveActivity,
 * FB_SHORTS_VIDEO_DEEP_DIVE). Each video's root (`LX/0BXp;` arm64, `LX/0BXs;` armv7) lays the video
 * out over a bottom bar its `A0C` picks: a reply bar under a reel shared in a chat, a drafts bar, and
 * by default the viewer's bottom chrome, the class whose method names [VIDEO_COMMENT_BAR] (`LX/08gJ;`,
 * `LX/08gO;`), built and returned right there. That chrome holds the comment bar, and under some of
 * Facebook's flags the scrub time while the seek bar is dragged or a settings row instead; on 585 on
 * the S22 it only ever showed the comment bar. It takes Emo().Bql() + 44 dp (60 dp, 169 px on the
 * S22) whatever it holds, so with Hide the video comment box on the box went but the band stayed: an
 * empty black strip under every video that still opened the comments when tapped. The extension is
 * asked right before that return, and while the switch is on the root gets null, Facebook's own
 * answer where it shows no bar. The extension hears each root render start too, so a render whose
 * pick keeps another bar keeps its band.
 *
 * The root places the seek bar from the band height (`LX/0BX4;->A00`, the static that adds 44 dp,
 * read twice in its `render`, handed the viewer's config `LX/0DhY;`), not from the video, so it
 * stayed over the caption once the video grew. Each read goes through the extension. Right after
 * the pick the extension also gets the bar the root got back and that config, which the root loads
 * once on every path before the pick, and a bar it didn't drop marks the config. A pick that came back
 * with no bar while the switch is on, dropped or none to begin with (the pick has five other returns,
 * two of them null), makes both reads in that render answer 3 dp, what the root uses where the viewer
 * has no bar, since the list gives that video its full height either way. Answering 3 dp only after
 * a drop left the seek bar under the caption in the feed reel scroller (VideoHomeRootFragment in
 * FbMainTabActivity, opened from a feed post's Reel button) on 2026-11-04.
 *
 * The videos' height comes from the viewer's list root (`LX/0BXZ;->BJw`, which logs
 * onRenderVideoHomeRootComponent), the one caller of the kept Kotlin function [VDD_SLOT_HEIGHT],
 * (full, band, reserve, Function0)I. It reads the same band height once, with the config it gets from
 * the viewer's `Dmt()` (`LX/0BWv;->A00` index 86), and takes it off the full height, and it does that
 * before any video's root has picked a bar. So that read goes through the extension too, which
 * answers 0 while the switch is on unless the config is marked, and the video fills the screen from
 * its first frame. Each root reads `Dmt()` once when it's made, and `LX/0Aon;->A0S` swaps the viewer's
 * config for a new one (`getAndSet`) from about 20 places, so the list's config and a root's are the
 * same object only until the next swap. A viewer whose roots keep another bar keeps Facebook's band
 * from the list's next layout with a config a root marked; its first layout, and any after a swap,
 * may put the video under that bar. With the switch off all of it stays Facebook's.
 *
 * 586 (read 2026-11-09) inlines the picker into the root's render (arm64 `LX/087O;->render`): each bar
 * is built in place, the comment bar `LX/09A7;` at 727 to 738, and a goto takes it to 565, where every
 * pick meets with its bar in v1 (null on the root's own no-bar path at 560). So the drop goes in front
 * of that goto and leaves null in v1, and the kept bar is told right after the merge's first
 * instruction. The config is `LX/087O;->A0B:LX/0APO;` in v15, the band height `LX/0BOH;->A00`, read at
 * 980 and 1250 after the merge and at 606 on the reply bar's way to it, which stays Facebook's. The
 * list root is `LX/0BOl;->BLf` and the height function's class `LX/0BOM;`.
 *
 * 587 (read 2026-11-13) has the picker as a static of its own again (arm64 `LX/0B2h;->A07`, armv7
 * `LX/0B29;->A07`), which builds only the comment bar and returns it (arm64 63), so the drop goes back
 * in front of that return. The root's render calls it from two branches (arm64 702 and 793, armv7 698
 * and 790), each keeping the bar in v6 and jumping to 641 (armv7 637), where the root's own no-bar
 * path arrives with v6 null. The kept bar is told right after that spot's first instruction. On
 * arm64 the config is `LX/0B2h;->A0C:LX/0AUE;` in v13 and the band height `LX/0B1u;->A01` (armv7
 * `LX/0B1M;->A00`), read at 998 and 1357 after the merge and at 682 on the way to the first pick,
 * which stays Facebook's.
 */

internal const val DROPS_SHORTS_COMMENT_BAR = "$COMMENT_SHEET_OPTIONS->dropsShortsCommentBar()Z"
internal const val PICKING_SHORTS_BAR = "$COMMENT_SHEET_OPTIONS->pickingShortsBar()V"
internal const val KEPT_SHORTS_BAR = "$COMMENT_SHEET_OPTIONS->keptShortsBar(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val SHORTS_SEEK_BAR_BAND = "$COMMENT_SHEET_OPTIONS->shortsSeekBarBand(I)I"
internal const val SHORTS_VIDEO_BAND = "$COMMENT_SHEET_OPTIONS->shortsVideoBand(ILjava/lang/Object;)I"

/** The Shorts viewer's video height function, a Kotlin name kept on 585 on both ABIs. */
internal const val VDD_SLOT_HEIGHT = "vddVideoSlotHeightPx"

/** What the viewer's band height adds, in dp, to the inset it reads. */
internal const val BAND_EXTRA_DP = 44

private const val PATCH = "Comment sheet options (video comment band)"

private val SLOT_PARAMS = listOf("I", "I", "Z", "Lkotlin/jvm/functions/Function0;")

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The Shorts viewer's video height functions among [methods]: static [VDD_SLOT_HEIGHT], (IIZ Function0)I. */
internal fun <M : Method> videoSlotHeights(methods: Iterable<M>): List<M> = methods.filter { method ->
    AccessFlags.STATIC.isSet(method.accessFlags) && method.name == VDD_SLOT_HEIGHT && method.returnType == "I" &&
        method.implementation != null && method.parameterTypes.map(CharSequence::toString) == SLOT_PARAMS
}

/** Where [method] calls a static [VDD_SLOT_HEIGHT], (IIZ Function0)I: the index of each call. */
internal fun videoSlotCalls(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        val opcode = code[at].opcode
        if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return@filter false
        val call = (code[at] as ReferenceInstruction).reference as? MethodReference ?: return@filter false
        call.name == VDD_SLOT_HEIGHT && call.returnType == "I" && call.parameterTypes.map(CharSequence::toString) == SLOT_PARAMS
    }
}

/**
 * Where [method] hands back a comment bar it just built: each return-object right after a
 * constructor of one of [bars], the classes naming [VIDEO_COMMENT_BAR], of the register that
 * constructor was called on. Refuses one that something other than the constructor jumps to, since
 * the hook goes in front of it.
 */
internal fun videoCommentBarReturns(method: Method, bars: Set<String>): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val found = code.indices.filter { at ->
        at > 0 && code[at].opcode == Opcode.RETURN_OBJECT &&
            constructed(code[at - 1], bars) == (code[at] as OneRegisterInstruction).registerA
    }
    return onlyFromConstructor(method, code, found)
}

/** Refuses any of [found] that something other than the instruction in front of it reaches, since a hook goes in front of it. */
private fun onlyFromConstructor(method: Method, code: List<Instruction>, found: List<Int>): List<Int> {
    if (found.isEmpty()) return found
    val flow = ControlFlow.of(method)
    for (at in found) {
        val into = code.indices.filter { at in flow.normal[it] || at in flow.exceptional[it] }
        if (into != listOf(at - 1)) refuse("${method.descriptor()} reaches its comment bar's hand-off at $at from $into")
    }
    return found
}

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/** The register a constructor call [init] builds, or null when it isn't one. */
private fun constructed(init: Instruction, bars: Set<String>): Int? {
    val call = (init as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (init.opcode != Opcode.INVOKE_DIRECT && init.opcode != Opcode.INVOKE_DIRECT_RANGE) return null
    if (call.name != "<init>" || call.definingClass !in bars) return null
    return (init as? FiveRegisterInstruction)?.registerC ?: (init as? RegisterRangeInstruction)?.startRegister
}

/**
 * 586's form of [videoCommentBarReturns]: the picker sits inside the root's render (arm64
 * `LX/087O;->render`), and each bar it builds jumps to one spot where the picks meet. Each goto right
 * after a constructor of one of [bars], which only that constructor reaches.
 */
internal fun videoCommentBarJumps(method: Method, bars: Set<String>): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val found = code.indices.filter { at -> at > 0 && code[at].opcode in GOTOS && constructed(code[at - 1], bars) != null }
    return onlyFromConstructor(method, code, found)
}

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * The registers holding the bar the constructor right before the goto [at] in [method] builds, at the
 * goto: the new-instance's register and each plain copy made since, less any written over. The range
 * constructor of 586 (arm64 `LX/087O;->render` 738) gets a copy in v55 while the picks meet with the
 * bar in v1. Empty unless the code from the new-instance to the goto runs straight through.
 */
internal fun videoBarHolders(method: Method, at: Int): Set<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptySet()
    val init = code.getOrNull(at - 1) as? ReferenceInstruction ?: return emptySet()
    val type = (init.reference as? MethodReference)?.definingClass ?: return emptySet()
    val receiver = constructed(init, setOf(type)) ?: return emptySet()
    val flow = ControlFlow.of(method)
    fun reachedOnlyFromAbove(index: Int) =
        code.indices.filter { index in flow.normal[it] || index in flow.exceptional[it] } == listOf(index - 1)
    var made = at - 1
    do {
        if (made < 1 || !reachedOnlyFromAbove(made)) return emptySet()
        made--
    } while (code[made].opcode != Opcode.NEW_INSTANCE || ((code[made] as ReferenceInstruction).reference as TypeReference).type != type)
    val holders = mutableSetOf((code[made] as OneRegisterInstruction).registerA)
    for (index in made + 1 until at - 1) {
        val instruction = code[index]
        if (!instruction.opcode.setsRegister()) continue
        val written = (instruction as OneRegisterInstruction).registerA
        val copied = instruction.opcode in OBJECT_MOVES && (instruction as TwoRegisterInstruction).registerB in holders
        holders.remove(written)
        if (instruction.opcode.setsWideRegister()) holders.remove(written + 1)
        if (copied) holders += written
    }
    return if (receiver in holders) holders else emptySet()
}

/**
 * Where the root tells the extension which bar it kept, for a pick that meets at [merge] in [root]:
 * right after the merge's first instruction, which writes neither [bar] nor [config], can only go on
 * to the next one, and is the only way there. Every pick, a bar or none, goes through it.
 */
internal fun keptAfterMerge(root: Method, merge: Int, bar: Int, config: Int): Int {
    val code = root.implementation!!.instructions.toList()
    val first = code[merge]
    val flow = ControlFlow.of(root)
    val written = (first as? OneRegisterInstruction)?.registerA?.takeIf { first.opcode.setsRegister() }
    if (written == bar || written == config || (first.opcode.setsWideRegister() && written != null && written + 1 in setOf(bar, config))) {
        refuse("${root.descriptor()} writes its bar or config first thing where its picks meet at $merge")
    }
    if (flow.normal[merge].toSet() != setOf(merge + 1) || flow.exceptional[merge].isNotEmpty() ||
        code.getOrNull(merge + 1)?.opcode in setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_EXCEPTION)
    ) {
        refuse("${root.descriptor()} doesn't go straight on where its picks meet at $merge")
    }
    val into = code.indices.filter { merge + 1 in flow.normal[it] || merge + 1 in flow.exceptional[it] }
    if (into != listOf(merge)) refuse("${root.descriptor()} reaches the spot after its picks meet at $merge from $into")
    return merge + 1
}

/** The indices of [method] reachable from [from], [from] included. */
private fun reachableFrom(method: Method, from: Int): Set<Int> {
    val flow = ControlFlow.of(method)
    val seen = mutableSetOf<Int>()
    val next = ArrayDeque(listOf(from))
    while (next.isNotEmpty()) {
        val at = next.removeFirst()
        if (!seen.add(at)) continue
        flow.normal[at].forEach(next::addLast)
        flow.exceptional[at].forEach(next::addLast)
    }
    return seen
}

/** Whether [method] is the viewer's band height: static, one object in, an int out, adding [BAND_EXTRA_DP]. */
internal fun isVideoBandHeight(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" && method.parameterTypes.size == 1 &&
        method.parameterTypes[0].startsWith("L") && method.implementation?.instructions?.any {
            it.opcode == Opcode.ADD_INT_LIT8 && (it as NarrowLiteralInstruction).narrowLiteral == BAND_EXTRA_DP
        } == true

/** Whether [instruction] calls [picker], the viewer's bar picker. */
private fun callsPicker(instruction: Instruction, picker: Method): Boolean {
    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return call.definingClass == picker.definingClass && call.name == picker.name && call.returnType == picker.returnType &&
        call.parameterTypes.map(CharSequence::toString) == picker.parameterTypes.map(CharSequence::toString)
}

/** The methods of [owner] that call [picker], the viewer's bar picker. */
internal fun videoBarPickerCallers(owner: ClassDef, picker: Method): List<Method> = owner.methods.filter { method ->
    method.implementation?.instructions?.any { callsPicker(it, picker) } == true
}

/** Where [root] calls [picker]: the index of each call whose bar the move-result-object right after keeps. */
internal fun videoBarPicks(root: Method, picker: Method): List<Int> {
    val code = root.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        callsPicker(code[at], picker) && code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }
}

/**
 * 587's form of the root's pick: the root calls its picker from more than one branch (arm64
 * `LX/0B2h;->render` 702 and 793), and each keeps the bar in one register and jumps to one spot where
 * the picks meet with the root's own no-bar path (641, v6 null there). The spot and the bar's register
 * when every pick in [picks] keeps its bar by the move-result-object right after it, in one register,
 * and goes on by a goto right after that to one spot. Null otherwise.
 */
internal fun videoBarPickJumps(root: Method, picks: List<Int>): Pair<Int, Int>? {
    if (picks.isEmpty()) return null
    val code = root.implementation?.instructions?.toList() ?: return null
    val flow = ControlFlow.of(root)
    val meets = picks.map { pick ->
        val keep = code.getOrNull(pick + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return null
        if (code.getOrNull(pick + 2)?.opcode !in GOTOS) return null
        val merge = flow.normal[pick + 2].singleOrNull() ?: return null
        merge to (keep as OneRegisterInstruction).registerA
    }.toSet()
    return meets.singleOrNull()
}

/**
 * Where [root] reads the band height: each static call [isBand] says is the band height, with its
 * result kept by the move-result right after it.
 */
internal fun videoBandReads(root: Method, isBand: (MethodReference) -> Boolean): List<Int> {
    val code = root.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        val opcode = code[at].opcode
        if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return@filter false
        val call = (code[at] as ReferenceInstruction).reference as? MethodReference ?: return@filter false
        at + 1 < code.size && code[at + 1].opcode == Opcode.MOVE_RESULT && isBand(call)
    }
}

/** The band height [call] (a static of [method]'s code at [at]) is handed: its one register, the viewer's config. */
internal fun bandConfigRegister(method: Method, at: Int): Int? {
    val call = method.implementation?.instructions?.elementAtOrNull(at) ?: return null
    return (call as? FiveRegisterInstruction)?.takeIf { it.registerCount == 1 }?.registerC
        ?: (call as? RegisterRangeInstruction)?.takeIf { it.registerCount == 1 }?.startRegister
}

/**
 * Where [root] loads [register], the viewer's config, of type [type]: its one write of the register
 * ahead of [pick] that is an iget-object of a [type] field, if every path from the start to [pick]
 * runs it first and no other write of the register reaches [pick] without running it again (587's
 * armv7 root, LX/0B29;->render, reuses v13 for a builder argument at 1380 on its way to a return).
 * Null otherwise, since a hook at the pick couldn't count on the register holding the config.
 */
internal fun videoConfigLoad(root: Method, register: Int, type: String, pick: Int): Int? {
    val code = root.implementation?.instructions?.toList() ?: return null
    val writes = code.indices.filter { at ->
        val opcode = code[at].opcode
        val written = (code[at] as? OneRegisterInstruction)?.registerA ?: return@filter false
        (opcode.setsRegister() && written == register) || (opcode.setsWideRegister() && written + 1 == register)
    }
    val load = writes.filter { at ->
        at < pick && code[at].opcode == Opcode.IGET_OBJECT &&
            ((code[at] as ReferenceInstruction).reference as FieldReference).type == type
    }.singleOrNull() ?: return null
    val flow = ControlFlow.of(root)
    fun reachesPick(from: List<Int>): Boolean {
        val seen = BooleanArray(code.size)
        val next = ArrayDeque(from)
        while (next.isNotEmpty()) {
            val at = next.removeFirst()
            if (seen[at]) continue
            if (at == pick) return true
            seen[at] = true
            // A load that throws never writes the register, so its handler goes on without the config.
            if (at != load) flow.normal[at].forEach(next::addLast)
            flow.exceptional[at].forEach(next::addLast)
        }
        return false
    }
    if (reachesPick(listOf(0))) return null
    // Another write's value reaches the pick unless the load runs again on the way.
    val overwrites = (writes - load).flatMap { flow.normal[it] + flow.exceptional[it] }
    if (reachesPick(overwrites)) return null
    return load
}

/**
 * The band height read at [at] in [method]: the register its dp lands in and the config it was
 * handed, both one a hook's four-bit invoke can name. Refuses otherwise, so a refusal comes before
 * anything is hooked.
 */
internal fun bandHandOff(method: Method, at: Int): Pair<Int, Int> {
    val config = bandConfigRegister(method, at)
        ?: refuse("${method.descriptor()} hands its band height at $at more than one register")
    val dp = (method.implementation!!.instructions.elementAt(at + 1) as OneRegisterInstruction).registerA
    if (dp == config) refuse("${method.descriptor()} keeps its band height at $at in its config's register v$dp")
    if (dp > 15 || config > 15) refuse("${method.descriptor()} reads its band height at $at into v$dp from v$config, past v15")
    return dp to config
}

/**
 * The local register [dropVideoCommentBar] answers in at the bar return [at]: the first under v16 that
 * isn't the bar's and that no path from the return reads, a handler of a try around it included.
 */
internal fun commentBarAnswer(
    method: Method,
    at: Int,
    bar: Int = (method.implementation!!.instructions.elementAt(at) as OneRegisterInstruction).registerA,
): Int {
    val live = RegisterLiveness.of(method).liveInto(at)
    return (0 until minOf(method.localRegisterCount(), 16)).firstOrNull { it != bar && it !in live }
        ?: refuse("${method.descriptor()} has no local register free at its bar return for the hook")
}

/**
 * The spots [dropVideoCommentBand] hooks: the bar picker [builder] in [owner] and where it hands its
 * bar on at [at], the [root] whose pick lands in register [bar], with the viewer's config in register
 * [config], by [kept], and which reads the band height at [reads] after that, and the viewer's [list]
 * root, the one caller of the height function [slot], which reads the same band height at
 * [listRead]. Up to 585 the builder is a method of its own returning the bar at [at], and the root
 * keeps its answer at [kept] - 1. On 586 ([jumps]) the builder is the root, [at] is the goto that
 * takes the bar to where the picks meet, and [kept] is just after that. On 587 the builder is a
 * method of its own again, and [kept] is just after the spot where the root's picks of it meet.
 */
internal class VideoCommentBand(
    val owner: ClassDef,
    val builder: Method,
    val at: Int,
    val root: Method,
    val kept: Int,
    val bar: Int,
    val config: Int,
    val reads: List<Int>,
    val slot: Method,
    val list: Method,
    val listRead: Int,
    val jumps: Boolean,
)

/**
 * Finds every spot of the Shorts viewer's comment band (#375) and refuses unless there's exactly one
 * bar return, one caller of its picker that calls it once or from branches meeting at one spot, one
 * caller of one height function, the
 * root reads one band height at least once after the pick with one config it loads before the pick,
 * and the list reads that same band height exactly once. Every register the hooks name is checked
 * here, so a refusal leaves Facebook's code untouched.
 */
internal fun BytecodePatchContext.videoCommentBand(): VideoCommentBand {
    val bars = classDefByStrings(VIDEO_COMMENT_BAR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.map { it.type }.toSet()
    if (bars.isEmpty()) refuse("no class names \"$VIDEO_COMMENT_BAR\"")
    val returns = mutableListOf<Triple<ClassDef, Method, Int>>()
    val jumps = mutableListOf<Triple<ClassDef, Method, Int>>()
    classDefForEachReferencing(types = bars) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        for (method in classDef.methods) {
            videoCommentBarReturns(method, bars).mapTo(returns) { Triple(classDef, method, it) }
            videoCommentBarJumps(method, bars).mapTo(jumps) { Triple(classDef, method, it) }
        }
    }
    val lists = mutableListOf<Method>()
    classDefForEachReferencing(names = listOf(VDD_SLOT_HEIGHT)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(lists) { videoSlotCalls(it).isNotEmpty() }
    }
    val handOffs = returns + jumps
    val (owner, builder, at) = handOffs.singleOrNull()
        ?: refuse("expected one method handing on a comment bar it just built, found ${handOffs.size}")
    val inline = jumps.isNotEmpty()
    val list = lists.singleOrNull() ?: refuse("expected one caller of $VDD_SLOT_HEIGHT(IIZ Function0)I, found ${lists.size}")
    val slotCall = videoSlotCalls(list).singleOrNull()
        ?: refuse("${list.descriptor()} calls $VDD_SLOT_HEIGHT more than once")
    val slotClass = ((list.implementation!!.instructions.elementAt(slotCall) as ReferenceInstruction).reference as MethodReference).definingClass
    val slot = classDefByOrNull(slotClass)?.let { videoSlotHeights(it.methods) }?.singleOrNull()
        ?: refuse("$slotClass has no static $VDD_SLOT_HEIGHT(IIZ Function0)I")
    val root = if (inline) builder else videoBarPickerCallers(owner, builder).singleOrNull()
        ?: refuse("expected one caller of ${builder.descriptor()} in ${owner.type}")
    // Each method takes its own hooks at indices read from its untouched code, so none may be two of
    // them, but for 586's root, which builds the bar itself and takes all of its hooks at once.
    val methods = setOf(builder.descriptor(), root.descriptor(), list.descriptor())
    if (methods.size != if (inline) 2 else 3) refuse("${builder.descriptor()}, ${root.descriptor()} and ${list.descriptor()} overlap")
    val rootCode = root.implementation!!.instructions.toList()
    // Where the bar lands: the move-result after the root's one call of the picker up to 585, on 586
    // the register the bar was built in, at the spot the goto takes it to, and on 587 the register
    // every call's move-result keeps, at the spot their gotos take it to.
    val bar: Int
    val merge: Int
    var picksMeet = false
    if (inline) {
        merge = ControlFlow.of(root).normal[at].single()
        // The bar the root goes on with, one register that holds it at the goto and is read where the picks meet.
        val holders = videoBarHolders(root, at)
        val live = RegisterLiveness.of(root).liveInto(merge)
        bar = holders.filter { it in live }.singleOrNull()
            ?: refuse("${root.descriptor()} doesn't go on with the bar it builds at ${at - 1} in one register, held in $holders")
    } else {
        val picks = videoBarPicks(root, builder)
        val meet = if (picks.size > 1) videoBarPickJumps(root, picks) else null
        if (meet != null) {
            picksMeet = true
            merge = meet.first
            bar = meet.second
            if (bar !in RegisterLiveness.of(root).liveInto(merge)) {
                refuse("${root.descriptor()} doesn't read the bar v$bar its picks bring to $merge")
            }
        } else {
            val pick = picks.singleOrNull()
                ?: refuse("${root.descriptor()} keeps the pick of ${builder.descriptor()} ${picks.size} times, not once or at one spot")
            bar = (rootCode[pick + 1] as OneRegisterInstruction).registerA
            merge = pick + 1
        }
    }
    val bandHeights = mutableMapOf<String, Boolean>()
    val isBand = { call: MethodReference ->
        bandHeights.getOrPut(call.toString()) {
            classDefByOrNull(call.definingClass)?.methods?.any {
                it.name == call.name && it.returnType == call.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
                    isVideoBandHeight(it)
            } == true
        }
    }
    // The seek bar's reads come after the pick. 586's reply bar reads the band height for itself on
    // its way to the merge (arm64 `LX/087O;->render` 606), and that read stays Facebook's.
    val afterPick = reachableFrom(root, merge)
    val reads = videoBandReads(root, isBand).filter { it in afterPick }
    if (reads.isEmpty()) refuse("${root.descriptor()} reads no band height that adds $BAND_EXTRA_DP dp after its pick")
    fun callee(method: Method, at: Int) =
        (method.implementation!!.instructions.elementAt(at) as ReferenceInstruction).reference as MethodReference
    val heights = reads.map { callee(root, it).toString() }.toSet()
    if (heights.size != 1) refuse("${root.descriptor()} reads ${heights.size} band heights: $heights")
    val configs = reads.map {
        bandConfigRegister(root, it) ?: refuse("${root.descriptor()} hands its band height at $it more than one register")
    }.toSet()
    val config = configs.singleOrNull() ?: refuse("${root.descriptor()} hands its band height ${configs.size} configs")
    val configType = callee(root, reads.first()).parameterTypes.single().toString()
    val kept = if (inline || picksMeet) keptAfterMerge(root, merge, bar, config) else merge + 1
    videoConfigLoad(root, config, configType, kept)
        ?: refuse("${root.descriptor()} doesn't load its config v$config once, on every path before its pick at $kept and not overwritten there")
    if (reads.any { it < kept }) refuse("${root.descriptor()} reads its band height before it keeps its pick at $kept")
    if (bar > 15 || config > 15) refuse("${root.descriptor()} keeps its pick in v$bar with its config in v$config, past v15")
    reads.forEach { bandHandOff(root, it) }
    val listReads = videoBandReads(list, isBand)
    val listRead = listReads.singleOrNull()
        ?: refuse("${list.descriptor()} reads the band height ${listReads.size} times, not once")
    if (callee(list, listRead).toString() != heights.single()) {
        refuse("${list.descriptor()} reads ${callee(list, listRead)}, not its root's ${heights.single()}")
    }
    bandHandOff(list, listRead)
    if (inline) commentBarAnswer(builder, at, bar) else commentBarAnswer(builder, at)
    return VideoCommentBand(owner, builder, at, root, kept, bar, config, reads, slot, list, listRead, inline)
}

/**
 * The Shorts viewer drops its comment bar and the bar's band, its videos keep their full height and
 * its seek bar goes down with them while Hide the video comment box is on (#375). The root tells the
 * extension first thing that a render starts, and right after the pick which bar it got back for
 * which config, so a viewer whose pick keeps another bar keeps its band.
 */
internal fun BytecodePatchContext.dropVideoCommentBand(band: VideoCommentBand) {
    // Read from the untouched root, where videoCommentBand checked it.
    val free = if (band.jumps) commentBarAnswer(band.root, band.at, band.bar) else null
    if (free == null) mutableClassDefBy(band.owner.type).findMutableMethodOf(band.builder).dropVideoCommentBar(band.at)
    mutableClassDefBy(band.owner.type).findMutableMethodOf(band.root).apply {
        // Every index comes from the untouched root, so the hooks go in from the last one back.
        val hooks = mutableListOf<Pair<Int, MutableMethod.() -> Unit>>()
        for (read in band.reads) hooks += read + 2 to { handBandToExtension(listOf(read), SHORTS_SEEK_BAR_BAND, withConfig = false) }
        hooks += band.kept to { addInstruction(band.kept, "invoke-static {v${band.bar}, v${band.config}}, $KEPT_SHORTS_BAR") }
        if (free != null) hooks += band.at to { dropVideoCommentBarJump(band.at, band.bar, free) }
        hooks += 0 to { addInstruction(0, "invoke-static {}, $PICKING_SHORTS_BAR") }
        hooks.sortedByDescending { it.first }.forEach { (_, hook) -> hook() }
    }
    mutableClassDefBy(band.list.definingClass).findMutableMethodOf(band.list)
        .handBandToExtension(listOf(band.listRead), SHORTS_VIDEO_BAND, withConfig = true)
}

/**
 * Right before the Shorts viewer hands back the comment bar it built, at [at]: on a yes from the
 * extension it hands back null instead, as it does where it shows no bar. Only the bar's register is
 * live at a return, so the answer goes in the first local register that isn't the bar's.
 */
internal fun MutableMethod.dropVideoCommentBar(at: Int) {
    val free = commentBarAnswer(this, at)
    addInstructionsWithLabels(
        at,
        """
            invoke-static {}, $DROPS_SHORTS_COMMENT_BAR
            move-result v$free
            if-eqz v$free, :bar
            const/4 v$free, 0x0
            return-object v$free
        """,
        ExternalLabel("bar", getInstruction(at)),
    )
}

/**
 * 586's form of [dropVideoCommentBar]: right before the goto at [at] takes the bar in [bar] to where
 * the picks meet, a yes from the extension leaves null there instead, what the root's own no-bar path
 * (arm64 `LX/087O;->render` 560) brings. The answer goes in [free], which nothing reads after the goto.
 */
internal fun MutableMethod.dropVideoCommentBarJump(at: Int, bar: Int, free: Int) {
    addInstructionsWithLabels(
        at,
        """
            invoke-static {}, $DROPS_SHORTS_COMMENT_BAR
            move-result v$free
            if-eqz v$free, :bar
            const/4 v$bar, 0x0
        """,
        ExternalLabel("bar", getInstruction(at)),
    )
}

/**
 * After each band height read at [reads], [hook] gets the dp, and the viewer's config the read was
 * handed when [withConfig], and its answer takes the dp's place in the same register. Goes in from
 * the last read back, so each index still holds. A jump to the spot after a read lands past the hook,
 * so the root's own no-bar paths (3 or 12 dp, jumping in right after its first read) stay Facebook's.
 */
internal fun MutableMethod.handBandToExtension(reads: List<Int>, hook: String, withConfig: Boolean) {
    for (read in reads.sortedDescending()) {
        val (dp, config) = bandHandOff(this, read)
        val handed = if (withConfig) "v$dp, v$config" else "v$dp"
        addInstructions(
            read + 2,
            """
                invoke-static {$handed}, $hook
                move-result v$dp
            """,
        )
    }
}
