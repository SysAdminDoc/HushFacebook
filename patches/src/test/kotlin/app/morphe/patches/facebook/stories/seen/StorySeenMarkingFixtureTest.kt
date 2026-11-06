/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.seen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTableCall
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The trace name Facebook's story navigation delegate loads where it moves the viewer to a card by index. */
private const val NAVIGATE_TO_CARD = "StoryViewerNavigationDelegate.navigateToCard"

/**
 * What marking a watched bucket on the phone hooks and calls (#61), on every declared build: the
 * seen helper's per-card method hands its bucket, card, helper and peek flag to the extension as a
 * range; there's one way of publishing the bucket's flag; StoryBucket's members are there;
 * RegularStoryBucket's one straight constructor takes the bucket hook in front of its return; and
 * every publishing call is public in a public class, so the extension in another package can make
 * it. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class StorySeenMarkingFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private val com.android.tools.smali.dexlib2.iface.instruction.Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    @Test
    fun `each declared build's seen helper hands each queued card to the extension`() = bundles { bundle ->
        val name = bundle.name
        val mutation = FixtureDex.classesHolding(bundle, SEEN_ROOT_FIELD).single(::isSeenMutation).type
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
        val card = cardSeen(helper)
        val first = card.parameterRegisterNumber(1)
        assertEquals("$name: the bucket, card, helper and peek flag aren't side by side", first + 3, card.parameterRegisterNumber(4))

        val code = card.implementation!!.instructions.toList()
        val patched = MutableMethod(card).apply {
            reportCard()
            hearViewedCards()
        }
        val after = patched.implementation!!.instructions.toList()
        assertEquals("$name: instructions added to the per-card method", code.size + 2, after.size)
        val viewed = after[0]
        assertEquals("$name: the viewed hook's form", Opcode.INVOKE_STATIC_RANGE, viewed.opcode)
        assertEquals("$name: the viewed hook", VIEWED, viewed.call!!.smali())
        assertEquals("$name: the viewed hook's first register", first, (viewed as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the viewed hook's registers", 4, viewed.registerCount)
        assertEquals("$name: the card hook stays", ON_CARD, after[1].call!!.smali())
        for (index in code.indices) {
            assertEquals("$name: per-card instruction $index changed", code[index].opcode, after[index + 2].opcode)
        }
    }

    @Test
    fun `each declared build has one bucket publisher and the bucket members the marking calls`() = bundles { bundle ->
        val name = bundle.name
        // 583's 32-bit publisher asks a string table for the flag's name, so the table's callers count too.
        val holders = (FixtureDex.classesHolding(bundle, BUCKET_SEEN_FIELD) + FixtureDex.classesHolding(bundle, BUCKET_TYPE))
            .distinctBy { it.type }
        val tables = holders.associateBy { it.type }
        val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
        val asking = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.definingClass in tables } }) {
            !holdsString(it, BUCKET_SEEN_FIELD) && namesString(it, BUCKET_SEEN_FIELD, resolve)
        }
        val sources = (holders + FixtureDex.classes(bundle, asking.map { it.definingClass }.toSet()).values).distinctBy { it.type }
        val publishers = sources.flatMap { it.methods }.mapNotNull { seenPublisher(it, resolve) }.toSet()
        assertEquals("$name: ways of publishing $BUCKET_SEEN_FIELD", 1, publishers.size)
        val publisher = publishers.single()
        val publishing = listOf(publisher.consistency, publisher.factory, publisher.setId, publisher.setFlag, publisher.build)

        val classes = FixtureDex.classes(bundle, setOf(STORY_BUCKET, REGULAR_BUCKET, STORY_CARD) + publishing.map { it.definingClass })
        val buckets = storyBuckets(classes.getValue(STORY_BUCKET), classes.getValue(REGULAR_BUCKET))
        assertNotNull("$name: StoryBucket and RegularStoryBucket aren't shaped as the marking expects", buckets)
        assertTrue("$name: StoryCard has no getId()", classes.getValue(STORY_CARD).methods.any {
            it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        })
        for (call in publishing) {
            val owner = classes.getValue(call.definingClass)
            val declared = owner.methods.single {
                it.name == call.name && it.returnType == call.returnType &&
                    it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
            }
            assertTrue("$name: ${owner.type} isn't public", AccessFlags.PUBLIC.isSet(owner.accessFlags))
            assertTrue("$name: ${call.smali()} isn't public", AccessFlags.PUBLIC.isSet(declared.accessFlags))
        }

        // The same, found the way the patch finds it, from the classes involved.
        val context = PatchContexts.of((sources + classes.values).distinctBy { it.type })
        val marking = context.seenMarking()
        assertEquals("$name: the patch's publisher", publisher, marking.publisher)
        assertEquals("$name: the patch's cards", buckets!!.cards.name, marking.buckets.cards.name)
        assertEquals("$name: the patch's answer", buckets.hasUnwatched.name, marking.buckets.hasUnwatched.name)
        assertEquals("$name: the patch's override", buckets.override.name, marking.buckets.override.name)
        assertEquals("$name: the patch's tree", buckets.tree, marking.buckets.tree)
        assertTrue("$name: RegularStoryBucket declares no ${buckets.tree.name} tree (#165)",
            classes.getValue(REGULAR_BUCKET).fields.any { it.name == buckets.tree.name && it.type == buckets.tree.type })

        // Both answers, StoryBucket's and RegularStoryBucket's, ask the extension first (#165).
        for (answer in listOf(buckets.hasUnwatched, buckets.override)) {
            val before = answer.implementation!!.instructions.toList()
            val asked = MutableMethod(answer).apply { answerWatchedFirst() }.implementation!!.instructions.toList()
            assertEquals("$name: instructions added to ${answer.definingClass}'s answer", before.size + 5, asked.size)
            val read = asked[0]
            assertEquals("$name: the read hook's form", Opcode.INVOKE_STATIC_RANGE, read.opcode)
            assertEquals("$name: the read hook", READS_WATCHED, read.call!!.smali())
            val self = answer.implementation!!.registerCount - 1
            assertEquals("$name: the read hook reads this", self, (read as RegisterRangeInstruction).startRegister)
            assertEquals(
                "$name: the read hook's answer",
                listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
                asked.subList(1, 5).map { it.opcode },
            )
            for (index in before.indices) {
                assertEquals("$name: ${answer.definingClass}'s answer instruction $index changed", before[index].opcode, asked[index + 5].opcode)
            }
        }
        assertEquals("$name: the patch's session", buckets.session.name, marking.buckets.session.name)

        val constructor = classes.getValue(REGULAR_BUCKET).methods.single { it.name == "<init>" }
        val code = constructor.implementation!!.instructions.toList()
        assertEquals("$name: RegularStoryBucket's constructor returns", 1, code.count { it.opcode == Opcode.RETURN_VOID })
        assertTrue("$name: RegularStoryBucket's constructor branches", code.none { it is OffsetInstruction })
        val made = MutableMethod(constructor).apply { hearBucketMade() }
        val after = made.implementation!!.instructions.toList()
        assertEquals("$name: instructions added to the constructor", code.size + 1, after.size)
        val hook = after[after.size - 2]
        assertEquals("$name: the bucket hook", BUCKET_MADE, hook.call!!.smali())
        val self = constructor.implementation!!.registerCount - constructor.parameterTypes.size - 1
        assertEquals("$name: the bucket hook reads this", self, (hook as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the constructor still ends", Opcode.RETURN_VOID, after.last().opcode)
    }

    @Test
    fun `each declared build has one story start check and it takes the watched-here hook first`() = bundles { bundle ->
        val name = bundle.name
        val owners = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filter { startCheck(it) != null }.mapTo(owners) { ImmutableClassDef.of(it) }
        }
        assertEquals("$name: story start checks", 1, owners.size)
        val check = startCheck(owners.single())!!

        // The viewer asks for it through the interface the check implements.
        val interfaces = owners.single().interfaces.toSet()
        val referencing = { dex: com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile ->
            dex.methodSection.any { it.definingClass in interfaces && it.name == check.name }
        }
        val asking = FixtureDex.methodsWhere(bundle, referencing) { method ->
            method.implementation?.instructions?.any {
                it.opcode == Opcode.INVOKE_INTERFACE && it.call?.let { call ->
                    call.definingClass in interfaces && call.name == check.name && call.returnType == "Z"
                } == true
            } == true
        }
        assertTrue("$name: nothing asks ${check.definingClass}'s interface for ${check.name}", asking.isNotEmpty())

        val code = check.implementation!!.instructions.toList()
        val card = check.parameterRegisterNumber(0)
        val after = MutableMethod(check).apply { skipWatchedCards() }.implementation!!.instructions.toList()
        assertEquals("$name: instructions added to the start check", code.size + 5, after.size)
        val hook = after[0]
        assertEquals("$name: the start hook's form", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals("$name: the start hook", WATCHED_HERE, hook.call!!.smali())
        assertEquals("$name: the start hook reads the card", card, (hook as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the start hook's registers", 1, hook.registerCount)
        assertEquals(
            "$name: the start hook's answer",
            listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
            after.subList(1, 5).map { it.opcode },
        )
        for (index in code.indices) {
            assertEquals("$name: start check instruction $index changed", code[index].opcode, after[index + 5].opcode)
        }

        // Its two calls, getMedia() and the seen flag, are public on StoryCard for the stubs (#218).
        val calls = startCalls(check)
        assertEquals("$name: the start check's calls", 2, calls.size)
        for (call in calls) assertEquals("$name: ${call.name} isn't on StoryCard", STORY_CARD, call.definingClass)

        // The one static method picking where a bucket opens, which takes the start card hook first.
        val cards = interfaces.single()
        val pickers = asking.filter { isStartPicker(it, cards, check.name) }
        assertEquals("$name: start pickers", 1, pickers.size)
        val picker = pickers.single()
        val pickerCode = picker.implementation!!.instructions.toList()
        val picked = MutableMethod(picker).apply { nameStartCard(owners.single().type) }.implementation!!.instructions.toList()
        assertEquals("$name: instructions added to the start picker", pickerCode.size + 5, picked.size)
        assertEquals(
            "$name: the start card hook's form",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT),
            picked.subList(0, 5).map { it.opcode },
        )
        val named = picked[3]
        assertEquals("$name: the start card hook", START_CARD, named.call!!.smali())
        assertEquals("$name: the start card hook reads the bucket", picker.parameterRegisterNumber(1), (named as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the start card hook's registers", 2, named.registerCount)
        for (index in pickerCode.indices) {
            assertEquals("$name: start picker instruction $index changed", pickerCode[index].opcode, picked[index + 5].opcode)
        }
    }

    @Test
    fun `each declared build's viewer asks the extension where a bucket opens once its loading stand-in is replaced`() = bundles { bundle ->
        val name = bundle.name
        val found = FixtureDex.classesHolding(bundle, DATA_CHANGED).flatMap { owner ->
            owner.methods.filter { loads(it, DATA_CHANGED) }.mapNotNull { method -> loadingSwap(method)?.let { method to it } }
        }
        assertEquals("$name: onDataChanged methods shaped for the loading swap", 1, found.size)
        val (method, swap) = found.single()
        val code = method.implementation!!.instructions.toList()
        fun static(returns: String, vararg types: String) = code.indices.filter { i ->
            code[i].opcode == Opcode.INVOKE_STATIC && code[i].call!!.returnType == returns &&
                code[i].call!!.parameterTypes.map(Any::toString) == types.toList()
        }
        val same = code[static("Z", STORY_BUCKET, STORY_BUCKET).single()] as FiveRegisterInstruction
        val compare = code[static("Z", STORY_CARD, STORY_CARD).single()] as FiveRegisterInstruction
        val lookups = static("I", STORY_BUCKET, STORY_CARD)
        val first = code[swap.replacing - 2] as FiveRegisterInstruction
        assertEquals("$name: the first hook follows the first lookup of the old card", lookups.first(), swap.replacing - 2)
        assertEquals("$name: the first lookup's result", Opcode.MOVE_RESULT, code[swap.replacing - 1].opcode)
        assertTrue("$name: the old card is looked up again after the card check", lookups.any { it > swap.check && (code[it] as FiveRegisterInstruction).registerD == swap.card })
        val move = code[swap.navigate] as FiveRegisterInstruction
        val navigate = code[swap.navigate].call!!
        assertEquals("$name: the viewer moves on its own delegate", method.definingClass, navigate.definingClass)
        val navigators = FixtureDex.classesHolding(bundle, NAVIGATE_TO_CARD).filter { it.type == method.definingClass }
            .flatMap { owner -> owner.methods.filter { loads(it, NAVIGATE_TO_CARD) } }
        assertEquals("$name: the call is the delegate's navigateToCard", listOf(navigate.name), navigators.map { it.name })
        assertEquals("$name: the index moved to", swap.index, move.registerE)
        val after = MutableMethod(method).apply { openAfterLoading(swap) }.implementation!!.instructions.toList()
        assertEquals("$name: instructions added to onDataChanged", code.size + 6, after.size)
        val indexHook = after[swap.navigate + 4] as FiveRegisterInstruction
        assertEquals("$name: the index hook", INDEX_AFTER_LOADING, after[swap.navigate + 4].call!!.smali())
        assertEquals(
            "$name: the index hook reads the new copy, the card looked for and the index",
            listOf(same.registerC, compare.registerD, swap.index),
            listOf(indexHook.registerC, indexHook.registerD, indexHook.registerE),
        )
        val indexAnswer = after[swap.navigate + 5]
        assertEquals("$name: the index hook's answer", Opcode.MOVE_RESULT, indexAnswer.opcode)
        assertEquals("$name: the answer is the index moved to", swap.index, (indexAnswer as OneRegisterInstruction).registerA)
        assertEquals("$name: navigateToCard follows the hook", navigate.smali(), after[swap.navigate + 6].call!!.smali())
        val replacing = after[swap.replacing] as FiveRegisterInstruction
        assertEquals("$name: the first hook", REPLACING, after[swap.replacing].call!!.smali())
        assertEquals("$name: the first hook reads the bucket replaced and the old card", listOf(same.registerD, first.registerD), listOf(replacing.registerC, replacing.registerD))
        val hook = after[swap.check + 1] as FiveRegisterInstruction
        assertEquals("$name: the card hook", CARD_AFTER_LOADING, after[swap.check + 1].call!!.smali())
        assertEquals("$name: the card hook reads the new copy and the card the check takes second", listOf(same.registerC, compare.registerD), listOf(hook.registerC, hook.registerD))
        val answer = after[swap.check + 2]
        assertEquals("$name: the card hook's answer", Opcode.MOVE_RESULT_OBJECT, answer.opcode)
        assertEquals("$name: the answer is the card the check takes", compare.registerD, (answer as OneRegisterInstruction).registerA)
        val cast = after[swap.check + 3]
        assertEquals("$name: the answer's cast", Opcode.CHECK_CAST, cast.opcode)
        assertEquals("$name: the answer is cast where it lands", compare.registerD, (cast as OneRegisterInstruction).registerA)
        assertEquals("$name: the answer is cast to a story card", STORY_CARD, (cast as ReferenceInstruction).reference.toString())
        assertEquals("$name: the card check follows the hook", code[swap.check].call!!.smali(), after[swap.check + 4].call!!.smali())
        for (index in code.indices) {
            val moved = when {
                index < swap.replacing -> index
                index < swap.check -> index + 1
                index < swap.navigate -> index + 4
                else -> index + 6
            }
            assertEquals("$name: onDataChanged instruction $index changed", code[index].opcode, after[moved].opcode)
        }
    }

    @Test
    fun `the extension has the marking hooks and the stubs the patch fills`() {
        val seen = ExtensionDex.classDef(STORY_SEEN).methods
        fun has(name: String, answer: String, vararg parameters: String) = seen.any {
            it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes.map(Any::toString) == parameters.toList()
        }
        val obj = "Ljava/lang/Object;"
        val string = "Ljava/lang/String;"
        assertTrue("StorySeen has no viewed hook", has("viewed", "V", obj, obj, obj, "Z"))
        assertTrue("StorySeen has no bucketMade hook", has("bucketMade", "V", obj))
        assertTrue("StorySeen has no watchedHere hook", has("watchedHere", "Z", obj))
        assertTrue("StorySeen has no readsWatched hook", has("readsWatched", "Z", obj))
        assertTrue("StorySeen has no startCard hook", has("startCard", string, obj, string))
        assertTrue("StorySeen has no replacing hook", has("replacing", "V", obj, obj))
        assertTrue("StorySeen has no indexAfterLoading hook", has("indexAfterLoading", "I", obj, obj, "I"))
        assertTrue("StorySeen has no cardAfterLoading hook", has("cardAfterLoading", obj, obj, obj))
        assertTrue("StorySeen has no mediaOf", has("mediaOf", obj, obj))
        assertTrue("StorySeen has no seenFlagOf", has("seenFlagOf", "Z", obj))
        for (stub in listOf("bucketIdOf", "cardsOf", "storyIdOf", "sessionOf", "bucketTypeOf")) assertTrue("StorySeen has no $stub", has(stub, obj, obj))
        assertTrue("StorySeen has no hasUnwatchedOf", has("hasUnwatchedOf", "Z", obj))
        assertTrue("StorySeen has no publishSeen", has("publishSeen", "V", obj, string, string, string, "Z"))
    }
}
