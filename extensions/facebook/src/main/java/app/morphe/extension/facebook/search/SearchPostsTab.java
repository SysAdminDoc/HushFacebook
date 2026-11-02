/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import android.content.Context;

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Search posts tab (#339): the Posts chip comes back to the row of chips over search results,
 * right after Reels.
 *
 * <p>Facebook builds that row from the search answer, one chip per tab the answer lists, each
 * labelled with the tab's name. Tapping a chip looks its label up in Facebook's own table of tab
 * labels and opens that tab's results. Posts is still in that table (Facebook calls the tab
 * BLENDED_STORIES), so a chip labelled with it opens the posts results like any other chip; the
 * answer just stopped listing it. The patch hands this class every row Facebook draws (the
 * answer's, the one it rebuilds after a tap, and its fallback row), and with the switch on a Posts
 * chip goes in after Reels (or after the first chip, All, when there's no Reels). A row that
 * already has Posts stays as it came. Facebook hides the row while the posts results are open, as
 * it does for Groups.
 */
public final class SearchPostsTab {
    /** The posts tab, as Facebook's tab enum names it. */
    static final String POSTS_TAB = "BLENDED_STORIES";

    /** The tab Posts goes after. */
    static final String REELS_TAB = "REELS";

    /** The member the report names once the row's chips have been handed over. */
    static final String CHIPS = "search result chips";

    /** What the report counts each time Posts goes into the row. */
    static final String ADDED = "Posts chip added";

    private static final String FAMILY = FamilyNames.SEARCH_POSTS_TAB;

    /** The table tests hand the hook in place of Facebook's; null in the app. */
    @Nullable
    static Map<?, ?> testLabels;

    private SearchPostsTab() {
    }

    /**
     * The hook as Facebook draws the row of chips over search results, handed the chips. The same
     * list comes back when nothing changes, and for a throw.
     */
    public static List<?> chips(List<?> chips) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, CHIPS);
            if (chips == null || chips.isEmpty() || !Utils.settingsReady() || !Settings.SEARCH_POSTS_TAB.get()) {
                return chips;
            }
            return withPosts(chips, testLabels != null ? testLabels : tabLabels(Utils.getContext()));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CHIPS, failure);
            return chips;
        }
    }

    /**
     * [chips] with a Posts chip added where {@link #insertAt} puts it, labelled as [labels] (Facebook's
     * table between tab labels and tabs, either way round) labels the posts tab. The same list
     * when the table has no posts label or the row needs no chip.
     */
    static List<?> withPosts(List<?> chips, @Nullable Map<?, ?> labels) throws ReflectiveOperationException {
        String posts = labelOf(labels, POSTS_TAB);
        if (posts == null) {
            Logger.printDebug(() -> "Search posts tab: no label for the posts tab");
            return chips;
        }
        List<String> names = new ArrayList<>(chips.size());
        for (Object chip : chips) names.add(label(chip));
        int at = insertAt(names, posts, labelOf(labels, REELS_TAB));
        if (at < 0) {
            Logger.printDebug(() -> "Search posts tab: chips stay " + names);
            return chips;
        }
        Object chip = chipLike(chips.get(0), posts);
        if (chip == null) return chips;
        List<Object> out = new ArrayList<>(chips.size() + 1);
        out.addAll(chips);
        out.add(at, chip);
        HookStatus.counted(FAMILY, ADDED);
        Logger.printDebug(() -> "Search posts tab: Posts goes in at " + at + " of " + names);
        return out;
    }

    /**
     * Where Posts goes among the chips' [labels], or -1 for no change: after the [reels] chip, else
     * after the first chip. No change for no chips, or when one is already labelled [posts].
     */
    static int insertAt(List<String> labels, String posts, @Nullable String reels) {
        if (labels.isEmpty() || labels.contains(posts)) return -1;
        int at = reels == null ? -1 : labels.indexOf(reels);
        return at >= 0 ? at + 1 : 1;
    }

    /** The label [labels] gives the tab named [tab], with the tab as the key or the value. */
    @Nullable
    static String labelOf(@Nullable Map<?, ?> labels, String tab) {
        if (labels == null) return null;
        for (Map.Entry<?, ?> entry : labels.entrySet()) {
            if (named(entry.getKey(), tab) && entry.getValue() instanceof String) return (String) entry.getValue();
            if (named(entry.getValue(), tab) && entry.getKey() instanceof String) return (String) entry.getKey();
        }
        return null;
    }

    private static boolean named(Object value, String name) {
        return value instanceof Enum && name.equals(((Enum<?>) value).name());
    }

    /** A chip's label: the first of its own string fields that holds one, or null. */
    @Nullable
    static String label(Object chip) throws IllegalAccessException {
        if (chip == null) return null;
        for (Field field : chip.getClass().getDeclaredFields()) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(chip);
            if (value != null) return (String) value;
        }
        return null;
    }

    /**
     * A new chip of [sample]'s kind, unselected and labelled [label], made the way Facebook makes
     * every chip in the row: its (selected, id, label) constructor, with the label as the id too.
     * Null when the kind has no such constructor.
     */
    @Nullable
    static Object chipLike(Object sample, String label) throws ReflectiveOperationException {
        for (Constructor<?> constructor : sample.getClass().getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length != 3 || types[0] != boolean.class || types[1] != String.class || types[2] != String.class) {
                continue;
            }
            constructor.setAccessible(true);
            return constructor.newInstance(false, label, label);
        }
        Logger.printDebug(() -> "Search posts tab: no chip constructor on " + sample.getClass().getName());
        return null;
    }

    /**
     * Facebook's table between tab labels and tabs for [context]. The patch fills this in with a
     * call to the method that builds it; unpatched it answers null.
     */
    @Nullable
    public static Map<?, ?> tabLabels(Context context) {
        return null;
    }
}
