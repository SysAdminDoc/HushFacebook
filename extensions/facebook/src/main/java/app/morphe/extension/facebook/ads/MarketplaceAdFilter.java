/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.navigation.MarketplaceFilterButton;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;

/**
 * What the Hide sponsored Marketplace listings patch does to the requests Marketplace's feed sends.
 *
 * <p>Marketplace is drawn by React Native, and its feed asks Facebook's servers through Relay, whose
 * network layer posts each GraphQL query through Facebook's own Networking module: a form-encoded
 * body with the query's {@code variables} as JSON, and a tracking name of "RelayFBNetwork_" plus the
 * query's name. The patch hands that body to {@link #requestBody} as the module reads it, before
 * anything is sent, so no ad is ever fetched and there's nothing on screen to take out afterwards.
 *
 * <p>Two things happen to a Marketplace query while the switch is on:
 *
 * <ul>
 *   <li>The feed's own query declares {@code shouldSkipAdRequest} and
 *       {@code shouldSkipBoostedListingAdRequest}, which tell the server to leave the ads and the
 *       boosted listings out of its answer. A Marketplace query whose variables name either one at
 *       their top level has it set to true. A variable a query doesn't name is never added.
 *   <li>The four ads-only queries Facebook's own code lists beside the feed ({@link
 *       #ADS_ONLY_QUERIES}) aren't sent, and neither are the three that fill a listing page's ad
 *       rows ({@link #LISTING_ADS_QUERIES}). The body answered is null, and the Networking module
 *       reports that to Relay as a request it couldn't make, its own error path.
 * </ul>
 *
 * <p>A video ad that reaches the feed anyway isn't drawn: the two native components that draw one
 * ask {@link #hidesVideoAd} first, and draw nothing on a yes.
 *
 * <p>Nothing else in a body changes: every other byte of it goes out as Facebook wrote it. Requests
 * of other surfaces aren't read past their tracking name.
 *
 * <p>Marketplace search has no ads-only query and no variable that asks to skip them: its results
 * query brings the ads back among the listings. So the answers of Marketplace's search queries are
 * read on their way back, where the module's Tigon callbacks hand the text to JavaScript ({@link
 * #responsePiece}, {@link #responseWhole}, and {@link #responseEnd} for what still waits when an
 * answer in pieces ends), and {@link MarketplaceSearchAds} takes the ads out.
 *
 * <p>It fails open: with the patch not in the build, the switch off, Hushfacebook paused, the
 * settings not ready yet, a body it can't read, or any failure in here, the request is Facebook's
 * own.
 */
public final class MarketplaceAdFilter {
    /** The diagnostic counter route: each Marketplace request, its query, and what was done to it. */
    static final String ROUTE = "Marketplace ads";

    /** The name the log lines go under, which is also their logcat tag after Morphe's prefix. */
    static final String SOURCE = "MarketplaceAdFilter";

    /** What every line of this hook starts with, for a person reading the log. */
    static final String PREFIX = "Marketplace ads: ";

    /** What Relay's network layer puts before a query's name in a request's tracking name. */
    static final String RELAY = "RelayFBNetwork_";

    /** The start of every Marketplace query's name. */
    static final String MARKETPLACE = "Marketplace";

    /** React Native's map interface, which the Networking module's request data is. Kept by Redex. */
    static final String READABLE_MAP = "com.facebook.react.bridge.ReadableMap";

    /** The request data's key for the tracking name, which the Networking module reads itself. */
    static final String TRACKING_NAME = "trackingName";

    /** The form parameter holding the query's variables. */
    static final String VARIABLES = "variables";

    /**
     * The Marketplace feed's ads-only queries, the list Facebook's own code keeps (with the
     * "RelayFBNetwork_" prefix) to catch their answers in the Networking module on 577 and 580.
     */
    static final String[] ADS_ONLY_QUERIES = {
            "MarketplaceHomeFeedAdsQueryRendererQuery",
            "MarketplaceHomeFeedAdsPaginationQuery",
            "MarketplaceHomeFeedBoostedListingAdsQuery",
            "MarketplaceHomeFeedBoostedListingAdsPaginationQuery",
    };

    /**
     * The queries that fill a listing page's ad rows: "Ads inspired by your views", the boosted
     * listings under "Ads from sellers", and "Suggested ad products", which Facebook asks for when
     * the first goes unanswered. None is on Facebook's list, and their names are only in the
     * compressed JavaScript bundle, so no fixture holds them. On 581 the first two answered ad
     * stories and nothing else, and every tile of the rows they fill was an ad.
     */
    static final String[] LISTING_ADS_QUERIES = {
            "MarketplaceProductDetailsPageRelatedAdsDetailQuery",
            "MarketplacePDPBoostedListingAdsQuery",
            "MarketplacePDPPersonalizedAdsQuery",
    };

    /** The variables of the feed's query that ask the server to leave its ads out. */
    static final String[] SKIP_VARIABLES = {
            "shouldSkipAdRequest",
            "shouldSkipBoostedListingAdRequest",
    };

    /** What the counter says was done to a request. */
    static final String HELD_BACK = "ads-only query held back";
    static final String SKIPPED = "feed query asked to skip ads";

    /** What the counter calls a video ad in Marketplace's feed, and what it says when one isn't drawn. */
    static final String VIDEO_AD = "video ad";
    static final String NOT_DRAWN = "video ad not drawn";

    /** What names a Marketplace search query, and what names the ones whose answers carry no results. */
    static final String SEARCH = "Search";
    static final String TYPEAHEAD = "Typeahead";
    static final String MUTATION = "Mutation";

    /** Where the response hooks report under the patch's family. */
    static final String SEARCH_ANSWER = "Marketplace search answer";

    /** Search answers being read, by the Networking module's record of their request. */
    private static final Map<Object, MarketplaceSearchAds.Answer> answers = new WeakHashMap<>();

    /** A body longer than this is sent as it is, unread. Relay's are a few kilobytes. */
    static final int MAX_BODY_CHARS = 512 * 1024;

    /** Requests logged one by one before the log only counts them. */
    static final int LOGGED_ONE_BY_ONE = 40;

    /** After those, one line per this many requests. */
    static final int SUMMED_UP_BY = 50;

    /** Whether the patch is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    /** Thrown by the next hook call, for a test of the fail-open path. */
    @Nullable
    static volatile RuntimeException failNextForTests;

    /** ReadableMap.getString(String), looked up once. */
    @Nullable
    private static volatile Method getString;
    private static volatile boolean lookedUp;

    /** How many Marketplace requests have had a line. */
    private static final AtomicInteger lines = new AtomicInteger();

    private MarketplaceAdFilter() {
    }

    /** Whether this build carries the patch. */
    static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.sponsoredMarketplace();
    }

    /**
     * Injection point, in Facebook's React Native Networking module right after it reads a POST
     * request's text body out of the request data, and before anything is built from it. [body] is
     * that text, [data] the request data it came from. Answers the body to send, null to have the
     * module refuse the request. Never throws.
     */
    @Nullable
    public static String requestBody(@Nullable String body, @Nullable Object data) {
        try {
            if (!inBuild()) return body;
            HookStatus.invoked(FamilyNames.SPONSORED_MARKETPLACE);
            RuntimeException failure = failNextForTests;
            if (failure != null) {
                failNextForTests = null;
                throw failure;
            }
            if (body == null || data == null) return body;
            String query = marketplaceQuery(trackingName(data));
            if (query == null) return body;
            HookStatus.bound(FamilyNames.SPONSORED_MARKETPLACE, "Marketplace request");
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.sawKind(ROUTE, query);
            MarketplaceResponseDiagnostics.request(query, body);
            boolean on = switchedOn();
            String heldBack = heldBack(query);
            if (heldBack != null) {
                if (!on) {
                    log(query + " went out, the switch is off.");
                    return body;
                }
                FeedFilterCounters.removed(ROUTE, 1, HELD_BACK);
                log("held back " + query + ", " + heldBack + ".");
                return null;
            }
            if (searchQuery(RELAY + query) != null) {
                // Every search query has its text rewritten alike, so none of them tells Facebook a
                // text the others don't. Only a page of results puts the button up.
                if (advancedSearch()) {
                    body = MarketplaceSearchSyntax.request(query, body);
                    if (resultsQuery(query) && Settings.MARKETPLACE_FILTERS_BUTTON.get()) {
                        MarketplaceFilterButton.searchSeen();
                    }
                }
            } else if (listingQuery(query)) {
                MarketplaceFilterButton.listingSeen();
            }
            Rewrite rewrite = skipAds(body, on);
            if (rewrite.body != body) FeedFilterCounters.removed(ROUTE, 1, SKIPPED);
            log(query + " " + rewrite.note);
            return rewrite.body;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPONSORED_MARKETPLACE, "Marketplace request", failure);
            return body;
        }
    }

    /**
     * Injection point, first thing in each of the two Litho components that draw a video ad in
     * Marketplace's feed. Answers true to have the component draw nothing, before it asks for the
     * ad's video. Never throws.
     */
    public static boolean hidesVideoAd() {
        try {
            if (!inBuild()) return false;
            HookStatus.invoked(FamilyNames.SPONSORED_MARKETPLACE);
            HookStatus.bound(FamilyNames.SPONSORED_MARKETPLACE, "Marketplace video ad");
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.sawKind(ROUTE, VIDEO_AD);
            if (!switchedOn()) {
                log("drew a video ad, the switch is off.");
                return false;
            }
            FeedFilterCounters.removed(ROUTE, 1, NOT_DRAWN);
            log("didn't draw a video ad.");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPONSORED_MARKETPLACE, "Marketplace video ad", failure);
            return false;
        }
    }

    /**
     * Injection point, in the Tigon callbacks of Facebook's Networking module, where a piece of a
     * response's text goes to JavaScript as it comes in. [piece] is that text, [trackingName] the
     * request's, and [request] the module's own record of the request, one object for every piece of
     * a response. Answers the text to pass on: a Marketplace search answer's with its ads taken out
     * (see {@link MarketplaceSearchAds}), anything else as it came. Never throws.
     */
    @Nullable
    public static String responsePiece(@Nullable String piece, @Nullable String trackingName, @Nullable Object request) {
        MarketplaceSearchAds.Answer answer = null;
        try {
            if (piece == null || request == null || !inBuild()) return piece;
            MarketplaceResponseDiagnostics.piece(piece, trackingName, request);
            String query = searchQuery(trackingName);
            if (query == null) return piece;
            synchronized (answers) {
                answer = answers.get(request);
                if (answer == null) {
                    answer = newAnswer(query);
                    answers.put(request, answer);
                }
            }
            return answer.read(piece);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPONSORED_MARKETPLACE, SEARCH_ANSWER, failure);
            return answer != null ? answer.giveUp(piece) : piece;
        }
    }

    /**
     * Injection point, in the same callbacks where a response's whole text goes to JavaScript at its
     * end, for a request that didn't ask for it in pieces. Answers the text to pass on. Never throws.
     */
    @Nullable
    public static String responseWhole(@Nullable String text, @Nullable String trackingName) {
        try {
            if (text == null || !inBuild()) return text;
            MarketplaceResponseDiagnostics.whole(text, trackingName);
            String query = searchQuery(trackingName);
            if (query == null) return text;
            MarketplaceSearchAds.Answer answer = newAnswer(query);
            String read = answer.read(text);
            String rest = answer.rest();
            return rest.isEmpty() ? read : read + rest;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPONSORED_MARKETPLACE, SEARCH_ANSWER, failure);
            return text;
        }
    }

    /**
     * Injection point, in the same callbacks right before a response that came in pieces is reported
     * complete to JavaScript. [request] is the module's record of it, as {@link #responsePiece} got
     * it. Answers the text still waiting there for a payload to finish, as it came, for the patch to
     * hand on as one last piece, or null when nothing waits. The answer is done with. Never throws.
     */
    @Nullable
    public static String responseEnd(@Nullable Object request) {
        try {
            if (request == null) return null;
            MarketplaceResponseDiagnostics.end(request);
            MarketplaceSearchAds.Answer answer;
            synchronized (answers) {
                answer = answers.remove(request);
            }
            return answer != null ? answer.end() : null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPONSORED_MARKETPLACE, SEARCH_ANSWER, failure);
            return null;
        }
    }

    /** A search answer's reading, reported as the hook reaching one; unread when the switch is off. */
    private static MarketplaceSearchAds.Answer newAnswer(String query) {
        HookStatus.invoked(FamilyNames.SPONSORED_MARKETPLACE);
        RuntimeException failure = failNextForTests;
        if (failure != null) {
            failNextForTests = null;
            throw failure;
        }
        HookStatus.bound(FamilyNames.SPONSORED_MARKETPLACE, SEARCH_ANSWER);
        return new MarketplaceSearchAds.Answer(query, switchedOn(),
                advancedSearch() ? MarketplaceSearchSyntax.active() : null);
    }

    /**
     * Whether Marketplace search reads its advanced syntax (see {@link MarketplaceSearchSyntax}).
     * It has no switch of its own, since a search without quotes or minus words is left alone, but
     * it stays out while Hushfacebook is paused or its settings aren't ready.
     */
    private static boolean advancedSearch() {
        try {
            return Utils.settingsReady() && !HushfacebookPause.isPaused() && Settings.MARKETPLACE_ADVANCED_SEARCH.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPONSORED_MARKETPLACE, "advanced search read", failure);
            return false;
        }
    }

    /**
     * The query's name when [trackingName] is a Relay query of Marketplace search's that can carry
     * results, else null. The typeahead's suggestions and the mutations that keep the search history
     * aren't read.
     */
    @Nullable
    static String searchQuery(@Nullable String trackingName) {
        String query = marketplaceQuery(trackingName);
        if (query == null || !query.contains(SEARCH) || query.contains(TYPEAHEAD) || query.endsWith(MUTATION)) return null;
        return query;
    }

    /**
     * Whether [query] fetches a page of search results: the head of the results feed or its next
     * page. Facebook also fetches these while a search is still being typed, so the button checks
     * for the keyboard itself before it shows.
     */
    static boolean resultsQuery(String query) {
        return searchQuery(RELAY + query) != null && query.contains("SearchFeed");
    }

    /** Whether [query] is one a listing's own page sends for its listing, which takes the Filters button down. */
    static boolean listingQuery(String query) {
        return query.startsWith("MarketplacePDP") || query.startsWith("MarketplaceProductDetailsPage");
    }

    /** The query's name when [trackingName] is a Relay query of Marketplace's, else null. */
    @Nullable
    static String marketplaceQuery(@Nullable String trackingName) {
        if (trackingName == null || !trackingName.startsWith(RELAY + MARKETPLACE)) return null;
        return trackingName.substring(RELAY.length());
    }

    /**
     * Why [query] isn't sent, for the log: it's one of {@link #ADS_ONLY_QUERIES} or {@link
     * #LISTING_ADS_QUERIES}. Null for a query that goes out.
     */
    @Nullable
    static String heldBack(String query) {
        for (String ads : ADS_ONLY_QUERIES) {
            if (ads.equals(query)) return "one of the feed's ads-only queries";
        }
        for (String ads : LISTING_ADS_QUERIES) {
            if (ads.equals(query)) return "one of the listing page's ad rows";
        }
        return null;
    }

    /**
     * The request data's tracking name, read the way the Networking module reads it. Null when the
     * data holds none or isn't React Native's map.
     */
    @Nullable
    static String trackingName(Object data) throws ReflectiveOperationException {
        Method read = getString();
        if (read == null || !read.getDeclaringClass().isInstance(data)) return null;
        Object name = read.invoke(data, TRACKING_NAME);
        return name instanceof String ? (String) name : null;
    }

    @Nullable
    private static Method getString() {
        if (lookedUp) return getString;
        synchronized (MarketplaceAdFilter.class) {
            if (lookedUp) return getString;
            try {
                getString = Class.forName(READABLE_MAP).getMethod("getString", String.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
                HookStatus.missingMember(FamilyNames.SPONSORED_MARKETPLACE, "method", READABLE_MAP, "getString");
            }
            lookedUp = true;
            return getString;
        }
    }

    /** A body to send and a note on what was done, for the log. */
    static final class Rewrite {
        final String body;
        final String note;

        Rewrite(String body, String note) {
            this.body = body;
            this.note = note;
        }
    }

    /**
     * [body] with each of {@link #SKIP_VARIABLES} its variables name at their top level as false or
     * null set to true, when [on]. The same string when nothing changes, and every byte outside
     * those values as it was.
     */
    static Rewrite skipAds(String body, boolean on) {
        if (body.length() > MAX_BODY_CHARS) return new Rewrite(body, "went out unread, its body is too long.");
        int[] value = variablesParameter(body);
        if (value == null) return new Rewrite(body, "went out as it was, it names no variables.");
        Decoded decoded = Decoded.of(body, value[0], value[1]);
        if (decoded == null) return new Rewrite(body, "went out as it was, its variables can't be read.");
        List<Member> members = Json.members(decoded.bytes, decoded.length);
        if (members == null) return new Rewrite(body, "went out as it was, its variables aren't a JSON object.");

        List<String> found = new ArrayList<>();
        List<Member> toSet = new ArrayList<>();
        for (String name : SKIP_VARIABLES) {
            Member member = null;
            for (Member candidate : members) {
                if (!candidate.named(decoded.bytes, name)) continue;
                if (member != null) return new Rewrite(body, "went out as it was, it names " + name + " twice.");
                member = candidate;
            }
            if (member == null) continue;
            String was = member.literal(decoded.bytes);
            found.add(name + " " + (was == null ? "not a boolean" : was));
            if ("false".equals(was) || "null".equals(was)) toSet.add(member);
        }
        if (found.isEmpty()) return new Rewrite(body, "went out as it was, it names neither ad variable.");
        String seen = String.join(", ", found);
        if (!on) return new Rewrite(body, "went out as it was, the switch is off (" + seen + ").");
        if (toSet.isEmpty()) return new Rewrite(body, "went out as it was (" + seen + ").");

        // Back to front, so each value's place in the body stays where it was found.
        StringBuilder edited = new StringBuilder(body);
        for (int i = toSet.size() - 1; i >= 0; i--) {
            Member member = toSet.get(i);
            edited.replace(decoded.from[member.valueStart], decoded.endOf(member.valueEnd), "true");
        }
        return new Rewrite(edited.toString(), "asked to skip its ads (" + seen + ", now true).");
    }

    /**
     * Where the value of the one {@code variables} parameter of a form-encoded body starts and
     * ends. Null when the body names none, or names it more than once.
     */
    @Nullable
    static int[] variablesParameter(String body) {
        int[] found = null;
        int at = 0;
        while (at <= body.length()) {
            int end = body.indexOf('&', at);
            if (end < 0) end = body.length();
            int equals = at + VARIABLES.length();
            if (equals < end && body.charAt(equals) == '=' && body.startsWith(VARIABLES, at)) {
                if (found != null) return null;
                found = new int[] {equals + 1, end};
            }
            at = end + 1;
        }
        return found;
    }

    /**
     * A form value's bytes after percent-decoding, with where each byte starts in the body. JSON's
     * own characters are all ASCII and a UTF-8 sequence never holds an ASCII byte, so the JSON can be
     * read from the bytes as they are.
     */
    static final class Decoded {
        final byte[] bytes;
        final int[] from;
        final int length;
        /** Where the value ends in the body. */
        final int end;

        private Decoded(byte[] bytes, int[] from, int length, int end) {
            this.bytes = bytes;
            this.from = from;
            this.length = length;
            this.end = end;
        }

        /** Where the byte at [index] starts in the body, or the value's end for the index past the last. */
        int endOf(int index) {
            return index >= length ? end : from[index];
        }

        /** The value between [start] and [end] of [body], or null when it's not well formed. */
        @Nullable
        static Decoded of(String body, int start, int end) {
            byte[] bytes = new byte[end - start];
            int[] from = new int[end - start];
            int length = 0;
            for (int i = start; i < end; ) {
                char c = body.charAt(i);
                if (c == '%') {
                    if (i + 2 >= end) return null;
                    int high = Character.digit(body.charAt(i + 1), 16);
                    int low = Character.digit(body.charAt(i + 2), 16);
                    if (high < 0 || low < 0) return null;
                    bytes[length] = (byte) (high << 4 | low);
                    from[length++] = i;
                    i += 3;
                } else if (c == '+') {
                    bytes[length] = ' ';
                    from[length++] = i++;
                } else if (c < 0x80) {
                    bytes[length] = (byte) c;
                    from[length++] = i++;
                } else {
                    // A form body is ASCII; anything else wasn't encoded the way this reads it.
                    return null;
                }
            }
            return new Decoded(bytes, from, length, end);
        }
    }

    /** A top-level member of a JSON object: where its name's text and its value lie in the bytes. */
    static final class Member {
        final int nameStart;
        final int nameEnd;
        final int valueStart;
        final int valueEnd;

        Member(int nameStart, int nameEnd, int valueStart, int valueEnd) {
            this.nameStart = nameStart;
            this.nameEnd = nameEnd;
            this.valueStart = valueStart;
            this.valueEnd = valueEnd;
        }

        /** Whether the member's name is exactly [name], written without escapes. */
        boolean named(byte[] bytes, String name) {
            if (nameEnd - nameStart != name.length()) return false;
            for (int i = 0; i < name.length(); i++) {
                if (bytes[nameStart + i] != name.charAt(i)) return false;
            }
            return true;
        }

        /** The value when it's true, false or null, else null. */
        @Nullable
        String literal(byte[] bytes) {
            for (String literal : new String[] {"true", "false", "null"}) {
                if (valueEnd - valueStart != literal.length()) continue;
                boolean same = true;
                for (int i = 0; i < literal.length() && same; i++) {
                    same = bytes[valueStart + i] == literal.charAt(i);
                }
                if (same) return literal;
            }
            return null;
        }
    }

    /** Just enough of a JSON reader to find an object's top-level members. */
    static final class Json {
        private Json() {
        }

        /** The members of the object [bytes] holds, or null when they don't hold exactly one object. */
        @Nullable
        static List<Member> members(byte[] bytes, int length) {
            List<Member> members = new ArrayList<>();
            int i = space(bytes, 0, length);
            if (i >= length || bytes[i] != '{') return null;
            i = space(bytes, i + 1, length);
            if (i < length && bytes[i] == '}') return space(bytes, i + 1, length) == length ? members : null;
            while (true) {
                if (i >= length || bytes[i] != '"') return null;
                int nameStart = i + 1;
                int nameEnd = stringEnd(bytes, i, length);
                if (nameEnd < 0) return null;
                i = space(bytes, nameEnd + 1, length);
                if (i >= length || bytes[i] != ':') return null;
                int valueStart = space(bytes, i + 1, length);
                int valueEnd = valueEnd(bytes, valueStart, length);
                if (valueEnd < 0) return null;
                members.add(new Member(nameStart, nameEnd, valueStart, valueEnd));
                i = space(bytes, valueEnd, length);
                if (i >= length) return null;
                if (bytes[i] == ',') {
                    i = space(bytes, i + 1, length);
                    continue;
                }
                if (bytes[i] == '}') return space(bytes, i + 1, length) == length ? members : null;
                return null;
            }
        }

        private static int space(byte[] bytes, int i, int length) {
            while (i < length && (bytes[i] == ' ' || bytes[i] == '\t' || bytes[i] == '\n' || bytes[i] == '\r')) i++;
            return i;
        }

        /** The index of the quote closing the string whose opening quote is at [open], or -1. */
        private static int stringEnd(byte[] bytes, int open, int length) {
            for (int i = open + 1; i < length; i++) {
                if (bytes[i] == '\\') i++;
                else if (bytes[i] == '"') return i;
            }
            return -1;
        }

        /** Where the value starting at [start] ends, exclusive, or -1. */
        private static int valueEnd(byte[] bytes, int start, int length) {
            if (start >= length) return -1;
            byte first = bytes[start];
            if (first == '"') {
                int close = stringEnd(bytes, start, length);
                return close < 0 ? -1 : close + 1;
            }
            if (first == '{' || first == '[') {
                int depth = 0;
                for (int i = start; i < length; i++) {
                    byte b = bytes[i];
                    if (b == '"') {
                        i = stringEnd(bytes, i, length);
                        if (i < 0) return -1;
                    } else if (b == '{' || b == '[') {
                        depth++;
                    } else if (b == '}' || b == ']') {
                        if (--depth == 0) return i + 1;
                    }
                }
                return -1;
            }
            int i = start;
            while (i < length && bytes[i] != ',' && bytes[i] != '}' && bytes[i] != ']' && bytes[i] != ' '
                    && bytes[i] != '\t' && bytes[i] != '\n' && bytes[i] != '\r') {
                i++;
            }
            return i > start ? i : -1;
        }
    }

    /** One line per request for the first {@link #LOGGED_ONE_BY_ONE}, then one per {@link #SUMMED_UP_BY}. */
    private static void log(String line) {
        int count = lines.incrementAndGet();
        if (count <= LOGGED_ONE_BY_ONE) {
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE, () -> PREFIX + line);
        } else if (count % SUMMED_UP_BY == 0) {
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> PREFIX + count + " Marketplace requests so far. The last one: " + line);
        }
    }

    /** The switch. Off, unreadable, or asked before the settings are ready, requests go out as they were. */
    private static boolean switchedOn() {
        try {
            return Utils.settingsReady() && Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPONSORED_MARKETPLACE, "switch read", failure);
            return false;
        }
    }

    /** Forgets the line counts and the answers being read, as a new process would. */
    static void forget() {
        lines.set(0);
        MarketplaceSearchAds.forget();
        MarketplaceSearchSyntax.forget();
        MarketplaceResponseDiagnostics.forget();
        synchronized (answers) {
            answers.clear();
        }
    }
}
