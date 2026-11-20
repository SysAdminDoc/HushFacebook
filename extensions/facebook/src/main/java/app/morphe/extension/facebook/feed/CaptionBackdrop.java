/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.content.res.Resources;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.navigation.TranslucentBars;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * An open reel caption gets a dark rounded box behind its text, like subtitles, so a long caption
 * stays readable over a bright video (#419). A folded one gets the same box from its own switch
 * (#472), and so do the names in the stories row, on an open story and on a reel (#490).
 *
 * <p>The caption under a reel (FbShortsViewerDescriptionComponent) is a Litho text component whose
 * builder holds the caption's line limit. Facebook starts that limit at Integer.MAX_VALUE and sets a
 * few lines only while the caption is folded, so an open caption is one still at MAX_VALUE when it's
 * built, and a folded one is one with a limit of a line or more below that. Right before the build,
 * the patch hands the limit here and, given a drawable back, sets it as the component's background
 * through Litho's own style, with no padding, so the text keeps its place.
 *
 * <p>A name's text builder comes here right after it's handed the name. An open story's title comes
 * here right before it's laid out, and the box goes on the style it's laid out with: the style the
 * shown title wraps its name in, or the container a title's lines go into. A reel's name is drawn by
 * the post header that feed posts use too, so the reel's author component marks the header props it
 * makes ({@link #reelAuthor}), and the header says whose props it's building from ({@link #header})
 * before it asks for the box.
 *
 * <p>The same header draws a feed video's name over the video itself, in its "on media" look, and that
 * name can get the box from its own switch (#533). A feed post's name can also take a few lines where
 * Facebook cuts it to one ({@link #nameLines}).
 *
 * <p>Box darkness and Soft box edges set every box's look. With the switch for its kind off, paused,
 * before the settings are ready, or when anything here fails, the text keeps Facebook's look.
 */
public final class CaptionBackdrop {
    /** The member the report names once a caption's line limit has gone through here. */
    static final String BACKDROP = "reel caption backdrop";

    /** Counted each time an open caption gets the box. */
    static final String DIMMED = "open reel caption dimmed";

    /** Counted each time a folded caption gets the box (#472). */
    static final String FOLDED_DIMMED = "folded reel caption dimmed";

    /** The member the report names once a story name has asked for its box (#490). */
    static final String STORY_NAME = "story name backdrop";

    /** Counted each time a story name gets the box. */
    static final String STORY_NAMED = "story name dimmed";

    /** The member the report names once a reel name has asked for its box (#490). */
    static final String REEL_NAME = "reel name backdrop";

    /** Counted each time a reel's name gets the box. */
    static final String REEL_NAMED = "reel name dimmed";

    /** Counted each time a name drawn over a feed video gets the box (#533). */
    static final String MEDIA_NAMED = "feed video name dimmed";

    /** The member the report names once a post header has read its name's line limit (#533). */
    static final String NAME_LINES = "post name lines";

    /** Counted each time a post's name gets room for more than Facebook's lines. */
    static final String NAME_WIDENED = "post name given more lines";

    /** The lines a post's name may take with Show full names on: room for a long name and what follows it. */
    static final int FULL_NAME_LINES = 4;

    /** What the header's look starts with when it's drawn over a photo or a video. */
    private static final String[] MEDIA_LOOKS = {"ON_MEDIA", "ON_OVERLAY"};

    /** Box darkness when its row is blank, in percent: close to the box behind video subtitles. */
    public static final int DEFAULT_DARKNESS = 70;
    public static final int MIN_DARKNESS = 10;
    public static final int MAX_DARKNESS = 100;

    /** About 70% black, the box at [DEFAULT_DARKNESS]. */
    static final int COLOR = 0xB3000000;

    /** The box's corner radius, in dp. */
    static final float CORNER_DP = 8f;

    /** How far a soft box fades out past its edge, in dp. */
    static final float SOFT_DP = 6f;

    private static final String FAMILY = FamilyNames.FULL_POST_TEXT;

    /**
     * Header props the reel's author component made while a switch that tells a reel's header apart
     * was on: Dark box behind reel names, Dark box behind feed video names or Show full names.
     */
    private static final Set<Object> REEL_AUTHORS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    /** Whether the header building on this thread right now builds from a reel author's props. */
    private static final ThreadLocal<Boolean> REEL_HEADER = new ThreadLocal<>();

    /** Whether the header building on this thread right now draws a feed post's name over its photo or video. */
    private static final ThreadLocal<Boolean> MEDIA_HEADER = new ThreadLocal<>();

    /** The header looks already written to the log, so each shows once. */
    private static final Set<String> LOOKS_LOGGED = Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean logged;
    private static volatile boolean loggedFolded;
    private static volatile boolean loggedStory;
    private static volatile boolean loggedReel;
    private static volatile boolean loggedMedia;
    private static volatile boolean loggedLines;

    private CaptionBackdrop() {
    }

    /**
     * Whether this build carries the hook. The patch answers true here once it's in; a Facebook build
     * missing one of its anchors leaves this false, so the settings page doesn't offer the switch.
     */
    public static boolean inBuild() {
        return false;
    }

    /** Whether this build boxes the names in the stories row and on an open story; the patch answers true. */
    public static boolean storyNamesInBuild() {
        return false;
    }

    /** Whether this build boxes a reel's name; the patch answers true. */
    public static boolean reelNamesInBuild() {
        return false;
    }

    /** Whether this build boxes a name drawn over a feed video (#533); the patch answers true. */
    public static boolean feedVideoNamesInBuild() {
        return false;
    }

    /** Whether this build lets a post's name take more lines (#533); the patch answers true. */
    public static boolean fullNamesInBuild() {
        return false;
    }

    /** Box darkness typed as [saved], held to 10 to 100 percent: 70 when it's blank or not a number. */
    public static int darknessPercent(@Nullable String saved) {
        return TranslucentBars.percent(saved, MIN_DARKNESS, MAX_DARKNESS, DEFAULT_DARKNESS);
    }

    /** Black at [percent] percent. */
    static int colorFor(int percent) {
        return Math.round(percent * 255f / 100f) << 24;
    }

    /** A box of its own each time, at the darkness and edges the rows set: Litho mounts a background per component. */
    private static Drawable box() {
        float density = Resources.getSystem().getDisplayMetrics().density;
        int color = colorFor(darknessPercent(Settings.TEXT_BOX_DARKNESS.get()));
        if (Settings.SOFT_TEXT_BOXES.get()) return new SoftBox(color, CORNER_DP * density, SOFT_DP * density);
        GradientDrawable box = new GradientDrawable();
        box.setColor(color);
        box.setCornerRadius(CORNER_DP * density);
        return box;
    }

    /**
     * The hook, right before a reel caption's text component is built, handed its line limit. Answers
     * the box for an open caption while Dark box behind open captions is on, and for a folded one
     * while Dark box behind folded captions is, otherwise null, which keeps Facebook's look.
     */
    public static Drawable forCaption(int maxLines) {
        try {
            HookStatus.bound(FAMILY, BACKDROP);
            boolean open = maxLines == Integer.MAX_VALUE;
            // A folded caption shows a few lines; a limit of none shows no text to put a box behind.
            boolean folded = !open && maxLines > 0;
            if (!(open || folded) || !Utils.settingsReady()) return null;
            if (open ? !Settings.DIM_OPEN_REEL_CAPTIONS.get() : !Settings.DIM_FOLDED_REEL_CAPTIONS.get()) return null;
            HookStatus.invoked(FAMILY);
            if (open) {
                HookStatus.counted(FAMILY, DIMMED);
                if (!logged) {
                    logged = true;
                    Logger.printDebug(() -> "Full post text: an open reel caption got its dark box");
                }
            } else {
                HookStatus.counted(FAMILY, FOLDED_DIMMED);
                if (!loggedFolded) {
                    loggedFolded = true;
                    Logger.printDebug(() -> "Full post text: a folded reel caption got its dark box, "
                            + maxLines + " lines");
                }
            }
            return box();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BACKDROP, failure);
            return null;
        }
    }

    /**
     * The hook, right after a name in the stories row goes into its text builder, and right before an
     * open story's title goes into its container. Answers the box while Dark box behind story names is
     * on, otherwise null.
     */
    public static Drawable forStoryName() {
        try {
            HookStatus.bound(FAMILY, STORY_NAME);
            if (!Utils.settingsReady() || !Settings.DIM_STORY_NAMES.get()) return null;
            HookStatus.invoked(FAMILY);
            HookStatus.counted(FAMILY, STORY_NAMED);
            if (!loggedStory) {
                loggedStory = true;
                Logger.printDebug(() -> "Full post text: a story name got its dark box");
            }
            return box();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, STORY_NAME, failure);
            return null;
        }
    }

    /**
     * The hook, right after the reel's author component makes its post header's props. Marks them
     * while a switch that treats a reel's header apart is on, so the header knows it's drawing a
     * reel's name: Dark box behind reel names boxes it, and the other two leave it as Facebook drew it.
     */
    public static void reelAuthor(@Nullable Object props) {
        try {
            if (props != null && Utils.settingsReady() && (Settings.DIM_REEL_NAMES.get()
                    || Settings.DIM_FEED_VIDEO_NAMES.get() || Settings.SHOW_FULL_NAMES.get())) {
                REEL_AUTHORS.add(props);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, REEL_NAME, failure);
        }
    }

    /**
     * The hook, as the post header starts building its name from [props]: a feed post's or a reel's.
     * [look] is how the header is drawn (an enum such as DEFAULT or ON_MEDIA), or null when the build
     * doesn't hand it over.
     */
    public static void header(@Nullable Object props, @Nullable Object look) {
        try {
            boolean reel = props != null && !REEL_AUTHORS.isEmpty() && REEL_AUTHORS.contains(props);
            REEL_HEADER.set(reel);
            MEDIA_HEADER.set(!reel && onMedia(look));
        } catch (Throwable failure) {
            REEL_HEADER.set(false);
            MEDIA_HEADER.set(false);
            HookStatus.threw(FAMILY, REEL_NAME, failure);
        }
    }

    /** Whether the header's [look] draws it over a photo or a video. Each look is logged the first time it's seen. */
    static boolean onMedia(@Nullable Object look) {
        if (!(look instanceof Enum)) return false;
        String name = ((Enum<?>) look).name();
        if (LOOKS_LOGGED.size() < 16 && LOOKS_LOGGED.add(name)) {
            Logger.printDebug(() -> "Full post text: a post header drawn in its " + name + " look");
        }
        for (String media : MEDIA_LOOKS) {
            if (name.startsWith(media)) return true;
        }
        return false;
    }

    /**
     * The hook, right after the post header reads how many lines its name may take (#533). With Show
     * full names on, a feed post's name cut to fewer than {@link #FULL_NAME_LINES} lines gets that many,
     * so a long one wraps onto the next line; a name that fits still takes one. A reel's name, and
     * every name with the switch off, paused or before the settings are ready, keeps Facebook's limit.
     */
    public static int nameLines(int lines) {
        try {
            HookStatus.bound(FAMILY, NAME_LINES);
            if (lines < 1 || lines >= FULL_NAME_LINES) return lines;
            if (Boolean.TRUE.equals(REEL_HEADER.get())) return lines;
            if (!Utils.settingsReady() || !Settings.SHOW_FULL_NAMES.get()) return lines;
            HookStatus.invoked(FAMILY);
            HookStatus.counted(FAMILY, NAME_WIDENED);
            if (!loggedLines) {
                loggedLines = true;
                Logger.printDebug(() -> "Full post text: a post's name may take " + FULL_NAME_LINES
                        + " lines instead of " + lines);
            }
            return FULL_NAME_LINES;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, NAME_LINES, failure);
            return lines;
        }
    }

    /**
     * The hook, right after the post header hands its text builder the name. Answers the box while
     * Dark box behind reel names is on and the header builds from a reel author's props, or while Dark
     * box behind feed video names is on and a feed post's header is drawn over its video; otherwise
     * null.
     */
    public static Drawable forReelName() {
        try {
            HookStatus.bound(FAMILY, REEL_NAME);
            boolean reel = Boolean.TRUE.equals(REEL_HEADER.get());
            if (!reel && !Boolean.TRUE.equals(MEDIA_HEADER.get())) return null;
            if (!Utils.settingsReady()) return null;
            if (reel ? !Settings.DIM_REEL_NAMES.get() : !Settings.DIM_FEED_VIDEO_NAMES.get()) return null;
            HookStatus.invoked(FAMILY);
            if (reel) {
                HookStatus.counted(FAMILY, REEL_NAMED);
                if (!loggedReel) {
                    loggedReel = true;
                    Logger.printDebug(() -> "Full post text: a reel's name got its dark box");
                }
            } else {
                HookStatus.counted(FAMILY, MEDIA_NAMED);
                if (!loggedMedia) {
                    loggedMedia = true;
                    Logger.printDebug(() -> "Full post text: a name over a feed video got its dark box");
                }
            }
            return box();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, REEL_NAME, failure);
            return null;
        }
    }

    /**
     * Soft box edges: the box stays whole under the text and fades out past its edge, so it has no
     * hard line. It carries no padding, so the text keeps its place.
     */
    static final class SoftBox extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float corner;
        final float blur;
        private final int alpha;
        private final RectF rect = new RectF();

        SoftBox(int color, float corner, float blur) {
            this.corner = corner;
            this.blur = blur;
            alpha = color >>> 24;
            paint.setColor(color);
            // SOLID keeps the inside as it is and blurs only outward.
            paint.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.SOLID));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            rect.set(getBounds());
            canvas.drawRoundRect(rect, corner, corner, paint);
        }

        @Override
        public void setAlpha(int fade) {
            paint.setAlpha(Math.round(alpha * fade / 255f));
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
