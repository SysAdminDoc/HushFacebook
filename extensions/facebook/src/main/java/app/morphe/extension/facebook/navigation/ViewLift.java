/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Views moved up with translationY on top of Facebook's own move, kept out of Litho's position math
 * (#466). Litho animates a host's Y by setting it to where it should be less where its parents are,
 * and it finds where they are by adding up their {@code getY()}, translation included, to the
 * LithoView. A parent lifted here would have that lift taken back off every child Litho animates,
 * so a reel's name and caption slid back down over its comment preview while the preview stayed
 * up. The patch has that walk (588's {@code LX/03lx;->A00}) ask {@link #lithoY} instead, which
 * leaves the lift out.
 *
 * <p>Each view is moved here by one owner at a time. A translation Facebook sets after the lift
 * replaces the lift's base, so the lift goes on top of it and giving it back leaves Facebook's.
 */
public final class ViewLift {
    /** Each moved view: [0] how far it's moved up, [1] the translationY it was left at. */
    static final Map<View, float[]> MOVED = new WeakHashMap<>();

    private ViewLift() {
    }

    /** Moves [view] up by [lift] in all, on top of Facebook's own move. 0 gives it back. */
    static void move(View view, int lift) {
        synchronized (MOVED) {
            float[] state = MOVED.get(view);
            float now = view.getTranslationY();
            float ours = state != null && now == state[1] ? state[0] : 0f;
            float left = now + ours - lift;
            if (left != now) view.setTranslationY(left);
            if (lift == 0) {
                MOVED.remove(view);
            } else if (state == null) {
                MOVED.put(view, new float[] {lift, left});
            } else {
                state[0] = lift;
                state[1] = left;
            }
        }
    }

    /** How far [view] is moved up here now, 0 when Facebook has moved it since. */
    static float liftOf(View view) {
        synchronized (MOVED) {
            float[] state = MOVED.get(view);
            return state != null && view.getTranslationY() == state[1] ? state[0] : 0f;
        }
    }

    /** Called by Litho's position walk in place of {@code View.getY()}: where [view] is without the lift. */
    public static float lithoY(View view) {
        float y = view.getY();
        try {
            return y + liftOf(view);
        } catch (Throwable ignored) {
            return y;
        }
    }

    static void resetForTests() {
        synchronized (MOVED) {
            MOVED.clear();
        }
    }
}
