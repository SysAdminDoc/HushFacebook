/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.media.PostMenuSpeed;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the post menu sheet's hook asks. Facebook tags each row of a post's three-dot menu with a
 * kind, a constant of one enum: SEE_MORE for Interested, SEE_LESS for Not interested, SAVE, REPORT,
 * CAST and about fifty more. One method sorts a filled menu into the sheet's parts, a holder with a
 * field for each of the Interested, Not interested and Manage your Feed rows, a list for the first
 * page and one for More options, and the hook hands that holder here before Facebook draws it.
 *
 * <p>Post menu items (#475) takes the kinds picked in its list out of every part, and writes down
 * the kinds Facebook has shown on this phone, so its list offers the real ones beside the common
 * ones. Keep the reel speed's Playback speed row (#473) has no kind, so Facebook files it under More
 * options; with {@link Settings#POST_MENU_SPEED_ON_TOP} on it moves to the end of the first page, the
 * way Facebook's own presenter moves its Why am I seeing this row there after the sort.
 *
 * <p>The holder's fields are found by what they hold, and the kind by the names the patch hands over:
 * the row metadata's class and its kind field, and the first page's list. Settings that aren't ready
 * yet, a holder of another shape, or a failure in here, and the sheet stays as Facebook sorted it.
 */
public final class PostMenuItems {
    /** Counted under the patch's name each time a sheet loses a row. */
    static final String HIDDEN = "Post menu row kept out";

    /** Counted under Keep the reel speed's name each time Playback speed goes to the first page. */
    static final String SPEED_MOVED = "post menu Playback speed put on the first page";

    /** At most this many kinds are written down, so a renamed enum can't grow the setting forever. */
    static final int SEEN_LIMIT = 100;

    /**
     * The longest the list of kinds to keep out gets, in chars, each one byte in a settings file.
     * Room for every one of Facebook's fifty-odd kinds.
     */
    public static final int MAX_HIDDEN_CHARS = 2048;

    /**
     * The kinds the list offers before Facebook has shown a menu, in the order a menu usually has
     * them. Each is a constant of 587's kind enum.
     */
    static final List<String> COMMON = Collections.unmodifiableList(Arrays.asList(
            "SEE_MORE", "SEE_LESS", "TUNE_YOUR_ALGORITHM", "SAVE", "REPORT", "CAST", "FAVORITE", "WAIST",
            "NOTIFY", "COPY_LINK", "HIDE", "SNOOZE", "UNFOLLOW"));

    /** An enum constant's name, the only thing the settings ever hold. */
    private static final Pattern KIND = Pattern.compile("[A-Z][A-Z0-9_]*");

    private static final String FAMILY = FamilyNames.POST_MENU_ITEMS;

    /** The kinds written down so far, read once, so a menu that shows nothing new writes nothing. */
    @Nullable
    private static volatile Set<String> seen;

    private static volatile boolean logged;

    /** How the last holder handed over is read, kept since a build sorts every menu into one shape. */
    @Nullable
    private static volatile Shape shape;

    private PostMenuItems() {
    }

    /**
     * The hook in front of the sorting method's return, handed the holder it answers, the class of a
     * row's metadata (Java's name for it), the metadata's kind field and the holder's first page list.
     * Changes the holder in place. Never throws.
     */
    public static void arrange(@Nullable Object holder, @Nullable String metadata, @Nullable String kindField,
            @Nullable String firstPage) {
        // PatchFamily names Settings, which mustn't load before the extension has its context.
        if (holder == null || metadata == null || kindField == null || firstPage == null || !Utils.settingsReady()) {
            return;
        }
        Shape read = null;
        try {
            if (PatchFamily.POST_MENU_ITEMS.inBuild()) {
                HookStatus.invoked(FAMILY);
                read = shapeOf(holder, metadata, kindField, firstPage);
                hide(holder, read);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "post menu items", failure);
        }
        try {
            if (PostMenuSpeed.onFirstPage()) {
                if (read == null) read = shapeOf(holder, metadata, kindField, firstPage);
                speedOnTop(holder, read);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.KEEP_REEL_SPEED, "post menu speed on the first page", failure);
        }
    }

    /** Takes the rows of a picked kind out of [holder]'s lists and its rows of their own. */
    private static void hide(Object holder, Shape read) throws IllegalAccessException {
        List<String> kinds = new ArrayList<>();
        Set<String> hidden = parse(Settings.HIDDEN_POST_MENU_ITEMS.get());
        int dropped = 0;
        for (Field field : read.lists) {
            Object value = field.get(holder);
            if (!(value instanceof List)) continue;
            for (Iterator<?> rows = ((List<?>) value).iterator(); rows.hasNext(); ) {
                String kind = read.kind(rows.next());
                if (kind == null) continue;
                kinds.add(kind);
                if (hidden.contains(kind)) {
                    rows.remove();
                    dropped++;
                }
            }
        }
        for (Field field : read.rows) {
            String kind = read.kind(field.get(holder));
            if (kind == null) continue;
            kinds.add(kind);
            if (hidden.contains(kind)) {
                field.set(holder, null);
                dropped++;
            }
        }
        remember(kinds);
        if (dropped == 0) return;
        HookStatus.bound(FAMILY, "post menu items");
        for (int i = 0; i < dropped; i++) HookStatus.counted(FAMILY, HIDDEN);
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "Post menu items: kept a row out of a post menu");
        }
    }

    /** Moves Keep the reel speed's Playback speed row from More options to the end of the first page. */
    private static void speedOnTop(Object holder, Shape read) throws IllegalAccessException {
        Object top = read.firstPage.get(holder);
        if (!(top instanceof List)) return;
        @SuppressWarnings("unchecked")
        List<Object> firstPage = (List<Object>) top;
        for (Field field : read.lists) {
            if (field.equals(read.firstPage)) continue;
            Object value = field.get(holder);
            if (!(value instanceof List)) continue;
            for (Iterator<?> rows = ((List<?>) value).iterator(); rows.hasNext(); ) {
                Object row = rows.next();
                if (!PostMenuSpeed.isRow(row)) continue;
                rows.remove();
                firstPage.add(row);
                HookStatus.bound(FamilyNames.KEEP_REEL_SPEED, "post menu speed on the first page");
                HookStatus.counted(FamilyNames.KEEP_REEL_SPEED, SPEED_MOVED);
                return;
            }
        }
    }

    /** How a holder of [holder]'s class is read, worked out once for the class. */
    static Shape shapeOf(Object holder, String metadata, String kindField, String firstPage)
            throws ReflectiveOperationException {
        Shape known = shape;
        if (known != null && known.holder == holder.getClass() && known.metadataName.equals(metadata)
                && known.kindName.equals(kindField) && known.firstPage.getName().equals(firstPage)) {
            return known;
        }
        Shape made = new Shape(holder.getClass(), metadata, kindField, firstPage);
        shape = made;
        return made;
    }

    /** The fields of a holder's class this reads, and how a row's kind is found. */
    static final class Shape {
        final Class<?> holder;
        final String metadataName;
        final String kindName;
        /** The holder's lists of rows. */
        final List<Field> lists = new ArrayList<>();
        /** The holder's fields for one row each, the ones that can be emptied. */
        final List<Field> rows = new ArrayList<>();
        final Field firstPage;
        private final Class<?> metadata;
        private final Field kind;
        /** The field of the row class seen last that holds its metadata, or null when it has none. */
        @Nullable
        private volatile Class<?> rowClass;
        @Nullable
        private volatile Field rowMetadata;

        Shape(Class<?> holder, String metadataName, String kindName, String firstPageName)
                throws ReflectiveOperationException {
            this.holder = holder;
            this.metadataName = metadataName;
            this.kindName = kindName;
            ClassLoader loader = holder.getClassLoader();
            metadata = Class.forName(metadataName, false, loader);
            kind = field(metadata, kindName);
            Field top = null;
            for (Class<?> type = holder; type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    Class<?> held = field.getType();
                    if (Modifier.isStatic(modifiers) || held.isPrimitive()) continue;
                    field.setAccessible(true);
                    if (List.class.isAssignableFrom(held)) {
                        lists.add(field);
                        if (field.getName().equals(firstPageName)) top = field;
                    } else if (!Modifier.isFinal(modifiers) && !held.getName().startsWith("java.")) {
                        rows.add(field);
                    }
                }
            }
            if (top == null) throw new NoSuchFieldException(holder.getName() + " holds no list " + firstPageName);
            firstPage = top;
        }

        /** [row]'s kind, the enum constant's name, or null when it has none. */
        @Nullable
        String kind(@Nullable Object row) throws IllegalAccessException {
            if (row == null) return null;
            Field held = metadataOf(row.getClass());
            if (held == null) return null;
            Object meta = held.get(row);
            if (meta == null) return null;
            Object value = kind.get(meta);
            if (!(value instanceof Enum)) return null;
            String name = ((Enum<?>) value).name();
            return KIND.matcher(name).matches() ? name : null;
        }

        @Nullable
        private Field metadataOf(Class<?> type) {
            if (type == rowClass) return rowMetadata;
            Field found = null;
            for (Class<?> at = type; at != null && at != Object.class && found == null; at = at.getSuperclass()) {
                for (Field field : at.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && field.getType() == metadata) {
                        field.setAccessible(true);
                        found = field;
                        break;
                    }
                }
            }
            rowMetadata = found;
            rowClass = type;
            return found;
        }

        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
                try {
                    Field field = at.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // Declared higher up, if anywhere.
                }
            }
            throw new NoSuchFieldException(type.getName() + "." + name);
        }
    }

    /** The kinds the list offers: the ones Facebook has shown, the common ones, then any picked earlier. */
    public static List<String> choices() {
        Set<String> all = new LinkedHashSet<>(parse(Settings.SEEN_POST_MENU_ITEMS.savedValue()));
        all.addAll(COMMON);
        all.addAll(parse(Settings.HIDDEN_POST_MENU_ITEMS.savedValue()));
        return new ArrayList<>(all);
    }

    /** The kinds picked to keep out, as saved. */
    public static Set<String> hidden() {
        return parse(Settings.HIDDEN_POST_MENU_ITEMS.savedValue());
    }

    /** What {@link #hide(Collection)} did with a pick. */
    public enum Hidden {
        SAVED,
        /** The kinds need more than {@link #MAX_HIDDEN_CHARS}. Nothing was saved. */
        NO_ROOM,
        /** The phone didn't keep the write. The old list stands, live and on disk. */
        NOT_STORED
    }

    /**
     * Saves [kinds] as the ones to keep out, dropping anything that isn't a kind. When the kinds need
     * more than {@link #MAX_HIDDEN_CHARS}, it saves nothing and says so, so a picked kind is never lost
     * in silence.
     */
    public static Hidden hide(Collection<String> kinds) {
        String picked = join(kinds);
        String kept = clean(picked);
        if (count(kept) < count(picked)) return Hidden.NO_ROOM;
        return Settings.HIDDEN_POST_MENU_ITEMS.save(kept) ? Hidden.SAVED : Hidden.NOT_STORED;
    }

    /**
     * [saved] as the list of kinds to keep out is stored: each kind once, in order, as far as
     * {@link #MAX_HIDDEN_CHARS} reaches. A kind this build doesn't name is kept like any other, so the
     * rows of a newer Facebook come through a settings file.
     */
    public static String clean(@Nullable String saved) {
        StringBuilder list = new StringBuilder();
        for (String kind : parse(saved)) {
            int length = list.length() == 0 ? kind.length() : list.length() + 1 + kind.length();
            if (length > MAX_HIDDEN_CHARS) break;
            if (list.length() > 0) list.append(',');
            list.append(kind);
        }
        return list.toString();
    }

    /** Whether [list] is a list of kinds to keep out exactly as {@link #clean} stores it. */
    public static boolean isClean(@Nullable String list) {
        return list != null && list.equals(clean(list));
    }

    /** How many kinds [list] names. */
    public static int count(@Nullable String list) {
        return parse(list).size();
    }

    /** What the list calls [kind]: the row's words where they're known, else the kind read out in words. */
    public static String label(String kind) {
        switch (kind) {
            case "SEE_MORE": return L10n.t("Interested");
            case "SEE_LESS": return L10n.t("Not interested");
            case "TUNE_YOUR_ALGORITHM": return L10n.t("Manage your Feed");
            case "SAVE": return L10n.t("Save");
            case "REPORT": return L10n.t("Report");
            case "CAST": return L10n.t("Cast");
            case "FAVORITE": return L10n.t("Add to Favorites");
            case "WAIST": return L10n.t("Why am I seeing this post?");
            case "NOTIFY": return L10n.t("Turn on notifications for this post");
            case "COPY_LINK": return L10n.t("Copy link");
            case "HIDE": return L10n.t("Hide post");
            case "SNOOZE": return L10n.t("Snooze");
            case "UNFOLLOW": return L10n.t("Unfollow");
            default: return readable(kind);
        }
    }

    /** [kind] in words, as ASK_META_AI reads "Ask meta ai". */
    static String readable(String kind) {
        String words = kind.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return words.isEmpty() ? kind : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Writes down the kinds in [kinds] it hadn't yet, up to [SEEN_LIMIT], in the order they came. */
    static void remember(List<String> kinds) {
        Set<String> known = seen;
        if (known == null) known = parse(Settings.SEEN_POST_MENU_ITEMS.savedValue());
        Set<String> grown = null;
        for (String kind : kinds) {
            Set<String> current = grown != null ? grown : known;
            if (current.contains(kind) || current.size() >= SEEN_LIMIT) continue;
            if (grown == null) grown = new LinkedHashSet<>(known);
            grown.add(kind);
        }
        if (grown == null) {
            seen = known;
            return;
        }
        seen = Collections.unmodifiableSet(grown);
        Settings.SEEN_POST_MENU_ITEMS.save(join(grown));
    }

    /** Forgets the cached kinds and holder shape, so the next menu reads them again. Tests only. */
    static void forgetForTests() {
        seen = null;
        shape = null;
        logged = false;
    }

    /** The kinds in a saved list, in order, each once. Anything that isn't a kind is dropped. */
    static Set<String> parse(@Nullable String saved) {
        Set<String> kinds = new LinkedHashSet<>();
        if (saved == null) return kinds;
        for (String part : saved.split(",")) {
            String kind = part.trim();
            if (KIND.matcher(kind).matches()) kinds.add(kind);
        }
        return kinds;
    }

    static String join(Collection<String> kinds) {
        return String.join(",", kinds);
    }
}
