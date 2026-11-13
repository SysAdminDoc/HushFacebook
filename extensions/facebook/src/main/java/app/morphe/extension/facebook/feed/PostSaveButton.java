/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The row under a post, as Post action buttons changes it (issue #265): Like, Comment, Repost
 * (#450), Share or Send taken out, and Save added.
 *
 * <p>Facebook decides which buttons a post's row shows from a list of button types its server
 * sends, and Save is one of the types its row already knows how to draw: a bookmark that saves the
 * post, and shows it saved, through Facebook's own code. Most accounts are never sent it. The
 * patch hands the list to {@link #types} as it's returned, with Facebook's Save constant. A hidden
 * button's type is dropped, and Save is added at the end while its switch is on. A row with no
 * buttons stays empty, so a post that shows no row doesn't get one, and a row whose buttons are
 * all hidden shows nothing, the way Facebook draws an empty one.
 */
public final class PostSaveButton {
    private PostSaveButton() {}

    /** The source every event of the button carries in the diagnostic report. */
    static final String SOURCE = "PostSaveButton";

    /** The type names of the buttons a switch can hide: Like is Facebook's REACT. */
    static final String LIKE = "REACT";
    static final String COMMENT = "COMMENT";
    /** The two arrows between Comment and Share, the type Facebook names REPOST (587). */
    static final String REPOST = "REPOST";
    static final String SHARE = "SHARE";
    static final String SEND = "SEND";

    /**
     * [types] without the buttons switched off, and with [save] added at the end while its switch
     * is on, or [types] itself when nothing changes, paused, before the settings are ready, or
     * when the row is empty. Never throws.
     */
    public static List<?> types(List<?> types, Object save) {
        try {
            if (types == null || types.isEmpty() || !Utils.settingsReady()) return types;
            boolean hides = Settings.HIDE_POST_LIKE_BUTTON.get() || Settings.HIDE_POST_COMMENT_BUTTON.get()
                    || Settings.HIDE_POST_REPOST_BUTTON.get() || Settings.HIDE_POST_SHARE_BUTTON.get()
                    || Settings.HIDE_POST_SEND_BUTTON.get();
            boolean adds = save != null && Settings.POST_SAVE_BUTTON.get();
            if (!hides && !adds) return types;
            HookStatus.invoked(FamilyNames.POST_ACTION_BUTTONS);
            List<Object> row = new ArrayList<>(types.size() + 1);
            for (Object type : types) {
                if (!hidden(type)) row.add(type);
            }
            if (adds && !row.contains(save)) row.add(save);
            if (row.equals(types)) return types;
            HookStatus.bound(FamilyNames.POST_ACTION_BUTTONS, "post action row");
            return row;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.POST_ACTION_BUTTONS, "post action row", t);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                () -> "could not change the buttons under a post", t);
            return types;
        }
    }

    /** True when [type]'s button is switched off. Facebook's types are enum constants. */
    private static boolean hidden(Object type) {
        if (type == null) return false;
        String name = type instanceof Enum ? ((Enum<?>) type).name() : type.toString();
        switch (name) {
            case LIKE: return Settings.HIDE_POST_LIKE_BUTTON.get();
            case COMMENT: return Settings.HIDE_POST_COMMENT_BUTTON.get();
            case REPOST: return Settings.HIDE_POST_REPOST_BUTTON.get();
            case SHARE: return Settings.HIDE_POST_SHARE_BUTTON.get();
            case SEND: return Settings.HIDE_POST_SEND_BUTTON.get();
            default: return false;
        }
    }
}
