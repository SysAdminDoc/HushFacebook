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
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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
 *
 * The held story's callback goes to the extension too, so the story play button can make the same
 * restart on a tap (#380), and every progress the callback gets tells the extension whether a card
 * is still held. Facebook also moves on from a finished story when a touch lifts: its controller's
 * touch release calls the navigator itself once the stored progress is complete, so that call is
 * asked the same question.
 */
@Suppress("unused")
val blockStoryAutoAdvancePatch = bytecodePatch(
    name = "Stop Story auto-advance",
    description = "Keeps each story on screen until you tap or swipe, so you can take your time with it. A " +
        "second switch replays a finished story from the start. Starts off. Turn it on in Hushfacebook settings " +
        "> Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
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
 * it makes Facebook's own restart through [route] with false, as the navigator does, so the
 * finished card plays again from the start. It has registers of its own, so the callback lends it
 * none. The navigator reaches the restart only after its own checks, and the environment getter
 * throws once the viewer has detached, so a throw or a missing restart leaves the story on its
 * last frame instead of reaching the progress callback.
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
            const/4 v1, 0x0
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
internal const val LOOP_RESTART_TO = 10

/**
 * A static method of [owner] taking the callback that makes Facebook's own restart through [route]
 * with no switch to ask, for the play button's replay, and answers whether it reached the reset. A
 * throw goes back to the extension's replay, which reports it and leaves the story where it is.
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
            const/4 v1, 0x0
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
