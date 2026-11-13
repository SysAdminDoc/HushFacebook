/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.upsells.CAPTION_DEEP_DIVE_PLUGIN
import app.morphe.patches.facebook.misc.upsells.IMAGINE_CTA_SOCKET
import app.morphe.patches.facebook.misc.upsells.captionDeepDiveGetter
import app.morphe.patches.facebook.misc.upsells.ctaCheck
import app.morphe.patches.facebook.misc.upsells.ctaTable
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Meta AI questions under posts' anchor on every Facebook build the bundle declares: the one
 * method loading DeepDivePillSocket and GenAiDeepDivePillPlugin's class name, with every plugin
 * name in one register and the per-plugin check called twice, each answer in its own move-result.
 * Then the patch on it: after each answer, the extension gets the answer and the plugin's name, and
 * its own answer lands back in the register the branch reads. In the socket's default way of
 * drawing a pill, the type read ahead of the stars compare goes to the extension, a yes returns no
 * pill, and every path from the meta_ai compare passes that hook before it returns anything. The
 * call-to-action selector's check asks the extension about each plugin first (Meta AI's question
 * chips under photos are one of its table's answers), and so does the caption deep dive's getter.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HideMetaAiQuestionsFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build has the pill socket once, and each check's answer goes through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, META_AI_PILL).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val sockets = owners.flatMap { owner ->
                    owner.methods.filter { method ->
                        method.implementation?.instructions?.any { holds(it, META_AI_PILL) } == true &&
                            method.implementation?.instructions?.any { holds(it, PILL_SOCKET) } == true
                    }
                }
                assertEquals("$name: methods loading $PILL_SOCKET and the Meta AI plugin", 1, sockets.size)
                val socket = pillSocket(sockets.single())
                val original = socket.method.code()

                // Every pill plugin the socket names, Meta AI's among them, goes to the one register.
                val plugins = original.filter { holdsPrefix(it, PILL_PLUGINS) }
                assertTrue("$name: the socket names only ${plugins.size} plugins", plugins.size >= 5)
                assertTrue("$name: Meta AI's plugin isn't one of them", plugins.any { holds(it, META_AI_PILL) })
                assertEquals("$name: two calls of the per-plugin check", 2, socket.answers.size)
                val pill = defaultPill(socket.method)
                assertEquals("$name: the default way's type register and the stars name's", expectedPill[version],
                    pill.typeRegister to pill.freeRegister)

                val owner = owners.single { it.type == socket.method.definingClass }

                // The call-to-action selector's table names Meta AI's question chips as a case's
                // answer, and its check and the caption deep dive's getter are found once each.
                val tableHolders = FixtureDex.classesHolding(bundle, META_AI_QUESTION_BUTTON)
                val table = ctaTable(tableHolders, META_AI_QUESTION_BUTTON, PATCH)
                val tableCode = table.code()
                val named = tableCode.indexOfFirst { holds(it, META_AI_QUESTION_BUTTON) }
                assertEquals("$name: the selector's table answers a case with the question chips' plugin",
                    Opcode.RETURN_OBJECT, tableCode[named + 1].opcode)
                val socketHolders = FixtureDex.classesHolding(bundle, IMAGINE_CTA_SOCKET)
                val ctaSockets = socketHolders.flatMap { methodsHolding(it, IMAGINE_CTA_SOCKET) }
                val calledClasses = FixtureDex.classes(bundle, ctaSockets.flatMap { method ->
                    method.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.map { it.definingClass }
                }.toSet())
                val check = ctaCheck(table, ctaSockets, PATCH) { calledClasses[it] }
                val captionPlugin = FixtureDex.classes(bundle, setOf(CAPTION_DEEP_DIVE_PLUGIN)).values.single()
                val captionGetter = captionDeepDiveGetter(captionPlugin, PATCH)

                val pool = (listOf(owner, ExtensionDex.classDef(SETTINGS_STATUS), captionPlugin) + tableHolders + socketHolders +
                    listOfNotNull(calledClasses[check.definingClass])).associateBy { it.type }.values
                val context = PatchContexts.of(pool)
                hideMetaAiQuestionsPatch.execute(context)

                val patchedMethod = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == socket.method.name && it.parameterTypes == socket.method.parameterTypes
                }
                val patched = patchedMethod.code()
                // The nop that aligns a switch payload comes and goes as code ahead of it moves (583's
                // arm64 build lost one here and its armeabi-v7a build gained one), so nops don't count.
                fun List<Instruction>.counted() = count { it.opcode != Opcode.NOP }
                assertEquals("$name: two instructions after each check and five for the default way",
                    original.counted() + 2 * socket.answers.size + 5, patched.counted())
                val calls = patched.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == KEEP }
                assertEquals("$name: calls of the extension", socket.answers.size, calls.size)
                for ((at, call) in calls) {
                    val answer = (patched[at - 1] as OneRegisterInstruction).registerA
                    assertTrue("$name: the call at $at doesn't follow a check's move-result",
                        patched[at - 1].opcode == Opcode.MOVE_RESULT && isPluginCheck(patched[at - 2], owner.type))
                    // The patcher's compiler drops an instruction whose registers don't fit, so
                    // the call has to be there with both registers, in order.
                    assertEquals("$name: the call at $at", Opcode.INVOKE_STATIC, call.opcode)
                    val registers = call as FiveRegisterInstruction
                    assertEquals("$name: registers handed over at $at", 2, registers.registerCount)
                    assertEquals("$name: the check's answer at $at", answer, registers.registerC)
                    assertEquals("$name: the plugin's name at $at", socket.nameRegister, registers.registerD)
                    assertEquals("$name: the extension's answer at $at", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                    assertEquals("$name: the extension's answer lands where the branch reads it", answer,
                        (patched[at + 1] as OneRegisterInstruction).registerA)
                }

                // The default way: the type goes to the extension once, and a yes returns no pill.
                val drops = patched.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == DROPS_DEFAULT_PILL }
                assertEquals("$name: calls of the default way's hook", 1, drops.size)
                val (hook, drop) = drops.single()
                assertEquals("$name: the default way's call", Opcode.INVOKE_STATIC, drop.opcode)
                assertEquals("$name: the type handed over", 1 to pill.typeRegister,
                    (drop as FiveRegisterInstruction).registerCount to drop.registerC)
                assertEquals("$name: right after the type's read", Opcode.MOVE_RESULT_OBJECT, patched[hook - 1].opcode)
                assertEquals(pill.typeRegister, (patched[hook - 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the default way's hook", listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                    patched.subList(hook + 1, hook + 5).map { it.opcode })
                assertEquals("$name: the registers the default way's hook writes", List(4) { pill.freeRegister },
                    patched.subList(hook + 1, hook + 5).map { (it as OneRegisterInstruction).registerA })
                assertEquals("$name: no pill", 0, (patched[hook + 3] as NarrowLiteralInstruction).narrowLiteral)
                assertTrue("$name: the stars compare after the hook", holds(patched[hook + 5], STARS_TYPE))
                val flow = ControlFlow.of(patchedMethod)
                assertEquals("$name: a no goes on to the stars compare", setOf(hook + 3, hook + 5), flow.normal[hook + 2].toSet())

                // A pill the default way gives Meta AI's icon, or the pill's own icon after the
                // meta_ai compare, goes through the hook before anything returns.
                val metaAi = patched.indexOfFirst { holds(it, META_AI_TYPE) }
                val (reaches, returns) = walk(flow, metaAi, hook)
                assertTrue("$name: the meta_ai compare never reaches the default way's hook", reaches)
                assertEquals("$name: returns the meta_ai compare reaches without the hook", emptyList<Int>(), returns)
                // The same walk in Facebook's own code, with no hook to stop at, finds the pill's returns.
                val unpatched = walk(ControlFlow.of(socket.method), original.indexOfFirst { holds(it, META_AI_TYPE) }, -1)
                assertTrue("$name: the walk finds no return in Facebook's own default way", unpatched.second.isNotEmpty())

                // The selector's check: the plugin's name from the table with the check's own number,
                // then the extension, a yes answers no, and Facebook's check runs on otherwise.
                fun patchedOf(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
                }.code()
                val guarded = patchedOf(check)
                val where = "$name: ${check.definingClass}->${check.name}"
                assertEquals("$where gains seven instructions", check.code().size + 7, guarded.size)
                assertEquals("$where: the plugin's name comes from the table", Opcode.INVOKE_STATIC_RANGE, guarded[0].opcode)
                assertEquals("$where: the plugin's name comes from the table", table.descriptor(), called(guarded[0]))
                assertEquals("$where: with the check's own number", check.implementation!!.registerCount - 1,
                    (guarded[0] as RegisterRangeInstruction).startRegister)
                assertEquals("$where: the extension is asked", HIDES_QUESTION_BUTTON, called(guarded[2]))
                assertEquals("$where: a yes answers no", listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
                    guarded.subList(3, 7).map { it.opcode })
                assertEquals("$where: Facebook's check stays", check.code().map { it.opcode }, guarded.drop(7).map { it.opcode })

                // The caption deep dive's getter asks first, answers null on a yes, and reads as before otherwise.
                val dived = patchedOf(captionGetter)
                assertEquals("$name: the caption deep dive getter asks first", HIDES_QUESTIONS_BELOW_CAPTION, called(dived[0]))
                assertEquals("$name: and returns null on a yes", Opcode.RETURN_OBJECT, dived[4].opcode)
                assertEquals("$name: the getter reads as before otherwise", captionGetter.code().map { it.opcode },
                    dived.drop(5).map { it.opcode })

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "metaAiQuestions" }
                assertEquals("$name: SettingsStatus.metaAiQuestions() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    /**
     * Where each declared build's default way keeps the pill's type, and the stars name's register:
     * 586 loaded the name into v0, 587 into v1 (arm64, as defaultPill reads it).
     */
    private val expectedPill = mapOf(
        "586.0.0.52.53" to (2 to 0),
        "587.0.0.39.55" to (2 to 1),
    )

    /**
     * Follows every normal path from [from], stopping at [hook]: whether one gets there, and the
     * returns reached without it.
     */
    private fun walk(flow: ControlFlow, from: Int, hook: Int): Pair<Boolean, List<Int>> {
        val seen = mutableSetOf<Int>()
        val queue = ArrayDeque(listOf(from))
        val returns = mutableListOf<Int>()
        var reaches = false
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (at == hook) {
                reaches = true
                continue
            }
            if (!seen.add(at)) continue
            if (flow.instructions[at].opcode == Opcode.RETURN_OBJECT) returns += at else queue += flow.normal[at]
        }
        return reaches to returns
    }

    private fun called(instruction: Instruction) = ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.descriptor()

    private fun holds(instruction: Instruction, string: String) =
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == string

    private fun holdsPrefix(instruction: Instruction, prefix: String) =
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string?.startsWith(prefix) == true
}
