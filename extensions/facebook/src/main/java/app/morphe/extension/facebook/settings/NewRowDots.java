/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.settings.Setting;

/**
 * A small dot beside a setting this build added since the settings were last opened, and beside
 * the home page row of the section it's in, so a long page shows what's new (issue #16).
 *
 * <p>There is no list of which release added what. The keys of the rows the person has already been
 * shown are kept in a file of their own, apart from the settings, so a backup neither exports nor
 * imports them. The first time the settings open with none stored, every row there now counts as
 * seen: a fresh install never shows a dot. A row whose key isn't in the set is new. Leaving a page
 * adds its rows, so a dot shows for the visit that reveals it and is gone by the next.
 *
 * <p>Mark new settings ({@link Settings#NEW_ROW_MARK}) can keep the dot for some days instead: the
 * first time a page with a new row is left, the time goes in the same file, and the row stays new
 * until that many days have passed, then joins the set. Off shows no dot, and its pages still join
 * the set as they're left, so turning it back on doesn't mark everything at once.
 *
 * <p>Only rows that are a saved setting count. A row that comes and goes on its own, such as a
 * running save or a font row, would otherwise read as new each time it came back. Anything that
 * goes wrong shows no dot.
 */
final class NewRowDots {
    static final String FILE = "hushfacebook_new_dots";
    static final String SEEN = "seen";
    /** Marks the dot's tag on a title, so a test can find it. */
    static final String TAG = "hushfacebook_new_dot";
    /** Before a row's key: when a page holding it, still new, was first left. */
    static final String FOUND = "found:";
    static final long DAY = 24L * 60 * 60 * 1000;
    /** The time for a test to read instead of the phone's clock; 0 reads the clock. */
    static long nowForTests;

    private final SharedPreferences store;
    private final Set<String> seen = new HashSet<>();

    /** Reads what's been seen, or marks everything in [pages] seen when nothing is stored yet. */
    NewRowDots(Context context, List<PreferenceCategory> pages) {
        store = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Set<String> stored = null;
        try {
            stored = store.getStringSet(SEEN, null);
        } catch (RuntimeException unreadable) {
            // A value of another kind is no set: start again, as on a first open.
        }
        if (stored != null) {
            seen.addAll(stored);
            return;
        }
        for (PreferenceCategory page : pages) addKeys(page);
        save();
    }

    private static boolean counts(Preference row) {
        String key = row.getKey();
        return key != null && Setting.getSettingFromPath(key) != null;
    }

    private void addKeys(PreferenceCategory page) {
        for (int i = 0; i < page.getPreferenceCount(); i++) {
            Preference row = page.getPreference(i);
            if (counts(row)) seen.add(row.getKey());
        }
    }

    private void save() {
        try {
            store.edit().putStringSet(SEEN, new HashSet<>(seen)).apply();
        } catch (RuntimeException ignored) {
            // The dot comes back next time.
        }
    }

    private static long now() {
        return nowForTests != 0 ? nowForTests : System.currentTimeMillis();
    }

    private static NewRowMark mark() {
        try {
            return Settings.NEW_ROW_MARK.get();
        } catch (RuntimeException unreadable) {
            return NewRowMark.ONE_VISIT;
        }
    }

    /** When a page holding [key] was first left while it was new, or 0. */
    private long foundAt(String key) {
        try {
            return store.getLong(FOUND + key, 0L);
        } catch (RuntimeException notATime) {
            return 0L;
        }
    }

    boolean isNew(Preference row) {
        if (!counts(row) || seen.contains(row.getKey())) return false;
        NewRowMark mark = mark();
        if (mark == NewRowMark.OFF) return false;
        if (mark.days == 0) return true;
        long found = foundAt(row.getKey());
        return found == 0 || now() - found < mark.days * DAY;
    }

    boolean anyNew(PreferenceCategory page) {
        for (int i = 0; i < page.getPreferenceCount(); i++) {
            if (isNew(page.getPreference(i))) return true;
        }
        return false;
    }

    /**
     * The person has been through [page]: its rows are no longer new, or, with a number of days
     * picked, start their days now and are no longer new once those have passed.
     */
    void left(PreferenceCategory page) {
        NewRowMark mark = mark();
        long now = now();
        SharedPreferences.Editor edit = store.edit();
        boolean changed = false;
        boolean seenChanged = false;
        for (int i = 0; i < page.getPreferenceCount(); i++) {
            Preference row = page.getPreference(i);
            if (!counts(row) || seen.contains(row.getKey())) continue;
            String key = row.getKey();
            long found = foundAt(key);
            if (mark.days > 0 && (found == 0 || found > now)) {
                // The first time it's been left new, or the clock went back: its days start now.
                edit.putLong(FOUND + key, now);
                changed = true;
            } else if (mark.days <= 0 || now - found >= mark.days * DAY) {
                seen.add(key);
                if (found != 0) edit.remove(FOUND + key);
                changed = seenChanged = true;
            }
        }
        if (seenChanged) edit.putStringSet(SEEN, new HashSet<>(seen));
        if (!changed) return;
        try {
            edit.apply();
        } catch (RuntimeException ignored) {
            // The dot stays a visit longer.
        }
    }

    /**
     * Puts the dot after [row]'s title when [fresh], and takes any earlier one off. The dot is part
     * of the title's text line, so it follows the reading direction, wraps with the title at a large
     * text size and can't leave the row.
     */
    static void paint(View row, boolean fresh, int accent) {
        try {
            TextView title = row.findViewById(android.R.id.title);
            if (title == null) return;
            boolean had = TAG.equals(title.getTag());
            if (!fresh) {
                if (had) {
                    title.setCompoundDrawablesRelative(null, null, null, null);
                    title.setTag(null);
                    title.setContentDescription(null);
                }
                return;
            }
            float density = row.getResources().getDisplayMetrics().density;
            int size = Math.round(8 * density);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(accent);
            dot.setBounds(0, 0, size, size);
            title.setCompoundDrawablesRelative(null, null, dot, null);
            title.setCompoundDrawablePadding(Math.round(8 * density));
            title.setTag(TAG);
            title.setContentDescription(title.getText() + ", " + L10n.t("New"));
        } catch (RuntimeException ignored) {
            // No dot.
        }
    }

    @Nullable
    static NewRowDots create(Context context, List<PreferenceCategory> pages) {
        try {
            return new NewRowDots(context, pages);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
