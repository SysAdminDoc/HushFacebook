/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.feed.GenAiReelFilter;
import app.morphe.extension.facebook.feed.PostLanguages;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hide posts in other languages on the Reels tab (#382). A reel on the tab keeps its caption in the
 * story its item holds next to the reel's model, the same GraphQLStory a post in News feed is, so
 * the rule reads it with {@link PostLanguages#storyKind} and takes the reel off when the caption is
 * in a language that isn't kept. Clean up Reels' page and section filters ({@link FollowedReels})
 * run it on every Reels page they're handed.
 *
 * <p>A reel with no story, no caption, too few letters or a caption the phone isn't sure of stays,
 * each counted on {@link #ROUTE} under what the read found.
 */
final class ReelLanguages {
    /** Every reel the rule read, counted only while its switch is on, with what it found as the kind. */
    static final String ROUTE = "Reels in other languages";
    static final String NO_STORY = "no reel story";
    static final String READ_FAILED = "read failed";

    /** What the rule makes of a reel's story: the language rule's kind, read the way it reads a post. */
    interface Captions {
        String kind(Object story);
    }

    static final Captions PATCHED = PostLanguages::storyKind;

    private ReelLanguages() {
    }

    /** The switch, shared with News feed. Off, unreadable, or asked before the settings are ready, reels stay. */
    static boolean switchedOn() {
        try {
            return Utils.settingsReady() && Settings.HIDE_OTHER_LANGUAGES.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "reel languages switch read", failure);
            return false;
        }
    }

    /** Whether [kind] takes the reel off. */
    static boolean hides(String kind) {
        return PostLanguages.REASON.equals(kind);
    }

    /** Whether the item comes off its page, counted on {@link #ROUTE} under what the read found. */
    static boolean drops(@Nullable Object item, String modelClassName, Captions captions) {
        String kind = kindOf(item, modelClassName, captions);
        FeedFilterCounters.sawList(ROUTE, 1);
        FeedFilterCounters.sawKind(ROUTE, kind);
        if (!hides(kind)) return false;
        FeedFilterCounters.removed(ROUTE, 1, kind);
        return true;
    }

    /** What one item counts as, by its caption's language. Never throws. */
    static String kindOf(@Nullable Object item, String modelClassName, Captions captions) {
        return kindOf(item, modelClassName, captions, "reel caption language");
    }

    /**
     * What [captions] makes of the item's reel story, the kinds any caption rule shares when there's
     * no story or the read fails, which goes in the report as [failure]. Never throws.
     */
    static String kindOf(@Nullable Object item, String modelClassName, Captions captions, String failure) {
        Object story;
        try {
            story = GenAiReelFilter.reelStory(item, modelClassName);
        } catch (Throwable thrown) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "reel story reader", thrown);
            return READ_FAILED;
        }
        if (story == null) return NO_STORY;
        try {
            return captions.kind(story);
        } catch (Throwable thrown) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, failure, thrown);
            return READ_FAILED;
        }
    }
}
