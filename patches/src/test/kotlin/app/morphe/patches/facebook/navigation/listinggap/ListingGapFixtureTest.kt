/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.listinggap

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remove the gap above Marketplace listings on every Facebook build the bundle declares: the SafeArea
 * module for React Native is there under its kept name with one static method turning insets into
 * the map, safeAreaInsets calls it after reading the decor's window insets, and the decor listener it
 * makes calls it with the view it was set on still in its first parameter. After the patch every one
 * of those calls hands the insets and that view to the extension first and reads back what it
 * answers, and Facebook's own code runs on unchanged. React Native's SafeAreaView is there too, and
 * the insets listener it sets when attached hands the insets it read and the view to the extension
 * right after casting to it. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ListingGapFixtureTest {
    @Test
    fun `each declared build hands every safe area to the extension with its decor view`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val module = FixtureDex.classes(bundle, setOf(SAFE_AREA))[SAFE_AREA]
                assertNotNull("${bundle.name}: $SAFE_AREA", module)
                val map = module!!.methods.single(::isInsetsMap)
                val read = module.methods.single { it.name == SAFE_AREA_READ && it.implementation != null }
                val made = FixtureDex.classes(bundle, madeBy(read).toSet()).values
                val listeners = made.flatMap { it.methods }
                    .filter { method -> method.implementation?.instructions?.any { callsInsetsMap(it, map) } == true }
                assertTrue("${bundle.name}: a listener made in $SAFE_AREA_READ turns insets into the map", listeners.isNotEmpty())
                for (listener in listeners) {
                    assertEquals("${bundle.name}: ${listener.definingClass}->${listener.name}", "invoke", listener.name)
                    val view = listener.implementation!!.registerCount - 2
                    for ((_, _, decor) in insetsMapCalls(listener, map)) {
                        assertEquals("${bundle.name}: the listener's view is its first parameter", view, decor)
                    }
                }

                val manager = FixtureDex.classes(bundle, setOf(SAFE_AREA_VIEW_MANAGER))[SAFE_AREA_VIEW_MANAGER]
                assertNotNull("${bundle.name}: $SAFE_AREA_VIEW_MANAGER", manager)
                val viewType = safeAreaViewType(manager!!)
                val safeAreaView = FixtureDex.classes(bundle, setOf(viewType))[viewType]
                assertNotNull("${bundle.name}: $viewType", safeAreaView)
                val attached = safeAreaView!!.methods.single { it.name == "onAttachedToWindow" && it.implementation != null }
                val viewListenerClasses = FixtureDex.classes(bundle, madeBy(attached).toSet()).values
                val viewListeners = viewListenerClasses.flatMap { it.methods }
                    .filter { isInsetsListener(it) && safeAreaViewReads(it, viewType).isNotEmpty() }
                assertTrue("${bundle.name}: an insets listener pads $viewType", viewListeners.isNotEmpty())

                val context = PatchContexts.of(
                    listOf(module, manager, safeAreaView) + made + viewListenerClasses + ExtensionDex.classDef(SETTINGS_STATUS),
                )
                removeListingGapPatch.execute(context)
                for (original in viewListeners) {
                    val patched = context.mutableClassDefBy(original.definingClass).methods
                        .single { MethodUtil.methodSignaturesMatch(it, original) }
                    val before = original.implementation!!.instructions.toList()
                    val code = patched.implementation!!.instructions.toList()
                    val reads = safeAreaViewReads(original, viewType)
                    assertEquals("${bundle.name}: ${original.definingClass} gains three instructions per cast",
                        before.size + 3 * reads.size, code.size)
                    assertEquals("${bundle.name}: ${original.definingClass} runs Facebook's own code around the hook",
                        before.map { it.opcode }, code.filterIndexed { at, _ -> at !in hookIndexes(code) }.map { it.opcode })
                    // Each earlier cast's hook moves the later ones three further down.
                    for ((earlier, read) in reads.sortedBy { it.first }.withIndex()) {
                        val (at, insets, view) = read
                        val cast = at + 3 * earlier
                        val trim = code[cast + 1] as FiveRegisterInstruction
                        assertEquals("${bundle.name}: the view's insets go to the extension right after the cast",
                            TRIM_VIEW, ((trim as ReferenceInstruction).reference as MethodReference).toString())
                        assertEquals("${bundle.name}: with the insets it read", insets, trim.registerC)
                        assertEquals("${bundle.name}: and the view it cast", view, trim.registerD)
                        assertEquals("${bundle.name}: the answer goes back where the insets were",
                            insets, (code[cast + 2] as OneRegisterInstruction).registerA)
                        assertEquals("${bundle.name}: cast back to Insets",
                            INSETS, ((code[cast + 3] as ReferenceInstruction).reference as TypeReference).type)
                    }
                }
                var hooked = 0
                for (original in listOf(read) + listeners) {
                    val patched = context.mutableClassDefBy(original.definingClass).methods
                        .single { MethodUtil.methodSignaturesMatch(it, original) }
                    val before = original.implementation!!.instructions.toList()
                    val code = patched.implementation!!.instructions.toList()
                    val calls = insetsMapCalls(original, map)
                    assertTrue("${bundle.name}: ${original.name} turns insets into the map", calls.isNotEmpty())
                    assertEquals("${bundle.name}: ${original.name} gains three instructions per call",
                        before.size + 3 * calls.size, code.size)
                    assertEquals("${bundle.name}: ${original.name} runs Facebook's own code around the hook",
                        before.map { it.opcode }, code.filterIndexed { at, _ -> at !in hookIndexes(code) }.map { it.opcode })
                    for (at in code.indices.filter { callsInsetsMap(code[it], map) }) {
                        val trim = code[at - 3] as FiveRegisterInstruction
                        assertEquals("${bundle.name}: ${original.name} asks the extension first",
                            TRIM, ((trim as ReferenceInstruction).reference as MethodReference).toString())
                        val insets = (code[at] as FiveRegisterInstruction).registerC
                        assertEquals("${bundle.name}: ${original.name} hands over the insets the map reads", insets, trim.registerC)
                        assertEquals("${bundle.name}: ${original.name} reads the answer back",
                            Opcode.MOVE_RESULT_OBJECT, code[at - 2].opcode)
                        assertEquals("${bundle.name}: into the same register", insets, (code[at - 2] as OneRegisterInstruction).registerA)
                        assertEquals("${bundle.name}: ${original.name} casts it back to Insets",
                            INSETS, ((code[at - 1] as ReferenceInstruction).reference as TypeReference).type)
                        hooked++
                    }
                }
                assertEquals("${bundle.name}: every call hooked", (listOf(read) + listeners).sumOf { insetsMapCalls(it, map).size }, hooked)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The indexes of the three instructions each of the patch's hooks puts in [code]. */
    private fun hookIndexes(code: List<Instruction>): Set<Int> = code.indices.filter {
        code[it].opcode == Opcode.INVOKE_STATIC &&
            ((code[it] as ReferenceInstruction).reference as MethodReference).toString() in setOf(TRIM, TRIM_VIEW)
    }.flatMap { listOf(it, it + 1, it + 2) }.toSet()
}
