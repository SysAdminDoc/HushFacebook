/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #37's Create story tile on each declared build: the tile at the start of the stories tray
 * is #333334 under the account's picture, which Facebook writes right after its own dark check says
 * yes (582 `LX/3Nj;->invoke`, the AddToStoryTileSplitCardComponentSpec, and `LX/374;->render`).
 * Route three's dark-branch pass finds at least those two (or, on 588's Android 9 build, the one
 * static helper both tiles call), and every site it finds gets the
 * extension's call right after its `const`, on the same register. AMOLED reads them first for Black
 * cards (#191), as an all-patches build does, and Material You's call then goes in after AMOLED's.
 */
class DarkBranchLiteralFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    /** The Create story tile's grey, #333334, as a signed int. */
    private val cardGrey = -0xcccccc

    @Test
    fun `the Create story tile's grey is on Facebook's dark branch and each such colour goes to the extension`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val darkCheck = fdsDarkCheckOf(bundle)
                val owners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.darkBranchLiterals(darkCheck).isNotEmpty() }) owners += ImmutableClassDef.of(classDef)
                    }
                }
                val sites = owners.flatMap { owner -> owner.methods.flatMap { method -> method.darkBranchLiterals(darkCheck).map { method to it } } }
                val cards = sites.filter { (method, index) -> (method.body()[index] as NarrowLiteralInstruction).narrowLiteral == cardGrey }
                // 588's Android 9 build writes it once, in a static helper both tiles call (`LX/057M;->A00`, 2026-11-20).
                val sharedBy = cards.singleOrNull()?.first?.takeIf { AccessFlags.STATIC.isSet(it.accessFlags) }?.let { helper ->
                    FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() == helper.definingClass } }) { method ->
                        method.body().any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == helper.descriptor() }
                    }
                }.orEmpty()
                assertTrue("$name: the Create story tile's two #333334 writes, at least (${cards.map { it.first.descriptor() }}, " +
                    "shared by ${sharedBy.map { it.descriptor() }})", cards.size >= 2 || sharedBy.size >= 2)

                val context = PatchContexts.of(owners)
                assertEquals("$name: every site changed for AMOLED", sites.size, with(context) { readDarkBranchLiterals(darkCheck, AMOLED_DARK_BRANCH) })
                assertEquals("$name: every site changed", sites.size, with(context) { readDarkBranchLiterals(darkCheck) })
                // Each site ahead of another in the same method pushes it on by the four instructions added.
                val moved = sites.groupBy { it.first.descriptor() }.values.flatMap { inMethod ->
                    inMethod.sortedBy { it.second }.mapIndexed { ahead, (method, index) -> method to index + 4 * ahead }
                }
                for ((method, index) in moved) {
                    val after = context.mutableClassDefBy(method.definingClass).methods.single { it.descriptor() == method.descriptor() }.body()
                    assertEquals("$name: ${method.descriptor()} keeps its const", Opcode.CONST, after[index].opcode)
                    val register = (after[index] as OneRegisterInstruction).registerA
                    for ((at, target) in listOf(index + 1 to AMOLED_DARK_BRANCH, index + 3 to DARK_BRANCH)) {
                        val call = after[at]
                        assertEquals("$name: ${method.descriptor()} calls the extension", target, (call as ReferenceInstruction).reference.toString())
                        assertEquals("$name: on the const's register", register, (call as RegisterRangeInstruction).startRegister)
                        assertEquals("$name: then reads it back", Opcode.MOVE_RESULT, after[at + 1].opcode)
                        assertEquals("$name: into the same register", register, (after[at + 1] as OneRegisterInstruction).registerA)
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
