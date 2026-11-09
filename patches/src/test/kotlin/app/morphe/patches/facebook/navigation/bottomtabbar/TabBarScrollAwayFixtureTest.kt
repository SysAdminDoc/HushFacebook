/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bars' scroll-away on every Facebook build the bundle declares. First the one check the main
 * screen's start asks before it builds the bottom bar's scroll-away entry, the bar's position check
 * inside it (the class's own Activity check that reads the TriState override, on 586 through one
 * more method of the class), and room for the
 * hook's one register. Then the patch on that class: a kept bar's no returned right after the
 * position check passes, then a sliding bar's yes, a no going on to Facebook's next instruction, and
 * nothing else in the method moved. Second the top bar: the method that adds it to the scroll-away
 * views, the name it reads off the entry it makes (followed from the "app_header_container" string
 * into that very field), and each move of that entry handing its distance, then how far out of
 * sight it takes the view, through the extension first. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class TabBarScrollAwayFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun Method.sameAs(other: MethodReference) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun Method.sameAs(other: Method) = definingClass == other.definingClass && name == other.name &&
        returnType == other.returnType && parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun Instruction.calls() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.loads() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun nonExtension(bundle: File, string: String) =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

    /** Everything the patch reads on [bundle]: the gate's holders and class, the top views' class, the entry and the tab pager. */
    private class Pieces(
        val gateHolders: List<ClassDef>,
        val gateClass: ClassDef,
        val topHolders: List<ClassDef>,
        val entry: ClassDef,
        val pagerHolders: List<ClassDef>,
    ) {
        fun context() = PatchContexts.of((gateHolders + gateClass + topHolders + entry + pagerHolders).distinctBy { it.type })
    }

    private fun pieces(bundle: File): Pieces {
        val name = bundle.name
        val holders = nonExtension(bundle, BOTTOM_TABS_CONTAINER)
        assertTrue("$name: nothing loads \"$BOTTOM_TABS_CONTAINER\"", holders.isNotEmpty())
        val gateClass = FixtureDex.classes(bundle, setOf(scrollAwayGate(holders).definingClass)).values.single()
        val topHolders = nonExtension(bundle, SET_TOP_VIEWS)
        assertTrue("$name: nothing is traced as \"$SET_TOP_VIEWS\"", topHolders.isNotEmpty())
        val entryType = scrollAwayEntryName(topScrollAwayViews(topHolders)).definingClass
        val pagerHolders = nonExtension(bundle, PAGER_INIT)
        assertTrue("$name: nothing is traced as \"$PAGER_INIT\"", pagerHolders.isNotEmpty())
        return Pieces(holders, gateClass, topHolders, FixtureDex.classes(bundle, setOf(entryType)).values.single(), pagerHolders)
    }

    @Test
    fun `each declared build gates the bar's scroll-away once, and the extension goes after the position check`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val pieces = pieces(bundle)
                val gateReference = scrollAwayGate(pieces.gateHolders)
                val gateClass = pieces.gateClass
                val gate = gateClass.methods.single { it.sameAs(gateReference) }
                assertEquals("$name: the check takes the Activity and answers a boolean", listOf(ACTIVITY),
                    gate.parameterTypes.map(CharSequence::toString))

                val at = barAtBottomCheck(gate, gateClass)
                val original = gate.code()
                val position = original[at - 3]
                val positionCheck = gateClass.methods.single {
                    it.sameAs((position as ReferenceInstruction).reference as MethodReference)
                }
                // Up to 585 the check reads it; 586's check calls a method of its class that does
                // (`LX/02Cc;->A09` calls `A05()Z` on arm64).
                fun readsOverride(method: Method) =
                    method.code().any { it.calls()?.startsWith(FB_SHARED_PREFERENCES) == true && it.calls()!!.endsWith(TRI_STATE) }
                val reader = if (readsOverride(positionCheck)) positionCheck else gateClass.methods.singleOrNull { method ->
                    readsOverride(method) && positionCheck.code().any { it.calls() == "${method.definingClass}->${method.name}" +
                        method.parameterTypes.joinToString("", "(", ")") + method.returnType }
                }
                assertTrue("$name: the position check doesn't read Facebook's override", reader != null)
                // Negative control: the gate itself asks the position check, not the override.
                assertTrue("$name: the gate reads the override itself", !readsOverride(gate))
                assertEquals("$name: the branch that leaves when the bar isn't at the bottom", Opcode.IF_EQZ, original[at - 1].opcode)
                // The hook borrows one register, a local: this and the Activity are the two parameter registers.
                assertTrue("$name: ${gate.definingClass}->${gate.name} has no local register for the hook",
                    gate.implementation!!.registerCount - 2 >= 1)

                val context = pieces.context()
                tabBarScrollAwayPatch.execute(context)
                val patchedMethod = context.mutableClassDefBy(gateClass.type).methods.single { it.sameAs(gateReference) }
                val patched = patchedMethod.code()
                val where = "$name: ${gate.definingClass}->${gate.name}"
                assertEquals("$where gains nine instructions", original.size + 9, patched.size)
                assertEquals("$where: Facebook's code up to the position check stays", original.take(at).map { it.opcode },
                    patched.take(at).map { it.opcode })
                assertEquals("$where: the extension is asked about a kept bar first", STAYS_PUT, patched[at].calls())
                assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                val register = (patched[at + 1] as OneRegisterInstruction).registerA
                assertTrue("$where: the hook writes v$register, which isn't a local", register < gate.localRegisterCount())
                for (offset in listOf(2, 3, 4, 6, 7, 8)) {
                    assertEquals("$where: the hook keeps to v$register", register, (patched[at + offset] as OneRegisterInstruction).registerA)
                }
                assertEquals("$where: a bar that isn't kept goes on to the scroll-away switch", Opcode.IF_EQZ, patched[at + 2].opcode)
                assertEquals("$where: a kept bar answers no", Opcode.CONST_16, patched[at + 3].opcode)
                assertEquals("$where: a kept bar's no is returned", Opcode.RETURN, patched[at + 4].opcode)
                assertEquals("$where: then the extension is asked about sliding", SLIDES_AWAY, patched[at + 5].calls())
                assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[at + 6].opcode)
                assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[at + 7].opcode)
                assertEquals("$where: a yes is returned", Opcode.RETURN, patched[at + 8].opcode)
                assertEquals("$where: Facebook's code after the hook stays", original.drop(at).map { it.opcode },
                    patched.drop(at + 9).map { it.opcode })

                val flow = ControlFlow.of(patchedMethod)
                assertEquals("$where: a bar that isn't kept skips the kept bar's return", setOf(at + 3, at + 5),
                    flow.normal[at + 2].toSet())
                assertEquals("$where: a no lands on Facebook's next instruction", setOf(at + 8, at + 9),
                    flow.normal[at + 7].toSet())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `each declared build hands the top bar's moves through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val pieces = pieces(bundle)
                val topViews = topScrollAwayViews(pieces.topHolders)
                assertTrue("$name: ${topViews.definingClass}->${topViews.name} doesn't add the top bar",
                    topViews.code().any { it.loads() == "app_header_container" })
                assertTrue("$name: nothing names the bar of tabs at the top",
                    nonExtension(bundle, "title_bar_tab_bar_container").isNotEmpty())
                val entryName = scrollAwayEntryName(topViews)
                assertEquals("$name: the entry's name is a String", "Ljava/lang/String;", entryName.type)

                val moves = entryMoves(pieces.entry)
                assertTrue("$name: ${pieces.entry.type} has no moves", moves.isNotEmpty())
                val context = pieces.context()
                tabBarScrollAwayPatch.execute(context)
                val patchedEntry = context.mutableClassDefBy(pieces.entry.type)
                for ((move, distance, hidden) in moves) {
                    val where = "$name: ${move.definingClass}->${move.name}"
                    // 582's two moves both take how far out of sight they take the view, which A02 fades it by.
                    assertTrue("$where takes no float to fade its view by", hidden != null)
                    val original = move.code()
                    val patched = patchedEntry.methods.single { it.sameAs(move) }.code()
                    assertEquals("$where gains five instructions", original.size + 5, patched.size)
                    val read = patched[0]
                    assertEquals("$where: the entry's name is read first", Opcode.IGET_OBJECT, read.opcode)
                    assertEquals("$where: the entry's name is read first", entryName.toString(),
                        ((read as ReferenceInstruction).reference as FieldReference).toString())
                    val local = (read as TwoRegisterInstruction).registerA
                    assertTrue("$where: the name goes in v$local, which isn't a local", local < move.localRegisterCount())
                    assertTrue("$where: the name goes in v$local, the distance's register", local != distance)
                    assertEquals("$where: then handed to the extension", TOP_BAR_OFFSET, patched[1].calls())
                    assertEquals("$where: the distance comes back", Opcode.MOVE_RESULT, patched[2].opcode)
                    assertEquals("$where: the distance comes back", distance, (patched[2] as OneRegisterInstruction).registerA)
                    assertEquals("$where: then the fade is handed to the extension", TOP_BAR_FADE, patched[3].calls())
                    val fade = patched[3] as FiveRegisterInstruction
                    assertEquals("$where: with the name and the fade", listOf(local, hidden), listOf(fade.registerC, fade.registerD))
                    assertEquals("$where: the fade comes back", Opcode.MOVE_RESULT, patched[4].opcode)
                    assertEquals("$where: the fade comes back", hidden, (patched[4] as OneRegisterInstruction).registerA)
                    assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `each declared build tells the extension as the main screen turns scroll-away off and on`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val pieces = pieces(bundle)
                val context = pieces.context()
                tabBarScrollAwayPatch.execute(context)
                for ((trace, call) in listOf(DEACTIVATE_SCROLL_AWAY to TOP_BARS_LET_GO, REACTIVATE_SCROLL_AWAY to TOP_BARS_TAKEN_BACK)) {
                    val holders = nonExtension(bundle, trace)
                    assertTrue("$name: nothing is traced as \"$trace\"", holders.isNotEmpty())
                    val switch = scrollAwaySwitch(holders, trace)
                    val where = "$name: ${switch.definingClass}->${switch.name}"
                    // 582: both sit in the class that adds the top bar, so the patch's context already holds them.
                    assertEquals("$where isn't in the class traced as \"$SET_TOP_VIEWS\"", topScrollAwayViews(pieces.topHolders).definingClass,
                        switch.definingClass)
                    // 586 checks the session isn't null first (three instructions), still before any branch.
                    val opens = switch.code().indexOfFirst { it.loads() == trace }
                    assertTrue("$where opens its trace first", opens in 0..3 &&
                        switch.code().take(opens).none { it is com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction })
                    val original = switch.code()
                    val patched = context.mutableClassDefBy(switch.definingClass).methods.single { it.sameAs(switch) }.code()
                    assertEquals("$where gains one instruction", original.size + 1, patched.size)
                    assertEquals("$where: the extension hears it first", call, patched[0].calls())
                    assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(1).map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    /** Keeps [held], the registers holding one value, up to date across [instruction] when it's an object move. */
    private fun follows(instruction: Instruction, held: MutableSet<Int>) {
        if (instruction.opcode.name.startsWith("move-object")) {
            val move = instruction as TwoRegisterInstruction
            if (move.registerB in held) held += move.registerA else held -= move.registerA
        }
    }

    /**
     * The field [name] ends up in on the entry [topViews] makes, as 582 hands it on: loaded by a
     * `const-string`, passed to the constructor of an options class (`LX/2Io;`), stored there, and
     * copied from there into the entry (`LX/2Ir;`) by the entry's constructor, which takes the options.
     */
    private fun nameRoute(bundle: File, topViews: Method, name: String): String {
        val where = "${bundle.name}: ${topViews.definingClass}->${topViews.name}"
        val code = topViews.code()
        val load = code.indexOfFirst { it.loads() == name }
        assertTrue("$where doesn't load \"$name\"", load >= 0)
        val loaded = (code[load] as OneRegisterInstruction).registerA
        // The first call after the load that takes the name, with nothing writing its register before.
        val call = (load + 1 until code.size).first { index ->
            val instruction = code[index]
            val takes = when (instruction) {
                is RegisterRangeInstruction -> loaded in instruction.startRegister until instruction.startRegister + instruction.registerCount
                is FiveRegisterInstruction -> loaded in listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                    instruction.registerF, instruction.registerG).take(instruction.registerCount)
                else -> false
            }
            if (!takes) {
                val written = (instruction as? OneRegisterInstruction)?.registerA
                assertTrue("$where writes over the name before handing it on", !(instruction.opcode.setsRegister() && written == loaded))
            }
            takes && instruction.opcode.name.startsWith("invoke")
        }
        val options = (code[call] as ReferenceInstruction).reference as MethodReference
        assertEquals("$where hands the name to a constructor", "<init>", options.name)
        val slot = when (val instruction = code[call]) {
            is RegisterRangeInstruction -> loaded - instruction.startRegister
            else -> (instruction as FiveRegisterInstruction).let {
                listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).indexOf(loaded)
            }
        }
        val optionsClass = FixtureDex.classes(bundle, setOf(options.definingClass)).values.single()
        val init = optionsClass.methods.single { it.sameAs(options) }
        val ins = 1 + init.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
        val held = mutableSetOf(init.implementation!!.registerCount - ins + slot)
        val stored = init.code().firstNotNullOfOrNull { instruction ->
            follows(instruction, held)
            ((instruction as? ReferenceInstruction)?.takeIf { instruction.opcode == Opcode.IPUT_OBJECT }?.reference as? FieldReference)
                ?.takeIf { (instruction as TwoRegisterInstruction).registerA in held && it.definingClass == options.definingClass }
        } ?: throw AssertionError("$where: ${options.definingClass}'s constructor doesn't keep the name")
        val made = code.drop(call + 1).firstNotNullOfOrNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                instruction.opcode.name.startsWith("invoke-direct") && it.name == "<init>" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(options.definingClass)
            }
        } ?: throw AssertionError("$where makes no entry from ${options.definingClass}")
        val entryInit = FixtureDex.classes(bundle, setOf(made.definingClass)).values.single().methods.single { it.sameAs(made) }
        val entryCode = entryInit.code()
        val read = entryCode.indexOfFirst { it.opcode == Opcode.IGET_OBJECT && (it as ReferenceInstruction).reference.toString() == stored.toString() }
        assertTrue("$where: ${made.definingClass}'s constructor doesn't read ${stored.name} off the options", read >= 0)
        val copied = mutableSetOf((entryCode[read] as TwoRegisterInstruction).registerA)
        return entryCode.drop(read + 1).firstNotNullOfOrNull { instruction ->
            follows(instruction, copied)
            ((instruction as? ReferenceInstruction)?.takeIf { instruction.opcode == Opcode.IPUT_OBJECT }?.reference as? FieldReference)
                ?.takeIf { (instruction as TwoRegisterInstruction).registerA in copied && it.definingClass == made.definingClass }
                ?.toString()
        } ?: throw AssertionError("$where: ${made.definingClass}'s constructor doesn't keep the name")
    }

    @Test
    fun `each declared build reads the top bar's name from the field it's stored in`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val topViews = topScrollAwayViews(nonExtension(bundle, SET_TOP_VIEWS))
                val picked = scrollAwayEntryName(topViews).toString()
                assertEquals("${bundle.name}: the hook reads the entry's name off another field",
                    nameRoute(bundle, topViews, "app_header_container"), picked)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `each declared build pads its tab pager for a kept bar through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val gate = scrollAwayGate(nonExtension(bundle, BOTTOM_TABS_CONTAINER))
                val holders = nonExtension(bundle, PAGER_INIT)
                assertTrue("$name: nothing is traced as \"$PAGER_INIT\"", holders.isNotEmpty())
                val (init, at) = keptBarPadding(holders, gate)
                val where = "$name: ${init.definingClass}->${init.name}"
                val original = init.code()
                val asked = original[at] as FiveRegisterInstruction

                val method = PatchContexts.of(holders).mutableClassDefBy(init.definingClass).methods.single { it.sameAs(init) }
                method.padsPagerForKeptBar(at)
                val patched = method.code()
                assertEquals("$where: nothing is added", original.size, patched.size)
                assertEquals("$where: the padding goes through the extension", Opcode.INVOKE_STATIC, patched[at].opcode)
                assertEquals("$where: the padding goes through the extension", PADS_PAGER, patched[at].calls())
                val call = patched[at] as FiveRegisterInstruction
                assertEquals("$where: with the pager and Facebook's own padding",
                    listOf(asked.registerC, asked.registerD, asked.registerE, asked.registerF, asked.registerG),
                    listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG))
                assertEquals("$where: Facebook's code around it stays",
                    original.filterIndexed { index, _ -> index != at }.map { it.opcode },
                    patched.filterIndexed { index, _ -> index != at }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
