/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.summaries.POST_SOCKET
import app.morphe.patches.facebook.comments.summaries.PluginSocket
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.comments.summaries.isNameTable
import app.morphe.patches.facebook.comments.summaries.switchKeys
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.reels.gesture.COMPOSER_ACTION
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.namedRegisters
import app.morphe.util.readsAfter
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Comment sheet options"

/** The name the comment box's attachment button socket gives itself, in the method that goes through its plugins. */
internal const val BUTTON_SOCKET = "CommentComposerAttachmentButtonSocket"

/** The comment box's GIF button, a kept class name the socket's name table loads. */
internal const val GIF_BUTTON =
    "com.facebook.feedback.comments.plugins.commentcomposer.attachmentbutton.gif.GifAttachmentButtonPlugin"

/** The comment box's sticker button, in the same name table. */
internal const val STICKER_BUTTON =
    "com.facebook.feedback.comments.plugins.commentcomposer.attachmentbutton.sticker.StickerAttachmentButtonPlugin"

/** The name the socket over the comment box's text field gives itself, in its render (#290). */
internal const val PRE_INPUT_SOCKET = "CommentComposerPreInputContentSocket"

/** The row of emoji over the comment box, a kept class name the same render loads. */
internal const val EMOJI_ROW =
    "com.facebook.feedback.comments.plugins.commentcomposerpreinput.conversationguide.ConversationGuidePlugin"

/** Meta AI's suggestion pills, the socket's other plugin, a kept class its applies check calls. */
internal const val META_AI_PILLS =
    "Lcom/facebook/feedback/comments/plugins/commentcomposerpreinput/metaaisuggestionpills/MetaAISuggestionPillsPlugin;"

/** The name the comment bar under a video in the full-screen viewer gives its sticker button, where it builds it. */
internal const val VIEWER_STICKER = "FbShortsComposerAttachmentComponentSpec_STICKER"

/** The name the same bar gives its GIF button, up to 584. */
internal const val VIEWER_GIF = "FbShortsComposerAttachmentComponentSpec_GIF"

/**
 * The flyout composer action the same bar hands its GIF button's builder, a constant of the kept
 * enum [COMPOSER_ACTION]. 585 names that button by a string resource instead of [VIEWER_GIF].
 */
internal const val VIEWER_GIF_ACTION = "GIF_PICKER"

/** The transition key the whole of that bar, the Add a comment box and its buttons, is laid out under, in its render. */
internal const val VIDEO_COMMENT_BAR = "comment_bar_layout_transition_key"

/** The name the socket under a post's comments gives itself, in the methods that go through its plugins. */
internal const val BOTTOM_SOCKET = "FeedbackSurfaceBottomContentSocket"

/** The Related groups list under a group post's comments, a kept class name the socket's name table loads. */
internal const val RELATED_GROUPS =
    "com.facebook.feedback.comments.plugins.bottomcontent.impl.relatedgroups.RelatedGroupsPlugin"

/**
 * The few comments a feed post shows under its buttons, a kept class name the name table of the
 * socket under those buttons ([POST_SOCKET]) loads, next to Meta AI's inline summary (#253).
 */
internal const val INLINE_COMMENTS =
    "com.facebook.feed.plugins.belowufifooter.impl.inlinecommentwithreactivex.InlineCommentWithReactiveXPlugin"

/** The same inline comments picked by how long the post stayed on screen, in the same name table. */
internal const val INLINE_COMMENTS_DWELL_TIME =
    "com.facebook.feed.plugins.belowufifooter.impl.inlinecommentwithdwelltime.InlineCommentWithDwellTimePlugin"

/** The same inline comments ranked on the phone, in the same name table. */
internal const val INLINE_COMMENTS_CLIENT_RANKING =
    "com.facebook.feed.plugins.belowufifooter.impl.inlinecommentwithclientranking.InlineCommentWithClientRankingPlugin"

/** The plugin that builds the thumbs-down under a comment, next to Like, a kept class (#241). */
internal const val COMMENT_DOWNVOTE =
    "Lcom/facebook/feedback/comments/plugins/commentufi/commentsdownvote/CommentsUFIInlineDownvotePlugin;"

/** The plugin that writes Like in the row under a comment, a kept class (#241). */
internal const val COMMENT_LIKE =
    "Lcom/facebook/feedback/comments/plugins/commentufi/like/InlineCommentLikePlugin;"

/** The name the reaction picker's popup logs itself under, in the method that opens it. */
internal const val DOCK_NAME = "reactions_dock"

/** The reaction picker the Like button's long press opens, a kept class. */
internal const val UFI_DOCK = "Lcom/facebook/feedback/sharedcomponents/reactions/dock/RopeStyleUFIDockView;"

/**
 * The name the comment section gives itself in its constructor. It draws one comment and, under
 * it, either the collapsed View replies row or the open reply thread.
 */
internal const val COMMENT_SECTION = "CommentSection"

/** The state update a tap on View replies sends the comment section, named where it's sent. */
internal const val EXPAND_REPLIES = "updateState:CommentSection.expandReplySection"

/**
 * The name an open reply thread's section gives itself in its constructor. Once its replies are
 * fetched, it builds a [COMMENT_LIST] over them.
 */
internal const val EXPANDED_REPLIES = "ExpandedReplySection"

/** The name the list of a post's comments, or of a thread's replies, gives itself in its constructor. */
internal const val COMMENT_LIST = "CommentListSection"

/** The name the View more replies row gives its render, a string in it. */
internal const val VIEW_MORE_REPLIES = "ViewMoreRepliesComponent.render"

/**
 * The name the View more replies row under a feed post's inline comments gives itself, in its
 * constructor, its render and one other method.
 */
internal const val FEED_VIEW_MORE_REPLIES = "FeedViewMoreRepliesComponent"

private const val VIEW = "Landroid/view/View;"

private const val OBJECTS = "[Ljava/lang/Object;"

internal const val COMMENT_SHEET_OPTIONS = "$EXTENSION_PACKAGE/comments/CommentSheetOptions;"
internal const val HOLDS_BUTTON = "$COMMENT_SHEET_OPTIONS->holdsButton(Ljava/lang/String;)Z"
internal const val HOLDS_EMOJI_ROW = "$COMMENT_SHEET_OPTIONS->holdsEmojiRow()Z"
internal const val HOLDS_VIEWER_BUTTONS = "$COMMENT_SHEET_OPTIONS->holdsViewerButtons()Z"
internal const val HIDES_VIDEO_COMMENT_COMPOSER = "$COMMENT_SHEET_OPTIONS->hidesVideoCommentComposer()Z"
internal const val HOLDS_BOTTOM_CONTENT = "$COMMENT_SHEET_OPTIONS->holdsBottomContent(Ljava/lang/String;)Z"
internal const val HOLDS_POST_COMMENTS = "$COMMENT_SHEET_OPTIONS->holdsPostComments(Ljava/lang/String;)Z"
internal const val HOLDS_COMMENT_DISLIKE = "$COMMENT_SHEET_OPTIONS->holdsCommentDislike()Z"
internal const val HOLDS_COMMENT_LIKE = "$COMMENT_SHEET_OPTIONS->holdsCommentLike()Z"
internal const val SKIP_PICKER = "$COMMENT_SHEET_OPTIONS->skipReactionPicker()Z"
internal const val OPEN_REPLY_THREADS = "$COMMENT_SHEET_OPTIONS->openReplyThreads(Z)Z"
internal const val HIDES_COMMENT = "$COMMENT_SHEET_OPTIONS->hidesComment(Ljava/lang/Object;)Z"
internal const val LOAD_WHOLE_REPLY_THREADS = "$COMMENT_SHEET_OPTIONS->loadWholeReplyThreads(Z)Z"
internal const val VIEW_MORE_REPLIES_TEXT_ONLY = "$COMMENT_SHEET_OPTIONS->viewMoreRepliesTextOnly(Z)Z"

/** Facebook's comment model, a kept class the comment section casts its comment to. */
internal const val GRAPHQL_COMMENT = "Lcom/facebook/graphql/model/GraphQLComment;"

/**
 * Four switches for comments and reactions, all off until turned on.
 *
 * Hide GIF and sticker buttons: the comment box draws its buttons through a plugin socket, the one
 * that names itself [BUTTON_SOCKET] in the method going through its plugins (581 `LX/A4X;->A0I`). A
 * static (I)String name table turns a plugin's number into its class name (581 `LX/A4X;->A0J`), and
 * a static check that the socket calls with the plugin's number last decides whether the button
 * shows (581 `LX/2Ap;->A25`). Unlike the comment summaries' sockets, the check sits in another
 * class, so it's found as the one static boolean the socket calls with an int last and a switch
 * over the table's numbers. The extension goes first in it with the plugin's name, and while the
 * switch is on the GIF and sticker buttons get a no. Photo, mention and every other button stay.
 * The comment bar under a video in the full-screen viewer (fb://watch) is its own component and
 * never asks that socket. Its render (582 `LX/AET;->render`) builds the mention, sticker and GIF
 * buttons one after another, the last two through a helper handed [VIEWER_STICKER] and
 * [VIEWER_GIF], then passes all three to a builder whose child list skips a missing one (582
 * `LX/9xu;->A1H` into `LX/1bP;->A00`). Once the GIF button is built the extension is asked, and
 * while the switch is on both buttons' registers are cleared, so the bar keeps only mention.
 * 585 (`LX/09gL;->render` arm64, `LX/09gQ;->render` armv7) hands its helper (`A0B`) each button's
 * [COMPOSER_ACTION] constant as well and names only the sticker: the GIF button's label is a string
 * resource (0x7f1413dc read through `LX/02Hq;->A08`), so its constant, [VIEWER_GIF_ACTION] (`A02`
 * there), picks it out, and the children go to `LX/01U2;->A00`, which still skips a missing one.
 *
 * Hide the video comment box (#213): those buttons are only the box's inner row. The whole bar is
 * another component, the one class naming [VIDEO_COMMENT_BAR] that builds the row's class (583
 * `LX/98j;`, whose `A0G` builds `LX/ALy`). First thing in its render the extension is asked, and
 * while the switch is on the render returns nothing, so the box is gone with its outline.
 *
 * Hide related groups: what comes after a post's comments is drawn through another socket, the one
 * that names itself [BOTTOM_SOCKET] in the two comment surfaces' methods going through its plugins
 * (581 `LX/Aco;->A0j` and `LX/BIE;->A0j`, 580 `LX/B8E;->A1S` and `LX/BLp;->A1S`, 577 `LX/AeD;->A3Y`
 * and `LX/BLW;->A3Y`). Its name table (581 `LX/2Ap;->A0p`) names [RELATED_GROUPS], the Related
 * groups list with its Join buttons that a group post gets under its comments (#101), and its check
 * (581 `LX/2Ap;->A24`) sits in the table's class here. It's found the same way as the button check
 * and hooked the same way: while the switch is on the Related groups plugin gets a no, and the
 * socket goes on to its next plugin. Comments, the comment box and the other plugins stay.
 *
 * Hide comments under posts (#253): the few comments a feed post shows under its buttons come
 * through the socket that names itself [POST_SOCKET], the one Hide Meta AI comment summaries asks
 * about its inline summary. Its name table (584 `LX/228;->A0k`) names three variants of them,
 * [INLINE_COMMENTS], [INLINE_COMMENTS_DWELL_TIME] and [INLINE_COMMENTS_CLIENT_RANKING], and its
 * check sits in the table's class. It's found and hooked the same way as the other two sockets:
 * while the switch is on the three get a no, and the socket goes on to its next plugin, so Meta
 * AI's summary switch and the post's Comment button stay as they were. The same table names three
 * comment boxes (584 `InlineCommentComposerPlugin`, its hoisted and its anonymous variants), and
 * in 584 they draw the post's pinned comment above the box (#305), so the extension gives them the
 * same no; the list of what it holds lives there, not here.
 *
 * Hide dislike on comments (#241): the thumbs-down under a comment is built by its own plugin,
 * [COMMENT_DOWNVOTE], a kept class with one static builder that takes the comment's context and
 * props and returns the button's component, or nothing when Facebook has none to show (584
 * `A00(LX/6u7;LX/4Fk;)LX/97H;`). The extension goes first in it, and while the switch is on the
 * builder returns nothing, so the socket of a comment's buttons (584 `LX/4Fu;->A03`) still counts
 * the plugin as present and draws Like, Reply, the reaction icons and their count as it does next
 * to a thumbs-down. 0.19.0 answered no for the plugin in that socket's check instead (584
 * `LX/228;->A2C`), and the reaction icons and count went with the thumbs-down.
 *
 * Hide Like on comments (#241): Like in that row is written by one of two plugins. The kept
 * [COMMENT_LIKE] has one static builder that takes the comment's context and answers the Like
 * text, or nothing when the comment has none to like (585 arm64 `A00(LX/07T7;)Landroid/text/Spannable;`).
 * The other, `commentufi.like.LikePlugin`, draws Like as a button beside the thumbs-down on public
 * Pages' comments, and its builder is folded into the plugin table's own (585 `LX/01xX;->A00`).
 * Both write Like through one method, the SpannableString one the kept builder calls with the
 * comment (585 `LX/05Mi;->A03`), and that's what's held, so the row keeps Reply, the reaction icons
 * and their count either way. Holding the kept builder alone left the button beside the thumbs-down.
 *
 * Like only: a long press on Like opens the reaction picker through one method that builds
 * [UFI_DOCK] in a popup and logs [DOCK_NAME] (581 `LX/3FO;->A06`, each an instance (View, View)V
 * method). The extension goes first there, and while the switch is on the method returns before
 * anything opens, so a tap on Like still likes.
 *
 * Open every reply thread: the comment section, which names itself [COMMENT_SECTION] in its
 * constructor (581 `LX/AeN`), keeps whether its reply thread is open in a boolean of its state
 * class (581 `LX/AeO;->A06`). Its initial-state method writes it false (581 `A0n`), a tap on View
 * replies sends the [EXPAND_REPLIES] update that sets it true, and the method drawing the comment's
 * children reads it: open draws the ExpandedReplySection, which loads the thread through its own
 * paginated replies list, and closed draws the CollapsedReplySection with the View replies row. The
 * flag is found through the update: the number the update is built with picks the arm of the
 * state's update switch, and that arm's first boolean write is the flag. Just before the
 * initial-state method returns, the extension is handed the flag and its answer is written back, so
 * with the switch on every comment starts the way a tap on View replies leaves it.
 *
 * Load whole reply threads (#140): once an open thread's replies are fetched, the section that
 * names itself [EXPANDED_REPLIES] (582 `LX/BEb;->A2G`) builds a [COMMENT_LIST] over them and writes
 * one boolean of it, false (582 `LX/A4r;->A0Q`). That flag is whether the list pages on by itself:
 * true, its paginated section loads the next page each time the end of the list comes into view,
 * the way Facebook's own comment lists do, and the View more replies pager isn't drawn; false, the
 * pager is drawn and each tap on it loads one more page. The extension is handed the flag just
 * before it's written, and while the switch is on its answer is true, so the rest of a thread loads
 * as it's scrolled with no further taps. It loads a page at a time, as far as the reader scrolls,
 * so a thread with thousands of replies can't hold the sheet up. 585 (`LX/0A8U;->A0p` writing
 * `LX/0A8C;->A0W` on arm64) still writes false, copied from a high register right before the write
 * (`const/16 v17, 0` near the top, `move/from16 v1, v17`), so the same finder and hook hold.
 *
 * Tap anywhere on View more replies (#219): the row's render names [VIEW_MORE_REPLIES] (584
 * `LX/BbN;->render`). It reads one server-set boolean (584 `LX/4EB;->A09` into v16) and builds its
 * tap target with one static call on either side of a check of it (584 `LX/2C8;->A01`). True, the
 * call wraps only the row's icon and words, which then sit in a plain row, so a tap past the words
 * does nothing. False, the plain row is built first and the call wraps all of it, and the tap
 * target puts its click straight on that row's own node (584 `LX/2C9;->A1e`). Both sides hand the
 * same load-more handler (584 `LX/CiR`) the tap. The same boolean also picks a few paddings and the row's height. Right after Facebook
 * reads it, the extension is handed it, and while the switch is on the answer is false, so the
 * row is built the way Facebook builds it for everyone the flag is off for.
 * The row under a feed post's inline comments names [FEED_VIEW_MORE_REPLIES] (584 `LX/5yr;->A0l`)
 * and reads the same boolean (into v21). True, it wraps the icon and words in the tap target and
 * finishes the outer row bare; false, it finishes the row and wraps all of it. The call on either
 * side of that check is the row's finishing call (584 `LX/4B4;->A0K`), the words' side again first,
 * so the same search finds it and the same hook goes in.
 * The View more replies pager under an open reply thread, the one with the down arrow, is another
 * component again (584 `LX/DXS;->render`), drawn by the thread's [COMMENT_LIST] and naming nothing.
 * It reads the same boolean (into v21): true, it wraps the arrow and words in its tap target and sets
 * that in a plain row; false, the tap target is the whole padded row. It's found as the one thing
 * the list makes whose render asks that answer, and the same hook goes in after it (#219, where the
 * switch reached View 7 replies but not this row).
 *
 * Hide comments with shop links and Hide comments with your words (#118): the same comment section
 * draws its one comment, with its replies, in the one method of it besides the constructor that
 * names [COMMENT_SECTION] (582 `LX/5mz;->A2F`). Its comment sits in a props holder kept in one field
 * of the section (582 `A05:LX/2Z5;`), whose Object field the method reads and casts to
 * [GRAPHQL_COMMENT]. First thing there, the extension is handed that comment, and a yes returns
 * no children, as Facebook's own comment list section does when it has nothing to draw.
 *
 * Hide who is typing in comments (#170) and Hide the new comments pop-up (#185) are nameless patches
 * this one carries, [incomingCommentTypingPatch] and [newCommentsPillPatch].
 *
 * In the default selection with every switch off, so nothing changes until one is turned on.
 */
@Suppress("unused")
val commentSheetOptionsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Comment sheet options",
    description = "Small fixes for comments. Stop a long press on Like from opening reactions, hide the GIF and " +
        "sticker buttons, show every reply without tapping View replies, load a whole reply thread as you " +
        "scroll, open more replies with a tap anywhere on their row, hide the comments under feed posts, hide the thumbs-down on comments or turn it into a Block button, hide Like on comments, hide Related groups under group posts, hide comments with shop links or your words, hide who is typing and the new comments pop-up, hide the emoji row over the comment box, and hide the comment box under full-screen videos. Starts off. Turn on the parts you want in " +
        "Hushfacebook settings > Comments and writing.",
) {
    category("Comments")
    dependsOn(settingsPatch, facebookExtensionPatch, incomingCommentTypingPatch, newCommentsPillPatch, commentBlockPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        requireStatusMethod("commentSheetOptions")
        val buttons = buttonSocket(
            holders(GIF_BUTTON),
            holders(BUTTON_SOCKET).flatMap { methodsHolding(it, BUTTON_SOCKET) },
        ) { types -> types.mapNotNull { classDefByOrNull(it) }.associateBy { it.type } }
        val bottom = bottomSocket(
            holders(RELATED_GROUPS),
            holders(BOTTOM_SOCKET).flatMap { methodsHolding(it, BOTTOM_SOCKET) },
        ) { types -> types.mapNotNull { classDefByOrNull(it) }.associateBy { it.type } }
        if (bottom.check.descriptor() == buttons.check.descriptor()) {
            refuse("the comment box and the bottom of the comments lead to one check, ${bottom.check.descriptor()}")
        }
        val post = postSocket(
            holders(INLINE_COMMENTS),
            holders(POST_SOCKET).flatMap { methodsHolding(it, POST_SOCKET) },
        ) { types -> types.mapNotNull { classDefByOrNull(it) }.associateBy { it.type } }
        if (post.check.descriptor() == buttons.check.descriptor() || post.check.descriptor() == bottom.check.descriptor()) {
            refuse("the socket under a post's buttons leads to another socket's check, ${post.check.descriptor()}")
        }
        val dislike = dislikeBuilder(classDefByOrNull(COMMENT_DOWNVOTE))
        val like = likeText(likeBuilder(classDefByOrNull(COMMENT_LIKE)))
        if ((classDefByOrNull(like.definingClass)?.methods?.singleOrNull { it.descriptor() == like.descriptor() }
                ?.localRegisterCount() ?: 0) < 1
        ) refuse("${like.descriptor()} has no local register for the hook")
        val dock = reactionPicker(holders(DOCK_NAME))
        val threads = replyThreads(
            holders(COMMENT_SECTION),
            holders(EXPAND_REPLIES).flatMap { methodsHolding(it, EXPAND_REPLIES) },
        ) { types -> types.mapNotNull { classDefByOrNull(it) }.associateBy { it.type } }
        val drawn = commentDraw(holders(COMMENT_SECTION))
        val replyList = replyList(holders(EXPANDED_REPLIES), holders(COMMENT_LIST))
        val replyRow = replyRowTap(holders(VIEW_MORE_REPLIES))
        val feedReplyRow = replyRowTap(holders(FEED_VIEW_MORE_REPLIES), FEED_VIEW_MORE_REPLIES)
        val threadPager = threadPagerTap(holders(COMMENT_LIST), replyRow) { types ->
            types.mapNotNull { classDefByOrNull(it) }.associateBy { it.type }
        }
        // 585's bar no longer names its GIF button, so the bar is found by its sticker and the enum constant.
        val gifAction = classDefByOrNull(COMPOSER_ACTION)?.let { enumConstant(it, VIEWER_GIF_ACTION) }
        val viewer = viewerButtons(holders(VIEWER_STICKER), gifAction)
        // 585 builds the video bar inside the player chrome's own method, so with no render of its own the inline form is used.
        val barHolders = holders(VIDEO_COMMENT_BAR)
        val videoBar = if (videoCommentBarRenders(barHolders, viewer.render).isEmpty()) null else videoCommentBar(barHolders, viewer.render)
        val inlineBar = if (videoBar == null) inlineCommentBar(barHolders, viewer.render) else null

        mutableClassDefBy(buttons.check.definingClass).methods.single { it.descriptor() == buttons.check.descriptor() }
            .holdButtons(buttons.table)
        mutableClassDefBy(bottom.check.definingClass).methods.single { it.descriptor() == bottom.check.descriptor() }
            .holdButtons(bottom.table, HOLDS_BOTTOM_CONTENT)
        mutableClassDefBy(post.check.definingClass).methods.single { it.descriptor() == post.check.descriptor() }
            .holdButtons(post.table, HOLDS_POST_COMMENTS)
        mutableClassDefBy(dislike.definingClass).methods.single { it.descriptor() == dislike.descriptor() }
            .holdBuild(HOLDS_COMMENT_DISLIKE)
        mutableClassDefBy(like.definingClass).methods.single { it.descriptor() == like.descriptor() }
            .holdBuild(HOLDS_COMMENT_LIKE)
        mutableClassDefBy(dock.definingClass).methods.single { it.descriptor() == dock.descriptor() }
            .skipPicker()
        mutableClassDefBy(threads.initialState.definingClass).methods
            .single { it.descriptor() == threads.initialState.descriptor() }
            .openReplyThreads(threads)
        mutableClassDefBy(drawn.render.definingClass).methods.single { it.descriptor() == drawn.render.descriptor() }
            .hideComments(drawn)
        mutableClassDefBy(replyList.build.definingClass).methods.single { it.descriptor() == replyList.build.descriptor() }
            .loadWholeReplyThreads(replyList)
        mutableClassDefBy(replyRow.render.definingClass).methods.single { it.descriptor() == replyRow.render.descriptor() }
            .tapWholeReplyRow(replyRow)
        mutableClassDefBy(feedReplyRow.render.definingClass).methods
            .single { it.descriptor() == feedReplyRow.render.descriptor() }
            .tapWholeReplyRow(feedReplyRow)
        mutableClassDefBy(threadPager.render.definingClass).methods
            .single { it.descriptor() == threadPager.render.descriptor() }
            .tapWholeReplyRow(threadPager)
        mutableClassDefBy(viewer.render.definingClass).methods.single { it.descriptor() == viewer.render.descriptor() }
            .holdViewerButtons(viewer)
        videoBar?.let { bar ->
            mutableClassDefBy(bar.definingClass).methods.single { it.descriptor() == bar.descriptor() }.hideVideoCommentComposer()
        }
        inlineBar?.let { bar ->
            mutableClassDefBy(bar.render.definingClass).methods.single { it.descriptor() == bar.render.descriptor() }
                .hideInlineCommentBar(bar)
        }
        emojiRowCheck(holders(EMOJI_ROW))?.let { check ->
            mutableClassDefBy(check.definingClass).methods.single { it.descriptor() == check.descriptor() }.holdEmojiRow()
        }
        enableStatus("commentSheetOptions")
    }
}

/**
 * The pre-input socket's check of whether a plugin applies, from [holders], the classes loading
 * [EMOJI_ROW]: the one static (object, int) boolean method of the class whose render names
 * [PRE_INPUT_SOCKET] and [EMOJI_ROW] and calls it. The socket numbers its two plugins, 1 for Meta
 * AI's pills and 0 for the emoji row, and the check opens on that number: not zero runs on to Meta
 * AI's own check, zero jumps to the emoji row's (584: LX/9eB;->A03). A build where the socket moved
 * keeps the rest of the patch, with Hide the emoji row on comments left doing nothing and a warning
 * saying why.
 */
internal fun emojiRowCheck(holders: List<ClassDef>): Method? {
    fun skip(why: String): Method? {
        patchLog.warning("$PATCH: $why. Hide the emoji row on comments does nothing in this build")
        return null
    }
    val renders = holders.flatMap { methodsHolding(it, EMOJI_ROW) }.filter { holdsString(it, PRE_INPUT_SOCKET) }
    val render = renders.singleOrNull() ?: return skip("expected one render naming $PRE_INPUT_SOCKET and the emoji row, found ${renders.size}")
    val owner = holders.single { it.type == render.definingClass }
    val checks = calls(render).filter { call ->
        call.definingClass == owner.type && call.returnType == "Z" &&
            call.parameterTypes.size == 2 && call.parameterTypes.last().toString() == "I"
    }.map { it.descriptor() }.distinct().mapNotNull { called -> owner.methods.singleOrNull { it.descriptor() == called } }
        .filter { AccessFlags.STATIC.isSet(it.accessFlags) }
    val check = checks.singleOrNull() ?: return skip("expected one applies check in ${render.descriptor()}, found ${checks.size}")
    if (check.localRegisterCount() < 1) return skip("${check.descriptor()} has no local register for the hook")
    val code = check.implementation!!.instructions.toList()
    val opening = code.firstOrNull()
    if (opening?.opcode != Opcode.IF_EQZ || (opening as OneRegisterInstruction).registerA != check.parameterRegisterNumber(1)) {
        return skip("${check.descriptor()} doesn't open on the plugin's number")
    }
    // The opening branch sits at code unit 0, so its offset is where plugin 0's check starts.
    val zero = (opening as OffsetInstruction).codeOffset
    var unit = 0
    val pills = code.takeWhile { instruction -> (unit < zero).also { unit += instruction.codeUnits } }.any {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == META_AI_PILLS
    }
    if (!pills) return skip("${check.descriptor()}'s non-zero path doesn't ask $META_AI_PILLS")
    return check
}

/** First thing in the pre-input socket's applies check: for plugin 0, the emoji row, answer no while the extension says so. */
internal fun MutableMethod.holdEmojiRow() {
    addInstructionsWithLabels(
        0,
        """
            if-nez p1, :check
            invoke-static {}, $HOLDS_EMOJI_ROW
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", getInstruction(0)),
    )
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun BytecodePatchContext.holders(string: String) =
    classDefByStrings(string, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

private fun calls(method: Method): List<MethodReference> =
    method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        if (!instruction.opcode.name.startsWith("invoke")) return@mapNotNull null
        (instruction as ReferenceInstruction).reference as? MethodReference
    }

/**
 * The comment box's button socket, from [holders], the classes loading [GIF_BUTTON], and
 * [sockets], the methods loading [BUTTON_SOCKET]. [classes] reads the classes of the types it's
 * given, so the check can be looked at in whichever class holds it. The table is the one name table
 * naming both buttons; see [pluginCheck] for the check.
 */
internal fun buttonSocket(
    holders: List<ClassDef>,
    sockets: List<Method>,
    classes: (Set<String>) -> Map<String, ClassDef>,
): PluginSocket = pluginCheck(holders, sockets, classes, BUTTON_SOCKET, GIF_BUTTON, STICKER_BUTTON)

/**
 * The socket under a post's comments, from [holders], the classes loading [RELATED_GROUPS], and
 * [sockets], the methods loading [BOTTOM_SOCKET]. The table is the one name table naming the
 * Related groups plugin; see [pluginCheck] for the check, which may share the table's class.
 */
internal fun bottomSocket(
    holders: List<ClassDef>,
    sockets: List<Method>,
    classes: (Set<String>) -> Map<String, ClassDef>,
): PluginSocket = pluginCheck(holders, sockets, classes, BOTTOM_SOCKET, RELATED_GROUPS)

/**
 * The socket under a post's buttons, from [holders], the classes loading [INLINE_COMMENTS], and
 * [sockets], the methods loading [POST_SOCKET]. The table is the one name table naming the inline
 * comments and their client-ranked variant; see [pluginCheck] for the check.
 */
internal fun postSocket(
    holders: List<ClassDef>,
    sockets: List<Method>,
    classes: (Set<String>) -> Map<String, ClassDef>,
): PluginSocket = pluginCheck(holders, sockets, classes, POST_SOCKET, INLINE_COMMENTS, INLINE_COMMENTS_CLIENT_RANKING)

/**
 * The builder of the thumbs-down under a comment: the one static method of [plugin], the class
 * [COMMENT_DOWNVOTE], that takes two objects, the comment's context and props, and returns an
 * object, the button's component, with a local register for the hook. Refuses without the class
 * or with any other number of such methods.
 */
internal fun dislikeBuilder(plugin: ClassDef?): Method {
    if (plugin == null) refuse("no class $COMMENT_DOWNVOTE")
    val builders = plugin.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.returnType.startsWith("L") &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes.all { it.startsWith("L") }
    }
    val builder = builders.singleOrNull() ?: refuse(
        "expected one static builder of two objects in $COMMENT_DOWNVOTE, found ${builders.size}: " +
            builders.joinToString { it.descriptor() },
    )
    if (builder.localRegisterCount() < 1) refuse("${builder.descriptor()} has no local register for the hook")
    return builder
}

/**
 * The builder of Like in the row under a comment: the one static method of [plugin], the class
 * [COMMENT_LIKE], that takes the comment's context and answers the Like text as a Spannable, or
 * nothing when the comment has none, with a local register for the hook. Refuses without the class
 * or with any other number of such methods.
 */
internal fun likeBuilder(plugin: ClassDef?): Method {
    if (plugin == null) refuse("no class $COMMENT_LIKE")
    val builders = plugin.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.returnType == "Landroid/text/Spannable;" &&
            method.parameterTypes.size == 1
    }
    val builder = builders.singleOrNull() ?: refuse(
        "expected one static Spannable builder of one object in $COMMENT_LIKE, found ${builders.size}: " +
            builders.joinToString { it.descriptor() },
    )
    if (builder.localRegisterCount() < 1) refuse("${builder.descriptor()} has no local register for the hook")
    return builder
}

/**
 * What writes Like, for both Like plugins: the one method [builder], the kept plugin's, calls that
 * takes a GraphQLComment and answers a SpannableString. Refuses with any other number of them.
 */
internal fun likeText(builder: Method): MethodReference {
    val writers = calls(builder).filter {
        it.returnType == "Landroid/text/SpannableString;" && GRAPHQL_COMMENT in it.parameterTypes
    }.distinctBy { it.descriptor() }
    return writers.singleOrNull() ?: refuse(
        "expected ${builder.descriptor()} to write Like through one method, found ${writers.size}: " +
            writers.joinToString { it.descriptor() },
    )
}

/**
 * A socket named [socket] whose name table names [plugin], and [alsoNamed] when it's given. The
 * table is the one static (I)String name table among [holders] loading [plugin]. The check is the
 * one static boolean method a method of [sockets] calls along with the table, with an int last, a
 * switch over the same numbers as the table's and a local register for the hook. [classes] reads
 * the classes the calls name. Refuses unless there's exactly one of each.
 */
internal fun pluginCheck(
    holders: List<ClassDef>,
    sockets: List<Method>,
    classes: (Set<String>) -> Map<String, ClassDef>,
    socket: String,
    plugin: String,
    alsoNamed: String? = null,
): PluginSocket {
    val tables = holders.flatMap { methodsHolding(it, plugin) }.filter(::isNameTable).distinctBy { it.descriptor() }
    val table = tables.singleOrNull() ?: refuse("expected one name table naming $plugin, found ${tables.size}")
    if (alsoNamed != null && !holdsString(table, alsoNamed)) refuse("${table.descriptor()} names $plugin but not $alsoNamed")
    val numbers = switchKeys(table).singleOrNull() ?: refuse("${table.descriptor()} has more than one switch")

    val tableCall = table.descriptor()
    val callers = sockets.filter { method -> calls(method).any { it.descriptor() == tableCall } }
    if (callers.isEmpty()) refuse("no method naming $socket calls $tableCall")
    val called = callers.flatMap(::calls).filter { call ->
        call.returnType == "Z" && call.parameterTypes.lastOrNull()?.toString() == "I"
    }.associateBy { it.descriptor() }
    val owners = classes(called.values.map { it.definingClass }.toSet())
    val checks = called.keys.mapNotNull { descriptor ->
        owners[called.getValue(descriptor).definingClass]?.methods?.singleOrNull { it.descriptor() == descriptor }
    }.filter { method -> AccessFlags.STATIC.isSet(method.accessFlags) && numbers in switchKeys(method) }
    val check = checks.singleOrNull() ?: refuse(
        "expected one check of $tableCall's plugins, found ${checks.size}: ${checks.joinToString { it.descriptor() }}",
    )
    if (check.localRegisterCount() < 1) refuse("${check.descriptor()} has no local register for the hook")
    return PluginSocket(table, check)
}

/** Whether [method] opens the reaction picker: an instance (View, View)V method building [UFI_DOCK] and logging [DOCK_NAME]. */
internal fun isReactionPicker(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V") return false
    if (method.parameterTypes.map(CharSequence::toString) != listOf(VIEW, VIEW)) return false
    if (!holdsString(method, DOCK_NAME)) return false
    return method.implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.NEW_INSTANCE &&
            ((instruction as ReferenceInstruction).reference as? TypeReference)?.type == UFI_DOCK
    } == true
}

/** The one method among [holders], the classes loading [DOCK_NAME], that opens the reaction picker. */
internal fun reactionPicker(holders: List<ClassDef>): Method {
    val pickers = holders.flatMap { methodsHolding(it, DOCK_NAME) }.filter(::isReactionPicker).distinctBy { it.descriptor() }
    val picker = pickers.singleOrNull() ?: refuse(
        "expected one method opening $UFI_DOCK that logs \"$DOCK_NAME\", found ${pickers.size}",
    )
    if (picker.localRegisterCount() < 1) refuse("${picker.descriptor()} has no local register for the hook")
    return picker
}

/**
 * First thing in a socket's check: get the plugin's name from [table] with the check's own number,
 * ask the extension ([hook], a static (String)Z), and answer no when it holds that plugin. Otherwise
 * the check runs from its first instruction. The number is the last register, so the calls take it
 * as a range.
 */
internal fun MutableMethod.holdButtons(table: Method, hook: String = HOLDS_BUTTON) {
    val number = implementation!!.registerCount - 1
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { v$number .. v$number }, ${table.descriptor()}
            move-result-object v0
            invoke-static { v0 }, $hook
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", getInstruction(0)),
    )
}

/**
 * First thing where a button under a comment is built, the thumbs-down or Like: while the
 * extension's [call] says so, return nothing.
 */
internal fun MutableMethod.holdBuild(call: String) {
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $call
            move-result v0
            if-eqz v0, :build
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("build", getInstruction(0)),
    )
}

/** First thing where the reaction picker opens: while the extension says so, return before it does. */
internal fun MutableMethod.skipPicker() {
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $SKIP_PICKER
            move-result v0
            if-eqz v0, :open
            return-void
        """,
        ExternalLabel("open", getInstruction(0)),
    )
}

/**
 * The comment section's [initialState] method and the open flag of its state, [flag], which the
 * method writes false at instruction [write] before its one return-void at [end].
 */
internal class ReplyThreads(val initialState: Method, val flag: FieldReference, val write: Int, val end: Int)

private fun Instruction.methodCall() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun FieldReference.sameAs(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister() || this !is OneRegisterInstruction) return false
    return registerA == register || (opcode.setsWideRegister() && registerA + 1 == register)
}

/** The literal the nearest instruction before [index] that writes [register] puts there, or null. */
private fun literalBefore(code: List<Instruction>, index: Int, register: Int): Int? =
    (index - 1 downTo 0).map { code[it] }.firstOrNull { it.writes(register) }
        .let { (it as? NarrowLiteralInstruction)?.narrowLiteral }

private val UPDATE_FACTORIES = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val UPDATE_CONSTRUCTORS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

/**
 * The update [update] sends: its type and its number, read from the last call before
 * [EXPAND_REPLIES] that builds something from an Object[] and an int, a static factory on 577 and
 * 581 and a constructor on 580. The number is the int, and it has to be a literal.
 */
internal fun expandUpdate(update: Method): Pair<String, Int> {
    val code = update.implementation?.instructions?.toList().orEmpty()
    val named = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == EXPAND_REPLIES }
    if (named < 0) refuse("${update.descriptor()} doesn't name \"$EXPAND_REPLIES\"")
    val build = (named - 1 downTo 0).firstOrNull { index ->
        val call = code[index].methodCall() ?: return@firstOrNull false
        call.parameterTypes.map(CharSequence::toString) == listOf(OBJECTS, "I") && when (code[index].opcode) {
            in UPDATE_FACTORIES -> call.returnType.startsWith("L")
            in UPDATE_CONSTRUCTORS -> call.name == "<init>"
            else -> false
        }
    } ?: refuse("${update.descriptor()} builds no update from an Object[] and a number before \"$EXPAND_REPLIES\"")
    val call = code[build].methodCall()!!
    val type = if (code[build].opcode in UPDATE_FACTORIES) call.returnType else call.definingClass
    val number = literalBefore(code, build, code[build].namedRegisters().last())
        ?: refuse("${update.descriptor()} builds its update with a number that isn't a literal")
    return type to number
}

/**
 * The boolean on [state] that the arm for update [number] of its one update method, an instance
 * ([type])V method with a switch, writes first. Refuses unless there's exactly one such method,
 * an arm for the number, and a boolean write on [state] in that arm before it leaves.
 */
internal fun openFlag(state: ClassDef, type: String, number: Int): FieldReference {
    val methods = state.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(type)
    }
    val apply = methods.singleOrNull() ?: refuse("expected one update method on ${state.type}, found ${methods.size}")
    val code = apply.implementation?.instructions?.toList().orEmpty()
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    val switch = code.indexOfFirst { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
    if (switch < 0) refuse("${apply.descriptor()} has no switch")
    val payload = code.getOrNull(addresses.indexOf(addresses[switch] + (code[switch] as OffsetInstruction).codeOffset))
        as? SwitchPayload ?: refuse("${apply.descriptor()}'s switch has no payload")
    val arm = payload.switchElements.singleOrNull { it.key == number }
        ?: refuse("${apply.descriptor()} has no arm for update $number")
    val start = addresses.indexOf(addresses[switch] + arm.offset)
    if (start < 0) refuse("${apply.descriptor()}'s arm for update $number lands between instructions")
    return code.drop(start)
        .takeWhile { !it.opcode.name.startsWith("return") && it.opcode != Opcode.THROW }
        .firstOrNull { it.opcode == Opcode.IPUT_BOOLEAN && it.field()?.definingClass == state.type }
        ?.field() ?: refuse("${apply.descriptor()}'s arm for update $number writes no boolean on ${state.type}")
}

/**
 * The comment section's reply flag and the method that starts it, from [sections], the classes
 * loading [COMMENT_SECTION], and [updates], the methods loading [EXPAND_REPLIES]. [classes] reads
 * the state class.
 *
 * The section is the one class whose constructor names itself [COMMENT_SECTION]. Its state is the
 * one class its no-argument methods make, and the flag is the boolean [EXPAND_REPLIES]'s update
 * sets on it ([openFlag]). The initial-state method is the section's one instance (x)V method that
 * writes the flag without reading it. It must write it once, from a literal false, then run
 * straight to its one return-void with nothing jumping in and the state's register kept, so the
 * hook just before that return still has the state and a free register to work with.
 */
internal fun replyThreads(
    sections: List<ClassDef>,
    updates: List<Method>,
    classes: (Set<String>) -> Map<String, ClassDef>,
): ReplyThreads {
    val named = sections.filter { section ->
        section.methods.any { it.name == "<init>" && holdsString(it, COMMENT_SECTION) }
    }.distinctBy { it.type }
    val section = named.singleOrNull()
        ?: refuse("expected one class naming itself \"$COMMENT_SECTION\", found ${named.size}")
    val sent = updates.distinctBy { it.descriptor() }
    val update = sent.singleOrNull() ?: refuse("expected one method sending \"$EXPAND_REPLIES\", found ${sent.size}")
    val (type, number) = expandUpdate(update)

    val made = section.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType.startsWith("L")
    }.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
    }.toSet()
    val stateType = made.singleOrNull() ?: refuse("expected one state class made by ${section.type}, found ${made.size}")
    val state = classes(setOf(stateType))[stateType] ?: refuse("$stateType isn't in this APK")
    val flag = openFlag(state, type, number)

    fun touching(method: Method, opcode: Opcode) = method.implementation?.instructions?.toList().orEmpty()
        .withIndex().filter { (_, instruction) -> instruction.opcode == opcode && instruction.field()?.sameAs(flag) == true }
        .map { it.index }
    val starters = section.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.size == 1 &&
            touching(it, Opcode.IPUT_BOOLEAN).isNotEmpty() && touching(it, Opcode.IGET_BOOLEAN).isEmpty()
    }
    val start = starters.singleOrNull()
        ?: refuse("expected one method of ${section.type} starting its reply flag, found ${starters.size}")
    val where = start.descriptor()
    val write = touching(start, Opcode.IPUT_BOOLEAN).singleOrNull() ?: refuse("$where writes the reply flag more than once")
    val code = start.implementation!!.instructions.toList()
    val put = code[write] as TwoRegisterInstruction
    if (literalBefore(code, write, put.registerA) != 0) refuse("$where doesn't start the reply flag false")
    val end = code.indices.singleOrNull { code[it].opcode == Opcode.RETURN_VOID }
        ?: refuse("$where doesn't have exactly one return-void")
    if (end < write || code.indices.any { code[it].opcode.name.startsWith("return") && it != end }) {
        refuse("$where doesn't end at one return-void after the reply flag")
    }
    val flow = ControlFlow.of(start)
    for (index in code.indices) {
        for (next in flow.normal[index] + flow.exceptional[index]) {
            if (next in write + 1..end && next != index + 1) refuse("$where has a jump to instruction $next, after the reply flag")
        }
    }
    if ((write + 1 until end).any { flow.normal[it] != listOf(it + 1) || code[it].writes(put.registerB) }) {
        refuse("$where doesn't run straight from the reply flag to its return with the state kept")
    }
    if (freeRegister(put.registerB) >= start.localRegisterCount()) refuse("$where has no local register for the hook")
    return ReplyThreads(start, flag, write, end)
}

/**
 * The comment section's draw method, [render]; the field of the section holding its props,
 * [props]; and the props' Object field the comment is read from, [comment].
 */
internal class CommentDraw(val render: Method, val props: FieldReference, val comment: FieldReference)

/**
 * Where the comment section draws its comment, from [sections], the classes loading
 * [COMMENT_SECTION]. The section is the one class whose constructor names itself. Its draw method
 * is its one other instance method naming [COMMENT_SECTION], with one argument and an object
 * returned. The comment is read from the one Object field whose read the method casts straight to
 * [GRAPHQL_COMMENT], on a props type the section keeps exactly one instance field of. Refuses
 * unless each is exactly one and the method has a local register for the hook.
 */
internal fun commentDraw(sections: List<ClassDef>): CommentDraw {
    val named = sections.filter { section ->
        section.methods.any { it.name == "<init>" && holdsString(it, COMMENT_SECTION) }
    }.distinctBy { it.type }
    val section = named.singleOrNull()
        ?: refuse("expected one class naming itself \"$COMMENT_SECTION\", found ${named.size}")
    val renders = section.methods.filter {
        it.name != "<init>" && !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.size == 1 &&
            it.returnType.startsWith("L") && holdsString(it, COMMENT_SECTION)
    }
    val render = renders.singleOrNull()
        ?: refuse("expected one method of ${section.type} drawing its comment, found ${renders.size}")
    val where = render.descriptor()
    val code = render.implementation?.instructions?.toList().orEmpty()
    val reads = code.indices.filter { index ->
        val read = code[index]
        val cast = code.getOrNull(index + 1) ?: return@filter false
        read.opcode == Opcode.IGET_OBJECT && read.field()?.type == "Ljava/lang/Object;" &&
            cast.opcode == Opcode.CHECK_CAST &&
            ((cast as ReferenceInstruction).reference as? TypeReference)?.type == GRAPHQL_COMMENT &&
            (cast as OneRegisterInstruction).registerA == (read as TwoRegisterInstruction).registerA
    }.mapNotNull { code[it].field() }.distinctBy { "${it.definingClass}->${it.name}" }
    val comment = reads.singleOrNull() ?: refuse("$where reads its comment from ${reads.size} fields, not one")
    val holders = section.fields.filter { it.type == comment.definingClass && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val props = holders.singleOrNull()
        ?: refuse("expected one field of ${section.type} holding ${comment.definingClass}, found ${holders.size}")
    if (render.localRegisterCount() < 1) refuse("$where has no local register for the hook")
    return CommentDraw(render, props, comment)
}

/**
 * First thing where the comment section draws: its comment out of the props, the extension asked
 * with it, and no children returned on a yes. Props not set yet go on to Facebook's code, which
 * meets them as it would. The section is copied into v0 first: 582's render has 46 registers,
 * and an iget can only name v0 to v15.
 */
internal fun MutableMethod.hideComments(drawn: CommentDraw) {
    val props = drawn.props.let { "${it.definingClass}->${it.name}:${it.type}" }
    val comment = drawn.comment.let { "${it.definingClass}->${it.name}:${it.type}" }
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, $props
            if-eqz v0, :draw
            iget-object v0, v0, $comment
            invoke-static { v0 }, $HIDES_COMMENT
            move-result v0
            if-eqz v0, :draw
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("draw", getInstruction(0)),
    )
}

/** The register the reply hook borrows: any but the state's, every register being free at the return. */
internal fun freeRegister(state: Int) = if (state == 0) 1 else 0

/**
 * Just before the initial-state method returns, hand the extension the reply flag as Facebook
 * left it and write back its answer. The state's register is the one the flag was written
 * through, and the call takes the flag as a range.
 */
internal fun MutableMethod.openReplyThreads(threads: ReplyThreads) {
    val state = getInstruction<TwoRegisterInstruction>(threads.write).registerB
    val field = threads.flag.let { "${it.definingClass}->${it.name}:${it.type}" }
    val free = freeRegister(state)
    addInstructions(
        threads.end,
        """
            iget-boolean v$free, v$state, $field
            invoke-static/range { v$free .. v$free }, $OPEN_REPLY_THREADS
            move-result v$free
            iput-boolean v$free, v$state, $field
        """,
    )
}

/**
 * Where an open reply thread builds the list of its replies: the method of the thread's section,
 * [build], and the instruction in it, [write], that writes [flag], the list's flag for paging on by
 * itself as it's scrolled.
 */
internal class ReplyList(val build: Method, val write: Int, val flag: FieldReference)

/**
 * The reply list's paging flag, from [threads], the classes loading [EXPANDED_REPLIES], and
 * [lists], the classes loading [COMMENT_LIST]. The thread's section and the list are each the one
 * class whose constructor names itself. The build is the one method of the section that makes a
 * list, and the flag is the one boolean of the list it writes there, which the list's own method
 * naming [COMMENT_LIST] reads. The hook replaces the value just before the write, so the write must
 * be reached only by the instruction before it, and nothing after it may read the register the
 * value sat in. Refuses unless each is exactly one and those hold.
 */
internal fun replyList(threads: List<ClassDef>, lists: List<ClassDef>): ReplyList {
    fun named(classes: List<ClassDef>, name: String): ClassDef {
        val found = classes.filter { holder -> holder.methods.any { it.name == "<init>" && holdsString(it, name) } }
            .distinctBy { it.type }
        return found.singleOrNull() ?: refuse("expected one class naming itself \"$name\", found ${found.size}")
    }
    val thread = named(threads, EXPANDED_REPLIES)
    val list = named(lists, COMMENT_LIST)

    val builds = thread.methods.filter { method ->
        method.implementation?.instructions?.any {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == list.type
        } == true
    }
    val build = builds.singleOrNull()
        ?: refuse("expected one method of ${thread.type} making a ${list.type}, found ${builds.size}")
    val where = build.descriptor()
    val code = build.implementation!!.instructions.toList()
    val writes = code.indices.filter { code[it].opcode == Opcode.IPUT_BOOLEAN && code[it].field()?.definingClass == list.type }
    val write = writes.singleOrNull() ?: refuse("$where writes ${writes.size} booleans of ${list.type}, not one")
    val flag = code[write].field()!!

    val reads = list.methods.filter { it.name != "<init>" && holdsString(it, COMMENT_LIST) }.any { method ->
        method.implementation?.instructions?.any { it.opcode == Opcode.IGET_BOOLEAN && it.field()?.sameAs(flag) == true } == true
    }
    if (!reads) refuse("${list.type} never reads the flag $where writes, ${flag.name}")

    val flow = ControlFlow.of(build)
    for (index in code.indices) {
        for (next in flow.normal[index] + flow.exceptional[index]) {
            if (next == write && index != write - 1) refuse("$where has a jump to the flag's write at $write")
        }
    }
    if (flow.normal[write - 1] != listOf(write)) refuse("$where doesn't run straight into the flag's write")
    val value = (code[write] as TwoRegisterInstruction).registerA
    val later = build.readsAfter(write, value)
    if (later.isNotEmpty()) refuse("$where reads v$value again after the flag's write, at $later")
    return ReplyList(build, write, flag)
}

/**
 * Just before the reply list's paging flag is written, hand the extension the value Facebook
 * gives it and write its answer instead. The value's register is free after the write, so the
 * answer goes back into it, and the call takes it as a range.
 */
internal fun MutableMethod.loadWholeReplyThreads(list: ReplyList) {
    val value = getInstruction<TwoRegisterInstruction>(list.write).registerA
    addInstructions(
        list.write,
        """
            invoke-static/range { v$value .. v$value }, $LOAD_WHOLE_REPLY_THREADS
            move-result v$value
        """,
    )
}

/**
 * Where the View more replies row picks which part of itself takes a tap: its [render], the
 * `move-result` that keeps Facebook's answer, [kept], and the answer's register, [flag].
 */
internal class ReplyRowTap(val render: Method, val kept: Int, val flag: Int)

/**
 * A View more replies row, from [holders], the classes loading [name] ([VIEW_MORE_REPLIES] or
 * [FEED_VIEW_MORE_REPLIES]). Its render is the one method naming it that answers an object, the
 * constructor and the feed row's other method answering nothing. It finishes the row with one
 * static call made in two places, one on each side of checks of the same boolean: an `if-eqz`
 * that falls through to the call (the boolean true, where only the words take a tap) and an
 * `if-eqz` that jumps to it (the boolean false, where the whole row is wrapped as the tap
 * target), the fall-through's call coming first in the method. That boolean reaches both checks
 * from one write only, the `move-result` of a call answering it, and the hook goes right after
 * that write, so the write must run straight into the next instruction and nothing may jump
 * there. Refuses unless each holds.
 */
internal fun replyRowTap(holders: List<ClassDef>, name: String = VIEW_MORE_REPLIES): ReplyRowTap {
    val renders = holders.flatMap { methodsHolding(it, name) }.filter { it.returnType.startsWith("L") }
        .distinctBy { it.descriptor() }
    val render = renders.singleOrNull()
        ?: refuse("expected one method naming \"$name\" that answers an object, found ${renders.size}")
    val where = render.descriptor()
    val code = render.implementation!!.instructions.toList()
    val flow = ControlFlow.of(render)

    /** The static call at [index] that answers an object, or null. */
    fun builderAt(index: Int) = code.getOrNull(index)?.takeIf { it.opcode == Opcode.INVOKE_STATIC }?.methodCall()
        ?.takeIf { it.returnType.startsWith("L") }?.toString()

    val falls = mutableSetOf<Pair<Int, String>>()
    val jumps = mutableSetOf<Pair<Int, String>>()
    val checksOf = mutableMapOf<Int, MutableList<Int>>()
    for ((index, instruction) in code.withIndex()) {
        if (instruction.opcode != Opcode.IF_EQZ) continue
        val register = (instruction as OneRegisterInstruction).registerA
        checksOf.getOrPut(register) { mutableListOf() } += index
        builderAt(index + 1)?.let { falls += register to it }
        builderAt(flow.normal[index].first())?.let { jumps += register to it }
    }
    val sides = falls.intersect(jumps)
    val (flag, builder) = sides.singleOrNull()
        ?: refuse("$where builds a tap target on both sides of ${sides.size} checks, not one")
    val made = code.count { it.opcode == Opcode.INVOKE_STATIC && it.methodCall()?.toString() == builder }
    if (made != 2) refuse("$where builds its tap target $made times, not twice")
    // The words are wrapped before the row holding them is built, so the call a check falls into
    // comes first and the one a check jumps to wraps the finished row. A build that swapped the two
    // would turn the switch into words-only taps for everyone.
    val wordsAt = checksOf.getValue(flag).map { it + 1 }.filter { builderAt(it) == builder }.distinct()
    val rowAt = checksOf.getValue(flag).map { flow.normal[it].first() }.filter { builderAt(it) == builder }.distinct()
    if (wordsAt.size != 1 || rowAt.size != 1 || wordsAt[0] >= rowAt[0]) {
        refuse("$where doesn't wrap the words before the whole row")
    }

    val checks = checksOf.getValue(flag).filter { builderAt(it + 1) == builder || builderAt(flow.normal[it].first()) == builder }
    val writers = checks.flatMap { render.writersReaching(it, flag) }.distinct()
    val kept = writers.singleOrNull() ?: refuse("$where sets v$flag ${writers.size} ways before its checks, not one")
    if (kept < 1 || code[kept].opcode != Opcode.MOVE_RESULT || code[kept - 1].methodCall()?.returnType != "Z") {
        refuse("$where doesn't take v$flag from a call answering a boolean")
    }
    if (flag > 255) refuse("$where keeps its flag in v$flag, out of a move-result's reach")
    val at = kept + 1
    if (flow.normal[kept] != listOf(at)) refuse("$where doesn't run straight on from v$flag's write")
    for (index in code.indices) {
        for (next in flow.normal[index] + flow.exceptional[index]) {
            if (next == at && index != kept) refuse("$where has a jump to instruction $at, where the hook goes")
        }
    }
    return ReplyRowTap(render, kept, flag)
}

/**
 * The View more replies pager at the end of an open reply thread, from [lists], the classes loading
 * [COMMENT_LIST], [sheetRow], the comment sheet's View more replies row, and [classes], which reads
 * the classes it's handed. The pager names nothing, so it's found through the list that draws it:
 * the one class whose constructor names [COMMENT_LIST], the classes its methods make, and of those
 * the one with a one-argument method answering an object that asks the same server answer
 * [sheetRow] keeps. That ask runs straight into its `move-result`, the hook goes right after it, so
 * nothing may jump there, and at least two checks of the answer must be reached by that write alone.
 * Refuses unless each holds.
 */
internal fun threadPagerTap(
    lists: List<ClassDef>,
    sheetRow: ReplyRowTap,
    classes: (Set<String>) -> Map<String, ClassDef>,
): ReplyRowTap {
    val named = lists.filter { holder -> holder.methods.any { it.name == "<init>" && holdsString(it, COMMENT_LIST) } }
        .distinctBy { it.type }
    val list = named.singleOrNull() ?: refuse("expected one class naming itself \"$COMMENT_LIST\", found ${named.size}")
    val ask = sheetRow.render.implementation!!.instructions.toList()[sheetRow.kept - 1].methodCall()?.toString()
        ?: refuse("${sheetRow.render.descriptor()} keeps an answer no call gave it")

    val made = list.methods.flatMap { method ->
        method.implementation?.instructions?.mapNotNull { instruction ->
            if (instruction.opcode != Opcode.NEW_INSTANCE) return@mapNotNull null
            ((instruction as ReferenceInstruction).reference as TypeReference).type
        }.orEmpty()
    }.toSet() - list.type - sheetRow.render.definingClass
    val renders = classes(made).values.flatMap { it.methods }.filter { method ->
        method.parameterTypes.size == 1 && method.returnType.startsWith("L") &&
            method.implementation?.instructions?.any { it.methodCall()?.toString() == ask } == true
    }.distinctBy { it.descriptor() }
    val render = renders.singleOrNull()
        ?: refuse("expected one renderer ${list.type} makes that asks $ask, found ${renders.size}")
    val where = render.descriptor()
    val code = render.implementation!!.instructions.toList()
    val asks = code.indices.filter { code[it].methodCall()?.toString() == ask }
    val asked = asks.singleOrNull() ?: refuse("$where asks $ask ${asks.size} times, not once")
    val kept = asked + 1
    if (code.getOrNull(kept)?.opcode != Opcode.MOVE_RESULT) refuse("$where doesn't keep the answer of $ask")
    val flag = (code[kept] as OneRegisterInstruction).registerA
    if (flag > 255) refuse("$where keeps its flag in v$flag, out of a move-result's reach")

    val flow = ControlFlow.of(render)
    val checks = code.indices.filter {
        code[it].opcode in setOf(Opcode.IF_EQZ, Opcode.IF_NEZ) && (code[it] as OneRegisterInstruction).registerA == flag &&
            kept in render.writersReaching(it, flag)
    }
    if (checks.size < 2) refuse("$where checks v$flag ${checks.size} times after the answer, not two or more")
    if (checks.any { render.writersReaching(it, flag) != listOf(kept) }) refuse("$where sets v$flag another way before a check")
    val at = kept + 1
    if (flow.normal[kept] != listOf(at)) refuse("$where doesn't run straight on from v$flag's write")
    for (index in code.indices) {
        for (next in flow.normal[index] + flow.exceptional[index]) {
            if (next == at && index != kept) refuse("$where has a jump to instruction $at, where the hook goes")
        }
    }
    return ReplyRowTap(render, kept, flag)
}

/**
 * Right after the View more replies row reads which part of itself takes a tap, hand the
 * extension Facebook's answer and keep its own instead. The call takes the register as a range,
 * which reaches past v15.
 */
internal fun MutableMethod.tapWholeReplyRow(tap: ReplyRowTap) {
    addInstructions(
        tap.kept + 1,
        """
            invoke-static/range { v${tap.flag} .. v${tap.flag} }, $VIEW_MORE_REPLIES_TEXT_ONLY
            move-result v${tap.flag}
        """,
    )
}

/**
 * Where the video viewer's comment bar builds its buttons: its [render], the registers its sticker
 * and GIF buttons land in, [sticker] and [gif], the instruction the hook goes in front of, [at], and
 * a register nothing reads from there, [free].
 */
internal class ViewerButtons(val render: Method, val sticker: Int, val gif: Int, val at: Int, val free: Int)

/**
 * The video viewer's comment bar, from [holders], the classes loading [VIEWER_STICKER]. Its render
 * is the one method naming [VIEWER_STICKER] and either [VIEWER_GIF] or, from 585 on, the constant
 * [gifAction] (`Class->field:Type`, the enum's [VIEWER_GIF_ACTION]). Each name is loaded once and
 * handed to the next call, whose result, the button, is moved straight into a register. 585 builds
 * the GIF button's label between the constant's load and its builder, so there the builder is the
 * first call handed the constant, and it must be the call the sticker's name went to. The hook goes
 * after the later of the two, so it must be reached only from there, neither button's register may
 * be written in between, and a local register other than theirs must be free there. Refuses unless
 * each holds.
 */
internal fun viewerButtons(holders: List<ClassDef>, gifAction: String? = null): ViewerButtons {
    fun loads(method: Method, field: String) = method.implementation?.instructions?.any {
        it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == field
    } == true
    val renders = holders.flatMap { methodsHolding(it, VIEWER_STICKER) }
        .filter { holdsString(it, VIEWER_GIF) || gifAction != null && loads(it, gifAction) }
        .distinctBy { it.descriptor() }
    val render = renders.singleOrNull() ?: refuse(
        "expected one method naming \"$VIEWER_STICKER\" and \"$VIEWER_GIF\" (or loading ${gifAction ?: VIEWER_GIF_ACTION}), " +
            "found ${renders.size}",
    )
    val where = render.descriptor()
    val code = render.implementation!!.instructions.toList()
    fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference

    /** The index of the move-result that keeps the button built by [call] from what [load] loads, [what]. */
    fun kept(load: Int, call: Int, what: String): Int {
        val named = (code[load] as OneRegisterInstruction).registerA
        if ((load + 1 until call).any { code[it].writes(named) } || named !in code[call].namedRegisters()) {
            refuse("$where doesn't hand $what to the call after it")
        }
        if (code.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where doesn't keep what it builds from $what")
        return call + 1
    }

    /** The index of the move-result that keeps the button built from [name]. */
    fun built(name: String): Int {
        val loads = code.indices.filter { ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == name }
        val load = loads.singleOrNull() ?: refuse("$where loads \"$name\" ${loads.size} times, not once")
        val call = (load + 1 until code.size).firstOrNull { code[it].call() != null }
            ?: refuse("$where makes nothing of \"$name\"")
        return kept(load, call, "\"$name\"")
    }
    val stickerKept = built(VIEWER_STICKER)
    // 585 lost the GIF button's name; the enum constant the bar hands its builder stands in for it.
    val gifKept = if (holdsString(render, VIEWER_GIF)) built(VIEWER_GIF) else {
        val field = gifAction ?: refuse("$where doesn't name \"$VIEWER_GIF\" and no $VIEWER_GIF_ACTION constant was found")
        val builder = code[stickerKept - 1].call().toString()
        val gifLoads = code.indices.filter { code[it].opcode == Opcode.SGET_OBJECT && (code[it] as ReferenceInstruction).reference.toString() == field }
        val load = gifLoads.singleOrNull() ?: refuse("$where loads $field ${gifLoads.size} times, not once")
        val named = (code[load] as OneRegisterInstruction).registerA
        val call = (load + 1 until code.size).firstOrNull { code[it].call() != null && named in code[it].namedRegisters() }
            ?: refuse("$where makes nothing of $field")
        if (code[call].call().toString() != builder) refuse("$where hands $field to ${code[call].call()}, not the sticker's $builder")
        kept(load, call, field)
    }
    val sticker = (code[stickerKept] as OneRegisterInstruction).registerA
    val gif = (code[gifKept] as OneRegisterInstruction).registerA
    if (sticker == gif) refuse("$where keeps both buttons in v$gif")
    val at = maxOf(stickerKept, gifKept) + 1
    if (at >= code.size) refuse("$where ends with its buttons")
    if ((stickerKept + 1 until at).any { code[it].writes(sticker) } || (gifKept + 1 until at).any { code[it].writes(gif) }) {
        refuse("$where writes over a button before the hook")
    }

    val flow = ControlFlow.of(render)
    for (index in code.indices) {
        for (next in flow.normal[index] + flow.exceptional[index]) {
            if (next == at && index != at - 1) refuse("$where has a jump to instruction $at, where the hook goes")
        }
    }
    if (flow.normal[at - 1] != listOf(at)) refuse("$where doesn't run straight from its buttons to instruction $at")
    val live = RegisterLiveness.of(render).liveInto(at)
    val free = (0 until render.localRegisterCount()).firstOrNull { it !in live && it != sticker && it != gif }
        ?: refuse("$where has no free local register at instruction $at")
    return ViewerButtons(render, sticker, gif, at, free)
}

/**
 * Once the video viewer's comment bar has built its sticker and GIF buttons, ask the extension,
 * and on a yes clear both registers, so the bar is put together without them. Every register the
 * hook names can be past v15, so the clears pick the form that reaches it.
 */
internal fun MutableMethod.holdViewerButtons(buttons: ViewerButtons) {
    fun clear(register: Int) = if (register < 16) "const/4 v$register, 0x0" else "const/16 v$register, 0x0"
    addInstructionsWithLabels(
        buttons.at,
        """
            invoke-static {}, $HOLDS_VIEWER_BUTTONS
            move-result v${buttons.free}
            if-eqz v${buttons.free}, :bar
            ${clear(buttons.sticker)}
            ${clear(buttons.gif)}
        """,
        ExternalLabel("bar", getInstruction(buttons.at)),
    )
}

/**
 * The render of the video viewer's whole comment bar: among [holders], the classes loading
 * [VIDEO_COMMENT_BAR], the one with a method building [buttons]' class, the box's inner row. Its
 * render names [VIDEO_COMMENT_BAR] and has the row render's name, parameters and return. Refuses
 * unless there's exactly one.
 */
internal fun videoCommentBar(holders: List<ClassDef>, buttons: Method): Method {
    val renders = videoCommentBarRenders(holders, buttons)
    return renders.singleOrNull()
        ?: refuse("expected one render naming \"$VIDEO_COMMENT_BAR\" in a class building ${buttons.definingClass}, found ${renders.size}")
}

/** Whether [method] makes an instance of [type]. */
private fun makes(method: Method, type: String) = method.implementation?.instructions?.any {
    it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == type
} == true

/** The candidates [videoCommentBar] picks from; none on 585, where the bar has no render of its own. */
internal fun videoCommentBarRenders(holders: List<ClassDef>, buttons: Method): List<Method> =
    holders.filter { holder -> holder.methods.any { makes(it, buttons.definingClass) } }
        .flatMap { methodsHolding(it, VIDEO_COMMENT_BAR) }
        .filter {
            it.name == buttons.name && it.returnType == buttons.returnType &&
                it.parameterTypes.map(CharSequence::toString) == buttons.parameterTypes.map(CharSequence::toString)
        }
        .distinctBy { it.descriptor() }

/**
 * Where 585 builds the video viewer's comment bar inline: [render], the player chrome's method that
 * builds it, [at], the one call the bar goes into alone, [bar], the register it sits in there, and
 * [free], a register nothing reads there.
 */
internal class InlineCommentBar(val render: Method, val at: Int, val bar: Int, val free: Int)

/**
 * 585's video comment bar, from [holders], the classes loading [VIDEO_COMMENT_BAR]. The bar has no
 * component of its own any more: a private method of the player chrome naming [VIDEO_COMMENT_BAR]
 * (585 `LX/08gJ;->A0C` arm64, `LX/08gO;->A0C` armv7) builds it from a static row builder of the
 * same class, the one making [buttons]' class (`A0D`), either bare or wrapped under that transition
 * key in a container of the builder's own return type. Every way leaves it in one register, which
 * holds a literal null where the player shows no bar, and that register goes alone into one call
 * (`LX/01A2;->A12`, a list of what isn't null). So the bar's call is the one call of one register
 * whose writers reaching it are a row builder's result, a literal null and new containers of that
 * type, nothing else, and it takes null already. The hook goes in front of it, so nothing may jump
 * there, and a local register other than the bar's must be free there. Refuses unless each holds.
 */
internal fun inlineCommentBar(holders: List<ClassDef>, buttons: Method): InlineCommentBar {
    val row = buttons.definingClass
    val found = holders.flatMap { holder ->
        val builders = holder.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && makes(it, row) }
            .associateBy { it.descriptor() }
        if (builders.isEmpty()) return@flatMap emptyList()
        methodsHolding(holder, VIDEO_COMMENT_BAR).flatMap { method -> barCalls(method, builders) }
    }.distinctBy { "${it.render.descriptor()}@${it.at}" }
    return found.singleOrNull() ?: refuse(
        "expected one call taking alone the comment bar a method naming \"$VIDEO_COMMENT_BAR\" builds from a static " +
            "builder of $row, or nothing, found ${found.size}",
    )
}

/** In [method], each call [inlineCommentBar] could hook, given [builders], the bar row's static builders by descriptor. */
private fun barCalls(method: Method, builders: Map<String, Method>): List<InlineCommentBar> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    fun Instruction.builder() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let { builders[it.descriptor()] }
    // The row builder's results, each the move-result-object right after a call to one.
    val results = code.indices.filter { it > 0 && code[it].opcode == Opcode.MOVE_RESULT_OBJECT && code[it - 1].builder() != null }
    if (results.isEmpty()) return emptyList()
    val containers = results.mapNotNull { code[it - 1].builder()?.returnType }.toSet()
    val where = method.descriptor()
    return code.indices.mapNotNull { at ->
        val call = code[at]
        if ((call as? ReferenceInstruction)?.reference !is MethodReference) return@mapNotNull null
        val registers = call.namedRegisters()
        if (registers.size != 1) return@mapNotNull null
        val bar = registers.single()
        val writers = method.writersReaching(at, bar)
        if (writers.none { it in results }) return@mapNotNull null
        // A null reaching the call means it already takes one, so clearing the bar is a way it knows.
        val nulls = writers.filter {
            it >= 0 && code[it].opcode in setOf(Opcode.CONST_4, Opcode.CONST_16) && (code[it] as NarrowLiteralInstruction).narrowLiteral == 0
        }
        val wrapped = writers.filter {
            it >= 0 && code[it].opcode == Opcode.NEW_INSTANCE && ((code[it] as ReferenceInstruction).reference as TypeReference).type in containers
        }
        if (nulls.isEmpty() || writers.any { it !in results && it !in nulls && it !in wrapped }) return@mapNotNull null

        val flow = ControlFlow.of(method)
        for (index in code.indices) {
            for (next in flow.normal[index] + flow.exceptional[index]) {
                if (next == at && index != at - 1) refuse("$where has a jump to instruction $at, where the comment bar's hook goes")
            }
        }
        if (at == 0 || flow.normal[at - 1] != listOf(at)) refuse("$where doesn't run straight into instruction $at")
        val live = RegisterLiveness.of(method).liveInto(at)
        if (bar > 255) refuse("$where hands the comment bar over in v$bar, past what a const can clear")
        val free = (0 until minOf(method.localRegisterCount(), 256)).firstOrNull { it !in live && it != bar }
            ?: refuse("$where has no free local register at instruction $at")
        InlineCommentBar(method, at, bar, free)
    }
}

/**
 * First thing where the video viewer's comment bar renders: on a yes from the extension it returns
 * nothing, so the Add a comment box under a full-screen video is gone with its outline (#213, from
 * @dijitalcozum's patch, which cleared only the box's button row). Asking at the start covers every
 * way out of the render, including the returns its jumps land on, which code put in front of a
 * return would be stepped past. Nothing local is live yet there, so v0 holds the answer.
 */
internal fun MutableMethod.hideVideoCommentComposer() {
    if (!returnType.startsWith("L")) refuse("${descriptor()} doesn't return an object")
    if (localRegisterCount() < 1) refuse("${descriptor()} has no local register for the hook")
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $HIDES_VIDEO_COMMENT_COMPOSER
            move-result v0
            if-eqz v0, :bar
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("bar", getInstruction(0)),
    )
}

/**
 * Right before 585's player chrome hands its comment bar on: on a yes from the extension the bar's
 * register is cleared, so the chrome goes on as it does when it shows no bar, and the Add a comment
 * box under a full-screen video is gone with its outline (#213). The clear picks the form that
 * reaches the bar's register.
 */
internal fun MutableMethod.hideInlineCommentBar(bar: InlineCommentBar) {
    val clear = if (bar.bar < 16) "const/4 v${bar.bar}, 0x0" else "const/16 v${bar.bar}, 0x0"
    addInstructionsWithLabels(
        bar.at,
        """
            invoke-static {}, $HIDES_VIDEO_COMMENT_COMPOSER
            move-result v${bar.free}
            if-eqz v${bar.free}, :bar
            $clear
        """,
        ExternalLabel("bar", getInstruction(bar.at)),
    )
}
