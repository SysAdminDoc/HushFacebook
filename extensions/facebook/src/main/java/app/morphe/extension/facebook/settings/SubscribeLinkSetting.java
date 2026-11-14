/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * Hide the Subscribe link on posts, split off the Follow switch (#434), which used to cover the
 * Follow and the Subscribe link with one switch. It starts off, but someone who had that switch on
 * before the split keeps Subscribe hidden: until this switch has a stored value of its own, it reads
 * the Follow switch's stored value, and the first start that finds that value on stores it as this
 * switch's own.
 *
 * <p>Its key being stored is what marks the split as done, so this switch always stores its value,
 * off included, and a later change to the Follow switch never reaches it.
 */
final class SubscribeLinkSetting extends BooleanSetting {
    static final String FOLLOW_KEY = "hushfacebook_hide_post_follow_link";

    SubscribeLinkSetting(String key) {
        super(key, false);
    }

    @Override
    protected void load() {
        if (preferences.preferences.contains(key)) {
            super.load();
            return;
        }
        value = preferences.getBoolean(FOLLOW_KEY, false);
        if (!value || !Utils.isMainProcess()) return;
        try {
            preferences.saveBoolean(key, true);
        } catch (RuntimeException failure) {
            // The next start reads the Follow switch again and retries.
            Logger.printException(() -> "Could not keep the Subscribe link hidden after the switch split", failure);
        }
    }

    @Override
    protected void removeFromPreferences() {
        preferences.saveBoolean(key, false);
    }
}
