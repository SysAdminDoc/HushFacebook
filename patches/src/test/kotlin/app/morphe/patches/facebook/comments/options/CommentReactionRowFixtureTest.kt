/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kept by the method that builds a feed post's inline comment actions. */
private const val INLINE_ACTIONS = "InlineCommentActionsComponent"

/** Kept by the method that builds a comment in an open post's comment list. */
private const val COMMENT_LIST = "CommentComponentSocket"

private const val MATH_MIN = "Ljava/lang/Math;->min(II)I"

private const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"

private val WIDE_CONSTS = setOf(Opcode.CONST_WIDE, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_HIGH16)

/**
 * Show every reaction on comments (#241) on every Facebook build the bundle declares. The class
 * named CommentActionsWithSimplifiedReactionsComponent is found by its kept string. Three spots
 * pick it, and a fourth trims the older row:
 *
 * - the comment actions plugin, through a MobileConfig read of its own;
 * - the shared check that a feed post's inline comment actions and an open post's comment list
 *   both call, a static boolean that reads another MobileConfig boolean past a gate;
 * - the comment list's MobileConfig int, read past that same gate, which picks its second build;
 * - the older row's own cap, a MobileConfig boolean past that gate keeping one reaction icon
 *   beside the count instead of three.
 *
 * Right after each read's `move-result` the extension is asked as a range call, and its answer goes
 * back into the read's register. The inline and comment list builders are found here by their own
 * kept strings, the shared check from the call the inline builder's branch tests, and the icon cap
 * from the gate's one caller that trims a list with Math.min, so these expectations don't come from
 * the finder they check. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it. Redex names differ between the arm64 and armv7
 * bundles, so nothing here names a class.
 */
class CommentReactionRowFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.called() = method()?.toString()

    private fun Method.sameAs(other: MethodReference) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun Instruction.passes(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun Instruction.isBranch() = opcode == Opcode.IF_EQZ || opcode == Opcode.IF_NEZ

    private fun Instruction.register() = (this as OneRegisterInstruction).registerA

    /** The index of a `const-wide` within three instructions before [call] whose pair [call] passes, or -1. */
    private fun List<Instruction>.idFor(call: Int): Int = (call - 1 downTo maxOf(0, call - 3)).firstOrNull { at ->
        this[at].opcode in WIDE_CONSTS && this[call].passes().zipWithNext().contains(this[at].register() to this[at].register() + 1)
    } ?: -1

    /**
     * After the patch, [method] holds Facebook's code with only two instructions added right after
     * the `move-result` at [result]: a one-register range call to [hook] on that result's register,
     * and a `move-result` back into it.
     */
    private fun assertHooked(where: String, before: List<Instruction>, after: List<Instruction>, result: Int, hook: String) {
        val register = before[result].register()
        assertEquals("$where: two instructions added", before.size + 2, after.size)
        assertEquals("$where: the extension is asked as a range call", Opcode.INVOKE_STATIC_RANGE, after[result + 1].opcode)
        assertEquals("$where: it asks $hook", hook, after[result + 1].called())
        assertEquals("$where: it's handed the read's answer", listOf(register), after[result + 1].passes())
        assertEquals("$where: its answer goes back", Opcode.MOVE_RESULT, after[result + 2].opcode)
        assertEquals("$where: into the read's register", register, after[result + 2].register())
        assertEquals("$where: Facebook's code before it stays", before.take(result + 1).map { it.opcode }, after.take(result + 1).map { it.opcode })
        assertEquals("$where: the rest is Facebook's own", before.drop(result + 1).map { it.opcode }, after.drop(result + 3).map { it.opcode })
    }

    @Test
    fun `each declared build's pick of the top-reaction row goes through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val components = FixtureDex.classesHolding(bundle, TOP_REACTION_COMPONENT)
                    .filter { owner -> owner.methods.any { holdsString(it, TOP_REACTION_COMPONENT) } }
                assertEquals("$name: classes named $TOP_REACTION_COMPONENT", 1, components.size)
                val component = components.single()
                fun Instruction.makesComponent() = opcode == Opcode.NEW_INSTANCE &&
                    ((this as ReferenceInstruction).reference as TypeReference).type == component.type

                val builders = FixtureDex.methodsWhere(
                    bundle,
                    { dex -> dex.typeSection.any { it.toString() == component.type } },
                ) { method -> method.implementation?.instructions?.any { it.makesComponent() } == true }
                // Positive control: the plugin, the inline actions and the comment list make the component.
                assertTrue("$name: only ${builders.size} methods make the component", builders.size >= 3)

                // The shared check: the static boolean whose answer the inline builder's branch tests.
                val inline = builders.filter { holdsString(it, INLINE_ACTIONS) }
                assertEquals("$name: inline comment actions making the component", 1, inline.size)
                val inlineCode = inline.single().code()
                val made = inlineCode.indexOfFirst { it.makesComponent() }
                val branch = (made - 1 downTo 0).first { inlineCode[it].isBranch() }
                val tested = inlineCode[branch].register()
                val writer = (branch - 1 downTo 1).first { writesRegister(inlineCode[it], tested) }
                assertEquals("$name: the inline branch tests a call's answer", Opcode.MOVE_RESULT, inlineCode[writer].opcode)
                val checkCall = inlineCode[writer - 1].opcode
                val check = inlineCode[writer - 1].method()!!
                assertEquals("$name: the shared check answers a boolean", "Z", check.returnType)
                val checkClass = FixtureDex.classes(bundle, setOf(check.definingClass)).values.single()
                // 587 calls a static. 588 calls a method of a final singleton, which nothing can override.
                assertTrue(
                    "$name: that call can only reach the check",
                    checkCall == Opcode.INVOKE_STATIC ||
                        (checkCall == Opcode.INVOKE_VIRTUAL && AccessFlags.FINAL.isSet(checkClass.accessFlags)),
                )
                val checkCode = checkClass.methods.single { it.sameAs(check) }.code()
                val checkIds = checkCode.indices.filter { checkCode[it].opcode in WIDE_CONSTS }
                assertEquals("$name: ids the shared check reads", 1, checkIds.size)
                val checkRead = (checkIds.single() + 1 until checkCode.size).first { checkCode[it].method()?.returnType == "Z" }
                assertEquals("$name: the shared check's read takes its id", checkIds.single(), checkCode.idFor(checkRead))
                val checkResult = checkRead + 1
                assertEquals("$name: the shared check's read has an answer", Opcode.MOVE_RESULT, checkCode[checkResult].opcode)
                // The gate: the static boolean whose answer the branch before that id tests.
                val gateBranch = (checkIds.single() - 1 downTo 0).first { checkCode[it].isBranch() }
                assertEquals("$name: the gate's answer", Opcode.MOVE_RESULT, checkCode[gateBranch - 1].opcode)
                assertEquals("$name: the gate is tested", checkCode[gateBranch - 1].register(), checkCode[gateBranch].register())
                val gate = checkCode[gateBranch - 2].method()!!.toString()

                // The comment list asks the shared check and, past the gate, a MobileConfig int.
                val lists = builders.filter { holdsString(it, COMMENT_LIST) }
                assertEquals("$name: comment list builders making the component", 1, lists.size)
                val list = lists.single()
                val listCode = list.code()
                assertTrue("$name: the comment list asks the shared check", listCode.any { it.called() == check.toString() })
                assertEquals("$name: the comment list makes the component twice", 2, listCode.count { it.makesComponent() })
                val layoutResults = listCode.indices.filter { at ->
                    at >= 4 && listCode[at].opcode == Opcode.MOVE_RESULT &&
                        listCode[at - 1].opcode == Opcode.INVOKE_STATIC && listCode[at - 1].method()?.returnType == "I" &&
                        listCode.idFor(at - 1) >= 0 &&
                        (listCode.idFor(at - 1) - 1).let { gated ->
                            gated >= 2 && listCode[gated].opcode == Opcode.IF_EQZ && listCode[gated - 1].opcode == Opcode.MOVE_RESULT &&
                                listCode[gated - 1].register() == listCode[gated].register() && listCode[gated - 2].called() == gate
                        }
                }
                assertEquals("$name: MobileConfig ints the comment list reads past the gate", 1, layoutResults.size)
                val layoutResult = layoutResults.single()

                // The reaction icons beside a comment's count: the one method asking the gate that also
                // trims an ImmutableList with Math.min, keeping that many icons.
                val cappers = FixtureDex.methodsWhere(
                    bundle,
                    { dex -> dex.methodSection.any { it.toString() == gate } },
                ) { method ->
                    val code = method.implementation?.instructions?.toList().orEmpty()
                    code.any { it.called() == gate } && code.any { it.called() == MATH_MIN } &&
                        code.any { it.method()?.definingClass == IMMUTABLE_LIST && it.method()?.name == "subList" }
                }
                assertEquals("$name: methods trimming reaction icons past the gate", 1, cappers.size)
                val capper = cappers.single()
                val capCode = capper.code()
                // Its flag: the first boolean read of an id after the gate, picking one icon over three.
                val gateCall = capCode.indexOfFirst { it.called() == gate }
                val capRead = (gateCall + 1 until capCode.size).first { capCode[it].method()?.returnType == "Z" && capCode.idFor(it) >= 0 }
                val capResult = capRead + 1
                assertEquals("$name: the icon flag has an answer", Opcode.MOVE_RESULT, capCode[capResult].opcode)
                val one = capCode[capResult + 1]
                val test = capCode[capResult + 2]
                val three = capCode[capResult + 3]
                assertEquals("$name: yes keeps one icon", listOf(Opcode.CONST_4, 1), listOf(one.opcode, (one as? NarrowLiteralInstruction)?.narrowLiteral))
                assertEquals("$name: the answer is tested", listOf(Opcode.IF_NEZ, capCode[capResult].register()), listOf(test.opcode, test.register()))
                assertEquals("$name: no keeps three", listOf(Opcode.CONST_4, 3), listOf(three.opcode, (three as? NarrowLiteralInstruction)?.narrowLiteral))
                assertEquals("$name: both land in one register", one.register(), three.register())
                assertTrue("$name: that count trims the icons", capCode.any { it.called() == MATH_MIN && one.register() in it.passes() })
                val capClass = FixtureDex.classes(bundle, setOf(capper.definingClass)).values.single()

                val owners = FixtureDex.classes(bundle, builders.map { it.definingClass }.toSet()).values.toList()
                val classes: List<ClassDef> = (owners + component + checkClass + capClass).distinctBy { it.type }
                val reads = topReactionReads(classes)
                assertEquals("$name: plugin reads deciding on the component", 1, reads.size)
                val read = reads.single()
                val pluginBefore = read.method.code()
                assertEquals("$name: the answer is a move-result", Opcode.MOVE_RESULT, pluginBefore[read.result].opcode)
                assertEquals("$name: the answer's register", read.register, pluginBefore[read.result].register())

                val context = PatchContexts.of(classes)
                commentReactionRowPatch.execute(context)
                fun patched(method: MethodReference) =
                    context.mutableClassDefBy(method.definingClass).methods.single { it.sameAs(method) }.code()

                assertHooked("$name plugin", pluginBefore, patched(read.method), read.result, TOP_REACTION_ONLY)
                assertHooked("$name shared check", checkCode, patched(check), checkResult, TOP_REACTION_CHECK)
                assertHooked("$name comment list", listCode, patched(list), layoutResult, TOP_REACTION_LAYOUT)
                assertHooked("$name icon cap", capCode, patched(capper), capResult, TOP_REACTION_ICONS)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hooks`() {
        val methods = ExtensionDex.classDef(COMMENT_SHEET_OPTIONS).methods
        for ((name, shape) in listOf(
            "topReactionOnly" to TOP_REACTION_ONLY,
            "topReactionCheck" to TOP_REACTION_CHECK,
            "topReactionLayout" to TOP_REACTION_LAYOUT,
            "topReactionIcons" to TOP_REACTION_ICONS,
        )) {
            val hook = methods.singleOrNull { it.name == name }
            assertTrue("the extension has no CommentSheetOptions.$name", hook != null)
            assertEquals("$name's shape", shape, hook!!.descriptor())
        }
    }
}
