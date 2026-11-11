/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The hook first in each of the browser's link history writer factories: a yes while the switch is
 * on, so neither writer is built, and Facebook's own answer every other time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LinkHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HOLD_LINK_HISTORY.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(LinkHistory.ROUTE + ":")) return line;
        }
        return null;
    }

    @Test
    public void onByDefaultNeitherWriterIsBuilt() {
        assertTrue("the switch starts on", Settings.HOLD_LINK_HISTORY.defaultValue);
        assertTrue("the signals writer was built", LinkHistory.hold());
        assertTrue("the page data writer was built", LinkHistory.hold());
        assertEquals(LinkHistory.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: " + LinkHistory.WRITERS
                + ". Removed: " + LinkHistory.WRITERS + " 2", counterLine());
        boolean reported = false;
        for (String line : HookStatus.report("")) {
            if (line.equals(FamilyNames.EXTERNAL_BROWSER + ": invoked 2, 0 found, 0 missing")) reported = true;
        }
        assertTrue(String.valueOf(HookStatus.report("")), reported);
    }

    @Test
    public void offTheBrowserBuildsItsWriters() {
        Settings.HOLD_LINK_HISTORY.save(false);
        assertFalse(LinkHistory.hold());
        assertEquals(LinkHistory.ROUTE + ": 1 lists, 1 items, 0 removed", counterLine());
    }

    @Test
    public void pausedTheBrowserBuildsItsWriters() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(LinkHistory.hold());
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertFalse(LinkHistory.hold());
        PauseForTests.resume();
        assertTrue(LinkHistory.hold());
    }

    @Test
    public void onByDefaultTheUploadIsHeldAndCountedApart() {
        assertTrue("the upload was sent", LinkHistory.holdUpload());
        assertTrue(LinkHistory.hold());
        assertEquals(LinkHistory.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: " + LinkHistory.WRITERS
                + ". Removed: " + LinkHistory.UPLOADS + " 1, " + LinkHistory.WRITERS + " 1", counterLine());
    }

    @Test
    public void offOrPausedTheUploadIsSent() {
        Settings.HOLD_LINK_HISTORY.save(false);
        assertFalse(LinkHistory.holdUpload());
        Settings.HOLD_LINK_HISTORY.resetToDefault();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(LinkHistory.holdUpload());
        assertEquals(LinkHistory.ROUTE + ": 2 lists, 2 items, 0 removed", counterLine());
    }

    @Test
    public void onByDefaultBothAutofillReportsAreHeldAndCountedApart() {
        assertTrue("the form report was logged", LinkHistory.holdAutofillReport());
        assertTrue("the first-party data event was logged", LinkHistory.holdAutofillReport());
        assertTrue(LinkHistory.holdUpload());
        assertEquals(LinkHistory.ROUTE + ": 3 lists, 3 items, 3 removed. Last reason: " + LinkHistory.UPLOADS
                + ". Removed: " + LinkHistory.AUTOFILL_REPORTS + " 2, " + LinkHistory.UPLOADS + " 1", counterLine());
    }

    @Test
    public void offOrPausedTheAutofillReportsAreLogged() {
        Settings.HOLD_LINK_HISTORY.save(false);
        assertFalse(LinkHistory.holdAutofillReport());
        Settings.HOLD_LINK_HISTORY.resetToDefault();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(LinkHistory.holdAutofillReport());
        assertEquals(LinkHistory.ROUTE + ": 2 lists, 2 items, 0 removed", counterLine());
    }

    @Test
    public void onByDefaultASampledBrowserEventIsntSent() {
        assertFalse("the landing page event went out", LinkHistory.sendBrowserEvent(1));
        assertFalse("the pixel event went out", LinkHistory.sendBrowserEvent(1));
        assertEquals(LinkHistory.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: " + LinkHistory.BROWSER_EVENTS
                + ". Removed: " + LinkHistory.BROWSER_EVENTS + " 2", counterLine());
    }

    /** An event Facebook's own sampling turned away stays unsent, and isn't counted as held. */
    @Test
    public void anUnsampledBrowserEventStaysUnsentAndUncounted() {
        assertFalse(LinkHistory.sendBrowserEvent(0));
        Settings.HOLD_LINK_HISTORY.save(false);
        assertFalse(LinkHistory.sendBrowserEvent(0));
        assertNull(counterLine());
    }

    @Test
    public void offOrPausedASampledBrowserEventIsSent() {
        Settings.HOLD_LINK_HISTORY.save(false);
        assertTrue(LinkHistory.sendBrowserEvent(1));
        Settings.HOLD_LINK_HISTORY.resetToDefault();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(LinkHistory.sendBrowserEvent(1));
        assertEquals(LinkHistory.ROUTE + ": 2 lists, 2 items, 0 removed", counterLine());
    }

    @Test
    public void onByDefaultAnAdEventIsHeldAndCountedApart() {
        assertTrue("the call button's tap was logged", LinkHistory.holdAdEvent());
        assertFalse(LinkHistory.sendBrowserEvent(1));
        assertEquals(LinkHistory.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: " + LinkHistory.BROWSER_EVENTS
                + ". Removed: " + LinkHistory.AD_EVENTS + " 1, " + LinkHistory.BROWSER_EVENTS + " 1", counterLine());
    }

    @Test
    public void offOrPausedAnAdEventIsLogged() {
        Settings.HOLD_LINK_HISTORY.save(false);
        assertFalse(LinkHistory.holdAdEvent());
        Settings.HOLD_LINK_HISTORY.resetToDefault();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(LinkHistory.holdAdEvent());
        assertEquals(LinkHistory.ROUTE + ": 2 lists, 2 items, 0 removed", counterLine());
    }

    @Test
    public void onByDefaultEachIntegrityReportIsHeldAndCountedApart() {
        assertTrue("the cloaking check got the page", LinkHistory.holdIntegrityReport("INTEGRITY_LOGGER"));
        assertTrue("the redirect chain was logged", LinkHistory.holdIntegrityReport("INTEGRITY_SIGNALS_EVENT"));
        assertTrue("the outbound click was logged", LinkHistory.holdIntegrityReport("INTEGRITY_OUTBOUND_CLICK_EVENT"));
        assertTrue(LinkHistory.holdAdEvent());
        assertEquals(LinkHistory.ROUTE + ": 4 lists, 4 items, 4 removed. Last reason: " + LinkHistory.AD_EVENTS
                + ". Removed: " + LinkHistory.INTEGRITY_REPORTS + " 3, " + LinkHistory.AD_EVENTS + " 1", counterLine());
    }

    /** The callback's other events do things (autofill, translation, the login dialog), so they always run. */
    @Test
    public void theCallbacksOtherEventsRunAndArentCounted() {
        for (String event : new String[] {"SAVE_AUTOFILL_DIALOG_COMPLETED", "AUTOFILL_BAR_ACCEPTED",
                "CALL_EXTENSION_DATA_FETCH", "TOGGLE_WEB_PAGE_TRANSLATION", "SHOW_BWP_MORE_INFO_BOTTOM_SHEET",
                "FBLOGIN_ACTION_SEND_REPONSE_BACK", "FBLOGIN_JS_SHOW_DIALOG", "integrity_logger", "", null}) {
            assertFalse(String.valueOf(event), LinkHistory.holdIntegrityReport(event));
        }
        assertNull(counterLine());
    }

    @Test
    public void offOrPausedAnIntegrityReportGoesOut() {
        Settings.HOLD_LINK_HISTORY.save(false);
        assertFalse(LinkHistory.holdIntegrityReport("INTEGRITY_SIGNALS_EVENT"));
        Settings.HOLD_LINK_HISTORY.resetToDefault();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(LinkHistory.holdIntegrityReport("INTEGRITY_LOGGER"));
        assertEquals(LinkHistory.ROUTE + ": 2 lists, 2 items, 0 removed", counterLine());
    }
}
