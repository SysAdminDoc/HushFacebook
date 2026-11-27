/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * What a held comment offers (#607). Holding a comment opens Facebook's glimpse: the comment lifted
 * over the screen, a row of reactions above it and a list of options under it. Five switches each
 * take one part away: the reactions, Reply, Copy, Report (Report comment and Report to group
 * admins alike) and Hide comment. Every other option, Edit and Delete on your own comments among
 * them, stays. With everything a comment offers taken away, holding it does nothing, where
 * Facebook would open its older comment sheet instead. Some accounts get that older sheet in place
 * of the glimpse, and the same switches take the same rows, and its row of reactions, off it.
 */
public final class HeldComment {
    private static final String FAMILY = FamilyNames.COMMENT_SHEET_OPTIONS;

    /** The hooks' names in the family's status line. */
    static final String ROWS = "held comment options";
    static final String REACTIONS = "held comment reactions";
    static final String EMPTIED = "held comment left empty";

    /** Counted each time a held comment's option was left out. */
    static final String OPTION_HIDDEN = "Held comment option hidden";

    /** Counted each time a held comment's reactions were left out. */
    static final String REACTIONS_HIDDEN = "Held comment reactions hidden";

    /** Counted each time a held comment had nothing left to offer and Facebook's comment sheet stayed shut. */
    static final String SHEET_KEPT_SHUT = "Comment sheet kept shut";

    /** The older comment sheet's hooks, for accounts whose held comment opens it in place of the glimpse. */
    static final String SHEET_PLUGINS = "held comment sheet plugin rows";
    static final String SHEET_ROWS = "held comment sheet rows";
    static final String SHEET_REACTIONS = "held comment sheet reactions";

    /** Resource names of the sheet's icons, by id, "" for an id with no name. */
    private static final Map<Integer, String> ICON_NAMES = new ConcurrentHashMap<>();

    /**
     * Whether a switch took something away from the held comment being built. Facebook builds it in
     * one go on the main thread, and this goes back to false once that's done.
     */
    private static boolean tookAway;

    /** Whether the reset of {@link #tookAway} is already waiting on the main thread. */
    private static boolean resetPosted;

    private HeldComment() {
    }

    /**
     * The hook, first thing where a held comment's option is added, handed Facebook's name for its
     * kind. True leaves it out while its switch is on; false adds it, as for any kind no switch
     * covers. Never throws.
     */
    public static boolean dropsOption(@Nullable Object kind) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, ROWS);
            if (!(kind instanceof Enum) || !Utils.settingsReady()) return false;
            BooleanSetting setting = switchFor(((Enum<?>) kind).name());
            if (setting == null || !setting.get()) return false;
            HookStatus.counted(FAMILY, OPTION_HIDDEN);
            tookSomething();
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, ROWS, failure);
            return false;
        }
    }

    /** The switch covering the option Facebook calls [kind], or null for one no switch covers. */
    @Nullable
    static BooleanSetting switchFor(@Nullable String kind) {
        if (kind == null) return null;
        switch (kind) {
            case "REPLY":
                return Settings.HIDE_HELD_COMMENT_REPLY;
            case "COPY":
                return Settings.HIDE_HELD_COMMENT_COPY;
            // Report comment is Facebook's "find support" flow, and pages and groups get their own.
            case "FIND_SUPPORT":
            case "REPORT":
            case "REPORT_TO_ADMIN":
                return Settings.HIDE_HELD_COMMENT_REPORT;
            case "HIDE":
                return Settings.HIDE_HELD_COMMENT_HIDE;
            default:
                return null;
        }
    }

    /**
     * The hook, right after the reactions a held comment shows are wrapped with what a pick does.
     * Null leaves the row of reactions out while the switch is on; otherwise, and for a throw, what
     * Facebook built comes back.
     */
    @Nullable
    public static Object reactions(@Nullable Object reactions) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, REACTIONS);
            if (reactions == null || !Utils.settingsReady() || !Settings.HIDE_HELD_COMMENT_REACTIONS.get()) {
                return reactions;
            }
            HookStatus.counted(FAMILY, REACTIONS_HIDDEN);
            tookSomething();
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, REACTIONS, failure);
            return reactions;
        }
    }

    /**
     * The hook, where a held comment with no options and no reactions would open Facebook's older
     * comment sheet in their place. True keeps it shut when the switches took them away, so holding
     * the comment does nothing; false opens it, as for a comment that had nothing to begin with.
     */
    public static boolean leftEmpty() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, EMPTIED);
            if (!tookAway) return false;
            tookAway = false;
            HookStatus.counted(FAMILY, SHEET_KEPT_SHUT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, EMPTIED, failure);
            return false;
        }
    }

    /**
     * The hook, first thing in the older comment sheet's check of whether one of its plugin rows
     * shows, handed the plugin's name. True leaves the row out while its switch is on. Never throws.
     */
    public static boolean dropsSheetPlugin(@Nullable String plugin) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SHEET_PLUGINS);
            return Utils.settingsReady() && drops(pluginKind(plugin));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SHEET_PLUGINS, failure);
            return false;
        }
    }

    /**
     * The option kind of the older comment sheet's plugin row Facebook names [plugin], by the
     * plugin's class, or null for one no switch covers.
     */
    @Nullable
    static String pluginKind(@Nullable String plugin) {
        if (plugin == null) return null;
        switch (plugin.substring(plugin.lastIndexOf('.') + 1)) {
            case "CommentReplyBottomSheetMenuPlugin":
                return "REPLY";
            case "ReportToAdminPlugin":
                return "REPORT_TO_ADMIN";
            case "CommentFRXBottomSheetMenuPlugin":
                return "FIND_SUPPORT";
            default:
                return null;
        }
    }

    /**
     * The hook, first thing in the older comment sheet's row builder, handed the row's icon. True
     * leaves the row out while its switch is on. Never throws.
     */
    public static boolean dropsSheetRow(int icon) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SHEET_ROWS);
            return Utils.settingsReady() && drops(iconKind(iconName(icon)));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SHEET_ROWS, failure);
            return false;
        }
    }

    /** The resource name of [icon], or null for none. */
    @Nullable
    static String iconName(int icon) {
        if (icon == 0) return null;
        String name = ICON_NAMES.get(icon);
        if (name == null) {
            Context context = Utils.getContext();
            if (context == null) return null;
            try {
                name = context.getResources().getResourceEntryName(icon);
            } catch (Resources.NotFoundException missing) {
                name = "";
            }
            ICON_NAMES.put(icon, name);
        }
        return name.isEmpty() ? null : name;
    }

    /**
     * The option kind of the older comment sheet's row drawn with the icon Facebook names [icon]: the
     * icons keep their names in every language, where the titles don't. Null for one no switch covers.
     */
    @Nullable
    static String iconKind(@Nullable String icon) {
        if (icon == null) return null;
        switch (icon) {
            case "fb_ic_comment_outline_24":
                return "REPLY";
            case "fb_ic_copy_outline_24":
                return "COPY";
            // Report comment, and Find support or report comment where a page or group words it so.
            case "fb_ic_feedback_outline_24":
            case "fb_ic_caution_triangle_outline_24":
                return "REPORT";
            case "fb_ic_hide_outline_24":
                return "HIDE";
            default:
                return null;
        }
    }

    /**
     * The hook, where the older comment sheet decides on its row of reactions. True leaves the row
     * out while the switch is on. Never throws.
     */
    public static boolean dropsSheetReactions() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SHEET_REACTIONS);
            if (!Utils.settingsReady() || !Settings.HIDE_HELD_COMMENT_REACTIONS.get()) return false;
            HookStatus.counted(FAMILY, REACTIONS_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SHEET_REACTIONS, failure);
            return false;
        }
    }

    /** Whether the switch covering [kind] is on, counting the option left out when it is. */
    private static boolean drops(@Nullable String kind) {
        BooleanSetting setting = switchFor(kind);
        if (setting == null || !setting.get()) return false;
        HookStatus.counted(FAMILY, OPTION_HIDDEN);
        return true;
    }

    private static void tookSomething() {
        tookAway = true;
        if (resetPosted) return;
        resetPosted = true;
        Utils.runOnMainThread(HeldComment::forget);
    }

    /** Back to a held comment that nothing was taken from. */
    static void forget() {
        tookAway = false;
        resetPosted = false;
    }
}
