/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.words

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.ads.sponsoredprofile.HIDE
import app.morphe.patches.facebook.ads.sponsoredprofile.SPONSORED_TEST_KEY
import app.morphe.patches.facebook.ads.sponsoredprofile.mutableRender
import app.morphe.patches.facebook.ads.sponsoredprofile.skipSponsoredStories
import app.morphe.patches.facebook.ads.sponsoredprofile.timelineStory
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Hide posts by words on profile and Page timelines (#458), on every declared Facebook build: the
 * timeline story component's render method takes Hide sponsored profile posts' check and this
 * patch's check, in either order, each first in turn. Whichever went in last runs first, and its
 * no reaches the other's check, whose no reaches Facebook's own code. Either one alone stands too.
 */
class TimelineWordsFixtureTest {
    private fun judge(at: Instruction): String {
        val call = (at as ReferenceInstruction).reference as MethodReference
        return "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
    }

    /**
     * Where each check in [body] starts, its loads taking [loaded] instructions, and what it asks.
     * Each check is the loads, the call, move-result, if-eqz, const and return-object.
     */
    private fun checks(name: String, body: List<Instruction>, loaded: Int, count: Int): List<String> {
        val size = loaded + 5
        return (0 until count).map { index ->
            val start = index * size
            val call = start + loaded
            assertEquals("$name: check $index loads the unit into v0", Opcode.MOVE_OBJECT_FROM16, body[start].opcode)
            assertEquals("$name: check $index", Opcode.MOVE_RESULT, body[call + 1].opcode)
            assertEquals("$name: check $index", Opcode.IF_EQZ, body[call + 2].opcode)
            assertEquals("$name: check $index", Opcode.RETURN_OBJECT, body[call + 4].opcode)
            assertEquals("$name: check $index returns null", 0, (body[call + 4] as OneRegisterInstruction).registerA)
            assertSame(
                "$name: check $index's no goes on to what follows it",
                body[start + size],
                (body[call + 2] as BuilderOffsetInstruction).target.location.instruction,
            )
            judge(body[call])
        }
    }

    @Test
    fun `each declared build stacks the words check with the sponsored check on the timeline`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val component = FixtureDex.classesHolding(bundle, SPONSORED_TEST_KEY).single()
                val story = FixtureDex.classes(bundle, setOf(GRAPHQL_STORY)).getValue(GRAPHQL_STORY)
                for (order in listOf(listOf(HIDE, TIMELINE_WORDS), listOf(TIMELINE_WORDS, HIDE))) {
                    val name = "${bundle.name}, ${order.joinToString(" then ") { it.substringBefore(";->").substringAfterLast('/') }}"
                    val context = PatchContexts.of(listOf(component, story))
                    val found = with(context) { timelineStory() }
                    val own = with(context) { mutableRender(found) }.implementation!!.instructions.toList()
                    for (judge in order) with(context) { mutableRender(found) }.skipSponsoredStories(found.unit, judge)

                    val render = with(context) { mutableRender(found) }
                    val body = render.implementation!!.instructions.toList()
                    val loaded = (body.size - own.size) / 2 - 5
                    assertEquals("$name: both checks, then the render method's own code", own.size + 2 * (loaded + 5), body.size)
                    assertEquals("$name: the last one in runs first", order.reversed(), checks(name, body, loaded, 2))
                    assertEquals("$name: the render method's own code follows", own.first().opcode, body[2 * (loaded + 5)].opcode)
                }

                val alone = PatchContexts.of(listOf(component, story))
                val found = with(alone) { timelineStory() }
                val own = with(alone) { mutableRender(found) }.implementation!!.instructions.size
                with(alone) { mutableRender(found) }.skipSponsoredStories(found.unit, TIMELINE_WORDS)
                val body = with(alone) { mutableRender(found) }.implementation!!.instructions.toList()
                val loaded = body.size - own - 5
                assertEquals("${bundle.name}: the words check stands alone", listOf(TIMELINE_WORDS), checks(bundle.name, body, loaded, 1))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
