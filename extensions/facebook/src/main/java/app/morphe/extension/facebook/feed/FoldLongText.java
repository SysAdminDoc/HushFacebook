/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

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
     * The hook on a comment's character budget, handed it and the line limit beside it (before
     * that goes through {@link #commentLines}). Answers about the picked count's worth, or Facebook's.
     */
    public static int commentChars(int chars, int lines) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, COMMENT_CHARS);
            int picked = commentPick();
            if (picked <= 0 || !folds(lines, picked) || chars <= 0) return chars;
            return budget(picked, COMMENT_CHARS_PER_LINE);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, COMMENT_CHARS, failure);
            return chars;
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
