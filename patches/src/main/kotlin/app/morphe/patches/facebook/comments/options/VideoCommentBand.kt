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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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
        if (at == 0 || code[at].opcode != Opcode.RETURN_OBJECT) return@filter false
        val init = code[at - 1]
        val call = (init as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        if (init.opcode != Opcode.INVOKE_DIRECT && init.opcode != Opcode.INVOKE_DIRECT_RANGE) return@filter false
        if (call.name != "<init>" || call.definingClass !in bars) return@filter false
        val built = (init as? FiveRegisterInstruction)?.registerC ?: (init as? RegisterRangeInstruction)?.startRegister
        built == (code[at] as OneRegisterInstruction).registerA
    }
    if (found.isEmpty()) return found
    val flow = ControlFlow.of(method)
    for (at in found) {
        val into = code.indices.filter { at in flow.normal[it] || at in flow.exceptional[it] }
        if (into != listOf(at - 1)) refuse("${method.descriptor()} reaches its comment bar's return at $at from $into")
    }
    return found
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
 * Where [root] loads [register], the viewer's config, of type [type]: the one instruction of [root]
 * that writes it, an iget-object of a [type] field, if every path from the start to [pick] runs it
 * first. Null otherwise, since a hook at the pick couldn't count on the register holding the config.
 */
internal fun videoConfigLoad(root: Method, register: Int, type: String, pick: Int): Int? {
    val code = root.implementation?.instructions?.toList() ?: return null
    val writes = code.indices.filter { at ->
        val opcode = code[at].opcode
        val written = (code[at] as? OneRegisterInstruction)?.registerA ?: return@filter false
        (opcode.setsRegister() && written == register) || (opcode.setsWideRegister() && written + 1 == register)
    }
    val load = writes.singleOrNull() ?: return null
    if (load >= pick || code[load].opcode != Opcode.IGET_OBJECT) return null
    if (((code[load] as ReferenceInstruction).reference as FieldReference).type != type) return null
    val flow = ControlFlow.of(root)
    val seen = BooleanArray(code.size)
    val next = ArrayDeque(listOf(0))
    while (next.isNotEmpty()) {
        val at = next.removeFirst()
        if (seen[at]) continue
        if (at == pick) return null
        seen[at] = true
        // A load that throws never writes the register, so its handler goes on without the config.
        if (at != load) flow.normal[at].forEach(next::addLast)
        flow.exceptional[at].forEach(next::addLast)
    }
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
internal fun commentBarAnswer(method: Method, at: Int): Int {
    val bar = (method.implementation!!.instructions.elementAt(at) as OneRegisterInstruction).registerA
    val live = RegisterLiveness.of(method).liveInto(at)
    return (0 until minOf(method.localRegisterCount(), 16)).firstOrNull { it != bar && it !in live }
        ?: refuse("${method.descriptor()} has no local register free at its bar return for the hook")
}

/**
 * The spots [dropVideoCommentBand] hooks: the bar picker [builder] in [owner] and its bar return at
 * [at], the [root] that calls it at [pick], keeps the bar in register [bar] with the viewer's config
 * in register [config] and reads the band height at [reads] after that, and the viewer's [list] root,
 * the one caller of the height function [slot], which reads the same band height at [listRead].
 */
internal class VideoCommentBand(
    val owner: ClassDef,
    val builder: Method,
    val at: Int,
    val root: Method,
    val pick: Int,
    val bar: Int,
    val config: Int,
    val reads: List<Int>,
    val slot: Method,
    val list: Method,
    val listRead: Int,
)

/**
 * Finds every spot of the Shorts viewer's comment band (#375) and refuses unless there's exactly one
 * bar return, one caller of its picker that calls it once, one caller of one height function, the
 * root reads one band height at least once after the pick with one config it loads before the pick,
 * and the list reads that same band height exactly once. Every register the hooks name is checked
 * here, so a refusal leaves Facebook's code untouched.
 */
internal fun BytecodePatchContext.videoCommentBand(): VideoCommentBand {
    val bars = classDefByStrings(VIDEO_COMMENT_BAR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.map { it.type }.toSet()
    if (bars.isEmpty()) refuse("no class names \"$VIDEO_COMMENT_BAR\"")
    val returns = mutableListOf<Triple<ClassDef, Method, Int>>()
    classDefForEachReferencing(types = bars) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        for (method in classDef.methods) videoCommentBarReturns(method, bars).mapTo(returns) { Triple(classDef, method, it) }
    }
    val lists = mutableListOf<Method>()
    classDefForEachReferencing(names = listOf(VDD_SLOT_HEIGHT)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(lists) { videoSlotCalls(it).isNotEmpty() }
    }
    val (owner, builder, at) = returns.singleOrNull()
        ?: refuse("expected one method handing back a comment bar it just built, found ${returns.size}")
    val list = lists.singleOrNull() ?: refuse("expected one caller of $VDD_SLOT_HEIGHT(IIZ Function0)I, found ${lists.size}")
    val slotCall = videoSlotCalls(list).singleOrNull()
        ?: refuse("${list.descriptor()} calls $VDD_SLOT_HEIGHT more than once")
    val slotClass = ((list.implementation!!.instructions.elementAt(slotCall) as ReferenceInstruction).reference as MethodReference).definingClass
    val slot = classDefByOrNull(slotClass)?.let { videoSlotHeights(it.methods) }?.singleOrNull()
        ?: refuse("$slotClass has no static $VDD_SLOT_HEIGHT(IIZ Function0)I")
    val root = videoBarPickerCallers(owner, builder).singleOrNull()
        ?: refuse("expected one caller of ${builder.descriptor()} in ${owner.type}")
    // Each method takes its own hooks at indices read from its untouched code, so none may be two of them.
    if (setOf(builder.descriptor(), root.descriptor(), list.descriptor()).size != 3) {
        refuse("${builder.descriptor()}, ${root.descriptor()} and ${list.descriptor()} aren't three methods")
    }
    val picks = videoBarPicks(root, builder)
    val pick = picks.singleOrNull()
        ?: refuse("${root.descriptor()} keeps the pick of ${builder.descriptor()} ${picks.size} times, not once")
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
    val reads = videoBandReads(root, isBand)
    if (reads.isEmpty()) refuse("${root.descriptor()} reads no band height that adds $BAND_EXTRA_DP dp")
    fun callee(method: Method, at: Int) =
        (method.implementation!!.instructions.elementAt(at) as ReferenceInstruction).reference as MethodReference
    val heights = reads.map { callee(root, it).toString() }.toSet()
    if (heights.size != 1) refuse("${root.descriptor()} reads ${heights.size} band heights: $heights")
    val configs = reads.map {
        bandConfigRegister(root, it) ?: refuse("${root.descriptor()} hands its band height at $it more than one register")
    }.toSet()
    val config = configs.singleOrNull() ?: refuse("${root.descriptor()} hands its band height ${configs.size} configs")
    val configType = callee(root, reads.first()).parameterTypes.single().toString()
    videoConfigLoad(root, config, configType, pick)
        ?: refuse("${root.descriptor()} doesn't load its config v$config once, on every path before its pick at $pick")
    // The pick's hook goes right after the bar is kept, so a band read there or before would move it.
    if (reads.any { it < pick + 2 }) refuse("${root.descriptor()} reads its band height before it keeps its pick at $pick")
    val bar = (root.implementation!!.instructions.elementAt(pick + 1) as OneRegisterInstruction).registerA
    if (bar > 15 || config > 15) refuse("${root.descriptor()} keeps its pick in v$bar with its config in v$config, past v15")
    reads.forEach { bandHandOff(root, it) }
    val listReads = videoBandReads(list, isBand)
    val listRead = listReads.singleOrNull()
        ?: refuse("${list.descriptor()} reads the band height ${listReads.size} times, not once")
    if (callee(list, listRead).toString() != heights.single()) {
        refuse("${list.descriptor()} reads ${callee(list, listRead)}, not its root's ${heights.single()}")
    }
    bandHandOff(list, listRead)
    commentBarAnswer(builder, at)
    return VideoCommentBand(owner, builder, at, root, pick, bar, config, reads, slot, list, listRead)
}

/**
 * The Shorts viewer drops its comment bar and the bar's band, its videos keep their full height and
 * its seek bar goes down with them while Hide the video comment box is on (#375). The root tells the
 * extension first thing that a render starts, and right after the pick which bar it got back for
 * which config, so a viewer whose pick keeps another bar keeps its band.
 */
internal fun BytecodePatchContext.dropVideoCommentBand(band: VideoCommentBand) {
    mutableClassDefBy(band.owner.type).findMutableMethodOf(band.builder).dropVideoCommentBar(band.at)
    mutableClassDefBy(band.owner.type).findMutableMethodOf(band.root).apply {
        // Every read comes after the kept bar, so their hooks leave the pick where it is.
        handBandToExtension(band.reads, SHORTS_SEEK_BAR_BAND, withConfig = false)
        addInstruction(band.pick + 2, "invoke-static {v${band.bar}, v${band.config}}, $KEPT_SHORTS_BAR")
        addInstruction(0, "invoke-static {}, $PICKING_SHORTS_BAR")
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
