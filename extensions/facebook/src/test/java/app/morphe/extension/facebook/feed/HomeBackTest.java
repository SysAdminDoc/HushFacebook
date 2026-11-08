/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10nTablesForTests;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/** Back leaves Home where it is (#247): what the hook in NewsFeedFragment's onBackPressed answers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HomeBackTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String TOAST = "Press Back again to go to the top";

    @Before public void turnTheSwitchOn() {
        Settings.BACK_KEEPS_HOME.save(true);
        HomeBack.forget();
        ShadowToast.reset();
    }

    @After public void restore() {
        PauseForTests.resume();
        Settings.BACK_KEEPS_HOME.resetToDefault();
        Settings.BLOCK_RETURN_REFRESH.resetToDefault();
        Settings.HIDE_BACK_REMINDER.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
        HomeBack.forget();
    }

    /** The first Back keeps Home, a second within two seconds is Facebook's, and the one after keeps again. */
    @Test public void onlyASecondBackWithinTwoSecondsGoesToTheTop() {
        assertTrue("the first Back", HomeBack.decideAt(10_000));
        assertFalse("a second Back a second later", HomeBack.decideAt(11_000));
        assertTrue("a third Back right after the second", HomeBack.decideAt(11_100));
        assertFalse("a Back right at the end of the two seconds", HomeBack.decideAt(11_100 + HomeBack.SECOND_BACK_MS));
    }

    @Test public void aBackLaterThanTwoSecondsKeepsHomeAgain() {
        assertTrue(HomeBack.decideAt(10_000));
        assertTrue("a Back after the two seconds", HomeBack.decideAt(10_000 + HomeBack.SECOND_BACK_MS + 1));
        assertFalse("its own second Back", HomeBack.decideAt(10_000 + HomeBack.SECOND_BACK_MS + 500));
    }

    /** A clock that went backwards doesn't count as a second Back. */
    @Test public void aBackBeforeTheLastOneKeepsHome() {
        assertTrue(HomeBack.decideAt(10_000));
        assertTrue(HomeBack.decideAt(9_000));
    }

    /** The hook keeps the feed with a toast, then lets a quick second Back through without one. */
    @Test public void theHookKeepsHomeAndSaysHowToGetToTheTop() {
        assertTrue("the first Back left Facebook's scroll to the top in", HomeBack.holdScrollToTop());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(TOAST, ShadowToast.getTextOfLatestToast());

        ShadowToast.reset();
        SystemClock.sleep(500);
        assertFalse("the second Back was kept too", HomeBack.holdScrollToTop());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull("the second Back showed a toast", ShadowToast.getLatestToast());

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.RETURN_REFRESH + ": invoked 2"));
        assertTrue(report, report.contains("kept Home where it was on Back 1"));
        assertTrue(report, report.contains("let Facebook go to the top on a second Back 1"));
    }

    /** With the reminder hidden, Back behaves the same and says nothing (#334). */
    @Test public void withTheReminderHiddenTheFirstBackStillKeepsHomeWithoutAToast() {
        Settings.HIDE_BACK_REMINDER.save(true);
        assertTrue("the first Back left Facebook's scroll to the top in", HomeBack.holdScrollToTop());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull("the first Back showed a toast", ShadowToast.getLatestToast());

        SystemClock.sleep(500);
        assertFalse("the second Back was kept too", HomeBack.holdScrollToTop());
    }

    @Test public void switchedOffEveryBackIsFacebooks() {
        Settings.BACK_KEEPS_HOME.save(false);
        assertFalse(HomeBack.holdScrollToTop());
        assertFalse(HomeBack.holdScrollToTop());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowToast.getLatestToast());
    }

    @Test public void pausedEveryBackIsFacebooks() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(HomeBack.holdScrollToTop());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowToast.getLatestToast());
        PauseForTests.resume();
        assertTrue("the first Back once running again", HomeBack.holdScrollToTop());
    }

    /** With Debug logging on, each Back says what it did, with nothing that names a post or a person. */
    @Test public void eachBackIsLoggedWithDebugOn() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        HomeBack.holdScrollToTop();
        SystemClock.sleep(500);
        HomeBack.holdScrollToTop();
        String log = LogBufferManager.buildExportText();
        assertTrue(log, log.contains("Home back: Back on a scrolled Home: kept the feed where it is"));
        assertTrue(log, log.contains("Home back: Back on a scrolled Home: second Back, Facebook goes to the top"));
    }

    /** A Back within a second of a screen over the feed closing, or while another window has the focus, closed it (#378). */
    @Test public void aBackRightAfterAScreenOverTheFeedClosedBelongsToClosingIt() {
        assertFalse("nothing has closed yet", HomeBack.closedOverFeedAt(10_000, true));
        HomeBack.cameBackAt(10_000);
        assertTrue("right as it closed", HomeBack.closedOverFeedAt(10_000, true));
        assertTrue("at the end of the second", HomeBack.closedOverFeedAt(10_000 + HomeBack.CLOSED_OVER_FEED_MS, true));
        assertFalse("past the second", HomeBack.closedOverFeedAt(10_001 + HomeBack.CLOSED_OVER_FEED_MS, true));
        assertFalse("a clock that went back", HomeBack.closedOverFeedAt(9_000, true));
        assertTrue("another window still has the focus", HomeBack.closedOverFeedAt(60_000, false));
    }

    /**
     * #378: with Keep feed position on return on, the Back that closes comments over the feed keeps
     * the feed where it is without a toast, even with Back leaves Home off, and a later Back is Facebook's.
     */
    @Test public void keepFeedPositionHoldsTheBackThatClosedCommentsOverTheFeed() {
        Settings.BACK_KEEPS_HOME.save(false);
        Settings.BLOCK_RETURN_REFRESH.save(true);
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ShadowToast.reset();
        HomeBack.cameBack();
        SystemClock.sleep(300);
        assertTrue("the Back that closed the comments scrolled the feed", HomeBack.holdScrollToTop());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull("it showed a toast", ShadowToast.getLatestToast());

        SystemClock.sleep(HomeBack.CLOSED_OVER_FEED_MS + 500);
        assertFalse("a Back on the feed itself, later, was held too", HomeBack.holdScrollToTop());

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("kept the feed on a Back that closed a screen over it 1"));
        String log = LogBufferManager.buildExportText();
        assertTrue(log, log.contains("Home back: Back as a screen over the feed closed: kept the feed where it is"));
    }

    /** The feed's screen not having the focus, as when a window over it handed the Back on, holds it too; getting it back starts the second. */
    @Test public void aBackWhileAnotherWindowHasTheFocusIsHeld() {
        Settings.BACK_KEEPS_HOME.save(false);
        Settings.BLOCK_RETURN_REFRESH.save(true);
        HomeBack.watch(RuntimeEnvironment.getApplication());
        ActivityController<Activity> screen = Robolectric.buildActivity(Activity.class).setup();
        screen.windowFocusChanged(true);
        SystemClock.sleep(HomeBack.CLOSED_OVER_FEED_MS + 500);
        assertFalse("a Back on the feed with its screen focused was held", HomeBack.holdScrollToTop());

        screen.windowFocusChanged(false);
        SystemClock.sleep(HomeBack.CLOSED_OVER_FEED_MS + 500);
        assertTrue("a Back handed on by a window over the feed scrolled it", HomeBack.holdScrollToTop());

        screen.windowFocusChanged(true);
        SystemClock.sleep(300);
        assertTrue("the Back right as the focus came back scrolled the feed", HomeBack.holdScrollToTop());
        SystemClock.sleep(HomeBack.CLOSED_OVER_FEED_MS + 500);
        assertFalse(HomeBack.holdScrollToTop());
    }

    /** Switched off, or paused, a Back that closed a screen over the feed is Facebook's as before. */
    @Test public void withKeepFeedPositionOffOrPausedTheClosingBackIsFacebooks() {
        Settings.BACK_KEEPS_HOME.save(false);
        HomeBack.cameBack();
        assertFalse("switched off", HomeBack.holdScrollToTop());
        Settings.BLOCK_RETURN_REFRESH.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", HomeBack.holdScrollToTop());
    }

    /** With both switches on, the closing Back is held quietly and the next Back on the feed still gets the toast first. */
    @Test public void theClosingBackLeavesBackLeavesHomesCountAlone() {
        Settings.BLOCK_RETURN_REFRESH.save(true);
        HomeBack.cameBack();
        assertTrue(HomeBack.holdScrollToTop());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull("the closing Back showed a toast", ShadowToast.getLatestToast());

        SystemClock.sleep(HomeBack.CLOSED_OVER_FEED_MS + 500);
        assertTrue("the first Back on the feed", HomeBack.holdScrollToTop());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(TOAST, ShadowToast.getTextOfLatestToast());
        SystemClock.sleep(500);
        assertFalse("its second Back", HomeBack.holdScrollToTop());
    }

    /** The feed coming back from another screen inside the app counts as that screen closing. */
    @Test public void theFeedsResumeAndHotStartCountAsAScreenClosing() {
        assertFalse(HomeBack.closedOverFeedAt(SystemClock.elapsedRealtime(), true));
        ReturnRefresh.hotStart();
        // Takes the hot start back, so no warm-start check in a later test counts as the one it asked.
        ReturnRefresh.askedByHotStartInApp(Long.MAX_VALUE);
        assertTrue("hot start", HomeBack.closedOverFeedAt(SystemClock.elapsedRealtime(), true));
        SystemClock.sleep(HomeBack.CLOSED_OVER_FEED_MS + 500);
        ReturnRefresh.skip();
        assertTrue("feed resume", HomeBack.closedOverFeedAt(SystemClock.elapsedRealtime(), true));
    }

    /** The toast fits two lines of about 28 characters in every language Hushfacebook carries. */
    @Test public void theToastFitsTwoShortLinesInEveryLanguage() {
        assertTrue(lines(TOAST) <= 2);
        for (String language : new String[]{"de", "es", "in", "pt-rbr", "ru", "tr", "vi", "zh-rcn", "zh-rtw"}) {
            String translated = L10nTablesForTests.of(language).get(TOAST);
            assertNotNull(language + " has no toast", translated);
            assertTrue(language + ": " + translated, lines(translated) <= 2);
        }
    }

    /** How many lines [text] takes wrapped at word breaks to 28 characters; text with no spaces wraps anywhere. */
    private static int lines(String text) {
        if (text.indexOf(' ') < 0) return (text.codePointCount(0, text.length()) + 27) / 28;
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > 28) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        lines.add(line.toString());
        return lines.size();
    }
}
