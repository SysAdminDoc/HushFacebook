/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import java.util.function.Predicate;

import app.morphe.extension.facebook.feed.PostTypes;
import app.morphe.extension.facebook.feed.PostWords;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * Hide posts with words you choose and Hide link posts on the Reels tab (#458). A reel's caption
 * sits in the story its item holds next to the reel's model, the same GraphQLStory a News feed post
 * is, so each rule reads it the way it reads a post: the word lists judge the caption's words
 * ({@link PostWords#storyKind}), and the link rule takes a reel whose story shares a website or
 * whose caption names one off Facebook ({@link PostTypes#captionLinkKind}). Both run beside Hide
 * posts in other languages ({@link ReelLanguages}) in Clean up Reels' page and section filters
 * ({@link FollowedReels}), on the News feed switches.
 *
 * <p>A reel with no story, no caption or one that can't be read stays, counted on the rule's route
 * under what the read found.
 */
final class ReelWords {
    /** One of the two rules: its switch, the route each reel it reads is counted on, and what takes a reel off. */
    static final class Rule {
        final String route;
        private final BooleanSetting setting;
        private final Predicate<String> hides;
        private final String failure;

        Rule(String route, BooleanSetting setting, Predicate<String> hides, String failure) {
            this.route = route;
            this.setting = setting;
            this.hides = hides;
            this.failure = failure;
        }

        /** The switch, shared with News feed. Off, unreadable, or asked before the settings are ready, reels stay. */
        boolean switchedOn() {
            try {
                return Utils.settingsReady() && setting.get();
            } catch (Throwable thrown) {
                HookStatus.threw(FamilyNames.REEL_DECLUTTER, failure + " switch read", thrown);
                return false;
            }
        }

        /** Whether [kind] takes the reel off. */
        boolean hides(String kind) {
            return hides.test(kind);
        }

        /** Whether the item comes off its page, counted on {@link #route} under what the read found. */
        boolean drops(@Nullable Object item, String modelClassName, ReelLanguages.Captions reader) {
            String kind = kindOf(item, modelClassName, reader);
            FeedFilterCounters.sawList(route, 1);
            FeedFilterCounters.sawKind(route, kind);
            if (!hides(kind)) return false;
            FeedFilterCounters.removed(route, 1, kind);
            if (this == WORDS) PostWords.countHiddenReel();
            return true;
        }

        /** What one item counts as for this rule. Never throws. */
        String kindOf(@Nullable Object item, String modelClassName, ReelLanguages.Captions reader) {
            return ReelLanguages.kindOf(item, modelClassName, reader, failure);
        }
    }

    static final Rule WORDS = new Rule("Reels with your words", Settings.HIDE_POSTS_WITH_WORDS,
            PostWords::storyHides, "reel caption words");
    static final Rule LINKS = new Rule("Reels with links", Settings.HIDE_LINK_POSTS,
            PostTypes::captionLinkHides, "reel caption links");

    /** The readers each rule judges a reel's story with: the patch's, or a test's stand-ins. */
    static final class Readers {
        final ReelLanguages.Captions languages;
        final ReelLanguages.Captions words;
        final ReelLanguages.Captions links;

        Readers(ReelLanguages.Captions languages, ReelLanguages.Captions words, ReelLanguages.Captions links) {
            this.languages = languages;
            this.words = words;
            this.links = links;
        }
    }

    static final Readers PATCHED = new Readers(ReelLanguages.PATCHED, PostWords::storyKind, PostTypes::captionLinkKind);

    /** The patch's readers with [languages] reading captions for the language rule. */
    static Readers withLanguages(ReelLanguages.Captions languages) {
        return new Readers(languages, PATCHED.words, PATCHED.links);
    }

    private ReelWords() {
    }
}
