/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.profile;

import android.os.Bundle;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.stories.StorySeen;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Friends, followers and following counts of your own profile that you type yourself (#280), shown
 * on this phone only.
 *
 * <p>Facebook lays the top of a profile out with Bloks, its server-sent layout, and a count is a
 * piece of text in a node's string attributes, like "1.2K followers". The patch hands every string
 * a Bloks node answers to {@link #text}. While the shown profile is yours, a number that sits right
 * before the word for friends, followers or following is swapped for the one you typed, and the
 * word stays as Facebook wrote it in your language. Nothing is sent anywhere and nothing about the
 * account changes.
 *
 * <p>Facebook 586 and 587 draw a count as two nodes side by side, the number ("121M") and its word
 * (" followers"), so neither string holds both (#488). The patch also hands every child list a
 * Bloks node answers to {@link #children}, which remembers the very number string of a node that
 * sits right before a count word, and {@link #text} swaps that string the same way.
 *
 * <p>The shown profile is the newest profile screen that's on top. {@link #opened} keeps the
 * screen, and its {@code com.facebook.katana.profile.id} argument is compared to the signed-in
 * user's id, which the screen holds in its ViewerContext or its FbUserSession. A screen with no id
 * shows your own profile. A profile that isn't yours, a screen that's gone behind another, a switch
 * that's off or a count left blank leaves Facebook's text alone. Any failure here does the same.
 */
public final class LocalProfileCounts {
    /** The argument Facebook's profile screen is given the profile's id in. */
    static final String PROFILE_ID_ARGUMENT = "com.facebook.katana.profile.id";

    /** The type of the object holding the signed-in user's id, and the field it's in. */
    static final String VIEWER_CONTEXT = "com.facebook.auth.viewercontext.ViewerContext";
    static final String USER_ID_FIELD = "mUserId";

    /** The session type the profile screen also keeps, which holds the same id. */
    static final String USER_SESSION = "com.facebook.auth.usersession.FbUserSession";

    /** The longest string looked at: a count line is short, and a long text is some other content. */
    static final int LONGEST_TEXT = 80;

    /** The most digits a typed count may have. */
    public static final int MAX_DIGITS = 10;

    /** Which of the three counts a word names. */
    public enum Count { FRIENDS, FOLLOWERS, FOLLOWING }

    private static final String NUMBER = "\\d[\\d.,\\u00a0\\u202f]*[KkMmBb]?";

    /**
     * The words that follow a number, as Facebook says them in the languages Hushfacebook comes in,
     * longest first so "followers" isn't read as "follower" and the rest of a word.
     */
    private static final String FRIENDS_WORDS =
            "friends|friend|Freunde|Freund|amigos|amigo|amis|ami|amici|amico|teman|arkadaş|bạn bè";
    private static final String FOLLOWERS_WORDS =
            "followers|follower|seguidores|seguidor|abonnés|abonné|takipçi|pengikut|người theo dõi";
    private static final String FOLLOWING_WORDS =
            "following|Gefolgt|seguidos|seguindo|abonnements|takip edilen|mengikuti|đang theo dõi";

    private static final Pattern FRIENDS = pattern(FRIENDS_WORDS);
    private static final Pattern FOLLOWERS = pattern(FOLLOWERS_WORDS);
    private static final Pattern FOLLOWING = pattern(FOLLOWING_WORDS);

    private static Pattern pattern(String words) {
        return Pattern.compile("(" + NUMBER + ")([ \\u00a0\\u202f]+)(" + words + ")(?![\\p{L}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** A count's number alone in its string, as Facebook 586 and 587 draw it before a separate word. */
    private static final Pattern BARE_NUMBER = Pattern.compile("([ \\u00a0\\u202f]*)(" + NUMBER + ")([ \\u00a0\\u202f]*)");
    private static final Pattern FRIENDS_WORD = word(FRIENDS_WORDS);
    private static final Pattern FOLLOWERS_WORD = word(FOLLOWERS_WORDS);
    private static final Pattern FOLLOWING_WORD = word(FOLLOWING_WORDS);

    private static Pattern word(String words) {
        return Pattern.compile("[ \\u00a0\\u202f]*(" + words + ")(?![\\p{L}]).*",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    /** How far into wrappers a split count's number or word is looked for. */
    private static final int WRAPPER_DEPTH = 3;

    /**
     * How many nodes one piece of a split count holds at most. Facebook 587 keeps the text node next
     * to a "..." node and an empty one, so a piece is about four; a bigger node is a whole section.
     */
    private static final int PIECE_NODES = 16;

    /** What a search returns when more than one string matched, or the node was too big to be a piece. */
    private static final String NOT_ONE = new String("not one");

    /** How many split counts are remembered: a profile shows three at most, twice with its Friends section. */
    private static final int REMEMBERED = 8;

    /**
     * The number strings of split counts, each the very string its node holds, and which count each
     * one is. A string with the same digits that isn't one of these, like a like count, keeps its number.
     */
    private static final Object[] marked = new Object[REMEMBERED];
    private static final Count[] markedCounts = new Count[REMEMBERED];
    private static int nextMark;

    private static volatile WeakReference<Object> screen = new WeakReference<>(null);

    private LocalProfileCounts() {
    }

    /**
     * Injection point, before every return of a Bloks node's child getters: the same list, unchanged.
     * While a profile screen is on top with the switch on, a number node right before a friends,
     * followers or following word node is remembered as that count. Never throws.
     */
    @Nullable
    public static List<?> children(@Nullable List<?> list) {
        try {
            if (list == null || list.size() < 2) return list;
            if (!Utils.settingsReady() || !Settings.LOCAL_PROFILE_COUNTS.get() || !anyCountTyped()) return list;
            Object current = screen.get();
            if (current == null || !resumed(current)) return list;
            for (int index = 0, last = list.size() - 1; index < last; index++) {
                Object numberPiece = list.get(index);
                String number = textIn(numberPiece, BARE_NUMBER);
                if (number == null) continue;
                Object wordPiece = list.get(index + 1);
                Count count = wordIn(wordPiece);
                if (count == null) continue;
                // A whole "121M followers" row next to a "5 following" row is two counts, not one.
                if (wordIn(numberPiece) != null || textIn(wordPiece, BARE_NUMBER) != null) continue;
                remember(number, count);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LOCAL_PROFILE_COUNTS, "count line pieces", failure);
            Logger.printException(() -> "Local profile counts: could not read a Bloks node's children", failure);
        }
        return list;
    }

    /** Which count [node]'s text names when it starts with the word for one, or null. */
    @Nullable
    private static Count wordIn(@Nullable Object node) throws IllegalAccessException {
        if (textIn(node, FRIENDS_WORD) != null) return Count.FRIENDS;
        if (textIn(node, FOLLOWERS_WORD) != null) return Count.FOLLOWERS;
        if (textIn(node, FOLLOWING_WORD) != null) return Count.FOLLOWING;
        return null;
    }

    /**
     * The first of [node]'s own short strings that [shape] matches whole, or else the one string a
     * single one of its children has, a few wrappers down at most. Null when two children have one,
     * when the node is too big to be a piece of a count line, and for anything that isn't a Bloks node.
     */
    @Nullable
    private static String textIn(@Nullable Object node, Pattern shape) throws IllegalAccessException {
        String found = search(node, shape, WRAPPER_DEPTH, new int[] {PIECE_NODES});
        return found == NOT_ONE ? null : found;
    }

    @Nullable
    private static String search(@Nullable Object node, Pattern shape, int depth, int[] budget)
            throws IllegalAccessException {
        if (node == null) return null;
        Field field = ProfileNotes.attributes(node.getClass());
        if (field == null) return null;
        if (--budget[0] < 0) return NOT_ONE;
        SparseArray<?> attributes = (SparseArray<?>) field.get(node);
        if (attributes == null) return null;
        int size = attributes.size();
        for (int index = 0; index < size; index++) {
            Object value = attributes.valueAt(index);
            if (value instanceof String) {
                String text = (String) value;
                if (text.length() <= LONGEST_TEXT && shape.matcher(text).matches()) return text;
            }
        }
        if (depth == 0) return null;
        String found = null;
        for (int index = 0; index < size; index++) {
            Object value = attributes.valueAt(index);
            if (value instanceof List) {
                for (Object child : (List<?>) value) {
                    String hit = search(child, shape, depth - 1, budget);
                    if (hit == NOT_ONE || (hit != null && found != null)) return NOT_ONE;
                    if (hit != null) found = hit;
                }
            } else if (value != null && !(value instanceof String)) {
                String hit = search(value, shape, depth - 1, budget);
                if (hit == NOT_ONE || (hit != null && found != null)) return NOT_ONE;
                if (hit != null) found = hit;
            }
        }
        return found;
    }

    private static void remember(String number, Count count) {
        synchronized (marked) {
            for (int index = 0; index < REMEMBERED; index++) {
                if (marked[index] instanceof WeakReference && ((WeakReference<?>) marked[index]).get() == number) {
                    markedCounts[index] = count;
                    return;
                }
            }
            marked[nextMark] = new WeakReference<>(number);
            markedCounts[nextMark] = count;
            nextMark = (nextMark + 1) % REMEMBERED;
        }
        HookStatus.bound(FamilyNames.LOCAL_PROFILE_COUNTS, "Bloks split count");
        Logger.printDebug(() -> "Local profile counts: found a " + count + " count drawn apart from its word");
    }

    /** Which count [text] is the number of, when it's the very string of a remembered split count. */
    @Nullable
    private static Count markedCount(String text) {
        synchronized (marked) {
            for (int index = 0; index < REMEMBERED; index++) {
                if (marked[index] instanceof WeakReference && ((WeakReference<?>) marked[index]).get() == text) {
                    return markedCounts[index];
                }
            }
        }
        return null;
    }

    private static boolean anyCountTyped() {
        return !clean(Settings.LOCAL_FRIENDS.get()).isEmpty() || !clean(Settings.LOCAL_FOLLOWERS.get()).isEmpty()
                || !clean(Settings.LOCAL_FOLLOWING.get()).isEmpty();
    }

    /**
     * Injection point, first thing in the profile screen's onFragmentCreate: remembers the screen.
     * Never throws.
     */
    public static void opened(@Nullable Object profileScreen) {
        try {
            HookStatus.invoked(FamilyNames.LOCAL_PROFILE_COUNTS);
            screen = new WeakReference<>(profileScreen);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LOCAL_PROFILE_COUNTS, "profile opened", failure);
        }
    }

    /**
     * Injection point, before every return of a Bloks node's string getter: the string Facebook has,
     * or the same line with your own count in it. Never throws.
     */
    @Nullable
    public static String text(@Nullable String text) {
        try {
            if (text == null || text.length() > LONGEST_TEXT || !hasDigit(text)) return text;
            if (!Utils.settingsReady() || !Settings.LOCAL_PROFILE_COUNTS.get()) return text;
            String friends = clean(Settings.LOCAL_FRIENDS.get());
            String followers = clean(Settings.LOCAL_FOLLOWERS.get());
            String following = clean(Settings.LOCAL_FOLLOWING.get());
            if (friends.isEmpty() && followers.isEmpty() && following.isEmpty()) return text;
            Count split = markedCount(text);
            String swapped = split == null ? rewrite(text, friends, followers, following)
                    : number(text, split == Count.FRIENDS ? friends : split == Count.FOLLOWERS ? followers : following);
            if (swapped.equals(text)) return text;
            if (!ownProfileOnTop()) return text;
            HookStatus.bound(FamilyNames.LOCAL_PROFILE_COUNTS, "Bloks string");
            Logger.printDebug(() -> "Local profile counts: swapped a count line on your own profile");
            return swapped;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LOCAL_PROFILE_COUNTS, "count line", failure);
            Logger.printException(() -> "Local profile counts: could not read a Bloks string", failure);
            return text;
        }
    }

    /**
     * [text] with the number before each friends, followers or following word swapped for the
     * matching count in [friends], [followers] or [following]. A blank count leaves its number
     * alone. Pure, so the matching is tested without a phone.
     */
    public static String rewrite(String text, String friends, String followers, String following) {
        String result = text;
        result = swap(FRIENDS, result, friends);
        result = swap(FOLLOWERS, result, followers);
        result = swap(FOLLOWING, result, following);
        return result;
    }

    /** A split count's number [text] as [digits], its spaces kept, or [text] when [digits] is blank. */
    private static String number(String text, String digits) {
        if (digits.isEmpty()) return text;
        Matcher matcher = BARE_NUMBER.matcher(text);
        if (!matcher.matches()) return text;
        return matcher.group(1) + formatted(digits) + matcher.group(3);
    }

    private static String swap(Pattern pattern, String text, String digits) {
        if (digits.isEmpty()) return text;
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return text;
        String shown = formatted(digits);
        StringBuffer out = new StringBuffer();
        do {
            matcher.appendReplacement(out, Matcher.quoteReplacement(shown + matcher.group(2) + matcher.group(3)));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }

    /** [digits] with the grouping the phone's language uses. */
    public static String formatted(String digits) {
        try {
            return NumberFormat.getIntegerInstance(Locale.getDefault()).format(Long.parseLong(digits));
        } catch (NumberFormatException tooLong) {
            return digits;
        }
    }

    /**
     * What a typed count is kept as: its digits, without leading zeros, at most {@link #MAX_DIGITS}
     * of them. Anything with another character in it, or longer, is kept as blank, which shows
     * Facebook's own count.
     */
    public static String clean(@Nullable String typed) {
        if (typed == null) return "";
        String trimmed = typed.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DIGITS) return "";
        for (int index = 0; index < trimmed.length(); index++) {
            char c = trimmed.charAt(index);
            if (c < '0' || c > '9') return "";
        }
        int start = 0;
        while (start < trimmed.length() - 1 && trimmed.charAt(start) == '0') start++;
        return trimmed.substring(start);
    }

    private static boolean hasDigit(String text) {
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c >= '0' && c <= '9') return true;
        }
        return false;
    }

    /** Whether the newest profile screen is on top and shows the signed-in user's profile. */
    private static boolean ownProfileOnTop() throws ReflectiveOperationException {
        Object current = screen.get();
        if (current == null) return false;
        if (!resumed(current)) return false;
        Method arguments = current.getClass().getMethod("getArguments");
        Object bundle = arguments.invoke(current);
        long id = bundle instanceof Bundle ? ((Bundle) bundle).getLong(PROFILE_ID_ARGUMENT, -1L) : -1L;
        return isOwn(id, viewerId(current));
    }

    /** True when the screen can't say, since only the one on top asks while it draws. */
    private static boolean resumed(Object current) {
        try {
            return (Boolean) current.getClass().getMethod("isResumed").invoke(current);
        } catch (ReflectiveOperationException | ClassCastException unknown) {
            return true;
        }
    }

    /**
     * Whether a profile id argument names the signed-in user: no id at all means your own profile,
     * and otherwise it must equal [viewerId]. Nothing is shown as yours when the viewer is unknown.
     */
    static boolean isOwn(long profileId, @Nullable String viewerId) {
        if (viewerId == null || viewerId.isEmpty()) return false;
        if (profileId <= 0) return true;
        return Long.toString(profileId).equals(viewerId);
    }

    /**
     * The signed-in user's id from the ViewerContext the screen holds, or else from its
     * FbUserSession, or null. Facebook 586 fills the ViewerContext field only for View As, so on a
     * plain profile the session is where the id is.
     */
    @Nullable
    static String viewerId(Object current) throws IllegalAccessException {
        String fromSession = null;
        for (Class<?> owner = current.getClass(); owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                String type = field.getType().getName();
                if (VIEWER_CONTEXT.equals(type)) {
                    field.setAccessible(true);
                    String id = userId(field.get(current));
                    if (id != null) return id;
                } else if (fromSession == null && USER_SESSION.equals(type)) {
                    field.setAccessible(true);
                    fromSession = StorySeen.account(field.get(current));
                }
            }
        }
        return fromSession;
    }

    @Nullable
    private static String userId(@Nullable Object context) throws IllegalAccessException {
        if (context == null) return null;
        try {
            Field user = context.getClass().getDeclaredField(USER_ID_FIELD);
            user.setAccessible(true);
            Object id = user.get(context);
            return id instanceof String && !((String) id).isEmpty() ? (String) id : null;
        } catch (NoSuchFieldException missing) {
            return null;
        }
    }

    /** Forgets the screen and the split counts, for tests. */
    static void forgetForTests() {
        screen = new WeakReference<>(null);
        synchronized (marked) {
            Arrays.fill(marked, null);
            Arrays.fill(markedCounts, null);
            nextMark = 0;
        }
    }
}
