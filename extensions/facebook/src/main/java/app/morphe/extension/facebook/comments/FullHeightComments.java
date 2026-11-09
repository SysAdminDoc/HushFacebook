/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.app.Dialog;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.morphe.extension.facebook.media.PauseUnderComments;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Open comments at full height (#179): the comment sheet fills the screen instead of opening part
 * way up.
 *
 * <p>The sheet's height is a fraction of the screen that its parameters carry from their
 * constructor to the popover fragment: one, Facebook's default, makes a phone's sheet full screen,
 * and anything less sizes the window to that share of the screen's height at the bottom. Reels ask
 * for two thirds, videos and some posts for a share a server value sets. The patch hands
 * {@link #sheetHeight} that fraction first thing in the constructor, and while Open comments at is
 * set to full height a share below one comes back as one. Set to half height, a share above a half
 * comes back as a half, so a reel's sheet stops at the middle of the screen with the reel playing
 * above it, and everything below about the video above the comments being hidden is full height's
 * alone.
 *
 * <p>A reel's or a feed video's Comment button first tries Facebook's split screen, the video on
 * top and the comments under it, through five launchers that each answer whether they opened it.
 * Each already answers no when its server gate is off, and the button then opens the regular
 * sheet. The patch asks {@link #skipSplitScreen} first in each, so with the switch on they answer
 * no and the regular sheet, at full height, opens instead.
 *
 * <p>A tap on a feed video reel's description goes through one of those launchers too, behind a
 * server gate, and falls back to the regular sheet when it answers no, so with the switch on "more"
 * opened the comments instead of the text (#269). The patch hands {@link #descriptionOpensComments}
 * that gate's answer, and while the switch is on it comes back closed, so the description expands
 * in place.
 *
 * <p>A share of one still has to pass the sheet fragment's "fill the screen?" check, which can say
 * no from the screen's size against a server value and then draws a centered box 80% of the screen
 * tall. The patch hands {@link #fillsScreen} the share where that check starts (on 584, right after
 * the check makes sure the sheet is the comment one), and while the switch is on a share of one
 * fills the screen.
 *
 * <p>A full-screen sheet on a reel still shows the reel above the comments, in the sheet's header,
 * so the list starts a third of the way down. Either the header holds the video, or, on the Reels
 * tab's watch-and-comment sheet, it's a see-through strip over the reel still playing behind the
 * sheet. The patch hands {@link #watchHeader} the sheet's views, the header's id and whether the
 * sheet is a watch-and-comment one first thing where Facebook sets them up, and while the switch is
 * on such a header is hidden, at once or at the first layout after the video lands in it. A post's
 * header, its reactions, holds no video and stays.
 *
 * <p>A sheet that stops part way up takes a tap on the reel above it as a tap outside and closes
 * (#315). Facebook already lets taps through for a part-height sheet opened from the reel viewer,
 * behind a server gate: the sheet stays on an outside tap and its window passes outside touches
 * on, so the reel gets the tap and plays or pauses. The patch hands {@link #tapThrough} the sheet's
 * dialog and the surface it was opened from right where Facebook reads that surface, and while Tap
 * a reel above its comments is on, a sheet over the reel viewer does the same.
 *
 * <p>The Reels tab's watch-and-comment sheet never gets that far: its window fills the screen, so
 * the reel shows through the sheet's see-through header and a tap there lands inside the sheet,
 * where the header's own tap closes it. While Tap a reel above its comments is on, {@link
 * #watchHeader} hands each view in such a header that covers most of it a touch listener, which
 * plays or pauses the reel on a tap through Pause reels while comments are open's player bridge
 * instead. Smaller views in the header, the sound button, keep their taps, a drag is left alone,
 * and with no reel found the tap closes the sheet as before.
 */
public final class FullHeightComments {
    /** The member the report names once a comment sheet's height has been asked about. */
    static final String SHEET = "comment sheet height";

    /** The member the report names once a split-screen launcher has been asked about. */
    static final String SPLIT_SCREEN = "split-screen comments";

    /** What the report counts each time a sheet opens at full height instead of part way. */
    static final String SHEET_RAISED = "Comment sheet opened at full height";

    /** What the report counts each time a sheet opens at half height instead of higher up. */
    static final String SHEET_HALVED = "Comment sheet opened at half height";

    /** The share of the screen's height a sheet opens to with Open comments at set to half height. */
    static final float HALF = 0.5f;

    /** What the report counts each time the split screen is passed over for the regular sheet. */
    static final String SPLIT_SCREEN_SKIPPED = "Split screen passed over";

    /** The member the report names once a reel description's tap has asked whether it opens the comments. */
    static final String DESCRIPTION = "reel description tap";

    /** What the report counts each time a reel description grows in place instead of opening the comments. */
    static final String DESCRIPTION_EXPANDED = "Reel description expanded in place";

    /** The member the report names once the sheet's "fill the screen?" check has been asked about. */
    static final String FILLS = "comment sheet fills the screen";

    /** What the report counts each time that check is answered yes instead of Facebook's own way. */
    static final String SCREEN_FILLED = "Comment sheet kept full screen";

    /** The member the report names once a comment sheet's views have been handed over. */
    static final String HEADER = "comment sheet header";

    /** What the report counts each time the video above a sheet's comments is hidden. */
    static final String VIDEO_HIDDEN = "Video above the comments hidden";

    /** The member the report names once a part-height sheet's dialog has been handed over. */
    static final String TAP_THROUGH = "comment sheet over a reel";

    /** What the report counts each time a sheet lets the taps above it through to the reel. */
    static final String TAPS_THROUGH = "Taps above the comments go to the reel";

    /** What the report counts each time a tap on a watch-and-comment sheet's header plays or pauses the reel. */
    static final String REEL_TAPPED = "Reel above the comments played or paused";

    /**
     * The surfaces Facebook logs as "tap_fb_shorts_viewer" when a comment sheet opens from them
     * (585 {@code LX/09ru;->A00}): the reel viewer, however it was reached. Facebook's own
     * tap-through takes only 20.
     */
    static final Set<Integer> REEL_VIEWER = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(20, 24, 36, 37, 38, 39)));

    /** How many levels into a header a video is looked for. */
    private static final int VIDEO_DEPTH = 12;

    private static final String FAMILY = FamilyNames.FULL_HEIGHT_COMMENTS;

    /** The headers being watched, so a sheet whose views are set up twice is watched once. */
    private static final Map<View, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());

    /** The views over the reel whose taps play or pause it, so each gets its listener once. */
    private static final Map<View, Boolean> OVER_REEL = Collections.synchronizedMap(new WeakHashMap<>());

    /** Plays or pauses the reel under a sheet, true when there was one. The tests hand in their own. */
    static volatile BooleanSupplier reelTap = PauseUnderComments::tapReel;

    private FullHeightComments() {
    }

    /**
     * The hook, first thing where a comment sheet's parameters are built, handed the share of the
     * screen's height the sheet opens to. A share between zero and one comes back as one while Open
     * comments at is set to full height, and as a half while it's set to half height and the share
     * is more than that. Otherwise, and for a throw, Facebook's own share comes back, so a post's
     * sheet, which fills the screen, stays as it is at half height.
     */
    public static float sheetHeight(float height) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SHEET);
            if (!(height > 0f && height < 1f) || !Utils.settingsReady()) return height;
            CommentsHeight chosen = Settings.COMMENTS_HEIGHT.get();
            if (chosen == CommentsHeight.FULL) {
                HookStatus.counted(FAMILY, SHEET_RAISED);
                return 1f;
            }
            if (chosen != CommentsHeight.HALF || !(height > HALF)) return height;
            HookStatus.counted(FAMILY, SHEET_HALVED);
            return HALF;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "sheet height", failure);
            return height;
        }
    }

    /**
     * The hook, first thing in each launcher of Facebook's split-screen comments. True makes the
     * launcher answer that it didn't open, so the button opens the regular sheet, while Open
     * comments at is set to full or half height; false lets the launcher run.
     */
    public static boolean skipSplitScreen() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SPLIT_SCREEN);
            if (!Utils.settingsReady() || !resized()) return false;
            HookStatus.counted(FAMILY, SPLIT_SCREEN_SKIPPED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "split screen", failure);
            return false;
        }
    }

    /**
     * The hook, where a reel description's tap reads the server gate that sends it to the comments
     * on a feed video's reel, handed that gate's answer (#269). Facebook tries its split screen
     * there, and when a launcher answers no it opens the whole comment sheet instead of growing the
     * text. While Open comments at is set to full or half height, the launchers always answer no, so
     * a tap on "more" would land in the comments: the gate comes back closed and the description
     * expands in place, the way it does wherever the gate is off. Otherwise, and for a throw,
     * Facebook's answer comes back.
     */
    public static boolean descriptionOpensComments(boolean opens) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, DESCRIPTION);
            if (!opens || !Utils.settingsReady() || !resized()) return opens;
            HookStatus.counted(FAMILY, DESCRIPTION_EXPANDED);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "description tap", failure);
            return opens;
        }
    }

    /**
     * The hook, where the comment sheet's "fill the screen?" check starts, handed the share of the
     * screen's height the sheet opens to. True, while Open comments at is set to full height and
     * the share is one or more, answers that the sheet fills the screen; false lets Facebook's check
     * decide.
     */
    public static boolean fillsScreen(float height) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, FILLS);
            if (!(height >= 1f) || !Utils.settingsReady() || !full()) return false;
            HookStatus.counted(FAMILY, SCREEN_FILLED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "fills screen", failure);
            return false;
        }
    }

    /**
     * The hook, first thing where Facebook sets up a comment sheet's views, handed those views, the
     * id of the sheet's header and whether the sheet is a watch-and-comment one, the reel playing on
     * behind it. While Open comments at is set to full height, the header of a watch-and-comment
     * sheet is hidden, and any other header once it holds a video, now or after a later layout; a
     * header without one is left as it is. At half height the video is the point, so it stays.
     * While Tap a reel above its comments is on, a watch-and-comment header that stays takes taps on
     * the reel for the reel.
     */
    public static void watchHeader(@Nullable View content, int headerId, boolean overVideo) {
        // Pause reels while comments are open has its own switch and reads this sheet either way (#266).
        PauseUnderComments.sheetOpened(content);
        // So has Search comments (#420).
        CommentSearch.sheetOpened(content);
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, HEADER);
            if (content == null || !Utils.settingsReady()) return;
            boolean taps = overVideo && Settings.TAP_REELS_ABOVE_COMMENTS.get();
            if (!taps && !full()) return;
            View header = content.findViewById(headerId);
            if (header == null || WATCHED.put(header, Boolean.TRUE) != null) return;
            hideVideo(header, overVideo);
            if (taps) catchReelTaps(header, header, VIDEO_DEPTH);
            // Watched for as long as the sheet's window lives, since Facebook can show it again.
            header.getViewTreeObserver().addOnGlobalLayoutListener(new Header(header, overVideo, taps));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment sheet header", failure);
        }
    }

    /**
     * The hook, where a comment sheet that stops part way up has read which surface opened it,
     * handed the sheet's dialog and that surface. While Tap a reel above its comments is on and the
     * surface is the reel viewer, the sheet stays open on a tap outside it and its window passes
     * that tap on to the reel, and true tells Facebook the dialog is set up; otherwise, and for a
     * throw, false lets Facebook's own check run.
     */
    public static boolean tapThrough(@Nullable Dialog dialog, int source) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, TAP_THROUGH);
            if (dialog == null || !Utils.settingsReady() || !Settings.TAP_REELS_ABOVE_COMMENTS.get()) return false;
            Logger.printDebug(() -> "Tap a reel above its comments: a part-height sheet from surface " + source);
            if (!REEL_VIEWER.contains(source)) return false;
            Window window = dialog.getWindow();
            if (window == null) return false;
            dialog.setCanceledOnTouchOutside(false);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            HookStatus.counted(FAMILY, TAPS_THROUGH);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, TAP_THROUGH, failure);
            return false;
        }
    }

    /** Whether Open comments at is set to full height. */
    private static boolean full() {
        return Settings.COMMENTS_HEIGHT.get() == CommentsHeight.FULL;
    }

    /** Whether Open comments at is set to full or half height, either of which skips the split screen. */
    private static boolean resized() {
        return Settings.COMMENTS_HEIGHT.get() != CommentsHeight.FACEBOOK;
    }

    /**
     * Hides [header] while Open comments at is set to full height and it shows a video, its own or,
     * on a watch-and-comment sheet ([overVideo]), the reel behind it; true when it did.
     */
    static boolean hideVideo(View header, boolean overVideo) {
        if (header.getVisibility() == View.GONE || !Utils.settingsReady() || !full()
                || !(overVideo || holdsVideo(header, VIDEO_DEPTH))) {
            return false;
        }
        header.setVisibility(View.GONE);
        HookStatus.counted(FAMILY, VIDEO_HIDDEN);
        Logger.printDebug(() -> "Open comments at full height: the video above a sheet's comments was hidden");
        return true;
    }

    /** Whether [view], or a view in it at most [depth] levels down, draws a video. */
    static boolean holdsVideo(View view, int depth) {
        if (view instanceof TextureView || view instanceof SurfaceView) return true;
        if (depth == 0 || !(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (holdsVideo(group.getChildAt(i), depth - 1)) return true;
        }
        return false;
    }

    /**
     * Hands each view that takes taps and covers at least half of [header] both ways, from [view]
     * down [depth] levels, the listener that sends its taps to the reel. A header that's hidden or
     * not laid out yet waits for a later layout, so a small view isn't taken for a big one.
     */
    static void catchReelTaps(View header, View view, int depth) {
        if (header.getWidth() == 0 || header.getHeight() == 0 || view.getVisibility() != View.VISIBLE) return;
        if (view.isClickable() && view.getWidth() * 2 >= header.getWidth() && view.getHeight() * 2 >= header.getHeight()
                && OVER_REEL.put(view, Boolean.TRUE) == null) {
            view.setOnTouchListener(new ReelTaps());
        }
        if (depth == 0 || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            catchReelTaps(header, group.getChildAt(i), depth - 1);
        }
    }

    /** One sheet's header, looked at after each layout. */
    private static final class Header implements ViewTreeObserver.OnGlobalLayoutListener {
        private final View header;
        private final boolean overVideo;
        private final boolean taps;

        Header(View header, boolean overVideo, boolean taps) {
            this.header = header;
            this.overVideo = overVideo;
            this.taps = taps;
        }

        @Override
        public void onGlobalLayout() {
            try {
                hideVideo(header, overVideo);
                // Litho mounts the header's views again as the sheet settles.
                if (taps) catchReelTaps(header, header, VIDEO_DEPTH);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "comment sheet header", failure);
            }
        }
    }

    /**
     * A view over the reel in a watch-and-comment sheet's header. While Tap a reel above its
     * comments is on when a touch starts, the touch is its own: a tap plays or pauses the reel, or
     * with no reel found does the view's own tap, and a drag does nothing. Off, the view has it.
     */
    private static final class ReelTaps implements View.OnTouchListener {
        private boolean mine;
        private boolean tap;
        private float downX;
        private float downY;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mine = tap = Utils.settingsReady() && Settings.TAP_REELS_ABOVE_COMMENTS.get();
                        downX = event.getX();
                        downY = event.getY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (tap && moved(view, event)) tap = false;
                        break;
                    case MotionEvent.ACTION_UP:
                        if (tap && !moved(view, event)) tapped(view);
                        tap = false;
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        tap = false;
                        break;
                    default:
                        break;
                }
                return mine;
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, TAP_THROUGH, failure);
                return false;
            }
        }

        private boolean moved(View view, MotionEvent event) {
            int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            return Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop;
        }

        private static void tapped(View view) {
            if (reelTap.getAsBoolean()) {
                HookStatus.counted(FAMILY, REEL_TAPPED);
                return;
            }
            // Without Tap to play's bridge there's no reel to play or pause, so the tap closes the sheet as before.
            view.performClick();
        }
    }
}
