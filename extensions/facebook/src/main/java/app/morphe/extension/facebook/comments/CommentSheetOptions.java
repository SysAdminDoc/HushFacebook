/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.Collection;

import app.morphe.extension.facebook.feed.PostWords;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Comment sheet options: the comment box without its GIF and sticker buttons, Like without the
 * reaction picker a long press opens, every comment's reply thread open from the start, a group
 * post's comments without the Related groups list under them, comments with shop links or your
 * words left out, and a way to Facebook's own setting that hides reaction counts.
 *
 * <p>The comment box draws its buttons through a socket that asks a check of each button, by
 * number, whether it shows. The patch asks {@link #holdsButton} first in that check with the
 * button's name, and a yes answers no for that button. The comment bar under a video in the
 * full-screen viewer builds its sticker and GIF buttons itself, and the patch asks
 * {@link #holdsViewerButtons} once they're built; a yes drops both. What comes after a post's comments is drawn
 * through another socket of the same kind, and the patch asks {@link #holdsBottomContent} first in
 * its check. The few comments under a feed post's buttons come through a third, and the patch asks
 * {@link #holdsPostComments} first in its check. The Dislike button under a comment is built by its
 * own plugin, and the patch asks {@link #holdsCommentDislike} first in that builder; a yes builds
 * nothing, and the socket of a comment's buttons draws the rest of the row as it would without a
 * Dislike. Like in that row is written by one of two plugins, the Like text or the Like button
 * beside the Dislike, both through one method, and the patch asks {@link #holdsCommentLike} first
 * there the same way. A long press on Like opens the reaction
 * picker through one method, and the patch asks {@link #skipReactionPicker} first there; a yes
 * returns before anything opens. A comment keeps whether its reply thread is open in its state,
 * which starts closed until a tap on View replies opens it. The patch hands
 * {@link #openReplyThreads} that flag as the state is first set up and writes back the answer.
 * Once an open thread's replies are fetched, it builds a list over them that either pages on by
 * itself or draws View more replies; the patch asks {@link #loadWholeReplyThreads} which, just
 * before Facebook sets it. A View more replies row reads a flag that makes only its words take a
 * tap, and the patch hands {@link #viewMoreRepliesTextOnly} that flag right after. Each comment is drawn by a section that the patch asks {@link #hidesComment} first, with the
 * comment it draws; a yes draws nothing for it. Facebook has no way to close a thread once it's
 * open, so the patch asks {@link #drawsHideRepliesRow} as one is drawn and on a yes draws a row over
 * it that clears the flag, with {@link #hideRepliesWords} on it.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answers are
 * Facebook's own and its code runs as written.
 */
public final class CommentSheetOptions {
    /** The comment box's GIF button, as the socket's name table gives it. */
    public static final String GIF_BUTTON =
            "com.facebook.feedback.comments.plugins.commentcomposer.attachmentbutton.gif.GifAttachmentButtonPlugin";

    /** The comment box's sticker button. */
    public static final String STICKER_BUTTON =
            "com.facebook.feedback.comments.plugins.commentcomposer.attachmentbutton.sticker.StickerAttachmentButtonPlugin";

    /** The Related groups list a group post gets under its comments, as its socket's name table gives it. */
    public static final String RELATED_GROUPS =
            "com.facebook.feedback.comments.plugins.bottomcontent.impl.relatedgroups.RelatedGroupsPlugin";

    /** The few comments a feed post shows under its buttons, as that socket's name table gives them (#253). */
    public static final String INLINE_COMMENTS =
            "com.facebook.feed.plugins.belowufifooter.impl.inlinecommentwithreactivex.InlineCommentWithReactiveXPlugin";

    /** The same inline comments picked by how long the post stayed on screen. */
    public static final String INLINE_COMMENTS_DWELL_TIME =
            "com.facebook.feed.plugins.belowufifooter.impl.inlinecommentwithdwelltime.InlineCommentWithDwellTimePlugin";

    /** The same inline comments ranked on the phone. */
    public static final String INLINE_COMMENTS_CLIENT_RANKING =
            "com.facebook.feed.plugins.belowufifooter.impl.inlinecommentwithclientranking.InlineCommentWithClientRankingPlugin";

    /**
     * The comment box under a feed post, which in 584 draws the post's pinned comment above itself
     * (#305, #253), as the same name table gives it.
     */
    public static final String INLINE_COMMENT_COMPOSER =
            "com.facebook.feed.plugins.belowufifooter.impl.inlinecommentcomposer.InlineCommentComposerPlugin";

    /** The same comment box with the comment a post was opened for lifted above it. */
    public static final String INLINE_COMMENT_COMPOSER_HOISTED =
            "com.facebook.feed.plugins.belowufifooter.impl.hoiststoryinlinecommentcomposer.HoistStoryInlineCommentComposerPlugin";

    /** The same comment box for a group that takes anonymous comments, its identity picker included. */
    public static final String INLINE_COMMENT_COMPOSER_ANONYMOUS =
            "com.facebook.feed.plugins.belowufifooter.impl.anoninlinecommentcomposer.AnonInlineCommentComposerPlugin";

    /** Every plugin that draws comments, or a comment box with comments above it, under a feed post's buttons. */
    private static final String[] POST_COMMENT_PLUGINS = {
            INLINE_COMMENTS, INLINE_COMMENTS_DWELL_TIME, INLINE_COMMENTS_CLIENT_RANKING,
            INLINE_COMMENT_COMPOSER, INLINE_COMMENT_COMPOSER_HOISTED, INLINE_COMMENT_COMPOSER_ANONYMOUS,
    };

    /** Counted each time a comment's Dislike button is kept out (#241). */
    static final String DOWNVOTE_HIDDEN = "Comment Dislike button kept out";

    /** The member the report names once the Dislike plugin's builder has asked. */
    static final String COMMENT_DISLIKE = "comment dislike";

    /** Counted each time Like is kept out of a comment's row (#241). */
    static final String LIKE_HIDDEN = "Comment Like kept out";

    /** The member the report names once the Like plugin's builder has asked. */
    static final String COMMENT_LIKE = "comment like";

    /** Counted each time a feed post's inline comments are kept out from under its buttons. */
    static final String POST_COMMENTS_HIDDEN = "Comments under a post kept out";

    /** The member the report names once the socket under a post's buttons has asked about its inline comments. */
    static final String POST_COMMENTS = "comments under posts";

    /** Counted under the patch's name each time the GIF or sticker button is kept out of a comment box. */
    static final String BUTTON_HIDDEN = "Comment box button kept out";

    /** Counted each time the Related groups list is kept out from under a post's comments. */
    static final String RELATED_GROUPS_HIDDEN = "Related groups kept out";

    /** Counted each time a long press on Like doesn't open the reaction picker. */
    static final String PICKER_SKIPPED = "Reaction picker kept closed";

    /** Counted each time a comment starts with its reply thread open. */
    static final String THREAD_OPENED = "Reply thread opened";

    /** Counted each time an open reply thread's list is set to load the rest of its replies as it's scrolled. */
    static final String THREAD_LOADS_IN_FULL = "Reply thread loads in full";

    /** Counted each time a View more replies row is built to take a tap anywhere on it. */
    static final String WHOLE_ROW_TAPS = "View more replies row takes a tap anywhere";

    /** Counted each time an open reply thread is drawn with a Hide replies row above it. */
    static final String HIDE_ROWS_DRAWN = "Hide replies row drawn";

    /** Counted each time a comment is left out for a link to a shop. */
    static final String SHOP_LINK_HIDDEN = "Comment with a shop link left out";

    /** Counted each time a comment is left out for a phrase from Words to hide. */
    static final String WORDS_HIDDEN = "Comment with your words left out";

    /** Counted each time a count of people typing is answered as nobody. */
    static final String TYPING_HIDDEN = "Incoming typing kept out";

    /** The member the report names once a count of people typing has been through the hook. */
    static final String TYPING = "incoming typing";

    /** Counted each time the "new comments" pill's text is answered empty, so the pill never shows. */
    static final String PILL_HIDDEN = "New comments pill kept out";

    /** The member the report names once the "new comments" pill's text has been through the hook. */
    static final String PILL = "new comments pill";

    /** The member the report names once a comment has been drawn through the hook. */
    static final String COMMENTS = "comments";

    /** The member the report names once the comment box's socket has asked about the GIF or sticker button. */
    static final String BUTTONS = "comment box buttons";

    /** The member the report names once the video viewer's comment bar has built its GIF and sticker buttons. */
    static final String VIEWER_BUTTONS = "video viewer comment buttons";

    static final String VIEWER_COMPOSER = "video viewer comment composer";
    static final String COMPOSER_HIDDEN = "Video comment composer hidden";

    /** The member the report names once the socket over the comment box has asked about the emoji row (#290). */
    static final String EMOJI_ROW = "emoji row over the comment box";

    /** Counted each time the emoji row is kept out from over the comment box. */
    static final String EMOJI_ROW_HIDDEN = "Comment emoji row kept out";

    /** The member the report names once the socket under a post's comments has asked about Related groups. */
    static final String BOTTOM = "related groups under comments";

    /** The member the report names once the reaction picker has been asked to open. */
    static final String PICKER = "reaction picker";

    /** The member the report names once a comment's state has been set up. */
    static final String REPLY_THREADS = "reply threads";

    /** The member the report names once an open reply thread has built the list of its replies. */
    static final String REPLY_LISTS = "reply lists";

    /** The member the report names once a View more replies row has been drawn through the hook. */
    static final String REPLY_ROWS = "View more replies rows";

    /** The member the report names once an open reply thread has asked about its Hide replies row. */
    static final String HIDE_ROWS = "Hide replies rows";

    /**
     * Facebook's own settings, a route its links table carries on 577, 580 and 581. Reaction
     * preferences sits under Preferences there.
     */
    static final String SETTINGS_ROUTE = "fb://facebook_settings/";

    private static final String FAMILY = FamilyNames.COMMENT_SHEET_OPTIONS;

    private CommentSheetOptions() {
    }

    /**
     * The hook, right after Facebook reads how many people are typing a comment on a post. With the
     * switch on it answers zero, so the comment box only hears that nobody is typing; otherwise,
     * and for a count of zero or a throw, it hands back what Facebook read.
     */
    public static int incomingTypers(int typers) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, TYPING);
            if (typers <= 0 || !Utils.settingsReady() || !Settings.HIDE_INCOMING_COMMENT_TYPING.get()) return typers;
            HookStatus.counted(FAMILY, TYPING_HIDDEN);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "incoming comment typing", failure);
            return typers;
        }
    }

    /**
     * The hook, right after Facebook writes the "3 new comments" text for the pill that shows while
     * a comment list is open. With the switch on it answers an empty text, and Facebook's own check
     * skips the pill when its text is empty; otherwise, and for a throw, it hands back what
     * Facebook wrote.
     */
    public static String newCommentsPill(String text) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, PILL);
            if (text == null || text.isEmpty() || !Utils.settingsReady() || !Settings.HIDE_NEW_COMMENTS_PILL.get()) return text;
            HookStatus.counted(FAMILY, PILL_HIDDEN);
            return "";
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "new comments pill", failure);
            return text;
        }
    }

    /**
     * The hook on the newer pill (#185), right before its builder returns the pill's data. That
     * comment list draws its pill from the data and draws none for a null, which is what the
     * builder's caller already answers when no comment came in. With the switch on it answers
     * null; otherwise, for a null, and for a throw, it hands back what Facebook built.
     */
    @Nullable
    public static Object newCommentsPillData(@Nullable Object data) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, PILL);
            if (data == null || !Utils.settingsReady() || !Settings.HIDE_NEW_COMMENTS_PILL.get()) return data;
            HookStatus.counted(FAMILY, PILL_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "new comments pill", failure);
            return data;
        }
    }

    /**
     * The hook, first thing in the comment box's check of whether a button shows, handed the
     * button's name. True answers no for the GIF and sticker buttons while the switch is on; false
     * leaves the check to Facebook, for every other button and otherwise.
     */
    public static boolean holdsButton(@Nullable String plugin) {
        try {
            HookStatus.invoked(FAMILY);
            if (!GIF_BUTTON.equals(plugin) && !STICKER_BUTTON.equals(plugin)) return false;
            HookStatus.bound(FAMILY, BUTTONS);
            if (!Utils.settingsReady() || !Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS.get()) return false;
            HookStatus.counted(FAMILY, BUTTON_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment box button check", failure);
            return false;
        }
    }

    /**
     * The hook, in the comment bar under a video in the full-screen viewer, once it has built its
     * sticker and GIF buttons. That bar is its own component, not the comment box's socket, so
     * {@link #holdsButton} never hears of it. True drops both buttons while the switch is on;
     * false keeps the bar as Facebook built it.
     */
    public static boolean holdsViewerButtons() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, VIEWER_BUTTONS);
            if (!Utils.settingsReady() || !Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS.get()) return false;
            HookStatus.counted(FAMILY, BUTTON_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "video viewer comment bar", failure);
            return false;
        }
    }

    /**
     * The hook, first thing in the check of whether the socket over the comment box shows its emoji
     * row (#290). True answers no while the switch is on, and the box draws without the row; false
     * leaves the check to Facebook. Meta AI's pills, the socket's other plugin, never reach it.
     */
    public static boolean holdsEmojiRow() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, EMOJI_ROW);
            if (!Utils.settingsReady() || !Settings.HIDE_COMMENT_EMOJI_ROW.get()) return false;
            HookStatus.counted(FAMILY, EMOJI_ROW_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment emoji row", failure);
            return false;
        }
    }

    /**
     * The hook immediately before the full-screen video's comment bar returns its rendered object.
     * True replaces that object with null while Hide the video comment box is on; false preserves
     * Facebook's original render result.
     */
    public static boolean hidesVideoCommentComposer() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, VIEWER_COMPOSER);
            if (!Utils.settingsReady() || !Settings.HIDE_VIDEO_COMMENT_COMPOSER.get()) return false;
            HookStatus.counted(FAMILY, COMPOSER_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "video viewer comment composer", failure);
            return false;
        }
    }

    /**
     * The hook, first thing in the check of what shows under a post's comments, handed the
     * plugin's name. True answers no for the Related groups list while Hide related groups is on;
     * false leaves the check to Facebook, for every other plugin and otherwise.
     */
    public static boolean holdsBottomContent(@Nullable String plugin) {
        try {
            HookStatus.invoked(FAMILY);
            if (!RELATED_GROUPS.equals(plugin)) return false;
            HookStatus.bound(FAMILY, BOTTOM);
            if (!Utils.settingsReady() || !Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS.get()) return false;
            HookStatus.counted(FAMILY, RELATED_GROUPS_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "under comments check", failure);
            return false;
        }
    }

    /**
     * The hook, first thing in the check of what shows under a feed post's buttons, handed the
     * plugin's name. True answers no for the post's inline comments, and for the comment boxes
     * that draw a pinned or lifted comment above themselves (#305), while Hide comments under
     * posts is on; false leaves the check to Facebook, for Meta AI's summary and every other
     * plugin, and otherwise.
     */
    public static boolean holdsPostComments(@Nullable String plugin) {
        try {
            HookStatus.invoked(FAMILY);
            // Every plugin asked while the switch is on, so a Debug report names the one that drew
            // a preview the switch missed (#253, #305).
            if (Utils.settingsReady() && Settings.HIDE_POST_COMMENT_PREVIEWS.get()) {
                Logger.printDebug(() -> "Under post buttons: " + plugin);
            }
            if (!drawsPostComments(plugin)) return false;
            HookStatus.bound(FAMILY, POST_COMMENTS);
            if (!Utils.settingsReady() || !Settings.HIDE_POST_COMMENT_PREVIEWS.get()) return false;
            HookStatus.counted(FAMILY, POST_COMMENTS_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "under post buttons check", failure);
            return false;
        }
    }

    /** True for a plugin that draws comments under a feed post's buttons, one of [POST_COMMENT_PLUGINS]. */
    static boolean drawsPostComments(@Nullable String plugin) {
        if (plugin == null) return false;
        for (String held : POST_COMMENT_PLUGINS) {
            if (held.equals(plugin)) return true;
        }
        return false;
    }

    /**
     * The hook, first thing where the Dislike plugin builds a comment's Dislike button. True builds
     * nothing while Hide Dislike on comments is on, so the row keeps Like, Reply, the reaction icons
     * and their count as Facebook lays them out next to a Dislike; false leaves the button to
     * Facebook, and otherwise. Answering no for the plugin in the socket's own check instead took
     * the reaction icons with it (#241). Block button on comments wins over it: that button is the
     * Dislike button redrawn, so it's built whenever Block is on (#271).
     */
    public static boolean holdsCommentDislike() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, COMMENT_DISLIKE);
            if (!Utils.settingsReady() || !Settings.HIDE_COMMENT_DOWNVOTE_BUTTON.get()
                    || Settings.COMMENT_BLOCK_BUTTON.get()) {
                return false;
            }
            HookStatus.counted(FAMILY, DOWNVOTE_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment dislike", failure);
            return false;
        }
    }

    /**
     * The hook, first thing where either Like plugin writes Like in a comment's row. True writes nothing
     * while Hide Like on comments is on, so the row keeps Reply, the reaction icons and their count;
     * false leaves Like to Facebook, and otherwise (#241).
     */
    public static boolean holdsCommentLike() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, COMMENT_LIKE);
            if (!Utils.settingsReady() || !Settings.HIDE_COMMENT_LIKE_BUTTON.get()) return false;
            HookStatus.counted(FAMILY, LIKE_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, COMMENT_LIKE, failure);
            return false;
        }
    }

    /**
     * The hook, first thing where a long press on Like opens the reaction picker. True returns
     * before the picker opens while Like only is on; false lets it open.
     */
    public static boolean skipReactionPicker() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, PICKER);
            if (!Utils.settingsReady() || !Settings.LIKE_ONLY.get()) return false;
            HookStatus.counted(FAMILY, PICKER_SKIPPED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reaction picker", failure);
            return false;
        }
    }

    /**
     * The hook, as a comment's state is first set up, handed whether its reply thread starts open,
     * which Facebook leaves false. True opens it, the way a tap on View replies does, while Open
     * every reply thread is on; otherwise Facebook's own value comes back.
     */
    public static boolean openReplyThreads(boolean open) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, REPLY_THREADS);
            if (open || !Utils.settingsReady() || !Settings.OPEN_REPLY_THREADS.get()) return open;
            HookStatus.counted(FAMILY, THREAD_OPENED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reply thread", failure);
            return open;
        }
    }

    /**
     * The hook, as an open reply thread builds the list of its fetched replies, handed whether that
     * list pages on by itself, which Facebook leaves false for replies: it draws View more replies
     * under them, and each tap loads one more page. True, while Load whole reply threads is on,
     * makes the list load its next page each time its end comes into view, the way Facebook's own
     * comment lists do, so the rest of the thread follows as it's scrolled with no more taps. It
     * goes a page at a time and only as far as the reader scrolls, so a huge thread can't hold the
     * sheet up. Otherwise Facebook's own value comes back.
     */
    public static boolean loadWholeReplyThreads(boolean pagesOnScroll) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, REPLY_LISTS);
            if (pagesOnScroll || !Utils.settingsReady() || !Settings.LOAD_WHOLE_REPLY_THREADS.get()) return pagesOnScroll;
            HookStatus.counted(FAMILY, THREAD_LOADS_IN_FULL);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reply list", failure);
            return pagesOnScroll;
        }
    }

    /**
     * The hook, as a View more replies row is drawn, handed Facebook's answer to whether only the
     * row's icon and words take a tap (#219). That's a server flag: true for some accounts, which
     * then get a row where a tap past the words does nothing. False, Facebook builds the row with
     * all of it taking the tap. While Tap anywhere on View more replies is on, the answer is false;
     * otherwise, and for a throw, Facebook's own answer comes back.
     */
    public static boolean viewMoreRepliesTextOnly(boolean textOnly) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, REPLY_ROWS);
            if (!textOnly || !Utils.settingsReady() || !Settings.TAP_ANYWHERE_ON_VIEW_MORE_REPLIES.get()) return textOnly;
            HookStatus.counted(FAMILY, WHOLE_ROW_TAPS);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "View more replies row", failure);
            return textOnly;
        }
    }

    /**
     * The hook, as a comment is drawn with its reply thread open, handed what the thread is open
     * for: {@code open}, the comment's reply flag, which a tap on View replies and Open every reply
     * thread set; {@code opensItself}, Facebook's own answer for where the comment sits;
     * {@code localReplies}, replies written on this phone; and {@code keptOpen}, the props' answer
     * for replies the server already sent along (#325). True draws a Hide replies row over the
     * thread. Only a thread the flag alone holds open gets one, because clearing the flag closes no
     * other. Off, paused, before the settings are ready or for a throw, false.
     */
    public static boolean drawsHideRepliesRow(boolean open, boolean opensItself, Collection<?> localReplies, Object keptOpen) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, HIDE_ROWS);
            if (!open || opensItself || (localReplies != null && !localReplies.isEmpty())
                    || Boolean.TRUE.equals(keptOpen) || !Utils.settingsReady() || !Settings.HIDE_REPLIES_BUTTON.get()) return false;
            HookStatus.counted(FAMILY, HIDE_ROWS_DRAWN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Hide replies row", failure);
            return false;
        }
    }

    /** The hook, as a Hide replies row picks its words: what it shows and what it reads out. */
    public static String hideRepliesWords() {
        try {
            return L10n.t("Hide replies");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Hide replies words", failure);
            return "Hide replies";
        }
    }

    /**
     * The hook, first thing as a comment is drawn, handed the GraphQLComment it draws. True leaves
     * the comment out, with its replies: while Hide comments with shop links is on and its words
     * link to a shop, or while Hide comments with your words is on and they hold a phrase from
     * Words to hide and none from the keep list. False draws it as Facebook does, and so does
     * anything this can't read.
     */
    public static boolean hidesComment(@Nullable Object comment) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, COMMENTS);
            if (!Utils.settingsReady()) return false;
            boolean links = Settings.HIDE_SHOP_LINK_COMMENTS.get();
            boolean words = Settings.HIDE_COMMENTS_WITH_WORDS.get();
            if (!links && !words) return false;
            String text = CommentText.body(comment);
            if (text == null) return false;
            if (links && ShopLinks.holds(text)) {
                HookStatus.counted(FAMILY, SHOP_LINK_HIDDEN);
                return true;
            }
            if (words && PostWords.hidesText(text, Settings.HIDDEN_WORDS.get(), Settings.KEPT_WORDS.get(),
                    Settings.POST_WORDS_WHOLE_WORDS.get())) {
                HookStatus.counted(FAMILY, WORDS_HIDDEN);
                return true;
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment filter", failure);
            return false;
        }
    }

    /** The route to Facebook's own settings, for this package only. */
    static Intent reactionSettingsIntent(Context context) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(SETTINGS_ROUTE)).setPackage(context.getPackageName());
    }

    /**
     * Opens Facebook's own settings from [context], inside the current task when it's an activity.
     * False when this build has no activity for the route or Android refused to start it.
     */
    public static boolean openReactionSettings(Context context) {
        try {
            Intent route = reactionSettingsIntent(context);
            ComponentName destination = route.resolveActivity(context.getPackageManager());
            if (destination == null || !context.getPackageName().equals(destination.getPackageName())) return false;
            route.setComponent(destination);
            if (!(context instanceof Activity)) route.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(route);
            return true;
        } catch (ActivityNotFoundException | SecurityException refused) {
            Logger.printInfo(() -> "Comment sheet options: Facebook's settings didn't open");
            return false;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Comment sheet options: could not open Facebook's settings", failure);
            return false;
        }
    }
}
