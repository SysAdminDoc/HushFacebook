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
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
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
}
