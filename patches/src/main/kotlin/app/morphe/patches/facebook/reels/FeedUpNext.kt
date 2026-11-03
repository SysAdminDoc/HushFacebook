/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The UP NEXT bar under a video opened from the feed, "UP NEXT: <author> · 0:27" with the next
 * video's thumbnail and an X (#367). Read from 585, 2026-11-02, arm64; the obfuscated names here are
 * for reviewers, the code never writes one down.
 *
 * - ModernFeedUpNextController (LX/0dTg;) holds the next video for each video by its id. Its static,
 *   synchronized lookup (A00) is the one method holding the ModernFeedUpNextFeedUnitFeedQuery
 *   GraphQL query, and the two maybeScheduleFetchRunnable runnables (LX/0cj1;, LX/0cj2;) call it
 *   with the controller and the video's id.
 * - The bar's components (LX/0Jom;, LX/0JpU;, built in LX/0cah;'s render) read what came back
 *   through the controller's synchronized (String)List getter (A03), which answers Facebook's own
 *   empty list when nothing was stored for that id.
 * - Both hooks go in first thing, before the lock: the lookup returns before it queries, and the
 *   getter answers an empty list, the answer Facebook gives a video with nothing up next. The getter
 *   catches a video whose next one was looked up before the switch was turned on.
 *
 * The bar's room (#375). The feed's video attachment (LX/03D0;'s A18) measures the bar and hands its
 * height to a static setter (LX/086O;'s A00, (…FI)V) that writes it into the video's Up next state
 * (LX/059K;'s A00 Float). The full-screen player keeps that many pixels empty under the seek bar,
 * from its first frame and whether or not the bar has a video to show, and the end screens get it
 * as ModernFeedUpNextBottomInsetPx. The setter is the one method calling the kept
 * [OVERLAID_ASPECT_RATIO]. It returns first thing when the switch is on, so the Float stays null:
 * Facebook's own state for a video with no inset, which keeps the seek bar at the bottom.
 */

internal const val SKIP_UP_NEXT_FETCH = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->skipUpNextFetch()Z"
internal const val HIDE_UP_NEXT_VIDEOS = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideUpNextVideos()Z"
internal const val SKIP_UP_NEXT_ROOM = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->skipUpNextRoom()Z"

/** The GraphQL query the Up next lookup sends, a name Facebook's server reads, so it stays. */
internal const val UP_NEXT_QUERY = "ModernFeedUpNextFeedUnitFeedQuery"

/** A Kotlin internal function the inset setter calls, kept by name with its module suffix on 585. */
internal const val OVERLAID_ASPECT_RATIO = "getOverlaidVideoAspectRatioMaybe\$fbandroid_java_com_facebook_feedplugins_video_video"

private const val PATCH = "Clean up Reels"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"

/** The lookups among [methods]: static, answering nothing, taking a [controller] and a String, holding [UP_NEXT_QUERY]. */
internal fun <M : Method> upNextLookups(controller: String, methods: Iterable<M>): List<M> = methods.filter { method ->
    val params = method.parameterTypes.map(CharSequence::toString)
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        controller in params && STRING in params && holdsString(method, UP_NEXT_QUERY)
}

/** The getters among [methods]: instance methods with a body that answer a List for a String. */
internal fun <M : Method> upNextGetters(methods: Iterable<M>): List<M> = methods.filter { method ->
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null && method.returnType == LIST &&
        method.parameterTypes.map(CharSequence::toString) == listOf(STRING)
}

/**
 * The inset setters among [methods]: static, answering nothing, ending in a Float and an int, and
 * calling [OVERLAID_ASPECT_RATIO].
 */
internal fun <M : Method> upNextInsetSetters(methods: Iterable<M>): List<M> = methods.filter { method ->
    val params = method.parameterTypes.map(CharSequence::toString)
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        params.size >= 2 && params.takeLast(2) == listOf("F", "I") &&
        method.implementation?.instructions?.any { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.name == OVERLAID_ASPECT_RATIO
        } == true
}

/** The controller's lookup skips its query and its getter answers an empty list when the switch is on. */
internal fun BytecodePatchContext.hideUpNext() {
    val controllers = classDefByStrings(UP_NEXT_QUERY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter { upNextLookups(it.type, it.methods).isNotEmpty() }
    val controller = controllers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one class whose lookup holds \"$UP_NEXT_QUERY\", found ${controllers.size}")
    val mutable = mutableClassDefBy(controller.type)
    val lookup = upNextLookups(controller.type, mutable.methods).singleOrNull()
        ?: throw PatchException("$PATCH: ${controller.type} has more than one Up next lookup")
    val getter = upNextGetters(mutable.methods).singleOrNull() ?: throw PatchException(
        "$PATCH: expected one (String)List getter in ${controller.type}, found ${upNextGetters(mutable.methods).size}",
    )
    lookup.returnVoidWhen(SKIP_UP_NEXT_FETCH)
    getter.returnEmptyListWhen(HIDE_UP_NEXT_VIDEOS)
    keepNoUpNextRoom()
}

/** The setter of the bar's room returns before it writes the height when the switch is on (#375). */
private fun BytecodePatchContext.keepNoUpNextRoom() {
    val setters = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        upNextInsetSetters(classDef.methods).mapTo(setters) { classDef.type to it }
    }
    val (owner, setter) = setters.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static (…FI)V method calling $OVERLAID_ASPECT_RATIO, found ${setters.size}",
    )
    val params = setter.parameterTypes.map(CharSequence::toString)
    mutableClassDefBy(owner).methods.first { it.name == setter.name && it.parameterTypes.map(CharSequence::toString) == params }
        .returnVoidWhen(SKIP_UP_NEXT_ROOM)
}

/** Asks [hook] first thing and answers an empty list when it says so. v0 is free there, as in [returnVoidWhen]. */
internal fun MutableMethod.returnEmptyListWhen(hook: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_list
            invoke-static { }, Ljava/util/Collections;->emptyList()Ljava/util/List;
            move-result-object v0
            return-object v0
        """,
        ExternalLabel("facebooks_list", getInstruction(0)),
    )
}
