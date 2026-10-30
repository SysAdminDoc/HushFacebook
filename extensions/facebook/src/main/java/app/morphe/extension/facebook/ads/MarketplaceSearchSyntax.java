/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;


import androidx.annotation.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Advanced search for Marketplace: what a person types in Marketplace's search box can carry rules
 * for listing titles, which Facebook's own search doesn't follow.
 *
 * <ul>
 *   <li>{@code "RAM"} (in quotes): a listing's title must hold RAM.
 *   <li>{@code "16gb ddr4"}: a title must hold that phrase, words in that order.
 *   <li>{@code -PC} or {@code -"gaming pc"}: a title holding PC, or that phrase, leaves the results.
 * </ul>
 *
 * <p>Matching ignores case and punctuation and finds a term anywhere in a title, so {@code -128}
 * takes out "128GB" and {@code -PC} takes out "PCIe" too. Spaces inside quotes count: {@code "128 "}
 * is 128 with a space after it, so it keeps "128 GB" and not "128GB", and {@code -" pc "} takes out
 * PC only as a word of its own. Words outside quotes with no minus go to Facebook as they are and
 * rank the results the way they always did.
 *
 * <p>Two hooks carry it, both ones Hide sponsored Marketplace listings already has. On the way out
 * ({@link #request}), a search query's variables have each query text with rules in it rewritten to
 * its plain words, so the server never sees the quotes or the minus words and searches for what's
 * left, and the rules are kept as the {@link #active} filter. On the way back, {@link
 * MarketplaceSearchAds} takes out each listing whose {@code marketplace_listing_title} breaks a rule.
 * A search with no rules in it leaves the filter empty and the request as it was.
 *
 * <p>The request hook and the answer hooks see different objects, so an answer is filtered by the
 * rules of the latest search request that carried a query text, which is the one whose answer is
 * on its way: a new search, the next page and a refresh each send their variables again. A
 * Marketplace search request with no query text in it leaves those rules as they were.
 *
 * <p>{@code in:melbourne} or {@code in:"north end"} keeps listings whose location holds that
 * place; with more than one, any of them. The search screen's Adv. Filters button ({@link
 * app.morphe.extension.facebook.navigation.MarketplaceFilterButton}) is a dialog for these: it
 * reads the rules out of the last search and writes its lists back into the search box as rules,
 * so what's typed is all there is, and a search Facebook runs again keeps its own rules.
 *
 * <p>A search with rules goes out with one or more spaces after its plain words, a count that
 * comes from its rules. The search reads the same, but Facebook keeps the results of each search
 * it sent, and without them {@code ram -corsair} and {@code ram} would both go out as {@code ram}
 * and share one set of results, filtered for whichever came first.
 */
public final class MarketplaceSearchSyntax {
    static final String SOURCE = "MarketplaceSearchSyntax";
    static final String PREFIX = "Marketplace advanced search: ";

    /** What the counter says a listing left the results for. */
    static final String REMOVED = "listings outside the title rules";

    /** What every reason a rule gives starts with, which tells it from an ad's. */
    static final String RULE = "rule: ";

    /** The longest query text read. Anything longer goes to Facebook as typed. */
    static final int MAX_QUERY_CHARS = 512;

    /** The most terms taken from one of the dialog's lists. */
    static final int MAX_LIST_TERMS = 50;

    /** What a place rule starts with. */
    static final String PLACE = "in:";

    /** The most spaces a search with rules goes out with. */
    static final int MAX_SPACES = 24;

    /** The rules typed into the latest search that carried a query text, or null when it had none. */
    @Nullable
    private static volatile Filter typed;

    /** What was typed into the latest search, as the app sent it, for the Go button to find on screen. */
    @Nullable
    private static volatile String lastText;

    /** What was typed into the latest search, or null before one. */
    @Nullable
    public static String lastText() {
        return lastText;
    }

    private MarketplaceSearchSyntax() {
    }

    /** The rules an answer arriving now is filtered by, the last search's, or null when it had none. */
    @Nullable
    static Filter active() {
        Filter rules = typed;
        return rules == null || rules.isEmpty() ? null : rules;
    }

    /** Forgets the typed rules, as a new process would. */
    static void forget() {
        typed = null;
        lastText = null;
    }

    /**
     * The spaces a search with [rules] goes out with after its plain words: one to {@link
     * #MAX_SPACES}, always as many for the same rules.
     */
    static String spaces(Filter rules) {
        int count = 1 + Math.floorMod(rules.toString().hashCode(), MAX_SPACES);
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(' ');
        return out.toString();
    }

    /** The rules of one search: phrases a title must hold, phrases it mustn't, and places it may be in. */
    static final class Filter {
        final List<String> required;
        final List<String> excluded;
        /** A listing's location must hold one of these, when there are any. */
        final List<String> places;

        Filter(List<String> required, List<String> excluded) {
            this(required, excluded, Collections.<String>emptyList());
        }

        Filter(List<String> required, List<String> excluded, List<String> places) {
            this.required = Collections.unmodifiableList(required);
            this.excluded = Collections.unmodifiableList(excluded);
            this.places = Collections.unmodifiableList(places);
        }

        boolean isEmpty() {
            return required.isEmpty() && excluded.isEmpty() && places.isEmpty();
        }

        /** [a]'s rules and [b]'s together, or whichever isn't null. */
        @Nullable
        static Filter merge(@Nullable Filter a, @Nullable Filter b) {
            if (a == null) return b;
            if (b == null) return a;
            return new Filter(union(a.required, b.required), union(a.excluded, b.excluded), union(a.places, b.places));
        }

        private static List<String> union(List<String> a, List<String> b) {
            List<String> out = new ArrayList<>(a);
            for (String each : b) {
                if (!out.contains(each)) out.add(each);
            }
            return out;
        }

        /** Why [title] leaves the results, or null when it keeps to every title rule. */
        @Nullable
        String broken(String title) {
            return broken(title, null);
        }

        /**
         * Why a listing of [title] at [location] leaves the results, or null when it keeps to every
         * rule. A listing whose location isn't known keeps to the place rule.
         */
        @Nullable
        String broken(String title, @Nullable String location) {
            String lower = title.toLowerCase(Locale.ROOT);
            for (String phrase : required) {
                if (!holds(lower, phrase)) return RULE + "title lacks \"" + phrase + "\"";
            }
            for (String phrase : excluded) {
                if (holds(lower, phrase)) return RULE + "title has -" + phrase;
            }
            if (!places.isEmpty() && location != null) {
                String where = location.toLowerCase(Locale.ROOT);
                for (String place : places) {
                    if (holds(where, place)) return null;
                }
                return RULE + "not in " + String.join(" or ", places);
            }
            return null;
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            for (String phrase : required) text.append(text.length() > 0 ? " " : "").append('"').append(phrase).append('"');
            for (String phrase : excluded) {
                text.append(text.length() > 0 ? " " : "").append('-');
                if (phrase.indexOf(' ') >= 0) text.append('"').append(phrase).append('"');
                else text.append(phrase);
            }
            if (!places.isEmpty()) text.append(text.length() > 0 ? " " : "").append("in ").append(String.join("/", places));
            return text.toString();
        }
    }

    /** Which of the Adv. Filters dialog's lists {@link #rules} reads. */
    public static final int INCLUDE = 0;
    public static final int EXCLUDE = 1;
    public static final int PLACES = 2;

    /**
     * One of the Adv. Filters dialog's lists as [text] holds it: {@link #INCLUDE}, the terms a title
     * must hold, {@link #EXCLUDE}, those it mustn't, or {@link #PLACES}. Empty when it holds none.
     */
    public static List<String> rules(@Nullable String text, int which) {
        Parsed parsed = parse(text);
        if (parsed == null) return new ArrayList<>();
        Filter filter = parsed.filter;
        return new ArrayList<>(which == INCLUDE ? filter.required : which == EXCLUDE ? filter.excluded : filter.places);
    }

    /** How many rules [text] holds, for the Adv. Filters button's label. */
    public static int ruleCount(@Nullable String text) {
        Parsed parsed = parse(text);
        return parsed == null ? 0 : parsed.filter.required.size() + parsed.filter.excluded.size() + parsed.filter.places.size();
    }

    /** [term] as the dialog shows it: as it is, or in quotes when a space at an end counts. */
    public static String shown(String term) {
        return term.equals(term.trim()) ? term : "\"" + term + "\"";
    }

    /**
     * The search text for [text]'s own words with the dialog's lists as its rules, in place of the
     * rules it held: {@code "ddr5"} for each term a title must hold, {@code -corsair} for each it
     * mustn't, {@code in:melbourne} for each place. A term of more than one word stays one phrase:
     * {@code "red wing"}, {@code -"red wing"}. The terms are as {@link #terms} gives them.
     */
    public static String compose(@Nullable String text, List<String> include, List<String> exclude, List<String> places) {
        String base = text == null ? "" : text.trim();
        Parsed parsed = parse(base);
        StringBuilder out = new StringBuilder(parsed == null ? base : parsed.loose);
        for (String term : include) append(out, "\"" + term + "\"");
        for (String term : exclude) append(out, "-" + rule(term));
        for (String term : places) append(out, PLACE + rule(term));
        return out.toString();
    }

    /** [term] as typed after a minus or in:, in quotes when it's more than one word. */
    private static String rule(String term) {
        return term.indexOf(' ') < 0 ? term : "\"" + term + "\"";
    }

    /**
     * [list]'s terms: split by commas and lines, a leading minus dropped. A term in quotes keeps the
     * spaces at its ends, as typed in the search box; any other is {@link #normalized}. Each is one
     * term however many words it has, so red wing is the phrase "red wing".
     */
    public static List<String> terms(@Nullable String list) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (String each : list.split("[,\\n;]")) {
            String term = each.trim();
            if (term.startsWith("-")) term = term.substring(1).trim();
            boolean quoted = term.length() >= 2 && term.startsWith("\"") && term.endsWith("\"");
            term = quoted ? squashed(term.substring(1, term.length() - 1)) : normalized(term.replace("\"", ""));
            if (!term.trim().isEmpty() && !out.contains(term)) out.add(term);
            if (out.size() >= MAX_LIST_TERMS) break;
        }
        return out;
    }

    /** One query text read: the words for Facebook, and the rules for the titles. */
    static final class Parsed {
        final String plain;
        /** The words that are no rule at all, as typed. */
        final String loose;
        final Filter filter;

        Parsed(String plain, String loose, Filter filter) {
            this.plain = plain;
            this.loose = loose;
            this.filter = filter;
        }
    }

    /**
     * [text] read as a query: quoted phrases are rules a title must keep to and go to Facebook as
     * plain words, minus words and minus phrases are rules that go no further, and so are places
     * after {@code in:}. Null when [text] holds no rule, so it goes to Facebook as typed.
     */
    @Nullable
    static Parsed parse(@Nullable String text) {
        if (text == null || text.length() > MAX_QUERY_CHARS) return null;
        if (text.indexOf('"') < 0 && text.indexOf('-') < 0 && !text.toLowerCase(Locale.ROOT).contains(PLACE)) return null;
        List<String> required = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        List<String> places = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        StringBuilder loose = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            boolean minus = c == '-' && i + 1 < n && !Character.isWhitespace(text.charAt(i + 1));
            int at = minus ? i + 1 : i;
            boolean place = !minus && text.regionMatches(true, at, PLACE, 0, PLACE.length())
                    && at + PLACE.length() < n && !Character.isWhitespace(text.charAt(at + PLACE.length()));
            if (place) at += PLACE.length();
            String phrase;
            boolean quoted = text.charAt(at) == '"';
            if (quoted) {
                int close = text.indexOf('"', at + 1);
                if (close < 0) close = n;
                phrase = text.substring(at + 1, close);
                i = Math.min(n, close + 1);
            } else {
                int end = at;
                while (end < n && !Character.isWhitespace(text.charAt(end))) end++;
                phrase = text.substring(at, end);
                i = end;
            }
            // Quoted, the spaces at its ends count: "128 " is 128 and then a space.
            String words = quoted ? squashed(phrase) : normalized(phrase);
            if (words.trim().isEmpty()) continue;
            if (place) {
                if (!places.contains(words.trim())) places.add(words.trim());
            } else if (minus) {
                excluded.add(words);
            } else if (quoted) {
                required.add(words);
                append(plain, phrase.trim());
            } else {
                append(plain, phrase);
                append(loose, phrase);
            }
        }
        Filter filter = new Filter(required, excluded, places);
        if (filter.isEmpty()) return null;
        // Empty for a search of only minus words: the text then goes to Facebook as typed.
        return new Parsed(plain.toString(), loose.toString(), filter);
    }

    private static void append(StringBuilder plain, String words) {
        if (words.isEmpty()) return;
        if (plain.length() > 0) plain.append(' ');
        plain.append(words);
    }

    /** An unquoted term as a rule: {@link #squashed} with no space at either end. */
    static String normalized(String phrase) {
        return squashed(phrase).trim();
    }

    /**
     * [text] lower-cased, with each run of anything but letters and digits made one space. A space
     * at either end stays, as one, so a quoted {@code "128 "} keeps the space that makes it exact.
     */
    static String squashed(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean gap = false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (gap) out.append(' ');
                gap = false;
                out.append(c);
            } else {
                gap = true;
            }
        }
        if (gap) out.append(' ');
        return out.toString();
    }

    /**
     * Whether [text], a title or a location, holds [phrase] anywhere, case and punctuation aside:
     * {@code 128} is in "128GB", and {@code "128 "} isn't, since a space has to follow. The text's
     * own start and end count as spaces, so {@code " pc "} is in "Gaming PC".
     */
    static boolean holds(String text, String phrase) {
        return squashed(" " + text + " ").contains(phrase);
    }

    /**
     * Injection point's helper, for a Marketplace search query's request [body]: each query text in
     * its variables that holds a rule is rewritten to its plain words, and the rules become the
     * typed part of the {@link #active} filter. A query text with no rule clears that part; a request
     * with no query text leaves it as it was. The same string when nothing changes, and the body as
     * it came on anything it can't read.
     */
    static String request(String query, String body) {
        try {
            if (body.length() > MarketplaceAdFilter.MAX_BODY_CHARS) return body;
            int[] range = MarketplaceAdFilter.variablesParameter(body);
            if (range == null) return body;
            MarketplaceAdFilter.Decoded decoded = MarketplaceAdFilter.Decoded.of(body, range[0], range[1]);
            if (decoded == null) return body;
            String text = new String(decoded.bytes, 0, decoded.length, StandardCharsets.UTF_8);
            MarketplaceSearchAds.Value variables = MarketplaceSearchAds.Parser.whole(text);
            if (variables.kind != '{') return body;
            List<String> rewritten = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            Filter found = rewrite(text, variables, null, rewritten, seen);
            if (seen.isEmpty()) {
                log(query + ": no query text in it, the rules stay " + (typed == null ? "empty" : typed.toString()) + ".");
                return body;
            }
            Filter before = typed;
            typed = found;
            if (rewritten.isEmpty()) {
                log(query + ": no rules in its query text (" + String.join(", ", seen) + ").");
                return body;
            }
            StringBuilder edited = new StringBuilder(text.length());
            variables.write(text, edited);
            String encoded = URLEncoder.encode(edited.toString(), "UTF-8");
            log(query + ": filtering titles by " + found + ", sent " + String.join(", ", rewritten)
                    + (String.valueOf(before).equals(String.valueOf(found)) ? "" : ", new rules") + ".");
            return body.substring(0, range[0]) + encoded + body.substring(range[1]);
        } catch (Exception unreadable) {
            log(query + ": its variables can't be read (" + unreadable.getClass().getSimpleName() + "), it went out as it was.");
            return body;
        }
    }

    /**
     * Rewrites each string member of [value] whose name says it's a query text and that holds a
     * rule, noting what it now says in [rewritten] and every query text it read in [seen]. The
     * rules of the last one, or null when none had any.
     */
    @Nullable
    private static Filter rewrite(String s, MarketplaceSearchAds.Value value, @Nullable String name,
                                  List<String> rewritten, List<String> seen) {
        Filter found = null;
        if (value.kind == '"') {
            if (!queryText(name)) return null;
            String typedText = Json.unescape(s, value.start + 1, value.end - 1);
            if (seen.isEmpty() && !typedText.trim().isEmpty()) lastText = typedText;
            seen.add(name + " \"" + typedText + "\"");
            Parsed parsed = parse(typedText);
            if (parsed == null) return null;
            // A search of rules only goes as typed, which finds more than nothing would.
            String plain = parsed.plain.isEmpty() ? typedText.trim() : parsed.plain;
            String sent = plain + spaces(parsed.filter);
            value.replace(Json.quote(sent));
            rewritten.add(name + " \"" + sent + "\"");
            return parsed.filter;
        }
        boolean object = value.kind == '{';
        for (int i = 0; i < value.items.size(); i++) {
            String child = object ? s.substring(value.names.get(i)[0], value.names.get(i)[1]) : name;
            Filter inner = rewrite(s, value.items.get(i), child, rewritten, seen);
            if (inner != null) found = inner;
        }
        return found;
    }

    /**
     * Whether a variable named [name] holds what was typed. No capture of the app's own search
     * request names it yet; the web's is {@code query}, so names that end in query or name a query
     * text, keyword or search term are read, and only a value holding a rule ever changes. Ids and
     * kinds of a search, like searchSessionID or search_type, aren't.
     */
    static boolean queryText(@Nullable String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT).replace("_", "");
        return lower.endsWith("query") || lower.contains("querytext") || lower.contains("querystring")
                || lower.contains("keyword") || lower.equals("searchterm") || lower.equals("searchtext")
                || lower.equals("searchstring");
    }

    /**
     * Where the listing of [result] is, as one line: every string under its {@code reverse_geocode}
     * (city, state, city page name) and its {@code location_text}, a few levels down. Null when it
     * carries none, and a listing with no location keeps its place.
     */
    @Nullable
    static String location(String s, MarketplaceSearchAds.Value result) {
        StringBuilder out = new StringBuilder();
        places(s, result, 0, false, out);
        return out.length() == 0 ? null : out.toString();
    }

    private static void places(String s, MarketplaceSearchAds.Value value, int depth, boolean inside, StringBuilder out) {
        if (depth > 8 || out.length() > 512) return;
        if (value.kind == '"') {
            if (!inside) return;
            if (out.length() > 0) out.append(", ");
            out.append(Json.unescape(s, value.start + 1, value.end - 1));
            return;
        }
        boolean object = value.kind == '{';
        for (int i = 0; i < value.items.size(); i++) {
            boolean here = inside;
            if (object && !inside) {
                String name = s.substring(value.names.get(i)[0], value.names.get(i)[1]);
                here = name.equals("reverse_geocode") || name.equals("location_text");
            }
            places(s, value.items.get(i), depth + 1, here, out);
        }
    }

    /**
     * The titles of [result], an edge of a list of search results, as one line: every string a few
     * levels down under a name ending in title ({@code marketplace_listing_title}, {@code
     * custom_title}, which vehicles may carry instead, or a plain {@code title}), or that object's
     * {@code text}. Subtitles aren't titles. Null when it has none, and a result with no title keeps
     * its place.
     */
    @Nullable
    static String title(String s, MarketplaceSearchAds.Value result) {
        StringBuilder out = new StringBuilder();
        titles(s, result, 0, out);
        return out.length() == 0 ? null : out.toString();
    }

    private static void titles(String s, MarketplaceSearchAds.Value value, int depth, StringBuilder out) {
        if (value.kind != '{' && value.kind != '[' || depth > 8 || out.length() > 1024) return;
        boolean object = value.kind == '{';
        for (int i = 0; i < value.items.size(); i++) {
            MarketplaceSearchAds.Value child = value.items.get(i);
            if (object && titled(s.substring(value.names.get(i)[0], value.names.get(i)[1]))) {
                MarketplaceSearchAds.Value text = child.kind == '{' ? child.member(s, "text") : child;
                if (text != null && text.kind == '"') {
                    String found = Json.unescape(s, text.start + 1, text.end - 1);
                    if (!found.isEmpty() && out.indexOf(found) < 0) {
                        if (out.length() > 0) out.append(" | ");
                        out.append(found);
                    }
                    continue;
                }
            }
            titles(s, child, depth + 1, out);
        }
    }

    private static boolean titled(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith("title") && !lower.contains("sub");
    }

    /** JSON strings, as much as a query text and a title need. */
    static final class Json {
        private Json() {
        }

        /** The text of the JSON string body between [start] and [end] of [s]. */
        static String unescape(String s, int start, int end) {
            StringBuilder out = new StringBuilder(end - start);
            for (int i = start; i < end; i++) {
                char c = s.charAt(i);
                if (c != '\\' || i + 1 >= end) {
                    out.append(c);
                    continue;
                }
                char e = s.charAt(++i);
                switch (e) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'u':
                        if (i + 4 < end) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                                break;
                            } catch (NumberFormatException notHex) {
                                // Falls through to the letter as it is.
                            }
                        }
                        out.append(e);
                        break;
                    default: out.append(e);
                }
            }
            return out.toString();
        }

        /** [text] as a JSON string, quotes included. */
        static String quote(String text) {
            StringBuilder out = new StringBuilder(text.length() + 2).append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                else out.append(c);
            }
            return out.append('"').toString();
        }
    }

    private static void log(String line) {
        Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE, () -> PREFIX + line);
    }
}
