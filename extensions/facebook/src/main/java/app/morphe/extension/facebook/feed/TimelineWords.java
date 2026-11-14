/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * Hide posts with words you choose and Hide link posts on a profile's or a Page's timeline and in
 * groups (#458).
 *
 * <p>Every post on a timeline is drawn by the one timeline story component Hide sponsored profile
 * posts judges, and Hide posts by words goes first in its render method too, with the unit the row
 * draws. Group posts are drawn by Facebook's story component, the one that draws a post on every
 * surface, so its check is handed the story and the row's environment too, and judges only a row
 * whose environment names one of {@link #GROUP_LISTS}. Search results, a post opened on its own and
 * the News feed (which the feed guard judges) are drawn as they come. True answers no component,
 * which a render method already does for a row it won't draw, so the post leaves no gap.
 *
 * <p>The words are read the way a News feed post's are ({@link PostWords#storyKind}, a keep phrase
 * still winning), and the link rule is the Reels tab's ({@link PostTypes#captionLinkKind}): a post
 * whose card leads off Facebook, or whose words name a website off it. Both run on the News feed
 * switches.
 *
 * <p>It fails open: switches off, a pause, settings that aren't ready, a unit that isn't a story or
 * can't be read, a list it can't name, or any failure in here, and Facebook draws the row. Each row
 * read is counted on the rule's route under what the read found, each list a switch saw is counted
 * by name on {@link #LISTS}, and nothing a post says is logged.
 */
public final class TimelineWords {
    /** What reads one story for a rule: the patch's readers, or a test's stand-ins. */
    interface Reader {
        String kind(@Nullable Object story);
    }

    /** What names the list a row's environment says it's in: the patch's stubs, or a test's stand-in. */
    interface ListReader {
        @Nullable
        String name(@Nullable Object environment);
    }

    /** One rule on a surface: its switch, its route, its reader and what takes a row off. */
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
    static final Rule GROUP_WORDS = new Rule("Group posts with your words", Settings.HIDE_POSTS_WITH_WORDS,
            PostWords::storyHides);
    static final Rule GROUP_LINKS = new Rule("Group posts with links", Settings.HIDE_LINK_POSTS,
            PostTypes::captionLinkHides);

    /** Where each list the story component drew a row in is counted, while a switch is on. */
    static final String LISTS = "Story lists";

    /** What a row's environment answers when its list can't be named. */
    static final String UNNAMED_LIST = "unnamed";

    /**
     * The lists of group posts, by Facebook's own names for them: a group's feed and its pinned
     * posts, a member's posts in a group, the Groups tab's feed of every group, and a set of groups.
     * An admin's queues (pending, reported, scheduled) aren't here, so an admin still sees each post
     * they have to act on.
     */
    static final Set<String> GROUP_LISTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "GROUPS", "GROUPS_PINNED", "GROUPS_MEMBER_BIO_FEED", "CROSS_GROUP_FEED", "GROUP_SET_FEED")));

    static final Reader WORDS_READER = PostWords::storyKind;
    static final Reader LINKS_READER = PostTypes::captionLinkKind;
    static final ListReader LIST_READER = TimelineWords::listNameOf;

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
            return judge(unit, WORDS, LINKS, words, links, "Profile timeline row");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "timeline row", failure);
            Logger.printException(() -> "Post words: could not judge a timeline row", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in the story component's render method. True answers no
     * component, so the row isn't drawn. Never throws.
     *
     * @param story       the story the row draws, from the component's props.
     * @param environment the row's feed environment, which knows the list the row is in.
     */
    public static boolean hideInList(Object story, Object environment) {
        return hideInList(story, environment, LIST_READER, WORDS_READER, LINKS_READER);
    }

    /** {@link #hideInList(Object, Object)} with the readers passed in, so a test can name the list. */
    static boolean hideInList(@Nullable Object story, @Nullable Object environment, ListReader lists,
                              Reader words, Reader links) {
        try {
            HookStatus.invoked(FamilyNames.POST_WORDS);
            if (!Utils.settingsReady()) return false;
            // The story component draws every post, the News feed's included, so nothing is read
            // until a switch is on.
            if (!GROUP_WORDS.switchedOn() && !GROUP_LINKS.switchedOn()) return false;
            String list = lists.name(environment);
            FeedFilterCounters.sawList(LISTS, 1);
            FeedFilterCounters.sawKind(LISTS, list == null ? UNNAMED_LIST : list);
            if (list == null || !GROUP_LISTS.contains(list)) return false;
            return judge(story, GROUP_WORDS, GROUP_LINKS, words, links, "Group feed row");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "group feed row", failure);
            Logger.printException(() -> "Post words: could not judge a group feed row", failure);
            return false;
        }
    }

    /** Whether [unit] comes off by [wordsRule], or else by [linksRule], each when its switch is on. */
    private static boolean judge(@Nullable Object unit, Rule wordsRule, Rule linksRule, Reader words, Reader links,
                                 String where) {
        if (wordsRule.switchedOn() && wordsRule.hides(unit, words)) {
            PostWords.HIDDEN.incrementAndGet();
            Logger.printDebug(() -> where + ": hid a post with your words");
            return true;
        }
        if (linksRule.switchedOn() && linksRule.hides(unit, links)) {
            Logger.printDebug(() -> where + ": hid a link post");
            return true;
        }
        return false;
    }

    /**
     * The name of the list [environment] says its row is in, through the two stubs, or null when
     * there's no environment, the stubs aren't filled in or the answer isn't a list name.
     */
    @Nullable
    static String listNameOf(@Nullable Object environment) {
        if (environment == null) return null;
        Object type = listType(environment);
        if (type == null || type == StoryFlag.NOT_PATCHED) return null;
        Object name = listName(type);
        return name instanceof Enum ? ((Enum<?>) name).name() : null;
    }

    /**
     * The list type a row's environment holds. Hide posts by words fills this in with a call to the
     * environment's renamed getter of it; until then it answers {@link StoryFlag#NOT_PATCHED}.
     */
    public static Object listType(Object environment) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * The list's name, Facebook's own enum of them, from a list type. Hide posts by words fills
     * this in with a call to the type's renamed getter; until then it answers
     * {@link StoryFlag#NOT_PATCHED}.
     */
    public static Object listName(Object listType) {
        return StoryFlag.NOT_PATCHED;
    }
}
