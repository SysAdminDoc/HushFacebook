/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
 * a turning one stays on the plain subtitle. Most stories show their credits as pills in a row
 * under the name instead, and the same switch leaves out the song and effect pills there while a
 * reshare, an archive or a close friends pill stays. A story whose first credit is a song gets a
 * pill of its own with a "Try it" button, and the same switch draws nothing in its place.
 *
 * <p>The reply suggestions switch takes the row of suggested replies off the bottom of a story,
 * leaving the reply box, and the Subscribe switch the Subscribe pill under the name. The Follow
 * switch takes the Follow button off a story from someone you don't follow (#543), the reply bar
 * switch the whole bar under someone else's story: the repost button, the Send message box and the
 * quick reactions (#542). The Up next switch opens the next people's stories without the "Up next"
 * card that holds the first one still while it counts down (#544). The create
 * cards switch keeps out the "Add to story" card Facebook puts between other people's stories, the
 * plain one and the ones built from the gallery (#439), and the full screen "continue the story"
 * card built from recent gallery photos, which the viewer's finished list loses on its way out (#521).
 *
 * <p>The flat slide switch keeps the cube Facebook turns between people's stories square to the
 * screen, so the pages slide across instead (#521). The time switch puts how long ago a story was
 * posted next to the name on the stories Facebook leaves it off, like a Page's (#563).
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and Facebook draws its own.
 */
public final class StoryViewerCleanup {
    /** Counted each time a story's credit line is drawn without its credits. */
    static final String CREDITS_HIDDEN = "story credit line hidden";

    /** Counted each time a song or effect pill is left out of the column under the name. */
    static final String PILLS_DROPPED = "story credit pills dropped";

    /** Counted each time a story's song pill, the one with the "Try it" button, isn't drawn. */
    static final String SONG_PILL_HIDDEN = "story song pill hidden";

    /** Counted each time a story's suggested replies aren't drawn. */
    static final String SUGGESTIONS_HIDDEN = "story reply suggestions hidden";

    /** Counted each time a story's Subscribe pill isn't drawn. */
    static final String SUBSCRIBE_HIDDEN = "story Subscribe pill hidden";

    /** Counted each time a story's Follow button isn't drawn (#543). */
    static final String FOLLOW_HIDDEN = "story Follow button hidden";

    /** Counted each time a story's page opens without the "Up next" card counting down over it (#544). */
    static final String UP_NEXT_SKIPPED = "story Up next card skipped";

    /** Counted each time the bar under a story isn't drawn (#542). */
    static final String REPLY_BAR_HIDDEN = "story reply bar hidden";

    /** Counted each time the viewer's list of stories is built without the "Add to story" card. */
    static final String CREATE_CARDS_HIDDEN = "story create cards left out";

    /** Counted each time a story keeps the time Facebook would have left out next to its name (#563). */
    static final String TIME_SHOWN = "story time shown";

    /** Counted each time a story's header shows the line with the time where it would have shown the name alone (#563). */
    static final String TIME_LINE_SHOWN = "story time line shown";

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

    /** Injection point, first thing in the song pill's render: whether to draw nothing. Never throws. */
    public static boolean hideSongPill() {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story song pill");
            if (!Utils.settingsReady() || !Settings.HIDE_STORY_CREDITS.get()) return false;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, SONG_PILL_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story song pill", failure);
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

    /** Injection point, first thing in the story Follow button's layout: whether to draw nothing (#543). Never throws. */
    public static boolean hideFollow() {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story Follow button");
            if (!Utils.settingsReady() || !Settings.HIDE_STORY_FOLLOW.get()) return false;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, FOLLOW_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story Follow button", failure);
            return false;
        }
    }

    /**
     * Injection point, where a story's page sets up its first state: whether it opens on the "Up next"
     * card, which holds the story still while it counts down (#544). Facebook's own answer comes in,
     * and with the switch on a yes turns into a no, as if the card had been dismissed already, so the
     * story plays straight away. Never throws.
     */
    public static boolean showUpNext(boolean show) {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story Up next card");
            if (!show || !Utils.settingsReady() || !Settings.HIDE_STORY_UP_NEXT.get()) return show;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, UP_NEXT_SKIPPED);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story Up next card", failure);
            return show;
        }
    }

    /**
     * Injection point, at each answer of the check the line next to a story's name asks before it
     * puts in how long ago the story was posted: whether to leave the time out (#563). Facebook says
     * yes for a Page's story and a few other kinds, and with the switch on a yes turns into a no, so
     * the line gets the time the way a friend's story does. Never throws.
     */
    public static boolean hideTime(boolean hide) {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story time");
            if (!hide || !Utils.settingsReady() || !Settings.SHOW_STORY_TIME.get()) return hide;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, TIME_SHOWN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story time", failure);
            return hide;
        }
    }

    /**
     * Injection point, where the title row of a story's header gives up on the line next to the name
     * before reading it, as it does for a Page's story (#563). With the switch on, {@link #hideTime}
     * already had the time put in that line, and a true answer makes the row read it and show it the
     * way a friend's story does. An empty line keeps Facebook's way. Never throws.
     *
     * @param line the line the header built, the time with the switch on.
     */
    public static boolean showsTimeLine(@Nullable CharSequence line) {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story time line");
            if (line == null || line.length() == 0 || !Utils.settingsReady() || !Settings.SHOW_STORY_TIME.get()) return false;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, TIME_LINE_SHOWN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story time line", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in both of the bars under someone else's story, the one with the
     * repost button, the Send message box and the quick reactions, and its newer feedback bar: whether
     * to draw nothing (#542). Never throws.
     */
    public static boolean hideReplyBar() {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story reply bar");
            if (!Utils.settingsReady() || !Settings.HIDE_STORY_REPLY_BAR.get()) return false;
            HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, REPLY_BAR_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story reply bar", failure);
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

    /** Counted for each card taken out of the viewer's finished list of stories (#521). */
    static final String CREATE_CARDS_DROPPED = "story create cards dropped";

    /** Which of the viewer's buckets is a card asking you to make a story. */
    interface CreateCards {
        boolean isCard(Object bucket);
    }

    /** Facebook's own card, asked about through the question the patch fills in. */
    private static final CreateCards PATCHED = new CreateCards() {
        @Override
        public boolean isCard(Object bucket) {
            return isCreateCard(bucket);
        }
    };

    /**
     * Injection point, where the viewer's list runner is done with its story list sources: the list
     * to show without the cards asking you to make a story, the full screen "continue the story" one
     * included (#521). The list comes back as it came when there's nothing to take out. Never throws.
     */
    public static List<?> withoutCreateCards(List<?> buckets) {
        return withoutCreateCards(buckets, PATCHED);
    }

    static List<?> withoutCreateCards(List<?> buckets, CreateCards cards) {
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, "story create card list");
            if (buckets == null || buckets.isEmpty() || !Utils.settingsReady()
                    || !Settings.HIDE_STORY_CREATE_CARDS.get()) {
                return buckets;
            }
            List<Object> kept = null;
            int dropped = 0;
            for (int i = 0; i < buckets.size(); i++) {
                Object bucket = buckets.get(i);
                if (cards.isCard(bucket)) {
                    if (kept == null) kept = new ArrayList<>(buckets.subList(0, i));
                    dropped++;
                } else if (kept != null) {
                    kept.add(bucket);
                }
            }
            if (kept == null) return buckets;
            for (int i = 0; i < dropped; i++) {
                HookStatus.counted(FamilyNames.STORY_VIEWER_CLEANUP, CREATE_CARDS_DROPPED);
            }
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, "story create card list", failure);
            return buckets;
        }
    }

    /**
     * Whether a bucket of the viewer's list is Facebook's card asking you to make a story. The patch
     * fills this in with an instance-of on that card's class, the one whose id falls back to
     * "story-end-card-bucket"; unpatched, nothing is a card.
     */
    public static boolean isCreateCard(Object bucket) {
        return false;
    }

    /** What the hook binds as, and the name a failure in it is reported under (#521). */
    static final String CUBE_TURN = "story cube turn";

    /**
     * Injection point, for each turn the stories pager's cube positioner gives a page as you swipe
     * between people (#521): the patch hands it every setRotationY that positioner makes. With the
     * flat slide switch on the page stays square to the screen. Read on each frame of a swipe, so
     * nothing is counted here.
     */
    public static void cubeTurn(View page, float degrees) {
        float turn = degrees;
        try {
            HookStatus.invoked(FamilyNames.STORY_VIEWER_CLEANUP);
            HookStatus.bound(FamilyNames.STORY_VIEWER_CLEANUP, CUBE_TURN);
            if (Utils.settingsReady() && Settings.STORY_FLAT_SLIDE.get()) turn = 0f;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_VIEWER_CLEANUP, CUBE_TURN, failure);
        }
        page.setRotationY(turn);
    }
}
