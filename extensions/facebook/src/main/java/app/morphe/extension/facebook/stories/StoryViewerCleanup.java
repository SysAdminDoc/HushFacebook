/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Clean up the story viewer patch asks as parts of a story draw (#380).
 *
 * <p>The line under the poster's name shows a song, effect or other credit in place of its plain
 * subtitle, or turns between the two. With the credits switch on, a lone credit draws nothing and
 * a turning one stays on the plain subtitle. Most stories show their credits as pills in a column
 * under the name instead, and the same switch leaves out the song and effect pills there while a
 * reshare, an archive or a close friends pill stays. The reply suggestions switch takes the row of
 * suggested replies off the bottom of a story, leaving the reply box, and the Subscribe switch the
 * Subscribe pill under the name. The create cards switch keeps out the "Add to story" card Facebook
 * puts between other people's stories, the plain one and the ones built from the gallery (#439).
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and Facebook draws its own.
 */
public final class StoryViewerCleanup {
    /** Counted each time a story's credit line is drawn without its credits. */
    static final String CREDITS_HIDDEN = "story credit line hidden";

    /** Counted each time a song or effect pill is left out of the column under the name. */
    static final String PILLS_DROPPED = "story credit pills dropped";

    /** Counted each time a story's suggested replies aren't drawn. */
    static final String SUGGESTIONS_HIDDEN = "story reply suggestions hidden";

    /** Counted each time a story's Subscribe pill isn't drawn. */
    static final String SUBSCRIBE_HIDDEN = "story Subscribe pill hidden";

    /** Counted each time the viewer's list of stories is built without the "Add to story" card. */
    static final String CREATE_CARDS_HIDDEN = "story create cards left out";

    /**
     * The pill types, by Facebook's own names for them, that credit a song, a sound or the effect,
     * filter, AI tool or app a story was made with. A type not named here keeps its pill: reshares,
     * close friends, archive and highlights, notes, AI info labels and anything Facebook adds later.
     */
    static final Set<String> CREDIT_PILLS = new HashSet<>(Arrays.asList(
            "MUSIC", "SPOTIFY", "ADD_TO_SPOTIFY", "SOUND_SYNC", "MUSE", "GROOVES",
            "EFFECT", "AI_BACKDROP", "AI_CAPTION", "AI_EXPANDER", "AI_IMAGINE", "AI_PHOTO_EDIT",
            "AI_READY_MADE_CONTENT", "AI_STYLES", "BOOMERANG", "MAGIC_MOD", "MOTION", "PHOTO_COLLAGE",
            "REMIX", "TEXT_MODE", "NO_EDIT", "DUAL", "AVATAR", "META_AI_APP", "VIBES_APP", "STELLA",
            "ENHANCED_WEARABLES"));

    private StoryViewerCleanup() {
    }

    /**
     * Injection point, in the pill column's render right before it sorts a pill by its type:
     * whether to leave this pill out. Never throws.
     */
    public static boolean dropCredit(Enum<?> type) {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story credit pills");
            if (type == null || !Utils.settingsReady() || !Settings.HIDE_STORY_CREDITS.get()) return false;
            if (!CREDIT_PILLS.contains(type.name())) return false;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, PILLS_DROPPED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story credit pills", failure);
            return false;
        }
    }

    /** Injection point, first thing in the Subscribe pill's render: whether to draw nothing. Never throws. */
    public static boolean hideSubscribe() {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story Subscribe pill");
            if (!Utils.settingsReady() || !Settings.HIDE_STORY_SUBSCRIBE.get()) return false;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, SUBSCRIBE_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story Subscribe pill", failure);
            return false;
        }
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

    /**
     * Injection point, first thing in the story list source that splices the "Add to story" card in
     * between other people's stories: whether to hand the list back as it came. Never throws.
     */
    public static boolean hideCreateStoryCard() {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story create card");
            if (!Utils.settingsReady() || !Settings.HIDE_STORY_CREATE_CARDS.get()) return false;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, CREATE_CARDS_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story create card", failure);
            return false;
        }
    }
}
