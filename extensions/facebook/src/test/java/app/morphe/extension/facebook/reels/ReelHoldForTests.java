/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.robolectric.RuntimeEnvironment;

import java.util.function.BooleanSupplier;
import java.util.function.ToDoubleFunction;

/** What tests outside this package need of Hold a reel for 2x: a hold on a reel and its lift. */
public final class ReelHoldForTests {
    private ReelHoldForTests() { }

    /** FbGrootPlayer's speed setter as the patched app has it: its hooks, then the speed they leave going on. */
    public interface Setter {
        void set(Object player, float speed);
    }

    /**
     * Runs [probe] in a build that carries Hold a reel for 2x, as its patch's status says in one, and
     * gives back what it answered. The build is as it was before once it returns.
     */
    public static boolean withHold(BooleanSupplier probe) {
        Boolean build = ReelHold.holdInBuildForTests;
        ReelHold.holdInBuildForTests = true;
        try {
            return probe.getAsBoolean();
        } finally {
            ReelHold.holdInBuildForTests = build;
        }
    }

    /**
     * A hold on [player] and its lift, as Facebook makes them in a build with Hold a reel for 2x: a
     * long press that goes to the speed-up sets 2x through [setter], and on the lift the release
     * listener, when both its flags say yes, sets [facebooks], the speed it noted when the reel was
     * drawn. [speeds] reads a player's speed as FbGrootPlayer's getter does.
     */
    public static void holdAndLift(Object player, ToDoubleFunction<Object> speeds, Setter setter, float facebooks) {
        ReelHold.holdInBuildForTests = true;
        ReelHold.speeds = held -> (float) speeds.applyAsDouble(held);
        finger(MotionEvent.ACTION_DOWN);
        // No, and Facebook opens its long-press menu.
        if (!ReelHold.longPress(false)) return;
        ReelHold.held();
        setter.set(player, 2f);
        finger(MotionEvent.ACTION_UP);
        if (ReelHold.release(false) && ReelHold.release(false)) setter.set(player, facebooks);
    }

    /** Facebook's 2x pill states, by the names its enum keeps. */
    public enum Pill { NORMAL, LOCK_HINT, READY_TO_LOCK, LOCKED, UNLOCK_HINT, READY_TO_UNLOCK, NUX_PROMPT }

    /**
     * A 2x pill and a Reels 2x pill made with the "Pull down to lock" hint, in a build with Hold a
     * reel for 2x: true when either hook drew its pill as the plain one.
     */
    public static boolean hidesTheLockHint() {
        return withHold(() -> LockHint.pill(Pill.LOCK_HINT) == Pill.NORMAL || !LockHint.reelPill(ReelPill.UNLOCKED_2X_SPEED, true));
    }

    /** A pause or a cold-start check's view of Hide the 2x popups: true when a 2x pill would draw nothing. */
    public static boolean hidesThePill() {
        return withHold(LockHint::hidesPill);
    }

    /** Facebook's Reels 2x pill states, by the names its enum keeps. */
    public enum ReelPill {
        LOCKED_WITH_FOOTER, INITIATING_LOCK, INITIATING_RESTORE, UNLOCKED_ORIGINAL_SPEED, UNLOCKED_2X_SPEED, ORIGINAL_SPEED
    }

    /**
     * A reel at 1.5x held, whose lift sets normal speed: true when the setter's hook changed that
     * speed. Leaves nothing behind.
     */
    public static boolean putsBackTheSpeedBeforeAHold() {
        ReelHold.forget();
        ReelHold.holdInBuildForTests = true;
        Object reel = new Object();
        ReelHold.speeds = player -> 1.5f;
        try {
            finger(MotionEvent.ACTION_DOWN);
            ReelHold.held();
            ReelHold.speedSet(reel, 2f);
            finger(MotionEvent.ACTION_UP);
            ReelHold.release(false);
            return ReelHold.speedSet(reel, 1f) != 1f;
        } finally {
            ReelHold.forget();
        }
    }

    /**
     * A long press [x] pixels into a reel 300 pixels wide, as Facebook's edge check gets it: the
     * press measured first, then the check's answer [facebooks] through the hook. Gives back what
     * the hook answered.
     */
    public static boolean pressAt(float x, boolean facebooks) {
        View reel = new View(RuntimeEnvironment.getApplication());
        reel.layout(0, 0, 300, 600);
        long now = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, 300, 0);
        try {
            ReelHold.edgeTouch(press, reel);
            return ReelHold.anywhere(facebooks);
        } finally {
            press.recycle();
        }
    }

    /** Forgets the hold and the gesture, and reads speeds through the patch's getter again. */
    public static void forget() {
        ReelHold.forget();
    }

    static void finger(int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 100, 200, 0);
        ReelHold.touch(event);
        event.recycle();
    }
}
