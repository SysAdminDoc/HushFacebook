/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The post date under a reel's or a full-screen video's author (#296). Read from 585, 2026-10-31,
 * arm64 and armeabi-v7a; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - The line under the author is built by a static helper (arm64 LX/07pM;->A02, v7 LX/07pR;->A02)
 *   that the reel footer's builder (LX/09bx;->A17, v7 LX/09c2;->A17) calls. It takes the session
 *   first and a flag last, and answers null, which draws nothing, unless a server-side switch says yes.
 * - That switch is one static call (LX/0ACj;->A0t, v7 LX/0ACo;->A0t) handed a lazy config, a
 *   Function0 and a field number, whose boolean comes back through move-result and is compared
 *   with 1. Facebook's answer is no for most accounts.
 * - Past it the helper reads the video's creation_time through TreeJNI.getTimeValue and formats it
 *   with the same (Context, FbUserSession, long) formatter feed headers use. The hook hands the
 *   switch's answer to the extension, which says yes while the setting is on.
 */

internal const val SHOW_POST_DATE = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->showsPostDate(Z)Z"

/** The video's tree key for when it was posted. It's the name's String.hashCode(). */
internal val CREATION_TIME = "creation_time".hashCode()

private const val PATCH = "Clean up Reels"
private const val TREE = "Lcom/facebook/graphservice/tree/TreeJNI;"
private const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private val CONSTANTS = setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16)

private val Method.code get() = implementation?.instructions?.toList().orEmpty()

private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)

/** Where [method] reads [CREATION_TIME] through TreeJNI.getTimeValue, or -1 unless it does exactly once. */
internal fun creationTimeRead(method: Method): Int {
    val code = method.code
    val reads = code.indices.filter { at ->
        val call = code[at] as? FiveRegisterInstruction ?: return@filter false
        val reference = (call as ReferenceInstruction).reference as? MethodReference ?: return@filter false
        if (call.opcode != Opcode.INVOKE_VIRTUAL || reference.definingClass != TREE || reference.name != "getTimeValue" ||
            reference.returnType != "J" || reference.params() != listOf("I") || call.registerCount != 2
        ) return@filter false
        val writers = method.writersReaching(at, call.registerD)
        writers.isNotEmpty() && writers.all {
            it >= 0 && code[it].opcode in CONSTANTS && (code[it] as NarrowLiteralInstruction).narrowLiteral == CREATION_TIME
        }
    }
    return reads.singleOrNull() ?: -1
}

/**
 * The server switch's call in [method] before [read]: invoke-static of a method taking an object, a
 * Function0 and an int and answering a boolean, with a move-result after it. Null unless there's one.
 */
internal fun postDateGate(method: Method, read: Int): Int? {
    val code = method.code
    return (0 until read).filter { at ->
        val reference = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        val params = reference.params()
        (code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE) && reference.returnType == "Z" && params.size == 3 &&
            params[0].startsWith("L") && params[1] == FUNCTION0 && params[2] == "I" &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT
    }.singleOrNull()
}

/**
 * Whether [method] is the reel's post date line: static, the session first and a boolean last, an
 * object back, one [CREATION_TIME] read formatted by a (Context, FbUserSession, long) to String
 * call, and one [postDateGate] ahead of the read.
 */
internal fun isReelPostDate(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || !method.returnType.startsWith("L")) return false
    val params = method.parameterTypes.map(CharSequence::toString)
    if (params.size < 3 || params.first() != USER_SESSION || params.last() != "Z") return false
    val read = creationTimeRead(method)
    if (read < 0) return false
    val formats = method.code.drop(read).any {
        val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
        reference != null && reference.returnType == "Ljava/lang/String;" &&
            reference.params() == listOf("Landroid/content/Context;", USER_SESSION, "J")
    }
    return formats && postDateGate(method, read) != null
}

/** Hands the line's server switch to the extension, which says yes while the setting is on. */
internal fun BytecodePatchContext.showReelPostDate() {
    val found = mutableListOf<Method>()
    classDefForEachReferencing(names = listOf("getTimeValue")) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(found, ::isReelPostDate)
    }
    val helper = found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one post date line reading creation_time behind a server switch, found ${found.size}")
    val method = mutableClassDefBy(helper.definingClass).methods.single {
        it.name == helper.name && it.parameterTypes.map(CharSequence::toString) == helper.parameterTypes.map(CharSequence::toString)
    }
    val gate = postDateGate(method, creationTimeRead(method))!!
    val result = (method.code[gate + 1] as OneRegisterInstruction).registerA
    method.addInstructions(
        gate + 2,
        """
            invoke-static/range { v$result .. v$result }, $SHOW_POST_DATE
            move-result v$result
        """,
    )
}
