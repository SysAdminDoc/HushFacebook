/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.notifications

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.FACEBOOK_APPLICATION
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireParameterIntact
import app.morphe.patches.facebook.misc.extension.requireThisIntact
import app.morphe.patches.facebook.misc.settings.MAIN_TAB_ACTIVITY
import app.morphe.patches.facebook.misc.settings.compressedCodeRefusal
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import app.morphe.util.namedRegisters
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val PUSH_DIAGNOSTICS = "$EXTENSION_PACKAGE/notifications/PushDiagnostics;"
internal const val MQTT_DELEGATE = "Lcom/facebook/rti/mqtt/manager/MqttPushServiceDelegate;"
internal const val GATEWAY_HANDLER = "Lcom/facebook/rti/notifgateway/streamhandler/NotifGatewayStreamHandler;"
internal const val GATEWAY_STREAM = "Lcom/facebook/rti/notifgateway/stream/INotifGatewayStream;"
internal const val FCM_LISTENER = "Lcom/facebook/push/fcm/FcmListenerService;"
internal const val REGISTER_RESULT = "Lcom/facebook/push/fbpushtoken/RegisterPushTokenResult;"
private const val OPERATION_RESULT = "Lcom/facebook/fbservice/service/OperationResult;"
private const val ENUM = "Ljava/lang/Enum;"
private const val ATOMIC_REFERENCE = "Ljava/util/concurrent/atomic/AtomicReference;"
private const val EVIDENCE = "Push observations"

internal val Instruction.pushCall: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference
private val Instruction.field: FieldReference?
    get() = (this as? ReferenceInstruction)?.reference as? FieldReference
internal fun Method.pushCode() = implementation?.instructions?.toList().orEmpty()
private fun Method.hasString(value: String) = pushCode().any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
}
private fun refuse(why: String): Nothing = throw PatchException("$EVIDENCE: $why")
private fun Method.parameters() = parameterTypes.map(CharSequence::toString)
private fun MethodReference.matches(method: Method) = definingClass == method.definingClass && name == method.name &&
    returnType == method.returnType && parameterTypes.map(CharSequence::toString) == method.parameters()
private fun BytecodePatchContext.enumWith(type: String, names: Set<String>): Boolean {
    val owner = classDefByOrNull(type) ?: return false
    return owner.superclass == ENUM && names.all { name -> owner.methods.any { it.name == "<clinit>" && it.hasString(name) } }
}

internal data class PushReturn(val index: Int, val value: Int, val scratch: Int)
internal data class RegistrationAnchors(val callback: Method, val failure: Method, val result: Method, val returns: List<PushReturn>)

/** Follow the kept callback into its typed Boolean validation, never treat the callback name as acceptance. */
internal fun BytecodePatchContext.findRegistrationAnchors(): RegistrationAnchors {
    val candidates = mutableListOf<Method>()
    classDefForEachReferencing(strings = listOf("FacebookPushServerRegistrar")) { owner -> candidates += owner.methods.filter {
        it.name == "onRegisterPushTokenSucceeded" && it.returnType == "V" && it.parameterTypes.size == 4 &&
            it.parameterTypes[2].toString() == OPERATION_RESULT && !AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.hasString("FacebookPushServerRegistrar")
    } }
    val callback = candidates.singleOrNull() ?: refuse("no unique kept server registrar callback")
    val route = callback.parameterTypes[1].toString()
    if (!enumWith(route, setOf("FCM", "GCM", "GCM_V3", "FBNS", "FBNS_LITE"))) refuse("server routes aren't the kept push enum")
    val failure = classDefBy(callback.definingClass).methods.singleOrNull {
        it.name == "onRegisterPushTokenFailed" && it.returnType == "V" && it.parameterTypes.size == 4 &&
            it.parameterTypes[0].toString() == route && it.parameterTypes[2].toString() == "Ljava/lang/Throwable;" &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.hasString("FacebookPushServerRegistrar")
    } ?: refuse("no kept registration failure boundary")
    val call = callback.pushCode().filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { it.pushCall }.singleOrNull {
            it.returnType == "Z" && it.parameterTypes.size == 6 && it.parameterTypes[1].toString() == OPERATION_RESULT &&
                it.parameterTypes[3].toString() == route && it.parameterTypes[5].toString() == "Ljava/lang/String;"
        } ?: refuse("no unique registration result validator")
    val result = classDefBy(call.definingClass).methods.singleOrNull { call.matches(it) && AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuse("registration validator has no body")
    val code = result.pushCode()
    if (code.none { it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as? TypeReference)?.type == REGISTER_RESULT } ||
        code.none { it.opcode == Opcode.IGET_BOOLEAN && it.field?.definingClass == REGISTER_RESULT && it.field?.type == "Z" }) {
        refuse("registration validator no longer reads the typed server acceptance result")
    }
    val indices = code.indices.filter { code[it].opcode == Opcode.RETURN }
    if (indices.isEmpty()) refuse("registration validator never returns a Boolean")
    result.requireParameterIntact(EVIDENCE, 3, indices)
    val returns = indices.map { index ->
        val value = (code[index] as OneRegisterInstruction).registerA
        if (value > 15) refuse("registration Boolean doesn't fit a read-only invoke")
        PushReturn(index, value, result.freeLocalsAt(EVIDENCE, index, 1).single())
    }
    return RegistrationAnchors(callback, failure, result, returns)
}

internal fun BytecodePatchContext.applyRegistrationAnchors(anchor: RegistrationAnchors) {
    val result = mutableClassDefBy(anchor.result.definingClass).findMutableMethodOf(anchor.result)
    val route = anchor.result.parameterRegisterNumber(3)
    anchor.returns.asReversed().forEach { (index, value, scratch) ->
        result.addInstructionsAtControlFlowLabel(index, """
            move-object/from16 v$scratch, v$route
            invoke-static { v$scratch, v$value }, $PUSH_DIAGNOSTICS->registrationResult(${ENUM}Z)V
        """.trimIndent())
    }
    mutableClassDefBy(anchor.failure.definingClass).findMutableMethodOf(anchor.failure).addInstruction(
        0, "invoke-static/range { p1 .. p1 }, $PUSH_DIAGNOSTICS->registrationFailed($ENUM)V",
    )
}

internal data class MqttAnchor(val method: Method, val write: Int, val receiver: Int, val state: Int)
internal fun BytecodePatchContext.findMqttAnchor(): MqttAnchor {
    val method = classDefByOrNull(MQTT_DELEGATE)?.methods?.singleOrNull {
        it.returnType == "V" && it.parameters() == listOf(MQTT_DELEGATE) && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.hasString("[state_machine] ") && it.pushCode().any { instruction ->
                instruction.pushCall?.let { call -> call.definingClass == ENUM && call.name == "name" } == true
            }
    } ?: refuse("no kept MQTT state transition")
    val writes = method.pushCode().withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IPUT_OBJECT && instruction.field?.definingClass == MQTT_DELEGATE &&
            instruction.field?.type?.let { enumWith(it, setOf("CONNECTING", "CONNECTED", "DISCONNECTED")) } == true
    }
    val write = writes.singleOrNull() ?: refuse("no unique MQTT enum state write")
    val instruction = write.value as TwoRegisterInstruction
    if (instruction.registerB != method.parameterRegisterNumber(0) || instruction.registerA > 15 || instruction.registerB > 15) {
        refuse("MQTT state write no longer uses its own delegate")
    }
    return MqttAnchor(method, write.index, instruction.registerB, instruction.registerA)
}

internal fun BytecodePatchContext.applyMqttAnchor(anchor: MqttAnchor) {
    mutableClassDefBy(MQTT_DELEGATE).findMutableMethodOf(anchor.method).addInstruction(anchor.write + 1,
        "invoke-static { v${anchor.receiver}, v${anchor.state} }, $PUSH_DIAGNOSTICS->mqttState(Ljava/lang/Object;$ENUM)V")
}

internal data class GatewayAnchors(val ready: Method, val readyAfter: Int, val closed: Method, val closedAfter: Int)
internal fun BytecodePatchContext.findGatewayAnchors(): GatewayAnchors {
    val owner = classDefByOrNull(GATEWAY_HANDLER) ?: refuse("no kept FBNS gateway stream handler")
    if (owner.methods.none { it.name == "getStreamName" && it.parameters().isEmpty() && it.returnType == "Ljava/lang/String;" }) {
        refuse("no kept gateway route getter")
    }
    val ready = owner.methods.singleOrNull {
        it.name == "onStreamReady" && it.returnType == "V" && it.parameters() == listOf(GATEWAY_STREAM) &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("no unique gateway ready boundary")
    val readyCode = ready.pushCode()
    val write = readyCode.indices.singleOrNull { index ->
        val instruction = readyCode[index]
        instruction.opcode == Opcode.IPUT_OBJECT && instruction.field?.let {
            it.definingClass == GATEWAY_HANDLER && it.name == "notifGatewayStream" && it.type == GATEWAY_STREAM
        } == true
    } ?: refuse("gateway readiness no longer stores the stream")
    if (write == 0 || readyCode[write - 1].pushCall?.let { it.definingClass == ATOMIC_REFERENCE && it.name == "set" } != true) {
        refuse("gateway readiness no longer sets its connection state")
    }
    val stored = readyCode[write] as TwoRegisterInstruction
    if (stored.registerA != ready.parameterRegisterNumber(0) || stored.registerB != ready.localRegisterCount()) {
        refuse("gateway readiness doesn't store its callback stream on itself")
    }
    val stateRead = readyCode.getOrNull(write - 3)
    val connected = readyCode.getOrNull(write - 2)?.field
    val connectedCheck = owner.methods.singleOrNull { it.name == "isConnected" && it.parameters().isEmpty() && it.returnType == "Z" }
        ?.pushCode()?.filter { it.opcode == Opcode.SGET_OBJECT }?.singleOrNull()?.field
    if (stateRead?.opcode != Opcode.IGET_OBJECT || stateRead.field?.definingClass != GATEWAY_HANDLER ||
        stateRead.field?.name != "connectionState" || stateRead.field?.type != ATOMIC_REFERENCE ||
        (stateRead as TwoRegisterInstruction).registerB != ready.localRegisterCount() ||
        stateRead.registerA != readyCode[write - 1].namedRegisters().first() ||
        connected == null || connected.toString() != connectedCheck?.toString()) {
        refuse("gateway readiness no longer commits its connected enum")
    }
    ready.requireParameterIntact(EVIDENCE, 0, listOf(write))
    ready.requireThisIntact(EVIDENCE, listOf(write + 1))
    val closed = owner.methods.singleOrNull {
        it.name == "closeStream" && it.returnType == "V" && it.parameterTypes.size == 3 &&
            it.parameterTypes[0].toString() == "Ljava/lang/String;" && it.parameterTypes[1].toString() == "Z" &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("no unique gateway close boundary")
    val closedCode = closed.pushCode()
    val swap = closedCode.indices.singleOrNull { index ->
        closedCode[index].pushCall?.let { it.definingClass == ATOMIC_REFERENCE && it.name == "getAndSet" } == true
    } ?: refuse("gateway close no longer commits a connection state")
    val read = closedCode.getOrNull(swap - 2)
    val closedState = closedCode.getOrNull(swap - 1)?.field
    if (read?.opcode != Opcode.IGET_OBJECT || read.field?.name != "connectionState" || read.field?.definingClass != GATEWAY_HANDLER ||
        read.field?.type != ATOMIC_REFERENCE || (read as TwoRegisterInstruction).registerB != closed.localRegisterCount() ||
        read.registerA != closedCode[swap].namedRegisters().first() ||
        closedState == null || closedState.type != connected.type || closedState.toString() == connected.toString() ||
        closedCode.getOrNull(swap + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("gateway close state shape changed")
    closed.requireThisIntact(EVIDENCE, listOf(swap + 2))
    return GatewayAnchors(ready, write + 1, closed, swap + 2)
}

internal fun BytecodePatchContext.applyGatewayAnchors(anchor: GatewayAnchors) {
    val owner = mutableClassDefBy(GATEWAY_HANDLER)
    owner.findMutableMethodOf(anchor.ready).addInstruction(anchor.readyAfter,
        "invoke-static/range { p0 .. p0 }, $PUSH_DIAGNOSTICS->gatewayReady(Ljava/lang/Object;)V")
    owner.findMutableMethodOf(anchor.closed).addInstruction(anchor.closedAfter,
        "invoke-static/range { p0 .. p0 }, $PUSH_DIAGNOSTICS->gatewayClosed(Ljava/lang/Object;)V")
}

internal fun BytecodePatchContext.findFcmReceipt(): Method = classDefByOrNull(FCM_LISTENER)?.methods?.singleOrNull {
    it.returnType == "V" && it.parameters() == listOf("Lcom/google/firebase/messaging/RemoteMessage;") &&
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.hasString("FcmMessageProcessorUtil.processMessage") &&
        it.hasString("PushProcessor.onFcmPushReceived")
} ?: refuse("no kept FCM receive callback")

/** Always included by settings, with no visible patch or toggle. A missing observer group stays unavailable. */
internal val pushDiagnosticsPatch = patch {
    dependsOn(facebookExtensionPatch)
    executeBytecode {
        if (compressedCodeRefusal(classDefByOrNull(FACEBOOK_APPLICATION) != null, classDefByOrNull(MAIN_TAB_ACTIVITY)) != null) return@executeBytecode
        fun <T> observe(status: String, find: () -> T, apply: (T) -> Unit) {
            val enabled = mutableClassDefBy(PUSH_DIAGNOSTICS).methods.singleOrNull {
                it.name == status && it.returnType == "Z" && it.parameterTypes.isEmpty()
            } ?: throw PatchException("$EVIDENCE: extension has no $status()")
            val anchor = try { find() } catch (missing: PatchException) {
                patchLog.warning("${missing.message}. This observer remains unavailable in reports.")
                return
            }
            apply(anchor)
            enabled.returnEarly(true)
        }
        observe("hasRegistrationObserver", { findRegistrationAnchors() }, { applyRegistrationAnchors(it) })
        observe("hasMqttObserver", { findMqttAnchor() }, { applyMqttAnchor(it) })
        observe("hasGatewayObserver", { findGatewayAnchors() }, { applyGatewayAnchors(it) })
        observe("hasFcmReceiptObserver", { findFcmReceipt() }) { method ->
            mutableClassDefBy(FCM_LISTENER).findMutableMethodOf(method).addInstruction(
                0, "invoke-static {}, $PUSH_DIAGNOSTICS->fcmCallback()V",
            )
        }
    }
}
