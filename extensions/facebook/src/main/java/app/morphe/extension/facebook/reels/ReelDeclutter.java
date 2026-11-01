/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.feed.FeedFilter;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * What the Clean up Reels patch asks before a reel's footer is built.
 *
 * <p>Three switches, five hooks, all in the Reels viewer of Facebook 577 and 580:
 *
 * <ul>
 *   <li>The chips under a reel come from one method that walks the reel's attribution models and
 *       builds the list of those it will draw. The patch hands that list here before each return,
 *       and {@link #filterChips} answers the chips to keep, or null to keep the list as it came.
 *       Each chip is a generated model that answers its GraphQL type with {@code getTypeName()},
 *       and only the types in {@link #HIDDEN_CHIPS} go. A type this doesn't know stays. The line
 *       naming the reel's sound, "Original audio" or a song, is one of those chips, and it goes
 *       under its own switch ({@link #SOUNDTRACK_CHIP}, #200). The footer draws nothing for a
 *       list left empty. The Reels tab draws that line in a component of its own, which picks the
 *       soundtrack out of the models itself, and {@link #hideAudioLine} is asked first thing there.
 *       "Made with Edits" under the caption is a component of its own too, drawn from a flag on the
 *       video, and {@link #hideEditsLabel} is asked first thing there under the chips switch (#314).
 *   <li>The Follow button beside a reel's author is built only when Facebook's Follow check
 *       answers yes, and {@link #hideFollowButton} is asked first thing in that check. The author
 *       row asks it on every surface, the Reels tab included. So do the author rows of sponsored
 *       reels and the feed, about a reel attached to a post.
 *   <li>An author you already follow gets a Following button there instead, which Facebook's own
 *       config getter for removing it takes away when it answers true. {@link #hideFollowingButton}
 *       is asked first thing in that getter. The row reads the getter only for such an author,
 *       which is why it alone left the Follow button on everyone else's reels.
 *   <li>A full-screen video's author row builds a pill beside the name that says Subscribe for some
 *       owners and Follow for the rest. The row hands {@link #showAuthorPill} both its answers
 *       where it decides to build the pill. A builder Facebook shares for the same pill elsewhere
 *       notes which one it builds ({@link #noteAuthorPill}), and its callers hand
 *       {@link #authorPillSlot} the slot they wrap it in. Only a Subscribe pill goes (#291).
 *   <li>The comment Facebook picks to show under a reel and the bubbles of friends who reacted each
 *       come from a query a runnable starts. {@link #skipHotComment} and {@link #skipSocialBubbles}
 *       are asked first thing in each, and true makes the runnable return before it queries.
 *   <li>Each button down a reel's right side is one vertical button component whose constructor
 *       takes the text under the icon. {@link #countText} is handed that text first thing in the
 *       constructor and answers what the button keeps (#180).
 *   <li>The sidebar builds those buttons by walking a list of types, one icon constant each.
 *       {@link #hideSidebarButton} is handed each type as it's read, and true skips Save or the
 *       direct WhatsApp button (#190, #243).
 *   <li>The speaker button under them comes from one of two places, picked by a server flag: the
 *       video controls build it only when one check of Facebook's answers yes, and
 *       {@link #showsSoundButton} is handed that answer before they branch on it. Otherwise the
 *       footer builds it, and {@link #soundButton} is handed what was built before the footer's
 *       null check. Either way the button stays out while the switch is on (#190). Move the speaker
 *       above the three dots leaves those two with nothing to build and has the side column build
 *       the footer's button from the reel's corner: {@link #cornerForSide} (#331).
 *   <li>A video that keeps buffering has Facebook post a runnable that shows "This is taking a
 *       while. Try checking your network connection." {@link #hideSlowConnectionMessage} is asked
 *       first thing in it, and true makes it return before the message is built (#222).
 *   <li>Each tile of the feed's Reels row plays a short clip of its reel that Facebook picks, and
 *       a clip it can't use comes out as the first second, played over and over (#228).
 *       {@link #allowsPreviewClip} is handed the last check before the tile asks for its clip, and
 *       false takes Facebook's own path for a tile with no clip. {@link #previewClipEnd} is handed
 *       the position the tile's player stops and loops at, and answers -1, the player's own
 *       default, so the reel plays to its end.
 *   <li>A reel whose comments are turned off gets no Comment button down its right side, though
 *       its feed post still shows one that opens them to read. {@link #showsCommentButton} is
 *       handed Facebook's can-comment answer in each sidebar builder, and true draws the button
 *       (#299).
 *   <li>The date a reel or full-screen video was posted is built under its author only when a
 *       server switch says so, and it's off for most accounts. {@link #showsPostDate} is handed
 *       that answer, and true draws the date (#296).
 * </ul>
 *
 * <p>Every hook fails open: until the settings are ready, while Hushfacebook is paused, with a
 * switch off, or when something throws, Facebook's own path runs.
 */
@SuppressWarnings("unused")
public final class ReelDeclutter {
    /**
     * The GraphQL types of the chips the chips switch hides: the prompts to make something of your
     * own (Remix, Use template, Add yours, Edits) and the promotions (Stars, instant games, a
     * partner app, a link out of Facebook). Facebook 577 and 580 both name all eight in the method
     * the patch hooks, and the patch holds each build to them.
     */
    static final String[] HIDDEN_CHIPS = {
            "XFBFBShortsRemixAttribution",
            "XFBFBShortsTemplateAttribution",
            "XFBFBShortsAddYoursStickerAttribution",
            "XFBFBShortsEditsAppAttribution",
            "XFBFBShortsSendStarsAttribution",
            "XFBFBShortsInstantGamesAttribution",
            "XFBFBShortsPartnerAppAttribution",
            "XFBFBShortsExternalLinkAttribution",
    };

    /**
     * The GraphQL type of the line naming a reel's sound, "Original audio" or a song, which the audio
     * line switch hides on its own (#200). The chips switch leaves it, and the patch holds the builder
     * to it as it does to the hidden chips.
     */
    static final String SOUNDTRACK_CHIP = "XFBFBShortsSoundtrackTitle";

    /** The diagnostic counter routes. */
    static final String CHIPS_ROUTE = "Reel chips";
    static final String FOLLOW_ROUTE = "Reel Follow button";
    static final String FOOTER_ROUTE = "Reel footer queries";

    /** Counted under its own name each time the search pill isn't built. */
    static final String SEARCH_ROUTE = "Reel search pill";
    static final String SEARCH_PILL = "search pill";
    static final String COMMENT_SEARCH_PILL = "comment search pill";
    /** The search bar over a reel opened from a profile, and its send button, each counted as it's handed in. */
    static final String SEARCH_BAR = "search bar";

    /** Counted under its own name each time the Reels tab's audio line isn't drawn. */
    static final String AUDIO_ROUTE = "Reel audio line";
    static final String AUDIO_LINE = "audio line";

    /** Counted under its own name each time a reel's "Made with Edits" line isn't drawn (#314). */
    static final String EDITS_ROUTE = "Reel Edits line";
    static final String EDITS_LABEL = "Made with Edits";

    /** Counted under its own name each time a video's slow connection message isn't shown. */
    static final String SLOW_ROUTE = "Slow connection message";
    static final String SLOW_MESSAGE = "slow connection message";

    /**
     * Counted under its own name for each tile of the feed's Reels row, as its end is handed in, and
     * removed each time a tile that had an end plays its whole reel. The clip check is tallied as its
     * own kind: Facebook asks it only while one of its server flags is on, and the end on every tile.
     */
    static final String PREVIEW_ROUTE = "Feed reel previews";
    static final String PREVIEW_CLIP = "preview clip";
    static final String PREVIEW_END = "clip end";
    static final String PREVIEW_NO_END = "no end";

    /** Counted under its own name each time a reel with comments turned off gets its Comment button anyway. */
    static final String COMMENT_ROUTE = "Reel Comment button";
    static final String COMMENTS_OFF = "comments off";

    /** Counted under its own name each time a reel's post date is drawn where the server left it out. */
    static final String DATE_ROUTE = "Reel post date";
    static final String DATE_HIDDEN = "date left out";

    /** What the tile's player reads as no stop position: the default its settings builder starts with. */
    static final int NO_END = -1;

    /** Counted under its own name each time a sidebar button's number is taken away. */
    static final String COUNTS_ROUTE = "Reel counts";
    static final String COUNT = "button count";

    /** Counted under its own name each time a sidebar button is skipped. */
    static final String SIDEBAR_ROUTE = "Reel sidebar buttons";
    static final String SAVE_BUTTON = "Save button";
    static final String WHATSAPP_BUTTON = "WhatsApp button";
    static final String LIKE_BUTTON = "Like button";
    static final String COMMENT_BUTTON = "Comment button";
    static final String SHARE_BUTTON = "Share button";
    static final String SOUND_BUTTON = "speaker button";
    /** Counted under the family's name each time the side column builds a reel's speaker (#331). */
    static final String SPEAKER_MOVED = "speaker kept above the three dots";
    /** How many reels the side column's speaker is remembered for, far past what a viewer keeps built. */
    static final int MOVED_SPEAKERS_KEPT = 24;
    /** Reels whose speaker goes to their side column, oldest first, by identity: nothing's asked of Facebook's objects. */
    private static final ArrayList<MovedSpeaker> MOVED_SPEAKERS = new ArrayList<>();
    /** Set while the side column asks Facebook's builder for the speaker, on the thread that asks. */
    private static final ThreadLocal<Boolean> BUILDING_SIDE_SPEAKER = new ThreadLocal<>();

    /** A reel whose speaker goes to its side column, and the corner to build it from once there is one. */
    private static final class MovedSpeaker {
        final WeakReference<Object> reel;
        WeakReference<Object> corner;

        MovedSpeaker(Object reel) {
            this.reel = new WeakReference<>(reel);
        }
    }

    /**
     * The constant of Facebook's icon enum the reel sidebar builds its Save button for (#190). The
     * sidebar walks one constant per button, and the enum's names are kept as strings in the app,
     * so {@code name()} reads this on any build. Like, Comment and Share are LIKE, COMMENT and SHARE (#265).
     */
    static final String SAVE_TYPE = "BOOKMARK";
    static final String WHATSAPP_TYPE = "APP_WHATSAPP";
    static final String LIKE_TYPE = "LIKE";
    static final String COMMENT_TYPE = "COMMENT";
    static final String SHARE_TYPE = "SHARE";

    /**
     * What a hidden count becomes: one zero-width space. The button still has a text row, so it
     * keeps the height it has with a number and the icons above and below it don't move, but the
     * row shows nothing. A null text would drop the row and pull every icon under it up.
     */
    static final String NO_COUNT = String.valueOf((char) 0x200B);

    /** The kind a chip is counted under when its type can't be read. It stays. */
    static final String UNREADABLE = "unreadable type";

    /** The author row's buttons in the report, each counted under the hook that asked. */
    static final String FOLLOW = "Follow button";
    static final String FOLLOWING = "Following button";

    /** Counted under its own name for each pill beside a video's author, Follow or Subscribe (#291). */
    static final String SUBSCRIBE_ROUTE = "Video Subscribe button";
    static final String SUBSCRIBE = "Subscribe button";

    /**
     * Whether the pill the shared builder last built on this thread says Subscribe. The builder
     * notes it just before it builds the pill, and its caller takes the note right after it wraps
     * the pill, all in one call on one thread.
     */
    private static final ThreadLocal<Boolean> BUILDING_SUBSCRIBE = new ThreadLocal<>();

    /** The footer queries' kinds in the report. */
    static final String HOT_COMMENT = "hot comment";
    static final String SOCIAL_BUBBLES = "friends' reactions";

    /** What has had its debug line, so each kind of change is logged once. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private ReelDeclutter() {
    }

    /**
     * Injection point, before each return of the method that builds a reel's chip list. The chips
     * to keep, when the switch took any out, or null to keep the list Facebook built. The patch
     * rebuilds its list from the answer. Never throws.
     *
     * @param chips the list Facebook is about to return, an ImmutableList of attribution models.
     */
    public static Object[] filterChips(Object chips) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            if (!(chips instanceof List)) return null;
            List<?> list = (List<?>) chips;
            FeedFilterCounters.sawList(CHIPS_ROUTE, list.size());
            if (list.isEmpty() || !Utils.settingsReady()) return null;
            boolean hideChips = Settings.HIDE_REEL_CHIPS.get();
            boolean audioLine = Settings.HIDE_REEL_AUDIO_LINE.get();
            if (!hideChips && !audioLine) return null;

            List<Object> kept = new ArrayList<>(list.size());
            for (Object chip : list) {
                // Facebook's list can't hold null, and the list the patch rebuilds can't either.
                if (chip == null) return null;
                String type = FeedFilter.typeName(chip);
                // The report names every chip type a reel carried, which is how a new one is found.
                FeedFilterCounters.sawKind(CHIPS_ROUTE, type == null ? UNREADABLE : type);
                if ((hideChips && isHiddenChip(type)) || (audioLine && SOUNDTRACK_CHIP.equals(type))) {
                    FeedFilterCounters.removed(CHIPS_ROUTE, 1, type);
                    logOnce("chip " + type, () -> "Reel chips: hid " + type);
                    continue;
                }
                kept.add(chip);
            }
            return kept.size() == list.size() ? null : kept.toArray();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "reel chips", failure);
            Logger.printException(() -> "Reel chips: could not filter the list", failure);
            return null;
        }
    }

    /** Whether a chip of this GraphQL type is one the chips switch hides. */
    static boolean isHiddenChip(String type) {
        if (type == null) return false;
        for (String hidden : HIDDEN_CHIPS) {
            if (hidden.equals(type)) return true;
        }
        return false;
    }

    /**
     * Injection point, first thing in Facebook's check for whether a reel's author row offers a
     * Follow button. True makes the check answer no, so the row is built without one, as it is
     * for an author Facebook won't let you follow. Never throws: false runs Facebook's own check.
     */
    public static boolean hideFollowButton() {
        return hideAuthorButton(FOLLOW);
    }

    /**
     * Injection point, first thing in Facebook's config getter for removing the Following button
     * an author you already follow gets in a reel's author row. True makes the getter answer true,
     * so the row is built without it. Never throws: false runs Facebook's own getter.
     */
    public static boolean hideFollowingButton() {
        return hideAuthorButton(FOLLOWING);
    }

    /** Both of the Follow switch's hooks: [button] names the one that asked. */
    private static boolean hideAuthorButton(String button) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(FOLLOW_ROUTE, 1);
            FeedFilterCounters.sawKind(FOLLOW_ROUTE, button);
            boolean hide = Utils.settingsReady() && Settings.HIDE_REEL_FOLLOW_BUTTON.get();
            if (hide) {
                FeedFilterCounters.removed(FOLLOW_ROUTE, 1, button);
                logOnce(button, () -> "Reel Follow button: hid the " + button);
            }
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, button, failure);
            Logger.printException(() -> "Reel Follow button: could not read its switch for the " + button, failure);
            return false;
        }
    }

    /**
     * Injection point, in a full-screen video's author row, where Facebook decides to build the pill
     * beside the author's name (#291). Handed whether the pill would say Subscribe, which the row
     * worked out from the video's owner a moment before, and whether the row builds it at all.
     * False takes the row's own way for a video with no pill. Never throws: anything but a
     * Subscribe pill with the switch on answers what Facebook decided.
     */
    public static boolean showAuthorPill(boolean subscribe, boolean show) {
        return show && !hideSubscribePill(subscribe);
    }

    /**
     * Injection point, in the builder Facebook shares for the same pill in two other spots, just
     * before it builds it (#291). Remembers on this thread whether the pill says Subscribe, for the
     * caller's {@link #authorPillSlot} a moment later. Never throws.
     */
    public static void noteAuthorPill(boolean subscribe) {
        BUILDING_SUBSCRIBE.set(subscribe);
    }

    /**
     * Injection point, in each caller of that builder, right after it wraps the pill in the slot the
     * row lays out (#291). Null is what the slot holds when Facebook shows no pill there, and it's
     * the answer for a Subscribe pill while the switch is on. Takes the builder's note either way.
     * Never throws: anything else answers the slot as it came.
     */
    public static Object authorPillSlot(Object slot) {
        boolean subscribe = Boolean.TRUE.equals(BUILDING_SUBSCRIBE.get());
        BUILDING_SUBSCRIBE.remove();
        return hideSubscribePill(subscribe) ? null : slot;
    }

    /**
     * Injection point, in the reel row that builds a pill of its own, right after it asks whether
     * the author gets Subscribe (#291). -1 takes the row's way for an author with no pill, and 1
     * and 0 are the yes and no it asked for. Never throws: anything but a Subscribe pill with the
     * switch on answers what Facebook decided.
     */
    public static int authorPillAnswer(boolean subscribe) {
        if (hideSubscribePill(subscribe)) return -1;
        return subscribe ? 1 : 0;
    }

    /** Whether the pill goes: a Subscribe pill, counted as either kind, while the switch is on. */
    private static boolean hideSubscribePill(boolean subscribe) {
        String pill = subscribe ? SUBSCRIBE : FOLLOW;
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(SUBSCRIBE_ROUTE, 1);
            FeedFilterCounters.sawKind(SUBSCRIBE_ROUTE, pill);
            boolean hide = subscribe && Utils.settingsReady() && Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON.get();
            if (hide) {
                FeedFilterCounters.removed(SUBSCRIBE_ROUTE, 1, pill);
                logOnce(SUBSCRIBE, () -> "Video Subscribe button: hid it");
            }
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, pill, failure);
            Logger.printException(() -> "Video Subscribe button: could not read its switch", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in the runnable that queries the comment Facebook previews under
     * a reel. True makes it return without querying, so nothing is previewed. Never throws.
     */
    public static boolean skipHotComment() {
        return skipFooterQuery(HOT_COMMENT);
    }

    /**
     * Injection point, first thing in the runnable that queries which friends reacted to a reel.
     * True makes it return without querying, so no bubbles are drawn. Never throws.
     */
    public static boolean skipSocialBubbles() {
        return skipFooterQuery(SOCIAL_BUBBLES);
    }

    private static boolean skipFooterQuery(String kind) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(FOOTER_ROUTE, 1);
            FeedFilterCounters.sawKind(FOOTER_ROUTE, kind);
            boolean skip = Utils.settingsReady() && Settings.HIDE_REEL_SOCIAL_FOOTER.get();
            if (skip) {
                FeedFilterCounters.removed(FOOTER_ROUTE, 1, kind);
                logOnce("footer " + kind, () -> "Reel footer: skipped the " + kind + " query");
            }
            return skip;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, kind + " query", failure);
            Logger.printException(() -> "Reel footer: could not read its switch", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in the component that draws the audio line on the Reels tab
     * (#200). That row picks the soundtrack out of the reel's attribution models on its own, so the
     * chip filter never sees it. True makes it draw nothing, as it does for a reel with no sound
     * line. Never throws: false draws it as before.
     */
    public static boolean hideAudioLine() {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(AUDIO_ROUTE, 1);
            FeedFilterCounters.sawKind(AUDIO_ROUTE, AUDIO_LINE);
            boolean hide = Utils.settingsReady() && Settings.HIDE_REEL_AUDIO_LINE.get();
            if (hide) {
                FeedFilterCounters.removed(AUDIO_ROUTE, 1, AUDIO_LINE);
                logOnce(AUDIO_LINE, () -> "Reel audio line: not drawn");
            }
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, AUDIO_LINE, failure);
            Logger.printException(() -> "Reel audio line: could not read its switch", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in the component that draws "Made with Edits" under the caption of
     * a video made in Meta's Edits app (#314). It reads that from the video on its own, so the chip
     * filter never sees it, and it goes with the chips switch, which already hides the Edits chip.
     * True makes it draw nothing, as it does for a video made anywhere else. Never throws: false
     * draws it as before.
     */
    public static boolean hideEditsLabel() {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(EDITS_ROUTE, 1);
            FeedFilterCounters.sawKind(EDITS_ROUTE, EDITS_LABEL);
            boolean hide = Utils.settingsReady() && Settings.HIDE_REEL_CHIPS.get();
            if (hide) {
                FeedFilterCounters.removed(EDITS_ROUTE, 1, EDITS_LABEL);
                logOnce(EDITS_LABEL, () -> "Reel Edits line: not drawn");
            }
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, EDITS_LABEL, failure);
            Logger.printException(() -> "Reel Edits line: could not read its switch", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in the runnable Facebook posts when a video has been loading for
     * a while, which shows "This is taking a while. Try checking your network connection." (#222).
     * True makes it return before the message is built, as it does on its own when the video started
     * playing in the meantime. The video keeps loading either way. Never throws: false shows it.
     */
    public static boolean hideSlowConnectionMessage() {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(SLOW_ROUTE, 1);
            FeedFilterCounters.sawKind(SLOW_ROUTE, SLOW_MESSAGE);
            boolean hide = Utils.settingsReady() && Settings.HIDE_SLOW_CONNECTION_MESSAGE.get();
            if (hide) {
                FeedFilterCounters.removed(SLOW_ROUTE, 1, SLOW_MESSAGE);
                logOnce(SLOW_MESSAGE, () -> "Slow connection message: not shown");
            }
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, SLOW_MESSAGE, failure);
            Logger.printException(() -> "Slow connection message: could not read its switch", failure);
            return false;
        }
    }

    /** Whether the feed's Reels row tiles play their whole reel: the switch, once the settings can be read. */
    private static boolean playsFeedReelsWhole() {
        return Utils.settingsReady() && Settings.PLAY_FEED_REELS_WHOLE.get();
    }

    /**
     * Injection point, in the render of a tile of the feed's Reels row, handed the last check
     * Facebook makes before it asks for the short clip the tile plays (#228). False takes Facebook's
     * own path for a tile with no clip: no start partway in, no speed change, no seek back to the
     * clip's start each loop. Never throws: anything going wrong answers what it was handed.
     */
    public static boolean allowsPreviewClip(boolean allowed) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawKind(PREVIEW_ROUTE, PREVIEW_CLIP);
            if (!playsFeedReelsWhole()) return allowed;
            logOnce(PREVIEW_CLIP, () -> "Feed reel previews: skipping the clip");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, PREVIEW_CLIP, failure);
            Logger.printException(() -> "Feed reel previews: could not read their switch", failure);
            return allowed;
        }
    }

    /**
     * Injection point, in the same render, handed the position in milliseconds the tile's player
     * stops and starts over at, before it's stored (#228). Answers {@link #NO_END} while the switch
     * is on, so the reel plays to its end. Never throws: anything going wrong answers what it was handed.
     */
    public static int previewClipEnd(int endMs) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(PREVIEW_ROUTE, 1);
            boolean hasEnd = endMs >= 0;
            FeedFilterCounters.sawKind(PREVIEW_ROUTE, hasEnd ? PREVIEW_END : PREVIEW_NO_END);
            if (!playsFeedReelsWhole()) return endMs;
            if (hasEnd) {
                FeedFilterCounters.removed(PREVIEW_ROUTE, 1, PREVIEW_END);
                logOnce(PREVIEW_END, () -> "Feed reel previews: playing the whole reel, not stopping at " + endMs + " ms");
            }
            return NO_END;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, PREVIEW_CLIP, failure);
            return endMs;
        }
    }

    /**
     * Injection point, in each builder of a reel's sidebar, handed Facebook's answer to whether you
     * can comment on the reel, right before the builder leaves the Comment button out for a no
     * (#299). True while the switch is on, so the button is drawn and opens the comments to read.
     * Never throws: anything going wrong answers what it was handed.
     */
    public static boolean showsCommentButton(boolean canComment) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            if (canComment || !Utils.settingsReady() || !Settings.SHOW_REEL_COMMENT_BUTTON.get()) return canComment;
            FeedFilterCounters.removed(COMMENT_ROUTE, 1, COMMENTS_OFF);
            logOnce(COMMENTS_OFF, () -> "Reel Comment button: drawn on a reel with comments turned off");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, COMMENTS_OFF, failure);
            return canComment;
        }
    }

    /**
     * Injection point, in the helper that builds the post date under a reel's or a full-screen
     * video's author, handed the server switch's answer to whether that line is drawn, right before
     * the helper draws nothing for a no (#296). True while the switch is on, so the date shows.
     * Never throws: anything going wrong answers what it was handed.
     */
    public static boolean showsPostDate(boolean shown) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            if (shown || !Utils.settingsReady() || !Settings.SHOW_REEL_POST_DATE.get()) return shown;
            FeedFilterCounters.removed(DATE_ROUTE, 1, DATE_HIDDEN);
            logOnce(DATE_HIDDEN, () -> "Reel post date: drawn where the server left it out");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, DATE_HIDDEN, failure);
            return shown;
        }
    }

    /**
     * Injection point, first thing in the builder of the search pill under a reel's caption (#173).
     * True makes the builder answer with Facebook's own empty component, so no pill is drawn.
     * Never throws: false builds the pill as before.
     */
    public static boolean hideSearchPill() {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(SEARCH_ROUTE, 1);
            FeedFilterCounters.sawKind(SEARCH_ROUTE, SEARCH_PILL);
            boolean hide = Utils.settingsReady() && Settings.HIDE_REEL_SEARCH_PILL.get();
            if (hide) {
                FeedFilterCounters.removed(SEARCH_ROUTE, 1, SEARCH_PILL);
                logOnce(SEARCH_PILL, () -> "Reel search pill: not built");
            }
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, SEARCH_PILL, failure);
            Logger.printException(() -> "Reel search pill: could not read its switch", failure);
            return false;
        }
    }

    /**
     * Injection point, right after the comment sheet's builder reads the search prompt it puts over
     * a reel's comments (#197). Null while the search pill switch is on, which is the path Facebook
     * takes for a sheet that has no prompt, so the row isn't built. Never throws: off, paused, before
     * the settings are ready or on a failure the prompt comes back as it was handed in.
     *
     * @param prompt Facebook's SearchPromptParam, or null when the sheet has none.
     */
    public static Object commentSearchPrompt(Object prompt) {
        if (prompt == null) return null;
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(SEARCH_ROUTE, 1);
            FeedFilterCounters.sawKind(SEARCH_ROUTE, COMMENT_SEARCH_PILL);
            if (!Utils.settingsReady() || !Settings.HIDE_REEL_SEARCH_PILL.get()) return prompt;
            FeedFilterCounters.removed(SEARCH_ROUTE, 1, COMMENT_SEARCH_PILL);
            logOnce(COMMENT_SEARCH_PILL, () -> "Reel search pill: none over the comments");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, COMMENT_SEARCH_PILL, failure);
            Logger.printException(() -> "Reel search pill: could not read its switch for the comments", failure);
            return prompt;
        }
    }

    /**
     * Injection point, in place of the call that puts the search bar, or the send button beside it,
     * into the header over a reel opened from a profile (#201). Null while the search pill switch is
     * on, which that call skips, as it does a part Facebook didn't build. Never throws: off, paused,
     * before the settings are ready or on a failure the part comes back as it was handed in.
     *
     * @param part Facebook's component for the bar or the button, or null when it built none.
     */
    public static Object searchBarPart(Object part) {
        if (part == null) return null;
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(SEARCH_ROUTE, 1);
            FeedFilterCounters.sawKind(SEARCH_ROUTE, SEARCH_BAR);
            if (!Utils.settingsReady() || !Settings.HIDE_REEL_SEARCH_PILL.get()) return part;
            FeedFilterCounters.removed(SEARCH_ROUTE, 1, SEARCH_BAR);
            logOnce(SEARCH_BAR, () -> "Reel search pill: no search bar over the reel");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, SEARCH_BAR, failure);
            Logger.printException(() -> "Reel search pill: could not read its switch for the search bar", failure);
            return part;
        }
    }

    /**
     * Injection point, first thing in the constructor of the vertical button a reel's sidebar is
     * made of (Like, Comment, Share and the rest), handed the text the button shows under its icon.
     * A text holding a number becomes {@link #NO_COUNT} while the switch is on, so the icon stays
     * and nothing shows under it. A word such as "Share" stays, and so does a missing text. Never
     * throws: off, paused, before the settings are ready or on a failure the text comes back as it
     * was handed in.
     */
    public static String countText(String text) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            if (text == null || !Utils.settingsReady() || !Settings.HIDE_REEL_COUNTS.get()) return text;
            if (!holdsDigit(text)) return text;
            FeedFilterCounters.sawList(COUNTS_ROUTE, 1);
            FeedFilterCounters.sawKind(COUNTS_ROUTE, COUNT);
            FeedFilterCounters.removed(COUNTS_ROUTE, 1, COUNT);
            logOnce(COUNT, () -> "Reel counts: a sidebar button's number was taken away");
            return NO_COUNT;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, COUNT, failure);
            return text;
        }
    }

    /**
     * Injection point, in the reel sidebar builders, handed each button type before anything is
     * built for it (#190, #243). True skips that type, so the button isn't built, marked or named,
     * and the others close the gap. Like, Comment, Share, Save and direct WhatsApp each have their
     * own switch (#265). Never throws: off, paused, before the settings are ready, for any other
     * type or on a failure the button is built as before.
     *
     * @param type the constant of Facebook's icon enum the sidebar is about to build a button for.
     */
    public static boolean hideSidebarButton(Object type) {
        String button = SAVE_BUTTON;
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            if (!(type instanceof Enum)) return false;
            BooleanSetting setting;
            switch (((Enum<?>) type).name()) {
                case SAVE_TYPE: setting = Settings.HIDE_REEL_SAVE_BUTTON; break;
                case WHATSAPP_TYPE: button = WHATSAPP_BUTTON; setting = Settings.HIDE_REEL_WHATSAPP_BUTTON; break;
                case LIKE_TYPE: button = LIKE_BUTTON; setting = Settings.HIDE_REEL_LIKE_BUTTON; break;
                case COMMENT_TYPE: button = COMMENT_BUTTON; setting = Settings.HIDE_REEL_COMMENT_BUTTON; break;
                case SHARE_TYPE: button = SHARE_BUTTON; setting = Settings.HIDE_REEL_SHARE_BUTTON; break;
                default: return false;
            }
            if (!Utils.settingsReady()) return false;
            if (!setting.get()) return false;
            FeedFilterCounters.sawList(SIDEBAR_ROUTE, 1);
            FeedFilterCounters.sawKind(SIDEBAR_ROUTE, button);
            FeedFilterCounters.removed(SIDEBAR_ROUTE, 1, button);
            String hidden = button;
            logOnce(hidden, () -> "Reel sidebar: skipped the " + hidden);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, button, failure);
            return false;
        }
    }

    /**
     * Injection point, in the reel viewer, handed Facebook's answer to whether the speaker button
     * at the bottom of the sidebar is built (#190). Answers false while the switch is on, so the
     * viewer takes its own branch for a reel without the button. Never throws: off, paused, before
     * the settings are ready, when Facebook already said no or on a failure, Facebook's answer
     * stands.
     *
     * @param shows whether Facebook would build the speaker button.
     */
    public static boolean showsSoundButton(boolean shows) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            if (!shows || !Utils.settingsReady() || !Settings.HIDE_REEL_SOUND_BUTTON.get()) return shows;
            FeedFilterCounters.sawList(SIDEBAR_ROUTE, 1);
            FeedFilterCounters.sawKind(SIDEBAR_ROUTE, SOUND_BUTTON);
            FeedFilterCounters.removed(SIDEBAR_ROUTE, 1, SOUND_BUTTON);
            logOnce(SOUND_BUTTON, () -> "Reel sidebar: left out the speaker button");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, SOUND_BUTTON, failure);
            return shows;
        }
    }

    /**
     * Injection point, in the reel footer, handed the speaker button Facebook just built, or null
     * when it built none (#190). Answers null while the switch is on, so the footer's own null check
     * leaves the button out. Asks {@link #showsSoundButton}, so both places the speaker can come
     * from answer to one switch and count the same way.
     *
     * @param button the speaker button's component, or null.
     */
    public static Object soundButton(Object button) {
        return button == null || showsSoundButton(true) ? button : null;
    }

    /** Whether Move the speaker above the three dots is on. */
    private static boolean movesSpeaker() {
        return Utils.settingsReady() && Settings.REEL_SPEAKER_ABOVE_MENU.get();
    }

    /** The mark for the reel, or null. Call with {@link #MOVED_SPEAKERS} held. */
    private static MovedSpeaker movedSpeaker(Object reel) {
        for (int i = MOVED_SPEAKERS.size() - 1; i >= 0; i--) {
            MovedSpeaker mark = MOVED_SPEAKERS.get(i);
            if (mark.reel.get() == reel) return mark;
        }
        return null;
    }

    /** The corner remembered for the reel, while the reel's speaker goes to its side column, or null. */
    private static Object rememberedCorner(Object reel) {
        synchronized (MOVED_SPEAKERS) {
            MovedSpeaker mark = movedSpeaker(reel);
            return mark == null || mark.corner == null ? null : mark.corner.get();
        }
    }

    /**
     * Injection point, in the reel's builder, handed the reel as it fills the side column's field
     * (#331): this reel's side column is one that can take the speaker. A reel the builder gives
     * another column never gets here, and stays as Facebook has it. Never throws.
     *
     * @param reel the reel.
     */
    public static void sideColumnComing(Object reel) {
        try {
            if (reel == null || !movesSpeaker()) return;
            synchronized (MOVED_SPEAKERS) {
                MovedSpeaker mark = movedSpeaker(reel);
                if (mark != null) {
                    mark.corner = null;
                    return;
                }
                if (MOVED_SPEAKERS.size() >= MOVED_SPEAKERS_KEPT) MOVED_SPEAKERS.remove(0);
                MOVED_SPEAKERS.add(new MovedSpeaker(reel));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, SPEAKER_MOVED, failure);
        }
    }

    /**
     * Injection point, in the corner the reel's builder just made, unless the side column is one
     * that leaves before it assembles its buttons (#331). From here on the reel's speaker goes to
     * the side column, which reads what it needs from this corner. Never throws.
     *
     * @param reel the reel.
     * @param corner the component whose column holds the speaker on Facebook's own layout.
     */
    public static void rememberCorner(Object reel, Object corner) {
        try {
            if (reel == null) return;
            synchronized (MOVED_SPEAKERS) {
                MovedSpeaker mark = movedSpeaker(reel);
                if (mark != null) mark.corner = new WeakReference<>(corner);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, SPEAKER_MOVED, failure);
        }
    }

    /**
     * Injection point, in the side column, right before it assembles its buttons (#331). Answers the
     * corner to build the speaker from, or null for no speaker here: the switch is off, the speaker
     * is hidden, the app is paused, or the reel keeps its speaker where Facebook put it. Never throws.
     *
     * @param reel the reel.
     */
    public static Object cornerForSide(Object reel) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            if (reel == null || !movesSpeaker() || !showsSoundButton(true)) return null;
            Object corner = rememberedCorner(reel);
            if (corner != null) BUILDING_SIDE_SPEAKER.set(Boolean.TRUE);
            return corner;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, SPEAKER_MOVED, failure);
            return null;
        }
    }

    /**
     * Injection point, in the side column, handed the speaker it just built from the corner, or null
     * when Facebook's builder made none (#331).
     *
     * @param button the speaker button's component, or null.
     */
    public static Object sideSpeaker(Object button) {
        BUILDING_SIDE_SPEAKER.remove();
        if (button != null) HookStatus.counted(FamilyNames.REEL_DECLUTTER, SPEAKER_MOVED);
        return button;
    }

    /**
     * Injection point, in the corner and the video controls, handed the speaker they just built and
     * the reel (#331). Answers null for a reel whose speaker goes to the side column, so it never
     * shows two, and what was built for any other. Never throws.
     *
     * @param button the speaker button's component, or null.
     * @param reel the reel.
     */
    public static Object speakerStays(Object button, Object reel) {
        if (button == null) return null;
        try {
            return movesSpeaker() && rememberedCorner(reel) != null ? null : button;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, SPEAKER_MOVED, failure);
            return button;
        }
    }

    /**
     * Injection point, in the speaker builder, handed whether it leaves the speaker to the video
     * controls (#331). A server flag says so on some accounts, and the side column needs the speaker
     * built all the same, so the answer is false while it asks. Every other caller gets Facebook's.
     *
     * @param leaves whether Facebook would leave the speaker to the video controls.
     */
    public static boolean footerLeavesSpeaker(boolean leaves) {
        return leaves && BUILDING_SIDE_SPEAKER.get() == null;
    }

    /**
     * Injection point, in the side column's render, handed which layout of buttons Facebook picked
     * (#331). Only the newer one assembles the buttons the speaker joins, so it's yes while the switch
     * is on. Never throws: off, paused, before the settings are ready, or on a failure, Facebook's
     * answer stands.
     *
     * @param newer whether Facebook would draw the newer layout.
     */
    public static boolean speakerLayout(boolean newer) {
        try {
            return newer || movesSpeaker();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, SPEAKER_MOVED, failure);
            return newer;
        }
    }

    /** Whether the text has a digit in any script, which a count always has and a word doesn't. */
    static boolean holdsDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) return true;
        }
        return false;
    }

    /**
     * A debug line the first time each kind of change happens, and only once the settings can be read
     * and Debug logging is on, so turning logging on later still gets the line. These hooks run on
     * every reel the viewer builds, and a line each time would bury the log.
     */
    private static void logOnce(String key, Logger.LogMessage message) {
        if (!Utils.settingsReady() || !BaseSettings.DEBUG.get()) return;
        if (LOGGED.add(key)) Logger.printDebug(message);
    }

    /** Forgets which lines were logged, so a test sees each one again. */
    static void forgetLoggedForTests() {
        LOGGED.clear();
    }
}
