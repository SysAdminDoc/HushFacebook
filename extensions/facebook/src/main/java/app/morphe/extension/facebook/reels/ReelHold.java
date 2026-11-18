/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.menu.MenuLayoutDump;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hold a reel for 2x: a reel you hold plays at double speed until you let go.
 *
 * <p>Facebook's Reels controls already have this, behind server flags most accounts don't get: a
 * long press on a reel's left or right edge speeds it up, and the reel's touch listener puts the
 * speed back when the finger lifts. Without the flags, a long press opens Facebook's long-press
 * menu instead. The patch hands this class Facebook's answers where the controls decide:
 *
 * <ul>
 *   <li>{@link #longPress}: the speed-up flag, where a long press on a reel chooses between the
 *       speed-up and the menu. Yes while the switch is on, so the hold speeds the reel up instead
 *       of opening the menu. The menu is still one tap away on the reel's more button. Ads open
 *       the menu whatever the flag says.</li>
 *   <li>{@link #held}: the handler has taken the speed-up path, past the flag, the ad check and
 *       the edge check. That's a hold.</li>
 *   <li>{@link #anywhere}: the check of whether the press landed on an edge. Yes while the switch
 *       is on, so a hold anywhere on the reel counts. With Only on the right edge on too, yes only
 *       where {@link #edgeTouch}, first in that check, found the press on the reel's right third,
 *       and no anywhere else, so the press opens Facebook's long-press menu.</li>
 *   <li>{@link #holdSpeed}: the speed a hold plays at, which the speed-up, the speed the lift puts
 *       back and the 2x label all read. Outside the Video tab Facebook answers a fixed 2x, and where
 *       an account's Reels live in the Video tab it answers a server value, which may say normal
 *       speed where the server never gave the feature. While on, anything not faster than normal
 *       is 2x. 588's speed-up sets a fixed 2x and reads no such value, so nothing calls this there.</li>
 *   <li>{@link #speedUp}: the speed-up flag where the controls decide whether to give a reel its
 *       release listener. Yes while the switch is on, so every reel has one.</li>
 *   <li>{@link #release}: both flags the release listener asks before it puts the speed back.
 *       Yes from a hold until a lift the listener hears, since it hears every touch and would
 *       otherwise put back the speed the reel had before its last hold, undoing a speed picked in
 *       the menu since then. It puts the speed back on a lift or a cancel, so once one reaches it
 *       during a hold, the hold is over from the next gesture on. A lift it doesn't hear leaves the
 *       hold for the next one: the listener is drawn with the reel, and a lift before the speed-up
 *       has it drawn again can reach one that lets it go by, or none. {@link #touch} sees each
 *       gesture start and end first.</li>
 *   <li>{@link #speedSet}: every speed FbGrootPlayer's speed setter gets. A hold's speed-up has the
 *       player's speed read through its getter first, and the hold's lift puts that speed back in
 *       place of the one Facebook noted, which is normal speed on a reel Keep the reel speed started
 *       at a kept 2x, and on one a listener drawn before a pick puts back.</li>
 * </ul>
 *
 * <p>With Debug logging on, {@link #speedSet} logs the speed a hold speeds a reel up to and the one
 * its lift puts back, which tells what a muted reel plays at. A tap still plays or pauses, a double
 * tap and the side buttons work as before, and reels that are ads keep Facebook's long-press menu.
 * Off, paused, before the settings are ready, or when anything here fails, every answer is
 * Facebook's own.
 *
 * <p>Keep the reel speed brings {@link #touch}, {@link #held}, {@link #speedSet} and
 * {@link #release} along too, for the accounts Facebook gives its own hold. There the release
 * listener is on every reel, hears every lift, and puts back the speed the reel was drawn at, so a
 * tap or the start of a swipe took a reel picked at 1.5x back to normal speed (issue #25). With Keep
 * the reel speed on and this switch off or not in the build, Facebook's flags keep their answers
 * during Facebook's own hold, its lift gets the speed from before the hold, and outside a hold the
 * listener is told no, so it leaves the speed alone.
 */
public final class ReelHold {
    /** Counted under the patch's name for each long press on a reel that went to the speed-up while the switch is on. */
    static final String HELD = "hold on a reel";

    /** Counted for each hold Only on the right edge leaves to Facebook because it landed outside the right third. */
    static final String OFF_THE_RIGHT_EDGE = "hold outside the right third";

    /** Where the edge check's press landed: in the reel's right third, outside it, or not measured. */
    private static final int UNMEASURED = -1;
    private static final int OUTSIDE = 0;
    private static final int RIGHT_THIRD = 1;

    /** What {@link #edgeTouch} measured for the edge check running now, until its answer goes out. */
    private static volatile int pressedAt = UNMEASURED;

    /** The hold speed while on, where Facebook's isn't faster than normal. */
    static final double DOUBLE_SPEED = 2.0;

    private static final String FAMILY = FamilyNames.HOLD_REEL_FOR_2X;

    /** Whether the build carries each patch, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean holdInBuildForTests;
    @Nullable
    static volatile Boolean keepInBuildForTests;

    /** Whether a hold sped a reel up and the speed hasn't gone back since. */
    private static volatile boolean holding;

    /** Whether the gesture going on now has lifted or been cancelled. */
    private static volatile boolean lifted;

    /** Whether the release listener heard a lift during a hold, so the speed went back. The next gesture ends the hold. */
    private static volatile boolean restored;

    /** Whether the next speed the setter gets is a hold's speed-up, or its lift's. */
    private static volatile boolean speedUpNext;
    private static volatile boolean backNext;

    /** Whether the hold going on began before the last one's speed went back. */
    private static volatile boolean again;

    /** The player a hold sped up, weakly, and the speed it played at before, NaN when unread. */
    @Nullable
    private static volatile WeakReference<Object> heldPlayer;
    private static volatile float before = Float.NaN;

    /** Two speeds this close are the same one, as Facebook's player compares them. */
    static final float SAME = 0.01f;

    /**
     * How long after the release listener's questions its lift may still set the held reel's speed.
     * It sets it straight after asking, so a set later than this is something else: a next reel
     * Facebook moved on to on the same pooled player, say.
     */
    static final long BACK_WINDOW_MS = 1_000;

    /** When the release listener last asked, for {@link #BACK_WINDOW_MS}. */
    private static volatile long backSince;

    /** Reads a player's speed. {@link #PATCHED} is the patch's getter; tests stand in. */
    interface Speeds {
        float of(Object player);
    }

    static final Speeds PATCHED = ReelHold::playerSpeed;

    static volatile Speeds speeds = PATCHED;

    private ReelHold() {
    }

    /**
     * The hook, first thing in FbFragmentActivity.dispatchTouchEvent, before any view hears the
     * event: a finger landing starts a gesture, and ends a hold whose speed went back; the last
     * finger lifting or a cancel ends the gesture.
     */
    public static void touch(MotionEvent event) {
        try {
            if (event == null) return;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (restored) holding = false;
                restored = false;
                lifted = false;
                speedUpNext = false;
                backNext = false;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                lifted = true;
            }
        } catch (Throwable failure) {
            threwShared("touch", failure);
        }
    }

    /**
     * After the long-press handler asks Facebook's speed-up flag. Yes while on. With Debug logging
     * on, the windows are read a moment later for the long-press menu's Muse share target.
     */
    public static boolean longPress(boolean facebooks) {
        HookStatus.invoked(FAMILY);
        MenuLayoutDump.reelLongPressed();
        return on("long press") || facebooks;
    }

    /**
     * The hook, straight after the long-press handler loads its "speed_up" log name, which it does
     * only on its way to the speed-up: past the flag, the ad check and the edge check. A hold.
     */
    public static void held() {
        invokedShared();
        boolean hold = on("hold");
        if (!hold && !keeping("hold")) return;
        again = holding && !restored;
        holding = true;
        restored = false;
        speedUpNext = true;
        if (hold) HookStatus.counted(FAMILY, HELD);
        Logger.printDebug(() -> hold ? "Reel hold: a long press on a reel went to the speed-up"
                : "Reel hold: Facebook's own hold sped a reel up");
    }

    /**
     * The hook, first thing in FbGrootPlayer's speed setter, whoever calls it: the speed the setter
     * goes on with. When a hold's speed-up sets [player]'s speed, it notes the speed the player plays
     * at, unless the hold began before the last one's speed went back on the same player, whose noted
     * speed stays. When the hold's lift then sets that player to another speed, the noted one goes on
     * instead: the speed-up takes a reel already at the hold speed for one at normal speed, and the
     * listener puts back the speed it had when the reel was drawn, so a kept speed or one picked
     * since would come back as normal speed. Every other speed goes on as Facebook set it, and so
     * does the lift's where the player's speed couldn't be read.
     */
    public static float speedSet(Object player, float speed) {
        try {
            invokedShared();
            if (!on("speed set") && !keeping("speed set")) return speed;
            if (speedUpNext) {
                speedUpNext = false;
                WeakReference<Object> last = heldPlayer;
                if (!again || last == null || last.get() != player) {
                    heldPlayer = null;
                    before = speeds.of(player);
                    heldPlayer = new WeakReference<>(player);
                }
                float was = before;
                Logger.printDebug(() -> "Reel hold: speed " + speed + "x, the reel was at " + was + "x");
            } else if (backNext) {
                if (SystemClock.uptimeMillis() - backSince > BACK_WINDOW_MS) {
                    // The lift this waited for never set a speed. Whatever sets one now goes on as set.
                    backNext = false;
                    return speed;
                }
                WeakReference<Object> held = heldPlayer;
                Object heldNow = held == null ? null : held.get();
                // Only the held reel's own lift ends the wait. Another player's speed set meanwhile,
                // a next reel Facebook readies say, goes on as Facebook set it; the next finger down
                // ends the wait anyway (touch).
                if (heldNow != null && heldNow != player) return speed;
                backNext = false;
                float back = before;
                if (heldNow != null && !Float.isNaN(back) && Math.abs(speed - back) >= SAME) {
                    Logger.printDebug(() -> "Reel hold: back to " + back + "x (the speed before the hold)");
                    return back;
                }
                Logger.printDebug(() -> "Reel hold: back to " + speed + "x");
            }
        } catch (Throwable failure) {
            threwShared("speed set", failure);
        }
        return speed;
    }

    /** Filled in by the patch: FbGrootPlayer's speed getter, which the release listener reads. Only a player may be passed. */
    public static float playerSpeed(Object player) {
        return Float.NaN;
    }

    /** Before Facebook's hold speed goes out. While on, one that isn't faster than normal is 2x. */
    public static double holdSpeed(double facebooks) {
        HookStatus.invoked(FAMILY);
        if (!on("hold speed") || facebooks > 1.0) return facebooks;
        Logger.printDebug(() -> "Reel hold: Facebook's hold speed is " + facebooks + "x, holding at " + DOUBLE_SPEED + "x");
        return DOUBLE_SPEED;
    }

    /**
     * The hook, first thing in Facebook's check of whether a long press landed on a reel's edge, the
     * press and the reel's view it measures against. Notes whether the press landed on the view's
     * right third, as wide as the view is at the press, for {@link #anywhere} to answer from. Reads no
     * setting, so it's safe whenever Facebook calls it.
     */
    public static void edgeTouch(MotionEvent event, View view) {
        try {
            pressedAt = UNMEASURED;
            if (event == null || view == null || view.getWidth() <= 0) return;
            pressedAt = event.getX() >= view.getWidth() * 2f / 3f ? RIGHT_THIRD : OUTSIDE;
        } catch (Throwable failure) {
            pressedAt = UNMEASURED;
            HookStatus.threw(FAMILY, "right edge", failure);
        }
    }

    /**
     * After Facebook's check of whether a long press landed on a reel's edge. Yes while on. With Only
     * on the right edge on too, yes on the reel's right third and no anywhere else, so the press opens
     * Facebook's long-press menu. That holds where Facebook gives the account its own hold as well,
     * whose check says yes on the left edge too: the switch promises the right third only. A press
     * {@link #edgeTouch} couldn't measure counts wherever it landed.
     */
    public static boolean anywhere(boolean facebooks) {
        HookStatus.invoked(FAMILY);
        int pressed = pressedAt;
        pressedAt = UNMEASURED;
        if (!on("edge check")) return facebooks;
        if (pressed != OUTSIDE || !rightEdgeOnly()) return true;
        HookStatus.counted(FAMILY, OFF_THE_RIGHT_EDGE);
        Logger.printDebug(() -> "Reel hold: a long press outside the right third goes to the menu, Facebook's answer "
                + facebooks);
        return false;
    }

    /** Whether Only on the right edge is on. Read only once {@link #on} said yes. A failure reads off. */
    private static boolean rightEdgeOnly() {
        try {
            return Settings.HOLD_REEL_RIGHT_EDGE.get();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "right edge", failure);
            return false;
        }
    }

    /** After the controls ask the speed-up flag to decide on a reel's release listener. Yes while on. */
    public static boolean speedUp(boolean facebooks) {
        HookStatus.invoked(FAMILY);
        return on("release listener") || facebooks;
    }

    /**
     * After the release listener asks either flag. While on, yes from a hold until a lift the listener
     * hears, whose two questions both get yes; the gesture after it starts with the hold over. With
     * Keep the reel speed guarding instead, Facebook's answer during a hold of Facebook's own and no
     * outside one, so a lift that ended no hold puts back nothing.
     */
    public static boolean release(boolean facebooks) {
        invokedShared();
        boolean hold = on("release");
        if (!hold && !keeping("release")) return facebooks;
        boolean yes = hold ? holding : facebooks && holding;
        if (yes && lifted && !restored) {
            restored = true;
            backSince = SystemClock.uptimeMillis();
            backNext = true;
        }
        // The listener asks on every touch event of a gesture, so only its lift, the one that used to
        // reset the speed, is logged.
        if (!hold && facebooks && !holding && lifted) {
            Logger.printDebug(() -> "Reel hold: a lift outside a hold, the reel keeps its speed");
        }
        return yes;
    }

    /** Whether the switch is on, with [where] bound. Never throws: a failure is reported and reads off. */
    private static boolean on(String where) {
        try {
            if (!holdInBuild() || !Utils.settingsReady() || !Settings.HOLD_REEL_FOR_2X.get()) return false;
            HookStatus.bound(FAMILY, where);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, where, failure);
            return false;
        }
    }

    /**
     * Whether Keep the reel speed guards the release listener, with [where] bound under its name: it's
     * in the build and on. Never throws: a failure is reported and reads off.
     */
    private static boolean keeping(String where) {
        try {
            if (!keepInBuild() || !Utils.settingsReady() || !Settings.KEEP_REEL_SPEED.get()) return false;
            HookStatus.bound(FamilyNames.KEEP_REEL_SPEED, where);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.KEEP_REEL_SPEED, where, failure);
            return false;
        }
    }

    /**
     * Whether Hold a reel for 2x is in this build: its own patch applied. These hooks come with it or
     * with Keep the reel speed, and the guard goes in before either patch does its own part, so when
     * Keep the reel speed's part refuses and Hold a reel for 2x wasn't picked, the hooks are there
     * with neither status on. They leave every answer to Facebook then, whatever Hold's switch says.
     */
    static boolean holdInBuild() {
        Boolean forced = holdInBuildForTests;
        return forced != null ? forced : SettingsStatus.reelHold();
    }

    static boolean keepInBuild() {
        Boolean forced = keepInBuildForTests;
        return forced != null ? forced : SettingsStatus.keepReelSpeed();
    }

    /**
     * The family the shared hooks count under: this patch's when it's in the build, else Keep the
     * reel speed's when that one is, else none, so a build carrying only the guard reports no
     * family's hooks as run.
     */
    @Nullable
    private static String family() {
        if (holdInBuild()) return FAMILY;
        return keepInBuild() ? FamilyNames.KEEP_REEL_SPEED : null;
    }

    /** One more run of a shared hook, under {@link #family()} when there is one. */
    private static void invokedShared() {
        String family = family();
        if (family != null) HookStatus.invoked(family);
    }

    /** A shared hook's throw, under {@link #family()}, or in the log when neither patch is in the build. */
    private static void threwShared(String where, Throwable failure) {
        String family = family();
        if (family != null) {
            HookStatus.threw(family, where, failure);
        } else {
            Logger.printException(() -> "Reel hold: the '" + where + "' hook threw with neither reel patch in the build", failure);
        }
    }

    /** Forgets the hold, the gesture and the speed before the hold, and reads speeds through the patch's getter. For tests. */
    static void forget() {
        holding = false;
        lifted = false;
        restored = false;
        speedUpNext = false;
        backNext = false;
        backSince = 0;
        again = false;
        heldPlayer = null;
        before = Float.NaN;
        pressedAt = UNMEASURED;
        speeds = PATCHED;
        holdInBuildForTests = null;
        keepInBuildForTests = null;
    }
}
