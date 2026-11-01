/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.ads.sponsoredreels.filterSectionsFirst
import app.morphe.patches.facebook.ads.sponsoredreels.reelPages
import app.morphe.patches.facebook.ads.sponsoredreels.toBinaryName
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val DECLUTTER = "$EXTENSION_PACKAGE/reels/ReelDeclutter;"
internal const val FILTER_CHIPS = "$DECLUTTER->filterChips(Ljava/lang/Object;)[Ljava/lang/Object;"
internal const val HIDE_AUDIO_LINE = "$DECLUTTER->hideAudioLine()Z"
internal const val HIDE_FOLLOW_BUTTON = "$DECLUTTER->hideFollowButton()Z"
internal const val HIDE_FOLLOWING_BUTTON = "$DECLUTTER->hideFollowingButton()Z"
internal const val SKIP_HOT_COMMENT = "$DECLUTTER->skipHotComment()Z"
internal const val SKIP_SOCIAL_BUBBLES = "$DECLUTTER->skipSocialBubbles()Z"

private const val PATCH = "Clean up Reels"

/**
 * Takes three things off the Reels viewer, each behind its own switch, all on once the patch is in:
 * the chips under a reel that prompt you to make something or promote something (and with them the
 * "Made with Edits" line under the caption, #314, see ReelEditsLabel.kt), the Follow button
 * in the author row (with the Following button an author you already follow gets there), and the
 * comment and friends' reaction previews in the footer. A fourth switch, off until it's turned on,
 * takes the "Threads you might like" card out from between reels (see ReelMidCards.kt), a fifth,
 * also off, starts reels and videos in Facebook's own Clean mode (see ReelCleanMode.kt), and a
 * sixth, also off, plays each reel once instead of starting it over at its end (see ReelLoop.kt,
 * whose hook is reelLoopPatch's). A seventh, also off, keeps reels and videos in the audio they
 * were made with instead of a Meta AI dub (see OriginalAudio.kt). An eighth, also off, takes the
 * search pill out from under a reel's caption and off the top of its comments, along with the search
 * bar and send button over a reel opened from a profile (see SearchPill.kt). A ninth, also off, takes the
 * numbers away from under the sidebar's Like, Comment and Share icons (see ReelCounts.kt). A
 * tenth, also off, leaves the Save button out of that sidebar (see ReelSaveButton.kt). An
 * eleventh, also off, opens a video tapped in the feed on its own, with no reels after it (see
 * FeedVideoAlone.kt). A twelfth, also off, leaves the speaker button out of the bottom of the
 * sidebar (see ReelSoundButton.kt). A thirteenth, also off, takes the line naming a reel's sound,
 * "Original audio" or a song, out of the same chip list the first switch filters, and has the row
 * the Reels tab draws it in on its own draw nothing (#200, see ReelAnchors.kt). A fourteenth, also
 * off, keeps a video that's slow to load from showing "This is taking a while. Try checking your
 * network connection." (#222, see ReelSlowConnection.kt). A fifteenth, also off, plays whole reels
 * in the feed's Reels row instead of the short clip each tile repeats (#228, see FeedReelPreviews.kt). A
 * sixteenth, also off, keeps the sidebar's Comment button on a reel whose comments are turned off, so
 * they can still be read (#299, see ReelCommentButton.kt). A seventeenth, also off, leaves the Subscribe
 * pill some authors get in place of Follow out of a full-screen video's author row (#291, see
 * ReelSubscribeButton.kt). An eighteenth, also off, takes Search and the button beside it off the
 * top of a video or reel opened full screen (#311, see ViewerTopBar.kt). A nineteenth, Burn-in
 * guard, also off, fades the buttons, caption and bars around a video filling the screen once
 * they're left alone (#288). It has no hook here: the extension's BurnInGuard watches the activity
 * in front from SettingsEntry while this patch is in. A twentieth, also off, shows when a reel or
 * full-screen video was posted under its author, a line Facebook builds behind a server switch
 * that's off for most accounts (#296, see ReelPostDate.kt). With Debug
 * logging on, it also reports whether a reel that ends
 * is on the player the Reels auto-advance controller holds (see ReelBinding.kt), with no switch.
 *
 * Every anchor is a kept name or literal (see ReelAnchors.kt), and every one of them is required: a
 * build where one can't be found stops the patch with what's missing, rather than shipping a switch
 * that quietly does nothing. The hooks ask the extension, which answers Facebook's own path until
 * the settings are ready, while paused, and whenever it fails.
 *
 * In the default selection, but it changes nothing until it's turned on in Hushfacebook settings:
 * nobody has seen it on a signed-in Reels feed yet.
 */
@Suppress("unused")
val cleanUpReelsPatch = bytecodePatch(
    name = "Clean up Reels",
    description = "Hides clutter on reels, such as the Follow button, comment previews, Remix and Use template " +
        "buttons and Threads cards between reels, so more of the video shows, and can play each reel once " +
        "instead of over and over, keep its original audio instead of a Meta AI dub, drop the search suggestions under the caption, over the comments and at the top of a profile's reels, or hide the like, comment and share counts, the Like, Comment, Share, Save or speaker button, or the line naming the reel's sound. It can also open a video you tap in the feed on its own, with no reels after it, stop the \"This is taking a while\" message when a video is slow to load, and play whole reels in the feed's Reels row instead of the same second over and over, or keep the Comment button on reels with comments turned off so you can read them, and hide the one-tap Subscribe button some authors get instead of Follow, or Search and the button beside it at the top of a video you open, and show when a reel or video was posted. Its Burn-in guard can fade a reel's buttons, caption and bars when you leave them alone, so they don't burn into the screen. Starts off. Turn on the parts you want in Hushfacebook settings > Reels and Watch.",
) {
    category("Reels")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch, reelLoopPatch, reelBindingPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        filterChips()
        // #314: and "Made with Edits" under the caption, which isn't one of the chips.
        hideEditsLabel()
        // #200: the Reels tab draws the audio line in a row of its own, past the chip list.
        hideReelsTabAudioLine()
        hideFollowButtons()
        skipFooterQueries()
        hideThreadsCards()
        startReelsInCleanMode()
        // #304: and its button over a clean reel switches Clean mode off and back on.
        toggleReelCleanMode()
        // #148: the audio language the player picks a video's sound by.
        hookOriginalAudio()
        // #148: and the manifest parser's dubbing reads, which skip that language when Facebook's Languages menu is on.
        hookOriginalAudioTracks()
        // #173: the search pill under a reel's caption.
        hideSearchPill()
        // #197: the same switch, for the search pill over a reel's comments.
        hideCommentSearchPill()
        // #201: and for the search bar and its send button over a reel opened from a profile.
        hideSearchBar()
        // #180: the numbers under the sidebar's Like, Comment and Share icons.
        hideReelCounts()
        // #190, #243, #265: Like, Comment, Share, Save and the direct WhatsApp button, skipped as their icon types come up.
        hideReelSaveButton()
        // #299: the Comment button on a reel whose comments are turned off.
        showReelCommentButton()
        // #296: the post date under a reel's author, behind a server switch.
        showReelPostDate()
        // #291: the Subscribe pill beside a video's author, in the viewer's row and wherever the shared builder's lands.
        hideSubscribePill()
        // #311: Search and the button beside it at the top of the full-screen player, by the bar's id.
        hideViewerTopButtons()
        // #190: the speaker button under the sidebar, from whichever place a server flag picks:
        // the video controls, through Facebook's own check for it, or the footer, through its null check.
        val controlsSpeaker = hideReelSoundButton()
        val footerSpeaker = hideReelFooterSoundButton()
        // #331: that speaker in the side column, right above the three dots.
        try {
            moveReelSpeakerAboveMenu(footerSpeaker, controlsSpeaker)
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so Move the speaker above the three dots leaves it where Facebook puts it.")
        }
        // #161: a feed video's player asks for no more reels after it.
        keepFeedVideosAlone()
        // #222: the "This is taking a while" message a stalled video posts.
        hideSlowConnectionMessage()
        // #228: the clip each tile of the feed's Reels row plays, and where it stops.
        playFeedReelsWhole()
        enableStatus("reelDeclutter")
    }
}

/** The chip list builder gets the extension's filter before every return. */
private fun BytecodePatchContext.filterChips() {
    val immutableList = classDefByOrNull(IMMUTABLE_LIST)
        ?: throw PatchException("$PATCH: this Facebook build has no $IMMUTABLE_LIST")
    if (!hasCopyOfArray(immutableList)) {
        throw PatchException("$PATCH: ImmutableList has no public static copyOf(Object[]) to rebuild the chip list with")
    }
    val tables = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    val builders = classDefByStrings(CHIP_ANCHOR, StringComparisonType.EQUALS)
        .flatMap { owner -> chipListBuilders(owner, tables).map { owner to it } }
    val (owner, builder) = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one ImmutableList builder naming every hidden chip (${(HIDDEN_CHIPS + SOUNDTRACK_CHIP).joinToString()}), " +
            "found ${builders.size}",
    )
    mutableClassDefBy(owner).methods.first { it.sameSignatureAs(builder) }.filterChipsBeforeEveryReturn()
}

/**
 * The Reels tab's audio line row asks the extension first thing and answers null when it says so,
 * Facebook's own answer for a reel with no sound line. Refuses unless exactly one render fits.
 */
private fun BytecodePatchContext.hideReelsTabAudioLine() {
    val rows = classDefByStrings(SOUNDTRACK_CHIP, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef -> classDef.methods.filter(::isAudioLineRender).map { classDef.type to it } }
    val (owner, row) = rows.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one render that picks $SOUNDTRACK_CHIP and answers null first, found ${rows.size}",
    )
    mutableClassDefBy(owner).methods.first { it.sameSignatureAs(row) }.returnNullWhen(HIDE_AUDIO_LINE)
}

/** Asks [hook] first thing and answers null when it says so. v0 is free there, as in [answerWhen]. */
internal fun MutableMethod.returnNullWhen(hook: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_row
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("facebooks_row", getInstruction(0)),
    )
}

/**
 * Sends the list at every `return-object` through the extension's chip filter. A null answer keeps
 * Facebook's list, and an array is rebuilt into an ImmutableList with Facebook's own copyOf. Each
 * hook goes in at the return's own control-flow label, so a branch that jumped to the return runs
 * it too. At a return nothing else is live, so one local other than the returned one holds the
 * answer. The branch lands on a nop of the hook's own, right before the return.
 */
internal fun MutableMethod.filterChipsBeforeEveryReturn() {
    val implementation = implementation ?: throw PatchException("$PATCH: $definingClass->$name has no body")
    val returns = implementation.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) throw PatchException("$PATCH: $definingClass->$name returns no chip list")
    val locals = localRegisterCount()

    returns.asReversed().forEach { (index, list) ->
        val answer = (0 until locals).firstOrNull { it != list }
            ?: throw PatchException("$PATCH: $definingClass->$name has no local besides v$list to hold the filter's answer")
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$list .. v$list }, $FILTER_CHIPS
                move-result-object v$answer
                if-eqz v$answer, :facebooks_chips
                invoke-static/range { v$answer .. v$answer }, $COPY_OF
                move-result-object v$list
                :facebooks_chips
                nop
            """,
        )
    }
}

/**
 * The author row's two buttons for following, both behind the Follow switch. Facebook's Follow
 * check answers no when the switch is on, so no Follow button is built for an author you don't
 * follow, and its config getter for removing the Following button answers true, so an author you
 * already follow gets none either. See ReelAnchors.kt for why one hook alone missed the first.
 */
private fun BytecodePatchContext.hideFollowButtons() {
    val dumpers = classDefByStrings(WATCH_FEED_DUMP, StringComparisonType.EQUALS)
        .flatMap { methodsHolding(it, WATCH_FEED_DUMP) }
    val dumper = dumpers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding \"$WATCH_FEED_DUMP\", found ${dumpers.size}")
    val found = followButtonGetter(dumper) { classDefByOrNull(it) }
    if (found.flags.isNotEmpty()) return hideFollowButtonsByFlags(found.flags)
    val getter = found.getter ?: throw PatchException("$PATCH: ${found.problem}")

    val readers = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(getter.definingClass)) { classDef ->
        classDef.methods.filterTo(readers) { calls(it, getter) }
    }
    followReaderProblem(readers, dumper)?.let { throw PatchException("$PATCH: $it") }

    val author = authorRow(readers, dumper)
        ?: throw PatchException("$PATCH: no single reader of the Follow getter holds \"$AUTHOR_COMPONENT\"")
    val asked = followCheck(author) { call -> classDefByOrNull(call.definingClass)?.let { resolveCalled(it, call) } }
    val check = asked.check ?: throw PatchException("$PATCH: ${asked.problem}")

    val method = mutableClassDefByOrNull(getter.definingClass)?.methods?.singleOrNull {
        it.name == getter.name && it.returnType == "Z" && it.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: throw PatchException("$PATCH: ${getter.definingClass} declares no ${getter.name}()Z with a body")
    method.returnTrueWhen(HIDE_FOLLOWING_BUTTON)
    mutableClassDefBy(check.definingClass).methods.first { it.sameSignatureAs(check) }.returnFalseWhen(HIDE_FOLLOW_BUTTON)
}

/**
 * The same two buttons from 583 on, where the Following button's config is two flag reads in a merged
 * lambda rather than one getter: each read answers true when the switch is on, and the author row,
 * the one method holding [AUTHOR_COMPONENT], asks the Follow check as before.
 */
private fun BytecodePatchContext.hideFollowButtonsByFlags(flags: List<FollowFlag>) {
    val authors = classDefByStrings(AUTHOR_COMPONENT, StringComparisonType.EQUALS).flatMap { methodsHolding(it, AUTHOR_COMPONENT) }
    val author = authors.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding \"$AUTHOR_COMPONENT\", found ${authors.size}")
    val asked = followCheck(author) { call -> classDefByOrNull(call.definingClass)?.let { resolveCalled(it, call) } }
    val check = asked.check ?: throw PatchException("$PATCH: ${asked.problem}")
    // Hooked in each lambda's invoke() by the flag's key, since 585 writes one flag's read into a switch with no method to hook.
    for (flag in flags) {
        val keyField = lambdaKeyField(classDefBy(flag.lambda))
            ?: throw PatchException("$PATCH: ${flag.lambda}'s invoke() reads no key before its switch")
        mutableClassDefBy(flag.lambda).methods.single {
            it.name == "invoke" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Object;" && it.implementation != null
        }.returnTrueObjectForKeyWhen(keyField, flag.key, HIDE_FOLLOWING_BUTTON)
    }
    mutableClassDefBy(check.definingClass).methods.first { it.sameSignatureAs(check) }.returnFalseWhen(HIDE_FOLLOW_BUTTON)
}

/**
 * Asks [hook] first thing and answers a boxed true when this lambda was made with [key] and the hook
 * says so; any other key, or a no, runs Facebook's own switch. v0 and v1 are free at the first
 * instruction, and the lambda reaches v0 through a 16-bit move, since `this` may sit above v15.
 */
internal fun MutableMethod.returnTrueObjectForKeyWhen(keyField: FieldReference, key: Int, hook: String) {
    requireLocals(PATCH, 2)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget v0, v0, ${keyField.definingClass}->${keyField.name}:I
            const v1, $key
            if-ne v0, v1, :facebooks_answer
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_answer
            sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
            return-object v0
        """,
        ExternalLabel("facebooks_answer", getInstruction(0)),
    )
}

/** Asks [hook] first thing and answers true when it says so. */
internal fun MutableMethod.returnTrueWhen(hook: String) = answerWhen(hook, true)

/** Asks [hook] first thing and answers false when it says so. */
internal fun MutableMethod.returnFalseWhen(hook: String) = answerWhen(hook, false)

/**
 * Asks [hook] first thing and answers [answer] when it says so. At the first instruction no local
 * holds anything yet, so v0 is free, and `const/4` and `return` both reach it.
 */
private fun MutableMethod.answerWhen(hook: String, answer: Boolean) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_answer
            const/4 v0, ${if (answer) "0x1" else "0x0"}
            return v0
        """,
        ExternalLabel("facebooks_answer", getInstruction(0)),
    )
}

/** The two footer runnables return before they query when the switch is on. */
private fun BytecodePatchContext.skipFooterQueries() {
    listOf(
        Triple(HOT_COMMENT_RUNNABLE, INLINE_COMMENTS_QUERY, SKIP_HOT_COMMENT),
        Triple(SOCIAL_BUBBLES_RUNNABLE, VIDEO_ID, SKIP_SOCIAL_BUBBLES),
    ).forEach { (redexName, holding, hook) ->
        val runs = classDefByStrings(holding, StringComparisonType.EQUALS)
            .mapNotNull { owner -> footerRunnable(owner, redexName, holding)?.let { owner to it } }
        val (owner, run) = runs.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one Runnable named $redexName whose run() holds \"$holding\", found ${runs.size}",
        )
        mutableClassDefBy(owner).methods.first { it.sameSignatureAs(run) }.returnVoidWhen(hook)
    }
}

/**
 * Asks [hook] first thing and returns when it says so. The runnable only starts its query and
 * attaches the callback that draws the answer, so returning leaves nothing half done.
 */
internal fun MutableMethod.returnVoidWhen(hook: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_query
            return-void
        """,
        ExternalLabel("facebooks_query", getInstruction(0)),
    )
}

/**
 * The Threads card between reels comes off each page of sections the Reels controller takes, through
 * the same walk the sponsored reels and GenAI filters use, with the mid-card item's class beside it.
 * The extension reads each mid-card's type through the stub this fills, and drops the Threads one.
 */
private fun BytecodePatchContext.hideThreadsCards() {
    val scan = MidCardScan()
    classDefForEach { classDef -> if (!classDef.type.startsWith(EXTENSION_PACKAGE)) scan.visit(classDef) }
    val resolved = scan.resolve()
    val card = resolved.found ?: throw PatchException("$PATCH: ${resolved.problem}")
    fillMidCardTypeStub(card)
    reelPages(PATCH).addPage.filterSectionsFirst(card.item.toBinaryName(), THREADS_CARD_SECTION_FILTER, PATCH)
}

private fun MutableMethod.sameSignatureAs(other: MethodReference) =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }
