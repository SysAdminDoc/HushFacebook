/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The strip Facebook draws on some posts: "Are you interested in this post?", "Show less", who
 * recently commented, and follow, chat or post suggestions. Facebook calls it a story bumper, and
 * one predicate on its component answers whether a story has one. The bumper plugin and every row
 * that keeps room for a bumper ask it, so with the switch on, a no means no strip and no gap.
 *
 * <p>The same family holds a second switch, off by default: the header of a post from a Page or a
 * person you don't follow shows a dot and "Follow" after the name, a text a title plugin adds when its own check says
 * yes. {@link #showFollowLink} answers that check no, so the header is drawn as for a post that has
 * no such text.
 *
 * <p>A third switch, also off by default, takes the numbers off a post's Like, Comment and Share
 * buttons. Each button's text comes from one builder that answers an empty text for a post nobody
 * has reacted to, commented on or shared, and the button then shows just its word. {@link
 * #hidePostCounts} tells that builder to answer the same way for every post (#180).
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class PostPrompts {
    /** Counted under the patch's name each time a story's bumper is answered away. */
    static final String HIDDEN = "Post prompt kept out";

    /** Counted under the patch's name each time a header's Follow link is answered away. */
    static final String FOLLOW_HIDDEN = "Follow link kept out";

    private static final String FAMILY = FamilyNames.POST_PROMPTS;

    private static volatile boolean logged;

    private static volatile boolean followLogged;

    /** Counted under the patch's name each time a button's count is answered away. */
    static final String COUNTS_HIDDEN = "Post count kept out";

    private static volatile boolean countsLogged;

    private PostPrompts() {
    }

    /**
     * The entry the patch calls, handed the predicate's answer as an int: a boolean method may
     * return a register the verifier types as int, and a boolean parameter wouldn't take it.
     */
    public static boolean keep(int hasBumper) {
        return keep(hasBumper != 0);
    }

    /**
     * The hook, at each of the has-bumper predicate's returns, handed what it was about to answer.
     * Answers what the predicate returns instead: false for a story with a bumper while the switch
     * is on, and Facebook's answer otherwise.
     */
    public static boolean keep(boolean hasBumper) {
        try {
            HookStatus.invoked(FAMILY);
            if (!hasBumper || !Utils.settingsReady() || !Settings.HIDE_POST_PROMPTS.get()) return hasBumper;
            HookStatus.bound(FAMILY, "bumper check");
            HookStatus.counted(FAMILY, HIDDEN);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Post prompts: a post's bumper strip was kept out");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "bumper check", failure);
            return hasBumper;
        }
    }

    /**
     * The entry the follow-link hook calls, handed the check's answer as an int for the same reason
     * as {@link #keep(int)}.
     */
    public static boolean showFollowLink(int shown) {
        return showFollowLink(shown != 0);
    }

    /**
     * The hook, at each return of the check that decides whether a post's header carries the
     * "Follow" (or "Subscribe") text after the name. The header builds without that text when the
     * check says no, so the name, the date and the layout stay as Facebook draws a header without
     * it. A yes becomes a no while the switch is on; everything else is Facebook's answer.
     */
    public static boolean showFollowLink(boolean shown) {
        try {
            HookStatus.invoked(FAMILY);
            if (!shown || !Utils.settingsReady() || !Settings.HIDE_POST_FOLLOW_LINK.get()) return shown;
            HookStatus.bound(FAMILY, "follow link");
            HookStatus.counted(FAMILY, FOLLOW_HIDDEN);
            if (!followLogged) {
                followLogged = true;
                Logger.printDebug(() -> "Post prompts: a post header's Follow link was kept out");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "follow link", failure);
            return shown;
        }
    }

    private static volatile boolean joinLogged;

    /** Counted under the patch's name each time a header is built without its Join button. */
    static final String JOIN_HIDDEN = "Join button kept out";

    /**
     * The hook, first thing in the group header helper that appends the dot and the Join button
     * after a group's name (#356). True makes it return before appending either, so the header is
     * the one Facebook draws for a post with no such button. False, which is the answer while the
     * switch is off, paused, before the settings are ready or when anything here fails, lets
     * Facebook append them.
     */
    public static boolean hideJoinButton() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.HIDE_POST_JOIN_BUTTON.get()) return false;
            HookStatus.bound(FAMILY, "join button");
            HookStatus.counted(FAMILY, JOIN_HIDDEN);
            if (!joinLogged) {
                joinLogged = true;
                Logger.printDebug(() -> "Post prompts: a post header's Join button was kept out");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "join button", failure);
            return false;
        }
    }

    private static volatile boolean postXLogged;

    /** Counted under the patch's name each time a post's header is answered out of its X. */
    static final String POST_X_HIDDEN = "Post X kept out";

    /**
     * The entry the X hook calls, handed the check's answer as an int for the same reason as
     * {@link #keep(int)}.
     */
    public static boolean showPostX(int shown) {
        return showPostX(shown != 0);
    }

    /**
     * The hook, at each return of the check that decides whether a feed post's header gets the X
     * next to its three-dot menu (#389). The header plugin that draws the X and the header builder
     * both ask it, so a no leaves the header as Facebook draws it for a post with no X. A yes
     * becomes a no while the switch is on; everything else is Facebook's answer.
     */
    public static boolean showPostX(boolean shown) {
        try {
            HookStatus.invoked(FAMILY);
            if (!shown || !Utils.settingsReady() || !Settings.HIDE_POST_X.get()) return shown;
            HookStatus.bound(FAMILY, "post X");
            HookStatus.counted(FAMILY, POST_X_HIDDEN);
            if (!postXLogged) {
                postXLogged = true;
                Logger.printDebug(() -> "Post prompts: a post header's X was kept out");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "post X", failure);
            return shown;
        }
    }

    private static volatile boolean joinBarLogged;

    /** Counted under the patch's name each time a group's video is drawn without its Join group bar. */
    static final String JOIN_BAR_HIDDEN = "Join group bar kept out";

    /**
     * The hook, first thing in the provider of the Join group bar under a group's video (#356).
     * True makes it answer null, which is its own answer for a video with no group, so the video
     * gets no footer. False, which is the answer while the switch is off, paused, before the
     * settings are ready or when anything here fails, lets Facebook build the bar.
     */
    public static boolean hideVideoJoinBar() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.HIDE_POST_JOIN_BUTTON.get()) return false;
            HookStatus.bound(FAMILY, "join group bar");
            HookStatus.counted(FAMILY, JOIN_BAR_HIDDEN);
            if (!joinBarLogged) {
                joinBarLogged = true;
                Logger.printDebug(() -> "Post prompts: a video's Join group bar was kept out");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "join group bar", failure);
            return false;
        }
    }

    /**
     * The hook, at the start of the builder that turns a post's reaction, comment or share count
     * into the text on its button. True makes the builder answer the empty text it gives a count of
     * zero, so the button shows its word and keeps its place. False, which is the answer while the
     * switch is off, paused, before the settings are ready or when anything here fails, lets the
     * builder write the number as Facebook does.
     */
    public static boolean hidePostCounts() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.HIDE_POST_COUNTS.get()) return false;
            HookStatus.bound(FAMILY, "count text");
            HookStatus.counted(FAMILY, COUNTS_HIDDEN);
            if (!countsLogged) {
                countsLogged = true;
                Logger.printDebug(() -> "Post prompts: a post button's count was kept out");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "count text", failure);
            return false;
        }
    }

    /** Counted under the patch's name each time a reaction preview is left undrawn. */
    static final String REACTIONS_HIDDEN = "Reaction preview kept out";

    /** Counted under the patch's name each time a comment's reaction preview is left undrawn. */
    static final String COMMENT_REACTIONS_HIDDEN = "Comment reaction preview kept out";

    private static volatile boolean reactionsLogged;

    private static volatile boolean commentReactionsLogged;

    /**
     * Whether the reaction faces this thread is about to build belong to a comment. The lambda that
     * asks FDSInlineReactions' factory for its Drawable reads the component first, and on 585 no
     * register still holds the component once the Drawable comes back, so its flag waits here for
     * the length of that one call.
     */
    private static final ThreadLocal<Boolean> pendingCommentFaces = new ThreadLocal<>();

    /** The hook, right before the lambda reads the component, handed the component's flag. */
    public static void stashCommentFaces(boolean comment) {
        pendingCommentFaces.set(comment);
    }

    /**
     * The hook, right after the factory hands the lambda its Drawable: the flag {@link
     * #stashCommentFaces} left on this thread, which it clears, or false when there is none.
     */
    public static boolean takeCommentFaces() {
        Boolean comment = pendingCommentFaces.get();
        if (comment == null) return false;
        pendingCommentFaces.set(null);
        return comment;
    }

    /** Counted under the patch's name each time the count beside a post's reaction faces is left out. */
    static final String COUNT_HIDDEN = "Reaction count kept out";

    private static volatile boolean countLogged;

    /**
     * The hook, at the start of the draw method behind the bling bar's reaction icons and count
     * line under a post. True makes the draw method return before it paints anything, so the space
     * stays and so does a tap on it (#158). False, which is the answer while the switch is off,
     * paused, before the settings are ready or when anything here fails, draws as Facebook does.
     * Runs every frame, so it only reads the switch and bumps two counters.
     */
    public static boolean hideReactionPreview() {
        return hideReactionPreview(false);
    }

    /**
     * The hook, at the start of the draw method behind the stacked reaction faces, which
     * FDSInlineReactions builds for the end of a post's button row and for the corner of a comment
     * bubble. The patch hands over whether the faces belong to a comment, and each surface answers
     * to its own switch. Same answers and cost as {@link #hideReactionPreview()}.
     */
    public static boolean hideReactionPreview(boolean comment) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady()) return false;
            if (comment) {
                if (!Settings.HIDE_COMMENT_REACTIONS.get()) return false;
                HookStatus.bound(FAMILY, "comment reaction preview");
                HookStatus.counted(FAMILY, COMMENT_REACTIONS_HIDDEN);
                if (!commentReactionsLogged) {
                    commentReactionsLogged = true;
                    Logger.printDebug(() -> "Post prompts: a comment's reaction preview was left undrawn");
                }
                return true;
            }
            if (!Settings.HIDE_POST_REACTIONS.get()) return false;
            HookStatus.bound(FAMILY, "reaction preview");
            HookStatus.counted(FAMILY, REACTIONS_HIDDEN);
            if (!reactionsLogged) {
                reactionsLogged = true;
                Logger.printDebug(() -> "Post prompts: a post's reaction preview was left undrawn");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, comment ? "comment reaction preview" : "reaction preview", failure);
            return false;
        }
    }

    /**
     * The hook, just before the reaction summary beside a post's buttons is built, handed its text:
     * the count next to the faces in Facebook's inline layout ("56"), or "Name and 55 others" in the
     * other one. With the post switch on it answers null, the text Facebook itself builds the
     * summary with when it has none, so nothing is drawn there, while the description TalkBack reads
     * and the tap on the row stay (#158). Otherwise, and for a text Facebook already left empty,
     * Facebook's own text comes back.
     */
    public static String reactionCount(String text) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, "reaction count");
            if (text == null || text.isEmpty()) return text;
            if (!Utils.settingsReady() || !Settings.HIDE_POST_REACTIONS.get()) return text;
            HookStatus.counted(FAMILY, COUNT_HIDDEN);
            if (!countLogged) {
                countLogged = true;
                Logger.printDebug(() -> "Post prompts: a post's reaction count was left out");
            }
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reaction count", failure);
            return text;
        }
    }
}
