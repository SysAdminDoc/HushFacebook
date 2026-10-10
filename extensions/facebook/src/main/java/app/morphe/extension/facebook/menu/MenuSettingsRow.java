/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.SavedShortcut;
import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.facebook.settings.WatchHistoryShortcut;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The Hushfacebook row at the end of the Settings and privacy group in Facebook's Menu.
 *
 * <p>The group's rows come from one list builder, and every list it hands back passes through
 * {@link #withRow}, which adds this row once, after Facebook's own. The row is one of Facebook's
 * own row items, made by a factory the patch adds to the item class ({@value #FACTORY}), with the
 * first row's icon, the title "Hushfacebook settings" in the phone's language, no address to open
 * and the id {@link #ROW_ID}. The row is known by that id, never by its title. A tap on a row asks
 * {@link #onTap} first, and the row's own tap opens the settings screen the same way the logo long
 * press does. The row's two loggers ask {@link #isRow} and skip it, so Facebook never records a
 * row it didn't make.
 *
 * <p>Like the logo and the launcher shortcut, the row has no switch and stays while Hushfacebook
 * is paused: the settings screen is where a pause is lifted. Any failure in here leaves Facebook's
 * list as it was.
 *
 * <p>With the Saved shortcut switch on, a Saved row ({@link #SAVED_ROW_ID}) goes just before it and
 * opens Facebook's own Saved route. One UI's launcher hides the appended launcher entry, so this
 * row is the way to Saved that every phone shows. Pause turns the switch off, and the row with it.
 * The Watch history shortcut switch adds a Watch history row ({@link #WATCH_HISTORY_ROW_ID}) the
 * same way, after Saved, which opens the videos you've watched.
 */
public final class MenuSettingsRow {
    /**
     * The row's id. Facebook's row ids are ids of its own objects, which are never negative, and
     * its tap handler picks special rows by comparing ids, so a negative one never matches them.
     */
    public static final long ROW_ID = -0x4855534846420001L;

    /** The Saved row's id, next to {@link #ROW_ID} and as far from Facebook's. */
    public static final long SAVED_ROW_ID = -0x4855534846420002L;

    /** The Watch history row's id, next to the Saved row's. */
    public static final long WATCH_HISTORY_ROW_ID = -0x4855534846420003L;

    /** The static factory the patch adds to the row item class: (template, title, id) to a new row. */
    static final String FACTORY = "hushfacebookRow";

    /** The static id reader the patch adds to the row item class. */
    static final String ID_READER = "hushfacebookRowId";

    /** The Hook status names of the two hooks. */
    static final String LIST_HOOK = "Settings and privacy rows";
    static final String TAP_HOOK = "Hushfacebook row tap";

    /** The row item class the helpers below were looked up on, and the helpers. */
    @Nullable
    private static volatile Helpers helpers;

    private MenuSettingsRow() {
    }

    private static final class Helpers {
        final Class<?> type;
        final Method factory;
        final Method idReader;

        Helpers(Class<?> type) throws NoSuchMethodException {
            this.type = type;
            this.factory = type.getMethod(FACTORY, Object.class, CharSequence.class, long.class);
            this.idReader = type.getMethod(ID_READER, Object.class);
        }
    }

    private static Helpers helpersFor(Class<?> type) throws NoSuchMethodException {
        Helpers known = helpers;
        if (known != null && known.type == type) return known;
        Helpers found = new Helpers(type);
        helpers = found;
        return found;
    }

    /**
     * Injection point, on each list the Settings and privacy group's builder hands back. Answers
     * the list with the Hushfacebook row after Facebook's rows, the Saved row before it when that's
     * wanted, or the list as it was when a row of ours is already there, the list is empty or
     * anything fails. Never null and never throws.
     */
    public static List<?> withRow(@Nullable List<?> rows) {
        if (rows == null) return Collections.emptyList();
        try {
            HookStatus.invoked(FamilyNames.MENU_SETTINGS_ROW);
            if (rows.isEmpty()) return rows;
            Object template = rows.get(0);
            if (template == null) return rows;
            Helpers found;
            try {
                found = helpersFor(template.getClass());
            } catch (NoSuchMethodException missing) {
                HookStatus.missingMember(FamilyNames.MENU_SETTINGS_ROW, "method", template.getClass().getName(),
                        FACTORY + " or " + ID_READER);
                return rows;
            }
            for (Object row : rows) {
                if (row != null && row.getClass() == found.type && isRow((long) found.idReader.invoke(null, row))) {
                    return rows;
                }
            }
            Context context = Utils.getContext();
            if (context == null) return rows;
            Object ours = found.factory.invoke(null, template, L10n.t(context, "Hushfacebook settings"), ROW_ID);
            if (ours == null) return rows;
            HookStatus.bound(FamilyNames.MENU_SETTINGS_ROW, LIST_HOOK);
            List<Object> withOurs = new ArrayList<>(rows.size() + 3);
            withOurs.addAll(rows);
            if (SavedShortcut.wanted(context)) {
                Object saved = found.factory.invoke(null, template, L10n.t(context, "Saved"), SAVED_ROW_ID);
                if (saved != null) withOurs.add(saved);
            }
            if (WatchHistoryShortcut.wanted(context)) {
                Object watched = found.factory.invoke(null, template, L10n.t(context, "Watch history"),
                        WATCH_HISTORY_ROW_ID);
                if (watched != null) withOurs.add(watched);
            }
            withOurs.add(ours);
            return withOurs;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_SETTINGS_ROW, LIST_HOOK, failure);
            return rows;
        }
    }

    /**
     * Injection point, first thing in the Menu's row tap handler. True for the Hushfacebook row,
     * which then opens the settings, and for the Saved row, which opens Saved; Facebook does
     * nothing more with the tap. Never throws.
     */
    public static boolean onTap(@Nullable View view, long id) {
        if (!isRow(id)) return false;
        try {
            HookStatus.invoked(FamilyNames.MENU_SETTINGS_ROW);
            HookStatus.bound(FamilyNames.MENU_SETTINGS_ROW, TAP_HOOK);
            Activity activity = activityOf(view == null ? null : view.getContext());
            if (id == SAVED_ROW_ID) {
                Context context = activity != null ? activity : Utils.getContext();
                if (context == null || !SavedShortcut.open(context)) {
                    Logger.printInfo(() -> "Saved shortcut: the Menu row couldn't open Saved");
                    if (context != null) Utils.showToastShort(L10n.t(context, "Saved isn't available in this build."));
                }
            } else if (id == WATCH_HISTORY_ROW_ID) {
                Context context = activity != null ? activity : Utils.getContext();
                if (context == null || !WatchHistoryShortcut.open(context)) {
                    Logger.printInfo(() -> "Watch history shortcut: the Menu row couldn't open the videos you've watched");
                    if (context != null) Utils.showToastShort(WatchHistoryShortcut.unavailable());
                }
            } else if (activity == null || !SettingsEntry.open(activity)) {
                Logger.printInfo(() -> "Hushfacebook in the Menu: the settings didn't open over "
                        + (activity == null ? "no activity" : activity.getClass().getSimpleName()));
                Context context = Utils.getContext();
                if (context != null) {
                    Utils.showToastShort(L10n.t(context, "Hushfacebook settings can't open here. Long-press the Facebook logo instead."));
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_SETTINGS_ROW, TAP_HOOK, failure);
        }
        // The row is ours either way: Facebook has nothing to open for it.
        return true;
    }

    /** Injection point, first thing in the row's loggers. True for the rows of ours, which they skip. */
    public static boolean isRow(long id) {
        return id == ROW_ID || id == SAVED_ROW_ID || id == WATCH_HISTORY_ROW_ID;
    }

    /** The activity a view's context wraps, or null. The depth guards against a wrapper that wraps itself. */
    @Nullable
    static Activity activityOf(@Nullable Context context) {
        for (int depth = 0; context != null && depth < 20; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Forgets the helpers looked up, as a new Facebook process would. For tests. */
    static void forget() {
        helpers = null;
    }
}
