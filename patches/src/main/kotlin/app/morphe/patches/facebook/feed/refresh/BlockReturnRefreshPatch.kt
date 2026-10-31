/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.refresh

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.findMutableMethodOf
import app.morphe.util.literalReads
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val CONTROLLER = "FeedRefreshTriggerController"
private const val ON_REFRESH = "onRefresh"
private const val SKIP = "$EXTENSION_PACKAGE/feed/ReturnRefresh;->skip()Z"

/** The warm-start check's trace name, which no other method of 580 or 577 loads. */
internal const val WARM_START_CHECK = "maybeRefreshForWarmStart"

/** What the warm-start check logs before it loads an empty feed, on its is-empty side. */
internal const val EMPTY_FEED_LOAD = "doHeadLoadOnEmptyFeed"
private const val HOLD_WARM_START = "$EXTENSION_PACKAGE/feed/ReturnRefresh;->holdWarmStart()Z"

/** What the warm-start check answers when it doesn't refresh; its callers take it as skipped. */
internal const val WARM_START_SKIPPED = 2

/** The two keys where Facebook's reset to feed writes down what it decided on a return. */
internal const val RESET_TO_FEED_OUTCOME = "r2f_log_outcome"
internal const val RESET_TO_FEED_DESTINATION = "r2f_log_destination"
private const val LOG_RESET_TO_FEED =
    "$EXTENSION_PACKAGE/feed/ReturnRefresh;->resetToFeed(Ljava/lang/String;Ljava/lang/String;)V"
private const val CONTEXT = "Landroid/content/Context;"
private const val STRING = "Ljava/lang/String;"

/** What the feed's teardown runnable logs, which no other method of 580 or 577 loads. */
internal const val LEFT_APP_TEARDOWN = "onUserLeftApp runnable"
private const val KEEP_FEED_WHILE_AWAY = "$EXTENSION_PACKAGE/feed/ReturnRefresh;->keepFeedWhileAway()Z"

/** NewsFeedFragment's foreground auto-scroll decision, which keeps its name, and its no-scroll answer. */
internal const val AUTO_SCROLL_DECISION = "decideForegroundAutoScroll"
internal const val NO_AUTO_SCROLL = "NONE"
private const val HOLD_AUTO_SCROLL = "$EXTENSION_PACKAGE/feed/ReturnRefresh;->holdAutoScroll()Z"

/**
 * What the feed's stale-post executor logs as it re-ranks, which no other method of 577, 580 or 581
 * loads. NewsFeedFragment's onSetUserVisibleHint and the worker it posts on pause hand it their decision.
 */
internal const val STALE_POST_RE_RANK = "maybeRefreshStalePost-RE_RANK"
private const val HOLD_STALE_POST = "$EXTENSION_PACKAGE/feed/ReturnRefresh;->holdStalePost()Z"

/**
 * What the feed's hot-start check adds to its caller's source before it asks the warm-start check,
 * which no other method loads. onSetUserVisibleHint and the feed's refreshForRevisit call it.
 */
internal const val HOT_START_CHECK = "-maybeRefreshForHotStart"
private const val PATCH = "Block background-return feed refresh"
private const val NOTE_HOT_START = "$EXTENSION_PACKAGE/feed/ReturnRefresh;->hotStart()V"

/** The source NewsFeedTabDataFetch hands the forced refresh it runs once the tab's data is stale. */
internal const val TAB_DATA_FETCH = "NewsFeedTabDataFetchSpec"
private const val HOLD_TAB_AUTO_REFRESH = "$EXTENSION_PACKAGE/feed/ReturnRefresh;->holdTabAutoRefresh()Z"

/** What NewsFeedFragment's onTabEntered marks about the tab's badge, which no other onTabEntered loads. */
internal const val TAB_ENTRY_BADGED = "isTabEntryBadged"
private const val ON_TAB_ENTERED = "onTabEntered"

/**
 * The friendly feed's prefetch on a first tab entry. Only its loader loads it, the class onTabEntered
 * tests the feed's loader against before it reloads the friendly feed.
 */
internal const val FRIENDLY_FEED_PREFETCH = "friendlyFeedPrefetch"

/** The refresh cause onTabEntered hands the friendly feed's reload as Home comes back. */
internal const val HOT_LOAD = "HOT_LOAD"
private const val HOLD_TAB_ENTRY_HOT_LOAD = "$EXTENSION_PACKAGE/feed/ReturnRefresh;->holdTabEntryHotLoad()Z"

/**
 * What NewsFeedFragment's onBackPressed logs as it refreshes a feed already at the top, which no
 * other method of 584 or 585 loads.
 */
internal const val BACK_AT_TOP = "handleFeedRefreshOnBackPressedAtTop"
private const val ON_BACK_PRESSED = "onBackPressed"

/** The feed's is-it-at-the-top question and its scroll there, which keep their names. */
internal const val IS_AT_TOP = "isAtTop"
internal const val SCROLL_TO_TOP = "scrollToTop"

/** How far into the not-at-the-top block onBackPressed calls scrollToTop: 585 calls it second. */
internal const val SCROLL_TO_TOP_REACH = 4
private const val HOLD_SCROLL_TO_TOP = "$EXTENSION_PACKAGE/feed/HomeBack;->holdScrollToTop()Z"

/**
 * Stops what Facebook does to the feed when it returns to view: the refresh FeedRefreshTriggerController
 * fires, the feed's warm-start check, which NewsFeedFragment's onResume reaches and which refreshes
 * a feed left alone for a few minutes, and NewsFeedFragment's foreground auto-scroll. Each asks the
 * extension, which gives every check of one return the same answer. The warm-start threshold also
 * times a runnable that tears the feed down while Facebook is away, so a return after it loads new
 * posts whatever the checks say. That's why the feed still reset after 6.5, 7 and 11 minutes while
 * the resume callback was held. The runnable asks first too, and does nothing while the switch is
 * on. Facebook's reset to feed only logs what it decided, for a debug log to say whether it ran.
 *
 * Inside the app, switching back to Home reaches four more: the feed's hot-start check, its
 * stale-post executor (a re-rank or a refresh that clears the feed, from the tab's visibility
 * change and from the worker the feed posts on pause), NewsFeedTabDataFetch's AUTO_REFRESH once
 * the tab's data is stale and, on accounts with the friendly feed, the HOT_LOAD NewsFeedFragment's
 * onTabEntered starts once Home has been left longer than Facebook's limit. The executor, the data
 * fetch and the tab entry ask the extension and skip their refresh while it holds. The hot-start
 * check only tells the extension it's running: it asks the warm-start check next, and that check's
 * own question, past its empty-feed load, gets the in-app answer, so a feed that came back empty
 * still loads. The friendly feed's first load of a launch, which onTabEntered starts on the first
 * entry, is left alone: it's what fills the feed.
 *
 * Back on Home has a switch of its own (#247). NewsFeedFragment's onBackPressed scrolls a feed that
 * isn't at the top back to it and refreshes there, which loses the post you came back to. Right
 * after it learns the feed isn't at the top, it asks the extension, and a yes ends the press as
 * handled with the feed where it is. A feed at the top goes on to Facebook's code without asking.
 *
 * Every anchor is found and checked before anything changes, so a build that moved one refuses
 * the whole patch rather than keeping some hooks with its switch row hidden.
 */
@Suppress("unused")
val blockReturnRefreshPatch = bytecodePatch(
    name = "Block background-return feed refresh",
    description = "Keeps your place in the feed when you come back to Facebook within ten minutes, or any time " +
        "with No time limit on, so you don't lose what you were reading. A second switch, Back leaves Home where it " +
        "is, stops Back on a scrolled Home from jumping to the top and reloading. Both start off. Turn them on in " +
        "Hushfacebook settings > News feed.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val callbacks = classDefByStrings(CONTROLLER, StringComparisonType.EQUALS)
            .flatMap { it.methods.filter(::isReturnRefreshCallback) }
        val callbackFound = callbacks.singleOrNull() ?: throw PatchException(
            "Expected one FeedRefreshTriggerController resume callback holding $ON_REFRESH, found ${callbacks.size}",
        )
        val callback = mutableClassDefBy(callbackFound.definingClass).methods.first {
            it.name == callbackFound.name && it.parameterTypes == callbackFound.parameterTypes
        }

        val checks = classDefByStrings(WARM_START_CHECK, StringComparisonType.EQUALS)
            .flatMap { it.methods.filter(::isWarmStartCheck) }
        val check = checks.singleOrNull() ?: throw PatchException(
            "Expected one feed warm-start check holding $WARM_START_CHECK and $EMPTY_FEED_LOAD, found ${checks.size}",
        )

        val logs = classDefByStrings(RESET_TO_FEED_OUTCOME, StringComparisonType.EQUALS)
            .flatMap { it.methods.filter(::isResetToFeedLog) }
        val log = logs.singleOrNull() ?: throw PatchException(
            "Expected one static Context method writing $RESET_TO_FEED_OUTCOME and $RESET_TO_FEED_DESTINATION " +
                "from its own String parameters, found ${logs.size}",
        )

        val teardowns = classDefByStrings(LEFT_APP_TEARDOWN, StringComparisonType.EQUALS)
            .flatMap { it.methods.filter(::isLeftAppTeardown) }
        val teardown = teardowns.singleOrNull() ?: throw PatchException(
            "Expected one feed teardown runnable holding \"$LEFT_APP_TEARDOWN\", found ${teardowns.size}",
        )

        val decisions = mutableListOf<Method>()
        classDefForEach { classDef -> decisions += classDef.methods.filter(::isAutoScrollDecision) }
        val decision = decisions.singleOrNull() ?: throw PatchException(
            "Expected one $AUTO_SCROLL_DECISION(long, boolean, long, long), found ${decisions.size}",
        )

        // Inside the app: the Home tab coming back into view and the feed coming back from another
        // screen, which reach the stale-post executor, the hot-start check and the tab's data fetch.
        val executors = classDefByStrings(STALE_POST_RE_RANK, StringComparisonType.EQUALS)
            .flatMap { it.methods.filter(::isStalePostExecutor) }
        val executor = executors.singleOrNull() ?: throw PatchException(
            "Expected one static (owner, int, boolean) stale-post executor holding $STALE_POST_RE_RANK, found ${executors.size}",
        )

        val hotStarts = classDefByStrings(HOT_START_CHECK, StringComparisonType.EQUALS)
            .flatMap { it.methods.filter(::isHotStartCheck) }
        val hotStart = hotStarts.singleOrNull() ?: throw PatchException(
            "Expected one static (owner, String...) hot-start check holding $HOT_START_CHECK, found ${hotStarts.size}",
        )
        if (hotStart.implementation!!.instructions.none { callsMethod(it, check) }) {
            throw PatchException("The hot-start check ${hotStart.definingClass}->${hotStart.name} doesn't ask the warm-start check")
        }

        val fetches = classDefByStrings(TAB_DATA_FETCH, StringComparisonType.EQUALS)
            .flatMap { it.methods.filter(::isTabDataFetch) }
        val fetch = fetches.singleOrNull() ?: throw PatchException(
            "Expected one static NewsFeedTabDataFetch dispatch holding $TAB_DATA_FETCH, found ${fetches.size}",
        )

        val entries = classDefByStrings(TAB_ENTRY_BADGED, StringComparisonType.EQUALS)
            .flatMap { it.methods.filter(::isTabEntry) }
        val entry = entries.singleOrNull() ?: throw PatchException(
            "Expected one $ON_TAB_ENTERED holding $TAB_ENTRY_BADGED, found ${entries.size}",
        )
        val friendlyLoaders = classDefByStrings(FRIENDLY_FEED_PREFETCH, StringComparisonType.EQUALS).map { it.type }.toSet()
        val classDefOf = { type: String -> classDefByOrNull(type) }

        val backs = classDefByStrings(BACK_AT_TOP, StringComparisonType.EQUALS)
            .flatMap { it.methods.filter(::isHomeBack) }
        val back = backs.singleOrNull() ?: throw PatchException(
            "Expected one $ON_BACK_PRESSED()Z holding $BACK_AT_TOP, found ${backs.size}",
        )
        homeBackToTop(back)

        val warmStartCheck = mutableClassDefBy(check.definingClass).findMutableMethodOf(check)
        val resetLog = mutableClassDefBy(log.definingClass).findMutableMethodOf(log)
        val teardownRun = mutableClassDefBy(teardown.definingClass).findMutableMethodOf(teardown)
        val autoScroll = mutableClassDefBy(decision.definingClass).findMutableMethodOf(decision)
        val noScroll = mutableClassDefBy(decision.returnType)
        val stalePost = mutableClassDefBy(executor.definingClass).findMutableMethodOf(executor)
        val hotStartCheck = mutableClassDefBy(hotStart.definingClass).findMutableMethodOf(hotStart)
        val tabFetch = mutableClassDefBy(fetch.definingClass).findMutableMethodOf(fetch)
        val tabEntry = mutableClassDefBy(entry.definingClass).findMutableMethodOf(entry)
        val homeBack = mutableClassDefBy(back.definingClass).findMutableMethodOf(back)
        for (method in listOf(callback, teardownRun, autoScroll, stalePost)) method.requireLocals(PATCH, 1)
        emptyFeedAnswer(warmStartCheck)
        noAutoScrollAnswer(autoScroll, noScroll)
        tabAutoRefresh(tabFetch)
        tabEntryHotLoad(tabEntry, friendlyLoaders, classDefOf)

        callback.skipBriefReturnRefresh()
        warmStartCheck.holdWarmStartOfAFeedWithStories()
        resetLog.logResetToFeedFirst()
        teardownRun.keepFeedWhileAwayFirst()
        autoScroll.holdAutoScrollFirst(noScroll)
        stalePost.holdFirst(HOLD_STALE_POST)
        hotStartCheck.noteHotStartFirst()
        tabFetch.holdTabAutoRefreshBeforeIt()
        tabEntry.holdTabEntryHotLoadBeforeIt(friendlyLoaders, classDefOf)
        homeBack.holdBackToTopBeforeIt()
        enableStatus("returnRefresh")
    }
}

internal fun isReturnRefreshCallback(method: Method): Boolean =
    method.returnType == "V" && method.parameterTypes.size == 1 && method.implementation != null &&
        holdsString(method, CONTROLLER) && holdsString(method, ON_REFRESH)

/**
 * Whether [method] is the feed's warm-start check: an instance method answering an int, its last
 * parameter the caller's reason, that holds its trace name and the empty-feed load's. 580 takes a
 * threshold before the reason and 577 doesn't.
 */
internal fun isWarmStartCheck(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" &&
        method.parameterTypes.lastOrNull()?.toString() == STRING && method.implementation != null &&
        holdsString(method, WARM_START_CHECK) && holdsString(method, EMPTY_FEED_LOAD)

/**
 * Whether [method] is where the reset to feed writes down its outcome and destination: a static
 * method taking a Context first that writes both keys straight from its own String parameters.
 * 582's took just those two after the Context. 583's takes the runnable's exit before them and two
 * times after. Facebook's other writers of the keys put a literal under one of them.
 */
internal fun isResetToFeedLog(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.firstOrNull()?.toString() == CONTEXT && resetToFeedArguments(method) != null

/**
 * The declared parameters [method] writes under the reset to feed's outcome and destination keys,
 * by index, or null unless each key is loaded once and handed, by the call right after, to a
 * (String, String) write together with a String parameter nothing wrote over before that call.
 */
internal fun resetToFeedArguments(method: Method): Pair<Int, Int>? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    fun writtenFrom(key: String): Int? {
        val load = instructions.indices.singleOrNull {
            ((instructions[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == key
        } ?: return null
        val keyRegister = (instructions[load] as? OneRegisterInstruction)?.registerA ?: return null
        val call = (load + 1 until instructions.size).firstOrNull { instructions[it].opcode.name.startsWith("invoke") } ?: return null
        val target = (instructions[call] as ReferenceInstruction).reference as? MethodReference ?: return null
        val registers = instructions[call].callRegisters()
        if (target.parameterTypes.map { it.toString() } != listOf(STRING, STRING) || registers.size != 3 ||
            registers[1] != keyRegister
        ) return null
        val value = registers[2]
        val parameter = method.parameterTypes.indices.singleOrNull {
            method.parameterTypes[it].toString() == STRING && method.parameterRegisterNumber(it) == value
        } ?: return null
        return parameter.takeIf { instructions.take(call).none { writes(it, value) } }
    }
    val outcome = writtenFrom(RESET_TO_FEED_OUTCOME) ?: return null
    val destination = writtenFrom(RESET_TO_FEED_DESTINATION) ?: return null
    return (outcome to destination).takeIf { outcome != destination }
}

private fun Instruction.callRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** Whether [instruction] can write [register], a wide write counting for both halves. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister() || instruction !is OneRegisterInstruction) return false
    return instruction.registerA == register || (instruction.opcode.setsWideRegister() && instruction.registerA + 1 == register)
}

/**
 * The index of the move-result that takes the warm-start check's answer to "is the feed empty":
 * its first no-argument call of its own class answering a boolean. The answer is read once, by an
 * if-eqz that falls through to the empty-feed load, and nothing but that move-result leads to the
 * instruction after it. Throws naming what differs.
 */
internal fun emptyFeedAnswer(method: Method): Int {
    val what = "Block background-return feed refresh: ${method.definingClass}->${method.name}"
    val instructions = method.implementation?.instructions?.toList() ?: throw PatchException("$what has no body")
    val load = instructions.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == EMPTY_FEED_LOAD
    }
    val call = instructions.indexOfFirst { instruction ->
        val target = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        instruction.opcode == Opcode.INVOKE_DIRECT && target != null && target.definingClass == method.definingClass &&
            target.parameterTypes.isEmpty() && target.returnType == "Z"
    }
    if (call < 0 || call > load) throw PatchException("$what asks no is-the-feed-empty question before $EMPTY_FEED_LOAD")
    val answer = call + 1
    val moveResult = instructions.getOrNull(answer)
    if (moveResult?.opcode != Opcode.MOVE_RESULT) throw PatchException("$what doesn't keep the is-empty answer")
    val register = (moveResult as OneRegisterInstruction).registerA
    val reads = method.literalReads(answer)
    val test = reads.singleOrNull()?.let { instructions[it] }
    if (test?.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != register) {
        throw PatchException("$what reads the is-empty answer at ${reads.joinToString()}, expected one if-eqz")
    }
    val flow = ControlFlow.of(method)
    val testAt = reads.single()
    val nonEmpty = flow.normal[testAt].filter { it != testAt + 1 }
    if (nonEmpty.size != 1 || testAt + 1 > load || nonEmpty.single() <= load) {
        throw PatchException("$what doesn't load an empty feed on the is-empty side of its test")
    }
    val arrivals = flow.instructions.indices.filter { answer + 1 in flow.normal[it] || answer + 1 in flow.exceptional[it] }
    if (arrivals != listOf(answer)) {
        throw PatchException("$what reaches instruction ${answer + 1} from ${arrivals.joinToString()}, not only its move-result")
    }
    return answer
}

private fun MutableMethod.skipBriefReturnRefresh() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $SKIP
            move-result v0
            if-eqz v0, :keep
            return-void
        """,
        ExternalLabel("keep", getInstruction(0)),
    )
}

/**
 * Asks the extension right after the warm-start check learns the feed has stories, and answers
 * [WARM_START_SKIPPED], Facebook's own answer for a check that doesn't refresh, while it holds the
 * return. An empty feed goes on to Facebook's load without asking. The question borrows the
 * register the is-empty answer sits in: it's 0 whenever the question is asked, and 0 again, the
 * same false, whenever Facebook's code runs on, and nothing else reads it (see [emptyFeedAnswer]).
 */
internal fun MutableMethod.holdWarmStartOfAFeedWithStories() {
    val answer = emptyFeedAnswer(this)
    val empty = getInstruction<OneRegisterInstruction>(answer).registerA
    addInstructionsWithLabels(
        answer + 1,
        """
            if-nez v$empty, :facebook
            invoke-static { }, $HOLD_WARM_START
            move-result v$empty
            if-eqz v$empty, :facebook
            const/16 v$empty, $WARM_START_SKIPPED
            return v$empty
        """,
        ExternalLabel("facebook", getInstruction(answer + 1)),
    )
}

/** Whether [method] is the feed's teardown runnable: an instance run() that loads its log text. */
internal fun isLeftAppTeardown(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.name == "run" && method.returnType == "V" &&
        method.parameterTypes.isEmpty() && method.implementation != null && holdsString(method, LEFT_APP_TEARDOWN)

/** Whether [method] is the foreground auto-scroll decision: an instance (J, Z, J, J) answering an enum. */
internal fun isAutoScrollDecision(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.name == AUTO_SCROLL_DECISION &&
        method.returnType.startsWith("L") && method.implementation != null &&
        method.parameterTypes.map { it.toString() } == listOf("J", "Z", "J", "J")

/** The static field of [enum] its static initialiser stores [name]'s constant in, as a reference. */
internal fun enumConstant(enum: ClassDef, name: String): String? {
    val clinit = enum.methods.firstOrNull { it.name == "<clinit>" } ?: return null
    var last: String? = null
    for (instruction in clinit.implementation?.instructions?.toList().orEmpty()) {
        when (val reference = (instruction as? ReferenceInstruction)?.reference) {
            is StringReference -> last = reference.string
            is FieldReference -> if (instruction.opcode == Opcode.SPUT_OBJECT && reference.type == enum.type &&
                reference.definingClass == enum.type && last == name
            ) {
                return reference.toString()
            }
        }
    }
    return null
}

/**
 * The reference of the constant [decision] answers for no scroll: [enum]'s NONE, which it loads and
 * returns straight away. Throws naming what differs.
 */
internal fun noAutoScrollAnswer(decision: Method, enum: ClassDef): String {
    val what = "Block background-return feed refresh: ${decision.definingClass}->${decision.name}"
    val none = enumConstant(enum, NO_AUTO_SCROLL) ?: throw PatchException("$what: ${enum.type} has no $NO_AUTO_SCROLL")
    val body = decision.implementation?.instructions?.toList().orEmpty()
    val at = body.indexOfFirst {
        it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == none
    }
    val ret = body.getOrNull(at + 1)
    if (at < 0 || ret?.opcode != Opcode.RETURN_OBJECT ||
        (ret as OneRegisterInstruction).registerA != (body[at] as OneRegisterInstruction).registerA
    ) {
        throw PatchException("$what never answers $none")
    }
    return none
}

/**
 * Asks the extension first thing in the teardown runnable and returns without tearing the feed
 * down while the switch is on. The answer goes through v0: at index 0 no local holds anything yet.
 */
internal fun MutableMethod.keepFeedWhileAwayFirst() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEP_FEED_WHILE_AWAY
            move-result v0
            if-eqz v0, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * Asks the extension first thing in the auto-scroll decision and, while it holds the return, goes
 * to the decision's own NONE answer, skipping only its minute and second tests. The answer goes
 * through v0: at index 0 no local holds anything yet.
 */
internal fun MutableMethod.holdAutoScrollFirst(enum: ClassDef) {
    val none = noAutoScrollAnswer(this, enum)
    requireLocals(PATCH, 1)
    val at = implementation!!.instructions.indexOfFirst {
        it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == none
    }
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_AUTO_SCROLL
            move-result v0
            if-nez v0, :none
        """,
        ExternalLabel("none", getInstruction(at)),
    )
}

/** Whether [method] is the feed's stale-post executor: a static (its own class, int, boolean) void that logs its re-rank. */
internal fun isStalePostExecutor(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.implementation != null &&
        method.parameterTypes.map { it.toString() } == listOf(method.definingClass, "I", "Z") &&
        holdsString(method, STALE_POST_RE_RANK)

/**
 * Whether [method] is the feed's hot-start check: a static void or boolean taking its own class and
 * the caller's source first. 580 and 581 take a threshold after the source and answer nothing, 577
 * takes none and answers whether it refreshed.
 */
internal fun isHotStartCheck(method: Method): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType in setOf("V", "Z") &&
        method.implementation != null && parameters.size >= 2 && parameters[0] == method.definingClass &&
        parameters[1] == STRING && holdsString(method, HOT_START_CHECK)
}

/** Whether [method] is NewsFeedTabDataFetch's dispatch: a static void, its last parameter a boolean, that loads its source. */
internal fun isTabDataFetch(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.implementation != null &&
        method.parameterTypes.lastOrNull()?.toString() == "Z" && holdsString(method, TAB_DATA_FETCH)

/** Whether [instruction] calls [method]. */
private fun callsMethod(instruction: Instruction, method: Method): Boolean {
    val target = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return target.definingClass == method.definingClass && target.name == method.name &&
        target.returnType == method.returnType &&
        target.parameterTypes.map { it.toString() } == method.parameterTypes.map { it.toString() }
}

/**
 * Tells the extension first thing that the hot-start check is running, so the warm-start check it
 * asks next gets the in-app answer. It holds nothing itself: an empty feed still reaches Facebook's
 * load. The call takes no register.
 */
internal fun MutableMethod.noteHotStartFirst() {
    addInstructions(0, "invoke-static { }, $NOTE_HOT_START")
}

/**
 * Asks [extension] first thing and, while it holds, answers what Facebook's own method answers when
 * it does nothing: it returns, or answers false for a method that says whether it refreshed. The
 * answer goes through v0: at index 0 no local holds anything yet.
 */
internal fun MutableMethod.holdFirst(extension: String) {
    requireLocals(PATCH, 1)
    val nothing = if (returnType == "Z") "const/4 v0, 0x0\nreturn v0" else "return-void"
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $extension
            move-result v0
            if-eqz v0, :facebook
            $nothing
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/** NewsFeedTabDataFetch's forced refresh, by index, and a local free in front of it and after it. */
internal class TabAutoRefresh(val refresh: Int, val register: Int)

/**
 * The forced refresh of NewsFeedTabDataFetch's dispatch: the virtual (cause, String) void call that
 * comes first after the dispatch loads its source and takes it, with nothing between them but an
 * sget-object of the cause (582 loads it there, 583 at the head of the refresh's block). Only the
 * instruction before the call reaches it, so code put in front of the call runs on every way in,
 * where dexlib2 would leave a jump to the call landing on the call itself. A local that nothing
 * reads from the call on is free for a hook there. Throws naming what differs.
 */
internal fun tabAutoRefresh(method: Method): TabAutoRefresh {
    val what = "Block background-return feed refresh: ${method.definingClass}->${method.name}"
    val instructions = method.implementation?.instructions?.toList() ?: throw PatchException("$what has no body")
    val sources = instructions.indices.filter {
        ((instructions[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == TAB_DATA_FETCH
    }
    val source = sources.singleOrNull() ?: throw PatchException("$what loads $TAB_DATA_FETCH ${sources.size} times")
    val sourceRegister = (instructions[source] as OneRegisterInstruction).registerA
    val refresh = (source + 1 until minOf(instructions.size, source + 3)).firstOrNull {
        instructions[it].opcode.name.startsWith("invoke")
    } ?: throw PatchException("$what calls nothing right after loading its source")
    val call = instructions[refresh]
    val target = (call as? ReferenceInstruction)?.reference as? MethodReference
    if (call.opcode != Opcode.INVOKE_VIRTUAL || target == null || target.returnType != "V" ||
        target.parameterTypes.size != 2 || target.parameterTypes[1].toString() != STRING ||
        (call as FiveRegisterInstruction).registerCount != 3 || call.registerE != sourceRegister
    ) {
        throw PatchException("$what doesn't hand its source to a refresh right after loading it")
    }
    if (refresh == source + 2) {
        val between = instructions[source + 1]
        if (between.opcode != Opcode.SGET_OBJECT || (between as OneRegisterInstruction).registerA != call.registerD) {
            throw PatchException("$what does something besides loading its cause between its source and its refresh")
        }
    }
    val flow = ControlFlow.of(method)
    val arrivals = flow.instructions.indices.filter { refresh in flow.normal[it] || refresh in flow.exceptional[it] }
    if (arrivals != listOf(refresh - 1)) {
        throw PatchException("$what reaches its refresh from ${arrivals.joinToString()}, not only the instruction before it")
    }
    val liveness = RegisterLiveness.of(method)
    val busy = liveness.liveInto(refresh) + liveness.liveInto(refresh + 1)
    val register = (0 until method.localRegisterCount()).firstOrNull { it !in busy }
        ?: throw PatchException("$what has no local free around its refresh")
    return TabAutoRefresh(refresh, register)
}

/**
 * Asks the extension right before the forced refresh and, while it holds, goes on past the refresh
 * to what follows it, the data fetch's own callback. The question borrows a local nothing reads from
 * the refresh on (see [tabAutoRefresh]).
 */
internal fun MutableMethod.holdTabAutoRefreshBeforeIt() {
    val found = tabAutoRefresh(this)
    addInstructionsWithLabels(
        found.refresh,
        """
            invoke-static { }, $HOLD_TAB_AUTO_REFRESH
            move-result v${found.register}
            if-nez v${found.register}, :refreshed
        """,
        ExternalLabel("refreshed", getInstruction(found.refresh + 1)),
    )
}

/** Whether [method] is NewsFeedFragment's onTabEntered: an instance void of one parameter that marks the tab's badge. */
internal fun isTabEntry(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.name == ON_TAB_ENTERED && method.returnType == "V" &&
        method.parameterTypes.size == 1 && method.implementation != null && holdsString(method, TAB_ENTRY_BADGED)

/**
 * The index of the load of the cause onTabEntered hands the friendly feed's reload: the one
 * sget-object of an enum's [HOT_LOAD] (its class from [classDefOf]) followed by the fragment's own
 * virtual (cause) void call, which takes it with this. It comes after the instance-of that finds
 * the feed's loader is the friendly feed's, one of [friendlyLoaders], only the instruction before
 * it reaches it, and nothing after the call reads the cause's register before writing it. Throws
 * naming what differs.
 */
internal fun tabEntryHotLoad(method: Method, friendlyLoaders: Set<String>, classDefOf: (String) -> ClassDef?): Int {
    val what = "Block background-return feed refresh: ${method.definingClass}->${method.name}"
    val implementation = method.implementation ?: throw PatchException("$what has no body")
    val instructions = implementation.instructions.toList()
    val hotLoads = mutableMapOf<String, String?>()
    fun hotLoadOf(type: String) = hotLoads.getOrPut(type) { classDefOf(type)?.let { enumConstant(it, HOT_LOAD) } }
    val loads = instructions.indices.filter { at ->
        val field = (instructions[at] as? ReferenceInstruction)?.reference as? FieldReference
        val target = (instructions.getOrNull(at + 1) as? ReferenceInstruction)?.reference as? MethodReference
        instructions[at].opcode == Opcode.SGET_OBJECT && field != null && field.definingClass == field.type &&
            target != null && instructions[at + 1].opcode == Opcode.INVOKE_VIRTUAL &&
            target.definingClass == method.definingClass && target.returnType == "V" &&
            target.parameterTypes.map { it.toString() } == listOf(field.type) && hotLoadOf(field.type) == field.toString()
    }
    val load = loads.singleOrNull() ?: throw PatchException("$what hands its own refresh $HOT_LOAD ${loads.size} times")
    val register = (instructions[load] as OneRegisterInstruction).registerA
    val call = instructions[load + 1] as FiveRegisterInstruction
    if (call.registerCount != 2 || call.registerC != method.localRegisterCount() || call.registerD != register) {
        throw PatchException("$what doesn't hand $HOT_LOAD to its own refresh right after loading it")
    }
    val friendly = instructions.subList(0, load).any {
        it.opcode == Opcode.INSTANCE_OF && (it as ReferenceInstruction).reference.toString() in friendlyLoaders
    }
    if (!friendly) throw PatchException("$what doesn't check for the friendly feed's loader before its $HOT_LOAD")
    val flow = ControlFlow.of(method)
    val arrivals = flow.instructions.indices.filter { load in flow.normal[it] || load in flow.exceptional[it] }
    if (arrivals != listOf(load - 1)) {
        throw PatchException("$what reaches its $HOT_LOAD from ${arrivals.joinToString()}, not only the instruction before it")
    }
    if (register in RegisterLiveness.of(method).liveInto(load + 2)) {
        throw PatchException("$what reads v$register after its $HOT_LOAD")
    }
    return load
}

/**
 * Asks the extension in place of loading the friendly feed's HOT_LOAD and, while it holds, goes on
 * past the reload to the rest of the tab entry. The question takes no register and borrows the
 * cause's for its answer: the load writes it next and nothing after the reload reads it (see
 * [tabEntryHotLoad]).
 */
internal fun MutableMethod.holdTabEntryHotLoadBeforeIt(friendlyLoaders: Set<String>, classDefOf: (String) -> ClassDef?) {
    val load = tabEntryHotLoad(this, friendlyLoaders, classDefOf)
    val register = getInstruction<OneRegisterInstruction>(load).registerA
    addInstructionsWithLabels(
        load,
        """
            invoke-static { }, $HOLD_TAB_ENTRY_HOT_LOAD
            move-result v$register
            if-nez v$register, :entered
        """,
        ExternalLabel("entered", getInstruction(load + 2)),
    )
}

/** Whether [method] is NewsFeedFragment's onBackPressed: an instance boolean of no parameters that logs its refresh at the top. */
internal fun isHomeBack(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.name == ON_BACK_PRESSED && method.returnType == "Z" &&
        method.parameterTypes.isEmpty() && method.implementation != null && holdsString(method, BACK_AT_TOP)

/**
 * The index of the if-eqz in NewsFeedFragment's onBackPressed that sends a feed that isn't at the
 * top to its scroll there. It tests the answer of the method's first call of its own [IS_AT_TOP],
 * kept by the move-result right before it, and jumps to a block that calls its own [SCROLL_TO_TOP]
 * within [SCROLL_TO_TOP_REACH] instructions. Only that move-result reaches the test, so code put in
 * front of it runs on every way in. Throws naming what differs.
 */
internal fun homeBackToTop(method: Method): Int {
    val what = "Block background-return feed refresh: ${method.definingClass}->${method.name}"
    val instructions = method.implementation?.instructions?.toList() ?: throw PatchException("$what has no body")
    fun callsOwn(at: Int, name: String, returnType: String): Boolean {
        val instruction = instructions.getOrNull(at) ?: return false
        val target = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            target.definingClass == method.definingClass && target.name == name &&
            target.parameterTypes.isEmpty() && target.returnType == returnType
    }
    val ask = instructions.indices.firstOrNull { callsOwn(it, IS_AT_TOP, "Z") }
        ?: throw PatchException("$what never asks its own $IS_AT_TOP")
    val keep = instructions.getOrNull(ask + 1)
    if (keep?.opcode != Opcode.MOVE_RESULT) throw PatchException("$what doesn't keep the $IS_AT_TOP answer")
    val answer = (keep as OneRegisterInstruction).registerA
    val test = ask + 2
    val branch = instructions.getOrNull(test)
    if (branch?.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != answer) {
        throw PatchException("$what doesn't test the $IS_AT_TOP answer with an if-eqz right away")
    }
    val flow = ControlFlow.of(method)
    val notAtTop = flow.normal[test].singleOrNull { it != test + 1 }
        ?: throw PatchException("$what's $IS_AT_TOP test jumps nowhere")
    if ((notAtTop until notAtTop + SCROLL_TO_TOP_REACH).none { callsOwn(it, SCROLL_TO_TOP, "V") }) {
        throw PatchException("$what doesn't call its own $SCROLL_TO_TOP where a feed that isn't at the top goes")
    }
    val arrivals = flow.instructions.indices.filter { test in flow.normal[it] || test in flow.exceptional[it] }
    if (arrivals != listOf(test - 1)) {
        throw PatchException("$what reaches its $IS_AT_TOP test from ${arrivals.joinToString()}, not only its move-result")
    }
    return test
}

/**
 * Asks the extension right before onBackPressed sends a feed that isn't at the top to its scroll
 * there and, on a yes, answers true, the press handled, with the feed where it is. A feed at the top
 * goes on without asking. The question borrows the register the is-at-the-top answer sits in: it's
 * 0 whenever the question is asked and 0 again, the same false, whenever Facebook's test runs on
 * after it (see [homeBackToTop]).
 */
internal fun MutableMethod.holdBackToTopBeforeIt() {
    val test = homeBackToTop(this)
    val answer = getInstruction<OneRegisterInstruction>(test).registerA
    addInstructionsWithLabels(
        test,
        """
            if-nez v$answer, :facebook
            invoke-static { }, $HOLD_SCROLL_TO_TOP
            move-result v$answer
            if-eqz v$answer, :facebook
            return v$answer
        """,
        ExternalLabel("facebook", getInstruction(test)),
    )
}

/**
 * Hands the reset to feed's outcome and destination, the two parameters it writes under those keys,
 * to the extension's log first thing. Side by side, as on 583, they go as a range, which reads any
 * register.
 */
internal fun MutableMethod.logResetToFeedFirst() {
    val what = "Block background-return feed refresh: $definingClass->$name"
    val (outcome, destination) = resetToFeedArguments(this)
        ?: throw PatchException("$what no longer writes the reset to feed's outcome and destination from its parameters")
    val first = parameterRegisterNumber(outcome)
    val second = parameterRegisterNumber(destination)
    val registers = when {
        second == first + 1 -> "/range { ${parameterRegister(outcome)} .. ${parameterRegister(destination)} }"
        maxOf(first, second) < 16 -> " { ${parameterRegister(outcome)}, ${parameterRegister(destination)} }"
        else -> throw PatchException("$what keeps its outcome and destination in v$first and v$second, apart and past v15")
    }
    addInstructions(0, "invoke-static$registers, $LOG_RESET_TO_FEED")
}
