/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The one question every analytics event factory asks (#221, #408): a profile visit is kept out
 * only when Don't save search history is in the build and on, a playback event only when Don't
 * send reel watch history is, and every other event is logged whatever is on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AnalyticsEventsTest {
    private static final String VISIT = "profile_engagement";
    private static final String PLAYBACK = "media_playback_compound";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void bothOn() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        Settings.DONT_SEND_REEL_WATCH_HISTORY.save(true);
    }

    @After
    public void restore() {
        AnalyticsEvents.searchHistoryForTests = null;
        AnalyticsEvents.reelWatchHistoryForTests = null;
        Settings.DONT_SAVE_SEARCH_HISTORY.resetToDefault();
        Settings.DONT_SEND_REEL_WATCH_HISTORY.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static void inBuild(boolean searchHistory, boolean reelWatchHistory) {
        AnalyticsEvents.searchHistoryForTests = searchHistory;
        AnalyticsEvents.reelWatchHistoryForTests = reelWatchHistory;
    }

    @Test
    public void bothPatchesInTheBuildKeepTheirOwnEventsOut() {
        inBuild(true, true);
        assertTrue(AnalyticsEvents.keepOut(VISIT));
        assertTrue(AnalyticsEvents.keepOut(PLAYBACK));
        assertTrue(AnalyticsEvents.keepOut("media_playback_compound_fast"));
        assertFalse(AnalyticsEvents.keepOut("merlin_unified_protocol_event"));
        assertFalse(AnalyticsEvents.keepOut(null));
    }

    /** A switch left on from an earlier build does nothing once its patch isn't in this one. */
    @Test
    public void aPatchNotInTheBuildKeepsNothingOut() {
        inBuild(false, true);
        assertFalse("a visit was kept out without Don't save search history", AnalyticsEvents.keepOut(VISIT));
        assertTrue(AnalyticsEvents.keepOut(PLAYBACK));

        inBuild(true, false);
        assertTrue(AnalyticsEvents.keepOut(VISIT));
        assertFalse("playback was kept out without Don't send reel watch history", AnalyticsEvents.keepOut(PLAYBACK));

        inBuild(false, false);
        assertFalse(AnalyticsEvents.keepOut(VISIT));
        assertFalse(AnalyticsEvents.keepOut(PLAYBACK));
    }

    /** In the build with its switch off, each event is logged. */
    @Test
    public void switchesOffEveryEventIsLogged() {
        inBuild(true, true);
        Settings.DONT_SAVE_SEARCH_HISTORY.save(false);
        Settings.DONT_SEND_REEL_WATCH_HISTORY.save(false);
        assertFalse(AnalyticsEvents.keepOut(VISIT));
        assertFalse(AnalyticsEvents.keepOut(PLAYBACK));
    }

    /** Unpatched, SettingsStatus says neither patch is in, so nothing is kept out. */
    @Test
    public void withoutThePatchesNothingIsKeptOut() {
        assertFalse(AnalyticsEvents.keepOut(VISIT));
        assertFalse(AnalyticsEvents.keepOut(PLAYBACK));
    }
}
