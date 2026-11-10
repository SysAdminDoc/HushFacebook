/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * What the Hide Menu promotions patch asks while Facebook builds its Menu.
 *
 * <p>Facebook's Menu is a list of groups, each typed by an enum Facebook keeps under its own
 * names: HELP, SETTINGS, LOGOUT and so on. Upgrades is the UPSELL group and Also from Meta is
 * PRODUCTS_FROM_FACEBOOK. Every group is built by two sections, the one Facebook draws itself
 * (the header and its rows) and one for what the server sends to go with the group. The patch
 * asks {@link #hideSection} in the first and {@link #hideServerSection} in the second as soon as
 * each knows its group, and a yes has the section build nothing.
 *
 * <p>Only those two groups and Help and support (HELP, issue #224) can go, each by its own switch,
 * and only by the enum's name, never by a title on screen. It fails open: with a switch off, a
 * pause, settings that aren't ready, anything but an enum, or any failure in here, the section
 * builds as Facebook built it.
 *
 * <p>Right after that, the native section asks {@link #collapsed} whether the group is folded up
 * under its header, which Facebook keeps in the section's own state and flips on each tap. Settings
 * and privacy (SETTINGS) can be kept open by a switch (issue #224); every other group, and that one
 * with the switch off, folds up as Facebook has it. It also asks {@link #keepsHeaderOpen} as it
 * makes the group's header, whose answer keeps that header's arrow up through a tap.
 */
public final class MenuSections {
    /** The enum name of the Upgrades group. */
    static final String UPGRADES = "UPSELL";

    /** The enum name of the Also from Meta group. */
    static final String ALSO_FROM_META = "PRODUCTS_FROM_FACEBOOK";

    /** The enum name of the Help and support group. */
    static final String HELP = "HELP";

    /** The enum name of the Settings and privacy group. */
    static final String SETTINGS = "SETTINGS";

    /** The counter route of the native sections: every group asked about, and the ones hidden. */
    static final String ROUTE = "Menu sections";

    /** The counter route of the sections carrying what the server sends with a group. */
    static final String SERVER_ROUTE = "Menu server sections";

    /** What a hidden Upgrades group is counted under. */
    static final String UPGRADES_HIDDEN = "Upgrades";

    /** What a hidden Also from Meta group is counted under. */
    static final String ALSO_FROM_META_HIDDEN = "Also from Meta";

    /** What a hidden Help and support group is counted under. */
    static final String HELP_HIDDEN = "Help and support";

    /** The Hook status names of the four hooks. */
    static final String NATIVE_HOOK = "Menu section";
    static final String SERVER_HOOK = "Menu server section";
    static final String STATE_HOOK = "Menu section state";
    static final String HEADER_HOOK = "Menu section header";

    /** Distinct debug lines kept, so a Menu opened again and again doesn't fill the log. */
    private static final int MAX_LOGGED = 64;

    /** The debug lines already written this process: hook, group and answer. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private MenuSections() {
    }

    /**
     * Injection point, in the children builder of the section Facebook draws for a Menu group,
     * right after it read the group. True has the section build no children. Never throws.
     */
    public static boolean hideSection(@Nullable Object group) {
        return decide(group, NATIVE_HOOK, ROUTE);
    }

    /**
     * Injection point, in the children builder of the section that carries what the server sends
     * with a Menu group, right after it read the group. True has it build nothing. Never throws.
     */
    public static boolean hideServerSection(@Nullable Object group) {
        return decide(group, SERVER_HOOK, SERVER_ROUTE);
    }

    /**
     * Injection point, in the children builder of the section Facebook draws for a Menu group,
     * right after it read the group. [collapsed] is the section's own state, true while the group
     * is folded up under its header, and what comes back takes its place for this build. Settings
     * and privacy comes back open while Keep Settings and privacy expanded is on, so its rows build
     * under its header and a tap on the header can't fold them away. The same builder asks again
     * with whether the group starts folded, which seeds its header's chevron and label, so the
     * header opens with the rows. Never throws.
     */
    public static boolean collapsed(boolean collapsed, @Nullable Object group) {
        try {
            HookStatus.invoked(FamilyNames.MENU_PROMOTIONS);
            if (!collapsed || !(group instanceof Enum)) return collapsed;
            HookStatus.bound(FamilyNames.MENU_PROMOTIONS, STATE_HOOK);
            if (!SETTINGS.equals(((Enum<?>) group).name())) return true;
            // Ready first: Settings loads every switch, and it can't before the context is set.
            boolean open = Utils.settingsReady() && Settings.KEEP_MENU_SETTINGS_EXPANDED.get();
            log(STATE_HOOK, SETTINGS, open ? "kept open" : "left folded");
            return !open;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_PROMOTIONS, STATE_HOOK, failure);
            return collapsed;
        }
    }

    /**
     * Injection point, in the children builder of the section Facebook draws for a Menu group,
     * right after it seeds the header it makes. The header keeps whether it's collapsed in a state
     * of its own after that and flips it on every tap, so true marks the header, and its render and
     * tap handler then take it as expanded whatever that state says. Settings and privacy is marked
     * while Keep Settings and privacy expanded is on, so a tap on its header leaves the arrow up
     * over rows that stay. Never throws.
     */
    public static boolean keepsHeaderOpen(@Nullable Object group) {
        try {
            HookStatus.invoked(FamilyNames.MENU_PROMOTIONS);
            if (!(group instanceof Enum)) return false;
            HookStatus.bound(FamilyNames.MENU_PROMOTIONS, HEADER_HOOK);
            if (!SETTINGS.equals(((Enum<?>) group).name())) return false;
            // Ready first: Settings loads every switch, and it can't before the context is set.
            boolean open = Utils.settingsReady() && Settings.KEEP_MENU_SETTINGS_EXPANDED.get();
            log(HEADER_HOOK, SETTINGS, open ? "header kept open" : "header left as is");
            return open;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_PROMOTIONS, HEADER_HOOK, failure);
            return false;
        }
    }

    private static boolean decide(@Nullable Object group, String hook, String route) {
        try {
            HookStatus.invoked(FamilyNames.MENU_PROMOTIONS);
            MenuLayoutDump.menuBuilt();
            if (!(group instanceof Enum)) {
                // The patch hands over what answers the group enum, so anything else means the
                // anchor took the wrong call and nothing here can be trusted.
                HookStatus.missingMember(FamilyNames.MENU_PROMOTIONS, "Menu group enum", hook,
                        group == null ? "null" : group.getClass().getName());
                return false;
            }
            HookStatus.bound(FamilyNames.MENU_PROMOTIONS, hook);
            String name = ((Enum<?>) group).name();
            FeedFilterCounters.sawList(route, 1);
            FeedFilterCounters.sawKind(route, name);
            // Ready first: switchFor loads Settings, which loads every switch, and it can't before
            // the context is set.
            if (!Utils.settingsReady()) return false;
            BooleanSetting toggle = switchFor(name);
            if (toggle == null) return false;
            boolean hide = toggle.get();
            if (hide) FeedFilterCounters.removed(route, 1, hiddenAs(name));
            log(hook, name, hide ? "hidden" : "left in");
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_PROMOTIONS, hook, failure);
            return false;
        }
    }

    /** The switch that hides the group named [name], or null for every group the Menu keeps. */
    @Nullable
    static BooleanSetting switchFor(String name) {
        if (UPGRADES.equals(name)) return Settings.HIDE_MENU_UPGRADES;
        if (ALSO_FROM_META.equals(name)) return Settings.HIDE_MENU_ALSO_FROM_META;
        if (HELP.equals(name)) return Settings.HIDE_MENU_HELP;
        return null;
    }

    /** The Menu title a hidden group named [name] is counted under. */
    private static String hiddenAs(String name) {
        if (UPGRADES.equals(name)) return UPGRADES_HIDDEN;
        if (ALSO_FROM_META.equals(name)) return ALSO_FROM_META_HIDDEN;
        return HELP_HIDDEN;
    }

    /** One debug line per hook, group and answer, so the phone check can see what the Menu built. */
    private static void log(String hook, String name, String answer) {
        String line = hook + ": " + name + " " + answer;
        if (LOGGED.size() >= MAX_LOGGED || !LOGGED.add(line)) return;
        Logger.printDebug(() -> "Hide Menu promotions: " + line);
    }

    /** Forgets which debug lines were written, as a new Facebook process would. For tests. */
    static void forgetLog() {
        LOGGED.clear();
    }
}
