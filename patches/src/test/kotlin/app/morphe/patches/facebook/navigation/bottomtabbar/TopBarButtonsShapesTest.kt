/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the Litho bar and its badge take a count off the store, in the shapes 586 has and the ones
 * Hide the Messenger count refuses rather than leave a read counting.
 */
class TopBarButtonsShapesTest {
    private val badge = "Lfixture/Badge;"
    private val keyType = "Lfixture/BadgeKey;"
    private val store = "Lfixture/BadgeStore;"
    private val key = ImmutableFieldReference(badge, "A02", keyType)
    private val valueOf = "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;"
    private val lookup = "invoke-interface { v1, v0 }, $store->Ccp($keyType)I"

    private fun method(body: String, parameters: List<String> = emptyList(), registers: Int = 6) = MutableMethod(
        ImmutableMethod(
            "Lfixture/BadgeListener;", "A00", parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    /** The badge's first count as 0RRm has it: the store read by the key the badge keeps, into v2. */
    private fun read(
        keyRead: String = "iget-object v0, p0, $badge->A02:$keyType",
        read: String = lookup,
        afterResult: String = "",
        tail: String = "",
    ) = method(
        """
            $keyRead
            iget-object v1, p0, $badge->A01:$store
            $read
            move-result v2
            $afterResult
            iput v2, p0, $badge->A00:I
            return-void
            $tail
        """,
    )

    /** The store's update as 0BzH->A00 has it: the posted count boxed only when the key is the badge's. */
    private fun update(
        compare: String = "if-ne p1, v1, :skip",
        box: String = "invoke-static { p3 }, $valueOf\nmove-result-object v0",
        tail: String = "",
    ) = method(
        """
            iget-object v0, p0, Lfixture/BadgeListener;->A00:$badge
            iget-object v1, v0, $badge->A02:$keyType
            $compare
            $box
            sput-object v0, Lfixture/BadgeListener;->A01:Ljava/lang/Object;
            :skip
            return-void
            $tail
        """,
        parameters = listOf(keyType, "I", "I"),
    )

    private fun reason(block: () -> Unit): String {
        val message = assertThrows(PatchException::class.java) { block() }.message!!
        assertTrue(message, message.startsWith("$PATCH: "))
        return message
    }

    @Test
    fun `the hook goes after the store read's move-result with the key and the count`() {
        val site = badgeCountSites(read(), key).single()
        assertEquals(listOf(4, 0, 2, 0), listOf(site.at, site.key, site.count, if (site.posted) 1 else 0))
    }

    @Test
    fun `a key moved on the way to the read still gets the hook`() {
        val moved = read(keyRead = "iget-object v0, p0, $badge->A02:$keyType\nmove-object v3, v0", read = "invoke-interface { v1, v3 }, $store->Ccp($keyType)I")
        val site = badgeCountSites(moved, key).single()
        assertEquals(listOf(5, 3, 2), listOf(site.at, site.key, site.count))
    }

    @Test
    fun `a read the hook can't follow refuses`() {
        val jumpedTo = reason { badgeCountSites(read(afterResult = ":after", tail = "goto :after"), key) }
        assertTrue(jumpedTo, "reads a count off the store by" in jumpedTo)
        val otherKey = """
            iget-object v0, p0, $badge->A02:$keyType
            if-nez v0, :lookup
            iget-object v0, p0, $badge->A03:$keyType
            :lookup
        """
        val mixed = reason { badgeCountSites(read(keyRead = otherKey), key) }
        assertTrue(mixed, "reads a count off the store by" in mixed)
    }

    @Test
    fun `a lookup by the key in another shape refuses`() {
        val shapes = listOf(
            "move-object v2, v0\ninvoke-interface/range { v1 .. v2 }, $store->Ccp($keyType)I",
            "const/4 v3, 0x1\ninvoke-interface { v1, v0, v3 }, $store->Ccp(${keyType}Z)I",
            "invoke-static { v1, v0 }, Lfixture/BadgeCounts;->A00($store$keyType)I",
            "invoke-interface { v1, v0 }, $store->Ccq($keyType)Ljava/lang/Integer;\nmove-result-object v3\ninvoke-virtual { v3 }, Ljava/lang/Integer;->intValue()I",
        )
        for (shape in shapes) {
            val message = reason { badgeCountSites(read(read = shape), key) }
            assertTrue(message, "a call the hook can't follow" in message)
        }
    }

    @Test
    fun `a key used any other way refuses`() {
        val calls = listOf(
            "invoke-interface { v0, v1 }, $store->Ccp($keyType)I",
            "invoke-interface { v1, v0 }, $store->Ccp(Ljava/lang/Object;)I",
            "invoke-interface { v1, v0 }, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;\nmove-result-object v3\n" +
                "check-cast v3, Ljava/lang/Integer;\ninvoke-virtual { v3 }, Ljava/lang/Integer;->intValue()I",
        )
        for (shape in calls) {
            val message = reason { badgeCountSites(read(read = shape), key) }
            assertTrue(message, "a call the hook can't follow" in message)
        }
        val compares = listOf(
            "invoke-static { v1, p1 }, Ljava/util/Objects;->equals(Ljava/lang/Object;Ljava/lang/Object;)Z\nmove-result v0\nif-eqz v0, :skip",
            "invoke-static { v1, p1 }, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z\nmove-result v0\nif-eqz v0, :skip",
            "invoke-virtual { v1 }, Ljava/lang/Enum;->ordinal()I\nmove-result v1\n" +
                "invoke-virtual { p1 }, Ljava/lang/Enum;->ordinal()I\nmove-result v0\nif-ne v0, v1, :skip",
        )
        for (compare in compares) {
            val message = reason { badgeCountSites(update(compare = compare), key) }
            assertTrue(message, "a call the hook can't follow" in message)
        }
        val escapes = listOf(
            read(afterResult = "iput-object v0, p0, $badge->A03:$keyType"),
            update(compare = "check-cast v1, Ljava/lang/Object;\nif-ne p1, v1, :skip"),
            method("iget-object v0, p0, $badge->A02:$keyType\nreturn-object v0"),
        )
        for (escape in escapes) {
            val message = reason { badgeCountSites(escape, key) }
            assertTrue(message, "a use the hook can't follow" in message)
        }
    }

    @Test
    fun `a test for none, a memo and two keys compared take no count`() {
        val quiet = method(
            """
                iget-object v0, p0, $badge->A02:$keyType
                if-eqz v0, :none
                iget-object v1, p1, $badge->A02:$keyType
                if-ne v0, v1, :none
                filled-new-array { v0, v1 }, [Ljava/lang/Object;
                move-result-object v2
                sput-object v2, Lfixture/BadgeListener;->A01:Ljava/lang/Object;
                :none
                return-void
            """,
            parameters = listOf(badge),
        )
        assertEquals(emptyList<BadgeCountSite>(), badgeCountSites(quiet, key))
    }

    @Test
    fun `a key or count past v15 refuses before any hook goes in`() {
        val wide = method(
            """
                sget-object v5, $badge->A09:$badge
                iget-object v0, v5, $badge->A02:$keyType
                iget-object v1, v5, $badge->A01:$store
                $lookup
                move-result v16
                sput v16, $badge->A05:I
                return-void
            """,
            registers = 20,
        )
        val message = reason { badgeCountSites(wide, key) }
        assertTrue(message, "is past v15" in message)
    }

    @Test
    fun `the update's hook takes the int parameter it boxes`() {
        val site = badgeCountSites(update(), key).single()
        // p1 to p3 are v3 to v5: the key, then the two ints.
        assertEquals(listOf(3, 1, 5, 1), listOf(site.at, site.key, site.count, if (site.posted) 1 else 0))
    }

    @Test
    fun `an update that boxes a count it worked out refuses`() {
        val worked = listOf(
            "add-int/lit8 v0, p3, 0x1\ninvoke-static { v0 }, $valueOf\nmove-result-object v0",
            "add-int/lit8 p3, p3, 0x1\ninvoke-static { p3 }, $valueOf\nmove-result-object v0",
        )
        for (box in worked) {
            val message = reason { badgeCountSites(update(box = box), key) }
            assertTrue(message, "doesn't box an int it was handed" in message)
        }
        val unboxed = reason { badgeCountSites(update(box = "const/4 v0, 0x0"), key) }
        assertTrue(unboxed, "boxes no count after it" in unboxed)
    }

    @Test
    fun `an update compared or reached in another shape refuses`() {
        val flipped = reason { badgeCountSites(update(compare = "if-eq p1, v1, :skip"), key) }
        assertTrue(flipped, "where the hook can't go" in flipped)
        val equals = "invoke-virtual { v1, p1 }, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z\nmove-result v0\nif-eqz v0, :skip"
        val equaled = reason { badgeCountSites(update(compare = equals), key) }
        assertTrue(equaled, "a call the hook can't follow" in equaled)
        val maybe = reason { badgeCountSites(update(compare = "if-nez p2, :compare\nconst/4 v1, 0x0\n:compare\nif-ne p1, v1, :skip"), key) }
        assertTrue(maybe, "where the hook can't go" in maybe)
        val landing = "const/4 v0, 0x0\n:box\ninvoke-static { p3 }, $valueOf\nmove-result-object v0"
        val jumpedIn = reason { badgeCountSites(update(box = landing, tail = "goto :box"), key) }
        assertTrue(jumpedIn, "has a way into the update" in jumpedIn)
        val ranged = reason { badgeCountSites(update(box = "invoke-static/range { p3 .. p3 }, $valueOf\nmove-result-object v0"), key) }
        assertTrue(ranged, "a range call the hook can't follow" in ranged)
    }

    private val name = ImmutableFieldReference("Lfixture/Spec;", "A0F", "Ljava/lang/String;")

    /** The Litho bar's render as 0BQo->A16 has it: the count read by the spec's key, worded, then handed to the badge. */
    private fun render(
        keyless: String = "",
        afterRead: String = "",
        badgeInit: String = initCall(", v3", "I"),
        afterBadge: String = "",
        tail: String = "",
    ) = method(
        """
            iget-object v0, p0, Lfixture/Spec;->A0F:Ljava/lang/String;
            iget-object v1, p0, Lfixture/Spec;->A09:$keyType
            $keyless
            iget-object v2, p0, Lfixture/Spec;->A10:$store
            invoke-interface { v2, v1 }, $store->Ccp($keyType)I
            move-result v3
            $afterRead
            iget-object v4, p0, Lfixture/Spec;->A11:Landroid/content/res/Resources;
            const/4 v5, 0x0
            :label
            invoke-virtual { v4, v5, v3, v5 }, Landroid/content/res/Resources;->getQuantityString(II[Ljava/lang/Object;)Ljava/lang/String;
            new-instance v4, $badge
            $badgeInit
            $afterBadge
            return-void
            $tail
        """,
        registers = 7,
    )

    private fun initCall(more: String, ints: String) = "invoke-direct { v4, v1$more }, $badge-><init>($keyType$ints)V"

    /** A spec without a key that brings its own count to just after the read, as 586's does. */
    private val keyless = "if-eqz v1, :keyless"
    private fun keylessJoins(alsoWrite: String = "") = ":keyless\nconst/4 v3, 0x5\n$alsoWrite\ngoto :join"

    @Test
    fun `the spec's count is read by its key, worded and handed to the badge`() {
        val site = specCountSites(render(), name).single()
        assertEquals(listOf(4, 0, 3, 1), listOf(site.read, site.name, site.count, site.key))
        assertEquals(badge, site.badge)
        val joined = specCountSites(render(keyless = keyless, afterRead = ":join", tail = keylessJoins()), name).single()
        assertEquals(listOf(5, 0, 3, 1), listOf(joined.read, joined.name, joined.count, joined.key))
    }

    @Test
    fun `the badge's int is found past a long and in a range call`() {
        val pastLong = specCountSites(render(badgeInit = initCall(", v5, v6, v3", "JI")), name).single()
        assertEquals(3, pastLong.count)
        val ranged = method(
            """
                iget-object v0, p0, Lfixture/Spec;->A0F:Ljava/lang/String;
                iget-object v2, p0, Lfixture/Spec;->A09:$keyType
                iget-object v1, p0, Lfixture/Spec;->A10:$store
                invoke-interface { v1, v2 }, $store->Ccp($keyType)I
                move-result v3
                iget-object v4, p0, Lfixture/Spec;->A11:Landroid/content/res/Resources;
                const/4 v5, 0x0
                invoke-virtual { v4, v5, v3, v5 }, Landroid/content/res/Resources;->getQuantityString(II[Ljava/lang/Object;)Ljava/lang/String;
                new-instance v1, $badge
                invoke-direct/range { v1 .. v3 }, $badge-><init>(${keyType}I)V
                return-void
            """,
            registers = 7,
        )
        val site = specCountSites(ranged, name).single()
        assertEquals(listOf(3, 2), listOf(site.count, site.key))
    }

    @Test
    fun `a count the hook doesn't write on every way to the label refuses`() {
        val pastHook = reason { specCountSites(render(keyless = keyless, tail = ":keyless\nconst/4 v3, 0x5\ngoto :label"), name) }
        assertTrue(pastHook, "doesn't come from the hook on every way in" in pastHook)
        // The label and the badge are hooked, but a dot further on is reached by the keyless count too.
        val dot = render(keyless = keyless, afterBadge = ":dot\nif-lez v3, :drawn\n:drawn", tail = ":keyless\nconst/4 v3, 0x5\ngoto :dot")
        val pastDot = reason { specCountSites(dot, name) }
        assertTrue(pastDot, "doesn't come from the hook on every way in" in pastDot)
    }

    @Test
    fun `a badge handed any int but the hook's, or none, refuses`() {
        listOf(
            initCall(", v5", "I"),
            initCall(", v3, v5", "II"),
            initCall("", ""),
        ).forEach { init ->
            val refused = reason { specCountSites(render(badgeInit = init), name) }
            assertTrue(refused, "doesn't come from the hook on every way in" in refused)
        }
        // A second badge built from the key read again, not from the register the count was read by.
        val again = "iget-object v6, p0, Lfixture/Spec;->A09:$keyType\nnew-instance v4, $badge\ninvoke-direct { v4, v6, v5 }, $badge-><init>(${keyType}I)V"
        val refused = reason { specCountSites(render(afterBadge = again), name) }
        assertTrue(refused, "doesn't come from the hook on every way in" in refused)
    }

    @Test
    fun `a worded count whose name can change refuses`() {
        val renamed = reason {
            specCountSites(render(keyless = keyless, afterRead = ":join", tail = keylessJoins("const-string v0, \"Search\"")), name)
        }
        assertTrue(renamed, "something other than the spec's name" in renamed)
    }

    @Test
    fun `a worded count whose key goes anywhere but one badge refuses`() {
        val otherBadge = "Lfixture/OtherBadge;"
        listOf(
            render(afterBadge = "invoke-static { v1 }, Lfixture/Other;->use($keyType)V"),
            render(afterBadge = "new-instance v6, $otherBadge\ninvoke-direct { v6, v1, v3 }, $otherBadge-><init>(${keyType}I)V"),
            render(badgeInit = "invoke-direct { v4, v3 }, $badge-><init>(I)V"),
        ).forEach { astray ->
            val refused = reason { specCountSites(astray, name) }
            assertTrue(refused, "to one badge's constructor alone" in refused)
        }
    }

    @Test
    fun `a worded count read into the key's or the name's register refuses`() {
        listOf("v1", "v0").forEach { into ->
            val shared = method(
                """
                    iget-object v0, p0, Lfixture/Spec;->A0F:Ljava/lang/String;
                    iget-object v1, p0, Lfixture/Spec;->A09:$keyType
                    iget-object v2, p0, Lfixture/Spec;->A10:$store
                    invoke-interface { v2, v1 }, $store->Ccp($keyType)I
                    move-result $into
                    iget-object v4, p0, Lfixture/Spec;->A11:Landroid/content/res/Resources;
                    const/4 v5, 0x0
                    invoke-virtual { v4, v5, $into, v5 }, Landroid/content/res/Resources;->getQuantityString(II[Ljava/lang/Object;)Ljava/lang/String;
                    return-void
                """,
                registers = 7,
            )
            val refused = reason { specCountSites(shared, name) }
            assertTrue(refused, "shares one register" in refused)
        }
    }
}
