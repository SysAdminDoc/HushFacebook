/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.feedsound

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.reels.SOUND_TOGGLE_STRING
import app.morphe.util.ControlFlow
import app.morphe.util.findFreeRegister
import app.morphe.util.findMutableMethodOf
import app.morphe.util.namedRegisters
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val OWN_SPEAKER_CATCHES_UP = "$FEED_SOUND->speakerCatchesUp()Z"

/**
 * The listener a video's speaker button hands the feed sound state as it starts listening (588
 * `LX/062B;`): [type] is its class, [callback] its method taking the state's reason, which reads
 * the state's flag and never the reason, and [inits] its constructors.
 */
internal class SpeakerListener(val type: String, val callback: Method, val inits: List<Method>)

private fun sameSignature(a: Method, b: Method) = a.name == b.name && a.returnType == b.returnType &&
    a.parameterTypes.map(CharSequence::toString) == b.parameterTypes.map(CharSequence::toString)

private fun addsToSet(instruction: Instruction): Boolean {
    val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return called.definingClass == "Ljava/util/Set;" && called.name == "add"
}

/**
 * Whether [method] reads [flag] and never the value its one parameter brings in: the first
 * instruction doesn't name the parameter's register, and nothing after it reads that value.
 */
private fun readsFlagNotReason(method: Method, flag: FieldReference): Boolean {
    val code = method.implementation?.instructions?.toList() ?: return false
    if (code.none { it.opcode == Opcode.IGET_BOOLEAN && (it as ReferenceInstruction).reference == flag }) return false
    val parameter = method.localRegisterCount() + 1
    if (parameter in code.first().namedRegisters()) return false
    return method.readsAfter(0, parameter).isEmpty()
}

/**
 * The speaker button's listener on the sound state (#593): of the classes taking the state's
 * listeners, the one whose callback reads the state's flag and never its reason, and whose
 * constructor is handed the unified sound toggle (the class loading [SOUND_TOGGLE_STRING]). The button works
 * its sound out from the flag as it's first built and only starts listening once it's on screen.
 * On the Reels tab the tab's own sound-on comes in between, so the first reels' buttons showed
 * the sound off and kept putting it back off. Throws when the state has no single method adding a
 * listener to a set, its listener type no single method taking a reason, or the build no single
 * such listener.
 */
internal fun BytecodePatchContext.speakerListener(state: FeedSoundState): SpeakerListener {
    val owner = classDefByOrNull(state.owner) ?: throw PatchException("No sound state ${state.owner}")
    val adds = owner.methods.filter { method ->
        method.returnType == "V" && method.parameterTypes.size == 1 &&
            method.implementation?.instructions?.any(::addsToSet) == true
    }
    val add = adds.singleOrNull()
        ?: throw PatchException("${state.owner} has ${adds.size} methods adding a listener to a set, expected one")
    val listener = add.parameterTypes.single().toString()
    val reason = state.change.parameterTypes[0].toString()
    val listenerClass = classDefByOrNull(listener) ?: throw PatchException("No sound listener type $listener")
    val callbacks = listenerClass.methods.filter {
        it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(reason)
    }
    val callback = callbacks.singleOrNull()
        ?: throw PatchException("$listener has ${callbacks.size} methods taking a $reason, expected one")
    val found = mutableListOf<SpeakerListener>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES) || listener !in classDef.interfaces) return@classDefForEach
        val heard = classDef.methods.singleOrNull { sameSignature(it, callback) && it.implementation != null }
            ?: return@classDefForEach
        if (!readsFlagNotReason(heard, state.field)) return@classDefForEach
        found += SpeakerListener(classDef.type, heard, classDef.methods.filter { it.name == "<init>" && it.implementation != null })
    }
    // Other toggles listen the same way (588: the feed's inline one and two more), and the speaker's
    // is the one handed the unified sound toggle as it's made.
    val toggles = classDefByStrings(SOUND_TOGGLE_STRING, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.map { it.type }.toSet()
    val speakers = found.filter { candidate ->
        candidate.inits.any { init -> init.parameterTypes.any { it.toString() in toggles } }
    }
    val speaker = speakers.singleOrNull()
        ?: throw PatchException(
            "Found ${speakers.size} sound listeners handed the sound toggle, of ${found.size} reading the flag and not the reason, expected one",
        )
    if (speaker.inits.isEmpty()) throw PatchException("${speaker.type} has no constructor")
    return speaker
}

/**
 * Right before each of the listener's constructors returns, asks FeedSound whether it should take
 * the flag as it is now, and if so hands its own callback no reason, which it never reads. The
 * button then shows, and puts back, the sound the state has. Throws when a constructor has a jump
 * to its return or registers past v15.
 */
internal fun BytecodePatchContext.speakerCatchesUp(speaker: SpeakerListener) {
    val classDef = mutableClassDefBy(speaker.type)
    val reason = speaker.callback.parameterTypes.single()
    for (init in speaker.inits) {
        val method = classDef.findMutableMethodOf(init)
        val where = "${speaker.type}->${init.name}"
        val self = method.localRegisterCount()
        val code = method.implementation!!.instructions.toList()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        if (returns.isEmpty()) throw PatchException("$where never returns")
        val flow = ControlFlow.of(method)
        for (index in returns) {
            if (code.indices.any { it != index - 1 && index in flow.normal[it] + flow.exceptional[it] }) {
                throw PatchException("$where has a jump to its return")
            }
        }
        for (index in returns.asReversed()) {
            val free = method.findFreeRegister(index, self)
            if (maxOf(self, free) > 15) throw PatchException("$where has registers past v15")
            method.addInstructionsWithLabels(
                index,
                """
                    invoke-static { }, $OWN_SPEAKER_CATCHES_UP
                    move-result v$free
                    if-eqz v$free, :listening
                    const/4 v$free, 0x0
                    invoke-virtual { v$self, v$free }, ${speaker.type}->${speaker.callback.name}($reason)V
                """,
                ExternalLabel("listening", method.getInstruction(index)),
            )
        }
    }
}
