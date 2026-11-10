/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.shared.AddNewEdgeToCollectionFingerprint
import app.morphe.patches.facebook.shared.FEED_UNIT_EDGE
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The swap guard on every Facebook build the bundle declares: exactly one class holding the swap
 * runnable's log line answers the guard's rule, it's the runnable Redex names
 * FeedUnitCollectionManager$onEdgeSwapped$1 (from 584 on the Function0 capturing the new edge and the
 * old key), and the feed guard's patch, run on it with the edge and the funnel's class, asks the
 * extension right after the cast, on registers nothing reads, before the swap, leaving the rest of
 * run() or invoke() as it was. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class EdgeSwapFixtureTest {
    /** The fingerprint keeps its last match, and each build brings a context of its own. */
    @Before
    fun forgetTheLastMatch() = AddNewEdgeToCollectionFingerprint.clearMatch()

    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference)?.toString()

    /** Every call on an instance, the calls EdgeSwap.kt takes a swap from. */
    private val instanceCalls = setOf(
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
        Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE,
    )

    @Test
    fun `each declared build has one swap runnable, and the guard goes in before its swap`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                AddNewEdgeToCollectionFingerprint.clearMatch()
                val owners = FixtureDex.classesHolding(bundle, EDGE_SWAP_DROPPED)
                val guards = owners.mapNotNull(::swapGuard)
                assertEquals("$name: swap runnables among ${owners.map { it.type }}", 1, guards.size)
                val guard = guards.single()
                val owner = owners.single { it.type == guard.method.definingClass }
                // Through 583 the runnable keeps its Redex name; 584's Function0 has none, but keeps
                // the names of what it captures. 586 merges it with another lambda into one Function0
                // switched on $t and renames the captures, so there it's the manager's log tag in the
                // swap and the old key captured as a String that say which lambda this is.
                val captures = owner.fields.map { it.name }.toSet()
                val merged = "\$t" in captures && holdsString(guard.method, "FeedUnitCollectionManager") &&
                    owner.fields.any { it.type == "Ljava/lang/String;" }
                assertTrue("$name: the Redex name or the swap's captures, $captures",
                    redexOriginalName(owner) == "FeedUnitCollectionManager\$onEdgeSwapped\$1" ||
                        captures.containsAll(setOf("\$newEdge", "\$oldPrimaryKey")) || merged)

                val funnel = FixtureDex.classesHolding(bundle, "Edge not added to FUC")
                val edge = FixtureDex.classes(bundle, setOf(FEED_UNIT_EDGE)).values
                val context = PatchContexts.of(owners + funnel + edge)
                feedFilterHookPatch.execute(context)

                val before = guard.method.body()
                val run = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == guard.method.name && it.parameterTypes.isEmpty() && it.returnType == guard.method.returnType
                }
                val after = run.body()
                // run() gets eight instructions, ending in its own return-void; invoke() seven, ending
                // in a jump to the Unit load its one return reads.
                val size = if (guard.unit == null) 8 else 7
                val added = after.subList(guard.index, guard.index + size)
                assertEquals("$name: the guard", HIDE_SWAPPED_EDGE, added[4].call())
                if (guard.unit == null) {
                    assertEquals("$name: what it returns", Opcode.RETURN_VOID, added[7].opcode)
                } else {
                    assertEquals("$name: the way out", Opcode.IF_NEZ, added[6].opcode)
                    // 586's merged invoke() shares the Unit return of the branch before the swap's,
                    // so the guard's instructions only move it when it comes after them.
                    val unitAt = guard.unit!!.let { if (it > guard.index) it + size else it }
                    val unit = after[unitAt]
                    assertEquals("$name: the jump's target", listOf(Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT),
                        listOf(unit.opcode, after[unitAt + 1].opcode))
                    assertSame("$name: the jump lands on the Unit load", unit,
                        (added[6] as BuilderOffsetInstruction).target.location.instruction)
                }
                assertEquals("$name: one guard", 1, after.count { it.call() == HIDE_SWAPPED_EDGE })
                val asked = (added[4] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) }
                val live = RegisterLiveness.of(guard.method).liveInto(guard.index)
                assertTrue("$name: the guard borrows $asked, which the runnable still reads: $live", asked.none { it in live } && asked.all { it <= 15 })
                // A jump the guard pushes out of a goto's reach comes back as goto/16, the same jump.
                fun Opcode.jump() = if (this == Opcode.GOTO_16 || this == Opcode.GOTO_32) Opcode.GOTO else this
                assertEquals("$name: the rest of the runnable", before.map { it.opcode.jump() },
                    after.filterIndexed { index, _ -> index !in guard.index until guard.index + size }.map { it.opcode.jump() })
                val swap = after.indexOfFirst {
                    it.opcode in instanceCalls && it.call()?.endsWith("(${FEED_UNIT_EDGE}Ljava/lang/String;)V") == true
                }
                assertTrue("$name: the guard comes before the swap at $swap", swap > guard.index + size - 1)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
