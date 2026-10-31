/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

import app.morphe.extension.facebook.profile.LocalProfileCountsForTests.Screen;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LocalProfileCountsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Locale locale;

    @Before
    public void startClean() {
        locale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        LocalProfileCounts.forgetForTests();
        HookStatus.clear();
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        Settings.LOCAL_FRIENDS.save("1234");
        Settings.LOCAL_FOLLOWERS.save("56");
        Settings.LOCAL_FOLLOWING.save("");
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.LOCAL_PROFILE_COUNTS.resetToDefault();
        Settings.LOCAL_FRIENDS.resetToDefault();
        Settings.LOCAL_FOLLOWERS.resetToDefault();
        Settings.LOCAL_FOLLOWING.resetToDefault();
        LocalProfileCounts.forgetForTests();
        HookStatus.clear();
        Locale.setDefault(locale);
    }

    @Test
    public void swapsTheNumberBeforeEachWordAndKeepsTheWord() {
        assertEquals("1,234 friends", LocalProfileCounts.rewrite("812 friends", "1234", "56", ""));
        assertEquals("56 followers", LocalProfileCounts.rewrite("1.2K followers", "1234", "56", ""));
        assertEquals("1,234 friends · 56 followers",
                LocalProfileCounts.rewrite("812 friends · 3 followers", "1234", "56", ""));
    }

    @Test
    public void aBlankCountLeavesItsNumberAlone() {
        assertEquals("77 following", LocalProfileCounts.rewrite("77 following", "1234", "56", ""));
    }

    @Test
    public void readsTheWordsOfOtherLanguages() {
        assertEquals("1,234 Freunde", LocalProfileCounts.rewrite("812 Freunde", "1234", "56", ""));
        assertEquals("56 seguidores", LocalProfileCounts.rewrite("3 seguidores", "1234", "56", ""));
        assertEquals("1,234 amis", LocalProfileCounts.rewrite("12 amis", "1234", "56", ""));
    }

    @Test
    public void leavesOtherTextAlone() {
        for (String text : new String[] {"Friends", "Add friend", "12 mutual connections", "4 friendship requests",
                "Joined 2019"}) {
            assertEquals(text, LocalProfileCounts.rewrite(text, "1234", "56", "9"));
        }
    }

    @Test
    public void cleanKeepsDigitsOnly() {
        assertEquals("1234", LocalProfileCounts.clean(" 1234 "));
        assertEquals("5", LocalProfileCounts.clean("0005"));
        assertEquals("0", LocalProfileCounts.clean("000"));
        assertEquals("", LocalProfileCounts.clean("12a"));
        assertEquals("", LocalProfileCounts.clean("-5"));
        assertEquals("", LocalProfileCounts.clean("12345678901"));
        assertEquals("", LocalProfileCounts.clean(""));
        assertEquals("", LocalProfileCounts.clean(null));
    }

    @Test
    public void ownProfileGetsTheTypedNumber() {
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        assertEquals("1,234 friends", LocalProfileCounts.text("812 friends"));
        String status = null;
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.LOCAL_PROFILE_COUNTS + ":")) status = line;
        }
        assertTrue(String.valueOf(status), status != null && status.contains("0 missing"));
    }

    @Test
    public void aLongLineIsNeverTouched() {
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        String longLine = "812 followers of the page group and many other words that make a long line, past eighty";
        assertEquals(longLine, LocalProfileCounts.text(longLine));
    }

    @Test
    public void aProfileWithNoIdIsYours() {
        LocalProfileCounts.opened(new Screen(null, true, LocalProfileCountsForTests.VIEWER));
        assertEquals("1,234 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void anotherPersonsProfileIsNeverTouched() {
        LocalProfileCounts.opened(new Screen(555L, true, LocalProfileCountsForTests.VIEWER));
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void aScreenBehindAnotherIsLeftAlone() {
        LocalProfileCounts.opened(new Screen(Long.parseLong(LocalProfileCountsForTests.VIEWER), false,
                LocalProfileCountsForTests.VIEWER));
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void anUnknownViewerShowsNothingAsYours() {
        LocalProfileCounts.opened(new Screen(null, true, null));
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
        LocalProfileCounts.opened(new Object());
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
        LocalProfileCounts.forgetForTests();
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void offShowsTheRealOnes() {
        Settings.LOCAL_PROFILE_COUNTS.save(false);
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void allBlankShowsTheRealOnes() {
        Settings.LOCAL_FRIENDS.save("");
        Settings.LOCAL_FOLLOWERS.save("");
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void aBadSavedValueShowsTheRealOne() {
        Settings.LOCAL_FRIENDS.save("12x");
        Settings.LOCAL_FOLLOWERS.save("");
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void pausedShowsTheRealOnes() {
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("812 friends", LocalProfileCounts.text("812 friends"));
    }

    @Test
    public void nullAndLongTextPass() {
        assertNull(LocalProfileCounts.text(null));
        assertEquals("no digits friends", LocalProfileCounts.text("no digits friends"));
        LocalProfileCounts.opened(LocalProfileCountsForTests.own());
        String longLine = "812 friends and a very long line that goes past the limit of eighty characters, for sure";
        assertEquals(longLine, LocalProfileCounts.text(longLine));
    }

    @Test
    public void isOwnNeedsAKnownViewer() {
        assertTrue(LocalProfileCounts.isOwn(0, "7"));
        assertTrue(LocalProfileCounts.isOwn(7, "7"));
        assertFalse(LocalProfileCounts.isOwn(8, "7"));
        assertFalse(LocalProfileCounts.isOwn(7, null));
        assertFalse(LocalProfileCounts.isOwn(0, ""));
    }
}
