/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/16464b2ba606745de0ebe9cc52f18ef42e6a05d8/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidepostprompts/HidePostPromptsPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: each answer goes through a switch instead of
 * being replaced.
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.filterBooleanReturns
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

internal const val PATCH = "Hide post prompts"

/** The name the bumper component gives itself, in its constructor and one other method. */
internal const val BUMPER_COMPONENT = "NTFeedStoryBumperComponent"

internal const val POST_PROMPTS = "Lapp/morphe/extension/facebook/feed/PostPrompts;"
internal const val KEEP = "$POST_PROMPTS->keep(I)Z"
internal const val SHOW_FOLLOW_LINK = "$POST_PROMPTS->showFollowLink(I)Z"

/**
 * The title text plugin that adds "Follow" (or "Subscribe") after the name in a post's header. The
 * class keeps its name, since Facebook's plugin registry names it by its Java path.
 */
internal const val FOLLOW_TEXT_PLUGIN =
    "Lcom/facebook/feedplugins/plugins/header/titletext/publicfiguregraph/PublicFigureFollowOrSubscribeTextPlugin;"

/** The GraphQL model the plugin's check reads the story from. */
internal const val STORY_MODEL = "Lcom/facebook/graphql/model/GraphQLStory;"

/**
 * The strip Facebook draws on some posts ("Are you interested in this post?", "Show less", who
 * recently commented, follow, chat and post suggestions) goes. On 580 the recently commented one
 * sits above the post's header. Facebook calls it a story bumper, and
 * NTFeedStoryBumperComponent (580 `LX/2Zb;`, 577 `LX/2Xd;`) has one static predicate, (props) -> Z,
 * that answers whether a story has one. The bumper plugin and every row that makes room for a
 * bumper ask it, so a no leaves the post as Facebook draws it without a bumper, gap included. Each
 * of its answers goes through the extension, which turns a yes into a no while the switch is on.
 *
 * The second switch, off by default, takes the "Follow" text out of a header. The title text plugin
 * that adds it (PublicFigureFollowOrSubscribeTextPlugin, a kept class name) has one static check
 * taking the story and answering Z. The header's plugin picker asks it to decide whether the plugin
 * is needed, and the title builder asks it again before appending the text, so a no from the check
 * leaves the title as a header without that plugin builds it. Its answers go through the extension
 * the same way. A collab post and a partnership ad build their own " · Follow" text in two other
 * plugins, and the same switch empties that text (PostFollowText.kt, #396).
 *
 * The third switch, also off by default, takes the numbers off a post's Like, Comment and Share
 * buttons by having their one text builder give its zero answer (PostCounts.kt, #180). The fourth,
 * also off, leaves the reaction faces and the reactions count under a post undrawn, and blanks the
 * count text beside the faces (ReactionPreview.kt, #158). The fifth, also off, takes the X off the top
 * of feed posts by having the check that offers it answer no (PostXButton.kt, #389).
 */
@Suppress("unused")
val hidePostPromptsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide post prompts",
    description = "Removes the strip Facebook adds to some posts, like \"Are you interested in this post?\", " +
        "\"Show less\" or follow suggestions, so posts look less cluttered. No gap is left. On by default. Turn " +
        "it off in Hushfacebook settings > News feed. Five more switches there, Hide the Follow link on posts, " +
        "Hide the Join button on posts, Hide the X on posts, Hide counts on posts and Hide the reaction preview " +
        "on posts, start off.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        hidePromptChecks()
        hidePostJoinButton()
        hidePostX()
        hidePostCounts()
        hideReactionPreview()
        hideReactionCount()
        enableStatus("postPrompts")
    }
}

/** Sends every answer of the bumper check and the follow text check through the extension. */
internal fun BytecodePatchContext.hidePromptChecks() {
    val check = findBumperCheck()
    mutableClassDefBy(check.definingClass).findMutableMethodOf(check).filterBooleanReturns(PATCH, KEEP)
    val follow = findFollowLinkCheck()
    mutableClassDefBy(follow.definingClass).findMutableMethodOf(follow).filterBooleanReturns(PATCH, SHOW_FOLLOW_LINK)
    hidePostFollowTexts()
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Whether [classDef] is the bumper component: its public no-argument constructor loads [BUMPER_COMPONENT]. */
internal fun isBumperComponent(classDef: ClassDef): Boolean = classDef.methods.any {
    it.name == "<init>" && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() &&
        holdsString(it, BUMPER_COMPONENT)
}

/** Whether [method] is the component's has-bumper predicate: static, one argument, answering Z. */
internal fun isBumperCheck(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.parameterTypes.size == 1 &&
        method.implementation != null

/** Whether [method] is the follow text plugin's check: static, taking the story, answering Z. */
internal fun isFollowLinkCheck(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.implementation != null &&
        method.parameterTypes.any { it.toString() == STORY_MODEL }

/** The follow text plugin's check. Changes nothing. */
internal fun BytecodePatchContext.findFollowLinkCheck(): Method {
    val plugin = classDefByOrNull(FOLLOW_TEXT_PLUGIN) ?: refuse("$FOLLOW_TEXT_PLUGIN isn't in this build")
    val checks = plugin.methods.filter(::isFollowLinkCheck)
    return checks.singleOrNull()
        ?: refuse("expected one static (..., story, ...) -> Z check on ${plugin.type}, found ${checks.size}")
}

/** The bumper component's has-bumper predicate. Changes nothing. */
internal fun BytecodePatchContext.findBumperCheck(): Method {
    val components = classDefByStrings(BUMPER_COMPONENT, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter(::isBumperComponent)
    val component = components.singleOrNull()
        ?: refuse("expected one class whose no-argument constructor loads \"$BUMPER_COMPONENT\", found ${components.size}")
    val checks = component.methods.filter(::isBumperCheck)
    return checks.singleOrNull()
        ?: refuse("expected one static (props) -> Z predicate on ${component.type}, found ${checks.size}")
}
