/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.profile;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Friends no longer on your list (#597). Facebook never says who unfriended you, but it does load
 * your whole friends list when you scroll Profile, Friends, All friends to its end. While Keep
 * track of your friends list is on, the patch hands that list here each time the All friends
 * section reads it, and once the list has no next page left, it's kept on this phone. The next time
 * the list is read to its end, anyone who was on the kept copy and isn't on the new one goes on the
 * Friends no longer on your list list with the dates of both reads, and a toast names them. Someone
 * who unfriended you, someone you unfriended and someone who closed their account all drop off the
 * same way, so the list says "no longer on your list" and never "unfriended you".
 *
 * <p>The section that handles the list's load is the one Facebook draws everyone's All friends
 * with, so the patch hands it over first ({@link #section}), and only a list the section marks as
 * your own, with nothing typed in its search, is read. Facebook's names for those two marks are
 * obfuscated, so the patch fills in {@link #ownListField} and {@link #noSearchField}. Friends are
 * kept by their id with their name, nothing leaves the phone, and no name or id is logged.
 *
 * <p>Off, paused, before the settings are ready, a list that isn't your own or isn't read to its
 * end, or anything here failing, and nothing is kept or compared.
 */
public final class FriendList {
    static final String FAMILY = FamilyNames.FRIEND_LIST;

    /** The Hook status name of the hook. */
    static final String HOOK = "All friends list";

    /** Counted each time your list was read to its end and kept. */
    static final String READ_TO_END = "Friends list read to its end";

    /** Counted each time a list was left alone because it's someone else's. */
    static final String NOT_YOURS = "Friends list left alone: someone else's";

    /** Counted each time a list was left alone because its search had text in it. */
    static final String SEARCHED = "Friends list left alone: a search was typed";

    /** Counted for each friend a read found missing since the one before. */
    static final String DROPPED_OFF = "Friends no longer on your list found";

    /** A tree keys each field by its name's hashCode. */
    static final int ID_KEY = "id".hashCode();
    static final int NAME_KEY = "name".hashCode();

    /** The tree getter a friend's row answers a String field with. */
    static final String STRING_FIELD = "getString";

    /** The paginated list's own fields, which Facebook keeps under these names. */
    static final String ROWS = "list";
    static final String HAS_NEXT_PAGE = "hasNextPage";
    static final String LOADING_NEXT = "isLoadingNext";
    static final String LOAD_ERROR = "paginationLoadHadError";

    /** The file the kept list and the friends no longer on it live in, apart from the settings. */
    static final String FILE = "hushfacebook_friend_list";
    static final String FRIENDS = "friends";
    static final String FRIENDS_AT = "friends_at";
    static final String GONE = "gone";
    /** The friends of a read set aside as only partly loaded, by {@link #signature}. */
    static final String SUSPECT = "suspect";
    static final String SUSPECT_AT = "suspect_at";

    /** More than this many gone at once, and more than half the list, and the read is set aside once. */
    static final int SUSPECT_MIN = 10;

    /**
     * How long after a read set aside the same list has to be read again to count, so the few
     * times Facebook redraws the same page in a row don't confirm it.
     */
    static final long CONFIRM_AFTER_MS = 2 * 60 * 1000L;

    /** Counted each time a read looked partly loaded and was set aside. */
    static final String SET_ASIDE = "Friends list read set aside: looked partly loaded";

    /** The most friends no longer on your list kept, newest first. */
    static final int MAX_GONE = 500;

    /** How many names a toast spells out before it counts the rest. */
    static final int NAMES_IN_TOAST = 3;

    /** A friend who was on your list at one read to its end and not at the next. */
    public static final class Gone {
        public final String id;
        public final String name;
        /** When the last read that still had them was. */
        public final long lastSeenAt;
        /** When the read that no longer had them was. */
        public final long noticedAt;

        Gone(String id, String name, long lastSeenAt, long noticedAt) {
            this.id = id;
            this.name = name;
            this.lastSeenAt = lastSeenAt;
            this.noticedAt = noticedAt;
        }
    }

    /** The section of the load being handled now, from {@link #section} until {@link #allFriendsPage}. */
    private static final ThreadLocal<Object> SECTION = new ThreadLocal<>();

    /** The friends of the last list kept this process, so the same list read again isn't compared twice. */
    @Nullable
    private static volatile Map<String, String> lastKept;

    private static final Object STORE_LOCK = new Object();

    /** Hands kept lists to the store, on a background thread in the app and at once in tests. */
    static volatile Runner runner = Utils::runOnBackgroundThread;

    interface Runner {
        void run(Runnable task);
    }

    private FriendList() {
    }

    /** Filled in by the patch: the name of the section's flag that's true when the list is your own. */
    public static String ownListField() {
        return "";
    }

    /** Filled in by the patch: the name of the section's flag that's true while nothing is typed in its search. */
    public static String noSearchField() {
        return "";
    }

    /** Injection point: the All friends section, right after the handler of its list's load casts it. Never throws. */
    public static void section(@Nullable Object section) {
        try {
            SECTION.set(section);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /**
     * Injection point: the paginated list of friends the section's load brought, everything loaded
     * so far, right after the section reads it off the load. Reads it and hands it back untouched.
     * Never throws.
     */
    public static void allFriendsPage(@Nullable Object page) {
        allFriendsPage(page, ownListField(), noSearchField());
    }

    /** {@link #allFriendsPage(Object)} with the names of the section's flags handed in, for the tests. */
    static void allFriendsPage(@Nullable Object page, String ownField, String noSearchField) {
        Object section = SECTION.get();
        SECTION.remove();
        try {
            HookStatus.invoked(FAMILY);
            if (page == null || !Utils.settingsReady() || !Settings.FRIEND_LIST_WATCH.get()) return;
            HookStatus.bound(FAMILY, HOOK);
            Boolean own = flag(section, ownField);
            Boolean noSearch = flag(section, noSearchField);
            if (own == null || noSearch == null) return;
            if (!own) {
                HookStatus.counted(FAMILY, NOT_YOURS);
                return;
            }
            if (!noSearch) {
                HookStatus.counted(FAMILY, SEARCHED);
                return;
            }
            Map<String, String> friends = readToEnd(page);
            if (friends == null || friends.equals(lastKept)) return;
            lastKept = friends;
            HookStatus.counted(FAMILY, READ_TO_END);
            Logger.printDebug(() -> FAMILY + ": your list was read to its end, " + friends.size() + " friends");
            long now = System.currentTimeMillis();
            runner.run(() -> kept(friends, now));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /** [section]'s boolean field [name], or null with the miss reported when it can't be read. */
    @Nullable
    private static Boolean flag(@Nullable Object section, String name) throws IllegalAccessException {
        if (section == null || name == null || name.isEmpty()) {
            HookStatus.missingMember(FAMILY, "field", section == null ? "section" : section.getClass().getName(), name);
            return null;
        }
        Field field = field(section.getClass(), name);
        if (field == null || field.getType() != boolean.class) {
            HookStatus.missingMember(FAMILY, "field", section.getClass().getName(), name);
            return null;
        }
        return field.getBoolean(section);
    }

    /**
     * The friends on [page] by id, in Facebook's order, once it has no next page, isn't loading one
     * and its last load didn't fail. Null before that, and with the miss reported when the list or
     * its rows can't be read.
     */
    @Nullable
    static Map<String, String> readToEnd(Object page) throws ReflectiveOperationException {
        Field rows = field(page.getClass(), ROWS);
        Field hasNext = field(page.getClass(), HAS_NEXT_PAGE);
        if (rows == null || hasNext == null) {
            HookStatus.missingMember(FAMILY, "field", page.getClass().getName(), ROWS + " or " + HAS_NEXT_PAGE);
            return null;
        }
        if (hasNext.getBoolean(page) || is(page, LOADING_NEXT) || is(page, LOAD_ERROR)) return null;
        Object list = rows.get(page);
        if (!(list instanceof List)) return null;
        Map<String, String> friends = new LinkedHashMap<>();
        Method string = null;
        for (Object row : (List<?>) list) {
            if (row == null) continue;
            if (string == null || string.getDeclaringClass() != row.getClass() && !string.getDeclaringClass().isInstance(row)) {
                try {
                    string = row.getClass().getMethod(STRING_FIELD, int.class);
                } catch (NoSuchMethodException absent) {
                    HookStatus.missingMember(FAMILY, "method", row.getClass().getName(), STRING_FIELD + "(int)");
                    return null;
                }
            }
            Object id = string.invoke(row, ID_KEY);
            if (!(id instanceof String) || ((String) id).isEmpty()) continue;
            Object name = string.invoke(row, NAME_KEY);
            friends.put((String) id, clean(name instanceof String ? (String) name : ""));
        }
        if (friends.isEmpty()) return null;
        return friends;
    }

    private static boolean is(Object page, String name) throws IllegalAccessException {
        Field field = field(page.getClass(), name);
        return field != null && field.getType() == boolean.class && field.getBoolean(page);
    }

    /** [type]'s field [name], its own or one it inherits, made readable. Null when there's none. */
    @Nullable
    private static Field field(Class<?> type, String name) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Field field = at.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Up a class.
            }
        }
        return null;
    }

    /** A name without the tabs and line breaks the store separates its fields with. */
    static String clean(String name) {
        return name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    // ------------------------------------------------------------------ the kept list

    /**
     * Keeps [friends] as your list as of [now], and puts anyone on the list kept before who isn't
     * on it any more at the top of the friends no longer on it. Says so in a toast: who dropped off,
     * or, the first time, that the list is kept now.
     *
     * <p>A read missing more than half the kept list, and more than {@link #SUSPECT_MIN} people, looks
     * like a list Facebook only partly loaded, so it's set aside and changes nothing. The same list
     * read again at least {@link #CONFIRM_AFTER_MS} later is taken as it is.
     */
    static void kept(Map<String, String> friends, long now) {
        List<Gone> gone;
        boolean first;
        synchronized (STORE_LOCK) {
            SharedPreferences store = store();
            if (store == null) return;
            Map<String, String> before = parseFriends(store.getString(FRIENDS, null));
            long beforeAt = store.getLong(FRIENDS_AT, 0);
            first = before.isEmpty();
            gone = first ? Collections.emptyList() : compare(before, beforeAt, friends, now);
            if (suspect(gone.size(), before.size())) {
                String signature = signature(friends);
                String setAside = store.getString(SUSPECT, null);
                long setAsideAt = store.getLong(SUSPECT_AT, 0);
                boolean confirmed = signature.equals(setAside) && now - setAsideAt >= CONFIRM_AFTER_MS;
                if (!confirmed) {
                    if (!signature.equals(setAside)) store.edit().putString(SUSPECT, signature).putLong(SUSPECT_AT, now).apply();
                    // So the same list read again later in this process comes back here.
                    lastKept = null;
                    HookStatus.counted(FAMILY, SET_ASIDE);
                    Logger.printDebug(() -> FAMILY + ": a read missing " + gone.size() + " of " + before.size()
                            + " was set aside");
                    return;
                }
            }
            List<Gone> all = merge(gone, parseGone(store.getString(GONE, null)));
            store.edit()
                    .putString(FRIENDS, formatFriends(friends))
                    .putLong(FRIENDS_AT, now)
                    .putString(GONE, formatGone(all))
                    .remove(SUSPECT)
                    .remove(SUSPECT_AT)
                    .apply();
        }
        if (!gone.isEmpty()) {
            for (int each = 0; each < gone.size(); each++) HookStatus.counted(FAMILY, DROPPED_OFF);
            Logger.printDebug(() -> FAMILY + ": " + gone.size() + " no longer on your list");
        }
        String message = first ? L10n.f("Hushfacebook kept a copy of your %1$d friends. Next time you scroll the list to "
                + "its end, it tells you who's no longer on it.", friends.size()) : toast(gone);
        if (message != null) Utils.showToastLong(message);
    }

    /** What the toast says about [gone], or null when nobody dropped off. */
    @Nullable
    static String toast(List<Gone> gone) {
        if (gone.isEmpty()) return null;
        if (gone.size() == 1) return L10n.f("%1$s is no longer on your friends list.", L10n.isolate(gone.get(0).name));
        List<String> names = new ArrayList<>();
        for (int index = 0; index < Math.min(NAMES_IN_TOAST, gone.size()); index++) {
            names.add(L10n.isolate(gone.get(index).name));
        }
        String shown = android.text.TextUtils.join(", ", names);
        return gone.size() <= NAMES_IN_TOAST
                ? L10n.f("No longer on your friends list: %1$s.", shown)
                : L10n.f("No longer on your friends list: %1$s and %2$d more.", shown, gone.size() - NAMES_IN_TOAST);
    }

    /** Whether [missing] of [kept] friends gone at once looks like a list only partly loaded. */
    static boolean suspect(int missing, int kept) {
        return missing > SUSPECT_MIN && missing * 2 > kept;
    }

    /** The same text for the same friends, whatever their order. */
    static String signature(Map<String, String> friends) {
        List<String> ids = new ArrayList<>(friends.keySet());
        Collections.sort(ids);
        return ids.size() + ":" + android.text.TextUtils.join(",", ids).hashCode();
    }

    /** Everyone on [before], read at [beforeAt], who isn't on [now], read at [nowAt], by name. */
    static List<Gone> compare(Map<String, String> before, long beforeAt, Map<String, String> now, long nowAt) {
        List<Gone> gone = new ArrayList<>();
        for (Map.Entry<String, String> friend : before.entrySet()) {
            if (!now.containsKey(friend.getKey())) {
                gone.add(new Gone(friend.getKey(), friend.getValue(), beforeAt, nowAt));
            }
        }
        Collections.sort(gone, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return gone;
    }

    /** [fresh] first, then [older] without anyone [fresh] has again, at most {@link #MAX_GONE}. */
    static List<Gone> merge(List<Gone> fresh, List<Gone> older) {
        List<Gone> all = new ArrayList<>(fresh);
        for (Gone entry : older) {
            boolean again = false;
            for (Gone added : fresh) {
                if (added.id.equals(entry.id)) {
                    again = true;
                    break;
                }
            }
            if (!again) all.add(entry);
        }
        return all.size() > MAX_GONE ? new ArrayList<>(all.subList(0, MAX_GONE)) : all;
    }

    /** The friends no longer on your list, the latest found first. */
    public static List<Gone> gone() {
        synchronized (STORE_LOCK) {
            SharedPreferences store = store();
            return store == null ? Collections.emptyList() : parseGone(store.getString(GONE, null));
        }
    }

    /** How many friends the kept copy of your list has, 0 before the first read to its end. */
    public static int keptCount() {
        synchronized (STORE_LOCK) {
            SharedPreferences store = store();
            return store == null ? 0 : parseFriends(store.getString(FRIENDS, null)).size();
        }
    }

    /** When the kept copy of your list was read, 0 before the first read to its end. */
    public static long keptAt() {
        synchronized (STORE_LOCK) {
            SharedPreferences store = store();
            return store == null ? 0 : store.getLong(FRIENDS_AT, 0);
        }
    }

    /** Takes [entry] off the friends no longer on your list. */
    public static void remove(Gone entry) {
        synchronized (STORE_LOCK) {
            SharedPreferences store = store();
            if (store == null) return;
            List<Gone> all = parseGone(store.getString(GONE, null));
            List<Gone> kept = new ArrayList<>();
            for (Gone each : all) {
                if (!(each.id.equals(entry.id) && each.noticedAt == entry.noticedAt)) kept.add(each);
            }
            store.edit().putString(GONE, formatGone(kept)).apply();
        }
    }

    /** Empties the friends no longer on your list. The kept list stays, for the next read to compare with. */
    public static void clear() {
        synchronized (STORE_LOCK) {
            SharedPreferences store = store();
            if (store != null) store.edit().remove(GONE).apply();
        }
    }

    @Nullable
    private static SharedPreferences store() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String formatFriends(Map<String, String> friends) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> friend : friends.entrySet()) {
            text.append(friend.getKey()).append('\t').append(friend.getValue()).append('\n');
        }
        return text.toString();
    }

    static Map<String, String> parseFriends(@Nullable String text) {
        Map<String, String> friends = new LinkedHashMap<>();
        if (text == null) return friends;
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) friends.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return friends;
    }

    static String formatGone(List<Gone> gone) {
        StringBuilder text = new StringBuilder();
        for (Gone entry : gone) {
            text.append(entry.id).append('\t').append(entry.name).append('\t').append(entry.lastSeenAt)
                    .append('\t').append(entry.noticedAt).append('\n');
        }
        return text.toString();
    }

    static List<Gone> parseGone(@Nullable String text) {
        List<Gone> gone = new ArrayList<>();
        if (text == null) return gone;
        for (String line : text.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 4 || parts[0].isEmpty()) continue;
            try {
                gone.add(new Gone(parts[0], parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[3])));
            } catch (NumberFormatException skipped) {
                // A line some other build wrote differently.
            }
        }
        return gone;
    }

    /** Forgets the list kept this process and everything stored, as a fresh install would start. Tests only. */
    static void forget() {
        lastKept = null;
        SECTION.remove();
        synchronized (STORE_LOCK) {
            SharedPreferences store = store();
            if (store != null) store.edit().clear().commit();
        }
    }

    /** Forgets the list kept this process only, as a new Facebook process would start. Tests only. */
    static void newProcess() {
        lastKept = null;
        SECTION.remove();
    }
}
