/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/RenditionPicker.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds the best media address that an object holds, and ranks addresses against each other.
 *
 * <p>Two things shape this class.
 *
 * <p>First, <b>no Android type appears anywhere in it</b>. Every method takes and answers a
 * {@code String}, an {@code int} or a plain object, and a {@code Uri} becomes a {@code String}
 * through {@code String.valueOf}. Thus the whole file compiles and runs with {@code javac} alone,
 * and {@code work/Renditions.java} checks the ranking on a fixed set of addresses with no device
 * and no Android jar. The ranking is the part that is easy to get wrong and impossible to see on a
 * device, so it is the part that gets a test.
 *
 * <p>Second, <b>Facebook renames the fields of its classes on every release</b> while the values
 * inside them keep their shape. So this class reads values and never names. It collects every
 * address that an object can reach and then asks which one looks best, rather than asking for the
 * field that held the best one last time.
 *
 * <p>The caller that knows a field name by other means must read that field directly with
 * {@link #fieldValue} and only fall back to {@link #harvest}. The video path does exactly that: the
 * patch reads the real field names out of the app itself, so it can ask for the high quality
 * address by name. The story path has no such source and ranks by value.
 */
final class RenditionPicker {

    private RenditionPicker() {}

    /** A progressive file: something that a single GET returns whole. */
    static final int TIER_PROGRESSIVE = 2;

    /** Plausible, but with nothing to say it is a file rather than a manifest. A last resort. */
    static final int TIER_PLAUSIBLE = 1;

    /** Rejected. */
    static final int TIER_NONE = 0;

    /** Stop the walk before it can wander into the app. */
    private static final int MAX_NODES = 512;

    /** Longer than any real address, and a guard against walking into a serialised blob. */
    private static final int MAX_URL = 4096;

    // ---------------------------------------------------------------- ranking (pure)

    /** Whether this text is an address that {@code HttpURLConnection} can fetch. */
    static boolean isHttpUrl(String text) {
        if (text == null) return false;
        if (text.length() < 11 || text.length() > MAX_URL) return false;

        String lower = text.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;

        // A real address holds no space. Inline XML that starts with a URL holds one.
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) return false;
        }

        return true;
    }

    /**
     * Whether this text describes where the pieces of a video are, rather than being the video.
     *
     * <p>A manifest is the one answer that looks right and is useless: fetching it returns a few
     * kilobytes of XML named {@code .mp4} by the time anyone notices.
     */
    static boolean isManifest(String text) {
        if (text == null) return true;

        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("<mpd") || lower.contains("<?xml")) return true;

        String path = pathOf(lower);
        return path.endsWith(".mpd")
            || path.endsWith(".m3u8")
            || path.endsWith(".m3u")
            || path.endsWith(".ism")
            || path.endsWith(".ismc");
    }

    /** How much this address looks like a video file. See the {@code TIER_} constants. */
    static int videoTier(String url) {
        if (!isHttpUrl(url) || isManifest(url)) return TIER_NONE;

        String lower = url.toLowerCase(Locale.US);
        String path = pathOf(lower);

        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov")) {
            return TIER_PROGRESSIVE;
        }

        // Facebook marks the single file variants of a video with this word. The measured
        // addresses of a reel carry it even when the path has no suffix.
        if (lower.contains("progressive")) return TIER_PROGRESSIVE;

        // A picture on the same host is not a video. Without this check, a photo story rates its
        // picture as a plausible video. Then the picture goes into Movies with a video name.
        if (hasImageSuffix(path)) return TIER_NONE;

        return isFacebookHost(lower) ? TIER_PLAUSIBLE : TIER_NONE;
    }

    /** How much this address looks like a full size picture rather than a thumbnail. */
    static int imageTier(String url) {
        if (!isHttpUrl(url) || isManifest(url)) return TIER_NONE;

        String lower = url.toLowerCase(Locale.US);

        // Facebook asks for a thumbnail by putting the size into the address. A saved thumbnail
        // looks like a success and is worthless, so these lose outright rather than rank low.
        if (lower.contains("stp=dst-jpg_s") || lower.matches(".*[/_]([sp])\\d{2,3}x\\d{2,3}.*")) {
            return TIER_NONE;
        }

        if (hasImageSuffix(pathOf(lower))) return TIER_PROGRESSIVE;

        return isFacebookHost(lower) ? TIER_PLAUSIBLE : TIER_NONE;
    }

    /**
     * The short side of this address in pixels, or {@code 0} when it says nothing.
     *
     * <p>Three markers, in a fixed order, so that the answer never depends on which one is looked
     * for first. {@code 720p} already means the short side, so a {@code WxH} pair reduces to the
     * same unit and the two never need separate scales.
     *
     * <p>Facebook does not always put the marker in plain sight. It packs the name of the variant
     * into the {@code efg} parameter as base64, and the marker is inside. Reading that parameter is
     * the difference between a real ranking and every address tying at zero.
     */
    static int qualityOf(String url) {
        if (url == null) return 0;

        // A measured marker first, wherever it is. Facebook writes the real one into the efg
        // parameter as often as into the address. An address can also carry a crude word that
        // disagrees with it. One seen here reads `tag=sd` while its efg states 360. A read of the
        // word first answered 480 for that address and stopped, which let a 360 outrank a 720
        // whose only marker sat inside its efg.
        String address = readablePart(url);
        int measured = markerIn(address);
        if (measured > 0) return measured;

        String efg = decodeEfg(url);
        if (efg != null) {
            measured = markerIn(efg);
            if (measured > 0) return measured;

            // The tag inside names the rendition as a bare number more often than as a marker:
            // `...C3.360.sve_sd` states 360 and never writes `360p`. Only the decoded tag is read
            // this way. The address itself is full of long digit runs that mean nothing, and so
            // are the efg's numeric fields: its `duration_s` of 1485 once made a 640x360 file
            // read as 1485p and outrank real 720p ones.
            measured = bareNumberIn(tagTextOf(efg));
            if (measured > 0) return measured;
        }

        // Only now the word, which says nothing more precise than "big" or "small".
        int word = wordIn(address);
        if (word > 0) return word;

        return efg == null ? 0 : wordIn(efg);
    }

    /**
     * Which of two addresses to prefer. Lower sorts better.
     *
     * <p>The last key is the address itself, and it has to be. The walk reads fields in whatever
     * order the runtime reports them, and the JVM and ART both leave that order unspecified. A tie
     * broken by the order they were found is a different answer on a different device. A compare
     * of the text makes the answer the same everywhere.
     */
    static int compare(String a, String b, boolean video) {
        int tierA = video ? videoTier(a) : imageTier(a);
        int tierB = video ? videoTier(b) : imageTier(b);
        if (tierA != tierB) return tierB - tierA;

        int qualityA = qualityOf(a);
        int qualityB = qualityOf(b);
        if (qualityA != qualityB) return qualityB - qualityA;

        return a.compareTo(b);
    }

    /** The best of these addresses, or {@code null} when none of them qualifies. */
    static String bestOf(Collection<String> urls, boolean video) {
        if (urls == null || urls.isEmpty()) return null;

        // The same address can arrive through two fields. Fold it first, so that a duplicate
        // cannot change the answer.
        Set<String> unique = new LinkedHashSet<>(urls);

        String best = null;
        for (String url : unique) {
            if ((video ? videoTier(url) : imageTier(url)) == TIER_NONE) continue;
            if (best == null || compare(url, best, video) < 0) best = url;
        }

        return best;
    }

    /**
     * The video address among these that suits [quality] best, or {@code null} when none is a
     * video. The best quality is {@link #bestOf}, as it always was. Below it, a single file still
     * wins over a plausible address first, then {@link DownloadQuality#compare} decides on the
     * stated qualities, and the address itself breaks a tie, for the same reason as in
     * {@link #compare}. An address that states no quality comes last but still counts, so a
     * ceiling never leaves a save with nothing.
     */
    static String bestVideo(Collection<String> urls, DownloadQuality quality) {
        if (quality == null || quality == DownloadQuality.BEST) return bestOf(urls, true);
        if (urls == null || urls.isEmpty()) return null;

        String best = null;
        for (String url : new LinkedHashSet<>(urls)) {
            if (videoTier(url) == TIER_NONE) continue;
            if (best == null || compare(url, best, quality) < 0) best = url;
        }

        return best;
    }

    /** {@link #compare} for a video save held to [quality]. Lower sorts better. */
    static int compare(String a, String b, DownloadQuality quality) {
        int tierA = videoTier(a);
        int tierB = videoTier(b);
        if (tierA != tierB) return tierB - tierA;

        int order = quality.compare(qualityOf(a), qualityOf(b));
        if (order != 0) return order;

        return a.compareTo(b);
    }

    // ---------------------------------------------------------------- reading an object

    /**
     * The value of one named field of [host] as text, or {@code null}.
     *
     * <p>For the caller that already knows the real name of the field. Walks the superclasses,
     * because the field is not always declared where the object says it is.
     */
    static String fieldValue(Object host, String fieldName) {
        if (host == null || fieldName == null) return null;

        for (Class<?> type = host.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(host);
                return value == null ? null : String.valueOf(value);
            } catch (NoSuchFieldException ignored) {
                // Declared further up, or renamed. Keep walking.
            } catch (Throwable t) {
                return null;
            }
        }

        return null;
    }

    /**
     * Every address that [host] can reach within [maxDepth] steps.
     *
     * <p>This is a walk with a fence around it, and the fence is the point. An object of Facebook
     * holds a view, a view holds a context, and a context holds the whole application. A walk that
     * follows every field thus reaches the entire app, on the thread that draws. The budget, the
     * depth and the check on the name of the class are what keep it to the media.
     */
    static List<String> harvest(Object host, int maxDepth) {
        return walk(host, maxDepth, RenditionPicker::isHttpUrl);
    }

    /**
     * The first DASH manifest [host] holds as text within [maxDepth] steps, or {@code null}.
     *
     * <p>A story's media carries its {@code playlist}, the manifest its player is built from, and
     * {@link #harvest} passes it by because it isn't an address.
     */
    static String manifestIn(Object host, int maxDepth) {
        List<String> found = walk(host, maxDepth, text -> text.contains("<MPD") && !isHttpUrl(text));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Every text [host] can reach within [maxDepth] steps that [keep] takes. */
    private static List<String> walk(Object host, int maxDepth, Predicate<String> keep) {
        List<String> found = new ArrayList<>();
        if (host == null) return found;

        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[] { host, 0 });
        seen.put(host, Boolean.TRUE);

        int nodes = 0;

        while (!queue.isEmpty() && nodes < MAX_NODES) {
            Object[] entry = queue.poll();
            Object node = entry[0];
            int depth = (Integer) entry[1];
            nodes++;

            for (Class<?> type = node.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                Field[] fields;
                try {
                    fields = type.getDeclaredFields();
                } catch (Throwable t) {
                    continue;
                }

                for (Field field : fields) {
                    // A static field is a constant or a cache, never the media of this item.
                    if (Modifier.isStatic(field.getModifiers())) continue;

                    Object value;
                    try {
                        field.setAccessible(true);
                        value = field.get(node);
                    } catch (Throwable t) {
                        // One unreadable field must not end the walk.
                        continue;
                    }

                    if (value == null) continue;
                    collect(value, depth, maxDepth, keep, found, seen, queue);
                }
            }
        }

        return found;
    }

    // ---------------------------------------------------------------- internals

    private static boolean hasImageSuffix(String lowerPath) {
        return lowerPath.endsWith(".jpg")
            || lowerPath.endsWith(".jpeg")
            || lowerPath.endsWith(".png")
            || lowerPath.endsWith(".webp")
            || lowerPath.endsWith(".heic")
            || lowerPath.endsWith(".avif")
            || lowerPath.endsWith(".gif");
    }

    private static void collect(
        Object value,
        int depth,
        int maxDepth,
        Predicate<String> keep,
        List<String> found,
        IdentityHashMap<Object, Boolean> seen,
        Deque<Object[]> queue
    ) {
        if (isText(value)) {
            String text = String.valueOf(value);
            if (keep.test(text)) found.add(text);
            return;
        }

        // One level into a list or an array, because a field can hold several variants of the
        // same video rather than one.
        if (value instanceof Iterable) {
            for (Object element : (Iterable<?>) value) {
                if (element != null && isText(element)) {
                    String text = String.valueOf(element);
                    if (keep.test(text)) found.add(text);
                }
            }
            return;
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(value, i);
                if (element != null && isText(element)) {
                    String text = String.valueOf(element);
                    if (keep.test(text)) found.add(text);
                }
            }
            return;
        }

        if (depth >= maxDepth) return;
        if (!isAppObject(value)) return;
        if (seen.put(value, Boolean.TRUE) != null) return;

        queue.add(new Object[] { value, depth + 1 });
    }

    /** Text, or something whose text is the address. An Android {@code Uri} is the second kind. */
    private static boolean isText(Object value) {
        return value instanceof CharSequence
            || value.getClass().getName().startsWith("android.net.Uri");
    }

    /**
     * Whether this is an object of the app rather than of the platform.
     *
     * <p>Facebook puts its own classes in its own packages and its renamed ones in {@code X}.
     * Everything else is a view, a framework object or a container, and the walk stops there.
     */
    private static boolean isAppObject(Object value) {
        String name = value.getClass().getName();
        return name.startsWith("com.facebook.") || name.startsWith("X.");
    }

    private static boolean isFacebookHost(String lowerUrl) {
        String host = hostOf(lowerUrl);
        return host.endsWith(".fbcdn.net") || host.startsWith("scontent");
    }

    private static String hostOf(String lowerUrl) {
        int start = lowerUrl.indexOf("://");
        if (start < 0) return "";
        start += 3;

        int end = lowerUrl.length();
        for (int i = start; i < lowerUrl.length(); i++) {
            char c = lowerUrl.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }

        return lowerUrl.substring(start, end);
    }

    private static String pathOf(String lowerUrl) {
        int cut = lowerUrl.indexOf('?');
        String withoutQuery = cut < 0 ? lowerUrl : lowerUrl.substring(0, cut);

        cut = withoutQuery.indexOf('#');
        return cut < 0 ? withoutQuery : withoutQuery.substring(0, cut);
    }

    /** A number this text actually states: a `720p` marker, or the short side of a `1280x720`. */
    private static int markerIn(String text) {
        int marked = matchNumberBefore(text, 'p');
        if (marked > 0) return marked;

        return matchDimensionPair(text);
    }

    /** The heights Facebook encodes renditions at. A bare number is read only when it's one. */
    private static final int[] RENDITION_HEIGHTS = {144, 240, 270, 360, 480, 540, 720, 1080, 1440, 2160};

    /**
     * The text of [efg] a bare number may be read from: when it's the JSON Facebook writes, the
     * string values of its tag fields ({@code vencode_tag}) only, never a number field such as
     * {@code duration_s} or an id; otherwise all of it.
     */
    static String tagTextOf(String efg) {
        String text = efg.trim();
        if (!text.startsWith("{")) return efg;

        StringBuilder tags = new StringBuilder();
        int index = 0;
        while (true) {
            int keyStart = text.indexOf('"', index);
            if (keyStart < 0) break;
            int keyEnd = text.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String key = text.substring(keyStart + 1, keyEnd);

            int colon = keyEnd + 1;
            while (colon < text.length() && Character.isWhitespace(text.charAt(colon))) colon++;
            if (colon >= text.length() || text.charAt(colon) != ':') {
                index = keyEnd + 1;
                continue;
            }
            int value = colon + 1;
            while (value < text.length() && Character.isWhitespace(text.charAt(value))) value++;
            if (value < text.length() && text.charAt(value) == '"') {
                int valueEnd = text.indexOf('"', value + 1);
                if (valueEnd < 0) break;
                if (key.toLowerCase(Locale.US).endsWith("tag")) {
                    tags.append(text, value + 1, valueEnd).append(' ');
                }
                index = valueEnd + 1;
            } else {
                index = value;
            }
        }
        return tags.toString();
    }

    /**
     * The largest standalone number in [text] that is a height Facebook encodes at.
     *
     * Held to {@link #RENDITION_HEIGHTS}, so an id, a width such as the 640 of a 640x360 file, or
     * a timestamp in the same tag cannot be mistaken for one.
     */
    private static int bareNumberIn(String text) {
        int best = 0;
        int index = 0;

        while (index < text.length()) {
            if (!Character.isDigit(text.charAt(index))) {
                index++;
                continue;
            }

            int start = index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) index++;

            int digits = index - start;
            if (digits < 3 || digits > 4) continue;

            try {
                int value = Integer.parseInt(text.substring(start, index));
                for (int height : RENDITION_HEIGHTS) {
                    if (value == height) best = Math.max(best, value);
                }
            } catch (NumberFormatException ignored) {
                // Cannot happen: every character was checked as a digit.
            }
        }

        return best;
    }

    /** The crude signal. Kept only for an address that states no number anywhere. */
    private static int wordIn(String text) {
        String lower = text.toLowerCase(Locale.US);

        if (containsWord(lower, "hd")) return 720;
        if (containsWord(lower, "sd")) return 480;

        return 0;
    }

    /**
     * The number in a marker such as {@code _720p}, or {@code 0}.
     *
     * <p>Written by hand rather than with a regular expression. This runs on every address of
     * every save, and a pattern here is not worth the cost to compile one.
     */
    private static int matchNumberBefore(String text, char suffix) {
        int best = 0;

        for (int i = 0; i < text.length(); i++) {
            if (Character.toLowerCase(text.charAt(i)) != suffix) continue;

            // A digit after the marker means this is part of a longer number, not a marker. A
            // letter means it sits inside a token, like the "3573p" of readablePart's note.
            if (i + 1 < text.length() && Character.isLetterOrDigit(text.charAt(i + 1))) continue;

            int end = i;
            int start = i;
            while (start > 0 && Character.isDigit(text.charAt(start - 1))) start--;

            // Three or four digits is a resolution. Fewer is a version, more is an id.
            int digits = end - start;
            if (digits < 3 || digits > 4) continue;
            // A letter right before the digits puts it inside a token too.
            if (start > 0 && Character.isLetter(text.charAt(start - 1))) continue;

            try {
                best = Math.max(best, Integer.parseInt(text.substring(start, end)));
            } catch (NumberFormatException ignored) {
                // Cannot happen: every character was checked as a digit.
            }
        }

        return best;
    }

    /** The short side of a {@code 1280x720} pair, or {@code 0}. */
    private static int matchDimensionPair(String text) {
        int best = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (c != 'x') continue;

            int leftEnd = i;
            int leftStart = i;
            while (leftStart > 0 && Character.isDigit(text.charAt(leftStart - 1))) leftStart--;

            int rightStart = i + 1;
            int rightEnd = rightStart;
            while (rightEnd < text.length() && Character.isDigit(text.charAt(rightEnd))) rightEnd++;

            int leftDigits = leftEnd - leftStart;
            int rightDigits = rightEnd - rightStart;
            if (leftDigits < 2 || leftDigits > 5 || rightDigits < 2 || rightDigits > 5) continue;
            if (!pairStandsApart(text, leftStart, rightEnd)) continue;

            try {
                int width = Integer.parseInt(text.substring(leftStart, leftEnd));
                int height = Integer.parseInt(text.substring(rightStart, rightEnd));
                best = Math.max(best, Math.min(width, height));
            } catch (NumberFormatException ignored) {
                // Cannot happen: every character was checked as a digit.
            }
        }

        return best;
    }

    private static boolean containsWord(String lowerText, String word) {
        int from = 0;

        while (true) {
            int at = lowerText.indexOf(word, from);
            if (at < 0) return false;

            boolean startsCleanly = at == 0 || !Character.isLetterOrDigit(lowerText.charAt(at - 1));
            int after = at + word.length();
            boolean endsCleanly = after >= lowerText.length()
                || !Character.isLetterOrDigit(lowerText.charAt(after));

            if (startsCleanly && endsCleanly) return true;
            from = at + 1;
        }
    }

    /**
     * Whether the pair from [start] to [end] is a size rather than digits inside a token.
     *
     * <p>Facebook writes a picture's size with a short lowercase prefix, {@code s2048x2048},
     * {@code p180x540} or {@code mx2048x3640}, and a video's as {@code 1280x720} or
     * {@code 1280x720p}. Anything else glued on, {@code Zk1280x720} or {@code 1280x720Qa}, is a
     * signed token's letters and digits.
     */
    private static boolean pairStandsApart(String text, int start, int end) {
        int prefix = start;
        while (prefix > 0 && Character.isLetter(text.charAt(prefix - 1))) prefix--;
        if (start - prefix > 2) return false;
        for (int i = prefix; i < start; i++) {
            if (!Character.isLowerCase(text.charAt(i))) return false;
        }
        if (prefix > 0 && Character.isLetterOrDigit(text.charAt(prefix - 1))) return false;

        int after = end;
        if (after < text.length() && Character.toLowerCase(text.charAt(after)) == 'p') after++;
        return after >= text.length() || !Character.isLetterOrDigit(text.charAt(after));
    }

    /** Query parameters whose values are signatures, ids or base64, never a size. */
    private static final String[] OPAQUE_PARAMETERS = {"oh", "oe", "ccb", "edm", "efg"};

    /**
     * This address with the parts that can't state a size left out: the values of its signed
     * parameters ({@code oh}, every {@code _nc_} one, the raw base64 of {@code efg}, whose
     * decoded text is read on its own) and any long mixed-case token, such as the file name of
     * an {@code /o1/} address.
     *
     * <p>A run like {@code 3573p} turns up inside those by chance. The S22 saved a 400x224 file
     * over a 720p one on 2026-11-12 after reading 3573 out of one.
     */
    private static String readablePart(String url) {
        int hash = url.indexOf('#');
        String address = hash < 0 ? url : url.substring(0, hash);
        int question = address.indexOf('?');
        String path = question < 0 ? address : address.substring(0, question);

        StringBuilder readable = new StringBuilder(address.length());
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) readable.append('/');
            if (!isSignedToken(segments[i])) readable.append(segments[i]);
        }
        if (question < 0) return readable.toString();

        readable.append('?');
        String[] parameters = address.substring(question + 1).split("&", -1);
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) readable.append('&');
            String parameter = parameters[i];
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            String value = equals < 0 ? "" : parameter.substring(equals + 1);
            if (isOpaqueParameter(name) || isSignedToken(value)) {
                readable.append(name).append('=');
            } else {
                readable.append(parameter);
            }
        }
        return readable.toString();
    }

    private static boolean isOpaqueParameter(String name) {
        if (name.startsWith("_nc_")) return true;
        for (String opaque : OPAQUE_PARAMETERS) {
            if (opaque.equals(name)) return true;
        }
        return false;
    }

    /**
     * Whether [token] is a signature or other base64: at least 16 characters of letters, digits,
     * {@code -}, {@code _} and {@code %}, in both cases. A file extension is left out first, and
     * the hex of a {@code %2C} escape doesn't count as a case. Facebook's own markers
     * ({@code dash_720p}, {@code dst-jpg_s2048x2048_tt6%2Cx}) are lowercase.
     */
    private static boolean isSignedToken(String token) {
        int dot = token.lastIndexOf('.');
        String stem = dot < 0 ? token : token.substring(0, dot);
        if (stem.length() < 16) return false;

        boolean upper = false;
        boolean lower = false;
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            if (c == '%' && i + 2 < stem.length()
                    && Character.digit(stem.charAt(i + 1), 16) >= 0
                    && Character.digit(stem.charAt(i + 2), 16) >= 0) {
                i += 2;
            } else if (c >= 'A' && c <= 'Z') {
                upper = true;
            } else if (c >= 'a' && c <= 'z') {
                lower = true;
            } else if (!(c >= '0' && c <= '9') && c != '-' && c != '_' && c != '%') {
                return false;
            }
        }
        return upper && lower;
    }

    /**
     * The {@code efg} parameter of this address, decoded, or {@code null}.
     *
     * <p>Facebook puts the name of the variant in here, so this is where the marker is when the
     * address itself shows none.
     */
    private static String decodeEfg(String url) {
        int at = url.indexOf("efg=");
        if (at < 0) return null;

        int start = at + 4;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '&' || c == '#') {
                end = i;
                break;
            }
        }

        String raw = url.substring(start, end);
        if (raw.isEmpty()) return null;

        try {
            // The padding of this parameter arrives percent-encoded, because it sits in a query
            // string. Left in place it is not base64 at all, so the decode throws and the whole
            // parameter is ignored without a word. That is what made a 360 look like a 480.
            String cleaned = raw.replace("%3D", "").replace("%3d", "").replace("=", "");

            StringBuilder padded = new StringBuilder(cleaned);
            while (padded.length() % 4 != 0) padded.append('=');

            byte[] decoded = java.util.Base64.getUrlDecoder().decode(padded.toString());
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // Not base64, or not for a URL. The address says nothing about its size.
            return null;
        }
    }
}
