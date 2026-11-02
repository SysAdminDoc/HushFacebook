/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import android.icu.text.RelativeDateTimeFormatter;

import androidx.annotation.Nullable;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;

/**
 * When a Marketplace listing went up, on the listing's own page (#303). Facebook only says so for a
 * new listing ("Just listed · 19 hours ago"), but the page's answer always carries the
 * listing's {@code creation_time}. Each listing in the answer that has one gets it written after its
 * place, in its {@code location_text}: "Moncton, NB · Listed 3 weeks ago (Oct 12, 2026)". A listing
 * the answer names twice, once without its time, gets the time it has under the same id.
 *
 * <p>Read from 585's MarketplacePDPSurfaceQuery on the S22, 2026-10-31:
 * data.viewer.marketplace_pdp.listing.primary_mp_ent has creation_time (seconds) and location_text
 * {text}, and data.listing has location_text and the same id. Nothing else in the answer changes.
 */
public final class MarketplaceListingAge {
    /** The queries a listing's own page answers with. */
    static final String[] QUERIES = {
            "MarketplacePDPSurfaceQuery",
            "MarketplaceProductDetailsPageAppQuery",
    };

    static final String CREATION_TIME = "creation_time";
    static final String LOCATION_TEXT = "location_text";
    static final String TEXT = "text";
    static final String ID = "id";

    /** What goes between the place and when it was listed, as Facebook writes its own. */
    static final String DOT = " " + (char) 0xB7 + " ";

    /** A time before this (2004) or this far past now isn't a listing's. */
    private static final long EARLIEST_SECONDS = 1_072_915_200L;
    private static final long SLACK_SECONDS = 24 * 60 * 60;

    private MarketplaceListingAge() {
    }

    /** Whether [trackingName] is one of {@link #QUERIES}. */
    static boolean listingPage(@Nullable String trackingName) {
        String query = MarketplaceAdFilter.marketplaceQuery(trackingName);
        if (query == null) return false;
        for (String page : QUERIES) {
            if (page.equals(query)) return true;
        }
        return false;
    }

    /** One listing page's answer on its way to JavaScript, read as it comes in. */
    static final class Answer {
        private final long now;
        private final StringBuilder waiting = new StringBuilder();
        private int characters;
        private int depth;
        private boolean inString;
        private boolean escaped;
        /** False once the answer grew too long to read: the rest goes on as it comes. */
        private boolean reading = true;
        /** Each listing's time by its id, for a later payload that names it again. */
        private final Map<String, Long> times = new HashMap<>();

        Answer(long now) {
            this.now = now;
        }

        /**
         * [piece], with every payload it finishes read and its listings dated. An unfinished
         * payload's start waits for the piece that finishes it. The same string when nothing changed.
         */
        String read(String piece) {
            if (!reading) return piece;
            StringBuilder out = new StringBuilder(piece.length());
            boolean held = waiting.length() > 0;
            int from = 0;
            for (int i = 0; i < piece.length(); i++) {
                if (++characters > MarketplaceSearchAds.MAX_RESPONSE_CHARS) {
                    reading = false;
                    out.append(waiting).append(piece, from, piece.length());
                    waiting.setLength(0);
                    return out.toString();
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
                    out.append(payload(text));
                    from = i + 1;
                }
            }
            if (depth > 0) waiting.append(piece, from, piece.length());
            else out.append(piece, from, piece.length());
            String result = out.toString();
            return result.equals(piece) ? piece : result;
        }

        /** What still waits for a payload to finish, as it came, or null when nothing does. */
        @Nullable
        String end() {
            reading = false;
            String rest = waiting.toString();
            waiting.setLength(0);
            return rest.isEmpty() ? null : rest;
        }

        /** [text], one payload, with each listing's place followed by when it was listed. */
        private String payload(String text) {
            MarketplaceSearchAds.Value root;
            try {
                root = MarketplaceSearchAds.Parser.whole(text);
            } catch (Exception unreadable) {
                Logger.printDebug(() -> "Listing date: a payload didn't read (" + unreadable.getClass().getSimpleName()
                        + "), passed on as it came");
                return text;
            }
            List<MarketplaceSearchAds.Value> listings = new ArrayList<>();
            collect(text, root, listings);
            for (MarketplaceSearchAds.Value listing : listings) {
                Long seconds = seconds(text, listing.member(text, CREATION_TIME));
                String id = string(text, listing.member(text, ID));
                if (seconds != null && id != null) times.put(id, seconds);
            }
            int untimed = 0, unwritten = 0, dated = 0;
            for (MarketplaceSearchAds.Value listing : listings) {
                Long seconds = seconds(text, listing.member(text, CREATION_TIME));
                if (seconds == null) {
                    String id = string(text, listing.member(text, ID));
                    if (id != null) seconds = times.get(id);
                }
                if (seconds == null) {
                    untimed++;
                    continue;
                }
                MarketplaceSearchAds.Value place = listing.member(text, LOCATION_TEXT);
                MarketplaceSearchAds.Value line = place == null || place.kind != '{' ? null : place.member(text, TEXT);
                String label = label(seconds, now);
                if (line == null || line.kind != '"' || label == null) {
                    unwritten++;
                    continue;
                }
                String was = text.substring(line.start + 1, line.end - 1);
                line.replace('"' + (was.isEmpty() ? "" : was + DOT) + escape(label) + '"');
                dated++;
            }
            if (!listings.isEmpty()) {
                int noTime = untimed, noLine = unwritten, wrote = dated;
                Logger.printDebug(() -> "Listing date: dated " + wrote + " place lines in a payload, "
                        + noTime + " with no time, " + noLine + " with no line or a time out of range");
            }
            if (!root.changed) return text;
            StringBuilder out = new StringBuilder(text.length() + 64);
            root.write(text, out);
            return out.toString();
        }
    }

    /** Every object under [value] with a location_text object, found through objects and lists. */
    private static void collect(String s, MarketplaceSearchAds.Value value, List<MarketplaceSearchAds.Value> into) {
        if (value.kind == '{') {
            MarketplaceSearchAds.Value place = value.member(s, LOCATION_TEXT);
            if (place != null && place.kind == '{') into.add(value);
        } else if (value.kind != '[') {
            return;
        }
        for (MarketplaceSearchAds.Value child : value.items) collect(s, child, into);
    }

    /** A listing's time in seconds from [value], a whole number, or null when it isn't a plausible one. */
    @Nullable
    private static Long seconds(String s, @Nullable MarketplaceSearchAds.Value value) {
        if (value == null || value.kind != '0') return null;
        try {
            return Long.parseLong(s.substring(value.start, value.end));
        } catch (NumberFormatException notWhole) {
            return null;
        }
    }

    /** The text of [value] when it's a string written without escapes, else null. */
    @Nullable
    private static String string(String s, @Nullable MarketplaceSearchAds.Value value) {
        if (value == null || value.kind != '"') return null;
        String text = s.substring(value.start + 1, value.end - 1);
        return text.indexOf('\\') >= 0 ? null : text;
    }

    /**
     * "Listed 3 weeks ago (Oct 12, 2026)" for a listing put up at [seconds], seen at [nowMillis], in
     * Facebook's language. Null for a time that can't be a listing's.
     */
    @Nullable
    static String label(long seconds, long nowMillis) {
        long nowSeconds = nowMillis / 1000;
        if (seconds < EARLIEST_SECONDS || seconds > nowSeconds + SLACK_SECONDS) return null;
        Locale locale = L10n.locale();
        String date = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(new Date(seconds * 1000));
        return L10n.f("Listed %1$s (%2$s)", ago(Math.max(0, nowSeconds - seconds), locale), date);
    }

    /**
     * "3 weeks ago" for an age of [age] seconds, in the largest whole unit it reaches. A reel's post
     * date reads the same way ({@link app.morphe.extension.facebook.reels.ReelDeclutter#postDateText}).
     */
    public static String ago(long age, Locale locale) {
        RelativeDateTimeFormatter format = RelativeDateTimeFormatter.getInstance(locale);
        long minutes = age / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (hours < 1) return format.format(Math.max(1, minutes), RelativeDateTimeFormatter.Direction.LAST,
                RelativeDateTimeFormatter.RelativeUnit.MINUTES);
        if (days < 1) return format.format(hours, RelativeDateTimeFormatter.Direction.LAST,
                RelativeDateTimeFormatter.RelativeUnit.HOURS);
        if (days < 7) return format.format(days, RelativeDateTimeFormatter.Direction.LAST,
                RelativeDateTimeFormatter.RelativeUnit.DAYS);
        if (days < 30) return format.format(days / 7, RelativeDateTimeFormatter.Direction.LAST,
                RelativeDateTimeFormatter.RelativeUnit.WEEKS);
        if (days < 365) return format.format(days / 30, RelativeDateTimeFormatter.Direction.LAST,
                RelativeDateTimeFormatter.RelativeUnit.MONTHS);
        return format.format(days / 365, RelativeDateTimeFormatter.Direction.LAST,
                RelativeDateTimeFormatter.RelativeUnit.YEARS);
    }

    /** [text] as the inside of a JSON string. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }
}
