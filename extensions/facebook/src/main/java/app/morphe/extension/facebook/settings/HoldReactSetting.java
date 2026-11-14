/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.SharedPreferences;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;

/**
 * The two Hold to react choices, a feed photo's and a feed video's (#454), including a one-time
 * upgrade from the old combined Hold a post to react. All three start off, so an old choice that was
 * on turns both on and one that was off leaves both off.
 */
final class HoldReactSetting extends BooleanSetting {
    static final String LEGACY_KEY = "hushfacebook_hold_post_to_react";
    static final String PHOTO_KEY = "hushfacebook_hold_photo_to_react";
    static final String VIDEO_KEY = "hushfacebook_hold_video_to_react";

    HoldReactSetting(String key) {
        super(key, false, false);
    }

    /** Run before the independent settings load. A failed write keeps the old choice readable. */
    static void migrate() {
        try {
            finishMigration();
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not migrate the Hold to react settings", failure);
        }
    }

    /** An import must finish a previously failed migration before default keys can be removed. */
    static void finishMigration() {
        synchronized (Setting.class) {
            SharedPreferences store = preferences.preferences;
            if (!store.contains(LEGACY_KEY) || !Utils.isMainProcess()) return;
            commit(store, null, null, null);
        }
    }

    @Override
    protected void load() {
        if (preferences.preferences.contains(key)) {
            super.load();
        } else {
            Object legacy = preferences.preferences.getAll().get(LEGACY_KEY);
            value = legacy instanceof Boolean ? (Boolean) legacy : defaultValue;
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
    private void persist(Boolean next) {
        synchronized (Setting.class) {
            if (!Utils.isMainProcess()) {
                throw new IllegalStateException("Hold to react settings are writable only from the main process");
            }
            commit(preferences.preferences, key, next, defaultValue);
        }
    }

    private static void commit(SharedPreferences store, String changedKey, Boolean next, Boolean defaultValue) {
        SharedPreferences.Editor editor = store.edit();
        if (store.contains(LEGACY_KEY)) {
            Object legacy = store.getAll().get(LEGACY_KEY);
            // The old choice started off, so only one that was on is carried over; off is each new
            // choice's default and needs no key.
            if (Boolean.TRUE.equals(legacy)) {
                if (!store.contains(PHOTO_KEY)) editor.putBoolean(PHOTO_KEY, true);
                if (!store.contains(VIDEO_KEY)) editor.putBoolean(VIDEO_KEY, true);
            }
            // Its removal is the migration marker. Default-valued later choices remove their own
            // keys, and must never be reseeded from the old choice on the next start.
            editor.remove(LEGACY_KEY);
        }
        if (changedKey != null) {
            if (defaultValue.equals(next)) editor.remove(changedKey);
            else editor.putBoolean(changedKey, next);
        }
        if (!editor.commit()) throw new IllegalStateException("Could not save the Hold to react settings");
    }
}
