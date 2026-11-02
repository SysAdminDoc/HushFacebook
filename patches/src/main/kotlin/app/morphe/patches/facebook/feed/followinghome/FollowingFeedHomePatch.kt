/*
 * Forked from:
 * https://github.com/ArunTS96/FroggoMorphePatches/blob/319cde236e373ff8feb66fa30e99028e3d9dff6e/patches/src/main/kotlin/app/froggo/patches/facebook/ads/Facebook573AdsPatch.kt
 * Copyright 2026 T.s. Arun (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: only the Following feed swap of its
 * fix/following-feed-home branch is taken. The builder is found by a literal it keeps instead of
 * 573's obfuscated names, the feed types by the names they keep, and the swap is the extension's,
 * behind a switch. Home is swapped to the most recent feed instead of the Following feed, whose
 * style Facebook's servers no longer honour for every account.
 */
package app.morphe.patches.facebook.feed.followinghome

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Following feed on Home"

/** The trace section the news feed parameter builder opens before it sets the feed's style. */
internal const val PAGED_NEWSFEED_PARAMS = "Companion.setPagedNewsfeedParams"

/** Facebook's feed type. Redex keeps the name, and each feed type's own name. */
internal const val HOME_FEED_TYPE_CLASS = "Lcom/facebook/api/feedtype/FeedType;"

/** The name Home's ranked feed type keeps, the one the extension swaps. */
internal const val HOME_FEED = "top_stories"

/** The name the most recent feed's type keeps, the one it's swapped for. */
internal const val MOST_RECENT_FEED = "most_recent"

/** The feed style the builder gives [MOST_RECENT_FEED], the one the Feeds tab's All gets. */
internal const val MOST_RECENT_STYLE = "MOST_RECENT_FEED_DEFAULT"

internal const val FOLLOWING_HOME = "$EXTENSION_PACKAGE/feed/FollowingHome;"
internal const val FEED_TYPE_ASKED = "$FOLLOWING_HOME->feedType(Ljava/lang/Object;)Ljava/lang/Object;"

/** The trace section the feed query method opens once it builds the feed's own query, past the async ad query. */
internal const val FEED_QUERY_INNER = "FetchNewsFeedMethod.getGraphQlQueryInner"

/** Facebook's GraphQL parameter set, which keeps its name. */
internal const val PARAM_SET_CLASS = "Lcom/facebook/graphql/query/GraphQlQueryParamSet;"

internal const val FEED_REQUEST_SENT = "$EXTENSION_PACKAGE/feed/FeedRequestParams;->sent(Ljava/lang/Object;)V"

/**
 * Has Home ask for the newest posts from the friends, groups and Pages you follow, the feed the
 * Feeds tab's All shows, instead of the ranked one.
 *
 * Facebook's NewsFeedQueryParamsPreparer builds every news feed request's parameters, and one of
 * its static helpers, handed the feed type second, sets the query's feed style from it:
 * MOST_RECENT_FEED_DEFAULT for the most recent feed types, FAVORITES_FEED and the rest for the
 * Feeds tab's other filters, FOLLOWING_FEED for the Following feed's type, and Home's own ordering
 * for Home's. It's the one method that opens the "Companion.setPagedNewsfeedParams" trace section
 * (581 `LX/1bb;->A02`), and the preparer's prepare method is its one caller. Every later read of
 * the feed type in it, the call it ends on included, reads the parameter, so a feed type put there
 * first is the one the whole request is built for.
 *
 * The patch hands the parameter to the extension's FollowingHome first thing and keeps its answer
 * there. While the switch is on, Home's feed type ("top_stories") comes back as the most recent
 * feed's ("most_recent"), both public constants of FeedType the extension finds by those names.
 * Every other feed type comes back as it was. Froggo's swap answered the Following feed's type
 * ("following_feed"), but on the S22 test account (581, 2026-10-08) Facebook answered that request
 * with the ranked feed, suggestions and all.
 *
 * 585 inlined the static helper into the preparer's own instance method (arm64 `LX/01bb;->A01`,
 * still handed the feed type second), which reads the feed type first right after it opens the
 * trace section, so the swap at its start still lands before every read.
 *
 * With debug logging on, the parameters each feed request goes out with are logged too (the
 * extension's FeedRequestParams), so Home with a Home shows filter can be compared with the Feeds
 * tab's own filter. The feed query method hands its parameter set to it before each return past
 * [FEED_QUERY_INNER]; a build without that method still gets the swap.
 *
 * In the default selection with its switch off: it only acts once the switch is turned on.
 */
@Suppress("unused")
val followingFeedHomePatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Following feed on Home",
    description = "Makes Home show the newest posts from the friends, groups and Pages you follow instead of " +
        "Facebook's ranked feed, so you see what they posted rather than what Facebook picks. Home shows, right " +
        "below it, asks Facebook for one of the Feeds tab's filters (Friends, Favorites, Groups or Pages) instead. " +
        "When that filter has nothing new, Facebook can fill Home with suggested posts and reels, so pair it with " +
        "Hide \"Suggested for you\" posts and Hide Reels in the feed. Starts off. Turn it on in Hushfacebook " +
        "settings > Opening Facebook.",
    default = true,
) {
    category("Navigation")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        requireStatusMethod("followingHome")
        val feedTypes = classDefByOrNull(HOME_FEED_TYPE_CLASS) ?: refuse("$HOME_FEED_TYPE_CLASS isn't in this APK")
        feedTypeRefusal(feedTypes)?.let(::refuse)
        val builder = newsFeedParamsBuilder()
        mutableClassDefBy(builder.definingClass).methods.single { it.sameAs(builder) }.askForHomeFeed()
        enableStatus("followingHome")

        // Debug logging only; the swap works without it.
        try {
            val query = feedQuery(
                classDefByStrings(FEED_QUERY_INNER, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
            ) { type -> classDefByOrNull(type) }
            val method = mutableClassDefBy(query.method.definingClass).methods.singleOrNull { it.sameAs(query.method) }
                ?: refuse("${query.method.definingClass}->${query.method.name} isn't in its class")
            method.sendsFeedParams(query)
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so debug logging won't list a feed request's parameters.")
        }
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.sameAs(other: Method) =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/**
 * Whether [method] is the news feed parameter builder: handed a feed type second, opening the
 * [PAGED_NEWSFEED_PARAMS] trace section, and reading the feed type nowhere before it. Up to 584 it
 * was a static helper of its own; 585 inlined it into the preparer's instance method (arm64
 * `LX/01bb;->A01`), whose first read of the feed type comes right after the trace section opens.
 */
internal fun isNewsFeedParams(method: Method): Boolean =
    method.parameterTypes.getOrNull(1)?.toString() == HOME_FEED_TYPE_CLASS &&
        holdsString(method, PAGED_NEWSFEED_PARAMS) && !readsFeedTypeBeforeTrace(method)

/**
 * The register the feed type arrives in, as a `p` number: past `this` on an instance method and
 * past the parameter before it (two registers when that one is wide).
 */
internal fun feedTypeParameter(method: Method): Int =
    (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1) +
        method.parameterTypes.first().toString().let { if (it == "J" || it == "D") 2 else 1 }

/**
 * Whether anything in [method] reads the feed type's register before the [PAGED_NEWSFEED_PARAMS]
 * load. The swap goes in first thing, so a read up there would see the swapped type where Facebook
 * didn't build for it.
 */
internal fun readsFeedTypeBeforeTrace(method: Method): Boolean {
    val implementation = method.implementation ?: return true
    val instructions = implementation.instructions.toList()
    val trace = instructions.indexOfFirst { loads(it, PAGED_NEWSFEED_PARAMS) }
    if (trace < 0) return true
    val parameterRegisters = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 } +
        (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1)
    val register = implementation.registerCount - parameterRegisters + feedTypeParameter(method)
    return instructions.take(trace).any { register in it.namedRegisters() }
}

/** The one news feed parameter builder among [holders]. Refuses unless there's exactly one. */
internal fun newsFeedParams(holders: List<ClassDef>): Method {
    val builders = holders.flatMap { methodsHolding(it, PAGED_NEWSFEED_PARAMS) }.filter(::isNewsFeedParams)
    return builders.singleOrNull()
        ?: refuse("expected one method opening \"$PAGED_NEWSFEED_PARAMS\" with a feed type second it reads no earlier, found ${builders.size}")
}

/**
 * Why [feedTypes] can't answer the extension's lookup, or null when it can: its static initializer
 * names both feed types, and every feed type it keeps as a static field is a public final one,
 * which is what the extension reads.
 */
internal fun feedTypeRefusal(feedTypes: ClassDef): String? {
    val initializer = feedTypes.methods.singleOrNull { it.name == "<clinit>" }
        ?: return "$HOME_FEED_TYPE_CLASS has no static initializer"
    listOf(HOME_FEED, MOST_RECENT_FEED).firstOrNull { !holdsString(initializer, it) }
        ?.let { return "$HOME_FEED_TYPE_CLASS names no \"$it\" feed type" }
    val hidden = feedTypes.staticFields.filter { it.type == HOME_FEED_TYPE_CLASS }.filterNot {
        AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.FINAL.isSet(it.accessFlags)
    }
    if (hidden.isNotEmpty()) return "$HOME_FEED_TYPE_CLASS keeps feed types the extension can't read: ${hidden.joinToString { it.name }}"
    return null
}

/**
 * First thing in the builder: hand the feed type to the extension and keep its answer, cast back
 * to a FeedType, in the parameter's register, where every later read finds it. The parameter can
 * sit past v15, so it's moved through v0 both ways.
 */
internal fun MutableMethod.askForHomeFeed() {
    requireLocals(PATCH, 1)
    val feedType = "p${feedTypeParameter(this)}"
    addInstructions(
        0,
        """
            move-object/from16 v0, $feedType
            invoke-static { v0 }, $FEED_TYPE_ASKED
            move-result-object v0
            check-cast v0, $HOME_FEED_TYPE_CLASS
            move-object/from16 $feedType, v0
        """,
    )
}

/** The feed query method, and the field of its result class that keeps the request's parameter set. */
internal class FeedQuery(val method: Method, val paramSet: String)

/**
 * The feed query method among [holders] (583 `LX/2Dt;->BWh`): an instance method opening
 * [FEED_QUERY_INNER] whose result class, looked up with [classOf], keeps one [PARAM_SET_CLASS]
 * field. Refuses unless there's exactly one.
 */
internal fun feedQuery(holders: List<ClassDef>, classOf: (String) -> ClassDef?): FeedQuery {
    val queries = holders.flatMap { methodsHolding(it, FEED_QUERY_INNER) }
        .filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }
        .mapNotNull { method ->
            val result = classOf(method.returnType) ?: return@mapNotNull null
            val field = result.fields.singleOrNull { it.type == PARAM_SET_CLASS && !AccessFlags.STATIC.isSet(it.accessFlags) }
                ?: return@mapNotNull null
            FeedQuery(method, "${result.type}->${field.name}:$PARAM_SET_CLASS")
        }
    return queries.singleOrNull()
        ?: refuse("expected one method opening \"$FEED_QUERY_INNER\" whose result keeps a parameter set, found ${queries.size}")
}

/** Where [method] hands back its own feed query: the object returns past [FEED_QUERY_INNER]. */
internal fun feedQueryReturns(method: Method): List<Int> {
    val instructions = method.implementation!!.instructions.toList()
    val inner = instructions.indexOfFirst { loads(it, FEED_QUERY_INNER) }
    if (inner < 0) return emptyList()
    return instructions.indices.filter { it > inner && instructions[it].opcode == Opcode.RETURN_OBJECT }
}

private fun loads(instruction: Instruction, string: String) =
    ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == string

/**
 * Before each of [feedQueryReturns], a result that isn't null has its parameter set handed to the
 * extension. Nothing after a return reads a register, so v0 (v1 when the result is in v0) is free
 * there; the result is moved into it first, since it can sit past v15 (never past v255, which is as
 * far as return-object and move-object/from16 reach). Every return of the method hands back its
 * declared result class, so the field read fits whichever return it lands before. The hook takes
 * over the return's own label, so a branch onto the return goes through it too.
 */
internal fun MutableMethod.sendsFeedParams(query: FeedQuery) {
    val returns = feedQueryReturns(this)
    if (returns.isEmpty()) refuse("${query.method.definingClass}->${query.method.name} returns nothing past \"$FEED_QUERY_INNER\"")
    if (implementation!!.registerCount < 2) refuse("${query.method.definingClass}->${query.method.name} has no spare register")
    for (at in returns.reversed()) {
        val result = getInstruction<OneRegisterInstruction>(at).registerA
        val spare = if (result == 0) 1 else 0
        addInstructionsAtControlFlowLabel(
            at,
            """
                move-object/from16 v$spare, v$result
                if-eqz v$spare, :done
                iget-object v$spare, v$spare, ${query.paramSet}
                invoke-static { v$spare }, $FEED_REQUEST_SENT
                :done
                nop
            """,
        )
    }
}

/** The builder [newsFeedParams] finds in this APK. */
internal fun BytecodePatchContext.newsFeedParamsBuilder(): Method = newsFeedParams(
    classDefByStrings(PAGED_NEWSFEED_PARAMS, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
)
