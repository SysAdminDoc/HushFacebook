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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #37's link box when MobileConfig sends no colour for it, on each declared build. With the
 * config's parse already Material You's, the readers whose empty string falls through take their
 * dark colour from a prop a parent hands them (unboxed by a static Object to int call) or from a
 * second config's grey (an Integer's intValue); each of those goes to the dark-branch stand-in on
 * the register the parse answers into, once. FDS's light or dark chooser answers its last parameter
 * when its dark check says yes, and that answer goes to the same stand-in while the light one stays;
 * the attachment footer's box is one of its callers.
 */
class LinkBoxFallbackFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.call(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.register(): Int = (this as OneRegisterInstruction).registerA

    private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    /**
     * What each declared build's fallbacks unbox, sorted. Up to 585 FDSPostLinkAttachment's render
     * also unboxed a colour its parent handed it, with a static (Object) int call. 586's starts from a
     * token route one themes instead, which leaves the second config's grey in a render and in the
     * attachment footer, which jumps to it now rather than falling through. 586's Android 9 build
     * unboxes one of them with a static null-safe (Number, int) call that answers the int for null.
     */
    private val unboxingsByVersion = mapOf(
        // 587's footer (arm64 `LX/094a;->A00`) and renders read the grey the way 586's did.
        "587.0.0.39.55" to listOf("intValue", "intValue"),
        "586.0.0.52.53" to listOf("intValue", "intValue"),
    )
    private val unboxingsAndroid9 = mapOf(
        "586.0.0.52.53" to listOf("intValue", "static (Number, int)"),
    )

    /**
     * A build whose ABIs unbox differently, by ABI. 587's armeabi-v7a unboxes one of them with the
     * static null-safe (Number, int) call too, as 586's Android 9 build did.
     */
    private val unboxingsByAbi = mapOf(
        "587.0.0.39.55" to mapOf("armeabi-v7a" to listOf("intValue", "static (Number, int)")),
    )

    @Test
    fun `a link box's fallback colours and the chooser's dark answer go to the dark-branch stand-in`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val owners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any(::readsLinkBoxColour)) owners += ImmutableClassDef.of(classDef)
                    }
                }
                val darkCheck = fdsDarkCheckOf(bundle)
                val pool = owners + darkCheckWrappers(bundle, owners.flatMap { it.methods }.filter(::readsLinkBoxColour), darkCheck)

                for (amoled in listOf(false, true)) {
                    val themes = "$name, AMOLED $amoled"
                    val context = PatchContexts.of(pool)
                    val before = mutableMapOf<String, List<Instruction>>()
                    with(context) {
                        if (amoled) rerouteColourCalls(AMOLED_COLOUR_CALLS)
                        rerouteColourCalls(YOU_COLOUR_CALLS)
                        hookLinkBoxColour(darkCheck)
                        owners.forEach { owner -> mutableClassDefBy(owner.type).methods.forEach { before[it.descriptor()] = it.body() } }
                    }
                    val sites = before.mapValues { (descriptor, _) ->
                        linkBoxFallbacks(context.mutableClassDefBy(descriptor.substringBefore("->")).methods.single { it.descriptor() == descriptor })
                    }.filterValues { it.isNotEmpty() }
                    val changed = with(context) { hookLinkBoxFallbacks() }
                    assertEquals("$themes: each fallback found is changed", sites.values.sumOf { it.size }, changed)
                    assertEquals("$themes: a second pass finds them in place", 0, with(context) { hookLinkBoxFallbacks() })

                    val unboxings = mutableListOf<String>()
                    for ((descriptor, indexes) in sites) {
                        val original = before.getValue(descriptor)
                        val patched = context.mutableClassDefBy(descriptor.substringBefore("->")).methods
                            .single { it.descriptor() == descriptor }.body()
                        assertEquals("$themes: $descriptor gains two instructions a site", original.size + 2 * indexes.size, patched.size)
                        val parse = original.indexOfFirst { it.call()?.toString() == PARSE_LINK_BOX }
                        val colour = original[parse + 1].register()
                        indexes.forEachIndexed { done, index ->
                            val at = index + 2 * done
                            val answer = original[index - 1].call()!!
                            assertEquals("$themes: $descriptor's fallback answers an int", "I", answer.returnType)
                            assertEquals("$themes: into the parse's register", colour, original[index].register())
                            assertEquals("$themes: the stand-in follows", DARK_BRANCH, patched[at + 1].call()?.toString())
                            val range = patched[at + 1] as RegisterRangeInstruction
                            assertEquals("$themes: on that register", listOf(colour, 1), listOf(range.startRegister, range.registerCount))
                            assertEquals("$themes: its answer kept", Opcode.MOVE_RESULT, patched[at + 2].opcode)
                            assertEquals("$themes: in that register", colour, patched[at + 2].register())
                            val static = original[index - 1].opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
                            val parameters = answer.parameterTypes.map(CharSequence::toString)
                            unboxings += when {
                                static && parameters == listOf("Ljava/lang/Object;") -> "static (Object)"
                                static && parameters == listOf("Ljava/lang/Number;", "I") -> "static (Number, int)"
                                else -> "${answer.definingClass}->${answer.name}"
                            }
                        }
                    }
                    assertTrue("$themes: the second config's grey is unboxed by intValue ($unboxings)",
                        unboxings.any { it == "Ljava/lang/Number;->intValue" || it == "Ljava/lang/Integer;->intValue" })
                    val android9 = name.substringAfter("-$version-").contains("-minapi")
                    val abi = name.substringAfter("-$version-").substringBefore(".apkm")
                    val expected = unboxingsByAbi[version]?.get(abi) ?: (if (android9) unboxingsAndroid9 else unboxingsByVersion)[version]
                    assertTrue("$themes: no unboxings pinned for $version", expected != null)
                    assertEquals("$themes: what the fallbacks unbox",
                        expected, unboxings.map { if (it.endsWith("->intValue")) "intValue" else it }.sorted())
                }

                // FDS's chooser, on the dark check's class.
                val resolverType = darkCheck.substringBefore("->")
                val resolver = FixtureDex.classes(bundle, setOf(resolverType)).getValue(resolverType)
                val picker = darkPicker(resolver.methods)
                val pickerDescriptor = picker.descriptor()
                assertTrue("$name: a link box reader picks its colour with $pickerDescriptor",
                    owners.flatMap { it.methods }.filter(::readsLinkBoxColour).any { reader ->
                        reader.body().any { it.call()?.toString() == pickerDescriptor }
                    })
                val original = picker.body()
                val dark = picker.implementation!!.registerCount - 1
                val answer = darkPickReturn(picker)
                assertEquals("$name: the dark side returns the last parameter", dark, original[answer].register())
                assertTrue("$name: an instance method", !AccessFlags.STATIC.isSet(picker.accessFlags))

                val context = PatchContexts.of(listOf(resolver))
                with(context) {
                    hookDarkPick(darkCheck)
                    hookDarkPick(darkCheck)
                }
                val patched = context.mutableClassDefBy(resolverType).methods.single { it.descriptor() == pickerDescriptor }.body()
                assertEquals("$name: two instructions, once", original.size + 2, patched.size)
                assertEquals("$name: the stand-in on the dark side", DARK_BRANCH, patched[answer].call()?.toString())
                val range = patched[answer] as RegisterRangeInstruction
                assertEquals("$name: on the dark colour's register", listOf(dark, 1), listOf(range.startRegister, range.registerCount))
                assertEquals("$name: its answer kept", Opcode.MOVE_RESULT, patched[answer + 1].opcode)
                assertEquals("$name: returned", listOf(Opcode.RETURN, dark), listOf(patched[answer + 2].opcode, patched[answer + 2].register()))
                assertEquals("$name: the light side stays", 1, patched.count { it.call()?.toString() == DARK_BRANCH })
                assertEquals("$name: Facebook's code stays", original.map { it.opcode },
                    patched.take(answer).map { it.opcode } + patched.drop(answer + 2).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
