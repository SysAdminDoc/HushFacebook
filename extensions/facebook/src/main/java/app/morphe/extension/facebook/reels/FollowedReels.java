/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import app.morphe.extension.facebook.feed.GenAiReelFilter;
import app.morphe.extension.facebook.feed.PostLanguages;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Only reels from accounts you follow on the Reels tab, for Clean up Reels (#349).
 *
 * <p>Facebook answers whether you follow a reel's author with a GraphQL enum whose names it keeps:
 * {@link #FOLLOWED_STATUS}, {@link #CAN_FOLLOW_STATUS} for an author you could follow and don't, and
 * {@link #CANNOT_FOLLOW_STATUS}. The reel's Follow button reads it through one call on the reel's
 * model, whose class and name change every build, so the patch writes that call into
 * {@link #followStatus} and passes the model's class name in with each page.
 *
 * <p>Each page of reels goes through here at the same levels as the sponsored and AI filters, and
 * the reel model is found in each item the way the AI filter finds it
 * ({@link GenAiReelFilter#reelModel}). Only {@link #CAN_FOLLOW_STATUS} comes off. A followed author,
 * one that can't be followed, an item with no reel model (the cards between reels) and anything
 * that can't be read all stay, each counted under its own kind so a report says why.
 *
 * <p>The same filters run Hide posts in other languages on the tab ({@link ReelLanguages}, #382), so
 * the page routes count what either rule took off, each under its own reason.
 *
 * <p>Off, paused, before the settings are ready, or when anything fails, the page goes on as
 * Facebook sent it.
 */
public final class FollowedReels {
    /** The follow status names Facebook keeps. "Subscribe" is its old word for follow. */
    static final String FOLLOWED_STATUS = "IS_SUBSCRIBED";
    static final String CAN_FOLLOW_STATUS = "CAN_SUBSCRIBE";
    static final String CANNOT_FOLLOW_STATUS = "CANNOT_SUBSCRIBE";

    /** The source every event of this filter carries in the diagnostic report. */
    private static final String SOURCE = "FollowedReels";

    /**
     * The diagnostic counter routes: the pages and sections handed in, each counted with what came
     * off it, the listener walk's copy of each insert, and every item read, with what the read found
     * as the kind.
     */
    static final String PAGES_ROUTE = "Followed reels pages";
    static final String SECTIONS_ROUTE = "Followed reels sections";
    static final String ANNOUNCED_ROUTE = "Followed reels announcements";
    static final String ITEMS_ROUTE = "Followed reels status";

    /** What reading one item found. Only {@link #NOT_FOLLOWED} comes off. */
    static final String FOLLOWED = "followed";
    static final String NOT_FOLLOWED = "not followed";
    static final String CANT_FOLLOW = "can't be followed";
    static final String OTHER_STATUS = "follow status ";
    static final String NO_STATUS = "no follow status";
    static final String NO_MODEL = "no reel model";
    static final String NOT_PATCHED = "status reader not patched";
    static final String READ_FAILED = "read failed";

    /** What {@link #followStatus} answers until the patch fills it in. */
    static final Object UNPATCHED = new Object();

    /** The follow status of a reel model, read through the stub the patch filled in or a test's stand-in. */
    interface Statuses {
        /** The status, an enum constant or null, or {@link #UNPATCHED}. */
        @Nullable
        Object status(Object model);
    }

    static final Statuses PATCHED = model -> followStatus(model, null, false);

    private FollowedReels() {
    }

    /**
     * Injection point, filled in by the patch: the follow status of a reel's author, as the reel's
     * Follow button reads it. The patch replaces this body with Facebook's call on the instance its
     * class keeps, which goes in [unused], and hands [fresh] on as it came; the Follow button passes
     * false. Only a reel model of the class the patch names may be passed.
     */
    public static Object followStatus(Object model, Object unused, boolean fresh) {
        return UNPATCHED;
    }

    /**
     * Injection point, before a page of reels enters the item collection: the same items without
     * the reels whose author you could follow and don't, or the very same collection when there were
     * none. The patch also hands it a one-item list from the collection's one-item insert, and skips
     * the insert when the answer comes back empty. Never throws.
     *
     * @param page           the page about to be added to the Reels collection.
     * @param modelClassName binary name of the reel model's class, as the patch resolved it.
     */
    public static Collection<?> followedOnly(Collection<?> page, String modelClassName) {
        return followedOnly(page, modelClassName, PATCHED, PAGES_ROUTE);
    }

    /**
     * Injection point, before the collection's listener walk after an insert: {@link #followedOnly},
     * counted on {@link #ANNOUNCED_ROUTE} so its items aren't counted twice. Never throws.
     */
    public static Collection<?> followedOnlyAnnounced(Collection<?> page, String modelClassName) {
        return followedOnly(page, modelClassName, PATCHED, ANNOUNCED_ROUTE);
    }

    static Collection<?> followedOnly(Collection<?> page, String modelClassName, Statuses statuses, String route) {
        return followedOnly(page, modelClassName, statuses, ReelLanguages.PATCHED, route);
    }

    static Collection<?> followedOnly(Collection<?> page, String modelClassName, Statuses statuses,
            ReelLanguages.Captions captions, String route) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(route, page == null ? 0 : page.size());
            if (page == null || page.isEmpty()) return page;
            boolean countItems = PAGES_ROUTE.equals(route);
            Pass pass = Pass.start(modelClassName, statuses, captions, countItems);
            if (pass == null) return page;

            ArrayList<Object> kept = new ArrayList<>(page.size());
            for (Object item : page) {
                if (!pass.drops(item)) kept.add(item);
            }
            if (pass.dropped() == 0) return page;

            pass.countOn(route);
            final int droppedItems = pass.dropped();
            final String where = countItems ? "dropped " : "announcement dropped ";
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> where + droppedItems + " of " + page.size());
            return kept;
        } catch (Throwable failure) {
            // Anything thrown here would go on into Facebook's Reels page insert.
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "followed reels page filter", failure);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "could not filter a page", failure);
            return page;
        }
    }

    /**
     * Injection point, before a page of sections reaches the Reels controller: the same page with
     * the reels of authors you don't follow taken out of each section's list, or the very same page
     * when there were none. Never throws.
     *
     * @param page           the sections about to be added to the Reels list.
     * @param modelClassName binary name of the reel model's class, as the patch resolved it.
     */
    public static List<?> followedSections(List<?> page, String modelClassName) {
        return followedSections(page, modelClassName, PATCHED);
    }

    static List<?> followedSections(List<?> page, String modelClassName, Statuses statuses) {
        return followedSections(page, modelClassName, statuses, ReelLanguages.PATCHED);
    }

    static List<?> followedSections(List<?> page, String modelClassName, Statuses statuses,
            ReelLanguages.Captions captions) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(SECTIONS_ROUTE, page == null ? 0 : page.size());
            if (page == null || page.isEmpty()) return page;
            Pass pass = Pass.start(modelClassName, statuses, captions, true);
            if (pass == null) return page;

            ReelSections.Result result = ReelSections.strip(page, pass::drops, FamilyNames.REEL_DECLUTTER, SOURCE);
            if (result.dropped == 0) return page;

            pass.countOn(SECTIONS_ROUTE);
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "section filter dropped " + result.dropped + " item(s) from " + page.size() + " section(s)");
            return result.page;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "followed reels section filter", failure);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "could not filter a page of sections", failure);
            return page;
        }
    }

    /** The switch. Off, unreadable, or asked before the settings are ready, the page passes as it came. */
    private static boolean switchedOn() {
        try {
            return Utils.settingsReady() && Settings.FOLLOWED_REELS_ONLY.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "followed reels switch read", failure);
            return false;
        }
    }

    /**
     * One page's run of the rules switched on: this followed-only rule and Hide posts in other
     * languages ({@link ReelLanguages}), with how many items each took off.
     */
    private static final class Pass {
        private final boolean followedOnly;
        private final boolean languages;
        private final String modelClassName;
        private final Statuses statuses;
        private final ReelLanguages.Captions captions;
        /** Whether each item read is counted on the rules' item routes; the listener walk's copy isn't. */
        private final boolean countItems;
        private int notFollowed;
        private int otherLanguage;

        private Pass(boolean followedOnly, boolean languages, String modelClassName, Statuses statuses,
                ReelLanguages.Captions captions, boolean countItems) {
            this.followedOnly = followedOnly;
            this.languages = languages;
            this.modelClassName = modelClassName;
            this.statuses = statuses;
            this.captions = captions;
            this.countItems = countItems;
        }

        /** The run for a page, or null when neither rule is on and the page passes as it came. */
        @Nullable
        static Pass start(String modelClassName, Statuses statuses, ReelLanguages.Captions captions,
                boolean countItems) {
            boolean followedOnly = switchedOn();
            boolean languages = ReelLanguages.switchedOn();
            if (!followedOnly && !languages) return null;
            return new Pass(followedOnly, languages, modelClassName, statuses, captions, countItems);
        }

        /**
         * Whether [item] comes off. The follow rule reads first, so a reel both rules would take is
         * counted once, as not followed, and its caption is never guessed at.
         */
        boolean drops(Object item) {
            if (followedOnly && (countItems ? FollowedReels.drops(item, modelClassName, statuses)
                    : NOT_FOLLOWED.equals(kindOf(item, modelClassName, statuses)))) {
                notFollowed++;
                return true;
            }
            if (languages && (countItems ? ReelLanguages.drops(item, modelClassName, captions)
                    : ReelLanguages.hides(ReelLanguages.kindOf(item, modelClassName, captions)))) {
                otherLanguage++;
                return true;
            }
            return false;
        }

        int dropped() {
            return notFollowed + otherLanguage;
        }

        /** What came off the page, on [route] under each rule's reason. */
        void countOn(String route) {
            if (notFollowed > 0) FeedFilterCounters.removed(route, notFollowed, NOT_FOLLOWED);
            if (otherLanguage > 0) FeedFilterCounters.removed(route, otherLanguage, PostLanguages.REASON);
        }
    }

    /**
     * Whether the item comes off its page. Every item read is counted on the items route under what
     * the read found, and a dropped one as a removal, so a kept reel always has a reason in the report.
     */
    private static boolean drops(Object item, String modelClassName, Statuses statuses) {
        String kind = kindOf(item, modelClassName, statuses);
        FeedFilterCounters.sawList(ITEMS_ROUTE, 1);
        FeedFilterCounters.sawKind(ITEMS_ROUTE, kind);
        if (!NOT_FOLLOWED.equals(kind)) return false;
        FeedFilterCounters.removed(ITEMS_ROUTE, 1, kind);
        return true;
    }

    /** What one item counts as, by its reel model's follow status. Never throws. */
    static String kindOf(@Nullable Object item, String modelClassName, Statuses statuses) {
        Object model;
        try {
            model = GenAiReelFilter.reelModel(item, modelClassName);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "reel model reader", failure);
            return READ_FAILED;
        }
        if (model == null) return NO_MODEL;

        Object status;
        try {
            status = statuses.status(model);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "follow status", failure);
            return READ_FAILED;
        }
        if (status == UNPATCHED) {
            HookStatus.missingMember(FamilyNames.REEL_DECLUTTER, "method", "reel model", "follow status");
            return NOT_PATCHED;
        }
        if (!(status instanceof Enum)) return NO_STATUS;

        HookStatus.bound(FamilyNames.REEL_DECLUTTER, "follow status");
        String name = ((Enum<?>) status).name();
        if (CAN_FOLLOW_STATUS.equals(name)) return NOT_FOLLOWED;
        if (FOLLOWED_STATUS.equals(name)) return FOLLOWED;
        if (CANNOT_FOLLOW_STATUS.equals(name)) return CANT_FOLLOW;
        return OTHER_STATUS + name;
    }
}
