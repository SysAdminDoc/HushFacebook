/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.autoadvance

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val AUTO_NAVIGATION = "StoryviewerAutoPlayNavigationController.moveToNextBucketOrThread"
private const val STORY_BUCKET = "Lcom/facebook/stories/model/StoryBucket;"
private const val STORY_CARD = "Lcom/facebook/stories/model/StoryCard;"
private const val WAIT_FOR_TAP = "$EXTENSION_PACKAGE/stories/StoryAdvance;->waitForTap()Z"
private const val LOOP = "$EXTENSION_PACKAGE/stories/StoryAdvance;->loop()Z"
private const val LOOP_FAILED = "$EXTENSION_PACKAGE/stories/StoryAdvance;->loopFailed(Ljava/lang/Throwable;)V"
internal const val STORY_ADVANCE = "$EXTENSION_PACKAGE/stories/StoryAdvance;"
internal const val HELD = "$STORY_ADVANCE->held(Ljava/lang/Object;)V"
internal const val PROGRESSED = "$STORY_ADVANCE->progressed(I)V"
internal const val LAST_CARD_TAP = "$STORY_ADVANCE->lastCardTap(Ljava/lang/Object;)Z"
internal const val RESTARTING = "$STORY_ADVANCE->restarting(Ljava/lang/Object;Ljava/lang/Object;)V"

/** The trace Facebook's forward navigation writes as it picks the next card, the next bucket or leaving. */
internal const val NEXT_OR_EXIT = "StoryViewerNavigationDelegate.moveToNextBucketOrThreadOrExit"

/** Facebook's name for the navigation reason a story's own completion moves on with. */
internal const val NAVIGATION_AUTO_ADVANCE = "AUTO_ADVANCE"

/** The extension's stub the patch fills with a call to [RESTART_HELPER], for the play button's replay. */
internal const val RESTART_STUB = "restart"

/** The method the patch adds to the progress callback's class to start a finished story again. */
internal const val LOOP_HELPER = "hushfacebookLoopStory"

/** The same restart without the Loop stories switch, for a replay the user asks for (#380). */
internal const val RESTART_HELPER = "hushfacebookRestartStory"

/**
 * Keeps the completion progress, but leaves the move to the next story to the user's gesture.
 *
 * Its second switch, Loop stories, starts a finished story again instead of holding its last
 * frame. Facebook has a restart of its own: the navigator the completion calls asks the viewer's
 * playback state to reset (a broadcast its listeners take as "play this card from the start")
 * when a one-card bucket finishes on a surface that loops, and its back-tap on a first card
 * resets the same way. The loop makes that same call, found in the navigator by its shape, from
 * a method the patch adds to the callback's class, so a tap or swipe still moves on as before.
 * It resets with the call's flag on, the replay Facebook asks for when a one-card bucket starts
 * again: a finished story video clears its finished state only then, and with the flag off it
 * stayed on its last frame with a full bar while a photo's timer restarted (#380).
 *
 * The held story's callback goes to the extension too, so the story play button can make the same
 * restart on a tap (#380), and every progress the callback gets tells the extension whether a card
 * is still held. Facebook also moves on from a finished story when a touch lifts: its controller's
 * touch release calls the navigator itself once the stored progress is complete, so that call is
 * asked the same question.
 *
 * Some surfaces, a Page's stories among them, ignore a forward tap on a bucket's last card and
 * leave only through the completion, so a held last story could never be left by a tap (#380).
 * Facebook's forward navigation compares its reason with the completion's at the start of its
 * last-card branch, and there a tap's reason becomes the completion's while the switch is on.
 */
@Suppress("unused")
val blockStoryAutoAdvancePatch = patch(
    name = "Stop Story auto-advance",
    description = "Keeps each story on screen until you tap or swipe, so you can take your time with it. A " +
        "second switch replays a finished story from the start. Starts off. Turn it on in Hushfacebook settings " +
        "> Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val hooks = classDefByStrings(AUTO_NAVIGATION, StringComparisonType.EQUALS)
            .mapNotNull(::autoAdvanceHook)
        val hook = hooks.singleOrNull() ?: throw PatchException(
            "Expected one Story progress callback with a completion navigation call, found ${hooks.size}",
        )
        val (callback, callIndex) = hook
        // The guard hands the loop helper p0, the callback itself.
        if (AccessFlags.STATIC.isSet(callback.accessFlags)) {
            throw PatchException("Stop Story auto-advance: the progress callback is static, so p0 isn't the callback")
        }
        val owner = mutableClassDefBy(callback.definingClass)
        val navigator = navigatorOf(owner, callback, callIndex)
        val route = restartRoute(navigator, owner.type) ?: throw PatchException(
            "Stop Story auto-advance: expected Facebook's own restart in ${owner.type}->${navigator.name}: " +
                "the callback's playback environment, its reset lookup and a reset taking a boolean",
        )
        owner.methods.add(loopHelper(owner.type, route))
        owner.methods.add(restartHelper(owner.type, route))
        fillRestartStub(owner.type)
        // Facebook's touch release, in the callback's class or the one it extends, before the callback
        // changes: its sites are read off the stock code.
        val releases = listOfNotNull(owner, owner.superclass?.let(::classDefByOrNull))
            .flatMap { holder -> releaseNavigations(holder, callback, navigator).map { holder.type to it } }
        for ((holder, site) in releases) {
            val (method, index) = site
            val mutableRelease = mutableClassDefBy(holder).methods.single {
                it.name == method.name && it.returnType == method.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
            }
            mutableRelease.addInstructionsWithLabels(
                index,
                releaseBlock(mutableRelease.waitForTapRegister(index)),
                ExternalLabel("held", mutableRelease.getInstruction(index + 1)),
            )
        }
        val mutable = owner.methods.single {
            it.name == callback.name && it.parameterTypes == callback.parameterTypes
        }
        mutable.addInstructionsWithLabels(
            callIndex,
            waitForTapBlock(mutable.waitForTapRegister(callIndex), owner.type),
            ExternalLabel("navigate", mutable.getInstruction(callIndex)),
        )
        // The callback's last parameter is the progress, 0 to 1000.
        mutable.addInstructions(0, "invoke-static/range { p3 .. p3 }, $PROGRESSED")
        // A forward tap on a bucket's last card goes where the finished story would have (#380).
        val (forward, branch) = lastCardNavigation()
        val mutableForward = mutableClassDefBy(forward.definingClass).methods.single {
            it.name == forward.name && it.returnType == forward.returnType &&
                it.parameterTypes.map(CharSequence::toString) == forward.parameterTypes.map(CharSequence::toString)
        }
        mutableForward.addInstructionsWithLabels(
            branch.index,
            lastCardBlock(mutableForward.waitForTapRegister(branch.index), branch.reason, branch.autoAdvance),
            ExternalLabel("last", mutableForward.getInstruction(branch.index)),
        )
        enableStatus("storyAutoAdvance")
    }
}

/**
 * The calls of [navigator] in [holder]'s methods other than [callback], the progress callback:
 * Facebook's touch release moves a finished story on through the same navigator. Each is a pair of
 * the method and the index of the call, followed by an instruction to skip to.
 */
internal fun releaseNavigations(holder: ClassDef, callback: Method, navigator: Method): List<Pair<Method, Int>> =
    holder.methods.filterNot {
        it.definingClass == callback.definingClass && it.name == callback.name && it.returnType == callback.returnType &&
            it.parameterTypes.map(CharSequence::toString) == callback.parameterTypes.map(CharSequence::toString)
    }
        .flatMap { method ->
            val body = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
            body.withIndex().filter { (index, instruction) ->
                val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                index + 1 < body.size && instruction.opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) &&
                    ref?.definingClass == navigator.definingClass && ref.name == navigator.name &&
                    ref.returnType == navigator.returnType &&
                    ref.parameterTypes.map(CharSequence::toString) == navigator.parameterTypes.map(CharSequence::toString)
            }.map { (index, _) -> method to index }
        }

/** Where the last-card branch of Facebook's forward navigation starts, the register holding the reason there, and the completion's reason. */
internal data class LastCardBranch(val index: Int, val reason: Int, val autoAdvance: String)

/**
 * The branch Facebook's forward navigation takes on a bucket's last card: it asks a boolean method
 * whether to move to the next bucket, and when that answers true the branch starts by comparing the
 * navigation's reason with [autoAdvance], the reason a story's own completion moves on with. 588
 * asks well before that branch and jumps to it, so the answer is traced from the branch's register
 * back to the call that wrote it. Null unless exactly one comparison sits right where such an answer
 * branches.
 */
internal fun lastCardBranch(navigation: Method, autoAdvance: String): LastCardBranch? {
    val body = navigation.implementation?.instructions?.toList() ?: return null
    val sites = (1 until body.size - 1).mapNotNull { i ->
        val load = body[i]
        if (load.opcode != Opcode.SGET_OBJECT || (load as ReferenceInstruction).reference.toString() != autoAdvance) {
            return@mapNotNull null
        }
        if (body[i + 1].opcode != Opcode.IF_NE && body[i + 1].opcode != Opcode.IF_EQ) return@mapNotNull null
        val compare = body[i + 1] as TwoRegisterInstruction
        val reason = when ((load as OneRegisterInstruction).registerA) {
            compare.registerA -> compare.registerB
            compare.registerB -> compare.registerA
            else -> return@mapNotNull null
        }
        if (body[i - 1].opcode != Opcode.IF_EQZ) return@mapNotNull null
        val answer = navigation.writersReaching(i - 1, (body[i - 1] as OneRegisterInstruction).registerA).singleOrNull()
            ?.takeIf { it > 0 && body[it].opcode == Opcode.MOVE_RESULT } ?: return@mapNotNull null
        val asked = (body[answer - 1] as? ReferenceInstruction)?.reference as? MethodReference
        if (asked?.returnType == "Z") LastCardBranch(i, reason, autoAdvance) else null
    }
    return sites.singleOrNull()
}

/**
 * Facebook's forward navigation, the instance method holding [NEXT_OR_EXIT], and its last-card
 * branch, with the completion's reason read off the navigation's reason enum. Throws naming what's
 * missing.
 */
internal fun BytecodePatchContext.lastCardNavigation(): Pair<Method, LastCardBranch> {
    val found = classDefByStrings(NEXT_OR_EXIT, StringComparisonType.EQUALS).flatMap { def ->
        def.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && holdsString(it, NEXT_OR_EXIT) }
    }
    val navigation = found.singleOrNull() ?: throw PatchException(
        "Stop Story auto-advance: expected one forward navigation holding \"$NEXT_OR_EXIT\", found ${found.size}",
    )
    val reasons = navigation.parameterTypes.mapNotNull { type ->
        classDefByOrNull(type.toString())?.let { enumConstant(it, NAVIGATION_AUTO_ADVANCE) }
    }
    val autoAdvance = reasons.singleOrNull() ?: throw PatchException(
        "Stop Story auto-advance: expected one reason enum with $NAVIGATION_AUTO_ADVANCE among " +
            "${navigation.definingClass}->${navigation.name}'s parameters ${navigation.parameterTypes}",
    )
    val branch = lastCardBranch(navigation, autoAdvance) ?: throw PatchException(
        "Stop Story auto-advance: expected one last-card branch comparing the reason with $autoAdvance in " +
            "${navigation.definingClass}->${navigation.name}",
    )
    return navigation to branch
}

/**
 * What goes first in the last-card branch: while the extension says so, a forward tap's reason
 * becomes the completion's, so the branch moves on to the next bucket or leaves, as it does once a
 * story finishes by itself.
 */
internal fun lastCardBlock(register: Int, reason: Int, autoAdvance: String) = """
    invoke-static/range { v$reason .. v$reason }, $LAST_CARD_TAP
    move-result v$register
    if-eqz v$register, :last
    sget-object v$reason, $autoAdvance
"""

/** What goes in front of a touch release's navigation: skip it while the extension holds the story. */
internal fun releaseBlock(register: Int) = """
    invoke-static { }, $WAIT_FOR_TAP
    move-result v$register
    if-nez v$register, :held
"""

/** Fills the extension's restart stub with a call to [RESTART_HELPER] on [owner], answering whether it restarted. */
internal fun BytecodePatchContext.fillRestartStub(owner: String) {
    val stub = mutableClassDefBy(STORY_ADVANCE).methods.singleOrNull {
        it.name == RESTART_STUB && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("Stop Story auto-advance: $STORY_ADVANCE has no static $RESTART_STUB(Object)Z")
    stub.addInstructions(
        0,
        """
            check-cast p0, $owner
            invoke-static/range { p0 .. p0 }, $owner->$RESTART_HELPER($owner)Z
            move-result p0
            return p0
        """,
    )
}

/**
 * The register the guard borrows in front of the navigation call at [callIndex]: the lowest local
 * that nothing reads from the call on. The guard sits in the middle of the callback, where a local
 * can still hold something the call or the code after it wants, so having locals isn't enough.
 * The guard only branches back to the call or returns, and names the register in a `move-result`
 * and an `if-eqz`, which reach v255.
 */
internal fun Method.waitForTapRegister(callIndex: Int): Int =
    freeLocalsAt("Stop Story auto-advance", callIndex, 1, highest = 255).single()

/**
 * What goes in front of the navigation call: ask the extension, and return instead of navigating.
 * When it holds the story, the extension keeps the callback for a replay, and the callback's class
 * gets to start it again ([loopHelper]), which does nothing unless Loop stories is on.
 */
internal fun waitForTapBlock(register: Int, owner: String) = """
    invoke-static { }, $WAIT_FOR_TAP
    move-result v$register
    if-eqz v$register, :navigate
    invoke-static/range { p0 .. p0 }, $HELD
    invoke-static/range { p0 .. p0 }, $owner->$LOOP_HELPER($owner)V
    return-void
"""

/** The three calls of Facebook's own restart: the playback environment, its reset lookup, the reset. */
internal data class RestartRoute(
    val environment: MethodReference,
    val lookup: MethodReference,
    val reset: MethodReference,
)

/** The navigator the completion call at [callIndex] of [callback] goes to, in [owner]. */
internal fun navigatorOf(owner: ClassDef, callback: Method, callIndex: Int): Method {
    val call = callback.implementation!!.instructions.elementAt(callIndex) as ReferenceInstruction
    val target = call.reference as MethodReference
    return owner.methods.single {
        it.name == target.name && it.returnType == target.returnType &&
            it.parameterTypes.map(Any::toString) == target.parameterTypes.map(Any::toString)
    }
}

private fun width(type: String) = if (type == "J" || type == "D") 2 else 1

/**
 * Facebook's own restart in [navigator]: a no-argument virtual call on the callback (the [owner]
 * itself, or its parameter of that type when the navigator is static) answering its playback
 * environment, a static lookup taking only that environment, and an interface call on what it
 * answers taking one boolean, followed by return-void. Null unless exactly one such run is there.
 */
internal fun restartRoute(navigator: Method, owner: String): RestartRoute? {
    val implementation = navigator.implementation ?: return null
    val body = implementation.instructions.toList()
    val static = AccessFlags.STATIC.isSet(navigator.accessFlags)
    val types = navigator.parameterTypes.map(Any::toString)
    var register = implementation.registerCount - types.sumOf(::width) - (if (static) 0 else 1)
    val ownerRegisters = mutableSetOf<Int>()
    if (!static) ownerRegisters += register++
    for (type in types) {
        if (type == owner) ownerRegisters += register
        register += width(type)
    }
    fun ref(index: Int) = (body[index] as? ReferenceInstruction)?.reference as? MethodReference
    fun registers(index: Int): List<Int> = (body[index] as? FiveRegisterInstruction)?.let {
        listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
    } ?: emptyList()
    fun result(index: Int) = if (body[index].opcode == Opcode.MOVE_RESULT_OBJECT) {
        (body[index] as OneRegisterInstruction).registerA
    } else {
        null
    }
    val routes = (0..body.size - 6).mapNotNull { i ->
        val environment = ref(i) ?: return@mapNotNull null
        val lookup = ref(i + 2) ?: return@mapNotNull null
        val reset = ref(i + 4) ?: return@mapNotNull null
        val envRegister = result(i + 1) ?: return@mapNotNull null
        val lookupRegister = result(i + 3) ?: return@mapNotNull null
        val ok = body[i].opcode == Opcode.INVOKE_VIRTUAL && environment.parameterTypes.isEmpty() &&
            registers(i).singleOrNull() in ownerRegisters && environment.returnType.startsWith("L") &&
            body[i + 2].opcode == Opcode.INVOKE_STATIC &&
            lookup.parameterTypes.map(Any::toString) == listOf(environment.returnType) &&
            registers(i + 2) == listOf(envRegister) && lookup.returnType.startsWith("L") &&
            body[i + 4].opcode == Opcode.INVOKE_INTERFACE && reset.definingClass == lookup.returnType &&
            reset.parameterTypes.map(Any::toString) == listOf("Z") && reset.returnType == "V" &&
            registers(i + 4).firstOrNull() == lookupRegister &&
            body[i + 5].opcode == Opcode.RETURN_VOID
        if (ok) RestartRoute(environment, lookup, reset) else null
    }
    return routes.singleOrNull()
}

/**
 * A static method of [owner], the callback's class, taking the callback: when Loop stories is on
 * it makes Facebook's own restart through [route] with true, Facebook's replay, so the finished
 * card plays again from the start. The story video's reset listener clears its finished state
 * only with the flag on, and the navigator's own false left a finished video still (#380). The
 * extension logs the restart first, with the callback and the reset's owner. It has registers of
 * its own, so the callback lends it none. The navigator reaches the restart only after its own
 * checks, and the environment getter throws once the viewer has detached, so a throw or a
 * missing restart leaves the story on its last frame instead of reaching the progress callback.
 */
internal fun loopHelper(owner: String, route: RestartRoute): MutableMethod = ImmutableMethod(
    owner,
    LOOP_HELPER,
    listOf(ImmutableMethodParameter(owner, null, null)),
    "V",
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(3),
).toMutable().apply {
    addInstructions(
        0,
        """
            invoke-static { }, $LOOP
            move-result v0
            if-eqz v0, :done
            invoke-virtual { p0 }, ${route.environment}
            move-result-object v0
            invoke-static { v0 }, ${route.lookup}
            move-result-object v0
            if-eqz v0, :done
            invoke-static { p0, v0 }, $RESTARTING
            const/4 v1, 0x1
            invoke-interface { v0, v1 }, ${route.reset}
            :done
            return-void
            move-exception v0
            invoke-static { v0 }, $LOOP_FAILED
            return-void
        """,
    )
    val body = implementation!!
    body.addCatch(body.newLabelForIndex(LOOP_RESTART_FROM), body.newLabelForIndex(LOOP_RESTART_TO),
        body.newLabelForIndex(LOOP_RESTART_TO + 1))
}

/** The helper's restart, from the environment getter up to (not including) the return it skips to. */
internal const val LOOP_RESTART_FROM = 3
internal const val LOOP_RESTART_TO = 11

/**
 * A static method of [owner] taking the callback that makes Facebook's own restart through [route]
 * with no switch to ask, for the play button's replay, and answers whether it reached the reset. It
 * resets with the flag on and logs first, as the loop does. A throw goes back to the extension's
 * replay, which reports it and leaves the story where it is.
 */
internal fun restartHelper(owner: String, route: RestartRoute): MutableMethod = ImmutableMethod(
    owner,
    RESTART_HELPER,
    listOf(ImmutableMethodParameter(owner, null, null)),
    "Z",
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(3),
).toMutable().apply {
    addInstructions(
        0,
        """
            invoke-virtual { p0 }, ${route.environment}
            move-result-object v0
            invoke-static { v0 }, ${route.lookup}
            move-result-object v0
            if-eqz v0, :none
            invoke-static { p0, v0 }, $RESTARTING
            const/4 v1, 0x1
            invoke-interface { v0, v1 }, ${route.reset}
            const/4 v0, 0x1
            return v0
            :none
            const/4 v0, 0x0
            return v0
        """,
    )
}

/** The only callback that receives Story progress and invokes this controller's auto navigation. */
internal fun autoAdvanceHook(owner: ClassDef): Pair<Method, Int>? {
    val navigators = owner.methods.filter { holdsString(it, AUTO_NAVIGATION) && it.returnType == "V" }
    if (navigators.size != 1) return null
    val navigator = navigators.single()
    val callbacks = owner.methods.mapNotNull { method ->
        if (method.returnType != "V" ||
            method.parameterTypes.map { it.toString() } != listOf(STORY_BUCKET, STORY_CARD, "I")
        ) return@mapNotNull null
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val completions = instructions.withIndex().filter { (_, instruction) ->
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == 1000
        }
        val calls = instructions.withIndex().filter { (_, instruction) ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == owner.type && ref.name == navigator.name &&
                ref.parameterTypes.map { it.toString() } == navigator.parameterTypes.map { it.toString() } &&
                ref.returnType == navigator.returnType
        }
        if (completions.size != 1 || calls.size != 1 ||
            calls.single().index <= completions.single().index ||
            calls.single().value.opcode !in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_DIRECT)
        ) return@mapNotNull null
        method to calls.single().index
    }
    return callbacks.singleOrNull()
}
