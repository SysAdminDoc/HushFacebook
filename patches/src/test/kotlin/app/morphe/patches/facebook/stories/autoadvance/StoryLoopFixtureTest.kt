/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.autoadvance

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loop stories makes Facebook's own restart from the progress callback's class. Each declared
 * build's navigator holds exactly one run of it, and the callback is an instance method, so the
 * guard's p0 is the callback the restart's first call is made on.
 */
class StoryLoopFixtureTest {
    @Test
    fun `each declared build's navigator holds one restart the loop can make`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val owners = FixtureDex.classesHolding(bundle, AUTO_NAVIGATION)
                    .mapNotNull { owner -> autoAdvanceHook(owner)?.let { owner to it } }
                assertEquals("${bundle.name}: completion hook", 1, owners.size)
                val (owner, hook) = owners.single()
                val (callback, index) = hook
                assertFalse("${bundle.name}: the callback is static", AccessFlags.STATIC.isSet(callback.accessFlags))

                val navigator = navigatorOf(owner, callback, index)
                val route = restartRoute(navigator, owner.type)
                assertNotNull("${bundle.name}: Facebook's restart in ${navigator.name}", route)
                route!!
                assertTrue("${bundle.name}: the environment comes from the callback", route.environment.parameterTypes.isEmpty())
                assertEquals("${bundle.name}: the lookup takes the environment", listOf(route.environment.returnType),
                    route.lookup.parameterTypes.map { it.toString() })
                assertEquals("${bundle.name}: the reset is the lookup's", route.lookup.returnType, route.reset.definingClass)
                assertEquals("${bundle.name}: the reset takes a boolean", listOf("Z"), route.reset.parameterTypes.map { it.toString() })
                assertFalse("${bundle.name}: the class already has the loop helper", owner.methods.any { it.name == LOOP_HELPER })
                // The helper builds: the switch, the branch over the restart, the restart's calls with
                // a null check, the extension's log line, and a catch-all around the restart that
                // reports and returns.
                val body = loopHelper(owner.type, route).implementation!!
                val helper = body.instructions.toList()
                assertEquals("${bundle.name}: the helper's body", 15, helper.size)
                // A finished video plays again only with the reset's replay flag on (#380).
                assertEquals("${bundle.name}: the loop's reset replays", 1, resetFlag(helper, route))
                assertTrue("${bundle.name}: the loop logs its restart first", logsBeforeReset(helper, route))
                assertEquals("${bundle.name}: the restart starts at the environment getter",
                    Opcode.INVOKE_VIRTUAL, helper[LOOP_RESTART_FROM].opcode)
                assertEquals("${bundle.name}: the restart ends at the return it skips to",
                    Opcode.RETURN_VOID, helper[LOOP_RESTART_TO].opcode)
                val guard = body.tryBlocks.singleOrNull()
                assertNotNull("${bundle.name}: one catch around the restart", guard)
                assertEquals("${bundle.name}: the catch takes every throwable", null,
                    guard!!.exceptionHandlers.single().exceptionType)
                assertEquals("${bundle.name}: the handler reads the throwable", Opcode.MOVE_EXCEPTION,
                    helper[LOOP_RESTART_TO + 1].opcode)

                // The play button's replay makes the same restart with no switch, answering whether it
                // reached the reset (#380).
                assertFalse("${bundle.name}: the class already has the restart helper", owner.methods.any { it.name == RESTART_HELPER })
                val restart = restartHelper(owner.type, route).implementation!!.instructions.toList()
                assertEquals("${bundle.name}: the restart helper's body", 12, restart.size)
                assertEquals(Opcode.INVOKE_VIRTUAL, restart[0].opcode)
                assertEquals(Opcode.INVOKE_INTERFACE, restart[7].opcode)
                assertEquals(Opcode.RETURN, restart[9].opcode)
                assertEquals("${bundle.name}: the replay's reset replays", 1, resetFlag(restart, route))
                assertTrue("${bundle.name}: the replay logs its restart first", logsBeforeReset(restart, route))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Why the helpers reset with the flag on (#380). Facebook's reset keeps its boolean while it
     * tells its listeners, and the story video's listener reads it: with it on (or on a surface
     * whose launch config loops) the listener clears the video's finished state before asking it
     * to play from the start. With it off a finished video stays finished, its bar full and its
     * frame still, while a photo's timer restarts either way.
     */
    @Test
    fun `each declared build's story video plays again on a reset with its flag on`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val (owner, hook) = FixtureDex.classesHolding(bundle, AUTO_NAVIGATION)
                    .mapNotNull { owner -> autoAdvanceHook(owner)?.let { owner to it } }.single()
                val route = restartRoute(navigatorOf(owner, hook.first, hook.second), owner.type)!!

                val resets = mutableListOf<Method>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (route.reset.definingClass !in classDef.interfaces) continue
                        classDef.methods.filter {
                            it.name == route.reset.name && it.returnType == "V" &&
                                it.parameterTypes.map(Any::toString) == listOf("Z")
                        }.mapTo(resets) { ImmutableMethod.of(it) }
                    }
                }
                assertEquals("${bundle.name}: the reset's implementations", 1, resets.size)
                val code = resets.single().implementation!!
                val reset = code.instructions.toList()
                val self = code.registerCount - 2
                val flag = reset.filter { it.opcode == Opcode.IPUT_BOOLEAN }
                    .singleOrNull { (it as TwoRegisterInstruction).registerA == self + 1 && it.registerB == self }
                    ?.let { (it as ReferenceInstruction).reference as FieldReference }
                assertNotNull("${bundle.name}: the reset keeps its flag", flag)
                val told = reset.filter { it.opcode == Opcode.INVOKE_INTERFACE }
                    .map { (it as ReferenceInstruction).reference as MethodReference }
                    .filter { it.parameterTypes.isEmpty() && it.returnType == "V" }
                    .distinctBy { "${it.definingClass}->${it.name}" }
                assertEquals("${bundle.name}: the listeners the reset tells", 1, told.size)
                val listener = told.single()

                val readers = mutableListOf<List<Instruction>>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (listener.definingClass !in classDef.interfaces) continue
                        for (method in classDef.methods) {
                            if (method.name != listener.name || method.parameterTypes.isNotEmpty()) continue
                            val body = method.implementation?.instructions?.toList() ?: continue
                            if (body.any { it.opcode == Opcode.IGET_BOOLEAN && (it as ReferenceInstruction).reference == flag }) {
                                readers += ImmutableMethod.of(method).implementation!!.instructions.toList()
                            }
                        }
                    }
                }
                assertTrue("${bundle.name}: a reset listener reads the flag", readers.isNotEmpty())
                assertTrue("${bundle.name}: the listener clears a finished state after reading the flag", readers.any { body ->
                    val read = body.indexOfFirst { it.opcode == Opcode.IGET_BOOLEAN && (it as ReferenceInstruction).reference == flag }
                    body.drop(read + 1).any { it.opcode == Opcode.IPUT_BOOLEAN }
                })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The literal a helper hands Facebook's reset, read off the instruction that last set its boolean register. */
    private fun resetFlag(helper: List<Instruction>, route: RestartRoute): Int? {
        val call = helper.indexOfFirst { (it as? ReferenceInstruction)?.reference == route.reset }
        if (call < 0) return null
        val register = (helper[call] as FiveRegisterInstruction).registerD
        return helper.subList(0, call).lastOrNull { (it as? OneRegisterInstruction)?.registerA == register }
            ?.let { (it as? NarrowLiteralInstruction)?.narrowLiteral }
    }

    /** Whether a helper hands the callback and the reset's owner to the extension's log line before the reset. */
    private fun logsBeforeReset(helper: List<Instruction>, route: RestartRoute): Boolean {
        val call = helper.indexOfFirst { (it as? ReferenceInstruction)?.reference == route.reset }
        val log = helper.indexOfFirst {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            it.opcode == Opcode.INVOKE_STATIC && ref?.definingClass == STORY_ADVANCE && ref.name == "restarting" &&
                ref.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
        }
        if (call < 0 || log < 0 || log > call) return false
        val owner = (helper[call] as FiveRegisterInstruction).registerC
        return (helper[log] as FiveRegisterInstruction).registerD == owner
    }
}
