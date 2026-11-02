/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.gesture

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

/*
 * Facebook copies a post's text on its own long press, and Select part of a post's text opens that
 * text in a box (#294). So the hold doesn't also put the whole text on the clipboard (#342), every
 * clipboard write Facebook makes goes through the extension's stand-in, which takes the same two
 * arguments in the same registers and passes every copy but a hold's on to Android. 585 makes them
 * from about forty methods, each a plain setPrimaryClip on the ClipboardManager it holds. Kept
 * names only: Android's class and method, which Redex leaves alone.
 *
 * Facebook then says "Text copied to clipboard" in a toast of its own, which a copy the box took
 * would make a lie. So every Toast.show call outside the extension (66 in 585 arm64) goes to a
 * second stand-in, which drops the one toast that follows such a copy and shows every other.
 */

/** Android's clipboard write. */
internal const val SET_PRIMARY_CLIP = "Landroid/content/ClipboardManager;->setPrimaryClip(Landroid/content/ClipData;)V"

/** The extension's stand-in for [SET_PRIMARY_CLIP], taking the ClipboardManager and the clip. */
internal const val TEXT_COPY =
    "$EXTENSION_PACKAGE/reels/PostTextSelect;->copy(Landroid/content/ClipboardManager;Landroid/content/ClipData;)V"

/** The fewest of Facebook's methods a build writes the clipboard from before the patch trusts it found them. */
internal const val FEWEST_CLIPBOARD_WRITERS = 10

/** Android's toast. */
internal const val TOAST_SHOW = "Landroid/widget/Toast;->show()V"

/** The extension's stand-in for [TOAST_SHOW], taking the toast. */
internal const val TOAST_STAND_IN = "$EXTENSION_PACKAGE/reels/PostTextSelect;->toast(Landroid/widget/Toast;)V"

/** The fewest of Facebook's classes a build shows toasts from before the patch trusts it found them. */
internal const val FEWEST_TOAST_SHOWERS = 10

/** Whether [instruction] is a call of [SET_PRIMARY_CLIP], in either invoke form. */
internal fun isClipboardWrite(instruction: Instruction) = isVirtualCall(instruction, SET_PRIMARY_CLIP)

/** Whether [instruction] is a call of [TOAST_SHOW], in either invoke form. A subclass's super call isn't one. */
internal fun isToastShow(instruction: Instruction) = isVirtualCall(instruction, TOAST_SHOW)

private fun isVirtualCall(instruction: Instruction, method: String) =
    (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
        (instruction as ReferenceInstruction).reference.toString() == method

private fun Method.makes(call: (Instruction) -> Boolean) = implementation?.instructions?.any(call) == true

/**
 * The classes outside the extension with a method that writes the clipboard. The extension's own
 * classes make the real write, so they're left out. Refused when fewer than
 * [FEWEST_CLIPBOARD_WRITERS] turn up, since then the copies have moved somewhere this doesn't see.
 */
internal fun BytecodePatchContext.clipboardWriters(): List<String> =
    callers(::isClipboardWrite, "writing the clipboard", FEWEST_CLIPBOARD_WRITERS) {
        classDefForEachReferencing(names = listOf("setPrimaryClip"), block = it)
    }

/** The classes outside the extension with a method that shows a toast, refused under [FEWEST_TOAST_SHOWERS]. */
internal fun BytecodePatchContext.toastShowers(): List<String> =
    callers(::isToastShow, "showing a toast", FEWEST_TOAST_SHOWERS) {
        classDefForEachReferencing(types = listOf("Landroid/widget/Toast;"), block = it)
    }

private fun callers(
    call: (Instruction) -> Boolean,
    what: String,
    fewest: Int,
    forEach: ((ClassDef) -> Unit) -> Unit,
): List<String> {
    val found = mutableListOf<String>()
    forEach { classDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES) && classDef.methods.any { it.makes(call) }) found += classDef.type
    }
    if (found.size < fewest) throw PatchException("$GESTURE_PATCH: found ${found.size} classes $what, expected $fewest or more")
    return found
}

/** Sends every clipboard write in [writers] to [TEXT_COPY]. Answers how many calls it sent. */
internal fun BytecodePatchContext.sendClipboardWrites(writers: List<String>): Int =
    sendCalls(writers, ::isClipboardWrite, TEXT_COPY)

/** Sends every toast shown in [showers] to [TOAST_STAND_IN]. Answers how many calls it sent. */
internal fun BytecodePatchContext.sendToastShows(showers: List<String>): Int =
    sendCalls(showers, ::isToastShow, TOAST_STAND_IN)

private fun BytecodePatchContext.sendCalls(types: List<String>, call: (Instruction) -> Boolean, standIn: String): Int =
    types.sumOf { type -> mutableClassDefBy(type).methods.filter { it.makes(call) }.sumOf { it.sendCalls(call, standIn) } }

/** Sends each [call] this method makes to [standIn], in the same registers. Answers how many. */
private fun MutableMethod.sendCalls(call: (Instruction) -> Boolean, standIn: String): Int {
    val sites = implementation!!.instructions.withIndex().filter { (_, instruction) -> call(instruction) }
    sites.asReversed().forEach { (index, instruction) -> replaceInstruction(index, "${registersOf(instruction)}, $standIn") }
    return sites.size
}

/** An `invoke-static` naming the registers [instruction] passes, in its form. */
private fun MutableMethod.registersOf(instruction: Instruction): String = when (instruction) {
    is RegisterRangeInstruction ->
        "invoke-static/range { v${instruction.startRegister} .. v${instruction.startRegister + instruction.registerCount - 1} }"
    is FiveRegisterInstruction ->
        listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG)
            .take(instruction.registerCount).joinToString(", ", "invoke-static { ", " }") { "v$it" }
    else -> throw PatchException("$GESTURE_PATCH: $definingClass->$name makes a call in an unexpected form")
}
