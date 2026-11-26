/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.settings.LOGO_CLICK_CALL
import app.morphe.patches.facebook.misc.settings.LOGO_TOUCH_STAND_IN
import app.morphe.patches.facebook.misc.settings.WORDMARK_NAVIGATION_BAR
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Where the home header's logo can be dressed: right after Facebook loads its stock wordmark into
 * the logo that the settings dependency gave its touch listener. Home header text and the top
 * bar's hide-the-logo switch both hook there.
 */

private val LOGO_BUILDER_PARAMETERS = listOf("Landroid/content/Context;", WORDMARK_NAVIGATION_BAR)

private fun MethodReference.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** Follows unambiguous object moves back to the register that entered the method. */
private fun Method.objectOriginAt(index: Int, register: Int): Int? {
    val instructions = implementation?.instructions?.toList() ?: return null
    var before = index
    var current = register
    val seen = mutableSetOf<Pair<Int, Int>>()
    while (seen.add(before to current)) {
        val writers = writersReaching(before, current)
        if (writers == listOf(-1)) return current
        val writer = writers.singleOrNull()?.takeIf { it >= 0 } ?: return null
        val move = instructions[writer] as? TwoRegisterInstruction ?: return null
        if (move.opcode !in OBJECT_MOVES || move.registerA != current) return null
        before = writer
        current = move.registerB
    }
    return null
}

/** The stock wordmark load after Facebook gives the logo its touch listener, and the logo register. */
internal data class LogoDressSite(val stockLoad: Int, val logo: Int)

/**
 * Finds the logo after the settings dependency has sent its touch listener through SettingsEntry.
 * Facebook loads the stock wordmark a few instructions later. Dressing after that load keeps the
 * stock path intact and prevents it from immediately overwriting the dressing. [patch] names the
 * patch in the messages.
 */
internal fun logoDressSite(builder: Method, patch: String): LogoDressSite {
    val where = "${builder.definingClass}->${builder.name}"
    val implementation = builder.implementation
        ?: throw PatchException("$patch: $where, the method building the Facebook logo, has no body")
    if (!AccessFlags.STATIC.isSet(builder.accessFlags) ||
        builder.parameterTypes.map(Any::toString) != LOGO_BUILDER_PARAMETERS
    ) {
        throw PatchException("$patch: $where no longer has the expected static logo-builder parameters")
    }
    val builderArguments = (
        implementation.registerCount - LOGO_BUILDER_PARAMETERS.size until implementation.registerCount
    ).toList()
    val code = implementation.instructions.toList()
    val touches = code.indices.filter { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        call?.signature() == LOGO_TOUCH_STAND_IN
    }
    val touch = touches.singleOrNull()
        ?: throw PatchException("$patch: expected one settings touch stand-in in $where, found ${touches.size}")
    val touchRegisters = code[touch].registers()
    if (touchRegisters.size != 2) {
        throw PatchException("$patch: the settings touch stand-in in $where has ${touchRegisters.size} registers")
    }
    val click = code.getOrNull(touch - 1)
    val clickCall = (click as? ReferenceInstruction)?.reference as? MethodReference
    val clickRegisters = click?.registers().orEmpty()
    if (clickCall?.signature() != LOGO_CLICK_CALL || clickRegisters.firstOrNull() != touchRegisters.first()) {
        throw PatchException("$patch: the settings touch stand-in in $where no longer follows the logo's tap")
    }

    val stockLoads = ((touch + 1)..minOf(touch + 12, code.lastIndex)).filter { index ->
        val instruction = code[index]
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@filter false
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        val registers = instruction.registers()
        call.definingClass == WORDMARK_NAVIGATION_BAR && call.returnType == "V" &&
            call.parameterTypes.map(Any::toString) == LOGO_BUILDER_PARAMETERS &&
            registers.size == builderArguments.size &&
            registers.map { builder.objectOriginAt(index, it) } == builderArguments
    }
    val stockLoad = stockLoads.singleOrNull() ?: throw PatchException(
        "$patch: expected one stock wordmark load after the logo's touch listener in $where, found ${stockLoads.size}",
    )
    return LogoDressSite(stockLoad, touchRegisters.first())
}

/** Hands the logo to [call] after Facebook has loaded the stock wordmark. */
internal fun MutableMethod.dressLogoAfter(site: LogoDressSite, call: String) {
    addInstructions(
        site.stockLoad + 1,
        "invoke-static/range { v${site.logo} .. v${site.logo} }, $call",
    )
}
