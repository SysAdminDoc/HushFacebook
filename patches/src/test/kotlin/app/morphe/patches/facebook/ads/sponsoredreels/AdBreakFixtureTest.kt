/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What the patch calls the method it adds to hold a fetch, and the state name its tick fingerprint reads. */
private const val FAILED_FUTURE = "failedAdFetch"
private const val DEFERRED_CARD_STATE_LITERAL = "UnresolvedWithDeferredCardState"

/**
 * The ad-break anchors on each declared build, then the patch's fetch holds run on those classes:
 * the banner helper and the ad-break server API's queries return the failed future, the idle state's
 * and the extended break's executor calls are replaced by it, and the deferred-card tick answers -1.
 *
 * Read from 585 (2026-10-29): the shared fetch logs both literals, its banner helper returns the
 * SettableFuture it creates (584 declared ListenableFuture), the server API keeps two
 * ListenableFuture queries next to a Pair maker, and each of the two other queries calls a static
 * executor answering a SettableFuture.
 *
 * Read from 587 (2026-11-13): the video query declares the transform future it returns instead of
 * ListenableFuture, so the fetch was matched to its banner call twice. The query still sends
 * through one static executor answering a SettableFuture, and its result goes straight into the
 * fetch's ListenableFuture field.
 */
class AdBreakFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private val listenable = "Lcom/google/common/util/concurrent/ListenableFuture;"
    private val settable = "Lcom/google/common/util/concurrent/SettableFuture;"

    /**
     * The type the server API's video query declares in each declared build, by the bundle's ABI,
     * and the index of its executor call. 587's is Guava's transform future under an obfuscated
     * name, an AbstractFuture that a SettableFuture isn't (each ABI is its own Redex output).
     */
    private val videoQuery = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to ("LX/02NP;" to 582),
            "armeabi-v7a" to ("LX/02NU;" to 575),
        ),
    )

    @Before
    @After
    fun forgetMatches() {
        AdBreakFetchFingerprint.clearMatch()
        ReelsVideoAdQueryFingerprint.clearMatch()
        ExtendedBreakQueryFingerprint.clearMatch()
        UnresolvedAdStateFingerprint.clearMatch()
    }

    private val Instruction.call get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Method.key() = "$definingClass->$name${parameterTypes.joinToString(prefix = "(", postfix = ")", separator = "")}$returnType"

    /** [seeds] and every superclass and interface of theirs the bundle carries, by type. */
    private fun hierarchy(bundle: java.io.File, seeds: Collection<ClassDef>, extra: Set<String> = emptySet()): Map<String, ClassDef> {
        val found = linkedMapOf<String, ClassDef>()
        seeds.forEach { found[it.type] = it }
        var wanted = extra + seeds.flatMap { listOfNotNull(it.superclass) + it.interfaces }
        while (true) {
            val missing = wanted.filter { it !in found && it.startsWith("L") }.toSet()
            if (missing.isEmpty()) break
            val loaded = FixtureDex.classes(bundle, missing)
            if (loaded.isEmpty()) break
            found += loaded
            wanted = loaded.values.flatMap { listOfNotNull(it.superclass) + it.interfaces }.toSet()
        }
        return found
    }

    private fun assertFailedFutureFirst(where: String, patched: Method, owner: String) {
        val head = patched.code().take(3)
        assertEquals("$where: the failed future first", listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            head.map { it.opcode })
        assertEquals("$where: its call", "$owner->$FAILED_FUTURE()$settable", head[0].call.toString())
    }

    @Test
    fun `each fetch hold lands on the banner helper, the server API queries and the two executor calls, and the tick stops, on every declared build`() {
        assertEquals("the declared builds", videoQuery.keys, versions)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val (videoType, videoExecutor) = videoQuery.getValue(version)
                    .getValue(name.substringAfter("-$version-").substringBefore(".apkm"))
                forgetMatches()

                val fetches = FixtureDex.classesHolding(bundle, BANNER_FETCH_LOG)
                    .flatMap { owner -> owner.methods.filter { holdsString(it, BANNER_FETCH_LOG) && holdsString(it, VIDEO_FETCH_LOG) && it.returnType == "V" } }
                assertEquals("$name: one void method logs both fetches", 1, fetches.size)
                val body = fetches.single().code()
                val banner = body.futureCallBefore(BANNER_FETCH_LOG)
                val video = body.futureCallBefore(VIDEO_FETCH_LOG)
                assertNotEquals("$name: the banner helper and the server API are two classes", banner.definingClass, video.definingClass)
                assertTrue("$name: the banner helper answers a future (${banner.returnType})", banner.returnType == listenable || banner.returnType == settable)
                assertEquals("$name: the type the server API's video query declares", videoType, video.returnType)

                val idle = FixtureDex.classesHolding(bundle, REELS_VIDEO_AD_QUERY)
                    .flatMap { owner -> owner.methods.filter { holdsString(it, REELS_VIDEO_AD_QUERY) && it.returnType == "V" } }
                assertEquals("$name: one void method builds the idle video ad query", 1, idle.size)
                val extended = FixtureDex.classesHolding(bundle, EXTENDED_BREAK_QUERY)
                    .flatMap { owner -> owner.methods.filter { holdsString(it, EXTENDED_BREAK_QUERY) && holdsString(it, EXTENDED_BREAK_FETCH_LOG) && it.returnType == "V" } }
                assertEquals("$name: one void method builds the extended break query", 1, extended.size)

                // The tick's state: what holds the name, the types tested near it, and all they extend.
                val stateHolders = FixtureDex.classesHolding(bundle, DEFERRED_CARD_STATE_LITERAL)
                assertEquals("$name: one method names the deferred-card state", 1, stateHolders.flatMap { it.methods }.count {
                    holdsString(it, DEFERRED_CARD_STATE_LITERAL) && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty()
                })
                val tested = stateHolders.flatMap { it.methods }.filter { holdsString(it, DEFERRED_CARD_STATE_LITERAL) }
                    .flatMap { method -> method.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? TypeReference }.map { it.type } }
                    .toSet()

                val owners = (fetches + idle + extended).map { it.definingClass }.toSet()
                val wanted = owners + banner.definingClass + video.definingClass
                val classes = FixtureDex.classes(bundle, wanted).values.toList()
                assertEquals("$name: classes missing", emptySet<String>(), wanted - classes.map { it.type }.toSet())
                val states = hierarchy(bundle, stateHolders, tested)
                val context = PatchContexts.of((classes + states.values).distinctBy { it.type })

                // Before: the tick candidates of the state's classes, and the two executor calls.
                val ticksBefore = states.values.flatMap { it.methods }
                    .filter { it.returnType == "J" && it.parameterTypes.size == 2 && it.parameterTypes[1].toString() == "I" && it.implementation != null }
                    .associate { it.key() to it.code().count { instruction -> instruction.opcode != Opcode.NOP } }
                val idleCall = idleExecutorCallIndex(idle.single().code(), name)
                val extendedCall = idleExecutorCallIndex(extended.single().code(), name, EXTENDED_BREAK_QUERY)
                val idleSize = idle.single().code().count { it.opcode != Opcode.NOP }
                val extendedSize = extended.single().code().count { it.opcode != Opcode.NOP }
                val videoQueries = classes.single { it.type == video.definingClass }.methods.filter { it.returnType == videoType }
                assertEquals("$name: the server API's queries declaring $videoType", listOf(video.name), videoQueries.map { it.name })
                val videoCall = queryExecutorCallIndex(videoQueries.single().code(), name)
                assertEquals("$name: the video query's executor call", videoExecutor, videoCall)
                val videoSize = videoQueries.single().code().count { it.opcode != Opcode.NOP }

                with(context) {
                    holdAdBreakFetches()
                    stopDeferredCardPoller()
                }
                fun patched(method: Method): Method = context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }

                // The banner helper is the one method with the call's signature on its class.
                val helpers = context.mutableClassDefBy(banner.definingClass).methods.filter {
                    it.name == banner.name && it.returnType == banner.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == banner.parameterTypes.map(CharSequence::toString)
                }
                assertEquals("$name: one banner helper", 1, helpers.size)
                assertFailedFutureFirst("$name: the banner helper", helpers.single(), banner.definingClass)

                // Each query the server API answers a ListenableFuture to is held at its head, the
                // video query at its executor call (below), and nothing else.
                val api = context.mutableClassDefBy(video.definingClass).methods
                val queries = api.filter { it.returnType == listenable }
                assertTrue("$name: the server API has no query", queries.isNotEmpty())
                queries.forEach { assertFailedFutureFirst("$name: ${it.name}", it, video.definingClass) }
                api.filter { it.returnType != listenable }.forEach {
                    val first = it.implementation?.instructions?.firstOrNull()?.call
                    assertTrue("$name: ${it.name} is held at its head", first?.name != FAILED_FUTURE)
                }

                // The video, idle and extended executor calls become the failed future, same size, move-result kept.
                for ((label, original, call, size) in listOf(
                    Quad("video", videoQueries.single(), videoCall, videoSize),
                    Quad("idle", idle.single(), idleCall, idleSize),
                    Quad("extended", extended.single(), extendedCall, extendedSize),
                )) {
                    val code = patched(original).code()
                    assertEquals("$name: $label call", "${original.definingClass}->$FAILED_FUTURE()$settable", code[call].call.toString())
                    assertEquals("$name: $label call is static", Opcode.INVOKE_STATIC, code[call].opcode)
                    assertEquals("$name: $label answer kept", Opcode.MOVE_RESULT_OBJECT, code[call + 1].opcode)
                    assertEquals("$name: $label kept its size", size, code.count { it.opcode != Opcode.NOP })
                }

                // Exactly one tick changed, and it answers -1 (for every state, or for the deferred-card one).
                val changed = states.keys.flatMap { type ->
                    context.mutableClassDefBy(type).methods.filter { it.key() in ticksBefore }
                }.filter { tick -> tick.code().count { it.opcode != Opcode.NOP } != ticksBefore.getValue(tick.key()) }
                assertEquals("$name: ticks changed ${changed.map { it.key() }}", 1, changed.size)
                val head = changed.single().code().take(6)
                assertTrue("$name: the tick returns early", head.any { it.opcode == Opcode.RETURN_WIDE })
                assertTrue("$name: with -1", head.any { (it as? WideLiteralInstruction)?.wideLiteral == -1L })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private data class Quad(val label: String, val method: Method, val call: Int, val size: Int)
}
