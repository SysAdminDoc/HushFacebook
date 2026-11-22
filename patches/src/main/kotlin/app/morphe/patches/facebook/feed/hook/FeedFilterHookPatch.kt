/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * The chokepoint and the two getters come from
 * https://github.com/andrewliang25/morphe-patches (GPL-3.0, Andrew Liang).
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.shared.feedFunnelBody
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.shared.FEED_STORY_CATEGORY
import app.morphe.patches.facebook.shared.FEED_UNIT_EDGE
import app.morphe.patches.facebook.shared.feedUnitGetter
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.shared.storyCategoryGetter
import app.morphe.util.executeBytecode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val HIDE_EDGE =
    "$EXTENSION_PACKAGE/feed/FeedFilter;->hideEdge(Ljava/lang/Object;Ljava/lang/Object;)Z"

/** The static helper the patch adds to GraphQLFeedUnitEdge, which reads the edge and asks the extension. */
internal const val HIDES_EDGE_HELPER = "hushfacebookHidesEdge"

/** How the funnel guard calls [HIDES_EDGE_HELPER]. */
internal const val HIDES_EDGE = "$FEED_UNIT_EDGE->$HIDES_EDGE_HELPER($FEED_UNIT_EDGE)Z"

/**
 * The helper: the edge's story category and feed unit, handed to the extension, whose answer it
 * returns. With the two reads in here, the guard is the helper's call first thing and one local
 * for its answer, which the receipt's start-call rule can hold it to.
 */
private fun hidesEdgeHelper(categoryGetter: String, feedUnit: Method) = ImmutableMethod(
    FEED_UNIT_EDGE,
    HIDES_EDGE_HELPER,
    listOf(ImmutableMethodParameter(FEED_UNIT_EDGE, null, null)),
    "Z",
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(3),
).toMutable().apply {
    addInstructions(
        0,
        """
            invoke-virtual { p0 }, $FEED_UNIT_EDGE->$categoryGetter()$FEED_STORY_CATEGORY
            move-result-object v0
            invoke-virtual { p0 }, $FEED_UNIT_EDGE->${feedUnit.name}()${feedUnit.returnType}
            move-result-object v1
            invoke-static { v0, v1 }, $HIDE_EDGE
            move-result v0
            return v0
        """,
    )
}

/**
 * The one guard every feed filter shares, at the start of `addNewEdgeToCollection`.
 *
 * <p>It reads the edge's story category and its feed unit and asks the extension whether the edge
 * stays out. The feed patches only switch their rule on there. From 583 on the guard goes on the
 * method `addNewEdgeToCollection` hands its edge to (a static one on 583, a private instance one on
 * 585), since the feed's batch add calls that method without the wrapper ([feedFunnelBody]). The reads happen in a static helper the patch adds to the
 * edge class, so the guard is one call first thing and needs one local, whichever method it's on. One guard rather than one per patch
 * is deliberate: FroggoMorphePatches prepended several label-bearing guards to this method and
 * users got "target dex pc is not at instruction start" crashes, because only the guard inserted
 * last kept its branch targets.
 *
 * <p>Nothing here is named by the obfuscator. The method keeps its name, the edge and the enum are
 * kept model classes, and the two getters are picked by return type and by the `inflateFeedUnit`
 * literal. A rule that needs more of the unit than this, such as the GenAI flag, reads it in the
 * extension from the feed unit this guard already passes, so no rule adds code here.
 *
 * <p>An edge Facebook swaps in over another one never reaches this method, so the runnable that
 * makes the swap asks the extension too, with the same two getters, before it touches the feed
 * (EdgeSwap.kt). That's a different method, so the funnel still carries one guard.
 *
 * <p>A page the guard empties can leave Home's scroll fetcher at the end of its list with nothing
 * that makes it ask for the next page, so the guard brings the tail fetch hook along (TailFetch.kt).
 * With the posts that do get through arriving one by one, Home adds most of them while it's on
 * screen, so it also brings the hook that lets in a row only an accessibility change holds back
 * (HeldInserts.kt).
 *
 * <p>A post the person hid could come back as a new edge on a pull to refresh, so the guard also
 * brings the hooks that remember each hide and Undo (HiddenPosts.kt).
 */
internal val feedFilterHookPatch = patch {
    dependsOn(facebookExtensionPatch, homeTailFetchPatch, heldInsertsPatch, hiddenPostsPatch)

    executeBytecode {
        val categoryGetter = storyCategoryGetter()
        val feedUnit = feedUnitGetter()
        // Both targets are found before either changes, so a build missing one is left as it was.
        val swap = edgeSwapGuard()

        val method = feedFunnelBody()
        val edgeIndex = method.parameterTypes.indexOfFirst { it.toString() == FEED_UNIT_EDGE }
        if (edgeIndex < 0) {
            throw PatchException("${method.definingClass}->${method.name} no longer takes a GraphQLFeedUnitEdge")
        }
        // Index 0, where no local holds anything yet, so the one local is free to borrow.
        method.requireLocals("Feed filter", 1)

        mutableClassDefBy(FEED_UNIT_EDGE).methods.add(hidesEdgeHelper(categoryGetter, feedUnit))
        // The edge can sit in a parameter register above v15, which only a range call reaches.
        val edge = method.parameterRegister(edgeIndex)
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { $edge .. $edge }, $HIDES_EDGE
                move-result v0
                if-eqz v0, :keep
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("keep", method.getInstruction(0)),
        )

        mutableClassDefBy(swap.method.definingClass).methods.single {
            it.name == swap.method.name && it.returnType == swap.method.returnType && it.parameterTypes.isEmpty()
        }.guardEdgeSwap(swap, categoryGetter, feedUnit)
    }
}
