/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.reelstab

import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.navigation.tabbar.tabBarFilterPatch
import app.morphe.patches.facebook.navigation.tabbar.tabLinksPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode

/**
 * Takes the Reels tab, which some accounts call Video, off Facebook's tab bar. The tab bar filter
 * (TabBarAnchors.kt) asks the extension about each tab as Facebook builds the bar, right after
 * Facebook's own hidden-tab set answers, and the extension's ReelsTab drops the one class Facebook
 * keeps for that tab, WatchTab, while the switch is on. A tab Facebook's own tab bar settings hide
 * stays theirs. Nothing else of Reels is touched, so reel links, reels in the feed and the Reels
 * viewer still open, and Open on a chosen tab sends a start meant for the hidden tab to Home. The
 * tab links patch (TabLinkAnchors.kt) has Facebook open Reels on its own screen where a link or the
 * Menu's Reels shortcut would otherwise switch to the missing tab.
 * Facebook's Reels shortcut on its launcher icon goes too: the settings patch already sends each of
 * Facebook's ShortcutManager calls through SettingsEntry, which asks ReelsTab to leave that one out.
 *
 * In the default selection with its switch off, like Hide Reels in the feed, so the tab stays
 * until it's turned on.
 */
@Suppress("unused")
val hideReelsTabPatch = patch(
    name = "Hide the Reels tab",
    description = "Takes the Reels tab, called Video on some accounts, off the tab bar, so Reels isn't one tap " +
        "away. Reels in your feed and reel links still open. Starts off. Turn it on in Hushfacebook settings > " +
        "Reels and Watch, then restart Facebook.",
) {
    category("Navigation")
    dependsOn(settingsPatch, tabBarFilterPatch, tabLinksPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        enableStatus("reelsTab")
    }
}
