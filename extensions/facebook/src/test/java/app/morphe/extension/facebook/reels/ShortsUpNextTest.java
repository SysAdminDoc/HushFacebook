/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.SettingReadsForTests;

/**
 * The Shorts viewer a feed video opens in (#375): its UP NEXT pill isn't shown once Hide Up next is
 * on. Any other pill, the 2x speed one included, still shows, and the pill takes Facebook's path
 * when paused, before the settings are ready, switched off, or when the switch can't be read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ShortsUpNextTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The kinds 585's label switch gives the two UP NEXT pills and the 2x speed pill. */
    private static final int PEEK = 1;
    private static final int NEXT = 2;
    private static final int SPEED = 3;

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_UP_NEXT.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
        ReelDeclutter.forgetLoggedForTests();
    }

    private static View pill() {
        View pill = new View(RuntimeEnvironment.getApplication());
        pill.setVisibility(View.VISIBLE);
        return pill;
    }

    private static boolean hides(View pill, Integer kind) {
        return ReelDeclutter.hideUpNextPill(pill, kind, PEEK, NEXT);
    }

    @Test
    public void bothUpNextPillsAreHiddenOnceTheSwitchIsOn() {
        View peek = pill();
        View next = pill();
        assertFalse(Settings.HIDE_UP_NEXT.defaultValue);
        assertFalse("the pill went with the switch off", hides(peek, PEEK));
        assertEquals(View.VISIBLE, peek.getVisibility());
        Settings.HIDE_UP_NEXT.save(true);
        assertTrue(hides(peek, PEEK));
        assertEquals("the pill's container still shows", View.GONE, peek.getVisibility());
        assertTrue(hides(next, NEXT));
        assertEquals("the next video pill's container still shows", View.GONE, next.getVisibility());
        assertTrue("a pill with no container to hide still showed", hides(null, PEEK));
        Settings.HIDE_UP_NEXT.save(false);
        View after = pill();
        assertFalse("turning the switch off didn't bring the pill back", hides(after, PEEK));
        assertEquals(View.VISIBLE, after.getVisibility());
    }

    @Test
    public void everyOtherPillStillShows() {
        Settings.HIDE_UP_NEXT.save(true);
        for (Integer kind : new Integer[] {SPEED, 4, 0, 5, null}) {
            View other = pill();
            assertFalse("pill kind " + kind + " was hidden", hides(other, kind));
            assertEquals("pill kind " + kind + "'s container was hidden", View.VISIBLE, other.getVisibility());
        }
        assertEquals("a pill that isn't Up next was counted", null, line());
    }

    /**
     * The pills share one container, and Facebook only shows what's inside it, so a container hidden
     * for the UP NEXT pill comes back for the next other pill, and for any pill once the switch is
     * off. One Facebook hid itself, or this already showed again, is left alone.
     */
    @Test
    public void aContainerHiddenForUpNextShowsForTheNextOtherPill() {
        Settings.HIDE_UP_NEXT.save(true);
        View shared = pill();
        assertTrue(hides(shared, NEXT));
        assertEquals(View.GONE, shared.getVisibility());
        assertFalse(hides(shared, SPEED));
        assertEquals("the 2x speed pill came in a hidden container", View.VISIBLE, shared.getVisibility());
        shared.setVisibility(View.GONE);
        assertFalse(hides(shared, SPEED));
        assertEquals("a container Facebook hid was shown", View.GONE, shared.getVisibility());

        View again = pill();
        assertTrue(hides(again, PEEK));
        Settings.HIDE_UP_NEXT.save(false);
        assertFalse(hides(again, PEEK));
        assertEquals("the pill stayed hidden after the switch went off", View.VISIBLE, again.getVisibility());

        View untouched = pill();
        untouched.setVisibility(View.GONE);
        assertFalse(hides(untouched, SPEED));
        assertEquals("a container this never hid was shown", View.GONE, untouched.getVisibility());

        Settings.HIDE_UP_NEXT.save(true);
        View twice = pill();
        assertTrue(hides(twice, NEXT));
        assertTrue(hides(twice, PEEK));
        assertFalse(hides(twice, SPEED));
        assertEquals("a container hidden for two UP NEXT pills in a row stayed hidden", View.VISIBLE,
                twice.getVisibility());

        View away = pill();
        away.setVisibility(View.GONE);
        assertTrue(hides(away, NEXT));
        assertFalse(hides(away, SPEED));
        assertEquals("a container Facebook had put away before the UP NEXT pill was shown", View.GONE,
                away.getVisibility());
        View blank = pill();
        blank.setVisibility(View.INVISIBLE);
        assertTrue(hides(blank, PEEK));
        assertEquals(View.GONE, blank.getVisibility());
        assertFalse(hides(blank, SPEED));
        assertEquals("a container Facebook kept blank came back shown", View.INVISIBLE, blank.getVisibility());
    }

    @Test
    public void theUpNextPillShowsWhilePausedOrBeforeTheSettingsAreReady() {
        Settings.HIDE_UP_NEXT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        View paused = pill();
        assertFalse("the pill went while paused", hides(paused, PEEK));
        assertEquals(View.VISIBLE, paused.getVisibility());
        PauseForTests.resume();
        View early = pill();
        SettingsContextRule.withoutContext(() ->
                assertFalse("the pill went before the settings were ready", hides(early, NEXT)));
        assertEquals(View.VISIBLE, early.getVisibility());
    }

    @Test
    public void bothPillsCountUnderUpNext() {
        Settings.HIDE_UP_NEXT.save(true);
        hides(pill(), PEEK);
        hides(pill(), NEXT);
        assertEquals(ReelDeclutter.UP_NEXT_ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: "
                        + ReelDeclutter.UP_NEXT_PILL + ". Removed: " + ReelDeclutter.UP_NEXT_PILL + " 2. Kinds: "
                        + ReelDeclutter.UP_NEXT_PILL + " 2",
                line());
    }

    /** A switch that can't be read shows the pill, and the report names the hook. */
    @Test
    public void anUpNextSwitchThatCantBeReadShowsThePill() {
        View peek = pill();
        SettingReadsForTests.breakReads(Settings.HIDE_UP_NEXT);
        try {
            assertFalse(hides(peek, PEEK));
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_UP_NEXT);
        }
        assertEquals(View.VISIBLE, peek.getVisibility());
        String missing = HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString();
        assertTrue(missing, missing.contains("a working '" + ReelDeclutter.UP_NEXT_PILL + "' hook (it threw "
                + NullPointerException.class.getName() + ")"));
    }

    private static String line() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(ReelDeclutter.UP_NEXT_ROUTE + ": ")) return line;
        }
        return null;
    }
}
