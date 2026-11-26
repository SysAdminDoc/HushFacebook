/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.view.View;

import com.facebook.bookmark.tab.BookmarkTab;
import com.facebook.feed.tab.FeedTab;
import com.facebook.marketplace.tab.MarketplaceTab;
import com.facebook.notifications.tab.NotificationsTab;
import com.facebook.timeline.dashboard.tab.TimelineTab;
import com.facebook.video.videohome.tab.WatchTab;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hold the profile tab for Activity log (#605): with the switch on, a long press on the profile tab,
 * or on the Menu tab an account has in its place, opens Facebook's Activity log route through its
 * link handler and takes the press, so the account switcher never opens. Every other tab, the switch
 * off, or Hushfacebook paused leaves the press to Facebook.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ProfileTabHoldTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        drain();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.PROFILE_TAB_ACTIVITY_LOG.resetToDefault();
        HookStatus.clear();
    }

    private static Application app() {
        return RuntimeEnvironment.getApplication();
    }

    private static void drain() {
        while (shadowOf(app()).getNextStartedActivity() != null) { }
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    private static void assertOpensTheActivityLog(Intent started) {
        assertNotNull("a long press opened something", started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("Facebook's Activity log route", "fb://activitylog", String.valueOf(started.getData()));
        assertNotNull(started.getComponent());
        assertEquals("through Facebook's own link handler", "com.facebook.katana.IntentUriHandler",
                started.getComponent().getClassName());
        assertEquals(app().getPackageName(), started.getComponent().getPackageName());
    }

    @Test
    public void startsOffAndBelongsToTheTabBarSwitches() {
        assertFalse("it starts off", Settings.PROFILE_TAB_ACTIVITY_LOG.get());
        assertFalse("a change shows on the next press", Settings.PROFILE_TAB_ACTIVITY_LOG.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.PROFILE_TAB_ACTIVITY_LOG));
    }

    @Test
    public void offTheProfileTabKeepsFacebooksAccountSwitcher() {
        assertFalse(ProfileTabHold.held(new View(app()), new TimelineTab()));
        assertNull("nothing opened", shadowOf(app()).getNextStartedActivity());
    }

    @Test
    public void onTheProfileTabOpensTheActivityLogAndTakesThePress() {
        Settings.PROFILE_TAB_ACTIVITY_LOG.save(true);
        assertTrue(ProfileTabHold.held(new View(app()), new TimelineTab()));
        Intent started = shadowOf(app()).getNextStartedActivity();
        assertOpensTheActivityLog(started);
        assertTrue("outside an activity it needs a task of its own",
                (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals(FamilyNames.BOTTOM_TAB_BAR + ": invoked 1, 1 found, 0 missing. Counted: "
                + ProfileTabHold.OPENED + " 1", statusLine());
    }

    @Test
    public void theMenuTabInTheProfileTabsPlaceDoesTheSame() {
        Settings.PROFILE_TAB_ACTIVITY_LOG.save(true);
        assertTrue(ProfileTabHold.held(new View(app()), new BookmarkTab()));
        assertOpensTheActivityLog(shadowOf(app()).getNextStartedActivity());
    }

    @Test
    public void fromTheMainScreenItOpensInThatScreensTask() {
        Settings.PROFILE_TAB_ACTIVITY_LOG.save(true);
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        View tab = new View(new android.view.ContextThemeWrapper(screen, android.R.style.Theme_Material));
        assertTrue(ProfileTabHold.held(tab, new TimelineTab()));
        Intent started = shadowOf(screen).getNextStartedActivity();
        assertOpensTheActivityLog(started);
        assertEquals("no new task from the screen", 0, started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test
    public void everyOtherTabKeepsFacebooksLongPress() {
        Settings.PROFILE_TAB_ACTIVITY_LOG.save(true);
        for (Object tab : new Object[] {new FeedTab(), new WatchTab(), new MarketplaceTab(), new NotificationsTab(), new Object()}) {
            assertFalse(tab.getClass().getName(), ProfileTabHold.held(new View(app()), tab));
        }
        assertFalse("no tab", ProfileTabHold.held(new View(app()), null));
        assertFalse("no view", ProfileTabHold.held(null, new TimelineTab()));
        assertNull("nothing opened", shadowOf(app()).getNextStartedActivity());
    }

    @Test
    public void pausedTheLongPressIsFacebooks() {
        Settings.PROFILE_TAB_ACTIVITY_LOG.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " took the press",
                    ProfileTabHold.held(new View(app()), new TimelineTab()));
            PauseForTests.resume();
        }
        assertNull("nothing opened", shadowOf(app()).getNextStartedActivity());
    }
}
