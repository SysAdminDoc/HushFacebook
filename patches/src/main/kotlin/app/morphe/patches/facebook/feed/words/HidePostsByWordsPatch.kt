/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.words

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.BASE_MODEL_WITH_TREE
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.TREE_JNI
import app.morphe.patches.facebook.feed.aidetected.GRAPHQL_STORY_ATTACHMENT
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.hasPublicTypeTag
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

/** The extension class that reads a post's text, and the three accessors this patch fills in. */
internal const val POST_TEXT = "$EXTENSION_PACKAGE/feed/PostText;"
internal const val MESSAGE_STUB = "message"
internal const val ATTACHED_STORY_STUB = "attachedStory"
internal const val ACTION_LINKS_STUB = "actionLinks"

internal const val PATCH = "Hide posts by words"

/**
 * Hides feed posts whose own text holds a word or phrase the person listed, unless it also holds
 * one from their keep list.
 *
 * The rule runs in the shared feed guard like every other feed rule, so `addNewEdgeToCollection`
 * still carries one Hushfacebook guard. What this patch adds there are the two reads the guard
 * can't make by name: GraphQLStory's accessors of its message and of the post a share wraps, which
 * Redex renames every build. Each is found by the two schema keys it loads (see PostTextAnchors.kt)
 * and written into a stub on `PostText`, which answers a marker until then. The message accessor is
 * also held to GraphQLStory's own toString, which labels its text "message.text" and reads it as
 * the field `text`; the extension reads the same field through the kept `getCachedString(int)`.
 *
 * The same patch hides posts from the people, Pages and sites the person lists: GraphQLStory's
 * accessors of its authors and its attachments go into PostSources' two stubs (see
 * PostSourceAnchors.kt), and a build where they can't be found keeps the words and leaves that list
 * reading nothing. Its four switches for kinds of post (photo, video, link and colored background
 * posts) read an attachment's styles and the story's text format through PostTypes' two stubs (see
 * PostTypeAnchors.kt), which a build without them leaves unfilled in the same way. Its reaction
 * ceiling reads a story's feedback and the feedback's reactors through PostReactions' two stubs (see
 * ReactionAnchors.kt), and counts them with the kept `getCachedInt(int)`. Its Hide reposts switch
 * needs nothing more: it asks the shared post accessor whether a story wraps another, and reads
 * no text. Its Hide posts from Pages switch (#262) reads the authors stub too, and asks each
 * author's kept `getTypeName()` whether it's a Page. Its Hide posts in other languages switch
 * (#382) reads the same text as the word filter and asks the phone's own text classifier for each
 * text's language, so it needs no stub of its own.
 *
 * The lists live in Hushfacebook's settings on the phone. Nothing here reads a post until a
 * switch is on and its list has something in it, and nothing of the text, the names or the lists
 * goes into a log, the diagnostic report or a request.
 */
@Suppress("unused")
val hidePostsByWordsPatch = bytecodePatch(
    name = "Hide posts by words",
    description = "Hides feed posts with words you list, or from people, Pages or sites you list, so you skip " +
        "topics you're tired of. Photo, video, link and colored background posts, every post a Page wrote, or " +
        "posts in languages you don't read can go too. Starts off. Turn it " +
        "on in Hushfacebook settings > News feed.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch)
    dependsOn(feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val story = classDefBy(GRAPHQL_STORY)
        val message = messageAccessor(story)
        val attached = attachedStoryAccessors(story).singleOrNull() ?: throw PatchException(
            "$PATCH: GraphQLStory has ${attachedStoryAccessors(story).size} accessors of $ATTACHED_STORY_FIELD " +
                "as $STORY_TYPE, expected one",
        )

        // Found before anything is filled in. A build without them keeps the rest of the patch.
        val types = try {
            findPostTypeAccessors(story)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the kinds of post.")
            null
        }

        // The reaction ceiling is optional in the same way: a build without it keeps everything else.
        val reactions = try {
            findReactionAccessors(story)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the reaction ceiling.")
            null
        }

        // The extension reads the text and the model's type tag through these, by reflection.
        if (!hasPublicStringReader(classDefBy(BASE_MODEL_WITH_TREE))) {
            throw PatchException("$PATCH: BaseModelWithTree has no public getCachedString(int)")
        }
        if (!hasPublicTypeTag(classDefBy(TREE_JNI))) {
            throw PatchException("$PATCH: TreeJNI has no public int mTypeTag")
        }

        fillStoryModelStub(POST_TEXT, MESSAGE_STUB, message)
        fillStoryModelStub(POST_TEXT, ATTACHED_STORY_STUB, attached)
        // A link card's headline on 584 (#255). A build without it still reads the card's own title.
        val actionLinks = classDefByOrNull(GRAPHQL_STORY_ATTACHMENT)?.let(::actionLinksAccessors).orEmpty()
        val actionLinksAccessor = actionLinks.singleOrNull()
        if (actionLinksAccessor != null) {
            fillStoryModelStub(POST_TEXT, ACTION_LINKS_STUB, actionLinksAccessor)
        } else {
            patchLog.warning(
                "$PATCH: GraphQLStoryAttachment has ${actionLinks.size} accessors of $ACTION_LINKS_FIELD as " +
                    "$ACTION_LINK_TYPE, expected one. The patch goes on without link card headlines from them.",
            )
        }
        try {
            fillPostSourceStubs(story)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the people, Pages and sites list.")
        }
        if (types != null) fillPostTypeStubs(types)
        if (reactions != null) fillReactionStubs(reactions)
        enableStatus("postWords")
    }
}

/**
 * GraphQLStory's one accessor of its message, held to the kept model class it answers and to
 * Facebook's own reading of it in toString, through a static reader or inline (584). If toString
 * stops calling it "message.text" or stops reading the `text` field of it, the words may have
 * moved, and a rule that guesses could hide the wrong posts, so the patch stops.
 */
private fun BytecodePatchContext.messageAccessor(story: ClassDef): Method {
    val accessors = messageAccessors(story)
    val accessor = accessors.singleOrNull() ?: throw PatchException(
        "$PATCH: GraphQLStory has ${accessors.size} accessors of $MESSAGE_FIELD as $TEXT_WITH_ENTITIES_TYPE, " +
            "expected one: ${accessors.joinToString { it.name }}",
    )
    if (accessor.returnType != GRAPHQL_TEXT_WITH_ENTITIES) {
        throw PatchException("$PATCH: GraphQLStory.${accessor.name}() answers ${accessor.returnType}, not GraphQLTextWithEntities")
    }
    val textModel = classDefByOrNull(GRAPHQL_TEXT_WITH_ENTITIES)
        ?: throw PatchException("$PATCH: GraphQLTextWithEntities is gone")
    if (!answersTextType(textModel)) {
        throw PatchException("$PATCH: GraphQLTextWithEntities no longer answers $TEXT_WITH_ENTITIES_TYPE as its type")
    }

    val toString = storyToString(story) ?: throw PatchException("$PATCH: GraphQLStory has no toString()")
    val readers = messageTextReaders(toString, accessor).mapNotNull { call ->
        classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) }
    }
    if (readers.none(::readsTextField) && !readsMessageTextInline(toString, accessor)) {
        throw PatchException(
            "$PATCH: GraphQLStory.toString() no longer reads \"$MESSAGE_TEXT_LABEL\" as the $TEXT_FIELD of " +
                "${accessor.name}()",
        )
    }
    return accessor
}
