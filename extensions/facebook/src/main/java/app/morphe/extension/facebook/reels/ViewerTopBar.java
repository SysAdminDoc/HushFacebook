/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Search and the button beside it at the top of a video or reel opened full screen (#311). The
 * full-screen player screens draw a top bar of three buttons in one LithoView: Back at the start,
 * then Search, then the Reels profile button over a reel or More over a feed video (S22, 585,
 * 2026-10-31: bar 0,0-1080,338, Back's host at 0,89-113,202, Search's at 838,98-939,199 and the
 * profile button's at 962,98-1063,199). The patch hands over the bar's id, which Facebook's own
 * setPipChromeVisibility finds the bar by ({@link #barId}).
 *
 * <p>A video opened from the feed mostly doesn't get that screen: the player is laid over the main
 * screen, a layer of its own under the content view with the same bar in it (S22, 585). The Reels
 * tab's header there has the same id too, Menu where Back would be, inside the tab pages that slide
 * sideways, and its Search and profile button go the same way (#346). No other tab's header has the
 * id (emulator, 585, 2026-11-02). So past the full-screen player screens, each layer of the content
 * view is looked through for the bar.
 *
 * <p>The buttons are Litho hosts with no ids, and their labels change with the language, so with
 * the switch on every clickable host in the bar that sits past its middle, away from Back, and in
 * Back's row is made invisible after each layout of the screen. Back stays, and so does More at
 * the foot of the side buttons, which holds Download. A bar with no clickable host on Back's side
 * is left alone. A host gets its visibility back when Litho takes it off the screen, so one Litho
 * hands to something else comes back as Facebook left it. Off, paused, before the settings are
 * ready, or when anything here fails, the bar is Facebook's own.
 *
 * <p>A second switch takes Back itself, the clickable host nearest the bar's start, the same way
 * (#388). On the Reels tab's header that host is Menu, so with the title switch on as well the
 * header keeps nothing on its start side (#390). The phone's own back still works.
 */
public final class ViewerTopBar {
    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;
    static final String HOOK = "viewer top bar";
    /** Counted for each button taken off a viewer's top bar. */
    static final String HIDDEN = "Viewer top bar button hidden";

    static final String HOST = "com.facebook.litho.ComponentHost";

    /** Counted for each part of the Reels tab's title taken off its header. */
    static final String TITLE_HIDDEN = "Reels title hidden";

    /** Counted for each Back (or Menu, on the Reels tab) taken off a viewer's top bar. */
    static final String BACK_HIDDEN = "Viewer Back button hidden";

    /** How many levels under the bar a button is looked for. 585's sit 2 to 5 down. */
    static final int MAX_DEPTH = 8;

    /** The screens watched for layouts. */
    private static final Map<Activity, Boolean> watched = new WeakHashMap<>();
    /** The hosts made invisible here, each shown again when it leaves the window. */
    private static final Map<View, Boolean> hidden = new WeakHashMap<>();
    /** The hosts with a detach listener on, which stays until the host leaves the window. */
    private static final Map<View, Boolean> listening = new WeakHashMap<>();
    /** The bars whose hosts were described in the log once. */
    private static final Map<View, Boolean> titleLogged = new WeakHashMap<>();

    /** The application this process's callbacks went on, so a second start of the hook adds none. */
    private static WeakReference<Application> watching = new WeakReference<>(null);

    @Nullable static volatile Integer barIdForTests;

    private ViewerTopBar() {
    }

    /** Filled by the patch with the top bar's view id, read from ImmersiveActivity.setPipChromeVisibility. */
    public static int barId() {
        return 0;
    }

    private static int id() {
        Integer forced = barIdForTests;
        return forced != null ? forced : barId();
    }

    /** From the settings entry's application hook, in the main process, with Clean up Reels in the build. */
    public static synchronized void watch(Context context) {
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
        @Override public void onActivityDestroyed(Activity activity) { watched.remove(activity); }
    }

    /** A screen in front starts being looked through after each layout. */
    static void resumed(Activity activity) {
        try {
            if (watched.containsKey(activity) || id() == 0) return;
            Window window = activity.getWindow();
            View decor = window == null ? null : window.peekDecorView();
            if (decor == null) return;
            ViewTreeObserver tree = decor.getViewTreeObserver();
            if (!tree.isAlive()) return;
            watched.put(activity, Boolean.TRUE);
            WeakReference<Activity> held = new WeakReference<>(activity);
            tree.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    try {
                        Activity shown = held.get();
                        if (shown == null || shown.isDestroyed()) {
                            if (tree.isAlive()) tree.removeOnGlobalLayoutListener(this);
                            return;
                        }
                        laidOut(shown);
                    } catch (Throwable failure) {
                        HookStatus.threw(FAMILY, HOOK, failure);
                    }
                }
            });
            laidOut(activity);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    private static void laidOut(Activity activity) {
        int id = id();
        if (id == 0 || (hidden.isEmpty() && !on() && !titleOn() && !backOn())) return;
        if (FeedVideoAlone.PLAYER_SCREENS.contains(activity.getClass().getName())) {
            View bar = activity.findViewById(id);
            if (bar != null) tidy(bar);
            return;
        }
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup layers = (ViewGroup) content;
        for (int i = 0; i < layers.getChildCount(); i++) {
            View bar = layers.getChildAt(i).findViewById(id);
            if (bar != null) tidy(bar, true);
        }
    }

    /** Whether the Reels tab's title goes now. */
    private static boolean titleOn() {
        return Utils.settingsReady() && !Setting.isPaused() && Settings.HIDE_REELS_TAB_TITLE.get();
    }

    /** Whether the bar's far buttons go now. */
    private static boolean on() {
        return Utils.settingsReady() && !Setting.isPaused() && Settings.HIDE_VIEWER_TOP_BUTTONS.get();
    }

    /** Whether the bar's Back, Menu on the Reels tab, goes now (#388, #390). */
    private static boolean backOn() {
        return Utils.settingsReady() && !Setting.isPaused() && Settings.HIDE_VIEWER_BACK_BUTTON.get();
    }

    /**
     * Hides each far button of [bar] while the switch is on, and shows again any it hid that no
     * longer is one, or every one when the switch is off. Answers how many it hid this time.
     */
    static int tidy(View bar) {
        return tidy(bar, false);
    }

    /**
     * [tab] says the bar was found in a layer under the content view, where the Reels tab's header
     * lives, rather than on a full-screen player screen. Only there is the title looked for (#362).
     */
    static int tidy(View bar, boolean tab) {
        boolean on = on();
        boolean title = tab && titleOn();
        boolean start = backOn();
        List<View> far = on ? farButtons(bar) : new ArrayList<>();
        List<View> titles = title ? titleHosts(bar) : new ArrayList<>();
        View back = start ? backButton(bar) : null;
        List<View> wanted = new ArrayList<>(far);
        wanted.addAll(titles);
        if (back != null) wanted.add(back);
        for (View host : new ArrayList<>(hidden.keySet())) {
            if (host != null && !wanted.contains(host) && within(bar, host)) show(host);
        }
        if (!on && !title && !start) return 0;
        HookStatus.invoked(FAMILY);
        if (title && !titleLogged.containsKey(bar)) {
            titleLogged.put(bar, Boolean.TRUE);
            Logger.printDebug(() -> "Viewer top bar: " + titles.size() + " title host(s) found of " + describe(bar));
        }
        int titled = 0;
        for (View host : titles) {
            if (host.getVisibility() != View.VISIBLE) continue;
            host.setVisibility(View.INVISIBLE);
            remember(host);
            titled++;
            HookStatus.bound(FAMILY, "reels title");
            HookStatus.counted(FAMILY, TITLE_HIDDEN);
        }
        if (titled > 0) {
            int hid = titled;
            Logger.printDebug(() -> "Viewer top bar: hid " + hid + " Reels title host" + (hid == 1 ? "" : "s"));
        }
        int count = 0;
        for (View host : far) {
            if (host.getVisibility() != View.VISIBLE) continue;
            host.setVisibility(View.INVISIBLE);
            remember(host);
            count++;
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, HIDDEN);
        }
        if (count > 0) {
            int hid = count;
            Logger.printDebug(() -> "Viewer top bar: hid " + hid + " button" + (hid == 1 ? "" : "s") + " past Back");
        }
        if (back != null && back.getVisibility() == View.VISIBLE) {
            back.setVisibility(View.INVISIBLE);
            remember(back);
            count++;
            HookStatus.bound(FAMILY, "viewer back");
            HookStatus.counted(FAMILY, BACK_HIDDEN);
            Logger.printDebug(() -> "Viewer top bar: hid " + (tab ? "Back or Menu on a tab's header" : "Back"));
        }
        return count;
    }

    /**
     * The Litho hosts in [bar] that could hold the Reels tab's title: not clickable and with no
     * clickable ancestor in the bar, no host inside them, on the start half of the bar. The title
     * is text, which Litho draws into the nearest host, so a host of its own is what can go.
     */
    static List<View> titleHosts(View bar) {
        List<View> found = new ArrayList<>();
        collectHosts(bar, bar, 0, found);
        List<View> titles = new ArrayList<>();
        if (bar.getWidth() <= 0) return titles;
        boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        for (View host : found) {
            if (host.isClickable() || host.getWidth() <= 0 || host.getHeight() <= 0 || holdsHost(host)) continue;
            if (clickableAbove(bar, host)) continue;
            int[] at = offset(bar, host);
            int start = rtl ? bar.getWidth() - (at[0] + host.getWidth()) : at[0];
            if (start + host.getWidth() / 2 >= bar.getWidth() / 2) continue;
            titles.add(host);
        }
        return titles;
    }

    private static void collectHosts(View bar, View view, int depth, List<View> found) {
        if (!(view instanceof ViewGroup) || depth > MAX_DEPTH) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isHost(child)) found.add(child);
            collectHosts(bar, child, depth + 1, found);
        }
    }

    private static boolean holdsHost(View host) {
        ViewGroup group = (ViewGroup) host;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isHost(child) || holdsHost(child)) return true;
        }
        return false;
    }

    private static boolean clickableAbove(View bar, View host) {
        ViewParent parent = host.getParent();
        while (parent instanceof View && parent != bar) {
            if (((View) parent).isClickable() && isHost((View) parent)) return true;
            parent = parent.getParent();
        }
        return false;
    }

    /** One line naming each host in [bar] with its size, place and whether it takes a tap, for the log. */
    private static String describe(View bar) {
        List<View> found = new ArrayList<>();
        collectHosts(bar, bar, 0, found);
        StringBuilder out = new StringBuilder("bar ").append(bar.getWidth()).append('x').append(bar.getHeight());
        for (View host : found) {
            int[] at = offset(bar, host);
            out.append(" [").append(at[0]).append(',').append(at[1]).append(' ').append(host.getWidth()).append('x')
                    .append(host.getHeight()).append(host.isClickable() ? " tap" : "")
                    .append(host.getContentDescription() != null ? " desc" : "").append(']');
        }
        return out.toString();
    }

    /**
     * The clickable hosts in [bar] past its middle on the side away from Back that share Back's
     * row, Back being the clickable host nearest the bar's start. None when no clickable host sits
     * on Back's side.
     */
    static List<View> farButtons(View bar) {
        List<View> found = new ArrayList<>();
        List<View> far = new ArrayList<>();
        buttons(bar, 0, found);
        View back = back(bar, found);
        if (back == null) return far;
        boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int middle = bar.getWidth() / 2;
        int[] backAt = offset(bar, back);
        int backTop = backAt[1];
        int backBottom = backAt[1] + back.getHeight();
        for (View host : found) {
            if (host == back) continue;
            int[] at = offset(bar, host);
            int start = rtl ? bar.getWidth() - (at[0] + host.getWidth()) : at[0];
            if (start + host.getWidth() / 2 <= middle) continue;
            if (at[1] >= backBottom || at[1] + host.getHeight() <= backTop) continue;
            far.add(host);
        }
        return far;
    }

    /**
     * Back in [bar], the clickable host nearest its start on the start half: Back over an opened
     * video or reel, Menu on the Reels tab's header. Null when no clickable host sits there.
     */
    @Nullable
    static View backButton(View bar) {
        List<View> found = new ArrayList<>();
        buttons(bar, 0, found);
        return back(bar, found);
    }

    @Nullable
    private static View back(View bar, List<View> found) {
        if (found.isEmpty() || bar.getWidth() <= 0) return null;
        boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int middle = bar.getWidth() / 2;
        View back = null;
        int backStart = Integer.MAX_VALUE;
        for (View host : found) {
            int[] at = offset(bar, host);
            int start = rtl ? bar.getWidth() - (at[0] + host.getWidth()) : at[0];
            if (start + host.getWidth() / 2 < middle && start < backStart) {
                back = host;
                backStart = start;
            }
        }
        return back;
    }

    /** Adds the clickable Litho hosts under [view] to [found], a clickable host's own children passed over. */
    private static void buttons(View view, int depth, List<View> found) {
        if (!(view instanceof ViewGroup) || depth > MAX_DEPTH) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isHost(child) && child.isClickable()) {
                found.add(child);
            } else {
                buttons(child, depth + 1, found);
            }
        }
    }

    /** Where [view] sits in [bar], left and top. */
    private static int[] offset(View bar, View view) {
        int x = 0;
        int y = 0;
        View at = view;
        while (at != null && at != bar) {
            x += at.getLeft();
            y += at.getTop();
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return new int[]{x, y};
    }

    /** Whether [view] is [bar] or sits inside it. */
    private static boolean within(View bar, View view) {
        View at = view;
        while (at != null) {
            if (at == bar) return true;
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean isHost(View view) {
        return view instanceof ViewGroup && HOST.equals(view.getClass().getName());
    }

    private static void remember(View host) {
        hidden.put(host, Boolean.TRUE);
        if (listening.put(host, Boolean.TRUE) != null) return;
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                view.removeOnAttachStateChangeListener(this);
                listening.remove(view);
                show(view);
            }
        });
    }

    private static void show(View host) {
        hidden.remove(host);
        if (host.getVisibility() == View.INVISIBLE) host.setVisibility(View.VISIBLE);
    }

    /** Forgets every watched screen and hidden host, for tests. */
    static void reset() {
        watched.clear();
        hidden.clear();
        listening.clear();
        titleLogged.clear();
        barIdForTests = null;
    }
}
