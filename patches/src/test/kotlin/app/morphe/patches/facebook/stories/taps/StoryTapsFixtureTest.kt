/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.taps

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story viewer taps on every declared build and ABI: the patch's own lookups find the tap
 * controller, its back and forward navigations, its pauser and the pause reason, and the gesture
 * listener. After the hooks the extension is asked in front of each navigation and a true answer
 * skips just that call, the listener has a double tap callback that hands the second lift to its
 * single tap, and the stubs read the pauser, pause, resume and load the reason.
 *
 * Read from 585 (2026-11-03): the controller writes "StoryViewerTapController.onSingleTap" into its
 * trace, and the enums keep TAP_BACKWARD, TAP_FORWARD, DRAGGING_IN_GESTURE_LISTENER and
 * PAUSE_STORY_VIEWER_ON_TAP_OF_PAUSE_BUTTON as their constants' names. Obfuscated names are never
 * written down, every one is found by shape.
 */
class StoryTapsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Instruction.reference() = (this as ReferenceInstruction).reference.toString()

    @Test
    fun `taps wait for the extension in front of both navigations, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, TAP_TRACE)
                val taps = holders.flatMap { c -> c.methods.filter(::isStoryTap) }
                assertEquals("$name: one story tap", 1, taps.size)
                val tap = taps.single()
                val calls = enumCalls(tap)
                val types = calls.map { it.enum }.toMutableSet()
                types += calls.map { it.call.definingClass }
                types += tap.implementation!!.instructions.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass
                }
                val listeners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    dex.classes.filter { c -> c.methods.any { handsTapTo(it, tap.name) } }.mapTo(listeners) { ImmutableClassDef.of(it) }
                }
                val supers = mutableMapOf<String, ClassDef>()
                var next = listeners.mapNotNull { it.superclass }.toSet()
                while (next.isNotEmpty()) {
                    val found = FixtureDex.classes(bundle, next)
                    supers += found
                    next = found.values.mapNotNull { it.superclass }.filter { it !in supers }.toSet()
                }
                val classes = (holders + FixtureDex.classes(bundle, types).values + listeners + supers.values +
                    ImmutableClassDef.of(ExtensionDex.classDef(STORY_TAPS))).distinctBy { it.type }
                val before = tap.implementation!!.instructions.toList()

                val context = PatchContexts.of(classes)
                val (route, listener) = with(context) {
                    val found = storyTapMethod()
                    assertEquals("$name: the lookup finds the same tap", "${tap.definingClass}->${tap.name}",
                        "${found.definingClass}->${found.name}")
                    val route = storyTapRoute(tap)
                    val listener = tapListener(tap)
                    mutableClassDefBy(tap.definingClass).methods
                        .single { it.name == tap.name && it.parameterTypes == tap.parameterTypes }.holdTaps(route.navigations)
                    mutableClassDefBy(listener.type).methods.add(secondTapCallback(listener.type))
                    fillStoryTapStubs(route)
                    route to listener
                }

                assertEquals("$name: a back and a forward navigation", 2, route.navigations.size)
                val hooked = context.mutableClassDefBy(tap.definingClass).methods
                    .single { it.name == tap.name && it.parameterTypes == tap.parameterTypes }
                val code = hooked.implementation!!.instructions.toList()
                assertEquals("$name: three instructions in front of each navigation", before.size + 6, code.size)
                val self = hooked.implementation!!.registerCount - 3
                route.navigations.forEachIndexed { hooksBefore, navigation ->
                    val at = navigation + 3 * hooksBefore
                    val call = code[at]
                    assertEquals("$name: the extension is asked first", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                    assertEquals("$name: its onTap", ON_TAP, call.reference())
                    assertEquals("$name: on this and the tap", listOf(2, self),
                        listOf((call as RegisterRangeInstruction).registerCount, call.startRegister))
                    assertEquals("$name: its answer is read", Opcode.MOVE_RESULT, code[at + 1].opcode)
                    val answer = (code[at + 1] as OneRegisterInstruction).registerA
                    assertTrue("$name: into a local", answer < self)
                    val jump = code[at + 2]
                    assertEquals("$name: a held tap jumps", Opcode.IF_NEZ, jump.opcode)
                    assertEquals("$name: on that answer", answer, (jump as OneRegisterInstruction).registerA)
                    assertEquals("$name: over just the navigation", at + 4, (jump as BuilderOffsetInstruction).target.location.index)
                    assertEquals("$name: which follows as it was", before[navigation].reference(), code[at + 3].reference())
                }

                val added = context.mutableClassDefBy(listener.type).methods.single { it.name == DOUBLE_TAP_EVENT }
                val callback = added.implementation!!.instructions.toList()
                assertEquals("$name: the double tap callback",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT,
                        Opcode.RETURN, Opcode.CONST_4, Opcode.RETURN),
                    callback.map { it.opcode })
                assertEquals("$name: asks the extension", SECOND_TAP, callback[0].reference())
                assertEquals("$name: and hands the lift to the listener's single tap",
                    "${listener.type}->onSingleTapUp(Landroid/view/MotionEvent;)Z", callback[3].reference())

                val stubs = context.mutableClassDefBy(STORY_TAPS).methods
                fun stub(stubName: String) = stubs.single { it.name == stubName }.implementation!!.instructions.toList()
                assertEquals("$name: the pauser stub",
                    listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT),
                    stub(PAUSER_OF).take(5).map { it.opcode })
                assertEquals("$name: tests the controller's class first", route.pauserField.substringBefore("->"),
                    stub(PAUSER_OF)[0].reference())
                assertEquals("$name: reads the controller's pauser", route.pauserField, stub(PAUSER_OF)[3].reference())
                assertEquals("$name: the pause stub", route.pause, stub(PAUSE_WITH)[2].reference())
                assertEquals("$name: the resume stub", route.resume, stub(RESUME_WITH)[2].reference())
                assertNotEquals("$name: pause and resume differ", route.pause, route.resume)
                assertEquals("$name: the reason stub", route.reason, stub(PAUSE_REASON)[0].reference())
                assertEquals("$name: the reason is the type pause takes",
                    route.pause.substringAfter("(").substringBefore(")"), route.reason.substringAfterLast(":"))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
