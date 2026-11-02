/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The order of the tabs on Facebook's tab bar, as Hide tabs' Tab order row has it (#341).
 *
 * <p>Facebook's tab bar list builder hands its shown tabs here right before it keeps them, after
 * its own hidden-tab set and {@link TabBarFilter} have taken theirs off. The tabs this knows, by the
 * classes Facebook keeps for each ({@link FacebookTabs}), go into the places known tabs held, in the
 * saved order, and a tab it doesn't know keeps its place. Known tabs the saved order doesn't name
 * follow the ones it does, in Facebook's order. Facebook opens on the bar's first tab when nothing
 * else decides, so the first tab is where it starts, unless Open on a chosen tab says otherwise.
 * Facebook builds the bar once, so a change shows when it restarts.
 *
 * <p>It fails open: with Hide tabs not in the build, no order saved, Hushfacebook paused, the
 * settings not ready yet, or a failure in here, the bar keeps Facebook's order.
 */
public final class TabOrder {
    /** The name the log lines go under, which is also their logcat tag after Morphe's prefix. */
    static final String SOURCE = "TabOrder";

    /**
     * The tabs the order can move, each by the key the setting keeps and the classes Facebook keeps
     * for it. Like {@link HiddenTabs.Tab}, a tab names no setting here, since the bar can be built
     * before the settings are ready.
     */
    public enum Tab {
        HOME("home", FacebookTabs.HOME_CLASS),
        VIDEO("video", FacebookTabs.VIDEO_CLASS),
        FRIENDS("friends", FacebookTabs.FRIENDS_CLASS),
        MARKETPLACE("marketplace", FacebookTabs.MARKETPLACE_CLASS),
        NOTIFICATIONS("notifications", FacebookTabs.NOTIFICATIONS_CLASS),
        MENU("menu", FacebookTabs.MENU_CLASS, FacebookTabs.PROFILE_CLASS),
        FEEDS("feeds", FacebookTabs.FEEDS_CLASS, FacebookTabs.MOST_RECENT_CLASS),
        GROUPS("groups", FacebookTabs.GROUPS_CLASS),
        GAMING("gaming", FacebookTabs.GAMING_CLASS, FacebookTabs.GAMING_CONTROLLER_CLASS),
        EVENTS("events", FacebookTabs.EVENTS_CLASS),
        DATING("dating", FacebookTabs.DATING_CLASS),
        PROFESSIONAL_DASHBOARD("professional_dashboard", FacebookTabs.PROFESSIONAL_DASHBOARD_CLASS,
                FacebookTabs.PROFESSIONAL_DASHBOARD_PLAIN_CLASS),
        SAVED("saved", FacebookTabs.SAVED_CLASS),
        AD_CENTER("ad_center", FacebookTabs.AD_CENTER_CLASS),
        CREATE("create", FacebookTabs.CREATE_CLASS),
        EXPLORE("explore", FacebookTabs.EXPLORE_CLASS),
        JOBS("jobs", FacebookTabs.JOBS_CLASS);

        /** What the setting keeps for this tab. */
        public final String key;

        final List<String> classes;

        Tab(String key, String... classes) {
            this.key = key;
            this.classes = Collections.unmodifiableList(Arrays.asList(classes));
        }

        /** The tab of the class Java names [className], or null. */
        @Nullable
        static Tab of(@Nullable String className) {
            for (Tab tab : values()) {
                if (tab.classes.contains(className)) return tab;
            }
            return null;
        }

        /** The tab the setting keeps as [key], or null. */
        @Nullable
        public static Tab forKey(@Nullable String key) {
            for (Tab tab : values()) {
                if (tab.key.equals(key)) return tab;
            }
            return null;
        }
    }

    /** The tabs most accounts' bars have, for the row before Facebook has built a bar in this process. */
    private static final List<Tab> USUAL = Collections.unmodifiableList(Arrays.asList(
            Tab.HOME, Tab.VIDEO, Tab.FRIENDS, Tab.MARKETPLACE, Tab.NOTIFICATIONS, Tab.MENU));

    /** The known tabs of the last bar Facebook built in this process, in Facebook's order. */
    private static volatile List<Tab> lastBar = Collections.emptyList();

    private TabOrder() {
    }

    /** The tabs [stored] names, in its order, with keys this build doesn't know and repeats left out. */
    public static List<Tab> parse(@Nullable String stored) {
        Set<Tab> tabs = new LinkedHashSet<>();
        if (stored == null) return new ArrayList<>(tabs);
        for (String key : stored.split(",")) {
            Tab tab = Tab.forKey(key.trim());
            if (tab != null) tabs.add(tab);
        }
        return new ArrayList<>(tabs);
    }

    /** What the setting keeps for [order]: the keys, commas between. */
    public static String stored(Collection<Tab> order) {
        StringBuilder text = new StringBuilder();
        for (Tab tab : order) {
            if (text.length() > 0) text.append(',');
            text.append(tab.key);
        }
        return text.toString();
    }

    /** [stored] as the row would save it. */
    public static String clean(@Nullable String stored) {
        return stored(parse(stored));
    }

    /** Whether [stored] is already what the row would save. */
    public static boolean isClean(@Nullable String stored) {
        return stored != null && stored.equals(clean(stored));
    }

    /**
     * The tabs the row lists, in the order the bar will have them: the last bar Facebook built here,
     * or before one, the saved tabs and the usual ones. Never throws.
     */
    public static List<Tab> rowTabs(List<Tab> saved) {
        List<Tab> bar = lastBar;
        Set<Tab> tabs = new LinkedHashSet<>();
        if (bar.isEmpty()) {
            tabs.addAll(saved);
            tabs.addAll(USUAL);
        } else {
            tabs.addAll(bar);
        }
        List<Tab> ordered = new ArrayList<>(tabs);
        Collections.sort(ordered, (first, second) -> Integer.compare(rank(saved, first), rank(saved, second)));
        return ordered;
    }

    /**
     * What the row saves for [picked], the tabs in the order it lists them: those, then the tabs
     * [saved] already placed that the row didn't list, so a tab off the bar for now keeps its place.
     */
    public static List<Tab> merged(List<Tab> picked, List<Tab> saved) {
        Set<Tab> order = new LinkedHashSet<>(picked);
        order.addAll(saved);
        return new ArrayList<>(order);
    }

    /** Where [tab] goes in [saved], or past every saved tab when it isn't there. */
    private static int rank(List<Tab> saved, @Nullable Tab tab) {
        int index = tab == null ? -1 : saved.indexOf(tab);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /**
     * Injection point in Facebook's tab bar list builder, right before it keeps the list of shown
     * tabs, [shown]. Answers the list the bar gets: [shown] itself when nothing moves, or a new
     * list with the known tabs in the saved order. Never throws.
     */
    @Nullable
    public static List<?> order(@Nullable List<?> shown) {
        try {
            if (shown == null || !HiddenTabs.inBuild()) return shown;
            List<Tab> tabs = new ArrayList<>(shown.size());
            List<Tab> known = new ArrayList<>();
            for (Object tab : shown) {
                Tab which = tab == null ? null : Tab.of(tab.getClass().getName());
                tabs.add(which);
                if (which != null) known.add(which);
            }
            lastBar = Collections.unmodifiableList(known);
            // The settings aren't read before they're ready, and a bar built then keeps Facebook's order.
            if (!Utils.settingsReady()) return shown;
            List<Tab> saved = parse(Settings.TAB_ORDER.get());
            if (saved.isEmpty()) return shown;

            List<Integer> slots = new ArrayList<>();
            List<Object> movable = new ArrayList<>();
            Map<Tab, Integer> ranks = new EnumMap<>(Tab.class);
            for (int index = 0; index < tabs.size(); index++) {
                Tab which = tabs.get(index);
                if (which == null) continue;
                slots.add(index);
                movable.add(shown.get(index));
                ranks.put(which, rank(saved, which));
            }
            List<Object> sorted = new ArrayList<>(movable);
            // A stable sort, so tabs of one rank keep Facebook's order.
            Collections.sort(sorted, (first, second) -> Integer.compare(
                    ranks.get(Tab.of(first.getClass().getName())), ranks.get(Tab.of(second.getClass().getName()))));
            boolean moved = false;
            for (int index = 0; index < sorted.size(); index++) moved |= sorted.get(index) != movable.get(index);
            if (!moved) return shown;

            List<Object> ordered = new ArrayList<>(shown);
            for (int index = 0; index < slots.size(); index++) ordered.set(slots.get(index), sorted.get(index));
            HookStatus.bound(FamilyNames.HIDDEN_TABS, "tab order");
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "Tab order: put the tab bar in the saved order, " + stored(saved) + ".");
            return ordered;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDDEN_TABS, "tab order", failure);
            return shown;
        }
    }

    /** Forgets the bar this process built, for tests. */
    static void clearForTests() {
        lastBar = Collections.emptyList();
    }
}
