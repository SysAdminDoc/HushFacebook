/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.preference.Preference;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.navigation.TabOrder;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;

/**
 * Hide tabs' Tab order row (#341): the tabs of the bar in the order they'll have, each tap on one
 * moving it up a place. Save keeps the order, As Facebook has it clears it. The first tab is where
 * Facebook opens. Facebook builds the bar once, so a change waits for a restart.
 */
@SuppressWarnings("deprecation")
final class TabOrderRow {
    private TabOrderRow() { }

    /** The row's key, for the settings search. The setting behind it is {@link Settings#TAB_ORDER}. */
    static final String ROW = "action_tab_order";

    static Preference row(HushfacebookPreferenceFragment page, Context context) {
        SettingsRows.Row row = new SettingsRows.Row(context);
        row.setKey(ROW);
        row.setTitle(L10n.t("Tab order"));
        row.setPersistent(false);
        showSaved(row);
        row.setOnPreferenceClickListener(p -> {
            choose(page, context, row);
            return true;
        });
        return row;
    }

    /** What the row and the list call [tab]: the tab's name in Facebook. */
    static String label(TabOrder.Tab tab) {
        switch (tab) {
            case HOME: return L10n.t("Home");
            case VIDEO: return L10n.t("Video");
            case FRIENDS: return L10n.t("Friends");
            case MARKETPLACE: return L10n.t("Marketplace");
            case NOTIFICATIONS: return L10n.t("Notifications");
            case MENU: return L10n.t("Menu");
            case FEEDS: return L10n.t("Feeds");
            case GROUPS: return L10n.t("Groups");
            case GAMING: return L10n.t("Gaming");
            case EVENTS: return L10n.t("Events");
            case DATING: return L10n.t("Dating");
            case PROFESSIONAL_DASHBOARD: return L10n.t("Professional dashboard");
            case SAVED: return L10n.t("Saved");
            case AD_CENTER: return L10n.t("Ad Center");
            case CREATE: return L10n.t("Create");
            case EXPLORE: return L10n.t("Explore");
            default: return L10n.t("Jobs");
        }
    }

    /** The row's summary for the saved order [stored]. */
    static String summary(@Nullable String stored) {
        List<TabOrder.Tab> saved = TabOrder.parse(stored);
        if (saved.isEmpty()) {
            return L10n.t("As Facebook has it. Tap to put the tabs in the order you want. Facebook opens on the first "
                    + "one. Restart Facebook after you change this.");
        }
        List<String> names = new ArrayList<>();
        for (TabOrder.Tab tab : TabOrder.rowTabs(saved)) names.add(label(tab));
        return L10n.f("In this order: %1$s. Facebook opens on the first one. Restart Facebook after you change this.",
                L10n.join(names));
    }

    /** Shows the saved order again, after an import or a change here. */
    static void showSaved(@Nullable Preference row) {
        if (row != null) row.setSummary(summary(Settings.TAB_ORDER.savedValue()));
    }

    private static void choose(HushfacebookPreferenceFragment page, Context context, Preference row) {
        List<TabOrder.Tab> saved = TabOrder.parse(Settings.TAB_ORDER.savedValue());
        List<TabOrder.Tab> order = TabOrder.rowTabs(saved);
        CharSequence[] names = new CharSequence[order.size()];
        for (int i = 0; i < names.length; i++) names[i] = label(order.get(i));
        // Through the page, so it closes with the page's view and takes the screen's colors. The
        // list is the dialog's own, so a tap that moves a tab rewrites the names it reads.
        AlertDialog list = page.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Tap a tab to move it up"))
                .setItems(names, null)
                .setPositiveButton(L10n.t("Save"), null)
                .setNeutralButton(L10n.t("As Facebook has it"), null)
                .setNegativeButton(L10n.t("Cancel"), null));
        if (list == null) return;
        list.getListView().setOnItemClickListener((parent, view, position, id) -> {
            if (position <= 0 || position >= order.size()) return;
            Collections.swap(order, position, position - 1);
            CharSequence moved = names[position];
            names[position] = names[position - 1];
            names[position - 1] = moved;
            list.getListView().invalidateViews();
        });
        list.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (!save(page, TabOrder.stored(TabOrder.merged(order, saved)))) return;
            showSaved(row);
            list.dismiss();
        });
        list.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(button -> {
            if (!save(page, "")) return;
            showSaved(row);
            list.dismiss();
        });
    }

    /** Saves [stored], then says a restart is owed when it changed. */
    static boolean save(HushfacebookPreferenceFragment page, String stored) {
        String before = Settings.TAB_ORDER.savedValue();
        if (stored.equals(before)) return true;
        Map<Setting<?>, Object> values = new LinkedHashMap<>();
        values.put(Settings.TAB_ORDER, stored);
        try {
            Setting.saveAll(values);
        } catch (java.io.IOException | RuntimeException failure) {
            Logger.printInfo(() -> "Tab order not saved", failure);
            Utils.showToastLong(L10n.t("Couldn't save the change. Try again."));
            return false;
        }
        Map<Setting<?>, Object> changed = new LinkedHashMap<>();
        changed.put(Settings.TAB_ORDER, before);
        page.savedTogether(changed);
        return true;
    }
}
