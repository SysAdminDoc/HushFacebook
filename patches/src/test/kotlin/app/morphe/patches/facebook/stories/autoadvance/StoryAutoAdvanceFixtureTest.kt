/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.autoadvance

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryAutoAdvanceFixtureTest {
    @Test
    fun `each declared build has one completion hook and a separate manual navigation path`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val hooks = FixtureDex.classesHolding(bundle, AUTO_NAVIGATION).mapNotNull(::autoAdvanceHook)
                assertEquals("${bundle.name}: completion hook", 1, hooks.size)
                val (callback, index) = hooks.single()
                val implementation = callback.implementation!!
                val locals = implementation.registerCount - 1 - callback.parameterTypes.size
                assertTrue("${bundle.name}: no local register", locals >= 1)
                assertTrue("${bundle.name}: navigation call missing", index < implementation.instructions.count())
                // Nothing reads v0 from the navigation call on, so the guard borrows it there.
                assertEquals("${bundle.name}: the guard's register", 0, callback.waitForTapRegister(index))

                // A touch release calls the same navigator once the stored progress is complete,
                // which moved a held story on whenever a finger lifted (#380). The patch asks the
                // extension there too, and skips only that call.
                val navigator = (implementation.instructions.elementAt(index) as ReferenceInstruction)
                    .reference as MethodReference
                fun callsNavigator(method: com.android.tools.smali.dexlib2.iface.Method) =
                    method.implementation?.instructions?.any { instruction ->
                        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        ref?.definingClass == navigator.definingClass && ref.name == navigator.name &&
                            ref.parameterTypes.map { it.toString() } == navigator.parameterTypes.map { it.toString() }
                    } == true
                val touchRoutes = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.methodSection.any { it.definingClass == navigator.definingClass && it.name == navigator.name }
                }) { method ->
                    method.parameterTypes.map { it.toString() } == listOf("Landroid/view/MotionEvent;") &&
                        callsNavigator(method)
                }
                assertEquals("${bundle.name}: manual touch route", 1, touchRoutes.size)

                val owner = FixtureDex.classes(bundle, setOf(callback.definingClass)).getValue(callback.definingClass)
                val holders = listOf(owner) + listOfNotNull(owner.superclass).mapNotNull {
                    FixtureDex.classes(bundle, setOf(it))[it]
                }
                val navigatorDef = owner.methods.single {
                    it.name == navigator.name &&
                        it.parameterTypes.map(Any::toString) == navigator.parameterTypes.map(Any::toString)
                }
                val releases = holders.flatMap { releaseNavigations(it, callback, navigatorDef) }
                assertEquals("${bundle.name}: touch release navigations", 1, releases.size)
                val (release, call) = releases.single()
                assertEquals("${bundle.name}: the release is the touch route", touchRoutes.single().name, release.name)
                assertEquals(touchRoutes.single().definingClass, release.definingClass)
                val after = release.implementation!!.instructions.elementAt(call + 1)
                assertEquals("${bundle.name}: what the guard skips to", Opcode.RETURN_VOID, after.opcode)
                release.waitForTapRegister(call)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has every hook and the restart stub`() {
        val stub = "$STORY_ADVANCE->$RESTART_STUB(Ljava/lang/Object;)Z"
        for (hook in listOf(HELD, PROGRESSED, stub, "$STORY_ADVANCE->waitForTap()Z", "$STORY_ADVANCE->loop()Z")) {
            val method = ExtensionDex.classDef(STORY_ADVANCE).methods.singleOrNull {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook
            }
            assertNotNull("the extension has no $hook", method)
            assertTrue("$hook isn't static", AccessFlags.STATIC.isSet(method!!.accessFlags))
        }
    }
}
