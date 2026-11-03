/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.feed.AutoTranslationForTests.CommentTranslation;
import app.morphe.extension.facebook.feed.AutoTranslationForTests.Translatability;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Turn off auto-translation: with the switch on, a post marked AUTO_TRANSLATION reads as
 * SEE_TRANSLATION of the same enum, the reels footer hears that a caption can't be translated by
 * itself, and a comment of any translation type reads as ORIGINAL, each counted. Every other mark,
 * and everything while off or paused, is Facebook's own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AutoTranslationTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TURN_OFF_AUTO_TRANSLATION.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.AUTO_TRANSLATION + ":")) return line;
        }
        return null;
    }

    @Test
    public void onPostsAndCaptionsKeepTheirOwnLanguage() {
        assertFalse("the switch starts on", Settings.TURN_OFF_AUTO_TRANSLATION.get());
        Settings.TURN_OFF_AUTO_TRANSLATION.save(true);
        assertSame(Translatability.SEE_TRANSLATION, AutoTranslation.translationType(Translatability.AUTO_TRANSLATION));
        assertSame(Translatability.NO_TRANSLATION, AutoTranslation.translationType(Translatability.NO_TRANSLATION));
        assertSame(Translatability.SEE_TRANSLATION, AutoTranslation.translationType(Translatability.SEE_TRANSLATION));
        assertNull(AutoTranslation.translationType(null));
        assertFalse("the footer still asks for a translation", AutoTranslation.captionAutoTranslates(true));
        assertFalse(AutoTranslation.captionAutoTranslates(false));
        assertEquals(FamilyNames.AUTO_TRANSLATION + ": invoked 6, 2 found, 0 missing. Counted: "
                + AutoTranslation.POST_KEPT + " 1, " + AutoTranslation.CAPTION_KEPT + " 1", statusLine());
    }

    @Test
    public void onCommentsStartInTheirOwnWords() {
        Settings.TURN_OFF_AUTO_TRANSLATION.save(true);
        assertSame(CommentTranslation.ORIGINAL, AutoTranslation.commentTranslation(CommentTranslation.AUTO_TRANSLATE));
        assertSame(CommentTranslation.ORIGINAL, AutoTranslation.commentTranslation(CommentTranslation.MACHINE_TRANSLATION));
        assertSame("a type this build doesn't know starts translated too when Facebook sent a translation",
                CommentTranslation.ORIGINAL,
                AutoTranslation.commentTranslation(CommentTranslation.UNSET_OR_UNRECOGNIZED_ENUM_VALUE));
        assertSame(CommentTranslation.ORIGINAL, AutoTranslation.commentTranslation(CommentTranslation.ORIGINAL));
        assertNull(AutoTranslation.commentTranslation(null));
        assertEquals(FamilyNames.AUTO_TRANSLATION + ": invoked 5, 1 found, 0 missing. Counted: "
                + AutoTranslation.COMMENT_KEPT + " 3", statusLine());
    }

    @Test
    public void offOrPausedCommentsKeepFacebooksTranslation() {
        assertSame(CommentTranslation.AUTO_TRANSLATE, AutoTranslation.commentTranslation(CommentTranslation.AUTO_TRANSLATE));
        Settings.TURN_OFF_AUTO_TRANSLATION.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertSame("a Hushfacebook paused by " + reason + " kept a comment's own language",
                    CommentTranslation.MACHINE_TRANSLATION,
                    AutoTranslation.commentTranslation(CommentTranslation.MACHINE_TRANSLATION));
            PauseForTests.resume();
        }
        assertEquals(FamilyNames.AUTO_TRANSLATION + ": invoked 3, 1 found, 0 missing", statusLine());
    }

    @Test
    public void offOrPausedFacebooksTranslationStands() {
        assertSame(Translatability.AUTO_TRANSLATION, AutoTranslation.translationType(Translatability.AUTO_TRANSLATION));
        assertTrue(AutoTranslation.captionAutoTranslates(true));
        Settings.TURN_OFF_AUTO_TRANSLATION.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertSame("a Hushfacebook paused by " + reason + " kept a post's own language",
                    Translatability.AUTO_TRANSLATION, AutoTranslation.translationType(Translatability.AUTO_TRANSLATION));
            assertTrue("a Hushfacebook paused by " + reason + " kept a caption's own language",
                    AutoTranslation.captionAutoTranslates(true));
            PauseForTests.resume();
        }
        assertEquals(FamilyNames.AUTO_TRANSLATION + ": invoked 6, 2 found, 0 missing", statusLine());
    }
}
