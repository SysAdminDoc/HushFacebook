/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.silence

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.reels.HERO_PLAYER_SETTING
import app.morphe.util.ControlFlow
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Facebook's checks on dropped frames (#286). Read from 585, 2026-10-31; the obfuscated names here
 * are for reviewers, the code never writes one down.
 *
 * HeroServicePlayer (arm64 LX/05qw;) checks its decoder on each position update (A0i(J)V). It looks
 * only when the decoder is Facebook's own dav1d ([META_DAV1D]), the video plays, it's 2 s in, and
 * abs(speed - 1) < 0.01, with the speed in a float field of its own (A02). Over the thresholds in
 * HeroPlayerSetting.gen ([HEALTH_THRESHOLD] and the rest) it takes the video down a rendition or
 * reports AV1_SW_HIGH_DROP_RATE, which plays the progressive SD file, and the reel's Quality row
 * goes. Skipped silence moves the clock past frames the picture never shows, so at normal speed
 * a reel a few seconds in tripped it.
 *
 * Four more checks look at any speed, each behind a gen field kept by name ([DROP_SETTINGS]): the
 * position handler's drop-rate fallback when the health check isn't set up (A1z), its low render
 * rate fallback, which first slows the video down (A0M), the large frame drop video issue
 * (LX/098C;->A06), and what dav1d's own frame drop warnings do (LX/05iS;->Inw). The warning
 * handler (LX/09MP;->A01) reads the same fields to pick the SD fallback for each warning.
 */

internal const val META_DAV1D = "meta.dav1d.av1.decoder"

/** A gen field only the health check and its kin read, which tells the health check apart. */
internal const val HEALTH_THRESHOLD = "dav1d_decoder_health_drop_rate_threshold_percent"

internal const val HEALTH_SPEED = "$SKIP_SILENCE->healthSpeed(F)F"

internal const val DROP_FALLBACK = "$SKIP_SILENCE->dropFallback(Z)Z"

internal const val DROP_THRESHOLD = "$SKIP_SILENCE->dropThreshold(J)J"

internal const val DROP_RECOVERY = "$SKIP_SILENCE->dropRecovery(J)J"

/** HeroPlayerSetting's field holding the generated settings. */
private const val GENERATED = "gen"

/** The gen fields Facebook's checks on dropped frames read, and the extension method each read goes through. */
internal val DROP_SETTINGS = mapOf(
    "enable_av1_sw_drop_rate_fallback" to DROP_FALLBACK,
    "enable_av1_sw_low_render_fps_fallback" to DROP_FALLBACK,
    "large_frame_drop_video_issue_threshold" to DROP_THRESHOLD,
    "very_large_frame_drop_video_issue_threshold" to DROP_THRESHOLD,
    "dav1d_frame_drop_recovery_action" to DROP_RECOVERY,
)

/** One read to hand the extension: the instruction at [index] of [method], through [hook]. */
internal class DropRead(val method: Method, val index: Int, val hook: String)

private fun fieldOf(instruction: Instruction) = (instruction as? ReferenceInstruction)?.reference as? FieldReference

/** Whether the only way into the instruction after [at] in [method] is from [at], so code put there always runs. */
private fun fallsThroughOnly(method: Method, at: Int): Boolean {
    val flow = ControlFlow.of(method)
    val size = method.implementation!!.instructions.count()
    return (0 until size).none { index -> index != at && at + 1 in flow.normal[index] + flow.exceptional[index] }
}

/**
 * The index in [method] of the speed read the health check compares with 1.0: an `iget` of a float
 * field of its own class, then a `const/high16` of 1.0f, a `sub-float/2addr` of the two, and
 * Math.abs on the result. Null unless there's exactly one.
 */
internal fun healthSpeedRead(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    return code.indices.filter { at ->
        val field = fieldOf(code[at])
        if (code[at].opcode != Opcode.IGET || field?.type != "F" || field.definingClass != method.definingClass) {
            return@filter false
        }
        val speed = (code[at] as TwoRegisterInstruction).registerA
        val one = code.getOrNull(at + 1)
        val sub = code.getOrNull(at + 2)
        val abs = (code.getOrNull(at + 3) as? ReferenceInstruction)?.reference as? MethodReference
        one?.opcode == Opcode.CONST_HIGH16 && (one as NarrowLiteralInstruction).narrowLiteral == 0x3f800000 &&
            sub?.opcode == Opcode.SUB_FLOAT_2ADDR && (sub as TwoRegisterInstruction).registerA == speed &&
            sub.registerB == (one as OneRegisterInstruction).registerA &&
            abs?.definingClass == "Ljava/lang/Math;" && abs.name == "abs" &&
            abs.parameterTypes.map(CharSequence::toString) == listOf("F")
    }.singleOrNull()
}

/** Whether [method] is the dav1d health check: it names [META_DAV1D] and reads [HEALTH_THRESHOLD] from [settings]. */
internal fun isHealthCheck(method: Method, settings: String): Boolean {
    val code = method.implementation?.instructions ?: return false
    return code.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == META_DAV1D } &&
        code.any { fieldOf(it)?.let { field -> field.definingClass == settings && field.name == HEALTH_THRESHOLD } == true }
}

/** The indices in [method] of its reads of a [DROP_SETTINGS] field of [settings], with the hook for each. */
internal fun dropReads(method: Method, settings: String): List<DropRead> {
    val reads = mutableListOf<DropRead>()
    method.implementation?.instructions?.forEachIndexed { at, instruction ->
        if (instruction.opcode != Opcode.IGET_BOOLEAN && instruction.opcode != Opcode.IGET_WIDE) return@forEachIndexed
        val field = fieldOf(instruction) ?: return@forEachIndexed
        val hook = DROP_SETTINGS[field.name]
        if (hook != null && field.definingClass == settings) reads += DropRead(method, at, hook)
    }
    return reads
}

/** The class HeroPlayerSetting keeps its generated settings in. Throws when it has none. */
internal fun BytecodePatchContext.playerSettings(): String =
    classDefByOrNull(HERO_PLAYER_SETTING)?.fields?.singleOrNull { it.name == GENERATED }?.type
        ?: throw PatchException("$PATCH: $HERO_PLAYER_SETTING has no $GENERATED settings")

/**
 * The health check's speed read and every read of a [DROP_SETTINGS] field outside the extension,
 * all found and checked before anything changes. Throws when the health check or a setting's read
 * isn't there, or code put after a read wouldn't always run.
 */
internal fun BytecodePatchContext.dropChecks(): List<DropRead> {
    val settings = playerSettings()
    val checks = mutableListOf<DropRead>()
    classDefForEachReferencing(strings = listOf(META_DAV1D)) { classDef ->
        classDef.methods.filter { isHealthCheck(it, settings) }.forEach { method ->
            healthSpeedRead(method)?.let { checks += DropRead(method, it, HEALTH_SPEED) }
        }
    }
    if (checks.size != 1) throw PatchException("$PATCH: found ${checks.size} dav1d health checks reading the speed")
    val reads = mutableListOf<DropRead>()
    classDefForEachReferencing(names = DROP_SETTINGS.keys.toList()) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES) || classDef.type == settings) return@classDefForEachReferencing
        classDef.methods.forEach { reads += dropReads(it, settings) }
    }
    val missing = DROP_SETTINGS.keys.filter { name ->
        reads.none { fieldOf(it.method.implementation!!.instructions.elementAt(it.index))?.name == name }
    }
    if (missing.isNotEmpty()) throw PatchException("$PATCH: nothing reads ${missing.joinToString()}")
    val all = checks + reads
    all.firstOrNull { !fallsThroughOnly(it.method, it.index) }?.let {
        throw PatchException("$PATCH: ${it.method.definingClass}->${it.method.name} has a jump past a read at ${it.index}")
    }
    return all
}

/**
 * Hands each of [reads] in this method to its hook right after the read, on the read's register,
 * last first so the indices hold. A read already handed over is left alone.
 */
internal fun MutableMethod.handDropReads(reads: List<DropRead>) {
    for (read in reads.sortedByDescending { it.index }) {
        val code = implementation!!.instructions
        val expected = read.method.implementation!!.instructions.elementAt(read.index)
        val found = code.getOrNull(read.index)
        if (found?.opcode != expected.opcode || fieldOf(found)?.toString() != fieldOf(expected)?.toString()) {
            throw PatchException("$PATCH: $definingClass->$name no longer reads ${fieldOf(expected)} at ${read.index}")
        }
        val next = code.getOrNull(read.index + 1)
        if (((next as? ReferenceInstruction)?.reference)?.toString() == read.hook) continue
        val register = (found as TwoRegisterInstruction).registerA
        val wide = read.hook.endsWith("J")
        val last = if (wide) register + 1 else register
        addInstructions(
            read.index + 1,
            """
                invoke-static/range { v$register .. v$last }, ${read.hook}
                move-result${if (wide) "-wide" else ""} v$register
            """,
        )
    }
}

/** [dropChecks], each handed to the extension in the patched app. */
internal fun BytecodePatchContext.leaveDropChecksToSkipSilence() {
    dropChecks().groupBy { it.method }.forEach { (method, reads) ->
        mutableClassDefBy(method.definingClass).methods.single { same(it, method) }.handDropReads(reads)
    }
}
