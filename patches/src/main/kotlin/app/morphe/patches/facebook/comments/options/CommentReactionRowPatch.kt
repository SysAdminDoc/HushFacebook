/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.EnumSet

/*
 * The row under a comment, read from 587 on 2026-11-16 (#241). The obfuscated names are for
 * reviewers; the code finds the spot by a kept string and the shape around it.
 *
 * The plugin that draws a comment's actions with its reactions (587 arm64 `LX/0B6J;->A08`) asks one
 * MobileConfig boolean (587 id 0x810904002138f6, through a static reader that takes the id first,
 * `LX/04UR;->A1O(JLjava/lang/Object;Z)Z`). On a yes it builds the component that keeps the name
 * "CommentActionsWithSimplifiedReactionsComponent": Reply, then only the top reaction and the
 * count, with Like and the thumbs-down as buttons. On a no it builds the older row, Like and Reply
 * with every reaction's icon beside the count. Facebook hands the yes to some accounts and not
 * others, Hushfacebook paused or not. The hook goes right after that read: the extension answers no
 * while the switch is on.
 *
 * The component class is the one loading the kept name. In each method that makes one with
 * `new-instance`, the last branch before it tests a register, and the read is the static boolean
 * call whose `move-result` last wrote that register, passed an id a `const-wide` loaded just before.
 * Four more methods make the component in 587 (arm64 `LX/0Cud;->A19`, `LX/04jx;->A0y` and two
 * renders), and each decides on its own grounds with no such read, so they're left as they are.
 * MobileConfig ids change between builds, so the id is read from the spot and never written down.
 */

/** The name the newer row's component keeps as a string. */
internal const val TOP_REACTION_COMPONENT = "CommentActionsWithSimplifiedReactionsComponent"

internal const val TOP_REACTION_ONLY = "$COMMENT_SHEET_OPTIONS->topReactionOnly(Z)Z"

private const val PATCH = "Comment sheet options (every reaction on comments)"

/** The most instructions from the id's load to the read taking it. */
private const val READ_REACH = 3

private val BRANCHES = EnumSet.of(Opcode.IF_EQZ, Opcode.IF_NEZ)
private val STATIC_CALLS = EnumSet.of(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val WIDE_CONSTS = EnumSet.of(Opcode.CONST_WIDE, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_HIGH16)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Facebook's pick of the newer row goes through the extension. Nameless, so Comment sheet options
 * carries it and its fixture tests run it on its own. It finds every spot before changing anything
 * and refuses unless there's at least one, all reading the same id.
 */
internal val commentReactionRowPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        hookTopReactionReads()
    }
}

/** One read of the newer row's flag: the `move-result` that takes its answer, the register, and the id. */
internal data class TopReactionRead(val method: Method, val result: Int, val register: Int, val flag: Long)

internal fun BytecodePatchContext.hookTopReactionReads() {
    val classes = mutableListOf<ClassDef>()
    classDefForEach { classDef: ClassDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES)) classes += classDef
    }
    // Later spots first, so an earlier one's index still holds when two share a method.
    for (read in topReactionReads(classes).sortedByDescending { it.result }) {
        val method = mutableClassDefBy(read.method.definingClass).findMutableMethodOf(read.method)
        method.addInstructions(
            read.result + 1,
            """
                invoke-static/range { v${read.register} .. v${read.register} }, $TOP_REACTION_ONLY
                move-result v${read.register}
            """,
        )
    }
}

/** The registers a call passes, in order. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/**
 * Every read of the newer row's flag in [classes]: the component class is the one whose code loads
 * [TOP_REACTION_COMPONENT], and each `new-instance` of it is checked as the comment above says.
 */
internal fun topReactionReads(classes: Iterable<ClassDef>): List<TopReactionRead> {
    val components = classes.filter { owner -> owner.methods.any { holdsString(it, TOP_REACTION_COMPONENT) } }.map { it.type }.toSet()
    if (components.size != 1) refuse("expected one class named $TOP_REACTION_COMPONENT, found ${components.size}")
    val component = components.single()

    val found = linkedSetOf<TopReactionRead>()
    for (classDef in classes) {
        for (method in classDef.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            for ((index, instruction) in code.withIndex()) {
                if (instruction.opcode != Opcode.NEW_INSTANCE ||
                    ((instruction as ReferenceInstruction).reference as TypeReference).type != component
                ) continue
                val branch = (index - 1 downTo 0).firstOrNull { code[it].opcode in BRANCHES } ?: continue
                val tested = (code[branch] as OneRegisterInstruction).registerA
                val writer = (branch - 1 downTo 1).firstOrNull { writesRegister(code[it], tested) } ?: continue
                if (code[writer].opcode != Opcode.MOVE_RESULT) continue
                val read = code[writer - 1]
                val call = (read as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (read.opcode !in STATIC_CALLS || call.returnType != "Z") continue
                val passed = read.registers().zipWithNext()
                val flag = (writer - 2 downTo maxOf(0, writer - 1 - READ_REACH)).firstNotNullOfOrNull { at ->
                    val load = code[at]
                    if (load.opcode !in WIDE_CONSTS) return@firstNotNullOfOrNull null
                    val id = (load as OneRegisterInstruction).registerA
                    if (passed.contains(id to id + 1)) (load as WideLiteralInstruction).wideLiteral else null
                } ?: continue
                found += TopReactionRead(method, writer, tested, flag)
            }
        }
    }
    if (found.isEmpty()) refuse("no flag read decides on $TOP_REACTION_COMPONENT")
    val flags = found.map { it.flag }.toSet()
    if (flags.size != 1) refuse("the reads deciding on $TOP_REACTION_COMPONENT take ${flags.size} ids")
    return found.toList()
}
