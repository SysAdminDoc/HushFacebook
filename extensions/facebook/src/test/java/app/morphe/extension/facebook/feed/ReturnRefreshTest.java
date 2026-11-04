/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowPausedSystemClock;

import java.util.Collections;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReturnRefreshTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The patch is in Morphe Manager's default selection with its switch off; these tests turn it on. */
    @Before
    public void turnTheSwitchOn() {
        Settings.BLOCK_RETURN_REFRESH.save(true);
    }

    @After public void restore() {
        PauseForTests.resume();
        Settings.BLOCK_RETURN_REFRESH.resetToDefault();
        Settings.RETURN_REFRESH_NO_LIMIT.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
        ReturnRefresh.skipAt(Long.MAX_VALUE);
    }

    /**
     * A return within ten minutes keeps the feed. One past them lets Facebook refresh, for its own
     * checks only: the feed back from another screen afterwards is inside the app and keeps it.
     */
    @Test public void aReturnPastTenMinutesLetsFacebookRefreshForThatReturnOnly() {
        long back = 1_000 + 10 * 60 * 1000;
        ReturnRefresh.uiHidden(1_000);
        assertTrue(ReturnRefresh.skipAt(back));
        assertTrue(ReturnRefresh.skipAt(back));
        ReturnRefresh.uiHidden(1_000);
        assertFalse(ReturnRefresh.skipAt(back + 1));
        assertFalse("a later check of the same return", ReturnRefresh.skipAt(back + 2));
        assertTrue("the feed back from another screen",
                ReturnRefresh.skipAt(back + 2 + ReturnRefresh.SAME_RETURN_MS + 1));
    }

    /** #23: with No time limit on, a return after any absence keeps the feed, for that return only. */
    @Test public void noTimeLimitKeepsTheFeedAfterAnyAbsence() {
        long back = 1_000 + 24L * 60 * 60 * 1000;
        Settings.RETURN_REFRESH_NO_LIMIT.save(true);
        ReturnRefresh.uiHidden(1_000);
        assertTrue(ReturnRefresh.skipAt(back));
        assertTrue(ReturnRefresh.askAt(back + 1, ReturnRefresh.WARM_START));
        assertTrue("inside the app", ReturnRefresh.askAt(back + ReturnRefresh.SAME_RETURN_MS + 1, ReturnRefresh.WARM_START));
        Settings.BLOCK_RETURN_REFRESH.save(false);
        ReturnRefresh.uiHidden(1_000);
        assertFalse(ReturnRefresh.skipAt(1_000 + 11 * 60 * 1000));
        Settings.BLOCK_RETURN_REFRESH.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ReturnRefresh.uiHidden(1_000);
        assertFalse(ReturnRefresh.skipAt(1_000 + 11 * 60 * 1000));
    }

    /**
     * Facebook checks more than once as it comes back: the refresh controller's resume callback,
     * then the feed's warm-start check. The S22 kept its place after a minute away and lost it
     * after 6.5, 7 and 11 with No time limit on, while the resume callback ran each time: the
     * first check of a return was the only one held.
     */
    @Test public void everyCheckOfOneReturnKeepsTheFeed() {
        long back = 1_000 + 7 * 60 * 1000;
        ReturnRefresh.uiHidden(1_000);
        assertTrue(ReturnRefresh.skipAt(back));
        assertTrue("the second check of a 7-minute return refreshed", ReturnRefresh.skipAt(back + 40));
        assertTrue("the warm-start check of a 7-minute return refreshed",
                ReturnRefresh.askAt(back + ReturnRefresh.SAME_RETURN_MS, ReturnRefresh.WARM_START));
        assertTrue("the auto-scroll of a 7-minute return moved the feed",
                ReturnRefresh.askAt(back + 60, ReturnRefresh.AUTO_SCROLL));

        // Either check can come first. 11 minutes away is past the window, so it holds only with
        // No time limit, and then for both.
        back = 1_000 + 11 * 60 * 1000;
        ReturnRefresh.uiHidden(1_000);
        assertFalse(ReturnRefresh.askAt(back, ReturnRefresh.WARM_START));
        assertFalse(ReturnRefresh.skipAt(back + 40));
        Settings.RETURN_REFRESH_NO_LIMIT.save(true);
        ReturnRefresh.uiHidden(1_000);
        assertTrue(ReturnRefresh.askAt(back, ReturnRefresh.WARM_START));
        assertTrue(ReturnRefresh.skipAt(back + 40));

        // A check after the return's own, the feed back from another screen, is inside the app (#378).
        assertTrue(ReturnRefresh.askAt(back + ReturnRefresh.SAME_RETURN_MS + 1, ReturnRefresh.WARM_START));
        assertTrue(ReturnRefresh.skipAt(back + ReturnRefresh.SAME_RETURN_MS + 2));
    }

    /**
     * A tab switch back to Home, or the feed back from another screen, reaches the hot-start check,
     * the stale-post executor and the tab's AUTO_REFRESH. With the switch on each keeps the feed, and
     * switched off or paused each is Facebook's. Inside a return from the background the stale-post
     * and tab checks get the return's answer without using it up, and the hot-start check goes on to
     * the warm-start check that decides it.
     */
    @Test public void aTabSwitchBackToHomeKeepsTheFeedWithTheSwitchOn() {
        long now = 1_000_000;
        assertTrue(ReturnRefresh.askInAppAt(now, ReturnRefresh.HOT_START, false));
        assertTrue(ReturnRefresh.askInAppAt(now, ReturnRefresh.STALE_POST, true));
        assertTrue(ReturnRefresh.askInAppAt(now, ReturnRefresh.TAB_AUTO_REFRESH, true));

        // Seven minutes away: the pause worker and the tab follow the return, which its own check decides.
        ReturnRefresh.uiHidden(1_000);
        long back = 1_000 + 7 * 60 * 1000;
        assertTrue("the pause worker", ReturnRefresh.askInAppAt(back, ReturnRefresh.STALE_POST, true));
        assertFalse("the hot-start check went past the warm-start check",
                ReturnRefresh.askInAppAt(back, ReturnRefresh.HOT_START, false));
        assertTrue("the return was used up", ReturnRefresh.askAt(back + 1, ReturnRefresh.WARM_START));
        assertTrue(ReturnRefresh.askInAppAt(back + 2, ReturnRefresh.TAB_AUTO_REFRESH, true));

        // Eleven minutes away: the return lets Facebook refresh, and so does each check within it.
        ReturnRefresh.uiHidden(1_000);
        back = 1_000 + 11 * 60 * 1000;
        assertFalse(ReturnRefresh.askInAppAt(back, ReturnRefresh.STALE_POST, true));
        assertFalse(ReturnRefresh.askAt(back + 1, ReturnRefresh.WARM_START));
        assertFalse(ReturnRefresh.askInAppAt(back + 2, ReturnRefresh.TAB_AUTO_REFRESH, true));
        // Past the return, a tab switch back to Home is inside the app again.
        long later = back + ReturnRefresh.SAME_RETURN_MS + 3;
        assertTrue(ReturnRefresh.askInAppAt(later, ReturnRefresh.HOT_START, false));

        Settings.BLOCK_RETURN_REFRESH.save(false);
        assertFalse("switch off", ReturnRefresh.askInAppAt(later, ReturnRefresh.STALE_POST, true));
        assertFalse("switch off", ReturnRefresh.askInAppAt(later, ReturnRefresh.HOT_START, false));
        Settings.BLOCK_RETURN_REFRESH.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", ReturnRefresh.askInAppAt(later, ReturnRefresh.TAB_AUTO_REFRESH, true));
        assertFalse("paused", ReturnRefresh.askInAppAt(later, ReturnRefresh.HOT_START, false));
    }

    /**
     * The three in-app entries keep the feed and say so in the report, one count each. The hot-start
     * check keeps it through the warm-start check it asks next, so both of its calls are invoked.
     */
    @Test public void theInAppEntriesKeepTheFeedAndSaySo() {
        HookStatus.clear();
        try {
            ReturnRefresh.hotStart();
            assertTrue(ReturnRefresh.holdWarmStart());
            assertTrue(ReturnRefresh.holdStalePost());
            assertTrue(ReturnRefresh.holdTabAutoRefresh());
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(FamilyNames.RETURN_REFRESH + ": invoked 4"));
            assertTrue(report, report.contains("kept the feed at hot start 1, kept the feed from a stale-post refresh 1, "
                    + "kept the feed from the tab's auto refresh 1"));
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * On accounts with the friendly feed, onTabEntered reloads it with a HOT_LOAD once Home was left
     * long enough. With the switch on that keeps the feed and counts it, inside a return it gets the
     * return's answer without using the return up, and switched off or paused it's Facebook's.
     */
    @Test public void theHomeTabsHotLoadKeepsTheFeedWithTheSwitchOn() {
        HookStatus.clear();
        try {
            assertTrue(ReturnRefresh.holdTabEntryHotLoad());
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(FamilyNames.RETURN_REFRESH + ": invoked 1"));
            assertTrue(report, report.contains("kept the feed from the Home tab's hot load 1"));
        } finally {
            HookStatus.clear();
        }

        ReturnRefresh.uiHidden(1_000);
        long back = 1_000 + 7 * 60 * 1000;
        assertTrue("seven minutes away", ReturnRefresh.askInAppAt(back, ReturnRefresh.TAB_ENTRY_HOT_LOAD, true));
        assertTrue("the return was left for its own check", ReturnRefresh.askAt(back + 1, ReturnRefresh.WARM_START));
        ReturnRefresh.uiHidden(1_000);
        back = 1_000 + 11 * 60 * 1000;
        assertFalse("eleven minutes away", ReturnRefresh.askInAppAt(back, ReturnRefresh.TAB_ENTRY_HOT_LOAD, true));
        assertFalse(ReturnRefresh.askAt(back + 1, ReturnRefresh.WARM_START));
        long later = back + ReturnRefresh.SAME_RETURN_MS + 3;
        assertTrue("inside the app", ReturnRefresh.askInAppAt(later, ReturnRefresh.TAB_ENTRY_HOT_LOAD, true));

        Settings.BLOCK_RETURN_REFRESH.save(false);
        assertFalse("switch off", ReturnRefresh.holdTabEntryHotLoad());
        Settings.BLOCK_RETURN_REFRESH.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", ReturnRefresh.holdTabEntryHotLoad());
    }

    /**
     * The hot-start check holds nothing itself, so an empty feed, which never reaches the warm-start
     * check's question, still loads. The warm-start check it asks next gets the in-app answer once,
     * and only straight after it, and only with no return from the background pending.
     */
    @Test public void onlyTheWarmStartCheckTheHotStartAsksGetsTheInAppAnswer() {
        ReturnRefresh.hotStartAt(1_000);
        assertTrue(ReturnRefresh.askedByHotStartInApp(1_000 + ReturnRefresh.HOT_START_ASKS_MS));
        assertFalse("taken", ReturnRefresh.askedByHotStartInApp(1_001 + ReturnRefresh.HOT_START_ASKS_MS));
        assertFalse("no hot start", ReturnRefresh.askedByHotStartInApp(5_000));

        ReturnRefresh.hotStartAt(10_000);
        assertFalse("too late", ReturnRefresh.askedByHotStartInApp(10_001 + ReturnRefresh.HOT_START_ASKS_MS));
        ReturnRefresh.hotStartAt(20_000);
        assertFalse("clock moved back", ReturnRefresh.askedByHotStartInApp(19_999));

        ReturnRefresh.uiHidden(30_000);
        ReturnRefresh.hotStartAt(40_000);
        assertFalse("a return decides itself", ReturnRefresh.askedByHotStartInApp(40_001));
    }

    /**
     * #378: the warm-start check with no return pending is the feed back from another screen, and
     * keeps the feed whether the hot-start check asked it or the feed's onResume did. Each counts
     * under its own label.
     */
    @Test public void aWarmStartCheckInsideTheAppKeepsTheFeed() {
        HookStatus.clear();
        try {
            assertTrue("onResume", ReturnRefresh.holdWarmStart());
            ReturnRefresh.hotStart();
            assertTrue("the hot start", ReturnRefresh.holdWarmStart());
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains("kept the feed at warm start 1, kept the feed at hot start 1"));
            Settings.BLOCK_RETURN_REFRESH.save(false);
            assertFalse("switch off", ReturnRefresh.holdWarmStart());
            ReturnRefresh.hotStart();
            assertFalse("switch off", ReturnRefresh.holdWarmStart());
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * The warm-start check reaches the extension through its own entry, counted in the report
     * beside the resume callback's, and keeps the feed in the same return.
     */
    @Test public void theWarmStartEntryKeepsTheFeedAndSaysSo() {
        HookStatus.clear();
        try {
            ReturnRefresh.uiHidden();
            assertTrue(ReturnRefresh.skip());
            assertTrue(ReturnRefresh.holdWarmStart());
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(FamilyNames.RETURN_REFRESH + ": invoked 2"));
            assertTrue(report, report.contains("kept the feed on resume 1, kept the feed at warm start 1"));
            // With nothing hidden since, a later warm-start check is inside the app and keeps it too.
            SystemClock.sleep(ReturnRefresh.SAME_RETURN_MS + 1);
            assertTrue(ReturnRefresh.holdWarmStart());
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * With Debug logging on, each check names itself, how long Facebook was away, and what it
     * decided, and so does Facebook's reset to feed, for a phone check to tell the routes apart.
     */
    @Test public void eachDecisionIsLoggedWithDebugOn() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        long back = 1_000 + 7 * 60 * 1000;
        ReturnRefresh.uiHidden(1_000);
        ReturnRefresh.skipAt(back);
        ReturnRefresh.askAt(back + 40, ReturnRefresh.WARM_START);
        ReturnRefresh.askAt(back + ReturnRefresh.SAME_RETURN_MS + 1, ReturnRefresh.WARM_START);
        ReturnRefresh.uiHidden(1_000);
        ReturnRefresh.askAt(1_000 + 11 * 60 * 1000, ReturnRefresh.WARM_START);
        ReturnRefresh.askAt(1_000 + 11 * 60 * 1000 + 30, ReturnRefresh.AUTO_SCROLL);
        ReturnRefresh.keepFeedWhileAway();
        ReturnRefresh.resetToFeed("reset", "newsfeed");
        String log = LogBufferManager.buildExportText();
        for (String line : new String[]{
                "Return refresh: Facebook's screens were hidden",
                "Return refresh: feed resume after 420 s away: keeping the feed",
                "Return refresh: warm start in the same return: keeping the feed",
                "Return refresh: warm start inside the app: keeping the feed",
                "Return refresh: warm start after 660 s away: Facebook goes ahead, away over ten minutes",
                "Return refresh: foreground auto-scroll in the same return: Facebook goes ahead, "
                        + "as the first check of this return decided",
                "Return refresh: feed teardown while away: skipped, keeping the feed",
                "Return refresh: Facebook's reset to feed: reset, to newsfeed"}) {
            assertTrue(line + " missing from:\n" + log, log.contains(line));
        }
    }

    /**
     * Facebook tears the feed down while it's away once it has been gone as long as the warm-start
     * threshold, and a torn-down feed loads new posts on any return. With the switch on the
     * teardown is skipped, however long the absence turns out to be, and the return is left to the
     * checks that come with it.
     */
    @Test public void theTeardownWhileAwayIsSkippedWithTheSwitchOn() {
        ReturnRefresh.uiHidden(1_000);
        assertTrue(ReturnRefresh.keepFeedWhileAway());
        assertTrue("the teardown used up the return", ReturnRefresh.skipAt(1_000 + 7 * 60 * 1000));
        ReturnRefresh.uiHidden(1_000);
        assertTrue(ReturnRefresh.keepFeedWhileAway());
        assertFalse("a return past ten minutes kept the feed", ReturnRefresh.skipAt(1_000 + 11 * 60 * 1000));
        Settings.BLOCK_RETURN_REFRESH.save(false);
        assertFalse(ReturnRefresh.keepFeedWhileAway());
        Settings.BLOCK_RETURN_REFRESH.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(ReturnRefresh.keepFeedWhileAway());
    }

    /** Where Facebook writes down its reset to feed, the extension only logs: the return stays pending. */
    @Test public void resetToFeedOnlyLogs() {
        ReturnRefresh.uiHidden(1_000);
        ReturnRefresh.resetToFeed("reset", "newsfeed");
        ReturnRefresh.resetToFeed(null, null);
        assertTrue(ReturnRefresh.skipAt(1_001));
    }

    @Test public void disabledOrPausedReturnsToFacebookRefresh() {
        Settings.BLOCK_RETURN_REFRESH.save(false);
        ReturnRefresh.uiHidden(1_000);
        assertFalse(ReturnRefresh.skipAt(1_001));
        Settings.BLOCK_RETURN_REFRESH.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ReturnRefresh.uiHidden(2_000);
        assertFalse(ReturnRefresh.skipAt(2_001));
    }

    @Test public void clocksMovingBackCannotHoldTheFeed() {
        ReturnRefresh.uiHidden(2_000);
        assertFalse(ReturnRefresh.skipAt(1_999));
    }

    /**
     * Every other hook counts its calls in the report. This one didn't, so a report from someone
     * whose feed still refreshed couldn't say whether the callback ever ran.
     */
    @Test public void eachResumeCountsInTheReport() {
        HookStatus.clear();
        try {
            ReturnRefresh.skip();
            ReturnRefresh.skip();
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(FamilyNames.RETURN_REFRESH + ": invoked 2"));
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * A check that lets Facebook refresh counts too, under its reason's own label, so a report
     * from someone whose feed keeps resetting isn't just "invoked N, 0 found, 0 missing" with
     * nothing else to read.
     */
    @Test public void everyDecisionCountsBothWaysInTheReport() {
        HookStatus.clear();
        try {
            ReturnRefresh.uiHidden();
            assertTrue(ReturnRefresh.keepFeedWhileAway());
            assertTrue(ReturnRefresh.skip());
            assertTrue(ReturnRefresh.holdWarmStart());
            assertTrue(ReturnRefresh.holdAutoScroll());
            String kept = String.join("\n", HookStatus.report());
            assertTrue(kept, kept.contains(FamilyNames.RETURN_REFRESH + ": invoked 4"));
            assertTrue(kept, kept.contains("kept the feed loaded while away 1, kept the feed on resume 1, "
                    + "kept the feed at warm start 1, kept the feed from the foreground auto-scroll 1"));

            HookStatus.clear();
            Settings.BLOCK_RETURN_REFRESH.save(false);
            assertFalse(ReturnRefresh.keepFeedWhileAway());
            ReturnRefresh.uiHidden();
            assertFalse(ReturnRefresh.skip());
            assertFalse(ReturnRefresh.holdWarmStart());
            assertFalse(ReturnRefresh.holdAutoScroll());
            String refused = String.join("\n", HookStatus.report());
            assertTrue(refused, refused.contains(FamilyNames.RETURN_REFRESH + ": invoked 4"));
            assertTrue(refused, refused.contains("let Facebook refresh: switch off 4"));
        } finally {
            Settings.BLOCK_RETURN_REFRESH.resetToDefault();
            HookStatus.clear();
        }
    }

    /**
     * #378: Back from a post's page or its comments, on accounts where those are another screen,
     * runs the feed's resume callback, its warm-start check and its auto-scroll with nothing hidden.
     * They went to Facebook, which reloaded the feed with Keep feed position on return on. Now each
     * keeps the feed while the switch is on and counts as kept, never under a refusal label (#22's
     * "let Facebook refresh 15" from a switch that never refused anything). Switched off, each is
     * Facebook's and says why.
     */
    @Test public void theFeedBackFromAnotherScreenKeepsItsPlace() {
        HookStatus.clear();
        try {
            for (int i = 0; i < 5; i++) {
                assertTrue(ReturnRefresh.skip());
                assertTrue(ReturnRefresh.holdWarmStart());
                assertTrue(ReturnRefresh.holdAutoScroll());
            }
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(FamilyNames.RETURN_REFRESH + ": invoked 15"));
            assertTrue(report, report.contains("kept the feed on resume 5, kept the feed at warm start 5, "
                    + "kept the feed from the foreground auto-scroll 5"));
            assertFalse(report, report.contains("let Facebook refresh"));

            HookStatus.clear();
            Settings.BLOCK_RETURN_REFRESH.save(false);
            assertFalse(ReturnRefresh.skip());
            assertFalse(ReturnRefresh.holdWarmStart());
            assertFalse(ReturnRefresh.holdAutoScroll());
            String off = String.join("\n", HookStatus.report());
            assertTrue(off, off.contains("let Facebook refresh: switch off 3"));
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * #23-style reports need to say why a return refreshed, not just that it did. Each fixed
     * reason from refreshBecause()/offBecause() counts under its own label, so a real refusal
     * during the same return keeps reporting the reason that return was decided on.
     */
    @Test public void eachRefusalReasonCountsUnderItsOwnLabel() {
        HookStatus.clear();
        try {
            // A hidden time ahead of the real clock: "the clock went back".
            ReturnRefresh.uiHidden(Long.MAX_VALUE / 2);
            assertFalse(ReturnRefresh.skip());

            // The switch off, right away: "switch off".
            Settings.BLOCK_RETURN_REFRESH.save(false);
            ReturnRefresh.uiHidden();
            assertFalse(ReturnRefresh.skip());

            // Paused instead of switched off: "Hushfacebook paused".
            Settings.BLOCK_RETURN_REFRESH.save(true);
            PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
            ReturnRefresh.uiHidden();
            assertFalse(ReturnRefresh.skip());

            // Away past the ten-minute limit: "away over ten minutes", and a later check of the
            // same return keeps that same reason rather than the first check's alone.
            PauseForTests.resume();
            ReturnRefresh.uiHidden();
            SystemClock.sleep(11 * 60 * 1000L);
            assertFalse(ReturnRefresh.skip());
            assertFalse("a later check of the same return keeps the same reason", ReturnRefresh.holdWarmStart());

            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(FamilyNames.RETURN_REFRESH + ": invoked 5"));
            for (String label : new String[]{
                    "let Facebook refresh: the clock went back 1",
                    "let Facebook refresh: switch off 1",
                    "let Facebook refresh: Hushfacebook paused 1",
                    "let Facebook refresh: away over ten minutes 2"}) {
                assertTrue(report + "\nmissing: " + label, report.contains(label));
            }
        } finally {
            PauseForTests.resume();
            Settings.BLOCK_RETURN_REFRESH.resetToDefault();
            HookStatus.clear();
        }
    }

    /**
     * A failure inside the check lets Facebook refresh, as it would unpatched, and the report
     * names the hook that threw. The clock is what the check asks first once it has counted the
     * call, so a clock that fails once stands in for anything that can throw there.
     */
    @Test @Config(shadows = FailingClock.class)
    public void aFailureLetsFacebookRefreshAndTheReportSaysSo() {
        HookStatus.clear();
        try {
            // Hidden just now with the switch on: the same return without the failure keeps its place.
            ReturnRefresh.uiHidden();
            assertTrue(ReturnRefresh.skip());

            ReturnRefresh.uiHidden();
            FailingClock.failNext = true;
            assertFalse(ReturnRefresh.skip());
            assertFalse("the check never asked the clock", FailingClock.failNext);
            assertEquals(Collections.singletonList("a working 'feed resume' hook (it threw "
                            + IllegalStateException.class.getName() + ")"),
                    HookStatus.missing(FamilyNames.RETURN_REFRESH));
        } finally {
            FailingClock.failNext = false;
            HookStatus.clear();
        }
    }

    /** Robolectric's clock, except that the next elapsedRealtime() can be made to throw, once. */
    @Implements(SystemClock.class)
    public static class FailingClock extends ShadowPausedSystemClock {
        static volatile boolean failNext;

        @Implementation
        protected static long elapsedRealtime() {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("the clock failed");
            }
            return ShadowPausedSystemClock.elapsedRealtime();
        }
    }
}
