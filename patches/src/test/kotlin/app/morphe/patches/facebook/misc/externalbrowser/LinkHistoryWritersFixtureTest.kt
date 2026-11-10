/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.externalbrowser

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each declared Facebook build has exactly one factory for each of the browser's link history
 * writers, the method Open links in external browser has answer null. The patch refuses a build where
 * either count is off, so a fixture that moved one fails here first. Each factory also has to answer
 * null on its own somewhere, which is what makes null a value its callers already handle, and needs a
 * local register for the hook to borrow. Each build also has one link history upload, which the
 * switch has return before it builds or sends anything, and one of each autofill report, which it
 * has return before it logs.
 */
class LinkHistoryWritersFixtureTest {
    @Test
    fun `each declared build has one factory per link history writer, and each can answer null`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                for (writer in LINK_HISTORY_WRITERS) {
                    val factories = FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.typeSection.any { it == writer } }) {
                        isWriterFactory(it, writer)
                    }
                    assertEquals(
                        "${bundle.name}: factories of $writer " + factories.joinToString { "${it.definingClass}->${it.name}" },
                        1,
                        factories.size,
                    )
                    val factory = factories.single()
                    val code = factory.implementation!!.instructions.toList()
                    val nulls = code.indices.filter { at ->
                        code[at].opcode == Opcode.CONST_4 && (code[at] as NarrowLiteralInstruction).narrowLiteral == 0
                    }.map { (code[it] as OneRegisterInstruction).registerA }.toSet()
                    assertTrue(
                        "${bundle.name}: ${factory.definingClass}->${factory.name} never answers null",
                        code.any { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA in nulls },
                    )
                    val locals = factory.implementation!!.registerCount - factory.parameterTypes.size - 1
                    assertTrue("${bundle.name}: ${factory.definingClass}->${factory.name} has no local to borrow", locals >= 1)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each declared build has one link history upload, and the hook returns before it sends`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
                val classes = FixtureDex.classesHolding(bundle, LINK_HISTORY_UPLOAD_ANCHOR)
                    .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val upload = context.linkHistoryUpload()
                val code = upload.implementation!!.instructions.toList()
                // Positive control: it fills the save request's times, session and direct-message flag.
                val strings = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                for (field in listOf("start_time_str", "end_time_str", "session_id", "is_direct_message")) {
                    assertTrue("$name: the upload doesn't fill $field", field in strings)
                }
                val types = upload.parameterTypes.map(CharSequence::toString)
                val locals = upload.implementation!!.registerCount - types.size - types.count { it == "J" || it == "D" } - 1
                assertTrue("$name: the upload has no local to borrow", locals >= 1)
                assertTrue("$name: a jump lands on the upload's first instruction", 0 !in branchTargets(code))

                context.mutableClassDefBy(upload.definingClass).findMutableMethodOf(upload).holdLinkHistoryUpload()
                val patched = context.mutableClassDefBy(upload.definingClass).methods.single {
                    it.name == upload.name && it.parameterTypes.map(CharSequence::toString) == types
                }.implementation!!.instructions.toList()
                assertEquals("$name: the gate",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                    patched.take(4).map { it.opcode })
                assertEquals("$name: the gate asks holdUpload", HOLD_LINK_HISTORY_UPLOAD,
                    ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the gate tests the answer it kept",
                    (patched[1] as OneRegisterInstruction).registerA, (patched[2] as OneRegisterInstruction).registerA)
                // A no jumps over the return to Facebook's first instruction.
                assertEquals("$name: where a no goes", patched[2].codeUnits + patched[3].codeUnits,
                    (patched[2] as OffsetInstruction).codeOffset)
                // Without nops: added code can move a payload, which gains or loses an alignment nop.
                assertEquals("$name: the rest is Facebook's",
                    code.map { it.opcode }.filter { it != Opcode.NOP }, patched.drop(4).map { it.opcode }.filter { it != Opcode.NOP })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `each declared build has one of each autofill report, and the hook returns before it logs`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
                val classes = AUTOFILL_REPORTS.flatMap { FixtureDex.classesHolding(bundle, it.first()) }
                    .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val reports = context.autofillReports()
                println("$name: autofill reports " + reports.joinToString { "${it.definingClass}->${it.name}" })
                // Positive control: the form report takes its 29 fields, ending in the two maps; the
                // first-party event takes the user session. (Key strings can move to Redex's string table.)
                val (form, firstParty) = reports
                assertEquals("$name: the form report's fields", 29, form.parameterTypes.size)
                assertEquals("$name: the form report's last two fields", listOf("Ljava/util/Map;", "Ljava/util/Map;"),
                    form.parameterTypes.takeLast(2).map(CharSequence::toString))
                assertTrue("$name: the first-party event takes no user session",
                    firstParty.parameterTypes.any { it.toString() == "Lcom/facebook/auth/usersession/FbUserSession;" })
                for (report in reports) {
                    val code = report.implementation!!.instructions.toList()
                    val types = report.parameterTypes.map(CharSequence::toString)
                    val locals = report.implementation!!.registerCount - types.size - types.count { it == "J" || it == "D" }
                    assertTrue("$name: ${report.definingClass} has no local to borrow", locals >= 1)
                    assertTrue("$name: a jump lands on ${report.definingClass}'s first instruction", 0 !in branchTargets(code))

                    context.mutableClassDefBy(report.definingClass).findMutableMethodOf(report).holdAutofillReport()
                    val patched = context.mutableClassDefBy(report.definingClass).methods.single {
                        it.name == report.name && it.parameterTypes.map(CharSequence::toString) == types
                    }.implementation!!.instructions.toList()
                    assertEquals("$name: the gate",
                        listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                        patched.take(4).map { it.opcode })
                    assertEquals("$name: the gate asks holdAutofillReport", HOLD_AUTOFILL_REPORT,
                        ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                    assertEquals("$name: the gate tests the answer it kept",
                        (patched[1] as OneRegisterInstruction).registerA, (patched[2] as OneRegisterInstruction).registerA)
                    assertEquals("$name: where a no goes", patched[2].codeUnits + patched[3].codeUnits,
                        (patched[2] as OffsetInstruction).codeOffset)
                    assertEquals("$name: the rest is Facebook's",
                        code.map { it.opcode }.filter { it != Opcode.NOP }, patched.drop(4).map { it.opcode }.filter { it != Opcode.NOP })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }
}
