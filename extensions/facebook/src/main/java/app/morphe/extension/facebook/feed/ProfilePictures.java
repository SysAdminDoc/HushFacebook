/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide profile pictures patch asks as a post header or a comment draws (#400).
 *
 * <p>A post header's picture comes from the person or Page model the header builds for it, and
 * Facebook already draws a header with no model for a header kind it doesn't know. A comment's
 * picture is a component the comment row adds before the bubble, and the row already skips it when
 * it's null. Answering null takes the picture away, and with it the space it held, so the name and
 * text start where the picture was.
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and Facebook draws its own.
 */
public final class ProfilePictures {
    /** Counted each time a post header is drawn without its picture. */
    static final String FEED_PICTURE_HIDDEN = "post header picture hidden";

    /** Counted each time a comment is drawn without its picture. */
    static final String COMMENT_PICTURE_HIDDEN = "comment picture hidden";

    private ProfilePictures() {
    }

    /** The model a post header draws its picture from, or null with the posts switch on. */
    public static Object feedPicture(Object picture) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_PICTURES);
            HookStatus.bound(FamilyNames.PROFILE_PICTURES, "post header picture");
            if (picture == null) return null;
            if (!Utils.settingsReady() || !Settings.HIDE_FEED_PROFILE_PICTURES.get()) return picture;
            HookStatus.counted(FamilyNames.PROFILE_PICTURES, FEED_PICTURE_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_PICTURES, "post header picture", failure);
            return picture;
        }
    }

    /** The picture a comment row is about to add, or null with the comments switch on. */
    public static Object commentPicture(Object picture) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_PICTURES);
            HookStatus.bound(FamilyNames.PROFILE_PICTURES, "comment picture");
            if (picture == null) return null;
            if (!Utils.settingsReady() || !Settings.HIDE_COMMENT_PROFILE_PICTURES.get()) return picture;
            HookStatus.counted(FamilyNames.PROFILE_PICTURES, COMMENT_PICTURE_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_PICTURES, "comment picture", failure);
            return picture;
        }
    }
}
