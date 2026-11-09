/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Where Facebook tells the sender that you've read a chat, read from 577, 580 and 581.
 *
 * A chat that opens inside Facebook marks a thread read through Mailbox, the same on-device store
 * the typing setter goes through: one feature method takes the thread under the API name below,
 * makes the future it answers, posts the call that does the work and returns the future (581
 * `LX/5LX;->A0D`). Every caller either drops that future or hangs a callback on it, and none waits
 * on it, so the patch hands back the future without posting the call. The read never leaves the
 * phone, and Mailbox doesn't mark the thread read here either.
 *
 * Dating's chats mark read through a mutation of their own (581 `LX/HgM;->Amx`, product type
 * DATING), which this leaves alone.
 */
internal const val READ_RECEIPTS_PATCH = "Hide read receipts"

internal const val HOLDS_CHAT_READ = "$EXTENSION_PACKAGE/chats/ReadReceipts;->holdsChatRead()Z"

/** The Mailbox API name the mark-read method logs its call under. */
internal const val MAILBOX_MARK_READ = "markAsReadThreadWithThreadIdentifier"

/**
 * Mailbox's mark-read: an instance method taking (Number, String, String) and answering an object,
 * that loads the API's name and reads one of MailboxFeature's own fields.
 */
internal fun isMailboxMarkRead(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || !method.returnType.startsWith("L")) return false
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (parameters != listOf("Ljava/lang/Number;", "Ljava/lang/String;", "Ljava/lang/String;")) return false
    val code = method.implementation?.instructions ?: return false
    return code.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == MAILBOX_MARK_READ } &&
        code.any { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass == MAILBOX_FEATURE }
}

/** The register the method's one return-object hands back. Throws when there isn't exactly one. */
internal fun Method.futureRegister(): Int {
    val returns = implementation!!.instructions.filter { it.opcode == Opcode.RETURN_OBJECT }
    return (returns.singleOrNull() as? OneRegisterInstruction)?.registerA
        ?: throw PatchException("$READ_RECEIPTS_PATCH: $definingClass->$name has ${returns.size} return-object, expected one")
}

/**
 * Where the future is in hand: the index right after the move-result that first writes the
 * register the method's one return-object hands back, or, where the method builds the future
 * itself (586), right after the constructor call on the new instance of its return type. Throws
 * when the method has another shape.
 */
internal fun Method.futureReadyIndex(): Int {
    val code = implementation!!.instructions.toList()
    val future = futureRegister()
    val made = code.indexOfFirst {
        (it.opcode == Opcode.MOVE_RESULT_OBJECT || it.opcode == Opcode.NEW_INSTANCE) && (it as OneRegisterInstruction).registerA == future
    }
    if (made < 0) throw PatchException("$READ_RECEIPTS_PATCH: $definingClass->$name never moves its future into v$future")
    if (code[made].opcode == Opcode.NEW_INSTANCE) return builtFutureIndex(code, made, future)
    // The first object moved into that register has to be the future itself: a call answering the
    // method's own return type. Anything else handed back would fail Android's verifier.
    val maker = ((code.getOrNull(made - 1) as? ReferenceInstruction)?.reference as? MethodReference)?.returnType
    if (maker != returnType) {
        throw PatchException("$READ_RECEIPTS_PATCH: $definingClass->$name first fills v$future from a call answering $maker, not $returnType")
    }
    return made + 1
}

/**
 * 586 (`LX/0FDK;->A0A`) builds the future with new-instance and its constructor. It's in hand right
 * after that constructor runs on [future], with nothing writing the register in between, and only
 * when the new instance is of the method's own return type, so the verifier takes it as the answer.
 */
private fun Method.builtFutureIndex(code: List<Instruction>, made: Int, future: Int): Int {
    val type = ((code[made] as ReferenceInstruction).reference as TypeReference).type
    if (type != returnType) {
        throw PatchException("$READ_RECEIPTS_PATCH: $definingClass->$name first fills v$future with a new $type, not $returnType")
    }
    val after = code.drop(made + 1)
    val built = after.indexOfFirst { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        val receiver = when (instruction) {
            is FiveRegisterInstruction -> instruction.registerC
            is RegisterRangeInstruction -> instruction.startRegister
            else -> null
        }
        (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            call?.name == "<init>" && call.definingClass == returnType && receiver == future
    }
    val overwritten = after.take(if (built < 0) after.size else built).any {
        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == future
    }
    if (built < 0 || overwritten) {
        throw PatchException("$READ_RECEIPTS_PATCH: $definingClass->$name never builds the new $type in v$future")
    }
    return made + 1 + built + 1
}

/**
 * Makes the mark-read hand back its future at once, before it posts the call, while the extension
 * holds reads back. Borrows one local nothing reads afterwards.
 */
internal fun MutableMethod.holdBackRead() {
    val at = futureReadyIndex()
    val future = futureRegister()
    val answer = freeLocalsAt(READ_RECEIPTS_PATCH, at, 1, highest = 255).single()
    addInstructionsWithLabels(
        at,
        """
            invoke-static { }, $HOLDS_CHAT_READ
            move-result v$answer
            if-eqz v$answer, :send
            return-object v$future
        """,
        ExternalLabel("send", getInstruction(at)),
    )
}
