/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.refresh

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Block background-return feed refresh on every Facebook build the bundle declares. Each holds one
 * FeedRefreshTriggerController resume callback, one feed warm-start check, the only method loading
 * its trace name, and one place the reset to feed writes down its outcome. The patch, run on them,
 * asks the extension first in the callback, right after the warm-start check's is-the-feed-empty
 * answer, and first in the reset's log, and leaves the rest of each method as it was. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReturnRefreshFixtureTest {
    private val extension = "Lapp/morphe/extension/facebook/feed/ReturnRefresh;"
    private val skip = "$extension->skip()Z"
    private val holdWarmStart = "$extension->holdWarmStart()Z"
    private val resetToFeed = "$extension->resetToFeed(Ljava/lang/String;Ljava/lang/String;)V"
    private val keepFeedWhileAway = "$extension->keepFeedWhileAway()Z"
    private val holdAutoScroll = "$extension->holdAutoScroll()Z"
    private val noteHotStart = "$extension->hotStart()V"
    private val holdStalePost = "$extension->holdStalePost()Z"
    private val holdTabAutoRefresh = "$extension->holdTabAutoRefresh()Z"
    private val holdTabEntryHotLoad = "$extension->holdTabEntryHotLoad()Z"
    private val holdScrollToTop = "Lapp/morphe/extension/facebook/feed/HomeBack;->holdScrollToTop()Z"

    /** What the dex files holding the in-app checks' callers load: two kept method names and the pause worker's event. */
    private val callerTexts = setOf("onSetUserVisibleHint", "refreshForRevisit", "refresh_stale_post_on_pause")

    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference)?.toString()

    private fun Instruction.callRegisters(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun signature(method: Method) =
        method.definingClass + "->" + method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType

    /** Whether [method] answers [value] somewhere: a constant of it returned straight away. */
    private fun answers(method: Method, value: Int): Boolean = method.body().zipWithNext().any { (constant, ret) ->
        constant is NarrowLiteralInstruction && constant.narrowLiteral == value && ret.opcode == Opcode.RETURN &&
            (ret as OneRegisterInstruction).registerA == (constant as OneRegisterInstruction).registerA
    }

    @Test
    fun `the extension has the entries the patch calls, public and static`() {
        for (entry in listOf(skip, holdWarmStart, resetToFeed, keepFeedWhileAway, holdAutoScroll, noteHotStart,
            holdStalePost, holdTabAutoRefresh, holdTabEntryHotLoad, holdScrollToTop)) {
            val method = ExtensionDex.classDef(entry.substringBefore("->")).methods.associateBy(::signature)[entry]
            assertTrue("the extension has no $entry", method != null)
            assertTrue("$entry isn't public static",
                AccessFlags.PUBLIC.isSet(method!!.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }

    @Test
    fun `each declared build holds the feed on every check of a return`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun check(bundle: File) {
        val name = bundle.name
        val controllers = FixtureDex.classesHolding(bundle, "FeedRefreshTriggerController")
        val callbacks = controllers.flatMap { it.methods.filter(::isReturnRefreshCallback) }
        assertEquals("$name: resume callbacks: ${callbacks.map(::signature)}", 1, callbacks.size)

        // The warm-start check is the one method loading its trace name. Its own skip answers 2,
        // which is what the hook answers for it.
        val warmOwners = FixtureDex.classesHolding(bundle, WARM_START_CHECK)
        val holding = warmOwners.flatMap { owner -> owner.methods.filter { holdsString(it, WARM_START_CHECK) } }
        assertEquals("$name: methods loading \"$WARM_START_CHECK\": ${holding.map(::signature)}", 1, holding.size)
        val warmStart = holding.single()
        assertTrue("$name: ${signature(warmStart)} isn't the warm-start check", isWarmStartCheck(warmStart))
        assertTrue("$name: ${signature(warmStart)} never answers $WARM_START_SKIPPED", answers(warmStart, WARM_START_SKIPPED))
        // Throws on a shape the hook can't go into: see emptyFeedAnswer.
        val answer = emptyFeedAnswer(warmStart)
        val empty = (warmStart.body()[answer] as OneRegisterInstruction).registerA

        val logOwners = FixtureDex.classesHolding(bundle, RESET_TO_FEED_OUTCOME)
        val logs = logOwners.flatMap { it.methods.filter(::isResetToFeedLog) }
        assertEquals("$name: reset-to-feed logs: ${logs.map(::signature)}", 1, logs.size)
        val log = logs.single()

        // The runnable that tears the feed down once Facebook has been away as long as the
        // warm-start threshold is the one method loading its log text.
        val teardownOwners = FixtureDex.classesHolding(bundle, LEFT_APP_TEARDOWN)
        val teardowns = teardownOwners.flatMap { owner -> owner.methods.filter { holdsString(it, LEFT_APP_TEARDOWN) } }
        assertEquals("$name: methods loading \"$LEFT_APP_TEARDOWN\": ${teardowns.map(::signature)}", 1, teardowns.size)
        val teardown = teardowns.single()
        assertTrue("$name: ${signature(teardown)} isn't the teardown runnable", isLeftAppTeardown(teardown))

        // NewsFeedFragment's foreground auto-scroll decision, the one method of its name, answers
        // its enum's NONE last.
        val decisions = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == AUTO_SCROLL_DECISION } }) {
            it.name == AUTO_SCROLL_DECISION
        }
        assertEquals("$name: methods named $AUTO_SCROLL_DECISION: ${decisions.map(::signature)}", 1, decisions.size)
        val decision = decisions.single()
        assertTrue("$name: ${signature(decision)} isn't the auto-scroll decision", isAutoScrollDecision(decision))
        val scrollTypes = FixtureDex.classes(bundle, setOf(decision.definingClass, decision.returnType))
        val none = noAutoScrollAnswer(decision, scrollTypes.getValue(decision.returnType))
        val noneAt = decision.body().indexOfFirst { it.call() == none }
        assertEquals("$name: the NONE answer's return", Opcode.RETURN_OBJECT, decision.body()[noneAt + 1].opcode)

        // Inside the app: the stale-post executor, the hot-start check and NewsFeedTabDataFetch's
        // dispatch, each the one method of its shape loading its text.
        val executorOwners = FixtureDex.classesHolding(bundle, STALE_POST_RE_RANK)
        val executors = executorOwners.flatMap { it.methods.filter(::isStalePostExecutor) }
        assertEquals("$name: stale-post executors: ${executors.map(::signature)}", 1, executors.size)
        val hotOwners = FixtureDex.classesHolding(bundle, HOT_START_CHECK)
        val hotStarts = hotOwners.flatMap { it.methods.filter(::isHotStartCheck) }
        assertEquals("$name: hot-start checks: ${hotStarts.map(::signature)}", 1, hotStarts.size)
        val fetchOwners = FixtureDex.classesHolding(bundle, TAB_DATA_FETCH)
        val fetches = fetchOwners.flatMap { it.methods.filter(::isTabDataFetch) }
        assertEquals("$name: tab data fetch dispatches: ${fetches.map(::signature)}", 1, fetches.size)
        val executor = executors.single()
        val hotStart = hotStarts.single()
        val fetch = fetches.single()
        // Throws on a shape the hook can't go into: see tabAutoRefresh.
        val autoRefresh = tabAutoRefresh(fetch)

        // On accounts with the friendly feed: NewsFeedFragment's onTabEntered, the one onTabEntered
        // marking the tab's badge, reloads the friendly feed with HOT_LOAD once Home was left long
        // enough. The friendly feed's loader is the class holding its first-entry prefetch.
        val entryOwners = FixtureDex.classesHolding(bundle, TAB_ENTRY_BADGED)
        val entries = entryOwners.flatMap { it.methods.filter(::isTabEntry) }
        assertEquals("$name: tab entries: ${entries.map(::signature)}", 1, entries.size)
        val entry = entries.single()
        val friendlyOwners = FixtureDex.classesHolding(bundle, FRIENDLY_FEED_PREFETCH)
        val friendlyLoaders = friendlyOwners.map { it.type }.toSet()
        val enumTypes = entry.body().filter { it.opcode == Opcode.SGET_OBJECT }
            .map { (it as ReferenceInstruction).reference as FieldReference }
            .filter { it.definingClass == it.type }.map { it.type }.toSet()
        val entryTypes = FixtureDex.classes(bundle, enumTypes)
        // Throws on a shape the hook can't go into: see tabEntryHotLoad.
        val hotLoad = tabEntryHotLoad(entry, friendlyLoaders) { entryTypes[it] }
        val hotLoadRegister = (entry.body()[hotLoad] as OneRegisterInstruction).registerA

        // Back on a scrolled Home (#247): NewsFeedFragment's onBackPressed, the one method loading its
        // refresh-at-the-top text, sends a feed that isn't at the top to its scroll there.
        val backOwners = FixtureDex.classesHolding(bundle, BACK_AT_TOP)
        val backs = backOwners.flatMap { owner -> owner.methods.filter { holdsString(it, BACK_AT_TOP) } }
        assertEquals("$name: methods loading \"$BACK_AT_TOP\": ${backs.map(::signature)}", 1, backs.size)
        val back = backs.single()
        assertTrue("$name: ${signature(back)} isn't onBackPressed", isHomeBack(back))
        // Throws on a shape the hook can't go into: see homeBackToTop.
        val backTest = homeBackToTop(back)
        val atTop = (back.body()[backTest] as OneRegisterInstruction).registerA

        // The tab's visibility change and the worker the feed posts on pause hand the executor their
        // decision, and the visibility change and the revisit refresh ask the hot-start check.
        val targets = setOf(signature(executor), signature(hotStart))
        val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it in callerTexts } }) { method ->
            method.implementation?.instructions?.any { it.call() in targets } == true
        }
        fun callersOf(target: Method) = callers.filter { caller -> caller.body().any { it.call() == signature(target) } }
        assertTrue("$name: no onSetUserVisibleHint hands the executor a decision",
            callersOf(executor).any { it.name == "onSetUserVisibleHint" })
        assertTrue("$name: no pause worker hands the executor a decision",
            callersOf(executor).any { it.name == "run" && holdsString(it, "refresh_stale_post_on_pause") })
        assertTrue("$name: onSetUserVisibleHint doesn't ask the hot-start check",
            callersOf(hotStart).any { it.name == "onSetUserVisibleHint" })
        assertTrue("$name: refreshForRevisit doesn't ask the hot-start check",
            callersOf(hotStart).any { it.name == "refreshForRevisit" })

        val owners = (controllers + warmOwners + logOwners + teardownOwners + scrollTypes.values + executorOwners +
            hotOwners + fetchOwners + entryOwners + friendlyOwners + entryTypes.values + backOwners).distinctBy { it.type }
        val context = PatchContexts.of(owners + ExtensionDex.classDef(SETTINGS_STATUS))
        blockReturnRefreshPatch.execute(context)
        fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }

        val callback = patched(callbacks.single()).body()
        assertEquals("$name: the callback's first call", skip, callback[0].call())

        // Right after the is-empty answer: an empty feed goes on to Facebook's load, a feed with
        // stories asks, and a yes answers the skip. Both no's land on Facebook's next instruction.
        val before = warmStart.body()
        val after = patched(warmStart).body()
        val added = after.subList(answer + 1, answer + 7)
        assertEquals("$name: the hook", listOf(Opcode.IF_NEZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
            Opcode.CONST_16, Opcode.RETURN), added.map { it.opcode })
        assertEquals("$name: the question", holdWarmStart, added[1].call())
        assertEquals("$name: the registers", listOf(empty, empty, empty, empty, empty),
            listOf(added[0], added[2], added[3], added[4], added[5]).map { (it as OneRegisterInstruction).registerA })
        assertEquals("$name: the skip answer", WARM_START_SKIPPED, (added[4] as NarrowLiteralInstruction).narrowLiteral)
        val flow = ControlFlow.of(patched(warmStart))
        assertTrue("$name: an empty feed doesn't go on to Facebook's load", answer + 7 in flow.normal[answer + 1])
        assertTrue("$name: a no doesn't reach Facebook's code", answer + 7 in flow.normal[answer + 4])
        assertEquals("$name: one question", 1, after.count { it.call() == holdWarmStart })
        assertEquals("$name: the rest of the check", before.map { it.opcode },
            after.filterIndexed { index, _ -> index !in answer + 1..answer + 6 }.map { it.opcode })

        // The log hands on the two parameters it writes under the outcome and destination keys, on
        // 583 the two strings after the runnable's exit.
        val logBody = patched(log).body()
        assertEquals("$name: the log's first call", resetToFeed, logBody[0].call())
        val (outcome, destination) = resetToFeedArguments(log)!!
        assertEquals("$name: the outcome and destination", listOf("p2", "p3"),
            listOf(log.parameterRegister(outcome), log.parameterRegister(destination)))
        assertEquals("$name: what the log hands on", listOf(log.parameterRegisterNumber(outcome), log.parameterRegisterNumber(destination)),
            logBody[0].callRegisters())
        assertEquals("$name: the rest of the log", log.body().map { it.opcode }, logBody.drop(1).map { it.opcode })

        // The teardown asks first and returns without tearing down while the switch is on.
        val teardownBody = patched(teardown).body()
        assertEquals("$name: the teardown's first call", keepFeedWhileAway, teardownBody[0].call())
        assertEquals("$name: the teardown's hook", listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
            Opcode.RETURN_VOID), teardownBody.take(4).map { it.opcode })
        assertEquals("$name: the rest of the teardown", teardown.body().map { it.opcode }, teardownBody.drop(4).map { it.opcode })

        // The decision asks first and a yes lands on its own NONE answer.
        val decisionBody = patched(decision).body()
        assertEquals("$name: the decision's first call", holdAutoScroll, decisionBody[0].call())
        assertEquals("$name: the decision's hook", listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ),
            decisionBody.take(3).map { it.opcode })
        assertTrue("$name: a yes doesn't answer NONE", noneAt + 3 in ControlFlow.of(patched(decision)).normal[2])
        assertEquals("$name: NONE", none, decisionBody[noneAt + 3].call())
        assertEquals("$name: the rest of the decision", decision.body().map { it.opcode },
            decisionBody.drop(3).map { it.opcode })

        // The executor asks first and, on a yes, does nothing, as Facebook's own answer does.
        val executorBody = patched(executor).body()
        val hook = listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID)
        assertEquals("$name: the executor's first call", holdStalePost, executorBody[0].call())
        assertEquals("$name: the executor's hook", hook, executorBody.take(hook.size).map { it.opcode })
        assertEquals("$name: the rest of the executor", executor.body().map { it.opcode },
            executorBody.drop(hook.size).map { it.opcode })

        // The hot-start check only says it's running, then asks the warm-start check, whose own
        // question past its empty-feed load holds it: nothing in the hot-start check is skipped.
        val hotBody = patched(hotStart).body()
        assertEquals("$name: the hot-start check's first call", noteHotStart, hotBody[0].call())
        assertEquals("$name: the rest of the hot-start check", hotStart.body().map { it.opcode },
            hotBody.drop(1).map { it.opcode })
        assertTrue("$name: the hot-start check doesn't ask the warm-start check",
            hotBody.any { it.call() == signature(warmStart) })

        // The dispatch asks where it loaded the refresh's cause, in that register, and a yes lands
        // right after the refresh, on the data fetch's own callback.
        val fetchBody = patched(fetch).body()
        val refresh = autoRefresh.refresh
        assertEquals("$name: the dispatch's hook", listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ),
            fetchBody.subList(refresh, refresh + 3).map { it.opcode })
        assertEquals("$name: the dispatch's question", holdTabAutoRefresh, fetchBody[refresh].call())
        assertEquals("$name: the dispatch's registers", listOf(autoRefresh.register, autoRefresh.register),
            listOf(fetchBody[refresh + 1], fetchBody[refresh + 2]).map { (it as OneRegisterInstruction).registerA })
        val patchedFlow = ControlFlow.of(patched(fetch))
        assertTrue("$name: a yes goes past the refresh", refresh + 4 in patchedFlow.normal[refresh + 2])
        assertTrue("$name: every way to the refresh asks first",
            patchedFlow.instructions.indices.none { it != refresh + 2 && refresh + 3 in patchedFlow.normal[it] })
        assertEquals("$name: the refresh", fetch.body()[refresh].call(), fetchBody[refresh + 3].call())
        assertEquals("$name: the rest of the dispatch", fetch.body().map { it.opcode },
            fetchBody.filterIndexed { index, _ -> index !in refresh..refresh + 2 }.map { it.opcode })

        // The tab entry asks where it loaded HOT_LOAD, with a call that takes no register, answers in
        // that register, and a yes lands right after the reload, on the rest of the tab entry.
        val entryBody = patched(entry).body()
        assertEquals("$name: the tab entry's hook", listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ),
            entryBody.subList(hotLoad, hotLoad + 3).map { it.opcode })
        assertEquals("$name: the tab entry's question", holdTabEntryHotLoad, entryBody[hotLoad].call())
        assertEquals("$name: the question's registers", 0, (entryBody[hotLoad] as FiveRegisterInstruction).registerCount)
        assertEquals("$name: the tab entry's registers", listOf(hotLoadRegister, hotLoadRegister),
            listOf(entryBody[hotLoad + 1], entryBody[hotLoad + 2]).map { (it as OneRegisterInstruction).registerA })
        assertTrue("$name: a yes doesn't skip the reload", hotLoad + 5 in ControlFlow.of(patched(entry)).normal[hotLoad + 2])
        assertEquals("$name: the reload", entry.body()[hotLoad + 1].call(), entryBody[hotLoad + 4].call())
        assertEquals("$name: one tab entry question", 1, entryBody.count { it.call() == holdTabEntryHotLoad })
        assertEquals("$name: the rest of the tab entry", entry.body().map { it.opcode },
            entryBody.filterIndexed { index, _ -> index !in hotLoad..hotLoad + 2 }.map { it.opcode })

        // Right before onBackPressed's not-at-the-top jump: a feed at the top goes on to it, one that
        // isn't asks, and a yes answers the press handled. Both no's land on Facebook's own test.
        val backBody = patched(back).body()
        val backHook = backBody.subList(backTest, backTest + 5)
        assertEquals("$name: the Back hook", listOf(Opcode.IF_NEZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
            Opcode.RETURN), backHook.map { it.opcode })
        assertEquals("$name: the Back question", holdScrollToTop, backHook[1].call())
        assertEquals("$name: the Back registers", List(4) { atTop },
            listOf(backHook[0], backHook[2], backHook[3], backHook[4]).map { (it as OneRegisterInstruction).registerA })
        val backFlow = ControlFlow.of(patched(back))
        assertTrue("$name: a feed at the top doesn't go on to Facebook's test", backTest + 5 in backFlow.normal[backTest])
        assertTrue("$name: a no doesn't reach Facebook's test", backTest + 5 in backFlow.normal[backTest + 3])
        assertEquals("$name: Facebook's test", Opcode.IF_EQZ, backBody[backTest + 5].opcode)
        assertEquals("$name: one Back question", 1, backBody.count { it.call() == holdScrollToTop })
        assertEquals("$name: the rest of onBackPressed", back.body().map { it.opcode },
            backBody.filterIndexed { index, _ -> index !in backTest until backTest + 5 }.map { it.opcode })
    }
}
