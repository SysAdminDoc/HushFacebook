/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.preference.ReopenSettingsAfterRestart;
import app.morphe.extension.facebook.ads.AudienceNetwork;
import app.morphe.extension.facebook.download.ClipboardLink;
import app.morphe.extension.facebook.download.SaveLeftovers;
import app.morphe.extension.facebook.download.SavedFileActions;
import app.morphe.extension.facebook.feed.LinkedPost;
import app.morphe.extension.facebook.feed.ReturnRefresh;
import app.morphe.extension.facebook.media.ResumePlayback;
import app.morphe.extension.facebook.menu.MenuLayoutDump;
import app.morphe.extension.facebook.media.TapToPlay;
import app.morphe.extension.facebook.misc.AppIcons;
import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.misc.BreakTimer;
import app.morphe.extension.facebook.misc.CloseWhenLeft;
import app.morphe.extension.facebook.misc.OpeningPause;
import app.morphe.extension.facebook.misc.ScreenTransitions;
import app.morphe.extension.facebook.misc.TextSize;
import app.morphe.extension.facebook.navigation.HiddenStatusBar;
import app.morphe.extension.facebook.navigation.MarketplaceFilterButton;
import app.morphe.extension.facebook.navigation.ReelsTab;
import app.morphe.extension.facebook.navigation.TranslucentBars;
import app.morphe.extension.facebook.notifications.PushDiagnostics;
import app.morphe.extension.facebook.reels.BurnInGuard;
import app.morphe.extension.facebook.reels.FeedVideoAlone;
import app.morphe.extension.facebook.reels.ViewerTopBar;
import app.morphe.extension.facebook.stories.StorySeenButton;
import app.morphe.extension.facebook.theme.AccentResources;
import app.morphe.extension.facebook.theme.AmoledResources;
import app.morphe.extension.facebook.theme.MaterialYouTheme;

/**
 * How the Hushfacebook screen is reached.
 *
 * <p>Inside Facebook, a long press on the Facebook logo at the top of the home feed opens it over
 * that screen (see {@link #setLogoTouchListener}).
 *
 * <p>From the home screen, a long-press shortcut on Facebook's launcher icon opens Facebook's
 * launcher entry with {@link #EXTRA_OPEN_SETTINGS}. Every Facebook activity reports its intent
 * here from {@code onCreate} and {@code onNewIntent}, and the next Facebook activity to resume
 * shows the screen as a full screen dialog. Nothing is added to Facebook's manifest, so no resource
 * has to be rebuilt to get here. The shortcut is kept first among Facebook's own, because a
 * launcher shows only the first few, and some launchers have no shortcut menu at all (#2). Its
 * switch ({@link Settings#SETTINGS_SHORTCUT}) takes it out of that menu for someone who opens the
 * screen from Facebook's Menu instead. The logo's long press and the Menu row have switches too, and
 * the last of the three still on can't be turned off, so there is always a way in (#146).
 */
@SuppressWarnings("unused")
public final class SettingsEntry {
    public static final String EXTRA_OPEN_SETTINGS = "app.morphe.extension.facebook.OPEN_SETTINGS";
    /** With {@link #EXTRA_OPEN_SETTINGS}: the key of the setting whose row the screen opens at. */
    public static final String EXTRA_SHOW_SETTING = "app.morphe.extension.facebook.SHOW_SETTING";
    static final String SHORTCUT_ID = "hushfacebook_settings";
    private static final String DIALOG_TAG = "hushfacebook_settings";

    /** A request older than this is dropped rather than opened over some later screen. */
    private static final long REQUEST_LIFETIME_MS = 30_000;

    private static volatile boolean openPending;
    private static volatile long requestedAt;
    /** The setting the pending request opens at, or null for the overview. */
    private static volatile String requestedSetting;
    /**
     * The page the next open lands on, or null for the first page. Set by a restart from the
     * restart row, kept while the screen is opened again over a later activity, and dropped
     * once the person closes it or asks for it another way.
     */
    private static volatile String requestedPage;
    private static volatile boolean callbacksRegistered;
    /** The activity the screen was last shown over, while the person hasn't closed it. */
    private static WeakReference<Activity> host;
    private static final Map<Activity, WeakReference<SettingsDialog>> opening = new WeakHashMap<>();
    private static volatile boolean closedByUser;
    /** The long label last pushed, or found already on the shortcut, in this process. */
    private static volatile String publishedLabel;
    /** How long after a launcher change the shortcuts go back, so Android has disabled the old ones first. */
    static final long LAUNCHER_CHANGE_DELAY_MS = 2_000;
    private static volatile Context watchingLauncherFor;
    /** A check of the shortcut's place is waiting for the background thread. */
    private static final AtomicBoolean keepFirstQueued = new AtomicBoolean();

    private SettingsEntry() {
    }

    /**
     * Injected before each return of the application's {@code onCreate}, after Facebook's own
     * startup. Watches every Facebook activity, so a pending open lands on whichever one resumes next:
     * signed out, the launcher hands straight over to the login screen. With Material You in the
     * build, each activity's dark window background takes the palette from here on, and with Accent
     * color each activity's resources take the accent's colour table. Also where the release
     * check, when it's on, asks at most once a day, on a worker, and where what a save cut short
     * by Android left behind is removed, on a worker too. Every process starts private push
     * observations. Other processes also start Lock Facebook's watch ({@link AppLock#watch}). The
     * report's network count starts here too ({@link NetworkUsage}), and any Audience Network
     * component that's on goes back off ({@link AudienceNetwork#keepOff}). A start that the settings
     * screen's restart row asked for opens the screen again, on the page it was on.
     */
    public static void onApplicationCreate(Context context) {
        try {
            PushDiagnostics.initialize(context);
            if (!Utils.isMainProcess()) {
                // Instant Games, the Audience Network ads and Facebook's crash screen have
                // processes of their own. Only the lock watches their screens.
                AppLock.watch(context);
                return;
            }
            // First, so a settings file an audit pushed is saved before anything below reads it.
            AuditMode.onApplicationCreate(context);
            NetworkUsage.start(context);
            if (context instanceof Application && !callbacksRegistered) {
                ((Application) context).registerActivityLifecycleCallbacks(new OpenWhenResumed());
                callbacksRegistered = true;
            }
            // After the entry's, so on a resume the cover goes on last, over anything they opened.
            // Whether Facebook is locked is settled before a settings request is judged, since that
            // waits for a post, and covering() holds it back. A window Facebook opens later lands
            // above the cover, which then goes back on top (AppLock.Cover).
            AppLock.watch(context);
            // Close Facebook when you leave it (#202): when the last screen goes to the background.
            CloseWhenLeft.watch(context);
            // The opening pause, after the lock's, so on a resume it knows whether the lock covers.
            OpeningPause.watch(context);
            // Take breaks (#248), after both, so a break waits for their covers.
            BreakTimer.watch(context);
            ReturnRefresh.register(context);
            watchLauncherChanges(context);
            TextSize.application(context);
            // Only with the theme in the build, so its class and palette aren't loaded otherwise.
            if (SettingsStatus.materialYouTheme()) MaterialYouTheme.watchWindows(context);
            // Accent color's route five, for the colours views inflated from XML read themselves.
            if (SettingsStatus.accentColor()) AccentResources.watchActivities(context);
            // AMOLED's Background color row, for the colours views inflated from XML read themselves (#214).
            if (SettingsStatus.amoledTheme()) AmoledResources.watchActivities(context);
            // See-through bars, a switch of the tab bar patch: each main screen as it resumes.
            if (SettingsStatus.bottomTabBar()) {
                TranslucentBars.watch(context);
                HiddenStatusBar.watch(context);
            }
            // Open feed videos on their own, a Clean up Reels switch: which screen is in front.
            // And the top bar of the full-screen player, another (#311): looked through after each layout.
            // And Burn-in guard (#288): the activity in front, for a video filling the screen.
            if (SettingsStatus.reelDeclutter()) {
                FeedVideoAlone.watch(context);
                ViewerTopBar.watch(context);
                BurnInGuard.install(context);
            }
            // An Audience Network component an earlier install turned on goes back off (#168).
            if (SettingsStatus.audienceNetwork()) AudienceNetwork.keepOff(context);
            // Last, so nothing above waits on it. The open itself waits for the first screen to resume.
            // A settings restart starts like a tap on the icon, and opens without the opening pause.
            if (ReopenSettingsAfterRestart.reopenIfPending(SettingsEntry::reopenOn)) OpeningPause.restarted();
        } catch (Throwable ex) {
            // Throwable, as in every hook: an Error here, from a class that fails to load or set
            // itself up, would end Facebook at every start, paused or not.
            Logger.printException(() -> "Settings entry: could not watch activities", ex);
        }
        ReleaseCheck.onFacebookStart();
        StartsOffNote.onFacebookStart(context);
        SaveLeftovers.sweepAfterStart(context);
        ResumePlayback.onFacebookStart();
        publishShortcut(context);
    }

    /**
     * Checks the launcher shortcut on a background thread. First, with Hide the Reels tab on, a Reels
     * shortcut Facebook published before goes. Package-visible for tests.
     */
    static void publishShortcut(Context context) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utils.runOnBackgroundThread(() -> {
            ReelsTab.removePublished(app);
            publishShortcutNow(app);
            SavedShortcut.refreshNow(app);
            WatchHistoryShortcut.refreshNow(app);
            FreeStuffShortcut.refreshNow(app);
        });
    }

    /**
     * Picking an app icon turns Facebook's launcher entry off and the icon's alias on, and Android
     * then disables every dynamic shortcut tied to the entry that went off, ours included. Facebook
     * keeps running, so they stayed gone until its next cold start (#119). Android announces the
     * change to Facebook itself with PACKAGE_CHANGED, so a moment after one that names the launcher
     * entry or an icon alias, the shortcuts are published again on the entry that's on now.
     */
    static void watchLauncherChanges(Context context) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (watchingLauncherFor == app) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                try {
                    if (!namesLauncher(app.getPackageName(), intent)) return;
                    Logger.printInfo(() -> "Settings entry: Facebook's launcher entry changed, publishing the shortcuts again");
                    Utils.runOnMainThreadDelayed(() -> publishShortcut(app), LAUNCHER_CHANGE_DELAY_MS);
                } catch (Throwable failure) {
                    Logger.printException(() -> "Settings entry: could not follow a launcher change", failure);
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_CHANGED);
            filter.addDataScheme("package");
            filter.addDataSchemeSpecificPart(app.getPackageName(), PatternMatcher.PATTERN_LITERAL);
            // The system sends it, so a receiver other apps can't reach still gets it.
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            watchingLauncherFor = app;
        } catch (Throwable failure) {
            Logger.printException(() -> "Settings entry: could not watch for launcher changes", failure);
        }
    }

    /**
     * Whether [intent] is a PACKAGE_CHANGED for [packageName] naming its launcher entry, one of the
     * icon aliases, or the whole package. Package-visible for tests.
     */
    static boolean namesLauncher(String packageName, @Nullable Intent intent) {
        if (intent == null || !Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())) return false;
        Uri data = intent.getData();
        if (data == null || !packageName.equals(data.getSchemeSpecificPart())) return false;
        String[] changed = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
        if (changed == null) return false;
        for (String name : changed) {
            if (packageName.equals(name) || AppIcons.LAUNCHER.equals(name)
                    || (name != null && name.startsWith(AppIcons.LAUNCHER + "."))) return true;
        }
        return false;
    }

    /**
     * Labels the shortcut again once Facebook has set its own language. That happens after the
     * application starts, so a label published then is in the phone's language, and it stayed
     * that way next to a screen in Facebook's. A string compare while the label still matches.
     */
    static void relabelIfStale(Context context) {
        try {
            if (!L10n.t(context, "Hushfacebook settings").equals(publishedLabel)) publishShortcut(context);
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not check the shortcut's label", ex);
        }
    }

    /**
     * Publishes the launcher shortcut, labels it again when Facebook's language has changed, or
     * puts it back in front when Facebook's own went ahead of it, on the thread it's called on.
     * Package-visible for tests.
     */
    static void publishShortcutNow(Context app) {
        try {
            // Nothing to go by until the settings are read: the shortcut stays as the last start left it.
            if (!Utils.settingsReady()) return;
            ShortcutManager manager = app.getSystemService(ShortcutManager.class);
            if (manager == null) return;
            String longLabel = L10n.t(app, "Hushfacebook settings");
            // Set before the attempt, so a shortcut that can't be pushed isn't tried on every screen.
            publishedLabel = longLabel;
            if (!Settings.SETTINGS_SHORTCUT.get()) {
                removeShortcut(manager);
                return;
            }
            ShortcutInfo existing = ours(manager);
            Intent open = openIntent(app);
            // One labelled in another language, behind Facebook's, or naming a launcher entry an
            // icon change has turned off (#119), is pushed again below.
            if (existing != null && existing.getRank() == 0
                    && longLabel.contentEquals(existing.getLongLabel())
                    && existing.getIntent() != null
                    && open.getComponent().equals(existing.getIntent().getComponent())) return;
            // Evicts the lowest-ranked dynamic shortcut when Facebook's own fill the limit.
            manager.pushDynamicShortcut(shortcut(app, longLabel, open));
            Logger.printInfo(() -> "Settings entry: launcher shortcut published");
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not publish the shortcut", ex);
        }
    }

    /**
     * Puts the shortcut back in front of Facebook's own, keeping the label it has, or publishes it
     * when Facebook's call removed it. The label is kept because the process Facebook pushes from
     * may not have Facebook's language yet, and the next screen relabels it anyway. Package-visible
     * for tests.
     */
    static void keepFirstNow(Context app) {
        try {
            if (!Utils.settingsReady()) return;
            ShortcutManager manager = app.getSystemService(ShortcutManager.class);
            if (manager == null) return;
            // Switched off, Facebook's calls neither bring it back nor rank it.
            if (!Settings.SETTINGS_SHORTCUT.get()) {
                removeShortcut(manager);
                return;
            }
            ShortcutInfo existing = ours(manager);
            if (existing == null) {
                publishShortcutNow(app);
                return;
            }
            final int rank = existing.getRank();
            if (rank == 0) return;
            CharSequence label = existing.getLongLabel();
            manager.pushDynamicShortcut(shortcut(app, label != null ? label : L10n.t(app, "Hushfacebook settings"), openIntent(app)));
            Logger.printInfo(() -> "Settings entry: launcher shortcut moved back in front from rank " + rank);
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not put the shortcut back in front", ex);
        }
    }

    /**
     * The settings shortcut switch changed: off takes the shortcut out of the launcher's menu now,
     * on puts it back in front, both on a background thread. Facebook's own shortcuts stay.
     */
    static void shortcutSwitchChanged(Context context) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utils.runOnBackgroundThread(() -> publishShortcutNow(app));
    }

    /** Takes the Hushfacebook shortcut out of the launcher's menu, if it's there. */
    private static void removeShortcut(ShortcutManager manager) {
        if (ours(manager) == null) return;
        manager.removeDynamicShortcuts(java.util.Collections.singletonList(SHORTCUT_ID));
        Logger.printInfo(() -> "Settings entry: launcher shortcut removed, its switch is off");
    }

    /** The Hushfacebook shortcut among the dynamic ones, or null. */
    private static ShortcutInfo ours(ShortcutManager manager) {
        for (ShortcutInfo existing : manager.getDynamicShortcuts()) {
            if (SHORTCUT_ID.equals(existing.getId())) return existing;
        }
        return null;
    }

    /**
     * What the launcher shortcut sends: Facebook's launcher entry, asked to show the screen. The
     * entry that's on now, which is the picked icon's after an icon change ({@link AppIcons#launcher}).
     */
    private static Intent openIntent(Context app) {
        return new Intent(Intent.ACTION_VIEW)
                .setComponent(AppIcons.launcher(app))
                .putExtra(EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The same, opening the screen at [key]'s row, for a notification's button. */
    public static Intent settingIntent(Context app, String key) {
        return openIntent(app).putExtra(EXTRA_SHOW_SETTING, key);
    }

    /** The setting the request that opened the screen asked for, once, or null. */
    @Nullable
    static String takeRequestedSetting() {
        String key = requestedSetting;
        requestedSetting = null;
        return key;
    }

    private static ShortcutInfo shortcut(Context app, CharSequence longLabel, Intent intent) {
        return new ShortcutInfo.Builder(app, SHORTCUT_ID)
                .setShortLabel("Hushfacebook")
                .setLongLabel(longLabel)
                .setIcon(Icon.createWithAdaptiveBitmap(shortcutIcon()))
                .setIntent(intent)
                // The platform puts the newest push first among equal ranks.
                .setRank(0)
                .build();
    }

    // Facebook's own calls that change its dynamic shortcuts come here instead: the patch sends each
    // ShortcutManager call of these names to the method of the same name here, the manager first.
    // Facebook pushes its Notifications, Friends and Reels shortcuts, and one per Messenger chat it
    // notifies about, each at rank 0, and the platform puts the newest push first. So the
    // Hushfacebook shortcut sank to the end of the list, where a launcher showing three or four,
    // or two beside a notification, cut it off (#2). Facebook's call runs as it did, with the same
    // answer and the same exceptions, and then the Hushfacebook shortcut goes back in front. With
    // Hide the Reels tab on, Facebook's Reels shortcut is left out of each call first (ReelsTab).

    public static void pushDynamicShortcut(ShortcutManager manager, ShortcutInfo shortcut) {
        if (!ReelsTab.dropsShortcut(manager, shortcut)) manager.pushDynamicShortcut(shortcut);
        keepFirst();
    }

    public static boolean addDynamicShortcuts(ShortcutManager manager, List<ShortcutInfo> shortcuts) {
        boolean added = manager.addDynamicShortcuts(ReelsTab.withoutShortcut(manager, shortcuts));
        keepFirst();
        return added;
    }

    /** Replaces every dynamic shortcut, the Hushfacebook one too, which is published again after. */
    public static boolean setDynamicShortcuts(ShortcutManager manager, List<ShortcutInfo> shortcuts) {
        boolean set = manager.setDynamicShortcuts(ReelsTab.withoutShortcut(manager, shortcuts));
        keepFirst();
        return set;
    }

    public static boolean updateShortcuts(ShortcutManager manager, List<ShortcutInfo> shortcuts) {
        boolean updated = manager.updateShortcuts(ReelsTab.withoutShortcut(manager, shortcuts));
        keepFirst();
        return updated;
    }

    public static void removeAllDynamicShortcuts(ShortcutManager manager) {
        manager.removeAllDynamicShortcuts();
        keepFirst();
    }

    /**
     * Checks the shortcut on a background thread, once however many of Facebook's calls ask. The
     * flag drops as the check starts, so a call that lands during it asks for another.
     */
    private static void keepFirst() {
        try {
            Context context = Utils.getContext();
            if (context == null || !keepFirstQueued.compareAndSet(false, true)) return;
            final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            boolean queued = Utils.runOnBackgroundThread(() -> {
                keepFirstQueued.set(false);
                keepFirstNow(app);
                SavedShortcut.refreshNow(app);
                WatchHistoryShortcut.refreshNow(app);
                FreeStuffShortcut.refreshNow(app);
            });
            if (!queued) keepFirstQueued.set(false);
        } catch (Throwable t) {
            keepFirstQueued.set(false);
            Logger.printException(() -> "Settings entry: could not check the shortcut after Facebook's", t);
        }
    }

    /** Injected at the start of every Facebook activity's {@code onCreate}. */
    public static void onActivityCreate(Activity activity) {
        HushfacebookPause.keepCrashMarkOnTop();
        MenuLayoutDump.screenCreated(activity);
        try {
            SavedFileActions.receive(activity.getIntent());
            AuditMode.noteIntent(activity, activity.getIntent());
            noteIntent(activity, activity.getIntent());
        } catch (Throwable ex) {
            Logger.printException(() -> "Settings entry: onActivityCreate failure", ex);
        }
    }

    /** Injected at the start of every Facebook activity's {@code onNewIntent}. */
    public static void onNewIntent(Activity activity, Intent intent) {
        try {
            SavedFileActions.receive(intent);
            AuditMode.noteIntent(activity, intent);
            noteIntent(activity, intent);
            OpeningPause.newIntent(intent);
        } catch (Throwable ex) {
            Logger.printException(() -> "Settings entry: onNewIntent failure", ex);
        }
    }

    /**
     * Facebook's call that gives the Facebook logo at the top of the home feed its touch listener
     * comes here instead. The patch sends the one right after the logo gets its tap, in the method
     * that builds the logo, with the same registers.
     *
     * <p>Facebook passes no listener for a plain logo, and the logo gets one that opens this
     * screen on a long press. When Facebook passes its own, Facebook reads the logo's gestures
     * itself (its World Cup mode takes a double tap there, and a long press that opens its game),
     * so its listener goes on as it was and the long press stays Facebook's.
     *
     * <p>The logo press has a switch ({@link Settings#SETTINGS_LOGO_PRESS}) that a pause leaves as it
     * is, since this is a way back to the switch that resumes Hushfacebook. The listener reads it on
     * every touch, and off, every touch is Facebook's. Anything that goes wrong leaves the logo with
     * Facebook's listener.
     */
    public static void setLogoTouchListener(View logo, View.OnTouchListener facebooks) {
        View.OnTouchListener listener = facebooks;
        if (facebooks == null) {
            try {
                listener = new LogoPress();
                Logger.printInfo(() -> "Settings entry: a long press on the Facebook logo opens the settings");
            } catch (Throwable failure) {
                Logger.printException(() -> "Settings entry: could not watch the Facebook logo", failure);
            }
        } else {
            Logger.printInfo(() -> "Settings entry: Facebook reads the logo's long press itself here, so it stays Facebook's");
        }
        // Facebook's own call, with Facebook's listener or the one above.
        logo.setOnTouchListener(listener);
    }

    /**
     * Facebook's call that hides the Facebook logo from accessibility, on the path of the logo's
     * builder that gives the logo no tap, comes here instead (#12). 583 takes that path when none of
     * its ways to read the logo's tap is on, as in Arabic, and jumps past the tap, the touch listener
     * that {@link #setLogoTouchListener} stands in for and the description, so the logo stays
     * disabled with nothing listening and the long press never opened the settings.
     *
     * <p>After Facebook's own call, the logo is enabled, since a disabled view never hands a touch
     * to its listener, and gets a press that takes the whole touch while the switch is on, since a
     * logo with no tap wouldn't hear the rest of it otherwise. A tap there did nothing before, and
     * still does nothing. Off, every touch goes past the logo as it did.
     */
    public static void setLogoWithoutTap(View logo, int importance) {
        // Facebook's own call.
        logo.setImportantForAccessibility(importance);
        try {
            logo.setEnabled(true);
            logo.setOnTouchListener(new LogoPress(true));
            Logger.printInfo(() -> "Settings entry: Facebook gave the logo no tap here, so a long press on it opens the settings");
        } catch (Throwable failure) {
            Logger.printException(() -> "Settings entry: could not watch the Facebook logo, which has no tap here", failure);
        }
    }

    /**
     * Whether a long press on the Facebook logo opens the settings. Its switch, except that when
     * every one of the three ways in is stored off (a backup imported that way, say) the press
     * stays on, so there is always a way back in. The Menu row only counts as a way in on a build
     * that carries it. Until the settings are read, the press is Facebook's.
     */
    public static boolean logoPressOn() {
        if (!Utils.settingsReady()) return false;
        if (Settings.SETTINGS_LOGO_PRESS.get()) return true;
        return !(Settings.SETTINGS_SHORTCUT.get()
                || (Settings.SETTINGS_MENU_ROW.get() && PatchFamily.MENU_SETTINGS_ROW.inBuild()));
    }

    /**
     * Whether the Hushfacebook row goes in the Menu: its switch, or on when the shortcut and the
     * logo press are both stored off, for the same reason as {@link #logoPressOn}.
     */
    public static boolean menuRowOn() {
        if (!Utils.settingsReady()) return false;
        return Settings.SETTINGS_MENU_ROW.get() || !(Settings.SETTINGS_SHORTCUT.get() || Settings.SETTINGS_LOGO_PRESS.get());
    }

    /**
     * Whether [way], one of the three switches that open the settings, is on and the only one that
     * is, so the screen shows it on and greyed out. [menuRowInBuild] is whether this build has the
     * Menu row, which counts as a way in only when it does.
     */
    static boolean isLastWayIn(BooleanSetting way, boolean menuRowInBuild) {
        if (!way.savedValue()) return false;
        int on = (Settings.SETTINGS_SHORTCUT.savedValue() ? 1 : 0)
                + (Settings.SETTINGS_LOGO_PRESS.savedValue() ? 1 : 0)
                + (menuRowInBuild && Settings.SETTINGS_MENU_ROW.savedValue() ? 1 : 0);
        return on <= 1;
    }

    /**
     * Times a press on the Facebook logo. Every touch still reaches the logo as it did, so a tap
     * is Facebook's. A press held for the phone's long-press time opens this screen, with the
     * same vibration a long press gives, and the rest of that touch is kept from the logo, so
     * letting go isn't also a tap. On a logo Facebook gave no tap, a timed press takes the whole
     * touch from the start ({@link #setLogoWithoutTap}).
     *
     * <p>It doesn't use the logo's own long-press handling. Facebook gives the logo an
     * accessibility delegate that puts back the long-press state it saw before the logo had any
     * listeners, and it does that each time an accessibility service reads the screen: after one
     * read by TalkBack, a password manager or a UI dump, a long-click listener would stop firing.
     */
    static final class LogoPress implements View.OnTouchListener {
        private final Runnable fire = this::fire;
        /**
         * Whether a timed press takes the whole touch, for a logo Facebook gave no tap, which
         * wouldn't hear past the finger going down otherwise.
         */
        private final boolean claims;
        /** The logo while a press on it is being timed. */
        private View pressed;
        private float downX;
        private float downY;
        /** This touch opened the screen, so the rest of it isn't the logo's. */
        private boolean opened;

        LogoPress() {
            this(false);
        }

        LogoPress(boolean claims) {
            this.claims = claims;
        }

        /** Whether a press on the logo is being timed for a long press. Package-visible for tests. */
        boolean timing() {
            return pressed != null;
        }

        @Override
        public boolean onTouch(View logo, MotionEvent event) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        stop();
                        opened = false;
                        // Switched off, the press is Facebook's, and nothing here times it.
                        if (!logoPressOn()) return false;
                        // A long press Facebook gave the logo itself stays Facebook's.
                        if (logo.isLongClickable()) return false;
                        pressed = logo;
                        downX = event.getX();
                        downY = event.getY();
                        logo.postDelayed(fire, ViewConfiguration.getLongPressTimeout());
                        return claims;
                    case MotionEvent.ACTION_MOVE:
                        // The same leeway the logo gives its own press before it lets go.
                        if (pressed != null && !within(logo, event)) stop();
                        return opened;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        stop();
                        boolean ours = opened;
                        opened = false;
                        return ours;
                    default:
                        return opened;
                }
            } catch (Throwable failure) {
                Logger.printException(() -> "Settings entry: could not follow a press on the Facebook logo", failure);
                return false;
            }
        }

        private static boolean within(View logo, MotionEvent event) {
            float slop = ViewConfiguration.get(logo.getContext()).getScaledTouchSlop();
            float x = event.getX();
            float y = event.getY();
            return x >= -slop && y >= -slop && x < logo.getWidth() + slop && y < logo.getHeight() + slop;
        }

        private void stop() {
            View logo = pressed;
            pressed = null;
            if (logo != null) logo.removeCallbacks(fire);
        }

        private void fire() {
            View logo = pressed;
            pressed = null;
            if (logo == null || !logo.isAttachedToWindow()) return;
            try {
                // Switched off while the finger was down: still Facebook's.
                if (!logoPressOn()) return;
                // Not asked for, and letting go is Facebook's tap, as it is on a Facebook without this.
                if (!requestFromLogo(logo)) return;
                opened = true;
                logo.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                // The logo lets go of its press here, so letting go of the screen isn't a tap on it.
                long now = SystemClock.uptimeMillis();
                MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0);
                logo.onTouchEvent(cancel);
                cancel.recycle();
            } catch (Throwable failure) {
                Logger.printException(() -> "Settings entry: the long press on the Facebook logo failed", failure);
            }
        }
    }

    /**
     * Asks for the screen over the logo's activity, the way the launcher shortcut does, so it
     * waits for the activity to settle and follows it to the next screen if it goes away.
     *
     * @return whether the request was made.
     */
    static boolean requestFromLogo(View logo) {
        Activity activity = activityOf(logo.getContext());
        if (activity == null) {
            Logger.printInfo(() -> "Settings entry: the Facebook logo isn't in an activity, so its long press is Facebook's");
            return false;
        }
        requestedAt = SystemClock.elapsedRealtime();
        requestedSetting = null;
        requestedPage = null;
        openPending = true;
        Logger.printInfo(() -> "Settings requested by a long press on the Facebook logo");
        OpenWhenResumed.openWhenSettled(activity);
        return true;
    }

    /** The activity a view's context wraps, or null. The depth guards against a wrapper that wraps itself. */
    private static Activity activityOf(Context context) {
        for (int depth = 0; context != null && depth < 20; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    static final class OpenWhenResumed implements Application.ActivityLifecycleCallbacks {
        /** The Facebook screen in front right now, if any. */
        private WeakReference<Activity> resumed;

        @Override
        public void onActivityResumed(Activity activity) {
            resumed = new WeakReference<>(activity);
            TextSize.activity(activity);
            ScreenLog.resumed(activity);
            SavedFileActions.onResumed(activity);
            ScreenTransitions.activityResumed(activity);
            TapToPlay.activityResumed(activity);
            // Only with Download any video in the build, whose switch it is an option of.
            if (SettingsStatus.videoDownload()) ClipboardLink.onResumed(activity);
            if (SettingsStatus.storySeen()) StorySeenButton.activityResumed(activity);
            if (openPending) openWhenSettled(activity);
            relabelIfStale(activity);
            SavedShortcut.refresh(activity);
            WatchHistoryShortcut.refresh(activity);
            FreeStuffShortcut.refresh(activity);
            if (SettingsStatus.sponsoredMarketplace()) MarketplaceFilterButton.activityResumed(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (SettingsStatus.sponsoredMarketplace()) MarketplaceFilterButton.activityPaused(activity);
            SavedFileActions.onPaused(activity);
            ClipboardLink.onPaused(activity);
            StorySeenButton.activityPaused(activity);
            ScreenTransitions.activityPaused(activity);
            ScreenLog.paused(activity);
            if (resumed != null && resumed.get() == activity) resumed = null;
            LastScreen.read(activity);
        }

        /** Before the activity's own onCreate, so its first layout is measured at the chosen text size. */
        @Override
        public void onActivityPreCreated(Activity activity, Bundle state) {
            TextSize.activity(activity);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            LinkedPost.activityCreated(activity);
            TapToPlay.activityCreated(activity, state);
            ScreenTransitions.activityCreated(activity);
            if (SettingsStatus.storySeen()) StorySeenButton.activityCreated(activity);
        }

        @Override public void onActivityStarted(Activity activity) { }
        @Override
        public void onActivityStopped(Activity activity) {
            TapToPlay.activityStopped(activity);
        }

        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override
        public void onActivityDestroyed(Activity activity) {
            opening.remove(activity);
            TextSize.destroyed(activity);
            SavedFileActions.onPaused(activity);
            if (SettingsStatus.storySeen()) StorySeenButton.activityDestroyed(activity);
            // The screen can land on an activity just before it clears itself for the next one.
            // If its host goes away before the person closed it, ask again.
            WeakReference<Activity> shownOver = host;
            if (shownOver == null || shownOver.get() != activity) return;
            host = null;
            if (closedByUser) return;
            if (SystemClock.elapsedRealtime() - requestedAt > REQUEST_LIFETIME_MS) return;
            openPending = true;
            Logger.printInfo(() -> "Settings host " + activity.getClass().getSimpleName()
                    + " went away; opening again over the next screen");
            // Android resumes the next screen before it destroys the one it replaced, so that
            // screen is usually in front already and won't resume again to pick the request up.
            // Signed out, the login screen hands over to Facebook's logged-out screen this way.
            Activity current = resumed == null ? null : resumed.get();
            if (current != null && current != activity) openWhenSettled(current);
        }

        /**
         * Posted, so the activity has finished resuming before a fragment is committed. The
         * request stays pending until a screen is actually shown: signed out, the launcher
         * activity resumes for a moment while it clears itself for the login screen, and a
         * request spent on it would be lost.
         */
        private static void openWhenSettled(Activity activity) {
            Utils.runOnMainThread(() -> {
                if (!openPending) return;
                if (SystemClock.elapsedRealtime() - requestedAt > REQUEST_LIFETIME_MS) {
                    openPending = false;
                    requestedSetting = null;
                    requestedPage = null;
                    Logger.printInfo(() -> "Settings request expired before a Facebook screen could show it");
                    return;
                }
                if (open(activity)) openPending = false;
            });
        }
    }

    /**
     * Shows the screen over the given activity.
     *
     * @return whether the screen is showing (or already was) over this activity.
     */
    @SuppressWarnings("deprecation") // Framework fragments are what the shared preference code builds on.
    public static boolean open(Activity activity) {
        final String name = activity.getClass().getSimpleName();
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                Logger.printInfo(() -> "Settings wait: " + name + " is finishing");
                return false;
            }
            // A dialog over the lock's cover would show above it, and could turn the lock off.
            if (AppLock.covering()) {
                Logger.printInfo(() -> "Settings wait: Facebook is locked");
                return false;
            }
            FragmentManager fragments = activity.getFragmentManager();
            Fragment shown = fragments.findFragmentByTag(DIALOG_TAG);
            if (shown != null) {
                opening.remove(activity);
                // Already up: a notification's button still takes it to the row it names.
                if (shown instanceof SettingsDialog) ((SettingsDialog) shown).showRequestedSetting();
                return true;
            }
            WeakReference<SettingsDialog> pending = opening.get(activity);
            if (pending != null && pending.get() != null) return true;
            if (fragments.isStateSaved()) {
                Logger.printInfo(() -> "Settings wait: " + name + " has saved its state");
                return false;
            }
            closedByUser = false;
            SettingsDialog dialog = SettingsDialog.onPage(requestedPage);
            opening.put(activity, new WeakReference<>(dialog));
            dialog.show(fragments, DIALOG_TAG);
            Utils.runOnMainThread(() -> {
                WeakReference<SettingsDialog> queued = opening.get(activity);
                if (queued != null && queued.get() == dialog) opening.remove(activity);
            });
            host = new WeakReference<>(activity);
            Logger.printInfo(() -> "Settings opened over " + name);
            return true;
        } catch (Exception ex) {
            opening.remove(activity);
            Logger.printException(() -> "Could not open the Hushfacebook settings over " + name, ex);
            return false;
        }
    }

    /**
     * The same, at [key]'s row, or at the top when this build has no such row. An open that can't
     * show leaves no row behind for a later one.
     */
    public static boolean open(Activity activity, String key) {
        requestedSetting = key;
        if (open(activity)) return true;
        requestedSetting = null;
        return false;
    }

    /**
     * After the app lock lets Facebook in: a request the lock held back, from the launcher shortcut
     * or a notification, opens over [activity] now rather than at the next screen.
     */
    public static void openIfRequested(Activity activity) {
        if (openPending) OpenWhenResumed.openWhenSettled(activity);
    }

    /** Whether the screen is over [activity] or waiting to open, so a page opened now would cover it. */
    public static boolean upOrComing(Activity activity) {
        if (openPending) return true;
        WeakReference<Activity> shownOver = host;
        return shownOver != null && shownOver.get() == activity;
    }

    /** Called by the screen when the person closes it, so it isn't reopened. */
    static void onClosedByUser() {
        closedByUser = true;
        host = null;
        requestedPage = null;
    }

    private static void noteIntent(Context context, Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS);
            String asked = intent.getStringExtra(EXTRA_SHOW_SETTING);
            intent.removeExtra(EXTRA_SHOW_SETTING);
            // The launcher entry is open to every app, so only a key's shape gets as far as the log.
            String key = asked != null && asked.matches("[a-z0-9_]{1,64}") ? asked : null;
            requestedAt = SystemClock.elapsedRealtime();
            requestedSetting = key;
            // Null unless an audit asked for a page (AuditMode).
            requestedPage = AuditMode.page(context, intent);
            openPending = true;
            Logger.printInfo(() -> key == null ? "Settings requested by the launcher shortcut"
                    : "Settings requested at " + key);
        }
    }

    /**
     * The screen again after the restart row restarted Facebook, on [page], over the first
     * Facebook screen to resume: the same open the logo's long press and the shortcut lead to.
     */
    static void reopenOn(String page) {
        requestedAt = SystemClock.elapsedRealtime();
        requestedSetting = null;
        requestedPage = page;
        openPending = true;
        Logger.printInfo(() -> "Settings requested by a restart from the settings screen");
    }

    /** The page the next open lands on. Package-visible for tests. */
    static String requestedPage() {
        return requestedPage;
    }

    /** A Facebook-blue disc with an "H", drawn so the shortcut needs no resource in Facebook's APK. */
    private static Bitmap shortcutIcon() {
        final int size = 432; // Adaptive icon canvas: 108dp at xxxhdpi.
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(0xFF0866FF);
        canvas.drawCircle(size / 2f, size / 2f, size * 0.30f, disc);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(size * 0.24f);
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText("H", size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, text);
        return bitmap;
    }
}
