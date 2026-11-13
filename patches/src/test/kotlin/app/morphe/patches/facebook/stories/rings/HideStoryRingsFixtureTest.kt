/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.rings

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide story rings on every declared build and ABI (#397). The feed's two ring statics each hand
 * every answer to the extension, the comment ring helper answers a ring state built from two nulls
 * when the extension says so, as Facebook's own answer for someone with no story is, and each
 * profile photo the two ring-testing renders build gets its ring kind through the extension right
 * before. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HideStoryRingsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    /**
     * Builds with more than the comment row builder holding the plugin's name: 586 adds a table of
     * plugin names, and its Android 9 build has a prefetch lambda in the table's place. 587's two are
     * the default profile picture plugin's lookup static and the table.
     */
    private val pluginHolders = mapOf("587.0.0.39.55" to 2)

    private fun Method.code() = implementation!!.instructions.toList()
    private fun Instruction.reference() = (this as ReferenceInstruction).reference.toString()
    private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun holding(bundle: File, string: String): List<ClassDef> =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }

    @Test
    fun `each declared build's feed, comment and profile rings ask the extension`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                // The decorators, the components they build, and the statics their checks ask.
                val checks = FixtureDex.methodsWhere(bundle, { true }, ::isRingCheck)
                val decorators = FixtureDex.classes(bundle, checks.map { it.definingClass }.toSet()).values.toList()
                val components = holding(bundle, STORY_RING_COMPONENT) + holding(bundle, LIVE_RING_COMPONENT)
                val statics = decorators.flatMap { it.methods.filter(::isRingCheck) }.flatMap(::actorChecksCalled)
                    .map { it.definingClass }.toSet()
                val commentHolder = holding(bundle, STORY_RING_PLUGIN)
                assertEquals("$name: classes holding the comment story ring plugin", pluginHolders[version] ?: 1, commentHolder.size)
                // 586 moved the ring state helper into a class of merged statics that the builder's
                // method naming the plugin calls, so those calls' classes and answers are loaded too.
                val pluginCalls = commentHolder.flatMap { it.methods }.filter { holdsString(it, STORY_RING_PLUGIN) }
                    .flatMap { it.code() }.filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
                    .map { (it as ReferenceInstruction).reference as MethodReference }.filter { it.parameterTypes.size == 1 }
                val ringStates = commentHolder.flatMap { it.methods }
                    .filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.size == 1 }
                    .map { it.returnType }.plus(pluginCalls.map { it.returnType }).filter { it.startsWith("L") }.toSet()
                val photo = FixtureDex.classes(bundle, setOf(PROFILE_PHOTO)).values.single()
                val photoArguments = photo.methods.single { it.name == "<init>" }.parameterTypes.map { it.toString() }
                    .filter { it.startsWith("L") }.toSet()
                val renders = holding(bundle, PROFILE_RING_TAGS.last())
                val loaded = FixtureDex.classes(bundle, statics + ringStates + photoArguments + pluginCalls.map { it.definingClass }).values
                val all = (decorators + components + commentHolder + loaded + photo + renders).distinctBy { it.type }

                val context = PatchContexts.of(all + ExtensionDex.classDef(SETTINGS_STATUS))
                val feedChecks = context.feedRingChecks()
                val ring = context.ringEnum()
                val (helper, ringState) = context.commentRingState(ring)
                val profileRenders = context.profileRingRenders()

                // Positive controls: the story decorator asks the LIVE static too, so the two are the
                // feed's ring statics rather than two unrelated checks; the comment helper builds a
                // ring state from one value twice somewhere, its no-story answer; and each profile
                // render builds the photo through a range call, the only kind that fits its arguments.
                val storyDecorator = decorators.single { decorator -> decorator.methods.filter(::isRingCheck)
                    .any { check -> actorChecksCalled(check).any { it.toString() == feedChecks[0].toString() } } }
                assertTrue("$name: the story ring check doesn't ask the LIVE static",
                    storyDecorator.methods.filter(::isRingCheck).flatMap(::actorChecksCalled)
                        .any { it.toString() == feedChecks[1].toString() })
                val noStory = helper.code().filter { it.opcode == Opcode.INVOKE_DIRECT && it.reference() == ringState.signature() }
                // One value for every argument: twice through 586, three times on 587.
                assertTrue("$name: the comment ring helper never builds a ring state from one value for each argument", noStory.any {
                    val call = it as FiveRegisterInstruction
                    val arguments = listOf(call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount - 1)
                    call.registerCount == ringState.parameterTypes.size + 1 && arguments.size >= 2 && arguments.distinct().size == 1
                })
                assertEquals("$name: renders testing for a profile ring", 2, profileRenders.size)

                val photoSignature = photo.methods.single { it.name == "<init>" }.signature()
                val parameters = photo.methods.single { it.name == "<init>" }.parameterTypes.map { it.toString() }
                val offset = 1 + parameters.indexOf(ring)
                assertTrue("$name: an argument before the ring kind is wide", parameters.take(parameters.indexOf(ring)).none { it == "J" || it == "D" })
                val originals = profileRenders.associate { (classDef, render) -> classDef.type to render.code() }
                val helperBefore = helper.code()

                context.hideStoryRings()
                context.enableStatus("storyRings")

                for (check in feedChecks) {
                    val code = context.mutableClassDefBy(check.definingClass).methods.single {
                        it.name == check.name && it.parameterTypes.map(CharSequence::toString) == check.parameterTypes.map(CharSequence::toString)
                    }.code()
                    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN }
                    assertTrue("$name: $check never returns", returns.isNotEmpty())
                    for (at in returns) {
                        val answer = (code[at] as OneRegisterInstruction).registerA
                        val call = code[at - 2]
                        assertEquals("$name: $check doesn't hand its answer to the extension", FEED_RING, call.reference())
                        assertEquals("$name: from the returned register", answer, (call as RegisterRangeInstruction).startRegister)
                        assertEquals("$name: and back", Opcode.MOVE_RESULT, code[at - 1].opcode)
                        assertEquals("$name: into it", answer, (code[at - 1] as OneRegisterInstruction).registerA)
                    }
                }

                val patchedHelper = context.mutableClassDefBy(helper.definingClass).methods.single {
                    it.name == helper.name && it.parameterTypes.map(CharSequence::toString) == helper.parameterTypes.map(CharSequence::toString)
                }.code()
                assertEquals("$name: the comment ring helper answers first",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.NEW_INSTANCE, Opcode.CONST_4,
                        Opcode.INVOKE_DIRECT, Opcode.RETURN_OBJECT),
                    patchedHelper.take(7).map { it.opcode })
                assertEquals("$name: asking the comments switch", HIDE_COMMENT_RING, patchedHelper[0].reference())
                assertEquals("$name: with a ring state", ringState.signature(), patchedHelper[5].reference())
                assertEquals("$name: the rest of the comment ring helper is Facebook's",
                    helperBefore.map { it.opcode }, patchedHelper.drop(7).map { it.opcode })

                for ((classDef, render) in profileRenders) {
                    val before = originals.getValue(classDef.type)
                    val code = context.mutableClassDefBy(classDef.type).methods.single {
                        it.name == render.name && it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
                    }.code()
                    val sites = code.indices.filter { code[it].opcode == Opcode.INVOKE_DIRECT_RANGE && code[it].reference() == photoSignature }
                    assertEquals("$name: ${classDef.type} lost or gained a profile photo",
                        before.count { it.opcode == Opcode.INVOKE_DIRECT_RANGE && it.reference() == photoSignature }, sites.size)
                    assertEquals("$name: ${classDef.type} grew by more than the hooks", before.size + 3 * sites.size, code.size)
                    for (at in sites) {
                        val ringRegister = (code[at] as RegisterRangeInstruction).startRegister + offset
                        assertEquals("$name: ${classDef.type} builds a photo without asking about its ring",
                            listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST),
                            code.subList(at - 3, at).map { it.opcode })
                        assertEquals("$name: the profiles hook", PROFILE_RING, code[at - 3].reference())
                        assertEquals("$name: reads the ring kind", ringRegister, (code[at - 3] as RegisterRangeInstruction).startRegister)
                        assertEquals("$name: and puts it back", ringRegister, (code[at - 2] as OneRegisterInstruction).registerA)
                        assertEquals("$name: as a ring kind", ring, code[at - 1].reference())
                    }
                }
                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "storyRings" }.code()
                assertEquals("$name: the settings screen doesn't show the switches",
                    listOf(Opcode.CONST_4, Opcode.RETURN), status.take(2).map { it.opcode })
                assertEquals("$name: the settings screen doesn't show the switches",
                    1, (status[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += name
            }
        }
        assertTrue("no fixture bundle for ${versions.joinToString()}", checked.isNotEmpty())
    }
}
