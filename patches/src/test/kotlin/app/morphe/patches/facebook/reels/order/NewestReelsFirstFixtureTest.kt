/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.order

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Newest reels first's two hooks on every Facebook build the bundle declares: the Reels ranker's
 * one rerank, which sorts its List parameter in place, and each createSignalForModel, whose model
 * has one FeedType field and an edge getter. Then the patch itself, run on those classes, with the
 * list handed over as the rerank starts and the done call in front of every return (branches into a
 * return included), and the signal hook in front of every return of each signal maker,
 * leaving the returned register alone. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class NewestReelsFirstFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    @Test
    fun `each declared build has one rerank and readable signal makers, and the patch hooks every return`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name
                val rankers = FixtureDex.classesHolding(fixture, RANKER_TAG)
                val reranks = rankers.flatMap { owner -> owner.methods.mapNotNull { m -> rerankList(m)?.let { m to it } } }
                assertEquals("$name: reranks", 1, reranks.size)
                val (rerank, list) = reranks.single()
                // The hook reads the List parameter (this, the context, then the list) as the rerank
                // starts, and Facebook's own code sorts it somewhere (585 also sorts a copy). 586 writes a
                // long over the register late on one branch, which the start hook doesn't mind.
                assertEquals("$name: the list register is the List parameter",
                    rerank.implementation!!.registerCount - 2, list)
                assertTrue("$name: the rerank has no (List, Comparator) sort", rerank.code().any { it.isListSort() })
                assertTrue("$name: the rerank never writes its List parameter", rerank.code().none { instruction ->
                    instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA == list
                })

                val makers = FixtureDex.methodsWhere(fixture, { dex -> dex.stringSection.any { it == SIGNAL_MAKER } }, ::isSignalMaker)
                assertTrue("$name: no signal maker", makers.isNotEmpty())
                val wanted = makers.flatMap { maker ->
                    listOfNotNull(maker.definingClass, maker.parameterTypes[0].toString(), castModel(maker))
                }.toSet()
                val classes = FixtureDex.classes(fixture, wanted)
                assertEquals("$name: classes missing", emptySet<String>(), wanted - classes.keys)
                for (maker in makers) {
                    assertNotNull("$name: ${maker.definingClass}->${maker.name} unread",
                        signalReads(maker) { classes[it] })
                }

                val owners: List<ClassDef> = (rankers + classes.values).distinctBy { it.type }
                val context = PatchContexts.of(owners + ExtensionDex.classDef(SETTINGS_STATUS))
                newestReelsFirstPatch.execute(context)
                fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }

                // The rerank now starts by handing the extension the list, before anything can write its
                // register, and every return is the done call, then the return, with no branch skipping it.
                val sorted = patched(rerank).code()
                val returns = rerank.code().count { it.opcode == Opcode.RETURN_VOID }
                assertEquals("$name: the first call", RERANKING, sorted.first().call.toString())
                assertEquals("$name: the first call's register", listOf(list), sorted.first().callRegisters())
                assertEquals("$name: start calls", 1, sorted.count { it.call.toString() == RERANKING })
                assertEquals("$name: rerank returns", returns, sorted.count { it.opcode == Opcode.RETURN_VOID })
                assertEquals("$name: done calls", returns, sorted.count { it.call.toString() == RERANKED })
                for ((index, instruction) in sorted.withIndex()) {
                    if (instruction.opcode != Opcode.RETURN_VOID) continue
                    assertEquals("$name: the call before return $index", RERANKED, sorted[index - 1].call.toString())
                }
                assertTrue("$name: a branch still lands on a return", sorted.filterIsInstance<BuilderOffsetInstruction>()
                    .none { it.target.location.instruction?.opcode == Opcode.RETURN_VOID })
                assertEquals("$name: the rerank lost its own code", rerank.code().count { it.opcode != Opcode.NOP } + returns + 1,
                    sorted.count { it.opcode != Opcode.NOP })

                // Every return of each signal maker gets the hook: the model's FeedType goes to
                // records, a no skips to the return, a yes reads the edge and hands the FeedType, the
                // edge and the returned signal to posted. The returned register is never written.
                for (maker in makers) {
                    val reads = signalReads(maker) { classes[it] }!!
                    val code = patched(maker).code()
                    val answers = maker.code().filter { it.opcode == Opcode.RETURN_OBJECT }
                        .map { (it as OneRegisterInstruction).registerA }
                    assertEquals("$name: ${maker.definingClass} records calls", answers.size,
                        code.count { it.call.toString() == RECORDS })
                    assertEquals("$name: ${maker.definingClass} posted calls", answers.size,
                        code.count { it.call.toString() == POSTED })
                    for ((index, instruction) in code.withIndex()) {
                        if (instruction.opcode != Opcode.RETURN_OBJECT) continue
                        val answer = (instruction as OneRegisterInstruction).registerA
                        val hook = code.subList(index - 12, index)
                        assertEquals("$name: ${maker.definingClass} hook shape", listOf(
                            Opcode.MOVE_OBJECT_FROM16, Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
                            Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
                            Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.NOP,
                        ), hook.drop(1).map { it.opcode })
                        val model = (hook[1] as TwoRegisterInstruction).registerA
                        assertEquals("$name: the model is the parameter",
                            patched(maker).implementation!!.registerCount - 1, (hook[1] as TwoRegisterInstruction).registerB)
                        assertEquals("$name: the FeedType field", reads.feedType,
                            ((hook[3] as ReferenceInstruction).reference as FieldReference).toString())
                        assertEquals("$name: records", RECORDS, hook[4].call.toString())
                        assertSame("$name: a no goes to the return", hook[11],
                            (hook[6] as BuilderOffsetInstruction).target.location.instruction)
                        val edgeCall = hook[7].call!!
                        val edgeReader = context.mutableClassDefBy(edgeCall.definingClass).methods.single {
                            it.name == edgeCall.name && it.parameterTypes == edgeCall.parameterTypes
                        }
                        assertNotNull("$name: the added lazy edge read must have its own failure boundary",
                            edgeReader.implementation)
                        val edgeBody = edgeReader.implementation!!
                        val getter = edgeBody.instructions.first { it.call.toString() == reads.edge }
                        val address = edgeBody.instructions.takeWhile { it !== getter }.sumOf { it.codeUnits }
                        assertTrue("$name: a throwing lazy edge escapes signal creation", edgeBody.tryBlocks.any {
                            address >= it.startCodeAddress && address < it.startCodeAddress + it.codeUnitCount &&
                                it.exceptionHandlers.any { handler -> handler.exceptionType == null || handler.exceptionType == "Ljava/lang/Throwable;" }
                        })
                        assertEquals("$name: only the optional getter is protected", listOf(reads.edge),
                            edgeBody.instructions.filter { it.opcode == Opcode.INVOKE_INTERFACE }.map { it.call.toString() })
                        val handler = edgeBody.tryBlocks.single().exceptionHandlers.single()
                        val edgeCode = edgeBody.instructions.toList()
                        assertEquals("$name: the handler returns no edge", listOf(
                            Opcode.MOVE_EXCEPTION, Opcode.INVOKE_STATIC, Opcode.CONST_4, Opcode.RETURN_OBJECT,
                        ), edgeCode.takeLast(4).map { it.opcode })
                        assertEquals("$name: the failure is reported", EDGE_FAILED, edgeCode[4].call.toString())
                        assertEquals("$name: the catch excludes native signal creation", edgeCode[0].codeUnits + edgeCode[1].codeUnits,
                            edgeBody.tryBlocks.single().codeUnitCount)
                        assertEquals("$name: the handler starts with move-exception",
                            edgeCode.take(3).sumOf { it.codeUnits }, handler.handlerCodeAddress)
                        assertEquals("$name: the edge's model", listOf(model), hook[7].callRegisters())
                        assertEquals("$name: the signal copy", answer, (hook[9] as TwoRegisterInstruction).registerB)
                        assertEquals("$name: posted", POSTED, hook[10].call.toString())
                        val written = hook.drop(1).filter { it.opcode.setsRegister() }
                            .map { (it as OneRegisterInstruction).registerA }
                        assertTrue("$name: the hook writes the returned v$answer", answer !in written)
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
