/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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

/** The surface name both of the scrubber's `(IZ)Z` length checks compare first, a kept literal. */
internal const val LENGTH_CHECK_SURFACE = "ai_styles_drafts"

/** The log tag of the Reels viewer's footer, which lays out the caption above the bar, a kept literal. */
internal const val REEL_FOOTER = "FbShortsViewerFooterParamsFactory"

internal const val INT_VALUE = "Ljava/lang/Number;->intValue()I"

/** How long, in ms, the kept bar waits between moves: about a frame, where Facebook's default is 100. */
internal const val SMOOTH_FRAME_DELAY_MS = 16

internal const val TEXT_VIEW_SET_TEXT = "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V"

/** A kept class whose superclass is the full-screen video controls every such player builds on. */
internal const val FULLSCREEN_CONTROLS = "Lcom/facebook/feed/video/fullscreen/orion/FeedFullscreenVideoControlsPlugin;"

internal const val SEEK_BAR = "Landroid/widget/SeekBar;"
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
internal const val KEEPS_CONTROLS = "$PROGRESS_BAR->keepsControls()Z"
internal const val HIDE_TIME_LABEL = "$PROGRESS_BAR->hideTimeLabel(Landroid/view/ViewGroup;)V"
internal const val BARS_EVERY_REEL = "$PROGRESS_BAR->barsEveryReel()Z"
internal const val REFRESHES_TIME = "$PROGRESS_BAR->refreshesTime(Landroid/view/ViewGroup;)Z"
internal const val SMOOTHS_BAR = "$PROGRESS_BAR->smoothsBar()Z"
internal const val REEL_LENGTH = "$PROGRESS_BAR->reelLength(I)I"
internal const val VIEW_GROUP = "Landroid/view/ViewGroup;"

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
 * itself and the seek bar listener sets the label's visibility. With the time switch on, the label
 * stays instead, and the scrubber's progress update has Facebook's own time writer refresh it a few
 * times a second, which Facebook does only during a drag.
 *
 * Three more hooks finish the Reels bar (582, issue #73). The scrubber's minimum length check
 * answers yes, so a reel Facebook finds too short for a bar gets one. The Reels footer's own bar
 * check reads such a reel's length as long enough, so its caption leaves the same room above the bar
 * as a longer reel's instead of sitting where the bar now goes. And the wait before the scrubber's
 * next progress update, Facebook's `defaultFrameDelayMs` of 100 ms, drops to about a frame, so the
 * bar moves smoothly instead of in visible steps.
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
 * to hide them about 3 seconds later. On the S25 (581, 2026-10-08) a stack trace of that hide went
 * from this `run()` through the controller to the updater, and none of the hooks above ran in that
 * player. So the same hook goes first in this `run()`. A tap still hides the controls, as the tap
 * handler calls the controller's hide itself.
 *
 * In the default selection with its switch off: it only acts once the switch is turned on.
 */
@Suppress("unused")
val keepProgressBarPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Keep the progress bar",
    description = "Keeps every reel's progress bar full size and moving smoothly, with the time above it, so you can " +
        "drag it without tapping first. Also keeps a full-screen video's controls on screen until you tap. Starts " +
        "off. Turn it on in Hushfacebook settings > Playback.",
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
        val scrubberClass = classDefByOrNull(scrubber.type) ?: refuse("${scrubber.type} went missing")
        val lengthCheck = minimumLengthCheck(scrubberClass)
        val progress = progressUpdate(
            classDefByStrings(SCRUBBER_UPDATE, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        if (progress.base.type != scrubberClass.superclass || !scrubber.viewsField.startsWith("${progress.base.type}->")) {
            refuse("${progress.base.type}, which updates the progress, isn't the base holding ${scrubber.type}'s views")
        }
        val delay = frameDelay(progress)
        val footerCheck = footerBarCheck(
            classDefByStrings(REEL_FOOTER, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        ) { classDefByOrNull(it) }
        val controllers = mutableListOf<ClassDef>()
        classDefForEach {
            if (it.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
            if (takesUpdater(it, updater)) controllers += it
        }
        val (hider, hiderRun) = controlsHideRunnable(controllers) { classDefByOrNull(it) }

        mutableClassDefBy(scrubber.type).methods.single { it.sameAs(scrubber.passive) }
            .activeInstead(scrubber)
        mutableClassDefBy(scrubber.type).methods.single { it.sameAs(lengthCheck) }.longEnoughWhileKept()
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(progress.update) }
            .refreshesTimeFirst(progress, scrubber)
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(delay) }.smoothWhileKept()
        mutableClassDefBy(footerCheck.first.type).methods.single { it.sameAs(footerCheck.second) }.roomForEveryReel()
        if (plugin != null && sizes != null) {
            mutableClassDefBy(plugin.type).methods.single { it.sameAs(sizes.shrink) }
                .fullSizeInstead(plugin.type, sizes.fullSize)
        }
        mutableClassDefBy(controls.type).methods.single { it.sameAs(timer) }.noTimerWhileKept()
        mutableClassDefBy(extension.type).methods.single { it.sameAs(hide) }.noTimerWhileKept()
        mutableClassDefBy(hider.type).methods.single { it.sameAs(hiderRun) }.noTimerWhileKept()
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
)

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
    return ScrubberLooks(scrubber.type, look(scrubber, SCRUBBER_ACTIVE).single(), passive, viewsField, labelField)
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

/**
 * The scrubber's check that a reel is long enough for a bar (582 `LX/9d9;->A1O`). The scrubber has
 * two instance `(IZ)Z` length checks, each loading [LENGTH_CHECK_SURFACE] first. This one answers no
 * through an `if-lt` of the reel's length, its int parameter, against Facebook's minimum, and its
 * answer decides between the passive look and no bar at all. The other (`A1N`) asks whether a reel
 * is long enough for the long-video look and compares the other way. Refuses unless there's
 * exactly one such check.
 */
internal fun minimumLengthCheck(scrubber: ClassDef): Method {
    val checks = methodsHolding(scrubber, LENGTH_CHECK_SURFACE).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("I", "Z")
    }
    val minimum = checks.filter { check ->
        val implementation = check.implementation!!
        // The parameters take the last three registers: this, the length, then the boolean.
        val length = implementation.registerCount - 2
        implementation.instructions.any { it.opcode == Opcode.IF_LT && (it as TwoRegisterInstruction).registerA == length }
    }
    return minimum.singleOrNull()
        ?: refuse("${scrubber.type} has ${minimum.size} length check(s) failing a reel shorter than a minimum, expected one")
}

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

/**
 * The footer's check that a reel gets a bar (582 `LX/90Q;->A0N`), which decides whether the
 * caption leaves room for one: the one method called from [holders]' methods loading [REEL_FOOTER]
 * that returns `Z` and takes the reel's length last, as an Integer. It answers no for a reel under
 * Facebook's minimum length, which then gets its caption laid out right down where the bar goes.
 * Returns its class, found with [classOf], and the method. Refuses unless there's exactly one, and
 * unless it still compares that length with an `if-lt`.
 */
internal fun footerBarCheck(holders: List<ClassDef>, classOf: (String) -> ClassDef?): Pair<ClassDef, Method> {
    val calls = holders.distinctBy { it.type }.flatMap { methodsHolding(it, REEL_FOOTER) }.flatMap { method ->
        method.implementation!!.instructions.mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.takeIf { instruction.opcode.name.startsWith("invoke") }?.reference as? MethodReference)
                ?.takeIf { it.returnType == "Z" && it.parameterTypes.lastOrNull()?.toString() == "Ljava/lang/Integer;" }
        }
    }.distinctBy { it.toString() }
    val call = calls.singleOrNull()
        ?: refuse("expected the methods loading \"$REEL_FOOTER\" to ask one method about a reel's length, found ${calls.size}")
    val owner = classOf(call.definingClass) ?: refuse("${call.definingClass}, the footer's bar check, isn't in this APK")
    val check = owner.methods.singleOrNull {
        it.name == call.name && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
    } ?: refuse("$call, the footer's bar check, isn't declared on ${owner.type}")
    lengthRead(check) ?: refuse("$call no longer compares the reel's length with an if-lt")
    return owner to check
}

/**
 * Where [method] has the reel's length: the index of the one `move-result` after an [INT_VALUE]
 * call whose register a later `if-lt` compares first, against the minimum.
 */
internal fun lengthRead(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val reads = code.indices.filter { index ->
        val result = code.getOrNull(index + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction
        code[index].opcode == Opcode.INVOKE_VIRTUAL && (code[index] as ReferenceInstruction).reference.toString() == INT_VALUE &&
            result != null && code.drop(index + 2).any {
                it.opcode == Opcode.IF_LT && (it as TwoRegisterInstruction).registerA == result.registerA
            }
    }
    return reads.singleOrNull()?.plus(1)
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
 * The interface the landscape player's controls updater implements (581 `LX/9Wi;`): the one
 * interface, among those of [holders] found with [classOf], declaring an instance `V` method of
 * theirs that traces [PLAYER_CONTROL_UPDATE].
 */
internal fun controlsUpdater(holders: List<ClassDef>, classOf: (String) -> ClassDef?): String {
    val found = holders.distinctBy { it.type }.flatMap { holder ->
        methodsHolding(holder, PLAYER_CONTROL_UPDATE)
            .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" }
            .flatMap { update ->
                holder.interfaces.mapNotNull(classOf).filter { face -> face.methods.any { it.sameAs(update) } }
            }
    }.map { it.type }.distinct()
    return found.singleOrNull()
        ?: refuse("expected one interface for the controls updater tracing \"$PLAYER_CONTROL_UPDATE\", found ${found.size}")
}

/** Whether a constructor of [classDef] takes the controls updater, as their controller's does. */
internal fun takesUpdater(classDef: ClassDef, updater: String) = classDef.methods.any { method ->
    method.name == "<init>" && method.parameterTypes.any { it.toString() == updater }
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
 * look on the same instance, hide the time label the way the passive look does, and return.
 * Otherwise Facebook's own code runs from its first instruction. The range form names p0, which
 * can sit past v15 in these long methods, and so does the move that reads it into v0 (format 22x).
 */
internal fun MutableMethod.activeInstead(looks: ScrubberLooks) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEPS_REEL_BAR
            move-result v0
            if-eqz v0, :facebook
            invoke-virtual/range { p0 .. p0 }, ${looks.type}->${looks.active.name}()V
            move-object/from16 v0, p0
            iget-object v0, v0, ${looks.viewsField}
            iget-object v0, v0, ${looks.labelField}
            invoke-static { v0 }, $HIDE_TIME_LABEL
            return-void
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
 * First thing in the scrubber's progress update: read the time label off the scrubber the update
 * takes, and when the extension asks for the time, have Facebook's own writer refresh it before the
 * update runs as always. A views holder that isn't there yet skips straight to Facebook's code.
 */
internal fun MutableMethod.refreshesTimeFirst(progress: ProgressUpdate, looks: ScrubberLooks) {
    requireLocals(PATCH, 1)
    val base = progress.base.type
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${looks.viewsField}
            if-eqz v0, :facebook
            iget-object v0, v0, ${looks.labelField}
            invoke-static { v0 }, $REFRESHES_TIME
            move-result v0
            if-eqz v0, :facebook
            invoke-static/range { p0 .. p0 }, $base->${progress.writeTime.name}($base)V
        """,
        ExternalLabel("facebook", getInstruction(0)),
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
 * Right after the footer's bar check reads the reel's length: hand it to the extension, which
 * answers a length past any minimum while it keeps the bar, so a short reel's caption leaves the
 * same room for the bar as a longer one's. The hook works in the length's own register, so no
 * other register is borrowed.
 */
internal fun MutableMethod.roomForEveryReel() {
    val at = lengthRead(this) ?: refuse("$definingClass->$name no longer compares the reel's length with an if-lt")
    val register = (getInstruction(at) as OneRegisterInstruction).registerA
    addInstructions(
        at + 1,
        """
            invoke-static/range { v$register .. v$register }, $REEL_LENGTH
            move-result v$register
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
