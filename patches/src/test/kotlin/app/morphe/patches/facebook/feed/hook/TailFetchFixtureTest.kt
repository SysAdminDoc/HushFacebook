/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tail fetch hook on every Facebook build the bundle declares: exactly one class holding the
 * scroll fetcher's onScroll trace answers the hook's rule, and the patch puts the extension's ask in
 * front of onScroll's branch that doesn't fetch, on registers nothing reads, with its yes going to
 * the first instruction of Facebook's own ask and the rest of onScroll left as it was. onScroll
 * also opens by handing the extension the fetcher and its arguments, and the fetcher gets a method
 * that runs onScroll again with them (#493). Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TailFetchFixtureTest {
    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference)?.toString()

    @Test
    fun `each declared build has one scroll fetcher, and its no-fetch branch can ask again`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, SCROLL_FETCHER_TRACE)
                val sites = owners.mapNotNull(::tailFetchSite)
                assertEquals("$name: scroll fetchers among ${owners.map { it.type }}", 1, sites.size)
                val site = sites.single()
                val before = site.method.body()
                assertEquals("$name: the ask opens by reading the feed loader", Opcode.IGET_OBJECT, before[site.ask].opcode)
                assertTrue("$name: the ask comes after the branch", site.ask > site.index)

                // #493: the entry hook goes in front of instruction 0, so nothing may jump back there.
                val stockFlow = ControlFlow.of(site.method)
                assertTrue("$name: something in onScroll jumps back to its start",
                    before.indices.none { 0 in stockFlow.normal[it] })

                val context = PatchContexts.of(owners)
                homeTailFetchPatch.execute(context)
                val fetcher = context.mutableClassDefBy(site.method.definingClass)
                val patched = fetcher.methods.single {
                    it.name == site.method.name &&
                        it.parameterTypes.map(CharSequence::toString) == site.method.parameterTypes.map(CharSequence::toString)
                }
                val after = patched.body()

                // First thing, onScroll hands the extension itself and its four arguments.
                val entry = after[0] as RegisterRangeInstruction
                assertEquals("$name: the entry hook", SCROLLED, after[0].call())
                assertEquals("$name: from p0", patched.implementation!!.registerCount - 5, entry.startRegister)
                assertEquals("$name: through p4", 5, entry.registerCount)
                assertEquals("$name: one entry hook", 1, after.count { it.call() == SCROLLED })

                // The tail hook, one down for the entry hook.
                val at = site.index + 1
                val added = after.subList(at, at + 5)
                assertEquals("$name: the hook's reads", listOf(Opcode.IGET, Opcode.IGET), added.take(2).map { it.opcode })
                assertEquals("$name: the last row", site.lastRow.toString(), added[0].call())
                assertEquals("$name: the rows", site.rows.toString(), added[1].call())
                assertEquals("$name: the ask", ASK_AGAIN, added[2].call())
                assertEquals("$name: one ask", 1, after.count { it.call() == ASK_AGAIN })
                assertEquals("$name: its yes", Opcode.IF_NEZ, added[4].opcode)

                // Its yes lands on Facebook's own ask, which moved down by the six added instructions.
                val target = ControlFlow.of(patched).normal[at + 4].single { it != at + 5 }
                assertEquals("$name: where its yes goes", site.ask + 6, target)
                assertEquals("$name: the instruction there", before[site.ask].call(), after[target].call())

                // The fetcher runs onScroll again for the extension, with the list cast back.
                assertTrue("$name: the fetcher implements $SCROLLER", SCROLLER in fetcher.interfaces)
                val again = fetcher.methods.single { it.name == SCROLL_AGAIN }
                assertEquals("$name: what $SCROLL_AGAIN takes", listOf("Ljava/lang/Object;", "I", "I", "I"),
                    again.parameterTypes.map(CharSequence::toString))
                val bridge = again.body()
                assertEquals("$name: the bridge", listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID),
                    bridge.map { it.opcode })
                assertEquals("$name: the list's type", site.method.parameterTypes[0].toString(), bridge[0].call())
                val call = bridge[1] as FiveRegisterInstruction
                assertEquals("$name: onScroll", "${site.method.definingClass}->${site.method.name}(" +
                    site.method.parameterTypes.joinToString("") + ")V", bridge[1].call())
                assertEquals("$name: its arguments", listOf(0, 1, 2, 3, 4),
                    listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG))

                val borrowed = (added[2] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) }
                val liveness = RegisterLiveness.of(site.method)
                val live = liveness.liveInto(site.index) + liveness.liveInto(site.ask)
                assertTrue("$name: the hook borrows $borrowed, which onScroll still reads: $live",
                    borrowed.none { it in live } && borrowed.all { it <= 15 })
                assertEquals("$name: the rest of onScroll", before.map { it.opcode },
                    after.filterIndexed { index, _ -> index != 0 && index !in at until at + 5 }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
