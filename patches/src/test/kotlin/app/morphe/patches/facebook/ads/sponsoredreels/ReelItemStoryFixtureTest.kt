/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * How the sponsored reels filter asks an item for its story, found in each declared build the way
 * the patch finds it: the item interface the ad base answers its story through, and GraphQLStory's
 * sponsored_data accessor. The item base every reel item extends implements that interface, and
 * Facebook's own Reels code reads the accessor on the story it gets through it, which is what the
 * rule rests on. The three stubs are filled with them, each in its own parameter register.
 */
class ReelItemStoryFixtureTest {
    @Before
    @After
    fun forgetMatches() {
        SfdAdInsertFingerprint.clearMatch()
        VideoHomeInsertAdsFingerprint.clearMatch()
    }

    /**
     * The item interface and its story getter in each declared build, then by the bundle's ABI.
     * Redex can name them differently per ABI, as on 582 and 583.
     */
    private val expected = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to ("LX/0DVK;" to "DTB"),
            "armeabi-v7a" to ("LX/0DVK;" to "DTB"),
        ),
    )

    private val Instruction.methodReference get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    /** Each story getter of an interface [method] calls, with the GraphQLStory accessor it calls within a few instructions after. */
    private fun storyReads(method: Method): List<Triple<String, String, String>> {
        val body = method.implementation?.instructions?.toList() ?: return emptyList()
        return body.indices.mapNotNull { index ->
            val getter = body[index].methodReference ?: return@mapNotNull null
            if (body[index].opcode != Opcode.INVOKE_INTERFACE || getter.returnType != GRAPHQL_STORY ||
                getter.parameterTypes.isNotEmpty()
            ) return@mapNotNull null
            body.subList(index + 1, minOf(body.size, index + 7)).firstNotNullOfOrNull { next ->
                next.methodReference?.takeIf { it.definingClass == GRAPHQL_STORY && it.parameterTypes.isEmpty() }
            }?.let { Triple(getter.definingClass, getter.name, it.name) }
        }
    }

    /** Every supertype of [type] in [parents], the interfaces' own included. */
    private fun supertypes(type: String, parents: Map<String, List<String>>): Set<String> {
        val found = linkedSetOf<String>()
        val pending = ArrayDeque(listOf(type))
        while (pending.isNotEmpty()) {
            parents[pending.removeFirst()].orEmpty().forEach { if (found.add(it)) pending += it }
        }
        return found
    }

    @Test
    fun `the item interface, its story getter and the sponsored data accessor in each declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("the declared builds", expected.keys, versions)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetMatches()
                val anchors = FixtureDex.classesHolding(bundle, "VideoHomeDataControllerSfdAdsUtil") +
                    FixtureDex.classesHolding(bundle, "VideoHomeDataControllerImpl.maybeInsertAds")
                val taken = anchors.flatMap { it.methods }.filter { it.name == "<init>" }
                    .flatMap { method -> method.parameterTypes.map { it.toString() } }
                    .filter { it.startsWith("L") }.toSet()
                val pageClasses = anchors + FixtureDex.classes(bundle, taken).values
                val adBase = with(PatchContexts.of(pageClasses)) { reelPages(SPONSORED_REELS_PATCH) }.adBase
                forgetMatches()

                // One pass for every class's supertypes, and for the code that asks an interface for
                // a story and reads one of the story's models straight after.
                val parents = mutableMapOf<String, List<String>>()
                val reads = mutableSetOf<Triple<String, String, String>>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        parents.putIfAbsent(classDef.type, listOfNotNull(classDef.superclass) + classDef.interfaces)
                        for (method in classDef.methods) reads += storyReads(method)
                    }
                }
                val support = FixtureDex.classes(bundle, supertypes(adBase, parents) + GRAPHQL_STORY)
                val extension = ExtensionDex.classDef(REELS_AD_FILTER)
                val context = PatchContexts.of(pageClasses + support.values + extension)

                val item = with(context) { reelItemStory(adBase) }
                val pin = expected.getValue(version)[bundle.name.substringAfter("-$version-").substringBefore(".apkm")]
                    ?: throw AssertionError("${bundle.name} has no pin")
                assertEquals("${bundle.name}: the item interface and its story getter", pin, item.type to item.getter.name)
                val itemBase = pageClasses.single { it.type == adBase }.superclass!!
                assertTrue("${bundle.name}: the item base $itemBase doesn't implement ${item.type}",
                    item.type in supertypes(itemBase, parents))
                assertTrue(
                    "${bundle.name}: no Reels code reads GraphQLStory->${item.sponsoredData.name}() on the story ${item.type} answers",
                    Triple(item.type, item.getter.name, item.sponsoredData.name) in reads,
                )

                with(context) { fillReelItemStubs(item) }
                val stubs = with(context) { mutableClassDefBy(REELS_AD_FILTER) }.methods
                fun stub(name: String) = stubs.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }
                fun body(name: String) = stub(name).implementation!!.instructions.toList()
                fun p0(name: String) = stub(name).implementation!!.registerCount - 1

                // An instance-of names its registers in four bits, and the patcher's compiler leaves
                // out an instruction whose register doesn't fit without a word.
                val isItem = body(IS_REEL_ITEM_STUB)
                assertEquals("${bundle.name}: the item check", Opcode.INSTANCE_OF, isItem[0].opcode)
                assertEquals(item.type, ((isItem[0] as ReferenceInstruction).reference as TypeReference).type)
                val tested = isItem[0] as TwoRegisterInstruction
                val self = p0(IS_REEL_ITEM_STUB)
                assertEquals("${bundle.name}: the item check's registers", self to self, tested.registerA to tested.registerB)
                assertEquals(Opcode.RETURN, isItem[1].opcode)

                val story = body(ITEM_STORY_STUB)
                assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                    story.take(4).map { it.opcode })
                assertEquals(p0(ITEM_STORY_STUB), (story[0] as OneRegisterInstruction).registerA)
                assertEquals("${item.type}->${item.getter.name}()$GRAPHQL_STORY", story[1].methodReference.toString())

                val data = body(REEL_SPONSORED_DATA_STUB)
                assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                    data.take(4).map { it.opcode })
                // The story comes in as the parameter, and the stub uses no other register.
                val argument = p0(REEL_SPONSORED_DATA_STUB)
                assertEquals("${bundle.name}: the sponsored data stub's cast", GRAPHQL_STORY to argument,
                    ((data[0] as ReferenceInstruction).reference as TypeReference).type to (data[0] as OneRegisterInstruction).registerA)
                val call = data[1] as FiveRegisterInstruction
                assertEquals("${bundle.name}: the sponsored data call's register", 1 to argument, call.registerCount to call.registerC)
                assertEquals("$GRAPHQL_STORY->${item.sponsoredData.name}()${item.sponsoredData.returnType}",
                    data[1].methodReference.toString())
                assertEquals("${bundle.name}: the sponsored data stub's result", argument to argument,
                    (data[2] as OneRegisterInstruction).registerA to (data[3] as OneRegisterInstruction).registerA)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
