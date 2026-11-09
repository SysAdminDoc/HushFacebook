/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.comments.CommentsHeight;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.Setting;

/**
 * Open comments at, with a value-preserving upgrade from the switch it replaced, Open comments at
 * full height. A phone that had the switch on opens comments at full height, and one that had it
 * off stays at Facebook's height, the list's default.
 */
final class CommentsHeightSetting extends EnumSetting<CommentsHeight> {
    static final String KEY = "hushfacebook_comments_height";
    static final String LEGACY_KEY = "hushfacebook_open_comments_full_height";

    CommentsHeightSetting() {
        super(KEY, CommentsHeight.FACEBOOK);
    }

    /** Run before the list loads. A failed write keeps the old switch readable. */
    static void migrate() {
        try {
            finishMigration();
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not migrate Open comments at full height", failure);
        }
    }

    /** An import must finish a previously failed migration before it writes the list's key. */
    static void finishMigration() {
        synchronized (Setting.class) {
            SharedPreferences store = preferences.preferences;
            if (!store.contains(LEGACY_KEY) || !Utils.isMainProcess()) return;
            commit(store, null);
        }
    }

    /** The choice the old switch stands for: on is full height, anything else Facebook's height. */
    static CommentsHeight fromLegacy(@Nullable Object legacy) {
        return Boolean.TRUE.equals(legacy) ? CommentsHeight.FULL : CommentsHeight.FACEBOOK;
    }

    @Override
    protected void load() {
        SharedPreferences store = preferences.preferences;
        if (store.contains(key) || !store.contains(LEGACY_KEY)) {
            super.load();
        } else {
            value = fromLegacy(store.getAll().get(LEGACY_KEY));
        }
    }

    @Override
    public void saveToPreferences() {
        persist(value);
    }

    @Override
    protected void removeFromPreferences() {
        persist(defaultValue);
    }

    /** A later save also retires a legacy key left by a failed startup commit, in the same write. */
    private void persist(CommentsHeight next) {
        synchronized (Setting.class) {
            commit(preferences.preferences, next);
        }
    }

    /**
     * One write: the old switch carried over when [next] is null and the list has no value yet,
     * else [next], and the old switch's key gone either way.
     */
    private static void commit(SharedPreferences store, @Nullable CommentsHeight next) {
        SharedPreferences.Editor editor = store.edit();
        if (next == null) {
            if (!store.contains(KEY)) {
                CommentsHeight carried = fromLegacy(store.getAll().get(LEGACY_KEY));
                if (carried != CommentsHeight.FACEBOOK) editor.putString(KEY, carried.name());
            }
        } else if (next == CommentsHeight.FACEBOOK) {
            editor.remove(KEY);
        } else {
            editor.putString(KEY, next.name());
        }
        // Its removal is the migration marker. Facebook's height removes the list's own key, and
        // must never be reseeded from the old switch on the next start.
        editor.remove(LEGACY_KEY);
        if (!editor.commit()) throw new IllegalStateException("Could not save Open comments at");
    }
}
