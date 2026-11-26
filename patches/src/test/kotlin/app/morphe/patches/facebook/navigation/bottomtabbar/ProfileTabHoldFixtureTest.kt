/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Hold the profile tab for Activity log on each declared Facebook build (#605): the tab bar's
 * long-click listener hands the pressed view and its TabTag to the extension first thing, and a
 * yes returns true before any of Facebook's code runs. A no lands on Facebook's first instruction,
 * and the rest of onLongClick stays as Facebook wrote it.
 */
class ProfileTabHoldFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each declared build's tab long-click listener asks the extension before Facebook's press`() = bundles { bundle ->
        val owners = FixtureDex.classesHolding(bundle, TAB_HIDING_LONG_PRESS)
        // Probed on 588 (2026-11-26): LX/02L4; keeps the session, the tab bar and the TabTag, and its
        // onLongClick fetches your accounts for the account switcher on the profile tab.
        assertEquals("${bundle.name}: tab long-click listeners", 1, owners.mapNotNull(::tabHoldSite).size)
        val context = PatchContexts.of(owners)
        val site = context.profileTabHoldSite()
        val type = site.method.definingClass
        assertEquals("${bundle.name}: the kept tab's type", TAB_TAG, site.tab.type)
        assertEquals("${bundle.name}: the kept tab's owner", type, site.tab.definingClass)
        val registers = site.method.implementation!!.registerCount
        val code = site.method.implementation!!.instructions.toList()

        context.hookProfileTabHold(site)
        val where = "${bundle.name}: $type->onLongClick"
        val body = context.mutableClassDefBy(type).methods.single { it.name == "onLongClick" }
            .implementation!!.instructions.toList()
        assertEquals("$where: eight instructions added", code.size + 8, body.size)
        assertEquals("$where: the hook's opcodes", listOf(
            Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC,
            Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN,
        ), body.take(8).map { it.opcode })

        val self = body[0] as TwoRegisterInstruction
        assertEquals("$where: this goes to v0", listOf(0, registers - 2), listOf(self.registerA, self.registerB))
        val tab = body[1]
        assertEquals("$where: the read is of the kept tab", site.tab.toString(), (tab as ReferenceInstruction).reference.toString())
        assertEquals("$where: the tab is read from this into v0", listOf(0, 0),
            listOf((tab as TwoRegisterInstruction).registerA, tab.registerB))
        val view = body[2] as TwoRegisterInstruction
        assertEquals("$where: the view goes to v1", listOf(1, registers - 1), listOf(view.registerA, view.registerB))
        val ask = body[3]
        assertEquals("$where: the ask", OWN_TAB_HELD, (ask as ReferenceInstruction).reference.toString())
        val asked = ask as FiveRegisterInstruction
        assertEquals("$where: the ask's registers", listOf(2, 1, 0), listOf(asked.registerCount, asked.registerC, asked.registerD))
        assertEquals("$where: the answer's register", 0, (body[4] as OneRegisterInstruction).registerA)
        val branch = body[5] as BuilderOffsetInstruction
        assertEquals("$where: a no goes on to Facebook's first instruction", 8, branch.target.location.index)
        assertEquals("$where: a yes returns true", 1, (body[6] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: the true is what returns", 0, (body[7] as OneRegisterInstruction).registerA)

        val jumps = setOf(Opcode.GOTO_16, Opcode.GOTO_32)
        for (index in code.indices) {
            val before = code[index].opcode.let { if (it in jumps) Opcode.GOTO else it }
            val after = body[index + 8].opcode.let { if (it in jumps) Opcode.GOTO else it }
            assertEquals("$where: instruction $index", before, after)
        }
    }
}
