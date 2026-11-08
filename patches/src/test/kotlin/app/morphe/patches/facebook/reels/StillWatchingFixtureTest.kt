/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Clean up Reels' Skip "Are you still watching?" hook (#427) on every Facebook build the bundle
 * declares: the Reels auto-advance controller has one idle runnable and one idle check reading the
 * flag it sets, the countdown to the next video holds the advance back on the check's yes and the end
 * of a video asks it too (so the check really is what decides), and after the patch every answer the
 * check gives goes through the extension, with no branch reaching a return past it. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class StillWatchingFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun MethodReference.isMethod(method: Method) = definingClass == method.definingClass &&
        name == method.name && returnType == method.returnType &&
        parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)

    private fun FieldReference.isField(other: FieldReference) =
        definingClass == other.definingClass && name == other.name && type == other.type

    /** Every class in [bundle] that keeps a [controller] in a field of its own. */
    private fun holders(bundle: File, controller: String): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(found) { classDef ->
                !classDef.type.startsWith(EXTENSION_PACKAGE) && classDef.type != controller &&
                    classDef.fields.any { it.type == controller }
            }
        }
        return found.map(ImmutableClassDef::of).distinctBy { it.type }
    }

    @Test
    fun `each declared build's idle check answers through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val controllers = autoAdvanceControllers(
                    FixtureDex.classesHolding(bundle, AUTO_ADVANCE_IDLE)
                        .filter { !it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type },
                )
                assertEquals("$name: classes holding \"$AUTO_ADVANCE_IDLE\" and \"$DID_AUTO_ADVANCE\"", 1, controllers.size)
                val controller = controllers.single()
                val holders = holders(bundle, controller.type)
                val runnables = holders.mapNotNull { idleRunnable(it, controller.type) }
                assertEquals("$name: idle runnables for ${controller.type}", 1, runnables.size)
                val runnable = runnables.single()
                assertTrue("$name: the runnable's prompt isn't one of ${controller.type}'s methods",
                    controller.methods.any { runnable.prompt.isMethod(it) })
                val checks = idleChecks(controller, runnable.flag)
                assertEquals("$name: static checks reading ${runnable.flag.name}", 1, checks.size)
                val check = checks.single()

                // Positive control: the countdown to the next video asks the check and, on a yes,
                // marks the advance held back, the mark the idle runnable reads before it prompts.
                val countdowns = holders.filter { "Ljava/lang/Runnable;" in it.interfaces }.flatMap { it.methods }
                    .filter { it.name == "run" }.filter { run ->
                        val code = run.body()
                        code.indices.any { at ->
                            code[at].called()?.isMethod(check) == true && code.drop(at + 1).take(4).any {
                                it.opcode == Opcode.IPUT_BOOLEAN && it.field()?.isField(runnable.pending) == true
                            }
                        }
                    }
                assertEquals("$name: countdowns holding the advance back on ${check.name}'s yes", 1, countdowns.size)
                // And the end of a video asks it before it reads the mark.
                assertTrue("$name: no method of ${controller.type} asks ${check.name} and reads ${runnable.pending.name}",
                    controller.methods.any { method ->
                        val code = method.body()
                        code.any { it.called()?.isMethod(check) == true } &&
                            code.any { it.opcode == Opcode.IGET_BOOLEAN && it.field()?.isField(runnable.pending) == true }
                    })

                val original = check.body()
                val returns = original.count { it.opcode == Opcode.RETURN }
                assertTrue("$name: ${check.name} never returns", returns > 0)
                val pool = (listOf(controller, ExtensionDex.classDef(STILL_WATCHING)) + holders).distinctBy { it.type }
                val context = PatchContexts.of(pool)
                context.skipStillWatching()
                val patched = context.mutableClassDefBy(controller.type).methods
                    .single { check.isMethod(it) }.body()
                assertEquals("$name: two instructions before each return", original.size + 2 * returns, patched.size)
                patched.withIndex().filter { it.value.opcode == Opcode.RETURN }.forEach { (at, ret) ->
                    val answer = (ret as OneRegisterInstruction).registerA
                    val call = patched[at - 2]
                    assertEquals("$name: return $at isn't handed to the extension", STILL_WATCHING_IDLE, call.called()?.let(::described))
                    call as RegisterRangeInstruction
                    assertEquals("$name: return $at hands over its own answer", listOf(1, answer), listOf(call.registerCount, call.startRegister))
                    assertEquals("$name: return $at takes the extension's answer", Opcode.MOVE_RESULT, patched[at - 1].opcode)
                    assertEquals("$name: return $at returns the extension's answer", answer,
                        (patched[at - 1] as OneRegisterInstruction).registerA)
                }
                val added = patched.indices.filter { patched[it].called()?.let(::described) == STILL_WATCHING_IDLE }
                    .flatMap { listOf(it, it + 1) }.toSet()
                assertEquals("$name: the rest is Facebook's",
                    original.map { it.opcode }, patched.filterIndexed { index, _ -> index !in added }.map { it.opcode })
                // No branch lands on a return past the hook.
                val addresses = patched.runningFold(0) { address, instruction -> address + instruction.codeUnits }
                val returnAddresses = patched.indices.filter { patched[it].opcode == Opcode.RETURN }.map { addresses[it] }.toSet()
                patched.withIndex().filter { it.value is OffsetInstruction }.forEach { (at, branch) ->
                    val target = addresses[at] + (branch as OffsetInstruction).codeOffset
                    assertTrue("$name: the branch at $at skips the hook", target !in returnAddresses)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val hook = ExtensionDex.classDef(STILL_WATCHING).methods.singleOrNull { described(it) == STILL_WATCHING_IDLE }
        assertNotNull("the extension has no $STILL_WATCHING_IDLE", hook)
        assertTrue("the hook isn't public static",
            AccessFlags.STATIC.isSet(hook!!.accessFlags) && AccessFlags.PUBLIC.isSet(hook.accessFlags))
    }
}
