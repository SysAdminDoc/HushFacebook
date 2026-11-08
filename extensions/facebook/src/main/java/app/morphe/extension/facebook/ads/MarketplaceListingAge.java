/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.icu.text.RelativeDateTimeFormatter;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.UpdateAppearance;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.theme.ReactColours;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * When a Marketplace listing went up, on the listing's own page (#303). Facebook only says so for a
 * new listing ("Just listed · 19 hours ago"), but the page's answer always carries the
 * listing's {@code creation_time}. Each listing in the answer that has one gets it written after its
 * place, in its {@code location_text}: "Moncton, NB · Listed 3 weeks ago (Oct 12, 2026)". A listing
 * the answer names twice, once without its time, gets the time it has under the same id.
 *
 * <p>The same date also goes on a line of its own under the listing's price, where Facebook writes
 * "Just listed" for a day-old one ({@link #priceLine}). JavaScript writes the price from the
 * answer's listing_price, so the answer's amount and title say which text React builds is the price.
 * A vehicle gets no such line: Facebook already writes "Listed 3 weeks ago" under its price. Once
 * the date sits under the price, the place line React builds after it goes back to Facebook's own
 * text, so the date shows once. A title two listings dated differently share gets no line under its
 * price, and its place line keeps the date. So does a title built while a listing page's request
 * waits for its answer, unless that request names the listing the title was seen with: Facebook
 * draws a page from what it kept before the answer comes, and the title may be another listing's
 * of the same name. A struck-through old price drawn beside the price gets an empty line
 * of the same height, so the row Facebook centres them in keeps the two prices level.
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

    static final String LISTING_PRICE = "listing_price";
    /** The price in its currency's smallest unit, a string of digits: "15000" for $150. */
    static final String AMOUNT = "amount_with_offset";
    static final String CATEGORY = "marketplace_listing_category_id";
    /**
     * What Facebook's fields only a vehicle fills start with (vehicle_odometer_data,
     * vehicle_make_display_name, vehicle_transmission_type). They're null on anything else.
     */
    static final String VEHICLE = "vehicle_";

    /** Listings whose page answered lately, by title, oldest first. Guarded by itself. */
    private static final LinkedHashMap<String, Priced> PRICED = new LinkedHashMap<>();
    static final int MAX_PRICED = 16;
    private static volatile boolean anyPriced;
    /** The listing whose title React built last, and how many texts it built since. Guarded by PRICED. */
    @Nullable
    private static Priced titled;
    private static int since;
    /** The dates put under a price lately, oldest first, for their place lines. Guarded by PRICED. */
    private static final LinkedHashSet<String> PLACED = new LinkedHashSet<>();
    /**
     * How many more texts may be the old price drawn beside the price that got a date, and the date
     * line's size for the old price's empty line. Guarded by PRICED.
     */
    private static int struckLeft;
    private static int struckSize;
    /** The variables of listing pages' requests whose answers aren't read yet, oldest first. Guarded by PRICED. */
    private static final ArrayDeque<Asked> ASKED = new ArrayDeque<>();
    static final int MAX_ASKED = 4;
    /** How long a request waits for its answer here before it's taken as one that never came. */
    static final long ASKED_MILLIS = 15_000;
    /** Each span class asked about, and whether it draws a line on its text ({@link #drawsLine}). */
    private static final Map<Class<?>, Boolean> DRAWS_LINE = new ConcurrentHashMap<>();
    /** What fills the old price's empty line: a space no layout drops. */
    static final String BLANK = String.valueOf((char) 0xA0);
    /** The price comes this many texts after the title at most: a listing page draws it right under. */
    static final int MAX_BETWEEN = 4;
    /** Longer texts are neither a title nor a price. */
    static final int MAX_TEXT_CHARS = 300;
    static final int MAX_PRICE_CHARS = 24;
    /** "CA$", "zł", "kr": a price's letters. More than this is a sentence that holds a number. */
    static final int MAX_PRICE_LETTERS = 3;
    /** The date line's size, Facebook's for the line under the price, never larger than the price. */
    static final float LINE_SP = 14;
    /** How strongly the date line shows the price's colour, so it reads as the quieter line. */
    static final float LINE_ALPHA = 0.7f;
    /** What the hook status counts when an old price gets its empty line, and a place line loses its date. */
    static final String OLD_PRICE_LEVEL = "old price lined up with the dated price";
    static final String PLACE_UNDATED = "place line left without the date the price has";

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
        /** Each listing's price and title by its id, which may come in another payload than its time. */
        private final Map<String, String> prices = new HashMap<>();
        private final Map<String, String> titles = new HashMap<>();
        /** The ids of listings that are vehicles, which get no line under their price. */
        private final Set<String> vehicles = new HashSet<>();
        /** Whether this answer has stopped its request from waiting ({@link #answerCame}). */
        private boolean answered;

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
            if (!answered) {
                answered = true;
                Set<String> ids = new HashSet<>(times.keySet());
                ids.addAll(titles.keySet());
                answerCame(ids);
            }
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
            vehicles(text, root, null, vehicles);
            String category = null;
            for (MarketplaceSearchAds.Value listing : listings) {
                Long seconds = seconds(text, listing.member(text, CREATION_TIME));
                String id = string(text, listing.member(text, ID));
                if (id == null) continue;
                if (seconds != null) times.put(id, seconds);
                MarketplaceSearchAds.Value price = listing.member(text, LISTING_PRICE);
                String amount = price == null || price.kind != '{' ? null : string(text, price.member(text, AMOUNT));
                if (amount != null && digits(amount)) prices.put(id, amount);
                String title = MarketplaceSearchSyntax.title(text, listing);
                if (title != null) titles.put(id, title);
                if (category == null) category = string(text, listing.member(text, CATEGORY));
            }
            Set<String> told = new HashSet<>();
            for (Map.Entry<String, String> price : prices.entrySet()) {
                String title = titles.get(price.getKey());
                if (title == null) continue;
                told.add(price.getKey());
                if (vehicles.contains(price.getKey())) {
                    unpriced(title);
                    continue;
                }
                Long seconds = times.get(price.getKey());
                String label = seconds == null ? null : label(seconds, now);
                if (label != null) priced(title, price.getKey(), price.getValue(), label);
            }
            // React draws this payload once it's handed on, so its listing's title is no longer
            // one built before the answer.
            if (!answered && !told.isEmpty()) {
                answered = true;
                answerCame(told);
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
                String kind = category == null ? "no category" : "category " + category;
                int cars = vehicles.size();
                Logger.printDebug(() -> "Listing date: dated " + wrote + " place lines in a payload, "
                        + noTime + " with no time, " + noLine + " with no line or a time out of range, "
                        + kind + ", " + cars + " vehicles");
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

    /**
     * Adds to [into] the id of each listing under [value] that fills a {@link #VEHICLE} field, on its
     * own object or one inside it. [owner] is the id of the object [value] sits in.
     */
    private static void vehicles(String s, MarketplaceSearchAds.Value value, @Nullable String owner, Set<String> into) {
        if (value.kind == '{') {
            String id = string(s, value.member(s, ID));
            if (id != null) owner = id;
            for (int i = 0; i < value.items.size(); i++) {
                MarketplaceSearchAds.Value item = value.items.get(i);
                if (owner != null && s.startsWith(VEHICLE, value.names.get(i)[0]) && filled(s, item)) into.add(owner);
                vehicles(s, item, owner, into);
            }
        } else if (value.kind == '[') {
            for (MarketplaceSearchAds.Value item : value.items) vehicles(s, item, owner, into);
        }
    }

    /**
     * Whether [value] says anything: not null, false or empty text, and a list or object only when
     * something in it does. Every listing carries vehicle_odometer_data as {unit:null,value:null}.
     */
    private static boolean filled(String s, MarketplaceSearchAds.Value value) {
        if (value.kind == '{' || value.kind == '[') {
            for (MarketplaceSearchAds.Value item : value.items) if (filled(s, item)) return true;
            return false;
        }
        if (value.kind == '"') return value.end - value.start > 2;
        return !s.startsWith("null", value.start) && !s.startsWith("false", value.start);
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

    /**
     * A listing's price as its page's answer gives it, and the line that goes under the price. No
     * line for a title two listings put up at different times share.
     */
    static final class Priced {
        final String id;
        final String amount;
        @Nullable
        final String label;

        Priced(String id, String amount, @Nullable String label) {
            this.id = id;
            this.amount = amount;
            this.label = label;
        }
    }

    /**
     * Remembers the listing [id] called [title], priced [amount], for the line under its price. A
     * seller who posts the same thing twice gives two listings one title and price, and a page
     * Facebook shows from what it kept sends no answer to say which one it is, so a title seen with
     * two listings dated differently gets no line under its price. Its place line keeps the date,
     * which comes with the listing itself (#303).
     */
    static void priced(String title, String id, String amount, String label) {
        synchronized (PRICED) {
            Priced was = PRICED.remove(title);
            boolean mixed = was != null && (was.label == null || !was.id.equals(id) && !was.label.equals(label));
            if (mixed) Logger.printDebug(() -> "Listing date: two listings share a title, so its price gets no date");
            PRICED.put(title, new Priced(id, amount, mixed ? null : label));
            if (PRICED.size() > MAX_PRICED) PRICED.remove(PRICED.keySet().iterator().next());
            anyPriced = true;
        }
    }

    /** Forgets the listing called [title], which turned out to be a vehicle. */
    static void unpriced(String title) {
        synchronized (PRICED) {
            Priced gone = PRICED.remove(title);
            if (gone != null && titled == gone) titled = null;
        }
    }

    /** A listing page's request that waits for its answer: its variables, and when it went out. */
    private static final class Asked {
        final String variables;
        final long at;

        Asked(String variables, long at) {
            this.variables = variables;
            this.at = at;
        }
    }

    /**
     * A listing page asked Facebook for its listing at [nowMillis], with [variables], the request's
     * variables as text (#303). Until its answer is read, a title built may be from a page Facebook
     * draws from what it kept, and a listing of the same name may have answered lately.
     */
    static void asked(String variables, long nowMillis) {
        synchronized (PRICED) {
            ASKED.addLast(new Asked(variables, nowMillis));
            while (ASKED.size() > MAX_ASKED) ASKED.removeFirst();
        }
    }

    /**
     * A listing page's answer naming the listings [ids] was read: the request that names one of them
     * waits no more, or, when none does, the oldest.
     */
    static void answerCame(Collection<String> ids) {
        synchronized (PRICED) {
            for (Iterator<Asked> it = ASKED.iterator(); it.hasNext(); ) {
                Asked asked = it.next();
                for (String id : ids) {
                    if (names(asked.variables, id)) {
                        it.remove();
                        return;
                    }
                }
            }
            ASKED.pollFirst();
        }
    }

    /**
     * Whether a title built at [nowMillis] can be the listing [id]'s: no listing page's request
     * waits for its answer, or one that waits names [id]. A request waiting past {@link #ASKED_MILLIS}
     * is dropped. Guarded by PRICED.
     */
    private static boolean mayBe(String id, long nowMillis) {
        boolean waiting = false;
        for (Iterator<Asked> it = ASKED.iterator(); it.hasNext(); ) {
            Asked asked = it.next();
            if (nowMillis < asked.at || nowMillis - asked.at >= ASKED_MILLIS) {
                it.remove();
            } else if (names(asked.variables, id)) {
                return true;
            } else {
                waiting = true;
            }
        }
        return !waiting;
    }

    /** Whether [variables] hold [id] as a number of its own, not part of a longer one. */
    static boolean names(String variables, String id) {
        if (id.isEmpty()) return false;
        for (int at = variables.indexOf(id); at >= 0; at = variables.indexOf(id, at + 1)) {
            int end = at + id.length();
            if ((at == 0 || !Character.isDigit(variables.charAt(at - 1)))
                    && (end == variables.length() || !Character.isDigit(variables.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Each text React builds, from its text builder's returns (#303). Facebook's JavaScript writes a
     * listing's title, then its price right under it. When a listing whose page answered lately had
     * its title built, the next few texts are looked at for its price, and that one gets the date on
     * a line of its own under it. React measures and draws what this returns, so the page makes room
     * for the line. A struck-through old price right after it gets an empty line of the same size,
     * and a place line carrying a date that went under a price comes back without it. Anything else,
     * or the date switched off, comes back as it was.
     */
    public static Spannable priceLine(@Nullable Spannable text) {
        if (!anyPriced || text == null) return text;
        try {
            int length = text.length();
            String shown = length == 0 || length > MAX_TEXT_CHARS ? null : text.toString();
            Priced line = null;
            int blank = 0;
            int placeAt = -1;
            String placed = null;
            synchronized (PRICED) {
                Priced named = shown == null ? null : PRICED.get(shown);
                if (named != null) {
                    boolean waits = named.label != null && !mayBe(named.id, System.currentTimeMillis());
                    if (waits) Logger.printDebug(() -> "Listing date: a title came before its page's answer, so its price waits");
                    titled = named.label == null || waits ? null : named;
                    since = 0;
                    // No date goes under this page's price, so its place line keeps the one it has.
                    if (titled == null) PLACED.clear();
                    return text;
                }
                if (struckLeft > 0 && shown != null) {
                    struckLeft--;
                    if (struck(text, shown)) {
                        struckLeft = 0;
                        blank = struckSize;
                    }
                }
                if (blank == 0 && shown != null) {
                    for (String label : PLACED) {
                        int at = shown.indexOf(DOT + label);
                        if (at >= 0) {
                            placeAt = at;
                            placed = label;
                            break;
                        }
                    }
                }
                if (blank == 0 && placed == null) {
                    if (titled == null) return text;
                    if (++since > MAX_BETWEEN) {
                        titled = null;
                        return text;
                    }
                    if (shown == null || length > MAX_PRICE_CHARS || !pricedAs(shown, titled.amount)) return text;
                    line = titled;
                    titled = null;
                }
            }
            if (!MarketplaceAdFilter.listingDate()) return text;
            if (blank > 0) {
                HookStatus.counted(FamilyNames.SPONSORED_MARKETPLACE, OLD_PRICE_LEVEL);
                Logger.printDebug(() -> "Listing date: gave the old price beside the dated price an empty line");
                return underneath(text, BLANK, blank);
            }
            if (placed != null) {
                HookStatus.counted(FamilyNames.SPONSORED_MARKETPLACE, PLACE_UNDATED);
                Logger.printDebug(() -> "Listing date: the place line goes without the date the price has");
                SpannableStringBuilder out = new SpannableStringBuilder(text);
                out.delete(placeAt, placeAt + DOT.length() + placed.length());
                return out;
            }
            HookStatus.bound(FamilyNames.SPONSORED_MARKETPLACE, MarketplaceAdFilter.PRICE_LINE);
            Logger.printDebug(() -> "Listing date: put the date on a line under the listing's price");
            Spannable dated = withLine(text, line.label);
            synchronized (PRICED) {
                PLACED.remove(line.label);
                PLACED.add(line.label);
                if (PLACED.size() > MAX_PRICED) PLACED.remove(PLACED.iterator().next());
                struckLeft = MAX_BETWEEN;
                struckSize = lineSize(text, length);
            }
            return dated;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPONSORED_MARKETPLACE, "listing date under the price", failure);
            return text;
        }
    }

    /**
     * Whether [shown] is the price [amount] (amount_with_offset): the same digits, or those with the
     * cents the price leaves off. A text with more letters than a currency has isn't a price.
     */
    static boolean pricedAs(String shown, String amount) {
        String number = priceDigits(shown);
        if (number == null) return false;
        return amount.equals(number) || amount.equals(number + "00") || amount.equals(number + "000");
    }

    /** The digits of [shown] when it reads as a price, one line with a currency's letters at most. */
    @Nullable
    static String priceDigits(String shown) {
        StringBuilder digits = new StringBuilder();
        int letters = 0;
        for (int i = 0; i < shown.length(); i++) {
            char c = shown.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            else if (c == '\n' || (Character.isLetter(c) && ++letters > MAX_PRICE_LETTERS)) return null;
        }
        return digits.length() == 0 ? null : digits.toString();
    }

    /**
     * Whether [text], reading [shown], is a price crossed out: what a listing's old price looks
     * like. Android's line through counts, and so does React's own, which 585 draws itself rather
     * than through the paint (#303).
     */
    static boolean struck(Spanned text, String shown) {
        if (shown.length() > MAX_PRICE_CHARS || priceDigits(shown) == null) return false;
        for (Object span : text.getSpans(0, text.length(), Object.class)) {
            if (span instanceof StrikethroughSpan || drawsLine(span.getClass())) return true;
        }
        return false;
    }

    /**
     * Whether a span of class [kind] draws a line on the text itself, given the canvas, the text's
     * layout and where it starts and ends: React's line through or under text in 585. Nothing
     * else React puts on a short price draws itself. Asked once per class.
     */
    static boolean drawsLine(Class<?> kind) {
        Boolean known = DRAWS_LINE.get(kind);
        if (known != null) return known;
        boolean draws = false;
        for (Method method : kind.getMethods()) {
            Class<?>[] takes = method.getParameterTypes();
            if (method.getReturnType() == void.class && takes.length == 4 && takes[0] == Canvas.class
                    && takes[1] == Layout.class && takes[2] == int.class && takes[3] == int.class) {
                draws = true;
                break;
            }
        }
        DRAWS_LINE.put(kind, draws);
        return draws;
    }

    /** Whether [text] is a whole number of up to 18 digits. */
    private static boolean digits(String text) {
        if (text.isEmpty() || text.length() > 18) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * [price] with [label] on a line under it: smaller than the price and in its colour, faded. The
     * price's own styles end where the price does.
     */
    static Spannable withLine(Spannable price, String label) {
        int end = price.length();
        Integer colour = null;
        for (ForegroundColorSpan each : price.getSpans(0, end, ForegroundColorSpan.class)) {
            colour = each.getForegroundColor();
        }
        SpannableStringBuilder out = underneath(price, label, lineSize(price, end));
        out.setSpan(new LineColour(colour), end + 1, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    /** The date line's size in pixels: {@link #LINE_SP}, or the price's own when that's smaller. */
    static int lineSize(Spanned price, int end) {
        int size = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LINE_SP,
                Resources.getSystem().getDisplayMetrics()));
        for (AbsoluteSizeSpan each : price.getSpans(0, end, AbsoluteSizeSpan.class)) {
            if (!each.getDip() && each.getSize() > 0) size = Math.min(size, each.getSize());
        }
        return size;
    }

    /** [text] with [line] under it at [size] pixels. [text]'s own styles end where it does. */
    static SpannableStringBuilder underneath(Spannable text, String line, int size) {
        int end = text.length();
        SpannableStringBuilder out = new SpannableStringBuilder(text);
        out.append('\n').append(line);
        int total = out.length();
        for (Object span : out.getSpans(0, total, Object.class)) {
            if (out.getSpanEnd(span) > end) out.setSpan(span, out.getSpanStart(span), end, out.getSpanFlags(span));
        }
        out.setSpan(new AbsoluteSizeSpan(size), end + 1, total, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    /** The date line's colour: the price's as the theme paints it, or the text's own, faded. */
    static final class LineColour extends CharacterStyle implements UpdateAppearance {
        @Nullable
        final Integer price;

        LineColour(@Nullable Integer price) {
            this.price = price;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            int colour = price == null ? paint.getColor() : ReactColours.text(price);
            int alpha = Math.round(Color.alpha(colour) * LINE_ALPHA);
            paint.setColor((colour & 0x00FFFFFF) | (alpha << 24));
        }
    }

    /** Forgets every listing, for tests. */
    static void forgetPricesForTests() {
        synchronized (PRICED) {
            PRICED.clear();
            anyPriced = false;
            titled = null;
            since = 0;
            PLACED.clear();
            struckLeft = 0;
            struckSize = 0;
            ASKED.clear();
        }
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
