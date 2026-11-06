/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.seen

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Keeps the stories you view from being reported, which is what puts you on their viewer lists.
 * See StorySeenAnchors.kt for the report and how the sender is found.
 *
 * The hook goes first in the sender and returns before anything is built or sent, the way the
 * sender already returns when it has no cards. Only the viewing report stops: replies and
 * reactions are sent by another class. With the Mark as seen button's switch on as well, the hook
 * sends the cards you marked instead, in a set of their own, and a second hook in the seen helper
 * tells the button which card is on screen. A third hook, where Facebook's story controllers make a card the
 * active one, keeps the button on that card. Two more give the button a card as soon as it's active,
 * before it plays, and the story card's state, so the eye hides while the story is held (#150).
 *
 * Facebook greys a ring only once its servers take the report, so a fourth hook in the seen helper
 * hears each card queued, and a fifth each story bucket Facebook builds, and the extension
 * publishes a bucket watched to its newest story as watched, with the same calls Facebook makes
 * when its servers answer (#61). The servers keep sending it as unwatched, so it's published again
 * each time it's built, until a newer story arrives. A cold start draws the tray before any of that
 * lands, from buckets the fifth hook never hears, so two more go first in the bucket's own answer
 * to whether it has a story not watched yet, StoryBucket's and RegularStoryBucket's, and a bucket
 * kept as watched answers no from the first time it's asked (#165).
 *
 * Facebook also opens a bucket at its first card not marked seen, and that mark is lost on a
 * restart for a view it never heard about, so one more hook, in the check that picks the card a
 * bucket opens at, passes over each card watched on this phone (#218). The method asking that
 * check skips it for a bucket that reads as watched and takes a card the launch names over it, so
 * another hook goes first there and names the first card not watched on this phone instead. A Page
 * opened from its picture asks that method about a loading stand-in only, and the real bucket that
 * replaces it stays on the stand-in's card, story 1. Three last hooks, where the viewer takes in the
 * real bucket, swap the card it was on for the one that method would have opened and the index it
 * moves to for that card's.
 *
 * In the default selection with its switch and the button's both off, since it changes what other
 * people see.
 */
@Suppress("unused")
val viewStoriesAnonymouslyPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "View stories anonymously",
    description = "Keeps you off the viewer list of stories you watch, because Facebook isn't told which ones " +
        "you've seen. Replying or reacting still shows you. Stories you watch to the end show as watched on this " +
        "phone only, until that person posts a new one. Starts off. Turn it on in Hushfacebook settings > Stories.",
) {
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        val mutations = classDefByStrings(SEEN_ROOT_FIELD, StringComparisonType.EQUALS).filter(::isSeenMutation)
        val mutation = mutations.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one query class whose constructor loads \"$SEEN_MUTATION\" and \"$SEEN_ROOT_FIELD\", " +
                "found ${mutations.size}",
        )
        val senders = classDefByStrings(STORY_IDS, StringComparisonType.EQUALS).mapNotNull { seenSender(it, mutation.type) }
        val sender = senders.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one class that builds $SEEN_MUTATION with \"$STORY_IDS\" and sends it from one void " +
                "method taking the set of story ids, found ${senders.size}",
        )
        if (!hasSendShape(sender)) {
            throw PatchException("$PATCH: the sender doesn't take a callback and ${SENDER_SHAPE.joinToString("")}")
        }
        // The helper's literal can sit in a string table (582's armeabi-v7a build), so the sender's
        // callers are where it's looked for.
        val callers = mutableListOf<ClassDef>()
        val startChecks = mutableListOf<Method>()
        // A caller names the sender's class, and the start check casts to StoryCard.
        classDefForEachReferencing(types = listOf(sender.definingClass, STORY_CARD)) { classDef ->
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
            if (callsSender(classDef, sender)) callers += classDef
            startCheck(classDef)?.let { startChecks += it }
        }
        val opener = startChecks.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one class with an (Object, int)I method casting to $STORY_BUCKET and an (Object)Z " +
                "method asking a $STORY_CARD for getMedia() and then a flag, found ${startChecks.size}",
        )
        if (opener.localRegisterCount() < 1) {
            throw PatchException("$PATCH: ${opener.definingClass}->${opener.name} has no local register for the start hook")
        }
        // The start check is asked through the one interface its class implements.
        val cardInterface = classDefByOrNull(opener.definingClass)?.interfaces?.singleOrNull() ?: throw PatchException(
            "$PATCH: ${opener.definingClass} doesn't implement one interface for the start check",
        )
        val pickers = mutableListOf<Method>()
        classDefForEachReferencing(types = listOf(cardInterface)) { classDef ->
            classDef.methods.filterTo(pickers) { isStartPicker(it, cardInterface, opener.name) }
        }
        val picker = pickers.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one static ($cardInterface, Object, String, int, int)I method asking " +
                "$cardInterface->${opener.name} of each card, found ${pickers.size}",
        )
        if (picker.localRegisterCount() < 1) {
            throw PatchException("$PATCH: ${picker.definingClass}->${picker.name} has no local register for the start card hook")
        }
        val swaps = classDefByStrings(DATA_CHANGED, StringComparisonType.EQUALS).flatMap { owner ->
            owner.methods.filter { loads(it, DATA_CHANGED) }.mapNotNull { method -> loadingSwap(method)?.let { method to it } }
        }
        val (dataChanged, swap) = swaps.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one method loading \"$DATA_CHANGED\" that checks the new copy of a bucket against the one " +
                "it replaces, looks for the card it was on by id, checks it against the card at its place, looks " +
                "for it again and moves the viewer by index, found ${swaps.size}",
        )
        val tables = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
        val helper = seenHelper(callers, sender, tables)
        val card = cardSeen(helper)
        val storyCard = classDefByOrNull(STORY_CARD) ?: throw PatchException("$PATCH: this Facebook build has no $STORY_CARD")
        if (storyCard.methods.none { "${it.definingClass}->${it.name}()${it.returnType}" == CARD_ID && it.parameterTypes.isEmpty() }) {
            throw PatchException("$PATCH: $STORY_CARD has no getId()")
        }
        for (call in startCalls(opener)) {
            val declared = storyCard.methods.singleOrNull { it.name == call.name && it.parameterTypes.isEmpty() && it.returnType == call.returnType }
            if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags)) {
                throw PatchException("$PATCH: ${call.smali()} isn't public, so the extension can't ask it")
            }
        }
        val activations = classDefByStrings(ACTIVATE_STATE, StringComparisonType.EQUALS)
            .flatMap { owner -> owner.methods.filter(::isCardActivation).map { owner to it } }
        val (activationOwner, activation) = activations.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one controller method that loads \"$ACTIVATE_STATE\" and \"$ACTIVATE_CARD\", " +
                "found ${activations.size}",
        )
        val store = activeCardStore(activation)
        if (store < 0) {
            throw PatchException("$PATCH: ${activationOwner.type}->${activation.name} doesn't store the activated $STORY_CARD once")
        }
        val later = countLater(helper) { type -> classDefByOrNull(type) } ?: throw PatchException(
            "$PATCH: ${helper.type} doesn't make one listener from (StoryBucket, StoryCard, ${helper.type}, boolean) " +
                "in its (StoryBucket, StoryCard) method, or has no one FbUserSession field",
        )
        val updates = classDefByStrings(CARD_STATE_UPDATE, StringComparisonType.EQUALS)
            .flatMap { owner -> owner.methods.filter(::isCardStateUpdate).map { owner to it } }
        val cardState = updates.map { it.second.parameterTypes.single().toString() }.toSet().singleOrNull()
        if (updates.isEmpty() || cardState == null) {
            throw PatchException(
                "$PATCH: expected the story card state's updates naming \"$CARD_STATE_UPDATE\" to take one state, " +
                    "found ${updates.size} taking ${updates.map { it.second.parameterTypes }}",
            )
        }
        val flags = classDefByStrings(LONG_PRESS_ACTIVE, StringComparisonType.EQUALS)
            .flatMap { owner -> owner.methods.mapNotNull { longPressFlag(it, cardState) } }
            .distinctBy { "${it.definingClass}->${it.name}" }
        val flag = flags.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one boolean of $cardState read before \"$LONG_PRESS_ACTIVE\", found ${flags.size}",
        )
        for ((owner, field) in listOf(cardState to flag.name, helper.type to later.session.name)) {
            val classDef = classDefByOrNull(owner)
            val declared = classDef?.fields?.singleOrNull { it.name == field }
            if (classDef == null || !AccessFlags.PUBLIC.isSet(classDef.accessFlags) || declared == null ||
                !AccessFlags.PUBLIC.isSet(declared.accessFlags)
            ) {
                throw PatchException("$PATCH: $owner->$field isn't public in a public class, so the extension can't read it")
            }
        }
        val sendStub = stub(STORY_SEEN, SEND_STUB, "V")
        val cardIdStub = stub(STORY_SEEN_BUTTON, CARD_ID_STUB, "Ljava/lang/String;")
        val eyeStubMethods = listOf(LONG_PRESSED_STUB to "Z", HELPER_SESSION_STUB to "Ljava/lang/Object;")
            .associate { (name, answer) -> name to stub(STORY_SEEN_BUTTON, name, answer) }
        val eyeStubs = { name: String, _: String -> eyeStubMethods.getValue(name) }
        val marking = seenMarking()
        val seenStubs = mutableClassDefBy(STORY_SEEN)
        val stubs = { name: String, answer: String, parameters: List<String> ->
            seenStubs.methods.singleOrNull {
                it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.parameterTypes.map(Any::toString) == parameters
            } ?: throw PatchException("$PATCH: $STORY_SEEN has no static $answer $name(${parameters.joinToString("")})")
        }

        mutableClassDefBy(sender.definingClass).methods.single { it.sameAs(sender) }.filterViews()
        mutableClassDefBy(card.definingClass).methods.single { it.sameAs(card) }.apply {
            reportCard()
            hearViewedCards()
        }
        mutableClassDefBy(activationOwner.type).methods.single { it.sameAs(activation) }.reportActive(store)
        mutableClassDefBy(later.listener.type).methods.single { it.sameAs(later.constructor) }.reportCardLater()
        for ((owner, update) in updates) mutableClassDefBy(owner.type).methods.single { it.sameAs(update) }.reportState()
        mutableClassDefBy(REGULAR_BUCKET).methods.single { it.sameAs(marking.constructor) }.hearBucketMade()
        mutableClassDefBy(STORY_BUCKET).methods.single { it.sameAs(marking.buckets.hasUnwatched) }.answerWatchedFirst()
        mutableClassDefBy(REGULAR_BUCKET).methods.single { it.sameAs(marking.buckets.override) }.answerWatchedFirst()
        mutableClassDefBy(opener.definingClass).methods.single { it.sameAs(opener) }.skipWatchedCards()
        mutableClassDefBy(picker.definingClass).methods.single { it.sameAs(picker) }.nameStartCard(opener.definingClass)
        mutableClassDefBy(dataChanged.definingClass).methods.single { it.sameAs(dataChanged) }.openAfterLoading(swap)
        sendStub.fillSend(sender)
        cardIdStub.fillCardId()
        fillEyeStubs(eyeStubs, flag, helper.type, later.session)
        fillSeenStubs(stubs, marking.buckets, marking.publisher)
        fillStartStubs(stubs, opener)
        enableStatus("storySeen")
    }
}

/** What marking a watched bucket on the phone hooks and calls (#61), found before anything changes. */
internal class SeenMarking(val constructor: Method, val publisher: SeenPublisher, val buckets: StoryBuckets)

/**
 * Facebook's way of publishing a bucket's seen flag, the story bucket's members and
 * RegularStoryBucket's constructor, held to what StorySeenAnchors.kt describes: one way of
 * publishing, however many methods use it; RegularStoryBucket with one constructor; and each call
 * public in a public class, since the extension's stubs make them from another package.
 */
internal fun BytecodePatchContext.seenMarking(): SeenMarking {
    // 583's 32-bit publisher asks a string table for the flag's name, so publishers are found by naming it.
    val resolve = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    val publishers = methodsNaming(BUCKET_SEEN_FIELD) { it.implementation != null }
        .mapNotNull { seenPublisher(it, resolve) }.toSet()
    val publisher = publishers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one way of publishing \"$BUCKET_SEEN_FIELD\" on a \"$BUCKET_TYPE\" with $PUBLISH, " +
            "found ${publishers.size}",
    )
    val regular = classDefByOrNull(REGULAR_BUCKET) ?: throw PatchException("$PATCH: this Facebook build has no $REGULAR_BUCKET")
    val bucket = classDefByOrNull(STORY_BUCKET) ?: throw PatchException("$PATCH: this Facebook build has no $STORY_BUCKET")
    val buckets = storyBuckets(bucket, regular) ?: throw PatchException(
        "$PATCH: $REGULAR_BUCKET and $STORY_BUCKET aren't shaped as expected: one ImmutableList of cards, one " +
            "FbUserSession field, getId(), and one override answering from \"$BUCKET_SEEN_FIELD\" turned around",
    )
    val constructor = regular.methods.filter { it.name == "<init>" }.singleOrNull()
        ?: throw PatchException("$PATCH: expected one constructor of $REGULAR_BUCKET")
    for (call in listOf(publisher.consistency, publisher.factory, publisher.setId, publisher.setFlag, publisher.build)) {
        val owner = classDefByOrNull(call.definingClass)
        val declared = owner?.methods?.singleOrNull {
            it.name == call.name && it.returnType == call.returnType &&
                it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
        }
        if (owner == null || !AccessFlags.PUBLIC.isSet(owner.accessFlags) || declared == null ||
            !AccessFlags.PUBLIC.isSet(declared.accessFlags)
        ) {
            throw PatchException("$PATCH: ${call.smali()} isn't public in a public class, so the extension can't call it")
        }
    }
    return SeenMarking(constructor, publisher, buckets)
}

private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
    parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)

/** The extension's static stub [name] in [owner], answering [answer]. */
private fun BytecodePatchContext.stub(owner: String, name: String, answer: String): MutableMethod =
    mutableClassDefBy(owner).methods.singleOrNull {
        it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: throw PatchException("$PATCH: $owner has no static $answer $name")
