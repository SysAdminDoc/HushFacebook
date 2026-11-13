/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.analytics

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.reels.watchhistory.callRegisters
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Format
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every anchor of Hold back analytics uploads, on every declared Facebook build, and the facts the
 * hooks rest on: each XAnalytics upload call is the only one in the app, nothing jumps onto it past
 * the check, and the Papaya gate's off path is the one that returns false without starting a job.
 */
class AnalyticsUploadsFixtureTest {
    @Test
    fun `each declared build has every anchor once`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                checkPapaya(bundle)
                checkXAnalytics(bundle)
                checkAnalytics2Retry(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun checkAnalytics2Retry(bundle: File) {
        val resolve = tableResolver(bundle)
        val matches = FixtureDex.classesHolding(bundle, ANALYTICS2_JOB_RAN).flatMap { owner ->
            owner.methods.mapNotNull { method -> analytics2RetryValue(method, resolve)?.let { method to it } }
        }
        assertEquals("${bundle.name}: Analytics2 retry stores", 1, matches.size)
        val (method, index) = matches.single()
        assertObservationOnly(method, index, resolve)
    }

    /** Finds a static call's method in [bundle], reading each class once: how a string table is asked for its literal. */
    private fun tableResolver(bundle: File): (MethodReference) -> Method? {
        val read = HashMap<String, ClassDef?>()
        return { call ->
            read.getOrPut(call.definingClass) { FixtureDex.classes(bundle, setOf(call.definingClass))[call.definingClass] }
                ?.let { resolveStatic(it, call) }
        }
    }

    private fun assertObservationOnly(method: Method, index: Int, resolve: (MethodReference) -> Method? = { null }) {
        val original = method.implementation!!.instructions.toList()
        val value = original[index].callRegisters()[2]
        val patched = MutableMethod(method).apply { observeAnalytics2Retry(index, resolve) }
        val instructions = patched.implementation!!.instructions.toList()
        val observer = instructions[index + 1]
        assertEquals(method.implementation!!.registerCount, patched.implementation!!.registerCount)
        assertEquals(original.size + 1, instructions.size)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, observer.opcode)
        assertEquals(OBSERVE_ANALYTICS2_RETRY, (observer as ReferenceInstruction).reference.toString())
        assertEquals(listOf(value), observer.callRegisters())
        val retained = instructions.filterIndexed { at, _ -> at != index + 1 }
        assertEquals(original.map { it.opcode }, retained.map { it.opcode })
        assertEquals(original.map { (it as? ReferenceInstruction)?.reference?.toString() },
            retained.map { (it as? ReferenceInstruction)?.reference?.toString() })
    }

    private val retryStore = """
        const-string/jumbo v16, "will_retry"
        invoke-virtual/range {v15 .. v17}, Landroid/os/BaseBundle;->putBoolean(Ljava/lang/String;Z)V
    """.trimIndent()

    private fun retryMethod(store: String = retryStore) = MutableMethod(
        ImmutableMethod("Lfixture/Analytics2;", "finish", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(18, emptyList(), null, null)),
    ).apply {
        addInstructionsWithLabels(0, """
            const-string v0, "$ANALYTICS2_JOB_RAN"
            const-string v0, "exitStateMachine"
            new-instance v15, Landroid/os/Bundle;
            invoke-direct/range {v15 .. v15}, Landroid/os/Bundle;-><init>()V
            const/16 v17, 0x1
            if-eqz v17, :finished
            $store
            :finished
            return-void
        """)
    }

    @Test
    fun `retry observation reads a high register after the store and preserves the bypass branch`() {
        val method = retryMethod()
        val index = analytics2RetryValue(method)!!
        assertObservationOnly(method, index)
        method.observeAnalytics2Retry(index)
        val code = method.implementation!!.instructions.toList()
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val branch = code.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertEquals(addresses[code.lastIndex], addresses[branch] + (code[branch] as OffsetInstruction).codeOffset)
        assertEquals(Opcode.RETURN_VOID, code.last().opcode)
        assertEquals(listOf(17), code[index + 1].callRegisters())
    }

    @Test
    fun `retry observation rejects an ambiguous or differently typed store without changing it`() {
        for (store in listOf(
            "$retryStore\n$retryStore",
            retryStore.replace("will_retry", "other_flag"),
            retryStore.replace("const-string/jumbo v16", "const-string/jumbo v14"),
            retryStore.replace("Ljava/lang/String;Z", "Ljava/lang/String;I"),
        )) {
            assertNull(analytics2RetryValue(retryMethod(store)))
        }
        val method = retryMethod()
        val before = method.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { method.observeAnalytics2Retry(0) }
        assertEquals(before.size, method.implementation!!.instructions.count())
    }

    /** A string table answering "will_retry" for 0x1ea, as 587's arm64 `LX/0fSL;->A00` does. */
    private val strings = MutableMethod(
        ImmutableMethod("Lfixture/Strings;", "A00", listOf(ImmutableMethodParameter("I", null, null)), "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(2, emptyList(), null, null)),
    ).apply {
        addInstructionsWithLabels(0, """
            packed-switch p0, :cases
            const-string v0, "unused"
            return-object v0
            :retry
            const-string v0, "will_retry"
            return-object v0
            :cases
            .packed-switch 0x1ea
                :retry
            .end packed-switch
        """)
    }

    private val tableLookup: (MethodReference) -> Method? = { call ->
        strings.takeIf { call.definingClass == it.definingClass && call.name == it.name }
    }

    private fun tableStore(index: String) = """
        const/16 v16, $index
        invoke-static/range {v16 .. v16}, Lfixture/Strings;->A00(I)Ljava/lang/String;
        move-result-object v16
        invoke-virtual/range {v15 .. v17}, Landroid/os/BaseBundle;->putBoolean(Ljava/lang/String;Z)V
    """.trimIndent()

    @Test
    fun `retry observation finds a key the store reads from a string table`() {
        val method = retryMethod(tableStore("0x1ea"))
        val index = analytics2RetryValue(method, tableLookup)
        assertNotNull("the store after the table's answer", index)
        assertNull("without the table the key is unknown", analytics2RetryValue(method))
        assertObservationOnly(method, index!!, tableLookup)
        method.observeAnalytics2Retry(index, tableLookup)
        assertEquals(listOf(17), method.implementation!!.instructions.elementAt(index + 1).callRegisters())
    }

    @Test
    fun `retry observation rejects a table answer that isn't the retry key`() {
        for (store in listOf(
            tableStore("0x1eb"),
            tableStore("0x1ea").replace("move-result-object v16", "move-result-object v14"),
        )) {
            assertNull(analytics2RetryValue(retryMethod(store), tableLookup))
        }
    }

    private fun checkPapaya(bundle: File) {
        // The job's start is the most derived onStartJob along FBPapayaJobService's superclasses.
        var type: String? = PAPAYA_SERVICE
        var start: Method? = null
        while (type != null && start == null) {
            val classDef = FixtureDex.classes(bundle, setOf(type))[type] ?: break
            start = classDef.methods.singleOrNull(::isJobStart)
            type = classDef.superclass
        }
        assertNotNull("${bundle.name}: no Papaya job start", start)
        val job = start!!
        assertTrue("${bundle.name}: the job start isn't synchronized", AccessFlags.DECLARED_SYNCHRONIZED.isSet(job.accessFlags))
        val gate = papayaGate(job) { FixtureDex.classes(bundle, setOf(it))[it] }
        assertNotNull("${bundle.name}: no Papaya gate in ${job.definingClass}->onStartJob", gate)
        val instructions = job.implementation!!.instructions.toList()
        // Nothing is started before the gate: no job is handed to an executor ahead of the answer.
        assertTrue(
            "${bundle.name}: the job start runs work before the gate",
            instructions.take(gate!!).none { executes(it) },
        )
        assertTrue("${bundle.name}: the job start hands nothing to an executor after the gate", instructions.drop(gate).any { executes(it) })
        assertEquals("${bundle.name}: methods loading \"$PAPAYA_CONFIG\"", 1,
            FixtureDex.classesHolding(bundle, PAPAYA_CONFIG).sumOf { c -> c.methods.count { m -> holds(m, PAPAYA_CONFIG) } })
    }

    private fun checkXAnalytics(bundle: File) {
        val kept = FixtureDex.classes(bundle, setOf(APP_JOB_HANDLER, LOW_PRIORITY_INIT))
        val handler = kept[APP_JOB_HANDLER]
        val init = kept[LOW_PRIORITY_INIT]
        assertNotNull("${bundle.name}: no $APP_JOB_HANDLER", handler)
        assertNotNull("${bundle.name}: no $LOW_PRIORITY_INIT", init)
        val built = handler!!.methods.flatMap { method ->
            method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
                .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        }.toSet()
        val runnables = FixtureDex.classes(bundle, built)
        val uploads = kickOffUploads(handler) { runnables[it] }
        assertEquals("${bundle.name}: foreground uploads", 1, uploads.size)
        val resumes = resumeUploads(init!!)
        assertEquals("${bundle.name}: uploader resumes", 1, resumes.size)

        for ((method, index) in uploads + resumes) {
            val call = method.implementation!!.instructions.elementAt(index)
            val free = method.findFreeRegister(index, call.callRegisters())
            assertFalse("${bundle.name}: ${method.name} borrows a register its call reads", free in call.callRegisters())
            assertFalse("${bundle.name}: a branch in ${method.definingClass}->${method.name} lands on the call", branchedTo(method, index))
        }

        // The two calls are the only ones in the app: the hooks cover every upload Java starts.
        for (name in listOf(KICK_OFF_UPLOAD, RESUME_UPLOADING)) {
            val callers = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                dex.methodSection.any { it.definingClass == XANALYTICS && it.name == name }
            }) { method ->
                method.implementation?.instructions?.any { instruction ->
                    val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.definingClass == XANALYTICS && ref.name == name
                } == true
            }
            assertEquals("${bundle.name}: callers of $name", 1, callers.size)
        }
    }

    /** Whether [instruction] hands a runnable to an executor. */
    private fun executes(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Boolean {
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return call.name == "execute" && call.parameterTypes.map { it.toString() } == listOf(RUNNABLE)
    }

    private fun holds(method: Method, literal: String): Boolean =
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)
                ?.string == literal
        } == true

    /** Whether a goto or an if in [method] lands on the instruction at [index]. */
    private fun branchedTo(method: Method, index: Int): Boolean {
        val instructions = method.implementation!!.instructions.toList()
        val addresses = instructions.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        return instructions.indices.any { at ->
            val branch = instructions[at] as? OffsetInstruction ?: return@any false
            instructions[at].opcode.format in BRANCHES && addresses[at] + branch.codeOffset == addresses[index]
        }
    }

    /** The formats of goto (10t, 20t, 30t) and if (21t, 22t); 31t is a switch or array payload. */
    private val BRANCHES = setOf(Format.Format10t, Format.Format20t, Format.Format30t, Format.Format21t, Format.Format22t)
}
