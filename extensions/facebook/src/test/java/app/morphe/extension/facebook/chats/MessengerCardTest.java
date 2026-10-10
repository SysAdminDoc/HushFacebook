/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplicationPackageManager;

import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The hook first in the Messenger card's show question: it answers no for Facebook while the
 * switch is on and Messenger is installed, and every other time lets Facebook ask its own question.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MessengerCardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        MessengerCardForTests.uninstall();
        MessengerCardForTests.newProcess();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_GET_MESSENGER_CARD.resetToDefault();
        MessengerCardForTests.uninstall();
        MessengerCardForTests.newProcess();
        FailingPackageManager.failNext = false;
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(MessengerCard.ROUTE + ":")) return line;
        }
        return null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.MESSENGER_CARD + ":")) return line;
        }
        return null;
    }

    @Test
    public void withMessengerInstalledTheSwitchStartsOnAndHidesTheCard() {
        assertTrue("the switch starts off", Settings.HIDE_GET_MESSENGER_CARD.get());
        MessengerCardForTests.install(true);
        assertTrue(MessengerCard.hide());
        assertEquals(MessengerCard.ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: " + MessengerCard.HIDDEN
                + ". Removed: " + MessengerCard.HIDDEN + " 1", counterLine());
        assertEquals(FamilyNames.MESSENGER_CARD + ": invoked 1, 0 found, 0 missing", statusLine());
    }

    /** Without Messenger the card keeps its way to install it. */
    @Test
    public void withoutMessengerTheCardStays() {
        assertFalse(MessengerCard.hide());
        assertEquals(MessengerCard.ROUTE + ": 1 lists, 1 items, 0 removed", counterLine());
    }

    /** A disabled Messenger can't be opened, so it counts as missing and the card stays. */
    @Test
    public void aDisabledMessengerLeavesTheCard() {
        MessengerCardForTests.install(false);
        assertFalse(MessengerCard.hide());
    }

    /**
     * Messenger signed with Meta's key beside a Facebook signed with the patcher's is the case the
     * patch is for: Facebook's own check turns that Messenger down, and this one takes it.
     */
    @Test
    public void aMessengerSignedWithAnotherKeyCounts() {
        MessengerCardForTests.install(true);
        PackageManager packages = RuntimeEnvironment.getApplication().getPackageManager();
        String self = RuntimeEnvironment.getApplication().getPackageName();
        assertNotEquals("the test Messenger shares the app's key", PackageManager.SIGNATURE_MATCH,
                packages.checkSignatures(self, MessengerCard.MESSENGER));
        assertTrue(MessengerCard.lookUp(packages));
        assertTrue(MessengerCard.hide());
    }

    /** HushMessenger installed beside Meta's apps is a Messenger too, so the card goes without Meta's. */
    @Test
    public void aMessengerUnderAnotherPackageNameCounts() {
        try {
            MessengerIconForTests.installLauncher(MessengerIconForTests.CLONE_HOME);
            assertFalse(MessengerCard.lookUp(RuntimeEnvironment.getApplication().getPackageManager()));
            assertTrue(MessengerCard.hide());
        } finally {
            MessengerIconForTests.uninstall();
        }
    }

    /** Another app's launcher entry isn't Messenger, so the card keeps its way to install it. */
    @Test
    public void anotherAppsLauncherEntryLeavesTheCard() {
        try {
            MessengerIconForTests.installLauncher(MessengerIconForTests.OTHER_HOME);
            assertFalse(MessengerCard.hide());
            assertEquals(MessengerCard.ROUTE + ": 1 lists, 1 items, 0 removed", counterLine());
        } finally {
            MessengerIconForTests.uninstall();
        }
    }

    @Test
    public void offTheCardIsFacebooksToShow() {
        MessengerCardForTests.install(true);
        Settings.HIDE_GET_MESSENGER_CARD.save(false);
        assertFalse(MessengerCard.hide());
        // Counted with the switch off too, so the report shows Chats asked.
        assertEquals(MessengerCard.ROUTE + ": 1 lists, 1 items, 0 removed", counterLine());
        Settings.HIDE_GET_MESSENGER_CARD.save(true);
        assertTrue(MessengerCard.hide());
    }

    @Test
    public void pausedTheCardComesBack() {
        MessengerCardForTests.install(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(MessengerCard.hide());
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertFalse(MessengerCard.hide());
        PauseForTests.resume();
        assertTrue(MessengerCard.hide());
    }

    /** Until the settings are ready, Facebook asks its own question and nothing is looked up. */
    @Test
    public void untilTheSettingsAreReadyTheCardIsFacebooks() {
        MessengerCardForTests.install(true);
        boolean[] hid = {true};
        SettingsContextRule.withoutContext(() -> hid[0] = MessengerCard.hide());
        assertFalse(hid[0]);
        SettingsContextRule.beforeThePauseIsDecided(() -> hid[0] = MessengerCard.hide());
        assertFalse(hid[0]);
        assertTrue(MessengerCard.hide());
    }

    /**
     * The answer is looked up once per Facebook process and kept: Chats asks on every pass over
     * the list, and a package lookup is a call into the system. Installing or removing Messenger
     * counts from Facebook's next start.
     */
    @Test
    public void theAnswerIsKeptUntilFacebookStartsAgain() {
        MessengerCardForTests.install(true);
        assertTrue(MessengerCard.hide());
        MessengerCardForTests.uninstall();
        assertTrue("the kept answer was looked up again", MessengerCard.hide());
        MessengerCardForTests.newProcess();
        assertFalse(MessengerCard.hide());
        MessengerCardForTests.install(true);
        assertFalse("the kept answer was looked up again", MessengerCard.hide());
        MessengerCardForTests.newProcess();
        assertTrue(MessengerCard.hide());
    }

    /**
     * A failure in the lookup leaves the card to Facebook, the report names the hook, and nothing
     * is kept, so the next question looks again.
     */
    @Test @Config(shadows = FailingPackageManager.class)
    public void aFailureLeavesTheCardAndTheReportSaysSo() {
        MessengerCardForTests.install(true);
        FailingPackageManager.failNext = true;
        assertFalse(MessengerCard.hide());
        assertFalse("the lookup never asked the package manager", FailingPackageManager.failNext);
        List<String> missing = HookStatus.missing(FamilyNames.MESSENGER_CARD);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains(
                "'Get Messenger card' hook (it threw " + IllegalStateException.class.getName() + ")"));
        assertTrue("a failed lookup was kept", MessengerCard.hide());
    }

    /** Every question is counted under the patch's name. */
    @Test
    public void theHookReportsUnderThePatchsName() {
        MessengerCardForTests.install(true);
        MessengerCard.hide();
        Settings.HIDE_GET_MESSENGER_CARD.save(false);
        MessengerCard.hide();
        assertEquals(FamilyNames.MESSENGER_CARD + ": invoked 2, 0 found, 0 missing", statusLine());
        assertEquals("Hide the Get Messenger card", FamilyNames.MESSENGER_CARD);
        assertEquals("com.facebook.orca", MessengerCard.MESSENGER);
    }

    /** Robolectric's package manager, except that the next getApplicationInfo() can be made to throw, once. */
    @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    public static class FailingPackageManager extends ShadowApplicationPackageManager {
        static volatile boolean failNext;

        @Implementation
        @Override
        protected ApplicationInfo getApplicationInfo(String packageName, int flags)
                throws PackageManager.NameNotFoundException {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("the package manager failed");
            }
            return super.getApplicationInfo(packageName, flags);
        }
    }
}
