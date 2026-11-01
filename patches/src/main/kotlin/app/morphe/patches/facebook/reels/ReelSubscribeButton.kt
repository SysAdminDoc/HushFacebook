/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.namedRegisters
import app.morphe.util.readsAfter
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The Subscribe pill beside a full-screen video's author (#291). Read from 585, 2026-10-30, arm64;
 * the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - Two methods load the pill's test tag "video_author_follow_button": the full-screen viewer's
 *   author row (LX/09bx;->A17) and a static builder other rows call (LX/09bY;->A01). Each asks one
 *   owner check (LX/08B6;->A0L, which reads tree key 0x73849f2a and nothing else) whether the author
 *   gets Subscribe rather than Follow, keeps the answer as a 1 or a 0, picks the pill's label by it
 *   and builds the same pill (LX/09p3;->A00), whose first argument is the tagged component.
 * - The row builds the pill only while a flag it sets to 1 after the tag is still 1, and goes the
 *   way it goes for an author with no pill otherwise. Its hook hands the flag and the owner answer
 *   to the extension right after the flag is set.
 * - The builder always builds the pill and returns it. Its hook notes the owner answer just before
 *   the tag, and every caller that puts the result in a row slot (a two-argument constructor whose
 *   first argument is the slot key, LX/02xv;) asks the extension whether to keep the slot. The rows
 *   pass a null slot for a part they don't have. A caller that only wraps the result and returns it
 *   (LX/09bY;->A0B) is passed over for its own callers.
 * - 585 has three such slots: the viewer row's, after A0B (LX/02zm;), and two in LX/0CLg;->render
 *   (LX/02zm; and LX/02y0;).
 * - One more row builds a pill of its own under another tag, "follow_button" (LX/0b4q;->render). It
 *   asks the same owner check and builds Subscribe past that check's if-eqz. Before the check it has
 *   a way for an author with no pill, an if-eqz whose target lies past the tag. Its hook turns the
 *   owner answer into 1, 0, or -1 for a Subscribe that goes, and -1 takes that way. The patch checks
 *   nothing on that way reads a register the row wrote since its own branch there, so the two ways
 *   in agree on every register it uses.
 */

internal const val SHOW_AUTHOR_PILL = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->showAuthorPill(ZZ)Z"
internal const val NOTE_AUTHOR_PILL = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->noteAuthorPill(Z)V"
internal const val AUTHOR_PILL_SLOT =
    "$EXTENSION_PACKAGE/reels/ReelDeclutter;->authorPillSlot(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val AUTHOR_PILL_ANSWER = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->authorPillAnswer(Z)I"

/** The test tag both places that build the author's Follow or Subscribe pill load. */
internal const val AUTHOR_PILL_TAG = "video_author_follow_button"

/** The test tag of the row that builds a Follow or Subscribe pill of its own. */
internal const val FOOTER_PILL_TAG = "follow_button"

/** The tree key the owner check reads: whether the author gets Subscribe rather than Follow. */
internal const val SUBSCRIBE_OWNER_KEY = 0x73849f2a

private const val PATCH = "Clean up Reels"
private val STRINGS = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)
private val INVOKE_STATIC = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val INVOKE_DIRECT = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

/**
 * One method loading [AUTHOR_PILL_TAG]: where the tag is loaded, the register the owner answer is
 * kept in, and the call that builds the pill.
 */
internal class AuthorPill(val method: Method, val tag: Int, val subscribe: Int, val pill: Int) {
    private val code = method.implementation!!.instructions.toList()

    /** Whether the pill is what the method returns, as the shared builder's is. */
    val returned: Boolean
        get() {
            val result = code.getOrNull(pill + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return false
            val back = code.getOrNull(pill + 2)?.takeIf { it.opcode == Opcode.RETURN_OBJECT } ?: return false
            return (result as OneRegisterInstruction).registerA == (back as OneRegisterInstruction).registerA
        }
}

/** The registers [instruction] passes, in order, or none for anything but an invoke. */
internal fun arguments(instruction: Instruction): List<Int> = when (instruction) {
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount)
    else -> emptyList()
}

private val Instruction.call get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.signature() = parameterTypes.map(CharSequence::toString)

/** Each instruction's address in code units, one past the end included. */
private fun addresses(code: List<Instruction>) = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }

/** Whether [instruction] can go anywhere but the next one, a switch or a throw included. */
private fun branches(instruction: Instruction) =
    instruction is OffsetInstruction && instruction.opcode != Opcode.FILL_ARRAY_DATA || instruction.opcode == Opcode.THROW

/** The method [call] names, among the classes the patch can see. */
private fun BytecodePatchContext.resolve(call: MethodReference): Method? =
    classDefByOrNull(call.definingClass)?.methods?.firstOrNull {
        it.name == call.name && it.returnType == call.returnType &&
            it.parameterTypes.map(CharSequence::toString) == call.signature()
    }

/** Whether [method] is the owner check: it reads [SUBSCRIBE_OWNER_KEY] from a tree, and no other key. */
internal fun isSubscribeOwnerCheck(method: Method): Boolean {
    if (method.returnType != "Z") return false
    val reads = treeBooleanReads(method)
    return reads.isNotEmpty() && reads.all { it.keys == setOf(SUBSCRIBE_OWNER_KEY) }
}

/**
 * [method]'s author pill, or null unless it loads [AUTHOR_PILL_TAG] once, hands it to one call whose
 * result a static call then takes first, and before the tag asks the owner check ([isOwnerCheck])
 * once and keeps its yes as `const/4 vS, 0x1` right behind its `if-eqz`.
 */
internal fun authorPill(method: Method, isOwnerCheck: (MethodReference) -> Boolean): AuthorPill? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val tag = code.indices.singleOrNull {
        code[it].opcode in STRINGS && ((code[it] as ReferenceInstruction).reference as StringReference).string == AUTHOR_PILL_TAG
    } ?: return null
    val tagRegister = (code[tag] as OneRegisterInstruction).registerA
    val tagged = (tag + 1 until code.size).firstOrNull { tagRegister in arguments(code[it]) } ?: return null
    val component = code.getOrNull(tagged + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return null
    val componentRegister = (component as OneRegisterInstruction).registerA
    val pill = (tagged + 2 until code.size).firstOrNull {
        code[it].opcode in INVOKE_STATIC && arguments(code[it]).firstOrNull() == componentRegister &&
            code[it].call!!.returnType.startsWith("L")
    } ?: return null
    val check = (0 until tag).singleOrNull { at -> code[at].call?.takeIf { it.returnType == "Z" }?.let(isOwnerCheck) == true }
        ?: return null
    val answer = code.getOrNull(check + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction ?: return null
    val skip = code.getOrNull(check + 2)?.takeIf { it.opcode == Opcode.IF_EQZ } as? OneRegisterInstruction ?: return null
    val yes = code.getOrNull(check + 3)?.takeIf { it.opcode == Opcode.CONST_4 } ?: return null
    if (skip.registerA != answer.registerA || (yes as NarrowLiteralInstruction).narrowLiteral != 1) return null
    return AuthorPill(method, tag, (yes as OneRegisterInstruction).registerA, pill)
}

/** The row with a pill of its own: where its owner answer lands, in which register, and where its way for no pill starts. */
internal class FooterPill(val method: Method, val answerAt: Int, val answer: Int, val noPill: Int)

/**
 * [method]'s own pill, or null unless it loads [FOOTER_PILL_TAG] once, asks the owner check
 * ([isOwnerCheck]) once before the tag and branches on the answer with an `if-eqz`, and has an
 * `if-eqz` before that check whose target lies past the tag. Null too if the answer is read after
 * that branch, or if the way for no pill reads a register written since the row's branch to it.
 */
internal fun footerPill(method: Method, isOwnerCheck: (MethodReference) -> Boolean): FooterPill? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val tag = code.indices.singleOrNull {
        code[it].opcode in STRINGS && ((code[it] as ReferenceInstruction).reference as StringReference).string == FOOTER_PILL_TAG
    } ?: return null
    val check = (0 until tag).singleOrNull { at -> code[at].call?.takeIf { it.returnType == "Z" }?.let(isOwnerCheck) == true }
        ?: return null
    val answer = code.getOrNull(check + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction ?: return null
    val branch = code.getOrNull(check + 2)?.takeIf { it.opcode == Opcode.IF_EQZ } as? OneRegisterInstruction ?: return null
    if (branch.registerA != answer.registerA) return null
    val at = addresses(code)
    val skip = (check - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.IF_EQZ && at[it] + (code[it] as OffsetInstruction).codeOffset > at[tag]
    } ?: return null
    val noPill = at.indexOf(at[skip] + (code[skip] as OffsetInstruction).codeOffset)
    if (noPill !in code.indices || method.readsAfter(check + 2, answer.registerA).isNotEmpty()) return null
    val live = RegisterLiveness.of(method).liveInto(noPill)
    val written = (skip + 1..check + 1).filter { code[it].opcode.setsRegister() }.map { code[it].namedRegisters().first() }
    if (written.any { it in live }) return null
    return FooterPill(method, check + 1, answer.registerA, noPill)
}

/** Whether every value [register] can hold at [index] in [method] is a `const/4` 0 or 1. */
private fun holdsConstantFlag(method: Method, index: Int, register: Int): Boolean {
    val code = method.implementation!!.instructions.toList()
    val writers = method.writersReaching(index, register)
    return writers.isNotEmpty() && writers.all {
        it >= 0 && code[it].opcode == Opcode.CONST_4 && (code[it] as NarrowLiteralInstruction).narrowLiteral in 0..1
    }
}

/** The row's pill flag: the register of the last `if-eqz` between the tag and the pill that skips the pill. */
internal fun pillFlag(row: AuthorPill): Int? {
    val code = row.method.implementation!!.instructions.toList()
    val at = addresses(code)
    return (row.pill - 1 downTo row.tag + 1).firstOrNull {
        code[it].opcode == Opcode.IF_EQZ && at[it] + (code[it] as OffsetInstruction).codeOffset > at[row.pill]
    }?.let { (code[it] as OneRegisterInstruction).registerA }
}

/** The slot key: the first parameter of the two-argument constructor the row runs on the pill right after building it. */
internal fun slotKey(row: AuthorPill): String? {
    val code = row.method.implementation!!.instructions.toList()
    for (at in row.pill + 1 until code.size) {
        val call = code[at].call
        if (code[at].opcode in INVOKE_DIRECT && call?.name == "<init>") {
            return call.signature().takeIf { it.size == 2 }?.first()
        }
        if (branches(code[at])) return null
    }
    return null
}

/** A row slot built from the pill: the constructor call at [init] in [method], whose new object is in [register]. */
internal data class PillSlot(val method: Method, val init: Int, val register: Int, val type: String)

/**
 * Every row slot built from what [builder] returns, following callers that only pass it back. A slot
 * is the first two-argument constructor whose first parameter is [key] after the call, with no branch
 * between. A caller that reaches a `return-object` first is passed over for its own callers.
 */
internal fun BytecodePatchContext.pillSlots(builder: Method, key: String): List<PillSlot> {
    val slots = mutableListOf<PillSlot>()
    val seen = mutableSetOf<String>()
    val pending = ArrayDeque(listOf(builder))
    while (pending.isNotEmpty()) {
        val target = pending.removeFirst()
        if (!seen.add(target.key())) continue
        val targetSignature = target.parameterTypes.map(CharSequence::toString)
        classDefForEachReferencing(types = listOf(target.definingClass)) { classDef ->
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
            for (method in classDef.methods) {
                val code = method.implementation?.instructions?.toList() ?: continue
                for (site in code.indices) {
                    val call = code[site].call ?: continue
                    if (call.definingClass != target.definingClass || call.name != target.name ||
                        call.returnType != target.returnType || call.signature() != targetSignature
                    ) continue
                    var branched = false
                    var found = false
                    for (at in site + 1 until code.size) {
                        val next = code[at]
                        val init = next.call
                        if (next.opcode in INVOKE_DIRECT && init?.name == "<init>" && init.signature().let { it.size == 2 && it[0] == key }) {
                            if (branched) {
                                throw PatchException("$PATCH: ${method.definingClass}->${method.name} branches between its author pill and its slot")
                            }
                            slots += PillSlot(method, at, arguments(next).first(), init.definingClass)
                            found = true
                            break
                        }
                        if (next.opcode == Opcode.RETURN_OBJECT) {
                            pending += method
                            found = true
                            break
                        }
                        if (branches(next)) branched = true
                    }
                    if (!found) throw PatchException("$PATCH: ${method.definingClass}->${method.name} puts its author pill neither in a slot nor back")
                }
            }
        }
    }
    return slots
}

/**
 * Hooks the Subscribe pill beside a full-screen video's author: the viewer row asks the extension
 * whether to build it, and every row slot holding the shared builder's pill asks whether to keep
 * it. Refuses unless the row and the shared builder are each found once with the owner check, and
 * the builder's pill lands in at least one slot.
 */
internal fun BytecodePatchContext.hideSubscribePill() {
    val ownerChecks = mutableMapOf<String, Boolean>()
    val isOwnerCheck = { call: MethodReference ->
        ownerChecks.getOrPut(call.toString()) { resolve(call)?.let(::isSubscribeOwnerCheck) == true }
    }
    val holders = classDefByStrings(AUTHOR_PILL_TAG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { owner -> owner.methods.filter { holdsString(it, AUTHOR_PILL_TAG) } }
    val pills = holders.mapNotNull { authorPill(it, isOwnerCheck) }
    val footers = classDefByStrings(FOOTER_PILL_TAG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { owner -> owner.methods.filter { holdsString(it, FOOTER_PILL_TAG) } }
        .mapNotNull { footerPill(it, isOwnerCheck) }
    val footer = footers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one row building its own \"$FOOTER_PILL_TAG\" pill after the Subscribe owner check, found ${footers.size}",
    )
    val row = pills.singleOrNull { !it.returned } ?: throw PatchException(
        "$PATCH: expected one author row building the \"$AUTHOR_PILL_TAG\" pill after the Subscribe owner check, " +
            "found ${pills.count { !it.returned }} among ${holders.size} methods loading the tag",
    )
    val builder = pills.singleOrNull { it.returned } ?: throw PatchException(
        "$PATCH: expected one builder returning the \"$AUTHOR_PILL_TAG\" pill after the Subscribe owner check, " +
            "found ${pills.count { it.returned }} among ${holders.size} methods loading the tag",
    )
    val flag = pillFlag(row) ?: throw PatchException("$PATCH: the author row builds its pill without a flag")
    val rowCode = row.method.implementation!!.instructions.toList()
    val set = (row.tag + 1 until row.pill).singleOrNull {
        rowCode[it].opcode == Opcode.CONST_4 && (rowCode[it] as OneRegisterInstruction).registerA == flag &&
            (rowCode[it] as NarrowLiteralInstruction).narrowLiteral == 1
    } ?: throw PatchException("$PATCH: the author row doesn't set its pill flag once")
    if (flag > 15 || row.subscribe > 15 || !holdsConstantFlag(row.method, set + 1, row.subscribe)) {
        throw PatchException("$PATCH: the author row's owner answer isn't a 1 or a 0 where its pill flag is set")
    }
    if (!holdsConstantFlag(builder.method, builder.tag, builder.subscribe)) {
        throw PatchException("$PATCH: the pill builder's owner answer isn't a 1 or a 0 at its tag")
    }
    val key = slotKey(row) ?: throw PatchException("$PATCH: the author row puts its pill in no slot")
    val slots = pillSlots(builder.method, key)
    if (slots.isEmpty()) throw PatchException("$PATCH: no row slot holds the pill builder's pill")
    if (slots.any { it.register > 255 }) throw PatchException("$PATCH: a pill slot sits past v255")

    val hooks = slots.map { slot ->
        PillHook(slot.method, slot.init) {
            addInstructions(
                slot.init + 1,
                """
                    invoke-static/range { v${slot.register} .. v${slot.register} }, $AUTHOR_PILL_SLOT
                    move-result-object v${slot.register}
                    check-cast v${slot.register}, ${slot.type}
                """,
            )
        }
    } + PillHook(row.method, set) {
        addInstructions(
            set + 1,
            """
                invoke-static { v${row.subscribe}, v$flag }, $SHOW_AUTHOR_PILL
                move-result v$flag
            """,
        )
    } + PillHook(builder.method, builder.tag) {
        addInstructionsAtControlFlowLabel(
            builder.tag,
            "invoke-static/range { v${builder.subscribe} .. v${builder.subscribe} }, $NOTE_AUTHOR_PILL",
        )
    } + PillHook(footer.method, footer.answerAt) {
        addInstructionsWithLabels(
            footer.answerAt + 1,
            """
                invoke-static/range { v${footer.answer} .. v${footer.answer} }, $AUTHOR_PILL_ANSWER
                move-result v${footer.answer}
                if-ltz v${footer.answer}, :no_pill
            """,
            ExternalLabel("no_pill", getInstruction(footer.noPill)),
        )
    }
    if (hooks.count { it.method.key() == footer.method.key() } != 1) {
        throw PatchException("$PATCH: the row building its own pill would take a second pill hook")
    }
    // Each method's hooks go in from the bottom up, so the indices found above still hold.
    for (inMethod in hooks.groupBy { it.method.key() }.values) {
        val found = inMethod.first().method
        val mutable = mutableClassDefBy(found.definingClass).methods.single { it.key() == found.key() }
        inMethod.sortedByDescending { it.at }.forEach { mutable.(it.insert)() }
    }
}

/** A hook going in at [at] of [method]. */
private class PillHook(val method: Method, val at: Int, val insert: MutableMethod.() -> Unit)

private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
