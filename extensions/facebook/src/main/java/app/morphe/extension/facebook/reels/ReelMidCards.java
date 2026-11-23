/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * Takes the "Threads you might like" card out from between reels, for Clean up Reels (#85), the
 * "To what extent does this video match your interests?" survey card, for Hide reel interest
 * prompts (#405), the "Suggested for you" groups card with its Join buttons, for Hide suggested
 * groups (#441), and the carousel of posts like Groups, "Catch up on conversations", for Hide post
 * cards between reels (#441).
 *
 * <p>The cards Facebook puts between reels, people you may know, groups to join, games, a survey
 * and a Threads post with an Open Threads button among them, are Reels items of one class, the
 * mid-card item. Each keeps a unit whose model answers the card's type as a constant of a GraphQL
 * enum: the Threads card's is {@link #THREADS_MID_CARD}, the survey's {@link #USER_SURVEY}, the
 * groups card's {@link #GROUPS_YOU_SHOULD_JOIN} and the post carousel's {@link #FEED_POSTS}. The
 * post carousel belongs to Clean up Reels like the Threads card. The item class and the three reads are Redex
 * names, so the patch passes the item's class name in with each page and fills in
 * {@link #midCardType}.
 *
 * <p>The hook is one patch the two Reels patches depend on, so each card comes off only while the
 * patch it belongs to is in the build and its switch is on. The groups card belongs to Hide
 * suggested and promoted posts, whose Hide suggested groups switch reaches it only when one of the
 * two Reels patches put the hook in. A card comes off each section's item list through the walk
 * every page filter shares ({@link ReelSections}), the lists the screen reads, so it never reaches
 * the viewer. While any of the four switches is on, every item the walk reads is counted on
 * the report's Kinds line: a mid-card by its type, anything else as {@link #NOT_A_MID_CARD}
 * followed by its class name. So a report names any other card too, and a photo card among the
 * reels (#267) shows up as a class of its own.
 *
 * <p>Off, paused, before the settings are ready, or when anything fails, the page goes on as
 * Facebook sent it.
 */
public final class ReelMidCards {
    /** The mid-card type of the Threads card. */
    static final String THREADS_MID_CARD = "THREADS_MIDCARD";

    /** The mid-card type of the interest survey. */
    static final String USER_SURVEY = "USER_SURVEY";

    /** The mid-card type of the "Suggested for you" groups card, groups you should join. */
    static final String GROUPS_YOU_SHOULD_JOIN = "GYSJ_MIDCARD";

    /**
     * The mid-card type of a carousel of feed posts, like Groups, "Catch up on conversations" (#441),
     * drawn by FbShortsNewsFeedMidCardPreviewComponent (587).
     */
    static final String FEED_POSTS = "NEWS_FEED_MIDCARD";

    /** The source every event of this filter carries in the diagnostic report. */
    private static final String SOURCE = "ReelMidCards";

    /** The diagnostic counter route: each page of sections handed in, and every item read. */
    static final String SECTIONS_ROUTE = "Reel mid-cards";

    /** The route for items going into the Reels item collection: pages, appends and single items. */
    static final String ITEMS_ROUTE = "Reel mid-card items";

    /** The route for the collection's listener walk, counted apart so the items route counts each item once. */
    static final String ANNOUNCED_ROUTE = "Reel mid-card announcements";

    /** What an item counts as. Only the Threads card, the survey, the groups card and the post carousel come off. */
    static final String MID_CARD = "mid-card ";
    static final String THREADS_CARD = MID_CARD + THREADS_MID_CARD;
    static final String SURVEY_CARD = MID_CARD + USER_SURVEY;
    static final String GROUPS_CARD = MID_CARD + GROUPS_YOU_SHOULD_JOIN;
    static final String POSTS_CARD = MID_CARD + FEED_POSTS;
    /**
     * A Meta quick promotion between reels. Facebook makes it a Reels item of its own class, not a
     * mid-card, and since 587 the Threads card comes this way too (#85), so it goes with the
     * Threads switch.
     */
    static final String PROMOTION_CARD = "promo card";
    static final String NOT_A_MID_CARD = "not a mid-card";
    static final String NO_TYPE = "mid-card without a type";
    static final String NOT_PATCHED = "type reader not patched";
    static final String READ_FAILED = "read failed";

    /** What {@link #midCardType} answers until the patch fills it in. */
    static final Object UNPATCHED = new Object();

    /** A mid-card item's type, read through the stub the patch filled in or a test's stand-in. */
    interface Types {
        /** The item's type, an enum constant or null, or {@link #UNPATCHED}. */
        @Nullable
        Object type(Object item);
    }

    static final Types PATCHED = ReelMidCards::midCardType;

    /** Whether a test says Clean up Reels is in the build, or null to ask SettingsStatus. */
    static volatile Boolean threadsPatchForTests;

    /** Whether a test says Hide reel interest prompts is in the build, or null to ask SettingsStatus. */
    static volatile Boolean promptsPatchForTests;

    /** Whether a test says Hide suggested and promoted posts is in the build, or null to ask SettingsStatus. */
    static volatile Boolean groupsPatchForTests;

    /** A test's stand-in for the promo item class the patch fills into {@link #promotionItemClass}, or null. */
    @Nullable
    static volatile String promotionClassForTests;

    private ReelMidCards() {
    }

    /**
     * Injection point, first thing in the Reels controller's method taking a page of sections. The
     * same page with no Threads card, survey, groups card or post carousel left in any section, each while its
     * patch is in and its switch on, or the very same page when none is or there was none. Never throws.
     *
     * @param page         the sections about to be added to the Reels list.
     * @param midCardClass binary name of the mid-card item class, for example {@code X.8bO}.
     */
    public static List<?> withoutThreadsCards(List<?> page, String midCardClass) {
        return withoutThreadsCards(page, midCardClass, PATCHED);
    }

    /** {@link #withoutThreadsCards(List, String)} with the type read passed in, so a test can stand in for the stub. */
    static List<?> withoutThreadsCards(List<?> page, String midCardClass, Types types) {
        Plan plan = Plan.now();
        FeedFilterCounters.sawList(SECTIONS_ROUTE, page == null ? 0 : page.size());
        if (page == null || page.isEmpty() || !plan.any()) return page;

        try {
            ReelSections.Result result = ReelSections.strip(page, item -> {
                String kind = kindOf(item, midCardClass, types, plan.family);
                FeedFilterCounters.sawKind(SECTIONS_ROUTE, kind);
                return plan.drops(kind);
            }, plan.family, SOURCE);
            if (result.dropped == 0) return page;

            plan.count(SECTIONS_ROUTE, page.size() + " section(s)");
            return result.page;
        } catch (Throwable failure) {
            // Anything thrown here would go on into Facebook's Reels page insert.
            HookStatus.threw(plan.family, "mid-card filter", failure);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "could not filter a page of sections", failure);
            return page;
        }
    }

    /**
     * Injection point, first thing in each method that puts reels into the Reels item collection: the
     * page inserts and appends, and the static placer every one-item insert goes through, which hands
     * its item over as a one-item list. The same items without the cards {@link #withoutThreadsCards}
     * takes off, or the very same collection when nothing comes off. Never throws.
     *
     * <p>587's client-side loader puts reels and cards in here without the controller's page of
     * sections ever holding them (#85, #441), so the section filter alone let a Threads card through.
     *
     * @param items        the items about to go into the collection.
     * @param midCardClass binary name of the mid-card item class, for example {@code X.8bO}.
     */
    public static Collection<?> withoutThreadsCardItems(Collection<?> items, String midCardClass) {
        return withoutCardItems(items, midCardClass, PATCHED, ITEMS_ROUTE);
    }

    /**
     * {@link #withoutThreadsCardItems} for the collection's listener walk after an insert, counted on
     * {@link #ANNOUNCED_ROUTE}, so a card that came off on its way in isn't announced either.
     */
    public static Collection<?> withoutAnnouncedThreadsCards(Collection<?> items, String midCardClass) {
        return withoutCardItems(items, midCardClass, PATCHED, ANNOUNCED_ROUTE);
    }

    /** The item filter with the type read and the route passed in, so a test can stand in for the stub. */
    static Collection<?> withoutCardItems(Collection<?> items, String midCardClass, Types types, String route) {
        Plan plan = Plan.now();
        FeedFilterCounters.sawList(route, items == null ? 0 : items.size());
        if (items == null || items.isEmpty() || !plan.any()) return items;

        try {
            ArrayList<Object> kept = new ArrayList<>(items.size());
            for (Object item : items) {
                String kind = kindOf(item, midCardClass, types, plan.family);
                FeedFilterCounters.sawKind(route, kind);
                if (!plan.drops(kind)) kept.add(item);
            }
            if (kept.size() == items.size()) return items;

            plan.count(route, items.size() + " item(s)");
            return kept;
        } catch (Throwable failure) {
            // Anything thrown here would go on into Facebook's Reels list.
            HookStatus.threw(plan.family, "mid-card item filter", failure);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "could not filter Reels items", failure);
            return items;
        }
    }

    /** Which cards come off in one call, by the patches in the build and their switches, and how many did. */
    private static final class Plan {
        final boolean threads;
        final boolean surveys;
        final boolean groups;
        final boolean posts;
        /** The hook's own patch, which carries the type read's status whichever switch asked for the walk. */
        final String family;
        /** Threads cards, surveys, groups cards, post carousels and promo cards taken off so far. */
        final int[] dropped = new int[5];

        private Plan(boolean threadsPatch, boolean promptsPatch, boolean groupsPatch) {
            threads = threadsPatch && switchedOn(() -> Settings.HIDE_REEL_THREADS_CARDS, FamilyNames.REEL_DECLUTTER);
            surveys = promptsPatch && switchedOn(() -> Settings.HIDE_REEL_PROMPTS, FamilyNames.REEL_PROMPTS);
            groups = groupsPatch && switchedOn(() -> Settings.HIDE_SUGGESTED_GROUPS, FamilyNames.SUGGESTED_POSTS);
            posts = threadsPatch && switchedOn(() -> Settings.HIDE_REEL_POST_CARDS, FamilyNames.REEL_DECLUTTER);
            family = threads || posts || (!surveys && threadsPatch) ? FamilyNames.REEL_DECLUTTER : FamilyNames.REEL_PROMPTS;
        }

        /** The plan for a call made now, with the hook marked as reached for each patch in the build. */
        static Plan now() {
            Boolean threadsForced = threadsPatchForTests;
            Boolean promptsForced = promptsPatchForTests;
            Boolean groupsForced = groupsPatchForTests;
            boolean threadsPatch = threadsForced != null ? threadsForced : SettingsStatus.reelDeclutter();
            boolean promptsPatch = promptsForced != null ? promptsForced : SettingsStatus.reelPrompts();
            boolean groupsPatch = groupsForced != null ? groupsForced : SettingsStatus.suggestedPosts();
            if (threadsPatch) HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            if (promptsPatch) HookStatus.invoked(FamilyNames.REEL_PROMPTS);
            return new Plan(threadsPatch, promptsPatch, groupsPatch);
        }

        boolean any() {
            return threads || surveys || groups || posts;
        }

        /** Whether an item of [kind] comes off, counted when it does. */
        boolean drops(String kind) {
            int card = threads && THREADS_CARD.equals(kind) ? 0
                    : surveys && SURVEY_CARD.equals(kind) ? 1
                    : groups && GROUPS_CARD.equals(kind) ? 2
                    : posts && POSTS_CARD.equals(kind) ? 3
                    : threads && PROMOTION_CARD.equals(kind) ? 4
                    : -1;
            if (card < 0) return false;
            dropped[card]++;
            return true;
        }

        /** Counts what came off on [route] and logs it against [from], what the call was handed. */
        void count(String route, String from) {
            FeedFilterCounters.removed(route, dropped[0], THREADS_CARD);
            FeedFilterCounters.removed(route, dropped[1], SURVEY_CARD);
            FeedFilterCounters.removed(route, dropped[2], GROUPS_CARD);
            FeedFilterCounters.removed(route, dropped[3], POSTS_CARD);
            FeedFilterCounters.removed(route, dropped[4], PROMOTION_CARD);
            if (dropped[1] > 0) HookStatus.counted(FamilyNames.REEL_PROMPTS, SURVEY_CARD);
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> route + ": dropped " + dropped[0] + " Threads card(s), " + dropped[1] + " survey(s), "
                            + dropped[2] + " groups card(s), " + dropped[3] + " post card(s) and " + dropped[4]
                            + " promo card(s) from " + from);
        }
    }

    /**
     * A card's switch. Off, unreadable, or asked before the settings are ready, its card stays. The
     * switch is only looked up once they are: Settings mustn't load before the extension has its context.
     */
    private static boolean switchedOn(Supplier<BooleanSetting> setting, String family) {
        try {
            return Utils.settingsReady() && setting.get().get();
        } catch (Throwable t) {
            HookStatus.threw(family, "mid-card switch read", t);
            return false;
        }
    }

    /**
     * What [item] counts as: a mid-card by its type, a mid-card whose type can't be read, or
     * anything else, with the read's status under [family]. Never throws.
     */
    static String kindOf(@Nullable Object item, String midCardClass, Types types, String family) {
        if (item == null) return NOT_A_MID_CARD;
        if (isOfClass(item, promotionClass())) return PROMOTION_CARD;
        if (!isOfClass(item, midCardClass)) return NOT_A_MID_CARD + " " + shortClassName(item);

        Object type;
        try {
            type = types.type(item);
        } catch (Throwable failure) {
            HookStatus.threw(family, "mid-card type", failure);
            return READ_FAILED;
        }
        if (type == UNPATCHED) {
            HookStatus.missingMember(family, "method", "mid-card item", "type");
            return NOT_PATCHED;
        }
        if (!(type instanceof Enum)) return NO_TYPE;

        HookStatus.bound(family, "mid-card type");
        return MID_CARD + ((Enum<?>) type).name();
    }

    /** [item]'s class name after its package, for example {@code 8bO} for {@code X.8bO}. */
    static String shortClassName(Object item) {
        String name = item.getClass().getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** Whether [item] is the class named [className] or extends it. */
    private static boolean isOfClass(Object item, @Nullable String className) {
        if (className == null) return false;

        for (Class<?> type = item.getClass(); type != null; type = type.getSuperclass()) {
            if (className.equals(type.getName())) return true;
        }

        return false;
    }

    /**
     * Injection point, filled in by the patch: the mid-card item's type, a constant of Facebook's
     * mid-card type enum, or null when the item holds none. The patch replaces this body with the
     * item's three reads. Only an object of the class the patch names may be passed.
     */
    public static Object midCardType(Object item) {
        return UNPATCHED;
    }

    /** The promo item class: a test's stand-in, or what the patch filled in. */
    @Nullable
    private static String promotionClass() {
        String forTests = promotionClassForTests;
        return forTests != null ? forTests : promotionItemClass();
    }

    /**
     * Injection point, filled in by the patch: the binary name of the Reels item class Facebook
     * makes for a quick promotion between reels (588 {@code X.06bi}), or null until it is.
     */
    @Nullable
    public static String promotionItemClass() {
        return null;
    }
}
