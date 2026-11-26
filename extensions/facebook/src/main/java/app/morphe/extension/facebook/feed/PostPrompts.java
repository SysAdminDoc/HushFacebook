/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The strip Facebook draws on some posts: "Are you interested in this post?", "Show less", who
 * recently commented, and follow, chat or post suggestions. Facebook calls it a story bumper, and
 * one predicate on its component answers whether a story has one. The bumper plugin and every row
 * that keeps room for a bumper ask it, so with the switch on, a no means no strip and no gap.
 *
 * <p>The same family holds two more switches, off by default (split in #434): the header of a post
 * from a Page or a person you don't follow shows a dot and "Follow" or "Subscribe" after the name, a
 * text one title plugin adds. Its own check says yes for Subscribe, and {@link #showFollowLink}
 * answers that check no while the Subscribe switch is on. When the check says no, the header's
 * plugin picker looks for a Follow text instead, and {@link #hideFollowRoute} stops it there while
 * the Follow switch is on. Either way the header is drawn as for a post that has no such text.
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

    /** Counted under the patch's name each time a header's Subscribe link is answered away. */
    static final String SUBSCRIBE_HIDDEN = "Subscribe link kept out";

    private static volatile boolean subscribeLogged;

    /**
     * Whether the last answer {@link #showFollowLink} gave on this thread was a yes it turned into a
     * no. The header's plugin picker asks the check, and when the answer is no it goes on to ask
     * whether a Follow, Follow back or Following text fits instead; {@link #hideFollowRoute} reads
     * this right after, so a header whose Subscribe link went doesn't get a Follow link in its place.
     */
    private static final ThreadLocal<Boolean> subscribeHeld = new ThreadLocal<>();

    /**
     * The entry the subscribe-link hook calls, handed the check's answer as an int for the same
     * reason as {@link #keep(int)}.
     */
    public static boolean showFollowLink(int shown) {
        return showFollowLink(shown != 0);
    }

    /**
     * The hook, at each return of the check that decides whether a post's header carries the
     * "Subscribe" (or "Subscribed") text after the name. The header builds without that text when
     * the check says no, so the name, the date and the layout stay as Facebook draws a header without
     * it. A yes becomes a no while the Subscribe switch is on; everything else is Facebook's answer.
     */
    public static boolean showFollowLink(boolean shown) {
        try {
            HookStatus.invoked(FAMILY);
            boolean hide = shown && Utils.settingsReady() && Settings.HIDE_POST_SUBSCRIBE_LINK.get();
            subscribeHeld.set(hide);
            if (!hide) return shown;
            HookStatus.bound(FAMILY, "subscribe link");
            HookStatus.counted(FAMILY, SUBSCRIBE_HIDDEN);
            if (!subscribeLogged) {
                subscribeLogged = true;
                Logger.printDebug(() -> "Post prompts: a post header's Subscribe link was kept out");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "subscribe link", failure);
            return shown;
        }
    }

    /**
     * The hook in the header's plugin picker, right where the subscribe check has said no and the
     * picker goes on to see whether a Follow, Follow back or Following text fits. True makes the
     * picker answer that the plugin isn't needed, so the header carries no such text: while the
     * Follow switch is on, and for a header whose Subscribe link {@link #showFollowLink} just held
     * back. False, which is the answer while the switch is off, paused, before the settings are
     * ready or when anything here fails, lets Facebook decide.
     */
    public static boolean hideFollowRoute() {
        try {
            HookStatus.invoked(FAMILY);
            if (Boolean.TRUE.equals(subscribeHeld.get())) {
                subscribeHeld.set(Boolean.FALSE);
                return true;
            }
            if (!Utils.settingsReady() || !Settings.HIDE_POST_FOLLOW_LINK.get()) return false;
            HookStatus.bound(FAMILY, "follow link");
            HookStatus.counted(FAMILY, FOLLOW_HIDDEN);
            if (!followLogged) {
                followLogged = true;
                Logger.printDebug(() -> "Post prompts: a post header's Follow link was kept out");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "follow link", failure);
            return false;
        }
    }

    /**
     * The hook in the two title plugins that build their own " · Follow" text, a collab post's and a
     * partnership ad's second name (#396), handed that text just before the title segment takes it.
     * While the switch is on the segment gets an empty text of the same kind, so the header carries
     * no dot, no word and nothing to tap. Everything else gets Facebook's text back.
     */
    public static CharSequence followText(CharSequence text) {
        try {
            HookStatus.invoked(FAMILY);
            if (text == null || text.length() == 0 || !Utils.settingsReady() || !Settings.HIDE_POST_FOLLOW_LINK.get()) return text;
            HookStatus.bound(FAMILY, "follow text");
            HookStatus.counted(FAMILY, FOLLOW_HIDDEN);
            if (!followLogged) {
                followLogged = true;
                Logger.printDebug(() -> "Post prompts: a post header's Follow link was kept out");
            }
            return text.subSequence(0, 0);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "follow text", failure);
            return text;
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

    private static volatile boolean reelsRowXLogged;

    /** Counted under the patch's name each time the Reels row in the feed is drawn without its X. */
    static final String REELS_ROW_X_HIDDEN = "Reels row X kept out";

    /** The entry the Reels row hook calls, handed the check's answer as an int like {@link #showPostX(int)}. */
    public static boolean showReelsRowX(int shown) {
        return showReelsRowX(shown != 0);
    }

    /**
     * The hook in the header of the Reels row in the feed, handed whether it's about to add the X
     * next to its three-dot menu (#389). Facebook adds it only when the row can be hidden from the
     * feed, so a no draws the header with the menu alone. Same switch as the X on posts.
     */
    public static boolean showReelsRowX(boolean shown) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, "Reels row X");
            if (!shown || !Utils.settingsReady() || !Settings.HIDE_POST_X.get()) return shown;
            HookStatus.counted(FAMILY, REELS_ROW_X_HIDDEN);
            if (!reelsRowXLogged) {
                reelsRowXLogged = true;
                Logger.printDebug(() -> "Post prompts: the Reels row's X was kept out");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Reels row X", failure);
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

    /** Counted under the patch's name each time a post you can't comment on keeps its Comment button. */
    static final String COMMENT_KEPT = "Comment button kept on a post with comments off";

    /** The server's names for the footer's Comment and Like items, what their enum constants' toString answers. */
    static final String COMMENT_ITEM = "COMMENT";
    static final String LIKE_ITEM = "REACT";

    /**
     * Injection point, in the check that picks a post's footer buttons, handed Facebook's answer to
     * whether you may comment on the post. A post whose author turned comments off or limited who
     * can comment gets no Comment button, even when it has comments to read (#299). While the switch
     * is on, a no becomes a yes, so the button shows and opens the comments. Off, paused, before the
     * settings are ready, or when anything here fails, the answer is Facebook's own. Never throws.
     */
    public static boolean showsCommentButton(boolean canComment) {
        try {
            HookStatus.invoked(FAMILY);
            if (canComment || !Utils.settingsReady() || !Settings.SHOW_POST_COMMENT_BUTTON.get()) return canComment;
            HookStatus.counted(FAMILY, COMMENT_KEPT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, COMMENT_KEPT, failure);
            return canComment;
        }
    }

    /** Whether you may comment on the post the footer list builder on this thread is building for, or null. */
    private static final ThreadLocal<Boolean> footerCanComment = new ThreadLocal<>();

    /**
     * Injection point, first thing in the builder of a post's footer item list: whether you may
     * comment on the post, kept for {@link #withCommentButton(List)} at the builder's return (the
     * builder reuses the post's register on the way). Never throws.
     */
    public static void footerPost(boolean canComment) {
        footerCanComment.set(canComment);
    }

    /**
     * Injection point, last thing in the builder of a post's footer item list, handed the list it
     * returns. {@link #withCommentButton(List, boolean)} with the answer {@link #footerPost} kept, a
     * yes when there's none. Never throws.
     */
    public static List<?> withCommentButton(@Nullable List<?> buttons) {
        Boolean canComment = footerCanComment.get();
        footerCanComment.remove();
        return withCommentButton(buttons, canComment == null || canComment);
    }

    /**
     * The footer item list (the constants of Facebook's footer item enum, each answering its server
     * name from toString) for a post, given whether you may comment on it. A post can come with its
     * buttons named by the server, and the builder takes that list as it is, never asking the check
     * {@link #showsCommentButton} hooks: on a post you can't comment on, Comment just isn't in it
     * (#299). While the switch is on, Comment goes back in right after the Like button (REACT), where
     * it sits on every other post. A list with no Like button is left alone, since that's no post you
     * could comment on anyway. Off, paused, before the settings are ready, or when anything here
     * fails, the very same list. Never throws.
     */
    static List<?> withCommentButton(@Nullable List<?> buttons, boolean canComment) {
        try {
            if (buttons == null || buttons.isEmpty()) return buttons;
            HookStatus.invoked(FAMILY);
            if (canComment || !Utils.settingsReady() || !Settings.SHOW_POST_COMMENT_BUTTON.get()) return buttons;

            int like = -1;
            for (int i = 0; i < buttons.size(); i++) {
                String name = String.valueOf(buttons.get(i));
                if (COMMENT_ITEM.equals(name)) return buttons;
                if (like < 0 && LIKE_ITEM.equals(name)) like = i;
            }
            if (like < 0 || !(buttons.get(like) instanceof Enum)) return buttons;

            Object comment = null;
            for (Object constant : ((Enum<?>) buttons.get(like)).getDeclaringClass().getEnumConstants()) {
                if (COMMENT_ITEM.equals(String.valueOf(constant))) comment = constant;
            }
            if (comment == null) {
                HookStatus.missingMember(FAMILY, "constant", "footer item", COMMENT_ITEM);
                return buttons;
            }

            List<Object> withComment = new ArrayList<>(buttons);
            withComment.add(like + 1, comment);
            HookStatus.counted(FAMILY, COMMENT_KEPT);
            return withComment;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, COMMENT_KEPT, failure);
            return buttons;
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
     * The hook the patch puts at the start of the stacked faces' draw, handed the faces themselves
     * and whether they belong to a comment. Each surface answers to its own Hide switch as {@link
     * #hideReactionPreview(boolean)} does, and a post's faces that switch keeps are then offered to
     * Show reactions on the Like button, which holds them back while the row's Like button draws them
     * (#541).
     */
    public static boolean hideReactionPreview(Drawable facepile, boolean comment) {
        if (hideReactionPreview(comment)) return true;
        return !comment && LikeButtonFaces.movedFrom(facepile);
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

    /** Counted under the patch's name each time a comment's reaction count is left out. */
    static final String COMMENT_COUNT_HIDDEN = "Comment reaction count kept out";

    private static volatile boolean commentCountLogged;

    /**
     * The hook, right after FDSInlineReactions reads the length of the count it would write beside
     * its faces, handed that length and whether the faces belong to a comment (#483). Facebook only
     * draws the count when the length is above zero, so 0 leaves a comment's faces with no number
     * beside them, while the faces, their tap and what TalkBack reads stay. A post's count, a
     * comment's with the switch off, paused, before the settings are ready or when anything here
     * fails, keeps Facebook's length.
     */
    public static int commentReactionCount(int length, boolean comment) {
        try {
            HookStatus.invoked(FAMILY);
            if (!comment || length <= 0) return length;
            if (!Utils.settingsReady() || !Settings.HIDE_COMMENT_REACTION_COUNTS.get()) return length;
            HookStatus.bound(FAMILY, "comment reaction count");
            HookStatus.counted(FAMILY, COMMENT_COUNT_HIDDEN);
            if (!commentCountLogged) {
                commentCountLogged = true;
                Logger.printDebug(() -> "Post prompts: a comment's reaction count was left out");
            }
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment reaction count", failure);
            return length;
        }
    }

    private static volatile boolean bubblesLogged;

    /** Counted under the patch's name each time the friend bubbles over a post's media are left out. */
    static final String BUBBLES_HIDDEN = "Friend reaction bubbles kept out";

    /**
     * The hook, first thing in the render of the bubble row Facebook lays over a post's photo or
     * video: each friend who reacted, as their picture with their reaction on it (#483). True makes
     * the render answer null, its own answer for a post with no bubbles, so nothing is drawn over the
     * media. False, which is the answer while the switch is off, paused, before the settings are
     * ready or when anything here fails, lets Facebook draw the row.
     */
    public static boolean hideReactionBubbles() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.HIDE_REACTION_BUBBLES.get()) return false;
            HookStatus.bound(FAMILY, "reaction bubbles");
            HookStatus.counted(FAMILY, BUBBLES_HIDDEN);
            if (!bubblesLogged) {
                bubblesLogged = true;
                Logger.printDebug(() -> "Post prompts: a post's friend reaction bubbles were left out");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reaction bubbles", failure);
            return false;
        }
    }

    private static volatile boolean reactorsLogged;

    /** Counted under the patch's name each time the line naming who reacted to a post is left out. */
    static final String REACTORS_HIDDEN = "Names of who reacted kept out";

    /**
     * The hook, first thing in the two checks the bar under a post's buttons uses to decide whether
     * to name who reacted: the short "Name and others reacted" line and the "Name and 20 others"
     * sentence (#483). True makes the check answer no, so the bar leaves that line out and keeps
     * whatever else it shows. False, which is the answer while the switch is off, paused, before the
     * settings are ready or when anything here fails, lets Facebook decide.
     */
    public static boolean hideReactorNames() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.HIDE_REACTOR_NAMES.get()) return false;
            HookStatus.bound(FAMILY, "who reacted");
            HookStatus.counted(FAMILY, REACTORS_HIDDEN);
            if (!reactorsLogged) {
                reactorsLogged = true;
                Logger.printDebug(() -> "Post prompts: the line naming who reacted to a post was left out");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "who reacted", failure);
            return false;
        }
    }
}
