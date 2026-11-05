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
        // The player's layout moves the video to the middle only when the listeners hear of it,
        // after the flag is already on.
        assertEquals("listeners told", 1, manager.told);
        assertTrue("quiet when told", manager.quietWhenTold);
        assertTrue("counted", status().contains(CenteredFullscreen.CENTERED));
    }

    @Test
    public void listenersThatArriveAfterTheStartHearItOnceTogether() {
        CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
        CenteredFullscreen.started(manager, false);
        // The player's own component and the comment bar start listening after the setup (the S22
        // showed the panel folded but the video at the top and the comment bar still up).
        CenteredFullscreen.listening(manager);
        CenteredFullscreen.listening(manager);
        assertEquals("not before the listener is in the list", 1, manager.told);
        CenteredFullscreenForTests.runMain();
        assertEquals("one notice for both", 2, manager.told);
        assertTrue("quiet when told", manager.quietWhenTold);
        CenteredFullscreen.listening(manager);
        CenteredFullscreenForTests.runMain();
        assertEquals("a later listener hears it too", 3, manager.told);
    }

    @Test
    public void aListenerThatStartsOverOnEveryNoticeCantKeepItTelling() {
        CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
        CenteredFullscreen.started(manager, false);
        for (int i = 0; i < CenteredFullscreen.RETELLS * 3; i++) {
            CenteredFullscreen.listening(manager);
            CenteredFullscreenForTests.runMain();
        }
        assertEquals(1 + CenteredFullscreen.RETELLS, manager.told);
    }

    @Test
    public void onceThePanelIsBackListenersAreFacebooks() {
        CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
        CenteredFullscreen.started(manager, false);
        CenteredFullscreen.listening(manager);
        manager.quiet.set(false); // the chevron, before the notice ran
        CenteredFullscreenForTests.runMain();
        assertEquals("no notice for a panel that's back", 1, manager.told);
        manager.quiet.set(true); // the chevron again: Facebook tells its listeners itself
        CenteredFullscreen.listening(manager);
        CenteredFullscreenForTests.runMain();
        assertEquals("the manager is Facebook's again", 1, manager.told);
    }

    @Test
    public void listenersOfAManagerNeverStartedHereAreLeftAlone() {
        CenteredFullscreenForTests.Manager quietAlready = new CenteredFullscreenForTests.Manager();
        quietAlready.quiet.set(true);
        CenteredFullscreen.started(quietAlready, false);
        CenteredFullscreenForTests.Manager keptOut = new CenteredFullscreenForTests.Manager();
        CenteredFullscreen.started(keptOut, true);
        CenteredFullscreenForTests.Manager unanswered = new CenteredFullscreenForTests.Manager();
        unanswered.answers = false;
        CenteredFullscreen.started(unanswered, false);
        Settings.CENTER_FULL_SCREEN_VIDEOS.save(false);
        CenteredFullscreenForTests.Manager off = new CenteredFullscreenForTests.Manager();
        CenteredFullscreen.started(off, false);
        for (CenteredFullscreenForTests.Manager manager : new CenteredFullscreenForTests.Manager[]{quietAlready, keptOut, unanswered, off}) {
            CenteredFullscreen.listening(manager);
        }
        CenteredFullscreen.listening(new Object());
        CenteredFullscreenForTests.runMain();
        assertEquals(0, quietAlready.told + keptOut.told + unanswered.told + off.told);
    }

    @Test
    public void aVideoAlreadyQuietIsLeftAndNotCountedAgain() {
        CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
        manager.quiet.set(true);
        CenteredFullscreen.started(manager, false);
        assertTrue(manager.quiet.get());
        assertEquals("listeners told", 0, manager.told);
        assertFalse(status().contains(CenteredFullscreen.CENTERED));
    }

    @Test
    public void aManagerWhoseListenersCantBeToldKeepsItsPanel() {
        CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
        manager.answers = false;
        CenteredFullscreen.started(manager, false);
        assertFalse("never half quiet", manager.quiet.get());
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
        assertEquals("listeners told", 0, manager.told);
    }

    @Test
    public void pausedTheFlagStaysAsFacebookLeftIt() {
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            CenteredFullscreenForTests.Manager manager = new CenteredFullscreenForTests.Manager();
            CenteredFullscreen.started(manager, false);
            assertFalse("paused by " + reason, manager.quiet.get());
            assertEquals("paused by " + reason + ": listeners told", 0, manager.told);
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
