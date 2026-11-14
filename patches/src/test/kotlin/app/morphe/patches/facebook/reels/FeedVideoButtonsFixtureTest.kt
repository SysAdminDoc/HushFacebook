/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Clean up Reels' feed video buttons hook (#478) on every Facebook build the bundle declares (arm64
 * and armv7): one plugin descriptor names itself for the full-screen button and one for each
 * picture-in-picture button, and after the hook each include check asks the extension first and
 * answers false when told to, with Facebook's own check kept behind it. A build missing one of
 * them changes none. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FeedVideoButtonsFixtureTest {
    private val hooks = mapOf(INLINE_FULLSCREEN_TOGGLE to HIDE_FEED_VIDEO_FULLSCREEN) + POPOUT_BUTTONS.associateWith { HIDE_FEED_VIDEO_PIP }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** Opcodes with payload-alignment NOPs dropped and a widened goto read as a goto. */
    private fun List<Instruction>.kinds() = filter { it.opcode != Opcode.NOP }.map {
        when (it.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> it.opcode
        }
    }

    /** Every class in [bundle] with a method that loads one of the descriptors' names, by name. */
    private fun holding(bundle: java.io.File): Map<String, List<ClassDef>> {
        val found = hooks.keys.associateWith { mutableListOf<ClassDef>() }
        FixtureDex.forEach(bundle) { dex ->
            for ((name, classes) in found) {
                if (dex.stringSection.none { it == name }) continue
                dex.classes.filterTo(classes) { classDef -> classDef.methods.any { holdsString(it, name) } }
            }
        }
        return found.mapValues { (_, classes) -> classes.map(ImmutableClassDef::of) }
    }

    @Test
    fun `each declared build's feed video buttons ask the extension first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val held = holding(bundle)
                val descriptors = held.mapValues { (descriptor, classes) ->
                    val taken = classes.filter { includeCheck(it) != null }
                    assertEquals("$name: descriptors named \"$descriptor\" with an include check", 1, taken.size)
                    taken.single()
                }
                for ((descriptor, owner) in descriptors) {
                    val check = includeCheck(owner)!!
                    assertTrue("$name: $descriptor's include check takes the player origin",
                        check.parameterTypes.any { it.toString() == PLAYER_ORIGIN })
                    val locals = check.implementation!!.registerCount - check.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }.sum() - 1
                    assertTrue("$name: $descriptor's include check has no local for the answer", locals >= 1)
                    // Negative control: the same class without its include check isn't taken.
                    val noCheck = ImmutableClassDef(
                        owner.type, owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile, owner.annotations,
                        owner.staticFields, owner.instanceFields, owner.directMethods,
                        owner.virtualMethods.filter { it.name != check.name || it.parameterTypes.map(Any::toString) != check.parameterTypes.map(Any::toString) },
                    )
                    assertNull("$name: a $descriptor with no include check was taken", includeCheck(noCheck))
                }

                val context = PatchContexts.of(held.values.flatten().distinctBy { it.type })
                with(context) { hideFeedVideoButtons() }
                for ((descriptor, owner) in descriptors) {
                    val stock = includeCheck(owner)!!
                    val hooked = context.mutableClassDefBy(owner.type).methods.single {
                        it.name == stock.name && it.parameterTypes.map(Any::toString) == stock.parameterTypes.map(Any::toString)
                    }.body()
                    assertEquals("$name: $descriptor asks first", hooks.getValue(descriptor), hooked[0].called())
                    assertEquals("$name: $descriptor keeps the answer", Opcode.MOVE_RESULT, hooked[1].opcode)
                    assertEquals("$name: $descriptor tests it", Opcode.IF_EQZ, hooked[2].opcode)
                    assertEquals("$name: $descriptor loads false", Opcode.CONST_4, hooked[3].opcode)
                    assertEquals("$name: $descriptor in the register it answers",
                        (hooked[3] as OneRegisterInstruction).registerA, (hooked[4] as OneRegisterInstruction).registerA)
                    assertEquals("$name: $descriptor answers it when told to", Opcode.RETURN, hooked[4].opcode)
                    assertEquals("$name: the rest of $descriptor's check is Facebook's", stock.body().kinds(), hooked.drop(5).kinds())
                }
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `a build missing one button changes none`() {
        val version = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.first()
        val bundle = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }.firstOrNull() ?: return
        val held = holding(bundle)
        val kept = held.filterKeys { it != POPOUT_BUTTONS.last() }.values.flatten().distinctBy { it.type }
        val context = PatchContexts.of(kept)
        try {
            with(context) { hideFeedVideoButtons() }
            fail("${bundle.name}: a build without \"${POPOUT_BUTTONS.last()}\" was patched")
        } catch (expected: PatchException) {
            assertTrue(expected.message, expected.message!!.contains(POPOUT_BUTTONS.last()))
        }
        for (owner in kept.filter { includeCheck(it) != null }) {
            val stock = includeCheck(owner)!!
            val after = context.mutableClassDefBy(owner.type).methods.single {
                it.name == stock.name && it.parameterTypes.map(Any::toString) == stock.parameterTypes.map(Any::toString)
            }.body()
            assertEquals("${bundle.name}: ${owner.type} changed though the patch stopped", stock.body().kinds(), after.kinds())
        }
    }

    @Test
    fun `the extension has the hooks`() {
        for (hook in setOf(HIDE_FEED_VIDEO_FULLSCREEN, HIDE_FEED_VIDEO_PIP)) {
            val type = hook.substringBefore("->")
            val method = hook.substringAfter("->").substringBefore("(")
            val found = ExtensionDex.classDef(type).methods.singleOrNull { it.name == method }
            assertNotNull("the extension has no ReelDeclutter.$method", found)
            assertEquals("$method's shape", hook,
                "${found!!.definingClass}->${found.name}(${found.parameterTypes.joinToString("")})${found.returnType}")
            assertTrue("$method isn't static", AccessFlags.STATIC.isSet(found.accessFlags))
        }
    }
}
