/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import app.morphe.extension.facebook.navigation.FacebookTabs;
import app.morphe.extension.facebook.navigation.StartTabRoute;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.facebook.settings.ShimmerTitle;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Take breaks. With its switch on, time spent browsing counts toward the use time picked in
 * settings, and once it's used up a break of the chosen length starts: a cover over Home, Feeds,
 * Video and Reels counts down until they open again. Chats, your profile, the Menu and the Hush
 * settings stay reachable, and the cover's Break settings button can turn the switch off at once.
 *
 * <p>Browsing is the main screen on the Home, Feeds, Most recent or Video tab, the Feeds page the
 * Menu opens, and a screen a reel started playing on. Pages opened from those tabs, a profile or a
 * group, don't count and are never covered. Only a resumed screen with the phone's screen on
 * counts, by elapsedRealtime, at most two seconds per tick, so time in the background, asleep, or
 * between a rotation's two screens adds nothing.
 *
 * <p>A break's end is fixed when it starts and kept on disk with the phone's boot count, so leaving
 * Facebook, a restart of it or a rotation can't end it early. After a phone restart it goes on with
 * the smaller of what was left at the last save and the time left by the wall clock, so moving the
 * clock can only shorten it. Time away from browsing at least as long as a break counts as one and
 * starts the use time over. The switch off, or Hushfacebook paused, ends a break and the count.
 *
 * <p>With {@link Settings#BREAK_AFTER_REELS} on, {@link #REELS_BEFORE_BREAK} different reels start a
 * break too, whichever comes first. A reel counts once in a count when it has played on for
 * {@link #REEL_WATCHED_AFTER_MS}; ads, live videos, GIFs and audio don't. A break, time away as
 * long as one, the switch off or a pause starts the reels over, and they're kept on disk with the
 * use time.
 */
public final class BreakTimer {
    /** How often the count runs while a Facebook screen is in front. */
    static final long TICK_MS = 1000;

    /** The most one tick adds, so a stalled main thread can't add a burst. */
    static final long MOST_PER_TICK_MS = 2000;

    /** How often the count is written down while browsing or on a break. */
    static final long SAVE_EVERY_MS = 15_000;

    /** The file the count lives in, apart from the settings so a settings backup never carries it. */
    static final String STORE = "hushfacebook_breaks";
    static final String USED = "used_ms", BROWSED_AT = "browsed_at", ENDS_AT = "ends_at",
            LEFT = "left_ms", WALL_ENDS_AT = "wall_ends_at", BOOT = "boot", REELS = "reels";

    /** How many different reels start a break with {@link Settings#BREAK_AFTER_REELS} on. */
    static final int REELS_BEFORE_BREAK = 20;

    /** How long after its start a reel is checked, and counted if it played on. */
    static final long REEL_WATCHED_AFTER_MS = 3000;

    private static final long NEVER = -1;

    /** Facebook's link for the Menu. */
    static final String MENU_LINK = "fb://bookmarks";

    /** Facebook's own handler for the links it opens, a manifest component that keeps its name. */
    static final String URI_HANDLER = "com.facebook.katana.IntentUriHandler";

    /** The tabs whose screen is browsing. */
    static final Set<String> BROWSING_TABS = Set.of(FacebookTabs.HOME_CLASS, FacebookTabs.FEEDS_CLASS,
            FacebookTabs.MOST_RECENT_CLASS, FacebookTabs.VIDEO_CLASS);

    /** How long browsing lasts before a break. */
    public enum Use {
        TEN_MINUTES(10, "10_minutes"),
        FIFTEEN_MINUTES(15, "15_minutes"),
        THIRTY_MINUTES(30, "30_minutes"),
        FORTY_FIVE_MINUTES(45, "45_minutes"),
        SIXTY_MINUTES(60, "60_minutes");

        public final int minutes;

        /** What a settings file holds for this choice. It never changes once written. */
        public final String fileValue;

        Use(int minutes, String fileValue) {
            this.minutes = minutes;
            this.fileValue = fileValue;
        }

        /** The choice a settings file names, or null for anything else. */
        @Nullable
        public static Use fromFile(@Nullable Object value) {
            if (!(value instanceof String)) return null;
            for (Use use : values()) {
                if (use.fileValue.equals(value)) return use;
            }
            return null;
        }
    }

    /** How long a break lasts. */
    public enum Length {
        ONE_MINUTE(1, "1_minute"),
        FIVE_MINUTES(5, "5_minutes"),
        TEN_MINUTES(10, "10_minutes"),
        FIFTEEN_MINUTES(15, "15_minutes"),
        THIRTY_MINUTES(30, "30_minutes");

        public final int minutes;

        /** What a settings file holds for this choice. It never changes once written. */
        public final String fileValue;

        Length(int minutes, String fileValue) {
            this.minutes = minutes;
            this.fileValue = fileValue;
        }

        /** The choice a settings file names, or null for anything else. */
        @Nullable
        public static Length fromFile(@Nullable Object value) {
            if (!(value instanceof String)) return null;
            for (Length length : values()) {
                if (length.fileValue.equals(value)) return length;
            }
            return null;
        }
    }

    /** Browsing counted toward the next break. */
    private static long usedMs;
    /** When browsing last counted, by the clock, or NEVER. */
    private static long browsedAt = NEVER;
    /** When the live break ends, by the clock, or NEVER with no break on. */
    private static long endsAt = NEVER;
    /** The last tick that counted, or NEVER, so a fresh screen starts its own count. */
    private static long countedAt = NEVER;
    /** When the count was last written down, by the clock. */
    private static long savedAt = NEVER;
    /** Whether this process read the count back from disk. */
    private static boolean loaded;
    /** The reels counted toward the next break, by video id. Main thread only. */
    private static final Set<String> reelsWatched = new HashSet<>();
    /** Moves on each time the reels start over, so a check from before then counts nothing. */
    private static volatile long reelGeneration;

    /** The resumed screen, while there is one. */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    /** The screen a reel last started playing on. */
    private static WeakReference<Activity> reelScreen = new WeakReference<>(null);
    private static final Map<Activity, Cover> covers = new WeakHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable tick = BreakTimer::tick;

    /** The clock everything here runs on. */
    static LongSupplier clock = SystemClock::elapsedRealtime;
    /** The wall clock, read only to carry a break over a phone restart. */
    static LongSupplier wallClock = System::currentTimeMillis;
    /** Whether the main screen or the Feeds page in [activity] is browsing. Tests swap it. */
    static Predicate<Activity> feedScreen = BreakTimer::feedScreen;

    /** The application this process's callbacks went on, so a second start of the hook adds none. */
    private static WeakReference<Application> watching = new WeakReference<>(null);

    private BreakTimer() {
    }

    /** From the settings entry's application hook, in the main process, after the opening pause's. */
    public static void watch(Context context) {
        if (!(context instanceof Application)) return;
        Application application = (Application) context;
        if (watching.get() == application) return;
        application.registerActivityLifecycleCallbacks(new Watcher());
        watching = new WeakReference<>(application);
    }

    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { resumed(activity); }
        @Override public void onActivityPaused(Activity activity) { paused(activity); }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { destroyed(activity); }
    }

    /** Whether the switch is on and Hushfacebook isn't paused. */
    public static boolean switchedOn() {
        return Utils.settingsReady() && Settings.TAKE_BREAKS.get();
    }

    static void resumed(Activity activity) {
        front = new WeakReference<>(activity);
        countedAt = NEVER;
        tick();
    }

    static void paused(Activity activity) {
        try {
            if (front.get() != activity) return;
            front = new WeakReference<>(null);
            main.removeCallbacks(tick);
            if (countedAt != NEVER || endsAt != NEVER) save(activity);
            countedAt = NEVER;
        } catch (Throwable failure) {
            Logger.printException(() -> "Breaks: could not save the count", failure);
        }
    }

    static void destroyed(Activity activity) {
        Cover cover = covers.remove(activity);
        if (cover != null) cover.close();
        if (reelScreen.get() == activity) reelScreen = new WeakReference<>(null);
    }

    /**
     * From the player hook Recently watched shares: a reel started playing. Its screen counts as
     * browsing from now on, and on a break it's covered. The player's thread hands it to the main
     * one, where the count lives.
     */
    public static void reelStarted() {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post(BreakTimer::reelStarted);
                return;
            }
            Activity activity = front.get();
            if (activity == null || !switchedOn()) return;
            reelScreen = new WeakReference<>(activity);
            tick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Breaks: could not take a reel's start", failure);
        }
    }

    /** Whether reels are counted: Take breaks on and its reel switch on. */
    public static boolean countingReels() {
        return switchedOn() && Settings.BREAK_AFTER_REELS.get();
    }

    /**
     * From the same player hook, after {@link #reelStarted}: reel [videoId] started, and [playsOn]
     * says later whether it played on. It's asked {@link #REEL_WATCHED_AFTER_MS} later on the
     * player's own thread, and a reel that did is counted on the main thread. Never throws.
     */
    public static void reelPlaying(String videoId, BooleanSupplier playsOn) {
        try {
            long generation = reelGeneration;
            Looper looper = Looper.myLooper();
            new Handler(looper != null ? looper : Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (generation == reelGeneration && playsOn.getAsBoolean()) {
                        main.post(() -> reelWatched(videoId, generation));
                    }
                } catch (Throwable failure) {
                    Logger.printException(() -> "Breaks: could not check a reel", failure);
                }
            }, REEL_WATCHED_AFTER_MS);
        } catch (Throwable failure) {
            Logger.printException(() -> "Breaks: could not take a reel", failure);
        }
    }

    /** Counts reel [videoId] once, and starts a break at {@link #REELS_BEFORE_BREAK}. */
    private static void reelWatched(String videoId, long generation) {
        try {
            Activity activity = front.get();
            if (activity == null || generation != reelGeneration || !countingReels()) return;
            load(activity);
            if (endsAt != NEVER || !reelsWatched.add(videoId)) return;
            long now = clock.getAsLong();
            if (reelsWatched.size() >= REELS_BEFORE_BREAK) {
                startBreak(activity, now, REELS_BEFORE_BREAK + " reels watched");
            } else {
                save(activity);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Breaks: could not count a reel", failure);
        }
    }

    /** Starts the reels over, and retires every check from before. */
    private static void forgetReels() {
        reelsWatched.clear();
        reelGeneration++;
    }

    /**
     * From the switch's row: the screen under the settings stays resumed while it's flipped, so the
     * next tick, after the new value is saved, starts the count or ends it and any break.
     */
    public static void switched() {
        main.removeCallbacks(tick);
        main.post(tick);
    }

    /** Whether [activity] is browsing. */
    static boolean browsing(Activity activity) {
        return reelScreen.get() == activity || feedScreen.test(activity);
    }

    private static boolean feedScreen(Activity activity) {
        if (FacebookTabs.MAIN_TAB_ACTIVITY.equals(activity.getClass().getName())) {
            String tab = StartTabRoute.shownTab(activity);
            return tab != null && BROWSING_TABS.contains(tab);
        }
        return StartTabRoute.feedsPage(activity);
    }

    /** Counts, starts and ends breaks, and comes back each second while a screen is in front. */
    static void tick() {
        main.removeCallbacks(tick);
        Activity activity = front.get();
        if (activity == null) return;
        try {
            if (!switchedOn()) {
                if (loaded && (endsAt != NEVER || usedMs > 0 || !reelsWatched.isEmpty())) {
                    Logger.printDebug(() -> "Breaks: switched off, so the count and any break end");
                    reset();
                    save(activity);
                }
                closeCovers();
                return;
            }
            load(activity);
            if (!reelsWatched.isEmpty() && !Settings.BREAK_AFTER_REELS.get()) {
                Logger.printDebug(() -> "Breaks: reels aren't counted, so their count ends");
                forgetReels();
                save(activity);
            }
            long now = clock.getAsLong();
            if (endsAt != NEVER) {
                if (now < endsAt) {
                    onBreak(activity, now);
                    main.postDelayed(tick, TICK_MS);
                    return;
                }
                breakOver(activity, now);
            }
            if (browsing(activity) && screenOn(activity) && !OpeningPause.covering() && !AppLock.covering()) {
                count(activity, now);
            } else {
                countedAt = NEVER;
            }
            main.postDelayed(tick, TICK_MS);
        } catch (Throwable failure) {
            Logger.printException(() -> "Breaks: could not count", failure);
        }
    }

    private static void count(Activity activity, long now) {
        // Time away at least as long as a break counts as one.
        if (browsedAt != NEVER && now - browsedAt >= breakMs()) {
            usedMs = 0;
            forgetReels();
        }
        if (countedAt != NEVER) usedMs += Math.min(Math.max(0, now - countedAt), MOST_PER_TICK_MS);
        countedAt = now;
        browsedAt = now;
        if (usedMs >= useMs()) {
            startBreak(activity, now, "use time is up");
        } else if (savedAt == NEVER || now - savedAt >= SAVE_EVERY_MS) {
            save(activity);
        }
    }

    /** A break starts now, and the use time and reels start over. [why] goes in the debug log. */
    private static void startBreak(Activity activity, long now, String why) {
        endsAt = now + breakMs();
        usedMs = 0;
        countedAt = NEVER;
        forgetReels();
        Logger.printDebug(() -> "Breaks: " + why + ", a break starts");
        save(activity);
        onBreak(activity, now);
    }

    /** On a break: [activity] covered while it's browsing, every cover showing the time left. */
    private static void onBreak(Activity activity, long now) {
        if (OpeningPause.covering() || AppLock.covering()) return;
        for (Map.Entry<Activity, Cover> each : new ArrayList<>(covers.entrySet())) {
            if (!browsing(each.getKey())) {
                covers.remove(each.getKey());
                each.getValue().close();
            }
        }
        if (browsing(activity)) cover(activity);
        long left = endsAt - now;
        for (Cover cover : new ArrayList<>(covers.values())) cover.showLeft(left);
        if (savedAt == NEVER || now - savedAt >= SAVE_EVERY_MS) save(activity);
    }

    private static void breakOver(Activity activity, long now) {
        endsAt = NEVER;
        usedMs = 0;
        browsedAt = now;
        countedAt = NEVER;
        forgetReels();
        boolean shown = !covers.isEmpty();
        closeCovers();
        save(activity);
        Logger.printDebug(() -> "Breaks: the break is over");
        if (shown) Toast.makeText(activity, L10n.t("Your break is over."), Toast.LENGTH_SHORT).show();
    }

    private static boolean screenOn(Activity activity) {
        PowerManager power = activity.getSystemService(PowerManager.class);
        return power == null || power.isInteractive();
    }

    private static long useMs() {
        return Settings.BREAK_AFTER.get().minutes * 60_000L;
    }

    private static long breakMs() {
        return Settings.BREAK_LENGTH.get().minutes * 60_000L;
    }

    /** Whether a break cover is up. */
    static boolean covering() {
        for (Cover cover : covers.values()) {
            if (cover.isShowing()) return true;
        }
        return false;
    }

    private static void cover(Activity activity) {
        if (activity.isFinishing()) return;
        Cover cover = covers.get(activity);
        if (cover == null) {
            try {
                cover = new Cover(activity);
                covers.put(activity, cover);
            } catch (RuntimeException failure) {
                // A break that can't be shown leaves Facebook as it is.
                Logger.printException(() -> "Breaks: could not cover " + activity.getClass().getSimpleName(), failure);
                return;
            }
        }
        if (!cover.isShowing()) cover.show();
    }

    private static void closeCovers() {
        for (Cover cover : new ArrayList<>(covers.values())) cover.close();
        covers.clear();
    }

    private static void reset() {
        usedMs = 0;
        browsedAt = NEVER;
        endsAt = NEVER;
        countedAt = NEVER;
        forgetReels();
    }

    // ------------------------------------------------------------------ on disk

    private static SharedPreferences store(Context context) {
        return context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    /** The phone's boot count, or -1 where it can't be read. */
    static int boot(Context context) {
        try {
            return android.provider.Settings.Global.getInt(context.getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException unreadable) {
            return -1;
        }
    }

    /**
     * Reads the count back once a process. A count from before a phone restart, or one stamped
     * later than now, starts over, and a break from before a restart keeps what was left of it.
     */
    private static void load(Context context) {
        if (loaded) return;
        loaded = true;
        SharedPreferences stored = store(context);
        long now = clock.getAsLong();
        int boot = boot(context);
        boolean sameBoot = boot != -1 && stored.getInt(BOOT, -1) == boot;
        long longest = Length.THIRTY_MINUTES.minutes * 60_000L;
        if (sameBoot) {
            long ends = stored.getLong(ENDS_AT, NEVER);
            long browsed = stored.getLong(BROWSED_AT, NEVER);
            endsAt = ends != NEVER && ends > now && ends - now <= longest ? ends : NEVER;
            browsedAt = browsed != NEVER && browsed <= now ? browsed : NEVER;
            usedMs = browsedAt == NEVER ? 0 : Math.max(0, stored.getLong(USED, 0));
            reelsWatched.clear();
            if (browsedAt != NEVER) reelsWatched.addAll(stored.getStringSet(REELS, Collections.emptySet()));
        } else {
            long left = Math.min(stored.getLong(LEFT, 0), stored.getLong(WALL_ENDS_AT, 0) - wallClock.getAsLong());
            endsAt = left > 0 && left <= longest ? now + left : NEVER;
            browsedAt = NEVER;
            usedMs = 0;
            reelsWatched.clear();
        }
        Logger.printDebug(() -> "Breaks: read back " + usedMs / 1000 + " s used, " + reelsWatched.size() + " reels"
                + (endsAt == NEVER ? "" : ", a break with " + (endsAt - now) / 1000 + " s left"));
    }

    private static void save(Context context) {
        long now = clock.getAsLong();
        savedAt = now;
        long left = endsAt == NEVER ? 0 : Math.max(0, endsAt - now);
        store(context).edit()
                .putLong(USED, usedMs)
                .putLong(BROWSED_AT, browsedAt)
                .putLong(ENDS_AT, endsAt)
                .putLong(LEFT, left)
                .putLong(WALL_ENDS_AT, endsAt == NEVER ? 0 : wallClock.getAsLong() + left)
                .putInt(BOOT, boot(context))
                .putStringSet(REELS, new HashSet<>(reelsWatched))
                .apply();
    }

    /** How many reels count toward the next break. */
    static int reelsCounted() {
        return reelsWatched.size();
    }

    // ------------------------------------------------------------------ the cover

    /** "4:05" for [millis], rounded up to the second. */
    static String clockText(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * An opaque window over a browsing screen with the time left, and buttons for the Menu, for
     * closing Facebook and for the break settings. It takes every touch. Back leaves the screen, or
     * sends Facebook to the background from the main screen.
     */
    private static final class Cover extends Dialog {
        private final Activity activity;
        private final TextView time;
        private final TextView left;

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
            title.setText(L10n.t("Time for a break"));
            ShimmerTitle.wear(title, Color.BLACK);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            title.setGravity(Gravity.CENTER);
            title.setAccessibilityHeading(true);
            column.addView(title);
            // What a screen reader speaks as the cover comes up, rather than the app's name.
            setTitle(title.getText());

            // The clock is for the eye; the line under it says the same in words.
            time = new TextView(activity);
            time.setTextColor(Color.WHITE);
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
            time.setGravity(Gravity.CENTER);
            time.setPadding(0, gap / 2, 0, 0);
            time.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            column.addView(time);

            left = new TextView(activity);
            left.setTextColor(0xFFB0B3B8);
            left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            left.setGravity(Gravity.CENTER);
            left.setPadding(0, gap / 2, 0, 0);
            column.addView(left);

            TextView still = new TextView(activity);
            still.setText(L10n.t("Chats, your profile and the Menu still work."));
            still.setTextColor(0xFFB0B3B8);
            still.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            still.setGravity(Gravity.CENTER);
            still.setPadding(0, gap / 2, 0, gap);
            column.addView(still);

            // Each at least 48dp tall, a touch target TalkBack and a thumb can find.
            int target = Math.round(48 * density);
            Button menu = new Button(getContext());
            menu.setText(L10n.t("Go to the Menu"));
            menu.setMinHeight(target);
            menu.setOnClickListener(view -> toMenu(activity));
            column.addView(menu);

            Button close = new Button(getContext());
            close.setText(L10n.t("Close Facebook"));
            close.setMinHeight(target);
            close.setOnClickListener(view -> activity.moveTaskToBack(true));
            column.addView(close);

            Button settings = new Button(getContext());
            settings.setText(L10n.t("Break settings"));
            settings.setMinHeight(target);
            settings.setOnClickListener(view -> SettingsEntry.open(activity, Settings.TAKE_BREAKS.key));
            column.addView(settings);

            setContentView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.setWindowAnimations(0);
            }
            showLeft(endsAt == NEVER ? 0 : endsAt - clock.getAsLong());
        }

        /** [millis] left of the break. */
        void showLeft(long millis) {
            time.setText(clockText(millis));
            long minutes = (Math.max(0, millis) + 59_999) / 60_000;
            if (minutes > 1) {
                left.setText(L10n.quantity(minutes, "Home, Feeds, Video and Reels open again in %1$d minute.",
                        "Home, Feeds, Video and Reels open again in %1$d minutes.", minutes));
            } else {
                left.setText(L10n.t("Home, Feeds, Video and Reels open again in under a minute."));
            }
        }

        /**
         * Back, as a key or as the back callback. Facebook targets API 36, so on Android 16 Back
         * never arrives as a key: the dialog's own callback calls this instead.
         */
        @Override
        public void onBackPressed() {
            if (FacebookTabs.MAIN_TAB_ACTIVITY.equals(activity.getClass().getName())) {
                activity.moveTaskToBack(true);
            } else {
                activity.finish();
            }
        }

        void close() {
            try {
                dismiss();
            } catch (RuntimeException gone) {
                // Its screen's window is already gone, and the cover with it.
            }
        }
    }

    /**
     * The Menu, through Facebook's own link for it: its link helper switches the main screen to the
     * Menu tab where the bar has one, and opens the Menu as a page of its own where it doesn't. On
     * 585 an account can keep the Menu behind a button at the top with a profile tab in its place on
     * the bar, and a start of the main screen asking for the Menu tab then stayed on Home, covered.
     */
    static void toMenu(Activity activity) {
        try {
            Intent menu = new Intent(Intent.ACTION_VIEW, Uri.parse(MENU_LINK))
                    .setClassName(activity.getPackageName(), URI_HANDLER);
            activity.startActivity(menu);
            if (!FacebookTabs.MAIN_TAB_ACTIVITY.equals(activity.getClass().getName())) activity.finish();
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Breaks: could not open the Menu", failure);
        }
    }

    /** Forgets this process's count, break, screens and covers. */
    static void forgetForTests() {
        reset();
        savedAt = NEVER;
        loaded = false;
        front = new WeakReference<>(null);
        reelScreen = new WeakReference<>(null);
        main.removeCallbacks(tick);
        closeCovers();
        clock = SystemClock::elapsedRealtime;
        wallClock = System::currentTimeMillis;
        feedScreen = BreakTimer::feedScreen;
    }
}
