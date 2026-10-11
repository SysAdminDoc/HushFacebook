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
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.facebook.download.ClipboardLink;
import app.morphe.extension.facebook.download.SaveLeftovers;
import app.morphe.extension.facebook.download.SavedFileActions;
import app.morphe.extension.facebook.feed.ReturnRefresh;
import app.morphe.extension.facebook.media.ResumePlayback;
import app.morphe.extension.facebook.menu.MenuLayoutDump;
import app.morphe.extension.facebook.media.TapToPlay;
import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.misc.ScreenTransitions;
import app.morphe.extension.facebook.misc.TextSize;
import app.morphe.extension.facebook.navigation.ReelsTab;
import app.morphe.extension.facebook.stories.StorySeenButton;
import app.morphe.extension.facebook.theme.AccentResources;
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
 * launcher shows only the first few, and some launchers have no shortcut menu at all (#2).
 */
@SuppressWarnings("unused")
public final class SettingsEntry {
    public static final String EXTRA_OPEN_SETTINGS = "app.morphe.extension.facebook.OPEN_SETTINGS";
    /** With {@link #EXTRA_OPEN_SETTINGS}: the key of the setting whose row the screen opens at. */
    public static final String EXTRA_SHOW_SETTING = "app.morphe.extension.facebook.SHOW_SETTING";
    static final String SHORTCUT_ID = "hushfacebook_settings";

    /**
     * The manifest's launcher alias. It points at {@code FbMainTabActivity}, and naming the alias
     * rather than its target keeps the shortcut working if the target is ever renamed.
     */
    private static final String LAUNCHER_ALIAS = "com.facebook.katana.LoginActivity";
    private static final String DIALOG_TAG = "hushfacebook_settings";

    /** A request older than this is dropped rather than opened over some later screen. */
    private static final long REQUEST_LIFETIME_MS = 30_000;

    private static volatile boolean openPending;
    private static volatile long requestedAt;
    /** The setting the pending request opens at, or null for the overview. */
    private static volatile String requestedSetting;
    private static volatile boolean callbacksRegistered;
    /** The activity the screen was last shown over, while the person hasn't closed it. */
    private static WeakReference<Activity> host;
    private static volatile boolean closedByUser;
    /** The long label last pushed, or found already on the shortcut, in this process. */
    private static volatile String publishedLabel;
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
     * by Android left behind is removed, on a worker too. In Facebook's other processes it only
     * starts Lock Facebook's watch ({@link AppLock#watch}).
     */
    public static void onApplicationCreate(Context context) {
        try {
            if (!Utils.isMainProcess()) {
                // Instant Games, the Audience Network ads and Facebook's crash screen have
                // processes of their own. Only the lock watches their screens.
                AppLock.watch(context);
                return;
            }
            if (context instanceof Application && !callbacksRegistered) {
                ((Application) context).registerActivityLifecycleCallbacks(new OpenWhenResumed());
                callbacksRegistered = true;
            }
            // After the entry's, so on a resume the cover goes on last, over anything they opened.
            // Whether Facebook is locked is settled before a settings request is judged, since that
            // waits for a post, and covering() holds it back. A window Facebook opens later lands
            // above the cover, which then goes back on top (AppLock.Cover).
            AppLock.watch(context);
            ReturnRefresh.register(context);
            TextSize.application(context);
            // Only with the theme in the build, so its class and palette aren't loaded otherwise.
            if (SettingsStatus.materialYouTheme()) MaterialYouTheme.watchWindows(context);
            // Accent color's route five, for the colours views inflated from XML read themselves.
            if (SettingsStatus.accentColor()) AccentResources.watchActivities(context);
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
        });
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
            ShortcutManager manager = app.getSystemService(ShortcutManager.class);
            if (manager == null) return;
            String longLabel = L10n.t(app, "Hushfacebook settings");
            // Set before the attempt, so a shortcut that can't be pushed isn't tried on every screen.
            publishedLabel = longLabel;
            ShortcutInfo existing = ours(manager);
            // One labelled in another language, or behind Facebook's, is pushed again below.
            if (existing != null && existing.getRank() == 0
                    && longLabel.contentEquals(existing.getLongLabel())) return;
            // Evicts the lowest-ranked dynamic shortcut when Facebook's own fill the limit.
            manager.pushDynamicShortcut(shortcut(app, longLabel));
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
            ShortcutManager manager = app.getSystemService(ShortcutManager.class);
            if (manager == null) return;
            ShortcutInfo existing = ours(manager);
            if (existing == null) {
                publishShortcutNow(app);
                return;
            }
            final int rank = existing.getRank();
            if (rank == 0) return;
            CharSequence label = existing.getLongLabel();
            manager.pushDynamicShortcut(shortcut(app, label != null ? label : L10n.t(app, "Hushfacebook settings")));
            Logger.printInfo(() -> "Settings entry: launcher shortcut moved back in front from rank " + rank);
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not put the shortcut back in front", ex);
        }
    }

    /** The Hushfacebook shortcut among the dynamic ones, or null. */
    private static ShortcutInfo ours(ShortcutManager manager) {
        for (ShortcutInfo existing : manager.getDynamicShortcuts()) {
            if (SHORTCUT_ID.equals(existing.getId())) return existing;
        }
        return null;
    }

    /** What the launcher shortcut sends: Facebook's launcher entry, asked to show the screen. */
    private static Intent openIntent(Context app) {
        return new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(app.getPackageName(), LAUNCHER_ALIAS))
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

    private static ShortcutInfo shortcut(Context app, CharSequence longLabel) {
        Intent intent = openIntent(app);
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
            noteIntent(activity.getIntent());
        } catch (Throwable ex) {
            Logger.printException(() -> "Settings entry: onActivityCreate failure", ex);
        }
    }

    /** Injected at the start of every Facebook activity's {@code onNewIntent}. */
    public static void onNewIntent(Activity activity, Intent intent) {
        try {
            SavedFileActions.receive(intent);
            noteIntent(intent);
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
     * <p>No switch reads this, so Pause leaves it working: it's the way back to the switch that
     * resumes Hushfacebook. Anything that goes wrong leaves the logo with Facebook's listener.
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
     * Times a press on the Facebook logo. Every touch still reaches the logo as it did, so a tap
     * is Facebook's. A press held for the phone's long-press time opens this screen, with the
     * same vibration a long press gives, and the rest of that touch is kept from the logo, so
     * letting go isn't also a tap.
     *
     * <p>It doesn't use the logo's own long-press handling. Facebook gives the logo an
     * accessibility delegate that puts back the long-press state it saw before the logo had any
     * listeners, and it does that each time an accessibility service reads the screen: after one
     * read by TalkBack, a password manager or a UI dump, a long-click listener would stop firing.
     */
    static final class LogoPress implements View.OnTouchListener {
        private final Runnable fire = this::fire;
        /** The logo while a press on it is being timed. */
        private View pressed;
        private float downX;
        private float downY;
        /** This touch opened the screen, so the rest of it isn't the logo's. */
        private boolean opened;

        @Override
        public boolean onTouch(View logo, MotionEvent event) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        stop();
                        opened = false;
                        // A long press Facebook gave the logo itself stays Facebook's.
                        if (logo.isLongClickable()) return false;
                        pressed = logo;
                        downX = event.getX();
                        downY = event.getY();
                        logo.postDelayed(fire, ViewConfiguration.getLongPressTimeout());
                        return false;
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
        }

        @Override
        public void onActivityPaused(Activity activity) {
            SavedFileActions.onPaused(activity);
            ClipboardLink.onPaused(activity);
            StorySeenButton.activityPaused(activity);
            ScreenTransitions.activityPaused(activity);
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
                // Already up: a notification's button still takes it to the row it names.
                if (shown instanceof SettingsDialog) ((SettingsDialog) shown).showRequestedSetting();
                return true;
            }
            if (fragments.isStateSaved()) {
                Logger.printInfo(() -> "Settings wait: " + name + " has saved its state");
                return false;
            }
            closedByUser = false;
            new SettingsDialog().show(fragments, DIALOG_TAG);
            host = new WeakReference<>(activity);
            Logger.printInfo(() -> "Settings opened over " + name);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the Hushfacebook settings over " + name, ex);
            return false;
        }
    }

    /**
     * After the app lock lets Facebook in: a request the lock held back, from the launcher shortcut
     * or a notification, opens over [activity] now rather than at the next screen.
     */
    public static void openIfRequested(Activity activity) {
        if (openPending) OpenWhenResumed.openWhenSettled(activity);
    }

    /** Called by the screen when the person closes it, so it isn't reopened. */
    static void onClosedByUser() {
        closedByUser = true;
        host = null;
    }

    private static void noteIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS);
            String asked = intent.getStringExtra(EXTRA_SHOW_SETTING);
            intent.removeExtra(EXTRA_SHOW_SETTING);
            // The launcher entry is open to every app, so only a key's shape gets as far as the log.
            String key = asked != null && asked.matches("[a-z0-9_]{1,64}") ? asked : null;
            requestedAt = SystemClock.elapsedRealtime();
            requestedSetting = key;
            openPending = true;
            Logger.printInfo(() -> key == null ? "Settings requested by the launcher shortcut"
                    : "Settings requested at " + key);
        }
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
