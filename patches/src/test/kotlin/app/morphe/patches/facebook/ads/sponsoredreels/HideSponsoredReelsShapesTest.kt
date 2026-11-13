/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Hide sponsored reels that need no Facebook build: which register each page filter
 * reads, whether the method is static or not, and the refusals when an anchor inside a method is
 * missing, longer than the literal, or doubled.
 */
class HideSponsoredReelsShapesTest {
    private val collection = "Ljava/util/Collection;"
    private val list = "Ljava/util/List;"
    private val withoutAds = "Lapp/morphe/extension/facebook/ads/ReelsAdFilter;->" +
        "withoutAds(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;"
    private val withoutAdSections = "Lapp/morphe/extension/facebook/ads/ReelsAdFilter;->" +
        "withoutAdSections(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"
    private val settableFuture = "Lcom/google/common/util/concurrent/SettableFuture;"
    private val listenableFuture = "Lcom/google/common/util/concurrent/ListenableFuture;"

    private fun method(
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        static: Boolean,
        smali: String,
        definingClass: String = "Lfixture/Items;",
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            definingClass,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0),
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    private val Instruction.call get() = ((this as ReferenceInstruction).reference as MethodReference).toString()

    /**
     * The five instructions a page filter puts at the top of the method: the page copied down from
     * [page] into a local, the class name into a second, the filter call on the two, and its answer
     * copied back into [page]. Answers the two locals.
     */
    private fun MutableMethod.filteredAtTop(filter: String, page: Int, at: Int = 0): Pair<Int, Int> {
        val body = body()
        assertEquals(Opcode.MOVE_OBJECT_FROM16, body[at].opcode)
        val copy = body[at] as TwoRegisterInstruction
        assertEquals("the page is copied down from its own register", page, copy.registerB)
        assertEquals(Opcode.CONST_STRING, body[at + 1].opcode)
        assertEquals("fixture.AdItem", ((body[at + 1] as ReferenceInstruction).reference as StringReference).string)
        val label = (body[at + 1] as OneRegisterInstruction).registerA
        assertEquals(filter, body[at + 2].call)
        val call = body[at + 2] as FiveRegisterInstruction
        assertEquals("the filter gets the copy and the name", listOf(copy.registerA, label), listOf(call.registerC, call.registerD))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, body[at + 3].opcode)
        assertEquals(copy.registerA, (body[at + 3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.MOVE_OBJECT_16, body[at + 4].opcode)
        val back = body[at + 4] as TwoRegisterInstruction
        assertEquals("the answer goes back where the method reads its page", listOf(page, copy.registerA), listOf(back.registerA, back.registerB))
        return copy.registerA to label
    }

    /**
     * On both builds the page insert is an instance method and the announcement a static one.
     * Redex decides that per build, so each is checked both ways: the page is the last register
     * either way, and a p register written down for one form is another parameter in the other.
     */
    @Test
    fun `the page filter reads the Collection parameter, static or not`() {
        val cases = listOf(
            // name, parameters, return type, registers, static
            method("insert", listOf("I", collection), "Z", 6, static = false, smali = "const/4 v0, 0x0\nreturn v0"),
            method("insert", listOf("I", collection), "Z", 5, static = true, smali = "const/4 v0, 0x0\nreturn v0"),
            method("announce", listOf("Lfixture/Items;", collection), "V", 7, static = true, smali = "return-void"),
            method("announce", listOf("Lfixture/Items;", collection), "V", 8, static = false, smali = "return-void"),
        )
        for (page in cases) {
            val registers = page.implementation!!.registerCount
            page.filterPageFirst("fixture.AdItem")
            val form = if (AccessFlags.STATIC.isSet(page.accessFlags)) "static" else "instance"
            assertEquals("$form ${page.name}: the locals borrowed", 0 to 1, page.filteredAtTop(withoutAds, page = registers - 1))
        }
    }

    /**
     * The filter call names its operands in four bits, and the patcher's smali compiler leaves out a
     * call whose register doesn't fit, without a word. The page used to be named there, so a page
     * above v15 stopped the patch; 577's controller page method already has 15 registers. The page
     * is copied down now, so it can sit anywhere.
     */
    @Test
    fun `a page kept above v15 is copied down instead of stopping the patch`() {
        val above = method("insert", listOf("I", collection), "Z", 20, static = true, smali = "const/4 v0, 0x0\nreturn v0")
        above.filterPageFirst("fixture.AdItem")
        above.filteredAtTop(withoutAds, page = 19)

        // The 577 controller with two registers more: its page in v16.
        val sections = method(
            "addPage", listOf(list), "Z", 17, static = false,
            smali = "const/4 v4, 0x0\nreturn v4",
            definingClass = "Lfixture/Controller;",
        )
        sections.filterSectionsFirst("fixture.AdItem")
        sections.filteredAtTop(withoutAdSections, page = 16)
    }

    @Test
    fun `a page method without two locals to borrow stops the patch`() {
        val none = method("insert", listOf("I", collection), "Z", 3, static = false, smali = "return p1")
        assertThrows(PatchException::class.java) { none.filterPageFirst("fixture.AdItem") }

        val one = method("insert", listOf("I", collection), "Z", 4, static = false, smali = "const/4 v0, 0x0\nreturn v0")
        val refused = assertThrows(PatchException::class.java) { one.filterPageFirst("fixture.AdItem") }
        assertTrue(refused.message, refused.message.orEmpty().startsWith("Hide sponsored reels:"))
        assertTrue(refused.message, refused.message.orEmpty().contains("needs 2"))

        // The positive control: two locals are enough.
        val two = method("insert", listOf("I", collection), "Z", 5, static = false, smali = "const/4 v0, 0x0\nreturn v0")
        two.filterPageFirst("fixture.AdItem")
        two.filteredAtTop(withoutAds, page = 4)
    }

    @Test
    fun `the sections filter reads the List parameter, static or not`() {
        for (static in listOf(false, true)) {
            val registers = if (static) 5 else 6
            val add = method(
                "addPage", listOf(list), "Z", registers, static,
                smali = "const/4 v3, 0x0\nreturn v3",
                definingClass = "Lfixture/Controller;",
            )
            add.filterSectionsFirst("fixture.AdItem")
            assertEquals("static=$static: the locals borrowed", 0 to 1, add.filteredAtTop(withoutAdSections, page = registers - 1))
        }
    }

    /**
     * The borrowed register used to be whichever one the method's first instruction names, as long
     * as it was a local the instruction wrote. That excluded `if-eqz p1` and `check-cast p1`, whose
     * register is the page, by refusing the whole patch. Now nothing borrowed is a parameter, so a
     * method that opens by testing or casting its page gets the filter like any other, and goes on
     * to test the filtered page.
     */
    @Test
    fun `a sections method that opens by testing or casting its page still gets the filter`() {
        val reads = method(
            "addPage", listOf(list), "Z", 6, static = false,
            smali = """
                if-eqz p1, :empty
                const/4 v0, 0x1
                return v0
                :empty
                const/4 v0, 0x0
                return v0
            """,
            definingClass = "Lfixture/Controller;",
        )
        reads.filterSectionsFirst("fixture.AdItem")
        assertEquals(0 to 1, reads.filteredAtTop(withoutAdSections, page = 5))
        assertEquals("the method's own test reads the filtered page", Opcode.IF_EQZ, reads.body()[5].opcode)
        assertEquals(5, (reads.body()[5] as OneRegisterInstruction).registerA)

        val casts = method(
            "addPage", listOf(list), "Z", 6, static = false,
            smali = """
                check-cast p1, Ljava/util/ArrayList;
                const/4 v0, 0x0
                return v0
            """,
            definingClass = "Lfixture/Controller;",
        )
        casts.filterSectionsFirst("fixture.AdItem")
        assertEquals(0 to 1, casts.filteredAtTop(withoutAdSections, page = 5))
    }

    /** Hide AI-detected posts puts its own filter in front of this one, on the same three methods. */
    @Test
    fun `a filter put in front of another borrows the same two locals`() {
        val add = method(
            "addPage", listOf(list), "Z", 6, static = false,
            smali = "const/4 v3, 0x0\nreturn v3",
            definingClass = "Lfixture/Controller;",
        )
        add.filterSectionsFirst("fixture.AdItem")
        add.filterSectionsFirst("fixture.AdItem", filter = otherSectionFilter)
        assertEquals(0 to 1, add.filteredAtTop(otherSectionFilter, page = 5))
        assertEquals(0 to 1, add.filteredAtTop(withoutAdSections, page = 5, at = 5))
    }

    private val otherSectionFilter = "Lfixture/OtherFilter;->pages(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

    private fun idle(smali: String) = method(
        "idle", listOf("Lfixture/Session;", "I"), "V", 6, static = false, smali = smali,
        definingClass = "Lfixture/IdleState;",
    ).body()

    private fun execute(from: String = "invoke-static { v0 }", literal: String = REELS_VIDEO_AD_QUERY) = """
        const-string v0, "$literal"
        $from, Lfixture/Executor;->run(Ljava/lang/String;)$settableFuture
        move-result-object v1
        return-void
    """

    @Test
    fun `the idle query's executor call is the first SettableFuture call after its literal`() {
        assertEquals(1, idleExecutorCallIndex(idle(execute()), "idle"))
    }

    /**
     * The patcher matched the fingerprint's literal by containment, so a method holding only a
     * longer name was found. Without the whole literal there is nowhere to start, and the search
     * used to start at index -1 and fail with an index error that named nothing.
     */
    @Test
    fun `a longer query name the fingerprint matched stops the patch naming the literal`() {
        val refused = assertThrows(PatchException::class.java) {
            idleExecutorCallIndex(idle(execute(literal = "${REELS_VIDEO_AD_QUERY}V2")), "idle")
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("\"$REELS_VIDEO_AD_QUERY\""))
    }

    @Test
    fun `an executor call the replacement can't stand in for stops the patch`() {
        // No SettableFuture call after the literal.
        assertThrows(PatchException::class.java) {
            idleExecutorCallIndex(idle("const-string v0, \"$REELS_VIDEO_AD_QUERY\"\nreturn-void"), "idle")
        }
        // Not a static call.
        assertThrows(PatchException::class.java) {
            idleExecutorCallIndex(
                idle(
                    """
                        const-string v0, "$REELS_VIDEO_AD_QUERY"
                        invoke-virtual { p0, v0 }, Lfixture/IdleState;->run(Ljava/lang/String;)$settableFuture
                        move-result-object v1
                        return-void
                    """,
                ),
                "idle",
            )
        }
        // Its answer isn't moved straight after.
        assertThrows(PatchException::class.java) {
            idleExecutorCallIndex(
                idle(
                    """
                        const-string v0, "$REELS_VIDEO_AD_QUERY"
                        invoke-static { v0 }, Lfixture/Executor;->run(Ljava/lang/String;)$settableFuture
                        return-void
                    """,
                ),
                "idle",
            )
        }
    }

    @Test
    fun `the fetch a log reports is the last future call before it`() {
        val fetch = method(
            "fetch", emptyList(), "V", 3, static = true,
            smali = """
                invoke-static { }, Lfixture/Api;->first()$listenableFuture
                move-result-object v0
                invoke-static { }, Lfixture/Api;->banner()$listenableFuture
                move-result-object v0
                const-string v1, "$BANNER_FETCH_LOG"
                invoke-static { }, Lfixture/Api;->after()$listenableFuture
                move-result-object v0
                return-void
            """,
        ).body()
        assertEquals("banner", fetch.futureCallBefore(BANNER_FETCH_LOG).name)

        val none = method(
            "fetch", emptyList(), "V", 3, static = true,
            smali = "const-string v1, \"$BANNER_FETCH_LOG\"\nreturn-void",
        ).body()
        val refused = assertThrows(PatchException::class.java) { none.futureCallBefore(BANNER_FETCH_LOG) }
        assertTrue(refused.message, refused.message.orEmpty().contains(BANNER_FETCH_LOG))
    }

    /** A fetch whose video query declares [declared] and whose answer goes into a field of [stored]. */
    private fun narrowedFetch(declared: String, stored: String, storeFrom: String = "v0") = method(
        "fetch", emptyList(), "V", 3, static = false,
        smali = """
            invoke-static { }, Lfixture/Api;->banner()$settableFuture
            move-result-object v0
            iput-object v0, p0, Lfixture/Fetch;->banner:$listenableFuture
            invoke-virtual { p0 }, Lfixture/Api;->video()$declared
            move-result-object v0
            iput-object $storeFrom, p0, Lfixture/Fetch;->video:$stored
            const-string v1, "$VIDEO_FETCH_LOG"
            return-void
        """,
    ).body()

    /**
     * 587 declares the video query as the transform future it builds, an obfuscated class, and
     * stores it straight into a ListenableFuture field. Matching only the two Guava types skipped
     * it and named the banner call for the video log as well.
     */
    @Test
    fun `a call stored straight into a ListenableFuture field is the fetch, whatever type it declares`() {
        val transform = "Lfixture/TransformFuture;"
        val video = narrowedFetch(transform, listenableFuture).futureCallBefore(VIDEO_FETCH_LOG)
        assertEquals("video", video.name)
        assertEquals(transform, video.returnType)

        // Negative controls: stored into a field of another type, or a register other than its
        // answer's, the call isn't a future fetch, and the banner call before it is.
        assertEquals("banner", narrowedFetch(transform, "Ljava/lang/Object;").futureCallBefore(VIDEO_FETCH_LOG).name)
        assertEquals("banner", narrowedFetch(transform, listenableFuture, storeFrom = "v1").futureCallBefore(VIDEO_FETCH_LOG).name)
    }

    /** A query that sends through [execute] and then transforms the answer into what it declares. */
    private fun narrowedQuery(execute: String) = method(
        "video", emptyList(), "Lfixture/TransformFuture;", 4, static = false,
        smali = """
            invoke-static { }, Lfixture/Executor;->prepare()$settableFuture
            move-result-object v0
            $execute
            sget-object v2, Lfixture/Transform;->INSTANCE:Lcom/google/common/base/Function;
            invoke-static { v2, v1 }, Lfixture/Futures;->transform(Lcom/google/common/base/Function;$listenableFuture)Lfixture/TransformFuture;
            move-result-object v0
            return-object v0
        """,
        definingClass = "Lfixture/Api;",
    ).body()

    @Test
    fun `a query declaring a narrower future is held at its last SettableFuture call`() {
        val sent = narrowedQuery(
            "invoke-static { v0 }, Lfixture/Executor;->run(Ljava/lang/Object;)$settableFuture\nmove-result-object v1",
        )
        assertEquals(2, queryExecutorCallIndex(sent, "video"))

        // Not static, or its answer not moved straight after: the same-size static call can't stand in.
        assertThrows(PatchException::class.java) {
            queryExecutorCallIndex(
                narrowedQuery("invoke-virtual { p0, v0 }, Lfixture/Api;->run(Ljava/lang/Object;)$settableFuture\nmove-result-object v1"),
                "video",
            )
        }
        assertThrows(PatchException::class.java) {
            queryExecutorCallIndex(
                narrowedQuery("invoke-static { v0 }, Lfixture/Executor;->run(Ljava/lang/Object;)$settableFuture\nconst/4 v1, 0x0"),
                "video",
            )
        }
        // No SettableFuture call at all.
        assertThrows(PatchException::class.java) {
            queryExecutorCallIndex(
                method("video", emptyList(), "Lfixture/TransformFuture;", 1, static = true, smali = "const/4 v0, 0x0\nreturn-object v0").body(),
                "video",
            )
        }
    }

    private fun tick(name: String, owner: String = "Lfixture/State;") = method(
        name, listOf("Lfixture/Media;", "I"), "J", 5, static = false,
        smali = "const-wide/16 v0, 0x0\nreturn-wide v0", definingClass = owner,
    )

    /**
     * One tick is the state's, none sends the walk to the parent, and two used to send it to the
     * parent as well, whose tick the state never runs.
     */
    @Test
    fun `a class declaring two ad-break ticks stops the patch`() {
        val other = method("other", listOf("Lfixture/Media;"), "J", 4, static = false, smali = "const-wide/16 v0, 0x0\nreturn-wide v0")
        val only = tick("tick")
        assertSame(only, adBreakTickAmong(listOf(other, only), "Lfixture/State;"))
        assertNull(adBreakTickAmong(listOf(other), "Lfixture/State;"))

        val refused = assertThrows(PatchException::class.java) {
            adBreakTickAmong(listOf(only, tick("tick2")), "Lfixture/State;")
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("Lfixture/State;"))
        assertTrue(refused.message, refused.message.orEmpty().contains("found 2"))
    }
}
