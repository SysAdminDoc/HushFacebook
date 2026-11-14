/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.facebook.settings.SettingsRows.Subheading;
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
 *
 * <p>A switch turned off in the list keeps its place, off, until the list closes, so a wrong tap is
 * one more tap to undo. It then goes under Turned off lately at the end of the list (#470), newest
 * first, at most {@link #LATELY} of them, each one tap from on again. A switch that's on again, from
 * here or anywhere else, leaves that group. Which switches those are is kept in a file of its own,
 * apart from the settings, so a settings file never carries it and an import never writes it, as
 * with the new-row dots.
 */
@SuppressWarnings("deprecation") // Framework preferences, as the rest of the screen.
final class FavoriteSwitchesScreen {
    /** The row's key, for the settings search. No setting is behind it. */
    static final String ROW = "action_favorite_switches";

    /** Where the switches turned off from the list are kept, newest first. */
    static final String OFF_STORE = "hushfacebook_switches_off";

    /** The keys in {@link #OFF_STORE}, one per line. */
    static final String OFF_KEYS = "keys";

    /** How many switches Turned off lately keeps. */
    static final int LATELY = 20;

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
                found.add(copyOf(context, preference, true));
            }
        }
    }

    private static Toggle copyOf(Context context, Preference preference, boolean on) {
        Toggle copy = new Toggle(context);
        copy.setKey(preference.getKey());
        copy.setTitle(preference.getTitle());
        copy.setSummary(preference.getSummary());
        copy.setPersistent(false);
        copy.setChecked(on);
        return copy;
    }

    /**
     * The switches turned off from the list that are still off, newest first, each a fresh row as
     * {@link #onSwitches} makes, shown off. One that's on again, or no longer a switch, is dropped from
     * the file. One this page doesn't show is kept but not listed.
     */
    static List<Toggle> turnedOffLately(Context context, PreferenceGroup screen) {
        Map<String, Preference> page = new HashMap<>();
        toggles(screen, page);
        List<String> stored = turnedOff(context);
        List<String> kept = new ArrayList<>();
        List<Toggle> found = new ArrayList<>();
        for (String key : stored) {
            Setting<?> setting = Setting.getSettingFromPath(key);
            if (!(setting instanceof BooleanSetting) || ((BooleanSetting) setting).savedValue()) continue;
            kept.add(key);
            Preference row = page.get(key);
            if (row != null) found.add(copyOf(context, row, false));
        }
        if (kept.size() != stored.size()) store(context, kept);
        return found;
    }

    private static void toggles(PreferenceGroup group, Map<String, Preference> found) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                toggles((PreferenceGroup) preference, found);
            } else if (preference instanceof Toggle && preference.getKey() != null
                    && !found.containsKey(preference.getKey())) {
                found.put(preference.getKey(), preference);
            }
        }
    }

    /** The keys of the switches turned off from the list, newest first, or none when the file can't be read. */
    static List<String> turnedOff(Context context) {
        List<String> keys = new ArrayList<>();
        try {
            String stored = offStore(context).getString(OFF_KEYS, null);
            if (stored == null) return keys;
            for (String key : stored.split("\n")) {
                if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
            }
        } catch (RuntimeException unreadable) {
            // A value of another kind is no list: the group starts empty again.
            keys.clear();
        }
        return keys;
    }

    /** Puts [key] first in the file when it was turned off, or takes it out when it was turned on. */
    private static void remember(Context context, String key, boolean off) {
        List<String> keys = turnedOff(context);
        boolean had = keys.remove(key);
        if (off) keys.add(0, key);
        else if (!had) return;
        while (keys.size() > LATELY) keys.remove(keys.size() - 1);
        store(context, keys);
    }

    private static void store(Context context, List<String> keys) {
        try {
            SharedPreferences.Editor edit = offStore(context).edit();
            if (keys.isEmpty()) edit.remove(OFF_KEYS);
            else edit.putString(OFF_KEYS, String.join("\n", keys));
            edit.apply();
        } catch (RuntimeException failure) {
            Logger.printDebug(() -> "Switches that are on: the switches turned off lately weren't saved", failure);
        }
    }

    private static SharedPreferences offStore(Context context) {
        return context.getApplicationContext().getSharedPreferences(OFF_STORE, Context.MODE_PRIVATE);
    }

    @Nullable
    static BooleanSetting settingOf(Preference preference) {
        String key = preference.getKey();
        Setting<?> setting = key == null ? null : Setting.getSettingFromPath(key);
        return setting instanceof BooleanSetting ? (BooleanSetting) setting : null;
    }

    /**
     * Turns [row]'s setting to [on], which the page's own row follows, and shows the switch to match.
     * Turned off, it goes first under Turned off lately; turned on, it leaves that group.
     */
    static void flip(Toggle row, boolean on) {
        BooleanSetting setting = settingOf(row);
        if (setting == null) return;
        setting.save(on);
        row.setChecked(on);
        remember(row.getContext(), setting.key, !on);
        Logger.printDebug(() -> "Switches that are on: " + setting.key + " turned " + (on ? "on" : "off"));
    }

    /** The list, through the page, so it closes with the page's view and takes the screen's colors. */
    @Nullable
    static AlertDialog show(HushfacebookPreferenceFragment page, Context context) {
        ScreenColors palette = ScreenColors.current();
        PreferenceGroup screen = page.getPreferenceScreen();
        List<Preference> rows = new ArrayList<>();
        boolean anyOn = false;
        if (screen != null) {
            List<Toggle> on = onSwitches(context, screen);
            anyOn = !on.isEmpty();
            rows.addAll(on);
            List<Toggle> lately = turnedOffLately(context, screen);
            if (!lately.isEmpty()) {
                Subheading heading = new Subheading(context);
                heading.setTitle(L10n.t("Turned off lately"));
                rows.add(heading);
                rows.addAll(lately);
            }
        }

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView empty = new TextView(context);
        empty.setText(L10n.t("No switches are on yet."));
        empty.setTextColor(palette.summary);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int side = dp(context, 24);
        empty.setPaddingRelative(side, dp(context, 16), side, dp(context, 16));
        // Above the list, so with nothing on it still reads first when switches turned off lately follow.
        empty.setVisibility(anyOn ? View.GONE : View.VISIBLE);
        content.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ListView list = new ListView(context);
        list.setDivider(null);
        list.setAdapter(new Listing(rows));
        list.setOnItemClickListener((parent, view, position, id) -> {
            Preference item = rows.get(position);
            if (!(item instanceof Toggle)) return;
            Toggle row = (Toggle) item;
            flip(row, !row.isChecked());
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        });
        list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return page.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Switches that are on"))
                .setView(content)
                .setPositiveButton(L10n.t("OK"), null));
    }

    /** The rows, each drawn by its own preference, so it looks as it does on the page. */
    private static final class Listing extends BaseAdapter {
        private final List<Preference> rows;

        Listing(List<Preference> rows) {
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Preference getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        /** Only a switch takes a tap; the Turned off lately heading doesn't. */
        @Override
        public boolean isEnabled(int position) {
            return rows.get(position) instanceof Toggle;
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
