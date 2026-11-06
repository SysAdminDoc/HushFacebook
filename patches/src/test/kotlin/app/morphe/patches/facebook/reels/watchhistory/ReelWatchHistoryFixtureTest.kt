/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.watchhistory

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.literalReads
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The send point of Don't send reel watch history on every Facebook build the bundle declares, and
 * where the hook sits in it: after the batcher has emptied its queue and chosen and built the
 * mutation, and before the request is handed to the network layer, which only the send the hook
 * decides on ever does. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelWatchHistoryFixtureTest {
    private class Found {
        /** Every class with a method loading the mutation's name, and how many such methods. */
        val holders = mutableListOf<ClassDef>()
        var holdingMethods = 0

        /** Every class Redex left the send's name on, by type. */
        val sends = mutableMapOf<String, ClassDef>()

        /** Every class with a method loading [VIDEO_IDS], by type: 584 fills it in the input's constructor. */
        val inputs = mutableMapOf<String, ClassDef>()
    }

    private val paramSet = "Lcom/facebook/graphql/query/GraphQlQueryParamSet;"
    private val futures = setOf(
        "Lcom/google/common/util/concurrent/SettableFuture;",
        "Lcom/google/common/util/concurrent/ListenableFuture;",
    )

    /** One pass over the bundle; only the dex files holding either literal are walked. */
    private fun scan(bundle: File): Found {
        val found = Found()
        FixtureDex.forEach(bundle) { dex ->
            val strings = dex.stringSection.filterTo(HashSet()) { it == SEEN_STATE_MUTATION || it == SEEN_STATE_SEND || it == VIDEO_IDS }
            if (strings.isEmpty()) return@forEach
            for (classDef in dex.classes) {
                if (SEEN_STATE_MUTATION in strings) {
                    val holding = classDef.methods.count { holdsString(it, SEEN_STATE_MUTATION) }
                    if (holding > 0) {
                        found.holdingMethods += holding
                        found.holders += ImmutableClassDef.of(classDef)
                    }
                }
                if (SEEN_STATE_SEND in strings && redexOriginalName(classDef) == SEEN_STATE_SEND) {
                    found.sends[classDef.type] = ImmutableClassDef.of(classDef)
                }
                if (VIDEO_IDS in strings && classDef.methods.any { holdsString(it, VIDEO_IDS) }) {
                    found.inputs[classDef.type] = ImmutableClassDef.of(classDef)
                }
            }
        }
        return found
    }

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    /** An instruction as text: its opcode, registers, reference and any literal or offset. */
    private fun render(instruction: Instruction): String = buildString {
        append(instruction.opcode.name).append(' ').append(instruction.namedRegisters())
        (instruction as? ReferenceInstruction)?.let { append(' ').append(it.reference) }
        (instruction as? NarrowLiteralInstruction)?.let { append(" #").append(it.narrowLiteral) }
        (instruction as? WideLiteralInstruction)?.let { append(" #").append(it.wideLiteral) }
        (instruction as? OffsetInstruction)?.let { append(" ->").append(it.codeOffset) }
    }

    @Test
    fun `each declared build hands the seen-state send over once, after the mutation is chosen and before the network`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val found = scan(bundle)

                // The anchors: one method names the mutation, one class carries the send's Redex
                // name, and the patch's own lookup finds exactly one send point.
                assertEquals("$name: methods loading \"$SEEN_STATE_MUTATION\"", 1, found.holdingMethods)
                assertEquals("$name: classes Redex named $SEEN_STATE_SEND", 1, found.sends.size)
                val points = found.holders.flatMap { owner ->
                    owner.methods.mapNotNull { method ->
                        sendPoint(method) { found.sends[it] ?: found.inputs[it] }?.let { Triple(owner, method, it) }
                    }
                }
                assertEquals("$name: send points", 1, points.size)
                val (owner, flush, send) = points.single()
                val code = flush.implementation!!.instructions.toList()

                // The queue is emptied before anything else: every Set the batcher keeps is cleared,
                // and every clear comes before the mutation is named. A batch the hook holds back is
                // already out of the queue, so nothing keeps it for a later send. 585's Android 9
                // build keeps the helper's send apart from the flush, so there the batcher is the
                // send's one caller, a void method taking nothing, and every clear comes before it
                // calls the send.
                val named = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SEEN_STATE_MUTATION }
                val sameCall = { call: MethodReference? ->
                    call != null && call.definingClass == flush.definingClass && call.name == flush.name &&
                        call.parameterTypes.map { it.toString() } == flush.parameterTypes.map { it.toString() }
                }
                val (batcher, batcherCode, emptiedBy) = if (flush.parameterTypes.isEmpty()) {
                    Triple(owner, code, named)
                } else {
                    val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any(sameCall) }) { method ->
                        method.implementation?.instructions?.any { sameCall(it.call) } == true
                    }
                    assertEquals("$name: callers of the send", 1, callers.size)
                    val caller = callers.single()
                    assertTrue("$name: the flush isn't a void method taking nothing",
                        caller.returnType == "V" && caller.parameterTypes.isEmpty())
                    val callerCode = caller.implementation!!.instructions.toList()
                    val calls = callerCode.indices.filter { sameCall(callerCode[it].call) }
                    assertEquals("$name: the flush's calls to the send", 1, calls.size)
                    Triple(FixtureDex.classes(bundle, setOf(caller.definingClass)).getValue(caller.definingClass), callerCode, calls.single())
                }
                val queues = batcher.fields.filter { it.type == "Ljava/util/Set;" && !AccessFlags.STATIC.isSet(it.accessFlags) }
                val clears = batcherCode.indices.filter {
                    batcherCode[it].call?.let { c -> c.definingClass == "Ljava/util/Set;" && c.name == "clear" } == true
                }
                assertTrue("$name: the batcher keeps no queue", queues.isNotEmpty())
                assertEquals("$name: queues cleared", queues.size, clears.size)
                assertTrue("$name: a queue is cleared after the mutation is named", clears.all { it < emptiedBy })

                // The mutation is chosen: its name goes into the query's constructor and nowhere else.
                val nameReads = flush.literalReads(named)
                assertEquals("$name: readers of the mutation's name", 1, nameReads.size)
                val queryInit = nameReads.single()
                val query = code[queryInit].call!!
                assertEquals("$name: the name isn't a constructor argument", "<init>", query.name)

                // The request is built from the query and the parameter set, and it goes nowhere but
                // into the send's constructor. 581 built it with one static call taking the two. 582
                // stores the parameter set into the query and hands the query to the request's own
                // constructor.
                val builds = code.indices.filter { index ->
                    code[index].opcode == Opcode.INVOKE_STATIC &&
                        code[index].call!!.parameterTypes.map { it.toString() } == listOf(paramSet, query.definingClass)
                }
                val wraps = code.indices.filter { index ->
                    code[index].opcode == Opcode.INVOKE_DIRECT && code[index].call!!.name == "<init>" &&
                        code[index].call!!.parameterTypes.map { it.toString() } == listOf(query.definingClass)
                }
                assertEquals("$name: request builds", 1, builds.size + wraps.size)
                val build: Int
                val request: String
                val requestReads: List<Int>
                if (builds.isNotEmpty()) {
                    build = builds.single()
                    assertEquals("$name: the request isn't kept", Opcode.MOVE_RESULT_OBJECT, code[build + 1].opcode)
                    request = code[build].call!!.returnType
                    requestReads = flush.literalReads(build + 1)
                } else {
                    build = wraps.single()
                    val queryValue = code[queryInit].callRegisters().first()
                    val (made, wrapped) = code[build].callRegisters()
                    assertEquals("$name: the request wraps something other than the query", queryValue, wrapped)
                    val stores = code.indices.filter { index ->
                        code[index].opcode == Opcode.IPUT_OBJECT &&
                            ((code[index] as ReferenceInstruction).reference as FieldReference).type == paramSet &&
                            (code[index] as TwoRegisterInstruction).registerB == queryValue
                    }
                    assertEquals("$name: parameter sets stored into the query", 1, stores.size)
                    assertTrue("$name: the parameter set goes in out of order", stores.single() in queryInit..build)
                    val creations = code.indices.filter { index ->
                        code[index].opcode == Opcode.NEW_INSTANCE && (code[index] as OneRegisterInstruction).registerA == made &&
                            build in flush.literalReads(index)
                    }
                    assertEquals("$name: requests created", 1, creations.size)
                    val creation = creations.single()
                    request = ((code[creation] as ReferenceInstruction).reference as TypeReference).type
                    assertEquals("$name: the request's constructor", request, code[build].call!!.definingClass)
                    requestReads = flush.literalReads(creation) - build
                }
                assertEquals("$name: readers of the request", 1, requestReads.size)
                val sendInit = requestReads.single()
                val sendType = code[sendInit].call!!.definingClass
                assertEquals("$name: the request goes into something other than a constructor", "<init>", code[sendInit].call!!.name)
                assertTrue("$name: $sendType isn't the send Redex named", sendType in found.sends)

                // The send is a new instance of that runnable, built with the request and handed to
                // the executor at the send point, and read by nothing else.
                val created = code.indices.filter { index ->
                    code[index].opcode == Opcode.NEW_INSTANCE && ((code[index] as ReferenceInstruction).reference as TypeReference).type == sendType
                }
                assertEquals("$name: sends built", 1, created.size)
                assertEquals("$name: readers of the send", listOf(sendInit, send), flush.literalReads(created.single()))
                assertTrue("$name: out of order", named < queryInit && queryInit < build && build < sendInit && sendInit < send)

                // Nothing before the send point reaches the network: no call in the flush answers a
                // future, which is what Facebook's GraphQL layer hands back for a request.
                val futureCalls = code.mapNotNull { it.call?.takeIf { call -> call.returnType in futures } }
                assertEquals("$name: the flush sends something itself", emptyList<MethodReference>(), futureCalls)

                // The send's run() is where the request is handed to the network layer: it reads the
                // request back and passes it straight to one static call answering a SettableFuture.
                val sender = found.sends.getValue(sendType)
                val run = sender.methods.single { it.name == "run" && it.parameterTypes.isEmpty() && it.returnType == "V" }
                val runCode = run.implementation!!.instructions.toList()
                val reads = runCode.indices.filter { index ->
                    runCode[index].opcode == Opcode.IGET_OBJECT &&
                        ((runCode[index] as ReferenceInstruction).reference as FieldReference).type == request
                }
                assertEquals("$name: reads of the request in run()", 1, reads.size)
                val handOff = run.literalReads(reads.single())
                assertEquals("$name: readers of the request in run()", 1, handOff.size)
                val network = runCode[handOff.single()]
                assertEquals("$name: the hand-off to the network", Opcode.INVOKE_STATIC, network.opcode)
                assertEquals("$name: the hand-off to the network", "Lcom/google/common/util/concurrent/SettableFuture;", network.call!!.returnType)

                // The hook, put in the way the patch puts it: it takes the hand-over's place with the
                // same two registers, the executor call is gone, and nothing else in the flush moves.
                val patched = MutableMethod(flush)
                patched.withholdSendAt(send)
                val after = patched.implementation!!.instructions.toList()
                assertEquals("$name: instructions added", code.size, after.size)
                assertEquals("$name: the hook", SEND, after[send].call!!.let {
                    it.definingClass + "->" + it.name + it.parameterTypes.joinToString("", "(", ")") + it.returnType
                })
                assertEquals("$name: the hook's registers", code[send].callRegisters(), after[send].callRegisters())
                assertTrue("$name: an executor call is left", after.none { it.isExecute() })
                for (index in code.indices) {
                    if (index != send) assertEquals("$name: instruction $index changed", render(code[index]), render(after[index]))
                }
                // So the only way the send reaches the executor is the hook, after the mutation is
                // chosen and built.
                assertEquals("$name: readers of the patched send", listOf(sendInit, send), patched.literalReads(created.single()))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
