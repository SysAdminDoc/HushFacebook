/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.icu.text.Normalizer2;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.settings.Settings;

/**
 * The two lists behind "Hide posts with words you choose": the words and phrases that hide a post,
 * and the ones that keep it whatever else it says.
 *
     * <p>A list is stored as one phrase per line. Each phrase is plain text, matched by default
 * anywhere in a post's words, inside longer words too, with case and compatibility forms folded the
 * Unicode way (NFKC case folding), so "Spoiler", "SPOILER" and "ｓｐｏｉｌｅｒ" are one phrase. A
 * phrase means its own characters and nothing else. Optional whole-word matching prevents a
 * phrase's endpoints from cutting a Unicode word run.
 *
 * <p>A line written between slashes, such as {@code /colou?r/}, is a pattern instead: a regular
 * expression read by {@link PostPattern}, matched against a post's words as they are, capital
 * letters aside, on a step budget per post so no pattern can hang the feed. A list holds up to
 * {@link #MAX_PATTERNS} of them, each up to {@link #MAX_PATTERN_LENGTH} characters between the
 * slashes. A pattern that can't be read is refused when the list is saved, and left out of a list
 * read from the store, like any line out of bounds.
 *
 * <p>Both lists are bounded, whatever wrote the store: at most {@link #MAX_PHRASES} phrases, each
 * {@link #MIN_LENGTH} to {@link #MAX_LENGTH} characters, or one that's a word on its own (an
 * ideograph, a kana or Hangul syllable, an emoji), a phrase given twice counted once. A line
 * outside the bounds is left out rather than cut, and the settings row says how many were. The
 * two lists also share {@link #MAX_LIST_BYTES} of room, so both always fit in a settings file
 * whole. The row refuses a list with too many phrases, or one past that room, rather than cutting it.
 *
 * <p>The lists never leave the phone through Hushfacebook: no log line, diagnostic report or
 * request carries a phrase, only the counts.
 */
public final class PostWords {
    /** How many phrases a list holds. The row refuses more, and past this a store's lines are left out. */
    public static final int MAX_PHRASES = 1000;
    /** A phrase's length in characters (code points), after the spaces around it are dropped. */
    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 60;
    /** How many patterns a list holds. They count among its {@link #MAX_PHRASES} too. */
    public static final int MAX_PATTERNS = 50;
    /** A pattern's length in characters (code points) between its slashes, at least {@link #MIN_LENGTH}. */
    public static final int MAX_PATTERN_LENGTH = 200;
    /**
     * The steps every pattern of both lists may take together on one post. A long post read by
     * many patterns runs out and stays, and the report counts it as {@link Verdict#TOO_SLOW}.
     */
    static final long STEPS_PER_POST = 2_000_000;
    /** Keys a pattern apart from a phrase among the lines already seen; no typed line holds U+0000. */
    private static final String PATTERN_KEY = "\u0000/";
    /**
     * The room both lists share, in bytes: what the two take together in a settings file, each as
     * {@link #encodedBytes} counts it. 56 KB holds 1,000 short phrases in each list and leaves the
     * file's other settings room under its 64 KB limit, which SettingsBackupTest holds with every
     * other setting at its longest.
     */
    public static final int MAX_LIST_BYTES = 56 * 1024;
    /** The longest a list within {@link #MAX_LIST_BYTES} can be, in Java chars: each takes a byte at least. */
    public static final int MAX_STORED_CHARS = MAX_LIST_BYTES;

    /** How many posts the rule has hidden since Facebook started. The settings row shows it. */
    static final AtomicInteger HIDDEN = new AtomicInteger();

    private PostWords() {
    }

    /** What the lists make of a post's words. Only {@link #HIDE} and {@link #HIDE_PATTERN} hide it. */
    enum Verdict {
        HIDE("hide word"),
        HIDE_PATTERN("hide pattern"),
        KEEP("keep word"),
        KEEP_PATTERN("keep pattern"),
        TOO_SLOW("pattern too slow"),
        NO_MATCH("no match");

        /** What the report counts this under: which list and kind of rule matched, never the phrase. */
        final String reason;

        Verdict(String reason) {
            this.reason = reason;
        }

        boolean hides() {
            return this == HIDE || this == HIDE_PATTERN;
        }
    }

    /**
     * The phrases a stored list holds, in the order given, each as it was typed without the spaces
     * around it. Lines out of bounds, blank lines and repeats are left out, and the list stops at
     * {@link #MAX_PHRASES}.
     */
    public static List<String> phrases(@Nullable String stored) {
        return phrases(stored, MAX_PHRASES);
    }

    /** {@link #phrases(String)}, stopping at [limit] phrases. */
    private static List<String> phrases(@Nullable String stored, int limit) {
        List<String> kept = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return kept;
        Set<String> seen = new HashSet<>();
        int patterns = 0;
        int start = 0;
        while (start <= stored.length() && kept.size() < limit) {
            int end = lineEnd(stored, start);
            String phrase = strip(stored.substring(start, end));
            String body = patternBody(phrase);
            if (body != null) {
                if (patterns < MAX_PATTERNS && readable(body) && seen.add(PATTERN_KEY + body)) {
                    kept.add(phrase);
                    patterns++;
                }
            } else if (phrase.codePointCount(0, phrase.length()) <= MAX_LENGTH) {
                String folded = fold(phrase);
                if (longEnough(phrase, folded) && seen.add(folded)) kept.add(phrase);
            }
            start = end + 1;
        }
        return kept;
    }

    /**
     * The pattern a stripped line holds when it's written between slashes with at least
     * {@link #MIN_LENGTH} characters between them, or null when the line is a phrase. "//" and
     * "/a/" are phrases.
     */
    @Nullable
    static String patternBody(String line) {
        int length = line.length();
        if (length < 2 || line.charAt(0) != '/' || line.charAt(length - 1) != '/') return null;
        String body = line.substring(1, length - 1);
        return body.codePointCount(0, body.length()) >= MIN_LENGTH ? body : null;
    }

    /** Whether a pattern's body is short enough and one {@link PostPattern} reads. */
    private static boolean readable(String body) {
        return body.codePointCount(0, body.length()) <= MAX_PATTERN_LENGTH && PostPattern.compileOrNull(body) != null;
    }

    /** The list as it's stored: the phrases {@link #phrases} keeps, one per line. */
    public static String clean(@Nullable String typed) {
        return String.join("\n", phrases(typed));
    }

    /** Whether a value is a list exactly as {@link #clean} stores it, which is all a settings file may hold. */
    public static boolean isClean(@Nullable String value) {
        return value != null && clean(value).equals(value);
    }

    /** How many lines with something on them {@link #clean} leaves out of what was typed. */
    public static int leftOut(@Nullable String typed) {
        if (typed == null || typed.isEmpty()) return 0;
        int written = 0;
        int start = 0;
        while (start <= typed.length()) {
            int end = lineEnd(typed, start);
            if (!strip(typed.substring(start, end)).isEmpty()) written++;
            start = end + 1;
        }
        return written - phrases(typed).size();
    }

    /** How many phrases a stored list holds. */
    public static int count(@Nullable String stored) {
        return phrases(stored).size();
    }

    /**
     * The bytes a stored list takes in a settings file: the list as a JSON string, the way
     * {@link JSONObject} writes one, in UTF-8 and without its quotes. A line break takes two.
     */
    public static int encodedBytes(@Nullable String stored) {
        if (stored == null || stored.isEmpty()) return 0;
        return JSONObject.quote(stored).getBytes(StandardCharsets.UTF_8).length - 2;
    }

    /** Whether two stored lists fit in the room they share. */
    public static boolean fits(@Nullable String hidden, @Nullable String kept) {
        return (long) encodedBytes(hidden) + encodedBytes(kept) <= MAX_LIST_BYTES;
    }

    /**
     * What a list being typed would take beside the other list: how many phrases it holds, and
     * how many bytes of the shared room the two would fill. The row reads it as the person types
     * and again when they save.
     *
     * @param otherBytes the other list's {@link #encodedBytes}, as it's stored.
     */
    public static Size size(@Nullable String typed, int otherBytes) {
        // One past the most is enough to say there are too many, and to stop reading there.
        List<String> phrases = phrases(typed, MAX_PHRASES + 1);
        int[] patterns = patterns(typed);
        int bytes = phrases.size() > MAX_PHRASES ? otherBytes : encodedBytes(String.join("\n", phrases)) + otherBytes;
        return new Size(phrases.size(), bytes, patterns[0], patterns[1]);
    }

    /**
     * The patterns a typed list holds, one given twice counted once, and the number of its first
     * line written as a pattern that can't be one (unreadable, or too long), 0 when there's none.
     */
    private static int[] patterns(@Nullable String typed) {
        if (typed == null || typed.isEmpty()) return new int[] {0, 0};
        Set<String> seen = new HashSet<>();
        int count = 0;
        int badLine = 0;
        int line = 1;
        int start = 0;
        while (start <= typed.length()) {
            int end = lineEnd(typed, start);
            String body = patternBody(strip(typed.substring(start, end)));
            if (body != null) {
                if (!readable(body)) {
                    if (badLine == 0) badLine = line;
                } else if (seen.add(body)) {
                    count++;
                }
            }
            start = end + 1;
            line++;
        }
        return new int[] {count, badLine};
    }

    /** A typed list measured by {@link #size}. */
    public static final class Size {
        /** The list's phrases, one past {@link #MAX_PHRASES} at most. */
        public final int phrases;
        /** The bytes both lists would take together. Without this one's when it has too many phrases. */
        public final int bytes;
        /** The readable patterns among its lines, however many there are. */
        public final int patterns;
        /** The number of the first line written as a pattern that can't be read, 0 when there's none. */
        public final int badLine;

        Size(int phrases, int bytes, int patterns, int badLine) {
            this.phrases = phrases;
            this.bytes = bytes;
            this.patterns = patterns;
            this.badLine = badLine;
        }

        public boolean tooMany() {
            return phrases > MAX_PHRASES;
        }

        public boolean tooManyPatterns() {
            return patterns > MAX_PATTERNS;
        }

        /**
         * Whether the list can be saved as it is: few enough phrases and patterns, every pattern
         * readable, and room for them.
         */
        public boolean fits() {
            return !tooMany() && !tooManyPatterns() && badLine == 0 && bytes <= MAX_LIST_BYTES;
        }

        /** How full the shared room would be, in whole percent rounded up, so one phrase shows. */
        public int percent() {
            return (int) (((long) bytes * 100 + MAX_LIST_BYTES - 1) / MAX_LIST_BYTES);
        }
    }

    /** How many posts the rule has hidden since Facebook started. */
    public static int hiddenSinceStart() {
        return HIDDEN.get();
    }

    /**
     * Text folded for matching: compatibility forms, case and the characters Unicode ignores in
     * matching all folded away, the way both a phrase and a post's words are read.
     */
    static String fold(String text) {
        return Normalizer2.getNFKCCasefoldInstance().normalize(text);
    }

    /** [text] with only the case a pattern ignores folded, so a fold that adds nothing more can be told. */
    static String caseFolded(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length(); ) {
            int point = text.codePointAt(at);
            out.appendCodePoint(PostPattern.fold(point));
            at += Character.charCount(point);
        }
        return out.toString();
    }

    /**
     * Both lists folded into one {@link Matcher}, and their patterns compiled, ready to judge
     * posts. Built again only when either list changes, never for a post.
     */
    static final class Rules {
        final String hideSource;
        final String keepSource;
        final boolean wholeWords;
        private final boolean hidesNothing;
        private final Matcher matcher;
        private final List<PostPattern> hidePatterns = new ArrayList<>();
        private final List<PostPattern> keepPatterns = new ArrayList<>();

        Rules(String hideSource, String keepSource, boolean wholeWords) {
            this.hideSource = hideSource;
            this.keepSource = keepSource;
            this.wholeWords = wholeWords;
            String[] hide = folded(hideSource, hidePatterns);
            hidesNothing = hide.length == 0 && hidePatterns.isEmpty();
            matcher = new Matcher(hide, folded(keepSource, keepPatterns), wholeWords);
        }

        /** The list's phrases folded, with its patterns compiled into [patterns]. */
        private static String[] folded(String stored, List<PostPattern> patterns) {
            List<String> folded = new ArrayList<>();
            for (String phrase : phrases(stored)) {
                String body = patternBody(phrase);
                if (body == null) {
                    folded.add(fold(phrase));
                } else {
                    PostPattern pattern = PostPattern.compileOrNull(body);
                    if (pattern != null) patterns.add(pattern);
                }
            }
            return folded.toArray(new String[0]);
        }

        /** Whether there's anything to hide by. With no hide phrase, no post is read at all. */
        boolean hidesNothing() {
            return hidesNothing;
        }

        /**
         * What the lists make of a post's words: a keep phrase anywhere in them wins, then a hide
         * phrase or pattern anywhere hides unless a keep pattern matches too. No words match
         * nothing. A phrase or pattern matches inside one text, never across two. Patterns read each
         * text as it is and folded, and either one can match. Patterns run only
         * where they can change the verdict, all on one {@link PostPattern.Budget}, and a post whose
         * patterns run out of steps is {@link Verdict#TOO_SLOW} and stays.
         */
        Verdict judge(List<String> texts) {
            if (hidesNothing() || texts.isEmpty()) return Verdict.NO_MATCH;
            int found = 0;
            // What the patterns read: each text as it is, so a pattern written with a styled or
            // full-width letter still sees it, and folded, so a plain pattern sees through styled
            // letters, full-width forms, no-break spaces and soft hyphens as the phrases do.
            List<String> patternTexts = new ArrayList<>(texts.size() * 2);
            for (String text : texts) {
                String folded = fold(text);
                found |= matcher.find(folded);
                if ((found & Matcher.KEEPS) != 0) return Verdict.KEEP;
                patternTexts.add(text);
                // Patterns already ignore case, so a copy that differs only by case is the same read twice.
                if (!folded.equals(text) && !folded.equals(caseFolded(text))) patternTexts.add(folded);
            }
            PostPattern.Budget budget = new PostPattern.Budget(STEPS_PER_POST);
            Verdict hide = (found & Matcher.HIDES) != 0 ? Verdict.HIDE : null;
            if (hide == null) {
                PostPattern.Result result = any(hidePatterns, patternTexts, budget);
                if (result == PostPattern.Result.TOO_SLOW) return Verdict.TOO_SLOW;
                if (result == PostPattern.Result.MATCH) hide = Verdict.HIDE_PATTERN;
            }
            if (hide == null) return Verdict.NO_MATCH;
            PostPattern.Result kept = any(keepPatterns, patternTexts, budget);
            if (kept == PostPattern.Result.TOO_SLOW) return Verdict.TOO_SLOW;
            return kept == PostPattern.Result.MATCH ? Verdict.KEEP_PATTERN : hide;
        }

        /** Whether one of [patterns] matches in one of [texts], or the budget ran out first. */
        private static PostPattern.Result any(List<PostPattern> patterns, List<String> texts, PostPattern.Budget budget) {
            for (PostPattern pattern : patterns) {
                for (String text : texts) {
                    PostPattern.Result result = pattern.find(text, budget);
                    if (result != PostPattern.Result.NO_MATCH) return result;
                }
            }
            return PostPattern.Result.NO_MATCH;
        }
    }

    /**
     * Every phrase of both lists in one Aho-Corasick automaton, so a text is read once, a char at a
     * time, however many phrases the lists hold. The trie of the phrases' chars is walked along the
     * text, and where it has no way on, the walk falls back to the longest end of what it has read
     * that still starts a phrase. Each node knows which lists have a phrase ending there or at a node
     * it falls back to, so every phrase inside a text is seen. UTF-16 chars are matched as
     * {@link String#contains} does: a phrase is whole code points, so a match never starts or ends
     * inside a surrogate pair. Whole-word rules include a boundary token in each phrase and in
     * the text, so each output's endpoints are checked even when it is another output's suffix.
     */
    static final class Matcher {
        static final int HIDES = 1;
        static final int KEEPS = 2;
        private static final long NO_EDGE = -1;
        /** Outside the UTF-16 alphabet, so no phrase or post can impersonate a boundary. */
        private static final int BOUNDARY = 1 << 16;
        private final boolean wholeWords;

        /** The trie's edges by key {@code node << 17 | symbol}, open addressing at most half full. */
        private final long[] edgeKeys;
        private final int[] edgeNodes;
        private final int shift;
        /** Per node: where the walk goes when the node has no edge for the next char. */
        private final int[] fallback;
        /** Per node: {@link #HIDES} and {@link #KEEPS} for the lists with a phrase ending there or along its fallbacks. */
        private final byte[] ends;

        Matcher(String[] hide, String[] keep, boolean wholeWords) {
            this.wholeWords = wholeWords;
            int chars = 0;
            for (String phrase : hide) chars += phrase.length();
            for (String phrase : keep) chars += phrase.length();
            // At most one boundary per char, and one at the start of each phrase.
            if (wholeWords) chars = chars * 2 + hide.length + keep.length;
            int capacity = 16;
            while (capacity < chars * 2) capacity <<= 1;
            edgeKeys = new long[capacity];
            Arrays.fill(edgeKeys, NO_EDGE);
            edgeNodes = new int[capacity];
            shift = 64 - Integer.numberOfTrailingZeros(capacity);

            // The trie: node 0 is the empty start, and each node remembers how it was reached.
            int[] parent = new int[chars + 1];
            int[] via = new int[chars + 1];
            int[] depth = new int[chars + 1];
            byte[] marks = new byte[chars + 1];
            int nodes = 1;
            int deepest = 0;
            for (int list = 0; list < 2; list++) {
                for (String phrase : list == 0 ? hide : keep) {
                    int[] symbols = wholeWords ? symbols(phrase) : null;
                    int length = wholeWords ? symbols.length : phrase.length();
                    int node = 0;
                    for (int i = 0; i < length; i++) {
                        int c = wholeWords ? symbols[i] : phrase.charAt(i);
                        int next = edge(node, c);
                        if (next < 0) {
                            next = nodes++;
                            parent[next] = node;
                            via[next] = c;
                            depth[next] = i + 1;
                            put(node, c, next);
                        }
                        node = next;
                    }
                    marks[node] |= list == 0 ? HIDES : KEEPS;
                    deepest = Math.max(deepest, length);
                }
            }

            // Fallbacks shallowest first, since each one lands on a shallower node than its own.
            int[] first = new int[deepest + 2];
            for (int node = 0; node < nodes; node++) first[depth[node] + 1]++;
            for (int d = 1; d < first.length; d++) first[d] += first[d - 1];
            int[] order = new int[nodes];
            for (int node = 0; node < nodes; node++) order[first[depth[node]]++] = node;
            fallback = new int[nodes];
            ends = new byte[nodes];
            for (int node : order) {
                if (depth[node] > 1) {
                    int at = fallback[parent[node]];
                    int next;
                    while ((next = edge(at, via[node])) < 0 && at != 0) at = fallback[at];
                    fallback[node] = Math.max(next, 0);
                }
                ends[node] = (byte) (marks[node] | ends[fallback[node]]);
            }
        }

        /**
         * {@link #HIDES} and {@link #KEEPS} for the lists with a phrase inside [text], which is
         * folded. Stops at the first keep phrase, since nothing after it changes the verdict.
         */
        int find(String text) {
            int found = 0;
            int node = 0;
            for (int i = 0, length = text.length(); i <= length; i++) {
                if (wholeWords && boundary(text, i)) {
                    node = next(node, BOUNDARY);
                    found |= ends[node];
                    if ((found & KEEPS) != 0) break;
                }
                if (i == length) break;
                node = next(node, text.charAt(i));
                found |= ends[node];
                if ((found & KEEPS) != 0) break;
            }
            return found;
        }

        private int next(int node, int symbol) {
            int next;
            while ((next = edge(node, symbol)) < 0 && node != 0) node = fallback[node];
            return Math.max(next, 0);
        }

        /** Compiled once per phrase. The text streams the same symbols without allocating them. */
        private static int[] symbols(String text) {
            int[] symbols = new int[text.length() * 2 + 1];
            int count = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (boundary(text, i)) symbols[count++] = BOUNDARY;
                if (i < text.length()) symbols[count++] = text.charAt(i);
            }
            return Arrays.copyOf(symbols, count);
        }

        /** A phrase may touch punctuation or a symbol, but may not cut a run or a code point. */
        private static boolean boundary(String text, int at) {
            if (at == 0 || at == text.length()) return true;
            if (Character.isHighSurrogate(text.charAt(at - 1))
                    && Character.isLowSurrogate(text.charAt(at))) return false;
            return !run(text, at - Character.charCount(text.codePointBefore(at))) || !run(text, at);
        }

        private static boolean run(String text, int at) {
            int point = text.codePointAt(at);
            if (point == '\'' || point == 0x2018 || point == 0x2019) {
                // Marks belong to their letters on either side. Each mark span is visited only
                // from its adjacent apostrophes, so this stays linear in the text's length.
                return letterBeside(text, at, false) && letterBeside(text, at + 1, true);
            }
            int type = Character.getType(point);
            return Character.isLetter(point) || mark(type) || type == Character.DECIMAL_DIGIT_NUMBER
                    || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER
                    || type == Character.CONNECTOR_PUNCTUATION;
        }

        private static boolean letterBeside(String text, int at, boolean forward) {
            while (forward ? at < text.length() : at > 0) {
                int point = forward ? text.codePointAt(at) : text.codePointBefore(at);
                if (!mark(Character.getType(point))) return Character.isLetter(point);
                at += forward ? Character.charCount(point) : -Character.charCount(point);
            }
            return false;
        }

        private static boolean mark(int type) {
            return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
        }

        private int slot(long key) {
            return (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
        }

        private int edge(int node, int c) {
            long key = ((long) node << 17) | c;
            int mask = edgeKeys.length - 1;
            for (int slot = slot(key); ; slot = (slot + 1) & mask) {
                long at = edgeKeys[slot];
                if (at == key) return edgeNodes[slot];
                if (at == NO_EDGE) return -1;
            }
        }

        private void put(int node, int c, int child) {
            long key = ((long) node << 17) | c;
            int mask = edgeKeys.length - 1;
            int slot = slot(key);
            while (edgeKeys[slot] != NO_EDGE) slot = (slot + 1) & mask;
            edgeKeys[slot] = key;
            edgeNodes[slot] = child;
        }
    }

    private static volatile Rules cached;

    /** Legacy callers use substring matching. */
    static Rules rules(String hideStored, String keepStored) {
        return rules(hideStored, keepStored, false);
    }

    /** The lists and mode, folded and compiled once and kept until any of them changes. */
    static Rules rules(String hideStored, String keepStored, boolean wholeWords) {
        Rules found = cached;
        if (found != null && found.hideSource.equals(hideStored) && found.keepSource.equals(keepStored)
                && found.wholeWords == wholeWords) return found;
        found = new Rules(hideStored, keepStored, wholeWords);
        cached = found;
        return found;
    }

    /**
     * Whether one text outside a post, a comment's words, goes by the post lists: a hide phrase or
     * pattern in it and no keep one, judged as a post's words are. The rules are the posts' own
     * cached ones, so the lists aren't folded twice. Blank text never goes.
     */
    public static boolean hidesText(@Nullable String text, String hideStored, String keepStored, boolean wholeWords) {
        if (text == null || text.trim().isEmpty()) return false;
        Rules found = rules(hideStored, keepStored, wholeWords);
        return !found.hidesNothing() && found.judge(Arrays.asList(text)).hides();
    }

    /**
     * What the lists make of [story], a reel's caption story on the Reels tab (#458): the verdict's
     * reason for the words the rule reads of a post, or why none were read. {@link #storyHides} says
     * whether that takes the reel off.
     */
    public static String storyKind(@Nullable Object story) {
        return storyKind(story, PostText.MESSAGE, PostText.ATTACHED, PostSources.ATTACHMENTS, PostText.ACTION_LINKS);
    }

    static String storyKind(@Nullable Object story, StoryFlag.Accessor message, StoryFlag.Accessor attached,
            StoryFlag.Accessor links, StoryFlag.Accessor actionLinks) {
        Rules found = rules(Settings.HIDDEN_WORDS.get(), Settings.KEPT_WORDS.get(),
                Settings.POST_WORDS_WHOLE_WORDS.get());
        if (found.hidesNothing()) return FeedFilter.NO_WORDS;
        PostText.Read read = PostText.read(story, message, attached, links, actionLinks);
        if (read.outcome != PostText.Outcome.TEXT) return read.outcome.reason;
        return found.judge(read.texts).reason;
    }

    /** Whether a {@link #storyKind} takes the reel off. */
    public static boolean storyHides(String kind) {
        return Verdict.HIDE.reason.equals(kind) || Verdict.HIDE_PATTERN.reason.equals(kind);
    }

    /** Counts a reel the lists took off with the posts they hid, for the settings row. */
    public static void countHiddenReel() {
        HIDDEN.incrementAndGet();
    }

    /** The most of a sample {@link #preview} reads, in code points. The rest is left out and said so. */
    public static final int PREVIEW_MAX_CODE_POINTS = 16_384;

    /** What {@link #preview} makes of a sample. */
    public enum Preview {
        /** A hide phrase or pattern matches and no keep one does. */
        HIDDEN,
        /** A keep phrase or pattern matches, so a post like it stays. */
        KEPT,
        /** Nothing on either list matches. */
        NO_MATCH,
        /** The patterns ran out of steps, and a post that does that stays. */
        TOO_SLOW,
        /** The hide list holds nothing, so no post is read at all. */
        NOTHING_TO_HIDE
    }

    /** A preview's verdict, and whether the sample was longer than {@link #PREVIEW_MAX_CODE_POINTS}. */
    public static final class PreviewResult {
        public final Preview verdict;
        public final boolean cut;

        PreviewResult(Preview verdict, boolean cut) {
            this.verdict = verdict;
            this.cut = cut;
        }
    }

    /**
     * What lists that may not be saved yet make of a typed sample, judged as a post's words are,
     * on the same step budget. It builds its own rules, so the feed's cached ones, the count of
     * hidden posts and the words report are left as they were, and it keeps nothing of the sample.
     */
    public static PreviewResult preview(@Nullable String sample, @Nullable String hideStored,
                                        @Nullable String keepStored, boolean wholeWords) {
        String text = sample == null ? "" : sample;
        boolean cut = text.codePointCount(0, text.length()) > PREVIEW_MAX_CODE_POINTS;
        if (cut) text = text.substring(0, text.offsetByCodePoints(0, PREVIEW_MAX_CODE_POINTS));
        Rules draft = new Rules(hideStored == null ? "" : hideStored, keepStored == null ? "" : keepStored, wholeWords);
        if (draft.hidesNothing()) return new PreviewResult(Preview.NOTHING_TO_HIDE, cut);
        if (text.trim().isEmpty()) return new PreviewResult(Preview.NO_MATCH, cut);
        switch (draft.judge(Arrays.asList(text))) {
            case HIDE:
            case HIDE_PATTERN:
                return new PreviewResult(Preview.HIDDEN, cut);
            case KEEP:
            case KEEP_PATTERN:
                return new PreviewResult(Preview.KEPT, cut);
            case TOO_SLOW:
                return new PreviewResult(Preview.TOO_SLOW, cut);
            default:
                return new PreviewResult(Preview.NO_MATCH, cut);
        }
    }

    /** Where the line starting at [start] ends: the next line break, or the end of the text. */
    private static int lineEnd(String text, int start) {
        int newline = text.indexOf('\n', start);
        return newline < 0 ? text.length() : newline;
    }

    /**
     * Whether a phrase is at least {@link #MIN_LENGTH} characters both as typed and folded, or folds
     * to one character that {@link #standsAlone}. Folding decides, so an emoji's variation selector
     * doesn't count, "Ⓐ" is the letter it folds to, and "ß", which folds to "ss", is still one
     * letter typed.
     */
    private static boolean longEnough(String phrase, String folded) {
        int length = folded.codePointCount(0, folded.length());
        if (length == 1) return standsAlone(folded.codePointAt(0));
        return length >= MIN_LENGTH && phrase.codePointCount(0, phrase.length()) >= MIN_LENGTH;
    }

    /**
     * Whether one character is a word on its own: an ideograph, a kana or Hangul syllable, or a
     * symbol such as an emoji. A letter or digit of a script that spaces its words is inside nearly
     * every post, which is what the floor is for.
     */
    private static boolean standsAlone(int point) {
        if (Character.isIdeographic(point) || Character.getType(point) == Character.OTHER_SYMBOL) return true;
        Character.UnicodeScript script = Character.UnicodeScript.of(point);
        return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
                || Character.UnicodeBlock.of(point) == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    /** The text without the spaces, tabs and returns around it, Unicode's spaces included. */
    static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end) {
            int point = text.codePointAt(start);
            if (!blank(point)) break;
            start += Character.charCount(point);
        }
        while (end > start) {
            int point = text.codePointBefore(end);
            if (!blank(point)) break;
            end -= Character.charCount(point);
        }
        return text.substring(start, end);
    }

    private static boolean blank(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    /** For a test: forgets the folded rules and the count of hidden posts. */
    static void forgetForTests() {
        cached = null;
        HIDDEN.set(0);
    }
}
