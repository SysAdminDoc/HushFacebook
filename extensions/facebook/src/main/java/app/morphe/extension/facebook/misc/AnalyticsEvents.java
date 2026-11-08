/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import app.morphe.extension.facebook.reels.ReelWatchHistory;
import app.morphe.extension.facebook.search.SearchHistory;
import app.morphe.extension.facebook.settings.SettingsStatus;

/**
 * The one question asked first thing where Facebook makes an analytics event, handed the event's
 * name. Two patches keep events out there and share the one hook, put in once whichever of them
 * is in the build: Don't save search history keeps profile visits out of your search history
 * (#221), and Don't send reel watch history keeps video playback out of "Videos you've watched"
 * (#408). Each is asked only when its patch is in the build, and each checks the name before
 * anything else, since every event the app logs comes through here.
 */
public final class AnalyticsEvents {
    /** Whether Don't save search history is in this build, when a test says so instead of {@link SettingsStatus}. */
    static volatile Boolean searchHistoryForTests;

    /** Whether Don't send reel watch history is in this build, when a test says so instead of {@link SettingsStatus}. */
    static volatile Boolean reelWatchHistoryForTests;

    private AnalyticsEvents() {
    }

    /**
     * Injection point, first in every analytics event factory. True sends back the factory's own
     * "not logged" event, which every caller already handles. Never throws: both features answer
     * false for a failure of their own.
     */
    public static boolean keepOut(String event) {
        if (event == null) return false;
        return (searchHistory() && SearchHistory.keepVisitOut(event))
                || (reelWatchHistory() && ReelWatchHistory.holdPlaybackEvent(event));
    }

    private static boolean searchHistory() {
        Boolean forced = searchHistoryForTests;
        return forced != null ? forced : SettingsStatus.searchHistory();
    }

    private static boolean reelWatchHistory() {
        Boolean forced = reelWatchHistoryForTests;
        return forced != null ? forced : SettingsStatus.reelWatchHistory();
    }
}
