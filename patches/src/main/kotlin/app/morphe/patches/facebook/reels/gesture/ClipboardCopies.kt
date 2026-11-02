/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.gesture

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Facebook copies a post's text on its own long press, and Select part of a post's text opens that
 * text in a box (#294). So the hold doesn't also put the whole text on the clipboard (#342), every
 * clipboard write Facebook makes goes through the extension's stand-in, which takes the same two
 * arguments in the same registers and passes every copy but a hold's on to Android. 585 makes them
 * from about forty methods, each a plain setPrimaryClip on the ClipboardManager it holds. Kept
 * names only: Android's class and method, which Redex leaves alone.
 *
 * Facebook then says "Text copied to clipboard", which a copy the box took would make a lie. It's no
 * Android toast but a popup Facebook draws itself (arm64 LX/05ZM;, read from 585 on 2026-11-02). The
 * hold's copy (LX/03BL;->A0D in both builds) calls a static clipboard helper (LX/0TYT;->A02), builds
 * the popup with a call taking a CallerContext (a kept name) and shows it with an instance call
 * taking nothing on what that answered. In front of each such show after a copy, the patch asks the
 * extension whether the copy was kept off, and jumps past the show when it was.
 */

/** Android's clipboard write. */
internal const val SET_PRIMARY_CLIP = "Landroid/content/ClipboardManager;->setPrimaryClip(Landroid/content/ClipData;)V"

/** The extension's stand-in for [SET_PRIMARY_CLIP], taking the ClipboardManager and the clip. */
internal const val TEXT_COPY =
    "$EXTENSION_PACKAGE/reels/PostTextSelect;->copy(Landroid/content/ClipboardManager;Landroid/content/ClipData;)V"

/** The fewest of Facebook's methods a build writes the clipboard from before the patch trusts it found them. */
internal const val FEWEST_CLIPBOARD_WRITERS = 10

/** The kept name of what Facebook shows its popups with. */
internal const val CALLER_CONTEXT = "Lcom/facebook/common/callercontext/CallerContext;"

/** The extension's check, in front of a "copied" popup, for whether the copy it announces was kept off. */
internal const val DROPS_POPUP = "$EXTENSION_PACKAGE/reels/PostTextSelect;->dropsPopup()Z"

/** How many instructions past its copy a method may show the popup saying so (12 in 585's hold copy). */
internal const val POPUP_REACH = 16

/** Whether [instruction] is a call of [SET_PRIMARY_CLIP], in either invoke form. */
internal fun isClipboardWrite(instruction: Instruction) =
    (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
        (instruction as ReferenceInstruction).reference.toString() == SET_PRIMARY_CLIP

private fun Method.makes(call: (Instruction) -> Boolean) = implementation?.instructions?.any(call) == true

/**
 * The classes outside the extension with a method that writes the clipboard. The extension's own
 * classes make the real write, so they're left out. Refused when fewer than
 * [FEWEST_CLIPBOARD_WRITERS] turn up, since then the copies have moved somewhere this doesn't see.
 */
internal fun BytecodePatchContext.clipboardWriters(): List<String> {
    val found = mutableListOf<String>()
    classDefForEachReferencing(names = listOf("setPrimaryClip")) { classDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES) && classDef.methods.any { it.makes(::isClipboardWrite) }) found += classDef.type
    }
    if (found.size < FEWEST_CLIPBOARD_WRITERS) {
        throw PatchException("$GESTURE_PATCH: found ${found.size} classes writing the clipboard, expected $FEWEST_CLIPBOARD_WRITERS or more")
    }
    return found
}

/** Sends every clipboard write in [writers] to [TEXT_COPY]. Answers how many calls it sent. */
internal fun BytecodePatchContext.sendClipboardWrites(writers: List<String>): Int =
    writers.sumOf { type ->
        mutableClassDefBy(type).methods.filter { it.makes(::isClipboardWrite) }.sumOf { it.sendCalls(::isClipboardWrite, TEXT_COPY) }
    }

/**
 * One "copied" popup Facebook shows right after a copy: the method, the index of the show, and the
 * local the check in front of it borrows.
 */
internal class CopyPopup(val method: Method, val show: Int, val borrowed: Int)

/**
 * Every place a method calls one of the static clipboard helpers in [writers] and then, within
 * [POPUP_REACH] instructions, builds a popup with a call taking only a CallerContext and shows what
 * that answered with an instance call taking nothing. Refused when none turns up. Read before
 * [sendClipboardWrites] changes the helpers.
 */
internal fun BytecodePatchContext.copyPopups(writers: List<String>): List<CopyPopup> {
    val helpers = writers.flatMap { type ->
        classDefBy(type).methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.makes(::isClipboardWrite) }
    }.mapTo(mutableSetOf()) { it.signature() }
    val found = mutableListOf<CopyPopup>()
    classDefForEachReferencing(types = writers) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        for (method in classDef.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            for ((copy, instruction) in code.withIndex()) {
                if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) continue
                if ((instruction as ReferenceInstruction).reference.toString() !in helpers) continue
                val show = popupShowAfter(code, copy) ?: continue
                val borrowed = method.freeLocalsAt(GESTURE_PATCH, show, 1, targets = listOf(show + 1)).single()
                found += CopyPopup(method, show, borrowed)
            }
        }
    }
    if (found.isEmpty()) throw PatchException("$GESTURE_PATCH: found no popup shown after a clipboard copy")
    return found
}

/** The index of the popup show within [POPUP_REACH] past the copy at [copy] in [code], or null. */
private fun popupShowAfter(code: List<Instruction>, copy: Int): Int? {
    for (made in copy + 1..minOf(copy + POPUP_REACH, code.size - 3)) {
        val build = (code[made] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (build.parameterTypes.map(CharSequence::toString) != listOf(CALLER_CONTEXT)) continue
        val result = code[made + 1]
        val show = code[made + 2]
        if (result.opcode != Opcode.MOVE_RESULT_OBJECT || show.opcode != Opcode.INVOKE_VIRTUAL) continue
        val shown = (show as ReferenceInstruction).reference as MethodReference
        val popup = (result as OneRegisterInstruction).registerA
        if (shown.definingClass == build.returnType && shown.parameterTypes.isEmpty() && shown.returnType == "V" &&
            (show as FiveRegisterInstruction).registerCount == 1 && show.registerC == popup
        ) {
            return made + 2
        }
    }
    return null
}

/** Puts the extension's check in front of each show in [popups], jumping past the show on a yes. */
internal fun BytecodePatchContext.guardCopyPopups(popups: List<CopyPopup>) {
    for (popup in popups.sortedByDescending { it.show }) {
        mutableClassDefBy(popup.method.definingClass).findMutableMethodOf(popup.method).apply {
            addInstructionsWithLabels(
                popup.show,
                """
                    invoke-static { }, $DROPS_POPUP
                    move-result v${popup.borrowed}
                    if-nez v${popup.borrowed}, :shown
                """,
                ExternalLabel("shown", getInstruction(popup.show + 1)),
            )
        }
    }
}

private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

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
