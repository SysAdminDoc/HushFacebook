/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.silence

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.downloads.reel.moreSheet
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PATCH = "Skip silent parts"
internal const val SKIP_SILENCE = "$EXTENSION_PACKAGE/media/SkipSilence;"
internal const val OWN_SKIP_SILENCE = "$SKIP_SILENCE->skipSilence(Z)Z"

/**
 * ExoPlayer's AudioSink.Listener callback, a name Facebook keeps. Each audio sink calls it right
 * after handing its processor chain the skip-silence flag.
 */
internal const val SILENCE_CHANGED = "onSkipSilenceEnabledChanged"

/**
 * Lets videos, reels and stories play past the silent stretches in their sound (#144). Facebook
 * plays them through ExoPlayer, whose audio sinks run the sound through a processor chain holding
 * ExoPlayer's silence-skipping processor, and Facebook never turns it on. Each sink's apply of its
 * playback parameters hands the chain the skip-silence flag and then tells its listener, by
 * [SILENCE_CHANGED], what it handed. Facebook 582 carries two such sinks, one per ExoPlayer copy
 * it ships, and the listener's own dispatch calls that name too but hands nothing to a chain.
 *
 * In each sink, the flag goes to the extension's SkipSilence first, and the chain gets its answer
 * in a register nothing else reads, so the sink's own field and the listener keep Facebook's flag
 * and the next apply asks again. The chain counts what it cuts, and the sink's clock moves on by
 * it, so the picture keeps up.
 *
 * A reel's More sheet gets a row that keeps the silent parts in that one reel ([addSkipSilenceRow]),
 * and Download any video's post menu hook adds the same row to a video post's menu. Both learn the
 * playing video from Resume long videos' player start hook, so without that patch neither shows.
 *
 * Facebook's checks on its dav1d decoder's dropped frames read the frames the skips leave out as a
 * decoder that can't keep up, and play the SD file instead ([leaveDropChecksToSkipSilence], #286).
 *
 * In the default selection with its switch off: it changes how long a video takes to play, and
 * nobody expects that from Facebook.
 */
@Suppress("unused")
val skipSilentPartsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Skip silent parts",
    description = "Plays videos, reels and stories past the silent stretches in their sound, so talks and how-tos " +
        "get to the point sooner. Starts off. Turn it on in Hushfacebook settings > Video and audio. With Resume long " +
        "videos patched too, a video's menu can keep the silent parts in that one video.",
) {
    category("Playback")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Every sink is found and checked before any is changed, so a build with a sink of a new
        // shape stops instead of skipping silence in only some players.
        val applies = silenceApplies()
        applies.forEach { apply ->
            mutableClassDefBy(apply.method.definingClass).methods.single { same(it, apply.method) }.askBeforeApplying(apply)
        }
        // The reel More sheet's row for one video (#144). The fixture tests hold it on every declared
        // build; on another one, silent parts are still skipped, so a miss here is a warning.
        try {
            addSkipSilenceRow(PATCH, moreSheet(PATCH))
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so reels get no Skip silent parts row in their More sheet.")
        }
        // Facebook's checks on dropped frames, which took a reel down to its SD file while silence
        // was skipped (#286). Silent parts are still skipped without this, so a miss is a warning.
        try {
            leaveDropChecksToSkipSilence()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so a reel can drop to its SD file while silent parts are skipped.")
        }
        enableStatus("skipSilentParts")
    }
}

/**
 * One audio sink's hand-off of the skip-silence flag: the call at [index] of [method] hands the
 * chain object in [chain] the flag in [flag], and [scratch] is a register nothing reads there. From
 * 587 the chain answers the flag it applied, which the sink moves into [result], keeps and tells its
 * listener; up to 586 it answers nothing and [result] is null.
 */
internal class SilenceApply(val method: Method, val index: Int, val chain: Int, val flag: Int, val scratch: Int, val result: Int? = null)

internal fun same(a: Method, b: Method) = a.name == b.name && a.returnType == b.returnType &&
    a.parameterTypes.map(CharSequence::toString) == b.parameterTypes.map(CharSequence::toString)

private fun interfaceCall(instruction: Instruction): MethodReference? {
    if (instruction.opcode != Opcode.INVOKE_INTERFACE) return null
    return (instruction as ReferenceInstruction).reference as? MethodReference
}

private fun takesOneFlag(called: MethodReference) =
    called.returnType == "V" && called.parameterTypes.map(CharSequence::toString) == listOf("Z")

/** A chain's apply of the flag: one boolean, answering nothing up to 586 and the flag it applied from 587 (`LX/07Xk;->ACx`). */
private fun handsOneFlag(called: MethodReference) =
    called.returnType in setOf("V", "Z") && called.parameterTypes.map(CharSequence::toString) == listOf("Z")

/** Whether [instruction] tells an audio sink's listener its skip-silence flag ([SILENCE_CHANGED]). */
internal fun tellsListener(instruction: Instruction): Boolean {
    val called = interfaceCall(instruction) ?: return false
    return called.name == SILENCE_CHANGED && takesOneFlag(called)
}

/**
 * The sink's hand-off in [method], when it tells a listener its skip-silence flag after handing
 * one boolean to an interface held in a field of its own class. Null for a method that tells the
 * listener without handing anything on, like the listener's own dispatch. Throws for a method that
 * tells the listener and is shaped some other way.
 */
internal fun silenceApply(method: Method): SilenceApply? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val told = code.indexOfFirst(::tellsListener)
    if (told < 0) return null
    val where = "${method.definingClass}->${method.name}"
    val handed = (0 until told).filter { at ->
        val called = interfaceCall(code[at])
        called != null && called.name != SILENCE_CHANGED && handsOneFlag(called)
    }
    if (handed.isEmpty()) return null
    if (handed.size != 1) throw PatchException("$PATCH: $where hands ${handed.size} flags on before telling its listener")
    val at = handed.single()
    val call = code[at] as FiveRegisterInstruction
    val called = (code[at] as ReferenceInstruction).reference as MethodReference
    val chain = call.registerC
    val flag = call.registerD

    // The chain is the sink's own field of the interface's type, read once on every way in.
    val loads = method.writersReaching(at, chain)
    val load = loads.singleOrNull()?.takeIf { it >= 0 }?.let { code[it] }
    val field = (load as? ReferenceInstruction)?.reference as? FieldReference
    if (load?.opcode != Opcode.IGET_OBJECT || field?.definingClass != method.definingClass || field.type != called.definingClass) {
        throw PatchException("$PATCH: $where hands its flag to something other than a chain of its own")
    }

    // Code put in front of the call has to run every time the call does.
    val flow = ControlFlow.of(method)
    for (index in code.indices) {
        for (next in flow.normal[index] + flow.exceptional[index]) {
            if (next == at && index != at - 1) throw PatchException("$PATCH: $where has a jump to its flag's hand-off")
        }
    }

    // The chain's own copy of the flag goes in a register nothing reads from here on, so the
    // register Facebook stores and tells its listener keeps Facebook's flag. Four bits, for the call.
    val live = RegisterLiveness.of(method).liveInto(at)
    val scratch = (0 until minOf(16, method.localRegisterCount())).firstOrNull { it !in live && it != chain && it != flag }
        ?: throw PatchException("$PATCH: $where has no free register at its flag's hand-off")
    val result = code.getOrNull(at + 1)?.takeIf { called.returnType == "Z" && it.opcode == Opcode.MOVE_RESULT }
        ?.let { (it as OneRegisterInstruction).registerA }
    return SilenceApply(method, at, chain, flag, scratch, result)
}

/**
 * Every audio sink's hand-off of the skip-silence flag outside the extension. Throws when there's
 * none: Facebook's players apply their parameters some other way then.
 */
internal fun BytecodePatchContext.silenceApplies(): List<SilenceApply> {
    val applies = mutableListOf<SilenceApply>()
    // A hand-off calls the listener's SILENCE_CHANGED.
    classDefForEachReferencing(names = listOf(SILENCE_CHANGED)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.forEach { method -> silenceApply(method)?.let(applies::add) }
    }
    if (applies.isEmpty()) throw PatchException("$PATCH: found no audio sink that hands its processor chain a skip-silence flag")
    return applies
}

/**
 * Asks the extension for the flag right before [apply]'s hand-off, into its scratch register,
 * and hands the chain that instead. The call keeps its place and its method, so the flag register
 * Facebook goes on to store and tell its listener is untouched. Where the chain answers the flag it
 * applied, the sink gets Facebook's flag in place of that answer, which is what the chain answers
 * unpatched, so the next apply asks again here too.
 */
internal fun MutableMethod.askBeforeApplying(apply: SilenceApply) {
    val called = (getInstruction(apply.index) as ReferenceInstruction).reference.toString()
    apply.result?.let { replaceInstruction(apply.index + 1, "move/from16 v$it, v${apply.flag}") }
    replaceInstruction(apply.index, "invoke-interface { v${apply.chain}, v${apply.scratch} }, $called")
    addInstructions(
        apply.index,
        """
            invoke-static/range { v${apply.flag} .. v${apply.flag} }, $OWN_SKIP_SILENCE
            move-result v${apply.scratch}
        """,
    )
}
