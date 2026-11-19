/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Long press to save Marketplace photos (#515), Download any photo's fourth switch.
 *
 * <p>A Marketplace listing is a React Native screen, and every photo on it, in the listing's photo
 * strip and opened full screen, is a React Native image view: an ImageView its image manager (a kept
 * name) hands the photo's sources to in setSource or setSrc, an array of maps each holding a
 * {@code uri} and, when there's more than one, a {@code width} and {@code height}. The patch hands
 * {@link #source} that view and that array first thing in both setters, and the biggest web address
 * among them is kept for the view. A photo on the phone (a seller's own, still being listed) or a
 * placeholder has no web address and keeps nothing.
 *
 * <p>The image view has no touch handling of its own, so a view with a kept address gets a touch
 * listener that only notes a finger going down on it and never takes the finger: the listing's own
 * pressable gets every touch as before. React Native then takes the press from the photo for that
 * pressable, so the photo never hears how it ends. What does is React Native's touch dispatcher,
 * which each root view of a React Native screen hands every touch in its window before its views
 * see it, and which the patch hands {@link #root} each touch first. A root never stops listening
 * when a view asks it to. This times the press the way Long press opens comments does: a finger
 * held still for the system's long-press time on a photo at least two thirds of the screen across
 * (and a third of it tall) saves the kept address through the same save as every other photo, with
 * its folder and its toasts, then ends the press for the rest of the screen, so the lift doesn't
 * also open the photo. Moving past the touch slop, a second finger, a lift, a cancel or a window that
 * lost focus saves nothing, and so does a press on a smaller image, like an icon or a seller's
 * picture, or one the root never heard go down. With either switch off or Hushfacebook paused
 * nothing is kept, the listener comes off with the next photo, and every touch goes through
 * untouched. Facebook draws big photos on a few other React Native screens the same way, where a
 * long press saves the same way.
 */
public final class ListingPhoto {
    private static final String FAMILY = FamilyNames.PHOTO_DOWNLOAD;

    /** The source every event of the hook carries in the diagnostic report. */
    static final String SOURCE = "ListingPhoto";
    /** The kept name of what setSource is handed, and of each entry in it. */
    static final String READABLE_ARRAY = "com.facebook.react.bridge.ReadableArray";
    static final String READABLE_MAP = "com.facebook.react.bridge.ReadableMap";
    /** Counted for each press on a listing photo held long enough to save it. */
    static final String HELD = "listing photo held";
    private static final String HOOK = "Marketplace listing photo";
    private static final String TOUCHES = "React Native touches";

    /** The address kept for each image view, forgotten with the view. */
    private static final Map<View, String> ADDRESSES = Collections.synchronizedMap(new WeakHashMap<>());

    @Nullable private static volatile Method size;
    @Nullable private static volatile Method getMap;
    @Nullable private static volatile Method getString;
    @Nullable private static volatile Method hasKey;
    @Nullable private static volatile Method getDouble;
    private static volatile boolean lookedUp;

    private static Handler main;
    private static WeakReference<View> pressed = new WeakReference<>(null);
    private static float downX;
    private static float downY;
    private static long downTime;
    private static int slop;
    private static boolean timing;
    /** When the finger React Native's root last heard go down went down. */
    private static long rootDown = Long.MIN_VALUE;
    private static final Runnable FIRE = ListingPhoto::fire;

    /** Each kept photo's touches: a finger going down is noted, and every touch goes on to the listing. */
    private static final View.OnTouchListener PRESS = (view, event) -> {
        down(view, event);
        return false;
    };

    private ListingPhoto() {
    }

    /** Both switches on, Hushfacebook running and its settings ready. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.DOWNLOAD_PHOTOS.get() && Settings.MARKETPLACE_PHOTO_SAVE.get();
    }

    /**
     * Injection point, first thing in the React Native image manager's setSource and setSrc: [view]
     * is the image view and [sources] what React Native set as its photo. The old photo's address
     * goes first, so a new photo without one never saves the last, and a view left with no address
     * gives its touch listener up. Never throws.
     */
    public static void source(@Nullable View view, @Nullable Object sources) {
        try {
            if (view == null) return;
            boolean listened = ADDRESSES.remove(view) != null;
            String url = null;
            if (enabled()) {
                HookStatus.invoked(FAMILY);
                url = biggest(sources);
            }
            if (url == null) {
                if (listened) view.setOnTouchListener(null);
                return;
            }
            ADDRESSES.put(view, url);
            view.setOnTouchListener(PRESS);
            HookStatus.bound(FAMILY, HOOK);
            String kept = url;
            Logger.diagnosticDebug(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "Marketplace photo: kept a " + MediaDownload.describe(kept) + " for a long press");
        } catch (Throwable t) {
            HookStatus.threw(FAMILY, HOOK, t);
        }
    }

    /**
     * The web address of the biggest source in [sources], or null when it isn't an array of
     * sources or none has one. A source's size is its width times its height, and one with either
     * missing or unreadable counts as no size; the first wins a tie.
     */
    @Nullable
    private static String biggest(@Nullable Object sources) throws ReflectiveOperationException {
        if (sources == null || !lookUp()) return null;
        if (!size.getDeclaringClass().isInstance(sources)) return null;
        int count = (Integer) size.invoke(sources);
        String best = null;
        double bestArea = -1;
        for (int i = 0; i < count; i++) {
            Object source = getMap.invoke(sources, i);
            if (source == null || !getString.getDeclaringClass().isInstance(source)) continue;
            Object uri = getString.invoke(source, "uri");
            if (!(uri instanceof String) || !isWebAddress((String) uri)) continue;
            double area = number(source, "width") * number(source, "height");
            if (area > bestArea) {
                best = (String) uri;
                bestArea = area;
            }
        }
        return best;
    }

    /** The number [source] holds under [name], or 0 when it holds none or something else. */
    private static double number(Object source, String name) {
        try {
            if (!Boolean.TRUE.equals(hasKey.invoke(source, name))) return 0;
            Object value = getDouble.invoke(source, name);
            return value instanceof Double && (Double) value > 0 ? (Double) value : 0;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return 0;
        }
    }

    private static boolean isWebAddress(String uri) {
        return uri.regionMatches(true, 0, "https://", 0, 8) || uri.regionMatches(true, 0, "http://", 0, 7);
    }

    /** Looks the bridge's methods up once; false, said in Hook status, when one is missing. */
    private static boolean lookUp() {
        if (lookedUp) return getDouble != null;
        synchronized (ListingPhoto.class) {
            if (lookedUp) return getDouble != null;
            String owner = READABLE_ARRAY;
            String name = "size";
            try {
                Class<?> array = Class.forName(READABLE_ARRAY);
                size = array.getMethod(name);
                name = "getMap";
                getMap = array.getMethod(name, int.class);
                owner = READABLE_MAP;
                Class<?> map = Class.forName(READABLE_MAP);
                name = "getString";
                getString = map.getMethod(name, String.class);
                name = "hasKey";
                hasKey = map.getMethod(name, String.class);
                name = "getDouble";
                getDouble = map.getMethod(name, String.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
                getDouble = null;
                HookStatus.missingMember(FAMILY, "method", owner, name);
            }
            lookedUp = true;
            return getDouble != null;
        }
    }

    /**
     * As big as a listing's photo: two thirds of the screen across and a third of it tall, so an
     * icon, a seller's picture or a thumbnail beside the photos never saves.
     */
    private static boolean bigEnough(View view) {
        int screen = view.getResources().getDisplayMetrics().widthPixels;
        return screen > 0 && view.getWidth() * 3 >= screen * 2 && view.getHeight() * 3 >= screen;
    }

    /**
     * A finger going down on a kept photo [view], from its touch listener: on a big photo with a
     * kept address, for a press React Native's root heard go down, the timer starts. A photo drawn
     * above another hears the press first, so the one under it leaves that press alone. Never throws.
     */
    static void down(@Nullable View view, @Nullable MotionEvent event) {
        try {
            if (view == null || event == null || event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
            if (timing && event.getDownTime() == downTime) return;
            stop();
            if (!enabled() || addressOf(view) == null || !bigEnough(view)) return;
            if (event.getDownTime() != rootDown) {
                Logger.diagnosticDebug(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "Marketplace photo: React Native's root didn't hear this press");
                return;
            }
            HookStatus.bound(FAMILY, TOUCHES);
            pressed = new WeakReference<>(view);
            downX = event.getRawX();
            downY = event.getRawY();
            downTime = event.getDownTime();
            slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            timing = true;
            main().postDelayed(FIRE, ViewConfiguration.getLongPressTimeout());
            Logger.diagnosticDebug(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "Marketplace photo: timing a press");
        } catch (Throwable t) {
            stop();
            HookStatus.threw(FAMILY, HOOK, t);
        }
    }

    /**
     * Injection point, first thing in React Native's touch dispatcher, which a React Native screen's
     * root view hands every touch in its window before its views see it. A finger going down is
     * noted, and while a press is timed, anything of that finger's but a small wobble ends it: a
     * move past the touch slop, a second finger, a lift or a cancel. Never throws.
     */
    public static void root(@Nullable MotionEvent event) {
        try {
            if (event == null) return;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                rootDown = event.getDownTime();
                if (timing && event.getDownTime() != downTime) stop();
                return;
            }
            if (!timing || event.getDownTime() != downTime) return;
            if (action == MotionEvent.ACTION_MOVE
                    && Math.hypot(event.getRawX() - downX, event.getRawY() - downY) <= slop) return;
            stop();
            Logger.diagnosticDebug(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "Marketplace photo: press ended before the long-press time (" + MotionEvent.actionToString(action) + ")");
        } catch (Throwable t) {
            stop();
            HookStatus.threw(FAMILY, TOUCHES, t);
        }
    }

    private static void fire() {
        if (!timing) return;
        timing = false;
        View view = pressed.get();
        pressed = new WeakReference<>(null);
        try {
            if (view == null || !enabled()) return;
            // A call, a task switch or a dialog that took the window mid-press leaves it alone.
            if (!view.isAttachedToWindow() || !view.hasWindowFocus()) return;
            String url = addressOf(view);
            if (url == null) return;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            endPress(view);
            HookStatus.counted(FAMILY, HELD);
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "Marketplace photo: held, saving a " + MediaDownload.describe(url));
            save(view.getContext().getApplicationContext(), url);
        } catch (Throwable t) {
            HookStatus.threw(FAMILY, HOOK, t);
        }
    }

    /**
     * Ends the held press for [view]'s whole window, the way a scroll that takes a finger does:
     * React Native's root view hears a cancel, so the listing never sees the lift as a tap.
     */
    private static void endPress(View view) {
        MotionEvent cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0);
        try {
            view.getRootView().dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }

    /** Saves [url] as every other photo saves, saying so on screen when it couldn't start. */
    private static void save(Context application, String url) {
        if (MediaDownload.busy(application)) return;
        if (!MediaDownload.savePhoto(application, url, PostDetails.NONE)) {
            Feedback.show(application, L10n.t(application, "Couldn't save this photo. Try again in a moment."), true);
        }
    }

    private static void stop() {
        timing = false;
        pressed = new WeakReference<>(null);
        if (main != null) main.removeCallbacks(FIRE);
    }

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /** The address kept for [view], or null when it has none. */
    @Nullable
    static String addressOf(@Nullable View view) {
        return view == null ? null : ADDRESSES.get(view);
    }

    /** For tests: forgets every kept address and the press being timed. */
    static void reset() {
        stop();
        rootDown = Long.MIN_VALUE;
        ADDRESSES.clear();
    }
}
