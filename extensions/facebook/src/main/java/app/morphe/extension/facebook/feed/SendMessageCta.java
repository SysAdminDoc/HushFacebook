/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The Messenger "Send message" bar Facebook puts under some posts once you've stayed on them a
 * moment, between the post and its reaction counts (issue #609). It's one of the plugins of the post
 * call-to-action selector, which asks each plugin in its table whether it applies to the post and
 * draws the first one that does. With the switch on, the selector hears that the Send message plugin
 * doesn't apply, so the post goes on to its next button, if it has one. The row of Like, Comment and
 * Share, the Send button in it included, is another part of the post and stays.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class SendMessageCta {
    /** The class name of the Send message plugin, as the selector's table names it. */
    public static final String SEND_MESSAGE =
            "com.facebook.feed.plugins.calltoaction.impl.messaging.MessagePostAuthorDwellCTAPlugin";

    /** Counted each time the selector is told the Send message bar doesn't apply. */
    static final String HIDDEN = "Send message bar under a post kept out";

    /** The member the report names once the selector has asked about the Send message plugin. */
    static final String CHECK = "send message check";

    private static final String FAMILY = FamilyNames.POST_ACTION_BUTTONS;

    private static volatile boolean logged;

    private SendMessageCta() {
    }

    /**
     * The hook first thing in the post call-to-action selector's check of whether a plugin applies,
     * handed the name the selector's table gives that plugin. True makes the check answer no: the
     * Send message plugin while the switch is on. Every other plugin, or none, is left to Facebook.
     */
    public static boolean hidesSendMessage(@Nullable String plugin) {
        try {
            HookStatus.invoked(FAMILY);
            if (!SEND_MESSAGE.equals(plugin)) return false;
            HookStatus.bound(FAMILY, CHECK);
            if (!Utils.settingsReady() || !Settings.HIDE_POST_SEND_MESSAGE.get()) return false;
            HookStatus.counted(FAMILY, HIDDEN);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Post action buttons: a Send message bar under a post was kept out");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CHECK, failure);
            return false;
        }
    }
}
