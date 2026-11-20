/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.confirm

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.stories.taps.PAUSE_BUTTON
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/*
 * The story reaction handlers (#502). Read from 587, 2026-11-17, arm64 and armeabi-v7a; the
 * obfuscated names here are for reviewers, the code never writes one down.
 *
 * - The story viewer's reaction bar handler (LX/0Blm;->IqY on arm64, LX/0BlE;->IqU on armeabi-v7a)
 *   sends every reaction the reply bar, its long press, the suggested footer, a swipe up and the
 *   single reaction button pass it, each naming itself in its last String. It's an instance method
 *   returning void that takes the view, the view group, the StoryBucket, the StoryCard, the
 *   reaction, that name and an int, and it's the one method of that shape holding "story_reaction".
 * - The single reaction button's own click is an arm of a merged lambda (LX/0WWm;->invoke,
 *   LX/0ZTs;->invoke), the one method holding "single_entry_reaction_component". Its int field
 *   picks the arm with a packed switch, and the hook only asks when it holds that arm's key.
 *
 * A story keeps playing under a plain dialog. Facebook's own dialogs over a story each pause it with
 * a (Z)V toggle (LX/0CDF;->BIK and LX/0C7c;->BIK on arm64, LX/0CCh;->BIM and LX/0C74;->BIM on
 * armeabi-v7a): it hands the story viewer's environment to a static getter (LX/0Af1;->A0a) for the
 * pauser, casts it, loads the ALERT_DIALOG_OPEN constant of the pause reason enum, and pauses with it
 * when the flag is set, resuming from it otherwise. The patch reads that route off the toggles and
 * fills AskBeforeLike's three stubs with it, so the question pauses a story the same way. Both
 * reaction handlers keep the environment in a field, where the extension finds it.
 */

/** The reaction bar handler's marker, and the single reaction button's. */
internal const val STORY_REACTION_NAME = "story_reaction"
internal const val SINGLE_REACTION = "single_entry_reaction_component"

/** The pause reason Facebook's own dialogs over a story pause it under. */
internal const val ALERT_DIALOG_OPEN = "ALERT_DIALOG_OPEN"

/** The stubs the patch fills in. */
internal const val STORY_PAUSER_OF = "storyPauserOf"
internal const val PAUSE_STORY = "pauseStory"
internal const val RESUME_STORY = "resumeStory"

private const val STORY_VIEW = "Landroid/view/View;"
private const val STORY_VIEW_GROUP = "Landroid/view/ViewGroup;"
private const val STORY_BUCKET = "Lcom/facebook/stories/model/StoryBucket;"
private const val STORY_CARD = "Lcom/facebook/stories/model/StoryCard;"
private const val STORY_SOURCE = "Ljava/lang/String;"
private const val LAMBDA_OBJECT = "Ljava/lang/Object;"

/** The story viewer's reaction bar handler: (View, ViewGroup, StoryBucket, StoryCard, reaction, String, int) returning void. */
internal fun isStoryReactionBar(method: Method): Boolean {
    val parameters = method.parameterTypes.map(Any::toString)
    return !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && parameters.size == 7 &&
        parameters.take(4) == listOf(STORY_VIEW, STORY_VIEW_GROUP, STORY_BUCKET, STORY_CARD) &&
        parameters[4].startsWith("L") && parameters.drop(5) == listOf(STORY_SOURCE, "I")
}

/** A Kotlin lambda's invoke(Object) returning Object. */
internal fun isOneArgLambda(method: Method) =
    method.name == "invoke" && method.returnType == LAMBDA_OBJECT &&
        method.parameterTypes.map(Any::toString) == listOf(LAMBDA_OBJECT)

/**
 * Both story reaction handlers, found and checked before anything changes. 588's store-less build
 * moved "story_reaction" into a string table (`LX/0Det;->A00`, index 0x48f), which its bar handler
 * (`LX/0BJa;->Jjk`) asks, so the bar is any bar-shaped method naming it ([methodsNaming]).
 */
internal fun BytecodePatchContext.storyReactionHandlers(): List<LikeHandler> {
    val bars = methodsNaming(STORY_REACTION_NAME, ::isStoryReactionBar)
        .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }
    val bar = bars.singleOrNull()
        ?: refuse("expected one story reaction bar handler holding \"$STORY_REACTION_NAME\", found ${bars.size}")
    val singles = methodsHolding(SINGLE_REACTION).filter(::isOneArgLambda)
    val single = singles.singleOrNull()
        ?: refuse("expected one lambda holding \"$SINGLE_REACTION\", found ${singles.size}")
    val handlers = listOf(
        LikeHandler(STORY_REACTION, bar),
        LikeHandler(STORY_SINGLE_REACTION, single, armOf(single, SINGLE_REACTION)),
    )
    handlers.forEach { checkHandler(it, "the story reaction handler of kind ${it.kind}") }
    if (handlers.map { descriptorOf(it.method) }.toSet().size != handlers.size) refuse("the two story reaction handlers are one")
    return handlers
}

/**
 * How Facebook pauses a story for one of its own dialogs: the static [getter] that takes the story
 * viewer's [environment] and answers the pauser, the type the toggles cast that to ([pauser]), the
 * [reason] field, and the [pause] and [resume] they call on it with [invoke].
 */
internal data class StoryPauseRoute(
    val environment: String,
    val getter: String,
    val pauser: String,
    val reason: String,
    val invoke: Opcode,
    val pause: String,
    val resume: String,
)

private val PAUSER_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE)

/** A dialog's pause toggle: an instance (Z)V that loads [alert]. */
internal fun isDialogPauseToggle(method: Method, alert: String) =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.map(Any::toString) == listOf("Z") &&
        method.code().any { it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == alert }

/**
 * The route [toggle] pauses and resumes a story by, or null when it isn't the plain shape: the
 * getter's answer cast and called with [alert], pause on the arm the flag being set takes and
 * resume on the other.
 */
internal fun dialogPauseRouteOf(toggle: Method, alert: String): StoryPauseRoute? {
    val code = toggle.code()
    val flag = toggle.implementation?.registerCount?.minus(1) ?: return null
    val branchAt = code.indexOfFirst {
        (it.opcode == Opcode.IF_EQZ || it.opcode == Opcode.IF_NEZ) && (it as OneRegisterInstruction).registerA == flag
    }
    if (branchAt < 0) return null
    val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
    val target = indexOfAddress(code)[addresses[branchAt] + (code[branchAt] as OffsetInstruction).codeOffset] ?: return null
    if (target <= branchAt) return null
    val alertAt = code.indexOfFirst { it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == alert }
    val castAt = (0 until branchAt).lastOrNull { code[it].opcode == Opcode.CHECK_CAST } ?: return null
    if (alertAt !in 0 until branchAt) return null
    val reasonRegister = (code[alertAt] as OneRegisterInstruction).registerA
    val pauserRegister = (code[castAt] as OneRegisterInstruction).registerA
    val pauser = ((code[castAt] as ReferenceInstruction).reference as TypeReference).type
    // The getter's answer is moved into the register the cast checks.
    val getterAt = (0 until castAt).lastOrNull { code[it].opcode == Opcode.INVOKE_STATIC } ?: return null
    val moved = code.getOrNull(getterAt + 1)
    if (moved?.opcode != Opcode.MOVE_RESULT_OBJECT || (moved as OneRegisterInstruction).registerA != pauserRegister) return null
    val getter = (code[getterAt] as ReferenceInstruction).reference as MethodReference
    if (getter.parameterTypes.size != 1 || !getter.parameterTypes[0].startsWith("L")) return null
    val reasonType = alert.substringAfterLast(":")

    /** The one pauser call in [range]: the cast pauser and the reason, returning void. */
    fun callIn(range: IntRange): Pair<Opcode, String>? {
        val calls = range.map { code[it] }.filter { it.opcode in PAUSER_CALLS }
        val call = calls.singleOrNull() as? FiveRegisterInstruction ?: return null
        val reference = (call as ReferenceInstruction).reference as MethodReference
        if (call.registerCount != 2 || call.registerC != pauserRegister || call.registerD != reasonRegister) return null
        if (reference.definingClass != pauser || reference.returnType != "V" ||
            reference.parameterTypes.map(Any::toString) != listOf(reasonType)
        ) {
            return null
        }
        return call.opcode to reference.toString()
    }
    val fallThrough = callIn(branchAt + 1 until target) ?: return null
    val jumped = callIn(target until code.size) ?: return null
    if (fallThrough.first != jumped.first) return null
    // if-eqz jumps when the flag is clear, so falling through is the pause.
    val (pause, resume) = if (code[branchAt].opcode == Opcode.IF_EQZ) fallThrough to jumped else jumped to fallThrough
    return StoryPauseRoute(
        environment = getter.parameterTypes[0].toString(),
        getter = getter.toString(),
        pauser = pauser,
        reason = alert,
        invoke = pause.first,
        pause = pause.second,
        resume = resume.second,
    )
}

/**
 * The route every dialog pause toggle agrees on, found by Facebook's own enum constant names.
 * Throws naming what's missing, or what the extension couldn't reach.
 */
internal fun BytecodePatchContext.storyPauseRoute(): StoryPauseRoute {
    val reasons = classDefByStrings(ALERT_DIALOG_OPEN, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter { AccessFlags.ENUM.isSet(it.accessFlags) && enumConstant(it, PAUSE_BUTTON) != null }
    val reason = reasons.singleOrNull()
        ?: refuse("expected one story pause reason enum with $ALERT_DIALOG_OPEN and $PAUSE_BUTTON, found ${reasons.size}")
    val alert = enumConstant(reason, ALERT_DIALOG_OPEN) ?: refuse("${reason.type} has no $ALERT_DIALOG_OPEN")
    val toggles = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(reason.type)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(toggles) { isDialogPauseToggle(it, alert) }
    }
    if (toggles.isEmpty()) refuse("no dialog pause toggle loads $alert")
    val routes = toggles.map { toggle ->
        dialogPauseRouteOf(toggle, alert) ?: refuse("${toggle.definingClass}->${toggle.name} isn't a plain pause toggle")
    }.toSet()
    val route = routes.singleOrNull() ?: refuse("the dialog pause toggles take ${routes.size} routes")

    // The stubs live in the extension's own package, so all of these have to be public.
    fun requirePublicClass(type: String) {
        val def = classDefByOrNull(type) ?: refuse("$type isn't in the app")
        if (!AccessFlags.PUBLIC.isSet(def.accessFlags)) refuse("$type isn't public")
    }
    fun requirePublicMethod(reference: String, static: Boolean) {
        val owner = reference.substringBefore("->")
        val def = classDefByOrNull(owner) ?: refuse("$owner isn't in the app")
        val declared = def.methods.singleOrNull { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == reference }
        if (!AccessFlags.PUBLIC.isSet(def.accessFlags) || declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags) ||
            AccessFlags.STATIC.isSet(declared.accessFlags) != static
        ) {
            refuse("$reference isn't a public ${if (static) "static " else ""}method of a public class")
        }
    }
    requirePublicClass(route.environment)
    requirePublicClass(route.pauser)
    requirePublicMethod(route.getter, static = true)
    requirePublicMethod(route.pause, static = false)
    requirePublicMethod(route.resume, static = false)
    val reasonField = reason.fields.singleOrNull { "${reason.type}->${it.name}:${it.type}" == alert }
    if (!AccessFlags.PUBLIC.isSet(reason.accessFlags) || reasonField == null || !AccessFlags.PUBLIC.isSet(reasonField.accessFlags)) {
        refuse("the reason enum ${reason.type} or its $ALERT_DIALOG_OPEN isn't public")
    }
    return route
}

/** Fills AskBeforeLike's story stubs with [route]. */
internal fun BytecodePatchContext.fillStoryPauseStubs(route: StoryPauseRoute) {
    val extension = mutableClassDefBy(ASK_BEFORE_LIKE)
    // D8 builds each stub in the registers its parameter needs, so each is rebuilt with a second to work in.
    fun rebuilt(name: String): MutableMethod {
        val stubbed = extension.methods.singleOrNull { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }
            ?: refuse("$ASK_BEFORE_LIKE has no static $name")
        val method = ImmutableMethod(
            ASK_BEFORE_LIKE, stubbed.name, stubbed.parameters, stubbed.returnType, stubbed.accessFlags, null, null,
            MutableMethodImplementation(2),
        ).toMutable()
        if (!extension.methods.remove(stubbed)) refuse("$ASK_BEFORE_LIKE->$name couldn't be replaced")
        extension.methods.add(method)
        return method
    }
    // Anything but the story viewer's environment answers null, which leaves the story playing, rather than throwing.
    rebuilt(STORY_PAUSER_OF).addInstructionsWithLabels(
        0,
        """
            instance-of v0, p0, ${route.environment}
            if-eqz v0, :other
            check-cast p0, ${route.environment}
            invoke-static { p0 }, ${route.getter}
            move-result-object p0
            return-object p0
            :other
            const/4 p0, 0x0
            return-object p0
        """,
    )
    val call = if (route.invoke == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual"
    for ((name, target) in listOf(PAUSE_STORY to route.pause, RESUME_STORY to route.resume)) {
        rebuilt(name).addInstructions(
            0,
            """
                check-cast p0, ${route.pauser}
                sget-object v0, ${route.reason}
                $call { p0, v0 }, $target
                return-void
            """,
        )
    }
}

/**
 * Hooks both story reaction handlers and fills the pause stubs, all found first, so a build missing
 * a handler refuses before any code changes. A build whose pause route can't be read still asks,
 * over a playing story, and warns.
 */
internal fun BytecodePatchContext.askBeforeStoryReaction() {
    val handlers = storyReactionHandlers()
    val route = try {
        storyPauseRoute()
    } catch (exception: PatchException) {
        patchLog.warning("${exception.message}, so a story keeps playing while the question is up.")
        null
    }
    hookAll(handlers)
    route?.let { fillStoryPauseStubs(it) }
}
