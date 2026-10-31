/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.profile;

import android.os.Bundle;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
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
 * <p>The shown profile is the newest profile screen that's on top. {@link #opened} keeps the
 * screen, and its {@code com.facebook.katana.profile.id} argument is compared to the signed-in
 * user's id, which the screen holds in its ViewerContext. A screen with no id shows your own
 * profile. A profile that isn't yours, a screen that's gone behind another, a switch that's off or a
 * count left blank leaves Facebook's text alone. Any failure here does the same.
 */
public final class LocalProfileCounts {
    /** The argument Facebook's profile screen is given the profile's id in. */
    static final String PROFILE_ID_ARGUMENT = "com.facebook.katana.profile.id";

    /** The type of the object holding the signed-in user's id, and the field it's in. */
    static final String VIEWER_CONTEXT = "com.facebook.auth.viewercontext.ViewerContext";
    static final String USER_ID_FIELD = "mUserId";

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

    private static volatile WeakReference<Object> screen = new WeakReference<>(null);

    private LocalProfileCounts() {
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
            String swapped = rewrite(text, friends, followers, following);
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

    /** The signed-in user's id from the ViewerContext the screen holds, or null. */
    @Nullable
    static String viewerId(Object current) throws IllegalAccessException {
        for (Class<?> owner = current.getClass(); owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (!VIEWER_CONTEXT.equals(field.getType().getName())) continue;
                field.setAccessible(true);
                Object context = field.get(current);
                if (context == null) return null;
                try {
                    Field user = context.getClass().getDeclaredField(USER_ID_FIELD);
                    user.setAccessible(true);
                    Object id = user.get(context);
                    return id instanceof String ? (String) id : null;
                } catch (NoSuchFieldException missing) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Forgets the screen, for tests. */
    static void forgetForTests() {
        screen = new WeakReference<>(null);
    }
}
