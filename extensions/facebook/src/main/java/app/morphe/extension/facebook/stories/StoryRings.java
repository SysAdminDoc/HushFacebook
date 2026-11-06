/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide story rings patch asks as a profile picture draws (#397).
 *
 * <p>Facebook draws a ring around someone's picture when they have a story up or are live. A switch
 * each takes it off the pictures at the top of posts, beside comments, and on profiles (with the
 * lists and search results that draw a profile picture the same way). In the feed the picture then
 * opens the profile on a tap, as it does for someone with no story.
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and Facebook draws its own.
 */
public final class StoryRings {
    /** Counted each time a post header's picture is drawn without its ring. */
    static final String FEED_RING_HIDDEN = "feed story ring hidden";

    /** Counted each time a comment's picture is drawn without its ring. */
    static final String COMMENT_RING_HIDDEN = "comment story ring hidden";

    /** Counted each time a profile picture is drawn without its ring. */
    static final String PROFILE_RING_HIDDEN = "profile story ring hidden";

    private StoryRings() {
    }

    /**
     * Facebook's answer to whether a post header's picture gets a story or LIVE ring, or false with
     * the feed switch on. Takes an int, since the answer can come from a register typed as one.
     */
    public static boolean feedRing(int shown) {
        try {
            HookStatus.invoked(FamilyNames.STORY_RINGS);
            HookStatus.bound(FamilyNames.STORY_RINGS, "feed ring check");
            if (shown == 0) return false;
            if (!Utils.settingsReady() || !Settings.HIDE_FEED_STORY_RINGS.get()) return true;
            HookStatus.counted(FamilyNames.STORY_RINGS, FEED_RING_HIDDEN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_RINGS, "feed ring check", failure);
            return shown != 0;
        }
    }

    /** Whether a comment's picture skips its story ring, which the comments switch decides. */
    public static boolean hideCommentRing() {
        try {
            HookStatus.invoked(FamilyNames.STORY_RINGS);
            HookStatus.bound(FamilyNames.STORY_RINGS, "comment ring");
            if (!Utils.settingsReady() || !Settings.HIDE_COMMENT_STORY_RINGS.get()) return false;
            HookStatus.counted(FamilyNames.STORY_RINGS, COMMENT_RING_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_RINGS, "comment ring", failure);
            return false;
        }
    }

    /**
     * The ring a profile picture is about to draw with, or null, Facebook's own answer for someone
     * with no story, when the profiles switch is on.
     */
    public static Object profileRing(Object ring) {
        try {
            HookStatus.invoked(FamilyNames.STORY_RINGS);
            HookStatus.bound(FamilyNames.STORY_RINGS, "profile ring");
            if (ring == null) return null;
            if (!Utils.settingsReady() || !Settings.HIDE_PROFILE_STORY_RINGS.get()) return ring;
            HookStatus.counted(FamilyNames.STORY_RINGS, PROFILE_RING_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_RINGS, "profile ring", failure);
            return ring;
        }
    }
}
