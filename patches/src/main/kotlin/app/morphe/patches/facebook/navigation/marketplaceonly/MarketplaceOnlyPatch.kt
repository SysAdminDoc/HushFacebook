/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.marketplaceonly

import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.menu.hushfacebookInTheMenuPatch
import app.morphe.patches.facebook.notifications.blockPromotionalNotificationsPatch
import app.morphe.patches.facebook.navigation.starttab.openOnChosenTabPatch
import app.morphe.patches.facebook.navigation.tabbar.tabBarFilterPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode

internal const val PATCH = "Marketplace only"

/**
 * Leaves Marketplace (or Events, with the Events only switch), Notifications and the profile or Menu tab in
 * Facebook's tab bar, and opens Facebook on it. The tab bar filter (TabBarAnchors.kt) asks the extension about each tab
 * as Facebook builds the bar, and the extension's MarketplaceOnly says which tabs go and when it
 * leaves the bar alone.
 *
 * It brings Open on a chosen tab with it: the start from the launcher icon goes to Marketplace
 * through that patch's route, which also gets Facebook's own start-up to use the tab.
 *
 * Included in the default selection, with its runtime switch off until the person opts in.
 */
@Suppress("unused")
val marketplaceOnlyPatch = patch(
    name = "Marketplace only",
    description = "Turns Facebook into a Marketplace app for buying and selling without the feed. The tab bar " +
        "keeps only Marketplace, Notifications and your profile or Menu, and Facebook opens on Marketplace. " +
        "The Events only switch does the same for Events once you pin Events in Facebook's Customize the bar. " +
        "Both start off. Turn one on in Hushfacebook settings > Opening Facebook, then restart Facebook.",
    default = true,
) {
    category("Navigation")
    dependsOn(settingsPatch, openOnChosenTabPatch, hushfacebookInTheMenuPatch,
        blockPromotionalNotificationsPatch, marketplaceFeedPrefetchPatch, tabBarFilterPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        enableStatus("marketplaceOnly")
    }
}
