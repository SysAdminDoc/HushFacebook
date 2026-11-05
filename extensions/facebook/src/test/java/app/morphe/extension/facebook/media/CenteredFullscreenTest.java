/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
 * Open full screen videos centered (#242): with its switch on, a full screen video Facebook sets up
 * with quiet mode off starts with it on, so the details and comments panel is folded away and the
 * video sits in the middle. A setup Facebook keeps out of quiet mode stays that way, and off or
 * paused the flag stays as Facebook left it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CenteredFullscreenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        CenteredFullscreenForTests.install();
        Settings.CENTER_FULL_SCREEN_VIDEOS.save(true);
    }

    @After
    public void restore() {
        CenteredFullscreenForTests.forget();
        PauseForTests.resume();
        Settings.CENTER_FULL_SCREEN_VIDEOS.resetToDefault();
        HookStatus.clear();
    }

    private static String status() {
        return String.join("\n", HookStatus.report());
    }

    @Test
    public void onAVideoStartsWithItsPanelFolded() {
        CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
        CenteredFullscreen.started(manager, false);
        assertTrue(manager.quiet.get());
        assertTrue("counted", status().contains(CenteredFullscreen.CENTERED));
    }

    @Test
    public void aVideoAlreadyQuietIsLeftAndNotCountedAgain() {
        CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
        manager.quiet.set(true);
        CenteredFullscreen.started(manager, false);
        assertTrue(manager.quiet.get());
        assertFalse(status().contains(CenteredFullscreen.CENTERED));
    }

    @Test
    public void aSetupFacebookKeepsOutOfQuietModeStaysThatWay() {
        CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
        CenteredFullscreen.started(manager, true);
        assertFalse(manager.quiet.get());
    }

    @Test
    public void offTheFlagStaysAsFacebookLeftIt() {
        Settings.CENTER_FULL_SCREEN_VIDEOS.save(false);
        CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
        CenteredFullscreen.started(manager, false);
        assertFalse(manager.quiet.get());
    }

    @Test
    public void pausedTheFlagStaysAsFacebookLeftIt() {
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
            CenteredFullscreen.started(manager, false);
            assertFalse("paused by " + reason, manager.quiet.get());
            PauseForTests.resume();
        }
    }

    @Test
    public void anUnpatchedStubLeavesFacebookAlone() {
        CenteredFullscreenForTests.forget();
        CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
        CenteredFullscreen.started(manager, false);
        assertFalse(manager.quiet.get());
    }

    @Test
    public void aStubThatThrowsIsReported() {
        CenteredFullscreen.flags = manager -> {
            throw new IllegalStateException("moved");
        };
        CenteredFullscreen.started(new Object(), false);
        assertEquals(true, status().contains("full screen quiet mode"));
    }
}
