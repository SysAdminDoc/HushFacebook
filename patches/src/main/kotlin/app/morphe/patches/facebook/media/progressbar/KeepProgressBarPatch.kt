/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.liveAcrossInjection
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Keep the progress bar"

/** The name the Reels viewer's bottom progress bar plugin gives itself, a kept literal. */
internal const val REEL_SEEK_BAR_PLUGIN = "FbShortsViewerBottomSeekBarPlugin"

/**
 * The trace names the unified video scrubber (the Reels viewer's bar on 581) gives its two looks:
 * full size, with the thumb showing, and the thin passive line. Kept literals, one per method.
 */
internal const val SCRUBBER_ACTIVE = "VDDScrubberPlugin.updateToActiveScrubber"
internal const val SCRUBBER_PASSIVE = "VDDScrubberPlugin.updateToPassiveScrubber"

/** The trace name of the scrubber base's progress update, a kept literal. */
internal const val SCRUBBER_UPDATE = "UDDScrubberBasePlugin.updateProgress"

/** The trace name of the scrubber base's light mount, run as it takes each video, a kept literal. */
internal const val SCRUBBER_LIGHT_MOUNT = "UDDScrubberBasePlugin.onLightMount"

/** The trace name of the scrubber base's load, run as its player gives it a reel, a kept literal. */
internal const val SCRUBBER_LOAD = "UDDScrubberBasePlugin.onLoad"

/** The surface name both of the scrubber's length checks compare first, a kept literal. */
internal const val LENGTH_CHECK_SURFACE = "ai_styles_drafts"

/** The names of the Reels footer's seek bar status values, kept enum literals (582 `LX/AGw;`). */
internal const val STATUS_HIDDEN = "HIDDEN"
internal const val STATUS_VISIBLE = "VISIBLE"
internal const val STATUS_DRAGGING = "DRAGGING"

internal const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
internal const val FUNCTION1_INVOKE = "$FUNCTION1->invoke(Ljava/lang/Object;)Ljava/lang/Object;"

/** The four looks of a reel's footer (585 `LX/0B73;`). Seeking clears the name, the caption and the buttons. */
internal const val FOOTER_SHOWN = "SHOW_FOOTER"
internal const val FOOTER_SEEKING = "SHOW_VIDEO_SETTINGS"
internal const val FOOTER_HIDDEN = "HIDE_FOOTER"
internal const val FOOTER_CLEAR = "CLEAR_MODE"

/** How long, in ms, the kept bar waits between moves: about a frame, where Facebook's default is 100. */
internal const val SMOOTH_FRAME_DELAY_MS = 16

internal const val TEXT_VIEW_SET_TEXT = "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V"

/** A kept class whose superclass is the full-screen video controls every such player builds on. */
internal const val FULLSCREEN_CONTROLS = "Lcom/facebook/feed/video/fullscreen/orion/FeedFullscreenVideoControlsPlugin;"

/**
 * The parameters of a reel's bar info constructor, sorted: has a bar, scrubbable and faded, the three
 * booleans in that order, then the length and the video id. 582 (`LX/AGr;`) and 583 take them as
 * `(ZZZILjava/lang/String;)V`, 584 (`LX/9UZ;`) as `(Ljava/lang/String;IZZZ)V`.
 */
internal val BAR_INFO_PARAMETERS = listOf("I", "Ljava/lang/String;", "Z", "Z", "Z")

internal const val SEEK_BAR = "Landroid/widget/SeekBar;"
internal const val GET_PROGRESS = "Landroid/widget/ProgressBar;->getProgress()I"
internal const val SET_PROGRESS = "Landroid/widget/ProgressBar;->setProgress(I)V"
internal const val SET_ALPHA = "Landroid/graphics/drawable/Drawable;->setAlpha(I)V"
internal const val SET_ENABLED = "Landroid/view/View;->setEnabled(Z)V"
internal const val SEND_DELAYED = "Landroid/os/Handler;->sendEmptyMessageDelayed(IJ)Z"
internal const val REMOVE_MESSAGES = "Landroid/os/Handler;->removeMessages(I)V"

/** The name the newer player's video controls extension gives itself, a kept literal. */
internal const val CONTROLS_EXTENSION = "VideoControlsExtension"
internal const val POST_DELAYED = "Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z"
internal const val REMOVE_CALLBACKS = "Landroid/os/Handler;->removeCallbacksAndMessages(Ljava/lang/Object;)V"
internal const val RUNNABLE = "Ljava/lang/Runnable;"
internal const val PLAYER_CONTROL_UPDATE = "updateState:FbShortsVideoControlComponent.updatePlayerControl"

internal const val PROGRESS_BAR = "$EXTENSION_PACKAGE/media/ProgressBar;"
internal const val KEEPS_REEL_BAR = "$PROGRESS_BAR->keepsReelBar()Z"
internal const val CLEARS_LAST_REEL = "$PROGRESS_BAR->clearsLastReel()Z"
internal const val LIST_CLEAR = "Ljava/util/List;->clear()V"
internal const val SET_VISIBILITY = "Landroid/view/View;->setVisibility(I)V"
internal const val SET_VIEW_ALPHA = "Landroid/view/View;->setAlpha(F)V"
/** 1.0f's bits, the `const/high16` an alpha back to full loads. */
internal const val FULL_ALPHA = 0x3f800000
internal const val KEEPS_CONTROLS = "$PROGRESS_BAR->keepsControls()Z"
internal const val HIDE_TIME_LABEL = "$PROGRESS_BAR->hideTimeLabel(Landroid/view/ViewGroup;)V"
internal const val BARS_EVERY_REEL = "$PROGRESS_BAR->barsEveryReel()Z"
internal const val HAS_BAR = "$PROGRESS_BAR->hasBar(ZI)Z"
internal const val REFRESHES_TIME = "$PROGRESS_BAR->refreshesTime(Landroid/view/ViewGroup;Landroid/view/View;)Z"
internal const val NEXT_REEL = "$PROGRESS_BAR->nextReel(Landroid/view/ViewGroup;Landroid/view/View;)V"
internal const val SMOOTHS_BAR = "$PROGRESS_BAR->smoothsBar()Z"
internal const val LIFTS_FOOTER = "$PROGRESS_BAR->liftsFooter()Z"
internal const val FOOTER_MADE = "$PROGRESS_BAR->footerMade(Ljava/lang/Object;)V"

/** The extension's stub the patch fills with a call to [FOOTER_LIFT_HELPER]. */
internal const val LIFT_FOOTER_STUB = "liftFooter"

/** The helper the patch adds to the footer's status holder, which the stub calls. */
internal const val FOOTER_LIFT_HELPER = "hushfacebookLiftFooter"
internal const val NO_FADED_BAR = "$PROGRESS_BAR->noFadedBar()Z"
internal const val COUNTS_LOOK = "$PROGRESS_BAR->look(Ljava/lang/Integer;)V"
internal const val REPLACES_PARKED = "$PROGRESS_BAR->replacesParkedLook(Ljava/lang/Integer;Ljava/lang/Integer;)Z"
internal const val HANDS_OVER =
    "$PROGRESS_BAR->handsOver(Landroid/view/ViewGroup;Landroid/view/View;Ljava/lang/String;Ljava/lang/String;)V"
internal const val LEFT_ON_LAST_REEL = "$PROGRESS_BAR->leftOnLastReel()V"
internal const val REGISTRY_ASKS = "$PROGRESS_BAR->registryAsks(ZLjava/lang/Integer;)V"
internal const val NO_SCRUBBER = "$PROGRESS_BAR->noScrubber()V"
internal const val MOUNTS = "$PROGRESS_BAR->mounts(Landroid/view/ViewGroup;Ljava/lang/Object;)V"
internal const val LOADS = "$PROGRESS_BAR->loads(Landroid/view/ViewGroup;Ljava/lang/Object;Ljava/lang/String;)V"
internal const val KEEPS_DEFAULT_HOLDER = "$PROGRESS_BAR->keepsDefaultHolder(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val KEEPS_OVERLAY = "$PROGRESS_BAR->keepsOverlayWhileSeeking()Z"

/** The extension's stub the patch fills with a call to the scrubber base's progress update. */
internal const val UPDATE_AGAIN_STUB = "updateAgain"
internal const val LOOK_FOR = "$PROGRESS_BAR->lookFor(Landroid/view/ViewGroup;Ljava/lang/Integer;)Ljava/lang/Integer;"
internal const val LOOKS_AGAIN = "$PROGRESS_BAR->looksAgain(Landroid/view/ViewGroup;)Z"

/** The trace name of the Reels scrubber registry's hand-over as a new reel shows, a kept literal. */
internal const val NEW_REELS_UNIT_SHOWN = ".onNewReelsUnitShown"
internal const val TAKES_TOUCH =
    "Lapp/morphe/extension/facebook/media/TapToSeek;->takesTouch(${SEEK_BAR}Landroid/view/MotionEvent;)Z"
internal const val VIEW_GROUP = "Landroid/view/ViewGroup;"

internal const val ON_TOUCH = "onTouchEvent(Landroid/view/MotionEvent;)Z"
internal const val ON_DRAW = "onDraw(Landroid/graphics/Canvas;)V"
internal const val INVALIDATE = "Landroid/view/View;->invalidate()V"

/**
 * Keeps a video's progress bar on screen, in the players that hide theirs a few seconds in.
 *
 * The unified video scrubber, which is the bar of 581's Reels viewer, has an "active" look (full
 * height track, thumb at full alpha) and a "passive" one (a 2 dp line, thumb hidden) that Facebook
 * switches to before a touch and again about a second after a drag ends. Both are `()V` methods
 * that begin by tracing their own name, [SCRUBBER_ACTIVE] and [SCRUBBER_PASSIVE] (582 `LX/9d9;`
 * `A19` and `A1B`, 581 `LX/RV4;` `A18` and `A19`, 580 `LX/Rln;` `A15` and `A16`, 577 `LX/SHD;` `A18` and `A19`). The extension
 * goes first in the passive one, and while the switch is on it runs the active one instead, then
 * hides the scrubber's time label and returns. The passive look hides that label (a ViewGroup
 * holding the elapsed and total time, `A09` of the scrubber's views holder) and the active one
 * shows it, and only a drag updates the times, so without the hide the label would sit frozen at
 * 0:00 over the reel's author row. A drag still shows it, as the touch path calls the active look
 * itself and the seek bar listener sets the label's visibility. The hook hides the "most replayed"
 * graph over the bar too, the way the passive look does: the active look draws it for the reel the
 * scrubber is on, and only the passive look takes it away, so one reel's graph stayed over the next
 * reel's bar until a drag. A drag shows it as before. Facebook also hands its one Reels scrubber
 * reel after reel without asking for a look again, so a hook in the scrubber's light mount, on
 * its branch for another video, hides the graph and runs Facebook's own reset of the chapter marks
 * (582 `LX/9d9;->A0C`), which brings back the bar where the last reel's marks had drawn it (seen on
 * the emulator: a 2:15 clip's marks and graph on the 0:16 reel after it, which then had no bar).
 * With the time switch on, the label
 * stays instead, and the scrubber's progress update has Facebook's own time writer refresh it a few
 * times a second, which Facebook does only during a drag. That call goes where the update asks
 * whether you're dragging, which it reaches only after it has found the reel's position and length
 * usable and moved the bar. The writer checks neither and moves the bar too, so asked any earlier
 * it put the bar back at the start whenever the player wasn't ready, as on the first reel while
 * its player is handed over (issue #120). The active look starts with that writer too, so the hook
 * puts the bar back where it was after running it, as the passive look never moves the bar. Letting
 * go of a drag switches to the passive look just before Facebook reads the bar to seek, so on a reel
 * showing preview frames during the drag, whose player hasn't moved, the seek went back to where the
 * drag began, and where the reel's length wasn't known yet the bar went to the start. Facebook asks
 * for a look only on a touch or a change of look, so ahead of the refresh the update asks the
 * extension whether the scrubber's look still matches the switch, and runs the passive look when it
 * doesn't: flipped with Reels open, the bar had stayed thin (on) or full size with its time stopped
 * (off) until a touch or a restart.
 *
 * More hooks finish the Reels bar (582, issue #73). The scrubber's bar info says first whether a
 * reel has a bar at all, and Facebook says no for an ad, for what the viewer's config rules out and
 * for some reels it flags on its servers, so the info's builder asks the extension, and while kept
 * a reel with a known length gets one. The scrubber's minimum length check answers
 * yes, so a reel Facebook finds too short for a bar gets one. Its other length check, which gives a
 * short reel the look that fades the bar out (alpha 0) when a server flag is on, answers no. Both
 * hooks go on the Reels tab's scrubber too, which overrides the two checks. The scrubber's look
 * selector reports the looks that still hide the bar, for the diagnostic report, and skips a look
 * the bar has only when no hide is parked: a hide parked during a player change otherwise landed on
 * the next reel to reuse the scrubber.
 * And the wait before the scrubber's next progress update, Facebook's `defaultFrameDelayMs` of
 * 100 ms, drops to about a frame, so the bar moves smoothly instead of in visible steps.
 *
 * Facebook hands its Reels scrubber each reel that shows from one registry method tracing
 * [NEW_REELS_UNIT_SHOWN] (583 `LX/8fL;->A00`), which binds it to the reel's player and so runs the
 * light mount, where the next reel's reset lives (583 runs the mount inline there, 582 called the
 * scrubber base's `LX/9c4;->A0m`). That method returns before the bind when the
 * reel's player has no parameters yet or the reel has no id, and the scrubber stays on the last
 * reel: on a phone (#90, #120) the kept bar and its time stopped where the last reel was after Up
 * next played the next reel or after a swipe, and a touch showed 0:00 over the last reel's length,
 * read off the last reel's bar info, with no progress update running. So a hook right after the method reads
 * the reel's id tells the extension which reel the scrubber holds and which is on screen, one at the
 * method's single return asks whether the light mount ran (a hook first in the mount reports that),
 * and when it didn't, the extension hides the bar and its time until a reel takes the scrubber. A
 * hook first in the look selector hands the extension each look, and while the bar is hidden that
 * way a look that would show the last reel's bar is dropped. The light mount also starts the
 * scrubber's progress update, which stops for good when it can't draw the reel yet: the pager still
 * scrolling, no player ready, or no position or length, the last leaving the update marked as
 * running with nothing scheduled. Only the scroll settling and a change in the player's state start
 * it again, and after a fast double swipe on a phone both could come too early, so the reel on screen
 * played with its bar at the start and no time (#120). So the mount's hook hands the extension the
 * scrubber too, and the patch fills the extension's [UPDATE_AGAIN_STUB] with a run of the base's
 * progress update, which the extension calls every half second until the update draws the reel.
 *
 * The Reels footer lays the caption out from a seek bar status (582 `LX/AGv;` holding a `LX/AGw;`):
 * HIDDEN while a reel plays, VISIBLE while it's paused, DRAGGING during a drag. Only VISIBLE lifts
 * the caption, by about 35 dp, above where the bar and its time go. While playing it sat laid out
 * for Facebook's 2 dp line, so the kept bar and its time landed on the caption. So the holder starts
 * at VISIBLE instead of HIDDEN, and its update stores VISIBLE where it would store HIDDEN and stops
 * there when VISIBLE was already the status. DRAGGING still applies, and the HIDDEN after a drag
 * becomes VISIBLE, so the footer doesn't stay in its drag layout. The update runs only on a player
 * event, so the reel on screen as the switch went on kept its playing layout and its time label sat
 * on its caption (#73). So each holder reports itself to the extension as it's made, and its update,
 * handed no event (Facebook's code returns at once for one, and only the helper the patch adds to the
 * holder calls it so), takes HIDDEN to VISIBLE through Facebook's own store, Function1 call and event
 * while the bar is kept. The extension runs that helper on each holder as the switch goes on.
 *
 * Older builds' Reels viewer used a bottom bar plugin, named [REEL_SEEK_BAR_PLUGIN], sizes its SeekBar with two
 * static (SeekBar, plugin) methods (581 `LX/8sg;->A00` and `A01`, still present on every declared
 * build): one shrinks it to a 2 dp line,
 * hides the thumb and turns drags off, the other makes it full size with the thumb at full alpha
 * and drags on. Facebook shrinks it when a reel's controls go away and when the reel plays on, so
 * the extension goes first in the shrinking one, and while the switch is on it calls the other
 * instead and returns.
 *
 * A full-screen video's controls are a plugin built on one class, the superclass of the kept
 * [FULLSCREEN_CONTROLS] (581 `LX/ReC;`), which also carries Orion, live and Watch and more
 * controls. Its one method that calls [SEND_DELAYED] (581 `A16`) sets the timer whose message fades
 * the controls out, each time they show and each time they're touched. The extension goes first
 * there, and while the switch is on no timer is set, so the controls and their bar stay until a tap
 * hides them the way it always has.
 *
 * The newer Litho player hides its controls through an extension named [CONTROLS_EXTENSION] (581
 * `LX/RbF;`). Its one method that drops its pending callbacks and posts a runnable (581 `A0N`) sets
 * the hide 3 seconds after the controls are touched, 10 with accessibility on. The same hook goes
 * first there.
 *
 * The player the video viewer's Enter fullscreen landscape mode opens on 577 to 581 runs on the
 * Reels controls component instead, whose updater traces [PLAYER_CONTROL_UPDATE] (581
 * `LX/87v;->Don`). A controller built with that updater's interface (581 `LX/87w;`) shows the
 * controls on a tap and posts its hide runnable (581 `LX/87x;`), whose `run()` asks the controller
 * to hide them about 3 seconds later. On 583 the updater is folded into the controller, a static
 * method of it (`LX/8Mz;->A00`), and the runnable is `LX/8N0;`. On 585 the updater is a class of its
 * own again, no interface (arm64 `LX/09hT;->A00`), that the controller (`LX/09hU;`) takes in its constructor. On the S25 (581, 2026-10-08) a stack trace of that hide went
 * from this `run()` through the controller to the updater, and none of the hooks above ran in that
 * player. So the same hook goes first in this `run()`. A tap still hides the controls, as the tap
 * handler calls the controller's hide itself.
 *
 * Tap the bar to seek (issue #125) is its own switch. The scrubber's SeekBar (582 `LX/9A0;`, found
 * from the base's `instance-of` on the bar it reads) runs one of two ways in its own
 * `onTouchEvent`, and a server setting picks which per account. Thumb-only turns away a press that
 * misses the thumb. Drag-anywhere hands every touch to Android's SeekBar, yet a tap there still
 * doesn't seek, as Facebook passes it on to the video. A drag that starts on the thumb seeks either
 * way. So the extension sees each touch first, holds a press that misses the thumb, and plays it
 * back to the handler as a drag from the thumb to where it lifted. On the emulator (2026-10-12)
 * the account ran drag-anywhere, where a hook at the thumb check never ran. On 583 (bar
 * `LX/9GA;`) the scrubber's listener can post its start of a scrub as a drag starts, so the
 * played drag lifts a moment later, after that start ran (#120).
 *
 * In the default selection with its switch off: it only acts once the switch is turned on.
 */
@Suppress("unused")
val keepProgressBarPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Keep the progress bar",
    description = "Keeps every reel's progress bar full size and moving smoothly, with the time above it, so you can " +
        "drag it without tapping first. Also keeps a full-screen video's controls on screen until you tap, and " +
        "another switch lets a tap anywhere on the bar jump there. Starts off. Turn it on in Hushfacebook settings > " +
        "Video and audio.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        val scrubber = vddScrubber(
            classDefByStrings(SCRUBBER_PASSIVE, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        // The older bottom bar plugin is kept hooked where a build still has it, but needn't exist.
        val pluginHolders = classDefByStrings(REEL_SEEK_BAR_PLUGIN, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val plugin = if (pluginHolders.isEmpty()) null else reelSeekBarPlugin(pluginHolders)
        val sizes = plugin?.let { barSizes(it) }
        val controls = classDefByOrNull(FULLSCREEN_CONTROLS)?.superclass
            ?.let { classDefByOrNull(it) }
            ?: refuse("FeedFullscreenVideoControlsPlugin or the controls class it extends isn't in this APK")
        val timer = fadeTimer(controls)
        val (extension, hide) = extensionFadeTimer(
            classDefByStrings(CONTROLS_EXTENSION, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val updater = controlsUpdater(
            classDefByStrings(PLAYER_CONTROL_UPDATE, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        ) { classDefByOrNull(it) }
        val graph = graphHide(scrubber) { classDefByOrNull(it) }
        val scrubberClass = classDefByOrNull(scrubber.type) ?: refuse("${scrubber.type} went missing")
        val lengthChecks = scrubberLengthChecks(scrubberClass)
        val progress = progressUpdate(
            classDefByStrings(SCRUBBER_UPDATE, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        if (progress.base.type != scrubberBase(scrubber) || progress.base.type !in listOfNotNull(scrubberClass.type, scrubberClass.superclass)) {
            refuse("${progress.base.type}, which updates the progress, isn't the class holding ${scrubber.type}'s views")
        }
        progress.update.freeLocalsAt(PATCH, dragCheck(progress.update, progress), 2)
        val info = barInfo(progress.base, lengthChecks?.minimum)
        val handOver = reelHandOver(
            classDefByStrings(NEW_REELS_UNIT_SHOWN, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
            progress.base.type,
        )
        handOver.method.freeLocalsAt(PATCH, handOver.at, 3, parameters = true)
        val returns = registryReturns(handOver)
        val another = anotherVideo(progress.base, info.builder.returnType, handOver)
        val load = scrubberLoad(progress.base, another, returns)
        load.method.freeLocalsAt(PATCH, load.at, 3)
        val holderClear = holderClear(progress.base, returns)
        holderClear.method.freeLocalsAt(PATCH, holderClear.clear, 2, targets = listOf(holderClear.store + 1))
        // Only a mount of the base's own is run virtually, so only then can a class extending it skip it.
        val baseMount = another.mount.definingClass == progress.base.type
        val delay = frameDelay(progress)
        val selector = lookSelector(progress.base, scrubber)
        val skip = sameLookSkip(selector) { classDefByOrNull(it) }
        val status = seekBarStatus(
            classDefByStrings(STATUS_DRAGGING, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val visibility = footerVisibility(
            classDefByStrings(FOOTER_SEEKING, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val controllers = mutableListOf<ClassDef>()
        val statusOwners = mutableListOf<ClassDef>()
        val visibilityOwners = mutableListOf<ClassDef>()
        val checkHolders = mutableListOf<ClassDef>()
        val mountHolders = mutableListOf<ClassDef>()
        val scrubberSubclasses = mutableListOf<ClassDef>()
        classDefForEach {
            if (it.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
            if (it.superclass == scrubber.type) scrubberSubclasses += it
            if (takesUpdater(it, updater)) controllers += it
            if (it.fields.any { field -> field.type == status.type }) statusOwners += it
            if (it.fields.any { field -> field.type == FUNCTION1 }) visibilityOwners += it
            if (lengthChecks != null && it.type != scrubber.type &&
                it.methods.any { method -> method.sameAs(lengthChecks.minimum) || method.sameAs(lengthChecks.short) }
            ) {
                checkHolders += it
            }
            if (baseMount && it.type != progress.base.type && it.methods.any { method -> method.sameAs(another.mount) }) mountHolders += it
        }
        val chapters = chapterReset(scrubberClass, scrubber, scrubberSubclasses) { classDefByOrNull(it) }
        val (hider, hiderRun) = controlsHideRunnable(controllers) { classDefByOrNull(it) }
        val footer = statusHolder(statusOwners, status)
        checkFooterLift(statusOwners.first { it.type == footer.type }, footer)
        val footerSeek = footerSeek(visibilityOwners, visibility)
        if (classDefByOrNull(PROGRESS_BAR)?.methods?.any(::isLiftFooterStub) != true) {
            refuse("$PROGRESS_BAR has no static boolean $LIFT_FOOTER_STUB(Object)")
        }
        if (classDefByOrNull(PROGRESS_BAR)?.methods?.any(::isUpdateAgainStub) != true) {
            refuse("$PROGRESS_BAR has no static void $UPDATE_AGAIN_STUB(Object)")
        }
        checkUpdateAgain(progress)
        val overrides = if (lengthChecks == null) emptyList() else {
            lengthCheckOverrides(checkHolders, scrubber.type, listOf(lengthChecks.minimum, lengthChecks.short)) {
                classDefByOrNull(it)?.superclass
            }
        }
        val skippedMounts = mountOverridesSkippingBase(mountHolders, progress.base.type, another.mount) {
            classDefByOrNull(it)?.superclass
        }
        if (skippedMounts.isNotEmpty()) {
            refuse("${skippedMounts.joinToString()} override the light mount ${another.mount.name} without calling the base's, so the next reel's reset wouldn't run there")
        }
        val barType = scrubberSeekBar(progress.base, scrubber.barField)
        // The extension takes the bar as a SeekBar, and the verifier holds the class to that.
        if (!extendsType(barType, SEEK_BAR) { classDefByOrNull(it)?.superclass }) refuse("$barType, the scrubber's bar, isn't a SeekBar")
        val touch = barTouch(classDefByOrNull(barType) ?: refuse("$barType, the scrubber's SeekBar, isn't in this APK"))

        mutableClassDefBy(scrubber.type).methods.single { it.sameAs(scrubber.passive) }
            .activeInstead(scrubber, graph)
        // Each hook goes in below the ones that follow it, so their indices, the unpatched methods', hold:
        // the hand-over's return first, then the mount, which 583 runs inline between the two, then the
        // hook at the id's test. The bar's start-over goes in before the clear, so the clear lands in
        // front of it and every way out of the clear, a no included, runs it.
        val handOverMethod = mutableClassDefBy(handOver.method.definingClass).methods.single { it.sameAs(handOver.method) }
        handOverMethod.checksHandOver(handOver)
        mutableClassDefBy(another.mount.definingClass).methods.single { it.sameAs(another.mount) }.apply {
            startsNextReelOver(scrubber, another)
            clearsLastReel(scrubber, graph, chapters, another)
            reportsMount(scrubber, another)
        }
        handOverMethod.notesHandOver(handOver, scrubber, another)
        // The registry's two early returns come ahead of the hook at the id's test, the later one first.
        handOverMethod.notesNoScrubber(returns)
        handOverMethod.notesRegistryAsk(returns)
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(load.method) }.reportsLoad(scrubber, load, another)
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(holderClear.method) }.keepsNewerHolder(holderClear, load, returns)
        // 584 has no length checks of their own: the bar info hook answers them in the builder.
        if (lengthChecks != null) {
            mutableClassDefBy(scrubber.type).methods.single { it.sameAs(lengthChecks.minimum) }.longEnoughWhileKept()
            mutableClassDefBy(scrubber.type).methods.single { it.sameAs(lengthChecks.short) }.notShortWhileKept()
        }
        for ((type, check) in overrides) {
            val method = mutableClassDefBy(type).methods.single { it.sameAs(check) }
            if (check.sameAs(lengthChecks!!.minimum)) method.longEnoughWhileKept() else method.notShortWhileKept()
        }
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(info.builder) }.hasBarWhileKept(info)
        // The look check goes in after the time refresh, at the index the refresh took, so it lands
        // in front of it and a no to the look check still runs the refresh.
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(progress.update) }.apply {
            val dragAt = dragCheck(this, progress)
            refreshesTimeWithBar(progress, scrubber)
            looksAgainFirst(scrubber, dragAt)
        }
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(delay) }.smoothWhileKept()
        // The skip's index is the unpatched selector's, so its hook goes in before the one at the top.
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(selector) }.apply {
            replacesParkedLook(skip)
            countsLook()
            asksLook(scrubber)
        }
        // The footer is reported after its start is lifted, at the same index, so the report lands in front.
        mutableClassDefBy(footer.type).methods.single { it.sameAs(footer.constructor) }.apply {
            startsLifted(footer, status)
            reportsFooter(footer)
        }
        // The lift on ask jumps to the store staysLifted follows, so staysLifted goes in first.
        mutableClassDefBy(footer.type).methods.single { it.sameAs(footer.update) }.apply {
            staysLifted(footer, status)
            liftsWhenAsked(footer, status)
        }
        fillFooterLift(footer)
        mutableClassDefBy(footerSeek.type).methods.single { it.sameAs(footerSeek.method) }.keepsOverlayOnPress(footerSeek, visibility)
        fillUpdateAgain(progress)
        if (plugin != null && sizes != null) {
            mutableClassDefBy(plugin.type).methods.single { it.sameAs(sizes.shrink) }
                .fullSizeInstead(plugin.type, sizes.fullSize)
        }
        mutableClassDefBy(controls.type).methods.single { it.sameAs(timer) }.noTimerWhileKept()
        mutableClassDefBy(extension.type).methods.single { it.sameAs(hide) }.noTimerWhileKept()
        mutableClassDefBy(hider.type).methods.single { it.sameAs(hiderRun) }.noTimerWhileKept()
        mutableClassDefBy(barType).methods.single { it.sameAs(touch) }.takesTapsToSeek()
        enableStatus("keepProgressBar")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
    parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun Method.calls(reference: String) = implementation?.instructions?.any {
    it.opcode.name.startsWith("invoke") && (it as ReferenceInstruction).reference.toString() == reference
} == true

/**
 * The Reels viewer's bottom progress bar plugin: the one class of [holders] with a `()String`
 * method loading [REEL_SEEK_BAR_PLUGIN], the name the plugin gives itself.
 */
internal fun reelSeekBarPlugin(holders: List<ClassDef>): ClassDef {
    val plugins = holders.filter { holder ->
        methodsHolding(holder, REEL_SEEK_BAR_PLUGIN).any {
            it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }
    }.distinctBy { it.type }
    return plugins.singleOrNull()
        ?: refuse("expected one plugin naming itself \"$REEL_SEEK_BAR_PLUGIN\", found ${plugins.size}")
}

/** The unified scrubber's class and its two looks. */
internal class ScrubberLooks(
    val type: String,
    val active: Method,
    val passive: Method,
    /** The scrubber's field holding its views (582 `LX/9c4;->A0J`), and the time label field in that holder (`A09`). */
    val viewsField: String,
    val labelField: String,
    /** The SeekBar field in that holder (582 `LX/9DH;->A0A`). */
    val barField: String,
)

/**
 * The class whose field holds the scrubber's views, which also runs its progress update: the
 * abstract base the scrubber extends up to 583 (583 `LX/9gJ;`), and on 584, where Redex merged the
 * scrubber's looks into that base (`LX/9ix;`, UDDScrubberBasePlugin), the scrubber itself.
 */
internal fun scrubberBase(looks: ScrubberLooks): String = looks.viewsField.substringBefore("->")

/**
 * The scrubber class: the one of [holders] with an instance `()V` method tracing
 * [SCRUBBER_ACTIVE] and another tracing [SCRUBBER_PASSIVE]. Refuses unless there's exactly one
 * such class with exactly one method of each.
 */
internal fun vddScrubber(holders: List<ClassDef>): ScrubberLooks {
    fun look(holder: ClassDef, string: String) = methodsHolding(holder, string).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.isEmpty()
    }
    val scrubbers = holders.distinctBy { it.type }.filter { look(it, SCRUBBER_ACTIVE).size == 1 && look(it, SCRUBBER_PASSIVE).size == 1 }
    val scrubber = scrubbers.singleOrNull()
        ?: refuse("expected one class tracing both \"$SCRUBBER_ACTIVE\" and \"$SCRUBBER_PASSIVE\", found ${scrubbers.size}")
    val passive = look(scrubber, SCRUBBER_PASSIVE).single()
    val (viewsField, labelField) = timeLabelFields(scrubber, passive)
    val barField = seekBarField(passive, labelField.substringBefore("->"))
    return ScrubberLooks(scrubber.type, look(scrubber, SCRUBBER_ACTIVE).single(), passive, viewsField, labelField, barField)
}

/**
 * The SeekBar field of the scrubber's views class [views], read from the passive look, which sizes
 * the bar: every `iget-object` there of a [SEEK_BAR] field that [views] declares. Refuses unless
 * that's exactly one field.
 */
internal fun seekBarField(passive: Method, views: String): String {
    val found = passive.implementation!!.instructions.mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.takeIf { instruction.opcode == Opcode.IGET_OBJECT }?.reference as? FieldReference)
            ?.takeIf { it.type == SEEK_BAR && it.definingClass == views }?.toString()
    }.distinct()
    return found.singleOrNull()
        ?: refuse("${passive.definingClass}->${passive.name} reads ${found.size} SeekBar fields off $views, expected the one bar")
}

/**
 * The time label's two fields, read from the passive look, which hides the label: the one
 * `iget-object` of a [VIEW_GROUP] field in it, straight after the `iget-object` of the scrubber's
 * own field that holds the views class declaring it. That field is declared on the scrubber, or on
 * the abstract base it extends: 582 moved the scrubber's fields and its looks' signatures to a base
 * (`LX/9c4;`, `LX/9d9;` overriding the looks), and its looks read the fields through the base.
 * Refuses unless that's exactly one field.
 */
internal fun timeLabelFields(scrubber: ClassDef, passive: Method): Pair<String, String> {
    val owners = setOf(scrubber.type) + listOfNotNull(scrubber.superclass)
    val code = passive.implementation!!.instructions.toList()
    val found = code.indices.mapNotNull { index ->
        val label = (code[index] as? ReferenceInstruction)?.takeIf { it.opcode == Opcode.IGET_OBJECT }?.reference as? FieldReference
        val views = (code.getOrNull(index - 1) as? ReferenceInstruction)?.takeIf { it.opcode == Opcode.IGET_OBJECT }?.reference as? FieldReference
        if (label != null && views != null && label.type == VIEW_GROUP && views.definingClass in owners &&
            views.type == label.definingClass
        ) {
            views.toString() to label.toString()
        } else {
            null
        }
    }.distinct()
    return found.singleOrNull()
        ?: refuse("${scrubber.type}->${passive.name} reads ${found.size} view group fields off the scrubber's views, expected the one time label")
}

/** The "most replayed" graph over the scrubber's bar: the views holder's field holding it, and the flag hiding it. */
internal class GraphHide(val graphField: String, val hiddenField: String)

/**
 * The "most replayed" graph (582 `LX/9DH;->A01`, a `LX/9DE;` built on `LX/ZHy;`) and its hidden
 * flag (`LX/ZHy;->A07`), read from the passive look, which hides it: of the boolean flags the look
 * sets on a view right before redrawing it, the one whose class's own `onDraw` returns at once while
 * it's set, and the one field of the views holder the look reads whose type is that class or extends
 * it, looked up through [classOf]. The look also redraws the chapter marks over the bar (`LX/9DG;`)
 * after setting a flag, but their `onDraw` goes on drawing. Refuses unless there's one of each.
 */
internal fun graphHide(looks: ScrubberLooks, classOf: (String) -> ClassDef?): GraphHide {
    val where = "${looks.type}->${looks.passive.name}"
    val views = looks.labelField.substringBefore("->")
    val code = looks.passive.implementation!!.instructions.toList()
    val flags = code.indices.mapNotNull { index ->
        val store = code[index] as? TwoRegisterInstruction
        val redraw = code.getOrNull(index + 1) as? FiveRegisterInstruction
        if (store == null || code[index].opcode != Opcode.IPUT_BOOLEAN || redraw == null ||
            code[index + 1].opcode != Opcode.INVOKE_VIRTUAL || code[index + 1].reference() != INVALIDATE ||
            redraw.registerCount != 1 || redraw.registerC != store.registerB
        ) {
            null
        } else {
            (code[index] as ReferenceInstruction).reference as FieldReference
        }
    }.distinctBy { it.toString() }
    val hiding = flags.filter { skipsDrawing(classOf(it.definingClass), it.toString()) }
    val flag = hiding.singleOrNull()
        ?: refuse("$where sets ${hiding.size} flag(s) a view stops drawing on, expected the one hiding the most replayed graph")
    val graphs = code.mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.takeIf { instruction.opcode == Opcode.IGET_OBJECT }?.reference as? FieldReference)
            ?.takeIf { it.definingClass == views && extendsType(it.type, flag.definingClass) { type -> classOf(type)?.superclass } }
            ?.toString()
    }.distinct()
    val graph = graphs.singleOrNull()
        ?: refuse("$where reads ${graphs.size} fields of $views holding a ${flag.definingClass}, expected the one graph")
    return GraphHide(graph, flag.toString())
}

/**
 * Whether [view]'s own [ON_DRAW] returns straight away while [flag] is set: an `iget-boolean` of it,
 * then an `if-nez` of that value jumping to a `return-void`.
 */
internal fun skipsDrawing(view: ClassDef?, flag: String): Boolean {
    val draw = view?.methods?.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == ON_DRAW && it.implementation != null
    } ?: return false
    val code = draw.implementation!!.instructions.toList()
    val flow = ControlFlow.of(draw)
    return code.indices.any { index ->
        val read = code[index] as? TwoRegisterInstruction
        val branch = code.getOrNull(index + 1) as? OneRegisterInstruction
        read != null && code[index].opcode == Opcode.IGET_BOOLEAN && code[index].reference() == flag &&
            branch != null && code[index + 1].opcode == Opcode.IF_NEZ && branch.registerA == read.registerA &&
            flow.normal[index + 1].any { it != index + 2 && code[it].opcode == Opcode.RETURN_VOID }
    }
}

/**
 * Facebook's reset of the chapter marks over the bar ([method]), run on a scrubber of class [owner],
 * or, when [field] is set, on what that field of the scrubber holds.
 */
internal class ChapterReset(val owner: String, val method: Method, val field: String?) {
    /** The reset as a call names it. */
    val call get() = "${method.definingClass}->${method.name}(${method.parameterTypes.single()})V"
}

/**
 * Facebook's reset of the chapter marks over the bar (582 `LX/9d9;->A0C`, 584 `LX/7la;->A01`),
 * which its chapter callback runs for a reel with none: a static method taking one object of its own
 * class that clears a list, sets a view's visibility (the marks go) and sets the alpha of a view to
 * 1.0 after reading the bar (the bar the marks drew over comes back, for the looks that show it).
 * Applying marks clears and hides too, but puts the bar's alpha at 0, so the 1.0 tells the two
 * apart. Up to 583 it's a method of [scrubber], taking the scrubber. 584 merged the scrubber's looks
 * into its base and left the marks to a helper the concrete scrubber holds (`LX/9iw;->A04`, a
 * `LX/7la;` whose own field leads back to the scrubber): then it's a method of the class of a final
 * instance field one of [subclasses], the classes extending [scrubber], declares, looked up through
 * [classOf]. Refuses unless there's one, and when it's private, since the light mount runs it.
 */
internal fun chapterReset(
    scrubber: ClassDef,
    looks: ScrubberLooks,
    subclasses: List<ClassDef> = emptyList(),
    classOf: (String) -> ClassDef? = { null },
): ChapterReset {
    fun resets(owner: ClassDef) = owner.methods.filter { method ->
        val code = method.implementation?.instructions ?: return@filter false
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(owner.type) &&
            method.calls(LIST_CLEAR) && method.calls(SET_VISIBILITY) && method.calls(SET_VIEW_ALPHA) &&
            code.any { it.reference() == looks.barField } &&
            code.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == FULL_ALPHA }
    }
    val own = resets(scrubber).map { ChapterReset(scrubber.type, it, null) }
    val held = if (own.isNotEmpty()) emptyList() else subclasses.distinctBy { it.type }.flatMap { subclass ->
        subclass.fields.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.FINAL.isSet(it.accessFlags) && it.type.startsWith("L")
        }.flatMap { field ->
            val helper = classOf(field.type) ?: return@flatMap emptyList()
            resets(helper).map { ChapterReset(subclass.type, it, "${subclass.type}->${field.name}:${field.type}") }
        }
    }
    val found = own + held
    val reset = found.singleOrNull()
        ?: refuse("${scrubber.type} and what its subclasses hold have ${found.size} static method(s) clearing its chapter marks and showing the bar again, expected one")
    if (AccessFlags.PRIVATE.isSet(reset.method.accessFlags)) {
        refuse("${reset.call}, the chapter reset, is private, so the light mount can't run it")
    }
    return reset
}

/**
 * The scrubber's light mount: the method it's in ([mount]), the index of its trace, where it starts
 * ([start]), the index where its branch for another video starts ([at]), the register holding the
 * scrubber from [start] to [at] ([scrubber]), the base's field holding the bar info it built last
 * ([info], 583 `LX/9gJ;->A06`) and the info's video id ([id], 583 `LX/AOM;->A01`), both as the mount
 * reads them before comparing. Up to 582 the mount is a method of the scrubber base, the scrubber its
 * p0. On 583 Redex inlined it into the Reels scrubber registry's hand-over (`LX/8fL;->A00`), which
 * keeps the scrubber in a local.
 */
internal class AnotherVideo(
    val mount: Method,
    val start: Int,
    val at: Int,
    val scrubber: Int,
    val info: String,
    val id: String,
)

/** Whether [instruction] writes [register], or a wide pair holding it. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    val destination = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    if (!instruction.opcode.setsRegister()) return false
    return destination == register || (instruction.opcode.setsWideRegister() && destination + 1 == register)
}

/**
 * The scrubber's light mount, which Facebook runs each time it hands its one Reels scrubber another
 * reel (its `onLoad` runs once), from its trace of [SCRUBBER_LIGHT_MOUNT]: up to 582 the one instance
 * method of [base] with a single parameter tracing it (582 `LX/9c4;->A0m`), and on 583, where Redex
 * inlined the mount, the registry's [handOver] (`LX/8fL;->A00`), between its test of the reel's id and
 * its return. It compares the new video's id with the one in the bar info it built last, a String
 * field of [barInfo] read off the scrubber, through `areEqual`, and an `if-nez` of that skips the reset
 * of the bar's state for the same video. 583 rebuilds the bar info between the compare and its
 * `if-nez`, so only code that runs straight on and leaves the answer alone may sit there, and 584 (the
 * hand-over is `LX/9UY;->A00`) gets there through a `goto`, its branch for a scrubber with no bar info
 * yet moved below the mount and jumping back to the compare. Returns the mount and the index right
 * after that `if-nez`, where only the branch for another video goes on. Refuses unless all that holds,
 * with no jump onto that index or the trace, no way into the stretch between them but from the trace or
 * the stretch itself, and the scrubber's register left alone along it.
 */
internal fun anotherVideo(base: ClassDef, barInfo: String, handOver: ReelHandOver): AnotherVideo {
    fun traces(method: Method) = method.implementation?.instructions?.toList().orEmpty().withIndex().filter { (_, it) ->
        (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
            ((it as ReferenceInstruction).reference as StringReference).string == SCRUBBER_LIGHT_MOUNT
    }.map { it.index }
    val own = methodsHolding(base, SCRUBBER_LIGHT_MOUNT).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.size == 1
    }
    val inlined = listOf(handOver.method).filter { traces(it).isNotEmpty() }
    val mounts = own + inlined
    val mount = mounts.singleOrNull()
        ?: refuse("${base.type} and the hand-over have ${mounts.size} method(s) tracing \"$SCRUBBER_LIGHT_MOUNT\", expected the one light mount")
    val where = "${mount.definingClass}->${mount.name}"
    val code = mount.implementation!!.instructions.toList()
    val start = traces(mount).singleOrNull() ?: refuse("$where loads \"$SCRUBBER_LIGHT_MOUNT\" more than once")
    val flow = ControlFlow.of(mount)
    // The if-nez testing the compare's answer, past only code that runs straight on, gotos included, with
    // no other way in, and leaves the answer alone.
    fun branchOf(compare: Int): Int? {
        if (code.getOrNull(compare + 1)?.opcode != Opcode.MOVE_RESULT) return null
        val result = (code[compare + 1] as OneRegisterInstruction).registerA
        var from = compare + 1
        var index = flow.normal[from].singleOrNull() ?: return null
        val seen = mutableSetOf<Int>()
        while (seen.add(index)) {
            if (code.indices.any { it != from && index in flow.normal[it] }) return null
            val instruction = code[index]
            if (instruction.opcode == Opcode.IF_NEZ && (instruction as OneRegisterInstruction).registerA == result) return index
            if (writes(instruction, result)) return null
            from = index
            index = flow.normal[index].singleOrNull() ?: return null
        }
        return null
    }
    val compares = (start until code.size).mapNotNull { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        val compares = code[index].opcode == Opcode.INVOKE_STATIC && call?.name == "areEqual" && call.returnType == "Z" &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
        if (compares) branchOf(index)?.let { index to it } else null
    }
    val (compare, branch) = compares.singleOrNull()
        ?: refuse("$where compares ${compares.size} pairs with areEqual and skips on a match, expected the video's id")
    fun read(index: Int) = code[index].takeIf { it.opcode == Opcode.IGET_OBJECT }
        ?.let { (it as ReferenceInstruction).reference as FieldReference }
    val ids = (start until compare).mapNotNull(::read).filter { it.definingClass == barInfo && it.type == "Ljava/lang/String;" }
    val id = ids.distinct().singleOrNull()
        ?: refuse("$where no longer reads the video id off its bar info $barInfo before comparing")
    val infoReads = (start until compare).filter { index ->
        read(index)?.let { it.definingClass == base.type && it.type == barInfo } == true
    }
    val infos = infoReads.map { read(it)!! }.distinct()
    val info = infos.singleOrNull()
        ?: refuse("$where reads ${infos.size} bar info field(s) of ${base.type} before comparing, expected one")
    val scrubber = infoReads.map { (code[it] as TwoRegisterInstruction).registerB }.distinct().singleOrNull()
        ?: refuse("$where reads its bar info off more than one register")
    val at = branch + 1
    if (at >= code.size || code.indices.any { it != at - 1 && at in flow.normal[it] }) {
        refuse("$where has a jump onto its branch for another video")
    }
    if (code.indices.any { it != start - 1 && start in flow.normal[it] }) refuse("$where has a jump onto its light mount's trace")
    if ((start + 1..at).any { index -> code.indices.any { index in flow.normal[it] && it !in start until at } }) {
        refuse("$where can reach its branch for another video without its light mount's trace")
    }
    if ((start until at).any { writes(code[it], scrubber) }) refuse("$where writes v$scrubber, the scrubber, before its branch for another video")
    if (own.isEmpty() && (start <= handOver.at || at >= handOver.end)) {
        refuse("$where runs its light mount outside the stretch from its test of the reel's id to its return")
    }
    return AnotherVideo(mount, start, at, scrubber, info.toString(), id.toString())
}

/**
 * The Reels scrubber registry's hand-over as a new reel shows ([method], 583 `LX/8fL;->A00`), where
 * the hand-over hook goes ([at], right after the reel's id is read, before Facebook checks it), the
 * register holding the reel's id ([shown]), the register holding the picked scrubber's holder
 * ([holder]) and the holder's field with the scrubber ([scrubber]), and the one `return-void` every way
 * on from [at] ends at ([end]).
 */
internal class ReelHandOver(
    val method: Method,
    val at: Int,
    val shown: Int,
    val holder: Int,
    val scrubber: String,
    val end: Int,
)

/**
 * The Reels scrubber registry's hand-over (583 `LX/8fL;->A00`): the one instance `V` method of
 * [holders] tracing [NEW_REELS_UNIT_SHOWN]. Facebook runs it for each reel that shows, swiped to or
 * played next by itself. It picks the scrubber for the reel, reads the reel's video id with a static
 * one-argument call, skips the rest for no id or for the reel the scrubber's bar info already holds,
 * and otherwise hands the scrubber the reel's player, which runs the scrubber's light mount (582
 * `LX/8ZR;->A00` calls the mount, 583 has it inline, see [anotherVideo]). Before
 * that it returns when the reel's player has no parameters yet. Every way on ends at one
 * `return-void` right after the trace's closing call. Refuses unless all that holds: the id call
 * right after the trace with its `move-result-object` and a test of the id, nothing but the
 * instruction before jumping onto that test, the first read of a [base] field afterwards taken off
 * a holder register written before the test and not in between, both registers and the id's at v15
 * or below, and no other return reachable from the test.
 */
internal fun reelHandOver(holders: List<ClassDef>, base: String): ReelHandOver {
    val methods = holders.distinctBy { it.type }.flatMap { holder ->
        methodsHolding(holder, NEW_REELS_UNIT_SHOWN).filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" }
    }
    val method = methods.singleOrNull()
        ?: refuse("expected one instance method tracing \"$NEW_REELS_UNIT_SHOWN\", found ${methods.size}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val trace = code.indices.singleOrNull {
        code[it].opcode.let { op -> op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO } &&
            ((code[it] as ReferenceInstruction).reference as StringReference).string == NEW_REELS_UNIT_SHOWN
    } ?: refuse("$where loads \"$NEW_REELS_UNIT_SHOWN\" more than once")
    val idCall = (trace + 1 until code.size).firstOrNull { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        code[index].opcode == Opcode.INVOKE_STATIC && call != null && call.returnType == "Ljava/lang/String;" &&
            call.parameterTypes.size == 1 && (code[index] as FiveRegisterInstruction).registerCount == 1
    } ?: refuse("$where no longer reads the reel's id with a static call after its trace")
    val result = code.getOrNull(idCall + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction
        ?: refuse("$where no longer keeps the reel's id")
    val shown = result.registerA
    val at = idCall + 2
    val test = code.getOrNull(at)
    if (test == null || (test.opcode != Opcode.IF_NEZ && test.opcode != Opcode.IF_EQZ) || (test as OneRegisterInstruction).registerA != shown) {
        refuse("$where no longer tests the reel's id right after reading it")
    }
    val flow = ControlFlow.of(method)
    if (code.indices.any { it != at - 1 && at in flow.normal[it] }) refuse("$where has a jump onto its test of the reel's id")
    val take = (at until code.size).firstOrNull { index ->
        code[index].opcode == Opcode.IGET_OBJECT && ((code[index] as ReferenceInstruction).reference as FieldReference).type == base
    } ?: refuse("$where no longer takes a $base off the scrubber it picked")
    val holder = (code[take] as TwoRegisterInstruction).registerB
    val scrubber = (code[take] as ReferenceInstruction).reference.toString()
    if ((at until take).any { writes(code[it], holder) }) refuse("$where writes v$holder between the id's test and its read of the scrubber")
    if ((at + 1..take).any { index -> code.indices.any { index in flow.normal[it] && it !in at until index } }) {
        refuse("$where can reach its read of the scrubber without its test of the reel's id")
    }
    if (holder > 15 || shown > 15) refuse("$where keeps the scrubber's holder or the reel's id past v15")
    val reached = mutableSetOf(at)
    val queue = ArrayDeque(listOf(at))
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        flow.normal[next].forEach { if (reached.add(it)) queue += it }
    }
    val returns = reached.filter { code[it].opcode == Opcode.RETURN_VOID }
    val end = returns.singleOrNull()
        ?: refuse("$where can end at ${returns.size} return-void instructions past its test of the reel's id, expected one")
    val closing = code.getOrNull(end - 1)
    val closes = (closing as? ReferenceInstruction)?.reference as? MethodReference
    if (closing?.opcode != Opcode.INVOKE_STATIC || closes == null || closes.returnType != "V" ||
        closes.parameterTypes.map(CharSequence::toString) != listOf("I")
    ) {
        refuse("$where no longer closes its trace right before it returns")
    }
    if (code.indices.any { it != end - 1 && end in flow.normal[it] }) refuse("$where has a jump onto its return")
    return ReelHandOver(method, at, shown, holder, scrubber, end)
}

/**
 * The two ways the Reels scrubber registry's hand-over returns ahead of [ReelHandOver.at] (583
 * `LX/8fL;->A00`, indices 2 to 5 and 13): the test of its hide flag ([test]), a boolean field of its
 * own read into [flag] right before, which when set has its own one-`Integer` method hide every
 * scrubber's bar for the reel's key ([key]) and returns; and its test for a scrubber holder
 * ([noHolder], of [holder]), the reel's own or its default one, which returns at that same
 * `return-void` when there's neither.
 */
internal class RegistryReturns(
    val test: Int,
    val flag: Int,
    val key: Int,
    val noHolder: Int,
    val holder: Int,
    /** The registry's field with the default holder. */
    val defaultHolder: String,
)

/**
 * Finds [RegistryReturns] in [handOver]'s method. Refuses unless the flag is read off the method's
 * own class ahead of the hand-over's hook and tested right after, with nothing else jumping onto
 * the test; the hide is the one call between that test and a `return-void`, on the method's own
 * class, taking just the key, which nothing writes before; exactly one other instruction, an
 * `if-eqz` ahead of the hook that only the instruction before reaches, jumps onto that return, and
 * that instruction reads the default holder off a field of the method's own class into the
 * register tested; and the flag, key and holder sit at v15 or below.
 */
internal fun registryReturns(handOver: ReelHandOver): RegistryReturns {
    val method = handOver.method
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val read = (0 until handOver.at).firstOrNull { index ->
        code[index].opcode == Opcode.IGET_BOOLEAN &&
            ((code[index] as ReferenceInstruction).reference as FieldReference).definingClass == method.definingClass
    } ?: refuse("$where no longer reads a flag of its own ahead of its hand-over")
    val flag = (code[read] as TwoRegisterInstruction).registerA
    val test = read + 1
    if (code.getOrNull(test)?.opcode != Opcode.IF_EQZ || (code[test] as OneRegisterInstruction).registerA != flag) {
        refuse("$where no longer tests its flag right after reading it")
    }
    val hide = code.getOrNull(test + 1)
    val hides = (hide as? ReferenceInstruction)?.reference as? MethodReference
    if (hide?.opcode != Opcode.INVOKE_VIRTUAL || hides == null || hides.definingClass != method.definingClass ||
        hides.returnType != "V" || hides.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Integer;")
    ) {
        refuse("$where no longer hides every bar for the reel's key when its flag is set")
    }
    val key = (hide as FiveRegisterInstruction).registerD
    if ((0 until test).any { writes(code[it], key) }) refuse("$where writes v$key, the reel's key, before its flag's test")
    val back = test + 2
    if (code.getOrNull(back)?.opcode != Opcode.RETURN_VOID) refuse("$where no longer returns right after hiding every bar")
    val flow = ControlFlow.of(method)
    if (code.indices.any { it != read && test in flow.normal[it] }) refuse("$where has a jump onto its test of the flag")
    val jumps = code.indices.filter { it != test + 1 && back in flow.normal[it] }
    val noHolder = jumps.singleOrNull()
        ?: refuse("$where returns at its hide's return-void from ${jumps.size} other places, expected one")
    if (code[noHolder].opcode != Opcode.IF_EQZ || noHolder >= handOver.at) {
        refuse("$where no longer tests for a scrubber holder ahead of its hand-over")
    }
    if (code.indices.any { it != noHolder - 1 && noHolder in flow.normal[it] }) refuse("$where has a jump onto its test for a scrubber holder")
    val holder = (code[noHolder] as OneRegisterInstruction).registerA
    val fallback = code.getOrNull(noHolder - 1)
    if (fallback?.opcode != Opcode.IGET_OBJECT || (fallback as TwoRegisterInstruction).registerA != holder ||
        ((fallback as ReferenceInstruction).reference as FieldReference).definingClass != method.definingClass
    ) {
        refuse("$where no longer reads its default scrubber holder right before testing for one")
    }
    if (maxOf(flag, key, holder) > 15) refuse("$where keeps its flag, the reel's key or the holder past v15")
    return RegistryReturns(test, flag, key, noHolder, holder, (fallback as ReferenceInstruction).reference.toString())
}

/**
 * The scrubber base's load ([method], 585 `LX/0AE0;->onLoad`), where the bar info of the reel it takes
 * has just been stored ([at], in the register [info]), and the base's field with the scrubber's own
 * holder ([ownHolder]), which the load stores as the registry's default.
 */
internal class ScrubberLoad(val method: Method, val at: Int, val info: Int, val ownHolder: String)

/**
 * Finds the [ScrubberLoad] in [base]. Refuses unless one method holds the load's trace, it stores the
 * bar info once with no jump onto the next instruction, and it stores the holder it just read off
 * the base as the registry's default once.
 */
internal fun scrubberLoad(base: ClassDef, another: AnotherVideo, returns: RegistryReturns): ScrubberLoad {
    val method = methodsHolding(base, SCRUBBER_LOAD).filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" }
        .singleOrNull() ?: refuse("${base.type} has no one method tracing \"$SCRUBBER_LOAD\"")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    fun stored(index: Int) = code[index].takeIf { it.opcode == Opcode.IPUT_OBJECT }
        ?.let { ((it as ReferenceInstruction).reference as FieldReference).toString() }
    val store = code.indices.singleOrNull { stored(it) == another.info }
        ?: refuse("$where no longer stores its bar info ${another.info} once")
    val at = store + 1
    val flow = ControlFlow.of(method)
    if (code.indices.any { it != store && at in flow.normal[it] }) refuse("$where has a jump past its store of the bar info")
    val default = code.indices.singleOrNull { stored(it) == returns.defaultHolder }
        ?: refuse("$where no longer stores the registry's default holder ${returns.defaultHolder} once")
    val own = code.getOrNull(default - 1)
    val field = (own as? ReferenceInstruction)?.reference as? FieldReference
    if (own?.opcode != Opcode.IGET_OBJECT || field?.definingClass != base.type ||
        (own as TwoRegisterInstruction).registerA != (code[default] as TwoRegisterInstruction).registerA
    ) {
        refuse("$where no longer stores a holder of its own as the registry's default")
    }
    return ScrubberLoad(method, at, (code[store] as TwoRegisterInstruction).registerA, field.toString())
}

/**
 * Where the scrubber base's unload clears the registry's default holder ([method], 585
 * `LX/0AE0;->onUnload`): the `const` of null ([clear]), the store right after it ([store]) and the
 * register holding the registry ([registry]).
 */
internal class HolderClear(val method: Method, val clear: Int, val store: Int, val registry: Int)

/**
 * Finds the [HolderClear] in [base]. Refuses unless exactly one store of the registry's default
 * holder comes right after loading null into the stored register, and nothing jumps onto the store.
 */
internal fun holderClear(base: ClassDef, returns: RegistryReturns): HolderClear {
    val clears = base.methods.flatMap { method ->
        val code = method.implementation?.instructions?.toList().orEmpty()
        code.indices.filter { index ->
            val store = code[index]
            val zero = code.getOrNull(index - 1)
            store.opcode == Opcode.IPUT_OBJECT && zero?.opcode == Opcode.CONST_4 &&
                ((store as ReferenceInstruction).reference as FieldReference).toString() == returns.defaultHolder &&
                (zero as OneRegisterInstruction).registerA == (store as TwoRegisterInstruction).registerA &&
                (zero as NarrowLiteralInstruction).narrowLiteral == 0
        }.map { method to it }
    }
    val (method, store) = clears.singleOrNull()
        ?: refuse("${base.type} clears the registry's default holder ${returns.defaultHolder} ${clears.size} time(s), expected once")
    val where = "${method.definingClass}->${method.name}"
    val flow = ControlFlow.of(method)
    val code = method.implementation!!.instructions.toList()
    if (code.indices.any { it != store - 1 && store in flow.normal[it] }) refuse("$where has a jump onto its clear of the default holder")
    if (store + 1 >= code.size) refuse("$where ends at its clear of the default holder")
    return HolderClear(method, store - 1, store, (code[store] as TwoRegisterInstruction).registerB)
}

/**
 * The classes extending the scrubber [base] that override its light [mount] without an
 * `invoke-super` of it. Facebook runs the mount virtually, so the reset hooked into the base's
 * mount would be skipped for any of them. 582 has none, as no class reaching `LX/9c4;` declares
 * `A0m`, and 583 runs the mount inline in the hand-over, where nothing can override it. Uses
 * [lengthCheckOverrides] to find the overrides among [candidates].
 */
internal fun mountOverridesSkippingBase(
    candidates: List<ClassDef>,
    base: String,
    mount: Method,
    superclassOf: (String) -> String?,
): List<String> = lengthCheckOverrides(candidates, base, listOf(mount), superclassOf).filterNot { (_, override) ->
    override.implementation!!.instructions.any {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        (it.opcode == Opcode.INVOKE_SUPER || it.opcode == Opcode.INVOKE_SUPER_RANGE) && call?.name == mount.name &&
            call.returnType == mount.returnType &&
            call.parameterTypes.map(CharSequence::toString) == mount.parameterTypes.map(CharSequence::toString)
    }
}.map { it.first }

/**
 * Whether [parameters] are a length check's: the reel's length and a boolean, in either order. 582
 * takes `(IZ)Z`, 583 `(ZI)Z`.
 */
private fun isLengthCheckShape(parameters: List<String>) = parameters.sorted() == listOf("I", "Z")

// 585 flips the minimum check's compare (arm64 `LX/0AE0;->A1O` jumps to its yes on `if-ge`, where 583 jumped to its no
// on `if-lt`); the short-reel check keeps its `if-gt` (`A1N`). Which side jumps is Redex's pick, so both are accepted.
private val MINIMUM_COMPARES = arrayOf(Opcode.IF_LT, Opcode.IF_GE)
private val SHORT_COMPARES = arrayOf(Opcode.IF_GT, Opcode.IF_LE)

/**
 * The scrubber's instance length checks loading [LENGTH_CHECK_SURFACE] that compare the reel's
 * length, their int parameter, first in an [opcode] against Facebook's minimum.
 */
private fun lengthChecks(scrubber: ClassDef, vararg opcodes: Opcode): List<Method> =
    methodsHolding(scrubber, LENGTH_CHECK_SURFACE).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            isLengthCheckShape(it.parameterTypes.map(CharSequence::toString))
    }.filter { check ->
        val implementation = check.implementation!!
        // The parameters take the last three registers: this, then the length and the boolean in their order.
        val parameters = check.parameterTypes.map(CharSequence::toString)
        val length = implementation.registerCount - parameters.size + parameters.indexOf("I")
        implementation.instructions.any { it.opcode in opcodes && (it as TwoRegisterInstruction).registerA == length }
    }

/** The scrubber's two length checks, [minimumLengthCheck] and [shortReelCheck]. */
internal class LengthChecks(val minimum: Method, val short: Method)

/**
 * The scrubber's two length checks, or null on a build whose scrubber has neither: 584 inlined both
 * into the bar info builder, the Reels tab scrubber's own minimum with them (an `instance-of
 * LX/7lL;` branch there), and [barInfo] finds their answers in it instead.
 */
internal fun scrubberLengthChecks(scrubber: ClassDef): LengthChecks? {
    if (lengthChecks(scrubber, *MINIMUM_COMPARES).isEmpty() && lengthChecks(scrubber, *SHORT_COMPARES).isEmpty()) return null
    return LengthChecks(minimumLengthCheck(scrubber), shortReelCheck(scrubber))
}

/**
 * The scrubber's check that a reel is long enough for a bar (583 `LX/9h9;->A1U`). The scrubber has
 * two instance length checks, each loading [LENGTH_CHECK_SURFACE] first. This one answers no
 * through an `if-lt` of the reel's length against Facebook's minimum, and its answer decides between
 * the passive look and no bar at all. Refuses unless there's exactly one such check.
 */
internal fun minimumLengthCheck(scrubber: ClassDef): Method {
    val minimum = lengthChecks(scrubber, *MINIMUM_COMPARES)
    return minimum.singleOrNull()
        ?: refuse("${scrubber.type} has ${minimum.size} length check(s) failing a reel shorter than a minimum, expected one")
}

/**
 * The scrubber's other length check (583 `LX/9h9;->A1T`), which answers no through an `if-gt` of
 * the reel's length against the same minimum, so yes for a short reel, when a server flag is on.
 * The scrubber asks it first, and its yes gives the reel the look that sets the bar's alpha to 0,
 * so a short reel's bar showed for a moment and went. Refuses unless there's exactly one.
 */
internal fun shortReelCheck(scrubber: ClassDef): Method {
    val short = lengthChecks(scrubber, *SHORT_COMPARES)
    return short.singleOrNull()
        ?: refuse("${scrubber.type} has ${short.size} length check(s) passing only a reel at most a minimum long, expected one")
}

/**
 * The overrides of the scrubber's length [checks] in classes extending it: each instance method of
 * [candidates] with a check's name and signature, on a class whose superclasses, looked up through
 * [superclassOf], reach [scrubber]. 582's Reels tab scrubber (`LX/9D6;`) extends `LX/9d9;` and
 * overrides both checks with its own minimum of about a minute, and the bar info builder asks the
 * checks virtually, so hooks on the scrubber's own checks never ran there. A reel under a minute
 * then had no passive look, and as the look selector skips a look equal to the current one, the
 * bar showed on every other such reel only (#73). Returns each override's class and method.
 * [mountOverridesSkippingBase] finds the light mount's overrides with it too.
 */
internal fun lengthCheckOverrides(
    candidates: List<ClassDef>,
    scrubber: String,
    checks: List<Method>,
    superclassOf: (String) -> String?,
): List<Pair<String, Method>> {
    fun extendsScrubber(type: String): Boolean {
        var current = superclassOf(type)
        var depth = 0
        while (current != null && depth++ < 64) {
            if (current == scrubber) return true
            current = superclassOf(current)
        }
        return false
    }
    return candidates.filter { it.type != scrubber && extendsScrubber(it.type) }.flatMap { holder ->
        holder.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
                checks.any { method.sameAs(it) }
        }.map { holder.type to it }
    }
}

/**
 * The scrubber base's look selector (582 `LX/9c4;->A1F`): the one instance `(Integer)V` method of
 * [base] calling both of [looks]' looks. Its Integer is the look, 0 passive, 1 or 3 active, 2 faded
 * out and 4 invisible. Refuses unless there's exactly one.
 */
internal fun lookSelector(base: ClassDef, looks: ScrubberLooks): Method {
    val selectors = base.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Integer;") &&
            listOf(looks.active, looks.passive).all { look ->
                method.implementation?.instructions?.any {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    it.opcode.name.startsWith("invoke") && call?.name == look.name && call.returnType == "V" &&
                        call.parameterTypes.isEmpty()
                } == true
            }
    }
    return selectors.singleOrNull()
        ?: refuse("${base.type} has ${selectors.size} (Integer)V method(s) calling both scrubber looks, expected one")
}

/**
 * The look selector's skip of a look the bar already has: the `if-eq` at [branch], the register
 * [flow] holding the selector's state flow there and its [read] of the state, the state class
 * [state] and its field [parked] holding the look Facebook parks while a reel's player changes
 * (582 `LX/9DA;->A02`).
 */
internal class SameLookSkip(val branch: Int, val flow: Int, val read: String, val state: String, val parked: String)

/**
 * The look selector's skip of a look the bar already has (582 `LX/9c4;->A1F`, its second branch):
 * the first `if-eq` of the look it's asked for, its parameter, and the look read straight off the
 * selector's state, which Facebook takes from a flow (an `invoke-interface` of a `()Object` method,
 * `move-result-object`, `check-cast` to the state class, `iget-object` of an Integer field). The
 * state class has two instance Integer fields, the look the bar has and the one parked to apply once
 * a reel's player change ends, which is the one the skip doesn't read. [stateClass] looks the state
 * class up. Refuses unless all that holds, the skip jumps to a `return-void`, only the instruction
 * before it leads onto it and the flow's register still holds the flow there.
 */
internal fun sameLookSkip(selector: Method, stateClass: (String) -> ClassDef?): SameLookSkip {
    val where = "${selector.definingClass}->${selector.name}"
    val code = selector.implementation!!.instructions.toList()
    val look = selector.implementation!!.registerCount - 1
    val branch = code.indices.firstOrNull { index ->
        code[index].opcode == Opcode.IF_EQ && (code[index] as TwoRegisterInstruction).let { it.registerA == look || it.registerB == look }
    } ?: refuse("$where has no if-eq of the look it's asked for")
    if (branch < 4) refuse("$where compares the look before reading its state")
    val read = code[branch - 4] as? FiveRegisterInstruction
    val readCall = (read as? ReferenceInstruction)?.reference as? MethodReference
    val result = code[branch - 3] as? OneRegisterInstruction
    val cast = code[branch - 2]
    val current = code[branch - 1]
    val currentField = (current as? ReferenceInstruction)?.reference as? FieldReference
    val state = cast.reference()
    val held = (current as? TwoRegisterInstruction)?.registerA
    val compared = (code[branch] as TwoRegisterInstruction).let { if (it.registerA == look) it.registerB else it.registerA }
    if (read == null || read.opcode != Opcode.INVOKE_INTERFACE || read.registerCount != 1 || readCall == null ||
        readCall.parameterTypes.isNotEmpty() || readCall.returnType != "Ljava/lang/Object;" ||
        result == null || code[branch - 3].opcode != Opcode.MOVE_RESULT_OBJECT ||
        cast.opcode != Opcode.CHECK_CAST || (cast as OneRegisterInstruction).registerA != result.registerA ||
        current.opcode != Opcode.IGET_OBJECT || (current as TwoRegisterInstruction).registerB != result.registerA ||
        currentField == null || currentField.definingClass != state || currentField.type != "Ljava/lang/Integer;" ||
        held != compared || read.registerC == result.registerA || read.registerC == held
    ) {
        refuse("$where no longer reads the bar's look off its state right before comparing it with the one asked for")
    }
    val flow = ControlFlow.of(selector)
    val target = flow.normal[branch].firstOrNull { it != branch + 1 }
    if (target == null || code[target].opcode != Opcode.RETURN_VOID) refuse("$where no longer returns on a look the bar has")
    if (code.indices.any { it != branch - 1 && branch in flow.normal[it] }) refuse("$where has a jump onto its skip of a look the bar has")
    val integers = stateClass(state!!)?.fields
        ?.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "Ljava/lang/Integer;" }
        ?: refuse("$where reads its look off $state, which isn't in this APK")
    val parked = integers.filter { it.name != currentField!!.name }
    if (integers.size != 2 || parked.size != 1) refuse("$state has ${integers.size} Integer fields, expected the look and the parked one")
    return SameLookSkip(branch, read.registerC, readCall.toString(), state, "$state->${parked.single().name}:Ljava/lang/Integer;")
}

/**
 * The scrubber base's builder of a reel's bar info, the index of the info's constructor call in it,
 * and the registers that call takes the has-a-bar [flag] and the reel's [length] in. Where the
 * builder works the length checks out itself (584), also the registers of their answers, the
 * [scrubbable] and [faded] flags.
 */
internal class BarInfo(
    val builder: Method,
    val call: Int,
    val flag: Int,
    val length: Int,
    val scrubbable: Int? = null,
    val faded: Int? = null,
)

/** Whether [instruction] calls a bar info constructor of [type], one taking [BAR_INFO_PARAMETERS]. */
private fun buildsBarInfo(instruction: Instruction, type: String): Boolean {
    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return instruction.opcode.name.startsWith("invoke") && call.definingClass == type && call.name == "<init>" &&
        call.parameterTypes.map(CharSequence::toString).sorted() == BAR_INFO_PARAMETERS
}

/**
 * The scrubber base's builder of a reel's bar info (583 `LX/9gJ;->A04`, building `LX/AOM;`): the one
 * static method of [base] that asks [lengthCheck] and returns an object it makes with a bar info
 * constructor ([BAR_INFO_PARAMETERS]). That constructor's first boolean says whether the reel has a
 * bar at all (583 `LX/AOM;->A04`), and on no the scrubber picks the invisible look before anything
 * else. Facebook works it out from the reel, and an ad, the viewer's config and a few kinds of reel
 * it flags on its servers get none, so a kept bar was missing from some reels (#73). The builder
 * hands the same flag to both length checks, which the hooks already answer while kept, on the
 * scrubber and on any class overriding them ([lengthCheckOverrides]). With no [lengthCheck] (584,
 * [scrubberLengthChecks]) the builder is the one loading [LENGTH_CHECK_SURFACE] itself, and the
 * constructor's other two booleans are the checks' answers, which the hook then gives. Returns the
 * builder, its constructor call and the registers. Refuses unless there's one builder making one
 * such object, by the range form, right after its `new-instance`, with no jump or try block onto the
 * call, unless the flag it passes is the one the length check gets, and unless the flag and the
 * length are at most v15 and the answers at most v255, as the hook's operands need.
 */
internal fun barInfo(base: ClassDef, lengthCheck: Method?): BarInfo {
    val checkParameters = lengthCheck?.parameterTypes?.map(CharSequence::toString)
    fun Method.asksLengthCheck() = implementation?.instructions?.any {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        it.opcode.name.startsWith("invoke-virtual") && call != null && call.name == lengthCheck!!.name &&
            call.returnType == "Z" && call.parameterTypes.map(CharSequence::toString) == checkParameters
    } == true
    fun Method.checksLengthItself() = implementation?.instructions?.any { it.reference() == LENGTH_CHECK_SURFACE } == true
    val builders = base.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
            (if (lengthCheck != null) method.asksLengthCheck() else method.checksLengthItself()) &&
            method.implementation!!.instructions.any { buildsBarInfo(it, method.returnType) }
    }
    val builder = builders.singleOrNull()
        ?: refuse(
            "${base.type} has ${builders.size} static method(s) ${if (lengthCheck != null) "asking its length check" else "checking a reel's length"} " +
                "and building a bar info, expected one",
        )
    val where = "${base.type}->${builder.name}"
    val code = builder.implementation!!.instructions.toList()
    val calls = code.indices.filter { buildsBarInfo(code[it], builder.returnType) }
    val call = calls.singleOrNull() ?: refuse("$where builds its bar info ${calls.size} times, expected once")
    val range = code[call] as? RegisterRangeInstruction
        ?: refuse("$where no longer builds its bar info by the range form")
    val made = code.getOrNull(call - 1)
    if (made == null || made.opcode != Opcode.NEW_INSTANCE || made.reference() != builder.returnType ||
        (made as OneRegisterInstruction).registerA != range.startRegister
    ) {
        refuse("$where no longer makes its bar info right before building it")
    }
    val flow = ControlFlow.of(builder)
    if (code.indices.any { it != call - 1 && call in flow.normal[it] } || flow.exceptional[call].isNotEmpty()) {
        refuse("$where has a jump or a try block onto its bar info's constructor")
    }
    // Each parameter takes one register, after the new instance.
    val parameters = ((code[call] as ReferenceInstruction).reference as MethodReference).parameterTypes.map(CharSequence::toString)
    val (has, scrubbable, faded) = parameters.indices.filter { parameters[it] == "Z" }.map { range.startRegister + 1 + it }
    val length = range.startRegister + 1 + parameters.indexOf("I")
    if (has > 15 || length > 15) refuse("$where builds its bar info past v15")
    if (lengthCheck == null) {
        if (scrubbable > 255 || faded > 255) refuse("$where builds its bar info past v255")
        return BarInfo(builder, call, has, length, scrubbable, faded)
    }
    val asking = "->${lengthCheck.name}(${checkParameters!!.joinToString("")})Z"
    val asks = code.filter { it.opcode == Opcode.INVOKE_VIRTUAL && (it.reference()?.contains(asking) == true) }
    // The call names this first, then the length and the flag in the order the check takes them.
    fun flagOf(ask: FiveRegisterInstruction) =
        listOf(ask.registerD, ask.registerE)[checkParameters.indexOf("Z")]
    if (asks.isEmpty() || asks.any { flagOf(it as FiveRegisterInstruction) != has }) {
        refuse("$where no longer hands its length check the bar info's first flag")
    }
    return BarInfo(builder, call, has, length)
}

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

/** The scrubber base's progress update and the method writing its time. */
internal class ProgressUpdate(val base: ClassDef, val update: Method, val writeTime: Method)

/**
 * The scrubber base's progress update (582 `LX/9c4;->A04`), the one static `(base)V` method of
 * [holders] tracing [SCRUBBER_UPDATE], and its time writer (`A06`), the one static `(base)V` method
 * of the same class that sets text. The writer puts the elapsed and total time into the time label
 * and moves the bar to where the reel is. Facebook's update only calls it while you drag. Refuses
 * unless there's exactly one of each.
 */
internal fun progressUpdate(holders: List<ClassDef>): ProgressUpdate {
    fun ClassDef.takesItself(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(type)
    val updates = holders.distinctBy { it.type }.flatMap { holder ->
        methodsHolding(holder, SCRUBBER_UPDATE).filter { holder.takesItself(it) }.map { holder to it }
    }
    val (base, update) = updates.singleOrNull()
        ?: refuse("expected one static method tracing \"$SCRUBBER_UPDATE\" on the class it takes, found ${updates.size}")
    val writers = base.methods.filter { base.takesItself(it) && it.calls(TEXT_VIEW_SET_TEXT) }
    val writer = writers.singleOrNull()
        ?: refuse("${base.type} has ${writers.size} static methods setting text, expected one, its time writer")
    return ProgressUpdate(base, update, writer)
}

/**
 * Where [update], the scrubber base's progress update, asks whether you're dragging: the
 * `sget-object` of the drag state right before the `if-ne` that skips the update's one call to its
 * time writer (582 `LX/9c4;->A04`, `sget-object v0, LX/0UA;->A01`). The update gets there only after
 * reading the reel's position and length, finding a position other than -1 and a length above 0,
 * and moving the bar. Returns that instruction's index. Refuses unless the writer is called once,
 * right after such a check.
 */
internal fun dragCheck(update: Method, progress: ProgressUpdate): Int {
    val base = progress.base.type
    val writer = "$base->${progress.writeTime.name}($base)V"
    val code = update.implementation!!.instructions.toList()
    val calls = code.indices.filter {
        code[it].opcode == Opcode.INVOKE_STATIC && (code[it] as ReferenceInstruction).reference.toString() == writer
    }
    val call = calls.singleOrNull()
        ?: refuse("$base->${update.name} calls its time writer ${calls.size} times, expected once, during a drag")
    val branch = code.getOrNull(call - 1)?.takeIf { it.opcode == Opcode.IF_NE } as? TwoRegisterInstruction
    val state = code.getOrNull(call - 2)?.takeIf { it.opcode == Opcode.SGET_OBJECT } as? OneRegisterInstruction
    if (branch == null || state == null || state.registerA !in setOf(branch.registerA, branch.registerB)) {
        refuse("$base->${update.name} no longer checks for a drag right before calling its time writer")
    }
    return call - 2
}

/**
 * The wait before the scrubber's next progress update (582 `LX/9c4;->A02`): the one static
 * `(base)J` method of the base that its update calls. The update hands that wait to a frame
 * callback that runs it again, and it's Facebook's `defaultFrameDelayMs` (100 ms on the S25), or
 * longer for a video over 30 seconds, so the bar moves in steps several times a second. Refuses
 * unless the update calls exactly one such method.
 */
internal fun frameDelay(progress: ProgressUpdate): Method {
    val base = progress.base
    val calls = progress.update.implementation!!.instructions.mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.takeIf { instruction.opcode == Opcode.INVOKE_STATIC }?.reference as? MethodReference)
            ?.takeIf {
                it.definingClass == base.type && it.returnType == "J" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(base.type)
            }
    }.distinctBy { it.toString() }
    val call = calls.singleOrNull()
        ?: refuse("${base.type}->${progress.update.name} asks ${calls.size} static methods for a wait, expected one")
    return base.methods.singleOrNull { it.name == call.name && it.returnType == "J" && it.parameterTypes.size == 1 }
        ?: refuse("$call, the wait before the next progress update, isn't declared on ${base.type}")
}

/** The Reels footer's seek bar status enum and its HIDDEN and VISIBLE values, as `class->name:type`. */
internal class SeekBarStatus(val type: String, val hidden: String, val visible: String)

/**
 * The seek bar status (582 `LX/AGw;`): the one enum of [holders] whose `<clinit>` builds exactly
 * three values, named [STATUS_HIDDEN], [STATUS_VISIBLE] and [STATUS_DRAGGING], each name the last
 * string loaded before the value's `sput-object`. Refuses unless there's exactly one.
 */
internal fun seekBarStatus(holders: List<ClassDef>): SeekBarStatus {
    val names = setOf(STATUS_HIDDEN, STATUS_VISIBLE, STATUS_DRAGGING)
    val found = holders.distinctBy { it.type }.mapNotNull { enum -> enumFields(enum)?.takeIf { it.keys == names }?.let { enum.type to it } }
    val (type, values) = found.singleOrNull()
        ?: refuse("expected one enum of $STATUS_HIDDEN, $STATUS_VISIBLE and $STATUS_DRAGGING, found ${found.size}")
    return SeekBarStatus(type, values.getValue(STATUS_HIDDEN), values.getValue(STATUS_VISIBLE))
}

/** An enum's fields by value name, each name the last string its `<clinit>` loads before the value's `sput-object`. */
private fun enumFields(enum: ClassDef): Map<String, String>? {
    if (enum.superclass != "Ljava/lang/Enum;") return null
    val code = enum.methods.singleOrNull { it.name == "<clinit>" }?.implementation?.instructions ?: return null
    var name: String? = null
    val values = mutableMapOf<String, String>()
    for (instruction in code) {
        val reference = (instruction as? ReferenceInstruction)?.reference
        if (reference is StringReference) name = reference.string
        if (instruction.opcode == Opcode.SPUT_OBJECT && reference is FieldReference && reference.type == enum.type) {
            values[name ?: return null] = reference.toString()
            name = null
        }
    }
    return values
}

/** A reel footer's looks: [shown], its everyday one, and [seeking], the one a press of the bar asks for. */
internal class FooterVisibility(val shown: String, val seeking: String)

/**
 * The footer's looks (585 `LX/0B73;`): the one enum of [holders] building exactly [FOOTER_SHOWN],
 * [FOOTER_SEEKING], [FOOTER_HIDDEN] and [FOOTER_CLEAR]. Refuses unless there's exactly one.
 */
internal fun footerVisibility(holders: List<ClassDef>): FooterVisibility {
    val names = setOf(FOOTER_SHOWN, FOOTER_SEEKING, FOOTER_HIDDEN, FOOTER_CLEAR)
    val found = holders.distinctBy { it.type }.mapNotNull { enum -> enumFields(enum)?.takeIf { it.keys == names } }
    val values = found.singleOrNull()
        ?: refuse("expected one enum of ${names.joinToString()}, found ${found.size}")
    return FooterVisibility(values.getValue(FOOTER_SHOWN), values.getValue(FOOTER_SEEKING))
}

/**
 * Where a reel's footer answers a press of the bar (585 `LX/0B7A;->A06`): [method] of [type] loads
 * [FooterVisibility.seeking] into [register] at [at] and the very next instruction hands it to the
 * footer's [FUNCTION1_INVOKE].
 */
internal class FooterSeek(val type: String, val method: Method, val at: Int, val register: Int)

/**
 * The one such load in [owners], the classes with a [FUNCTION1] field. Nothing may jump onto the load,
 * or a press could get past a hook in front of it. Refuses unless there's exactly one.
 */
internal fun footerSeek(owners: List<ClassDef>, visibility: FooterVisibility): FooterSeek {
    val found = owners.distinctBy { it.type }.flatMap { owner ->
        owner.methods.flatMap { method ->
            val code = method.code()
            code.indices.filter { at ->
                val load = code[at]
                val invoke = code.getOrNull(at + 1)
                load.opcode == Opcode.SGET_OBJECT && load.reference() == visibility.seeking &&
                    invoke?.reference() == FUNCTION1_INVOKE &&
                    (invoke as FiveRegisterInstruction).registerD == (load as OneRegisterInstruction).registerA
            }.map { at -> FooterSeek(owner.type, method, at, (code[at] as OneRegisterInstruction).registerA) }
        }
    }
    val seek = found.singleOrNull()
        ?: refuse("expected one method handing ${visibility.seeking} to a Function1, found ${found.size}")
    if (seek.at in branchTargets(seek.method.code())) refuse("${seek.type}->${seek.method.name} jumps onto its ${visibility.seeking}")
    return seek
}

/**
 * The class keeping the footer's status (582 `LX/AGv;`): [update], its one store of a new status
 * into [field] at [store], from register [new] into the object in [self], with the status before it
 * in [old]; and [constructor], whose one store of the starting status is at [initialStore], done
 * with that status at [started]: right after the store, or on 584 (`LX/AEI;`), which copies the
 * start into a second field it never reads, after that copy too.
 */
internal class StatusHolder(
    val type: String,
    val field: String,
    val update: Method,
    val store: Int,
    val new: Int,
    val old: Int,
    val self: Int,
    val constructor: Method,
    val initialStore: Int,
    val started: Int,
)

private fun Method.code() = implementation?.instructions?.toList().orEmpty()

private fun stores(method: Method, field: String) = method.code().withIndex().filter { (_, it) ->
    it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as? FieldReference)?.toString() == field
}.map { it.index }

/**
 * The status holder among [owners], the classes with a field of [status]' type: the one whose
 * instance `V` method stores a status into its own field once, hands that same register to
 * [FUNCTION1_INVOKE] after the store, and reads the field once before it, into a register no
 * instruction between the read and the store writes. Its one constructor stores the starting status
 * into that field once, with no branch anywhere in it. Refuses unless there's exactly one.
 */
internal fun statusHolder(owners: List<ClassDef>, status: SeekBarStatus): StatusHolder {
    val found = owners.distinctBy { it.type }.flatMap { owner ->
        owner.fields.filter { it.type == status.type && !AccessFlags.STATIC.isSet(it.accessFlags) }.flatMap { declared ->
            val field = "${owner.type}->${declared.name}:${status.type}"
            owner.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.name != "<init>" }
                .mapNotNull { update -> holderOf(owner, field, update) }
        }
    }
    return found.singleOrNull()
        ?: refuse("expected one class storing a ${status.type} and handing it to a Function1, found ${found.size}")
}

private fun holderOf(owner: ClassDef, field: String, update: Method): StatusHolder? {
    val code = update.code()
    val store = stores(update, field).singleOrNull() ?: return null
    val put = code[store] as TwoRegisterInstruction
    // `this` sits right after the locals.
    val self = update.localRegisterCount()
    if (put.registerB != self) return null
    val handed = code.drop(store + 1).any {
        it.opcode == Opcode.INVOKE_INTERFACE && (it as ReferenceInstruction).reference.toString() == FUNCTION1_INVOKE &&
            (it as FiveRegisterInstruction).registerCount == 2 && it.registerD == put.registerA
    }
    if (!handed) return null
    val read = code.withIndex().filter { (index, it) ->
        index < store && it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).toString() == field
    }.singleOrNull() ?: return null
    val old = (read.value as TwoRegisterInstruction).registerA
    val overwritten = code.subList(read.index + 1, store).any {
        val target = (it as? OneRegisterInstruction)?.registerA
        target != null && (it.opcode.setsRegister() && target == old || it.opcode.setsWideRegister() && target + 1 == old)
    }
    if (overwritten || old == put.registerA) return null
    val constructors = owner.methods.filter { it.name == "<init>" && stores(it, field).size == 1 }
    val constructor = constructors.singleOrNull() ?: return null
    val start = constructor.code()
    if (start.any { it is OffsetInstruction }) return null
    val initialStore = stores(constructor, field).single()
    val stored = (start[initialStore] as TwoRegisterInstruction).registerA
    val started = (initialStore + 1 until start.size).firstOrNull { stored !in constructor.liveAcrossInjection(it) } ?: return null
    // The hooks there read the holder off the register the start was stored into it from.
    val holder = (start[initialStore] as TwoRegisterInstruction).registerB
    val rewritten = start.subList(initialStore + 1, started).any {
        val target = (it as? OneRegisterInstruction)?.registerA
        target != null && (it.opcode.setsRegister() && target == holder || it.opcode.setsWideRegister() && target + 1 == holder)
    }
    if (rewritten) return null
    return StatusHolder(owner.type, field, update, store, put.registerA, old, self, constructor, initialStore, started)
}

/** The plugin's two size methods. */
internal class BarSizes(val shrink: Method, val fullSize: Method)

/**
 * The plugin's static `(SeekBar, plugin)V` size methods, both setting the thumb's alpha and whether
 * the bar takes drags: the full-size one loads 255 for the alpha, the shrinking one doesn't.
 * Refuses unless there's exactly one of each.
 */
internal fun barSizes(plugin: ClassDef): BarSizes {
    val sizes = plugin.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(SEEK_BAR, plugin.type) &&
            method.calls(SET_ALPHA) && method.calls(SET_ENABLED)
    }
    val (fullSize, shrink) = sizes.partition { method ->
        method.implementation!!.instructions.any { (it as? WideLiteralInstruction)?.wideLiteral == 255L }
    }
    if (fullSize.size != 1 || shrink.size != 1) {
        refuse(
            "${plugin.type} has ${fullSize.size} method(s) making its SeekBar full size and ${shrink.size} " +
                "shrinking it, expected one of each",
        )
    }
    return BarSizes(shrink.single(), fullSize.single())
}

/**
 * The full-screen controls' fade timer: the one instance `()V` method of [controls] that calls
 * [SEND_DELAYED], after [REMOVE_MESSAGES] drops the timer already set.
 */
internal fun fadeTimer(controls: ClassDef): Method {
    val timers = controls.methods.filter { it.calls(SEND_DELAYED) }
    val timer = timers.singleOrNull()
        ?: refuse("${controls.type} sets a delayed message in ${timers.size} methods, expected one, its fade timer")
    if (AccessFlags.STATIC.isSet(timer.accessFlags) || timer.returnType != "V" || timer.parameterTypes.isNotEmpty() ||
        !timer.calls(REMOVE_MESSAGES)
    ) {
        refuse("${controls.type}->${timer.name}, its fade timer, is no longer an instance ()V that drops the old timer first")
    }
    return timer
}

/**
 * The newer player's controls hide: the one instance `V` method among [holders] (the classes that
 * load [CONTROLS_EXTENSION]) that drops its pending callbacks and posts a runnable.
 */
internal fun extensionFadeTimer(holders: List<ClassDef>): Pair<ClassDef, Method> {
    val timers = holders.flatMap { holder ->
        holder.methods.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.calls(POST_DELAYED) &&
                it.calls(REMOVE_CALLBACKS)
        }.map { holder to it }
    }
    return timers.singleOrNull()
        ?: refuse("expected one method of the classes loading \"$CONTROLS_EXTENSION\" to post the controls' hide, found ${timers.size}")
}

/**
 * The landscape player's controls updater as its controller is built on it: [type] is the interface
 * the updater implements (581 `LX/9Wi;`, 582 `LX/9tH;`), which the controller's constructor takes,
 * unless [folded]. On 583 Redex folded the updater into its one controller, as a static method of the
 * controller taking it last (`LX/8Mz;->A00`), and [type] is that controller. [update] is the call the
 * controller makes to hand the updater a new control state.
 */
internal class ControlsUpdater(val type: String, val folded: Boolean, val update: String, val virtual: Boolean = false)

/**
 * The landscape player's controls updater, among [holders], whose interfaces are found with
 * [classOf]: an instance `V` method tracing [PLAYER_CONTROL_UPDATE] that an interface of its class
 * declares, or a static `V` method tracing it that takes its own class. Refuses unless there's
 * exactly one such interface or class.
 */
internal fun controlsUpdater(holders: List<ClassDef>, classOf: (String) -> ClassDef?): ControlsUpdater {
    val found = holders.distinctBy { it.type }.flatMap { holder ->
        methodsHolding(holder, PLAYER_CONTROL_UPDATE).filter { it.returnType == "V" }.flatMap { update ->
            val shape = "${update.name}(${update.parameterTypes.joinToString("")})V"
            if (AccessFlags.STATIC.isSet(update.accessFlags)) {
                listOf(ControlsUpdater(holder.type, true, "${holder.type}->$shape"))
                    .filter { holder.type in update.parameterTypes.map(CharSequence::toString) }
            } else {
                val faces = holder.interfaces.mapNotNull(classOf).filter { face -> face.methods.any { it.sameAs(update) } }
                    .map { ControlsUpdater(it.type, false, "${it.type}->$shape") }
                // 585 (arm64 LX/09hT;->A00, which the controller LX/09hU; is built with) drops the interface: the
                // updater is a class of its own that the controller's constructor takes. A bridge or synthetic
                // method is a lambda switch that merely traces the same string, never the updater.
                val flags = update.accessFlags
                if (faces.isEmpty() && !AccessFlags.BRIDGE.isSet(flags) && !AccessFlags.SYNTHETIC.isSet(flags)) {
                    listOf(ControlsUpdater(holder.type, false, "${holder.type}->$shape", virtual = true))
                } else {
                    faces
                }
            }
        }
    }.distinctBy { it.type }
    return found.singleOrNull()
        ?: refuse("expected one interface or controller for the controls updater tracing \"$PLAYER_CONTROL_UPDATE\", found ${found.size}")
}

/**
 * Whether [classDef] is a controller of the controls [updater]: one whose constructor takes the
 * updater's interface, or the controller the updater is folded into.
 */
internal fun takesUpdater(classDef: ClassDef, updater: ControlsUpdater) = if (updater.folded) {
    classDef.type == updater.type
} else {
    classDef.methods.any { method -> method.name == "<init>" && method.parameterTypes.any { it.toString() == updater.type } }
}

/** The fields of [owner]'s own [RUNNABLE]s that [method] hands to an interface method with a delay. */
private fun delayedRunnables(owner: String, method: Method): Set<String> {
    val code = method.implementation?.instructions?.toList().orEmpty()
    return code.indices.mapNotNull { index ->
        val post = (code[index] as? ReferenceInstruction)?.takeIf { code[index].opcode == Opcode.INVOKE_INTERFACE }
            ?.reference as? MethodReference
        val field = (code.getOrNull(index - 1) as? ReferenceInstruction)?.takeIf { it.opcode == Opcode.IGET_OBJECT }
            ?.reference as? FieldReference
        field?.toString()?.takeIf {
            post != null && post.returnType == "V" && post.parameterTypes.map(CharSequence::toString) == listOf(RUNNABLE, "J") &&
                field.definingClass == owner && field.type == RUNNABLE
        }
    }.toSet()
}

private fun delayedRunnables(controller: ClassDef) = controller.methods.flatMap { delayedRunnables(controller.type, it) }.toSet()

/**
 * The landscape player's controls hide: of [controllers] (the classes taking the updater), the one
 * that posts one of its own runnables with a delay, that runnable's class, found with [classOf] as
 * the `new-instance` its constructor stores in the field, and its `run()V`, which asks that
 * controller to hide the controls through a method other than the one posting it.
 */
internal fun controlsHideRunnable(controllers: List<ClassDef>, classOf: (String) -> ClassDef?): Pair<ClassDef, Method> {
    val posting = controllers.distinctBy { it.type }.map { it to delayedRunnables(it) }.filter { it.second.isNotEmpty() }
    val (controller, fields) = posting.singleOrNull()
        ?: refuse("expected one class built with the controls updater to post a runnable with a delay, found ${posting.size}")
    val field = fields.singleOrNull()
        ?: refuse("${controller.type} posts ${fields.size} of its runnables with a delay, expected the one hide")
    val made = controller.methods.filter { it.name == "<init>" }.mapNotNull { init ->
        val code = init.implementation?.instructions?.toList().orEmpty()
        val put = code.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference.toString() == field }
        if (put < 0) return@mapNotNull null
        val register = (code[put] as TwoRegisterInstruction).registerA
        code.subList(0, put).lastOrNull { it.opcode == Opcode.NEW_INSTANCE && (it as OneRegisterInstruction).registerA == register }
            ?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
    }.distinct()
    val runnable = made.singleOrNull()?.let(classOf)?.takeIf { RUNNABLE in it.interfaces }
        ?: refuse("$field isn't filled with one Runnable of Facebook's by ${controller.type}'s constructor")
    val run = runnable.methods.singleOrNull { it.name == "run" && it.returnType == "V" && it.parameterTypes.isEmpty() }
        ?: refuse("${runnable.type}, the controls' hide runnable, has no run()V")
    val posters = controller.methods.filter { field in delayedRunnables(controller.type, it) }
    val asked = run.implementation!!.instructions.mapNotNull { instruction ->
        (instruction as? ReferenceInstruction)?.takeIf { instruction.opcode.name.startsWith("invoke") }?.reference as? MethodReference
    }.filter { it.definingClass == controller.type }
    if (asked.isEmpty() || asked.any { call -> posters.any { it.name == call.name && it.returnType == call.returnType } }) {
        refuse("${runnable.type}->run doesn't ask ${controller.type} to hide the controls")
    }
    return runnable to run
}

/**
 * First thing in the scrubber's passive look: while the extension keeps the bar, run the active
 * look on the same instance, hide the time label and the most replayed [graph] the way the passive
 * look does, and return. The graph is hidden by its own flag and redrawn; a graph the views holder
 * doesn't have yet is skipped.
 * Otherwise Facebook's own code runs from its first instruction. The range form names p0, which
 * can sit past v15 in these long methods, and so does the move that reads it into v0 (format 22x).
 *
 * The active look starts with Facebook's time writer, which moves the bar to the player's position,
 * or to the start when the reel's length isn't known yet. The passive look never moves the bar, so
 * the hook puts the bar back where it was. Letting go of a drag switches to the passive look just
 * before Facebook reads the bar to seek, and where the player hadn't followed the drag (a reel
 * showing preview frames instead) the seek went back to where the drag began (issue #120).
 */
internal fun MutableMethod.activeInstead(looks: ScrubberLooks, graph: GraphHide) {
    requireLocals(PATCH, 2)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEPS_REEL_BAR
            move-result v0
            if-eqz v0, :facebook
            move-object/from16 v0, p0
            iget-object v0, v0, ${looks.viewsField}
            iget-object v0, v0, ${looks.barField}
            invoke-virtual { v0 }, $GET_PROGRESS
            move-result v1
            invoke-virtual/range { p0 .. p0 }, ${looks.type}->${looks.active.name}()V
            invoke-virtual { v0, v1 }, $SET_PROGRESS
            move-object/from16 v0, p0
            iget-object v0, v0, ${looks.viewsField}
            iget-object v0, v0, ${looks.labelField}
            invoke-static { v0 }, $HIDE_TIME_LABEL
            move-object/from16 v0, p0
            iget-object v0, v0, ${looks.viewsField}
            iget-object v0, v0, ${graph.graphField}
            if-eqz v0, :kept
            const/4 v1, 0x1
            iput-boolean v1, v0, ${graph.hiddenField}
            invoke-virtual { v0 }, $INVALIDATE
            :kept
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * In the light mount, at the start of its branch for another video ([AnotherVideo.at]): while the
 * extension keeps the bar, run Facebook's [chapters] reset on a scrubber of its class, the one in
 * [AnotherVideo.scrubber] (on 584 on the helper that scrubber holds, skipped when there's none), and
 * hide the most replayed [graph] by its flag, then go on resetting the bar as always. Facebook hands
 * its one Reels scrubber reel after reel without asking for a look again, so the last reel's graph and
 * chapter marks, which only the passive look took away, stayed over the next reel's bar, and where the
 * marks had drawn the bar the next reel had none. The reset reads only what the scrubber's
 * constructor sets. A scrubber of another class, or a graph the views holder doesn't have, is
 * skipped. The hook borrows two low registers that nothing reads from there on, locals first, then
 * parameters, which 584's inlined mount needs.
 */
internal fun MutableMethod.clearsLastReel(looks: ScrubberLooks, graph: GraphHide, chapters: ChapterReset, another: AnotherVideo) {
    val at = another.at
    val (v, flag) = freeLocalsAt(PATCH, at, 2, parameters = true)
    val helper = chapters.field?.let {
        """
            iget-object v$v, v$v, $it
            if-eqz v$v, :facebook
        """
    }.orEmpty()
    addInstructionsWithLabels(
        at,
        """
            invoke-static { }, $CLEARS_LAST_REEL
            move-result v$v
            if-eqz v$v, :facebook
            move-object/from16 v$v, v${another.scrubber}
            instance-of v$flag, v$v, ${chapters.owner}
            if-eqz v$flag, :facebook
            check-cast v$v, ${chapters.owner}
            $helper
            invoke-static { v$v }, ${chapters.call}
            move-object/from16 v$v, v${another.scrubber}
            iget-object v$v, v$v, ${looks.viewsField}
            iget-object v$v, v$v, ${graph.graphField}
            if-eqz v$v, :facebook
            const/4 v$flag, 0x1
            iput-boolean v$flag, v$v, ${graph.hiddenField}
            invoke-virtual { v$v }, $INVALIDATE
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

/**
 * In the light mount, at the start of its branch for another video ([AnotherVideo.at]): hand the
 * extension the scrubber's time label and bar, which Facebook leaves where the last reel stopped until
 * the next one's progress update runs (#90, #120). A views holder that isn't there yet goes straight
 * on. The hook borrows two low registers that nothing reads from there on, locals first, then
 * parameters, which 584's inlined mount needs.
 */
internal fun MutableMethod.startsNextReelOver(looks: ScrubberLooks, another: AnotherVideo) {
    val at = another.at
    val (v, bar) = freeLocalsAt(PATCH, at, 2, parameters = true)
    addInstructionsWithLabels(
        at,
        """
            move-object/from16 v$v, v${another.scrubber}
            iget-object v$v, v$v, ${looks.viewsField}
            if-eqz v$v, :facebook
            iget-object v$bar, v$v, ${looks.barField}
            iget-object v$v, v$v, ${looks.labelField}
            invoke-static { v$v, v$bar }, $NEXT_REEL
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

/**
 * In the Reels scrubber registry's hand-over ([ReelHandOver]), right after it reads the reel's id: take
 * the scrubber off the holder Facebook picked, as Facebook does next, and hand the extension its time
 * label, its bar, the video id of the bar info it holds (null without one) and the reel's id. Then
 * Facebook's test of the id runs as before. A scrubber or views holder that isn't there goes straight
 * on. The hook borrows three low registers that nothing reads from there on, all 583's hand-over has
 * free there: the register that takes the scrubber takes the id after it, and the bar's register holds
 * the bar info first. The id's register is set to null before the info is tested, so it holds a String
 * on both ways onto the call. 584's hand-over has two free locals there, so its third is a parameter
 * Facebook is done with.
 */
internal fun MutableMethod.notesHandOver(handOver: ReelHandOver, looks: ScrubberLooks, another: AnotherVideo) {
    val (held, label, bar) = freeLocalsAt(PATCH, handOver.at, 3, parameters = true)
    addInstructionsWithLabels(
        handOver.at,
        """
            iget-object v$held, v${handOver.holder}, ${handOver.scrubber}
            if-eqz v$held, :facebook
            iget-object v$label, v$held, ${looks.viewsField}
            if-eqz v$label, :facebook
            iget-object v$bar, v$held, ${another.info}
            const/4 v$held, 0x0
            if-eqz v$bar, :ask
            iget-object v$held, v$bar, ${another.id}
            :ask
            iget-object v$bar, v$label, ${looks.barField}
            iget-object v$label, v$label, ${looks.labelField}
            invoke-static { v$label, v$bar, v$held, v${handOver.shown} }, $HANDS_OVER
        """,
        ExternalLabel("facebook", getInstruction(handOver.at)),
    )
}

/**
 * At the hand-over's one `return-void` ([ReelHandOver.end]), which every way on from the hook above
 * reaches: ask the extension whether Facebook left the scrubber on the last reel. It needs nothing
 * from the method, so nothing is borrowed, and nothing jumps onto the return.
 */
internal fun MutableMethod.checksHandOver(handOver: ReelHandOver) {
    addInstructions(handOver.end, "invoke-static { }, $LEFT_ON_LAST_REEL")
}

/**
 * In the registry's hand-over, between the read of its hide flag and the test of it
 * ([RegistryReturns.test]): hand the extension the flag and the reel's key, both still in their
 * registers, so each call the registry gets is seen ahead of both early returns. Nothing is
 * borrowed, and only the read reaches the test.
 */
internal fun MutableMethod.notesRegistryAsk(returns: RegistryReturns) {
    addInstructions(returns.test, "invoke-static { v${returns.flag}, v${returns.key} }, $REGISTRY_ASKS")
}

/**
 * At the registry's test for a scrubber holder ([RegistryReturns.noHolder]): with none, tell the
 * extension, then Facebook's test returns as before. A holder goes straight to Facebook's test.
 * Nothing is borrowed, and only the instruction before reaches the test.
 */
internal fun MutableMethod.notesNoScrubber(returns: RegistryReturns) {
    addInstructionsWithLabels(
        returns.noHolder,
        """
            if-nez v${returns.holder}, :facebook
            invoke-static { }, $NO_SCRUBBER
        """,
        ExternalLabel("facebook", getInstruction(returns.noHolder)),
    )
}

/**
 * In the scrubber's load, right after it stores the bar info of the reel it takes ([ScrubberLoad.at]):
 * hand the extension the scrubber's time label, the scrubber and that reel's id. A views holder that
 * isn't there goes straight on. The reel's id is read first, as the bar info's register may be one of
 * the free locals the hook borrows.
 */
internal fun MutableMethod.reportsLoad(looks: ScrubberLooks, load: ScrubberLoad, another: AnotherVideo) {
    val (label, scrubber, reel) = freeLocalsAt(PATCH, load.at, 3)
    addInstructionsWithLabels(
        load.at,
        """
            iget-object v$reel, v${load.info}, ${another.id}
            move-object/from16 v$label, p0
            iget-object v$label, v$label, ${looks.viewsField}
            if-eqz v$label, :facebook
            iget-object v$label, v$label, ${looks.labelField}
            move-object/from16 v$scrubber, p0
            invoke-static { v$label, v$scrubber, v$reel }, $LOADS
        """,
        ExternalLabel("facebook", getInstruction(load.at)),
    )
}

/**
 * Ahead of the scrubber's unload clearing the registry's default holder ([HolderClear.clear]): ask the
 * extension about what the registry holds and the scrubber's own holder. A yes skips Facebook's clear,
 * so a scrubber unloading late leaves the holder of the one loaded after it.
 */
/**
 * With the switch on, a press of the bar hands the footer its everyday look instead of the seek one.
 * The load's register holds nothing Facebook reads, since the load overwrites it.
 */
internal fun MutableMethod.keepsOverlayOnPress(seek: FooterSeek, visibility: FooterVisibility) {
    val look = seek.register
    addInstructionsWithLabels(seek.at, """
            invoke-static { }, $KEEPS_OVERLAY
            move-result v$look
            if-eqz v$look, :facebook
            sget-object v$look, ${visibility.shown}
            goto :hand
        """, ExternalLabel("facebook", getInstruction(seek.at)), ExternalLabel("hand", getInstruction(seek.at + 1)))
}

internal fun MutableMethod.keepsNewerHolder(clear: HolderClear, load: ScrubberLoad, returns: RegistryReturns) {
    val (current, own) = freeLocalsAt(PATCH, clear.clear, 2, targets = listOf(clear.store + 1))
    addInstructionsWithLabels(
        clear.clear,
        """
            iget-object v$current, v${clear.registry}, ${returns.defaultHolder}
            move-object/from16 v$own, p0
            iget-object v$own, v$own, ${load.ownHolder}
            invoke-static { v$current, v$own }, $KEEPS_DEFAULT_HOLDER
            move-result v$current
            if-nez v$current, :kept
        """,
        ExternalLabel("kept", getInstruction(clear.store + 1)),
    )
}

/**
 * First thing in the light mount, at its trace ([AnotherVideo.start]): hand the extension the
 * scrubber's time label and the scrubber, as the scrubber takes the reel it's given. A views holder
 * that isn't there goes straight on. It goes in after the hooks at the branch for another video, whose
 * index is the unpatched mount's. The hook borrows two low locals that nothing reads from there on,
 * which at the top of the base's own mount is any two.
 */
internal fun MutableMethod.reportsMount(looks: ScrubberLooks, another: AnotherVideo) {
    val (label, scrubber) = freeLocalsAt(PATCH, another.start, 2)
    addInstructionsWithLabels(
        another.start,
        """
            move-object/from16 v$label, v${another.scrubber}
            iget-object v$label, v$label, ${looks.viewsField}
            if-eqz v$label, :facebook
            iget-object v$label, v$label, ${looks.labelField}
            move-object/from16 v$scrubber, v${another.scrubber}
            invoke-static { v$label, v$scrubber }, $MOUNTS
        """,
        ExternalLabel("facebook", getInstruction(another.start)),
    )
}

/** Whether [method] is the extension's [UPDATE_AGAIN_STUB]: static, void, taking one Object. */
internal fun isUpdateAgainStub(method: Method) = method.name == UPDATE_AGAIN_STUB &&
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
    method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")

/**
 * Refuses unless the scrubber base and its progress update can be reached from the extension's
 * package, the update being the static method taking the base that the stub calls.
 */
internal fun checkUpdateAgain(progress: ProgressUpdate) {
    val base = progress.base
    if (!AccessFlags.PUBLIC.isSet(base.accessFlags)) refuse("${base.type}, the scrubber base, isn't public")
    val update = progress.update
    if (!AccessFlags.PUBLIC.isSet(update.accessFlags) || !AccessFlags.STATIC.isSet(update.accessFlags)) {
        refuse("${base.type}->${update.name}, the progress update, isn't public and static")
    }
}

/**
 * Fills the extension's [UPDATE_AGAIN_STUB] with a run of the scrubber base's progress update on
 * the scrubber it's handed: Facebook's frame callback runs the same update. d8 gives the stub no
 * locals of its own, so the scrubber is cast in p0.
 */
internal fun BytecodePatchContext.fillUpdateAgain(progress: ProgressUpdate) {
    checkUpdateAgain(progress)
    val base = progress.base.type
    val stub = mutableClassDefBy(PROGRESS_BAR).methods.singleOrNull(::isUpdateAgainStub)
        ?: refuse("$PROGRESS_BAR has no static void $UPDATE_AGAIN_STUB(Object)")
    stub.addInstructions(
        0,
        """
            check-cast p0, $base
            invoke-static { p0 }, $base->${progress.update.name}($base)V
        """,
    )
}

/**
 * First thing in the scrubber's look selector, ahead of the look count: hand the extension the
 * scrubber's time label and the look asked for, and go on with the look it answers, which may be
 * null, a look the selector returns on at once. A views holder that isn't there goes straight on with
 * the look as asked. The range-free moves name the look wherever it sits.
 */
internal fun MutableMethod.asksLook(looks: ScrubberLooks) {
    requireLocals(PATCH, 2)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${looks.viewsField}
            if-eqz v0, :facebook
            iget-object v0, v0, ${looks.labelField}
            move-object/from16 v1, p1
            invoke-static { v0, v1 }, $LOOK_FOR
            move-result-object v1
            move-object/from16 p1, v1
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * First thing in the scrubber's minimum length check: while the extension keeps the bar, answer
 * yes, so a short reel gets the passive look (which the hook above turns into the active one)
 * instead of no bar. Otherwise Facebook's own code runs from its first instruction.
 */
internal fun MutableMethod.longEnoughWhileKept() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $BARS_EVERY_REEL
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x1
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * In the scrubber base's bar info builder, between the info's `new-instance` and its constructor
 * call ([BarInfo.call]): hand the extension the flag saying whether the reel has a bar, and the
 * reel's length, and pass on its answer in the flag's register. Where the builder works the length
 * checks out itself (584), also answer them as the hooks on the checks do elsewhere: scrubbable while
 * the extension gives every reel a bar, and not faded while it keeps short reels from the faded bar.
 * Those two can sit past v15, hence the three-register forms, and a boolean or-ed or and-ed with a
 * boolean stays one for the verifier. The hook has no branch, and nothing jumps between the two
 * instructions. It borrows one low local that nothing reads from there on.
 */
internal fun MutableMethod.hasBarWhileKept(info: BarInfo) {
    val checks = if (info.scrubbable == null || info.faded == null) "" else {
        val v = freeLocalsAt(PATCH, info.call, 1).single()
        """
            invoke-static { }, $BARS_EVERY_REEL
            move-result v$v
            or-int v${info.scrubbable}, v${info.scrubbable}, v$v
            invoke-static { }, $NO_FADED_BAR
            move-result v$v
            xor-int/lit8 v$v, v$v, 0x1
            and-int v${info.faded}, v${info.faded}, v$v
        """
    }
    addInstructions(
        info.call,
        """
            invoke-static { v${info.flag}, v${info.length} }, $HAS_BAR
            move-result v${info.flag}
        """ + checks,
    )
}

/**
 * In the scrubber's progress update, in front of its [dragCheck], so only once Facebook has moved
 * the bar: read the time label and the bar off the scrubber the update takes, and when the extension
 * asks for the time, have Facebook's own writer refresh it, then go on to the drag check as always.
 * The extension also puts back a label Facebook left hidden (#73), which is why it gets the bar. A
 * views holder that isn't there yet goes straight on. The hook borrows two low locals that nothing
 * reads from there on.
 */
internal fun MutableMethod.refreshesTimeWithBar(progress: ProgressUpdate, looks: ScrubberLooks) {
    val at = dragCheck(this, progress)
    val (v, bar) = freeLocalsAt(PATCH, at, 2)
    val base = progress.base.type
    addInstructionsWithLabels(
        at,
        """
            move-object/from16 v$v, p0
            iget-object v$v, v$v, ${looks.viewsField}
            if-eqz v$v, :facebook
            iget-object v$bar, v$v, ${looks.barField}
            iget-object v$v, v$v, ${looks.labelField}
            invoke-static { v$v, v$bar }, $REFRESHES_TIME
            move-result v$v
            if-eqz v$v, :facebook
            invoke-static/range { p0 .. p0 }, $base->${progress.writeTime.name}($base)V
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

/**
 * In the scrubber's progress update, at [at], the index the time refresh took ([refreshesTimeWithBar]
 * goes in first): on a scrubber of [looks]' class, hand the extension the time label, and when it
 * answers yes, run the scrubber's passive look, which the hook in it turns into the active one while
 * the bar is kept. Facebook asks for a look only on a touch or a change of look, so a switch flipped
 * with Reels open left the bar in the look it had: Facebook's thin line after turning it on, or full
 * size with its time stopped after turning it off. A scrubber of another class or a views holder that
 * isn't there yet goes straight on to the refresh. The hook borrows two low locals that nothing reads
 * from there on.
 */
internal fun MutableMethod.looksAgainFirst(looks: ScrubberLooks, at: Int) {
    if (AccessFlags.PRIVATE.isSet(looks.passive.accessFlags) || AccessFlags.STATIC.isSet(looks.passive.accessFlags)) {
        refuse("${looks.type}->${looks.passive.name} is private or static, so the progress update can't run it")
    }
    val (v, flag) = freeLocalsAt(PATCH, at, 2)
    addInstructionsWithLabels(
        at,
        """
            move-object/from16 v$v, p0
            instance-of v$flag, v$v, ${looks.type}
            if-eqz v$flag, :facebook
            iget-object v$flag, v$v, ${looks.viewsField}
            if-eqz v$flag, :facebook
            iget-object v$flag, v$flag, ${looks.labelField}
            invoke-static { v$flag }, $LOOKS_AGAIN
            move-result v$flag
            if-eqz v$flag, :facebook
            check-cast v$v, ${looks.type}
            invoke-virtual { v$v }, ${looks.type}->${looks.passive.name}()V
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

/**
 * First thing in the wait before the scrubber's next progress update: while the extension keeps
 * the bar, answer [SMOOTH_FRAME_DELAY_MS], so the bar moves about once a frame instead of in steps.
 * Otherwise Facebook's own code runs from its first instruction.
 */
internal fun MutableMethod.smoothWhileKept() {
    requireLocals(PATCH, 2)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $SMOOTHS_BAR
            move-result v0
            if-eqz v0, :facebook
            const-wide/16 v0, $SMOOTH_FRAME_DELAY_MS
            return-wide v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * First thing in the scrubber's short reel check: while the extension keeps the bar, answer no, so
 * a short reel isn't given the look that fades its bar out. Otherwise Facebook's own code runs from
 * its first instruction.
 */
internal fun MutableMethod.notShortWhileKept() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $NO_FADED_BAR
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * In the look selector, in front of its skip of a look the bar already has: read the look Facebook
 * parked, and when the extension says the look asked for takes its place, go on past the skip, so
 * Facebook parks this look in its stead (or applies it again). Otherwise the skip runs as before.
 * One low local that nothing reads there carries the parked look and then the answer.
 */
internal fun MutableMethod.replacesParkedLook(skip: SameLookSkip) {
    val free = freeLocalsAt(PATCH, skip.branch, 1).single()
    val look = implementation!!.registerCount - 1
    if (look > 15 || skip.flow > 15) refuse("$definingClass->$name keeps its look or its state flow past v15")
    addInstructionsWithLabels(
        skip.branch,
        """
            invoke-interface { v${skip.flow} }, ${skip.read}
            move-result-object v$free
            check-cast v$free, ${skip.state}
            iget-object v$free, v$free, ${skip.parked}
            invoke-static { v$look, v$free }, $REPLACES_PARKED
            move-result v$free
            if-nez v$free, :replaces
        """,
        ExternalLabel("replaces", getInstruction(skip.branch + 1)),
    )
}

/** First thing in the scrubber's look selector: hand the look to the extension, which only counts it. */
internal fun MutableMethod.countsLook() {
    // The range form names any register, so p1 past v15 is fine.
    addInstructions(0, "invoke-static/range { p1 .. p1 }, $COUNTS_LOOK")
}

/**
 * Once the status holder's constructor is done with the starting status ([StatusHolder.started]):
 * while the extension keeps the bar, store VISIBLE over it, so a reel's footer starts in its lifted
 * layout. The constructor has no branch, so nothing jumps past the code.
 */
internal fun MutableMethod.startsLifted(holder: StatusHolder, status: SeekBarStatus) {
    val at = holder.started
    val self = (getInstruction(holder.initialStore) as TwoRegisterInstruction).registerB
    val free = freeLocalsAt(PATCH, at, 1).single()
    if (self > 15) refuse("$definingClass->$name keeps itself past v15")
    addInstructionsWithLabels(
        at,
        """
            invoke-static { }, $LIFTS_FOOTER
            move-result v$free
            if-eqz v$free, :facebook
            sget-object v$free, ${status.visible}
            iput-object v$free, v$self, ${holder.field}
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

/**
 * Right after the status holder's update stores a new status: when that's HIDDEN and the extension
 * keeps the bar, store VISIBLE instead, which the update then hands on as it would HIDDEN, and
 * return if VISIBLE was already the status. The instruction after the store isn't a branch target
 * on 582, and the code borrows one local nothing reads from there on that isn't the old status.
 */
internal fun MutableMethod.staysLifted(holder: StatusHolder, status: SeekBarStatus) {
    val at = holder.store + 1
    val registers = listOf(holder.new, holder.old, holder.self)
    // The old status is dead past the store on 582, so liveness alone could hand it out.
    val live = liveAcrossInjection(at)
    val free = (0 until minOf(localRegisterCount(), 16)).firstOrNull { it !in live && it !in registers }
        ?: refuse("$definingClass->$name has no local free after its status store besides $registers")
    if (registers.any { it > 15 }) refuse("$definingClass->$name keeps the status past v15: $registers")
    addInstructionsWithLabels(
        at,
        """
            sget-object v$free, ${status.hidden}
            if-ne v${holder.new}, v$free, :facebook
            invoke-static { }, $LIFTS_FOOTER
            move-result v$free
            if-eqz v$free, :facebook
            sget-object v${holder.new}, ${status.visible}
            iput-object v${holder.new}, v${holder.self}, ${holder.field}
            if-ne v${holder.old}, v${holder.new}, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

/**
 * Once the status holder's constructor is done with the starting status, in front of [startsLifted]
 * (which goes in first at the same index): hand the extension the holder, so it can lift the footer
 * of the reel on screen when the switch goes on with Reels open.
 */
internal fun MutableMethod.reportsFooter(holder: StatusHolder) {
    val self = (getInstruction(holder.initialStore) as TwoRegisterInstruction).registerB
    if (self > 15) refuse("$definingClass->$name keeps itself past v15")
    addInstructions(holder.started, "invoke-static { v$self }, $FOOTER_MADE")
}

/**
 * Refuses unless [owner], the status holder's class, and its update can be reached from the
 * extension's package, and the update takes one object, the player event, so a call with none can
 * be told apart.
 */
internal fun checkFooterLift(owner: ClassDef, holder: StatusHolder) {
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags)) refuse("${holder.type}, the footer's status holder, isn't public")
    val update = holder.update
    if (!AccessFlags.PUBLIC.isSet(update.accessFlags)) refuse("${holder.type}->${update.name} isn't public")
    val parameters = update.parameterTypes.map(CharSequence::toString)
    if (parameters.size != 1 || !(parameters[0].startsWith("L") || parameters[0].startsWith("["))) {
        refuse("${holder.type}->${update.name} takes $parameters, expected one event object")
    }
}

/**
 * First thing in the status holder's update: when it's handed no event, which only the extension's
 * [FOOTER_LIFT_HELPER] does (Facebook's code returns at once for one), and the extension keeps the
 * bar, take a HIDDEN status to VISIBLE through Facebook's own store, Function1 call and event, by
 * jumping to the store with VISIBLE as the new status and the status now as the old one. Anything
 * else returns, and an update with an event runs Facebook's code from its first instruction.
 * [staysLifted] goes in first, after the store, so the store keeps its index.
 */
internal fun MutableMethod.liftsWhenAsked(holder: StatusHolder, status: SeekBarStatus) {
    val store = getInstruction(holder.store)
    val put = store as? TwoRegisterInstruction
    if (store.opcode != Opcode.IPUT_OBJECT || (store as ReferenceInstruction).reference.toString() != holder.field ||
        put?.registerA != holder.new || put.registerB != holder.self
    ) {
        refuse("$definingClass->$name has no store of its new status at ${holder.store} any more")
    }
    if (listOf(holder.new, holder.old, holder.self).any { it > 15 }) refuse("$definingClass->$name keeps the status past v15")
    val event = holder.self + 1
    addInstructionsWithLabels(
        0,
        """
            if-nez v$event, :facebook
            invoke-static { }, $LIFTS_FOOTER
            move-result v${holder.new}
            if-eqz v${holder.new}, :stays
            iget-object v${holder.old}, v${holder.self}, ${holder.field}
            sget-object v${holder.new}, ${status.hidden}
            if-ne v${holder.old}, v${holder.new}, :stays
            sget-object v${holder.new}, ${status.visible}
            goto :apply
            :stays
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
        ExternalLabel("apply", store),
    )
}

/** Whether [method] is the extension's [LIFT_FOOTER_STUB]: static, boolean, taking one Object. */
internal fun isLiftFooterStub(method: Method) = method.name == LIFT_FOOTER_STUB &&
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
    method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")

/**
 * The helper on the status holder's class: runs the holder's update with no event, which
 * [liftsWhenAsked] turns into the lift, and answers whether the status changed. Two locals and the
 * holder.
 */
internal fun footerLiftHelper(holder: StatusHolder): MutableMethod {
    val event = holder.update.parameterTypes.single().toString()
    return ImmutableMethod(
        holder.type,
        FOOTER_LIFT_HELPER,
        listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
        "Z",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${holder.type}
                iget-object v0, p0, ${holder.field}
                const/4 v1, 0x0
                invoke-virtual { p0, v1 }, ${holder.type}->${holder.update.name}($event)V
                iget-object v1, p0, ${holder.field}
                if-eq v0, v1, :same
                const/4 v0, 0x1
                return v0
                :same
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}

/**
 * Adds [footerLiftHelper] to the status holder's class and fills the extension's [LIFT_FOOTER_STUB]
 * with a call to it. d8 gives the stub no locals of its own, so the answer goes back in p0.
 */
internal fun BytecodePatchContext.fillFooterLift(holder: StatusHolder) {
    val owner = mutableClassDefBy(holder.type)
    if (owner.methods.none { it.name == FOOTER_LIFT_HELPER }) owner.methods.add(footerLiftHelper(holder))
    val stub = mutableClassDefBy(PROGRESS_BAR).methods.singleOrNull(::isLiftFooterStub)
        ?: refuse("$PROGRESS_BAR has no static boolean $LIFT_FOOTER_STUB(Object)")
    stub.addInstructions(
        0,
        """
            invoke-static { p0 }, ${holder.type}->$FOOTER_LIFT_HELPER(Ljava/lang/Object;)Z
            move-result p0
            return p0
        """,
    )
}

/**
 * First thing in the method that shrinks the bar: while the extension keeps it, call [fullSize]
 * with the same two arguments and return, so the bar is made full size instead. Otherwise
 * Facebook's own code runs from its first instruction.
 */
internal fun MutableMethod.fullSizeInstead(plugin: String, fullSize: Method) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEPS_REEL_BAR
            move-result v0
            if-eqz v0, :facebook
            invoke-static { p0, p1 }, $plugin->${fullSize.name}($SEEK_BAR$plugin)V
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * First thing in the fade timer: while the extension keeps the controls, return without setting
 * a timer. Otherwise Facebook's own code runs from its first instruction.
 */
internal fun MutableMethod.noTimerWhileKept() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEPS_CONTROLS
            move-result v0
            if-eqz v0, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * The scrubber's own SeekBar class (582 `LX/9A0;`): the type the scrubber [base] checks the bar
 * against with an `instance-of` straight after reading it from [barField]. The bar is inflated from
 * a layout, so this is where the code names it. Refuses unless that's exactly one type.
 */
internal fun scrubberSeekBar(base: ClassDef, barField: String): String {
    val types = base.methods.flatMap { method ->
        val code = method.code()
        code.indices.mapNotNull { at ->
            val read = code[at].takeIf { it.opcode == Opcode.IGET_OBJECT && it.reference() == barField } ?: return@mapNotNull null
            val check = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.INSTANCE_OF } ?: return@mapNotNull null
            val bar = (read as TwoRegisterInstruction).registerA
            ((check as ReferenceInstruction).reference as TypeReference).type.takeIf { (check as TwoRegisterInstruction).registerB == bar }
        }
    }.distinct()
    return types.singleOrNull() ?: refuse("${base.type} checks the bar it reads from $barField against ${types.size} types, expected one")
}

/** Whether [type] is [ancestor] or extends it, through [superOf], which answers null past the APK's own classes. */
internal fun extendsType(type: String, ancestor: String, superOf: (String) -> String?): Boolean =
    generateSequence(type) { superOf(it) }.take(64).any { it == ancestor }

/** The bar's own [ON_TOUCH] in [bar] (582 `LX/9A0;->onTouchEvent`). Refuses when it has none. */
internal fun barTouch(bar: ClassDef): Method =
    bar.methods.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == ON_TOUCH && it.implementation != null
    } ?: refuse("${bar.type} has no $ON_TOUCH of its own")

/**
 * First in the bar's touch handler: the extension sees every touch, and a yes answers true without
 * running Facebook's code, so the bar keeps the gesture. A no runs the handler as it was, which is
 * also how the drag the extension plays back for a tap reaches it. Nothing has run yet at the
 * start, so the bar and the touch are still in p0 and p1. Refuses when a jump comes back to the
 * start, as the hook would run twice and the register it writes may be live there.
 */
internal fun MutableMethod.takesTapsToSeek() {
    val first = implementation!!.instructions[0] as BuilderInstruction
    if (first.location.labels.isNotEmpty()) refuse("$definingClass->$name has a jump back to its start")
    val register = freeLocalsAt(PATCH, 0, 1).single()
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p1 }, $TAKES_TOUCH
            move-result v$register
            if-eqz v$register, :facebook
            const/4 v$register, 0x1
            return v$register
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}
