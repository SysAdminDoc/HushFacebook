/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidesponsoredreels/HideSponsoredReelsPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags

internal const val SPONSORED_REELS_PATCH = "Hide sponsored reels"

internal const val COLLECTION = "Ljava/util/Collection;"
internal const val LIST = "Ljava/util/List;"
internal const val MAP = "Ljava/util/Map;"

/** The two ad filters the sponsored reels patch sends each page through, one per level. */
internal const val AD_FILTER = "Lapp/morphe/extension/facebook/ads/ReelsAdFilter;->" +
    "withoutAds(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;"
internal const val AD_SECTION_FILTER = "Lapp/morphe/extension/facebook/ads/ReelsAdFilter;->" +
    "withoutAdSections(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

/** The ad filter on the collection's listener walk, counted apart from the inserts. */
internal const val ANNOUNCED_AD_FILTER = "Lapp/morphe/extension/facebook/ads/ReelsAdFilter;->" +
    "withoutAnnouncedAds(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;"

/**
 * Where a fetched page enters the Reels and Watch item collection, and the ad item base class.
 *
 * Facebook does not splice ads into Reels on the device. A logging build showed items reaching the
 * collection only as whole fetched pages, never one at a time, with the ad already in the page
 * beside the organic reels -- the server inlines it. So the page arriving at the collection is the
 * only place left to drop it, the same way the news feed patch rejects an edge rather than trying
 * to prevent its insertion. Every filter of a page, the ad filter and the GenAI one, goes on these
 * methods, each prepended with a call that hands the page to the extension and goes on with what
 * comes back. The one-item insert hands its item over as a one-item page and is skipped when the
 * answer is empty.
 *
 * Nothing obfuscated is named. The task that inserts an SFD ad carries a trace literal, and its
 * constructor is handed both the collection and the ad item -- so one string anchor yields both
 * classes, each picked out by a shape no sibling parameter shares.
 */
internal class ReelPages(
    /** The ad item base class, which every ad item extends. */
    val adBase: String,
    /** The item collection's method that puts a page into the backing list: (int, Collection)Z. */
    val insertPage: MutableMethod,
    /** The collection's listener walk over a new page: (collection, Collection)V. */
    val announcePage: MutableMethod,
    /** The controller's method taking a page of fetched sections: (List)Z. */
    val addPage: MutableMethod,
    /** The collection's one-item insert: (item, int)V. */
    val insertItem: MutableMethod,
    /** The collection's static append of a page at the end: (collection, Collection)Z. 577 and 585 have none. */
    val appendPage: MutableMethod?,
    /** The collection's static placer of one item, (item, collection, int)V, which the one-item insert calls. Null where a build has none. */
    val placeItem: MutableMethod?,
    /** The collection's static appends of a page with an out-list and a map, (collection, Collection, List, Map). */
    val appendPages: List<MutableMethod>,
) {
    /** The methods that put a page into the collection's backing list. */
    val pageInserts get() = listOfNotNull(insertPage, appendPage) + appendPages

    /**
     * Where one item goes in: the static placer when the build has one, since the one-item insert
     * calls it and so does the client-side loader's edge swap, which never goes through the insert.
     * One drop there sees each item once, and the insert's own walk afterwards meets the announced
     * filter, which drops it again before any listener hears of it.
     */
    val itemInsert get() = placeItem ?: insertItem
}

/** The reel page methods, resolved for [patch], whose name goes in any refusal. */
internal fun BytecodePatchContext.reelPages(patch: String): ReelPages {
    val adTask = SfdAdInsertFingerprint.method.definingClass
    val taken = mutableClassDefBy(adTask).methods
        .filter { it.name == "<init>" }
        .singleOrPatchException("$patch: the one constructor of the SFD ad insert task $adTask")
        .parameterTypes
        .map { it.toString() }

    fun pick(what: String, matches: (List<String>) -> Boolean) = taken
        .filter { parameter ->
            mutableClassDefByOrNull(parameter)
                ?.methods
                ?.any { matches(listOf(it.returnType) + it.parameterTypes.map(CharSequence::toString)) }
                ?: false
        }
        .also { check(it.size == 1) { "Expected 1 $what among ${taken.joinToString()}, found $it" } }
        .single()

    // The ad item base: the only one of them that can hand back a story.
    val adBase = pick("ad item type") { shape -> shape == listOf(GRAPHQL_STORY) }

    // The item collection: the only one of them that takes a whole collection and answers.
    val collection = pick("item collection") { shape -> shape == listOf("Z", COLLECTION) }

    val collectionClass = mutableClassDefBy(collection)

    // The method that actually puts the page into the backing list: it takes the position to
    // insert at as well as the page. Its sibling only walks the page afterwards telling
    // listeners about each item, which is why filtering that one alone removed nothing.
    val insertPage = collectionClass.methods.filter {
        it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("I", COLLECTION)
    }.singleOrPatchException("$patch: the item collection's page insert, (int, Collection)Z, on $collection")

    // The listener walk. Filtered too, so nothing is announced that was just dropped.
    val announcePage = collectionClass.methods.filter {
        it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(collection, COLLECTION)
    }.singleOrPatchException("$patch: the item collection's page announcement, (collection, Collection)V, on $collection")

    // The walk is not where an item goes in, though. A report from a 581 phone on 0.7.2 (#47) had
    // the walk drop a one-item page with an ad on the Reels tab's client-side loader thread, and a
    // reel ad on screen 8 seconds later. That loader (VideoFeedUnitFeedCSRDataLoaderAdapter) adds a
    // reel with the one-item insert, or a page with the static append, and each puts it in the
    // backing list before it calls the walk. Both get a filter of their own.
    val insertItem = collectionClass.methods.filter {
        val parameters = it.parameterTypes.map(CharSequence::toString)
        it.returnType == "V" && parameters.size == 2 && parameters[1] == "I" &&
            parameters[0].startsWith("L") && !parameters[0].startsWith("Ljava/")
    }.singleOrPatchException("$patch: the item collection's one-item insert, (item, int)V, on $collection")

    // 580 and 581 append a page through a static helper the loader calls directly. 577 appends
    // through the positioned insert, which is already filtered.
    val appendPage = collectionClass.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(collection, COLLECTION)
    }.also { check(it.size <= 1) { "$patch: more than one static page append on $collection: $it" } }
        .singleOrNull()

    // 587's loader goes around both of those (#85, #441). Its edge swap
    // (createVideoHomeItemFromEdge) puts a reel in with the static placer the one-item insert calls,
    // then walks it itself, and its page loads append through two static helpers that take an
    // out-list and a map beside the page. A Threads card reached the Reels tab on 2026-11-17 with
    // nothing but the walk ever seeing it.
    val itemType = insertItem.parameterTypes[0].toString()
    val placeItem = collectionClass.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(itemType, collection, "I")
    }.also { check(it.size <= 1) { "$patch: more than one static item placer on $collection: $it" } }
        .singleOrNull()

    val appendPages = collectionClass.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(collection, COLLECTION, LIST, MAP)
    }

    // The filter on the collection is not enough. A device round on 2026-09-19 showed why. It
    // caught an ad in a page and logged the drop. The app then showed that ad as the third
    // reel. One step earlier, the controller adds a section wrapper. The wrapper holds its own
    // list of items, and the screen reads that list. Thus the ad stayed in the wrapper, and
    // the drop count in the log stayed correct.
    //
    // So the same filter also runs one level up, over the sections that the controller gets.
    // Both levels stay. This level reaches the screen. The collection level still covers
    // anything that enters the list by another route.
    val controller = mutableClassDefBy(VideoHomeInsertAdsFingerprint.method.definingClass)

    // A page of fetched sections: one List in, boolean out.
    val addPage = controller.methods.filter {
        it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(LIST)
    }.singleOrPatchException("$patch: the Reels controller's (List)Z method that takes a page of sections")

    return ReelPages(adBase, insertPage, announcePage, addPage, insertItem, appendPage, placeItem, appendPages)
}

/**
 * Hands the one item this insert receives to the page [filter] as a one-item list, with [className]
 * beside it, and returns before the insert when the filter hands back an empty list. An item the
 * filter keeps goes in as Facebook sent it. The insert answers nothing, so returning early only
 * leaves the item out: the backing list clamps a later insert's position to its size.
 *
 * The item is the insert's first declared parameter. Its two locals come from [freeLocalsAt] the way
 * [filterParameterFirst] takes them, so a second filter put in front borrows the same two.
 */
internal fun MutableMethod.dropItemFirst(className: String, filter: String = AD_FILTER, patch: String = SPONSORED_REELS_PATCH) {
    if (returnType != "V") throw PatchException("$patch: $definingClass->$name answers $returnType, not void")
    val (copy, label) = freeLocalsAt(patch, 0, 2)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v$copy, ${parameterRegister(0)}
            invoke-static { v$copy }, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;
            move-result-object v$copy
            const-string v$label, "$className"
            invoke-static { v$copy, v$label }, $filter
            move-result-object v$copy
            invoke-interface { v$copy }, $COLLECTION->isEmpty()Z
            move-result v$copy
            if-eqz v$copy, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * Sends the page this method receives through [filter] before its own code runs, with [className]
 * beside it: the binary name of the class the filter tells its items by. The helper hands back the
 * very same collection when it takes nothing out, so the usual page is untouched and keeps its type.
 *
 * The page is the method's one Collection parameter, and its register comes from the declared
 * parameters. Redex makes a method static in one build and leaves it an instance method in the
 * next, and a p register written down for one of those would hand the filter the collection
 * object, or `this`, in place of the page.
 */
internal fun MutableMethod.filterPageFirst(className: String, filter: String = AD_FILTER, patch: String = SPONSORED_REELS_PATCH) {
    val page = parameterTypes.indices.filter { parameterTypes[it].toString() == COLLECTION }
        .singleOrPatchException("$patch: the one Collection parameter of $definingClass->$name")
    filterParameterFirst(page, className, filter, patch)
}

/**
 * Sends the page of sections this controller method receives through [filter] before its own code
 * runs, with [className] beside it. The page is its List parameter, found the way
 * [filterPageFirst] finds the page.
 */
internal fun MutableMethod.filterSectionsFirst(className: String, filter: String = AD_SECTION_FILTER, patch: String = SPONSORED_REELS_PATCH) {
    val sections = parameterTypes.indices.filter { parameterTypes[it].toString() == LIST }
        .singleOrPatchException("$patch: the one List parameter of $definingClass->$name")
    filterParameterFirst(sections, className, filter, patch)
}

/**
 * Sends declared parameter [parameter] through [filter], with [className] beside it, before the
 * method's own code runs, and goes on with the answer in the parameter's own register.
 *
 * The filter call names its operands in four bits, so it never names the parameter: the page is
 * copied down into a local first and the answer copied back. 577's controller page method already
 * has 15 registers, and two more would put its page above v15, where the patcher's smali compiler
 * leaves out a call that names it without a word.
 *
 * Both locals come from [freeLocalsAt] at the top of the method, so neither is ever a parameter,
 * nor a local the method reads before writing. The one the first instruction named used to be
 * taken, which was the page itself when a method opened by testing or casting it. A filter put in
 * front of another, as Hide AI-detected posts puts its own, borrows the same two, and the one
 * after writes them again before reading them.
 */
private fun MutableMethod.filterParameterFirst(parameter: Int, className: String, filter: String, patch: String) {
    val (copy, label) = freeLocalsAt(patch, 0, 2)
    val register = parameterRegister(parameter)
    addInstructions(
        0,
        """
            move-object/from16 v$copy, $register
            const-string v$label, "$className"
            invoke-static { v$copy, v$label }, $filter
            move-result-object v$copy
            move-object/16 $register, v$copy
        """,
    )
}

/** `LX/B89;` as the runtime reports it: `X.B89`. */
internal fun String.toBinaryName() = removePrefix("L").removeSuffix(";").replace('/', '.')
