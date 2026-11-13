/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.seen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.isStringTableCall
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mark as seen eye's timing (#150) on each declared Facebook build: the listener the seen
 * helper makes to count a card once it plays is found and takes the early card hook first, as a
 * range over the bucket, the card and the helper; the story card state's two Litho updates take the
 * state hook first; and the state's long press flag is the boolean Facebook's state dump reads
 * before "LONG_PRESS_ACTIVE", a public field the extension's stub can read. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class StoryEyeFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun MethodReference.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun registers(instruction: Instruction): List<Int> = when (instruction) {
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        is FiveRegisterInstruction -> listOf(
            instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
        ).take(instruction.registerCount)
        else -> emptyList()
    }

    private fun versions(): Set<String> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions
    }

    /**
     * Per build, whether the helper's (StoryBucket, StoryCard) method lays the listener's block out
     * before the count it makes at once. Up to 586 the count came first in the code.
     */
    private val listenerLaidOutFirst = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf("arm64-v8a" to true, "armeabi-v7a" to true),
    )

    @Test
    fun `each declared build's seen helper makes one listener to count a card later, which takes the early card hook`() {
        val versions = versions()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val mutation = seenQueryClass(bundle)
                val sender = FixtureDex.classesHolding(bundle, STORY_IDS).mapNotNull { seenSender(it, mutation) }.single()
                val callers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.methodSection.none { it == sender }) return@forEach
                    dex.classes.filter { callsSender(it, sender) }.mapTo(callers) { ImmutableClassDef.of(it) }
                }
                val tableOwners = callers.flatMap { it.methods }.flatMap { method ->
                    method.implementation?.instructions?.toList().orEmpty().filter(::isStringTableCall).mapNotNull { it.call?.definingClass }
                }.toSet()
                val tables = FixtureDex.classes(bundle, tableOwners)
                val helper = seenHelper(callers, sender) { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }

                val later = countLater(helper) { type -> FixtureDex.classes(bundle, setOf(type))[type] }
                assertNotNull("$name: ${helper.type} makes no one listener to count a card later", later)
                later!!
                assertTrue("$name: the helper isn't public", AccessFlags.PUBLIC.isSet(helper.accessFlags))
                assertTrue("$name: the helper's session isn't public", AccessFlags.PUBLIC.isSet(later.session.accessFlags))

                // The helper's (StoryBucket, StoryCard) method counts a card at once when the story
                // plays and makes the listener otherwise, after the same checks. Up to 586 the
                // count came first in the code; 587 lays the listener's block out first.
                val activated = helper.methods.single {
                    !AccessFlags.STATIC.isSet(it.accessFlags) &&
                        it.parameterTypes.map(Any::toString) == listOf(STORY_BUCKET, STORY_CARD) &&
                        it.implementation?.instructions?.any { i -> i.call?.definingClass == later.listener.type } == true
                }
                val code = activated.implementation!!.instructions.toList()
                val countNow = code.indexOfFirst { it.call?.let { c -> c.definingClass == helper.type && c.takes(cardSeen(helper)) } == true }
                val made = code.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == later.listener.type }
                assertTrue("$name: the helper doesn't count the card at once", countNow >= 0)
                assertTrue("$name: the helper doesn't make the listener", made >= 0)
                val listenerFirst = listenerLaidOutFirst.getValue(version)[name.substringAfter("-$version-").substringBefore(".apkm")]
                    ?: throw AssertionError("$name has no pin")
                assertEquals("$name: the listener's block comes before the count (the count at $countNow, the listener at $made)",
                    listenerFirst, made < countNow)

                // The bucket, the card and the helper are the constructor's first three
                // parameters (p1 to p3) whatever primitives follow them.
                val constructor = later.constructor
                val count = constructor.implementation!!.registerCount
                val p1 = count - constructor.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }.sum()
                val before = constructor.implementation!!.instructions.toList()
                val patched = MutableMethod(constructor).apply { reportCardLater() }
                val after = patched.implementation!!.instructions.toList()
                assertEquals("$name: instructions added to the listener's constructor", before.size + 1, after.size)
                assertEquals("$name: the hook's form", Opcode.INVOKE_STATIC_RANGE, after[0].opcode)
                assertEquals("$name: the hook", ON_CARD_LATER, after[0].call!!.descriptor())
                assertEquals("$name: the hook's arguments are the bucket, the card and the helper",
                    (p1 until p1 + 3).toList(), registers(after[0]))
                assertTrue("$name: the constructor branches", before.none { it.opcode.name.startsWith("if-") || it.opcode.name.startsWith("goto") })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun MethodReference.takes(method: com.android.tools.smali.dexlib2.iface.Method): Boolean =
        name == method.name && parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)

    @Test
    fun `each declared build's story card state updates take the state hook and its long press flag is public`() {
        val versions = versions()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val updates = FixtureDex.classesHolding(bundle, CARD_STATE_UPDATE).flatMap { it.methods.filter(::isCardStateUpdate) }
                assertEquals("$name: the story card state's updates (sync and async)", 2, updates.size)
                val state = updates.map { it.parameterTypes.single().toString() }.toSet().single()

                val flags = FixtureDex.classesHolding(bundle, LONG_PRESS_ACTIVE)
                    .flatMap { owner -> owner.methods.mapNotNull { longPressFlag(it, state) } }
                assertEquals("$name: booleans of $state read before $LONG_PRESS_ACTIVE", 1, flags.size)
                val flag: FieldReference = flags.single()
                val stateClass = FixtureDex.classes(bundle, setOf(state)).getValue(state)
                assertTrue("$name: $state isn't public", AccessFlags.PUBLIC.isSet(stateClass.accessFlags))
                val field = stateClass.fields.single { it.name == flag.name }
                assertTrue("$name: the long press flag isn't public", AccessFlags.PUBLIC.isSet(field.accessFlags))
                assertEquals("$name: the long press flag's type", "Z", field.type)

                for (update in updates) {
                    val count = update.implementation!!.registerCount
                    val before = update.implementation!!.instructions.toList()
                    val patched = MutableMethod(update).apply { reportState() }
                    val after = patched.implementation!!.instructions.toList()
                    assertEquals("$name: instructions added to ${update.definingClass}->${update.name}", before.size + 1, after.size)
                    assertEquals("$name: the hook's form", Opcode.INVOKE_STATIC_RANGE, after[0].opcode)
                    assertEquals("$name: the hook", ON_STATE, after[0].call!!.descriptor())
                    assertEquals("$name: the hook's argument is the new state", listOf(count - 1), registers(after[0]))
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the eye's hooks and the stubs the patch fills`() {
        val button = ExtensionDex.classDef(STORY_SEEN_BUTTON).methods
        fun MethodReference.key() = descriptor()
        assertTrue("StorySeenButton has no $ON_STATE", button.any { it.key() == ON_STATE })
        assertTrue("StorySeenButton has no $ON_CARD_LATER", button.any { it.key() == ON_CARD_LATER })
        for ((stub, answer) in listOf(LONG_PRESSED_STUB to "Z", HELPER_SESSION_STUB to "Ljava/lang/Object;")) {
            val method = button.single { it.name == stub }
            assertTrue("StorySeenButton.$stub isn't static", AccessFlags.STATIC.isSet(method.accessFlags))
            assertEquals("StorySeenButton.$stub's answer", answer, method.returnType)
            assertEquals("StorySeenButton.$stub's parameters", listOf("Ljava/lang/Object;"), method.parameterTypes.map { it.toString() })
        }
    }
}
