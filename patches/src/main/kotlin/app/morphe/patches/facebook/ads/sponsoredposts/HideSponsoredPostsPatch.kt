/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidesponsoredposts/HideSponsoredPostsPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: the guard moved into the shared feed hook, and
 * PROMOTION is dropped beside SPONSORED as https://github.com/SapitoSucio/FroggoMorphePatches
 * (GPL-3.0) does.
 */
package app.morphe.patches.facebook.ads.sponsoredposts

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.ads.sponsoredreels.holdAdPills
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.util.executeBytecode

internal const val SPONSORED_POSTS_PATCH = "Hide sponsored posts"

/** Gets a comment pill plugin's class name. Gives true when a feed ad's button stays off. */
internal const val HOLDS_FEED_AD_PILL = "$EXTENSION_PACKAGE/ads/FeedAdPills;->holdsAdPill(Ljava/lang/String;)Z"

@Suppress("unused")
val hideSponsoredPostsPatch = patch(
    name = "Hide sponsored posts",
    description = "Removes sponsored and promoted posts from the news feed, with no gap left behind, so you see " +
        "fewer ads. It also keeps the floating ad button off an ad's comments. On by default. Turn it off in " +
        "Hushfacebook settings > News feed.",
    default = true,
) {
    category("Ads")
    dependsOn(facebookExtensionPatch)
    dependsOn(settingsPatch)
    dependsOn(feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    // Rejecting at the collection boundary means nothing downstream sees the ad: no placeholder,
    // no impression. The edge already carries the server's story category, so this costs one
    // cached-enum lookup rather than a walk of the story tree for sponsored_data.
    executeBytecode {
        // An ad that still reaches the feed, or one opened from a link, shows its comments without
        // the floating ad button: the permalink page's and the comment flyout's. This asks first in
        // the same pill check Hide sponsored reels asks in, so either patch works alone.
        try {
            holdAdPills(SPONSORED_POSTS_PATCH, HOLDS_FEED_AD_PILL)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without holding the ad button on comments.")
        }

        enableStatus("sponsoredPosts")
    }
}
