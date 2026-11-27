/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

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
 * Hide Send message under posts (#609): with the switch on, the post call-to-action selector hears
 * that the Messenger Send message plugin doesn't apply, and that's counted. Every other plugin is
 * left to Facebook, and off or paused so is the Send message one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SendMessageCtaTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_POST_SEND_MESSAGE.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.POST_ACTION_BUTTONS + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSendMessageBarGoesAndEveryOtherButtonStays() {
        assertFalse("the switch starts on", Settings.HIDE_POST_SEND_MESSAGE.get());
        Settings.HIDE_POST_SEND_MESSAGE.save(true);
        assertTrue("the Send message bar stayed", SendMessageCta.hidesSendMessage(SendMessageCta.SEND_MESSAGE));
        assertFalse("Meta AI's question chips went with it", SendMessageCta.hidesSendMessage(
                "com.facebook.feed.plugins.calltoaction.impl.genaideepdive.GenAiDeepDiveCtaPlugin"));
        assertFalse("Imagine me went with it", SendMessageCta.hidesSendMessage(
                "com.facebook.feed.plugins.calltoaction.impl.imagineme.ImagineMePlugin"));
        assertFalse(SendMessageCta.hidesSendMessage(null));
        assertFalse("the selector's names are whole class names",
                SendMessageCta.hidesSendMessage("MessagePostAuthorDwellCTAPlugin"));
        assertEquals(FamilyNames.POST_ACTION_BUTTONS + ": invoked 5, 1 found, 0 missing. Counted: "
                + SendMessageCta.HIDDEN + " 1", statusLine());
    }

    @Test
    public void offOrPausedTheSendMessageBarStays() {
        assertFalse("off by default, the bar went", SendMessageCta.hidesSendMessage(SendMessageCta.SEND_MESSAGE));
        Settings.HIDE_POST_SEND_MESSAGE.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " hid the Send message bar",
                    SendMessageCta.hidesSendMessage(SendMessageCta.SEND_MESSAGE));
            PauseForTests.resume();
        }
        assertEquals(FamilyNames.POST_ACTION_BUTTONS + ": invoked 3, 1 found, 0 missing", statusLine());
    }
}
