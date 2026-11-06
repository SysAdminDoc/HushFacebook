/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.storiestray

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.menu.menuSubpagesPatch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/** The extension's answer for a tray adapter's count, which takes the adapter, its kind and Facebook's count. */
internal const val STORIES_TRAY_COUNT = "$EXTENSION_PACKAGE/feed/FeedFilter;->storiesTrayCount(Ljava/lang/Object;II)I"

/** What the hook passes, matching the extension's `FeedFilter.LEGACY_TRAY`, `UNIFIED_TRAY` and `HOME_COMPOSER`. */
internal const val LEGACY_TRAY = 0
internal const val UNIFIED_TRAY = 1
internal const val HOME_COMPOSER = 2

/**
 * Removes the row of stories at the top of the news feed, Create story included, and the
 * "What's on your mind?" composer row above it.
 *
 * The tray is never an edge, so the feed guard can't see it (a debug log of every edge on a
 * signed-in 580 showed none while the tray was on screen). It is an adapter the feed's adapter
 * configuration adds, in one of two methods (see Fingerprints.kt), and each returns its adapter's
 * class, a final one. The patch gives both classes a getItemCount() that asks the extension with
 * what Facebook's own count says, and the extension answers 0 while the switch is on. The feed
 * builds its adapters once per feed view but reads their counts on every change, a pull to
 * refresh among them, so the switch shows then, either way, with no restart (seen on a Galaxy
 * S25 with Facebook 581, 2026-10-05). Both switches start off: stories are people's own posts.
 *
 * The composer row is an adapter of the same list, handed over by a getter of the same
 * configuration class, and its class is final and counts through the same superclass chain, so it
 * gets the same count under a kind of its own and a switch of its own, which starts off.
 *
 * The rows of Stories Facebook puts between posts are edges, DiscoverFeedUnit ones, so the feed
 * guard this patch brings takes them out under the independent between-posts switch (issue #45), and the tray too
 * should it ever come as an edge, a StoriesTrayFeedUnit one. So do the single large Stories tile
 * and the single person's Stories viewer the same model answers through its table of type names,
 * StoriesOneColumnOneRowLargeTileFeedUnit and StoriesSingleBucketInlineViewerFeedUnit
 * (StoriesBetweenPostsFixtureTest finds both in every declared build). Hide suggested and
 * promoted posts takes out the rows of Stories from people you aren't connected to on its own
 * switch.
 */
@Suppress("unused")
val hideStoriesTrayPatch = bytecodePatch(
    name = "Hide Stories tray",
    description = "Hides the row of stories at the top of the feed, the stories between posts, or the \"What's " +
        "on your mind?\" row at the top of Home, so you reach posts sooner. Starts off. Turn on the parts you " +
        "want in Hushfacebook settings > News feed.",
) {
    category("Stories")
    // A Menu built while the tray first goes could open its pages over the Menu (#276).
    dependsOn(settingsPatch, feedFilterHookPatch, menuSubpagesPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val legacy = classDefByStrings(ADD_STORIES_ADAPTER, StringComparisonType.EQUALS).flatMap(::legacyTrayAdapters)
        val classic = legacy.singleOrNull() ?: throw PatchException(
            "Expected one static tray adapter holding \"$ADD_STORIES_ADAPTER\", found ${legacy.size}",
        )
        val configuration = mutableClassDefBy(classic.definingClass)
        val unified = unifiedTrayAdapters(configuration).singleOrNull() ?: throw PatchException(
            "${classic.definingClass} has no single unified tray adapter holding \"$TRAY_ADAPTER_START\", " +
                "\"$TRAY_ADAPTER_STOP\" and \"$TOFU\"",
        )

        val composer = composerAdapters(configuration).singleOrNull() ?: throw PatchException(
            "${classic.definingClass} has no single composer row getter holding \"$INLINE_COMPOSER_ADAPTER\"",
        )

        countThroughTheExtension(classic.returnType, LEGACY_TRAY)
        countThroughTheExtension(unified.returnType, UNIFIED_TRAY)
        countThroughTheExtension(composer.returnType, HOME_COMPOSER)
        enableStatus("storiesTray")
    }
}

/**
 * Gives the adapter class [adapterType], a tray's or the composer row's, a getItemCount() that
 * hands its superclass's count to the extension and returns the answer. The class has to be final, so no subclass counts past it,
 * must not count for itself yet, and must inherit the count and the notifyDataSetChanged() the
 * extension calls when the switch changes.
 */
private fun BytecodePatchContext.countThroughTheExtension(adapterType: String, kind: Int) {
    val adapter = mutableClassDefBy(adapterType)
    if (!AccessFlags.FINAL.isSet(adapter.accessFlags)) {
        throw PatchException("The feed adapter $adapterType isn't final, so a subclass could count past the hook")
    }
    if (adapter.methods.any { it.name == "getItemCount" && it.parameterTypes.isEmpty() }) {
        throw PatchException("The feed adapter $adapterType counts its own items")
    }
    val inherited = superclassChain(adapter.superclass ?: throw PatchException("$adapterType has no superclass"))
        .mapNotNull { classDefByOrNull(it) }.flatMap { it.methods }.toList()
    if (inherited.none { it.isCount() && !AccessFlags.ABSTRACT.isSet(it.accessFlags) }) {
        throw PatchException("The feed adapter $adapterType inherits no getItemCount()")
    }
    if (inherited.none { it.isNotify() && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
        throw PatchException("The feed adapter $adapterType inherits no public notifyDataSetChanged()")
    }

    ImmutableMethod(
        adapterType,
        "getItemCount",
        emptyList(),
        "I",
        AccessFlags.PUBLIC.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                invoke-super { p0 }, ${adapter.superclass}->getItemCount()I
                move-result v0
                const/4 v1, $kind
                invoke-static { p0, v1, v0 }, $STORIES_TRAY_COUNT
                move-result v0
                return v0
            """,
        )
        adapter.methods.add(this)
    }
}

private fun Method.isCount() =
    name == "getItemCount" && parameterTypes.isEmpty() && returnType == "I"

private fun Method.isNotify() =
    name == "notifyDataSetChanged" && parameterTypes.isEmpty() && returnType == "V"
