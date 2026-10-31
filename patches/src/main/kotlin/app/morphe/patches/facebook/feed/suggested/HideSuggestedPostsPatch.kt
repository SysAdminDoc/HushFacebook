/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidesuggested/HideSuggestedPostsPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: the instance-of chain moved into the extension's
 * feed filter, behind the shared feed hook. The patch still refuses a build that carries none of
 * the unit classes, so a rename fails at patch time instead of filtering nothing, and names each
 * one a build lacks in the patch log. It also finds the
 * story's recommendation flag, held to Facebook's own filter, the People you may know and
 * suggested groups type names, and the flag Facebook's own Discover unit reads to tell a row of
 * Stories you might like from other rows of Stories, which four more switches read. The People you
 * may know switch also reaches the carousel on your own profile, through a hook in that section's
 * children builder. It also fills the two accessors the switch for posts from groups you're not in
 * reads: a story's group and that group's join state. On the search screen, before anything is
 * typed, it hands each row to the extension, which draws the For you section or People you may know
 * as an empty row while its search switch is on, and names every row for Debug logging. Under a
 * post's buttons, it lets the extension leave out the Related posts list.
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.DISCOVER_FEED_UNIT_TYPE
import app.morphe.patches.facebook.feed.DISCOVER_UNIT_LAYOUT
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.HIDE_RECOMMENDATIONS_VALIDATOR
import app.morphe.patches.facebook.feed.RECOMMENDATION_CONTEXT_FIELD
import app.morphe.patches.facebook.feed.RECOMMENDATION_CONTEXT_TYPE
import app.morphe.patches.facebook.feed.RECOMMENDED_FLAG
import app.morphe.patches.facebook.feed.UNCONNECTED_STORIES_FLAG
import app.morphe.patches.facebook.feed.edgePredicateCalls
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.readsRecommendedFlag
import app.morphe.patches.facebook.feed.recommendationContextAccessors
import app.morphe.patches.facebook.feed.requireFeedTypeName
import app.morphe.patches.facebook.feed.requireTaggedFeedTypeName
import app.morphe.patches.facebook.feed.requireStoryFlagReaders
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.unconnectedStoriesAccessors
import app.morphe.patches.facebook.feed.unconnectedStoriesReaders
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.handleTargets
import app.morphe.patches.facebook.misc.extension.javaName
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.search.hookPreSearchHead
import app.morphe.patches.facebook.search.hookPreSearchList
import app.morphe.patches.facebook.search.hookPreSearchQuery
import app.morphe.patches.facebook.search.hookPreSearchSections
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

private const val PATCH = "Hide suggested and promoted posts"

/**
 * Units Facebook injects into the feed that are not paid ads. All keep their real names through
 * Redex. The extension's `FeedFilter.SUGGESTED_UNITS` holds the same list, and a test holds the
 * two to each other.
 */
internal val SUGGESTED_FEED_UNITS = listOf(
    // "Pages you may like" and its variants.
    "Lcom/facebook/graphql/model/GraphQLPagesYouMayLikeFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPaginatedPagesYouMayLikeFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLCreativePagesYouMayLikeFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPYMLWithLargeImageFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPagesYouMayFollowFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPagesYouMayAdvertiseFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPymgfFeedUnit;",
    // Facebook's own in-feed upsell nags.
    "Lcom/facebook/graphql/model/GraphQLQuickPromotionFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLQuickPromotionNativeTemplateFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLEndOfFeedUpsellCustomNTFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLExploreFeedUpsellNTUnit;",
    "Lcom/facebook/graphql/model/GraphQLGreetingCardPromotionFeedUnit;",
    // In-feed prompts and experiment slots.
    "Lcom/facebook/graphql/model/GraphQLStoryGallerySurveyFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLBusinessPageReviewFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLHoldoutAdFeedUnit;",
)

/**
 * The units of [SUGGESTED_FEED_UNITS] Facebook took out between 577 and 582. They stay on the
 * list for a build that brings one back, and the patch log says in one plain line that the
 * declared build goes without them, where a unit missing beyond these is a warning (issue #162).
 * PartialTargetsFixtureTest holds this to the declared build's fixtures.
 */
internal val DROPPED_SUGGESTED_FEED_UNITS = setOf(
    "Lcom/facebook/graphql/model/GraphQLPagesYouMayFollowFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPagesYouMayAdvertiseFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLEndOfFeedUpsellCustomNTFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLGreetingCardPromotionFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLBusinessPageReviewFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLHoldoutAdFeedUnit;",
)

/**
 * The type name the "People you may know" row answers. The extension's
 * `FeedFilter.PEOPLE_YOU_MAY_KNOW_TYPE` matches it.
 */
internal const val PEOPLE_YOU_MAY_KNOW_TYPE = "PaginatedPeopleYouMayKnowFeedUnit"

/**
 * The type name the suggested groups row ("Suggested for you" groups to join, with its "Discover
 * more groups" button) answers. The extension's `FeedFilter.GROUPS_YOU_SHOULD_JOIN_TYPE` matches it.
 *
 * Its model is the People you may know one (`LX/3zk;` in 580, `LX/3zc;` in 577), which answers
 * three type tags: its own literal for People you may know, and a string table entry for this one
 * (tag 0x363babe0, `LX/18Z;->A00(266)` in 580, `LX/19t;->A00(223)` in 577) and for
 * `FriendRequestsFeedUnit`. On 581 (`LX/40I;`) all three are literals of its own. So a literal
 * alone isn't evidence here; the tag's branch is, whichever way it answers.
 */
internal const val GROUPS_YOU_SHOULD_JOIN_TYPE = "GroupsYouShouldJoinFeedUnit"

/** The extension class that reads the recommendation flag, and its accessor this patch fills in. */
internal const val RECOMMENDATION_LABEL = "$EXTENSION_PACKAGE/feed/RecommendationLabel;"
internal const val RECOMMENDATION_CONTEXT_STUB = "recommendationContext"

/** The extension's question in your profile's People you may know section. */
internal const val HIDE_PROFILE_SECTION =
    "$EXTENSION_PACKAGE/feed/ProfileSuggestions;->hideSection(Ljava/lang/Object;)Z"

@Suppress("unused")
val hideSuggestedPostsPatch = bytecodePatch(
    name = "Hide suggested and promoted posts",
    description = "Removes what Facebook adds to the feed besides ads, like \"Suggested for you\" posts, " +
        "\"People you may know\" and suggested groups, so you see more from people you follow. Most of its " +
        "switches are on by default. Change them in Hushfacebook settings > News feed.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch)
    dependsOn(feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireSuggestedUnits()
        requireFeedTypeName(PEOPLE_YOU_MAY_KNOW_TYPE)
        requireTaggedFeedTypeName(GROUPS_YOU_SHOULD_JOIN_TYPE)
        requireUnconnectedStoriesFlag()

        // A "Suggested for you" post is an ordinary story on the wire (ENGAGEMENT, like a friend's),
        // so the rule reads the story's recommendation flag through GraphQLStory's accessor.
        val accessors = recommendationContextAccessors(classDefBy(GRAPHQL_STORY))
        val accessor = accessors.singleOrNull() ?: throw PatchException(
            "GraphQLStory has ${accessors.size} accessors of $RECOMMENDATION_CONTEXT_FIELD as " +
                "$RECOMMENDATION_CONTEXT_TYPE, expected one: ${accessors.joinToString { it.name }}",
        )

        // Facebook's own "hide suggested posts" filter reads the flag through that accessor. If it
        // stops doing that, the flag may have moved or changed meaning, and the rule stops here.
        val validators = classDefByStrings(HIDE_RECOMMENDATIONS_VALIDATOR, StringComparisonType.EQUALS)
            .flatMap { methodsHolding(it, HIDE_RECOMMENDATIONS_VALIDATOR) }
        if (validators.isEmpty()) {
            throw PatchException("No method holds \"$HIDE_RECOMMENDATIONS_VALIDATOR\" any more")
        }
        val predicates = validators.flatMap(::edgePredicateCalls).mapNotNull { call ->
            classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) }
        }
        if (predicates.none { readsRecommendedFlag(it, accessor) }) {
            throw PatchException(
                "Facebook's own recommendation filter no longer reads $RECOMMENDED_FLAG through " +
                    "GraphQLStory.${accessor.name}()",
            )
        }

        // The extension reads the flag and the model's type tag through these, by reflection.
        requireStoryFlagReaders()
        fillStoryModelStub(RECOMMENDATION_LABEL, RECOMMENDATION_CONTEXT_STUB, accessor)

        // The carousel on your own profile is a section of its own, not a feed unit.
        hideProfileSuggestions()
        // The list of more posts under a post opened from a notification is asked for with the post, not a feed unit (#163).
        try {
            hookNotificationFooter()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so posts opened from a notification keep their list of more posts.")
        }
        // The Find friends card where friends' posts run out is part of the end of the feed, not a feed unit (#187).
        try {
            hookFindFriendsCard()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so the end of the feed keeps its Find friends card.")
        }
        // A friend's post in a group you're not in is an ordinary story; its group's join state tells it apart (#195).
        try {
            if (fillGroupJoinStubs().targetGroup == null) {
                patchLog.warning(
                    "GraphQLStory declares no $STORY_TARGET_GROUP_FIELD, so Hide posts from groups you're not in " +
                        "reads a post's group from its $STORY_TARGET_FIELD alone.",
                )
            }
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so Hide posts from groups you're not in keeps every post.")
        }
        // Home asks for that join state only where MobileConfig says so.
        try {
            askForJoinState()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so Home asks for groups' join states only when Facebook does.")
        }
        // A story posted to no profile names its group in its title or an attachment, and Debug logging says which (#195).
        try {
            fillStoryShapeStubs()
        } catch (exception: PatchException) {
            patchLog.warning(
                "${exception.message}, so Hide posts from groups you're not in can't read a group a post's title or " +
                    "attachment names, and Debug logging can't say what a story posted to no profile carries.",
            )
        }
        // A title's group is a bare entity, read again as the class the join state stub takes (#195).
        try {
            fillStoryTargetClassStub()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so Hide posts from groups you're not in can't read a group a post's title names.")
        }
        // A list of only hidden rows loads page after page without end (#235). Before the section hook, which changes the shape this reads.
        try {
            hookPreSearchList()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so the search screen can keep loading while every row is hidden.")
        }
        // The search screen's For you and People you may know rows, and Debug logging's names for every row there (#80).
        try {
            hookPreSearchSections()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so For you and People you may know stay on the search screen.")
        }
        // The first page of those rows has a builder of its own, which the section never sees (#285).
        try {
            hookPreSearchHead()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so For you and People you may know stay on the search screen's first page.")
        }
        // Facebook's older null-state query reads the same rows its own way (#285).
        try {
            hookPreSearchQuery()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so the search screen's rows from Facebook's older query stay as they are.")
        }
        // The Related posts list under a post is a plugin of the socket under its buttons, not a feed unit (#231).
        try {
            hookRelatedPosts()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so Related posts stay under posts.")
        }
        enableStatus("suggestedPosts")
    }
}

/**
 * Checks the build for the suggested feed units the extension hides. Each unit class stands alone:
 * the extension tells them apart by name at run time, so one a build drops is one it can't show.
 * The patch goes on with the ones it finds. The six of [DROPPED_SUGGESTED_FEED_UNITS] get one
 * plain line between them, and the patch log names any other missing one with a warning. None of
 * them stops the patch: that is a renamed model package, not a smaller feed.
 */
internal fun BytecodePatchContext.requireSuggestedUnits() {
    handleTargets(PATCH, "suggested feed unit classes", SUGGESTED_FEED_UNITS, DROPPED_SUGGESTED_FEED_UNITS) { type ->
        if (classDefByOrNull(type) != null) null else "${javaName(type)} isn't in this Facebook build"
    }
}

/** Your profile's People you may know section: its children builder, and the Children type that answers. */
internal class ProfileSuggestionSection(val builder: Method, val children: String)

/**
 * Your profile's People you may know section, held to the evidence the hook rests on: it's the one
 * children builder outside the extension loading [PROFILE_PYMK_SECTION], its class hands that name
 * to the section base class, a superclass declares the kept getLogTag() the extension reads it back
 * through and setChildren taking what the builder answers, and that Children type can be built
 * empty. See ProfileSuggestionAnchors.kt.
 */
internal fun BytecodePatchContext.profileSuggestionSection(): ProfileSuggestionSection {
    val builders = classDefByStrings(PROFILE_PYMK_SECTION, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap(::profileSuggestionBuilders)
    val builder = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one section children builder loading \"$PROFILE_PYMK_SECTION\", found " +
            builders.joinToString { "${it.definingClass}->${it.name}" }.ifEmpty { "none" },
    )
    val section = classDefBy(builder.definingClass)
    if (!namesItself(section)) {
        throw PatchException(
            "$PATCH: ${section.type} doesn't hand \"$PROFILE_PYMK_SECTION\" to its superclass's constructor, " +
                "so it isn't the section of that name",
        )
    }
    val children = builder.returnType
    if (superclasses(section).none { isSectionBase(it, children) }) {
        throw PatchException(
            "$PATCH: no superclass of ${section.type} declares $LOG_TAG() and $SET_CHILDREN($children), so " +
                "${builder.name} doesn't build a section's children",
        )
    }
    val list = classDefByOrNull(children)
    if (list == null || !isChildrenList(list)) {
        throw PatchException(
            "$PATCH: $children has no public constructor taking nothing and $GET_CHILDREN(), so the patch " +
                "can't hand back an empty one",
        )
    }
    return ProfileSuggestionSection(builder, children)
}

/** The superclasses of [classDef] this APK carries, nearest first. */
private fun BytecodePatchContext.superclasses(classDef: ClassDef): List<ClassDef> =
    generateSequence(classDef.superclass?.let(::classDefByOrNull)) { it.superclass?.let(::classDefByOrNull) }.toList()

/** Hooks your profile's People you may know section. */
internal fun BytecodePatchContext.hideProfileSuggestions() {
    val found = profileSuggestionSection()
    mutableClassDefBy(found.builder.definingClass).findMutableMethodOf(found.builder)
        .buildNoChildrenWhenHidden(found.children)
}

/**
 * First thing in the section's children builder: hand the extension the section, and answer an
 * empty [children] when it says the carousel goes. Otherwise the builder runs from its first
 * instruction. v0 is free at index 0, and the range form names `this` wherever it sits.
 */
internal fun MutableMethod.buildNoChildrenWhenHidden(children: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p0 }, $HIDE_PROFILE_SECTION
            move-result v0
            if-eqz v0, :facebook
            new-instance v0, $children
            invoke-direct { v0 }, $children-><init>()V
            return-object v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * Checks the build for what the "Stories you might like" rule reads: a model answering
 * `DiscoverFeedUnit`, and Facebook's own DiscoverUnitComponent reading [UNCONNECTED_STORIES_FLAG]
 * from such a unit. The component's class is renamed, so it's found as the one parameter of its
 * kept layout manager's constructor that reads the flag, itself or (585) through the unit model's
 * `()Z` accessor whose implementation reads it. If Facebook stops reading it there, the flag may
 * have changed meaning, and the patch stops.
 */
internal fun BytecodePatchContext.requireUnconnectedStoriesFlag() {
    requireFeedTypeName(DISCOVER_FEED_UNIT_TYPE)
    val layout = classDefByOrNull(DISCOVER_UNIT_LAYOUT)
        ?: throw PatchException("$DISCOVER_UNIT_LAYOUT isn't in this Facebook build")
    // Why: an accessor's implementations name its interface, so the indexed walk visits only them.
    val flagAccessors = { owners: Set<String> ->
        val found = mutableSetOf<String>()
        classDefForEachReferencing(types = owners) { classDef -> found += unconnectedStoriesAccessors(classDef, owners) }
        found
    }
    val readers = unconnectedStoriesReaders(layout, flagAccessors) { classDefByOrNull(it) }
    if (readers.size != 1) {
        throw PatchException(
            "Facebook's Discover unit component should read $UNCONNECTED_STORIES_FLAG once, found " +
                "${readers.size} readers: ${readers.joinToString { it.type }}",
        )
    }
}
