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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.EnumSet

/*
 * The row under a comment, read from 587 on 2026-11-16 (#241). The obfuscated names are for
 * reviewers; the code finds each spot by a kept string and the shape around it.
 *
 * The component that keeps the name "CommentActionsWithSimplifiedReactionsComponent" draws Reply,
 * then only the top reaction and the count, with Like and the thumbs-down as buttons. Where it isn't
 * picked, Facebook builds the older row, Like and Reply with every reaction's icon beside the count.
 * Facebook hands the newer row to some accounts and not others, Hushfacebook paused or not. Three
 * spots pick it, and the extension answers each with the older row while the switch is on:
 *
 * - The plugin that draws a comment's actions with its reactions (587 arm64 `LX/0B6J;->A08`) asks
 *   one MobileConfig boolean (587 id 0x810904002138f6, through a static reader that takes the id
 *   first, `LX/04UR;->A1O(JLjava/lang/Object;Z)Z`). In each method that makes the component with
 *   `new-instance`, the last branch before it tests a register, and this read is the static boolean
 *   call whose `move-result` last wrote that register, passed an id a `const-wide` loaded just
 *   before. The extension is asked through topReactionOnly.
 * - A feed post's inline comment actions (arm64 `LX/04jx;->A0y`, the method keeping the string
 *   "InlineCommentActionsComponent") test a static boolean passed no id there: a check (arm64
 *   `LX/04jg;->A03`) that asks a gate for the surface it's on (`A02`), then
 *   one MobileConfig boolean (587 id 0x010102fb001b239e, through `MobileConfigUnsafeContext.CLf`).
 *   An open post's comment list (arm64 `LX/0Cud;->A19`) and several renders around the row ask the
 *   same check, so the extension is asked right after its read, through topReactionCheck, and they
 *   all hear no together, the answer the check gives where the gate is closed. The check is the one
 *   such call whose code has a single id-fed boolean read with a gated branch before it.
 * - The comment list also reads a MobileConfig int past the same gate (587 id 0x010208d300000dc9):
 *   any of 1 to 3 changes how it lays out a comment's row, and 2 or 3 build the component whatever
 *   the check says. It's the one gate call in a method making the component whose answer an
 *   `if-eqz` tests straight away, falling through to an id's load and a static int read taking it.
 *   The extension is asked through topReactionLayout, and answers 0, the value the list takes where
 *   the gate is closed.
 *
 * The older row can still show a single icon. Its count pill (587 arm64 `LX/05lh;->render`, armv7
 * `LX/05lP;->render`) keeps as many icons as a count it trims its reaction list to with Math.min,
 * and when its own count is 0 it asks the same gate, then one MobileConfig boolean (587 id
 * 0x010102fb001d239f): yes keeps one icon, no or a closed gate keeps three. The read is the one
 * past the gate whose answer an `if-nez` tests between `const/4` 1 and `const/4` 3 into the
 * register Math.min later takes. The extension is asked through topReactionIcons, and answers no.
 *
 * One more method makes the component in 587 (arm64 `LX/0wse;->render`), a card in a carousel of
 * comments in the feed. It builds the component every time with no older row to pick instead, so
 * it's left as it is. MobileConfig ids change between builds, so each id is read from the spot and
 * never written down. The armv7 build has the same shapes under other names: the plugin
 * `LX/0B5l;->A08`, the inline actions `LX/04je;->A0y`, the check `LX/04jN;->A03` and the comment
 * list `LX/0Cu5;->A19`.
 */

/** The name the newer row's component keeps as a string. */
internal const val TOP_REACTION_COMPONENT = "CommentActionsWithSimplifiedReactionsComponent"

internal const val TOP_REACTION_ONLY = "$COMMENT_SHEET_OPTIONS->topReactionOnly(Z)Z"

internal const val TOP_REACTION_CHECK = "$COMMENT_SHEET_OPTIONS->topReactionCheck(Z)Z"

internal const val TOP_REACTION_LAYOUT = "$COMMENT_SHEET_OPTIONS->topReactionLayout(I)I"

internal const val TOP_REACTION_ICONS = "$COMMENT_SHEET_OPTIONS->topReactionIcons(Z)Z"

private const val MATH_MIN = "Ljava/lang/Math;->min(II)I"

private const val PATCH = "Comment sheet options (every reaction on comments)"

/** The most instructions from the id's load to the read taking it. */
private const val READ_REACH = 3

private val BRANCHES = EnumSet.of(Opcode.IF_EQZ, Opcode.IF_NEZ)
private val STATIC_CALLS = EnumSet.of(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val WIDE_CONSTS = EnumSet.of(Opcode.CONST_WIDE, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_HIGH16)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Facebook's picks of the newer row go through the extension. Nameless, so Comment sheet options
 * carries it and its fixture tests run it on its own. It finds every spot before changing anything
 * and refuses unless the plugin's reads, the shared check, the comment list's layout and the older
 * row's icon cap are all there.
 */
internal val commentReactionRowPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        hookTopReactionPicks()
    }
}

/** One read deciding on the newer row: the `move-result` that takes its answer, the register, and the id. */
internal data class TopReactionRead(val method: Method, val result: Int, val register: Int, val flag: Long)

/** A method making the component, its code, and the index of each `new-instance` of it. */
private class Builder(val method: Method, val code: List<Instruction>, val made: List<Int>)

/** A `new-instance` of the component whose last branch before it tests what the `move-result` at [result] wrote. */
private class Pick(val builder: Builder, val result: Int) {
    val call: Instruction get() = builder.code[result - 1]
}

/** The shared check's read, and the gate it asks before it. */
private class SharedCheck(val read: TopReactionRead, val gate: String)

internal fun BytecodePatchContext.hookTopReactionPicks() {
    val classes = mutableListOf<ClassDef>()
    classDefForEach { classDef: ClassDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES)) classes += classDef
    }
    val builders = builders(classes)
    val picks = picks(builders)
    val check = sharedCheck(classes, picks)
    val hooks = pluginReads(picks).map { it to TOP_REACTION_ONLY } +
        (check.read to TOP_REACTION_CHECK) +
        (listLayout(builders, check.gate) to TOP_REACTION_LAYOUT) +
        (iconCap(classes, check.gate) to TOP_REACTION_ICONS)
    // Later spots first, so an earlier one's index still holds when two share a method.
    for ((read, hook) in hooks.sortedByDescending { it.first.result }) {
        val method = mutableClassDefBy(read.method.definingClass).findMutableMethodOf(read.method)
        method.addInstructions(
            read.result + 1,
            """
                invoke-static/range { v${read.register} .. v${read.register} }, $hook
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

private fun Instruction.register() = (this as OneRegisterInstruction).registerA

private fun Instruction.callee() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.isStaticCall(returns: String) = opcode in STATIC_CALLS && callee()?.returnType == returns

private fun Method.sameAs(other: MethodReference) = name == other.name && returnType == other.returnType &&
    parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/** Where a `const-wide` at most [READ_REACH] instructions before the call at [call] loaded an id it passes, or null. */
private fun List<Instruction>.idLoadFor(call: Int): Int? {
    val passed = this[call].registers().zipWithNext()
    return (call - 1 downTo maxOf(0, call - READ_REACH)).firstOrNull { at ->
        this[at].opcode in WIDE_CONSTS && this[at].register().let { passed.contains(it to it + 1) }
    }
}

private fun List<Instruction>.read(method: Method, result: Int, load: Int) =
    TopReactionRead(method, result, this[result].register(), (this[load] as WideLiteralInstruction).wideLiteral)

/** The `move-result` that last wrote what the last branch before [at] tests, or null. */
private fun List<Instruction>.testedAnswer(at: Int): Int? {
    val branch = (at - 1 downTo 0).firstOrNull { this[it].opcode in BRANCHES } ?: return null
    val tested = this[branch].register()
    val writer = (branch - 1 downTo 1).firstOrNull { writesRegister(this[it], tested) } ?: return null
    return writer.takeIf { this[it].opcode == Opcode.MOVE_RESULT }
}

/**
 * Every method in [classes] making the component, the class whose code loads
 * [TOP_REACTION_COMPONENT], with where each of its `new-instance`s of it sits.
 */
private fun builders(classes: Iterable<ClassDef>): List<Builder> {
    val components = classes.filter { owner -> owner.methods.any { holdsString(it, TOP_REACTION_COMPONENT) } }.map { it.type }.toSet()
    if (components.size != 1) refuse("expected one class named $TOP_REACTION_COMPONENT, found ${components.size}")
    val component = components.single()
    val found = mutableListOf<Builder>()
    for (classDef in classes) {
        for (method in classDef.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            val made = code.indices.filter { at ->
                code[at].opcode == Opcode.NEW_INSTANCE &&
                    ((code[at] as ReferenceInstruction).reference as TypeReference).type == component
            }
            if (made.isNotEmpty()) found += Builder(method, code, made)
        }
    }
    return found
}

private fun picks(builders: List<Builder>): List<Pick> =
    builders.flatMap { builder -> builder.made.mapNotNull { at -> builder.code.testedAnswer(at)?.let { Pick(builder, it) } } }

/** The picks whose answer is a static boolean read of an id passed to it right there: the plugin's. */
private fun pluginReads(picks: List<Pick>): List<TopReactionRead> {
    val found = linkedSetOf<TopReactionRead>()
    for (pick in picks) {
        if (!pick.call.isStaticCall("Z")) continue
        val load = pick.builder.code.idLoadFor(pick.result - 1) ?: continue
        found += pick.builder.code.read(pick.builder.method, pick.result, load)
    }
    if (found.isEmpty()) refuse("no flag read decides on $TOP_REACTION_COMPONENT")
    val flags = found.map { it.flag }.toSet()
    if (flags.size != 1) refuse("the reads deciding on $TOP_REACTION_COMPONENT take ${flags.size} ids")
    return found.toList()
}

/**
 * Every read of the plugin's flag in [classes]: the component class is the one whose code loads
 * [TOP_REACTION_COMPONENT], and each `new-instance` of it is checked as the comment above says.
 */
internal fun topReactionReads(classes: Iterable<ClassDef>): List<TopReactionRead> = pluginReads(picks(builders(classes)))

/**
 * The shared check: of the static booleans the picks test that are passed no id, the one whose code
 * holds a single boolean read of an id, behind a branch on another static boolean's answer, the gate.
 */
private fun sharedCheck(classes: Iterable<ClassDef>, picks: List<Pick>): SharedCheck {
    val asked = picks.filter { it.call.isStaticCall("Z") && it.builder.code.idLoadFor(it.result - 1) == null }
        .mapNotNull { it.call.callee() }.distinctBy { it.toString() }
    val checks = asked.mapNotNull { callee ->
        val method = classes.firstOrNull { it.type == callee.definingClass }?.methods?.firstOrNull { it.sameAs(callee) }
        method?.let(::checkIn)
    }
    if (checks.size != 1) refuse("expected one shared check deciding on $TOP_REACTION_COMPONENT, found ${checks.size}")
    return checks.single()
}

private fun checkIn(method: Method): SharedCheck? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val reads = code.indices.filter { at ->
        at >= 1 && code[at].opcode == Opcode.MOVE_RESULT && code[at - 1].callee()?.returnType == "Z" && code.idLoadFor(at - 1) != null
    }
    val result = reads.singleOrNull() ?: return null
    val load = code.idLoadFor(result - 1)!!
    val gated = code.testedAnswer(load) ?: return null
    if (!code[gated - 1].isStaticCall("Z")) return null
    return SharedCheck(code.read(method, result, load), code[gated - 1].callee().toString())
}

/**
 * The comment list's layout: in a method making the component, the [gate] call whose answer an
 * `if-eqz` tests straight away, falling through to an id's load and, within reach, a static int
 * read taking that id.
 */
private fun listLayout(builders: List<Builder>, gate: String): TopReactionRead {
    val found = mutableListOf<TopReactionRead>()
    for (builder in builders) {
        val code = builder.code
        for (branch in code.indices) {
            val result = code.gatedResult(branch, gate, "I") ?: continue
            found += code.read(builder.method, result, branch + 1)
        }
    }
    if (found.size != 1) refuse("expected one layout read past the shared check's gate, found ${found.size}")
    return found.single()
}

/**
 * The older row's icon cap: anywhere in [classes], a static boolean read past [gate] (as
 * [gatedResult] finds it) whose answer picks one icon over three, by [capsIcons].
 */
private fun iconCap(classes: Iterable<ClassDef>, gate: String): TopReactionRead {
    val found = mutableListOf<TopReactionRead>()
    for (classDef in classes) {
        for (method in classDef.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            for (branch in code.indices) {
                val result = code.gatedResult(branch, gate, "Z") ?: continue
                if (code.capsIcons(result)) found += code.read(method, result, branch + 1)
            }
        }
    }
    if (found.size != 1) refuse("expected one reaction icon cap past the shared check's gate, found ${found.size}")
    return found.single()
}

/**
 * The `move-result` of a read past [gate]: the gate's answer, which the `if-eqz` at [branch] tests
 * straight away, falls through to an id's load and, within reach, a static read returning [returns]
 * that takes the id. Null anywhere else.
 */
private fun List<Instruction>.gatedResult(branch: Int, gate: String, returns: String): Int? {
    if (branch < 2 || branch + 1 >= size) return null
    if (this[branch].opcode != Opcode.IF_EQZ || this[branch - 1].opcode != Opcode.MOVE_RESULT ||
        this[branch - 1].register() != this[branch].register() || this[branch - 2].callee()?.toString() != gate
    ) return null
    val load = branch + 1
    if (this[load].opcode !in WIDE_CONSTS) return null
    val call = (load + 1..minOf(size - 2, load + READ_REACH)).firstOrNull { at ->
        this[at].isStaticCall(returns) && idLoadFor(at) == load && this[at + 1].opcode == Opcode.MOVE_RESULT
    } ?: return null
    return call + 1
}

private fun Instruction.smallConst(): Int? = if (opcode == Opcode.CONST_4) (this as NarrowLiteralInstruction).narrowLiteral else null

/**
 * Whether the answer at [result] picks how many reaction icons to keep: `const/4 vN, 1`, an
 * `if-nez` on the answer, `const/4 vN, 3`, and vN later passed to [MATH_MIN].
 */
private fun List<Instruction>.capsIcons(result: Int): Boolean {
    if (result + 3 >= size) return false
    val one = this[result + 1]
    val test = this[result + 2]
    val three = this[result + 3]
    if (one.smallConst() != 1 || three.smallConst() != 3 || one.register() != three.register()) return false
    if (test.opcode != Opcode.IF_NEZ || test.register() != this[result].register()) return false
    return (result + 4 until size).any { at -> this[at].callee()?.toString() == MATH_MIN && one.register() in this[at].registers() }
}
