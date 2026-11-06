/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * Debug-only schema evidence at the existing Marketplace Tigon boundary. The observer never holds
 * or changes text sent to JavaScript. It reports types, with query/model/field names drawn only from
 * fixed vocabulary; arbitrary keys, model names and all scalar values remain private. Known names
 * describe fields actually present, not a promise of fulfillment or distance semantics.
 */
final class MarketplaceResponseDiagnostics {
    // One search page on the S22 (2026-10-20) was a single payload past 256 KB, so its capture
    // stopped before reading anything. Debug-only, and still under the whole-response budget.
    static final int MAX_PAYLOAD_CHARS = 1024 * 1024;
    static final int MAX_RESPONSE_CHARS = 2 * 1024 * 1024;
    static final int MAX_REQUESTS = 16;
    // A search page of listings walks past 256 objects and 32 shapes, so the S22's
    // 2026-10-20 search capture stopped early and said incomplete. Once the seller and price
    // children had names, Home filled 96 shapes too, and so did the search pages between them.
    static final int MAX_SHAPES = 192;
    // 584's search pages of 27 and 48 listings (emulator, 2026-10-23) carry ad story trees that
    // nest past level 14 under node.story, so both pages said incomplete. Depth stays under the
    // parser's MAX_PARSE_DEPTH, and each limit names itself in the summary.
    static final int MAX_VISITS = 8192;
    static final int MAX_LEVEL = 32;
    private static final String PREFIX = "Marketplace shape: ";
    // The last three are a listing's own page, from Facebook 583's code: the listing route times
    // MarketplacePDPSurfaceQuery (react_native_routes.json), and the other two are in its dex.
    private static final String[] QUERIES = {
            "MarketplaceHomeFeedAdsThemedQuery",
            "MarketplaceProductDetailsPageRelatedAdsDetailQuery",
            "MarketplacePDPBoostedListingAdsQuery",
            "MarketplacePDPPersonalizedAdsQuery",
            "MarketplaceHomeFeedThemedAdsFetch",
            "MarketplaceHomeFeedQueryRendererQuery",
            "MarketplaceHomeQuery",
            "MarketplacePlainHomeAppQuery",
            "MarketplaceSearchApp_MarketplaceSearchFeedHeadQuery",
            "MarketplaceSearchFeedPaginationQuery",
            "MarketplacePDPSurfaceQuery",
            "MarketplaceProductDetailsPageAppQuery",
            "MarketplaceListingAvailabilityQuery",
    };
    // The last group is what a listing's seller may be. The S22's 2026-10-20 capture had every
    // seller object unrecognized, so whether a page or business sells it couldn't be told.
    private static final String[] MODELS = {
            "MarketplaceFeedGeneralListingObject", "MarketplaceFeedListingStoryObject",
            "MarketplaceFeedAdStory", "GroupCommerceProductItem", "MarketplaceSearch",
            "MarketplaceSearchFeedStoriesEdge",
            "User", "Page", "Group", "Business", "MarketplaceSeller",
    };
    // Structural fields used by the existing filter; the selection variables come from the fixture
    // query configs, including PlainHome's radius. Shipping names occur in stock app serializers,
    // without proven semantics on returned results. The listing names come from Facebook 583's own
    // code: its GraphQL model reader for product items (prices, title, photos, subtitles), the
    // composer's listing serializer (delivery) and Marketplace's React Native route params (listing
    // price, seller id, local pickup). Naming one says it was present, not what it means. The last
    // group is candidates from Facebook's web Marketplace listings (price amount, place, seller,
    // sold or pending state); the S22's 2026-10-20 capture saw id, location_text,
    // marketplace_listing_seller and marketplace_listing_category_id among them. The names after
    // those are guesses for what Android's listing_price (two strings) and location_text (one)
    // hold, since none of the web names arrived inside them. The final line is the web listing
    // page's description, age, map point and inventory kind: a listing's own page on the S22 came
    // back with 35 unnamed strings and 19 unnamed objects, and 583's dex names none of these.
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
            "__typename", "data", "edges", "node", "listing", "story", "sponsored_data", "ad_id",
            "marketplace_search", "feed_units", "location", "is_shipping_offered", "shipping_offer_type",
            "localOnly", "shippedOnly", "radius", "search_radius_in_meters",
            "listing_price", "formatted_price", "strikethrough_price", "current_product_price",
            "product_item_price", "product_sale_price", "is_on_sale", "marketplace_listing_title",
            "description", "custom_sub_titles", "primary_listing_photo", "listing_photos",
            "delivery_types", "pickup_delivery_info", "local_pickup", "seller_id",
            "id", "amount", "formatted_amount", "reverse_geocode", "city", "state", "city_page",
            "display_name", "location_text", "marketplace_listing_seller", "name", "is_sold", "is_pending",
            "is_hidden", "is_live", "custom_title", "marketplace_listing_category_id",
            "text", "currency", "amount_with_offset", "amount_with_offset_in_currency",
            "formatted_amount_zeros_stripped", "offset",
            "redacted_description", "creation_time", "latitude", "longitude", "listing_inventory_type"));
    // A listing's own page names every key it has (#303): the S22's capture of it came back mostly
    // unnamed, and the listing's time is one of those. Keys are Facebook's schema names, never
    // values, and only a lowercase snake_case key of up to 48 characters is named.
    private static final Set<String> NAMED_KEY_QUERIES = new HashSet<>(Arrays.asList(
            "MarketplacePDPSurfaceQuery", "MarketplaceProductDetailsPageAppQuery"));
    private static final int MAX_KEY_CHARS = 48;
    private static final Map<Object, Observer> observers = new WeakHashMap<>();
    private static final Map<String, Set<String>> shapes = new HashMap<>();
    private static final Set<String> summaryBudgetNotices = new HashSet<>();
    /**
     * The diagnostic clear the logged shapes belong to. A clear empties the report, so every shape
     * logs again after one: a response Facebook loaded before the clear would otherwise leave a
     * report taken after it with no line for it.
     */
    private static long shapesGeneration = -1;

    private MarketplaceResponseDiagnostics() {
    }

    private static boolean enabled() {
        try {
            return Utils.settingsReady() && BaseSettings.DEBUG.get();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    @Nullable
    private static String query(@Nullable String trackingName) {
        for (String known : QUERIES) {
            if ((MarketplaceAdFilter.RELAY + known).equals(trackingName)) return known;
        }
        return null;
    }

    static void request(String query, String body) {
        if (query(MarketplaceAdFilter.RELAY + query) == null || !enabled()) return;
        try {
            if (body.length() > MarketplaceAdFilter.MAX_BODY_CHARS) return;
            int[] range = MarketplaceAdFilter.variablesParameter(body);
            if (range == null) return;
            MarketplaceAdFilter.Decoded decoded = MarketplaceAdFilter.Decoded.of(body, range[0], range[1]);
            if (decoded == null) return;
            String text = new String(decoded.bytes, 0, decoded.length, StandardCharsets.UTF_8);
            MarketplaceSearchAds.Value variables = MarketplaceSearchAds.Parser.whole(text);
            if (variables.kind == '{') record(query, "request variables " + fields(query, text, variables));
        } catch (Throwable unreadable) {
            record(query, "unreadable request variables");
        }
    }

    static void piece(String piece, String trackingName, Object request) {
        String query = query(trackingName);
        if (query == null) return;
        if (!enabled()) {
            synchronized (observers) { observers.remove(request); }
            return;
        }
        try {
            Observer observer;
            synchronized (observers) {
                observer = observers.get(request);
                if (observer == null) {
                    if (observers.size() >= MAX_REQUESTS) {
                        record(query, "observer request budget");
                        return;
                    }
                    observer = new Observer(query);
                    observers.put(request, observer);
                }
            }
            observer.read(piece);
        } catch (Throwable unreadable) {
            record(query, "observer unavailable");
        }
    }

    static void whole(String text, String trackingName) {
        String query = query(trackingName);
        if (query == null || !enabled()) return;
        try {
            Observer observer = new Observer(query);
            observer.read(text);
            observer.end();
        } catch (Throwable unreadable) {
            record(query, "observer unavailable");
        }
    }

    static void end(Object request) {
        try {
            Observer observer;
            synchronized (observers) { observer = observers.remove(request); }
            if (observer != null && enabled()) observer.end();
        } catch (Throwable unreadable) {
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, "MarketplaceResponseDiagnostics",
                    () -> PREFIX + "observer end unavailable");
        }
    }

    private static final class Observer {
        private final String query;
        private final StringBuilder waiting = new StringBuilder();
        private int characters;
        private int depth;
        private boolean inString;
        private boolean escaped;
        private boolean stopped;
        /** Why the capture is incomplete, the first reason only, or null while it's complete. */
        @Nullable private String gap;
        private int payloads;
        private int unrecognizedModels;
        private final Map<String, Integer> models = new TreeMap<>();

        Observer(String query) { this.query = query; }

        synchronized void read(String piece) {
            if (stopped) return;
            if (piece.length() > MAX_RESPONSE_CHARS - characters) { stop("response budget"); return; }
            characters += piece.length();
            for (int i = 0; i < piece.length(); i++) {
                char c = piece.charAt(i);
                if (depth == 0) {
                    if (c != '{' && c != '[') continue;
                    depth = 1;
                } else if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{' || c == '[') {
                    if (++depth > MarketplaceSearchAds.MAX_PARSE_DEPTH) { stop("depth budget"); return; }
                } else if (c == '}' || c == ']') {
                    depth--;
                }
                if (waiting.length() >= MAX_PAYLOAD_CHARS) { stop("payload budget"); return; }
                waiting.append(c);
                if (depth != 0) continue;
                String text = waiting.toString();
                waiting.setLength(0);
                if (waiting.capacity() > 65_536) waiting.trimToSize();
                try {
                    inspect(text, MarketplaceSearchAds.Parser.whole(text), "response", 0, new int[] {MAX_VISITS});
                    payloads++;
                } catch (Exception unreadable) {
                    stop("unreadable payload");
                    return;
                }
            }
        }

        private void inspect(String text, MarketplaceSearchAds.Value value, String path, int level, int[] visits) {
            if (--visits[0] < 0 || level > MAX_LEVEL) {
                String reason = visits[0] < 0 ? "shape traversal budget" : "shape depth budget";
                missing(reason);
                record(query, reason);
                return;
            }
            if (value.kind == '{') {
                String model = model(text, value);
                if (model.equals("unrecognized")) unrecognizedModels++;
                else if (!model.equals("none")) models.put(model, models.getOrDefault(model, 0) + 1);
                // The walk goes on, so the model counts stay whole; only this shape's line is dropped.
                if (!record(query, path + " model=" + model + " " + fields(query, text, value))) missing("shape budget");
                for (int i = 0; i < value.items.size(); i++) {
                    MarketplaceSearchAds.Value child = value.items.get(i);
                    if (child.kind != '{' && child.kind != '[') continue;
                    inspect(text, child, path + "." + field(query, text, value.names.get(i)), level + 1, visits);
                }
            } else if (value.kind == '[') {
                for (MarketplaceSearchAds.Value child : value.items) inspect(text, child, path + "[]", level + 1, visits);
            }
        }

        synchronized void end() {
            if (!stopped && waiting.length() > 0) stop("unfinished payload");
            Set<String> counts = new TreeSet<>();
            for (Map.Entry<String, Integer> model : models.entrySet()) counts.add(model.getKey() + ":" + model.getValue());
            record(query, "response summary payloads=" + payloads + " capture=" + (gap == null ? "complete" : "incomplete(" + gap + ")")
                    + " models={" + String.join(",", counts) + "} unrecognized_models=" + unrecognizedModels, true);
        }

        private void missing(String reason) {
            if (gap == null) gap = reason;
        }

        private void stop(String reason) {
            stopped = true;
            missing(reason);
            waiting.setLength(0);
            waiting.trimToSize();
            record(query, reason + "; observer stopped");
        }
    }

    private static String fields(String query, String text, MarketplaceSearchAds.Value object) {
        Set<String> fields = new TreeSet<>();
        Map<String, Integer> unknown = new TreeMap<>();
        for (int i = 0; i < object.items.size(); i++) {
            String field = field(query, text, object.names.get(i));
            String shape = type(text, object.items.get(i));
            if (field.equals("other")) unknown.put(shape, unknown.getOrDefault(shape, 0) + 1);
            else fields.add(field + ":" + shape);
        }
        for (Map.Entry<String, Integer> item : unknown.entrySet()) fields.add("other_" + item.getKey() + ":" + item.getValue());
        return "fields={" + String.join(",", fields) + "}";
    }

    private static String field(String query, String text, int[] range) {
        for (String known : FIELDS) {
            if (range[1] - range[0] == known.length() && text.startsWith(known, range[0])) return known;
        }
        return NAMED_KEY_QUERIES.contains(query) && schemaName(text, range[0], range[1])
                ? text.substring(range[0], range[1]) : "other";
    }

    private static boolean schemaName(String text, int start, int end) {
        if (end - start < 1 || end - start > MAX_KEY_CHARS) return false;
        char first = text.charAt(start);
        if (first < 'a' || first > 'z') return false;
        for (int i = start + 1; i < end; i++) {
            char c = text.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return true;
    }

    private static String model(String text, MarketplaceSearchAds.Value object) {
        MarketplaceSearchAds.Value type = object.member(text, "__typename");
        if (type == null) return "none";
        if (type.kind == '"') {
            for (String known : MODELS) {
                if (type.end - type.start == known.length() + 2 && text.startsWith(known, type.start + 1)) return known;
            }
        }
        return "unrecognized";
    }

    private static String type(String text, MarketplaceSearchAds.Value value) {
        if (value.kind == '{') return "object";
        if (value.kind == '[') return "array";
        if (value.kind == '"') return "string";
        int length = value.end - value.start;
        if (length == 4 && text.startsWith("null", value.start)) return "null";
        if ((length == 4 && text.startsWith("true", value.start)) || (length == 5 && text.startsWith("false", value.start))) return "boolean";
        char first = text.charAt(value.start);
        return first == '-' || first >= '0' && first <= '9' ? "number" : "literal";
    }

    private static boolean record(String query, String shape) {
        return record(query, shape, false);
    }

    private static boolean record(String query, String shape, boolean summary) {
        boolean retained = true;
        synchronized (shapes) {
            long current = HookStatus.generation();
            if (shapesGeneration != current) {
                shapesGeneration = current;
                shapes.clear();
                summaryBudgetNotices.clear();
            }
            String key = query + (summary ? " summary" : " shapes");
            Set<String> seen = shapes.get(key);
            if (seen == null) { seen = new HashSet<>(); shapes.put(key, seen); }
            // A duplicate is already retained, even when this query has filled its budget.
            if (seen.contains(shape)) return true;
            if (seen.size() >= (summary ? 8 : MAX_SHAPES)) {
                if (!summary || !summaryBudgetNotices.add(query)) return false;
                retained = false;
            } else {
                seen.add(shape);
            }
        }
        String message = retained ? shape : "response summary omitted: summary budget";
        Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, "MarketplaceResponseDiagnostics",
                () -> PREFIX + query + " " + message);
        return retained;
    }

    static void forget() {
        synchronized (observers) { observers.clear(); }
        synchronized (shapes) { shapes.clear(); summaryBudgetNotices.clear(); }
    }
}
