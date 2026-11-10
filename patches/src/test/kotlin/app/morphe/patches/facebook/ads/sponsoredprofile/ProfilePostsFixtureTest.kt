/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredprofile

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.treeFieldKey
import app.morphe.patches.facebook.feed.treeTypeTag
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchors of Hide sponsored profile posts on every declared Facebook build, found the way the
 * patch finds them: one method holding the sponsored timeline tag, the timeline story component's
 * render method, which asks GraphQLStory's one sponsored_data accessor and gets the unit from one
 * field, or on 585 one parameter, typed as GraphQLStory's first interface. The hook then goes first
 * in it with v0.
 */
class ProfilePostsFixtureTest {
    /**
     * Registers of the render method, and the declared parameter it takes the unit as (null when it
     * reads a field of its component, as up to 584 did and 586 does again), per build and then by
     * ABI. 586's Android 9 build gives it two registers more.
     */
    private val expected = mapOf<String, Map<String, Pair<Int, Int?>>>(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to (37 to null),
            "armeabi-v7a" to (37 to null),
            "arm64-v8a-minapi28-storeless" to (39 to null),
        ),
    )

    private fun locals(method: Method): Int = method.implementation!!.registerCount - 1 - method.parameterTypes.size

    private fun literal(method: Method, value: Int) =
        method.implementation!!.instructions.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == value }

    @Test
    fun `each declared build has one timeline story render method reading sponsored_data`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("the declared builds", expected.keys, versions)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, SPONSORED_TEST_KEY)
                assertEquals("${bundle.name}: classes holding the sponsored timeline tag", 1, holders.size)
                val component = holders.single()
                assertEquals("${bundle.name}: render methods", 1, component.methods.count(::isTimelineStoryRender))
                val story = FixtureDex.classes(bundle, setOf(GRAPHQL_STORY)).getValue(GRAPHQL_STORY)

                val context = PatchContexts.of(listOf(component, story))
                val found = with(context) { timelineStory() }
                assertEquals("${bundle.name}: the component", component.type, found.render.definingClass)
                val (registers, parameter) = expected.getValue(version)[bundle.name.substringAfter("-$version-").substringBefore(".apkm")]
                    ?: throw AssertionError("${bundle.name} has no pin")
                // The unit is typed as the first interface GraphQLStory declares, in a field or (585) a parameter.
                val unitType = when (val unit = found.unit) {
                    is StoryUnit.InField -> unit.field.type.also { assertEquals("${bundle.name}: a field", null, parameter) }
                    is StoryUnit.InParameter -> found.render.parameterTypes[unit.index].toString()
                        .also { assertEquals("${bundle.name}: the unit's parameter", parameter, unit.index) }
                }
                assertEquals("${bundle.name}: the unit's type", story.interfaces.first(), unitType)
                assertTrue(
                    "${bundle.name}: the accessor loads sponsored_data as SponsoredData",
                    literal(found.sponsoredData, treeFieldKey(SPONSORED_DATA_FIELD)) &&
                        literal(found.sponsoredData, treeTypeTag(SPONSORED_DATA_TYPE)),
                )
                assertEquals(0x1456568f, treeTypeTag(SPONSORED_DATA_TYPE))

                val render = with(context) { mutableClassDefBy(component.type) }.methods.single {
                    it.name == found.render.name && it.parameterTypes.size == found.render.parameterTypes.size &&
                        !AccessFlags.STATIC.isSet(it.accessFlags) && isTimelineStoryRender(it)
                }
                assertEquals("${bundle.name}: render registers", registers, render.implementation!!.registerCount)
                assertTrue("${bundle.name}: the render method has no local", locals(render) >= 1)
                val own = render.implementation!!.instructions.toList()
                render.skipSponsoredStories(found.unit)

                val body = render.implementation!!.instructions.toList()
                val copy = body[0] as TwoRegisterInstruction
                val unit = found.unit
                val loaded = if (unit is StoryUnit.InField) {
                    // this, the one before the last register of a one-parameter render, then the field off it.
                    assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, 0, registers - 2), listOf(body[0].opcode, copy.registerA, copy.registerB))
                    assertEquals(Opcode.IGET_OBJECT, body[1].opcode)
                    assertEquals(unit.field.name, ((body[1] as ReferenceInstruction).reference as FieldReference).name)
                    2
                } else {
                    // The unit parameter's own register, past v15 on 585, copied down whole.
                    val source = render.parameterRegisterNumber((unit as StoryUnit.InParameter).index)
                    assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, 0, source), listOf(body[0].opcode, copy.registerA, copy.registerB))
                    // The render method's own first instruction reads the same register: it is the unit.
                    assertTrue("${bundle.name}: the render method reads v$source first", own.first().let {
                        it is TwoRegisterInstruction && it.registerB == source
                    })
                    1
                }
                val hook = (body[loaded] as ReferenceInstruction).reference as MethodReference
                assertEquals(HIDE, "${hook.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
                assertEquals(0, (body[loaded] as FiveRegisterInstruction).registerC)
                assertEquals(Opcode.MOVE_RESULT, body[loaded + 1].opcode)
                assertEquals(Opcode.IF_EQZ, body[loaded + 2].opcode)
                assertEquals(Opcode.RETURN_OBJECT, body[loaded + 4].opcode)
                assertEquals(0, (body[loaded + 4] as OneRegisterInstruction).registerA)
                assertEquals("${bundle.name}: the hook, then the render method's own code", own.size + loaded + 5, body.size)
                assertEquals("${bundle.name}: the render method's own code follows", own.first().opcode, body[loaded + 5].opcode)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
