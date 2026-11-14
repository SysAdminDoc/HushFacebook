/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.doubletapskip

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.shared.MOBILE_CONFIG
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Double tap to skip on each declared Facebook build: the double-tap skip lambda reads its
 * MobileConfig flag once, the player builders read it too, and right after every read the answer
 * goes through the extension into the same register, with the rest of each method as Facebook
 * wrote it. SettingsStatus says the patch is in.
 *
 * Read from 584 (2026-10-24): the lambda `LX/8ab;`, its read at 35 to 38, and the builders
 * `LX/8bK;->A0l` and `LX/8XQ;->A00`. None of those names is used here.
 */
class DoubleTapSkipFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun same(a: Method, b: Method) = a.name == b.name && a.returnType == b.returnType &&
        a.parameterTypes.map(CharSequence::toString) == b.parameterTypes.map(CharSequence::toString)

    @Test
    fun `each declared build's skip flag reads ask the extension`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val lambdas = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex -> dex.classes.filterTo(lambdas, ::isDoubleTapSkip) }
                assertEquals("$name: one double-tap skip lambda, found ${lambdas.map { it.type }}", 1, lambdas.size)
                val flag = skipFlag(lambdas.single())

                val owners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    dex.classes.filterTo(owners) { owner -> owner.methods.any { loadsOf(it, flag).isNotEmpty() } }
                }
                val context = PatchContexts.of(owners + ExtensionDex.classDef(SETTINGS_STATUS))

                // The patch finds the lambda and the reads through the reference index, which hands each
                // walk only the classes naming a skip field or MobileConfig. Before anything changes,
                // each walk has to reach what the full scans above found, or a lambda or player builder
                // Facebook rewrites would go unhooked without a refusal.
                val walkedLambdas = mutableListOf<String>()
                context.classDefForEachReferencing(names = SKIP_FIELDS.keys) { if (isDoubleTapSkip(it)) walkedLambdas += it.type }
                assertEquals("$name: the skip field walk reaches the lambda", lambdas.map { it.type }, walkedLambdas)
                val walked = mutableSetOf<String>()
                context.classDefForEachReferencing(types = listOf(MOBILE_CONFIG)) { walked += it.type }
                assertEquals("$name: the MobileConfig walk reaches every class loading the skip flag",
                    emptyList<String>(), owners.map { it.type }.filterNot { it in walked })
                assertFalse("$name: the MobileConfig walk is keyed, so SettingsStatus isn't handed to it", SETTINGS_STATUS in walked)

                val reads = owners.flatMap { owner -> owner.methods.flatMap { readsOf(it, flag) } }
                assertTrue("$name: the lambda and a player builder read the flag, found ${reads.size}", reads.size >= 2)

                doubleTapSkipPatch.execute(context)

                for ((method, inMethod) in reads.groupBy { it.method }) {
                    val code = method.implementation!!.instructions.toList()
                    val body = context.mutableClassDefBy(method.definingClass).methods.single { same(it, method) }
                        .implementation!!.instructions.toList()
                    val where = "$name: ${method.definingClass}->${method.name}"
                    assertEquals("$where: two instructions added after each read", code.size + 2 * inMethod.size, body.size)
                    val added = mutableSetOf<Int>()
                    inMethod.sortedBy { it.result }.forEachIndexed { earlier, read ->
                        val at = read.result + 1 + 2 * earlier
                        val ask = body[at]
                        assertEquals("$where: the ask's form after ${read.result}", Opcode.INVOKE_STATIC_RANGE, ask.opcode)
                        assertEquals("$where: the ask after ${read.result}", SKIP_FLAG_CALL, (ask as ReferenceInstruction).reference.toString())
                        assertEquals("$where: the ask after ${read.result} reads the answer", read.register,
                            (ask as RegisterRangeInstruction).startRegister)
                        assertEquals("$where: the ask after ${read.result} reads one register", 1, ask.registerCount)
                        val answer = body[at + 1]
                        assertEquals("$where: the answer after ${read.result}", Opcode.MOVE_RESULT, answer.opcode)
                        assertEquals("$where: the answer's register after ${read.result}", read.register,
                            (answer as OneRegisterInstruction).registerA)
                        added += at
                        added += at + 1
                    }
                    assertEquals("$where: everything else as Facebook wrote it", code.map { it.opcode },
                        body.indices.filterNot { it in added }.map { body[it].opcode })
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "doubleTapSkip" }
                    .implementation!!.instructions.toList()
                assertEquals("$name: SettingsStatus answers first", listOf(Opcode.CONST_4, Opcode.RETURN),
                    status.take(2).map { it.opcode })
                assertEquals("$name: SettingsStatus says the patch is in", 1L,
                    (status.first() as WideLiteralInstruction).wideLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun fieldOf(instruction: Instruction): FieldReference? = (instruction as? ReferenceInstruction)?.reference as? FieldReference

    private fun registersOf(instruction: Instruction): List<Int> = when (instruction) {
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG).take(instruction.registerCount)
        else -> emptyList()
    }

    private fun isSetVisibility(instruction: Instruction): Boolean {
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return false
        val call = (instruction as ReferenceInstruction).reference as MethodReference
        return call.name == "setVisibility" && call.parameterTypes.map(CharSequence::toString) == listOf("I")
    }

    /**
     * #476: the "+5s" or "-10s" over a skipped reel is the one TextView the skip lambda's gesture view
     * keeps. Read from 587 (2026-11-14): the overlay arm (arm64 `LX/0On1;->invoke`, armv7
     * `LX/0Ii7;->invoke`) stores the label it makes and later shows it with setVisibility on the
     * same register. On each declared build that show, and every other setVisibility the patch
     * swaps, becomes the extension's label call with Facebook's registers, and nothing else moves.
     */
    @Test
    fun `each declared build's skip seconds ask the extension before they show`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val lambdas = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex -> dex.classes.filterTo(lambdas, ::isDoubleTapSkip) }
                val lambda = lambdas.single()
                val flag = skipFlag(lambda)
                val viewTypes = skipInvoke(lambda)!!.implementation!!.instructions.filter { it.opcode == Opcode.INSTANCE_OF }
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }.distinct()
                assertEquals("$name: the lambda checks one view, found $viewTypes", 1, viewTypes.size)
                val view = viewTypes.single()

                val owners = mutableListOf<ClassDef>()
                val gestureViews = mutableListOf<ClassDef>()
                val writers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    dex.classes.filterTo(owners) { owner -> owner.methods.any { loadsOf(it, flag).isNotEmpty() } }
                    dex.classes.filterTo(gestureViews) { it.type == view }
                    dex.classes.filterTo(writers) { writer ->
                        writer.methods.any { method ->
                            method.implementation?.instructions?.any {
                                it.opcode == Opcode.IPUT_OBJECT && fieldOf(it)?.definingClass == view
                            } == true
                        }
                    }
                }
                val labels = gestureViews.single().fields
                    .filter { it.type == "Landroid/widget/TextView;" && !AccessFlags.STATIC.isSet(it.accessFlags) }
                assertEquals("$name: $view keeps one TextView", 1, labels.size)
                val label = labels.single()
                fun isLabel(field: FieldReference?) = field != null && field.definingClass == view && field.name == label.name

                // Where Facebook shows the label it stored: the first setVisibility on the stored register after the store.
                val shows = mutableMapOf<Method, Int>()
                for (writer in writers.filter { it.type != view }) for (method in writer.methods) {
                    val code = method.implementation?.instructions?.toList() ?: continue
                    code.forEachIndexed { put, instruction ->
                        if (instruction.opcode != Opcode.IPUT_OBJECT || !isLabel(fieldOf(instruction))) return@forEachIndexed
                        val stored = (instruction as TwoRegisterInstruction).registerA
                        val show = (put + 1 until code.size).firstOrNull { isSetVisibility(code[it]) && registersOf(code[it]).first() == stored }
                        if (show != null) shows[method] = show
                    }
                }
                assertTrue("$name: something stores the label and shows it", shows.isNotEmpty())

                val context = PatchContexts.of((owners + gestureViews + writers).distinctBy { it.type } +
                    ExtensionDex.classDef(SETTINGS_STATUS))
                doubleTapSkipPatch.execute(context)

                var swapped = 0
                for (writer in (gestureViews + writers).distinctBy { it.type }) for (method in writer.methods) {
                    val code = method.implementation?.instructions?.toList() ?: continue
                    if (code.none { isLabel(fieldOf(it)) }) continue
                    val body = context.mutableClassDefBy(writer.type).methods.single { same(it, method) }
                        .implementation!!.instructions.toList()
                    val where = "$name: ${writer.type}->${method.name}"
                    val inMethod = mutableSetOf<Int>()
                    body.forEachIndexed { at, instruction ->
                        if ((instruction as? ReferenceInstruction)?.reference?.toString() == SKIP_LABEL_CALL) {
                            assertTrue("$where: the label call at $at stands in for a setVisibility", isSetVisibility(code[at]))
                            assertEquals("$where: the label call at $at's form", Opcode.INVOKE_STATIC, instruction.opcode)
                            assertEquals("$where: the label call at $at passes Facebook's registers",
                                registersOf(code[at]), registersOf(instruction))
                            inMethod += at
                        }
                    }
                    assertEquals("$where: everything else as Facebook wrote it",
                        code.indices.filterNot { it in inMethod }.map { code[it].opcode },
                        body.indices.filterNot { it in inMethod }.map { body[it].opcode })
                    assertEquals("$where: the same length", code.size, body.size)
                    shows[method]?.let { assertTrue("$where: the show at $it asks the extension, swapped $inMethod", it in inMethod) }
                    if (writer.type == view) assertTrue("$where: the gesture view keeps its own calls", inMethod.isEmpty())
                    swapped += inMethod.size
                }
                assertTrue("$name: the patch swapped every show it was found to need, found $swapped", swapped >= shows.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
