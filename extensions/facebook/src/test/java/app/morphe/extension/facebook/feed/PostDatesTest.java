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

import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.StyleSpan;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Keep post dates: with the switch on, a post header's yes to rotating its subtitle is answered as
 * a no, and counted, and its no stays a no. Off or paused, Facebook's yes stands.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostDatesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.KEEP_POST_DATES.resetToDefault();
        Settings.HIDE_POST_SONGS.resetToDefault();
        Settings.BOLD_POST_TIMES.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.POST_DATES + ":")) return line;
        }
        return null;
    }

    @Test
    public void aHeaderThatWouldRotateKeepsTheOneLine() {
        assertTrue("the switch doesn't start on", Settings.KEEP_POST_DATES.get());
        assertFalse("the header still rotates its subtitle", PostDates.cycling(true));
        assertFalse("a header that doesn't rotate was made to", PostDates.cycling(false));
        assertEquals(FamilyNames.POST_DATES + ": invoked 2, 1 found, 0 missing. Counted: "
                + PostDates.ONE_LINE + " 1", statusLine());
    }

    @Test
    public void offOrPausedFacebooksRotationStands() {
        Settings.KEEP_POST_DATES.save(false);
        assertTrue(PostDates.cycling(true));
        assertFalse(PostDates.cycling(false));
        Settings.KEEP_POST_DATES.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertTrue("a Hushfacebook paused by " + reason + " kept the header from rotating", PostDates.cycling(true));
            PauseForTests.resume();
        }
        assertEquals(FamilyNames.POST_DATES + ": invoked 4, 1 found, 0 missing", statusLine());
    }

    /** Hide songs under names (#533): the line under the name leaves the song out only while the switch is on. */
    @Test
    public void theSongIsLeftOutOnlyWithTheSwitchOn() {
        assertFalse(PostDates.songsInBuild());
        assertFalse("the switch doesn't start off", Settings.HIDE_POST_SONGS.get());
        assertFalse("a song was left out with the switch off", PostDates.hideSong());

        Settings.HIDE_POST_SONGS.save(true);
        assertTrue("the song stayed with the switch on", PostDates.hideSong());
        assertTrue(statusLine(), statusLine().contains(PostDates.SONG_HIDDEN + " 1"));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, the song was left out", PostDates.hideSong());
    }

    /** Bold post times (#533): the time text comes back bold over its whole length, and as it was otherwise. */
    @Test
    public void thePostTimeComesBackBold() {
        assertFalse(PostDates.timesInBuild());
        assertFalse("the switch doesn't start off", Settings.BOLD_POST_TIMES.get());
        CharSequence time = "15h";
        assertSame("the switch off changed the time", time, PostDates.boldTime(time));

        Settings.BOLD_POST_TIMES.save(true);
        CharSequence bold = PostDates.boldTime(time);
        assertEquals("the time's words changed", "15h", bold.toString());
        assertTrue("the time isn't styled", bold instanceof Spanned);
        StyleSpan[] spans = ((Spanned) bold).getSpans(0, bold.length(), StyleSpan.class);
        assertEquals("one bold span", 1, spans.length);
        assertEquals(Typeface.BOLD, spans[0].getStyle());
        assertEquals(0, ((Spanned) bold).getSpanStart(spans[0]));
        assertEquals(bold.length(), ((Spanned) bold).getSpanEnd(spans[0]));
        assertNull(PostDates.boldTime(null));
        assertEquals("", PostDates.boldTime("").toString());
        assertTrue(statusLine(), statusLine().contains(PostDates.TIME_BOLD + " 1"));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame("paused, the time was made bold", time, PostDates.boldTime(time));
    }
}
