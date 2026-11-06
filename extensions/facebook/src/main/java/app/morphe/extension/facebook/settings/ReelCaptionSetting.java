/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * Show full reel captions, split off Show full post text (#398), which used to cover feed posts and
 * reel captions with one switch. It starts off, but someone who had that switch on before the split
 * keeps whole reel captions: until this switch has a stored value of its own, it reads the feed
 * switch's stored value, and the first start that finds that value on stores it as this switch's own.
 *
 * <p>Its key being stored is what marks the split as done, so this switch always stores its value,
 * off included, and a later change to the feed switch never reaches it.
 */
final class ReelCaptionSetting extends BooleanSetting {
    static final String FEED_KEY = "hushfacebook_show_full_post_text";

    ReelCaptionSetting(String key) {
        super(key, false);
    }

    @Override
    protected void load() {
        if (preferences.preferences.contains(key)) {
            super.load();
            return;
        }
        value = preferences.getBoolean(FEED_KEY, false);
        if (!value || !Utils.isMainProcess()) return;
        try {
            preferences.saveBoolean(key, true);
        } catch (RuntimeException failure) {
            // The next start reads the feed switch again and retries.
            Logger.printException(() -> "Could not keep reel captions whole after the switch split", failure);
        }
    }

    @Override
    protected void removeFromPreferences() {
        preferences.saveBoolean(key, false);
    }
}
