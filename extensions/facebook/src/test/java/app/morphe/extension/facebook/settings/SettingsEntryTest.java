/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.preference.Preference;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.facebook.menu.MenuSettingsRow;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.preference.ReopenSettingsAfterRestart;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SettingsEntryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final SettingsEntry.OpenWhenResumed watcher = new SettingsEntry.OpenWhenResumed();

    @Before public void watch() {
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(watcher);
    }

    @After public void stopWatching() {
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(watcher);
        HushfacebookPreferenceFragment.failNextInitialization = null;
        PatchFamily.inBuildForTests = null;
        Settings.SETTINGS_SHORTCUT.resetToDefault();
        Settings.SETTINGS_LOGO_PRESS.resetToDefault();
        Settings.SETTINGS_MENU_ROW.resetToDefault();
    }

    /** PausedHooksTest's probe: a start publishes the settings shortcut. */
    static boolean aStartPublishes() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        manager.removeDynamicShortcuts(java.util.Collections.singletonList(SettingsEntry.SHORTCUT_ID));
        SettingsEntry.publishShortcutNow(context);
        return shortcut(manager, SettingsEntry.SHORTCUT_ID) != null;
    }

    /**
     * Someone who opens the screen from Facebook's Menu didn't want it in the icon's long-press
     * menu too (#146). Off takes it out at once and leaves Facebook's own; Facebook's next calls
     * and the next start don't bring it back; on puts it back in front.
     */
    @Test public void theSettingsShortcutSwitchTakesTheShortcutOutAndPutsItBack() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        assertTrue("on by default", Settings.SETTINGS_SHORTCUT.savedValue());
        SettingsEntry.publishShortcutNow(context);
        manager.pushDynamicShortcut(facebookShortcut(context, "friends"));
        assertNotNull(shortcut(manager, SettingsEntry.SHORTCUT_ID));

        Settings.SETTINGS_SHORTCUT.save(false);
        SettingsEntry.shortcutSwitchChanged(context);
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        assertNull("the shortcut stayed in the launcher's menu", shortcut(manager, SettingsEntry.SHORTCUT_ID));
        assertNotNull("Facebook's own shortcut went too", shortcut(manager, "friends"));

        SettingsEntry.pushDynamicShortcut(manager, facebookShortcut(context, "notifications"));
        SettingsEntry.setDynamicShortcuts(manager, java.util.Collections.singletonList(facebookShortcut(context, "reels")));
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        SettingsEntry.publishShortcutNow(context);
        SettingsEntry.keepFirstNow(context);
        assertNull("Facebook's calls or a start brought it back", shortcut(manager, SettingsEntry.SHORTCUT_ID));
        assertNotNull(shortcut(manager, "reels"));

        Settings.SETTINGS_SHORTCUT.save(true);
        SettingsEntry.shortcutSwitchChanged(context);
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        android.content.pm.ShortcutInfo ours = shortcut(manager, SettingsEntry.SHORTCUT_ID);
        assertNotNull("on didn't put it back", ours);
        org.junit.Assert.assertEquals(0, ours.getRank());
        assertNotNull(shortcut(manager, "reels"));
    }

    private static void waysIn(boolean shortcut, boolean logo, boolean menuRow) {
        Settings.SETTINGS_SHORTCUT.save(shortcut);
        Settings.SETTINGS_LOGO_PRESS.save(logo);
        Settings.SETTINGS_MENU_ROW.save(menuRow);
    }

    /** Each of the three ways into the settings goes off alone and the other two carry on (#146). */
    @Test public void eachWayInSwitchesOffAlone() {
        PatchFamily.inBuildForTests = java.util.EnumSet.of(PatchFamily.MENU_SETTINGS_ROW);

        waysIn(true, true, true);
        assertTrue(SettingsEntry.logoPressOn());
        assertTrue(SettingsEntry.menuRowOn());

        waysIn(true, false, true);
        assertFalse("the logo press stayed on", SettingsEntry.logoPressOn());
        assertTrue(SettingsEntry.menuRowOn());
        assertTrue(Settings.SETTINGS_SHORTCUT.get());

        waysIn(true, true, false);
        assertTrue(SettingsEntry.logoPressOn());
        assertFalse("the Menu row stayed on", SettingsEntry.menuRowOn());
        assertTrue(Settings.SETTINGS_SHORTCUT.get());

        waysIn(false, true, true);
        assertTrue(SettingsEntry.logoPressOn());
        assertTrue(SettingsEntry.menuRowOn());
        assertFalse("the shortcut stayed on", Settings.SETTINGS_SHORTCUT.get());
    }

    /** With one off, nothing is locked. With two off, the third is the last way in and can't be turned off. */
    @Test public void theLastWayInIsLocked() {
        waysIn(true, true, true);
        for (BooleanSetting way : new BooleanSetting[]{Settings.SETTINGS_SHORTCUT, Settings.SETTINGS_LOGO_PRESS, Settings.SETTINGS_MENU_ROW}) {
            org.junit.Assert.assertFalse(SettingsEntry.isLastWayIn(way, true));
        }
        waysIn(true, false, true);
        assertFalse(SettingsEntry.isLastWayIn(Settings.SETTINGS_SHORTCUT, true));
        assertFalse(SettingsEntry.isLastWayIn(Settings.SETTINGS_MENU_ROW, true));
        assertFalse("an off switch is never the last one on", SettingsEntry.isLastWayIn(Settings.SETTINGS_LOGO_PRESS, true));

        waysIn(false, false, true);
        assertTrue(SettingsEntry.isLastWayIn(Settings.SETTINGS_MENU_ROW, true));
        assertFalse(SettingsEntry.isLastWayIn(Settings.SETTINGS_SHORTCUT, true));
        waysIn(false, true, false);
        assertTrue(SettingsEntry.isLastWayIn(Settings.SETTINGS_LOGO_PRESS, true));
        waysIn(true, false, false);
        assertTrue(SettingsEntry.isLastWayIn(Settings.SETTINGS_SHORTCUT, true));
    }

    /** A build without the Menu row has two ways in, so the second to last is already the last. */
    @Test public void aBuildWithoutTheMenuRowLocksOneSoonerAndNeverCountsTheRow() {
        waysIn(true, true, true);
        assertFalse(SettingsEntry.isLastWayIn(Settings.SETTINGS_SHORTCUT, false));
        waysIn(false, true, true);
        assertTrue("the row isn't in this build, so the logo is the last way in", SettingsEntry.isLastWayIn(Settings.SETTINGS_LOGO_PRESS, false));
        PatchFamily.inBuildForTests = java.util.Collections.emptySet();
        waysIn(false, false, true);
        assertTrue("a stored Menu row switch counted as a way in on a build without the row", SettingsEntry.logoPressOn());
    }

    /** Stored off in all three, as a backup import can leave it, the reads still leave a way in. */
    @Test public void theReadPathNeverReportsEveryWayInOff() {
        PatchFamily.inBuildForTests = java.util.EnumSet.of(PatchFamily.MENU_SETTINGS_ROW);
        waysIn(false, false, false);
        assertTrue("no way in was left", SettingsEntry.logoPressOn());
        assertTrue("no way in was left", SettingsEntry.menuRowOn());
        assertFalse("the shortcut was forced on", Settings.SETTINGS_SHORTCUT.get());
        assertFalse("the stored choice was rewritten", Settings.SETTINGS_LOGO_PRESS.savedValue());
    }

    /** Before the settings are read, a long press and the Menu row are Facebook's own. */
    @Test public void untilTheSettingsAreReadyBothAreFacebooks() {
        SettingsContextRule.withoutContext(() -> {
            assertFalse(SettingsEntry.logoPressOn());
            assertFalse(SettingsEntry.menuRowOn());
        });
    }

    /** The switch is kept while paused: a pause neither brings the shortcut back nor takes it away. */
    @Test public void aPauseLeavesTheSettingsShortcutAsItsSwitchSays() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        try {
            app.morphe.extension.shared.settings.PauseForTests.pause(
                    app.morphe.extension.shared.settings.HushfacebookPause.Reason.SWITCH);
            assertTrue("a paused start dropped the way back to settings", aStartPublishes());
            Settings.SETTINGS_SHORTCUT.save(false);
            SettingsEntry.publishShortcutNow(context);
            assertNull("a pause brought the shortcut back", shortcut(manager, SettingsEntry.SHORTCUT_ID));
        } finally {
            app.morphe.extension.shared.settings.PauseForTests.resume();
        }
    }

    /**
     * The launcher shortcut is labelled in the phone's language when it's first published, and a
     * phone that changes language gets it relabelled: one found under the old label is pushed
     * again. Kept as it was, it stayed in its first language for good.
     */
    @Test public void theShortcutFollowsThePhonesLanguage() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        org.junit.Assert.assertEquals("Hushfacebook settings", longLabel(manager));

        RuntimeEnvironment.setQualifiers("+de");
        SettingsEntry.publishShortcutNow(context);
        org.junit.Assert.assertEquals(app.morphe.extension.shared.L10nTablesForTests.of("de").get("Hushfacebook settings"),
                longLabel(manager));
        org.junit.Assert.assertEquals("one shortcut, relabelled, not two", 1, manager.getDynamicShortcuts().size());
    }

    /**
     * Facebook sets its own language after the application starts, so the label published then
     * is in the phone's. On a German phone with Facebook in English it stayed German next to an
     * English screen. The next Facebook screen to resume labels it again in Facebook's language.
     */
    @Test public void theShortcutIsLabelledAgainOnceFacebookHasSetItsLanguage() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        org.junit.Assert.assertEquals("Hushfacebook settings", longLabel(manager));

        RuntimeEnvironment.setQualifiers("+de");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
            org.junit.Assert.assertEquals(app.morphe.extension.shared.L10nTablesForTests.of("de").get("Hushfacebook settings"),
                    longLabel(manager));
            org.junit.Assert.assertEquals(1, manager.getDynamicShortcuts().size());
        }
    }

    private static String longLabel(android.content.pm.ShortcutManager manager) {
        for (android.content.pm.ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
            if (SettingsEntry.SHORTCUT_ID.equals(shortcut.getId())) return String.valueOf(shortcut.getLongLabel());
        }
        return null;
    }

    /**
     * Facebook pushes its own shortcuts at rank 0, and the platform ranks the newest push first, so
     * the Hushfacebook one ended up last. A launcher that shows three or four of them, or two next
     * to a notification, cut it off (#2). Facebook's push still goes through, and then the
     * Hushfacebook shortcut goes back in front.
     */
    @Test public void facebooksOwnPushLeavesTheHushfacebookShortcutFirst() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        demote(context, manager, 3, "Hushfacebook settings");

        SettingsEntry.pushDynamicShortcut(manager, facebookShortcut(context, "notifications"));
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();

        assertNotNull("Facebook's own push was lost", shortcut(manager, "notifications"));
        org.junit.Assert.assertEquals("the Hushfacebook shortcut stayed behind Facebook's",
                0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    /** An update of Facebook's shortcuts can rank them again too, and its answer is Facebook's. */
    @Test public void facebooksUpdateKeepsItsAnswerAndTheHushfacebookShortcutFirst() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        manager.pushDynamicShortcut(facebookShortcut(context, "friends"));
        demote(context, manager, 1, "Hushfacebook settings");

        boolean updated = SettingsEntry.updateShortcuts(manager,
                java.util.Collections.singletonList(facebookShortcut(context, "friends")));
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();

        assertTrue("Facebook's update answered false", updated);
        org.junit.Assert.assertEquals(0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    /** A call that replaces every dynamic shortcut took the Hushfacebook one with it. */
    @Test public void facebookReplacingItsShortcutsPublishesTheHushfacebookOneAgain() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);

        boolean set = SettingsEntry.setDynamicShortcuts(manager,
                java.util.Collections.singletonList(facebookShortcut(context, "reels")));
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();

        assertTrue("Facebook's replacement answered false", set);
        assertNotNull("Facebook's own shortcut was lost", shortcut(manager, "reels"));
        android.content.pm.ShortcutInfo ours = shortcut(manager, SettingsEntry.SHORTCUT_ID);
        assertNotNull("the Hushfacebook shortcut stayed gone", ours);
        org.junit.Assert.assertEquals(0, ours.getRank());
        org.junit.Assert.assertEquals("Hushfacebook settings", String.valueOf(ours.getLongLabel()));
    }

    /** Each start checks the place as well as the label, so a shortcut left behind comes back first. */
    @Test public void theNextStartPutsAShortcutLeftBehindBackInFront() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        demote(context, manager, 2, "Hushfacebook settings");

        SettingsEntry.publishShortcutNow(context);

        org.junit.Assert.assertEquals(0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
        org.junit.Assert.assertEquals(1, manager.getDynamicShortcuts().size());
    }

    /**
     * The process Facebook pushes from may not have Facebook's language yet, so moving the shortcut
     * back keeps the label it has. Relabelled in the phone's language there, it flipped between
     * the two languages each time Facebook pushed.
     */
    @Test public void movingTheShortcutBackKeepsItsLabel() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        String german = app.morphe.extension.shared.L10nTablesForTests.of("de").get("Hushfacebook settings");
        demote(context, manager, 3, german);

        SettingsEntry.keepFirstNow(context);

        org.junit.Assert.assertEquals(german, longLabel(manager));
        org.junit.Assert.assertEquals(0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    /**
     * Picking an app icon turns Facebook's launcher entry off and the icon's alias on. The shortcut
     * still named the entry, so a tap on it started nothing (#119). The next start pushes it again
     * naming the icon's alias, and a notification's open-setting intent names it too.
     */
    @Test public void anIconChangeMovesTheShortcutToTheLauncherEntryThatsOn() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        android.content.pm.PackageManager packages = context.getPackageManager();
        android.content.ComponentName entry = new android.content.ComponentName(context.getPackageName(),
                app.morphe.extension.facebook.misc.AppIcons.LAUNCHER);
        android.content.ComponentName icon = new android.content.ComponentName(context.getPackageName(),
                app.morphe.extension.facebook.misc.AppIcons.LAUNCHER + ".vaporwave_ic");
        android.content.IntentFilter main = new android.content.IntentFilter(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.ComponentName launcher : new android.content.ComponentName[] {entry, icon}) {
            org.robolectric.Shadows.shadowOf(packages).addActivityIfNotPresent(launcher);
            org.robolectric.Shadows.shadowOf(packages).addIntentFilterForActivity(launcher, main);
        }
        int on = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        int off = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        packages.setComponentEnabledSetting(icon, off, android.content.pm.PackageManager.DONT_KILL_APP);
        SettingsEntry.publishShortcutNow(context);
        org.junit.Assert.assertEquals(entry, shortcut(manager, SettingsEntry.SHORTCUT_ID).getIntent().getComponent());

        packages.setComponentEnabledSetting(icon, on, android.content.pm.PackageManager.DONT_KILL_APP);
        packages.setComponentEnabledSetting(entry, off, android.content.pm.PackageManager.DONT_KILL_APP);
        SettingsEntry.publishShortcutNow(context);

        Intent opens = shortcut(manager, SettingsEntry.SHORTCUT_ID).getIntent();
        org.junit.Assert.assertEquals("the shortcut still named the entry that's off", icon, opens.getComponent());
        assertTrue(opens.getBooleanExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, false));
        org.junit.Assert.assertEquals("one shortcut, pushed again, not two", 1, manager.getDynamicShortcuts().size());
        org.junit.Assert.assertEquals(icon,
                SettingsEntry.settingIntent(context, Settings.DOWNLOAD_COMPATIBLE.key).getComponent());
    }

    /**
     * Android disables the shortcuts tied to the launcher entry an icon change turns off, and
     * Facebook keeps running, so the Hushfacebook one stayed gone until the next cold start (#119).
     * Facebook's own PACKAGE_CHANGED naming the launcher entry puts it back after Android has had
     * its turn; one naming another component, or another app, leaves the shortcuts alone.
     */
    @Test public void aLauncherChangePublishesTheShortcutAgainWithoutARestart() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        String pkg = context.getPackageName();
        SettingsEntry.watchLauncherChanges(context);
        manager.removeAllDynamicShortcuts();

        context.sendBroadcast(packageChanged("package:" + pkg, pkg + ".SomeService"));
        context.sendBroadcast(packageChanged("package:com.example.other", app.morphe.extension.facebook.misc.AppIcons.LAUNCHER));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(SettingsEntry.LAUNCHER_CHANGE_DELAY_MS));
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        assertNull("another component's change", shortcut(manager, SettingsEntry.SHORTCUT_ID));

        context.sendBroadcast(packageChanged("package:" + pkg, app.morphe.extension.facebook.misc.AppIcons.LAUNCHER,
                app.morphe.extension.facebook.misc.AppIcons.LAUNCHER + ".vaporwave_ic"));
        ShadowLooper.idleMainLooper();
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        assertNull("not before Android has disabled the old ones", shortcut(manager, SettingsEntry.SHORTCUT_ID));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(SettingsEntry.LAUNCHER_CHANGE_DELAY_MS));
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        assertNotNull("published again on the launcher change", shortcut(manager, SettingsEntry.SHORTCUT_ID));
        assertTrue("the whole package", SettingsEntry.namesLauncher(pkg, packageChanged("package:" + pkg, pkg)));
    }

    private static Intent packageChanged(String data, String... components) {
        return new Intent(Intent.ACTION_PACKAGE_CHANGED, android.net.Uri.parse(data))
                .putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, components);
    }

    /** What the platform leaves after Facebook's pushes rank ahead of the Hushfacebook shortcut. */
    private static void demote(android.content.Context context, android.content.pm.ShortcutManager manager,
                               int rank, String label) {
        manager.pushDynamicShortcut(new android.content.pm.ShortcutInfo.Builder(context, SettingsEntry.SHORTCUT_ID)
                .setShortLabel("Hushfacebook")
                .setLongLabel(label)
                .setIntent(new Intent(Intent.ACTION_VIEW))
                .setRank(rank)
                .build());
        org.junit.Assert.assertEquals(rank, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    private static android.content.pm.ShortcutInfo facebookShortcut(android.content.Context context, String id) {
        return new android.content.pm.ShortcutInfo.Builder(context, id)
                .setShortLabel(id)
                .setIntent(new Intent(Intent.ACTION_VIEW))
                .setRank(0)
                .build();
    }

    private static android.content.pm.ShortcutInfo shortcut(android.content.pm.ShortcutManager manager, String id) {
        for (android.content.pm.ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
            if (id.equals(shortcut.getId())) return shortcut;
        }
        return null;
    }

    /**
     * Signed out, the shortcut's screen lands on the login screen, and Facebook replaces that with
     * its logged-out screen a moment later. Android resumes the replacement before it destroys the
     * login screen, so the replacement is already in front when the request comes back, and it
     * never resumes again to pick it up. On a phone that left the request pending until it expired.
     */
    @Test public void repeatedDirectOpensBeforeTheFragmentCommitKeepOneSettingsDialog() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            assertTrue(SettingsEntry.open(activity));
            assertTrue(SettingsEntry.open(activity));

            assertOneSettingsDialog(activity);
        }
    }

    @Test public void repeatedMenuTapsBeforeTheFragmentCommitKeepOneSettingsDialog() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            View menuRow = new View(activity);
            assertTrue(MenuSettingsRow.onTap(menuRow, MenuSettingsRow.ROW_ID));
            assertTrue(MenuSettingsRow.onTap(menuRow, MenuSettingsRow.ROW_ID));

            assertOneSettingsDialog(activity);
        }
    }

    private static void assertOneSettingsDialog(Activity activity) {
        activity.getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        int count = 0;
        for (Fragment fragment : activity.getFragmentManager().getFragments()) {
            if (fragment instanceof SettingsDialog) count++;
        }
        assertEquals("the two requests stacked settings screens", 1, count);
        assertNotNull(dialogOver(activity));
    }

    @Test public void theScreenFollowsItsHostToAReplacementAlreadyInFront() {
        ActivityController<Activity> login = openedOverNewActivity();

        ActivityController<Activity> loggedOut = replace(login);

        assertNotNull("the screen was lost when its host went away", dialogOver(loggedOut.get()));
    }

    @Test public void aScreenThePersonClosedStaysClosedWhenItsHostGoesAway() {
        ActivityController<Activity> login = openedOverNewActivity();
        SettingsEntry.onClosedByUser();

        ActivityController<Activity> loggedOut = replace(login);

        assertNull("a screen the person closed came back", dialogOver(loggedOut.get()));
    }

    /**
     * The three ways a person leaves the screen (the title bar's arrow, the Back key and Back on the
     * recovery page) have to tell the entry so, or the screen follows its host to the next Facebook
     * screen as if nobody had closed it. The request is still inside its window here, so a close
     * the entry didn't hear about would reopen it.
     */
    @Test public void backOnTheRecoveryPageKeepsTheScreenClosedWhenItsHostGoesAway() {
        HushfacebookPreferenceFragment.failNextInitialization = new IllegalStateException("injected");
        ActivityController<Activity> login = openedOverNewActivity();
        android.preference.Preference back = pageOver(login.get()).findPreference("morphe_settings_error_back");
        assertNotNull("no recovery page", back);

        back.getOnPreferenceClickListener().onPreferenceClick(back);
        ShadowLooper.idleMainLooper();
        assertNull("Back left the screen open", dialogOver(login.get()));
        ActivityController<Activity> loggedOut = replace(login);

        assertNull("the screen came back after the person left it with Back", dialogOver(loggedOut.get()));
    }

    @Test public void theBackKeyKeepsTheScreenClosedWhenItsHostGoesAway() {
        ActivityController<Activity> login = openedOverNewActivity();

        ((SettingsDialog) dialogOver(login.get())).getDialog().onBackPressed();
        ShadowLooper.idleMainLooper();
        assertNull("the Back key left the screen open", dialogOver(login.get()));
        ActivityController<Activity> loggedOut = replace(login);

        assertNull("the screen came back after the person closed it with the Back key", dialogOver(loggedOut.get()));
    }

    /** The arrow dismisses rather than cancels, so it has to tell the entry itself. */
    @Test public void theTitleBarArrowKeepsTheScreenClosedWhenItsHostGoesAway() {
        ActivityController<Activity> login = openedOverNewActivity();
        SettingsDialog dialog = (SettingsDialog) dialogOver(login.get());
        ArrayList<View> labelled = new ArrayList<>();
        dialog.getView().findViewsWithText(labelled, "Back", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        View arrow = null;
        for (View view : labelled) {
            if (view instanceof android.widget.ImageButton && ((android.widget.ImageButton) view).getDrawable() != null) arrow = view;
        }
        assertNotNull("no arrow in the title bar among " + labelled, arrow);

        assertTrue("the arrow didn't take the tap", arrow.performClick());
        ShadowLooper.idleMainLooper();
        assertNull("the arrow left the screen open", dialogOver(login.get()));
        ActivityController<Activity> loggedOut = replace(login);

        assertNull("the screen came back after the person closed it with the arrow", dialogOver(loggedOut.get()));
    }

    /**
     * The restart row restarts Facebook with a marker naming its page. The next start takes the
     * marker, the same way onApplicationCreate does, and the screen opens over the first Facebook
     * screen to resume, on that page. Closing it drops the page, so a later open starts at the top.
     */
    @Test public void aStartAfterTheRestartRowOpensTheScreenOnItsPage() {
        android.content.SharedPreferences store = app.morphe.extension.shared.settings.Setting.preferences.preferences;
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        try {
            assertFalse("a start without a marker asked for the screen",
                    ReopenSettingsAfterRestart.reopenIfPending(SettingsEntry::reopenOn));
            ReopenSettingsAfterRestart.remember(store, "Links", android.os.SystemClock.elapsedRealtime());
            assertTrue(ReopenSettingsAfterRestart.reopenIfPending(SettingsEntry::reopenOn));
            assertFalse("the marker outlived the start that read it", store.contains("hush_reopen_settings_page"));

            ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
            ShadowLooper.idleMainLooper();
            SettingsDialog dialog = (SettingsDialog) dialogOver(activity.get());
            assertNotNull("the restart didn't open the screen again", dialog);
            org.junit.Assert.assertEquals("Links", dialog.startPage());
            ListView list = pageOver(activity.get()).getView().findViewById(android.R.id.list);
            boolean onLinks = false;
            for (int i = 0; i < list.getCount(); i++) {
                Object row = list.getItemAtPosition(i);
                if (row instanceof Preference
                        && Settings.OPEN_LINKS_EXTERNALLY.key.equals(((Preference) row).getKey())) onLinks = true;
            }
            assertTrue("the screen didn't open on Links", onLinks);
            assertTrue(pageOver(activity.get()).navigation.isExpanded("Links"));

            SettingsL10nTest.backOf(dialog).performClick();
            ShadowLooper.idleMainLooper();
            assertNull("the screen stayed open", dialogOver(activity.get()));
            assertNull("closing the screen kept its page for the next open", SettingsEntry.requestedPage());
        } finally {
            SettingsEntry.onClosedByUser();
            PatchFamily.inBuildForTests = null;
        }
    }

    /**
     * A note's button opens the screen at the setting it names: after a save WhatsApp may refuse,
     * Save videos other apps can open on the Downloads page. Asked once, it isn't asked again.
     */
    @Test public void aRequestForASettingOpensTheScreenAtItsRow() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        Intent asked = SettingsEntry.settingIntent(RuntimeEnvironment.getApplication(), Settings.DOWNLOAD_COMPATIBLE.key);
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class, asked).create();
        SettingsEntry.onActivityCreate(activity.get());
        activity.start().resume();
        ShadowLooper.idleMainLooper();
        List<String> titles = titles(pageOver(activity.get()));
        assertTrue(titles.toString(), titles.contains("Save videos other apps can open"));
        assertTrue(titles.toString(), titles.contains("Opening Facebook"));
        assertTrue(pageOver(activity.get()).navigation.isExpanded("Downloads"));
        assertNull("the request was kept for the next screen", SettingsEntry.takeRequestedSetting());
    }

    /** The break cover's Break settings button: the screen opens at Take breaks, not at the top. */
    @Test public void anOpenAtARowLandsOnIt() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).create().start().resume();
        assertTrue(SettingsEntry.open(activity.get(), Settings.TAKE_BREAKS.key));
        ShadowLooper.idleMainLooper();
        List<String> titles = titles(pageOver(activity.get()));
        assertTrue(titles.toString(), titles.contains("Take breaks"));
        assertNull("the request was kept for the next screen", SettingsEntry.takeRequestedSetting());
    }

    /** An open at a row over a screen that's going away keeps no row for the next open. */
    @Test public void anOpenAtARowThatCannotShowKeepsNoRow() {
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).create();
        activity.get().finish();
        assertFalse(SettingsEntry.open(activity.get(), Settings.TAKE_BREAKS.key));
        assertNull("a later open would land on the row", SettingsEntry.takeRequestedSetting());
    }

    /** A screen that's already up goes to the row too, rather than staying where it was. */
    @Test public void aRequestForASettingMovesAScreenAlreadyOpen() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        ActivityController<Activity> activity = openedOverNewActivity();
        HushfacebookPreferenceFragment page = pageOver(activity.get());
        assertTrue(titles(page).contains("Downloads"));
        assertFalse(page.navigation.isExpanded("Downloads"));
        page.navigation.navigate("Playback");
        SettingsEntry.onNewIntent(activity.get(),
                SettingsEntry.settingIntent(RuntimeEnvironment.getApplication(), Settings.DOWNLOAD_COMPATIBLE.key));
        activity.pause().resume();
        ShadowLooper.idleMainLooper();
        List<String> titles = titles(pageOver(activity.get()));
        assertTrue(titles.toString(), titles.contains("Save videos other apps can open"));
        assertTrue(pageOver(activity.get()).navigation.isExpanded("Downloads"));
        assertFalse("the section open before stayed open (#368)", pageOver(activity.get()).navigation.isExpanded("Playback"));
        assertNull("the request was kept for the next screen", SettingsEntry.takeRequestedSetting());
    }

    /** The launcher entry is open to every app: a request that isn't a key's shape opens the overview. */
    @Test public void aRequestThatIsNoKeyOpensTheOverview() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        Intent odd = SettingsEntry.settingIntent(RuntimeEnvironment.getApplication(), "Save videos\nFAKE LINE");
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class, odd).create();
        SettingsEntry.onActivityCreate(activity.get());
        activity.start().resume();
        ShadowLooper.idleMainLooper();
        HushfacebookPreferenceFragment page = pageOver(activity.get());
        ListView list = page.getView().findViewById(android.R.id.list);
        org.junit.Assert.assertEquals(24, list.getCount());
        org.junit.Assert.assertEquals("section_Opening Facebook", ((Preference) list.getItemAtPosition(1)).getKey());
        assertFalse(page.navigation.isExpanded("Downloads"));
        assertFalse(titles(pageOver(activity.get())).contains("More settings"));
        String report = app.morphe.extension.shared.settings.preference.LogBufferManager.buildExportText();
        assertFalse(report, report.contains("FAKE LINE"));
    }

    /**
     * The settings patch puts three calls into Facebook's own frames: its application's start and
     * every screen's onCreate and onNewIntent. An Error under them, from a Hushfacebook class that
     * fails to load or set itself up, has to stop there as an exception does. Caught as an
     * Exception only, it ended Facebook at every start or screen, and Pause couldn't help, since
     * these calls run paused too.
     */
    @Test public void anErrorUnderTheStartAndScreenHooksStaysOutOfFacebook() throws Exception {
        FailingApplication failing = new FailingApplication(RuntimeEnvironment.getApplication());
        SettingsEntry.onApplicationCreate(failing);
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        assertTrue("the start never asked for the lifecycle watch, so nothing threw", failing.asked > 0);

        int[] read = {0};
        Intent broken = new Intent() {
            @Override public String getAction() {
                read[0]++;
                throw new NoClassDefFoundError("a class missing from the build");
            }
        };
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).create();
        activity.get().setIntent(broken);
        SettingsEntry.onActivityCreate(activity.get());
        SettingsEntry.onNewIntent(activity.get(), broken);
        org.junit.Assert.assertEquals("both screen hooks read the intent", 2, read[0]);
    }

    /** An application whose lifecycle watch can't be set up: asking throws an Error, not an exception. */
    private static final class FailingApplication extends android.app.Application {
        int asked;

        FailingApplication(android.content.Context base) {
            attachBaseContext(base);
        }

        @Override
        public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callbacks) {
            asked++;
            throw new NoClassDefFoundError("a class missing from the build");
        }
    }

    private static List<String> titles(HushfacebookPreferenceFragment page) {
        ListView list = page.getView().findViewById(android.R.id.list);
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < list.getCount(); i++) titles.add(String.valueOf(((Preference) list.getItemAtPosition(i)).getTitle()));
        return titles;
    }

    @SuppressWarnings("deprecation")
    private static HushfacebookPreferenceFragment pageOver(Activity activity) {
        SettingsDialog dialog = (SettingsDialog) dialogOver(activity);
        assertNotNull("no screen over " + activity, dialog);
        return (HushfacebookPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
    }

    /** An activity started by the launcher shortcut, with the screen open over it. */
    private static ActivityController<Activity> openedOverNewActivity() {
        Intent shortcut = new Intent().putExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, true);
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class, shortcut).create();
        SettingsEntry.onActivityCreate(activity.get());
        activity.start().resume();
        ShadowLooper.idleMainLooper();
        assertNotNull("the screen did not open over the shortcut's activity", dialogOver(activity.get()));
        return activity;
    }

    /** The order Android uses when an activity starts the next one and finishes. */
    private static ActivityController<Activity> replace(ActivityController<Activity> current) {
        current.pause();
        ActivityController<Activity> next = Robolectric.buildActivity(Activity.class).create().start().resume();
        current.stop().destroy();
        ShadowLooper.idleMainLooper();
        return next;
    }

    private static Object dialogOver(Activity activity) {
        return activity.getFragmentManager().findFragmentByTag("hushfacebook_settings");
    }
}
