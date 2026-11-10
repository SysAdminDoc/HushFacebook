/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' hook on the Shorts viewer a feed video opens in (#375), on every Facebook build
 * the bundle declares (arm64 and armv7): one static pill shower names both UP NEXT pill kinds in its
 * label switch, and after the hook it hands the extension the container, the kind and those two kinds
 * first and returns when told to. The 2x speed pill's kind is never one of them. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ShortsUpNextFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.params() = parameterTypes.map(CharSequence::toString)

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** Every class in [bundle] with a method naming the next video pill. */
    private fun holders(bundle: java.io.File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.stringSection.none { it.toString() == NEXT_PILL }) return@forEach
            dex.classes.filterTo(found) { classDef -> classDef.methods.any { holdsString(it, NEXT_PILL) } }
        }
        return found.map(ImmutableClassDef::of)
    }

    @Test
    fun `each declared build's Shorts viewer asks before an UP NEXT pill`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = holders(bundle)
                val owners = classes.filter { upNextPillShowers(it.type, it.methods).isNotEmpty() }
                assertEquals("$name: classes whose pill shower names $NEXT_PILL", 1, owners.size)
                val owner = owners.single()
                val shower = upNextPillShowers(owner.type, owner.methods).single()
                // Negative control: a shower taking some other controller isn't this one.
                assertTrue("$name: a shower for another class was taken", upNextPillShowers("LX/other;", owner.methods).isEmpty())
                val peek = pillKinds(shower, PEEK_PILL).singleOrNull()
                val next = pillKinds(shower, NEXT_PILL).singleOrNull()
                assertNotNull("$name: no single $PEEK_PILL kind", peek)
                assertNotNull("$name: no single $NEXT_PILL kind", next)
                assertNotEquals("$name: the two UP NEXT kinds are one", peek, next)
                // Negative control: the 2x speed pill has its own kind, so holding to speed up still shows it.
                val speed = pillKinds(shower, "Playback2xSpeedPill").singleOrNull()
                assertNotNull("$name: no single 2x speed pill kind", speed)
                assertTrue("$name: the 2x speed pill would be hidden", speed !in setOf(peek, next))

                val context = PatchContexts.of(classes)
                with(context) { hideShortsUpNext() }

                val hookedMethod = context.mutableClassDefBy(owner.type).methods
                    .single { it.name == shower.name && it.params() == shower.params() }
                val hookedShower = hookedMethod.body()
                assertEquals("$name: hands the peek kind", peek, (hookedShower[0] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: and the next video kind", next, (hookedShower[1] as NarrowLiteralInstruction).narrowLiteral)
                // A static shower of three one-register parameters: the container is p0 and the kind p2.
                val registers = hookedMethod.implementation!!.registerCount
                for ((at, copied) in listOf(2 to registers - 3, 3 to registers - 1)) {
                    val move = hookedShower[at]
                    assertEquals("$name: copies a parameter down at $at", Opcode.MOVE_OBJECT_FROM16, move.opcode)
                    assertEquals("$name: into v$at", at, (move as TwoRegisterInstruction).registerA)
                    assertEquals("$name: from the right parameter at $at", copied, move.registerB)
                }
                val handed = hookedShower[4] as FiveRegisterInstruction
                assertEquals("$name: hands the container, the kind, then the two UP NEXT kinds", listOf(2, 3, 0, 1),
                    listOf(handed.registerC, handed.registerD, handed.registerE, handed.registerF).take(handed.registerCount))
                assertEquals("$name: the shower asks first", HIDE_UP_NEXT_PILL, hookedShower[4].called())
                assertEquals("$name: tests the answer", Opcode.IF_EQZ, hookedShower[6].opcode)
                assertEquals("$name: and returns before it shows the pill", Opcode.RETURN_VOID, hookedShower[7].opcode)
                // The assembler pads the switch payloads with a nop when the hook moves them off an even address.
                assertEquals("$name: the rest of the shower is Facebook's", shower.body().map { it.opcode }.filter { it != Opcode.NOP },
                    hookedShower.drop(8).map { it.opcode }.filter { it != Opcode.NOP })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the Shorts viewer's pill hook`() {
        val hook = ExtensionDex.classDef(HIDE_UP_NEXT_PILL.substringBefore("->")).methods.singleOrNull { it.name == "hideUpNextPill" }
        assertNotNull("the extension has no ReelDeclutter.hideUpNextPill", hook)
        assertEquals("hideUpNextPill's shape", HIDE_UP_NEXT_PILL,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideUpNextPill isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
