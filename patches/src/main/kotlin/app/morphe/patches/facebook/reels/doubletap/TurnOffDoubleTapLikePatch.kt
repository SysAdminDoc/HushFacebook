/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.doubletap

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Keeps a double tap on a reel or a video from liking it. See DoubleTapAnchors.kt for where a double
 * tap likes and where the hooks go.
 *
 * Over nearly every player, the gesture view's double-tap handler reads as absent while the switch
 * is on, which is how Facebook builds a player without a double-tap like: no heart plays and the
 * tap goes no further. The view's other gestures, the double-tap seek included, and a single tap
 * are untouched. Behind that, the reel like helper's double-tap like finds no key for the reel,
 * which stops the players that call it themselves before the reel's sidebar hears of it, and the
 * helper's like holds back a like whose source is a double tap. The feed attachment that plays its
 * own heart leaves its double tap unhandled. On Home's photos the double-tap listener their
 * components share answers no double tap, so no heart plays and nothing is liked, while the single
 * tap that opens a photo and the long press have detectors of their own; the feed's double-tap like
 * helper holds its like back behind that. The Like button likes as before.
 *
 * In the default selection with its switch off: a double tap to like is a gesture people use on
 * purpose, so taking it away is a choice to make, as with Tap to play.
 */
@Suppress("unused")
val turnOffDoubleTapLikePatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Turn off double tap to like",
    description = "Stops a double tap on a reel, a video or a photo in the feed from liking it, so you don't like " +
        "things by accident. A single tap still plays, pauses or opens the photo, and the Like button still works. " +
        "Starts off. Turn it on in Hushfacebook settings > Reels and Watch.",
) {
    category("Reels")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Every anchor of every hook is found, every reader that would refuse the gesture-view hook
        // is checked, and SettingsStatus is confirmed to carry the switch's own method, before either
        // hook changes a single instruction. A build that fails anywhere in the find phase is refused
        // with nothing patched: the like hooks, the gesture view or a reader partway through can't
        // stay in the APK while a later anchor is missing. Without this, enableStatus's own check
        // would run last and could refuse after both hooks were already in.
        requireStatusMethod("doubleTapLike")
        val reelLikeAnchors = findReelLikeAnchors()
        val gestureViewAnchors = findGestureViewAnchors()
        val feedPhotoAnchors = findFeedPhotoAnchors()
        // The two hooks read different classes in every build seen so far; if a reader the gesture
        // view hook would change ever turned out to live in the helper or the attachment, changing
        // one hook would shift the read positions the other already recorded. Refuse rather than
        // risk that, instead of assuming the classes stay apart.
        val reelLikeClasses = setOf(reelLikeAnchors.helperType, reelLikeAnchors.attachment.definingClass)
        gestureViewAnchors.plans.firstOrNull { it.reader.definingClass in reelLikeClasses }?.let {
            refuse(
                "${it.reader.definingClass}->${it.reader.name} is both a gesture view reader and part of the " +
                    "reel like hooks; the two hooks can't be told apart",
            )
        }
        // The feed photo hooks' classes are kept apart from the other hooks' for the same reason.
        val feedPhotoClasses = setOf(feedPhotoAnchors.like.definingClass, feedPhotoAnchors.listener.definingClass)
        (gestureViewAnchors.plans.map { it.reader.definingClass } + reelLikeClasses).firstOrNull { it in feedPhotoClasses }?.let {
            refuse("$it is part of both the feed photo hooks and the reel or gesture view hooks; the hooks can't be told apart")
        }
        applyReelLikeAnchors(reelLikeAnchors)
        applyGestureViewAnchors(gestureViewAnchors)
        applyFeedPhotoAnchors(feedPhotoAnchors)
        enableStatus("doubleTapLike")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.isSameAs(other: Method): Boolean =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)

/** What [findReelLikeAnchors] found, for [applyReelLikeAnchors] to change. */
private class ReelLikeAnchors(
    val helperType: String,
    val like: Method,
    val doubleTapLike: Method,
    val key: Int,
    val attachment: Method,
)

/**
 * The reel like helper: its like holds back a double tap's, its double-tap like finds no key, and
 * the feed attachment asking it leaves its double tap unhandled. Finds every anchor; changes none.
 */
private fun BytecodePatchContext.findReelLikeAnchors(): ReelLikeAnchors {
    val likes = classDefByStrings(MUTATE_LIKE, StringComparisonType.EQUALS).flatMap { owner -> owner.methods.filter(::isReelLike) }
    val like = likes.singleOrNull()
        ?: refuse("expected one reel like holding \"$MUTATE_LIKE\" and taking the session first and the source as its last string, found ${likes.size}")
    val helper = classDefBy(like.definingClass)
    val doubleTapLike = doubleTapLikes(helper).singleOrNull()
        ?: refuse("expected the reel like helper to have one double-tap like taking two objects and a boolean")
    val key = likeKeyResult(doubleTapLike)
        ?: refuse("the reel like helper's double-tap like doesn't return on a missing key from its key maker")
    val attachments = classDefByStrings(HEART_RISE, StringComparisonType.EQUALS)
        .flatMap { owner -> owner.methods.filter { isAttachmentDoubleTap(it, doubleTapLike) } }
    val attachment = attachments.singleOrNull() ?: refuse(
        "expected one onDoubleTap asking the reel like helper's double-tap like and loading \"$HEART_RISE\", " +
            "found ${attachments.size}",
    )
    // Both hooks below borrow v0 at index 0, which only a method with a free local can spare.
    // Checked here, before either mutation runs, so a method with none of its own refuses the whole
    // hook rather than leaving the other one already changed.
    like.requireOneLocal()
    attachment.requireOneLocal()
    return ReelLikeAnchors(helper.type, like, doubleTapLike, key, attachment)
}

/** Throws unless [this] has at least one local register, which an injection at index 0 may borrow. */
private fun Method.requireOneLocal() {
    val locals = localRegisterCount()
    if (locals < 1) refuse("$definingClass->$name has $locals local register(s), needs 1")
}

private fun BytecodePatchContext.applyReelLikeAnchors(anchors: ReelLikeAnchors) {
    val mutableHelper = mutableClassDefBy(anchors.helperType)
    mutableHelper.methods.single { it.isSameAs(anchors.like) }.holdBackDoubleTapLike()
    mutableHelper.methods.single { it.isSameAs(anchors.doubleTapLike) }.emptyKeyAfter(anchors.key)
    mutableClassDefBy(anchors.attachment.definingClass).methods.single { it.isSameAs(anchors.attachment) }.leaveDoubleTapUnhandled()
}

/** What [findGestureViewAnchors] found for one reader: where its hook goes and which reads it covers. */
private class GestureViewReaderPlan(val reader: Method, val hook: String, val reads: List<Int>)

/** What [findGestureViewAnchors] found, for [applyGestureViewAnchors] to change. */
private class GestureViewAnchors(val handler: FieldReference, val plans: List<GestureViewReaderPlan>)

/**
 * GestureReactionComponent's view: the heart and each hand-over read the double-tap handler as
 * absent. Finds every reader and checks every one of them before any changes; a reader that can't
 * be trusted refuses here, with every other reader still exactly as Facebook built it.
 */
private fun BytecodePatchContext.findGestureViewAnchors(): GestureViewAnchors {
    val components = classDefByStrings(GESTURE_REACTION, StringComparisonType.EQUALS).filter(::isGestureReactionComponent)
    val component = components.singleOrNull()
        ?: refuse("expected one class whose constructor holds \"$GESTURE_REACTION\", found ${components.size}")
    val viewType = mountedView(component)
        ?: refuse("$GESTURE_REACTION's onCreateMountContent doesn't make one view")
    val view = classDefByOrNull(viewType) ?: refuse("this build has no $viewType")
    val heart = hearts(view).singleOrNull()
        ?: refuse("expected the gesture view to have one static heart holding \"$SHORT_FORM_VIDEO_UNIT\"")
    val handler = handlerField(heart) ?: refuse("the gesture view's heart reads none of the view's fields")

    // The view, what the view makes (its gesture listener) and what the component makes (its event
    // subscriber): nothing else in 577 or 580 reads the handler.
    val owners = (sequenceOf(viewType) + typesMadeBy(view) + typesMadeBy(component)).distinct()
        .mapNotNull(::classDefByOrNull).toList()
    val readers = owners.flatMap { owner -> owner.methods.filter { readsOf(it, handler).isNotEmpty() } }
    if (readers.none { it.isSameAs(heart) && it.definingClass == heart.definingClass }) {
        refuse("the gesture view's heart doesn't read $handler")
    }
    if (readers.none { it.name == "onDoubleTap" }) refuse("no onDoubleTap of the gesture view's listener reads $handler")

    // Every reader is checked here, before the loop that changes one; a reader that fails its check
    // stops the whole hook with every reader before it still unread.
    val plans = readers.map { reader ->
        val reads = readsOf(reader, handler)
        reads.firstOrNull { !isCheckedRead(reader, it) }?.let {
            refuse("${reader.definingClass}->${reader.name} reads $handler at $it without checking it for null straight away")
        }
        val hook = if (reader.definingClass == heart.definingClass && reader.isSameAs(heart)) HEART else HANDLER
        GestureViewReaderPlan(reader, hook, reads)
    }
    return GestureViewAnchors(handler, plans)
}

private fun BytecodePatchContext.applyGestureViewAnchors(anchors: GestureViewAnchors) {
    for (plan in anchors.plans) {
        val mutable = mutableClassDefBy(plan.reader.definingClass).findMutableMethodOf(plan.reader)
        // Last read first, so each index still names its read.
        plan.reads.sortedDescending().forEach { mutable.askAfterRead(it, anchors.handler, plan.hook) }
    }
}

/** What [findFeedPhotoAnchors] found, for [applyFeedPhotoAnchors] to change. */
private class FeedPhotoAnchors(val like: Method, val listener: Method)

/**
 * Home's photos: the double-tap listener their components share answers no double tap, and the
 * feed's double-tap like helper holds its like back. Finds both; changes neither.
 */
private fun BytecodePatchContext.findFeedPhotoAnchors(): FeedPhotoAnchors {
    // From 586 the helper asks a string table for the haptic's name instead of loading it, so the
    // table's callers count too.
    val helpers = methodsNaming(FEED_LIKE_HAPTIC) { it.implementation != null }.map { it.definingClass }.distinct()
        .mapNotNull(::classDefByOrNull).filter(::isFeedLikeHelper)
    val helper = helpers.singleOrNull() ?: refuse(
        "expected one class loading \"$FEED_LIKE_HAPTIC\" (itself or through a string table) with an instance " +
            "$ANIMATION_POSITION answering a PointF, found ${helpers.size}",
    )
    val like = feedLikes(helper).singleOrNull()
        ?: refuse("expected the feed's double-tap like helper to have one static like taking a GraphQLFeedback")
    // The feed components calling the helper make the listener along with their other detectors'.
    val made = mutableSetOf<String>()
    classDefForEachReferencing(types = listOf(helper.type)) { owner ->
        if (owner.type != helper.type && callsInto(owner, helper.type)) made += typesMadeBy(owner)
    }
    val listeners = made.mapNotNull(::classDefByOrNull).mapNotNull(::feedDoubleTapEvent)
    val listener = listeners.singleOrNull() ?: refuse(
        "expected one $GESTURE_LISTENER with only $DOUBLE_TAP_EVENT made by a class calling the feed's double-tap " +
            "like helper, found ${listeners.size}",
    )
    // Both hooks borrow v0 at index 0; checked before any hook of this patch changes anything.
    like.requireOneLocal()
    listener.requireOneLocal()
    return FeedPhotoAnchors(like, listener)
}

private fun BytecodePatchContext.applyFeedPhotoAnchors(anchors: FeedPhotoAnchors) {
    mutableClassDefBy(anchors.like.definingClass).findMutableMethodOf(anchors.like).holdBackFeedLike()
    mutableClassDefBy(anchors.listener.definingClass).findMutableMethodOf(anchors.listener).leaveFeedDoubleTapUnreported()
}

/**
 * First thing in the feed photo listener's onDoubleTapEvent: hand the extension the event and
 * answer false, no double tap, while the switch holds it back. v0 is free at index 0, checked by
 * [requireOneLocal] before any hook of this patch changes anything.
 */
private fun MutableMethod.leaveFeedDoubleTapUnreported() {
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${parameterRegister(0)}
            invoke-static { v0 }, $HOLD_BACK_FEED_TAP
            move-result v0
            if-eqz v0, :tap
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("tap", getInstruction(0)),
    )
}

/**
 * First thing in the feed's double-tap like: return without liking while the switch holds it back.
 * v0 is free at index 0, checked by [requireOneLocal] before any hook of this patch changes anything.
 */
private fun MutableMethod.holdBackFeedLike() {
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_BACK_FEED_LIKE
            move-result v0
            if-eqz v0, :like
            return-void
        """,
        ExternalLabel("like", getInstruction(0)),
    )
}

/**
 * After the read at [read], the extension's answer in place of what was read: the handler, or null
 * while the switch holds the double tap back. Nothing else reaches the null check that follows, and
 * the range form passes the register whatever its number.
 */
private fun MutableMethod.askAfterRead(read: Int, handler: FieldReference, hook: String) {
    val register = (getInstruction(read) as OneRegisterInstruction).registerA
    addInstructions(
        read + 1,
        """
            invoke-static/range { v$register .. v$register }, $hook
            move-result-object v$register
            check-cast v$register, ${handler.type}
        """,
    )
}

/**
 * First thing in the reel like helper's like: hand the extension the source, the [likeSource]
 * parameter, and return while it holds a double tap's like back. v0 is free at index 0, checked by
 * [requireOneLocal] before this or any other hook of this patch changes anything.
 */
private fun MutableMethod.holdBackDoubleTapLike() {
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${parameterRegister(likeSource(this)!!)}
            invoke-static { v0 }, $HOLD_BACK_LIKE
            move-result v0
            if-eqz v0, :like
            return-void
        """,
        ExternalLabel("like", getInstruction(0)),
    )
}

/**
 * After the double-tap like takes the reel's key at [taken], the extension's answer in its place:
 * the key, or null while the switch holds the double tap back, which sends the method down its own
 * return before anything else runs.
 */
private fun MutableMethod.emptyKeyAfter(taken: Int) {
    val register = (getInstruction(taken) as OneRegisterInstruction).registerA
    addInstructions(
        taken + 1,
        """
            invoke-static/range { v$register .. v$register }, $LIKE_KEY
            move-result-object v$register
            check-cast v$register, $STRING
        """,
    )
}

/**
 * First thing in the feed attachment's onDoubleTap: answer false, the tap not handled, while the
 * switch holds it back. v0 is free at index 0, checked by [requireOneLocal] before this or any
 * other hook of this patch changes anything.
 */
private fun MutableMethod.leaveDoubleTapUnhandled() {
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_BACK_TAP
            move-result v0
            if-eqz v0, :tap
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("tap", getInstruction(0)),
    )
}
