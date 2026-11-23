/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.feed.PostWords;
import app.morphe.extension.facebook.reels.ReactionHold;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;

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
 * there the same way. Reply in that row has no builder of its own, so the patch asks
 * {@link #holdsCommentReply} first in the check of the socket of a comment's buttons, with the
 * plugin's name, and a yes answers no for either Reply plugin. A commenter's badge (Top fan,
 * Subscriber) is a plugin of the sockets that draw the commenter's name, and the patch asks
 * {@link #hidesCommenterBadge} first in each socket's check when it's asked about that plugin; a
 * yes answers no for the badge. A long press on Like opens the reaction
 * picker through one method, and the patch asks {@link #skipReactionPicker} first there; a yes
 * returns before anything opens. A comment keeps whether its reply thread is open in its state,
 * which starts closed until a tap on View replies opens it. The patch hands
 * {@link #openReplyThreads} that flag as the state is first set up and writes back the answer.
 * An open thread's replies draw a View more replies pager while there's another page, and the
 * patch hands {@link #loadRestOfThread} that pager as it's laid out, which may run the loader its
 * tap runs. A View more replies row reads a flag that makes only its words take a
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

    /** The plugin that writes Reply in the row under a comment, as that socket's name table gives it (#447). */
    public static final String REPLY_PLUGIN =
            "com.facebook.feedback.comments.plugins.commentufi.reply.InlineCommentReplyPlugin";

    /** The plugin that draws Reply as a button on public Pages' comments, in the same name table. */
    public static final String REPLY_BUTTON_PLUGIN =
            "com.facebook.feedback.comments.plugins.commentufi.reply.ReplyPlugin";

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

    /** Counted each time Reply is kept out of a comment's row (#447). */
    static final String REPLY_HIDDEN = "Comment Reply kept out";

    /** The member the report names once the socket of a comment's buttons has asked about Reply. */
    static final String COMMENT_REPLY = "comment reply";

    /** Counted each time a badge like Top fan is kept off a commenter's name (#485). */
    static final String BADGE_HIDDEN = "Commenter badge kept out";

    /** The member the report names once a check of a commenter's name has asked about its badge. */
    static final String COMMENTER_BADGE = "commenter badge";

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

    /** Counted each time a reply thread's next page is loaded without a tap on View more replies. */
    static final String THREAD_LOADS_IN_FULL = "Reply page loaded without a tap";

    /** Counted each time a View more replies row is built to take a tap anywhere on it. */
    static final String WHOLE_ROW_TAPS = "View more replies row takes a tap anywhere";

    /** Counted each time an open reply thread is drawn with a Hide replies row above it. */
    static final String HIDE_ROWS_DRAWN = "Hide replies row drawn";

    /** Counted each time a comment is left out for a link to a shop. */
    static final String SHOP_LINK_HIDDEN = "Comment with a shop link left out";

    /** Counted each time a comment is left out for a phrase from Words to hide. */
    static final String WORDS_HIDDEN = "Comment with your words left out";

    /** Counted each time a comment is left out for not holding the comment search's words. */
    static final String SEARCH_HIDDEN = "Comment without the searched words left out";

    /** Counted each time a count of people typing is answered as nobody. */
    static final String TYPING_HIDDEN = "Incoming typing kept out";

    /** The member the report names once a count of people typing has been through the hook. */
    static final String TYPING = "incoming typing";

    /** Counted each time Facebook's pick of the top-reaction-only row under a comment is answered no. */
    static final String EVERY_REACTION_KEPT = "Comment row with every reaction kept";

    /** The member the report names once Facebook's pick of a comment's reaction row has been through the hook. */
    static final String REACTION_ROW = "comment reaction row";

    /** Counted each time the shared check behind the feed's and the comment list's rows is answered no. */
    static final String EVERY_REACTION_CHECKED = "Comment row check answered with every reaction";

    /** The member the report names once that shared check has been through the hook. */
    static final String REACTION_CHECK = "comment reaction check";

    /** Counted each time the comment list's pick of a row layout is answered with the full row's. */
    static final String EVERY_REACTION_LAID_OUT = "Comment list layout kept with every reaction";

    /** The member the report names once the comment list's row layout has been through the hook. */
    static final String REACTION_LAYOUT = "comment list reaction layout";

    /** Counted each time the older row's cap of one reaction icon beside the count is answered no. */
    static final String EVERY_REACTION_ICON = "Comment row icons kept past one";

    /** The member the report names once that icon cap has been through the hook. */
    static final String REACTION_ICONS = "comment reaction icons";

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

    /** The member the report names once the Shorts viewer has asked about the comment bar it built (#375). */
    static final String SHORTS_BAR = "Shorts viewer comment bar";

    /** The member the report names once the Shorts viewer's root has handed over the bar its pick got back (#375). */
    static final String PICKED_BAR = "Shorts viewer bar pick";

    /** The member the report names once the Shorts viewer has read a video's band through the hook (#375). */
    static final String VIDEO_HEIGHT = "Shorts viewer video height";

    /** Counted each time that video keeps the height the comment bar's band took off it. */
    static final String FULL_HEIGHT = "Video kept its full height";

    /** Counted each time that video and its seek bar take only part of the band, leaving room for a reel's time. */
    static final String TIME_ROOM = "Video left room for the reel's time";

    /**
     * How much of the band, in dp, a Shorts viewer video and its seek bar both take while a reel's
     * time stays up (Keep the progress bar with Show a reel's time). Facebook rests the caption on
     * the video's bottom edge less 34 px or on the seek bar's bottom, whichever is higher, and lifts
     * it 35 dp from there while the seek bar shows. The seek bar goes up by its band, and by the nav
     * bar's inset too while the video runs to the bottom edge (S22 588, 2026-11-23). At the full
     * height the seek bar held the caption, and the time row, which Facebook shows only during a drag
     * when the caption is cleared, sat 63 px into the caption's last line. The video alone at 32 dp
     * cleared the caption but dropped the seek bar onto the gesture handle. The band is the nav bar's
     * inset plus 44 dp, so taking 28 dp of it keeps the seek bar 16 dp higher than at the full height
     * on any phone, where it was before.
     */
    static final int TIME_ROOM_BAND_TAKEN_DP = 28;

    /**
     * How much more of the band, in dp, the video keeps than its seek bar while a reel's time stays
     * up. At the same band the time came 13 px into the caption's box, Facebook's spacing under the
     * comment bar, where its featured-comment line pads the gap, but a plain caption's last line
     * touched the time's digits (S22 588, 2026-11-23). The caption moves with the video's edge alone.
     */
    static final int TIME_ROOM_GAP_DP = 8;

    /** The member the report names once the Shorts viewer has placed its seek bar through the hook (#375). */
    static final String SEEK_BAR = "Shorts viewer seek bar";

    /** Counted each time that seek bar goes down to the bottom with the video. */
    static final String SEEK_BAR_LOWERED = "Seek bar moved down with the video";

    /**
     * How far up, in dp, the Shorts viewer puts its seek bar when it shows no bar under the video:
     * the 3 its root uses on 585 where the viewer has no bottom bar.
     */
    static final int NO_BAR_SEEK_BAR_DP = 3;

    /**
     * Whether the Shorts viewer root rendering on this thread dropped its comment bar while Hide the
     * video comment box was on (#375). Each root render starts it over. Its other bars, such as the
     * reply bar under a reel shared in a chat, stay, and so does their band, so the seek bar follows
     * this, not the switch alone.
     *
     * <p>A pick with no bar to begin with leaves it unset. That's the scroller a video tapped in Home
     * opens, whose list gives the video its full height all the same, but on 588 its caption stays
     * where it was as the video grows (S22, 2026-11-23: "Suckers!" at 1937 to 1978 either way). Taken
     * down to {@link #NO_BAR_SEEK_BAR_DP} there, the seek bar only went under the Up next bar and into
     * the gesture strip (#552), so it keeps Facebook's band. In the viewer a reel from Home's shelf
     * opens, where the comment bar is dropped, the caption does come down with the video.
     */
    private static final ThreadLocal<Boolean> shortsBarDropped = new ThreadLocal<>();

    /**
     * Facebook's full-screen player screens, where the Shorts viewer a reel from Home's shelf opens
     * runs. The scroller a video tapped in Home opens runs in the main screen, and on 588 its root
     * drops the comment bar too when the video is a reel's (S22, 2026-11-23: "Reel by Elisha Elle",
     * caption at 1663 to 1764 with the bar or without), so a drop alone doesn't say the caption came
     * down (#552).
     */
    static final Set<String> SHORTS_SCREENS = Set.of(
            "com.facebook.katana.immersiveactivity.ImmersiveActivity",
            "com.facebook.katana.immersiveactivity.ImmersivePortraitActivity");

    /** The class of the screen created or resumed last, or null before the first. */
    @Nullable
    private static volatile String screenInFront;

    private static boolean watchingScreens;

    /**
     * The Shorts viewer configs whose root last got a bar back from its pick (#375): the reply bar
     * under a reel shared in a chat, the drafts bar, or the comment bar while the switch was off. Held
     * weakly and told apart by identity, since a config's equals may compare contents. A viewer's list
     * sizes its videos with the same config before any root has picked, so a config nothing marked is
     * taken to have no bar, and a viewer keeps its band once its own root kept one, whatever another
     * viewer open beside it picked.
     */
    private static final List<WeakReference<Object>> barConfigs = new ArrayList<>();

    /** The member the report names once the socket over the comment box has asked about the emoji row (#290). */
    static final String EMOJI_ROW = "emoji row over the comment box";

    /** Counted each time the emoji row is kept out from over the comment box. */
    static final String EMOJI_ROW_HIDDEN = "Comment emoji row kept out";

    /** The member the report names once the comment box has set its background (#344). */
    static final String BOX_LINE = "line over the comment box";

    /** The member the report names once the socket over the comment box has loaded its background (#344). */
    static final String SOCKET_LINE = "line over the emoji row";

    /** Counted each time the line along the top of the comment box, or of the socket over it, is taken off. */
    static final String BOX_LINE_HIDDEN = "Comment box line kept out";

    /** Counted each time a comment box is kept off a post's comments (#479). */
    static final String BOX_HIDDEN = "Comment box kept out";

    /** The comment boxes held gone, so a box that sets its background twice as it's built is held once. */
    private static final Set<View> heldBoxes = Collections.newSetFromMap(new WeakHashMap<>());

    /** The member the report names once the socket under a post's comments has asked about Related groups. */
    static final String BOTTOM = "related groups under comments";

    /** The member the report names once the reaction picker has been asked to open. */
    static final String PICKER = "reaction picker";

    /** The member the report names once a comment's state has been set up. */
    static final String REPLY_THREADS = "reply threads";

    /** The member the report names once a View more replies pager under an open thread has been laid out. */
    static final String REPLY_PAGERS = "reply pagers";

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
     * The hook, right after Facebook asks whether the row under a comment should be its newer one,
     * Reply beside the top reaction and its count with Like and the thumbs-down as buttons (#241).
     * With the switch on it answers no, so the row is the one with Like, Reply and every reaction
     * beside the count; otherwise, and for a throw, it hands back what Facebook read.
     */
    public static boolean topReactionOnly(boolean topOnly) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, REACTION_ROW);
            if (!topOnly || !Utils.settingsReady() || !Settings.SHOW_EVERY_COMMENT_REACTION.get()) return topOnly;
            HookStatus.counted(FAMILY, EVERY_REACTION_KEPT);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment reaction row", failure);
            return topOnly;
        }
    }

    /**
     * The hook, right after the check that a feed post's inline comment actions, an open post's
     * comment list and several renders around a comment's row share reads whether the row should be
     * the newer one (#241). With the switch on it answers no, the answer the check gives where its
     * surface is left out, so every row it decides keeps Like, Reply and every reaction; otherwise,
     * and for a throw, it hands back what Facebook read.
     */
    public static boolean topReactionCheck(boolean topOnly) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, REACTION_CHECK);
            if (!topOnly || !Utils.settingsReady() || !Settings.SHOW_EVERY_COMMENT_REACTION.get()) return topOnly;
            HookStatus.counted(FAMILY, EVERY_REACTION_CHECKED);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, REACTION_CHECK, failure);
            return topOnly;
        }
    }

    /**
     * The hook, right after an open post's comment list reads which layout its rows take (#241): 0
     * for the older row, and 1 to 3 for parts of the newer one, where 2 and 3 build it whatever the
     * shared check says. With the switch on it answers 0, the value the list takes where its surface
     * is left out; otherwise, and for 0 or a throw, it hands back what Facebook read.
     */
    public static int topReactionLayout(int layout) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, REACTION_LAYOUT);
            if (layout == 0 || !Utils.settingsReady() || !Settings.SHOW_EVERY_COMMENT_REACTION.get()) return layout;
            HookStatus.counted(FAMILY, EVERY_REACTION_LAID_OUT);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, REACTION_LAYOUT, failure);
            return layout;
        }
    }

    /**
     * The hook, right after the older row under a comment reads whether to keep a single reaction
     * icon beside its count (#241). With the switch on it answers no, so the row keeps three icons,
     * as it does where its surface is left out; otherwise, and for a throw, it hands back what
     * Facebook read.
     */
    public static boolean topReactionIcons(boolean oneIcon) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, REACTION_ICONS);
            if (!oneIcon || !Utils.settingsReady() || !Settings.SHOW_EVERY_COMMENT_REACTION.get()) return oneIcon;
            HookStatus.counted(FAMILY, EVERY_REACTION_ICON);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, REACTION_ICONS, failure);
            return oneIcon;
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
     * The hook, right after the comment box sets its background as it's built (#344). Facebook
     * draws the box as layers: the line's color filling the whole box, and the box's own color over
     * it, pushed down from the top, so the strip left showing is the line. While the switch is on,
     * the box's layer goes back to the top and the layers under it stop drawing, on this box's own
     * copy of the drawable. Any other background, off, paused, or before the settings are ready,
     * the box stays as Facebook drew it.
     *
     * <p>While Hide the comment box is on (#479), the box is held gone for as long as it lives
     * ({@link #holdGone}), whatever its background.
     */
    public static void flattenCommentBox(@Nullable View box) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, BOX_LINE);
            if (box == null || !Utils.settingsReady()) return;
            if (Settings.HIDE_COMMENT_BOX.get() && holdGone(box)) HookStatus.counted(FAMILY, BOX_HIDDEN);
            if (!Settings.HIDE_COMMENT_BOX_LINE.get()) return;
            if (flatten(box.getBackground(), "box")) HookStatus.counted(FAMILY, BOX_LINE_HIDDEN);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BOX_LINE, failure);
        }
    }

    /**
     * Holds [box] gone for as long as it lives (#479): gone now, and gone again before any frame
     * that would draw it, since Facebook shows the box again on its own (a tap on Reply, a
     * comment posted, the sheet growing). False when this box was already held. Turning the
     * switch off lets go at the next frame, and the box comes back on the next comments built.
     */
    static boolean holdGone(View box) {
        return holdGone(box, Settings.HIDE_COMMENT_BOX, BOX_LINE);
    }

    /**
     * {@link #holdGone(View)} for any view, held while [setting] is on, with a throw counted on
     * [where]. The live video comment bar (#537) is held the same way.
     */
    static boolean holdGone(View box, BooleanSetting setting, String where) {
        if (!heldBoxes.add(box)) return false;
        box.setVisibility(View.GONE);
        BoxHold hold = new BoxHold(box, setting, where);
        box.addOnAttachStateChangeListener(hold);
        if (box.isAttachedToWindow()) hold.onViewAttachedToWindow(box);
        return true;
    }

    /** Puts a held view back to gone before each frame of the window it's in, while its switch is on. */
    private static final class BoxHold implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> held;
        private final BooleanSetting setting;
        private final String where;
        @Nullable
        private ViewTreeObserver tree;

        BoxHold(View box, BooleanSetting setting, String where) {
            held = new WeakReference<>(box);
            this.setting = setting;
            this.where = where;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            stop();
            tree = view.getViewTreeObserver();
            tree.addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stop();
        }

        private void stop() {
            if (tree != null && tree.isAlive()) tree.removeOnPreDrawListener(this);
            tree = null;
        }

        /** False skips a frame that would have drawn the box, and the next one draws without it. */
        @Override
        public boolean onPreDraw() {
            try {
                View box = held.get();
                if (box == null) {
                    stop();
                    return true;
                }
                if (box.getVisibility() == View.GONE || !Utils.settingsReady() || !setting.get()) return true;
                box.setVisibility(View.GONE);
                return false;
            } catch (Throwable failure) {
                stop();
                HookStatus.threw(FAMILY, where, failure);
                return true;
            }
        }
    }

    /**
     * The hook, right after the socket over the comment box's text field, the one holding the emoji
     * row and Meta AI's pills, loads the drawable it draws behind itself (#344). It's the same
     * layers as the box's, so the line shows along the top of the socket when the socket is there.
     * While the switch is on, the socket's own copy gets the same treatment as the box's; it's
     * handed back either way, and as it came off, paused, before the settings are ready or on a
     * throw.
     */
    @Nullable
    public static Drawable flattenSocketLine(@Nullable Drawable background) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SOCKET_LINE);
            if (background == null || !Utils.settingsReady() || !Settings.HIDE_COMMENT_BOX_LINE.get()) return background;
            if (flatten(background, "socket")) HookStatus.counted(FAMILY, BOX_LINE_HIDDEN);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SOCKET_LINE, failure);
        }
        return background;
    }

    /**
     * Takes the line off [background], a line-colored layer under the top layer pushed down from
     * the top: the top layer goes back to the top and the layers under it stop drawing, on
     * [background]'s own copy. True when it did; any other drawable is left as it is.
     */
    static boolean flatten(@Nullable Drawable background, String whose) {
        if (!(background instanceof LayerDrawable)) {
            Logger.printDebug(() -> "Comment box line: the " + whose + "'s background is "
                    + (background == null ? "nothing" : background.getClass().getName()) + ", left as it is");
            return false;
        }
        LayerDrawable layers = (LayerDrawable) background;
        int top = layers.getNumberOfLayers() - 1;
        if (top < 1 || layers.getLayerInsetTop(top) <= 0) {
            Logger.printDebug(() -> "Comment box line: the " + whose + "'s " + (top + 1) + " layers leave no strip at the top");
            return false;
        }
        layers.mutate();
        layers.setLayerInsetTop(top, 0);
        for (int layer = 0; layer < top; layer++) layers.getDrawable(layer).setAlpha(0);
        return true;
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

    /** The hook, first thing in the Shorts viewer root's render (#375): this render hasn't dropped a bar yet. */
    public static void pickingShortsBar() {
        shortsBarDropped.remove();
    }

    /**
     * The hook right before the Shorts viewer a feed video opens in hands back the comment bar it
     * built for the bottom of the screen (#375). True hands back null instead while Hide the video
     * comment box is on, and the video and its seek bar then take the bar's band; false keeps the bar.
     */
    public static boolean dropsShortsCommentBar() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SHORTS_BAR);
            if (!Utils.settingsReady() || !Settings.HIDE_VIDEO_COMMENT_COMPOSER.get()) return false;
            HookStatus.counted(FAMILY, COMPOSER_HIDDEN);
            shortsBarDropped.set(Boolean.TRUE);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SHORTS_BAR, failure);
            return false;
        }
    }

    /**
     * The hook right after the Shorts viewer's root gets its bottom bar back from the pick (#375),
     * with that bar, null where it shows none or the comment bar was dropped, and the viewer's config.
     * A bar marks the config, so the viewer's videos keep Facebook's band from their next layout on;
     * no bar clears the mark. Whether the seek bar goes down is up to {@link #dropsShortsCommentBar}.
     */
    public static void keptShortsBar(@Nullable Object bar, @Nullable Object config) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, PICKED_BAR);
            if (config != null) markKeepsBar(config, bar != null);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, PICKED_BAR, failure);
        }
    }

    /**
     * Called once per Facebook start, from the settings entry's onApplicationCreate, with Comment
     * sheet options in the build: from then on each screen that's created or resumed is the one in
     * front, for {@link #shortsSeekBarBand}.
     */
    public static synchronized void watch(Context context) {
        try {
            if (watchingScreens || !(context instanceof Application)) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Screens());
            watchingScreens = true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SEEK_BAR, failure);
        }
    }

    /** Records the class of the screen in front. */
    static void inFront(@Nullable String screen) {
        screenInFront = screen;
    }

    /** Whether the screen in front is a full-screen player, where the Shorts viewer runs. */
    static boolean shortsScreenInFront() {
        String screen = screenInFront;
        return screen != null && SHORTS_SCREENS.contains(screen);
    }

    /**
     * Whether the band stays Facebook's for the UP NEXT pill: in the Shorts viewer, while Hide Up
     * next is off. Facebook lays that pill over the comment bar, in its band (S22 588, 2026-11-23:
     * "Add a comment" at 2105 to 2274 under "UP NEXT:" at 2147, the seek bar at 2048 to 2166 above
     * both). Given to the video, the band took the caption down and the seek bar under the pill
     * (#552), so the video, its caption and its seek bar stay where Facebook puts them, with the
     * pill where the comment bar was.
     */
    private static boolean bandKeptForUpNext() {
        return shortsScreenInFront() && !Settings.HIDE_UP_NEXT.get();
    }

    /**
     * Whether a reel's time stays up over the seek bar in the Shorts viewer, which needs part of the
     * band ({@link #TIME_ROOM_BAND_TAKEN_DP}). The scroller a video tapped in Home opens keeps its
     * caption where it was whatever the video's height, so there the video takes the whole band.
     */
    private static boolean timeRowKept() {
        return shortsScreenInFront() && Settings.KEEP_PROGRESS_BAR.get() && Settings.KEEP_PROGRESS_BAR_TIME.get();
    }

    /** The band, in dp, the seek bar keeps while {@link #timeRowKept}. */
    static int timeRoomBand(int band) {
        return Math.max(NO_BAR_SEEK_BAR_DP, band - TIME_ROOM_BAND_TAKEN_DP);
    }

    /** The band, in dp, the video keeps while {@link #timeRowKept}: its seek bar's and the gap, at most Facebook's. */
    static int timeRoomVideoBand(int band) {
        return Math.min(band, timeRoomBand(band) + TIME_ROOM_GAP_DP);
    }

    /** Keeps the screen in front up to date. */
    static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
            inFront(activity.getClass().getName());
        }

        @Override
        public void onActivityResumed(Activity activity) {
            inFront(activity.getClass().getName());
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    /**
     * The hook on each band height, in dp, the Shorts viewer's root places its seek bar and time
     * above (#375). Once this render dropped the comment bar while the switch is on and a full-screen
     * player is in front they go down with the video to {@link #NO_BAR_SEEK_BAR_DP}, where Facebook
     * puts them with no bar, instead of staying over the caption, or to the band the video keeps while
     * a reel's time stays up ({@link #timeRoomBand}). Otherwise the band is Facebook's: a
     * pick with no bar of its own, the scroller a video tapped in Home opens, whose caption stays put
     * with the bar gone or not, and a viewer whose band the UP NEXT pill keeps (#552).
     */
    public static int shortsSeekBarBand(int band) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SEEK_BAR);
            if (!Boolean.TRUE.equals(shortsBarDropped.get()) || !shortsScreenInFront() || !Utils.settingsReady()
                    || !Settings.HIDE_VIDEO_COMMENT_COMPOSER.get() || bandKeptForUpNext()) return band;
            HookStatus.counted(FAMILY, SEEK_BAR_LOWERED);
            return timeRowKept() ? timeRoomBand(band) : NO_BAR_SEEK_BAR_DP;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SEEK_BAR, failure);
            return band;
        }
    }

    /**
     * The hook on the band height, in dp, the Shorts viewer's list takes off each video's full height
     * (#375), with the viewer's config. It sizes the videos before any of their roots has picked a
     * bar, so while Hide the video comment box is on the video keeps its full height and 0 comes back,
     * unless the config's root kept a bar ({@link #keptShortsBar}) or the UP NEXT pill keeps the band
     * ({@link #bandKeptForUpNext}). While a reel's time stays up the video takes only part of it
     * ({@link #timeRowKept}). A list handed no config keeps Facebook's band.
     */
    public static int shortsVideoBand(int band, @Nullable Object config) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, VIDEO_HEIGHT);
            if (config == null || !Utils.settingsReady() || !Settings.HIDE_VIDEO_COMMENT_COMPOSER.get()
                    || keepsBar(config) || bandKeptForUpNext()) return band;
            if (timeRowKept()) {
                HookStatus.counted(FAMILY, TIME_ROOM);
                return timeRoomVideoBand(band);
            }
            HookStatus.counted(FAMILY, FULL_HEIGHT);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, VIDEO_HEIGHT, failure);
            return band;
        }
    }

    /** Keeps whether {@code config}'s root got a bar back, letting go of configs no longer held. */
    private static void markKeepsBar(Object config, boolean keeps) {
        synchronized (barConfigs) {
            boolean held = false;
            for (Iterator<WeakReference<Object>> each = barConfigs.iterator(); each.hasNext(); ) {
                Object kept = each.next().get();
                if (kept == null || (kept == config && !keeps)) each.remove();
                else if (kept == config) held = true;
            }
            if (keeps && !held) barConfigs.add(new WeakReference<>(config));
        }
    }

    /** Whether {@code config}'s root last got a bar back. */
    private static boolean keepsBar(Object config) {
        synchronized (barConfigs) {
            for (WeakReference<Object> each : barConfigs) {
                if (each.get() == config) return true;
            }
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
     * The hook, first thing in the check of the buttons under a comment, handed the plugin's name.
     * True answers no for either Reply plugin while Hide Reply on comments is on; false leaves the
     * check to Facebook, for Like, Dislike, the reaction icons and every other plugin, and otherwise
     * (#447). View replies is drawn by a section of its own, so it stays.
     */
    public static boolean holdsCommentReply(@Nullable String plugin) {
        try {
            HookStatus.invoked(FAMILY);
            if (!REPLY_PLUGIN.equals(plugin) && !REPLY_BUTTON_PLUGIN.equals(plugin)) return false;
            HookStatus.bound(FAMILY, COMMENT_REPLY);
            if (!Utils.settingsReady() || !Settings.HIDE_COMMENT_REPLY_BUTTON.get()) return false;
            HookStatus.counted(FAMILY, REPLY_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, COMMENT_REPLY, failure);
            return false;
        }
    }

    /**
     * The hook, first thing in each check that decides what goes by a commenter's name, asked only
     * when the check is about the badge plugin (#485). True answers no for that badge, Top fan,
     * Subscriber and the rest, while Hide badges like Top fan on comments is on; false leaves it to
     * Facebook. The name, the time and the comment come through other plugins, so they stay.
     */
    public static boolean hidesCommenterBadge() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, COMMENTER_BADGE);
            if (!Utils.settingsReady() || !Settings.HIDE_COMMENTER_BADGES.get()) return false;
            HookStatus.counted(FAMILY, BADGE_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, COMMENTER_BADGE, failure);
            return false;
        }
    }

    /**
     * The hook, first thing where a long press on Like opens the reaction picker. True returns
     * before the picker opens while Like only is on; false lets it open, as it does for the tray
     * Hold to react opens.
     */
    public static boolean skipReactionPicker() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, PICKER);
            if (!Utils.settingsReady() || !Settings.LIKE_ONLY.get()) return false;
            // Hold to react asks for the tray, so the one it opens shows all the same.
            if (ReactionHold.opening()) return false;
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
     * The hook, first thing as the View more replies pager under an open reply thread is laid out,
     * which Facebook does as the pager comes near the screen. The pager keeps the loader its tap
     * runs. While Load whole reply threads is on, that loader runs on the main thread, the way a
     * tap would, so the thread's next page comes in with no tap, and the next pager does the same
     * as the reader scrolls on (#140). It runs once per page, however often the pager is laid out
     * again before the page arrives, and never for a pager under a post's own comments, which
     * stays a tap away. Off, paused or cold, the pager is left to Facebook.
     *
     * <p>Up to 0.24.0 this switch made the list page on by itself instead, but on Facebook 588 a
     * reply list told to do that drops the pager and never loads its next page, so a long thread
     * stopped after its first page with nothing to tap (#536).
     */
    public static void loadRestOfThread(Object pager) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, REPLY_PAGERS);
            if (pager == null || !Utils.settingsReady() || !Settings.LOAD_WHOLE_REPLY_THREADS.get()) return;
            Runnable nextPage = nextPage(pager);
            if (nextPage == null) return;
            HookStatus.counted(FAMILY, THREAD_LOADS_IN_FULL);
            Utils.runOnMainThread(nextPage);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reply pager", failure);
        }
    }

    /** The pages whose loader already ran, each by the list it loads on from. */
    private static final Set<Object> pagesAsked = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * The run of {@code pager}'s loader for a thread's next page, or null. The loader is the value
     * of one of the pager's fields: a lambda whose captures keep their names, the list it pages on
     * from in {@code $paginableList}, how deep it sits in {@code $depth} and what it loads the page
     * through in {@code $modelAdapter}, with an {@code invoke()} that loads one page. The pager's
     * other lambda, which logs that it was seen (588 {@code LX/0Vm6}), names the list and depth
     * too but loads nothing, so it has no model adapter. Depth 0 is a post's own comments, which are left to
     * the pager. Null too once that list's page was asked for, so a pager laid out again before
     * its page arrives asks nothing more.
     */
    @Nullable
    static Runnable nextPage(Object pager) throws ReflectiveOperationException {
        for (Class<?> type = pager.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (field.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object loader = field.get(pager);
                if (loader == null) continue;
                java.lang.reflect.Field list = capture(loader.getClass(), "$paginableList");
                java.lang.reflect.Field depth = capture(loader.getClass(), "$depth");
                if (list == null || depth == null || depth.getType() != int.class) continue;
                if (capture(loader.getClass(), "$modelAdapter") == null) continue;
                java.lang.reflect.Method invoke = loader.getClass().getMethod("invoke");
                if (depth.getInt(loader) < 1) return null;
                Object page = list.get(loader);
                synchronized (pagesAsked) {
                    if (!pagesAsked.add(page == null ? loader : page)) return null;
                }
                return () -> {
                    try {
                        invoke.invoke(loader);
                    } catch (Throwable failure) {
                        HookStatus.threw(FAMILY, "reply page", failure);
                    }
                };
            }
        }
        return null;
    }

    /** The field {@code name} that {@code type} declares, opened for reading, or null. */
    @Nullable
    private static java.lang.reflect.Field capture(Class<?> type, String name) {
        try {
            java.lang.reflect.Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException absent) {
            return null;
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
     * Words to hide and none from the keep list, or while the comment search box holds words they
     * aren't in (Show only comments that match). False draws it as Facebook does, and so does
     * anything this can't read.
     */
    public static boolean hidesComment(@Nullable Object comment) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, COMMENTS);
            if (!Utils.settingsReady()) return false;
            boolean links = Settings.HIDE_SHOP_LINK_COMMENTS.get();
            boolean words = Settings.HIDE_COMMENTS_WITH_WORDS.get();
            boolean search = Settings.FILTER_COMMENT_SEARCH.get();
            if (!links && !words && !search) return false;
            String text = CommentText.body(comment);
            // First, so every comment drawn is noted under the search words in use.
            if (search && CommentSearch.filtersOut(comment, text)) {
                HookStatus.counted(FAMILY, SEARCH_HIDDEN);
                return true;
            }
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
