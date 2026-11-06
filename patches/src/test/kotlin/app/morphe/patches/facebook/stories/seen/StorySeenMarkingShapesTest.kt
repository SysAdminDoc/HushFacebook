/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.seen

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22b
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of marking watched buckets on the phone (#61) that need no Facebook build: which
 * methods the anchors take as Facebook's way of publishing a bucket's seen flag and StoryBucket's
 * members, which they turn down, and the code the two hooks put in, then the same for the check
 * that picks where a bucket opens (#218). The seen helper's per-card
 * method is cardSeen's, which StorySeenShapesTest and MarkAsSeenFixtureTest cover.
 */
class StorySeenMarkingShapesTest {
    private val helperType = "Lfixture/SeenHelper;"
    private val publisherType = "Lfixture/OnSeen;"
    private val builderType = "Lfixture/BucketBuilder;"
    private val builtType = "Lfixture/BuiltBucket;"
    private val string = "Ljava/lang/String;"

    private val consistency = ImmutableMethodReference("Lfixture/Consistency;", "A00", listOf(FB_USER_SESSION), CONSISTENCY)
    private val factory = ImmutableMethodReference(builtType, "A01", listOf(string), builderType)
    private val setId = ImmutableMethodReference("Lfixture/Trees;", "A1D", listOf(TREE_BUILDER, string), "V")
    private val setFlag = ImmutableMethodReference(builderType, "A0F", listOf(string, "Z"), "V")
    private val build = ImmutableMethodReference(builderType, "A01", emptyList(), builtType)
    private val publish = ImmutableMethodReference(CONSISTENCY, PUBLISH, listOf(TREE), "V")

    private fun text(register: Int, value: String) =
        ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

    private fun invoke(opcode: Opcode, reference: MethodReference, vararg registers: Int) = ImmutableInstruction35c(
        opcode, registers.size, registers.getOrElse(0) { 0 }, registers.getOrElse(1) { 0 }, registers.getOrElse(2) { 0 },
        registers.getOrElse(3) { 0 }, registers.getOrElse(4) { 0 }, reference,
    )

    private fun result(register: Int) = ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, register)

    private val returnVoid = ImmutableInstruction10x(Opcode.RETURN_VOID)

    private fun method(
        definingClass: String,
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        body: List<Instruction>,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ): Method = ImmutableMethod(
        definingClass, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
        ImmutableMethodImplementation(registers, body, null, null),
    )

    /**
     * Facebook's success callback for a seen report: the session's consistency, a bucket tree with
     * its id and [field] set, built and published. [published] false leaves out the publishing call.
     */
    private fun onSeen(field: String = BUCKET_SEEN_FIELD, published: Boolean = true, withConsistency: Boolean = true): Method {
        val body = mutableListOf<Instruction>()
        if (withConsistency) body += listOf(invoke(Opcode.INVOKE_STATIC, consistency, 6), result(2))
        body += listOf(
            text(0, BUCKET_TYPE), invoke(Opcode.INVOKE_STATIC, factory, 0), result(1),
            invoke(Opcode.INVOKE_STATIC, setId, 1, 4),
            text(0, field), invoke(Opcode.INVOKE_VIRTUAL, setFlag, 1, 0, 5),
            invoke(Opcode.INVOKE_VIRTUAL, build, 1), result(0),
        )
        if (published) body += invoke(Opcode.INVOKE_INTERFACE, publish, 2, 0)
        body += returnVoid
        return method(publisherType, "onSuccess", listOf(FB_USER_SESSION), "V", 7, body)
    }

    @Test
    fun `the publisher is the five calls that set a bucket's seen flag and publish it`() {
        val found = seenPublisher(onSeen())
        assertNotNull(found)
        assertEquals(SeenPublisher(consistency, factory, setId, setFlag, build), found)
    }

    @Test
    fun `a method that doesn't publish the seen flag gives no publisher`() {
        // Negative controls: Facebook's thread delete publishes another flag the same way, a
        // bucket built and never published isn't the callback, and nor is one with no session's
        // consistency to publish to.
        assertNull(seenPublisher(onSeen(field = "local_is_delete_in_progress")))
        assertNull(seenPublisher(onSeen(published = false)))
        assertNull(seenPublisher(onSeen(withConsistency = false)))
    }

    private fun queue() = method(
        helperType, "A00", listOf(FB_USER_SESSION, STORY_BUCKET, STORY_CARD, helperType, "Z"), "V", 8, listOf(returnVoid),
        flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    )

    private val bucketType = STORY_BUCKET
    private val regularType = REGULAR_BUCKET

    /** RegularStoryBucket's answer to "not watched yet": the seen field by its hash, turned around. */
    private fun unwatched(hash: Int = BUCKET_SEEN_FIELD.hashCode(), flips: Boolean = true, name: String = "A0X") = method(
        regularType, name, emptyList(), "Z", 3,
        listOfNotNull(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 2, ImmutableFieldReference(regularType, "A01", TREE_JNI)),
            ImmutableInstruction31i(Opcode.CONST, 1, hash),
            invoke(Opcode.INVOKE_VIRTUAL, ImmutableMethodReference(TREE_JNI, "getBooleanValue", listOf("I"), "Z"), 0, 1),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            if (flips) ImmutableInstruction22b(Opcode.XOR_INT_LIT8, 0, 0, 1) else null,
            ImmutableInstruction11x(Opcode.RETURN, 0),
        ),
    )

    private fun abstractMethod(name: String, returnType: String, flags: Int = AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value) =
        ImmutableMethod(bucketType, name, emptyList(), returnType, flags, null, null, null)

    private fun bucket(
        cards: Int = 1,
        sessions: Int = 1,
        finalAnswer: Boolean = false,
        id: Boolean = true,
    ): ClassDef {
        val methods = mutableListOf<Method>()
        repeat(cards) { methods += abstractMethod("A0I$it", IMMUTABLE_LIST) }
        methods += abstractMethod("A0X", "Z", AccessFlags.PUBLIC.value or (if (finalAnswer) AccessFlags.FINAL.value else AccessFlags.ABSTRACT.value))
        if (id) methods += abstractMethod("getId", string)
        val fields: List<Field> = List(sessions) { ImmutableField(bucketType, "A0$it", FB_USER_SESSION, AccessFlags.PUBLIC.value, null, null, null) }
        return ImmutableClassDef(bucketType, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;",
            null, null, null, fields, methods)
    }

    private fun regular(vararg methods: Method, superclass: String = bucketType): ClassDef =
        ImmutableClassDef(regularType, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods.toList())

    @Test
    fun `the bucket members are its cards, its session and the answer RegularStoryBucket gives from the seen field`() {
        val found = storyBuckets(bucket(), regular(unwatched()))
        assertNotNull(found)
        assertEquals("A0I0", found!!.cards.name)
        assertEquals("A00", found.session.name)
        assertEquals("A0X", found.hasUnwatched.name)
        assertEquals(bucketType, found.hasUnwatched.definingClass)
        assertEquals("A0X", found.override.name)
        assertEquals(regularType, found.override.definingClass)
        assertEquals("the tree a Highlights bucket's type is read from (#165)", "A01", found.tree.name)
        assertEquals(regularType, found.tree.definingClass)
    }

    @Test
    fun `a bucket's answer asks the extension first and goes on to Facebook's when it says no`() {
        val answer = unwatched()
        val code = answer.implementation!!.instructions.toList()
        val asked = MutableMethod(answer).apply { answerWatchedFirst() }.implementation!!.instructions.toList()
        assertEquals(code.size + 5, asked.size)
        assertEquals(READS_WATCHED, ((asked[0] as ReferenceInstruction).reference as MethodReference).let {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
        })
        assertEquals("the hook reads this", 2, (asked[0] as RegisterRangeInstruction).startRegister)
        assertEquals("the hook's answer lands in the free local", 0, (asked[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN, asked[4].opcode)
        assertEquals("a no goes on to Facebook's first instruction", code[0].opcode, asked[5].opcode)

        // Negative control: an answer with no local to spare isn't changed.
        val bare = method(regularType, "A0X", emptyList(), "Z", 1, listOf(ImmutableInstruction11x(Opcode.RETURN, 0)))
        assertThrows(PatchException::class.java) { MutableMethod(bare).answerWatchedFirst() }
    }

    @Test
    fun `a bucket that isn't shaped so gives no members`() {
        // Negative controls: the field read as is (seen, not unwatched), another field's hash, no
        // such override, an override StoryBucket doesn't declare, or declares final, two lists or
        // two sessions to pick from, no getId, and a RegularStoryBucket that isn't a StoryBucket.
        assertNull(storyBuckets(bucket(), regular(unwatched(flips = false))))
        assertNull(storyBuckets(bucket(), regular(unwatched(hash = "is_bucket_eligible_for_display".hashCode()))))
        assertNull(storyBuckets(bucket(), regular()))
        assertNull(storyBuckets(bucket(), regular(unwatched(name = "A0Y"))))
        assertNull(storyBuckets(bucket(finalAnswer = true), regular(unwatched())))
        assertNull(storyBuckets(bucket(cards = 2), regular(unwatched())))
        assertNull(storyBuckets(bucket(sessions = 2), regular(unwatched())))
        assertNull(storyBuckets(bucket(id = false), regular(unwatched())))
        assertNull(storyBuckets(bucket(), regular(unwatched(), superclass = "Ljava/lang/Object;")))
    }

    private fun MutableMethod.at(index: Int) = implementation!!.instructions.elementAt(index)

    @Test
    fun `the queue hands its bucket, card, helper and peek flag to the extension first thing`() {
        val queue = MutableMethod(queue())
        queue.hearViewedCards()
        val hook = queue.at(0)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        val reference = (hook as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/facebook/stories/StorySeen;", reference.definingClass)
        assertEquals("viewed", reference.name)
        assertEquals(listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;", "Z"),
            reference.parameterTypes.map { it.toString() })
        // Eight registers, five parameters: p1 to p4, the bucket to the peek flag, are v4 to v7.
        assertEquals(4, (hook as RegisterRangeInstruction).startRegister)
        assertEquals(4, hook.registerCount)
        assertEquals(Opcode.RETURN_VOID, queue.at(1).opcode)
    }

    /** RegularStoryBucket's constructor: the super call, a field set, and [extra] before its return. */
    private fun constructor(vararg extra: Instruction) = method(
        regularType, "<init>", listOf(TREE), "V", 3,
        listOf(
            invoke(Opcode.INVOKE_DIRECT, ImmutableMethodReference(bucketType, "<init>", emptyList(), "V"), 1),
            ImmutableInstruction22c(Opcode.IPUT_OBJECT, 2, 1, ImmutableFieldReference(regularType, "A01", TREE)),
        ) + extra + returnVoid,
        flags = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
    )

    @Test
    fun `the constructor hands the built bucket to the extension in front of its return`() {
        val made = MutableMethod(constructor())
        made.hearBucketMade()
        assertEquals(4, made.implementation!!.instructions.count())
        val hook = made.at(2)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals("bucketMade", ((hook as ReferenceInstruction).reference as MethodReference).name)
        // One local, so this is v1.
        assertEquals(1, (hook as RegisterRangeInstruction).startRegister)
        assertEquals(1, hook.registerCount)
        assertEquals(Opcode.RETURN_VOID, made.at(3).opcode)
    }

    @Test
    fun `a constructor that branches or writes over this is refused before anything goes in`() {
        val branches = MutableMethod(constructor(ImmutableInstruction21t(Opcode.IF_EQZ, 2, 2)))
        assertTrue(assertThrows(PatchException::class.java) { branches.hearBucketMade() }.message!!.contains(PATCH))
        assertEquals(4, branches.implementation!!.instructions.count())

        val overwrites = MutableMethod(constructor(ImmutableInstruction12x(Opcode.MOVE_OBJECT, 1, 2)))
        assertTrue(assertThrows(PatchException::class.java) { overwrites.hearBucketMade() }.message!!.contains(PATCH))
        assertEquals(4, overwrites.implementation!!.instructions.count())
    }

    private val starterType = "Lfixture/BucketStarter;"
    private val getMedia = ImmutableMethodReference(STORY_CARD, "getMedia", emptyList(), "Lfixture/Media;")
    private val seenFlag = ImmutableMethodReference(STORY_CARD, "A1d", emptyList(), "Z")

    /** 584's start check (LX/B6N;->Cfw): the card cast, then getMedia() and the seen flag, with [extra] calls after. */
    private fun check(
        cast: String = STORY_CARD,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        extra: List<Instruction> = emptyList(),
        lead: List<Instruction> = emptyList(),
    ) = method(
        starterType, "Cfw", listOf("Ljava/lang/Object;"), "Z", 3,
        lead + listOf(
            ImmutableInstruction21c(Opcode.CHECK_CAST, 2, ImmutableTypeReference(cast)),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            // Offsets in code units, each to the return (unit 18 without extra calls).
            ImmutableInstruction21t(Opcode.IF_EQZ, 2, 15 + 3 * extra.size),
            invoke(Opcode.INVOKE_VIRTUAL, getMedia, 2),
            result(0),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 9 + 3 * extra.size),
            invoke(Opcode.INVOKE_VIRTUAL, seenFlag, 2),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
        ) + extra + listOf(
            ImmutableInstruction21t(Opcode.IF_NEZ, 0, 3),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 1),
            ImmutableInstruction11x(Opcode.RETURN, 1),
        ),
        flags,
    )

    /** The interface's (Object, int)I method beside it, casting its bucket to [cast]. */
    private fun bucketIndex(cast: String = STORY_BUCKET) = method(
        starterType, "AAa", listOf("Ljava/lang/Object;", "I"), "I", 6,
        listOf(ImmutableInstruction21c(Opcode.CHECK_CAST, 4, ImmutableTypeReference(cast)), ImmutableInstruction11x(Opcode.RETURN, 5)),
    )

    private fun starter(vararg methods: Method): ClassDef =
        ImmutableClassDef(starterType, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    @Test
    fun `the start check is the card check beside the bucket index method`() {
        assertEquals("Cfw", startCheck(starter(check(), bucketIndex()))?.name)
        // The null check Kotlin puts first in the methods beside it (584 LX/0eX;->A0H) is let by.
        val nullCheck = invoke(Opcode.INVOKE_STATIC, ImmutableMethodReference("Lfixture/Checks;", "A0H", listOf("Ljava/lang/Object;", "I"), "V"), 2, 1)
        assertEquals("Cfw", startCheck(starter(check(lead = listOf(nullCheck)), bucketIndex()))?.name)
    }

    @Test
    fun `a card check that isn't the start check is turned down`() {
        // Negative controls: the other implementer (584 LX/B6O;) casts its bucket to another type,
        // a check of another card type, a static one, one making a third virtual call, one calling a
        // constructor, and no index method.
        assertNull(startCheck(starter(check(), bucketIndex(cast = "Lfixture/OtherBucket;"))))
        assertNull(startCheck(starter(check(cast = "Lfixture/OtherCard;"), bucketIndex())))
        assertNull(startCheck(starter(check(flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value), bucketIndex())))
        val third = invoke(Opcode.INVOKE_VIRTUAL, ImmutableMethodReference(STORY_CARD, "A1e", emptyList(), "Z"), 2)
        assertNull(startCheck(starter(check(extra = listOf(third)), bucketIndex())))
        val made = invoke(Opcode.INVOKE_DIRECT, ImmutableMethodReference("Lfixture/Media;", "<init>", emptyList(), "V"), 0)
        assertNull(startCheck(starter(check(lead = listOf(made)), bucketIndex())))
        assertNull(startCheck(starter(check())))
    }

    @Test
    fun `a card watched here answers no and every other card gets Facebook's own check`() {
        val start = MutableMethod(check())
        start.skipWatchedCards()
        assertEquals(16, start.implementation!!.instructions.count())
        val hook = start.at(0)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals(WATCHED_HERE, ((hook as ReferenceInstruction).reference as MethodReference).let {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
        })
        // Three registers, one local: the card is v2.
        assertEquals(2, (hook as RegisterRangeInstruction).startRegister)
        assertEquals(1, hook.registerCount)
        assertEquals(Opcode.MOVE_RESULT, start.at(1).opcode)
        val branch = start.at(2) as BuilderOffsetInstruction
        assertEquals(Opcode.IF_EQZ, branch.opcode)
        assertEquals("the branch doesn't go on to Facebook's check", 5, branch.target.location.index)
        assertEquals(Opcode.CONST_4, start.at(3).opcode)
        assertEquals(Opcode.RETURN, start.at(4).opcode)
        assertEquals(Opcode.CHECK_CAST, start.at(5).opcode)
    }

    private val cardsType = "Lfixture/Cards;"
    private val asked = ImmutableMethodReference(cardsType, "Cfw", listOf("Ljava/lang/Object;"), "Z")

    /** 585's start picker (LX/0C5S;->A01) in short: the card interface's check asked of a card, then an index returned. */
    private fun picker(
        parameters: List<String> = listOf(cardsType, "Ljava/lang/Object;", string, "I", "I"),
        asks: MethodReference = asked,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
    ) = method(
        "Lfixture/Picker;", "A01", parameters, "I", 17,
        listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 10, -1),
            invoke(Opcode.INVOKE_INTERFACE, asks, 12, 13),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN, 10),
        ),
        flags,
    )

    @Test
    fun `the start picker is the static method asking the card interface's check`() {
        assertTrue(isStartPicker(picker(), cardsType, "Cfw"))
        // Negative controls: an instance method, another interface's check, another method of the
        // interface, and one taking no second int.
        assertFalse(isStartPicker(picker(flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value), cardsType, "Cfw"))
        assertFalse(isStartPicker(picker(asks = ImmutableMethodReference("Lfixture/Other;", "Cfw", listOf("Ljava/lang/Object;"), "Z")), cardsType, "Cfw"))
        assertFalse(isStartPicker(picker(), cardsType, "Cfx"))
        assertFalse(isStartPicker(picker(parameters = listOf(cardsType, "Ljava/lang/Object;", string, "I")), cardsType, "Cfw"))
    }

    @Test
    fun `the start picker goes on with the extension's card id for StoryBucket's interface only`() {
        val picked = MutableMethod(picker())
        picked.nameStartCard(starterType)
        assertEquals(9, picked.implementation!!.instructions.count())
        // Seventeen registers, five parameters: the interface is v12, the bucket v13, the card id v14.
        val move = picked.at(0)
        assertEquals(Opcode.MOVE_OBJECT_FROM16, move.opcode)
        assertEquals(12, (move as TwoRegisterInstruction).registerB)
        val type = picked.at(1)
        assertEquals(Opcode.INSTANCE_OF, type.opcode)
        assertEquals(starterType, ((type as ReferenceInstruction).reference as TypeReference).type)
        val branch = picked.at(2) as BuilderOffsetInstruction
        assertEquals(Opcode.IF_EQZ, branch.opcode)
        assertEquals("the branch doesn't go on to Facebook's picker", 5, branch.target.location.index)
        val hook = picked.at(3)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals(START_CARD, ((hook as ReferenceInstruction).reference as MethodReference).smali())
        assertEquals(13, (hook as RegisterRangeInstruction).startRegister)
        assertEquals(2, hook.registerCount)
        val answer = picked.at(4)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, answer.opcode)
        assertEquals("the answer isn't the card id the picker goes on with", 14, (answer as OneRegisterInstruction).registerA)
        assertEquals(Opcode.CONST_4, picked.at(5).opcode)
    }
}
