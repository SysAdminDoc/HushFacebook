/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.util.function.Predicate;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * Hide posts with words you choose and Hide link posts on a profile's or a Page's timeline (#458).
 * Every post there is drawn by the one timeline story component Hide sponsored profile posts judges,
 * and Hide posts by words goes first in its render method too, with the unit the row draws. True
 * answers no component, which the render method already does for a row it won't draw, so the post
 * leaves no gap.
 *
 * <p>The words are read the way a News feed post's are ({@link PostWords#storyKind}, a keep phrase
 * still winning), and the link rule is the Reels tab's ({@link PostTypes#captionLinkKind}): a post
 * whose card leads off Facebook, or whose words name a website off it. Both run on the News feed
 * switches.
 *
 * <p>It fails open: switches off, a pause, settings that aren't ready, a unit that isn't a story or
 * can't be read, or any failure in here, and Facebook draws the row. Each row read is counted on
 * the rule's route under what the read found, and nothing a post says is logged.
 */
public final class TimelineWords {
    /** What reads one story for a rule: the patch's readers, or a test's stand-ins. */
    interface Reader {
        String kind(@Nullable Object story);
    }

    /** One rule on a timeline: its switch, its route, its reader and what takes a row off. */
    static final class Rule {
        final String route;
        private final BooleanSetting setting;
        private final Predicate<String> hides;

        Rule(String route, BooleanSetting setting, Predicate<String> hides) {
            this.route = route;
            this.setting = setting;
            this.hides = hides;
        }

        boolean switchedOn() {
            return setting.get();
        }

        /** Whether [unit] comes off, counted on {@link #route} under what [reader] found. */
        boolean hides(@Nullable Object unit, Reader reader) {
            String kind = reader.kind(unit);
            FeedFilterCounters.sawList(route, 1);
            FeedFilterCounters.sawKind(route, kind);
            if (!hides.test(kind)) return false;
            FeedFilterCounters.removed(route, 1, kind);
            return true;
        }
    }

    static final Rule WORDS = new Rule("Profile posts with your words", Settings.HIDE_POSTS_WITH_WORDS,
            PostWords::storyHides);
    static final Rule LINKS = new Rule("Profile posts with links", Settings.HIDE_LINK_POSTS,
            PostTypes::captionLinkHides);

    static final Reader WORDS_READER = PostWords::storyKind;
    static final Reader LINKS_READER = PostTypes::captionLinkKind;

    private TimelineWords() {
    }

    /**
     * Injection point, first thing in the timeline story component's render method. True answers
     * no component, so the row isn't drawn. Never throws.
     *
     * @param unit the timeline unit the row draws.
     */
    public static boolean hide(Object unit) {
        return hide(unit, WORDS_READER, LINKS_READER);
    }

    /** {@link #hide(Object)} with the readers passed in, so a test can hand it a story's words. */
    static boolean hide(@Nullable Object unit, Reader words, Reader links) {
        try {
            HookStatus.invoked(FamilyNames.POST_WORDS);
            if (!Utils.settingsReady()) return false;
            if (WORDS.switchedOn() && WORDS.hides(unit, words)) {
                PostWords.HIDDEN.incrementAndGet();
                Logger.printDebug(() -> "Profile timeline row: hid a post with your words");
                return true;
            }
            if (LINKS.switchedOn() && LINKS.hides(unit, links)) {
                Logger.printDebug(() -> "Profile timeline row: hid a link post");
                return true;
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "timeline row", failure);
            Logger.printException(() -> "Post words: could not judge a timeline row", failure);
            return false;
        }
    }
}
