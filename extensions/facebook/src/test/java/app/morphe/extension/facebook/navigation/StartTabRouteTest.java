/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;

import com.facebook.api.feedtype.FeedType;
import com.facebook.feed.feedstab.tab.FeedsTab;
import com.facebook.feed.fragment.FeedFiltersFragment;
import com.facebook.feed.tab.FeedTab;
import com.facebook.friending.tab.FriendRequestsTab;
import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.katana.activity.FbMainTabActivityDelegate;
import com.facebook.marketplace.tab.MarketplaceTab;
import com.facebook.video.videohome.tab.WatchTab;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The hook first thing in the main screen's creation: a start from the launcher icon gets a copy of
 * its intent that asks Facebook for the chosen tab, and every other start keeps its own destination.
 * Then the check a moment after the screen shows, which reads what Facebook opened.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StartTabRouteTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void optIn() {
        Settings.OPEN_ON_CHOSEN_TAB.save(true);
    }

    @After
    public void restore() {
        StartTabRoute.settled();
        StartTabRoute.failNextStartUpHook = null;
        FeedsSubtabRoute.disarm();
        PauseForTests.resume();
        HiddenTabs.inBuildForTests = null;
        HiddenTabs.clearForTests();
        Settings.EVENTS_ONLY.resetToDefault();
        MarketplaceOnlyForTests.inBuild(null);
        Settings.HIDE_FEEDS_TAB.resetToDefault();
        Settings.HIDE_FRIENDS_TAB.resetToDefault();
        Settings.FEEDS_SUBTAB.resetToDefault();
        Settings.OPEN_ON_CHOSEN_TAB.resetToDefault();
        Settings.START_TAB.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.START_TAB + ":")) return line;
        }
        return null;
    }

    /** The main screen started by [intent], after the hook has seen its creation with [saved]. */
    private static FbMainTabActivity created(Intent intent, Bundle saved) {
        FbMainTabActivity screen = StartTabRouteForTests.screen(intent);
        StartTabRoute.onActivityCreate(screen, saved);
        return screen;
    }

    @Test
    public void eventsOnlyAsksForTheEventsTabWhateverTabIsChosen() {
        MarketplaceOnlyForTests.inBuild(Boolean.TRUE);
        Settings.OPEN_ON_CHOSEN_TAB.save(false);
        Settings.START_TAB.save(StartTab.FRIENDS);
        Settings.EVENTS_ONLY.save(true);
        FbMainTabActivity screen = created(StartTabRouteForTests.launcherStart(), null);
        assertEquals(FacebookTabs.EVENTS_ID, screen.getIntent().getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));
        assertEquals("events", screen.getIntent().getStringExtra(StartTabRoute.ROUTED));

        // A link keeps its own destination.
        Intent link = StartTabRouteForTests.launcherStart().setData(android.net.Uri.parse("https://www.facebook.com/events/1/"));
        assertEquals(-1, created(link, null).getIntent().getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));
    }

    @Test
    public void anOptedInLauncherStartAsksForTheChosenMarketplaceTab() {
        assertTrue("the person opted in", Settings.OPEN_ON_CHOSEN_TAB.get());
        assertEquals(StartTab.MARKETPLACE, Settings.START_TAB.get());
        FbMainTabActivity screen = StartTabRouteForTests.screen(StartTabRouteForTests.launcherStart());
        Intent launched = screen.getIntent();

        StartTabRoute.onActivityCreate(screen, null);

        Intent routed = screen.getIntent();
        assertNotSame("the screen kept the intent it was started with", launched, routed);
        assertEquals(FacebookTabs.MARKETPLACE_ID, routed.getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));
        assertEquals("marketplace", routed.getStringExtra(StartTabRoute.ROUTED));
        assertEquals(Intent.ACTION_MAIN, routed.getAction());
        assertTrue(routed.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(launched.getFlags(), routed.getFlags());
        assertEquals(launched.getComponent(), routed.getComponent());
        // Facebook's start-up prediction may be reading the first one on another thread.
        assertFalse("the intent the screen was started with was changed", launched.hasExtra(FacebookTabs.TARGET_TAB_ID));
        assertEquals(FamilyNames.START_TAB + ": invoked 1, 0 found, 0 missing", statusLine());
    }

    @Test
    public void eachTabAsksForItsOwnId() {
        for (StartTab tab : StartTab.values()) {
            Settings.START_TAB.save(tab);
            FbMainTabActivity screen = created(StartTabRouteForTests.launcherStart(), null);
            assertEquals(tab.name(), tab.tabId, screen.getIntent().getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));
        }
        assertEquals(FacebookTabs.HOME_ID, StartTab.HOME.tabId);
        assertEquals(FacebookTabs.FEEDS_ID, StartTab.FEEDS.tabId);
        assertEquals(FacebookTabs.VIDEO_ID, StartTab.VIDEO.tabId);
        assertEquals(FacebookTabs.FRIENDS_ID, StartTab.FRIENDS.tabId);
        assertEquals(FacebookTabs.MARKETPLACE_ID, StartTab.MARKETPLACE.tabId);
        assertEquals(FacebookTabs.NOTIFICATIONS_ID, StartTab.NOTIFICATIONS.tabId);
        assertEquals(FacebookTabs.MENU_ID, StartTab.MENU.tabId);
    }

    /**
     * A link, a notification, a shortcut and a restored screen each keep where they were going:
     * the screen keeps the very intent it was started with.
     */
    @Test
    public void everyOtherStartKeepsItsOwnDestination() {
        Intent notification = StartTabRouteForTests.launcherStart()
                .putExtra(FacebookTabs.TARGET_TAB_ID, FacebookTabs.NOTIFICATIONS_ID);
        Intent[] others = {
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/marketplace/item/1/")),
                StartTabRouteForTests.launcherStart().setData(Uri.parse("fb://feed")),
                notification,
                StartTabRouteForTests.launcherStart().putExtra("tabbar_target_intent", new Intent()),
                StartTabRouteForTests.launcherStart().putExtra("extra_launch_uri", "fb://notifications"),
                StartTabRouteForTests.launcherStart().putExtra("target_fragment", 7),
                StartTabRouteForTests.launcherStart().putExtra("fragment_type", 7),
                new Intent(Intent.ACTION_MAIN),
                new Intent(Intent.ACTION_VIEW).putExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, true),
        };
        for (Intent intent : others) {
            FbMainTabActivity screen = created(intent, null);
            assertSame(intent.toString(), intent, screen.getIntent());
            assertFalse(intent.toString(), screen.getIntent().hasExtra(StartTabRoute.ROUTED));
        }
        assertEquals("a notification's own tab was replaced", FacebookTabs.NOTIFICATIONS_ID,
                notification.getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));

        // Android restoring the screen, after a rotation or with the process gone: Facebook puts
        // back what was on screen.
        Intent launcher = StartTabRouteForTests.launcherStart();
        FbMainTabActivity restored = created(launcher, new Bundle());
        assertSame(launcher, restored.getIntent());

        assertEquals(FamilyNames.START_TAB + ": invoked " + (others.length + 1) + ", 0 found, 0 missing", statusLine());
    }

    @Test
    public void onlyTheMainScreenIsTouched() {
        Intent launcher = StartTabRouteForTests.launcherStart();
        Activity other = Robolectric.buildActivity(Activity.class, launcher).get();
        StartTabRoute.onActivityCreate(other, null);
        assertSame(launcher, other.getIntent());
        assertNull("another activity counted as the main screen", statusLine());
        StartTabRoute.onActivityCreate(null, null);
        assertNull(statusLine());
    }

    @Test
    public void offOrPausedFacebookOpensWhereItChooses() {
        Settings.OPEN_ON_CHOSEN_TAB.save(false);
        Intent launcher = StartTabRouteForTests.launcherStart();
        assertSame(launcher, created(launcher, null).getIntent());

        Settings.OPEN_ON_CHOSEN_TAB.save(true);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            Intent paused = StartTabRouteForTests.launcherStart();
            assertSame(why.name(), paused, created(paused, null).getIntent());
        }
        PauseForTests.resume();
        assertTrue(StartTabRouteForTests.routes());
    }

    @Test
    public void beforeTheSettingsAreReadyTheStartIsFacebooks() {
        boolean[] routed = {true};
        SettingsContextRule.withoutContext(() -> routed[0] = StartTabRouteForTests.routes());
        assertFalse(routed[0]);
        SettingsContextRule.beforeThePauseIsDecided(() -> routed[0] = StartTabRouteForTests.routes());
        assertFalse(routed[0]);
        assertTrue(StartTabRouteForTests.routes());
    }

    /** A failure in the hook leaves Facebook's start as it was and says so in Hook status. */
    @Test
    public void aFailureLeavesTheStartAlone() {
        FbMainTabActivity screen = StartTabRouteForTests.screen(StartTabRouteForTests.launcherStart());
        screen.failGetIntent = true;
        StartTabRoute.onActivityCreate(screen, null);
        screen.failGetIntent = false;
        assertFalse(screen.getIntent().hasExtra(FacebookTabs.TARGET_TAB_ID));
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.startsWith(FamilyNames.START_TAB + ": invoked 1, 0 found, 1 missing"));
        assertTrue(line, line.contains("'start tab' hook (it threw java.lang.IllegalStateException)"));
    }

    /** Whether Facebook's start-up would be told to use the tab a start asked for, at both of its checks. */
    private static boolean startUpAsksForTheTab() {
        boolean position = StartTabRoute.startOnAskedTab(false);
        boolean kept = StartTabRoute.keepAskedStartTab(false);
        assertEquals("the two checks disagree", position, kept);
        return position;
    }

    /**
     * Facebook's start-up hands the screen a sanitized copy of its intent; what the screen ends up
     * with once the hook has handed it over.
     */
    private static Intent handedOver(Activity screen, Intent sanitized) {
        StartTabRoute.setSanitizedIntent(screen, sanitized);
        return screen.getIntent();
    }

    /** What Facebook's sanitizing step builds for a start from another app: the action and the link. */
    private static Intent sanitizedCopy(Intent intent) {
        return new Intent(intent.getAction(), intent.getData());
    }

    @Test
    public void aStartFromTheLauncherIconIsHelpedThroughFacebooksStartUp() {
        FbMainTabActivity screen = created(StartTabRouteForTests.launcherStart(), null);
        assertTrue(startUpAsksForTheTab());

        // The step drops every extra of a start another app sent; the tab goes back in.
        Intent sanitized = sanitizedCopy(screen.getIntent());
        Intent kept = handedOver(screen, sanitized);
        assertNotSame(sanitized, kept);
        assertEquals(FacebookTabs.MARKETPLACE_ID, kept.getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));
        assertEquals("marketplace", kept.getStringExtra(StartTabRoute.ROUTED));
        assertEquals(Intent.ACTION_MAIN, kept.getAction());
        assertFalse("Facebook's own copy was changed", sanitized.hasExtra(FacebookTabs.TARGET_TAB_ID));

        // A copy that still carries a tab is Facebook's to keep.
        Intent carries = sanitizedCopy(screen.getIntent()).putExtra(FacebookTabs.TARGET_TAB_ID, FacebookTabs.MENU_ID);
        assertSame(carries, handedOver(screen, carries));

        // Each check that helped, once, as found; the screen's creation, once, as invoked.
        assertEquals(FamilyNames.START_TAB + ": invoked 1, 3 found, 0 missing", statusLine());
    }

    @Test
    public void facebooksOwnAnswersPassThroughWhenNoStartIsBeingHelped() {
        assertFalse(StartTabRoute.startOnAskedTab(false));
        assertTrue(StartTabRoute.startOnAskedTab(true));
        assertFalse(StartTabRoute.keepAskedStartTab(false));
        assertTrue(StartTabRoute.keepAskedStartTab(true));
        FbMainTabActivity screen = StartTabRouteForTests.screen(StartTabRouteForTests.launcherStart());
        Intent sanitized = sanitizedCopy(screen.getIntent());
        assertSame(sanitized, handedOver(screen, sanitized));
        assertNull("a check that changed nothing was counted", statusLine());

        // While a start is helped, Facebook's yes stays yes.
        created(StartTabRouteForTests.launcherStart(), null);
        assertTrue(StartTabRoute.startOnAskedTab(true));
        assertTrue(StartTabRoute.keepAskedStartTab(true));
    }

    /** Links, notifications, shortcuts, restored screens, the switch off and a pause: Facebook's start-up is left alone. */
    @Test
    public void onlyAPlainLauncherStartIsHelped() {
        Intent[] others = {
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/marketplace/item/1/")),
                StartTabRouteForTests.launcherStart().putExtra(FacebookTabs.TARGET_TAB_ID, FacebookTabs.NOTIFICATIONS_ID),
                StartTabRouteForTests.launcherStart().putExtra("tabbar_target_intent", new Intent()),
                StartTabRouteForTests.launcherStart().setData(Uri.parse("fb://feed")),
        };
        for (Intent intent : others) {
            FbMainTabActivity screen = created(intent, null);
            assertFalse(intent.toString(), startUpAsksForTheTab());
            Intent sanitized = sanitizedCopy(intent);
            assertSame(intent.toString(), sanitized, handedOver(screen, sanitized));
        }
        created(StartTabRouteForTests.launcherStart(), new Bundle());
        assertFalse("a restored screen", startUpAsksForTheTab());

        Settings.OPEN_ON_CHOSEN_TAB.save(false);
        created(StartTabRouteForTests.launcherStart(), null);
        assertFalse("the switch is off", startUpAsksForTheTab());
        Settings.OPEN_ON_CHOSEN_TAB.save(true);

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        created(StartTabRouteForTests.launcherStart(), null);
        assertFalse("paused", startUpAsksForTheTab());
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> created(StartTabRouteForTests.launcherStart(), null));
        assertFalse("before the settings are ready", startUpAsksForTheTab());

        // Another activity isn't a main screen, and the step only hands the main screen its intent.
        created(StartTabRouteForTests.launcherStart(), null);
        Activity other = Robolectric.buildActivity(Activity.class, StartTabRouteForTests.launcherStart()).get();
        Intent sanitized = sanitizedCopy(other.getIntent());
        assertSame(sanitized, handedOver(other, sanitized));
    }

    /** A later main screen that isn't helped ends the help an earlier one was waiting on. */
    @Test
    public void aNewMainScreenEndsTheStartBefore() {
        created(StartTabRouteForTests.launcherStart(), null);
        assertTrue(startUpAsksForTheTab());
        created(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/")), null);
        assertFalse(startUpAsksForTheTab());
    }

    /**
     * A cold start as Facebook runs it: the main screen's onCreate goes to a stand-in that queues
     * Facebook's own creation behind a splash screen, so Android creates and resumes the screen
     * first, and the start-up steps run later, once the app is ready. The help lasts until then.
     */
    @Test
    public void theHelpOutlastsTheSplashScreen() {
        ActivityController<FbMainTabActivity> controller =
                Robolectric.buildActivity(FbMainTabActivity.class, StartTabRouteForTests.launcherStart());
        FbMainTabActivity screen = controller.get();
        StartTabRoute.onActivityCreate(screen, null);
        // Android shows the splash: created, started, resumed, and the landing check's first try.
        controller.create().start().resume().visible();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StartTabRoute.LANDING_CHECK_MS));
        assertNull("the landing check read a screen Facebook hadn't built", foundLine());

        // Then Facebook's queued creation: the sanitizing step, the tab bar, the check.
        Intent kept = handedOver(screen, sanitizedCopy(screen.getIntent()));
        assertEquals(FacebookTabs.MARKETPLACE_ID, kept.getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));
        assertTrue("the help ended when the splash showed", startUpAsksForTheTab());

        // Built, the landing check reads it and the help ends.
        StartTabRouteForTests.tabBar(screen, StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()), null);
        screen.currentTab = new MarketplaceTab();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StartTabRoute.LANDING_CHECK_MS));
        assertEquals(FamilyNames.START_TAB + ": invoked 1, 4 found, 0 missing", statusLine());
        assertFalse(startUpAsksForTheTab());
    }

    /**
     * A screen Facebook never builds is checked all the same after the last try, and the help ends
     * there: a tab bar Facebook builds later, or builds again when it reloads its tabs, gets
     * Facebook's own answers. With no tab on the screen, the tab bar state is left for Facebook to
     * ask for first.
     */
    @Test
    public void theLandingCheckGivesUpOnAScreenThatIsNeverBuilt() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        ActivityController<FbMainTabActivity> controller =
                Robolectric.buildActivity(FbMainTabActivity.class, StartTabRouteForTests.launcherStart());
        FbMainTabActivity screen = controller.get();
        StartTabRoute.onActivityCreate(screen, null);
        StartTabRouteForTests.TabBarState state = new StartTabRouteForTests.TabBarState();
        state.shown = new java.util.ArrayList<>(StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()));
        StartTabRouteForTests.Lazy unasked = new StartTabRouteForTests.Lazy(state, false);
        FbMainTabActivityDelegate delegate = new FbMainTabActivityDelegate();
        delegate.tabBarStateManager$delegate = unasked;
        screen.delegate = delegate;

        controller.create().start().resume().visible();
        shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(StartTabRoute.LANDING_CHECK_MS * (StartTabRoute.LANDING_ATTEMPTS - 1)));
        assertEquals("it stopped waiting early", FamilyNames.START_TAB + ": invoked 1, 0 found, 0 missing", statusLine());
        assertTrue("the help ended before the last try", startUpAsksForTheTab());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StartTabRoute.LANDING_CHECK_MS));
        String line = statusLine();
        assertTrue(line, line.contains("1 missing"));
        assertFalse("the help outlived the last try", startUpAsksForTheTab());
        Intent sanitized = sanitizedCopy(screen.getIntent());
        assertSame("the screen didn't get Facebook's copy", sanitized, handedOver(screen, sanitized));
        assertFalse("the landing check built the tab bar state ahead of Facebook", unasked.isInitialized());

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Start tab: gave up waiting for the main screen to be built after "
                + StartTabRoute.LANDING_ATTEMPTS + " tries; Facebook's start-up keeps its own answers from here on."));
        assertTrue(report, report.contains("Start tab: asked for marketplace, and the main screen shows no tab yet."));
        assertFalse(report, report.contains("Tab bar:"));
    }

    /** With Debug logging on, each start-up hook says once per start whether it had anything to do. */
    @Test
    public void eachStartUpHookLogsOneLinePerStart() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Settings.OPEN_ON_CHOSEN_TAB.save(false);
        FbMainTabActivity plain = created(StartTabRouteForTests.launcherStart(), null);
        for (int i = 0; i < 2; i++) {
            handedOver(plain, sanitizedCopy(plain.getIntent()));
            StartTabRoute.startOnAskedTab(false);
            StartTabRoute.keepAskedStartTab(true);
        }
        Settings.OPEN_ON_CHOSEN_TAB.save(true);
        FbMainTabActivity routed = created(StartTabRouteForTests.launcherStart(), null);
        for (int i = 0; i < 2; i++) {
            handedOver(routed, sanitizedCopy(routed.getIntent()));
            StartTabRoute.startOnAskedTab(false);
            StartTabRoute.keepAskedStartTab(true);
        }

        String report = LogBufferManager.buildExportText();
        String[] lines = {
                "Start tab: sanitize hook: nothing pending.",
                "Start tab: tab bar start position hook: nothing pending; Facebook's no stands.",
                "Start tab: main screen start tab hook: nothing pending; Facebook's yes stands.",
                "Start tab: sanitize hook: Facebook's start-up kept no tab in the screen's intent; asked again for marketplace.",
                "Start tab: tab bar start position hook: Facebook said no; asked it to use marketplace.",
                "Start tab: main screen start tab hook: Facebook already said yes for marketplace.",
        };
        for (String line : lines) {
            assertEquals(line + " in\n" + report, 1, occurrences(report, line));
        }
        assertTrue("the route came after the lines of the start before it",
                report.indexOf("asked Facebook to open on marketplace") > report.indexOf(lines[2]));
        assertEquals(6, occurrences(report, " hook: "));
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    @Test
    public void theHelpEndsWhenTheScreenGoesAwayUnshown() {
        ActivityController<FbMainTabActivity> controller =
                Robolectric.buildActivity(FbMainTabActivity.class, StartTabRouteForTests.launcherStart());
        StartTabRoute.onActivityCreate(controller.get(), null);
        controller.create();
        assertTrue(startUpAsksForTheTab());
        controller.destroy();
        assertFalse(startUpAsksForTheTab());
    }

    /** A failure in a start-up hook gives Facebook its own answer and intent, and says so in Hook status. */
    @Test
    public void aFailureInTheStartUpHooksLeavesFacebooksStartUpAlone() {
        FbMainTabActivity screen = created(StartTabRouteForTests.launcherStart(), null);

        StartTabRoute.failNextStartUpHook = new IllegalStateException("for this test");
        assertFalse(StartTabRoute.startOnAskedTab(false));
        StartTabRoute.failNextStartUpHook = new IllegalStateException("for this test");
        assertTrue(StartTabRoute.keepAskedStartTab(true));
        // One failure at a time: the next start-up check is helped again.
        assertTrue(StartTabRoute.keepAskedStartTab(false));

        StartTabRoute.failNextStartUpHook = new IllegalStateException("for this test");
        Intent sanitized = sanitizedCopy(screen.getIntent());
        assertSame("the screen didn't get Facebook's copy", sanitized, handedOver(screen, sanitized));

        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.startsWith(FamilyNames.START_TAB + ": invoked 1, 1 found, 3 missing"));
        assertTrue(line, line.contains("'tab bar start position' hook (it threw java.lang.IllegalStateException)"));

        // Facebook's copy no longer asks for the tab, so its later checks are its own.
        assertFalse(StartTabRoute.startOnAskedTab(false));
    }

    /** The log names what a start carried, never the link or a value, which can name a person. */
    @Test
    public void theLogNamesWhatAStartCarriedButNotItsValues() {
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/some.person/posts/123"))
                .putExtra("notification_source", "PUSH")
                .putExtra("extra_secret", "c_user=100012345678901");
        String described = StartTabRoute.describe(link);
        assertTrue(described, described.contains("action android.intent.action.VIEW"));
        assertTrue(described, described.contains("a link"));
        assertTrue(described, described.contains("extras extra_secret notification_source"));
        for (String leak : new String[]{"some.person", "123", "PUSH", "c_user", "100012345678901", "https"}) {
            assertFalse(described + " carries " + leak, described.contains(leak));
        }
        assertEquals("Intent: action android.intent.action.MAIN, categories android.intent.category.LAUNCHER, "
                        + "flags 0x10200000, no link, extras none.",
                StartTabRoute.describe(StartTabRouteForTests.launcherStart()));
        assertEquals("No intent.", StartTabRoute.describe(null));
    }

    /** With Debug logging on, the capture on a phone can see the route and the tab bar. */
    @Test
    public void debugLoggingShowsTheRouteAndTheTabBar() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        FbMainTabActivity screen = created(StartTabRouteForTests.launcherStart(), null);
        screen.currentTab = new MarketplaceTab();
        StartTabRouteForTests.tabBar(screen, StartTabRouteForTests.tabs(new FeedTab(), new FriendRequestsTab(),
                new MarketplaceTab()), null);
        StartTabRoute.check(screen, StartTab.MARKETPLACE);
        created(StartTabRouteForTests.launcherStart(), new Bundle());

        // The exported report leaves out every number as long as an account's id, so most tab ids
        // read as omitted there. Logcat keeps them, and so does Home's, which is shorter.
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Start tab: asked Facebook to open on marketplace (tab [id omitted]). "
                + "Intent: action android.intent.action.MAIN, categories android.intent.category.LAUNCHER"));
        assertTrue(report, report.contains("Start tab: Facebook opened MarketplaceTab [id omitted], as asked. "
                + "Tab bar: FeedTab " + FacebookTabs.HOME_ID + ", FriendRequestsTab [id omitted], MarketplaceTab [id omitted]"));
        assertTrue(report, report.contains("Start tab: left Facebook's own start alone: the screen is being restored."));
    }

    @Test
    public void theTabFacebookOpenedIsReadAMomentAfterTheScreenShows() {
        ActivityController<FbMainTabActivity> controller =
                Robolectric.buildActivity(FbMainTabActivity.class, StartTabRouteForTests.launcherStart());
        FbMainTabActivity screen = controller.get();
        StartTabRoute.onActivityCreate(screen, null);
        StartTabRouteForTests.tabBar(screen, StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()), null);
        screen.currentTab = new MarketplaceTab();

        // Then the screen's lifecycle, the way Android runs it after onCreate starts.
        controller.create().start().resume().visible();
        assertNull("the tab was read before the screen had shown for a moment", foundLine());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StartTabRoute.LANDING_CHECK_MS));
        assertEquals(FamilyNames.START_TAB + ": invoked 1, 1 found, 0 missing", statusLine());

        // Once only: a later resume in the same session reads nothing again.
        controller.pause().resume();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StartTabRoute.LANDING_CHECK_MS * 2));
        assertEquals(FamilyNames.START_TAB + ": invoked 1, 1 found, 0 missing", statusLine());
    }

    private static String foundLine() {
        String line = statusLine();
        return line != null && !line.contains(" 0 found") ? line : null;
    }

    /**
     * A main screen a start from the launcher icon created, before Android runs its lifecycle, that
     * Facebook builds with [shown] on the tab bar, or no readable bar for null, and [current] showing.
     */
    private static ActivityController<FbMainTabActivity> startedOn(Object current, List<Object> shown) {
        ActivityController<FbMainTabActivity> controller =
                Robolectric.buildActivity(FbMainTabActivity.class, StartTabRouteForTests.launcherStart());
        FbMainTabActivity screen = controller.get();
        StartTabRoute.onActivityCreate(screen, null);
        if (shown != null) StartTabRouteForTests.tabBar(screen, shown, null);
        screen.currentTab = current;
        return controller;
    }

    /** What the screen started once the landing check ran, or null. */
    private static Intent startedAfterTheLandingCheck(ActivityController<FbMainTabActivity> controller) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StartTabRoute.LANDING_CHECK_MS));
        return shadowOf(controller.get()).getNextStartedActivity();
    }

    /**
     * Feeds chosen while Hide tabs keeps it off the bar: the start asks for Home, and once Facebook
     * has built the main screen, the Feeds page opens over it through Facebook's own link, the way
     * the Menu's Feeds row opens it with no Feeds tab. Back from it is Home, under it.
     */
    @Test
    public void feedsOffTheBarOpensTheFeedsPageOverHome() {
        HiddenTabs.inBuildForTests = Boolean.TRUE;
        Settings.HIDE_FEEDS_TAB.save(true);
        Settings.START_TAB.save(StartTab.FEEDS);
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        ActivityController<FbMainTabActivity> controller =
                startedOn(new FeedTab(), StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()));
        FbMainTabActivity screen = controller.get();
        assertEquals(FacebookTabs.HOME_ID, screen.getIntent().getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));

        controller.create().start().resume().visible();
        assertNull("the page opened before the screen was built", shadowOf(screen).getNextStartedActivity());
        Intent page = startedAfterTheLandingCheck(controller);

        assertNotNull("no Feeds page", page);
        assertEquals(Intent.ACTION_VIEW, page.getAction());
        assertEquals(Uri.parse(StartTabRoute.FEEDS_PAGE_LINK), page.getData());
        assertEquals("fb", page.getData().getScheme());
        assertEquals("feeds", page.getData().getHost());
        assertNotNull("Facebook's link map wants a source", page.getData().getQueryParameter("source"));
        assertEquals("another app could answer the link", screen.getPackageName(), page.getPackage());
        assertEquals(FamilyNames.START_TAB + ": invoked 1, 2 found, 0 missing", statusLine());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Start tab: the tab bar has no feeds tab, so opened the Feeds page over Home."));

        // Once: coming back to the screen opens nothing more.
        controller.pause().resume();
        assertNull(startedAfterTheLandingCheck(controller));
    }

    /** The page gets the filter chosen for Feeds, the same as the tab would. */
    @Test
    public void theFeedsPageOpensOnTheChosenFilter() {
        HiddenTabs.inBuildForTests = Boolean.TRUE;
        Settings.HIDE_FEEDS_TAB.save(true);
        Settings.START_TAB.save(StartTab.FEEDS);
        Settings.FEEDS_SUBTAB.save(FeedsSubtab.FAVORITES);
        startedOn(new FeedTab(), StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()));
        FeedFiltersFragment page = new FeedFiltersFragment();
        page.filters.addAll(Arrays.asList(FeedType.TOP_STORIES, FeedType.FAVORITES, FeedType.MOST_RECENT));

        page.onResume();

        assertEquals("Favorites wasn't picked on the Feeds page", 1, page.picked);
    }

    /**
     * An account whose bar has no Feeds tab gets the page too: Facebook was asked for Feeds and fell
     * back to Home. With no bar to read, there's no knowing, and nothing opens.
     */
    @Test
    public void aBarWithoutFeedsOfItsOwnGetsThePageAndAnUnreadableBarDoesNot() {
        Settings.START_TAB.save(StartTab.FEEDS);
        ActivityController<FbMainTabActivity> controller =
                startedOn(new FeedTab(), StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()));
        assertEquals(FacebookTabs.FEEDS_ID, controller.get().getIntent().getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));
        controller.create().start().resume().visible();
        Intent page = startedAfterTheLandingCheck(controller);
        assertNotNull("no Feeds page", page);
        assertEquals(Uri.parse(StartTabRoute.FEEDS_PAGE_LINK), page.getData());

        ActivityController<FbMainTabActivity> unread = startedOn(new FeedTab(), null);
        unread.create().start().resume().visible();
        assertNull("opened the page without reading the bar", startedAfterTheLandingCheck(unread));
    }

    /** A bar with Feeds opens on the tab, as asked, and no page goes over it. */
    @Test
    public void aBarWithFeedsGetsTheTabAndNoPage() {
        Settings.START_TAB.save(StartTab.FEEDS);
        ActivityController<FbMainTabActivity> controller =
                startedOn(new FeedsTab(), StartTabRouteForTests.tabs(new FeedTab(), new FeedsTab()));
        controller.create().start().resume().visible();
        assertNull(startedAfterTheLandingCheck(controller));
        assertEquals(FamilyNames.START_TAB + ": invoked 1, 1 found, 0 missing", statusLine());

        // Facebook opened Home with the tab on the bar: a route not taken, not a missing tab.
        ActivityController<FbMainTabActivity> home =
                startedOn(new FeedTab(), StartTabRouteForTests.tabs(new FeedTab(), new FeedsTab()));
        home.create().start().resume().visible();
        assertNull(startedAfterTheLandingCheck(home));
    }

    /**
     * A bar that opens on Reels, as Meta tests with Home second (2026), and one that opens on a tab
     * this build doesn't know. Start tab on Home asks for Home by its id, and Home opening counts as
     * found. Feeds chosen on such a bar without a Feeds tab: Facebook falls back to the bar's first
     * tab, and the Feeds page opens over it, or over Home. Over another tab it stays closed.
     */
    @Test
    public void aBarThatDoesNotStartWithHomeStillGetsHomeAndTheFeedsPage() {
        for (Object first : StartTabRouteForTests.tabs(new WatchTab(), new StartTabRouteForTests.UnknownTab())) {
            List<Object> bar = StartTabRouteForTests.tabs(first, new FeedTab(), new MarketplaceTab());
            String which = first.getClass().getSimpleName();
            HookStatus.clear();
            Settings.START_TAB.save(StartTab.HOME);
            ActivityController<FbMainTabActivity> home = startedOn(new FeedTab(), bar);
            assertEquals(which, FacebookTabs.HOME_ID, home.get().getIntent().getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));
            home.create().start().resume().visible();
            assertNull(which, startedAfterTheLandingCheck(home));
            assertEquals(which, FamilyNames.START_TAB + ": invoked 1, 1 found, 0 missing", statusLine());

            Settings.START_TAB.save(StartTab.FEEDS);
            BaseSettings.DEBUG.save(true);
            LogBufferManager.clearLogBuffer();
            ActivityController<FbMainTabActivity> fellBack = startedOn(first, bar);
            assertEquals(which, FacebookTabs.FEEDS_ID, fellBack.get().getIntent().getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));
            fellBack.create().start().resume().visible();
            Intent page = startedAfterTheLandingCheck(fellBack);
            assertNotNull("no Feeds page over " + which, page);
            assertEquals(Uri.parse(StartTabRoute.FEEDS_PAGE_LINK), page.getData());
            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains("Start tab: the tab bar has no feeds tab, so opened the Feeds page over "
                    + which));

            ActivityController<FbMainTabActivity> onHome = startedOn(new FeedTab(), bar);
            onHome.create().start().resume().visible();
            assertNotNull("no Feeds page over Home on a bar starting with " + which, startedAfterTheLandingCheck(onHome));

            LogBufferManager.clearLogBuffer();
            ActivityController<FbMainTabActivity> tapped = startedOn(new MarketplaceTab(), bar);
            tapped.create().start().resume().visible();
            assertNull("over another tab, " + which, startedAfterTheLandingCheck(tapped));
            report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains("Start tab: the tab bar has no feeds tab, but the main screen had already "
                    + "left Home and the bar's first tab, so the Feeds page stays closed."));
        }
    }

    /**
     * Hide tabs keeps Feeds off a bar that has no Home tab either: the start asks for Home, Facebook
     * falls back to the bar's first tab, and the Feeds page opens over that one. Over another tab it
     * stays closed.
     */
    @Test
    public void feedsKeptOffABarWithoutHomeOpensThePageOverTheFirstTab() {
        HiddenTabs.inBuildForTests = Boolean.TRUE;
        Settings.HIDE_FEEDS_TAB.save(true);
        Settings.START_TAB.save(StartTab.FEEDS);
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        List<Object> noHome = StartTabRouteForTests.tabs(new WatchTab(), new MarketplaceTab());
        ActivityController<FbMainTabActivity> fellBack = startedOn(new WatchTab(), noHome);
        assertEquals(FacebookTabs.HOME_ID, fellBack.get().getIntent().getLongExtra(FacebookTabs.TARGET_TAB_ID, -1));
        fellBack.create().start().resume().visible();
        Intent page = startedAfterTheLandingCheck(fellBack);
        assertNotNull("no Feeds page over the bar's first tab", page);
        assertEquals(Uri.parse(StartTabRoute.FEEDS_PAGE_LINK), page.getData());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Start tab: the tab bar has no feeds tab, so opened the Feeds page over WatchTab"));

        LogBufferManager.clearLogBuffer();
        ActivityController<FbMainTabActivity> tapped = startedOn(new MarketplaceTab(), noHome);
        tapped.create().start().resume().visible();
        assertNull("over another tab", startedAfterTheLandingCheck(tapped));
        report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Start tab: the tab bar has no feeds tab, but the main screen had already "
                + "left Home and the bar's first tab, so the Feeds page stays closed."));
    }

    /**
     * The person has gone somewhere by the time the screen is built: another tab, or another screen
     * over this one. The page stays closed. So does every start that didn't choose Feeds.
     */
    @Test
    public void noPageOnceThePersonHasMovedOnOrForAnotherTab() {
        HiddenTabs.inBuildForTests = Boolean.TRUE;
        Settings.HIDE_FEEDS_TAB.save(true);
        Settings.START_TAB.save(StartTab.FEEDS);
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        ActivityController<FbMainTabActivity> tapped =
                startedOn(new MarketplaceTab(), StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()));
        tapped.create().start().resume().visible();
        assertNull("over another tab", startedAfterTheLandingCheck(tapped));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Start tab: the tab bar has no feeds tab, but the main screen had already left Home, "
                + "so the Feeds page stays closed."));

        ActivityController<FbMainTabActivity> covered =
                startedOn(new FeedTab(), StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()));
        covered.create().start().resume().visible().pause();
        assertNull("behind another screen", startedAfterTheLandingCheck(covered));

        // Friends kept off the bar has no page here; the start is Home's.
        Settings.HIDE_FRIENDS_TAB.save(true);
        Settings.START_TAB.save(StartTab.FRIENDS);
        ActivityController<FbMainTabActivity> friends =
                startedOn(new FeedTab(), StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()));
        friends.create().start().resume().visible();
        assertNull(startedAfterTheLandingCheck(friends));

        // The switch off: Facebook's own start, nothing watched, no page.
        Settings.START_TAB.save(StartTab.FEEDS);
        Settings.OPEN_ON_CHOSEN_TAB.save(false);
        ActivityController<FbMainTabActivity> off =
                startedOn(new FeedTab(), StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()));
        off.create().start().resume().visible();
        assertNull(startedAfterTheLandingCheck(off));
    }

    /** A main screen that can't start anything, for the failure below. */
    public static final class RefusingScreen extends FbMainTabActivity {
        @Override
        public void startActivity(Intent intent) {
            throw new SecurityException("for this test");
        }
    }

    /** A failure opening the page leaves the screen on Home and says so in Hook status. */
    @Test
    public void aFailureOpeningTheFeedsPageFailsOpen() {
        RefusingScreen screen = Robolectric.buildActivity(RefusingScreen.class, StartTabRouteForTests.launcherStart()).get();
        screen.currentTab = new FeedTab();

        StartTabRoute.openFeedsPage(screen, StartTab.HOME, StartTab.FEEDS, true);

        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains("'feeds page' hook (it threw java.lang.SecurityException)"));
    }

    @Test
    public void aTabThisAccountHasNotGotIsFacebooksFallBackNotAFault() {
        FbMainTabActivity screen = StartTabRouteForTests.screen(StartTabRouteForTests.launcherStart());
        screen.currentTab = new FeedTab();
        StartTabRouteForTests.tabBar(screen, StartTabRouteForTests.tabs(new FeedTab(), new FriendRequestsTab()), null);
        LogBufferManager.clearLogBuffer();
        StartTabRoute.check(screen, StartTab.MARKETPLACE);
        assertNull("the account's own tab bar counted against the build", statusLine());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Start tab: this tab bar has no marketplace tab, so Facebook opened FeedTab "
                + FacebookTabs.HOME_ID + "."));
    }

    @Test
    public void aTabTheBarHasThatFacebookDidNotOpenIsARouteNotTaken() {
        FbMainTabActivity screen = StartTabRouteForTests.screen(StartTabRouteForTests.launcherStart());
        screen.currentTab = new FeedTab();
        StartTabRouteForTests.tabBar(screen, StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab()), null);
        StartTabRoute.check(screen, StartTab.MARKETPLACE);
        assertEquals(FamilyNames.START_TAB + ": invoked 0, 0 found, 1 missing. First missing: start on tab "
                + FacebookTabs.MAIN_TAB_ACTIVITY + "#marketplace", statusLine());
    }

    @Test
    public void theTabBarIsReadThroughTheNamesFacebookKeeps() {
        FbMainTabActivity screen = StartTabRouteForTests.screen(StartTabRouteForTests.launcherStart());
        screen.currentTab = new FeedTab();
        assertNull("no delegate, no tab bar", StartTabRoute.TabBar.tabs(screen));

        // The shown list first: it leaves out the tabs hidden in Facebook's settings.
        List<Object> shown = StartTabRouteForTests.tabs(new FeedTab(), new MarketplaceTab());
        List<Object> configured = StartTabRouteForTests.tabs(new FeedTab(), new FriendRequestsTab(), new MarketplaceTab());
        FbMainTabActivityDelegate delegate = StartTabRouteForTests.tabBar(screen, shown, configured);
        assertEquals(Arrays.asList("FeedTab", "MarketplaceTab"), names(StartTabRoute.TabBar.tabs(screen)));

        // Without one, the configuration's.
        StartTabRouteForTests.tabBar(screen, null, configured);
        assertEquals(Arrays.asList("FeedTab", "FriendRequestsTab", "MarketplaceTab"),
                names(StartTabRoute.TabBar.tabs(screen)));

        // A delegate handed out inside a wrapper is found in it.
        screen.delegate = new StartTabRouteForTests.DelegateWrapper(delegate);
        assertEquals(Arrays.asList("FeedTab", "MarketplaceTab"), names(StartTabRoute.TabBar.tabs(screen)));

        // After a plain start the delegate hasn't asked for the state yet. While the screen shows
        // no tab, the reader doesn't ask either: asking could build the state ahead of Facebook.
        StartTabRouteForTests.TabBarState unasked = new StartTabRouteForTests.TabBarState();
        unasked.shown = new java.util.ArrayList<>(shown);
        StartTabRouteForTests.Lazy early = new StartTabRouteForTests.Lazy(unasked, false);
        delegate.tabBarStateManager$delegate = early;
        screen.delegate = delegate;
        screen.currentTab = null;
        assertNull("the tab bar was read before the screen showed a tab", StartTabRoute.TabBar.tabs(screen));
        assertFalse("the reader built the tab bar state ahead of Facebook", early.isInitialized());

        // Once the screen shows a tab, the tab bar was built from that state, so it's read.
        screen.currentTab = new FeedTab();
        StartTabRouteForTests.Lazy lazy = new StartTabRouteForTests.Lazy(unasked, false);
        delegate.tabBarStateManager$delegate = lazy;
        assertEquals(Arrays.asList("FeedTab", "MarketplaceTab"), names(StartTabRoute.TabBar.tabs(screen)));
        assertTrue(lazy.isInitialized());

        // A lazy value that can't hand one over is no tab bar, and no failure.
        StartTabRouteForTests.Lazy broken = new StartTabRouteForTests.Lazy(unasked, false);
        broken.failure = new IllegalStateException("no session yet");
        delegate.tabBarStateManager$delegate = broken;
        assertNull(StartTabRoute.TabBar.tabs(screen));

        assertEquals(FacebookTabs.MARKETPLACE_ID, StartTabRoute.TabBar.tabId(new MarketplaceTab()));
        assertEquals(-1, StartTabRoute.TabBar.tabId(new Object()));
        assertEquals("no tab", StartTabRoute.TabBar.name(null));
        assertEquals("unreadable", StartTabRoute.TabBar.describe(null));
    }

    private static List<String> names(List<Object> tabs) {
        assertNotNull("the tab bar wasn't read", tabs);
        List<String> names = new java.util.ArrayList<>();
        for (Object tab : tabs) names.add(tab.getClass().getSimpleName());
        return names;
    }

    @Test
    public void aSettingsFileHoldsTheTabByAValueThatNeverChanges() {
        for (StartTab tab : StartTab.choices()) {
            assertSame(tab, StartTab.fromFile(tab.fileValue));
        }
        assertEquals(Arrays.asList("home", "feeds", "video", "friends", "marketplace", "notifications", "menu"),
                fileValues());
        for (Object refused : new Object[]{"MARKETPLACE", "Marketplace", "market", "", null, 5, true}) {
            assertNull(String.valueOf(refused), StartTab.fromFile(refused));
        }
        assertTrue(StartTab.FEEDS.isTab(FacebookTabs.FEEDS_CLASS));
        assertTrue(StartTab.FEEDS.isTab(FacebookTabs.MOST_RECENT_CLASS));
        assertFalse(StartTab.FEEDS.isTab(FacebookTabs.HOME_CLASS));
        assertFalse(StartTab.MARKETPLACE.isTab(null));
    }

    private static List<String> fileValues() {
        List<String> values = new java.util.ArrayList<>();
        for (StartTab tab : StartTab.choices()) values.add(tab.fileValue);
        return values;
    }
}
