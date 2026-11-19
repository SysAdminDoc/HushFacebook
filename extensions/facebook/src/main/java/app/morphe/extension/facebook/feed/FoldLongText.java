/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * A long post's text, and a long comment, fold after the number of lines the reader picks, with
 * Facebook's own See more after them (#497).
 *
 * <p>Facebook folds a post's text one of two ways. With the text's width known it lays the text
 * out and cuts after a number of lines, which a post's line limit gives: the story's own, the
 * server's, or 4, 6 or 7 by the post's kind, and -1 for a post it never folds. Otherwise it cuts
 * after that many line breaks or a number of characters, whichever comes first. A comment in the
 * comment sheet is cut the second way, after 5 line breaks or 400 characters unless the server
 * says otherwise. The hooks hand each of those numbers through here just before Facebook uses them.
 *
 * <p>With a line count picked, the line limit becomes that count and the characters become about
 * that many lines' worth ({@link #POST_CHARS_PER_LINE} and {@link #COMMENT_CHARS_PER_LINE} a line,
 * less room for See more). A post Facebook doesn't fold (-1), or folds to one line on purpose,
 * keeps Facebook's numbers. Show full post text, while on, wins for posts: their numbers stay
 * Facebook's, and that switch shows them whole.
 *
 * <p>Those numbers alone missed many posts and comments (#506). A post can come with a fixed place
 * to cut, which beats the line limit, so with a count picked that place is dropped ({@link #postCut}).
 * A feed post Facebook has chosen to show whole folds again ({@link #hoisted}). A comment's "lines"
 * only count line breaks, so its characters decide, and they're measured for the comment's own text
 * instead of guessed. A video's caption has a limit of its own, which takes the count picked for
 * posts ({@link #captionLines}).
 *
 * <p>Facebook's choice, paused, before the settings are ready, or when anything here fails, the
 * numbers are Facebook's own.
 */
public final class FoldLongText {
    /** The member the report names once a post's line limit has gone through here. */
    static final String POST_LINES = "post line limit";

    /** The member the report names once a post's character budget has gone through here. */
    static final String POST_CHARS = "post character budget";

    /** The member the report names once a comment's line limit has gone through here. */
    static final String COMMENT_LINES = "comment line limit";

    /** The member the report names once a comment's character budget has gone through here. */
    static final String COMMENT_CHARS = "comment character budget";

    /** The member the report names once a post's fixed cut has gone through here. */
    static final String POST_CUT = "post fixed cut";

    /** The member the report names once a feed post shown whole has gone through here. */
    static final String POST_HOISTED = "post shown whole in the feed";

    /** The member the report names once a video caption's line limit has gone through here. */
    static final String CAPTION_LINES = "video caption line limit";

    /** Counted each time a post's line limit becomes the one picked. */
    static final String POST_FOLDED = "post folded at the picked lines";

    /** Counted each time a comment's line limit becomes the one picked. */
    static final String COMMENT_FOLDED = "comment folded at the picked lines";

    /**
     * About how many characters fill a line of a post's text on a phone: the same 45 Facebook uses
     * when the server gives a post a line limit (585 and later).
     */
    static final int POST_CHARS_PER_LINE = 45;

    /** A comment sits in a narrower bubble than a post's text, so a line holds fewer characters. */
    static final int COMMENT_CHARS_PER_LINE = 40;

    /** Room left on the last line for the " ... See more" Facebook adds. */
    static final int SEE_MORE_ROOM = 10;

    /**
     * The screen's width a comment's text doesn't get: the picture beside it and the bubble's sides.
     * Measured on a Galaxy S22 on 588, where a comment's first line ran about 877 of 1080 pixels.
     */
    static final int COMMENT_SIDE_DP = 72;

    /** The size of a comment's text, measured from 588's drawn comments on a Galaxy S22. */
    static final int COMMENT_TEXT_SP = 16;

    /**
     * The share of that width the measure lays a comment out in. Facebook's lines came out 1 to 4
     * percent shorter than this measure's, so a first line wrapped a word the measure kept and the
     * See more fell onto one line more than picked (#506). A narrower measure never fits more on a line.
     */
    static final float COMMENT_MEASURED_WIDTH = 0.95f;

    /** How much of a long comment is measured for each line asked for, so its length costs nothing. */
    static final int COMMENT_MEASURED_CHARS_PER_LINE = 200;

    /** What the last line has to leave room for, with a wide letter to spare for rounding. */
    static final String COMMENT_SEE_MORE = "... See moreW";

    private static final String FAMILY = FamilyNames.FOLD_LONG_TEXT;

    /** Whether Show full post text is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean fullTextInBuildForTests;

    private static volatile boolean postLogged;

    private static volatile boolean commentLogged;

    private FoldLongText() {
    }

    /**
     * The hook on a post's line limit, handed Facebook's: the lines a post shows before See more,
     * or -1 when Facebook doesn't fold it. Answers the picked count, or Facebook's.
     */
    public static int postLines(int lines) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, POST_LINES);
            int picked = postPick();
            if (picked <= 0 || lines <= 1) return lines;
            HookStatus.counted(FAMILY, POST_FOLDED);
            if (!postLogged) {
                postLogged = true;
                Logger.printDebug(() -> "Fold long text: a post folds after " + picked + " lines");
            }
            return picked;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, POST_LINES, failure);
            return lines;
        }
    }

    /**
     * The hook on a post's character budget, handed it and the line limit beside it (before that
     * goes through {@link #postLines}). Answers about the picked count's worth, or Facebook's.
     */
    public static int postChars(int chars, int lines) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, POST_CHARS);
            int picked = postPick();
            if (picked <= 0 || !folds(lines, picked) || chars <= 0) return chars;
            return budget(picked, POST_CHARS_PER_LINE);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, POST_CHARS, failure);
            return chars;
        }
    }

    /** The hook on a comment's line limit, handed Facebook's. Answers the picked count, or Facebook's. */
    public static int commentLines(int lines) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, COMMENT_LINES);
            int picked = commentPick();
            if (picked <= 0 || lines <= 1) return lines;
            HookStatus.counted(FAMILY, COMMENT_FOLDED);
            if (!commentLogged) {
                commentLogged = true;
                Logger.printDebug(() -> "Fold long text: a comment folds after " + picked + " lines");
            }
            return picked;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, COMMENT_LINES, failure);
            return lines;
        }
    }

    /**
     * The hook on a comment's character budget, handed it, the line limit beside it (before that
     * goes through {@link #commentLines}) and the comment's text. Facebook's line limit only counts
     * line breaks, so these characters are what really folds a comment: answers where the picked
     * count of lines ends for this text, or Facebook's.
     */
    public static int commentChars(int chars, int lines, @Nullable CharSequence text) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, COMMENT_CHARS);
            int picked = commentPick();
            if (picked <= 0 || !folds(lines, picked) || chars <= 0) return chars;
            return commentBudget(picked, text);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, COMMENT_CHARS, failure);
            return chars;
        }
    }

    /**
     * The hook on the fixed place a post's text may be cut at, which Facebook prefers to any line
     * limit. Answers null with a count picked, so the count decides, or Facebook's place.
     */
    @Nullable
    public static Integer postCut(@Nullable Integer position) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, POST_CUT);
            return postPick() > 0 ? null : position;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, POST_CUT, failure);
            return position;
        }
    }

    /**
     * The hook on Facebook's choice to show a feed post whole, handed it as non-zero for yes.
     * Answers no with a count picked, so the post folds like the others, or Facebook's.
     */
    public static int hoisted(int whole) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, POST_HOISTED);
            return postPick() > 0 ? 0 : whole;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, POST_HOISTED, failure);
            return whole;
        }
    }

    /**
     * The hook on the lines a video's folded caption shows, handed Facebook's. Answers the count
     * picked for posts, or Facebook's. Show full post text has no say here, since captions have a
     * switch of their own.
     */
    public static int captionLines(int lines) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, CAPTION_LINES);
            if (!Utils.settingsReady()) return lines;
            int picked = Settings.FOLD_POST_TEXT.get().lines;
            return picked > 0 ? picked : lines;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CAPTION_LINES, failure);
            return lines;
        }
    }

    /**
     * The characters that end [picked] laid out lines of [text], leaving room for See more, or at
     * least the whole text when it fits. A line that ends in a line break answers that line's end, so
     * Facebook's own count of line breaks stops at the same place. Without the text, or when it can't
     * be measured, about that many lines' worth.
     */
    private static int commentBudget(int picked, @Nullable CharSequence text) {
        int fallback = budget(picked, COMMENT_CHARS_PER_LINE);
        if (text == null) return fallback;
        try {
            DisplayMetrics metrics = Utils.getContext().getResources().getDisplayMetrics();
            int side = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, COMMENT_SIDE_DP, metrics));
            int width = Math.round((metrics.widthPixels - side) * COMMENT_MEASURED_WIDTH);
            if (width <= 0) return fallback;

            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, COMMENT_TEXT_SP, metrics));
            String whole = text.toString();
            int length = whole.length();
            int end = Math.min(length, (picked + 1) * COMMENT_MEASURED_CHARS_PER_LINE);
            StaticLayout layout = StaticLayout.Builder.obtain(whole, 0, end, paint, width)
                    .setIncludePad(false).build();
            int count = layout.getLineCount();
            if (count <= picked && end == length) return Math.max(length, fallback);

            int last = Math.min(picked, count) - 1;
            float room = paint.measureText(COMMENT_SEE_MORE);
            float x = layout.getParagraphDirection(last) == Layout.DIR_RIGHT_TO_LEFT ? room : width - room;
            int lineStart = layout.getLineStart(last);
            int lineEnd = layout.getLineEnd(last);
            int offset = Math.max(lineStart, Math.min(lineEnd, layout.getOffsetForHorizontal(last, x)));
            if (offset >= lineEnd - 1) return lineEnd;
            return Math.max(1, offset);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, COMMENT_CHARS, failure);
            return fallback;
        }
    }

    /**
     * Whether a fold handed [lines] beside its characters takes the picked count's: Facebook's own
     * fold (more than one line), or one already at [picked], as it is when the line limit went
     * through here before the two were put together. Not a post Facebook never folds (-1), nor one it
     * folds to a single line on purpose.
     */
    private static boolean folds(int lines, int picked) {
        return lines > 1 || lines == picked;
    }

    /** About [lines] lines' worth of characters at [perLine] a line, with room for See more. */
    static int budget(int lines, int perLine) {
        return lines * perLine - SEE_MORE_ROOM;
    }

    /** The lines picked for posts, or 0 for Facebook's own, which Show full post text on also means. */
    private static int postPick() {
        if (!Utils.settingsReady()) return 0;
        int picked = Settings.FOLD_POST_TEXT.get().lines;
        if (picked > 0 && fullTextOn()) return 0;
        return picked;
    }

    /** The lines picked for comments, or 0 for Facebook's own. */
    private static int commentPick() {
        if (!Utils.settingsReady()) return 0;
        return Settings.FOLD_COMMENTS.get().lines;
    }

    /** Whether Show full post text is in this build and on, so posts show whole instead. */
    private static boolean fullTextOn() {
        Boolean forced = fullTextInBuildForTests;
        boolean inBuild = forced != null ? forced : SettingsStatus.fullPostText();
        return inBuild && Settings.SHOW_FULL_POST_TEXT.get();
    }
}
