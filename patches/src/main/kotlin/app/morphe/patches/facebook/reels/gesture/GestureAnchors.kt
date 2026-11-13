/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.gesture

import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE

/*
 * Where a reel's comments open from a double tap or a long press, found by kept names and shapes
 * only (read from 583, arm64 and armv7, 2026-10-17). The obfuscated names in these comments are for
 * reviewers; the code never writes one down.
 *
 * - The Comment button on a reel's sidebar is one object per reel (arm64 LX/ARV;, armv7 LX/ATE;),
 *   public and final. Its static click handler (A00) takes the clicked view, the object and a
 *   boolean, and is the one method in either build with that shape holding "tap_comment_button" (the
 *   same click, logged "ufi_comment_tap", or "ufi_comment_count_tap" when the boolean says the
 *   count was tapped). The object has one constructor, made by the sidebar's builder, and takes the
 *   reel the sidebar draws: the one parameter whose type is the type the reel like helper's
 *   double-tap like takes first (arm64 LX/5mF;, armv7 LX/5me;). Pressing the handler with the
 *   gesture's own view and the reel's object opens the same sheet the button opens.
 * - A double tap's reel reaches the reel like helper's double-tap like (arm64 LX/AQS;->A03,
 *   armv7 LX/ASB;->A03), which Turn off double tap to like hooks too. It is an instance method taking
 *   the reel first, so this patch asks the extension at its first instruction whether to take the
 *   double tap, and returns without liking when it does. The view a double tap landed on is the
 *   second parameter of the gesture view's static heart, which holds "shortFormVideoUnit".
 * - The reel controls' long-press handler is a Kotlin lambda (arm64 LX/8NS;, armv7 LX/8OZ;) that
 *   keeps $shouldDispatchLongPressEventDirectly, the reel it captured ($shortFormVideoUnit) and the
 *   config, and holds "speed_up". Its invoke takes the gesture (a holder with the MotionEvent and
 *   the View, arm64 LX/D6k;, armv7 LX/DAG;) and returns Kotlin's Unit, which Redex renames, so the
 *   Unit is read from the static field the method's own early return reads.
 * - In Clean mode (Clean up Reels' Always use clean mode) the sidebar is never built, so a reel has no
 *   Comment button object (#179). The long-press handler is made by the overlay and the video controls
 *   (arm64 LX/7vw; and LX/7w1;) and keeps, by the names Kotlin gives them, what the button's click
 *   hands the comment sheet: the session, the reel, the viewer's environment (arm64 LX/VrH;), the
 *   reel's story holder (LX/7t9;) and the viewer config (LX/9el;). The click builds the sheet with one
 *   static (LX/VtZ;->A00), the one call in it taking a FlyoutComposerAction and a SearchPromptParam
 *   (kept names), from the environment's surface (VrH.BSX) and the holder's story merged with the reel
 *   and wrapped (LX/8SK;->A00, LX/2Zf;->A05), then shows it with a static taking a Context and the
 *   sheet (VtZ.A01) unless split screen takes it. 587 moved the builder's feedback argument from 8 to
 *   4, ahead of the surface and the props; given null there, the builder takes the feedback from the
 *   props (read on both ABIs). The patch adds a helper to the extension making the
 *   same calls with the values the click passes, which the extension uses for a reel with no button,
 *   keeping each handler per reel from the end of its constructor. Clean mode has no overlay, and the
 *   controls' handler (LX/7w1;->A1C makes it) always keeps a null story holder, so the helper then
 *   makes one with the holder's (reel, Integer) constructor, whose story getter derives the story from
 *   the reel when it has none, as Facebook's own holders for a reel without a story do.
 *
 * - A feed post has no double-tap or long-press handler in either build (every double-tap site is a
 *   reel, story, tab bar or Meta AI one), so its long press is timed by the extension from the first
 *   instruction of FbFragmentActivity.dispatchTouchEvent, the hook Tap to play, Hold a reel for 2x
 *   and the Messenger icon share. It gets the activity too and may set the rest of a gesture it took
 *   to a cancel. The extension finds the post's Comment button by the footer's order, not a label.
 * - The Comment button opens the comment sheet to write: its parameters (FeedbackParams, a kept
 *   name) carry showKeyboardOnFirstLoad, the boolean toString labels ", showKeyboardOnFirstLoad="
 *   (arm64 and armv7 A0u), which the sheet reads in FeedbackFragment.maybeShowKeyboardOnFirstLoad to
 *   focus the comment box with the keyboard up. Every reader reads the instance the builder made, and
 *   the Parcel constructor copies what writeToParcel wrote, so the one hook is the other constructor
 *   (it takes the builder, 5 registers in both builds): just before its one store of the flag the
 *   extension may turn the value to false, for the sheet a feed hold opened. Not at its returns: by
 *   then it has reused the object's register (v3) for a long, and reading the flag from there failed
 *   verification on the S22, so every comment sheet crashed. The store's value register (v0) is
 *   written again by the next instruction in both builds.
 * - Hold a post to react (#454) long-clicks the post's Like itself, which opens the reaction tray
 *   (RopeStyleUFIDockView, a kept name, read from 587 on both ABIs) and tracks the rest of the
 *   gesture through Like. To give one chosen reaction, the extension clicks the tray through its
 *   accessibility helper, so it needs the tray: every constructor of the class hands the new tray
 *   to the extension just before it returns, reading this (one constructor in 587, taking a Context
 *   and the session, 13 registers). The range form borrows no register.
 *
 * What isn't covered: a feed post's double tap (every part of a post already reacts to a single tap,
 * so a second tap can't be waited for without delaying it), a reel with neither a Comment button
 * object nor a long-press handler made for it, and the full-height sheet (no switch for the
 * comment sheet's height was found that doesn't move every flyout).
 */

internal const val GESTURE_PATCH = "Open comments with a gesture"

/** The log name the Comment button's click handler holds. */
internal const val COMMENT_TAP = "tap_comment_button"

/** The field name Kotlin gives the reel a lambda captured. */
internal const val ITEM_FIELD = "\$shortFormVideoUnit"

internal const val OBJECT = "Ljava/lang/Object;"
internal const val VIEW = "Landroid/view/View;"

internal const val REEL_GESTURES = "$EXTENSION_PACKAGE/reels/ReelGestures;"
internal const val OPEN_STUB = "openStub"
internal const val REMEMBER = "$REEL_GESTURES->remember(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val NOTE_VIEW = "$REEL_GESTURES->noteView(Ljava/lang/Object;)V"
internal const val DOUBLE_TAP = "$REEL_GESTURES->doubleTap(Ljava/lang/Object;)Z"
internal const val LONG_PRESS = "$REEL_GESTURES->longPress(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val REMEMBER_HANDLER = "$REEL_GESTURES->rememberHandler(Ljava/lang/Object;Ljava/lang/Object;)V"

/** The extension's stub for a reel with no Comment button, and the helper the patch adds for it to call. */
internal const val SHEET_STUB = "openSheetStub"
internal const val SHEET_HELPER = "openSheet"

/** What the long-press handler keeps that the comment sheet needs, by the names Kotlin gives them. */
internal const val SESSION_FIELD = "\$fbUserSession"
internal const val ENVIRONMENT_FIELD = "\$environment"
internal const val STORY_FIELD = "\$videoGraphQLPhoton"
internal const val CONFIG_FIELD = "\$immersiveFeedPlayerConfig"

internal const val CONTEXT = "Landroid/content/Context;"
internal const val STRING = "Ljava/lang/String;"
internal const val LIST = "Ljava/util/List;"
internal const val INTEGER = "Ljava/lang/Integer;"
internal const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
internal const val GRAPHQL_STORY = "Lcom/facebook/graphql/model/GraphQLStory;"
internal const val GRAPHQL_COMMENT = "Lcom/facebook/graphql/model/GraphQLComment;"
internal const val COMPOSER_ACTION = "Lcom/facebook/ufiservices/flyout/composer/FlyoutComposerAction;"
internal const val SEARCH_PROMPT = "Lcom/facebook/ufiservices/flyout/params/SearchPromptParam;"
internal const val FEED_PRESS = "$EXTENSION_PACKAGE/reels/PostLongPress;->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)V"

/** The comment sheet's parameters, the label its toString gives the keyboard flag, and the parcel its other constructor reads. */
internal const val FEEDBACK_PARAMS = "Lcom/facebook/ufiservices/flyout/params/FeedbackParams;"
internal const val KEYBOARD_LABEL = ", showKeyboardOnFirstLoad="
internal const val PARCEL = "Landroid/os/Parcel;"
internal const val KEYBOARD = "$EXTENSION_PACKAGE/reels/PostLongPress;->keyboardOnFirstLoad(Z)Z"

/** Where each reaction tray made goes, for Hold a post to react to click a chosen reaction in. */
internal const val DOCK_MADE = "$EXTENSION_PACKAGE/reels/ReactionHold;->dockMade(Ljava/lang/Object;)V"
