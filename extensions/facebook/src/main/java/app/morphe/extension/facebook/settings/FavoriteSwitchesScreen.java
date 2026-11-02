/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.facebook.settings.SettingsRows.Toggle;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;

/**
 * The Switches that are on row near the top of the page and the list it opens (#353): every switch
 * that's on right now, each drawn as the page draws it, so the ones you changed can be looked over and
 * turned off in one place. A switch here is the page's own setting, so turning it off turns it off
 * everywhere, and its row on the page follows.
 */
@SuppressWarnings("deprecation") // Framework preferences, as the rest of the screen.
final class FavoriteSwitchesScreen {
    /** The row's key, for the settings search. No setting is behind it. */
    static final String ROW = "action_favorite_switches";

    private FavoriteSwitchesScreen() {
    }

    static Preference row(HushfacebookPreferenceFragment page, Context context) {
        Row row = new Row(context);
        row.setKey(ROW);
        row.setTitle(L10n.t("Switches that are on"));
        row.setSummary(L10n.t("Every switch you have on, in one list. Turn one off here and it's off everywhere."));
        row.setPersistent(false);
        row.setOnPreferenceClickListener(p -> {
            show(page, context);
            return true;
        });
        return row;
    }

    /**
     * The switches on [screen] that are on now, in the order the page has them. Each is a fresh row
     * with the page's title and summary, so the list can draw and hold it apart from the page.
     */
    static List<Toggle> onSwitches(Context context, PreferenceGroup screen) {
        List<Toggle> found = new ArrayList<>();
        collect(context, screen, found);
        return found;
    }

    private static void collect(Context context, PreferenceGroup group, List<Toggle> found) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                collect(context, (PreferenceGroup) preference, found);
            } else if (preference instanceof Toggle) {
                BooleanSetting setting = settingOf(preference);
                if (setting == null || !setting.savedValue()) continue;
                Toggle copy = new Toggle(context);
                copy.setKey(preference.getKey());
                copy.setTitle(preference.getTitle());
                copy.setSummary(preference.getSummary());
                copy.setPersistent(false);
                copy.setChecked(true);
                found.add(copy);
            }
        }
    }

    @Nullable
    static BooleanSetting settingOf(Preference preference) {
        String key = preference.getKey();
        Setting<?> setting = key == null ? null : Setting.getSettingFromPath(key);
        return setting instanceof BooleanSetting ? (BooleanSetting) setting : null;
    }

    /** Turns [row]'s setting to [on], which the page's own row follows, and shows the switch to match. */
    static void flip(Toggle row, boolean on) {
        BooleanSetting setting = settingOf(row);
        if (setting == null) return;
        setting.save(on);
        row.setChecked(on);
        Logger.printDebug(() -> "Switches that are on: " + setting.key + " turned " + (on ? "on" : "off"));
    }

    /** The list, through the page, so it closes with the page's view and takes the screen's colors. */
    @Nullable
    static AlertDialog show(HushfacebookPreferenceFragment page, Context context) {
        ScreenColors palette = ScreenColors.current();
        List<Toggle> rows = page.getPreferenceScreen() == null
                ? new ArrayList<>() : onSwitches(context, page.getPreferenceScreen());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        ListView list = new ListView(context);
        list.setDivider(null);
        list.setAdapter(new Listing(rows));
        list.setOnItemClickListener((parent, view, position, id) -> {
            Toggle row = rows.get(position);
            flip(row, !row.isChecked());
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        });
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView empty = new TextView(context);
        empty.setText(L10n.t("No switches are on yet."));
        empty.setTextColor(palette.summary);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int side = dp(context, 24);
        empty.setPaddingRelative(side, dp(context, 16), side, dp(context, 16));
        content.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list.setEmptyView(empty);

        return page.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Switches that are on"))
                .setView(content)
                .setPositiveButton(L10n.t("OK"), null));
    }

    /** The rows, each drawn by its own preference, so it looks as it does on the page. */
    private static final class Listing extends BaseAdapter {
        private final List<Toggle> rows;

        Listing(List<Toggle> rows) {
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Toggle getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return rows.get(position).getView(null, parent);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
