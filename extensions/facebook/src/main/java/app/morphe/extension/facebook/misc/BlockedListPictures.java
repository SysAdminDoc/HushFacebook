/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Hide pictures on the Blocked list (#361). Facebook's Blocking page, under Settings &amp; privacy,
 * lists the people you blocked with their profile pictures. With the switch on, those pictures
 * go invisible and the names and Unblock buttons stay where they are.
 *
 * <p>The page is React Native in {@link #ACTIVITY}, the screen Facebook opens for many of its
 * React routes, so it's told apart by the route its intent asks for ({@link #ROUTE}, or a uri under
 * {@link #URI}). Its views have no ids or names worth matching. On the S22 on 585 (2026-11-02) each
 * row's picture is an ImageView 113 px square, about 40 dp, and the only other image on the page,
 * the plus beside Add to blocked list, is about 24 dp. So a square image of {@link #MIN_DP} or more
 * is a picture. Each draw of the page looks again, which covers rows that mount late or scroll in.
 */
public final class BlockedListPictures {
    /** The screen the Blocking page opens in. */
    static final String ACTIVITY = "com.facebook.katana.activity.react.ImmersiveReactActivity";

    /** The Blocking page's route name, in the intent's {@link #ROUTE_KEYS}. */
    static final String ROUTE = "PrivacyBlockingRoute";

    /** The start of the Blocking page's uri, in the intent's {@link #URI_KEYS}. */
    static final String URI = "/privacy_blocking";

    /** The extras Facebook names a React route by. */
    private static final String[] ROUTE_KEYS = {"route_name", "ReactRouteName"};

    /** The extras Facebook carries a React route's uri in. */
    private static final String[] URI_KEYS = {"uri", "key_uri", "ReactURI"};

    /** The smallest square image that counts as a picture: between the plus's 24 dp and a picture's 40. */
    static final int MIN_DP = 32;

    /** The Blocking pages being watched, each with the pictures it hid. */
    private static final Map<Activity, Page> pages = new WeakHashMap<>();

    /** The application this process's callbacks went on, so a second start of the hook adds none. */
    private static WeakReference<Application> watching = new WeakReference<>(null);

    private BlockedListPictures() {
    }

    /** From the settings entry's application hook, in the main process. */
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
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { pages.remove(activity); }
    }

    /**
     * A screen came to the front. A Blocking page gets its drawing watched, once, and is tidied now,
     * so a switch flipped while it was behind the settings shows as soon as it's back.
     */
    static void resumed(Activity activity) {
        try {
            Page page = pages.get(activity);
            if (page == null) {
                if (!ACTIVITY.equals(activity.getClass().getName()) || !blockingRoute(activity.getIntent())) return;
                page = new Page(activity.getWindow().getDecorView());
                pages.put(activity, page);
                page.root.getViewTreeObserver().addOnPreDrawListener(page);
                Logger.printDebug(() -> "Hide pictures on the Blocked list: watching the Blocking page");
            }
            page.tidy();
        } catch (Throwable failure) {
            Logger.printException(() -> "Hide pictures on the Blocked list: could not watch the page", failure);
        }
    }

    static boolean on() {
        return Utils.settingsReady() && Settings.HIDE_BLOCKED_LIST_PICTURES.get();
    }

    /** Whether the intent opens the Blocking page, by its route name or its uri. */
    static boolean blockingRoute(@Nullable Intent intent) {
        if (intent == null) return false;
        try {
            return blockingRoute(intent.getExtras(), 0);
        } catch (Throwable failure) {
            // An extra this process can't unparcel; the page isn't one this can tell.
            Logger.printDebug(() -> "Hide pictures on the Blocked list: could not read the intent: " + failure);
            return false;
        }
    }

    /** Looks one bundle deep as well, in case a builder nests the route's arguments. */
    @SuppressWarnings("deprecation")
    private static boolean blockingRoute(@Nullable Bundle extras, int depth) {
        if (extras == null) return false;
        for (String key : ROUTE_KEYS) {
            if (ROUTE.equals(extras.get(key))) return true;
        }
        for (String key : URI_KEYS) {
            Object uri = extras.get(key);
            if (uri instanceof String && ((String) uri).startsWith(URI)) return true;
        }
        if (depth > 0) return false;
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value instanceof Bundle && blockingRoute((Bundle) value, depth + 1)) return true;
        }
        return false;
    }

    /**
     * One Blocking page: hides its pictures while the switch is on, and shows them again when it's
     * off. It looks before each draw, not each layout: React Native's view groups skip requestLayout,
     * so rows that mount after the page opens never start a layout pass, but they're always drawn.
     */
    static final class Page implements ViewTreeObserver.OnPreDrawListener {
        final View root;
        private final Set<View> hidden = Collections.newSetFromMap(new WeakHashMap<>());

        Page(View root) {
            this.root = root;
        }

        @Override
        public boolean onPreDraw() {
            try {
                tidy();
            } catch (Throwable failure) {
                Logger.printException(() -> "Hide pictures on the Blocked list: could not tidy the page", failure);
            }
            return true;
        }

        /** Returns how many pictures this pass hid. */
        int tidy() {
            if (!on()) {
                for (View picture : hidden) {
                    if (picture.getVisibility() == View.INVISIBLE) picture.setVisibility(View.VISIBLE);
                }
                hidden.clear();
                return 0;
            }
            int minPx = Math.round(MIN_DP * root.getResources().getDisplayMetrics().density);
            int count = hide(root, minPx);
            if (count > 0) Logger.printDebug(() -> "Hide pictures on the Blocked list: hid " + count);
            return count;
        }

        private int hide(View view, int minPx) {
            if (view.getVisibility() != View.VISIBLE) return 0;
            if (view instanceof ImageView) {
                if (!picture(view, minPx)) return 0;
                view.setVisibility(View.INVISIBLE);
                hidden.add(view);
                return 1;
            }
            if (!(view instanceof ViewGroup)) return 0;
            ViewGroup group = (ViewGroup) view;
            int count = 0;
            for (int i = 0; i < group.getChildCount(); i++) count += hide(group.getChildAt(i), minPx);
            return count;
        }
    }

    /** A square image at least [minPx] wide, give or take a pixel of rounding. */
    static boolean picture(View image, int minPx) {
        int width = image.getWidth();
        int height = image.getHeight();
        return width >= minPx && Math.abs(width - height) <= 2;
    }

    static void forgetForTests() {
        for (Map.Entry<Activity, Page> entry : pages.entrySet()) {
            entry.getValue().root.getViewTreeObserver().removeOnPreDrawListener(entry.getValue());
        }
        pages.clear();
    }
}
