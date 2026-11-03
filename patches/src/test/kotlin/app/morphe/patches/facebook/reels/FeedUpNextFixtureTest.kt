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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' UP NEXT hooks (#367) on every Facebook build the bundle declares (arm64 and armv7):
 * one class holds the Up next query in a static lookup, and after the hook that lookup asks the
 * extension first and returns, and the class's (String)List getter asks first and answers an empty
 * list. One static setter stores the bar's room (#375), and after the hook it asks first and
 * returns before it writes. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FeedUpNextFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** Every class in [bundle] with a method holding the Up next query or storing the bar's room. */
    private fun holders(bundle: java.io.File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val strings = dex.stringSection.map(CharSequence::toString).toSet()
            if (UP_NEXT_QUERY !in strings && OVERLAID_ASPECT_RATIO !in strings) return@forEach
            dex.classes.filterTo(found) { classDef ->
                classDef.methods.any { holdsString(it, UP_NEXT_QUERY) } || upNextInsetSetters(classDef.methods).isNotEmpty()
            }
        }
        return found.map(ImmutableClassDef::of)
    }

    @Test
    fun `each declared build's Up next lookup and getter ask the extension first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = holders(bundle)
                val controllers = classes.filter { upNextLookups(it.type, it.methods).isNotEmpty() }
                assertEquals("$name: classes whose lookup holds $UP_NEXT_QUERY", 1, controllers.size)
                val controller = controllers.single()
                val lookup = upNextLookups(controller.type, controller.methods).single()
                val getter = upNextGetters(controller.methods).singleOrNull()
                assertNotNull("$name: ${controller.type} has no single (String)List getter", getter)
                // Negative control: a lookup that takes some other class isn't the controller's.
                assertTrue("$name: a lookup for another class was taken", upNextLookups("LX/other;", controller.methods).isEmpty())
                val setters = classes.flatMap { classDef -> upNextInsetSetters(classDef.methods).map { classDef to it } }
                assertEquals("$name: static (…FI)V methods calling $OVERLAID_ASPECT_RATIO", 1, setters.size)
                val (setterOwner, setter) = setters.single()
                // Negative control: the aspect ratio method itself takes two Floats and answers one, so it isn't taken.
                assertTrue("$name: the aspect ratio method was taken",
                    upNextInsetSetters(setterOwner.methods.filter { it.name == OVERLAID_ASPECT_RATIO }).isEmpty())
                assertTrue("$name: the setter writes no Float field", setter.body().any { instruction ->
                    instruction.opcode == Opcode.IPUT_OBJECT &&
                        ((instruction as ReferenceInstruction).reference as FieldReference).type == "Ljava/lang/Float;"
                })

                val context = PatchContexts.of(classes)
                with(context) { hideUpNext() }
                val methods = context.mutableClassDefBy(controller.type).methods
                fun Method.params() = parameterTypes.map(CharSequence::toString)
                val hookedSetter = context.mutableClassDefBy(setterOwner.type).methods
                    .single { it.name == setter.name && it.params() == setter.params() }.body()
                assertEquals("$name: the room setter asks first", SKIP_UP_NEXT_ROOM, hookedSetter[0].called())
                assertEquals("$name: tests the answer", Opcode.IF_EQZ, hookedSetter[2].opcode)
                assertEquals("$name: and returns before it writes", Opcode.RETURN_VOID, hookedSetter[3].opcode)
                assertEquals("$name: the rest of the setter is Facebook's", setter.body().map { it.opcode }, hookedSetter.drop(4).map { it.opcode })
                val hookedLookup = methods.single { it.name == lookup.name && it.params() == lookup.params() }.body()
                assertEquals("$name: the lookup asks first", SKIP_UP_NEXT_FETCH, hookedLookup[0].called())
                assertEquals("$name: tests the answer", Opcode.IF_EQZ, hookedLookup[2].opcode)
                assertEquals("$name: and returns before the query", Opcode.RETURN_VOID, hookedLookup[3].opcode)
                assertEquals("$name: the rest of the lookup is Facebook's", lookup.body().map { it.opcode }, hookedLookup.drop(4).map { it.opcode })

                val hookedGetter = methods.single { it.name == getter!!.name && it.params() == getter.params() }.body()
                assertEquals("$name: the getter asks first", HIDE_UP_NEXT_VIDEOS, hookedGetter[0].called())
                assertEquals("$name: tests the answer", Opcode.IF_EQZ, hookedGetter[2].opcode)
                assertEquals("$name: answers an empty list", "Ljava/util/Collections;->emptyList()Ljava/util/List;", hookedGetter[3].called())
                assertEquals("$name: and returns it", Opcode.RETURN_OBJECT, hookedGetter[5].opcode)
                assertEquals("$name: the rest of the getter is Facebook's", getter!!.body().map { it.opcode }, hookedGetter.drop(6).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), abis)
    }

    @Test
    fun `the extension has every Up next hook`() {
        for ((hookName, descriptor) in listOf(
            "skipUpNextFetch" to SKIP_UP_NEXT_FETCH, "hideUpNextVideos" to HIDE_UP_NEXT_VIDEOS, "skipUpNextRoom" to SKIP_UP_NEXT_ROOM,
        )) {
            val hook = ExtensionDex.classDef(descriptor.substringBefore("->")).methods.singleOrNull { it.name == hookName }
            assertNotNull("the extension has no ReelDeclutter.$hookName", hook)
            assertEquals("$hookName's shape", descriptor,
                "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
            assertTrue("$hookName isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
        }
    }
}
