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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.facebook.settings.SettingsRows.Subheading;
import app.morphe.extension.facebook.settings.SettingsRows.Toggle;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
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
 * here or anywhere else, leaves that group, and each one still off there has a remove button that
 * takes it off the group for good, staying off (#577). Which switches those are is kept in a file of its own,
 * apart from the settings, so a settings file never carries it and an import never writes it, as
 * with the new-row dots.
 *
 * <p>With more than one switch on, a row of choices above the list sets their order (#604): as the
 * page has them, A to Z by title, or Newest, the one turned on last first. The same file keeps the
 * choice and when each switch was turned on. Times are only kept from this version on, so a switch
 * that was on before it follows the timed ones, in page order.
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

    /** The tag of a Turned off lately row's remove button. */
    static final String REMOVE = "remove";

    /** The order choice in {@link #OFF_STORE}, an {@link Order} name; none means the page's order. */
    static final String ORDER = "order";

    /** Before a switch's key in {@link #OFF_STORE}: when it was last turned on, for {@link Order#NEWEST}. */
    static final String ON_AT = "on:";

    /** The tag of the order row above the list; each of its chips is tagged with its {@link Order}. */
    static final String ORDER_ROW = "order_row";

    /** How the switches that are on are listed (#604). */
    enum Order {
        /** As the settings page has them. */
        PAGE,
        /** By title. */
        NAME,
        /** The one turned on last first. */
        NEWEST
    }

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

    /** Takes [key] out of the file, so it's no longer under Turned off lately. Its setting stays as it is. */
    static void forget(Context context, String key) {
        List<String> keys = turnedOff(context);
        if (keys.remove(key)) store(context, keys);
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

    /** The order the list was last left in, or the page's when none was picked or it can't be read. */
    static Order order(Context context) {
        try {
            String stored = offStore(context).getString(ORDER, null);
            for (Order order : Order.values()) if (order.name().equals(stored)) return order;
        } catch (RuntimeException unreadable) {
            // A value of another kind is no choice: the page's order again.
        }
        return Order.PAGE;
    }

    private static void storeOrder(Context context, Order order) {
        try {
            SharedPreferences.Editor edit = offStore(context).edit();
            if (order == Order.PAGE) edit.remove(ORDER);
            else edit.putString(ORDER, order.name());
            edit.apply();
        } catch (RuntimeException failure) {
            Logger.printDebug(() -> "Switches that are on: the order wasn't saved", failure);
        }
    }

    /** Notes [key] turned on now, for Newest, or drops its time when it was turned off. */
    static void noteTurned(Context context, String key, boolean on) {
        if (context == null || key == null) return;
        try {
            SharedPreferences.Editor edit = offStore(context).edit();
            if (on) edit.putLong(ON_AT + key, System.currentTimeMillis());
            else edit.remove(ON_AT + key);
            edit.apply();
        } catch (RuntimeException failure) {
            Logger.printDebug(() -> "Switches that are on: when " + key + " was turned on wasn't saved", failure);
        }
    }

    /** When [key] was last turned on, or 0 when that isn't known. */
    static long turnedOnAt(Context context, @Nullable String key) {
        if (key == null) return 0L;
        try {
            return offStore(context).getLong(ON_AT + key, 0L);
        } catch (RuntimeException unreadable) {
            return 0L;
        }
    }

    /**
     * [on], which is in the page's order, put in [order]. A to Z compares titles as the language
     * Facebook runs in sorts them. Newest puts the switches with a time first, the latest first,
     * and the rest after them as the page has them.
     */
    static List<Toggle> ordered(Context context, List<Toggle> on, Order order) {
        List<Toggle> sorted = new ArrayList<>(on);
        if (order == Order.NAME) {
            Collator collator = Collator.getInstance(L10n.locale(context));
            collator.setStrength(Collator.SECONDARY);
            sorted.sort((a, b) -> collator.compare(String.valueOf(a.getTitle()), String.valueOf(b.getTitle())));
        } else if (order == Order.NEWEST) {
            Map<Preference, Long> at = new IdentityHashMap<>();
            for (Toggle row : sorted) at.put(row, turnedOnAt(context, row.getKey()));
            // List.sort is stable, so switches with the same time, or none, keep the page's order.
            sorted.sort((a, b) -> Long.compare(at.get(b), at.get(a)));
        }
        return sorted;
    }

    static String label(Order order) {
        switch (order) {
            case NAME:
                return L10n.t("A to Z");
            case NEWEST:
                return L10n.t("Newest");
            default:
                return L10n.t("Page order");
        }
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
        noteTurned(row.getContext(), setting.key, on);
        Logger.printDebug(() -> "Switches that are on: " + setting.key + " turned " + (on ? "on" : "off"));
    }

    /** The list, through the page, so it closes with the page's view and takes the screen's colors. */
    @Nullable
    static AlertDialog show(HushfacebookPreferenceFragment page, Context context) {
        ScreenColors palette = ScreenColors.current();
        PreferenceGroup screen = page.getPreferenceScreen();
        List<Preference> rows = new ArrayList<>();
        // The Turned off lately rows, the ones a remove button can take off the list.
        Set<Preference> removable = Collections.newSetFromMap(new IdentityHashMap<>());
        // In the page's order; the list shows them in the order picked above it.
        List<Toggle> on = screen == null ? new ArrayList<>() : onSwitches(context, screen);
        boolean anyOn = !on.isEmpty();
        rows.addAll(ordered(context, on, order(context)));
        if (screen != null) {
            List<Toggle> lately = turnedOffLately(context, screen);
            if (!lately.isEmpty()) {
                Subheading heading = new Subheading(context);
                heading.setTitle(L10n.t("Turned off lately"));
                rows.add(heading);
                rows.addAll(lately);
                removable.addAll(lately);
            }
        }

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        ListView list = new ListView(context);
        Listing listing = new Listing(context, palette, rows, removable);
        if (on.size() > 1) {
            content.addView(orderRow(context, palette, picked -> {
                // The switches that were on fill the top of the list, one turned off there included,
                // so the new order goes over them and Turned off lately stays under them.
                List<Toggle> sorted = ordered(context, on, picked);
                for (int i = 0; i < sorted.size(); i++) rows.set(i, sorted.get(i));
                listing.notifyDataSetChanged();
                list.setSelection(0);
                Logger.printDebug(() -> "Switches that are on: listed in " + picked + " order");
            }));
        }
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
        list.setDivider(null);
        listing.remover = row -> {
            String key = row.getKey();
            if (key != null) forget(context, key);
            rows.remove(row);
            removable.remove(row);
            if (removable.isEmpty()) {
                // The last one gone, the heading goes with it.
                for (int i = rows.size() - 1; i >= 0; i--) {
                    if (rows.get(i) instanceof Subheading) rows.remove(i);
                }
            }
            listing.notifyDataSetChanged();
            list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
            Utils.showToastShort(L10n.f("Removed %1$s", L10n.isolate(String.valueOf(row.getTitle()))));
            Logger.printDebug(() -> "Switches that are on: " + key + " taken off Turned off lately");
        };
        list.setAdapter(listing);
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

    /**
     * The order chips, the stored order picked, drawn as Recently watched draws its filters. Picking
     * another keeps it for the next time the list opens and hands it to [picked].
     */
    private static View orderRow(Context context, ScreenColors palette, Consumer<Order> picked) {
        HorizontalScrollView row = new HorizontalScrollView(context);
        row.setHorizontalScrollBarEnabled(false);
        row.setTag(ORDER_ROW);
        RadioGroup chips = new RadioGroup(context);
        chips.setOrientation(RadioGroup.HORIZONTAL);
        chips.setPaddingRelative(dp(context, 20), dp(context, 4), dp(context, 20), dp(context, 8));
        Order stored = order(context);
        for (Order order : Order.values()) {
            RadioButton chip = RecentlyWatchedScreen.chip(context, palette, label(order));
            chip.setId(View.generateViewId());
            chip.setTag(order);
            RadioGroup.LayoutParams place = new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            place.setMarginEnd(dp(context, 8));
            chips.addView(chip, place);
            if (order == stored) {
                chips.check(chip.getId());
                RecentlyWatchedScreen.paintChip(chip, palette, true);
            }
            // Set after the stored one is checked, so opening the list doesn't count as a pick.
            chip.setOnCheckedChangeListener((button, checked) -> {
                RecentlyWatchedScreen.paintChip(button, palette, checked);
                if (!checked) return;
                storeOrder(context, order);
                picked.accept(order);
            });
        }
        row.addView(chips);
        return row;
    }

    /**
     * The rows, each drawn by its own preference, so it looks as it does on the page. A Turned off
     * lately row that's still off gets a remove button at its end.
     */
    private static final class Listing extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        private final List<Preference> rows;
        private final Set<Preference> removable;
        /** What a row's remove button does with its row. */
        @Nullable Consumer<Preference> remover;

        Listing(Context context, ScreenColors palette, List<Preference> rows, Set<Preference> removable) {
            this.context = context;
            this.palette = palette;
            this.rows = rows;
            this.removable = removable;
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
            Preference item = rows.get(position);
            View view = item.getView(null, parent);
            // One turned back on in the list shows none: it's on, so it leaves the group anyway.
            if (!removable.contains(item) || (item instanceof Toggle && ((Toggle) item).isChecked())) return view;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(view, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton remove = new ImageButton(context);
            remove.setTag(REMOVE);
            remove.setImageDrawable(SettingsIcons.icon(context, SettingsIcons.DELETE, palette.summary));
            ScreenColors.pressFeedback(remove, palette.summary, true);
            int pad = dp(context, 12);
            remove.setPadding(pad, pad, pad, pad);
            remove.setContentDescription(L10n.f("Remove %1$s", L10n.isolate(String.valueOf(item.getTitle()))));
            // Not focusable, or the list stops handing taps on the row to its click listener.
            remove.setFocusable(false);
            remove.setOnClickListener(button -> {
                if (remover != null) remover.accept(item);
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48));
            size.setMarginEnd(dp(context, 8));
            row.addView(remove, size);
            return row;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
