/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.taptoplay

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.ads.sponsoredsearch.enumConstantFields
import app.morphe.patches.facebook.ads.sponsoredsearch.invokeRegisters
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val REELS_PLAYBACK_STARTED = "reels_autoplay_started"
internal const val REELS_AUTOPLAY_DISABLED = "reels_autoplay_disabled"
internal const val CLEAR_REEL_PLAY_BUTTON = "$TAP_TO_PLAY->clearReelPlayButton(ZLjava/lang/Object;)Z"
internal const val KEEP_REEL_PLAYING = "$TAP_TO_PLAY->keepReelPlaying(Ljava/lang/Object;)Z"
internal val REEL_CONTROL_NAMES = listOf("AUTOPLAY_OFF_INIT_STATE", "PLAYING", "PAUSED", "PLAYBACK_COMPLETE")
internal val PLAYBACK_STATE_NAMES = listOf("UNINITIALIZED", "PREPARED", "ATTEMPT_TO_PLAY", "PLAYING", "PAUSED", "SEEKING")

/**
 * The playback event listener's PLAYING branch clears controls only behind viewer flags. Extend
 * that decision, rather than forcing a control state or hiding the whole component. The existing
 * reset still updates Litho, cancels the hide timer and delivers Facebook's own visibility events.
 * Both 577 and 580 use this flow; pause, seek, errors and completion branch past the injection.
 * On 583 the reset (`LX/8Mz;->A03`) hands its null to the controller's static update (`A00`), the
 * controls updater folded into the controller, which stores it as the control state.
 */
internal fun BytecodePatchContext.hookReelPlayback() {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: Reels playback controls: $detail")
    val handler = classDefByStrings(REELS_PLAYBACK_STARTED, StringComparisonType.EQUALS)
        .flatMap { it.methods }.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                it.parameterTypes.size == 1 && holdsString(it, REELS_PLAYBACK_STARTED)
        }.singleOrNull() ?: refuse("expected one playback event listener")
    val code = handler.implementation!!.instructions.toList()
    val fields = code.map { ((it as? ReferenceInstruction)?.reference as? FieldReference) }
    val stateReads = code.indices.filter { index ->
        code[index].opcode == Opcode.IGET_OBJECT && fields[index]?.type?.let { type ->
            classDefByOrNull(type)?.let { isEnumNaming(it, PLAYBACK_STATE_NAMES) }
        } == true
    }
    val read = stateReads.singleOrNull() ?: refuse("expected one player state read")
    val state = classDefBy(fields[read]!!.type)
    val playing = enumConstantFields(state).entries.singleOrNull { it.value == "PLAYING" }?.key
        ?: refuse("the player state has no unique PLAYING constant")
    val branch = read + 2
    if (fields.getOrNull(read + 1)?.let { it.definingClass == state.type && it.name == playing } != true ||
        code.getOrNull(read + 1)?.opcode != Opcode.SGET_OBJECT || code.getOrNull(branch)?.opcode != Opcode.IF_NE) {
        refuse("the state read does not branch on PLAYING")
    }
    val compare = code[branch] as TwoRegisterInstruction
    if (compare.registerA != (code[read] as OneRegisterInstruction).registerA ||
        compare.registerB != (code[read + 1] as OneRegisterInstruction).registerA) refuse("unexpected state comparison")
    val flow = ControlFlow.of(handler)
    val end = flow.normal[branch].first()
    val reset = (branch + 1 until end).firstOrNull { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        call != null && code[index].opcode == Opcode.INVOKE_VIRTUAL && call.returnType == "V" &&
            call.parameterTypes.size == 2 && call.parameterTypes[1].toString() == "Z"
    } ?: refuse("the PLAYING branch has no control reset")
    val controller = fields.getOrNull(reset - 1) ?: refuse("the reset has no controller field")
    val call = (code[reset] as ReferenceInstruction).reference as MethodReference
    val load = code[reset - 1] as? TwoRegisterInstruction ?: refuse("unexpected controller load")
    if (code[reset - 1].opcode != Opcode.IGET_OBJECT || controller.definingClass != handler.definingClass ||
        controller.type != call.definingClass || invokeRegisters(code[reset]).first() != load.registerA ||
        code[reset - 3].opcode != Opcode.IF_NEZ || code[reset - 2].opcode != Opcode.IF_EQZ ||
        flow.normal[reset - 3].first() != reset - 1 || flow.normal[reset - 2].first() != reset + 1) {
        refuse("the reset is not guarded by the two viewer conditions")
    }
    val controls = classDefBy(controller.type)
    val control = controls.fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && classDefByOrNull(field.type)?.let {
            isEnumNaming(it, REEL_CONTROL_NAMES)
        } == true
    } ?: refuse("expected one control state field")
    val clear = methodNamed(controls, call.toString()) ?: refuse("missing native reset")
    val clearCode = clear.implementation!!.instructions.toList()
    val zero = clearCode.getOrNull(1)
    if (zero?.opcode != Opcode.CONST_4 || (zero as WideLiteralInstruction).wideLiteral != 0L) {
        refuse("the native reset does not clear the control state")
    }
    val cleared = (zero as OneRegisterInstruction).registerA
    // Each body that stores the control state, with the register it must store: the reset's null, or
    // on 583, where Redex folded the controls updater into the controller, the parameter of the
    // controller's static update that the reset hands its null as the new state.
    val stores = listOf(clearCode to cleared) + clearCode.mapNotNull { instruction ->
        val callee = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (instruction.opcode != Opcode.INVOKE_STATIC || callee?.definingClass != controls.type) return@mapNotNull null
        val position = callee.parameterTypes.indexOfFirst { it.toString() == control.type }
        if (position < 0 || invokeRegisters(instruction).getOrNull(position) != cleared) return@mapNotNull null
        val update = controls.methods.singleOrNull {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null && it.name == callee.name &&
                it.returnType == callee.returnType &&
                it.parameterTypes.map(CharSequence::toString) == callee.parameterTypes.map(CharSequence::toString)
        } ?: return@mapNotNull null
        val code = update.implementation!!.instructions.toList()
        val state = update.implementation!!.registerCount - callee.parameterTypes.size + position
        // The state's parameter has to hold the reset's null wherever the update stores it.
        if (callee.parameterTypes.any { it.toString() == "J" || it.toString() == "D" } ||
            code.any { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == state }
        ) {
            refuse("the native reset does not clear the control state")
        }
        code to state
    }
    val writes = stores.flatMap { (code, register) ->
        code.filter { it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference.toString() == control.toString() }
            .map { it to register }
    }
    if (writes.isEmpty() || writes.any { (write, register) -> (write as OneRegisterInstruction).registerA != register } ||
        stores.any { (code, _) ->
            code.any { it.opcode == Opcode.SGET_OBJECT && ((it as ReferenceInstruction).reference as? FieldReference)?.type == control.type }
        }) {
        refuse("the native reset does not clear the control state")
    }
    val mutable = mutableClassDefBy(handler.definingClass).findMutableMethodOf(handler)
    val index = reset - 2
    val answer = (code[index] as OneRegisterInstruction).registerA
    val scratch = mutable.freeLocalsAt(PATCH, index, 1).single()
    if (answer > 15 || load.registerB > 15) refuse("viewer decision registers do not fit")
    mutable.addInstructionsAtControlFlowLabel(index, """
        iget-object v$scratch, v${load.registerB}, $controller
        iget-object v$scratch, v$scratch, $control
        invoke-static { v$answer, v$scratch }, $CLEAR_REEL_PLAY_BUTTON
        move-result v$answer
    """)
}

/**
 * A reel's autoplay check (GrootVideoController's onCanAutoplay) posts an "autoplay disabled" event
 * each time it fails, and the Reels controls answer it by storing AUTOPLAY_OFF_INIT_STATE, the state
 * that shows the play button. The controls only subscribe to that event where a MobileConfig flag
 * says so (585: `LX/091d;->A1F` registers the handler under `0x8106fc028c2dc2`, off on the test
 * account), and there the event can land after the PLAYING event cleared the button, leaving it over
 * a playing reel until a tap (#91). Before that store the extension is asked with the player's
 * state, read the way the controls' own update reads it, and a yes ends the handler there. On 585
 * the handler is a shared lambda's switch (`LX/0AEP;->A06`, case 3), the controls `LX/09hU`, and the
 * state read `A09` then `Er5()`.
 */
internal fun BytecodePatchContext.hookReelAutoplayDisabled() {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: Reels autoplay check: $detail")
    val handler = classDefByStrings(REELS_AUTOPLAY_DISABLED, StringComparisonType.EQUALS)
        .flatMap { it.methods }.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                it.parameterTypes.size == 1 && holdsString(it, REELS_AUTOPLAY_DISABLED)
        }.singleOrNull() ?: refuse("expected one autoplay-disabled handler")
    val code = handler.implementation!!.instructions.toList()
    val mark = code.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == REELS_AUTOPLAY_DISABLED
    }
    if (mark < 0) refuse("the handler does not load its marker")
    val store = (mark + 1 until code.size).firstOrNull { index ->
        code[index].opcode == Opcode.IPUT_OBJECT &&
            ((code[index] as ReferenceInstruction).reference as FieldReference).type.let { type ->
                classDefByOrNull(type)?.let { isEnumNaming(it, REEL_CONTROL_NAMES) } == true
            }
    } ?: refuse("no control state store after the marker")
    val control = (code[store] as ReferenceInstruction).reference as FieldReference
    val init = enumConstantFields(classDefBy(control.type)).entries
        .singleOrNull { it.value == "AUTOPLAY_OFF_INIT_STATE" }?.key
        ?: refuse("the control state has no unique AUTOPLAY_OFF_INIT_STATE constant")
    val write = code[store] as TwoRegisterInstruction
    val load = code[store - 1]
    val loaded = (load as? ReferenceInstruction)?.reference as? FieldReference
    if (load.opcode != Opcode.SGET_OBJECT || loaded?.definingClass != control.type || loaded.name != init ||
        (load as OneRegisterInstruction).registerA != write.registerA) {
        refuse("the store is not of AUTOPLAY_OFF_INIT_STATE")
    }
    // The controls' own read of the player's state: one of their fields, asked with no arguments
    // for the state enum.
    val controls = classDefBy(control.definingClass)
    val reads = controls.methods.flatMap { method ->
        val body = method.implementation?.instructions?.toList().orEmpty()
        (1 until body.size).mapNotNull { index ->
            val field = (body[index - 1] as? ReferenceInstruction)?.reference as? FieldReference
            val call = (body[index] as? ReferenceInstruction)?.reference as? MethodReference
            val opcode = body[index].opcode
            if (body[index - 1].opcode != Opcode.IGET_OBJECT || field?.definingClass != controls.type ||
                call == null || call.parameterTypes.isNotEmpty() || call.definingClass != field.type ||
                (opcode != Opcode.INVOKE_INTERFACE && opcode != Opcode.INVOKE_VIRTUAL) ||
                invokeRegisters(body[index]).singleOrNull() != (body[index - 1] as TwoRegisterInstruction).registerA ||
                classDefByOrNull(call.returnType)?.let { isEnumNaming(it, PLAYBACK_STATE_NAMES) } != true
            ) return@mapNotNull null
            Triple(field.toString(), opcode, call.toString())
        }
    }.toSet()
    val (source, opcode, read) = reads.singleOrNull() ?: refuse("expected one read of the player's state")
    val target = write.registerB
    if (target > 15) refuse("the controls register does not fit")
    val mutable = mutableClassDefBy(handler.definingClass).findMutableMethodOf(handler)
    val scratch = mutable.freeLocalsAt(PATCH, store - 1, 1).single()
    val invoke = if (opcode == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual"
    mutable.addInstructionsAtControlFlowLabel(store - 1, """
        iget-object v$scratch, v$target, $source
        if-eqz v$scratch, :keep
        $invoke { v$scratch }, $read
        move-result-object v$scratch
        invoke-static { v$scratch }, $KEEP_REEL_PLAYING
        move-result v$scratch
        if-eqz v$scratch, :keep
        return-void
        :keep
        nop
    """)
}
