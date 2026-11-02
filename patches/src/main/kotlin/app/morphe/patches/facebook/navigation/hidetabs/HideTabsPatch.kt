/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.hidetabs

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.navigation.tabbar.tabBarFilterPatch
import app.morphe.patches.facebook.navigation.tabbar.tabLinksPatch
import app.morphe.patches.facebook.navigation.tabbar.tabOrderPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/** The classes Facebook keeps the names of for each tab Hide tabs can take off, as the extension's HiddenTabs knows them. */
internal val HIDEABLE_TABS = mapOf(
    "Feeds" to listOf("Lcom/facebook/feed/feedstab/tab/FeedsTab;", "Lcom/facebook/feed/filters/tab/MostRecentFeedTab;"),
    "Friends" to listOf("Lcom/facebook/friending/tab/FriendRequestsTab;"),
    "Marketplace" to listOf("Lcom/facebook/marketplace/tab/MarketplaceTab;"),
    "Groups" to listOf("Lcom/facebook/groups/targetedtab/groupstabtag/GroupsTargetedTab;"),
    "Gaming" to listOf("Lcom/facebook/games/tab/GamesTab;", "Lcom/facebook/games/tab/GamesTabWithSNESControllerIcon;"),
    "Events" to listOf("Lcom/facebook/events/targetedtab/EventsTab;"),
    "Dating" to listOf("Lcom/facebook/gemstone/tab/GemstoneTab;"),
    "Professional dashboard" to listOf(
        "Lcom/facebook/professionaldashboard/tab/ProfessionalDashboardTab;",
        "Lcom/facebook/professionaldashboard/tab/ProfessionalDashboardTabWithoutComponentHelper;",
    ),
    "Saved" to listOf("Lcom/facebook/saved2/tab/SavedTab;"),
    "Ad Center" to listOf("Lcom/facebook/adinterfaces/adcenter/AdCenterTab;"),
    "Create" to listOf("Lcom/facebook/creator/tab/CreatorToolComposerTab;"),
    "Explore" to listOf("Lcom/facebook/notifications/discoverhub/tab/DiscoverHubTab;"),
    "Jobs" to listOf("Lcom/facebook/jobsv2/tab/JobsV2Tab;"),
)

/**
 * Takes the tabs you pick off Facebook's tab bar. The tab bar filter (TabBarAnchors.kt) asks the
 * extension about each tab as Facebook builds the bar, right after Facebook's own hidden-tab set
 * answers, and the extension's HiddenTabs drops a tab whose switch is on, known by the classes
 * Facebook keeps for it. A tab Facebook's own tab bar settings hide stays theirs, and Open on a
 * chosen tab sends a start meant for a hidden tab to Home. Each page stays in the Menu: the tab
 * links patch (TabLinkAnchors.kt) has Facebook open a hidden tab's page on its own screen where it
 * would otherwise switch to the missing tab. The tab order patch (TabOrderPatch.kt) puts the tabs
 * left in the order picked in the Tab order row (#341).
 *
 * In the default selection with every switch off, so it changes nothing until a tab is picked.
 */
@Suppress("unused")
val hideTabsPatch = bytecodePatch(
    name = "Hide tabs",
    description = "Takes the tabs you pick off the tab bar, such as Friends, Marketplace, Groups, Gaming or " +
        "Events, so the bar only has what you use, and puts the ones left in the order you want. Each page stays " +
        "in the Menu. Starts off. Pick the tabs and their order in Hushfacebook settings > Tabs and navigation, " +
        "then restart Facebook.",
    default = true,
) {
    category("Navigation")
    dependsOn(settingsPatch, tabBarFilterPatch, tabLinksPatch, tabOrderPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        enableStatus("hiddenTabs")
    }
}
