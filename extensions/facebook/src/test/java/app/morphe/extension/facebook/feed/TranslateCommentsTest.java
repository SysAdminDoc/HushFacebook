/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.feed.TranslateCommentsForTests.Body;
import app.morphe.extension.facebook.feed.TranslateCommentsForTests.Broken;
import app.morphe.extension.facebook.feed.TranslateCommentsForTests.Untouched;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Translate every comment (#522): with the switch on, each See translation handler a comment body
 * hands over is tapped through the body's own tap method once the main thread is free, and counted
 * when the tap went through. Off, paused, before the settings are ready, or switched off before the
 * tap runs, nothing is tapped. A body without the tap, or a tap that fails, is reported, never thrown.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TranslateCommentsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        Body.reset();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TRANSLATE_EVERY_COMMENT.resetToDefault();
        HookStatus.clear();
        Body.reset();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.AUTO_TRANSLATION + ":")) return line;
        }
        return null;
    }

    @Test
    public void offNoCommentIsTapped() {
        assertFalse("the switch starts off", Settings.TRANSLATE_EVERY_COMMENT.get());
        TranslateComments.link(new Object(), Body.class);
        idle();
        assertTrue(Body.tapped.isEmpty());
        assertEquals(FamilyNames.AUTO_TRANSLATION + ": invoked 1, 1 found, 0 missing", statusLine());
    }

    @Test
    public void onEachLinkIsTappedOnceTheMainThreadIsFree() {
        Settings.TRANSLATE_EVERY_COMMENT.save(true);
        Object first = new Object();
        Object second = new Object();
        TranslateComments.link(first, Body.class);
        assertTrue("the render isn't where the tap happens", Body.tapped.isEmpty());
        idle();
        assertEquals(1, Body.tapped.size());
        assertSame(first, Body.tapped.get(0));
        TranslateComments.link(second, Body.class);
        idle();
        assertEquals(2, Body.tapped.size());
        assertSame(second, Body.tapped.get(1));
        assertEquals(FamilyNames.AUTO_TRANSLATION + ": invoked 2, 1 found, 0 missing. Counted: "
                + TranslateComments.TRANSLATED + " 2", statusLine());
    }

    @Test
    public void aCommentAlreadyTappedOrTranslatedIsNotCounted() {
        Settings.TRANSLATE_EVERY_COMMENT.save(true);
        Body.answer = false;
        TranslateComments.link(new Object(), Body.class);
        idle();
        assertEquals("the body is still asked, it decides", 1, Body.tapped.size());
        assertEquals(FamilyNames.AUTO_TRANSLATION + ": invoked 1, 1 found, 0 missing", statusLine());
    }

    @Test
    public void turnedOffBeforeTheTapRunsNothingIsTapped() {
        Settings.TRANSLATE_EVERY_COMMENT.save(true);
        TranslateComments.link(new Object(), Body.class);
        Settings.TRANSLATE_EVERY_COMMENT.save(false);
        idle();
        assertTrue(Body.tapped.isEmpty());
    }

    @Test
    public void pausedOrBeforeTheSettingsAreReadyNothingIsTapped() {
        Settings.TRANSLATE_EVERY_COMMENT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        TranslateComments.link(new Object(), Body.class);
        idle();
        assertTrue("paused", Body.tapped.isEmpty());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            TranslateComments.link(new Object(), Body.class);
            idle();
        });
        assertTrue("before the settings are ready", Body.tapped.isEmpty());
    }

    @Test
    public void nothingToTapIsLeftAlone() {
        Settings.TRANSLATE_EVERY_COMMENT.save(true);
        TranslateComments.link(null, Body.class);
        TranslateComments.link(new Object(), null);
        idle();
        assertTrue(Body.tapped.isEmpty());
        assertEquals(FamilyNames.AUTO_TRANSLATION + ": invoked 2, 1 found, 0 missing", statusLine());
    }

    @Test
    public void aBodyWithoutTheTapIsReportedNotThrown() {
        Settings.TRANSLATE_EVERY_COMMENT.save(true);
        TranslateComments.link(new Object(), Untouched.class);
        idle();
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.startsWith(FamilyNames.AUTO_TRANSLATION + ": invoked 1, 1 found, 1 missing"));
        assertTrue(line, line.contains(Untouched.class.getName() + "#" + TranslateComments.TAP));
    }

    @Test
    public void aTapThatFailsIsReportedNotThrown() {
        Settings.TRANSLATE_EVERY_COMMENT.save(true);
        TranslateComments.link(new Object(), Broken.class);
        idle();
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.startsWith(FamilyNames.AUTO_TRANSLATION + ": invoked 1, 1 found, 1 missing"));
        assertTrue(line, line.contains(IllegalStateException.class.getName()));
    }
}
