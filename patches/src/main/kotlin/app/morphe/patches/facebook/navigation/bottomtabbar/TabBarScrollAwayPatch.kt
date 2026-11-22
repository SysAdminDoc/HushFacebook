/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.ControlFlow
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/**
 * The name Facebook's main screen gives the bottom tab bar's entry in its scroll-away list, the
 * list of views that slide off screen as the feed scrolls down and back as it scrolls up.
 */
internal const val BOTTOM_TABS_CONTAINER = "bottom_tabs_container"

internal const val ACTIVITY = "Landroid/app/Activity;"

internal const val TAB_BAR_SCROLL_AWAY = "Lapp/morphe/extension/facebook/navigation/TabBarScrollAway;"
internal const val SLIDES_AWAY = "$TAB_BAR_SCROLL_AWAY->slidesAway()Z"
internal const val STAYS_PUT = "$TAB_BAR_SCROLL_AWAY->staysPut()Z"
internal const val TOP_BAR_OFFSET = "$TAB_BAR_SCROLL_AWAY->topBarOffset(Ljava/lang/String;I)I"
internal const val TOP_BAR_FADE = "$TAB_BAR_SCROLL_AWAY->topBarFade(Ljava/lang/String;F)F"
internal const val PADS_PAGER = "$TAB_BAR_SCROLL_AWAY->padsPager(Landroid/view/View;IIII)V"

/** The trace name Facebook gives the tab pager's setup, where it pads its pages for the bar. */
internal const val PAGER_INIT = "TabBarViewPager.init"

/** The trace name Facebook gives the method that adds the top bar to the scroll-away views. */
internal const val SET_TOP_VIEWS = "ScrollAwayNavigationController.setTopScrollAwayViews"

/**
 * The trace names of the main screen's two switches of scroll-away: it turns the bars off as it
 * leaves the surface they belong on (the Reels tab, a full-screen page), hiding them itself, and
 * back on as it returns. The extension hears both, so a kept top bar goes with Facebook's own hide
 * instead of sitting over a reel, and comes back with the feed.
 */
internal const val DEACTIVATE_SCROLL_AWAY = "ScrollAwayNavigationController.deactivateScrollaway"
internal const val REACTIVATE_SCROLL_AWAY = "ScrollAwayNavigationController.reactivateScrollaway"
internal const val TOP_BARS_LET_GO = "$TAB_BAR_SCROLL_AWAY->topBarsLetGo()V"
internal const val TOP_BARS_TAKEN_BACK = "$TAB_BAR_SCROLL_AWAY->topBarsTakenBack()V"

/**
 * The trace names inside Facebook's scroll-away controller: its scroll step, the part of that step
 * that brings the bars back as the feed scrolls up, and its snap of the bars back into sight.
 */
internal const val CONTROLLER_SCROLL = "BaseScrollAwayNavigationController.onScroll"
internal const val SHOW_BARS = "BaseScrollAwayNavigationController.showCurrentScrollAwayViews"
internal const val SNAP_SHOWN = "BaseScrollAwayNavigationController.snapToFullyShown"
internal const val SHOWN_AT_TOP = "$TAB_BAR_SCROLL_AWAY->shownAtTop(Ljava/lang/Object;I)I"

/** The name Facebook schedules its nudge of the feed after a cold start under. */
internal const val COMPOSER_NUDGE = "composerScrollNudge"
internal const val SKIPS_OPENING_NUDGE = "$TAB_BAR_SCROLL_AWAY->skipsOpeningNudge()Z"

private const val VIEW = "Landroid/view/View;"
private const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"
private const val RUNNABLE = "Ljava/lang/Runnable;"

private const val STRING = "Ljava/lang/String;"
private const val SET_PADDING = "Landroid/view/View;->setPadding(IIII)V"
private const val SET_TRANSLATION_Y = "Landroid/view/View;->setTranslationY(F)V"
private const val ANIMATE_TRANSLATION_Y =
    "Landroid/view/ViewPropertyAnimator;->translationY(F)Landroid/view/ViewPropertyAnimator;"

/** How far before the entry's name the start may ask the gate. 581 asks 11 instructions before. */
private const val GATE_WINDOW = 16

/**
 * Facebook already knows how to slide its bottom tab bar away while the feed scrolls down. As the
 * main screen starts, it builds the bar's scroll-away entry, named [BOTTOM_TABS_CONTAINER], only
 * when one check answers yes (581 `LX/1wt;->A0A`). The same check is asked by the feed's floating
 * button, the mini player and the screens that make room for the bar, so they all agree on whether
 * it slides. It answers yes when scroll-away runs at all on this phone, the bar is at the bottom
 * (the class's own check of fb4a_bottom_tabs_override_enabled, the read Tab bar at the bottom
 * answers), and two server settings turn the experiment on for the account.
 *
 * The extension goes in right after the bar's position check passes. A kept bar answers no there
 * outright, so it stays put even for the accounts the server settings give the experiment to.
 * While the scroll-away switch is on it answers yes in place of the two server settings; otherwise
 * Facebook carries on to them. With the bar at the top, or scroll-away off for the phone, the
 * extension isn't reached at all. Facebook's own scroll-away code then moves the bar, snaps it back
 * at the top and keeps the feed's bottom inset, the way it does for the accounts it gives the
 * experiment to.
 *
 * A bar that stays put has the tab pager pad its pages by the bar's height, in the method traced as
 * [PAGER_INIT]. On a phone drawing under its navigation bar, the bar's holder covers that too, so
 * the bottom of each page sat behind the bar: a reel's progress bar, 63 px of it on the Android 16
 * emulator (2026-10-12). That padding call goes through the extension, which adds the overlap for
 * a kept bar.
 *
 * The top bar is an entry of the same scroll-away list, made in the method traced as
 * [SET_TOP_VIEWS] (582 `LX/25q;->A0x`) whatever the server settings say. Each method of the entry
 * class that moves its view starts by adding the entry's resting place to the distance it's asked
 * to move (582 `LX/2Ir;->A01` and `A03`). The extension goes in front of that with the entry's name
 * and the distance, and a kept top bar gets 0, so it stays where it rests and keeps its inset. Both
 * moves also take a float, how far out of sight the move takes the entry, which Facebook fades the
 * view by (582 `LX/2Ir;->A02`, from `A01` and from `A03`'s animation): over a see-through
 * navigation bar, and for one part of the top bar under a server setting. That float goes through
 * the extension too, and a kept top bar gets 0, so it doesn't fade out in place.
 */
internal val tabBarScrollAwayPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        val holders = classDefByStrings(BOTTOM_TABS_CONTAINER, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val gate = scrollAwayGate(holders)
        val (pagerInit, padding) = keptBarPadding(
            classDefByStrings(PAGER_INIT, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
            gate,
        )
        mutableClassDefBy(pagerInit.definingClass).findMutableMethodOf(pagerInit).padsPagerForKeptBar(padding)
        val gateClass = mutableClassDefBy(gate.definingClass)
        val method = gateClass.findMutableMethodOf(gate)
        method.slideAwayAfterBarCheck(barAtBottomCheck(method, gateClass))

        val topViews = topScrollAwayViews(
            classDefByStrings(SET_TOP_VIEWS, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val name = scrollAwayEntryName(topViews)
        val entry = mutableClassDefBy(name.definingClass)
        for (move in entryMoves(entry)) {
            entry.findMutableMethodOf(move.method).holdsTopBar(name, move.distance, move.hidden)
        }
        try {
            hookReelUnderTopBar { why ->
                patchLog.warning("$PATCH: $why, so the reel keeps its black strip under a top tab bar")
            }
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so the reel keeps its black strip under a top tab bar")
        }
        hookReelsHeader { why ->
            patchLog.warning("$PATCH: $why, so the Reels header can sit under a top tab bar")
        }
        hookReelRow { why ->
            patchLog.warning("$PATCH: $why, so a reel under a top tab bar ends short of the screen's bottom")
        }
        for ((trace, call) in listOf(DEACTIVATE_SCROLL_AWAY to TOP_BARS_LET_GO, REACTIVATE_SCROLL_AWAY to TOP_BARS_TAKEN_BACK)) {
            val switch = scrollAwaySwitch(
                classDefByStrings(trace, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
                trace,
            )
            mutableClassDefBy(switch.definingClass).findMutableMethodOf(switch).tellsExtensionFirst(call)
        }
        try {
            val controllers = classDefByStrings(CONTROLLER_SCROLL, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            val step = barsShowStep(controllers)
            val (manager, content) = scrollContent(controllers.single { it.type == step.definingClass })
            mutableClassDefBy(step.definingClass).findMutableMethodOf(step).showsBarsAtTop(manager, content)
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so the tab bar can stop short of the bottom as the feed comes back to its top")
        }
        try {
            val (nudge, at) = openingNudge(
                classDefByStrings(COMPOSER_NUDGE, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
            )
            mutableClassDefBy(nudge.definingClass).findMutableMethodOf(nudge).skipsNudge(at)
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so a cold start can still open Home scrolled with the tab bar set to slide away")
        }
    }
}

/**
 * The step of Facebook's scroll-away controller that brings the bars back by a distance as the feed
 * scrolls up (588 `LX/03Fg;->A0G`): the one method of its own class taking (int, boolean, List) that
 * the method of [controllers] traced as [CONTROLLER_SCROLL] calls after it loads [SHOW_BARS]. The
 * hide step before it has the same shape, so the trace name tells them apart. Refuses unless one.
 */
internal fun barsShowStep(controllers: List<ClassDef>): Method {
    val steps = controllers.flatMap { controller ->
        controller.methods.filter { method -> method.implementation?.instructions?.any { loadsString(it, CONTROLLER_SCROLL) } == true }
            .flatMap { scroll ->
                val code = scroll.implementation!!.instructions.toList()
                val from = code.indexOfFirst { loadsString(it, SHOW_BARS) }
                if (from < 0) emptyList() else code.drop(from).mapNotNull(::calledMethod).filter { called ->
                    called.definingClass == controller.type && called.returnType == "V" &&
                        called.parameterTypes.map(CharSequence::toString) == listOf("I", "Z", "Ljava/util/List;")
                }.mapNotNull { called ->
                    controller.methods.firstOrNull { it.name == called.name && MethodUtil.methodSignaturesMatch(it, called) }
                }
            }
    }.distinctBy { "${it.definingClass}->${it.name}" }
    return steps.singleOrNull()
        ?: refuse("expected one step that brings the bars back after \"$SHOW_BARS\", found ${steps.size}")
}

/**
 * Where [controller] keeps the view the bars scroll with (588 `LX/03Fg;->A07`, then that object's
 * `A01`): in its method traced as [SNAP_SHOWN], a field read off this, then a View field read off
 * what it gave and tested as a RecyclerView, which the snap scrolls. Refuses unless exactly one pair.
 */
internal fun scrollContent(controller: ClassDef): Pair<FieldReference, FieldReference> {
    val pairs = controller.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any { loadsString(it, SNAP_SHOWN) } == true
    }.flatMap { snap ->
        val code = snap.implementation!!.instructions.toList()
        val self = snap.implementation!!.registerCount - snap.parameterTypes.sumOf { parameterSize(it.toString()) } - 1
        code.indices.mapNotNull { at ->
            val holder = code[at].takeIf { it.opcode == Opcode.IGET_OBJECT } as? TwoRegisterInstruction ?: return@mapNotNull null
            if (holder.registerB != self) return@mapNotNull null
            val view = (at + 1..minOf(at + 4, code.lastIndex)).firstOrNull { next ->
                val read = code[next].takeIf { it.opcode == Opcode.IGET_OBJECT } as? TwoRegisterInstruction
                read != null && read.registerB == holder.registerA &&
                    ((read as ReferenceInstruction).reference as FieldReference).type == VIEW
            } ?: return@mapNotNull null
            val viewRegister = (code[view] as TwoRegisterInstruction).registerA
            val tested = code.getOrNull(view + 1)?.takeIf { it.opcode == Opcode.INSTANCE_OF } as? TwoRegisterInstruction
            if (tested == null || tested.registerB != viewRegister ||
                ((tested as ReferenceInstruction).reference as TypeReference).type != RECYCLER_VIEW
            ) return@mapNotNull null
            ((code[at] as ReferenceInstruction).reference as FieldReference) to
                ((code[view] as ReferenceInstruction).reference as FieldReference)
        }
    }.distinctBy { (manager, content) -> "$manager $content" }
    return pairs.singleOrNull()
        ?: refuse("expected one view the bars scroll with in ${controller.type}'s \"$SNAP_SHOWN\", found ${pairs.size}")
}

/**
 * Puts the extension at the start of [this], the step that brings the bars back: the view they
 * scroll with ([content] read off [manager], read off this, null when there's no manager) and the
 * distance go in, and the distance comes back, far enough to bring every bar all the way back once
 * the view can't scroll up any more. Two locals carry them, since this and the distance may sit
 * past v15. With no manager the view's local still holds the manager, so ART types it as Object
 * where the two paths meet and the extension takes an Object: a View there failed verification.
 */
internal fun MutableMethod.showsBarsAtTop(manager: FieldReference, content: FieldReference) {
    val self = implementation!!.registerCount - parameterTypes.sumOf { parameterSize(it.toString()) } - 1
    val distance = parameterRegisters(this).single { (type) -> type == "I" }.second
    if (self > 255 || distance > 255) refuse("$definingClass->$name keeps this or the distance past v255")
    // A jump back to the start would ask twice, harmless, but the method has never had one.
    val first = implementation!!.instructions[0] as BuilderInstruction
    if (first.location.labels.isNotEmpty()) refuse("$definingClass->$name has a jump back to its start")
    val (view, moved) = freeLocalsAt(PATCH, 0, 2, highest = 15)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v$view, v$self
            iget-object v$view, v$view, $manager
            if-eqz v$view, :ask
            iget-object v$view, v$view, $content
            :ask
            move/from16 v$moved, v$distance
            invoke-static { v$view, v$moved }, $SHOWN_AT_TOP
            move-result v$moved
            move/from16 v$distance, v$moved
        """,
    )
}

/**
 * Facebook's nudge of the feed a few seconds after a cold start (588 `LX/03o7;->run`): the `run()V`
 * of [holders] that loads [COMPOSER_NUDGE] to schedule itself again, and the index right after it
 * clears its own pending task, its first write of a null Runnable field. Skipping from there leaves
 * that field clear, as a finished nudge does. Refuses unless exactly one such run.
 */
internal fun openingNudge(holders: List<ClassDef>): Pair<Method, Int> {
    val runs = holders.flatMap { holder ->
        holder.methods.filter { method ->
            method.name == "run" && method.returnType == "V" && method.parameterTypes.isEmpty() &&
                method.implementation?.instructions?.any { loadsString(it, COMPOSER_NUDGE) } == true
        }
    }
    val run = runs.singleOrNull() ?: refuse("expected one run of the nudge scheduled as \"$COMPOSER_NUDGE\", found ${runs.size}")
    val code = run.implementation!!.instructions.toList()
    val cleared = code.indexOfFirst {
        it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == RUNNABLE
    }
    val nulled = code.getOrNull(cleared - 1)?.takeIf { it.opcode == Opcode.CONST_4 } as? OneRegisterInstruction
    if (cleared < 0 || nulled == null || nulled.registerA != (code[cleared] as TwoRegisterInstruction).registerA ||
        (code[cleared - 1] as NarrowLiteralInstruction).narrowLiteral != 0
    ) refuse("${run.definingClass}->run doesn't start by clearing its pending task")
    return run to cleared + 1
}

/**
 * Puts the extension at [at] in the nudge's run: a yes returns before the feed is scrolled, and a
 * no goes on to Facebook's next instruction. Nothing may jump to [at], since that would skip the ask.
 */
internal fun MutableMethod.skipsNudge(at: Int) {
    val next = implementation!!.instructions[at] as BuilderInstruction
    if (next.location.labels.isNotEmpty()) refuse("$definingClass->$name has a jump past the clearing of its pending task")
    val register = freeLocalsAt(PATCH, at, 1, highest = 255).single()
    addInstructionsWithLabels(
        at,
        """
            invoke-static { }, $SKIPS_OPENING_NUDGE
            move-result v$register
            if-eqz v$register, :nudge
            return-void
        """,
        ExternalLabel("nudge", getInstruction(at)),
    )
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun MethodReference.takesOnlyActivity() =
    returnType == "Z" && parameterTypes.size == 1 && parameterTypes[0].toString() == ACTIVITY

private fun calledMethod(instruction: Instruction) =
    instruction.takeIf { it.opcode.name.startsWith("invoke") }
        ?.let { (it as ReferenceInstruction).reference as? MethodReference }

private fun loadsContainerName(instruction: Instruction) =
    instruction.opcode.name.startsWith("const-string") &&
        ((instruction as ReferenceInstruction).reference as StringReference).string == BOTTOM_TABS_CONTAINER

/**
 * Whether [code] at [call] keeps its answer with a move-result and branches away on false right
 * after, and returns that register, or null.
 */
private fun testedResult(code: List<Instruction>, call: Int): Int? {
    val result = code.getOrNull(call + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } ?: return null
    val register = (result as OneRegisterInstruction).registerA
    val branch = code.getOrNull(call + 2)?.takeIf { it.opcode == Opcode.IF_EQZ } ?: return null
    return register.takeIf { (branch as OneRegisterInstruction).registerA == it }
}

/**
 * The check the start asks before it builds the bottom tab bar's scroll-away entry: in each method
 * of [holders] that loads [BOTTOM_TABS_CONTAINER], the nearest call before the load that takes an
 * Activity and answers a boolean, whose false jumps past the load. Refuses unless exactly one check
 * is found, since another load deciding the bar on its own would go unseen.
 */
internal fun scrollAwayGate(holders: List<ClassDef>): MethodReference {
    val gates = holders.flatMap { holder ->
        holder.methods.flatMap { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.indices.filter { loadsContainerName(code[it]) }.mapNotNull { load ->
                val call = (load - 1 downTo maxOf(0, load - GATE_WINDOW))
                    .firstOrNull { calledMethod(code[it])?.takesOnlyActivity() == true } ?: return@mapNotNull null
                testedResult(code, call) ?: return@mapNotNull null
                // The false way has to skip the entry, or the check wouldn't decide it.
                val flow = ControlFlow.of(method)
                val skips = flow.normal[call + 2].any { it > load } || flow.falseWayAvoids(call, load)
                calledMethod(code[call])!!.takeIf { skips }
            }
        }
    }.distinctBy { "${it.definingClass}->${it.name}" }
    return gates.singleOrNull()
        ?: refuse("expected one check before the start builds \"$BOTTOM_TABS_CONTAINER\", found ${gates.size}")
}

/**
 * Whether the false way out of the test after [call], the branch at `call + 2`, can't come to
 * [load] without asking [call] again. 588's Android 9+ build (#102, read 2026-11-19) runs the
 * start's steps in one loop (`LX/03Dz;->A05`), and a false gate jumps back to the loop's next step
 * (from 2600 to 45), which comes back to the load only through the gate.
 */
private fun ControlFlow.falseWayAvoids(call: Int, load: Int): Boolean {
    val falseWay = normal[call + 2].filter { it != call + 3 }
    if (falseWay.isEmpty()) return false
    val seen = BooleanArray(instructions.size)
    val queue = ArrayDeque(falseWay)
    while (queue.isNotEmpty()) {
        val at = queue.removeFirst()
        if (at == call || seen[at]) continue
        if (at == load) return false
        seen[at] = true
        queue.addAll(normal[at])
        queue.addAll(exceptional[at])
    }
    return true
}

/**
 * Where the extension goes in [gate]: right after the one call to [gateClass]'s own
 * `(Activity)Z` method that reads a TriState from FbSharedPreferences, the bar's position check,
 * and the branch that leaves when it answers no. Refuses a gate without exactly one such check.
 * 586's check reads it through one more method of the class ([asksTriState]).
 */
internal fun barAtBottomCheck(gate: Method, gateClass: ClassDef): Int {
    val code = gate.implementation?.instructions?.toList() ?: refuse("${gate.definingClass}->${gate.name} has no body")
    val checks = code.indices.filter { index ->
        val called = calledMethod(code[index]) ?: return@filter false
        called.definingClass == gate.definingClass && called.takesOnlyActivity() &&
            gateClass.methods.any { it.name == called.name && it.takesOnlyActivity() && it.asksTriState(gateClass) }
    }
    val call = checks.singleOrNull()
        ?: refuse("${gate.definingClass}->${gate.name} asks where the tab bar goes ${checks.size} times, expected once")
    testedResult(code, call)
        ?: refuse("${gate.definingClass}->${gate.name} doesn't leave right after the tab bar's position check answers no")
    return call + 3
}

private fun Method.readsTriState() = implementation?.instructions?.any { instruction ->
    val called = calledMethod(instruction)
    called != null && called.definingClass == FB_SHARED_PREFERENCES && called.returnType == TRI_STATE
} == true

/**
 * Whether [this] reads the TriState itself or through a method of [owner] it calls: 586's position
 * check (`LX/02Cc;->A09` on arm64) asks the big screen check and then `A05()Z`, which reads it.
 */
internal fun Method.asksTriState(owner: ClassDef): Boolean = readsTriState() || implementation?.instructions?.any { instruction ->
    val called = calledMethod(instruction) ?: return@any false
    called.definingClass == owner.type && owner.methods.any {
        it.name == called.name && MethodUtil.methodSignaturesMatch(it, called) && it.readsTriState()
    }
} == true

/**
 * Puts the extension at [at], the bar at the bottom: a kept bar returns no, then a bar set to
 * slide away returns yes, and anything else goes on to Facebook's own server settings. The
 * register is one nothing reads from there. Nothing may jump to [at], since code arriving there
 * would skip the calls.
 */
internal fun MutableMethod.slideAwayAfterBarCheck(at: Int) {
    val next = implementation!!.instructions[at] as BuilderInstruction
    if (next.location.labels.isNotEmpty()) refuse("$definingClass->$name has a jump past its tab bar position check")
    val register = freeLocalsAt(PATCH, at, 1, highest = 255).single()
    addInstructionsWithLabels(
        at,
        """
            invoke-static { }, $STAYS_PUT
            move-result v$register
            if-eqz v$register, :ask
            const/16 v$register, 0x0
            return v$register
            :ask
            invoke-static { }, $SLIDES_AWAY
            move-result v$register
            if-eqz v$register, :facebook
            return v$register
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

private fun loadsString(instruction: Instruction, value: String) =
    instruction.opcode.name.startsWith("const-string") &&
        ((instruction as ReferenceInstruction).reference as StringReference).string == value

/**
 * The method of [holders] traced as [SET_TOP_VIEWS], the one that adds the top bar to the
 * scroll-away views. Refuses unless exactly one loads the name.
 */
internal fun topScrollAwayViews(holders: List<ClassDef>): Method {
    val methods = holders.flatMap { holder ->
        holder.methods.filter { method -> method.implementation?.instructions?.any { loadsString(it, SET_TOP_VIEWS) } == true }
    }
    return methods.singleOrNull() ?: refuse("expected one method traced as \"$SET_TOP_VIEWS\", found ${methods.size}")
}

/**
 * The method of [holders] traced as [trace], one of the main screen's two switches of scroll-away
 * ([DEACTIVATE_SCROLL_AWAY], [REACTIVATE_SCROLL_AWAY]). Refuses unless exactly one loads the name.
 */
internal fun scrollAwaySwitch(holders: List<ClassDef>, trace: String): Method {
    val methods = holders.flatMap { holder ->
        holder.methods.filter { method -> method.implementation?.instructions?.any { loadsString(it, trace) } == true }
    }
    return methods.singleOrNull() ?: refuse("expected one method traced as \"$trace\", found ${methods.size}")
}

/**
 * Puts [call], an extension call that takes nothing and answers nothing, at the start of this
 * method, before Facebook's trace opens. A jump back to the start lands on Facebook's first
 * instruction and leaves the call behind, which is fine: it has nothing to undo.
 */
internal fun MutableMethod.tellsExtensionFirst(call: String) {
    addInstructions(0, "invoke-static { }, $call")
}

/**
 * The scroll-away entry's name, read in [topViews]: the String field it reads off a class it makes,
 * the entry whose name it hands Facebook's inset tracker. Refuses unless exactly one such field.
 */
internal fun scrollAwayEntryName(topViews: Method): FieldReference {
    val where = "${topViews.definingClass}->${topViews.name}"
    val code = topViews.implementation?.instructions?.toList() ?: refuse("$where has no body")
    val made = code.filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .toSet()
    val names = code.filter { it.opcode == Opcode.IGET_OBJECT }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .filter { it.type == STRING && it.definingClass in made }
        .distinctBy { "${it.definingClass}->${it.name}" }
    return names.singleOrNull() ?: refuse("$where reads ${names.size} names off the entries it makes, expected one")
}

/**
 * A move of the scroll-away entry: the register of the [distance] it's asked to move its view and,
 * when it takes one float, the register of how far out of sight that takes the view, [hidden], which
 * Facebook fades some entries by.
 */
internal data class EntryMove(val method: Method, val distance: Int, val hidden: Int?)

/**
 * The methods of the scroll-away [entry] that move its view. Every method that moves a view with
 * setTranslationY or a ViewPropertyAnimator's translationY has to start by adding an int field of
 * the entry to its one int parameter, or the patch refuses, since the hook would miss that move. A
 * move taking more than one float is refused too, since which one fades the view isn't known.
 * Refuses when none moves.
 */
internal fun entryMoves(entry: ClassDef): List<EntryMove> {
    val moves = entry.methods.filter { method ->
        method.implementation?.instructions?.any {
            calledMethod(it)?.toString().let { called -> called == SET_TRANSLATION_Y || called == ANIMATE_TRANSLATION_Y }
        } == true
    }
    if (moves.isEmpty()) refuse("${entry.type} has no method that moves its view")
    return moves.map { method ->
        val distance = movedDistance(method)
            ?: refuse("${entry.type}->${method.name} moves its view without first adding a field to the int it's given")
        val floats = parameterRegisters(method).filter { (type) -> type == "F" }
        if (floats.size > 1) refuse("${entry.type}->${method.name} takes ${floats.size} floats, expected at most one")
        EntryMove(method, distance, floats.singleOrNull()?.second)
    }
}

private fun parameterSize(type: String) = if (type == "J" || type == "D") 2 else 1

/** Each parameter's type and register of the instance method [method], this left out. */
private fun parameterRegisters(method: Method): List<Pair<String, Int>> {
    val types = method.parameterTypes.map(CharSequence::toString)
    var next = method.implementation!!.registerCount - types.sumOf { parameterSize(it) }
    return types.map { type -> (type to next).also { next += parameterSize(type) } }
}

/**
 * The register of [method]'s one int parameter, when the method is an instance method whose first
 * two instructions read an int field of its own class off this and add it to that parameter.
 */
private fun movedDistance(method: Method): Int? {
    if (AccessFlags.STATIC.isSet(method.accessFlags)) return null
    val types = method.parameterTypes.map(CharSequence::toString)
    if (types.count { it == "I" } != 1) return null
    val implementation = method.implementation ?: return null
    val self = implementation.registerCount - types.sumOf { parameterSize(it) } - 1
    val distance = parameterRegisters(method).single { (type) -> type == "I" }.second
    val code = implementation.instructions.toList()
    val read = code.getOrNull(0)?.takeIf { it.opcode == Opcode.IGET } as? TwoRegisterInstruction ?: return null
    val field = (read as ReferenceInstruction).reference as FieldReference
    if (field.definingClass != method.definingClass || field.type != "I" || read.registerB != self) return null
    val add = code.getOrNull(1)?.takeIf { it.opcode == Opcode.ADD_INT_2ADDR } as? TwoRegisterInstruction ?: return null
    return distance.takeIf { add.registerA == it && add.registerB == read.registerA }
}

/**
 * Puts the extension at the start of this move of a scroll-away entry: the entry's [entryName] and
 * the [distance] go in, and the distance comes back, 0 for a kept top bar. Then, for a move that
 * takes one, the same with [hidden], how far out of sight the move takes the view. The name goes in
 * a local nothing reads before writing it.
 */
internal fun MutableMethod.holdsTopBar(entryName: FieldReference, distance: Int, hidden: Int?) {
    val self = implementation!!.registerCount - parameterTypes.sumOf { parameterSize(it.toString()) } - 1
    if (self > 15 || distance > 15 || (hidden ?: 0) > 15) refuse("$definingClass->$name keeps this or what it's given past v15")
    // A jump back to the start would land past the hold and move the bar a second time.
    val first = implementation!!.instructions[0] as BuilderInstruction
    if (first.location.labels.isNotEmpty()) refuse("$definingClass->$name has a jump back to its start")
    val local = freeLocalsAt(PATCH, 0, 1, highest = 15).single()
    val unfaded = hidden?.let {
        """
            invoke-static { v$local, v$it }, $TOP_BAR_FADE
            move-result v$it
        """
    }.orEmpty()
    addInstructions(
        0,
        """
            iget-object v$local, v$self, $entryName
            invoke-static { v$local, v$distance }, $TOP_BAR_OFFSET
            move-result v$distance
        """ + unfaded,
    )
}

/**
 * The method of [holders] traced as [PAGER_INIT], where the tab pager pads its pages for a bar that
 * stays put (582 `LX/25E;->A01`): the one `setPadding` whose bottom is the answer of a method of
 * [gate]'s own class that takes an Activity and answers an int, the bar's height, read right before
 * it. The method has to ask [gate] too, since its no is what leads there. Refuses unless exactly one
 * such call, as index and method.
 */
internal fun keptBarPadding(holders: List<ClassDef>, gate: MethodReference): Pair<Method, Int> {
    val sites = holders.flatMap { holder ->
        holder.methods.filter { method -> method.implementation?.instructions?.any { loadsString(it, PAGER_INIT) } == true }
            .flatMap { method ->
                val code = method.implementation!!.instructions.toList()
                val asksGate = code.any { instruction ->
                    calledMethod(instruction)?.let { it.definingClass == gate.definingClass && it.name == gate.name && it.takesOnlyActivity() } == true
                }
                code.indices.filter { at ->
                    asksGate && calledMethod(code[at])?.toString() == SET_PADDING && code[at].opcode == Opcode.INVOKE_VIRTUAL &&
                        code.getOrNull(at - 1)?.opcode == Opcode.MOVE_RESULT &&
                        (code[at - 1] as OneRegisterInstruction).registerA == (code[at] as FiveRegisterInstruction).registerG &&
                        calledMethod(code[at - 2])?.let { height ->
                            height.definingClass == gate.definingClass && height.returnType == "I" &&
                                height.parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY)
                        } == true
                }.map { method to it }
            }
    }
    return sites.singleOrNull()
        ?: refuse("expected one setPadding by the tab bar's height in the method traced as \"$PAGER_INIT\", found ${sites.size}")
}

/**
 * The pager's padding call at [at] goes through the extension with the same registers, so a kept
 * bar's pages also clear the navigation bar under it. Facebook's padding is set first either way.
 */
internal fun MutableMethod.padsPagerForKeptBar(at: Int) {
    val call = getInstruction<FiveRegisterInstruction>(at)
    replaceInstruction(
        at,
        "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE}, v${call.registerF}, v${call.registerG} }, $PADS_PAGER",
    )
}
