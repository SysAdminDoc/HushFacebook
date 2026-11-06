/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.watchhistory

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.util.literalReads
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Where Facebook sends the reels you watched, on the 577 and 580 builds.
 *
 * The Reels viewer queues the id of each reel you watch (580 queues the post it came in, too) in a
 * batcher, and the batcher's flush sends the queue as one GraphQL mutation,
 * FbShortsSeenStateMutation, persisted query 234674973 on both builds. Its input is `video_ids`
 * (and `post_ids` on 580) and nothing else: it names no viewer, and nobody else ever sees it. It's
 * the record Facebook ranks your Reels feed with. The flush runs when the queue reaches a size
 * Facebook's config sets, and when the Reels fragment pauses.
 *
 * The flush empties the queue, builds the request, and hands a runnable to an executor. Redex keeps
 * that runnable's name, FbShortsSeenStateMutationHelper$sendFbShortsSeenStateMutation$1, and its
 * run() is what gives the request to Facebook's GraphQL layer. Should the send fail, the runnable's
 * callback takes the ids out of an in-memory list of ones already sent, so they can queue again;
 * a send held back never calls it, so the ids stay counted as sent and don't queue again.
 *
 * The batcher and its flush are renamed every build (580 and 577 each call it A00 of a different
 * class), so the flush is found by the two literals it loads and the shape of its one hand-over.
 * 584's Redex merged the mutation's input into a class many GraphQL inputs share (`LX/7nD`), so
 * `video_ids` moved into the constructor the flush builds its input with.
 */
internal const val PATCH = "Don't send reel watch history"

/** The mutation's name: the flush loads it to build the query. */
internal const val SEEN_STATE_MUTATION = "FbShortsSeenStateMutation"

/** The input field every build fills with the watched reels' ids, in the flush or its input's constructor. */
internal const val VIDEO_IDS = "video_ids"

/** The name Redex keeps on the runnable the flush hands its executor. */
internal const val SEEN_STATE_SEND = "FbShortsSeenStateMutationHelper\$sendFbShortsSeenStateMutation\$1"

internal const val EXECUTOR = "Ljava/util/concurrent/Executor;"
internal const val RUNNABLE = "Ljava/lang/Runnable;"

/** The extension's stand-in for the hand-over: static, the executor first, then the runnable. */
internal const val SEND = "$EXTENSION_PACKAGE/reels/ReelWatchHistory;->send($EXECUTOR$RUNNABLE)V"

/** Whether this instruction hands a runnable to an executor: `Executor.execute(Runnable)`. */
internal fun Instruction.isExecute(): Boolean {
    if (opcode != Opcode.INVOKE_INTERFACE && opcode != Opcode.INVOKE_INTERFACE_RANGE) return false
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return call.definingClass == EXECUTOR && call.name == "execute" && call.returnType == "V" &&
        call.parameterTypes.map { it.toString() } == listOf(RUNNABLE)
}

/** The registers a two-register call reads, in order, whether it's written as a range or not. */
internal fun Instruction.callRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Instruction.loads(string: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string == string

/**
 * The index where the flush hands the seen-state send to its executor, or null when [method]
 * isn't that flush: a void instance method that fills [VIDEO_IDS] (loading it, or through the
 * constructor of the input it builds before naming the mutation) and makes one `Executor.execute`
 * call after it loads the mutation's name, whose runnable is a new instance of the Runnable Redex
 * left [SEEN_STATE_SEND] on and reaches the call by no other way. [classOf] looks a class up by its
 * type.
 *
 * The flush takes nothing where the helper's send is inlined into it (every build before 585's
 * Android 9 one, and 585's other builds). 585's Android 9
 * build keeps the helper's send apart (LX/09pq;->A00, taking the session, the two id lists and the
 * callback), and the flush calls it once with the queue already emptied, so there the send point
 * is in the helper and takes arguments. Only that send builds its own `$1` runnable, so the
 * runnable's name is what says this is the method, whatever it takes.
 */
internal fun sendPoint(method: Method, classOf: (String) -> ClassDef?): Int? {
    if (method.returnType != "V" || AccessFlags.STATIC.isSet(method.accessFlags)) return null
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val named = instructions.indices.singleOrNull { instructions[it].loads(SEEN_STATE_MUTATION) } ?: return null
    if (!holdsString(method, VIDEO_IDS) && (0 until named).none { instructions[it].fillsVideoIds(classOf) }) return null
    val send = instructions.indices.singleOrNull { instructions[it].isExecute() } ?: return null
    if (send < named) return null
    val runnable = instructions[send].callRegisters().getOrNull(1) ?: return null

    // Every new instance whose value can reach the call: exactly one, in the runnable's register.
    val origins = instructions.indices.filter { index ->
        instructions[index].opcode == Opcode.NEW_INSTANCE && send in method.literalReads(index)
    }
    val origin = origins.singleOrNull() ?: return null
    if ((instructions[origin] as OneRegisterInstruction).registerA != runnable) return null
    val type = ((instructions[origin] as ReferenceInstruction).reference as TypeReference).type
    val sender = classOf(type) ?: return null
    if (redexOriginalName(sender) != SEEN_STATE_SEND || sender.interfaces.none { it.toString() == RUNNABLE }) return null
    return send
}

/** Whether this calls a constructor, found through [classOf], that loads [VIDEO_IDS]: 584's merged input. */
private fun Instruction.fillsVideoIds(classOf: (String) -> ClassDef?): Boolean {
    if (opcode != Opcode.INVOKE_DIRECT && opcode != Opcode.INVOKE_DIRECT_RANGE) return false
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (call.name != "<init>") return false
    val parameters = call.parameterTypes.map { it.toString() }
    val constructor = classOf(call.definingClass)?.methods?.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map { type -> type.toString() } == parameters
    } ?: return false
    return holdsString(constructor, VIDEO_IDS)
}

/**
 * Puts the extension's [SEND] where the flush hands its runnable to the executor. The stand-in
 * reads the same two registers in the same order and is the same size, so nothing around it moves
 * and no branch changes. A range call stays a range call.
 */
internal fun MutableMethod.withholdSendAt(index: Int) {
    val call = implementation?.instructions?.elementAtOrNull(index)
        ?: throw PatchException("$PATCH: $definingClass->$name has no instruction $index")
    if (!call.isExecute()) throw PatchException("$PATCH: instruction $index of $definingClass->$name isn't Executor.execute")
    val registers = call.callRegisters()
    if (registers.size != 2) throw PatchException("$PATCH: $definingClass->$name hands over ${registers.size} registers, not 2")
    val invoke = if (call is RegisterRangeInstruction) {
        "invoke-static/range { v${registers[0]} .. v${registers[1]} }, $SEND"
    } else {
        "invoke-static { v${registers[0]}, v${registers[1]} }, $SEND"
    }
    replaceInstruction(index, invoke)
}
