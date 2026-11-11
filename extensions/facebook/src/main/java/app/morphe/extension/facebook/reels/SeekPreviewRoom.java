/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keeps Facebook's seek preview off a reel's kept name and caption (found during #120). With Keep
 * the name and buttons while seeking on, a press of the bar leaves the footer up, but Facebook still
 * shows its preview frame and time just above the bar, where its own seek look has nothing. The box
 * drew over the creator line and the caption's second row. The S22 tree mid-drag on 586 (2026-11-10):
 *
 * <pre>
 *   X.0A4v 0,1085-1080,2165             the scrubber's host, beside the list of reels
 *     X.0TKN 0,0-1080,1080
 *       X.02Eg 0,918-291,1080           the time label, hidden while the preview shows
 *       X.0DAH 45,529-1035,1080         the preview, inflated from a stub at the first drag
 *         LinearLayout 349,0-630,410    the frame and its time, following the finger
 *       X.09jb                          the bar
 * </pre>
 *
 * <p>The footer's block started near 1771 on screen, so the box from 1614 to 2024 covered its first
 * rows. While the preview of a kept press shows, this raises it until the box clears the footer by
 * {@link #GAP_DP}, and puts it back when the box goes. The footer is the block {@link CaptionRoom}
 * knows, hanging below its column. With none under the box, Facebook's placement stays. 586's ids
 * are obfuscated, so the preview is the label's sibling holding a LinearLayout.
 */
public final class SeekPreviewRoom {
    private static final String FAMILY = FamilyNames.PROGRESS_BAR;
    static final String HOOK = "reel seek preview room";
    /** Counted each time a kept press's preview is raised over the reel's name and caption. */
    static final String LIFTED = "Reel seek preview raised over the kept name and caption";

    /** How far above the footer the box sits. */
    static final float GAP_DP = 8f;
    /** A preview that shows this soon after a kept press's last event belongs to that press. */
    static final long PRESS_MS = 1000;
    private static final String BOX = "android.widget.LinearLayout";

    /** The label's parents being watched. */
    private static final Map<View, Room> rooms = new WeakHashMap<>();
    /** When a press of the bar last left the footer up, or 0. */
    private static volatile long pressedAt;

    private SeekPreviewRoom() {
    }

    /** From each event of a press that leaves the reel's name and buttons up. */
    public static void pressed() {
        pressedAt = Math.max(1, SystemClock.uptimeMillis());
    }

    /** From the scrubber's light mount and its progress update, with its time label. */
    public static void updated(@Nullable View label) {
        try {
            if (label == null || !Utils.settingsReady() || !Settings.KEEP_REEL_OVERLAY.get()) return;
            ViewParent above = label.getParent();
            if (!(above instanceof ViewGroup) || !label.isAttachedToWindow()) return;
            ViewTreeObserver tree = label.getViewTreeObserver();
            Room held = rooms.get(above);
            if (held != null && held.tree == tree && tree.isAlive()) return;
            if (!tree.isAlive()) return;
            Room room = new Room(label, (ViewGroup) above, tree);
            rooms.put((View) above, room);
            tree.addOnPreDrawListener(room);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /** The shown box of the preview beside [label] in [frame], or null. */
    @Nullable
    static View boxIn(ViewGroup frame, View label) {
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (child == label || !(child instanceof ViewGroup) || child.getWidth() == frame.getWidth()) continue;
            ViewGroup preview = (ViewGroup) child;
            for (int j = 0; j < preview.getChildCount(); j++) {
                View box = preview.getChildAt(j);
                if (BOX.equals(box.getClass().getName()) && box.getHeight() > 0 && box.isShown()) return box;
            }
        }
        return null;
    }

    /** How far the preview holding [box] has to rise for the box to clear the footer of [label]'s page. */
    static int liftFor(View label, View box) {
        View preview = (View) box.getParent();
        View page = CaptionRoom.pageOf(label);
        if (page == null) return 0;
        float density = page.getResources().getDisplayMetrics().density;
        int gap = Math.round(GAP_DP * density);
        int[] at = new int[2];
        box.getLocationInWindow(at);
        int boxBottom = at[1] + box.getHeight();
        int[] band = {at[1], boxBottom + gap};
        preview.getLocationInWindow(at);
        int[] span = {at[0], at[0] + preview.getWidth()};
        int top = footerTop(page, 0, Math.round(CaptionRoom.MAX_HANG_DP * density), span, band);
        if (top == Integer.MAX_VALUE) return 0;
        // The preview's parent clips it, so it rises no further than that parent's top.
        int room = Math.round(preview.getTop() + preview.getTranslationY());
        return Math.max(0, Math.min(boxBottom + gap - top, room));
    }

    /** The highest window top of a footer block under [view] that reaches into [span] and [band], or MAX_VALUE. */
    private static int footerTop(View view, int depth, int most, int[] span, int[] band) {
        if (!(view instanceof ViewGroup) || depth > CaptionRoom.MAX_DEPTH) return Integer.MAX_VALUE;
        ViewGroup row = (ViewGroup) view;
        int best = Integer.MAX_VALUE;
        int[] at = new int[2];
        for (int i = 0; i < row.getChildCount(); i++) {
            View column = row.getChildAt(i);
            View block = CaptionRoom.hangingBlock(row, column, most);
            if (block != null && block.isShown()) {
                block.getLocationInWindow(at);
                boolean across = at[0] < span[1] && span[0] < at[0] + block.getWidth();
                boolean under = at[1] < band[1] && band[0] < at[1] + block.getHeight();
                if (across && under) best = Math.min(best, at[1]);
            }
            best = Math.min(best, footerTop(column, depth + 1, most, span, band));
        }
        return best;
    }

    /** Forgets every watched label and press, for tests. */
    static void reset() {
        rooms.clear();
        pressedAt = 0;
    }

    private static final class Room implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> heldLabel;
        private final WeakReference<ViewGroup> heldFrame;
        final ViewTreeObserver tree;
        @Nullable
        private WeakReference<View> heldPreview;
        /** How far this raised the preview, and the translation that left it at. */
        private int lift;
        private float raisedTo;
        /** Whether this show of the box has been measured. */
        private boolean measured;

        Room(View label, ViewGroup frame, ViewTreeObserver tree) {
            heldLabel = new WeakReference<>(label);
            heldFrame = new WeakReference<>(frame);
            this.tree = tree;
        }

        @Override
        public boolean onPreDraw() {
            try {
                place();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, HOOK, failure);
            }
            return true;
        }

        private void place() {
            View label = heldLabel.get();
            ViewGroup frame = heldFrame.get();
            if (label == null || frame == null || label.getParent() != frame || !frame.isAttachedToWindow()) {
                stop(frame);
                return;
            }
            View box = Settings.KEEP_REEL_OVERLAY.get() ? boxIn(frame, label) : null;
            if (box == null) {
                settle();
                return;
            }
            View preview = (View) box.getParent();
            if (measured) {
                // Facebook set the preview's translation itself, so the rise goes on top of its value.
                if (lift > 0 && preview.getTranslationY() != raisedTo) raise(preview);
                return;
            }
            long at = pressedAt;
            if (at == 0 || SystemClock.uptimeMillis() - at > PRESS_MS) return;
            measured = true;
            lift = liftFor(label, box);
            if (lift <= 0) return;
            heldPreview = new WeakReference<>(preview);
            raise(preview);
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, LIFTED);
            int by = lift;
            Logger.printDebug(() -> "Reel bar: the seek preview rose " + by + " px over the kept name and caption");
        }

        private void raise(View preview) {
            raisedTo = preview.getTranslationY() - lift;
            preview.setTranslationY(raisedTo);
        }

        /** Puts the preview back as the box goes, unless Facebook moved it meanwhile. */
        private void settle() {
            View preview = heldPreview == null ? null : heldPreview.get();
            if (preview != null && lift > 0 && preview.getTranslationY() == raisedTo) {
                preview.setTranslationY(raisedTo + lift);
            }
            heldPreview = null;
            lift = 0;
            measured = false;
        }

        private void stop(@Nullable ViewGroup frame) {
            settle();
            if (tree.isAlive()) tree.removeOnPreDrawListener(this);
            if (frame != null && rooms.get(frame) == this) rooms.remove(frame);
        }
    }
}
