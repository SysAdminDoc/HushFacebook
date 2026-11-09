/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.rings

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.filterBooleanReturns
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The story and LIVE rings around profile pictures (#397). Read from 585, 2026-11-06, arm64; the
 * obfuscated names here are for reviewers, the code never writes one down.
 *
 * - A post header's picture is decorated by implementations of one interface (LX/0LUc;), each with
 *   a check of whether it applies (H7B, an instance boolean method taking the actor and two flags
 *   last) and the decoration itself. The story decorator (LX/077i;) builds the component that names
 *   itself FeedStoryRingComponent in its constructor, the LIVE one (LX/0Kwn;) the component naming
 *   itself FeedLiveRingComponent in its static initializer.
 * - Neither check decides alone. Each asks a static of the feed's ring helper (LX/02xQ;): A02 for
 *   LIVE, and A03, the only static in the app taking (actor, flag, flag) and answering a boolean,
 *   for a story. Every caller of the two is feed header code: the two checks, the header's ring
 *   kind for its own model (LX/02xP;->A01), a header render (LX/0Jnr;) and the single-actor photo
 *   (LX/0783;->BjN), which drops the photo's own click handler when A03 says yes so that a tap
 *   opens the story. Their answers go through the extension, so with the switch on there's no ring
 *   and a tap opens the profile.
 * - A comment's ring comes from the comment row builder's helper (LX/0A8d;->A0B), the static that
 *   answers a ring state (LX/09tD;, a story bucket and a ring kind). With no story it answers one
 *   built from two nulls, and with the switch on it answers that straight away. The class is the
 *   one holding the comment story ring plugin's name.
 * - A profile's picture is Facebook's design system profile photo, a class that keeps its name. Its
 *   one constructor takes the ring kind as its tenth argument, an enum (LX/09tC;) whose names are
 *   LIVE, STORY_SEEN, STORY_UNSEEN and STORY_UNSEEN_CLOSE_FRIEND, and stores it without a null check.
 *   The two renders that test for a profile ring (they hold both ring test tags) build it, the
 *   profile header (LX/0cXF;) once and the shared profile picture with ring (LX/0Duw;, also drawn
 *   by search and some lists) twice. Right before each build the kind goes through the extension,
 *   which answers null, Facebook's own no-ring value, with the switch on.
 */

internal const val STORY_RINGS_PATCH = "Hide story rings"

internal const val STORY_RINGS = "$EXTENSION_PACKAGE/stories/StoryRings;"
internal const val FEED_RING = "$STORY_RINGS->feedRing(I)Z"
internal const val HIDE_COMMENT_RING = "$STORY_RINGS->hideCommentRing()Z"
internal const val PROFILE_RING = "$STORY_RINGS->profileRing(Ljava/lang/Object;)Ljava/lang/Object;"

internal const val GRAPHQL_ACTOR = "Lcom/facebook/graphql/model/GraphQLActor;"

/** The names the feed's two ring components give themselves. */
internal const val STORY_RING_COMPONENT = "FeedStoryRingComponent"
internal const val LIVE_RING_COMPONENT = "FeedLiveRingComponent"

/** The comment story ring plugin's name, which the comment row builder holds. */
internal const val STORY_RING_PLUGIN =
    "com.facebook.feedback.comments.plugins.actorprofilepicture.ring.impl.storyring.StoryRingPlugin"

/** Facebook's design system profile photo, which keeps its name. */
internal const val PROFILE_PHOTO = "Lcom/facebook/fds/profilephoto/FDSProfilePhoto;"

/** The test tags a profile render gives its picture with and without a ring. */
internal val PROFILE_RING_TAGS = listOf("profile_photo_unseen_story_ring_", "profile_photo_no_story_ring")

/**
 * The ring kinds' names, which the ring enum's static initializer must all hold. Facebook's color
 * enum, which the photo also takes, has its own LIVE, STORY_SEEN and STORY_UNSEEN.
 */
internal val RING_NAMES = listOf("LIVE", "STORY_SEEN", "STORY_UNSEEN", "STORY_UNSEEN_CLOSE_FRIEND")

/** The most constants a ring kind enum has, room for a kind or two more than today's four. */
internal const val RING_KINDS_AT_MOST = 8

/**
 * Takes the story and LIVE rings off profile pictures in the feed, in comments and on profiles,
 * with a switch for each (#397).
 *
 * In the default selection with every switch off, so nothing changes until one is turned on.
 */
@Suppress("unused")
val hideStoryRingsPatch = bytecodePatch(
    name = "Hide story rings",
    description = "Switches that take the story and LIVE rings off profile pictures at the top of posts, next to " +
        "comments and on profiles, one for each. All start off. Turn them on in Hushfacebook settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("storyRings")
        hideStoryRings()
        enableStatus("storyRings")
    }
}

internal fun refuse(detail: String): Nothing = throw PatchException("$STORY_RINGS_PATCH: $detail")

private fun Method.code() = implementation?.instructions?.toList() ?: emptyList()

private fun Method.parameterNames() = parameterTypes.map { it.toString() }

private fun MethodReference.parameterNames() = parameterTypes.map { it.toString() }

/** Whether [method] is a feed header decorator's check of whether it applies. */
internal fun isRingCheck(method: Method): Boolean =
    method.returnType == "Z" && !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
        method.parameterNames().takeLast(3) == listOf(GRAPHQL_ACTOR, "Z", "Z")

/** The types [method] creates. */
internal fun createdTypes(method: Method): Set<String> = method.code()
    .filter { it.opcode == Opcode.NEW_INSTANCE }
    .map { ((it as ReferenceInstruction).reference as TypeReference).type }
    .toSet()

/** The static boolean methods taking the actor that [method] calls, each once. */
internal fun actorChecksCalled(method: Method): List<MethodReference> = method.code()
    .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
    .map { (it as ReferenceInstruction).reference as MethodReference }
    .filter { it.returnType == "Z" && GRAPHQL_ACTOR in it.parameterNames() }
    .distinctBy { it.toString() }

/** Whether [classDef] is a feed header decorator building one of [components]. */
internal fun isDecoratorOf(classDef: ClassDef, components: Set<String>): Boolean =
    classDef.methods.any(::isRingCheck) && classDef.methods.any { method -> createdTypes(method).any { it in components } }

/** Whether [classDef] is the ring kind enum. */
internal fun isRingEnum(classDef: ClassDef): Boolean =
    classDef.superclass == "Ljava/lang/Enum;" &&
        classDef.staticFields.count { it.type == classDef.type } in RING_NAMES.size..RING_KINDS_AT_MOST &&
        classDef.methods.any { method -> method.name == "<clinit>" && RING_NAMES.all { holdsString(method, it) } }

/** The types of the classes whose [where] (`<init>` or `<clinit>`) loads [name]. */
private fun BytecodePatchContext.componentTypes(name: String, where: String): Set<String> =
    classDefByStrings(name, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }
        .filter { classDef -> classDef.methods.any { it.name == where && holdsString(it, name) } }
        .map { it.type }
        .toSet()
        .ifEmpty { refuse("no class names itself $name") }

/** The check of the one feed header decorator building one of [components]. */
private fun BytecodePatchContext.decoratorCheck(components: Set<String>, what: String): Method {
    val found = mutableListOf<ClassDef>()
    classDefForEachReferencing(types = components) { classDef ->
        if (isDecoratorOf(classDef, components) && found.none { it.type == classDef.type }) found += classDef
    }
    val decorator = found.singleOrNull() ?: refuse("expected one feed header decorator building the $what, found ${found.size}")
    return decorator.methods.singleOrNull(::isRingCheck) ?: refuse("${decorator.type} has more than one ring check")
}

/** The two statics the feed asks about a header picture's ring: the story one, then the LIVE one. */
internal fun BytecodePatchContext.feedRingChecks(): List<MethodReference> {
    val storyCheck = decoratorCheck(componentTypes(STORY_RING_COMPONENT, "<init>"), "story ring")
    val liveCheck = decoratorCheck(componentTypes(LIVE_RING_COMPONENT, "<clinit>"), "LIVE ring")
    val story = actorChecksCalled(storyCheck).filter { it.parameterNames() == listOf(GRAPHQL_ACTOR, "Z", "Z") }
        .singleOrNull() ?: refuse("the story ring check asks no single (actor, flag, flag) static")
    val live = actorChecksCalled(liveCheck).singleOrNull()
        ?: refuse("the LIVE ring check asks ${actorChecksCalled(liveCheck).size} statics about the actor, not one")
    if (live.toString() == story.toString()) refuse("the story and LIVE ring checks ask the same static")
    return listOf(story, live)
}

/** The ring kind enum: the one argument of the profile photo's constructor that is it. */
internal fun BytecodePatchContext.ringEnum(): String {
    val photo = classDefByOrNull(PROFILE_PHOTO) ?: refuse("$PROFILE_PHOTO isn't in this build")
    val constructor = photo.methods.singleOrNull { it.name == "<init>" } ?: refuse("$PROFILE_PHOTO has more than one constructor")
    val found = constructor.parameterNames().filter { type -> classDefByOrNull(type)?.let(::isRingEnum) == true }
    return found.singleOrNull()
        ?: refuse("$PROFILE_PHOTO's constructor takes ${found.size} ring kinds, not one: ${found.distinct().joinToString()}")
}

/** The one constructor of [type] taking something and then a ring kind, or null. */
private fun BytecodePatchContext.ringStateConstructor(type: String, ring: String): Method? {
    val classDef = classDefByOrNull(type) ?: return null
    val constructor = classDef.methods.singleOrNull { it.name == "<init>" } ?: return null
    val parameters = constructor.parameterNames()
    return constructor.takeIf { parameters.size == 2 && parameters[0].startsWith("L") && parameters[1] == ring }
}

/**
 * The comment row builder's ring state helper, with the ring state's constructor. 586 also names the
 * plugin in a table of plugin names (LX/0AZU;->A00), which has no ring state helper, so the holder is
 * the one class holding the name that has one. 586 also moved the helper out of the builder (arm64
 * LX/01xJ;->A0L, a class of merged statics, answering LX/0Caa;): the builder's method naming the
 * plugin (LX/0AfM;->A0B) calls it, so a holder without a helper of its own counts the one it calls.
 */
internal fun BytecodePatchContext.commentRingState(ring: String): Pair<Method, Method> {
    val holders = classDefByStrings(STORY_RING_PLUGIN, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    fun isHelper(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
        method.parameterTypes.size == 1 && method.returnType.startsWith("L") && ringStateConstructor(method.returnType, ring) != null
    fun calledHelpers(holder: ClassDef) = holder.methods.filter { holdsString(it, STORY_RING_PLUGIN) }
        .flatMap { it.code() }.filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .map { (it as ReferenceInstruction).reference as MethodReference }.distinctBy { it.toString() }
        .mapNotNull { call ->
            classDefByOrNull(call.definingClass)?.methods?.firstOrNull { method ->
                method.name == call.name && method.returnType == call.returnType &&
                    method.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
            }?.takeIf(::isHelper)
        }
    val withHelpers = holders.map { holder -> holder to holder.methods.filter(::isHelper).ifEmpty { calledHelpers(holder) } }
        .filter { (_, helpers) -> helpers.isNotEmpty() }
    val (holder, helpers) = withHelpers.singleOrNull()
        ?: refuse("expected one class holding the comment story ring plugin with a ring state helper, found ${withHelpers.size} of ${holders.size}")
    val helper = helpers.singleOrNull() ?: refuse("${holder.type} has ${helpers.size} ring state helpers, not one")
    return helper to ringStateConstructor(helper.returnType, ring)!!
}

/** The renders that test for a profile ring, by the tags they give the picture. */
internal fun BytecodePatchContext.profileRingRenders(): List<Pair<ClassDef, Method>> =
    classDefByStrings(PROFILE_RING_TAGS.last(), StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef ->
            classDef.methods.filter { method -> PROFILE_RING_TAGS.all { holdsString(method, it) } }.map { classDef to it }
        }
        .ifEmpty { refuse("no render tests for a profile ring") }

private fun Method.reference() = "$definingClass->$name(${parameterNames().joinToString("")})$returnType"

/** Finds everything first, so a build without one of the parts refuses before any code changes. */
internal fun BytecodePatchContext.hideStoryRings() {
    val feedChecks = feedRingChecks()
    val ring = ringEnum()
    val (helper, ringState) = commentRingState(ring)
    val renders = profileRingRenders()
    val photo = classDefByOrNull(PROFILE_PHOTO)!!.methods.single { it.name == "<init>" }
    val photoParameters = photo.parameterNames()
    if (photoParameters.count { it == ring } != 1) refuse("$PROFILE_PHOTO takes the ring kind more than once")
    val ringIndex = photoParameters.indexOf(ring)
    // this, then a register per argument before the ring and a second for each long or double.
    val ringOffset = 1 + photoParameters.take(ringIndex).sumOf { if (it == "J" || it == "D") 2 else 1 }
    val photoReference = photo.reference()
    val sites = renders.map { (classDef, render) ->
        val at = render.code().withIndex().filter { (_, instruction) ->
            instruction.opcode == Opcode.INVOKE_DIRECT_RANGE &&
                (instruction as ReferenceInstruction).reference.toString() == photoReference
        }.map { it.index }
        if (at.isEmpty()) refuse("${classDef.type}->${render.name} tests for a ring but builds no profile photo")
        Triple(classDef, render, at)
    }

    for (check in feedChecks) {
        val method = mutableClassDefBy(check.definingClass).methods.singleOrNull {
            it.name == check.name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
                it.parameterNames() == check.parameterNames()
        } ?: refuse("$check isn't a static of its class")
        method.filterBooleanReturns(STORY_RINGS_PATCH, FEED_RING)
    }

    val mutableHelper = mutableClassDefBy(helper.definingClass).findMutableMethodOf(helper)
    mutableHelper.requireLocals(STORY_RINGS_PATCH, 2)
    mutableHelper.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_COMMENT_RING
            move-result v0
            if-eqz v0, :facebooks_ring
            new-instance v1, ${helper.returnType}
            const/4 v0, 0x0
            invoke-direct { v1, v0, v0 }, ${ringState.reference()}
            return-object v1
        """,
        ExternalLabel("facebooks_ring", mutableHelper.getInstruction(0)),
    )

    for ((classDef, render, at) in sites) {
        val method = mutableClassDefBy(classDef.type).findMutableMethodOf(render)
        for (index in at.asReversed()) {
            val register = method.getInstruction<RegisterRangeInstruction>(index).startRegister + ringOffset
            method.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static/range { v$register .. v$register }, $PROFILE_RING
                    move-result-object v$register
                    check-cast v$register, $ring
                """,
            )
        }
    }
}
