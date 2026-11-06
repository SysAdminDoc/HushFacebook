/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.provider.Settings.Global;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;


import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.download.SavedFileActions;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.facebook.settings.ShimmerTitle;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.shared.settings.preference.ReadOnlyPreferences;

/**
 * Lock Facebook: with its switch on, a cold start and a return after the chosen time cover every
 * Facebook screen and ask for the phone's own screen lock, its fingerprint, face, PIN, pattern or
 * password, through Android's BiometricPrompt. Only a check that passes takes the cover away. One
 * that fails or is cancelled leaves Facebook covered, with an Unlock button to ask again, and Back
 * sends Facebook to the background.
 *
 * <p>Facebook carries an app lock of its own, {@code AuthAppLockState}, but in 577, 580 and 581
 * nothing outside its own class reads the "lock due" flag its start sets, and its other readers
 * only keep the screen out of the recent apps view, log, or filter a promotion. There's no lock
 * screen behind it to turn on, so this is the extension's own.
 *
 * <p>Its own activity callbacks drive it ({@link #watch}), registered in every one of Facebook's
 * processes: Instant Games, the Audience Network ads and Facebook's crash screen run in processes
 * of their own, and each process locks on its own cold start and its own time away.
 *
 * <p>Facebook is in front while one of its screens is started and isn't a picture-in-picture
 * window. "Away" starts when the last of those stops, or shrinks into a picture-in-picture window,
 * which Android reports as a pause with the screen already in that mode. The floating window keeps
 * playing and is never covered, but the first full-screen Facebook screen after the chosen time
 * asks, the video brought back to full screen included. A reply typed into a notification runs
 * without any screen, so it starts nothing. A rotation, or a screen Facebook rebuilds for a new
 * configuration, isn't a leave. While the switch is on, Android 13 and later keep Facebook's
 * screens out of the recent apps view, as Facebook's own lock does.
 *
 * <p>Off, settings that aren't ready yet, or a phone without a screen lock, and nothing is covered
 * or asked. Turning the switch on doesn't lock the screen in front. A pause doesn't turn it off,
 * whatever paused Hushfacebook: the Pause switch, the marker file, or safe mode after crashed
 * starts. Each of those is in reach of someone holding the phone, and the switch, which sits
 * behind the lock, is the way off ({@link Settings#APP_LOCK} keeps its value while paused).
 *
 * <p>Only the main process writes the settings file, and a setting holds the value its process
 * loaded at start. So Facebook's other processes, such as its games and ad screens, read the switch
 * and the time away fresh from the file at each check ({@link #lockOn}, {@link #lockAfter}), and a
 * change reaches a game that was already running. They read the XML themselves rather than through
 * SharedPreferences ({@link #readSettingsFile}), whose first load can delete a file the main process
 * is writing. No side process opens the settings file as SharedPreferences at all: the settings
 * class reads it through {@link ReadOnlyPreferences} there, which changes nothing on disk.
 *
 * <p>The main process also leaves a private note in the app's no-backup folder with the boot and
 * the last moment Facebook was unlocked and in use ({@link #shareUnlock}). A side process starting
 * within Lock after of it, on the same boot, doesn't ask again; locking Facebook takes the note away,
 * and a side process only reads it.
 *
 * <p>While locked, the screen's windows are read from the framework's list ({@link #sweep}). One
 * that lands above the cover, focusable or not, stops taking touches and the cover goes back on top.
 */
public final class AppLock {
    /** How long Facebook may be away before a return asks again. */
    public enum After {
        IMMEDIATELY(0L, "immediately"),
        ONE_MINUTE(60_000L, "1_minute"),
        FIVE_MINUTES(5 * 60_000L, "5_minutes"),
        FIFTEEN_MINUTES(15 * 60_000L, "15_minutes"),
        ONE_HOUR(60 * 60_000L, "1_hour");

        public final long millis;

        /** What a settings file holds for this choice. It never changes once written. */
        public final String fileValue;

        After(long millis, String fileValue) {
            this.millis = millis;
            this.fileValue = fileValue;
        }

        /** The choice a settings file names, or null when it names none this build knows. */
        @Nullable
        public static After fromFile(@Nullable Object value) {
            if (!(value instanceof String)) return null;
            for (After after : values()) {
                if (after.fileValue.equals(value)) return after;
            }
            return null;
        }
    }

    /** Asks for the phone's screen lock over [activity]: Android's prompt, or a test's stand-in. */
    interface Prompter {
        void ask(Activity activity, Answer answer);
    }

    /** What a check came to. Called on the main thread. */
    interface Answer {
        void unlocked();

        /** The check ended without passing, with Android's reason, which it words in the phone's language. */
        void refused(int code, @Nullable CharSequence message);
    }

    /** BiometricPrompt's code for a check Android itself called off, as when the screen went away. */
    static final int CALLED_OFF = BiometricPrompt.BIOMETRIC_ERROR_CANCELED;

    private static final long NEVER = -1;

    /** How long after a check ends a cover that hasn't got the focus back goes on top again. */
    static final long SETTLE_MS = 500;

    static Prompter prompter = AppLock::askAndroid;

    /** Facebook screens between their start and their stop. */
    private static int started;
    /** The started screens that are picture-in-picture windows, as their last pause found them. */
    private static final Set<Activity> floating = Collections.newSetFromMap(new WeakHashMap<>());
    /**
     * When the last full-screen Facebook screen left, or {@link #NEVER} while one is in front or the
     * lock is off.
     */
    private static long leftAt = NEVER;
    /** A check passed in this process, or it ran with the lock off, so a start alone doesn't lock. */
    private static boolean everUnlocked;
    /** The screens are covered until a check passes. */
    private static boolean locked;
    /** The Facebook screen in front, between its resume and its pause. */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    /** A check is on screen. */
    private static boolean asking;
    /** The screen the check was asked over, so its going away ends the wait for an answer. */
    @Nullable
    private static WeakReference<Activity> askedOn;
    private static long promptGeneration;
    @Nullable private static CancellationSignal promptCancellation;
    /** The person called the last check off, so the next one waits for Unlock or a return. */
    private static boolean declined;
    /** Android's reason the last check ended, shown on the cover until the next one. */
    @Nullable
    private static CharSequence refusal;
    private static final Map<Activity, Cover> covers = new WeakHashMap<>();
    /** How often a locked screen looks for a window that landed above its cover. */
    static final long WATCH_MS = 100;

    /** The window lists of this process: a test's stand-in, or the framework's own list. */
    interface Roots {
        /** Every window's root view in the order they were added, or null when this phone won't say. */
        @Nullable
        List<View> list();
    }

    static Roots roots = AppLock::frameworkRoots;
    /** The token of a screen's own window, which its sub-windows carry. A test's stand-in where a window manager has none. */
    static java.util.function.Function<Activity, android.os.IBinder> tokenOf =
            screen -> screen.getWindow().getDecorView().getApplicationWindowToken();
    private static boolean rootsFailureLogged;
    static final int WINDOW_CHECK_UNKNOWN = 0;
    static final int WINDOW_CHECK_WORKS = 1;
    static final int WINDOW_CHECK_UNAVAILABLE = -1;
    /** Whether the last read of the framework's window list worked, for the diagnostics report. */
    private static int windowCheck = WINDOW_CHECK_UNKNOWN;

    /**
     * The [LOCK FACEBOOK] section of the diagnostic report: whether the lock can read the window
     * list, which a hidden-API block on a newer Android would take away without any sign, leaving
     * the lock noticing only windows that take the focus. Nothing while the lock is off.
     */
    public static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override public String title() { return "LOCK FACEBOOK"; }
        @Override public List<String> lines() { return windowCheckLines(); }
        @Override public boolean isAppState() { return true; }
    };

    /** The report's line for the window check, or nothing when the lock is off. */
    static List<String> windowCheckLines() {
        if (!switchedOn()) return Collections.emptyList();
        switch (windowCheck) {
            case WINDOW_CHECK_WORKS:
                return Collections.singletonList("Window check: works, windows above the cover are found");
            case WINDOW_CHECK_UNAVAILABLE:
                return Collections.singletonList("Window check: unavailable on this phone, so only windows that "
                        + "take the focus are noticed");
            default:
                return Collections.singletonList("Window check: not run yet, it starts the first time Facebook is covered");
        }
    }
    private static boolean watchingWindows;
    /** Windows made untouchable while locked, with the window manager that holds each. */
    private static final Map<View, WindowManager> untouchable = new IdentityHashMap<>();
    private static final Set<Activity> keptFromRecents = Collections.newSetFromMap(new WeakHashMap<>());

    /** The application this process's callbacks went on, so a second start of the hook adds none. */
    private static WeakReference<Application> watching = new WeakReference<>(null);

    private AppLock() {
    }

    /**
     * From the settings entry's application hook, in every one of Facebook's processes. Its
     * callbacks go on after the entry's, so on a resume the cover is the newest window, over
     * anything the entry's callbacks opened. Facebook's application only wraps the callbacks it's
     * handed, in any process, so registering them early in a side process is safe.
     */
    public static void watch(Context context) {
        if (!(context instanceof Application)) return;
        Application application = (Application) context;
        if (watching.get() == application) return;
        application.registerActivityLifecycleCallbacks(new Watcher());
        watching = new WeakReference<>(application);
    }

    /** The lock's own activity callbacks. */
    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
            started(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            resumed(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            paused(activity);
        }

        @Override
        public void onActivityStopped(Activity activity) {
            stopped(activity);
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            destroyed(activity);
        }
    }

    /** Whether the lock is on: its switch, which reads the same paused or not, once the settings are ready. */
    private static boolean switchedOn() {
        return Utils.settingsReady() && lockOn();
    }

    /** The lock's switch: the setting in the main process, the settings file as it is now in any other. */
    static boolean lockOn() {
        if (!Utils.isMainProcess()) {
            try {
                FileSettings file = readSettingsFile();
                if (file != null) return file.on != null ? file.on : Settings.APP_LOCK.defaultValue;
            } catch (RuntimeException failure) {
                Logger.printException(() -> "App lock: could not read the switch from the settings file", failure);
            }
        }
        return Settings.APP_LOCK.get();
    }

    /** How long Facebook may be away: the setting in the main process, the settings file as it is now in any other. */
    static After lockAfter() {
        if (!Utils.isMainProcess()) {
            try {
                FileSettings file = readSettingsFile();
                if (file != null) return file.after == null ? Settings.APP_LOCK_AFTER.defaultValue : After.valueOf(file.after);
            } catch (RuntimeException failure) {
                Logger.printException(() -> "App lock: could not read the time away from the settings file", failure);
            }
        }
        return Settings.APP_LOCK_AFTER.get();
    }

    /** The lock's two settings as the settings file holds them. A null is one the file doesn't name. */
    static final class FileSettings {
        @Nullable final Boolean on;
        @Nullable final String after;

        FileSettings(@Nullable Boolean on, @Nullable String after) {
            this.on = on;
            this.after = after;
        }
    }

    /** This process's read-only view of the settings file, for a side process. */
    @Nullable
    private static ReadOnlyPreferences sideFile;
    @Nullable
    private static Context sideFileContext;

    /**
     * The lock's settings as the settings file holds them now, read in a side process straight from
     * its XML and nothing else ({@link ReadOnlyPreferences}), which keeps the parse until the file or
     * its {@code .bak} copy changes. Null when the file can't be read whole and never was.
     */
    @Nullable
    static FileSettings readSettingsFile() {
        Context context = Utils.getContext();
        if (context == null) return null;
        ReadOnlyPreferences file;
        synchronized (AppLock.class) {
            if (sideFile == null || sideFileContext != context) {
                sideFile = new ReadOnlyPreferences(context, Setting.PREFERENCES_NAME);
                sideFileContext = context;
            }
            file = sideFile;
        }
        Map<String, Object> values = file.snapshot();
        if (values == null) return null;
        Object on = values.get(Settings.APP_LOCK.key);
        Object after = values.get(Settings.APP_LOCK_AFTER.key);
        return new FileSettings(on instanceof Boolean ? (Boolean) on : null, after instanceof String ? (String) after : null);
    }

    /** Whether a Facebook screen is in front: started, and not a picture-in-picture window. */
    private static boolean inFront() {
        return started > floating.size();
    }

    /** Whether [activity] is a picture-in-picture window. */
    private static boolean inPictureInPicture(Activity activity) {
        return activity.isInPictureInPictureMode();
    }

    /**
     * From the lock's callbacks, as a Facebook screen starts. The first start with no
     * Facebook screen in front is a return, and it locks when the lock is due. A picture-in-picture
     * window that starts again, as it does when the phone is unlocked, isn't one: it floats, so the
     * time away keeps running until a full-screen Facebook screen comes back.
     */
    public static void started(Activity activity) {
        boolean returning = !inFront();
        started++;
        try {
            if (inPictureInPicture(activity)) {
                floating.add(activity);
                return;
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not judge a floating start", failure);
        }
        if (returning) returned(activity);
    }

    /**
     * A Facebook screen came to the front with none there before it: a start, or a
     * picture-in-picture window brought back to full screen. Locks when the lock is due.
     */
    private static void returned(Activity activity) {
        try {
            if (!Utils.settingsReady()) return;
            if (!lockOn() || !canLock(activity)) {
                // Nothing to ask with, or nothing asked for: turning the lock on later won't lock this
                // screen. A lock already up goes too, since with the phone's screen lock gone no check
                // could ever pass.
                if (locked) release(null);
                everUnlocked = true;
                leftAt = NEVER;
                return;
            }
            if (!everUnlocked && !Utils.isMainProcess() && sharedUnlockFresh(SystemClock.elapsedRealtime())) {
                // The main process was unlocked a moment ago, so a side process doesn't ask again for it.
                everUnlocked = true;
            }
            if (!locked && due(SystemClock.elapsedRealtime())) {
                locked = true;
                shareUnlock(NEVER);
                Logger.printInfo(() -> "App lock: locked on " + (everUnlocked ? "a return" : "a cold start"));
            }
            declined = false;
            refusal = null;
            leftAt = NEVER;
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not judge a start", failure);
        }
    }

    /** Whether a return now asks: always on a cold start, otherwise after the chosen time away. */
    static boolean due(long now) {
        if (!everUnlocked) return true;
        return leftAt != NEVER && now - leftAt >= lockAfter().millis;
    }

    /**
     * From the lock's callbacks, as a Facebook screen leaves the front. A screen that
     * pauses as a picture-in-picture window no longer counts as Facebook in front, and when it was
     * the last one that did, the time away starts.
     */
    public static void paused(Activity activity) {
        try {
            if (front.get() == activity) front = new WeakReference<>(null);
            // Facebook was unlocked right up to this pause, which a game's start in another process reads.
            if (!locked && everUnlocked && switchedOn()) shareUnlock(SystemClock.elapsedRealtime());
            if (!inPictureInPicture(activity) || floating.contains(activity) || !inFront()) return;
            floating.add(activity);
            if (!inFront()) leftAt = SystemClock.elapsedRealtime();
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not judge a pause", failure);
        }
    }

    /**
     * From the lock's callbacks, as a Facebook screen stops. The last full-screen one to
     * stop starts the time away.
     */
    public static void stopped(Activity activity) {
        boolean wasInFront = inFront();
        if (started > 0) started--;
        floating.remove(activity);
        if (wasInFront && !inFront() && !activity.isChangingConfigurations()) {
            leftAt = SystemClock.elapsedRealtime();
            if (!locked && everUnlocked && switchedOn()) shareUnlock(leftAt);
        }
    }

    /**
     * From the lock's callbacks, as a Facebook screen comes to the front: covered while
     * locked, and the check asked for unless the person just called one off. A picture-in-picture
     * window is left as it is, and one brought back to full screen is a return.
     */
    public static void resumed(Activity activity) {
        try {
            front = new WeakReference<>(activity);
            if (floating.contains(activity) && !inPictureInPicture(activity)) {
                boolean returning = !inFront();
                floating.remove(activity);
                if (returning) returned(activity);
            }
            if (!switchedOn()) {
                showInRecents(activity);
                if (locked) release(null);
                return;
            }
            keepFromRecents(activity);
            if (locked && !canLock(activity)) {
                // The phone's screen lock was taken away while Facebook was locked: nothing could pass.
                Logger.printInfo(() -> "App lock: the phone has no screen lock now, so Facebook opens");
                release(null);
            }
            if (!locked || inPictureInPicture(activity)) return;
            cover(activity);
            // A cover already up is only raised, which doesn't start the look for windows above it.
            watchWindows();
            if (!asking && !declined) ask(activity);
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not lock a screen", failure);
        }
    }

    /** From the lock's callbacks, as a Facebook screen goes away for good. */
    public static void destroyed(Activity activity) {
        Cover cover = covers.remove(activity);
        if (cover != null) cover.close();
        keptFromRecents.remove(activity);
        floating.remove(activity);
        forgetTouches(activity);
        if (front.get() == activity) front = new WeakReference<>(null);
        // Android ends a check whose screen goes, and an answer that never comes mustn't stop the next one.
        WeakReference<Activity> asked = askedOn;
        if (asked != null && asked.get() == activity) {
            retirePrompt();
        }
    }

    /** Whether Facebook is covered, so nothing of Hushfacebook's opens over the cover. */
    public static boolean covering() {
        return locked;
    }

    /** Whether [activity] carries a cover right now. */
    static boolean covered(Activity activity) {
        Cover cover = covers.get(activity);
        return cover != null && cover.isShowing();
    }

    /**
     * Whether the phone has a screen lock this could ask for. Without one Facebook is never covered,
     * and a cover already up goes, since no check could pass. Android 9's prompt can't fall back to
     * the PIN, so a phone without a fingerprint would stay covered for good: there's no lock below
     * Android 10.
     */
    public static boolean canLock(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    private static void keepFromRecents(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33 && keptFromRecents.add(activity)) activity.setRecentsScreenshotEnabled(false);
    }

    private static void showInRecents(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33 && keptFromRecents.remove(activity)) activity.setRecentsScreenshotEnabled(true);
    }

    private static void cover(Activity activity) {
        Cover cover = covers.get(activity);
        if (cover != null && cover.isShowing()) {
            // Already up: back on top, over anything Facebook opened on the screen while it was away.
            raise(activity);
            return;
        }
        if (cover == null) {
            try {
                cover = new Cover(activity);
                covers.put(activity, cover);
            } catch (RuntimeException failure) {
                // A screen that can't be covered isn't left showing: Facebook goes to the background.
                Logger.printException(() -> "App lock: could not cover " + activity.getClass().getSimpleName(), failure);
                activity.moveTaskToBack(true);
                return;
            }
        }
        cover.showReason();
        if (!cover.isShowing()) cover.show();
        watchWindows();
    }

    /**
     * Puts a fresh cover on [activity] and then takes the old one away, so the cover is the newest
     * of the screen's windows again and nothing shows between the two. A dialog or sheet Facebook
     * opened over the old cover ends up under it.
     */
    private static void raise(Activity activity) {
        Cover old = covers.get(activity);
        if (old == null || activity.isFinishing() || activity.isDestroyed()) return;
        // Its loss of focus to the fresh one is no reason to move again.
        old.retired = true;
        Cover fresh;
        try {
            fresh = new Cover(activity);
            fresh.show();
        } catch (RuntimeException failure) {
            old.retired = false;
            Logger.printException(() -> "App lock: could not put the cover back on top", failure);
            return;
        }
        covers.put(activity, fresh);
        old.close();
    }

    /**
     * After [cover] lost the focus, or a check ended without it getting the focus back. When
     * Facebook is still locked, [activity] is still in front and no check is on screen, the window
     * holding the focus is one Facebook opened on the screen over the cover, so a fresh cover goes
     * on top of it. Android's own windows, the notification shade among them, sit above every app
     * window anyway, and a cover put back under one of them changes nothing.
     */
    private static void keepOnTop(Activity activity, Cover cover) {
        if (!locked || asking || cover.retired || cover.focused || covers.get(activity) != cover) return;
        if (front.get() != activity) return;
        Logger.printInfo(() -> "App lock: another window took the focus from the cover, so the cover went back on top");
        raise(activity);
    }

    /**
     * Asks for the screen lock once [activity] has finished coming to the front, which Android
     * wants of an app that asks.
     */
    private static void ask(Activity activity) {
        retirePrompt();
        long generation = promptGeneration;
        asking = true;
        askedOn = new WeakReference<>(activity);
        Utils.runOnMainThread(() -> {
            if (!ownsPrompt(generation, activity)) return;
            if (!locked || activity.isFinishing() || activity.isDestroyed()) {
                retirePrompt();
                return;
            }
            try {
                prompter.ask(activity, new Answer() {
                    @Override
                    public void unlocked() {
                        if (!ownsPrompt(generation, activity)) return;
                        Logger.printInfo(() -> "App lock: unlocked");
                        release(activity);
                    }

                    @Override
                    public void refused(int code, @Nullable CharSequence message) {
                        if (!ownsPrompt(generation, activity)) return;
                        retirePrompt();
                        // Android calling a check off, as when the screen goes, isn't the person saying no.
                        declined = code != CALLED_OFF;
                        refusal = message;
                        Logger.printInfo(() -> "App lock: the check ended with code " + code + ", Facebook stays covered");
                        for (Cover cover : covers.values()) cover.showReason();
                        // A dialog Facebook opened while the check was up sits over the cover, which
                        // then never gets the focus back to notice. Once the check has gone, it does.
                        Activity shown = front.get();
                        if (shown == null) return;
                        Utils.runOnMainThreadDelayed(() -> {
                            Cover cover = covers.get(shown);
                            if (cover != null) keepOnTop(shown, cover);
                        }, SETTLE_MS);
                    }
                });
            } catch (Throwable failure) {
                if (!ownsPrompt(generation, activity)) return;
                retirePrompt();
                declined = true;
                Logger.printException(() -> "App lock: could not ask for the screen lock", failure);
            }
        });
    }

    private static boolean ownsPrompt(long generation, Activity activity) {
        return generation == promptGeneration && askedOn != null && askedOn.get() == activity;
    }

    private static void retirePrompt() {
        promptGeneration++;
        asking = false;
        askedOn = null;
        CancellationSignal cancellation = promptCancellation;
        promptCancellation = null;
        if (cancellation != null) {
            try {
                cancellation.cancel();
            } catch (Throwable failure) {
                Logger.printException(() -> "App lock: could not cancel the retired screen-lock check", failure);
            }
        }
    }

    /** Takes every cover away, after a check passed or with the lock off. */
    private static void release(@Nullable Activity unlockedOn) {
        retirePrompt();
        locked = false;
        everUnlocked = true;
        leftAt = NEVER;
        declined = false;
        refusal = null;
        for (Cover cover : new ArrayList<>(covers.values())) cover.close();
        covers.clear();
        restoreTouch();
        shareUnlock(SystemClock.elapsedRealtime());
        Activity shown = unlockedOn != null ? unlockedOn : front.get();
        if (shown != null && !shown.isFinishing()) {
            // A saved file Facebook was asked to open while it was locked is opened now.
            SavedFileActions.onUnlocked(shown);
            // An opening pause owed comes up once the lock is off.
            OpeningPause.unlocked(shown);
        }
        if (unlockedOn != null && !unlockedOn.isFinishing()) SettingsEntry.openIfRequested(unlockedOn);
    }

    /** Back to a fresh process, covers closed. */
    static void forgetForTests() {
        retirePrompt();
        for (Cover cover : new ArrayList<>(covers.values())) cover.close();
        covers.clear();
        keptFromRecents.clear();
        floating.clear();
        front = new WeakReference<>(null);
        started = 0;
        leftAt = NEVER;
        everUnlocked = false;
        locked = false;
        asking = false;
        askedOn = null;
        declined = false;
        refusal = null;
        prompter = AppLock::askAndroid;
        sideFile = null;
        sideFileContext = null;
        roots = AppLock::frameworkRoots;
        tokenOf = screen -> screen.getWindow().getDecorView().getApplicationWindowToken();
        rootsFailureLogged = false;
        windowCheck = WINDOW_CHECK_UNKNOWN;
        readersFound = false;
        windowGlobalName = "android.view.WindowManagerGlobal";
        watchingWindows = false;
        restoreTouch();
        shareUnlock(NEVER);
    }

    /** Starts the look for windows above the cover, which runs while Facebook is locked. */
    private static void watchWindows() {
        if (watchingWindows || !locked) return;
        watchingWindows = true;
        Utils.runOnMainThreadDelayed(AppLock::watchTick, WATCH_MS);
    }

    /**
     * One look, and the next only while a cover is up on the screen in front. With Facebook away
     * there's nothing to look at, so the poll stops there instead of waking the main thread ten
     * times a second for hours; {@link #resumed} starts it again on the way back.
     */
    private static void watchTick() {
        watchingWindows = false;
        if (!locked) return;
        Activity shown = front.get();
        if (shown == null || !covered(shown)) return;
        sweep(shown);
        watchWindows();
    }

    /**
     * A window of [activity]'s above its cover takes taps whether or not it can take the focus: a
     * popup, a tooltip bubble, anything added to the window manager with FLAG_NOT_FOCUSABLE. The
     * cover only learns of windows that take the focus, so while locked the screen's windows are
     * read. The list is in the order they were added, not the order Android layers them, so two
     * kinds are judged by what they are rather than where they sit. A sub-window attached to the
     * screen's own window (a popup menu, a spinner list) is layered with the screen, under the
     * cover, wherever it sits in the list, so it is left alone. A window of the screen's whose type
     * is above the application range, such as an overlay, is layered above every cover, so it is
     * flagged wherever it sits. Whatever is flagged stops taking touches, so a tap goes through to
     * the cover, and the cover goes back on top of what it can. Fails safe: when this phone won't
     * give its window list, nothing changes and the cover stays as it was.
     */
    static void sweep(Activity activity) {
        try {
            if (!locked || activity.isFinishing() || activity.isDestroyed()) return;
            Cover cover = covers.get(activity);
            if (cover == null || !cover.isShowing()) return;
            List<View> all = roots.list();
            if (all == null) return;
            Window window = cover.getWindow();
            View mine = window == null ? null : window.getDecorView();
            int at = mine == null ? -1 : all.lastIndexOf(mine);
            if (at < 0) return;
            android.os.IBinder own = tokenOf.apply(activity);
            boolean foreign = false;
            for (int i = 0; i < all.size(); i++) {
                View root = all.get(i);
                if (isCover(root) || !belongsTo(root, activity)) continue;
                WindowManager.LayoutParams params = root.getLayoutParams() instanceof WindowManager.LayoutParams
                        ? (WindowManager.LayoutParams) root.getLayoutParams() : null;
                if (params != null && isSubWindowOf(params, own)) continue;
                boolean above = i > at;
                boolean overlay = params != null && params.type > WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
                if (!above && !overlay) continue;
                boolean stopped = stopTouches(activity, root);
                // A window that sits below the cover is only worth a fresh cover the once, when it is first stopped.
                if (above || stopped) foreign = true;
            }
            if (foreign) {
                Logger.printInfo(() -> "App lock: a window opened above the cover, so the cover went back on top of it");
                raise(activity);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not look for windows above the cover", failure);
        }
    }

    /** A sub-window type (popup, panel, media) whose parent is the screen's own window. */
    private static boolean isSubWindowOf(WindowManager.LayoutParams params, @Nullable android.os.IBinder own) {
        return params.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW
                && params.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW
                && own != null && params.token == own;
    }

    private static boolean isCover(View root) {
        for (Cover cover : covers.values()) {
            Window window = cover.getWindow();
            if (window != null && window.getDecorView() == root) return true;
        }
        return false;
    }

    /** Whether [root] is one of [activity]'s windows: made with it, or sharing its window token. */
    private static boolean belongsTo(View root, Activity activity) {
        Context context = root.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context == activity) return true;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        android.os.IBinder token = root.getApplicationWindowToken();
        return token != null && token == tokenOf.apply(activity);
    }

    /** Takes the touches from [root] unless it is already flagged, and says whether the lock did. */
    private static boolean stopTouches(Activity activity, View root) {
        if (untouchable.containsKey(root) || !(root.getLayoutParams() instanceof WindowManager.LayoutParams)) return false;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
        if ((params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) return false;
        WindowManager manager = activity.getWindowManager();
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            manager.updateViewLayout(root, params);
            untouchable.put(root, manager);
            return true;
        } catch (RuntimeException gone) {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            return false;
        }
    }

    /**
     * Lets go of the windows of a screen that's gone, as a rotation while locked destroys one. They
     * go with it, so there are no touches to give back, and holding them would keep the screen alive.
     */
    private static void forgetTouches(Activity activity) {
        WindowManager own = activity.getWindowManager();
        for (Map.Entry<View, WindowManager> entry : new ArrayList<>(untouchable.entrySet())) {
            if (entry.getValue() == own || belongsTo(entry.getKey(), activity)) untouchable.remove(entry.getKey());
        }
    }

    /** Whether the lock holds a window it made untouchable, for a test. */
    static int untouchableCount() {
        return untouchable.size();
    }

    /** Gives back the touches taken from other windows while locked, only the ones the lock took. */
    private static void restoreTouch() {
        for (Map.Entry<View, WindowManager> entry : new ArrayList<>(untouchable.entrySet())) {
            try {
                View root = entry.getKey();
                if (root.getLayoutParams() instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
                    params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    entry.getValue().updateViewLayout(root, params);
                }
            } catch (RuntimeException gone) {
                // The window went while locked.
            }
        }
        untouchable.clear();
    }

    /** The framework's window list reader, found once: its holder, the view list, or the name lookups. */
    private static Object windowGlobal;
    @Nullable private static Field viewsField;
    @Nullable private static Method rootNames;
    @Nullable private static Method rootView;
    private static boolean readersFound;
    /** The framework class that holds the window list. A test names another to see the lock without it. */
    static String windowGlobalName = "android.view.WindowManagerGlobal";

    private static void findReaders() throws ReflectiveOperationException {
        if (readersFound) return;
        Class<?> global = Class.forName(windowGlobalName);
        windowGlobal = global.getMethod("getInstance").invoke(null);
        try {
            Field views = global.getDeclaredField("mViews");
            views.setAccessible(true);
            viewsField = views;
        } catch (ReflectiveOperationException | RuntimeException blocked) {
            viewsField = null;
        }
        try {
            rootNames = global.getMethod("getViewRootNames");
            rootView = global.getMethod("getRootView", String.class);
        } catch (ReflectiveOperationException | RuntimeException blocked) {
            rootNames = null;
            rootView = null;
        }
        readersFound = true;
    }

    /**
     * This process's windows, for a Debug logging read of what Facebook shows over its screens (the
     * reel long-press menu's, in MenuLayoutDump). Null when the phone won't give them.
     */
    @Nullable
    public static List<View> windowsForDiagnostics() {
        return frameworkRoots();
    }

    /**
     * This process's windows from the framework's own list, in the order they were added, which is
     * not the order Android layers them. Reads WindowManagerGlobal's list of views, and when a phone
     * won't give that, its root names. Null when neither is reachable. The reflection objects are
     * found once, since this runs ten times a second while a cover is up.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static List<View> frameworkRoots() {
        try {
            findReaders();
            Field views = viewsField;
            if (views != null) {
                try {
                    Object list = views.get(windowGlobal);
                    if (list instanceof List) {
                        windowCheck = WINDOW_CHECK_WORKS;
                        return new ArrayList<>((List<View>) list);
                    }
                } catch (ReflectiveOperationException | RuntimeException blocked) {
                    // Fall back to the names below.
                }
            }
            Method names = rootNames;
            Method lookup = rootView;
            if (names == null || lookup == null) throw new NoSuchMethodException("getViewRootNames");
            String[] all = (String[]) names.invoke(windowGlobal);
            List<View> found = new ArrayList<>();
            for (String name : all) {
                Object view = lookup.invoke(windowGlobal, name);
                if (view instanceof View) found.add((View) view);
            }
            windowCheck = WINDOW_CHECK_WORKS;
            return found;
        } catch (Throwable unavailable) {
            windowCheck = WINDOW_CHECK_UNAVAILABLE;
            if (!rootsFailureLogged) {
                rootsFailureLogged = true;
                Logger.printInfo(() -> "App lock: this phone doesn't give its window list, so only windows that take the focus are noticed");
            }
            return null;
        }
    }

    /** The file the main process leaves for a side process: the boot and when Facebook was last unlocked. */
    private static File shareFile(Context context) {
        return new File(context.getNoBackupFilesDir(), "applock-unlocked");
    }

    /** How far the boot time may drift between two reads of the clocks and still be the same boot. */
    static final long BOOT_SLACK_MS = 60_000;

    /** When this boot began on the wall clock. A restart moves it by the time the phone was off. */
    private static long bootWallTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    private static int bootCount(Context context) {
        try {
            return Global.getInt(context.getContentResolver(), Global.BOOT_COUNT, -1);
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    /**
     * The main process's note of the boot (its count and its start on the wall clock) and when Facebook
     * was last unlocked and in use, for a game or ad screen
     * in a process of its own to read. A private file in the app's own no-backup folder, which only
     * this app's processes reach, written whole and renamed into place; no one else can read or
     * write it. [at] is {@link #NEVER} to take the note away, as when Facebook locks.
     */
    private static void shareUnlock(long at) {
        if (!Utils.isMainProcess()) return;
        try {
            Context context = Utils.getContext();
            if (context == null) return;
            File file = shareFile(context);
            if (at == NEVER) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return;
            }
            int boot = bootCount(context);
            if (boot < 0) return;
            File part = new File(file.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write((boot + " " + at + " " + bootWallTime()).getBytes(StandardCharsets.US_ASCII));
            }
            if (!part.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not leave the unlock for a side process", failure);
        }
    }

    /**
     * Whether the main process was unlocked and in use within Lock after of [now], on this boot. A
     * missing, unreadable, odd or old note, or one from before a restart, is no. Read only.
     */
    static boolean sharedUnlockFresh(long now) {
        try {
            Context context = Utils.getContext();
            if (context == null) return false;
            File file = shareFile(context);
            if (!file.isFile() || file.length() > 64) return false;
            byte[] bytes;
            try (InputStream in = new FileInputStream(file)) {
                bytes = new byte[(int) file.length()];
                int read = 0;
                while (read < bytes.length) {
                    int more = in.read(bytes, read, bytes.length - read);
                    if (more < 0) return false;
                    read += more;
                }
            }
            String[] parts = new String(bytes, StandardCharsets.US_ASCII).split(" ");
            if (parts.length != 3) return false;
            int boot = bootCount(context);
            if (boot < 0 || Integer.parseInt(parts[0]) != boot) return false;
            // The boot count alone can repeat (a reset count, a restored note), so the boot's start on
            // the wall clock has to agree too.
            if (Math.abs(Long.parseLong(parts[2]) - bootWallTime()) > BOOT_SLACK_MS) return false;
            long at = Long.parseLong(parts[1]);
            return at >= 0 && now >= at && now - at < lockAfter().millis;
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** Android's own prompt: a biometric the phone counts as at least weak, or its PIN, pattern or password. */
    @SuppressWarnings("deprecation") // Android 10's way to the PIN, replaced in 11.
    private static void askAndroid(Activity activity, Answer answer) {
        // canLock keeps Android 9 from getting here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            answer.refused(BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT, null);
            return;
        }
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                .setTitle(L10n.t("Unlock Facebook"))
                .setConfirmationRequired(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            builder.setDeviceCredentialAllowed(true);
        }
        BiometricPrompt prompt = builder.build();
        CancellationSignal cancellation = new CancellationSignal();
        promptCancellation = cancellation;
        prompt.authenticate(cancellation, activity.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        answer.unlocked();
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence message) {
                        answer.refused(code, message);
                    }
                });
    }

    /**
     * An opaque window over a Facebook screen, added after the windows it already has, so a sheet
     * or dialog Facebook left open stays under it too. One Facebook opens later lands above it and
     * takes the focus, and the cover answers by going back on top ({@link #keepOnTop}). It takes
     * every touch, and Back sends Facebook to the background rather than closing it. It comes and
     * goes without an animation, so a fresh one put on top shows nothing of the screen between.
     */
    private static final class Cover extends Dialog {
        private final Activity activity;
        private final TextView reason;
        /** A fresh cover replaced it, so its own loss of focus means nothing. */
        boolean retired;
        /** Whether its window has the focus, as Android last said. */
        boolean focused;

        Cover(Activity activity) {
            super(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
            this.activity = activity;
            setCancelable(false);
            setCanceledOnTouchOutside(false);
            float density = activity.getResources().getDisplayMetrics().density;
            int gap = Math.round(24 * density);

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER);
            column.setPadding(gap, gap, gap, gap);
            column.setBackgroundColor(Color.BLACK);

            TextView title = new TextView(activity);
            title.setText(L10n.t("Facebook is locked"));
            ShimmerTitle.wear(title, Color.BLACK);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            title.setGravity(Gravity.CENTER);
            title.setAccessibilityHeading(true);
            column.addView(title);
            // The window's name is what a screen reader speaks when the cover comes up; without one
            // it said the app's name, and nothing told a blind person Facebook had locked.
            setTitle(title.getText());

            reason = new TextView(activity);
            // A refused check changes this line (too many attempts, cancelled): say so when it does.
            reason.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            reason.setTextColor(0xFFB0B3B8);
            reason.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            reason.setGravity(Gravity.CENTER);
            reason.setPadding(0, gap / 2, 0, gap);
            column.addView(reason);

            Button unlock = new Button(getContext());
            unlock.setText(L10n.t("Unlock"));
            // Asks again even while a check may still be on its way: one that never answered mustn't trap Facebook.
            unlock.setOnClickListener(view -> {
                if (!locked) return;
                if (!canLock(activity)) {
                    // No screen lock to ask for any more: asking would fail forever.
                    release(activity);
                    return;
                }
                declined = false;
                refusal = null;
                showReason();
                ask(activity);
            });
            column.addView(unlock);

            setContentView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.setWindowAnimations(0);
            }
            showReason();
        }

        /**
         * Back, as a key or as the back callback. Facebook targets API 36, so on Android 16 Back
         * never arrives as a key: the dialog's own callback calls this instead.
         */
        @Override
        public void onBackPressed() {
            activity.moveTaskToBack(true);
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            focused = hasFocus;
            // Judged once the change has settled, when the screen's own pause has been heard.
            if (!hasFocus && !retired) Utils.runOnMainThread(() -> {
                sweep(activity);
                keepOnTop(activity, this);
            });
        }

        /** Android's reason the last check ended, or what unlocks Facebook. */
        void showReason() {
            CharSequence why = refusal;
            reason.setText(why != null && why.length() > 0 ? why
                    : L10n.t("Use your fingerprint, face or screen lock to open it."));
        }

        void close() {
            try {
                dismiss();
            } catch (RuntimeException gone) {
                // Its screen's window is already gone, and the cover with it.
            }
        }
    }
}
