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
 */

/** Android's clipboard write. */
internal const val SET_PRIMARY_CLIP = "Landroid/content/ClipboardManager;->setPrimaryClip(Landroid/content/ClipData;)V"

/** The extension's stand-in for [SET_PRIMARY_CLIP], taking the ClipboardManager and the clip. */
internal const val TEXT_COPY =
    "$EXTENSION_PACKAGE/reels/PostTextSelect;->copy(Landroid/content/ClipboardManager;Landroid/content/ClipData;)V"

/** The fewest of Facebook's methods a build writes the clipboard from before the patch trusts it found them. */
internal const val FEWEST_CLIPBOARD_WRITERS = 10

/** Whether [instruction] is a call of [SET_PRIMARY_CLIP], in either invoke form. */
internal fun isClipboardWrite(instruction: Instruction) =
    (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
        (instruction as ReferenceInstruction).reference.toString() == SET_PRIMARY_CLIP

private fun writesClipboard(method: Method) = method.implementation?.instructions?.any(::isClipboardWrite) == true

/**
 * The classes outside the extension with a method that writes the clipboard. The extension's own
 * classes make the real write, so they're left out. Refused when fewer than
 * [FEWEST_CLIPBOARD_WRITERS] turn up, since then the copies have moved somewhere this doesn't see.
 */
internal fun BytecodePatchContext.clipboardWriters(): List<String> {
    val writers = mutableListOf<String>()
    classDefForEachReferencing(names = listOf("setPrimaryClip")) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        if (classDef.methods.any(::writesClipboard)) writers += classDef.type
    }
    if (writers.size < FEWEST_CLIPBOARD_WRITERS) {
        throw PatchException("$GESTURE_PATCH: found ${writers.size} classes writing the clipboard, expected $FEWEST_CLIPBOARD_WRITERS or more")
    }
    return writers
}

/** Sends every clipboard write in [writers] to [TEXT_COPY]. Answers how many calls it sent. */
internal fun BytecodePatchContext.sendClipboardWrites(writers: List<String>): Int =
    writers.sumOf { type -> mutableClassDefBy(type).methods.filter(::writesClipboard).sumOf { it.sendClipboardWrites() } }

/** Sends each clipboard write this method makes to [TEXT_COPY], in the same registers. Answers how many. */
internal fun MutableMethod.sendClipboardWrites(): Int {
    val sites = implementation!!.instructions.withIndex().filter { (_, instruction) -> isClipboardWrite(instruction) }
    sites.asReversed().forEach { (index, instruction) -> replaceInstruction(index, "${registersOf(instruction)}, $TEXT_COPY") }
    return sites.size
}

/** An `invoke-static` naming the two registers [instruction] passes, in its form. */
private fun MutableMethod.registersOf(instruction: Instruction): String = when (instruction) {
    is RegisterRangeInstruction ->
        "invoke-static/range { v${instruction.startRegister} .. v${instruction.startRegister + instruction.registerCount - 1} }"
    is FiveRegisterInstruction -> "invoke-static { v${instruction.registerC}, v${instruction.registerD} }"
    else -> throw PatchException("$GESTURE_PATCH: $definingClass->$name writes the clipboard in an unexpected form")
}
