/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.navigation.FeedsSubtabRoute;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * Debug logging only: the parameters a news feed request goes out with, read off the parameter set
 * Facebook's feed query method returns, so Home with a Home shows filter can be set side by side
 * with the Feeds tab's own filter. With Friends picked, Home's request carries Friends' feed type
 * and still comes back as recommendations, while the Feeds tab's Friends chip doesn't (2026-10-14),
 * so whatever key differs between the two is what the server reads.
 *
 * <p>Each distinct shape of parameter set is logged once, up to {@value #MAX_REQUESTS} of them, in
 * parts short enough for logcat; sets that differ only in their numbers count as one shape, so
 * paging through a feed doesn't use them up. Keys and the words Facebook writes (feed styles,
 * orderings, booleans) are kept as they are; numbers long enough to be ids, digit strings, anything
 * that looks like hex, and any other text are replaced by their kind. A nested input is read the
 * same way when its class answers one map. Nothing here changes the request, and with debug logging
 * off it returns at once.
 */
public final class FeedRequestParams {
    static final String PREFIX = "Feed request ";

    /** How a request's feed style reads, and Facebook's default one. */
    private static final String FEED_STYLE = "feed_style=";
    private static final String DEFAULT_FEED_STYLE = FEED_STYLE + "MOST_RECENT_FEED_DEFAULT";
    static final int MAX_REQUESTS = 24;
    static final int PART = 800;
    static final int MAX_ITEMS = 12;
    static final int MAX_DEPTH = 3;

    /** A key kept as it is, like feed_style or orderby. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,47}");
    /** A value kept as it is: a word in one case, like MOST_RECENT_FRIENDS_FEED or top_stories. */
    private static final Pattern WORD = Pattern.compile("[A-Z][A-Z0-9_]{0,47}|[a-z][a-z0-9_]{0,47}");
    /** What an id or a token has and a word doesn't: a long run of digits, or of hex. */
    private static final Pattern ID_LIKE = Pattern.compile("[0-9]{5,}|[0-9a-fA-F]{8,}");
    private static final Pattern DIGITS = Pattern.compile("-?[0-9]+");
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]+");
    private static final String SOURCE = "FeedRequestParams";

    /** The shapes logged since the process started, and how many. */
    private static final Set<String> logged = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger requests = new AtomicInteger();

    /** Each class's one public no-argument method answering a map, or {@link #NONE}. */
    private static final Map<Class<?>, Method> maps = new ConcurrentHashMap<>();
    private static final Method NONE;

    static {
        try {
            NONE = Object.class.getMethod("toString");
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private FeedRequestParams() {
    }

    private static boolean enabled() {
        try {
            return Utils.settingsReady() && BaseSettings.DEBUG.get();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** The hook, before each of the feed query method's returns, handed the request's parameter set. */
    public static void sent(@Nullable Object params) {
        if (params != null && FeedsSubtabRoute.awaitingPosts()) tellTheStartsFilter(params);
        if (params == null || requests.get() >= MAX_REQUESTS || !enabled()) return;
        try {
            Map<?, ?> map = mapOf(params);
            String text = map == null ? "parameters unreadable from " + params.getClass().getName() : render(map, 0);
            if (!logged.add(DIGIT_RUN.matcher(text).replaceAll("#"))) return;
            int number = requests.incrementAndGet();
            if (number > MAX_REQUESTS) return;
            int parts = (text.length() + PART - 1) / PART;
            for (int i = 0; i < parts; i++) {
                String piece = text.substring(i * PART, Math.min(text.length(), (i + 1) * PART));
                String label = PREFIX + number + " part " + (i + 1) + "/" + parts + ": ";
                Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE, () -> label + piece);
            }
        } catch (Throwable unreadable) {
            // This runs inside Facebook's feed request, so not even the note about it may throw there.
            try {
                Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                        () -> PREFIX + "parameters unreadable: " + unreadable.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Says when a request names a feed style other than Facebook's default, which goes out at every
     * start whatever the filter. That's the filter the start asked for, since its page is showing.
     */
    private static void tellTheStartsFilter(Object params) {
        try {
            Map<?, ?> map = mapOf(params);
            String text = map == null ? "" : render(map, 0);
            if (text.contains(FEED_STYLE) && !text.contains(DEFAULT_FEED_STYLE)) FeedsSubtabRoute.filterPostsRequested();
        } catch (Throwable unreadable) {
            // This runs inside Facebook's feed request. The asking runs out by itself.
        }
    }

    /** Whether [text] is kept as it is under [pattern]: it matches and has nothing of an id about it. */
    private static boolean kept(Pattern pattern, String text) {
        return pattern.matcher(text).matches() && !ID_LIKE.matcher(text).find();
    }

    /** The map [value]'s class answers from its one public no-argument method returning a map, or null. */
    @Nullable
    static Map<?, ?> mapOf(Object value) throws ReflectiveOperationException {
        Class<?> type = value.getClass();
        Method method = maps.get(type);
        if (method == null) {
            List<Method> found = new ArrayList<>();
            for (Method candidate : type.getMethods()) {
                if (candidate.getParameterTypes().length == 0 && !Modifier.isStatic(candidate.getModifiers())
                        && Map.class.isAssignableFrom(candidate.getReturnType())
                        && candidate.getDeclaringClass() != Object.class) {
                    found.add(candidate);
                }
            }
            method = found.size() == 1 ? found.get(0) : NONE;
            // A public method of a class that isn't public can't be invoked from here without it.
            if (method != NONE) method.setAccessible(true);
            maps.put(type, method);
        }
        return method == NONE ? null : (Map<?, ?>) method.invoke(value);
    }

    static String render(@Nullable Object value, int depth) throws ReflectiveOperationException {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Enum) return ((Enum<?>) value).name();
        if (value instanceof Number) {
            String number = value.toString();
            return number.replaceAll("[^0-9]", "").length() > 8 ? "<number>" : number;
        }
        if (value instanceof CharSequence) {
            String text = value.toString();
            if (kept(WORD, text)) return text;
            if (DIGITS.matcher(text).matches()) return "<id>";
            return "<text>";
        }
        if (depth >= MAX_DEPTH) return "<deeper>";
        if (value instanceof Map) {
            Map<String, String> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                sorted.put(kept(KEY, key) ? key : "<key>", render(entry.getValue(), depth + 1));
            }
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                if (out.length() > 1) out.append(", ");
                out.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return out.append('}').toString();
        }
        if (value instanceof Collection) {
            Collection<?> items = (Collection<?>) value;
            StringBuilder out = new StringBuilder("[");
            int shown = 0;
            for (Object item : items) {
                if (shown == MAX_ITEMS) break;
                if (shown++ > 0) out.append(", ");
                out.append(render(item, depth + 1));
            }
            if (items.size() > shown) out.append(", +").append(items.size() - shown);
            return out.append(']').toString();
        }
        Map<?, ?> nested = mapOf(value);
        return nested == null ? "<object>" : render(nested, depth + 1);
    }

    /** Lets the next requests log again, for tests. */
    static void forget() {
        logged.clear();
        requests.set(0);
        maps.clear();
    }
}
