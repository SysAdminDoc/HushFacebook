/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.reelspeed

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.media.resume.TRACK_START
import app.morphe.patches.facebook.media.resume.VIDEO_PLAYER_PARAMS
import app.morphe.patches.facebook.media.resume.paramsGetters
import app.morphe.patches.facebook.media.resume.reportedValues
import app.morphe.patches.facebook.media.resume.trackers
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.reels.hold.reelLiftGuardPatch
import app.morphe.patches.facebook.reels.hold.SPEED_SET as GUARD_SPEED_SET
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PATCH = "Keep the reel speed"

/**
 * The speed picked in a reel's menu stays for the next reels. See ReelSpeedAnchors.kt for where
 * Facebook sets, announces and forgets a reel's speed, and the extension's ReelSpeed for the rule.
 *
 * FbGrootPlayer's speed setter and its maybeTrackVideoStart tell the extension about themselves
 * first thing, and so does the Reels menu's speed toast, which follows a pick. The gear menu's speed
 * sheet shows no toast, so its pick tells the extension straight after it sets the speed. The
 * extension's stubs are filled with the player's speed setter, its PlayerOrigin getter, its
 * VideoPlayerParams getter and the params' isFbShorts, isSponsored and isLiveNow.
 *
 * Keep the video speed, the extension's second switch for this patch, needs nothing more: the same
 * hooks fire for every FbGrootPlayer, so a gear pick on a feed or Watch video and each later video's
 * start already reach the extension, which tells reels from other videos by isFbShorts.
 *
 * Slower speeds, the third switch, hands the extension the list of speeds each of the Reels menu's
 * pickers offers ([addSlowerSpeeds]), and a pick of one of the added speeds goes through the same
 * toast and setter, so Keep the reel speed keeps it like any other. The gear menu's speed sheet
 * gets them too ([addSlowerGearSpeeds]), and its pick goes through the setter the gear hook follows.
 * Facebook's player keeps every speed at 0.25x or faster, so the patch lowers that floor to 0.1x
 * ([lowerSpeedFloor]). Facebook itself never asks for less than 0.5x.
 *
 * The gear menu's speed sheet checks Normal when it can't find its player, and its checked row
 * can't be picked, so the sheet asks the extension which speed to check there ([checkGearSheetSpeed]),
 * and Normal can be picked to forget a kept speed (#199).
 *
 * Speed on finished live videos, the fourth switch, is the gear menu of a full-screen video that
 * isn't live asking the extension before it leaves Playback speed out ([offerReplaySpeed], see
 * ReplaySpeedSite.kt), since Facebook's live player opens a live video that has ended with no
 * speed row (#238). Videos that are still live and ads keep Facebook's menu.
 */
@Suppress("unused")
val keepReelSpeedPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Keep the reel speed",
    description = "A speed you pick in a reel's menu stays for the next reels, even after Facebook restarts, " +
        "so you don't set it every time. Pick normal speed to go back. On by default. A second switch for feed and Watch videos starts off. " +
        "Live videos that have ended get Playback speed in their gear menu too, also on by default. All of it is " +
        "in Hushfacebook settings > Reels and Watch.",
    default = true,
) {
    category("Playback")
    // The guard keeps a tap from undoing a picked speed on accounts Facebook gives its own hold.
    dependsOn(settingsPatch, reelLiftGuardPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val anchors = findReelSpeedAnchors()
        applyReelSpeedAnchors(anchors)
        // The slower speeds are the extension's third switch; a build where the menus moved keeps the rest.
        try {
            addSlowerSpeeds()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the slower speeds in that picker.")
        }
        try {
            addSlowerGearSpeeds(anchors.gearPick)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the slower speeds in the gear menu.")
        }
        // After the slower speeds, which place their hooks by the sheet's own indexes.
        try {
            checkGearSheetSpeed(anchors)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on, and a gear menu that finds no player checks Normal.")
        }
        try {
            lowerSpeedFloor()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on, and 0.1x plays at Facebook's slowest, 0.25x.")
        }
        try {
            offerReplaySpeed(anchors.flags.getValue("sponsored"))
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on, and a finished live video's gear menu keeps Facebook's rows.")
        }
        enableStatus("keepReelSpeed")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** What [findReelSpeedAnchors] found, for [applyReelSpeedAnchors] to change. [flags] maps each flag stub to its field. */
internal class ReelSpeedAnchors(
    val owner: ClassDef,
    val setter: Method,
    val origin: Method,
    val start: Method,
    val toast: Method,
    /** The Runnable field holding the picked speed when [toast] is a run(); null when the speed is its second parameter. */
    val toastSpeed: FieldReference?,
    val gearPick: Method,
    val params: Method,
    val flags: Map<String, FieldReference>,
)

/**
 * FbGrootPlayer's setter, origin getter, start and params getter, the params' fields the rule
 * reads, the Reels menu's speed toast and the gear menu's speed pick. Changes nothing.
 */
internal fun BytecodePatchContext.findReelSpeedAnchors(): ReelSpeedAnchors {
    val plays = classDefByStrings(GROOT_PLAY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap(::grootPlays)
    val play = plays.singleOrNull() ?: refuse("expected one player play holding \"$GROOT_PLAY\", found ${plays.size}")
    val owner = classDefBy(play.definingClass)
    val trigger = play.parameterTypes.single().toString()

    fun single(what: String, found: List<Method>): Method = found.singleOrNull()
        ?: refuse("expected one $what in ${owner.type}, found ${found.size}")

    val setter = single("speed setter reading HeroPlayerSetting's speed cache switch", speedSetters(owner))
    val origin = single("PlayerOrigin getter", originGetters(owner))
    val start = single("$TRACK_START($trigger)", trackers(owner, TRACK_START, trigger))
    val params = single("getter of its $VIDEO_PLAYER_PARAMS", paramsGetters(owner))

    // Up to 584 a static (Context, float) method; 585 made it a Runnable's run() with the speed in a field.
    val toasts = classDefByStrings(SPEED_TOAST, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { holder -> holder.methods.filter { isSpeedToast(it) || isSpeedToastRun(holder, it) }.map { holder to it } }
    val (toastHolder, toast) = toasts.singleOrNull()
        ?: refuse("expected one speed toast (a static (Context, float) method or a Runnable's run) holding \"$SPEED_TOAST\", found ${toasts.size}")
    val toastSpeed = if (isSpeedToast(toast)) null else speedToastField(toastHolder, toast)

    val gearPicks = classDefByStrings(GEAR_PICK, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { holder ->
            holder.methods.filter { holdsString(it, GEAR_PICK) && setterCalls(it, owner.type, setter).isNotEmpty() }
        }
    val gearPick = gearPicks.singleOrNull()
        ?: refuse("expected one gear menu speed pick holding \"$GEAR_PICK\" and setting the speed with ${setter.name}, " +
            "found ${gearPicks.size}")

    val paramsClass = classDefByOrNull(VIDEO_PLAYER_PARAMS) ?: refuse("this build has no $VIDEO_PLAYER_PARAMS")
    val dumps = paramsClass.methods.filter { method -> REEL_PARAM_STUBS.keys.all { holdsString(method, it) } }
    val dump = dumps.singleOrNull()
        ?: refuse("expected one method of $VIDEO_PLAYER_PARAMS reporting ${REEL_PARAM_STUBS.keys.joinToString()}, found ${dumps.size}")
    val reported = reportedValues(dump)
    val flags = REEL_PARAM_STUBS.entries.associate { (name, stub) ->
        val field = reported[name] ?: refuse("$VIDEO_PLAYER_PARAMS->${dump.name} doesn't report $name")
        val declared = paramsClass.fields.singleOrNull {
            it.name == field.name && it.type == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("$name is ${field.name}:${field.type}, not a boolean field of $VIDEO_PLAYER_PARAMS")
        if (!AccessFlags.PUBLIC.isSet(declared.accessFlags)) refuse("$name, ${field.name}, isn't public, so the extension can't read it")
        stub to field
    }

    // The extension's stubs call these from outside Facebook's package.
    listOf(owner, paramsClass).forEach { reachable ->
        if (!AccessFlags.PUBLIC.isSet(reachable.accessFlags)) refuse("${reachable.type} isn't public, so the extension can't reach it")
    }
    listOf(setter, origin, params).forEach { method ->
        if (!AccessFlags.PUBLIC.isSet(method.accessFlags)) {
            refuse("${owner.type}->${method.name} isn't public, so the extension can't call it")
        }
    }
    return ReelSpeedAnchors(owner, setter, origin, start, toast, toastSpeed, gearPick, params, flags)
}

/**
 * Each hook goes first in its method and hands the extension the method's own arguments through
 * the range form, which names any register and borrows none: the player and the speed for the
 * setter, the player for the start, and the speed, the toast's second argument. In the setter the
 * release guard's hook, which this patch brings and so runs first, stays ahead of it, so the speed
 * logged is the one the player gets. The gear pick tells the extension after each of its setter
 * calls, with the speed that call handed over; the branches that land after a call come from paths
 * that set nothing, so they skip it.
 */
internal fun BytecodePatchContext.applyReelSpeedAnchors(anchors: ReelSpeedAnchors) {
    val owner = mutableClassDefBy(anchors.owner.type)
    val setter = owner.findMutableMethodOf(anchors.setter)
    setter.addInstruction(afterGuard(setter), "invoke-static/range { p0 .. p1 }, $SPEED_SET")
    owner.findMutableMethodOf(anchors.start).addInstruction(0, "invoke-static/range { p0 .. p0 }, $STARTED")
    val toast = mutableClassDefBy(anchors.toast.definingClass).findMutableMethodOf(anchors.toast)
    val toastSpeed = anchors.toastSpeed
    if (toastSpeed == null) {
        toast.addInstruction(0, "invoke-static/range { p1 .. p1 }, $PICKED")
    } else {
        // The Runnable's run() reads its fields into low locals first thing, so v0 is free to borrow before that.
        toast.requireLocals(PATCH, 1)
        if (toast.localRegisterCount() >= 16) refuse("${toast.definingClass}->${toast.name} keeps `this` past v15, out of an iget's reach")
        toast.addInstructions(
            0,
            """
                iget v0, p0, $toastSpeed
                invoke-static/range { v0 .. v0 }, $PICKED
            """,
        )
    }
    val gear = mutableClassDefBy(anchors.gearPick.definingClass).findMutableMethodOf(anchors.gearPick)
    setterCalls(gear, anchors.owner.type, anchors.setter).asReversed().forEach { (call, speed) ->
        gear.addInstruction(call + 1, "invoke-static/range { v$speed .. v$speed }, $GEAR_PICKED")
    }
    fillStubs(anchors)
}

/**
 * The Reels menu's two speed pickers hand their list of speeds to the extension as soon as
 * Arrays.asList makes it, and build their items from the list it answers (#95): the attribute
 * selector, found in the toast's class by the kept class it answers, and the dropdown, by the
 * literal naming its control. More than one of either refuses before anything changes; either one
 * missing refuses after the other is hooked.
 */
internal fun BytecodePatchContext.addSlowerSpeeds() {
    // Up to 584 the selector sits in the toast's class; 585 moved the toast into a Runnable, so look build-wide.
    val selectors = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
        selectors += classDef.methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == ATTRIBUTE_SELECTOR && speedLists(it).isNotEmpty()
        }
    }
    val menus = classDefByStrings(SPEED_DROPDOWN, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { holder -> holder.methods.filter { holdsString(it, SPEED_DROPDOWN) } }
    // 585's dropdown has no list of its own: it calls the selector, which fills both lists.
    val dropdowns = menus.filter { speedLists(it).isNotEmpty() }
    val delegating = menus.filter { menu ->
        speedLists(menu).isEmpty() && menu.implementation?.instructions?.any { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            call != null && selectors.any { it.definingClass == call.definingClass && it.name == call.name }
        } == true
    }
    if (selectors.size > 1 || dropdowns.size > 1) {
        refuse("expected one speed selector and one speed dropdown, found ${selectors.size} and ${dropdowns.size}")
    }
    (selectors + dropdowns).forEach { picker ->
        val method = mutableClassDefBy(picker.definingClass).findMutableMethodOf(picker)
        speedLists(method).asReversed().forEach { (result, list) ->
            method.addInstructions(
                result + 1,
                """
                    invoke-static/range { v$list .. v$list }, $SPEED_CHOICES
                    move-result-object v$list
                """,
            )
        }
    }
    if (selectors.isEmpty()) refuse("no speed selector answering $ATTRIBUTE_SELECTOR fills a Float[]")
    if (dropdowns.isEmpty() && delegating.isEmpty()) {
        refuse("no speed dropdown holding \"$SPEED_DROPDOWN\" fills a Float[] or calls the speed selector")
    }
}

/**
 * The gear menu's speed sheet (#95). Its builder, the one method of a class holding
 * "PlayerControlsPlaybackSpeedBottomSheet" that makes [gearPick]'s class, hands the extension the
 * flag for reading each speed from its float as soon as it has it (its last parameter, first thing,
 * up to 583; right after the MobileConfig read that answers it on 584), and takes the answer back
 * in the same register, ahead of the copy the pick gets. Where its paths have met and it's about to walk the
 * labels, it hands over the speeds and then the labels, each through the range form, and takes
 * each back in its own register. That hook goes in under the walk's label, so a branch landing
 * there runs it too. Refuses before anything changes.
 */
internal fun BytecodePatchContext.addSlowerGearSpeeds(gearPick: Method) {
    val sheet = gearSheet(gearPick)
    val meets = gearMeets(sheet)
    val meet = meets.singleOrNull()
        ?: refuse("expected one place ${sheet.definingClass}->${sheet.name} reads a speed for each label, found ${meets.size}")
    val method = mutableClassDefBy(sheet.definingClass).findMutableMethodOf(sheet)
    method.addInstructionsAtControlFlowLabel(
        meet.index,
        """
            invoke-static/range { v${meet.speeds} .. v${meet.speeds} }, $GEAR_SPEEDS
            move-result-object v${meet.speeds}
            invoke-static/range { v${meet.labels} .. v${meet.labels} }, $GEAR_LABELS
            move-result-object v${meet.labels}
        """,
    )
    method.addInstructions(
        meet.valuesAt,
        """
            invoke-static/range { v${meet.values} .. v${meet.values} }, $GEAR_VALUES
            move-result v${meet.values}
        """,
    )
}

/** The gear menu's speed sheet builder: the one method of a class holding [GEAR_SHEET] that makes [gearPick]'s class. */
private fun BytecodePatchContext.gearSheet(gearPick: Method): Method {
    val sheets = classDefByStrings(GEAR_SHEET, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { holder -> holder.methods.filter { isGearSheet(it, gearPick.definingClass) } }
    return sheets.singleOrNull()
        ?: refuse("expected one gear speed sheet builder making ${gearPick.definingClass}, found ${sheets.size}")
}

/**
 * The speed the gear menu's sheet checks (#199). Where its builder finds no player and loads 1f as
 * the speed to check, it hands that to the extension through the range form straight after and
 * takes the answer back in the same register, so the sheet checks the speed the player on screen
 * plays at, and Normal can be picked to forget a kept speed. The extension's speed stub is filled
 * with the getter the builder reads when it does find its player. Looks the builder over as it
 * stands, after [addSlowerGearSpeeds], and refuses before anything changes.
 */
internal fun BytecodePatchContext.checkGearSheetSpeed(anchors: ReelSpeedAnchors) {
    val sheet = gearSheet(anchors.gearPick)
    val owner = anchors.owner.type
    val method = mutableClassDefBy(sheet.definingClass).findMutableMethodOf(sheet)
    val misses = gearMisses(method, owner)
    val miss = misses.singleOrNull()
        ?: refuse("expected one place ${sheet.definingClass}->${sheet.name} checks normal speed for a player it can't find, " +
            "found ${misses.size}")
    val getter = anchors.owner.methods.singleOrNull {
        it.name == miss.getter.name && it.returnType == "F" && it.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$owner has no ()F ${miss.getter.name} of its own")
    if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("$owner->${getter.name} isn't public, so the extension can't call it")
    val stub = reelSpeedStub(SPEED_STUB, listOf("Ljava/lang/Object;"), "F")
    method.addInstructions(
        miss.index + 1,
        """
            invoke-static/range { v${miss.speed} .. v${miss.speed} }, $GEAR_SHEET_SPEED
            move-result v${miss.speed}
        """,
    )
    stub.addInstructions(
        0,
        """
            check-cast p0, $owner
            invoke-virtual/range { p0 .. p0 }, $owner->${getter.name}()F
            move-result p0
            return p0
        """,
    )
}

/**
 * HeroManager's setPlaybackSpeed keeps the speed and the pitch at [HERO_FLOOR] or faster. Its one
 * load of that floor loads [SLOWEST] instead, into the same register, so a 0.1x pick reaches the
 * service player, whose audio goes down to 0.1x.
 */
private fun BytecodePatchContext.lowerSpeedFloor() {
    val methods = classDefByStrings(SPEED_RANGE_LOG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { holder -> holder.methods.filter { holdsString(it, SPEED_RANGE_LOG) } }
    val method = methods.singleOrNull()
        ?: refuse("expected one speed setter holding \"$SPEED_RANGE_LOG\", found ${methods.size}")
    val floors = speedFloors(method)
    val floor = floors.singleOrNull()
        ?: refuse("expected ${method.definingClass}->${method.name} to load ${HERO_FLOOR}f once, found ${floors.size}")
    val helpers = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    if (!callsFloatMax(method, helpers)) refuse("${method.definingClass}->${method.name} keeps no speed over a floor")
    val register = (method.implementation!!.instructions.elementAt(floor) as OneRegisterInstruction).registerA
    mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
        .replaceInstruction(floor, "const v$register, 0x${SLOWEST.toRawBits().toString(16)}")
}

/** 2 when [setter] starts with the release guard's hook and its move-result, else 0. */
private fun afterGuard(setter: MutableMethod): Int {
    val code = setter.implementation!!.instructions
    val first = code.firstOrNull() as? ReferenceInstruction ?: return 0
    return if (first.reference.toString() == GUARD_SPEED_SET && code.getOrNull(1)?.opcode == Opcode.MOVE_RESULT) 2 else 0
}

/** The extension's static [answer] [name]([parameters]) stub. */
private fun BytecodePatchContext.reelSpeedStub(name: String, parameters: List<String>, answer: String): MutableMethod =
    mutableClassDefBy(REEL_SPEED).methods.singleOrNull {
        it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: refuse("$REEL_SPEED has no static $answer $name(${parameters.joinToString("")})")

/** Fills the extension's stubs. Each reads only its parameter registers, cast to the player's or the params' own type. */
private fun BytecodePatchContext.fillStubs(anchors: ReelSpeedAnchors) {
    fun stub(name: String, parameters: List<String>, answer: String): MutableMethod = reelSpeedStub(name, parameters, answer)

    val owner = anchors.owner.type
    val objectType = "Ljava/lang/Object;"
    stub(SET_SPEED_STUB, listOf(objectType, "F"), "V").addInstructions(
        0,
        """
            check-cast p0, $owner
            invoke-virtual/range { p0 .. p1 }, $owner->${anchors.setter.name}(F)V
            return-void
        """,
    )
    stub(ORIGIN_STUB, listOf(objectType), objectType).addInstructions(
        0,
        """
            check-cast p0, $owner
            invoke-virtual/range { p0 .. p0 }, $owner->${anchors.origin.name}()$PLAYER_ORIGIN
            move-result-object p0
            return-object p0
        """,
    )
    stub(REEL_PARAMS_STUB, listOf(objectType), objectType).addInstructions(
        0,
        """
            check-cast p0, $owner
            invoke-virtual/range { p0 .. p0 }, $owner->${anchors.params.name}()$VIDEO_PLAYER_PARAMS
            move-result-object p0
            return-object p0
        """,
    )
    anchors.flags.forEach { (name, field) ->
        stub(name, listOf(objectType), "Z").addInstructions(
            0,
            """
                check-cast p0, $VIDEO_PLAYER_PARAMS
                iget-boolean p0, p0, $VIDEO_PLAYER_PARAMS->${field.name}:Z
                return p0
            """,
        )
    }
}
