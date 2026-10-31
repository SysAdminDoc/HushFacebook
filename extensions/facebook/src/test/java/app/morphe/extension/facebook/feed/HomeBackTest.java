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

import android.os.Looper;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
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

    /** The toast fits two lines of about 28 characters in every language Hushfacebook carries. */
    @Test public void theToastFitsTwoShortLinesInEveryLanguage() {
        assertTrue(lines(TOAST) <= 2);
        for (String language : new String[]{"de", "es", "in", "pt-rbr", "tr", "vi", "zh-rcn", "zh-rtw"}) {
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
