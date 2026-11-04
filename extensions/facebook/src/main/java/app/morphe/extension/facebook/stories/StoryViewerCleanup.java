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
 * What the Clean up the story viewer patch asks as parts of a story draw (#380).
 *
 * <p>The line under the poster's name shows a song, effect or other credit in place of its plain
 * subtitle, or turns between the two. With the credits switch on, a lone credit draws nothing and
 * a turning one stays on the plain subtitle. The reply suggestions switch takes the row of
 * suggested replies off the bottom of a story, leaving the reply box.
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and Facebook draws its own.
 */
public final class StoryViewerCleanup {
    /** Counted each time a story's credit line is drawn without its credits. */
    static final String CREDITS_HIDDEN = "story credit line hidden";

    /** Counted each time a story's suggested replies aren't drawn. */
    static final String SUGGESTIONS_HIDDEN = "story reply suggestions hidden";

    private StoryViewerCleanup() {
    }

    /** Injection point, first thing in both credit line renders: whether to leave the credits out. Never throws. */
    public static boolean hideCredits() {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story credit line");
            if (!Utils.settingsReady() || !Settings.HIDE_STORY_CREDITS.get()) return false;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, CREDITS_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story credit line", failure);
            return false;
        }
    }

    /** Injection point, first thing in the reply suggestions' render: whether to draw nothing. Never throws. */
    public static boolean hideReplySuggestions() {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story reply suggestions");
            if (!Utils.settingsReady() || !Settings.HIDE_STORY_REPLY_SUGGESTIONS.get()) return false;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, SUGGESTIONS_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story reply suggestions", failure);
            return false;
        }
    }
}
