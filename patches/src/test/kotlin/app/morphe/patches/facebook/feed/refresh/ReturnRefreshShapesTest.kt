/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.refresh

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Block background-return feed refresh that need no Facebook build: which methods the
 * anchors take as the warm-start check and the reset to feed's log, where the warm-start hook goes
 * and what it leaves, and the check shapes it refuses.
 */
class ReturnRefreshShapesTest {
    private val string = "Ljava/lang/String;"
    private val context = "Landroid/content/Context;"

    private fun method(
        body: String,
        parameters: List<String> = listOf(string),
        returnType: String = "I",
        static: Boolean = false,
        registers: Int = 7,
        name: String = "A05",
        owner: String = "Lfixture/FeedLoader;",
    ) = MutableMethod(
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
            null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    /** The check as 577 and 580 have it: is the feed empty, then the empty-feed load or the rest. */
    private fun warmStart(beforeTest: String = "", test: String = "if-eqz v1, :stories", stories: String = "") = method(
        """
            const-string v0, "$WARM_START_CHECK"
            invoke-direct { p0 }, Lfixture/FeedLoader;->A03()Z
            move-result v1
            const/4 v3, 0x1
            $beforeTest
            $test
            const-string v0, "$EMPTY_FEED_LOAD"
            const/4 v0, 0x0
            return v0
            :stories
            $stories
            const/4 v0, 0x2
            return v0
        """,
    )

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    /** A tab data fetch dispatch with [locals] locals, the owner, the data and the boolean after them. */
    private fun dispatch(body: String, locals: Int = 3) = method(
        body, listOf("Lfixture/Fetch;", "Lfixture/Data;", "Z"), "V", static = true, registers = locals + 3,
    )

    private val refreshCall = "invoke-virtual { p1, v2, v0 }, Lfixture/Data;->A0N(Lfixture/Cause;Ljava/lang/String;)V"

    @Test
    fun `the tab data fetch asks right before its refresh, which 583 reaches only by a jump to the cause`() {
        // 583: the refresh's block starts with the cause, which only the jump reaches, and logs the
        // refresh before it loads the source.
        val hoisted = dispatch(
            """
                if-nez p2, :refresh
                return-void
                :refresh
                sget-object v2, Lfixture/Cause;->A02:Lfixture/Cause;
                const-string v0, "AUTO_REFRESH"
                const-string v0, "$TAB_DATA_FETCH"
                $refreshCall
                return-void
            """,
        )
        val found = tabAutoRefresh(hoisted)
        assertEquals("the refresh and the free local", 5 to 1, found.refresh to found.register)
        hoisted.holdTabAutoRefreshBeforeIt()
        val body = hoisted.body()
        assertEquals("Lapp/morphe/extension/facebook/feed/ReturnRefresh;->holdTabAutoRefresh()Z",
            (body[5] as ReferenceInstruction).reference.toString())
        assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.INVOKE_VIRTUAL),
            body.subList(6, 9).map { it.opcode })
        assertEquals(listOf(1, 1), listOf(body[6], body[7]).map { (it as OneRegisterInstruction).registerA })
        val flow = ControlFlow.of(hoisted)
        assertTrue("a yes goes past the refresh", 9 in flow.normal[7])
        assertTrue("the jump still lands on the cause", 2 in flow.normal[0])

        // 582: the cause right after the source.
        assertEquals(3 to 1, tabAutoRefresh(dispatch(
            """
                const/4 v1, 0x0
                const-string v0, "$TAB_DATA_FETCH"
                sget-object v2, Lfixture/Cause;->A02:Lfixture/Cause;
                $refreshCall
                return-void
            """,
        )).let { it.refresh to it.register })

        val refused = mapOf(
            "a jump straight to the refresh" to dispatch(
                """
                    if-nez p2, :refresh
                    const-string v0, "$TAB_DATA_FETCH"
                    sget-object v2, Lfixture/Cause;->A02:Lfixture/Cause;
                    :refresh
                    $refreshCall
                    return-void
                """,
            ),
            "something else between the source and the refresh" to dispatch(
                """
                    const-string v0, "$TAB_DATA_FETCH"
                    const/4 v1, 0x0
                    $refreshCall
                    return-void
                """,
            ),
            "no local free" to dispatch(
                """
                    sget-object v1, Lfixture/Cause;->A02:Lfixture/Cause;
                    const-string v0, "$TAB_DATA_FETCH"
                    invoke-virtual { p0, v1, v0 }, Lfixture/Data;->A0N(Lfixture/Cause;Ljava/lang/String;)V
                    return-void
                """,
                locals = 2,
            ),
        )
        for ((shape, fetch) in refused) {
            val count = fetch.body().size
            assertThrows(shape, PatchException::class.java) { fetch.holdTabAutoRefreshBeforeIt() }
            assertEquals("$shape changed", count, fetch.body().size)
        }
    }

    @Test
    fun `the warm-start check is the instance int method over a reason holding both names`() {
        assertTrue(isWarmStartCheck(warmStart()))
        val empty = """
            const-string v0, "$EMPTY_FEED_LOAD"
            const/4 v0, 0x0
            return v0
        """
        assertFalse(isWarmStartCheck(method(empty)))
        assertFalse(isWarmStartCheck(method("const-string v0, \"$WARM_START_CHECK\"\nconst/4 v0, 0x0\nreturn v0")))
        val both = "const-string v0, \"$WARM_START_CHECK\"\nconst-string v0, \"$EMPTY_FEED_LOAD\"\n"
        assertTrue(isWarmStartCheck(method(both + "const/4 v0, 0x0\nreturn v0", parameters = listOf("J", string))))
        assertFalse(isWarmStartCheck(method(both + "const/4 v0, 0x0\nreturn v0", static = true)))
        assertFalse(isWarmStartCheck(method(both + "const/4 v0, 0x0\nreturn v0", parameters = listOf(string, "J"))))
        assertFalse(isWarmStartCheck(method(both + "return-void", returnType = "V")))
    }

    @Test
    fun `the hook asks once the feed has stories and answers the skip while the return holds`() {
        val check = warmStart()
        val before = check.body()
        assertEquals(2, emptyFeedAnswer(check))
        check.holdWarmStartOfAFeedWithStories()
        val after = check.body()
        assertEquals(listOf(Opcode.IF_NEZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_16,
            Opcode.RETURN), after.subList(3, 9).map { it.opcode })
        assertEquals("Lapp/morphe/extension/facebook/feed/ReturnRefresh;->holdWarmStart()Z",
            (after[4] as ReferenceInstruction).reference.toString())
        assertEquals(listOf(1, 1, 1, 1, 1),
            listOf(after[3], after[5], after[6], after[7], after[8]).map { (it as OneRegisterInstruction).registerA })
        assertEquals(WARM_START_SKIPPED, (after[7] as NarrowLiteralInstruction).narrowLiteral)
        // Both no's land on the check's own next instruction, and its test still lands on the rest.
        val flow = ControlFlow.of(check)
        assertEquals(setOf(4, 9), flow.normal[3].toSet())
        assertEquals(setOf(7, 9), flow.normal[6].toSet())
        assertEquals(Opcode.IF_EQZ, after[10].opcode)
        assertEquals(setOf(11, 14), flow.normal[10].toSet())
        assertEquals(before.map { it.opcode }, after.filterIndexed { index, _ -> index !in 3..8 }.map { it.opcode })
    }

    @Test
    fun `a check the hook can't be sure of is refused and left as it was`() {
        val shapes = mapOf(
            // The answer read again past the test: borrowing its register would change that read.
            "read twice" to warmStart(stories = "invoke-static { v1 }, Lfixture/Log;->note(Z)V"),
            // Tested the other way, so the empty-feed load is on the branch.
            "if-nez" to warmStart(test = "if-nez v1, :stories"),
            // Another way into the instruction after the answer, which the hook would sit in front of.
            "loop" to method(
                """
                    const-string v0, "$WARM_START_CHECK"
                    invoke-direct { p0 }, Lfixture/FeedLoader;->A03()Z
                    move-result v1
                    :again
                    const/4 v3, 0x1
                    if-eqz v1, :stories
                    const-string v0, "$EMPTY_FEED_LOAD"
                    const/4 v0, 0x0
                    return v0
                    :stories
                    if-nez v3, :again
                    const/4 v0, 0x2
                    return v0
                """,
            ),
            // No is-empty question before the load.
            "no question" to method(
                """
                    const-string v0, "$WARM_START_CHECK"
                    const-string v0, "$EMPTY_FEED_LOAD"
                    invoke-direct { p0 }, Lfixture/FeedLoader;->A03()Z
                    move-result v1
                    return v1
                """,
            ),
        )
        for ((shape, check) in shapes) {
            val count = check.body().size
            assertThrows("$shape went in", PatchException::class.java) { check.holdWarmStartOfAFeedWithStories() }
            assertEquals("$shape changed", count, check.body().size)
        }
    }

    /** A log writing [outcome] and [destination] under the two keys, each the way Facebook's write takes them. */
    private fun keys(outcome: String, destination: String, before: String = "") = """
        const/4 v0, 0x0
        $before
        const-string v1, "$RESET_TO_FEED_OUTCOME"
        invoke-virtual { v0, v1, $outcome }, Lfixture/Log;->A0A(Ljava/lang/String;Ljava/lang/String;)V
        const-string v1, "$RESET_TO_FEED_DESTINATION"
        invoke-virtual { v0, v1, $destination }, Lfixture/Log;->A0A(Ljava/lang/String;Ljava/lang/String;)V
        return-void
    """

    private val resetToFeed = "Lapp/morphe/extension/facebook/feed/ReturnRefresh;->resetToFeed(Ljava/lang/String;Ljava/lang/String;)V"

    @Test
    fun `the reset to feed's log is the static Context method writing both keys from its own strings`() {
        val parameters582 = listOf(context, string, string)
        // 583: the runnable's exit, then the outcome and destination, then two times.
        val parameters583 = listOf(context, string, string, string, "J", "J")
        fun log(body: String, parameters: List<String> = parameters582, static: Boolean = true) = method(
            body, parameters, "V", static = static, registers = 2 + parameters.sumOf { if (it == "J") 2 else 1 } + (if (static) 0 else 1),
        )
        assertEquals(1 to 2, resetToFeedArguments(log(keys("p1", "p2"))))
        assertTrue(isResetToFeedLog(log(keys("p1", "p2"))))
        assertEquals(2 to 3, resetToFeedArguments(log(keys("p2", "p3"), parameters583)))
        assertTrue(isResetToFeedLog(log(keys("p2", "p3"), parameters583)))

        assertFalse("an instance method", isResetToFeedLog(log(keys("p2", "p3"), static = false)))
        assertFalse("no Context first", isResetToFeedLog(log(keys("p1", "p2"), listOf(string, string, string))))
        // 583's other writers put "reset" or "none" under one of the keys.
        assertFalse("a literal outcome", isResetToFeedLog(log(keys("v0", "p2", before = "const-string v0, \"reset\""))))
        assertFalse("a literal destination", isResetToFeedLog(log(keys("p1", "v0", before = "const-string v0, \"none\""))))
        assertFalse("a parameter written over first", isResetToFeedLog(log(keys("p1", "p2", before = "const-string p1, \"reset\""))))
        assertFalse("one parameter under both keys", isResetToFeedLog(log(keys("p1", "p1"))))
        assertFalse("only the outcome", isResetToFeedLog(log("""
            const/4 v0, 0x0
            const-string v1, "$RESET_TO_FEED_OUTCOME"
            invoke-virtual { v0, v1, p1 }, Lfixture/Log;->A0A(Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """)))

        // Side by side, the outcome and destination go to the extension first thing as a range.
        for ((parameters, body, start) in listOf(Triple(parameters582, keys("p1", "p2"), 3), Triple(parameters583, keys("p2", "p3"), 4))) {
            val hooked = log(body, parameters)
            val count = hooked.body().size
            hooked.logResetToFeedFirst()
            val first = hooked.body()[0]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, first.opcode)
            assertEquals(resetToFeed, (first as ReferenceInstruction).reference.toString())
            assertEquals("$parameters", start to 2, (first as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
            assertEquals(count + 1, hooked.body().size)
        }

        // Apart, they go one by one, in the order the extension takes them.
        val apart = log(keys("p4", "p1"), listOf(context, string, "J", string))
        apart.logResetToFeedFirst()
        val first = apart.body()[0] as FiveRegisterInstruction
        assertEquals(resetToFeed, (first as ReferenceInstruction).reference.toString())
        assertEquals(listOf(6, 3), listOf(first.registerC, first.registerD).take(first.registerCount))

        // A method that stopped writing them is refused, and left alone.
        val stopped = log(keys("v0", "p2", before = "const-string v0, \"reset\""))
        val count = stopped.body().size
        assertThrows(PatchException::class.java) { stopped.logResetToFeedFirst() }
        assertEquals(count, stopped.body().size)
    }

    @Test
    fun `the teardown runnable is the instance run() holding its log text, and it asks first`() {
        val body = """
            iget-object v0, p0, Lfixture/Teardown;->A00:Ljava/lang/Object;
            const-string v0, "$LEFT_APP_TEARDOWN"
            return-void
        """
        val plain = """
            const-string v0, "$LEFT_APP_TEARDOWN"
            return-void
        """
        fun run(static: Boolean = false, name: String = "run") =
            method(if (static) plain else body, emptyList(), "V", static, registers = 2, name = name, owner = "Lfixture/Teardown;")
        assertTrue(isLeftAppTeardown(run()))
        assertFalse(isLeftAppTeardown(run(static = true)))
        assertFalse(isLeftAppTeardown(run(name = "A00")))
        assertFalse(isLeftAppTeardown(method(plain, listOf(string), "V", registers = 3, name = "run", owner = "Lfixture/Teardown;")))
        assertFalse(isLeftAppTeardown(method("return-void", emptyList(), "V", registers = 2, name = "run")))

        val teardown = run()
        teardown.keepFeedWhileAwayFirst()
        val after = teardown.body()
        assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID, Opcode.IGET_OBJECT),
            after.take(5).map { it.opcode })
        assertEquals("Lapp/morphe/extension/facebook/feed/ReturnRefresh;->keepFeedWhileAway()Z",
            (after[0] as ReferenceInstruction).reference.toString())
        assertEquals(setOf(3, 4), ControlFlow.of(teardown).normal[2].toSet())

        // A runnable with no local to borrow is refused as it was.
        val tight = method(body.replace("v0", "p0"), emptyList(), "V", registers = 1, name = "run", owner = "Lfixture/Teardown;")
        assertThrows(PatchException::class.java) { tight.keepFeedWhileAwayFirst() }
        assertEquals(3, tight.body().size)
    }

    private val scroll = "Lfixture/Scroll;"

    /** The enum the decision answers, built the way Facebook's is: each name loaded just before its constant is stored. */
    private fun scrollEnum(vararg names: String): ImmutableClassDef {
        val constants = names.withIndex().joinToString("") { (index, name) ->
            """
                const-string v1, "$name"
                const/4 v0, $index
                new-instance v2, $scroll
                invoke-direct { v2, v1, v0 }, $scroll-><init>(Ljava/lang/String;I)V
                sput-object v2, $scroll->A0$index:$scroll
            """
        }
        val clinit = method(
            "$constants\n                return-void", emptyList(), "V", static = true, registers = 3, name = "<clinit>", owner = scroll,
        )
        return ImmutableClassDef(
            scroll, AccessFlags.PUBLIC.value or AccessFlags.ENUM.value, "Ljava/lang/Enum;", null, null, null, null, listOf(clinit),
        )
    }

    /** The decision as 577 and 580 have it: STANDARD past the minutes, ADS past the seconds, NONE last. */
    private fun decision(none: String = "A02", static: Boolean = false, parameters: List<String> = listOf("J", "Z", "J", "J")) = method(
        """
            if-eqz p3, :ads
            sget-object v0, $scroll->A00:$scroll
            return-object v0
            :ads
            if-eqz p3, :none
            sget-object v0, $scroll->A01:$scroll
            return-object v0
            :none
            sget-object v0, $scroll->$none:$scroll
            return-object v0
        """,
        parameters, scroll, static, registers = 12, name = AUTO_SCROLL_DECISION, owner = "Lfixture/AutoScroll;",
    )

    @Test
    fun `the auto-scroll decision asks first and a yes answers its own NONE`() {
        assertTrue(isAutoScrollDecision(decision()))
        assertFalse(isAutoScrollDecision(decision(static = true)))
        assertFalse(isAutoScrollDecision(decision(parameters = listOf("J", "Z", "J"))))
        val enum = scrollEnum("STANDARD", "ADS", "NONE")
        assertEquals("$scroll->A02:$scroll", noAutoScrollAnswer(decision(), enum))

        val check = decision()
        val before = check.body()
        check.holdAutoScrollFirst(enum)
        val after = check.body()
        assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ), after.take(3).map { it.opcode })
        assertEquals("Lapp/morphe/extension/facebook/feed/ReturnRefresh;->holdAutoScroll()Z",
            (after[0] as ReferenceInstruction).reference.toString())
        assertEquals(setOf(3, 9), ControlFlow.of(check).normal[2].toSet())
        assertEquals("$scroll->A02:$scroll", (after[9] as ReferenceInstruction).reference.toString())
        assertEquals(before.map { it.opcode }, after.drop(3).map { it.opcode })

        // No NONE in the enum, or none the decision answers: refused, left as it was.
        for (refused in listOf(decision() to scrollEnum("STANDARD", "ADS"), decision(none = "A01") to enum)) {
            val count = refused.first.body().size
            assertThrows(PatchException::class.java) { refused.first.holdAutoScrollFirst(refused.second) }
            assertEquals(count, refused.first.body().size)
        }
    }

    private val feed = "Lfixture/NewsFeed;"

    /** NewsFeedFragment's onBackPressed as 584 and 585 have it: is the feed at the top, then the refresh there or the scroll. */
    private fun homeBack(
        ask: String = "invoke-virtual { p0 }, $feed->isAtTop()Z",
        beforeTest: String = "",
        test: String = "if-eqz v0, :scroll",
        scroll: String = "invoke-virtual { p0 }, $feed->scrollToTop()V",
        name: String = "onBackPressed",
        parameters: List<String> = emptyList(),
        static: Boolean = false,
    ) = method(
        """
            invoke-virtual { p0 }, $feed->dispatchOnBackPressed()Z
            move-result v0
            const/4 v1, 0x1
            if-nez v0, :done
            $ask
            move-result v0
            $beforeTest
            $test
            const-string v2, "$BACK_AT_TOP"
            invoke-virtual { p0 }, $feed->A0R()V
            return v1
            :scroll
            invoke-static { p0 }, $feed->A0C(Lfixture/NewsFeed;)Lfixture/Bar;
            $scroll
            :done
            return v1
        """,
        parameters, "Z", static, registers = 4, name = name, owner = feed,
    )

    @Test
    fun `onBackPressed asks right before a feed that isn't at the top scrolls there and a yes ends the press`() {
        assertTrue(isHomeBack(homeBack()))
        assertFalse(isHomeBack(homeBack(name = "onBackPressedAtTop")))
        assertFalse(isHomeBack(homeBack(parameters = listOf(feed), static = true)))
        assertFalse(isHomeBack(homeBack(parameters = listOf("Z"))))
        assertEquals(6, homeBackToTop(homeBack()))

        val back = homeBack()
        val before = back.body()
        back.holdBackToTopBeforeIt()
        val after = back.body()
        assertEquals(listOf(Opcode.IF_NEZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN,
            Opcode.IF_EQZ), after.subList(6, 12).map { it.opcode })
        assertEquals("Lapp/morphe/extension/facebook/feed/HomeBack;->holdScrollToTop()Z",
            (after[7] as ReferenceInstruction).reference.toString())
        assertEquals(List(5) { 0 }, listOf(6, 8, 9, 10, 11).map { (after[it] as OneRegisterInstruction).registerA })
        val flow = ControlFlow.of(back)
        assertEquals("a feed at the top goes on to Facebook's test", setOf(7, 11), flow.normal[6].toSet())
        assertEquals("a no goes on to Facebook's test", setOf(10, 11), flow.normal[9].toSet())
        assertEquals("Facebook's test still jumps to the scroll", setOf(12, 15), flow.normal[11].toSet())
        assertEquals(before.map { it.opcode }, after.filterIndexed { index, _ -> index !in 6..10 }.map { it.opcode })

        val refused = mapOf(
            "another class's isAtTop" to homeBack(ask = "invoke-virtual { p0 }, Lfixture/Other;->isAtTop()Z"),
            "something between the answer and its test" to homeBack(beforeTest = "const/4 v3, 0x0"),
            "an if-nez test" to homeBack(test = "if-nez v0, :scroll"),
            "no scrollToTop where the test jumps" to homeBack(scroll = "invoke-virtual { p0 }, $feed->A0D()V"),
            "scrollToTop too far into the block" to homeBack(
                scroll = "nop\nnop\nnop\ninvoke-virtual { p0 }, $feed->scrollToTop()V",
            ),
            "a jump to the test" to homeBack(
                beforeTest = ":again",
                scroll = "invoke-virtual { p0 }, $feed->scrollToTop()V\nif-eqz v1, :again",
            ),
        )
        for ((shape, method) in refused) {
            val count = method.body().size
            assertThrows(shape, PatchException::class.java) { method.holdBackToTopBeforeIt() }
            assertEquals("$shape changed", count, method.body().size)
        }
    }
}
