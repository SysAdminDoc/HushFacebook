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
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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
 * has return before it logs. And one browser event logger, where the extension gets the last word on
 * each answer Facebook's sampling gives.
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

    @Test
    fun `each declared build has one ad event callback, and the hook returns before it logs`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
                val classes = FixtureDex.classesHolding(bundle, AD_EVENT_CALLBACK_ANCHOR)
                    .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val callback = context.adEventCallback()
                val code = callback.implementation!!.instructions.toList()
                println("$name: ad event callback ${callback.definingClass}->${callback.name}")
                // Positive control, from the 586 dex read: the attention events and the call button's
                // render and tap each ask isSampled, and the confirm dialog's steps go to the
                // click-to-call app state logger.
                val strings = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                assertTrue("$name: no attention events", "ATTENTION_FRAMEWORK" in strings)
                assertTrue("$name: no call button tap", "CALL_EXTENSION_CTA_CLICKED" in strings)
                val calls = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference) }
                assertEquals("$name: sampling checks", 3, calls.count { it.name == "isSampled" })
                assertTrue("$name: no click-to-call app state logger",
                    calls.any { it.definingClass == "Lcom/facebook/clicktocall/CTCAppStateLogger;" })
                val locals = callback.implementation!!.registerCount - callback.parameterTypes.size - 1
                assertTrue("$name: no local to borrow", locals >= 1)
                assertTrue("$name: a jump lands on the first instruction", 0 !in branchTargets(code))
                // Negative control: the other method of its shape on that class names no call button.
                val shaped = context.mutableClassDefBy(callback.definingClass).methods.filter {
                    it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;", "Ljava/lang/String;", "Ljava/util/Map;")
                }
                assertTrue("$name: only ${shaped.size} methods of its shape", shaped.size >= 2)
                assertEquals("$name: callbacks among them", 1, shaped.count(::isAdEventCallback))

                val types = callback.parameterTypes.map(CharSequence::toString)
                context.mutableClassDefBy(callback.definingClass).findMutableMethodOf(callback).holdAdEvent()
                val patched = context.mutableClassDefBy(callback.definingClass).methods.single {
                    it.name == callback.name && it.parameterTypes.map(CharSequence::toString) == types
                }.implementation!!.instructions.toList()
                assertEquals("$name: the gate",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                    patched.take(4).map { it.opcode })
                assertEquals("$name: the gate asks holdAdEvent", HOLD_AD_EVENT,
                    ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the gate tests the answer it kept",
                    (patched[1] as OneRegisterInstruction).registerA, (patched[2] as OneRegisterInstruction).registerA)
                assertEquals("$name: where a no goes", patched[2].codeUnits + patched[3].codeUnits,
                    (patched[2] as OffsetInstruction).codeOffset)
                assertEquals("$name: the rest is Facebook's",
                    code.map { it.opcode }.filter { it != Opcode.NOP }, patched.drop(4).map { it.opcode }.filter { it != Opcode.NOP })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `each declared build has one integrity callback, and the hook asks with the event's name before anything else`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
                val classes = FixtureDex.classesHolding(bundle, INTEGRITY_CALLBACK_ANCHOR)
                    .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val callback = context.integrityCallback()
                val code = callback.implementation!!.instructions.toList()
                println("$name: integrity callback ${callback.definingClass}->${callback.name}")
                // Positive control, from the 586 dex read: it starts the cloaking check, the signals and
                // outbound click events each ask isSampled, and it switches on the event name's hash.
                // (The event names themselves can sit in Redex's string table.)
                val strings = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                assertTrue("$name: no cloaking check", "CloakingDetectionService.ACTION_MAYBE_IAB_CLASSIFICATION" in strings)
                assertTrue("$name: no signals event", "invalid_event_decision_not_provided" in strings)
                assertTrue("$name: no page hashes", "sim_hash_dom" in strings)
                val calls = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference) }
                assertEquals("$name: sampling checks", 2, calls.count { it.name == "isSampled" })
                assertTrue("$name: no switch on the name",
                    calls.any { it.definingClass == "Ljava/lang/String;" && it.name == "hashCode" })
                val locals = callback.implementation!!.registerCount - callback.parameterTypes.size - 1
                assertTrue("$name: no local to borrow", locals >= 1)
                assertTrue("$name: a jump lands on the first instruction", 0 !in branchTargets(code))
                // Negative control: the ad event callback is the other method of its shape on that class.
                val shaped = context.mutableClassDefBy(callback.definingClass).methods.filter {
                    it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;", "Ljava/lang/String;", "Ljava/util/Map;")
                }
                assertTrue("$name: only ${shaped.size} methods of its shape", shaped.size >= 2)
                assertEquals("$name: integrity callbacks among them", 1, shaped.count(::isIntegrityCallback))
                assertEquals("$name: ad event callbacks among them", 1, shaped.count(::isAdEventCallback))
                assertTrue("$name: the integrity callback names a call button", !isAdEventCallback(callback))

                val types = callback.parameterTypes.map(CharSequence::toString)
                context.mutableClassDefBy(callback.definingClass).findMutableMethodOf(callback).holdIntegrityReport()
                val patchedMethod = context.mutableClassDefBy(callback.definingClass).methods.single {
                    it.name == callback.name && it.parameterTypes.map(CharSequence::toString) == types
                }
                val patched = patchedMethod.implementation!!.instructions.toList()
                assertEquals("$name: the gate",
                    listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                    patched.take(4).map { it.opcode })
                assertEquals("$name: the gate asks holdIntegrityReport", HOLD_INTEGRITY_REPORT,
                    ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                // The name: after this and the bundle, before the map, so the last register but one.
                val ask = patched[0] as RegisterRangeInstruction
                assertEquals("$name: the gate passes one register", 1, ask.registerCount)
                assertEquals("$name: the gate passes the event's name", patchedMethod.implementation!!.registerCount - 2, ask.startRegister)
                assertEquals("$name: the gate tests the answer it kept",
                    (patched[1] as OneRegisterInstruction).registerA, (patched[2] as OneRegisterInstruction).registerA)
                assertEquals("$name: where a no goes", patched[2].codeUnits + patched[3].codeUnits,
                    (patched[2] as OffsetInstruction).codeOffset)
                assertEquals("$name: the rest is Facebook's",
                    code.map { it.opcode }.filter { it != Opcode.NOP }, patched.drop(4).map { it.opcode }.filter { it != Opcode.NOP })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `each declared build has one browser event logger, and every sampling answer goes through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
                val holders = FixtureDex.classesHolding(bundle, BROWSER_EVENT_LOGGER_ANCHOR)
                    .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }
                val found = PatchContexts.of(holders).browserEventLogger()
                // The classes of the static calls it asks a yes or no of one object, wrappers among them.
                val owners = found.implementation!!.instructions.mapNotNull {
                    if (it.opcode != Opcode.INVOKE_STATIC && it.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
                    val call = (it as ReferenceInstruction).reference as MethodReference
                    call.definingClass.takeIf { call.returnType == "Z" && call.parameterTypes.size == 1 }
                }.toSet()
                val context = PatchContexts.of((holders + FixtureDex.classes(bundle, owners).values).distinctBy { it.type })
                val logger = context.browserEventLogger()
                val code = logger.implementation!!.instructions.toList()
                val answers = context.browserEventSamplingAnswers(logger)
                println("$name: ${logger.definingClass}->${logger.name}, ${answers.size} sampling answers")

                // Positive control, from the 586 dex read: 39 sampling answers, 18 straight from isSampled
                // and 21 through a static wrapper that only asks it.
                val direct = answers.filter { at ->
                    ((code[at - 1] as ReferenceInstruction).reference as MethodReference).name == "isSampled"
                }
                assertTrue("$name: ${answers.size} sampling answers", answers.size >= 30)
                assertTrue("$name: no answer comes straight from isSampled", direct.isNotEmpty())
                assertTrue("$name: no answer comes through a wrapper", answers.size > direct.size)
                for (at in answers) {
                    assertEquals("$name: answer $at", Opcode.MOVE_RESULT, code[at].opcode)
                }
                // Negative control: with no method taken for a wrapper, only isSampled's own answers are left.
                assertEquals("$name: answers without wrappers", direct, samplingAnswers(code) { false })
                assertTrue("$name: a jump lands right after an answer", answers.none { it + 1 in branchTargets(code) })

                context.mutableClassDefBy(logger.definingClass).findMutableMethodOf(logger).holdBrowserEvents(answers)
                val types = logger.parameterTypes.map(CharSequence::toString)
                val patched = context.mutableClassDefBy(logger.definingClass).methods.single {
                    it.name == logger.name && it.parameterTypes.map(CharSequence::toString) == types
                }.implementation!!.instructions.toList()
                assertEquals("$name: two instructions after each answer", code.size + 2 * answers.size, patched.size)
                val added = mutableSetOf<Int>()
                for ((n, at) in answers.sorted().withIndex()) {
                    val moved = at + 2 * n
                    val register = (code[at] as OneRegisterInstruction).registerA
                    assertEquals("$name: answer $at moved", Opcode.MOVE_RESULT, patched[moved].opcode)
                    val ask = patched[moved + 1]
                    assertEquals("$name: after answer $at", Opcode.INVOKE_STATIC_RANGE, ask.opcode)
                    assertEquals("$name: after answer $at", SEND_BROWSER_EVENT,
                        ((ask as ReferenceInstruction).reference as MethodReference).toString())
                    val handed = ask as RegisterRangeInstruction
                    assertEquals("$name: answer $at is handed over", register to 1, handed.startRegister to handed.registerCount)
                    assertEquals("$name: the reply to $at", Opcode.MOVE_RESULT, patched[moved + 2].opcode)
                    assertEquals("$name: the reply to $at goes back in", register, (patched[moved + 2] as OneRegisterInstruction).registerA)
                    added += moved + 1
                    added += moved + 2
                }
                assertEquals("$name: the rest is Facebook's",
                    code.map { it.opcode }.filter { it != Opcode.NOP },
                    patched.filterIndexed { at, _ -> at !in added }.map { it.opcode }.filter { it != Opcode.NOP })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }
}
