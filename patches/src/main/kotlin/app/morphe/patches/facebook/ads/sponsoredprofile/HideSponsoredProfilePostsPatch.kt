/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredprofile

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.reels.callsMethod
import app.morphe.patches.facebook.feed.storyModelAccessors
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method

/** The extension class that judges a timeline unit, and the stub this patch fills in. */
internal const val PROFILE_AD_FILTER = "$EXTENSION_PACKAGE/ads/ProfileAdFilter;"
internal const val HIDE = "$PROFILE_AD_FILTER->hide(Ljava/lang/Object;)Z"
internal const val SPONSORED_DATA_STUB = "sponsoredData"

/** Where the render method gets the timeline unit it draws. */
internal sealed interface StoryUnit {
    /** A field of the component, read off `this` (up to 584). */
    class InField(val field: Field) : StoryUnit

    /** One of the render method's own declared parameters, by index (585's stateless component). */
    class InParameter(val index: Int) : StoryUnit
}

/** What the patch found: the render method, where it gets the unit, and the story's accessor. */
internal class TimelineStory(val render: Method, val unit: StoryUnit, val sponsoredData: Method)

/**
 * Leaves an ad out of a profile's timeline: the timeline story component answers no component for
 * a story that carries `sponsored_data`, as it already does for a row it won't draw. See
 * ProfilePostAnchors.kt for how the component and Facebook's own ad test are found. The profile's
 * own posts, and every other row of the profile, are drawn as before.
 */
@Suppress("unused")
val hideSponsoredProfilePostsPatch = bytecodePatch(
    name = "Hide sponsored profile posts",
    description = "Removes the ads between posts on someone's profile or a Page, so you only see what they " +
        "posted. On by default. Turn it off in Hushfacebook settings > News feed.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val story = timelineStory()
        fillStoryModelStub(PROFILE_AD_FILTER, SPONSORED_DATA_STUB, story.sponsoredData)
        mutableRender(story).skipSponsoredStories(story.unit)
        enableStatus("sponsoredProfilePosts")
    }
}

/** The render method [story] found, as the method a patch can add to. */
internal fun BytecodePatchContext.mutableRender(story: TimelineStory): MutableMethod =
    mutableClassDefBy(story.render.definingClass).methods.single {
        it.name == story.render.name && it.returnType == story.render.returnType &&
            it.parameterTypes.map(Any::toString) == story.render.parameterTypes.map(Any::toString)
    }

/**
 * The timeline story component's render method, held to the evidence the rule rests on: it's the
 * one method holding both test tags and the component's name, it asks the story for
 * `sponsored_data` through the one accessor GraphQLStory declares for it, and it gets the unit from
 * exactly one place typed as an interface GraphQLStory implements: a field of its class that it
 * reads, or (585) one of its own parameters.
 */
internal fun BytecodePatchContext.timelineStory(): TimelineStory {
    val renders = classDefByStrings(SPONSORED_TEST_KEY, StringComparisonType.EQUALS)
        .flatMap { methodsHolding(it, SPONSORED_TEST_KEY) }
    if (renders.size != 1) {
        throw PatchException(
            "$PATCH: expected one method holding \"$SPONSORED_TEST_KEY\", found " +
                renders.joinToString { "${it.definingClass}->${it.name}" }.ifEmpty { "none" },
        )
    }
    val render = renders.single()
    if (!isTimelineStoryRender(render)) {
        throw PatchException(
            "$PATCH: ${render.definingClass}->${render.name} isn't the $COMPONENT_NAME render method: it should be an " +
                "instance method answering an object that holds \"$ORGANIC_TEST_KEY\" and \"$COMPONENT_NAME\" too",
        )
    }

    val story = classDefBy(GRAPHQL_STORY)
    val accessors = storyModelAccessors(story, SPONSORED_DATA_FIELD, SPONSORED_DATA_TYPE)
    val accessor = accessors.singleOrNull() ?: throw PatchException(
        "$PATCH: GraphQLStory has ${accessors.size} accessors of $SPONSORED_DATA_FIELD as $SPONSORED_DATA_TYPE, expected one",
    )
    if (!callsMethod(render, accessor)) {
        throw PatchException(
            "$PATCH: ${render.definingClass}->${render.name} no longer asks the story for $SPONSORED_DATA_FIELD " +
                "through GraphQLStory->${accessor.name}()",
        )
    }

    val component = classDefBy(render.definingClass)
    val interfaces = storyInterfaces()
    val fields = unitFields(component, interfaces)
    // 585's component keeps no state, and its render method takes the unit as a parameter instead.
    val parameters = unitParameters(render, interfaces)
    if (fields.size + parameters.size != 1) {
        throw PatchException(
            "$PATCH: expected the unit in one field of ${component.type} or one parameter of " +
                "${render.name}, typed as an interface of GraphQLStory, found fields [${fields.joinToString { it.name }}] " +
                "and parameters [${parameters.joinToString()}]",
        )
    }
    val field = fields.singleOrNull() ?: return TimelineStory(render, StoryUnit.InParameter(parameters.single()), accessor)
    if (!readsField(render, field)) {
        throw PatchException("$PATCH: ${render.definingClass}->${render.name} doesn't read ${component.type}->${field.name}")
    }
    return TimelineStory(render, StoryUnit.InField(field), accessor)
}

/** Every interface GraphQLStory implements, through its superclasses and the interfaces' own. */
private fun BytecodePatchContext.storyInterfaces(): Set<String> {
    val found = mutableSetOf<String>()
    val pending = ArrayDeque<String>()
    var type: String? = GRAPHQL_STORY
    while (type != null) {
        val classDef = classDefByOrNull(type) ?: break
        pending += classDef.interfaces
        type = classDef.superclass
    }
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (found.add(next)) classDefByOrNull(next)?.let { pending += it.interfaces }
    }
    return found
}

/**
 * First thing in the render method: hand the extension the unit this row draws, and answer no
 * component when it says the row goes. Otherwise the render method runs from its first
 * instruction. v0 is free at index 0. For a field, `this` is copied down into it because
 * `iget-object` names its registers in four bits; a parameter is copied with `move-object/from16`,
 * since 585's sits past v15.
 *
 * [judge] is the extension method asked, `(Ljava/lang/Object;)Z`: this patch's, or Hide posts by
 * words' own (#458). Each patch's check goes in whole at index 0 and jumps to whatever was first,
 * so the two run one after the other and neither one's branch points into the other's code.
 */
internal fun MutableMethod.skipSponsoredStories(unit: StoryUnit, judge: String = HIDE) {
    val load = when (unit) {
        is StoryUnit.InField -> {
            val field = unit.field
            if (field.definingClass != definingClass || AccessFlags.STATIC.isSet(accessFlags)) {
                throw PatchException("$PATCH: $definingClass->$name can't read ${field.definingClass}->${field.name} from this")
            }
            "move-object/from16 v0, p0\niget-object v0, v0, ${field.definingClass}->${field.name}:${field.type}"
        }
        is StoryUnit.InParameter -> {
            val type = parameterTypes.getOrNull(unit.index)?.toString()
            if (type == null || !type.startsWith("L")) {
                throw PatchException("$PATCH: $definingClass->$name has no object parameter ${unit.index} to read the unit from")
            }
            "move-object/from16 v0, ${parameterRegister(unit.index)}"
        }
    }
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            $load
            invoke-static { v0 }, $judge
            move-result v0
            if-eqz v0, :render
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("render", getInstruction(0)),
    )
}
