/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

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
 * Takes the "Threads you might like" card out from between reels, for Clean up Reels (#85), and
 * the "To what extent does this video match your interests?" survey card, for Hide reel interest
 * prompts (#405).
 *
 * <p>The cards Facebook puts between reels, people you may know, groups to join, games, a survey
 * and a Threads post with an Open Threads button among them, are Reels items of one class, the
 * mid-card item. Each keeps a unit whose model answers the card's type as a constant of a GraphQL
 * enum: the Threads card's is {@link #THREADS_MID_CARD} and the survey's {@link #USER_SURVEY}. The
 * item class and the three reads are Redex names, so the patch passes the item's class name in
 * with each page and fills in {@link #midCardType}.
 *
 * <p>The hook is one patch both of those depend on, so each card comes off only while the patch
 * it belongs to is in the build and its switch is on. A card comes off each section's item list
 * through the walk every page filter shares ({@link ReelSections}), the lists the screen reads, so
 * it never reaches the viewer. While either switch is on, every item the walk reads is counted on
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

    /** The source every event of this filter carries in the diagnostic report. */
    private static final String SOURCE = "ReelMidCards";

    /** The diagnostic counter route: each page of sections handed in, and every item read. */
    static final String SECTIONS_ROUTE = "Reel mid-cards";

    /** What an item counts as. Only the Threads card and the survey come off. */
    static final String MID_CARD = "mid-card ";
    static final String THREADS_CARD = MID_CARD + THREADS_MID_CARD;
    static final String SURVEY_CARD = MID_CARD + USER_SURVEY;
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

    private ReelMidCards() {
    }

    /**
     * Injection point, first thing in the Reels controller's method taking a page of sections. The
     * same page with no Threads card or survey left in any section, each while its patch is in and
     * its switch on, or the very same page when neither is or there was none. Never throws.
     *
     * @param page         the sections about to be added to the Reels list.
     * @param midCardClass binary name of the mid-card item class, for example {@code X.8bO}.
     */
    public static List<?> withoutThreadsCards(List<?> page, String midCardClass) {
        return withoutThreadsCards(page, midCardClass, PATCHED);
    }

    /** {@link #withoutThreadsCards(List, String)} with the type read passed in, so a test can stand in for the stub. */
    static List<?> withoutThreadsCards(List<?> page, String midCardClass, Types types) {
        Boolean threadsForced = threadsPatchForTests;
        Boolean promptsForced = promptsPatchForTests;
        boolean threadsPatch = threadsForced != null ? threadsForced : SettingsStatus.reelDeclutter();
        boolean promptsPatch = promptsForced != null ? promptsForced : SettingsStatus.reelPrompts();
        if (threadsPatch) HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
        if (promptsPatch) HookStatus.invoked(FamilyNames.REEL_PROMPTS);
        FeedFilterCounters.sawList(SECTIONS_ROUTE, page == null ? 0 : page.size());
        if (page == null || page.isEmpty()) return page;

        boolean threads = threadsPatch && switchedOn(() -> Settings.HIDE_REEL_THREADS_CARDS, FamilyNames.REEL_DECLUTTER);
        boolean surveys = promptsPatch && switchedOn(() -> Settings.HIDE_REEL_PROMPTS, FamilyNames.REEL_PROMPTS);
        if (!threads && !surveys) return page;
        String family = threads ? FamilyNames.REEL_DECLUTTER : FamilyNames.REEL_PROMPTS;

        try {
            int[] dropped = new int[2];
            ReelSections.Result result = ReelSections.strip(page, item -> {
                String kind = kindOf(item, midCardClass, types, family);
                FeedFilterCounters.sawKind(SECTIONS_ROUTE, kind);
                if (threads && THREADS_CARD.equals(kind)) {
                    dropped[0]++;
                    return true;
                }
                if (surveys && SURVEY_CARD.equals(kind)) {
                    dropped[1]++;
                    return true;
                }
                return false;
            }, family, SOURCE);
            if (result.dropped == 0) return page;

            FeedFilterCounters.removed(SECTIONS_ROUTE, dropped[0], THREADS_CARD);
            FeedFilterCounters.removed(SECTIONS_ROUTE, dropped[1], SURVEY_CARD);
            if (dropped[1] > 0) HookStatus.counted(FamilyNames.REEL_PROMPTS, SURVEY_CARD);
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "dropped " + dropped[0] + " Threads card(s) and " + dropped[1] + " survey(s) from "
                            + page.size() + " section(s)");

            return result.page;
        } catch (Throwable failure) {
            // Anything thrown here would go on into Facebook's Reels page insert.
            HookStatus.threw(family, "mid-card filter", failure);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "could not filter a page of sections", failure);
            return page;
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
}
