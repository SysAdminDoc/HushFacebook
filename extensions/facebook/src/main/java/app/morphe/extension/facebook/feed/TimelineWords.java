/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
 * Hide posts with words you choose and Hide link posts on a profile's or a Page's timeline, in
 * groups and in search results (#458).
 *
 * <p>Every post on a timeline is drawn by the one timeline story component Hide sponsored profile
 * posts judges, and Hide posts by words goes first in its render method too, with the unit the row
 * draws. Group posts and search results are drawn by Facebook's story component, the one that draws
 * a post on every surface, so its check is handed the story and the row's environment too, and
 * judges only a row whose environment names one of {@link #GROUP_LISTS} or {@link #SEARCH_LISTS}. A
 * post opened on its own and the News feed (which the feed guard judges) are drawn as they come. True answers no component,
 * which a render method already does for a row it won't draw, so the post leaves no gap.
 *
 * <p>The cards on a search's Posts tab and the posts mixed into All aren't drawn by that component.
 * Each comes in a page of search results as a module carrying its post as a GraphQLStory, so
 * {@link #keptSearchResults} judges every module's story before the page is built, and a module the
 * search switches take off never reaches the list on screen.
 *
 * <p>The words are read the way a News feed post's are ({@link PostWords#storyKind}, a keep phrase
 * still winning), and the link rule is the Reels tab's ({@link PostTypes#captionLinkKind}): a post
 * whose card leads off Facebook, or whose words name a website off it. Both run on the News feed
 * switches, each with its own switch for profiles and Pages, one for groups and one for search
 * results under it. The search ones start off. A search result's post that isn't an ad comes with
 * no story on 588, so the words its card shows go by the same lists and the same web address rule.
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

    /** One rule on a surface: its switch, the surface's own switch, its route and what takes a row off. */
    static final class Rule {
        final String route;
        private final BooleanSetting setting;
        private final BooleanSetting place;
        private final Predicate<String> hides;

        Rule(String route, BooleanSetting setting, BooleanSetting place, Predicate<String> hides) {
            this.route = route;
            this.setting = setting;
            this.place = place;
            this.hides = hides;
        }

        boolean switchedOn() {
            return setting.get() && place.get();
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
            Settings.POST_WORDS_ON_TIMELINES, PostWords::storyHides);
    static final Rule LINKS = new Rule("Profile posts with links", Settings.HIDE_LINK_POSTS,
            Settings.LINK_POSTS_ON_TIMELINES, PostTypes::captionLinkHides);
    static final Rule GROUP_WORDS = new Rule("Group posts with your words", Settings.HIDE_POSTS_WITH_WORDS,
            Settings.POST_WORDS_IN_GROUPS, PostWords::storyHides);
    static final Rule GROUP_LINKS = new Rule("Group posts with links", Settings.HIDE_LINK_POSTS,
            Settings.LINK_POSTS_IN_GROUPS, PostTypes::captionLinkHides);
    static final Rule SEARCH_WORDS = new Rule("Search posts with your words", Settings.HIDE_POSTS_WITH_WORDS,
            Settings.POST_WORDS_IN_SEARCH, PostWords::storyHides);
    static final Rule SEARCH_LINKS = new Rule("Search posts with links", Settings.HIDE_LINK_POSTS,
            Settings.LINK_POSTS_IN_SEARCH, PostTypes::captionLinkHides);

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

    /** The list of posts a search turns up, by Facebook's own name for it. */
    static final Set<String> SEARCH_LISTS = Collections.singleton("SEARCH_RESULTS");

    /**
     * What reads the post a search results module carries, its story or else the words its card
     * shows: the patch's stubs, or a test's stand-in.
     */
    interface ModuleReader {
        @Nullable
        Object post(@Nullable Object module);
    }

    /** Where each module of a page of search results is counted while a search switch is on. */
    static final String SEARCH_MODULES = "Search result modules";
    static final String SEARCH_POST = "post";
    static final String SEARCH_NOT_A_POST = "not a post";

    static final Reader WORDS_READER = TimelineWords::wordsKind;
    static final Reader LINKS_READER = TimelineWords::linksKind;
    static final ListReader LIST_READER = TimelineWords::listNameOf;
    static final ModuleReader MODULE_READER = TimelineWords::searchPostOf;

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
            boolean groups = GROUP_WORDS.switchedOn() || GROUP_LINKS.switchedOn();
            boolean search = SEARCH_WORDS.switchedOn() || SEARCH_LINKS.switchedOn();
            if (!groups && !search) return false;
            String list = lists.name(environment);
            FeedFilterCounters.sawList(LISTS, 1);
            FeedFilterCounters.sawKind(LISTS, list == null ? UNNAMED_LIST : list);
            if (list == null) return false;
            if (groups && GROUP_LISTS.contains(list)) {
                return judge(story, GROUP_WORDS, GROUP_LINKS, words, links, "Group feed row");
            }
            if (search && SEARCH_LISTS.contains(list)) {
                return judge(story, SEARCH_WORDS, SEARCH_LINKS, words, links, "Search result row");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "group feed row", failure);
            Logger.printException(() -> "Post words: could not judge a group feed row", failure);
            return false;
        }
    }

    /**
     * Called by the search page hook (SearchResultsPage) with a page's modules, after the Meta AI
     * and ads filters. Null keeps the list it was handed; otherwise the modules to keep, in their
     * order. Never throws.
     *
     * @param modules the page's module list.
     * @param page    the page's name, which this doesn't need.
     */
    @Nullable
    public static List<Object> keptSearchResults(@Nullable List<?> modules, @Nullable String page) {
        return keptSearchResults(modules, MODULE_READER, WORDS_READER, LINKS_READER);
    }

    /** {@link #keptSearchResults(List, String)} with the readers passed in, so a test can stand in for them. */
    @Nullable
    static List<Object> keptSearchResults(@Nullable List<?> modules, ModuleReader stories, Reader words, Reader links) {
        try {
            HookStatus.invoked(FamilyNames.POST_WORDS);
            if (modules == null || modules.isEmpty() || !Utils.settingsReady()) return null;
            if (!SEARCH_WORDS.switchedOn() && !SEARCH_LINKS.switchedOn()) return null;
            FeedFilterCounters.sawList(SEARCH_MODULES, modules.size());
            ArrayList<Object> kept = null;
            int posts = 0;
            for (int index = 0; index < modules.size(); index++) {
                Object module = modules.get(index);
                Object post = stories.post(module);
                FeedFilterCounters.sawKind(SEARCH_MODULES, post == null ? SEARCH_NOT_A_POST : SEARCH_POST);
                boolean hidden = false;
                if (post != null) {
                    posts++;
                    hidden = judge(post, SEARCH_WORDS, SEARCH_LINKS, words, links, "Search result");
                }
                if (hidden) {
                    if (kept == null) kept = new ArrayList<>(modules.subList(0, index));
                } else if (kept != null) {
                    kept.add(module);
                }
            }
            final int total = modules.size();
            final int read = posts;
            final int left = kept == null ? total : kept.size();
            Logger.printDebug(() -> "Search results: " + total + " modules, " + read + " posts read, "
                    + (total - left) + " taken off");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "search results page", failure);
            Logger.printException(() -> "Post words: could not judge a page of search results", failure);
            return null;
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
     * What the lists make of [post]: a story's words the way a News feed post's are read, or the
     * text a search result's card shows when the result comes with no story.
     */
    static String wordsKind(@Nullable Object post) {
        return post instanceof String ? PostWords.textKind((String) post) : PostWords.storyKind(post);
    }

    /** What the link rule makes of [post], read the same two ways as {@link #wordsKind}. */
    static String linksKind(@Nullable Object post) {
        return post instanceof String ? PostTypes.textLinkKind((String) post) : PostTypes.captionLinkKind(post);
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

    /**
     * The post [module] carries: its story, which Facebook fills in only for an ad, or else the
     * words its card shows ("" for a post with none), or null when it isn't a search module or
     * carries no post.
     */
    @Nullable
    static Object searchPostOf(@Nullable Object module) {
        if (module == null || !isSearchModule(module)) return null;
        Object story = searchStory(module);
        return story != null ? story : searchPostText(module);
    }

    /**
     * Injection point, filled in by Hide posts by words: whether an item of a page of search results
     * is a search module. The patch replaces this body with an instance-of the module class, whose
     * name changes every build. Until then nothing is, so no module is read.
     */
    public static boolean isSearchModule(Object item) {
        return false;
    }

    /**
     * Injection point, filled in by Hide posts by words: the GraphQLStory a search module carries,
     * or null. The patch replaces this body with a read of the module's story field, which Facebook
     * fills in only for an ad. Only an item {@link #isSearchModule} took may be passed.
     */
    public static Object searchStory(Object module) {
        return null;
    }

    /**
     * Injection point, filled in by Hide posts by words: the words of the post a search module's
     * card shows, "" for a post with none, or null when the module carries no post. On 588 every
     * post that isn't an ad comes with no story, and its words are on the module's own tree. The
     * patch replaces this body with the calls Facebook's search card makes to read them. Only an
     * item {@link #isSearchModule} took may be passed.
     */
    public static String searchPostText(Object module) {
        return null;
    }
}
