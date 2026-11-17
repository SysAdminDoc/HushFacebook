/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.aidetected

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.ads.sponsoredreels.dropItemFirst
import app.morphe.patches.facebook.ads.sponsoredreels.filterPageFirst
import app.morphe.patches.facebook.ads.sponsoredreels.filterSectionsFirst
import app.morphe.patches.facebook.ads.sponsoredreels.reelPages
import app.morphe.patches.facebook.ads.sponsoredreels.toBinaryName
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.requireStoryFlagReaders
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The extension class that reads the feed's flag, and its accessor this patch fills in. */
internal const val GEN_AI_LABEL = "$EXTENSION_PACKAGE/feed/GenAiLabel;"
internal const val DETECTED_INFO_STUB = "detectedInfo"
internal const val SELF_DISCLOSURE_INFO_STUB = "selfDisclosureInfo"

/** The extension class that filters the Reels pages, its finder stub this patch fills in, and its two page filters. */
internal const val GEN_AI_REEL_FILTER = "$EXTENSION_PACKAGE/feed/GenAiReelFilter;"
internal const val ATTRIBUTION_STUB = "transparencyAttribution"
private const val REEL_FILTER = "$GEN_AI_REEL_FILTER->withoutAiReels(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;"
private const val REEL_SECTION_FILTER = "$GEN_AI_REEL_FILTER->withoutAiSections(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"
private const val ANNOUNCED_REEL_FILTER = "$GEN_AI_REEL_FILTER->withoutAnnouncedAiReels(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;"

private const val PATCH = "Hide AI-detected posts"

/**
 * Hides feed posts, reels and Watch videos that Facebook's own detection marked as made with AI.
 *
 * The feed rule runs in the shared feed guard like every other feed rule, so `addNewEdgeToCollection`
 * still carries one Hushfacebook guard. What this patch adds there is the one read the guard can't
 * make by name: GraphQLStory's accessor of the detected-AI info model, which Redex renames every
 * build. It is found by the two schema keys it loads (see Fingerprints.kt), held to the model
 * GenAiTransparencyPlugin reads the flag on, and written into `GenAiLabel.detectedInfo`, which
 * answers a marker until then. Everything else, the flag included, the extension reads through
 * members Facebook keeps.
 *
 * The same plugin reads a second flag beside it, `was_self_disclosed_as_ai_generated` on the
 * story's self-disclosure model, and shows the label on that flag alone: that's a post its creator
 * labelled as AI. Its accessor is found and held to the plugin the same way and written into
 * `GenAiLabel.selfDisclosureInfo`, for the opt-in switch that hides those posts too.
 *
 * A fifth switch takes out posts featuring one of Meta's AI characters. Facebook asks for such a
 * post's attachment style through a finder of its own, and this patch writes GraphQLStory's
 * attachments accessor and a call of that finder into `AiCharacterPosts` (see AiCharacterAnchors.kt).
 * That rule is the newest and least needed here, so a build whose anchors moved only loses it: the
 * patch log says why, the stubs stay unfilled, and Hook status names what's missing while its switch
 * is on.
 *
 * The Reels rule runs where a fetched page enters the Reels and Watch item collection, on the same
 * three methods the sponsored reels filter runs on, each prepended with a call of its own; the two
 * patches work alone or together. What this patch adds there is Facebook's own finder of the
 * attribution the Reels viewer draws its AI label from, found by the type name literal it is
 * handed (see ReelLabel.kt) and written into `GenAiReelFilter.transparencyAttribution`. The flag
 * on that attribution the extension reads through `TreeJNI.getBooleanValue`, the kept reader the
 * viewer's own label decision calls, and the patch stops if that decision no longer does. The
 * Reels tab's own items keep the model and the story in a holder rather than a field of their
 * own, and for those the extension reads `ai_generated_detected_info` by its key through
 * `TreeJNI.getTree(int)`, which the patch requires too (see ReelLabel.kt).
 *
 * Every switch but the Meta AI cards' starts off. Nobody has yet recorded a signed-in feed with one
 * AI-labeled post and one ordinary post beside it, a Reels feed with an AI-labelled reel or a post
 * featuring an AI character, and until someone does, each rule waits to be turned on. The cards are
 * Facebook's own promotion, not anyone's post, so that switch starts on.
 */
@Suppress("unused")
val hideAiDetectedPostsPatch = bytecodePatch(
    name = "Hide AI-detected posts",
    description = "Removes posts and reels Facebook marked as made with AI, posts creators labeled as AI, posts " +
        "with Meta's AI characters and Meta AI cards in the feed. Only the Meta AI cards switch starts on. " +
        "Change them in Hushfacebook settings > News feed and Reels and Watch.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch)
    dependsOn(feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val story = classDefBy(GRAPHQL_STORY)
        val accessors = detectedInfoAccessors(story)
        val accessor = accessors.singleOrNull() ?: throw PatchException(
            "GraphQLStory has ${accessors.size} accessors of $DETECTED_INFO_FIELD as $DETECTED_INFO_TYPE, " +
                "expected one: ${accessors.joinToString { it.name }}",
        )

        // Facebook's own label reads the flag on this model. If it stops doing that, the flag may
        // have moved or changed meaning, and a rule that guesses could hide the wrong posts.
        val plugin = classDefByOrNull(GEN_AI_TRANSPARENCY_PLUGIN)
            ?: throw PatchException("GenAiTransparencyPlugin is gone, so nothing shows which flag Facebook's AI label reads")
        if (plugin.methods.none { readsDetectedFlag(it, accessor) }) {
            throw PatchException(
                "GenAiTransparencyPlugin no longer reads $DETECTED_FLAG through GraphQLStory.${accessor.name}()",
            )
        }

        // The creator's own label: the plugin shows it on this flag alone. Held to the plugin the
        // same way, so a flag that moved or changed meaning stops the patch instead of guessing.
        val selfLabels = selfDisclosureInfoAccessors(story)
        val selfLabel = selfLabels.singleOrNull() ?: throw PatchException(
            "GraphQLStory has ${selfLabels.size} accessors of $SELF_DISCLOSURE_INFO_FIELD as " +
                "$SELF_DISCLOSURE_INFO_TYPE, expected one: ${selfLabels.joinToString { it.name }}",
        )
        if (plugin.methods.none { readsSelfDisclosedFlag(it, selfLabel) }) {
            throw PatchException(
                "GenAiTransparencyPlugin no longer reads $SELF_DISCLOSED_FLAG through GraphQLStory.${selfLabel.name}()",
            )
        }

        // The extension reads the flags and the models' type tags through these, by reflection.
        requireStoryFlagReaders()
        fillStoryModelStub(GEN_AI_LABEL, DETECTED_INFO_STUB, accessor)
        fillStoryModelStub(GEN_AI_LABEL, SELF_DISCLOSURE_INFO_STUB, selfLabel)

        try {
            hideAiCharacterPosts(story)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the AI character posts rule.")
        }
        hideReels()

        enableStatus("aiDetectedPosts")
    }
}

/**
 * Hide AI character posts' rule for posts that carry an AI character: GraphQLStory's attachments
 * accessor and Facebook's finder of an attachment's style, written into AiCharacterPosts' two stubs.
 * The finder is the one static method the style's literal is handed to, held to the shape and the
 * style_infos read AiCharacterAnchors.kt describes, so a finder that changed throws here rather than
 * the rule guessing, and both stubs are filled only once everything has been found.
 */
private fun BytecodePatchContext.hideAiCharacterPosts(story: ClassDef) {
    val attachmentLists = attachmentsAccessors(story)
    val attachments = attachmentLists.singleOrNull() ?: throw PatchException(
        "$PATCH: GraphQLStory has ${attachmentLists.size} accessors of $ATTACHMENTS_FIELD as $ATTACHMENT_TYPE, " +
            "expected one: ${attachmentLists.joinToString { it.name }}",
    )
    val styleLists = styleInfosAccessors(classDefBy(GRAPHQL_STORY_ATTACHMENT))
    val styleInfos = styleLists.singleOrNull() ?: throw PatchException(
        "$PATCH: GraphQLStoryAttachment has ${styleLists.size} accessors of $STYLE_INFOS_FIELD as $STYLE_INFO_TYPE, " +
            "expected one: ${styleLists.joinToString { it.name }}",
    )

    val holders = classDefByStrings(AI_CHARACTER_STYLE, StringComparisonType.EQUALS)
        .flatMap { methodsHolding(it, AI_CHARACTER_STYLE) }
    val found = attributionFinder(holders, AI_CHARACTER_STYLE)
    val finder = found.call ?: throw PatchException("$PATCH: ${found.problem}")
    val finderClass = classDefByOrNull(finder.definingClass)
        ?: throw PatchException("$PATCH: ${finder.definingClass}, the style finder's class, isn't in this build")
    val finderMethod = resolveStatic(finderClass, finder)
        ?: throw PatchException("$PATCH: ${finder.definingClass} declares no static ${finder.name} the style is handed to")
    if (!isStyleFinder(finderMethod, finderClass, styleInfos)) {
        throw PatchException(
            "$PATCH: ${finder.definingClass}->${finder.name} isn't a public finder walking " +
                "GraphQLStoryAttachment.${styleInfos.name}() by getTypeName(), so it isn't the style finder",
        )
    }

    fillStoryModelStub(AI_CHARACTER_POSTS, ATTACHMENTS_STUB, attachments)
    fillFinderStub(AI_CHARACTER_POSTS, STYLE_INFO_STUB, finder)
}

/** The Reels and Watch side: the finder stub, and the page filters at both levels a page enters. */
private fun BytecodePatchContext.hideReels() {
    val holders = classDefByStrings(TRANSPARENCY_ATTRIBUTION, StringComparisonType.EQUALS)
        .flatMap { methodsHolding(it, TRANSPARENCY_ATTRIBUTION) }.toMutableList()
    // A string table holding the literal can hand it to the finder instead (582's armeabi-v7a
    // build does), so the methods asking one for it are holders too.
    val entries = tableEntries(holders.filter(::isStringTable), TRANSPARENCY_ATTRIBUTION)
    val resolve = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    if (entries.isNotEmpty()) {
        // An asker calls one of the tables.
        classDefForEachReferencing(types = entries.keys.map { it.substringBefore("->") }) { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
            classDef.methods.filterTo(holders) { asksTableFor(it, entries) }
        }
    }
    val found = attributionFinder(holders, resolve = resolve)
    val finder = found.call ?: throw PatchException("$PATCH: ${found.problem}")
    val finderMethod = classDefByOrNull(finder.definingClass)?.let { resolveStatic(it, finder) }
        ?: throw PatchException("$PATCH: ${finder.definingClass} declares no static ${finder.name} the literal is handed to")
    if (!isAttributionFinder(finderMethod)) {
        throw PatchException(
            "$PATCH: ${finder.definingClass}->${finder.name} doesn't pick an attribution by getTypeName(), so it isn't the finder",
        )
    }
    val modelType = finder.parameterTypes[0].toString()
    val attributionType = finder.returnType

    // Facebook's own label decision reads the detected flag on the attribution the finder answers.
    // If it stops doing that, the flag may have moved, and a rule that guesses could hide the
    // wrong reels.
    var reads = false
    // The decision takes the attribution.
    classDefForEachReferencing(types = listOf(attributionType)) { classDef ->
        if (!reads && classDef.methods.any { readsReelDetectedFlag(it, attributionType, modelType) }) reads = true
    }
    if (!reads) {
        throw PatchException(
            "$PATCH: no reel label decision reads $DETECTED_FLAG on $attributionType through TreeJNI.$TREE_BOOLEAN_READER",
        )
    }

    // The extension reads the flag through these, by reflection.
    requireTreeReaders()
    fillFinderStub(GEN_AI_REEL_FILTER, ATTRIBUTION_STUB, finder)

    // The page filters, at both levels a page enters, with the reel model's class beside each so
    // the extension knows which of an item's fields holds the model.
    val pages = reelPages(PATCH)
    val model = modelType.toBinaryName()
    pages.pageInserts.forEach { it.filterPageFirst(model, REEL_FILTER, PATCH) }
    pages.announcePage.filterPageFirst(model, ANNOUNCED_REEL_FILTER, PATCH)
    pages.itemInsert.dropItemFirst(model, REEL_FILTER, PATCH)
    pages.addPage.filterSectionsFirst(model, REEL_SECTION_FILTER, PATCH)
}
