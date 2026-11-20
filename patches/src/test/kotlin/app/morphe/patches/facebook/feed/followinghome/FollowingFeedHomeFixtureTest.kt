/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.followinghome

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Following feed on Home on every Facebook build the bundle declares: the news feed parameter
 * builder found by the trace section it opens, static with the feed type second, the feed types
 * the extension looks up by name kept as public constants, and the hook first thing in the
 * builder, with the feed type through the extension and back into its parameter before any of
 * Facebook's code runs. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FollowingFeedHomeFixtureTest {
    /** What the feed query hook adds before each return: move, null check, read, call, nop. */
    private val HOOK = 5

    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each declared build asks the extension for Home's feed type first thing`() = bundles { bundle ->
        val name = bundle.name
        val holders = FixtureDex.classesHolding(bundle, PAGED_NEWSFEED_PARAMS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val builder = newsFeedParams(holders)
        val where = "$name: ${builder.definingClass}->${builder.name}"
        // 588's Android 9 build reads the feed type off a field of its own class instead.
        val fieldRead = feedTypeFieldRead(builder)
        if (fieldRead == null) {
            assertEquals("$where: the feed type isn't second", HOME_FEED_TYPE_CLASS, builder.parameterTypes[1].toString())
            assertTrue("$where reads the feed type before \"$PAGED_NEWSFEED_PARAMS\"", !readsFeedTypeBeforeTrace(builder))
        }
        // The feed style it sets for the most recent feed is what makes the swap a most recent request.
        // 588 keeps the style's name in a string table.
        val tables = FixtureDex.classesHolding(bundle, MOST_RECENT_STYLE)
            .filter { owner -> owner.methods.any { isStringTable(it) && holdsString(it, MOST_RECENT_STYLE) } }
            .associateBy { it.type }
        val resolve = { call: MethodReference -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
        assertTrue("$where sets no $MOST_RECENT_STYLE style", namesString(builder, MOST_RECENT_STYLE, resolve))
        if (fieldRead == null) assertTrue("$where has no local register for the hook", builder.localRegisterCount() >= 1)

        val feedTypes = FixtureDex.classes(bundle, setOf(HOME_FEED_TYPE_CLASS)).values.single()
        assertNull("$name: FeedType can't answer the lookup", feedTypeRefusal(feedTypes))
        // FeedType's toString is the name each feed type keeps, which is what the lookup compares.
        val toString = feedTypes.methods.single { it.name == "toString" && it.parameterTypes.isEmpty() }
        assertTrue("$name: FeedType's toString isn't the kept name's", toString.implementation!!.instructions.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                call.name == "toString" && call.definingClass == "Ljava/lang/Object;"
            } == true
        })

        val context = PatchContexts.of(listOf(FixtureDex.classes(bundle, setOf(builder.definingClass)).values.single()))
        val method = context.mutableClassDefBy(builder.definingClass).methods.single {
            it.name == builder.name && it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
        }
        val original = method.implementation!!.instructions.toList()
        method.askForHomeFeed()
        val patched = method.implementation!!.instructions.toList()
        if (fieldRead != null) {
            val feedType = (original[fieldRead] as OneRegisterInstruction).registerA
            val field = (original[fieldRead] as ReferenceInstruction).reference.toString()
            assertTrue("$where: the read isn't the feed type's", field.startsWith("${builder.definingClass}->") && field.endsWith(":$HOME_FEED_TYPE_CLASS"))
            assertEquals("$where gains three instructions", original.size + 3, patched.size)
            val hook = patched.subList(fieldRead + 1, fieldRead + 4)
            assertEquals("$where: the extension is asked right after the read", listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST),
                hook.map { it.opcode })
            assertEquals("$where: with the feed type", listOf(FEED_TYPE_ASKED, 1, feedType), (hook[0] as RegisterRangeInstruction).let {
                listOf((it as ReferenceInstruction).reference.toString(), it.registerCount, it.startRegister)
            })
            assertEquals("$where: its answer is kept as a feed type where the read put it", listOf(feedType, feedType, HOME_FEED_TYPE_CLASS),
                listOf((hook[1] as OneRegisterInstruction).registerA, (hook[2] as OneRegisterInstruction).registerA,
                    (hook[2] as ReferenceInstruction).reference.toString()))
            assertEquals("$where: Facebook's code stays", original.map { it.opcode },
                patched.filterIndexed { index, _ -> index !in fieldRead + 1..fieldRead + 3 }.map { it.opcode })
            return@bundles
        }
        // Every parameter before the feed type is an object, and an instance method's `this` comes first.
        assertTrue("$where: a wide parameter", builder.parameterTypes.none { it.toString() == "J" || it.toString() == "D" })
        val self = if (AccessFlags.STATIC.isSet(builder.accessFlags)) 0 else 1
        val firstParameter = method.implementation!!.registerCount - (builder.parameterTypes.size + self)
        val feedType = firstParameter + self + 1
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the feed type is read", listOf(Opcode.MOVE_OBJECT_FROM16, 0, feedType),
            (patched[0] as TwoRegisterInstruction).let { listOf(it.opcode, it.registerA, it.registerB) })
        assertEquals("$where: the extension is asked", FEED_TYPE_ASKED, (patched[1] as ReferenceInstruction).reference.toString())
        assertEquals("$where: with the feed type", listOf(1, 0), (patched[1] as FiveRegisterInstruction).let {
            listOf(it.registerCount, it.registerC)
        })
        assertEquals("$where: its answer is kept as a feed type", listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST),
            patched.subList(2, 4).map { it.opcode })
        assertEquals("$where: and put back where the feed type was", listOf(Opcode.MOVE_OBJECT_FROM16, feedType, 0),
            (patched[4] as TwoRegisterInstruction).let { listOf(it.opcode, it.registerA, it.registerB) })
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
    }

    @Test
    fun `each declared build hands the feed query's parameter set to the debug log before it returns`() = bundles { bundle ->
        val name = bundle.name
        val holders = FixtureDex.classesHolding(bundle, FEED_QUERY_INNER).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val query = feedQuery(holders) { type -> FixtureDex.classes(bundle, setOf(type)).values.singleOrNull() }
        val where = "$name: ${query.method.definingClass}->${query.method.name}"
        // The extension reads the set through its one public no-argument method answering a map.
        val paramSet = FixtureDex.classes(bundle, setOf(PARAM_SET_CLASS)).values.single()
        assertEquals("$name: the parameter set answers no single map", 1, paramSet.methods.count {
            it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/Map;" &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        })
        val returns = feedQueryReturns(query.method)
        assertTrue("$where returns nothing past the feed's own query", returns.isNotEmpty())

        val context = PatchContexts.of(listOf(FixtureDex.classes(bundle, setOf(query.method.definingClass)).values.single()))
        val method = context.mutableClassDefBy(query.method.definingClass).methods.single {
            it.name == query.method.name && it.parameterTypes.map(CharSequence::toString) == query.method.parameterTypes.map(CharSequence::toString)
        }
        val original = method.implementation!!.instructions.toList()
        method.sendsFeedParams(query)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains five instructions per return", original.size + HOOK * returns.size, patched.size)
        var address = 0
        val addresses = patched.map { val at = address; address += it.codeUnits; at }
        returns.forEachIndexed { done, at ->
            val start = at + HOOK * done
            val hook = patched.subList(start, start + HOOK + 1)
            val step = "$where: the hook before return ${done + 1}"
            assertEquals(step, listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.NOP,
                Opcode.RETURN_OBJECT), hook.map { it.opcode })
            val result = (original[at] as OneRegisterInstruction).registerA
            val spare = (hook[0] as TwoRegisterInstruction).registerA
            assertTrue("$step: the spare is the result", spare != result)
            assertEquals("$step: the result is moved to the spare", result, (hook[0] as TwoRegisterInstruction).registerB)
            assertEquals("$step: a null result goes straight to the return", listOf(spare, start + 4),
                listOf((hook[1] as OneRegisterInstruction).registerA, addresses.indexOf(addresses[start + 1] + (hook[1] as OffsetInstruction).codeOffset)))
            assertEquals("$step: the set is read off the result", listOf(query.paramSet, spare, spare),
                (hook[2] as TwoRegisterInstruction).let { listOf((it as ReferenceInstruction).reference.toString(), it.registerA, it.registerB) })
            assertEquals("$step: the extension is handed the set", listOf(FEED_REQUEST_SENT, 1, spare),
                (hook[3] as FiveRegisterInstruction).let { listOf((it as ReferenceInstruction).reference.toString(), it.registerCount, it.registerC) })
            assertEquals("$step: the result is still returned", result, (hook[5] as OneRegisterInstruction).registerA)
        }
        assertEquals("$where: Facebook's code stays", original.map { it.opcode },
            patched.filterIndexed { index, _ -> returns.withIndex().none { (done, at) -> index in at + HOOK * done until at + HOOK * (done + 1) } }.map { it.opcode })
    }
}
