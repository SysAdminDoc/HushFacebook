/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidesponsoredreels/HideSponsoredReelsPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.returnEarly
import app.morphe.util.singleOrPatchException
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.util.MethodUtil
import app.morphe.patches.facebook.misc.settings.settingsPatch

private const val LISTENABLE_FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"
private const val SETTABLE_FUTURE = "Lcom/google/common/util/concurrent/SettableFuture;"

private const val PATCH = SPONSORED_REELS_PATCH

@Suppress("unused")
val hideSponsoredReelsPatch = bytecodePatch(
    name = "Hide sponsored reels",
    description = "Removes ads from Reels and Watch, including product banners over reels and ads in the middle " +
        "of videos, so you can watch without interruptions. On by default. Turn it off in Hushfacebook settings " +
        "> Reels and Watch.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())
    dependsOn(facebookExtensionPatch)

    execute {
        // The server inlines the ads into each fetched page, so the page arriving at the
        // collection is where they come out: see ReelPages.kt for how the three methods and the
        // ad item base are found. Replace the incoming page with one that has no ads in it, at
        // both levels a page enters.
        val pages = reelPages(PATCH)
        // An ad can also come as an ordinary item around a story with sponsored data, so the
        // filters ask each item for its story too: see ReelItemStory.kt.
        val items = reelItemStory(pages.adBase)
        val adBase = pages.adBase.toBinaryName()
        fillReelItemStubs(items)
        pages.pageInserts.forEach { it.filterPageFirst(adBase) }
        pages.announcePage.filterPageFirst(adBase, ANNOUNCED_AD_FILTER)
        pages.insertItem.dropItemFirst(adBase)
        pages.addPage.filterSectionsFirst(adBase)

        // The Reels tab's story loader draws those ads from Facebook's ad pool one slot at a time,
        // and the pool marks each as used before a page holds it. Its vends answer no ad first:
        // see ReelsAdPool.kt.
        val poolVends = reelsAdPoolVends()
        poolVends.forEach { it.holdPoolAdFirst() }

        // The Reels row in the News feed draws its ad tiles from a second pool, through the same
        // two base methods (#216). A build that moved it keeps the rest: see ReelsAdPool.kt.
        try {
            fbShortsAdPoolVends(poolVends).forEach { it.holdPoolAdFirst(HOLD_ROW_AD) }
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without holding the ads in the feed's Reels row.")
        }

        // A profile's or Page's Reels tab fetches its own ads through a query of its own, outside
        // the pool. It returns before sending it: see ProfileReelAds.kt.
        profileReelAdFetch().holdProfileReelAdsFirst()

        // The client-side insert paths stay blocked. None of them fired in the logging run, but
        // they are what the app would use if a future release went back to inserting on the device,
        // and blocking them costs nothing.
        //
        // Only the inserts are blocked, not the requests that feed them. Stopping those would save
        // data, but belongs with the prefetch patch, and they have not been checked for organic
        // side effects.
        listOf(
            VideoHomeInsertAdsFingerprint,
            RealtimeIntentAdInsertFingerprint,
            SfdAdInsertFingerprint,
            PoeAdRenderFingerprint,
        ).forEach { it.method.returnEarly() }

        // Banners over a reel and mid-rolls are fetched while the reel plays, not in the page, so
        // the filters cannot see them. Their fetches return a failed future instead. Each caller
        // handles it as a network error: it stores no ad, and no query goes out.
        holdAdBreakFetches()

        // The ad-break lookup retries a failed fetch each second while the reel plays. Its tick
        // returns -1, the value for a reel with no media, which stops the poller. The lookup then
        // never starts.
        stopDeferredCardPoller()

        // An ad that still reaches a reel or a video opens its comments without the floating ad
        // button: see AdPills.kt. A build that draws the button another way keeps the rest.
        try {
            holdAdPills()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without holding the ad button on comments.")
        }

        enableStatus("sponsoredReels")
    }
}

/**
 * Makes every ad-break fetch return a failed future: the banner helper, the ad-break server API's
 * queries, and the idle state's and the extended break's own queries on the shared executor.
 *
 * <p>585 (Redex): the banner helper returns the SettableFuture it creates where 584 declared
 * ListenableFuture, so [futureCallBefore] reads either. 587 declares the server API's video query
 * as the transform future it builds, an obfuscated subclass of Guava's AbstractFuture that a
 * SettableFuture isn't, so that query is held at its executor call instead. AdBreakFixtureTest
 * runs this on every declared build.
 */
internal fun BytecodePatchContext.holdAdBreakFetches() {
    val adBreakFetch = AdBreakFetchFingerprint.method.instructions()

    // The banner helper. All banner requests go through it.
    val bannerFetch = adBreakFetch.futureCallBefore(BANNER_FETCH_LOG)
    mutableClassDefBy(bannerFetch.definingClass).methods
        .filter { MethodUtil.methodSignaturesMatch(it, bannerFetch) }
        .singleOrPatchException("$PATCH: the banner query helper $bannerFetch, declared on its own class")
        .let(::returnFailedFuture)

    // The ad-break server API. Each of its methods that returns a future is an ad query. One that
    // declares a ListenableFuture returns the failed future at once. One that declares the narrower
    // future type the fetch stores (587's transform future) can't return a SettableFuture, so its
    // executor call hands the failed future to the transform: no query goes out, and the transform
    // fails with it.
    val videoFetch = adBreakFetch.futureCallBefore(VIDEO_FETCH_LOG)
    val videoFetcher = videoFetch.definingClass
    val narrowed = videoFetch.returnType.takeUnless { it == LISTENABLE_FUTURE || it == SETTABLE_FUTURE }
    val apiMethods = mutableClassDefBy(videoFetcher).methods.toList()
    val queries = apiMethods.filter { it.returnType == LISTENABLE_FUTURE }
    val narrowedQueries = apiMethods.filter { narrowed != null && it.returnType == narrowed }
    check(queries.isNotEmpty() || narrowedQueries.isNotEmpty()) { "$videoFetcher has no method that returns a future" }
    queries.forEach(::returnFailedFuture)
    narrowedQueries.forEach { query ->
        query.replaceInstruction(
            queryExecutorCallIndex(query.instructions(), "${query.definingClass}->${query.name}"),
            "invoke-static { }, ${failedFutureOn(query.definingClass)}",
        )
    }

    // The idle state runs its own video ad query on the GraphQL executor, which the whole app
    // shares. Thus only this one executor call changes. The helper call has the same size and
    // no arguments, so the next move-result gets the failed future.
    val idleVideoFetch = ReelsVideoAdQueryFingerprint.method
    val executeIndex = idleExecutorCallIndex(
        idleVideoFetch.instructions(),
        "${idleVideoFetch.definingClass}->${idleVideoFetch.name}",
    )
    idleVideoFetch.replaceInstruction(
        executeIndex,
        "invoke-static { }, ${failedFutureOn(idleVideoFetch.definingClass)}",
    )

    // An extended break, the longer ad break a video can queue up, runs its own query on the
    // same shared executor, so its executor call gets the failed future too.
    val extendedBreak = ExtendedBreakQueryFingerprint.method
    extendedBreak.replaceInstruction(
        idleExecutorCallIndex(
            extendedBreak.instructions(),
            "${extendedBreak.definingClass}->${extendedBreak.name}",
            EXTENDED_BREAK_QUERY,
        ),
        "invoke-static { }, ${failedFutureOn(extendedBreak.definingClass)}",
    )
}

/**
 * Makes the tick of the deferred-card ad-break state answer -1 ("no media"), so its poller stops.
 *
 * <p>577 names each state in the state's own class, so the class holding the literal is the state
 * and declares the tick. 580 names every state from one method on the abstract base, by
 * `instance-of`, and the tick moved up into a parent the deferred-card state shares with two
 * other states. There the state is the type tested just before the literal, and the tick answers
 * -1 only for an instance of it, so the other states keep their own behaviour.
 */
internal fun BytecodePatchContext.stopDeferredCardPoller() {
    val nameMethod = UnresolvedAdStateFingerprint.method
    val instructions = nameMethod.instructions()
    val literal = instructions.indexOfFirst { it.string == DEFERRED_CARD_STATE }
    check(literal >= 0) { "\"$DEFERRED_CARD_STATE\" is not in ${nameMethod.definingClass}->${nameMethod.name}" }

    // The type tested just before the literal, when the name comes from an instance-of chain.
    val tested = (maxOf(0, literal - 3) until literal).reversed()
        .firstNotNullOfOrNull { index ->
            instructions[index].takeIf { it.opcode == Opcode.INSTANCE_OF }
                ?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
        }
    val state = tested ?: nameMethod.definingClass

    // The tick the state runs: its own, or the nearest one it inherits.
    val tick = superclassChain(state)
        .mapNotNull { mutableClassDefByOrNull(it) }
        .firstNotNullOfOrNull { classDef -> adBreakTickAmong(classDef.methods, classDef.type) }
        ?: throw PatchException("$PATCH: no class of $state's hierarchy declares an ad-break tick")

    if (tick.definingClass == state) {
        tick.returnEarly(-1L)
        return
    }

    // Shared with sibling states: answer -1 only for the deferred-card one. Index 0, where no
    // local holds anything yet; this is copied down because instance-of takes 4-bit registers.
    tick.requireLocals("Hide sponsored reels", 2)
    tick.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            instance-of v0, v0, $state
            if-eqz v0, :other_state
            const-wide/16 v0, -0x1
            return-wide v0
        """,
        ExternalLabel("other_state", tick.getInstruction(0)),
    )
}

/**
 * The ad-break tick among [methods], all declared by [owner]: null when none of them is one, and a
 * refusal when several are. Taking none of several would carry the walk up to a parent's tick,
 * which the state never runs, and the poller would keep retrying with nothing to say so.
 */
internal fun <T : Method> adBreakTickAmong(methods: Iterable<T>, owner: String): T? {
    val ticks = methods.filter(::isAdBreakTick)
    if (ticks.isEmpty()) return null
    return ticks.singleOrPatchException("$PATCH: the ad-break tick, (state, int)J with a body, that $owner declares")
}

private fun isAdBreakTick(method: Method) = method.returnType == "J" && method.parameterTypes.size == 2 &&
    method.parameterTypes[1].toString() == "I" && method.implementation != null

/** The state whose poller the lookup keeps retrying. */
private const val DEFERRED_CARD_STATE = "UnresolvedWithDeferredCardState"

private const val FAILED_FUTURE = "failedAdFetch"

/** Counts each fetch the failed future answers, for the diagnostic report. */
private const val HELD_AD_BREAK_FETCH = "${REELS_AD_FILTER}->heldAdBreakFetch()V"

/** Adds, once per class, a static method that returns a failed future. */
private fun BytecodePatchContext.failedFutureOn(classType: String): String {
    val reference = "$classType->$FAILED_FUTURE()$SETTABLE_FUTURE"
    val classDef = mutableClassDefBy(classType)
    if (classDef.methods.any { it.name == FAILED_FUTURE }) return reference

    ImmutableMethod(
        classType,
        FAILED_FUTURE,
        emptyList(),
        SETTABLE_FUTURE,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(2),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                invoke-static { }, $HELD_AD_BREAK_FETCH
                new-instance v0, Ljava/io/IOException;
                const-string v1, "Reels ad fetch blocked"
                invoke-direct { v0, v1 }, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
                invoke-static { }, $SETTABLE_FUTURE->create()$SETTABLE_FUTURE
                move-result-object v1
                invoke-virtual { v1, v0 }, $SETTABLE_FUTURE->setException(Ljava/lang/Throwable;)Z
                return-object v1
            """,
        )
        classDef.methods.add(this)
    }

    return reference
}

/** Makes [method] return the failed future. It can write v0, because it returns at once. */
private fun BytecodePatchContext.returnFailedFuture(method: MutableMethod) =
    method.addInstructions(
        0,
        """
            invoke-static { }, ${failedFutureOn(method.definingClass)}
            move-result-object v0
            return-object v0
        """,
    )

/**
 * The last call before [log] that returns a future. This is the fetch that the log reports. A
 * call may declare either future type: 585's banner helper returns the SettableFuture it creates
 * (584's declared ListenableFuture), and the caller stores both into a ListenableFuture field.
 * 587 declares the video query as an obfuscated future class, so a call whose answer goes straight
 * into a ListenableFuture field counts too, whatever it declares. Matched by declared type alone,
 * that query was skipped and both logs led to the banner helper.
 */
internal fun List<Instruction>.futureCallBefore(log: String): MethodReference {
    val logIndex = indexOfFirst { it.string == log }
    check(logIndex >= 0) { "\"$log\" is not in the ad-break fetch" }

    return (logIndex - 1 downTo 0).firstNotNullOfOrNull { index ->
        this[index].methodReference?.takeIf {
            it.returnType == LISTENABLE_FUTURE || it.returnType == SETTABLE_FUTURE || storedAsFuture(index)
        }
    } ?: throw PatchException(
        "$PATCH: the ad-break fetch makes no call returning a future before \"$log\"",
    )
}

/** Whether the call at [call] has its answer moved out and put straight into a ListenableFuture field. */
private fun List<Instruction>.storedAsFuture(call: Int): Boolean {
    val result = getOrNull(call + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction
        ?: return false
    val store = getOrNull(call + 2)?.takeIf { it.opcode == Opcode.IPUT_OBJECT || it.opcode == Opcode.SPUT_OBJECT }
        ?: return false
    val field = (store as ReferenceInstruction).reference as? FieldReference ?: return false
    return field.type == LISTENABLE_FUTURE && (store as OneRegisterInstruction).registerA == result.registerA
}

/**
 * The executor call of a server API query that declares a narrower future than ListenableFuture:
 * the last call in it that hands back a SettableFuture, which the query then transforms into what
 * it returns. Static with its answer moved straight after, as the replacement is a static call of
 * the same size.
 */
internal fun queryExecutorCallIndex(instructions: List<Instruction>, method: String): Int {
    val execute = instructions.indices.lastOrNull { index ->
        instructions[index].methodReference?.returnType == SETTABLE_FUTURE
    } ?: throw PatchException("$PATCH: $method makes no call returning SettableFuture")
    return requireReplaceableExecutorCall(instructions, execute, method)
}

/**
 * The executor call of an ad query that runs on the shared GraphQL executor, the idle state's own
 * video ad query unless [literal] names another: the first call after the query's name that hands
 * back a SettableFuture. It has to be static with its answer moved straight after,
 * because the replacement is a static call of the same size.
 *
 * The name has to be the whole literal. The fingerprint found the method by a literal that only
 * holds it, since the patcher matches `strings` by containment, so a renamed query such as
 * "FBFetchReelsVideoAdsQueryV2" finds the method and leaves no literal to start from.
 */
internal fun idleExecutorCallIndex(
    instructions: List<Instruction>,
    method: String,
    literal: String = REELS_VIDEO_AD_QUERY,
): Int {
    val query = instructions.indexOfFirst { it.string == literal }
    if (query < 0) {
        throw PatchException(
            "$PATCH: $method holds no \"$literal\" literal, only a longer string the fingerprint " +
                "matched by containment, so it may not build the query this patch replaces",
        )
    }
    val execute = (query until instructions.size).firstOrNull { index ->
        instructions[index].methodReference?.returnType == SETTABLE_FUTURE
    } ?: throw PatchException(
        "$PATCH: $method makes no call returning SettableFuture after \"$literal\"",
    )
    return requireReplaceableExecutorCall(instructions, execute, method)
}

/** [execute], once it's a static call with its answer moved straight after. */
private fun requireReplaceableExecutorCall(instructions: List<Instruction>, execute: Int, method: String): Int {
    if (instructions[execute].opcode != Opcode.INVOKE_STATIC) {
        throw PatchException("$PATCH: the executor call at $execute in $method is not invoke-static")
    }
    if (instructions.getOrNull(execute + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException("$PATCH: the executor call at $execute in $method has no move-result-object after it")
    }
    return execute
}

private val Instruction.methodReference
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private val Instruction.string
    get() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun MutableMethod.instructions(): List<Instruction> =
    implementation?.instructions?.toList()
        ?: throw IllegalStateException("$definingClass->$name has no body")
