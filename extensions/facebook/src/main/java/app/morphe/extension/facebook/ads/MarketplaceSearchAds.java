/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What Hide sponsored Marketplace listings takes out of Marketplace search's answers, on their way
 * from Facebook's servers to the React Native screen that draws them.
 *
 * <p>Marketplace search is React Native. Its results reach Relay as the text of a GraphQL answer, in
 * the pieces Tigon reads it in, and nothing in Java holds them as a list. Its query names no variable
 * that asks the server to leave the ads out either, so the ads come out of that text before
 * JavaScript sees it.
 *
 * <p>An answer is one or more JSON payloads, one after another. The first holds the results as a
 * connection's edges. When Relay streams a list, each later result comes in a payload of its own
 * whose path ends at its index in that list, and fields Relay defers come in payloads whose path runs
 * through the result they belong to. A result is an ad when its node is a story of an ad type
 * ({@code __typename} ending "AdStory", as MarketplaceFeedAdStory does) or carries a
 * {@code sponsored_data} object naming an ad id, on the node itself or one object down. An ad's edge
 * leaves its list, a streamed ad's payload gives way to one with no data that keeps its extensions
 * (Relay passes over it but still reads is_final), and every later index of that list moves down
 * past the ads taken out, so the list stays contiguous and no tile is left empty. Deferred fields of
 * an ad go to an index no list reaches, where Relay never applies them.
 *
 * <p>Only a whole payload can be read, so the start of one that hasn't finished arriving waits for
 * the rest, and what still waits when the answer ends goes on as it came. Text between payloads goes
 * straight on, and so does every byte of a payload outside the ads taken out and the indices moved.
 * A payload whose reading fails, or that grows too long to wait for, goes on as it came, and nothing
 * more is taken out of that answer; later indices still move past the ads already taken out, so the
 * list keeps no hole.
 */
final class MarketplaceSearchAds {
    /** The diagnostic counter route: each list of results, each result's type, and each ad taken out. */
    static final String ROUTE = "Marketplace search ads";

    /** Why a result was taken out, as the counter tells it. */
    static final String REMOVED = "search ads removed";

    /** Where a deferred part of an ad goes: its own index past this, which no list reaches. */
    static final int NOWHERE = 1_000_000;

    /** Maximum characters across an entire response, before any payload tree is built. */
    static final int MAX_RESPONSE_CHARS = 8 * 1024 * 1024;
    static final int MAX_PARSE_DEPTH = 64;
    static final int MAX_PARSE_NODES = 65_536;
    static final String CHARACTER_BUDGET = "search character budget";
    static final String DEPTH_BUDGET = "search depth budget";
    static final String NODE_BUDGET = "search node budget";

    /** What an ad story's type name ends with in Facebook's schema. */
    static final String AD_STORY = "AdStory";

    static final String SPONSORED_DATA = "sponsored_data";

    /** What names the ad in sponsored data. */
    static final String AD_ID = "ad_id";

    /** Parts of answers logged one by one before the log only counts them. */
    static final int LOGGED_ONE_BY_ONE = 40;

    /** After those, one line per this many. */
    static final int SUMMED_UP_BY = 50;

    /** Thrown by the next piece an answer reads, for a test of what a failure passes on. */
    @Nullable
    static volatile RuntimeException failNextPieceForTests;

    private static final AtomicInteger lines = new AtomicInteger();

    private MarketplaceSearchAds() {
    }

    /** One answer on its way to JavaScript, read as it comes in. */
    static final class Answer {
        private final String query;
        private boolean reading;
        /**
         * False once a payload failed to be read or grew too long to wait for: nothing more is taken
         * out, and later indices only move past the ads already taken out.
         */
        private boolean removing = true;
        private int characters;
        @Nullable private MarketplacePathRemapper remapping;
        private final StringBuilder waiting = new StringBuilder();
        private int depth;
        private boolean inString;
        private boolean escaped;
        private int parts;
        /** Each list's ads taken out so far, by the indices the server gave them. */
        private final Map<String, TreeSet<Integer>> taken = new HashMap<>();

        /** Whether ads come out, with Hide sponsored Marketplace listings on. */
        private final boolean ads;
        /** The advanced search's title rules for this answer, or null. */
        @Nullable private final MarketplaceSearchSyntax.Filter filter;

        Answer(String query, boolean reading) {
            this(query, reading, null);
        }

        Answer(String query, boolean ads, @Nullable MarketplaceSearchSyntax.Filter filter) {
            this.query = query;
            this.ads = ads;
            this.filter = filter;
            this.reading = ads || filter != null;
            if (!reading) log(query + " answer went on unread, the switch is off.");
            else if (filter != null) log(query + " answer: titles filtered by " + filter + (ads ? "" : ", ads left in") + ".");
        }

        /**
         * Why [result] leaves its list: it's an ad and ads come out, or its title breaks a rule of
         * the advanced search. Null when it stays.
         */
        @Nullable
        private String why(String s, Value result) {
            if (ads) {
                String ad = adReason(s, result);
                if (ad != null) return ad;
            }
            if (filter == null) return null;
            String title = MarketplaceSearchSyntax.title(s, result);
            if (title == null) return null;
            return filter.broken(title, filter.places.isEmpty() ? null : MarketplaceSearchSyntax.location(s, result));
        }

        /** What the counter says [why] took a result out for. */
        private String counted(String why) {
            return why.startsWith(MarketplaceSearchSyntax.RULE) ? MarketplaceSearchSyntax.REMOVED : REMOVED;
        }

        /**
         * [piece], with every payload it finishes read and its ads taken out. An unfinished payload's
         * start waits for the piece that finishes it. The same string when nothing changed.
         */
        String read(String piece) {
            if (!reading) return piece;
            RuntimeException failure = failNextPieceForTests;
            if (failure != null) {
                failNextPieceForTests = null;
                throw failure;
            }
            boolean held = waiting.length() > 0;
            StringBuilder out = new StringBuilder(piece.length());
            int from = 0;
            for (int i = 0; i < piece.length(); i++) {
                if (remapping != null) {
                    remapping.read(piece, from, piece.length(), out);
                    from = piece.length();
                    break;
                }
                if (++characters > MAX_RESPONSE_CHARS) {
                    refuse(CHARACTER_BUDGET);
                    if (held) remapping.read(waiting, 0, waiting.length(), out);
                    waiting.setLength(0);
                    waiting.trimToSize();
                    remapping.read(piece, from, piece.length(), out);
                    from = piece.length();
                    break;
                }
                char c = piece.charAt(i);
                if (depth == 0) {
                    if (c == '{' || c == '[') {
                        out.append(piece, from, i);
                        from = i;
                        depth = 1;
                    }
                } else if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if ((c == '}' || c == ']') && --depth == 0) {
                    String text = held ? waiting + piece.substring(from, i + 1) : piece.substring(from, i + 1);
                    held = false;
                    waiting.setLength(0);
                    if (waiting.capacity() > 65_536) waiting.trimToSize();
                    Map<String, TreeSet<Integer>> before = new HashMap<>();
                    for (Map.Entry<String, TreeSet<Integer>> list : taken.entrySet()) {
                        before.put(list.getKey(), new TreeSet<>(list.getValue()));
                    }
                    try {
                        out.append(payload(text));
                    } catch (BudgetExceeded budget) {
                        refuse(budget.reason);
                        remapping.read(text, 0, text.length(), out);
                    } catch (Throwable failed) {
                        taken.clear();
                        taken.putAll(before);
                        removing = false;
                        remapping = new MarketplacePathRemapper(taken);
                        HookStatus.threw(FamilyNames.SPONSORED_MARKETPLACE, MarketplaceAdFilter.SEARCH_ANSWER, failed);
                        log(query + " answer: a payload failed to be read (" + failed.getClass().getSimpleName()
                                + "). Nothing more comes out of this answer; known indices still move.");
                        remapping.read(text, 0, text.length(), out);
                    }
                    from = i + 1;
                }
            }
            if (remapping == null && depth > 0) waiting.append(piece, from, piece.length());
            else out.append(piece, from, piece.length());
            String result = out.toString();
            return result.equals(piece) ? piece : result;
        }

        private void refuse(String reason) {
            removing = false;
            remapping = new MarketplacePathRemapper(taken);
            HookStatus.counted(FamilyNames.SPONSORED_MARKETPLACE, reason);
            log(query + " answer: " + reason + ". Nothing more comes out; known indices still move.");
        }

        /** What waits for a payload to finish, as it came, for the end of a whole answer. */
        String rest() {
            String rest = waiting.toString();
            waiting.setLength(0);
            return remapping == null ? rest : rest + remapping.rest();
        }

        /**
         * [piece] after what earlier pieces left waiting, as they came, with nothing read from here
         * on. For a failure outside a payload's reading.
         */
        String giveUp(String piece) {
            reading = false;
            return rest() + piece;
        }

        /** The end of an answer that came in pieces: what still waits, as it came, or null when nothing does. */
        @Nullable
        String end() {
            reading = false;
            String rest = rest();
            if (rest.isEmpty()) return null;
            log(query + " answer ended with " + rest.length() + " characters of a payload that never finished. They went on as they came.");
            return rest;
        }

        /**
         * [text], one whole payload or a list of them, with its ads taken out, or once nothing more
         * comes out, only its indices moved. The same string when nothing changed.
         */
        private String payload(String text) throws BudgetExceeded {
            if (!removing && !moved()) return text;
            Value root;
            try {
                root = Parser.whole(text);
            } catch (Malformed unreadable) {
                parts++;
                log(query + " answer, part " + parts + ": can't be read, it went on as it was.");
                return text;
            }
            if (root.kind == '[') {
                for (Value each : root.items) {
                    if (each.kind == '{') one(text, each);
                }
            } else if (root.kind == '{') {
                one(text, root);
            }
            if (root.replacement != null) return root.replacement;
            if (!root.changed) return text;
            StringBuilder out = new StringBuilder(text.length());
            root.write(text, out);
            return out.toString();
        }

        /** Whether a list has had results taken out, so later indices of it move. */
        private boolean moved() {
            for (TreeSet<Integer> list : taken.values()) {
                if (!list.isEmpty()) return true;
            }
            return false;
        }

        /** One payload: a streamed result, deferred fields, or data with lists of results in it. */
        private void one(String s, Value payload) {
            parts++;
            Part part = new Part();
            Value data = payload.member(s, "data");
            Value path = payload.member(s, "path");
            List<Object> at = path == null ? new ArrayList<>() : steps(s, path);
            if (path != null && at != null && !at.isEmpty() && at.get(at.size() - 1) instanceof Integer
                    && data != null && data.kind == '{' && data.member(s, "node") != null) {
                String list = key(at, at.size() - 1);
                int index = (Integer) at.get(at.size() - 1);
                TreeSet<Integer> out = listOf(list);
                if (out.contains(index)) {
                    // More of a result already taken out: it goes where its deferred fields go.
                    path.items.get(at.size() - 1).replace(Integer.toString(NOWHERE + index));
                    log(query + " answer, part " + parts + ": more of listing " + index + ", which was taken out.");
                    return;
                }
                if (removing) {
                    FeedFilterCounters.sawList(ROUTE, 1);
                    String why = why(s, data);
                    if (why != null) {
                        out.add(index);
                        payload.replace(emptied(s, payload));
                        FeedFilterCounters.removed(ROUTE, 1, counted(why));
                        log(query + " answer, part " + parts + ": streamed listing " + index + ", took it out (" + why + ").");
                        return;
                    }
                }
                int moved = index - out.headSet(index).size();
                if (moved != index) path.items.get(at.size() - 1).replace(Integer.toString(moved));
                if (removing) clean(s, data, at, part);
                log(query + " answer, part " + parts + ": streamed listing " + index
                        + (moved != index ? ", now " + moved : "") + part.said(false) + ".");
                return;
            }
            if (path != null && at != null) renumber(path, at);
            if (data != null && removing) clean(s, data, at, part);
            log(query + " answer, part " + parts + ": " + (removing ? part.said(true) : "only its indices were read") + ".");
        }

        /** Moves each index of [at], a deferred payload's path, the way its list's results moved. */
        private void renumber(Value path, List<Object> at) {
            for (int k = 0; k < at.size(); k++) {
                if (!(at.get(k) instanceof Integer)) continue;
                TreeSet<Integer> list = taken.get(key(at, k));
                if (list == null || list.isEmpty()) continue;
                int index = (Integer) at.get(k);
                int moved = list.contains(index) ? NOWHERE + index : index - list.headSet(index).size();
                if (moved != index) path.items.get(k).replace(Integer.toString(moved));
            }
        }

        /** Takes the ads out of every list of results in [value], which lies at [at], or somewhere unknown when null. */
        private void clean(String s, Value value, @Nullable List<Object> at, Part part) {
            if (value.kind == '{') {
                for (int i = 0; i < value.items.size(); i++) {
                    Value child = value.items.get(i);
                    if (child.kind != '{' && child.kind != '[') continue;
                    clean(s, child, at == null ? null : with(at, s.substring(value.names.get(i)[0], value.names.get(i)[1])), part);
                }
                return;
            }
            if (value.kind != '[') return;
            boolean results = false;
            for (Value item : value.items) {
                if (item.kind == '{' && item.member(s, "node") != null) {
                    results = true;
                    break;
                }
            }
            if (results) {
                TreeSet<Integer> list = at == null ? null : listOf(key(at, at.size()));
                int seen = 0;
                int took = 0;
                int titles = 0;
                for (int i = 0; i < value.items.size(); i++) {
                    Value item = value.items.get(i);
                    if (item.kind != '{') continue;
                    seen++;
                    String why = why(s, item);
                    if (why == null) continue;
                    item.remove();
                    if (list != null) list.add(i);
                    if (REMOVED.equals(counted(why))) took++;
                    else titles++;
                    part.took(why);
                }
                part.listings += seen;
                FeedFilterCounters.sawList(ROUTE, seen);
                FeedFilterCounters.removed(ROUTE, took, REMOVED);
                if (titles > 0) FeedFilterCounters.removed(ROUTE, titles, MarketplaceSearchSyntax.REMOVED);
            }
            for (int i = 0; i < value.items.size(); i++) {
                Value item = value.items.get(i);
                if (!item.gone && (item.kind == '{' || item.kind == '[')) {
                    clean(s, item, at == null ? null : with(at, i), part);
                }
            }
        }

        private TreeSet<Integer> listOf(String list) {
            TreeSet<Integer> out = taken.get(list);
            if (out == null) {
                out = new TreeSet<>();
                taken.put(list, out);
            }
            return out;
        }
    }

    /** What one payload held, for its log line. */
    private static final class Part {
        int listings;
        final Map<String, Integer> took = new LinkedHashMap<>();

        void took(String why) {
            Integer count = took.get(why);
            took.put(why, count == null ? 1 : count + 1);
        }

        String said(boolean whole) {
            int count = 0;
            for (int each : took.values()) count += each;
            if (!whole) return count == 0 ? "" : ", took out " + count + " ad" + (count == 1 ? "" : "s") + " (" + reasons() + ")";
            if (listings == 0) return "no listings in it";
            if (count == 0) return listings + " listings, no ads";
            return listings + " listings, took out " + count + " ad" + (count == 1 ? "" : "s") + " (" + reasons() + ")";
        }

        private String reasons() {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, Integer> why : took.entrySet()) {
                if (text.length() > 0) text.append("; ");
                text.append(why.getKey());
                if (why.getValue() > 1) text.append(' ').append(why.getValue());
            }
            return text.toString();
        }
    }

    /**
     * Why [result], an edge or the node itself, is an ad, or null when it isn't: its node's type name
     * then, when the node carries sponsored data, that too. Counts the node's type as a kind.
     */
    @Nullable
    static String adReason(String s, Value result) {
        Value node = result.member(s, "node");
        Value story = node != null && node.kind == '{' ? node : result;
        Value type = story.member(s, "__typename");
        String name = type != null && type.kind == '"' ? s.substring(type.start + 1, type.end - 1) : null;
        FeedFilterCounters.sawKind(ROUTE, name != null ? name : "no type name");
        if (name != null && name.endsWith(AD_STORY)) return name;
        if (sponsored(s, story)) return (name != null ? name : "no type name") + ", sponsored data";
        for (Value child : story.items) {
            if (child.kind == '{' && sponsored(s, child)) return (name != null ? name : "no type name") + ", sponsored data";
        }
        return null;
    }

    /** Whether [object]'s sponsored data names an ad: an ad id that's a number or a string with something in it. */
    private static boolean sponsored(String s, Value object) {
        Value data = object.member(s, SPONSORED_DATA);
        Value id = data != null && data.kind == '{' ? data.member(s, AD_ID) : null;
        if (id == null) return false;
        if (id.kind == '"') return id.end - id.start > 2;
        char first = s.charAt(id.start);
        return id.kind == '0' && first >= '0' && first <= '9';
    }

    /**
     * What takes a streamed ad's payload's place: data null, and what else it carries, is_final and
     * hasNext above all. Relay passes over a payload whose data is null when its extensions aren't
     * null and it has no errors, and still reads is_final off it. So an extensions object goes in
     * when there's none, and the errors go, as do the label and the path that would make Relay read
     * it as a result.
     */
    static String emptied(String s, Value payload) {
        StringBuilder out = new StringBuilder("{\"data\":null");
        boolean extensions = false;
        for (int i = 0; i < payload.items.size(); i++) {
            String name = s.substring(payload.names.get(i)[0], payload.names.get(i)[1]);
            Value value = payload.items.get(i);
            if (name.equals("data") || name.equals("label") || name.equals("path") || name.equals("errors")) continue;
            if (name.equals("extensions")) {
                if (value.kind != '{') continue;
                extensions = true;
            }
            out.append(",\"").append(name).append("\":").append(s, value.start, value.end);
        }
        if (!extensions) out.append(",\"extensions\":{}");
        return out.append('}').toString();
    }

    /** The steps of a payload's path, names and indices, or null when one is neither. */
    @Nullable
    private static List<Object> steps(String s, Value path) {
        if (path.kind != '[') return null;
        List<Object> steps = new ArrayList<>(path.items.size());
        for (Value step : path.items) {
            if (step.kind == '"') {
                steps.add(s.substring(step.start + 1, step.end - 1));
            } else {
                try {
                    steps.add(Integer.parseInt(s.substring(step.start, step.end)));
                } catch (NumberFormatException notAnIndex) {
                    return null;
                }
            }
        }
        return steps;
    }

    private static List<Object> with(List<Object> at, Object step) {
        List<Object> next = new ArrayList<>(at.size() + 1);
        next.addAll(at);
        next.add(step);
        return next;
    }

    /** The first [count] steps of [at], as one key. */
    private static String key(List<Object> at, int count) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < count; i++) key.append('/').append(at.get(i));
        return key.toString();
    }

    /** One line per payload for the first {@link #LOGGED_ONE_BY_ONE}, then one per {@link #SUMMED_UP_BY}. */
    private static void log(String line) {
        int count = lines.incrementAndGet();
        if (count <= LOGGED_ONE_BY_ONE) {
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, MarketplaceAdFilter.SOURCE,
                    () -> MarketplaceAdFilter.PREFIX + line);
        } else if (count % SUMMED_UP_BY == 0) {
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, MarketplaceAdFilter.SOURCE,
                    () -> MarketplaceAdFilter.PREFIX + count + " search answer parts so far. The last one: " + line);
        }
    }

    /** Forgets the line count, as a new process would. */
    static void forget() {
        lines.set(0);
    }

    /** A value in a payload: where it lies, what's in it, and what's to change. */
    static final class Value {
        final int start;
        int end;
        /** '{', '[', '"', or '0' for a number or a literal. */
        final char kind;
        @Nullable final Value parent;
        final List<Value> items = new ArrayList<>();
        /** An object's member names, as [start, end) inside their quotes. */
        final List<int[]> names = new ArrayList<>();
        boolean gone;
        boolean changed;
        @Nullable String replacement;

        Value(int start, char kind, @Nullable Value parent) {
            this.start = start;
            this.kind = kind;
            this.parent = parent;
        }

        /** The value of this object's member [name], or null. */
        @Nullable
        Value member(String s, String name) {
            for (int i = 0; i < names.size(); i++) {
                int[] at = names.get(i);
                if (at[1] - at[0] == name.length() && s.startsWith(name, at[0])) return items.get(i);
            }
            return null;
        }

        void remove() {
            gone = true;
            touch();
        }

        void replace(String text) {
            replacement = text;
            touch();
        }

        private void touch() {
            for (Value up = parent; up != null && !up.changed; up = up.parent) up.changed = true;
        }

        /** This value's text with what's to change changed. */
        void write(String s, StringBuilder out) {
            if (replacement != null) {
                out.append(replacement);
            } else if (!changed) {
                out.append(s, start, end);
            } else {
                boolean object = kind == '{';
                out.append(object ? '{' : '[');
                boolean first = true;
                for (int i = 0; i < items.size(); i++) {
                    Value item = items.get(i);
                    if (item.gone) continue;
                    if (!first) out.append(',');
                    first = false;
                    if (object) out.append('"').append(s, names.get(i)[0], names.get(i)[1]).append("\":");
                    item.write(s, out);
                }
                out.append(object ? '}' : ']');
            }
        }
    }

    private static final class BudgetExceeded extends Exception {
        final String reason;

        BudgetExceeded(String reason) {
            super(null, null, false, false);
            this.reason = reason;
        }
    }

    /** A payload that isn't one JSON value. */
    static final class Malformed extends Exception {
        Malformed() {
            super(null, null, false, false);
        }
    }

    /** Just enough of a JSON reader to find a payload's values and where each lies. */
    static final class Parser {
        private static final Malformed MALFORMED = new Malformed();
        private static final BudgetExceeded TOO_DEEP = new BudgetExceeded(DEPTH_BUDGET);
        private static final BudgetExceeded TOO_WIDE = new BudgetExceeded(NODE_BUDGET);
        private final String s;
        private int i;
        private int nodes;

        private Parser(String s) {
            this.s = s;
        }

        /** The one value [s] holds, or Malformed when it holds anything else. */
        static Value whole(String s) throws Malformed, BudgetExceeded {
            Parser parser = new Parser(s);
            Value value = parser.value(null, 1);
            parser.space();
            if (parser.i != s.length()) throw MALFORMED;
            return value;
        }

        private Value value(@Nullable Value parent, int depth) throws Malformed, BudgetExceeded {
            if (depth > MAX_PARSE_DEPTH) throw TOO_DEEP;
            if (++nodes > MAX_PARSE_NODES) throw TOO_WIDE;
            space();
            if (i >= s.length()) throw MALFORMED;
            char c = s.charAt(i);
            Value value = new Value(i, c == '{' || c == '[' || c == '"' ? c : '0', parent);
            if (c == '{') {
                i++;
                space();
                if (next() != '}') {
                    while (true) {
                        space();
                        if (next() != '"') throw MALFORMED;
                        int name = i + 1;
                        string();
                        int nameEnd = i - 1;
                        space();
                        if (next() != ':') throw MALFORMED;
                        i++;
                        value.names.add(new int[] {name, nameEnd});
                        value.items.add(value(value, depth + 1));
                        space();
                        char after = next();
                        if (after == ',') {
                            i++;
                            continue;
                        }
                        if (after != '}') throw MALFORMED;
                        break;
                    }
                }
                i++;
            } else if (c == '[') {
                i++;
                space();
                if (next() != ']') {
                    while (true) {
                        value.items.add(value(value, depth + 1));
                        space();
                        char after = next();
                        if (after == ',') {
                            i++;
                            continue;
                        }
                        if (after != ']') throw MALFORMED;
                        break;
                    }
                }
                i++;
            } else if (c == '"') {
                string();
            } else {
                while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) {
                    char d = s.charAt(i);
                    if (d == '{' || d == '[' || d == '"' || d == ':') throw MALFORMED;
                    i++;
                }
                if (i == value.start) throw MALFORMED;
            }
            value.end = i;
            return value;
        }

        /** From an opening quote to just past its closing one. */
        private void string() throws Malformed {
            for (i++; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    i++;
                    return;
                }
            }
            throw MALFORMED;
        }

        private char next() {
            return i < s.length() ? s.charAt(i) : 0;
        }

        private void space() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
                i++;
            }
        }
    }
}
