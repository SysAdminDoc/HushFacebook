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
 * already has Posts stays as it came. Facebook marks the chip of the open tab, and the row it
 * draws over the posts results has none marked, so there the Posts chip goes in marked.
 *
 * <p>Videos (#483) is in the same table (BLENDED_SHOWS_HOME), so its own switch adds it the same
 * way, after Posts. There's no Photos chip: Facebook answers both photo tabs its results page can
 * open with no results for any search. A tap rebuilds the row from Facebook's
 * stored chips, which never include the added ones, so with more than one added the patch also
 * hands over the label of each tapped chip, and a row with none marked marks the one tapped last.
 */
public final class SearchPostsTab {
    /** The posts tab, as Facebook's tab enum names it. */
    static final String POSTS_TAB = "BLENDED_STORIES";

    /** The videos tab: the one Facebook's table labels Videos. */
    static final String VIDEOS_TAB = "BLENDED_SHOWS_HOME";

    /** The tab the added chips go after. */
    static final String REELS_TAB = "REELS";

    /** The member the report names once the row's chips have been handed over. */
    static final String CHIPS = "search result chips";

    /** The member the report names once a tapped chip's label has been handed over. */
    static final String TAPS = "search chip taps";

    /** What the report counts each time Posts goes into the row. */
    static final String ADDED = "Posts chip added";

    /** What the report counts each time Videos goes into the row. */
    static final String VIDEOS_ADDED = "Videos chip added";

    private static final String FAMILY = FamilyNames.SEARCH_POSTS_TAB;

    /** The table tests hand the hook in place of Facebook's; null in the app. */
    @Nullable
    static Map<?, ?> testLabels;

    /** The label of the chip tapped last, or null before the first tap. */
    @Nullable
    static volatile String tapped;

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
            if (chips == null || chips.isEmpty() || !Utils.settingsReady()) return chips;
            List<String> tabs = new ArrayList<>(2);
            if (Settings.SEARCH_POSTS_TAB.get()) tabs.add(POSTS_TAB);
            if (Settings.SEARCH_VIDEOS_TAB.get()) tabs.add(VIDEOS_TAB);
            if (tabs.isEmpty()) return chips;
            return withTabs(chips, testLabels != null ? testLabels : tabLabels(Utils.getContext()), tabs);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CHIPS, failure);
            return chips;
        }
    }

    /**
     * The hook as a chip is tapped, at the start of the method that rebuilds the row with the tapped
     * chip marked and opens its tab, handed the chip's label.
     */
    public static void tapped(@Nullable String label) {
        try {
            HookStatus.bound(FAMILY, TAPS);
            tapped = label;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, TAPS, failure);
        }
    }

    /**
     * [chips] with a chip added for each of [tabs] (tab names, in order) that [labels] (Facebook's
     * table between tab labels and tabs, either way round) labels and the row doesn't have yet. The
     * first goes after Reels, else after the first chip, and each after the one before it, or after
     * that tab's chip when the row already has it. The same list when nothing goes in.
     */
    static List<?> withTabs(List<?> chips, @Nullable Map<?, ?> labels, List<String> tabs)
            throws ReflectiveOperationException {
        List<String> names = new ArrayList<>(chips.size());
        for (Object chip : chips) names.add(label(chip));
        List<String> added = new ArrayList<>(tabs.size());
        List<String> addedTabs = new ArrayList<>(tabs.size());
        List<Integer> places = new ArrayList<>(tabs.size());
        List<String> out = new ArrayList<>(names);
        int next = insertAt(names, labelOf(labels, REELS_TAB));
        if (next < 0) return chips;
        for (String tab : tabs) {
            String label = labelOf(labels, tab);
            if (label == null) {
                Logger.printDebug(() -> "Search posts tab: no label for " + tab);
                continue;
            }
            int there = out.indexOf(label);
            if (there >= 0) {
                next = there + 1;
                continue;
            }
            out.add(next, label);
            added.add(label);
            addedTabs.add(tab);
            places.add(next);
            next++;
        }
        if (added.isEmpty()) {
            Logger.printDebug(() -> "Search posts tab: chips stay " + names);
            return chips;
        }
        // Facebook marks the chip of the tab that's open, and the added tabs aren't among its
        // chips, so a row with none marked is the results of one of them: the only one added, or
        // the one tapped last.
        String marked = null;
        if (noneSelected(chips)) {
            String last = tapped;
            marked = added.size() == 1 ? added.get(0) : added.contains(last) ? last : null;
        }
        List<Object> row = new ArrayList<>(chips);
        for (int i = 0; i < added.size(); i++) {
            Object chip = chipLike(chips.get(0), added.get(i), added.get(i).equals(marked));
            if (chip == null) return chips;
            row.add(places.get(i), chip);
        }
        for (String tab : addedTabs) {
            HookStatus.counted(FAMILY, POSTS_TAB.equals(tab) ? ADDED : VIDEOS_ADDED);
        }
        Logger.printDebug(() -> "Search posts tab: " + added + " go in, making " + out);
        return row;
    }

    /**
     * Where the first added chip goes among the chips' [labels], or -1 for no change: after the
     * [reels] chip, else after the first chip. -1 for no chips.
     */
    static int insertAt(List<String> labels, @Nullable String reels) {
        if (labels.isEmpty()) return -1;
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
     * A new chip of [sample]'s kind, labelled [label] and [selected] or not, made the way Facebook
     * makes every chip in the row: its (selected, id, label) constructor, with the label as the id
     * too. Null when the kind has no such constructor.
     */
    @Nullable
    static Object chipLike(Object sample, String label, boolean selected) throws ReflectiveOperationException {
        Constructor<?> constructor = chipConstructor(sample.getClass());
        if (constructor == null) {
            Logger.printDebug(() -> "Search posts tab: no chip constructor on " + sample.getClass().getName());
            return null;
        }
        return constructor.newInstance(selected, label, label);
    }

    @Nullable
    private static Constructor<?> chipConstructor(Class<?> kind) {
        for (Constructor<?> constructor : kind.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length != 3 || types[0] != boolean.class || types[1] != String.class || types[2] != String.class) {
                continue;
            }
            constructor.setAccessible(true);
            return constructor;
        }
        return null;
    }

    /**
     * Whether no chip in [chips] is marked selected. False when it can't tell: a kind with no
     * (selected, id, label) constructor, or no boolean field that constructor's first argument sets.
     */
    static boolean noneSelected(List<?> chips) throws ReflectiveOperationException {
        Field selected = null;
        for (Object chip : chips) {
            if (chip == null) return false;
            if (selected == null || selected.getDeclaringClass() != chip.getClass()) selected = selectedField(chip.getClass());
            if (selected == null || selected.getBoolean(chip)) return false;
        }
        return true;
    }

    /** The boolean field [kind]'s (selected, id, label) constructor sets from its first argument, or null. */
    @Nullable
    static Field selectedField(Class<?> kind) throws ReflectiveOperationException {
        Constructor<?> constructor = chipConstructor(kind);
        if (constructor == null) return null;
        Object on = constructor.newInstance(true, "", "");
        Object off = constructor.newInstance(false, "", "");
        Field found = null;
        for (Field field : kind.getDeclaredFields()) {
            if (field.getType() != boolean.class || Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            if (field.getBoolean(on) == field.getBoolean(off)) continue;
            if (found != null) return null;
            found = field;
        }
        return found;
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
