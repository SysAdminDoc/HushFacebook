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
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' whole reel hook for the feed's Reels row (#228) on every Facebook build the bundle
 * declares (arm64 and armv7): one tile render reads the preview clip through the model's getters
 * (which implement the three preview field hashes) and builds a player, every other method calling
 * the clip helper is turned down, and after the hook the render hands
 * its last clip check and its end to the extension right where they're used. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FeedReelPreviewsFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** Every class in [bundle] with a method calling the clip helper. */
    private fun callers(bundle: java.io.File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(found) { classDef -> classDef.methods.any(::callsClipHelper) }
        }
        return found.map(ImmutableClassDef::of)
    }

    /** Every class in [bundle] implementing the interface that declares [getters]. */
    private fun implementers(bundle: java.io.File, getters: List<MethodReference>): List<ClassDef> {
        val declaring = getters.map { it.definingClass }.distinct().single()
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex -> dex.classes.filterTo(found) { declaring in it.interfaces } }
        return found.map(ImmutableClassDef::of)
    }

    /** [classDef] with [getter]'s const loading [key] instead of its field's. */
    private fun loadingKey(classDef: ClassDef, getter: MethodReference, key: Int): ClassDef = ImmutableClassDef(
        classDef.type, classDef.accessFlags, classDef.superclass, classDef.interfaces, classDef.sourceFile,
        classDef.annotations, classDef.fields,
        classDef.methods.map { method ->
            val code = method.implementation
            if (method.name != getter.name || method.parameterTypes.isNotEmpty() || code == null) return@map method
            ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags,
                method.annotations, method.hiddenApiRestrictions,
                ImmutableMethodImplementation(
                    code.registerCount,
                    code.instructions.map { instruction ->
                        if (instruction.opcode != Opcode.CONST) instruction
                        else ImmutableInstruction31i(Opcode.CONST, (instruction as OneRegisterInstruction).registerA, key)
                    },
                    code.tryBlocks,
                    code.debugItems,
                ),
            )
        },
    )

    @Test
    fun `each declared build's Reels row tile hands its clip check and end to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = callers(bundle)
                val readingMethods = classes.flatMap { owner -> owner.methods.filter(::callsClipHelper).map { owner to it } }
                val taken = readingMethods.filter { (_, method) -> previewClipPoints(method) != null }
                assertEquals("$name: methods the patch takes", 1, taken.size)
                // Negative control: the other render that sets a clip up builds no player from a
                // VideoDataSource, so it is turned down.
                assertTrue("$name: only one method calls the clip helper, so nothing was turned down", readingMethods.size > 1)
                val (tile, render) = taken.single()
                val points = previewClipPoints(render)!!
                val original = render.body()
                // The getters the render asks are the three preview fields: every class implementing them reads those hashes.
                val getters = previewModelGetters(original)
                assertEquals("$name: preview getters", PREVIEW_FIELDS.size, getters.size)
                val implementing = implementers(bundle, getters)
                val implemented = previewGetterKeys(implementing, getters)
                assertTrue("$name: no class implements the preview getters", implemented.isNotEmpty())
                implemented.forEach { assertEquals("$name: an implementer reads the preview fields", PREVIEW_FIELDS.map(String::hashCode).toSet(), it) }
                // Negative control: with nothing to show what the getters read, the patch refuses.
                assertThrows("$name: patched without the getters' implementers", PatchException::class.java) {
                    with(PatchContexts.of(listOf(tile))) { playFeedReelsWhole() }
                }
                // Negative control: one implementer's getter loads some other field's key, so the
                // doubles render reads aren't all the preview clip, and the patch refuses.
                val changed = implementing.first { previewGetterKeys(listOf(it), getters).isNotEmpty() }
                val swapped = loadingKey(changed, getters.first(), "not_a_preview_field".hashCode())
                val refused = assertThrows("$name: patched with an implementer loading another key", PatchException::class.java) {
                    with(PatchContexts.of(listOf(tile) + implementing.map { if (it.type == changed.type) swapped else it })) { playFeedReelsWhole() }
                }
                assertTrue(refused.message, refused.message.orEmpty().contains("don't all load the keys"))
                // Positive control: a build whose implementers answer a fourth double beside the
                // three preview fields still passes; one missing a preview field's key doesn't.
                val fourth = "a_fourth_double".hashCode()
                assertTrue("$name: a fourth double getter was refused", loadsPreviewKeys(implemented.map { it + fourth }))
                assertTrue("$name: an implementer missing a preview key passed",
                    !loadsPreviewKeys(implemented.mapIndexed { at, it -> if (at == 0) it - it.first() + fourth else it }))
                assertEquals("$name: the store is an int field of the builder", Opcode.IPUT, original[points.store].opcode)
                assertEquals("$name: the end is stored twice", (original[points.store + 1] as TwoRegisterInstruction).registerA, points.end)

                val context = PatchContexts.of(listOf(tile) + implementing)
                with(context) { playFeedReelsWhole() }
                val hooked = context.mutableClassDefBy(tile.type).methods.single {
                    it.name == render.name && it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
                }.body()
                assertEquals("$name: asks about the clip", PREVIEW_CLIP_ALLOWED, hooked[points.gate].called())
                assertEquals("$name: takes the answer back", points.gateAnswer, (hooked[points.gate + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: and Facebook branches on it", Opcode.IF_EQZ, hooked[points.gate + 2].opcode)
                val store = points.store + 2
                assertEquals("$name: asks about the end", PREVIEW_CLIP_END, hooked[store].called())
                assertEquals("$name: takes the end back", points.end, (hooked[store + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: and stores it", Opcode.IPUT, hooked[store + 2].opcode)
                assertEquals("$name: then stores it again", Opcode.IPUT, hooked[store + 3].opcode)
                val rest = hooked.filterIndexed { at, _ -> at != points.gate && at != points.gate + 1 && at != store && at != store + 1 }
                assertEquals("$name: the rest is Facebook's", original.map { it.opcode }, rest.map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hooks`() {
        for (hook in listOf(PREVIEW_CLIP_ALLOWED, PREVIEW_CLIP_END)) {
            val type = hook.substringBefore("->")
            val name = hook.substringAfter("->").substringBefore("(")
            val method = ExtensionDex.classDef(type).methods.singleOrNull { it.name == name }
            assertNotNull("the extension has no ReelDeclutter.$name", method)
            assertEquals("$name's shape", hook,
                "${method!!.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}")
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }
}
