/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.text.SpannableStringBuilder;
import android.text.Spanned;

import java.util.ArrayList;
import java.util.List;

/**
 * Which hashtags Hide hashtags in reel captions leaves off a reel's caption (#594).
 *
 * <p>A hashtag is a {@code #} at the start of the caption or after a space, followed by letters,
 * digits, marks or underscores. Hashtags with only spaces or line breaks between them make a run. A
 * run goes when it ends the caption, fills its lines on its own, or holds two hashtags or more, so a
 * caption's block of tags goes and a tag someone wrote as a word of a sentence ("I love #cars so
 * much") stays. The spaces a run leaves behind go with it, and the rest of the caption keeps its
 * spans: links, mentions and styles stay where they were.
 */
final class ReelHashtags {
    private ReelHashtags() {
    }

    /**
     * [text] without its hashtag runs, or [text] itself when it has none to leave off. A caption
     * with spans comes back as a {@link SpannableStringBuilder} that keeps them.
     */
    static CharSequence without(CharSequence text) {
        List<int[]> cuts = cuts(text);
        if (cuts.isEmpty()) return text;
        if (text instanceof Spanned) {
            SpannableStringBuilder kept = new SpannableStringBuilder(text);
            for (int at = cuts.size() - 1; at >= 0; at--) kept.delete(cuts.get(at)[0], cuts.get(at)[1]);
            return kept;
        }
        StringBuilder kept = new StringBuilder(text);
        for (int at = cuts.size() - 1; at >= 0; at--) kept.delete(cuts.get(at)[0], cuts.get(at)[1]);
        return kept.toString();
    }

    /** How many hashtag runs [text] would lose. */
    static int runsLeftOff(CharSequence text) {
        return cuts(text).size();
    }

    /** The ranges, start inclusive and end exclusive, in order and apart, that leave [text]'s hashtag runs off. */
    static List<int[]> cuts(CharSequence text) {
        List<int[]> cuts = new ArrayList<>();
        if (text == null) return cuts;
        int length = text.length();
        List<int[]> tags = tags(text);
        int at = 0;
        while (at < tags.size()) {
            int start = tags.get(at)[0];
            int end = tags.get(at)[1];
            int count = 1;
            while (at + count < tags.size() && onlyGaps(text, end, tags.get(at + count)[0])) {
                end = tags.get(at + count)[1];
                count++;
            }
            at += count;

            boolean ends = onlyGaps(text, end, length);
            int before = back(text, start);
            int after = forward(text, end);
            boolean lineStart = before == 0 || text.charAt(before - 1) == '\n';
            boolean lineEnd = after == length || text.charAt(after) == '\n';
            if (ends) {
                // The caption's last words: the run and every space and line break before it go.
                int from = start;
                while (from > 0 && gap(text.charAt(from - 1))) from--;
                cuts.add(new int[]{from, length});
            } else if (lineStart && lineEnd) {
                // Lines of their own: they go with the line break that ends them.
                cuts.add(new int[]{before, after + 1});
            } else if (count >= 2) {
                // Inside a line: the run goes with the spaces before it, or after it at the line's start.
                cuts.add(lineStart ? new int[]{start, after} : new int[]{before, end});
            }
        }
        return cuts;
    }

    /** Every hashtag in [text], as start and end. */
    private static List<int[]> tags(CharSequence text) {
        List<int[]> tags = new ArrayList<>();
        int length = text.length();
        int at = 0;
        while (at < length) {
            if (text.charAt(at) != '#' || (at > 0 && !gap(text.charAt(at - 1)))) {
                at++;
                continue;
            }
            int end = at + 1;
            while (end < length) {
                int point = Character.codePointAt(text, end);
                if (!word(point)) break;
                end += Character.charCount(point);
            }
            if (end > at + 1) tags.add(new int[]{at, end});
            at = Math.max(end, at + 1);
        }
        return tags;
    }

    /** Whether a hashtag goes on past [point]: a letter, a digit, a mark (Thai and Hindi vowel signs) or an underscore. */
    private static boolean word(int point) {
        if (point == '_' || Character.isLetterOrDigit(point)) return true;
        int type = Character.getType(point);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /** Whether [c] is a space, a line break or a zero-width space, which Facebook captions carry between words. */
    static boolean gap(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x200B || c == 0x2060 || c == 0xFEFF;
    }

    /** Whether [text] holds only gaps from [from] to [to]. */
    private static boolean onlyGaps(CharSequence text, int from, int to) {
        for (int at = from; at < to; at++) {
            if (!gap(text.charAt(at))) return false;
        }
        return true;
    }

    /** Where the gaps before [at] on its line start. */
    private static int back(CharSequence text, int at) {
        while (at > 0 && text.charAt(at - 1) != '\n' && gap(text.charAt(at - 1))) at--;
        return at;
    }

    /** Where the gaps after [at] on its line end. */
    private static int forward(CharSequence text, int at) {
        while (at < text.length() && text.charAt(at) != '\n' && gap(text.charAt(at))) at++;
        return at;
    }
}
