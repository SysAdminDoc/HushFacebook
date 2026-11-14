/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.confirm

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ask before a follow on every declared build and ABI (#477). The reel author row's Follow, the
 * collaborator row's event dispatcher, the post header's Follow link and the shared attachment's
 * Follow button each start by handing the extension their descriptor, instance and arguments, and
 * return right away when the extension holds the tap; otherwise they jump to Facebook's own first
 * instruction, unmoved. The dispatcher only asks for the event its Follow sits in, and the
 * attachment's merged listener only on the arm holding its Follow. A build missing one of them
 * changes none. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class AskBeforeFollowFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
    private val markers = mapOf(
        REEL_FOLLOW to REEL_FOLLOW_LOG,
        REEL_COLLAB_FOLLOW to REEL_FOLLOW_LOG,
        POST_FOLLOW to POST_FOLLOW_EVENT,
        POST_ATTACHMENT_FOLLOW to FOLLOW_LINK,
    )

    private fun Method.code() = implementation!!.instructions.toList()

    /** [this] without the nop the dex writer puts before a payload to align it. */
    private fun List<Instruction>.unpadded() =
        filterIndexed { i, it -> it.opcode != Opcode.NOP || getOrNull(i + 1)?.opcode?.format?.isPayloadFormat != true }

    /** An opcode with a goto widened by the insert read as the plain goto it was. */
    private fun Instruction.kind() = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode
    private fun Instruction.reference() = (this as ReferenceInstruction).reference.toString()
    private fun Instruction.loads(string: String) =
        (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
            ((this as ReferenceInstruction).reference as StringReference).string == string

    private fun holding(bundle: File, string: String): List<ClassDef> =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }

    private fun BytecodePatchContext.patched(method: Method) = mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }.code()

    private fun Instruction.target() = (this as BuilderOffsetInstruction).target.location.index

    /** The index [at]'s branch in unpatched [code] lands on, or the one [offset] from it. */
    private fun branchTarget(code: List<Instruction>, at: Int, offset: Int = (code[at] as OffsetInstruction).codeOffset): Int {
        val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
        return addresses.indexOf(addresses[at] + offset)
    }

    /** What the guard does with the arm number: wait for one case's [key], or take a two-armed [skip] branch. */
    private data class Guard(val key: Int? = null, val skip: Opcode? = null)

    /**
     * The guard stock [code] calls for, read on its own: Facebook branches on [field] right after
     * reading it (past a few moves and constants). On a switch, the key of the one case whose arm
     * (up to the next case, or the end) loads [marker]; on a two-armed branch, the branch that leads
     * away from [marker].
     */
    private fun guardOf(code: List<Instruction>, field: String, marker: String, label: String): Guard {
        val readAt = code.indexOfFirst { it.opcode == Opcode.IGET && it.reference() == field }
        val markerAt = code.indexOfFirst { it.loads(marker) }
        assertTrue("$label: doesn't read its arm number before \"$marker\"", readAt in 0 until markerAt)
        val setup = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)
        val switchAt = (readAt + 1 until code.size).first { code[it].opcode !in setup }
        assertTrue("$label: doesn't branch on its arm number right after reading it", switchAt - readAt in 1..5)
        val branch = code[switchAt].opcode
        if (branch == Opcode.IF_EQZ || branch == Opcode.IF_NEZ) {
            val onFallThrough = markerAt < branchTarget(code, switchAt)
            return Guard(skip = if (onFallThrough) branch else if (branch == Opcode.IF_EQZ) Opcode.IF_NEZ else Opcode.IF_EQZ)
        }
        assertTrue("$label: branches on its arm number with $branch",
            branch == Opcode.PACKED_SWITCH || branch == Opcode.SPARSE_SWITCH)
        val payload = code[branchTarget(code, switchAt)] as SwitchPayload
        val starts = payload.switchElements.associate { it.key to branchTarget(code, switchAt, it.offset) }
        val bounds = (starts.values + (switchAt + 1) + code.size).toSortedSet()
        val cases = starts.filter { (_, start) ->
            code.subList(start, bounds.first { it > start }).any { it.loads(marker) }
        }.keys
        assertEquals("$label: not one case of the switch holds \"$marker\"", 1, cases.size)
        return Guard(key = cases.single())
    }

    private fun contextOf(classes: List<ClassDef>) =
        PatchContexts.of(classes.distinctBy { it.type } + ExtensionDex.classDef(SETTINGS_STATUS))

    @Test
    fun `each declared build's Follow handlers ask the extension first`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val context = contextOf(markers.values.toSet().flatMap { holding(bundle, it) })
                val handlers = context.followHandlers()
                assertEquals("$name: not one handler per kind",
                    listOf(REEL_FOLLOW, REEL_COLLAB_FOLLOW, POST_FOLLOW, POST_ATTACHMENT_FOLLOW), handlers.map { it.kind })
                val before = handlers.associate { it.kind to it.method.code() }

                // Positive controls: each handler loads its own marker, the reel row's Follow takes the
                // tapped view first, the post's two are onClick(View), the dispatcher reads the event
                // id off the event handler it's given first, and the attachment's listener reads its
                // arm number off itself.
                handlers.forEach { handler ->
                    assertTrue("$name: kind ${handler.kind} doesn't load \"${markers.getValue(handler.kind)}\"",
                        before.getValue(handler.kind).any { it.loads(markers.getValue(handler.kind)) })
                }
                val reel = handlers.single { it.kind == REEL_FOLLOW }.method
                assertEquals("$name: the reel row's Follow doesn't take the view first", "Landroid/view/View;",
                    reel.parameterTypes[0].toString())
                listOf(POST_FOLLOW, POST_ATTACHMENT_FOLLOW).forEach { kind ->
                    val method = handlers.single { it.kind == kind }.method
                    assertEquals("$name: kind $kind isn't onClick(View)", listOf("onClick", "Landroid/view/View;"),
                        listOf(method.name) + method.parameterTypes.map { it.toString() })
                }
                val collab = handlers.single { it.kind == REEL_COLLAB_FOLLOW }
                assertTrue("$name: the collaborator row's guard reads a field off something other than the event handler",
                    collab.arm!!.field.startsWith(collab.method.parameterTypes[0].toString() + "->"))
                val attachment = handlers.single { it.kind == POST_ATTACHMENT_FOLLOW }
                assertTrue("$name: the attachment's guard reads a field off something other than its listener",
                    attachment.arm!!.field.startsWith(attachment.method.definingClass + "->"))
                val guards = listOf(collab, attachment).associate {
                    it.kind to guardOf(before.getValue(it.kind), it.arm!!.field, markers.getValue(it.kind), "$name: kind ${it.kind}")
                }
                assertTrue("$name: the collaborator row's dispatcher doesn't switch on its event id",
                    guards.getValue(REEL_COLLAB_FOLLOW).key != null)

                context.askBeforeFollow()

                handlers.forEach { handler ->
                    val method = handler.method
                    val label = "$name: kind ${handler.kind}"
                    val old = before.getValue(handler.kind).unpadded()
                    val after = context.patched(method).unpadded()
                    val grown = after.size - old.size
                    assertTrue("$label: no hook went in", grown > 0)
                    assertEquals("$label: Facebook's own code moved or changed",
                        old.map { it.kind() }, after.drop(grown).map { it.kind() })
                    val hook = after.take(grown)

                    var at = 0
                    handler.arm?.let { arm ->
                        assertEquals("$label: no case guard", listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET),
                            hook.take(2).map { it.opcode })
                        val from = if (handler.kind == REEL_COLLAB_FOLLOW) method.parameterRegisterNumber(0) else method.localRegisterCount()
                        assertEquals("$label: the guard reads the wrong register", from, (hook[0] as TwoRegisterInstruction).registerB)
                        assertEquals("$label: the guard reads another field", arm.field, hook[1].reference())
                        val guard = guards.getValue(handler.kind)
                        if (guard.key != null) {
                            assertTrue("$label: the guard loads no case key", hook[2].opcode == Opcode.CONST_16 || hook[2].opcode == Opcode.CONST)
                            assertEquals("$label: the guard waits for another case",
                                guard.key, (hook[2] as NarrowLiteralInstruction).narrowLiteral)
                            assertEquals("$label: the guard doesn't compare the case", Opcode.IF_NE, hook[3].opcode)
                            assertEquals("$label: the guard doesn't skip to Facebook's code", grown, hook[3].target())
                            at = 4
                        } else {
                            assertEquals("$label: the guard takes the branch toward the Follow", guard.skip, hook[2].opcode)
                            assertEquals("$label: the guard doesn't skip to Facebook's code", grown, hook[2].target())
                            at = 3
                        }
                    }
                    val call = hook.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC && it.reference() == HOLD }
                    assertTrue("$label: the hook doesn't call hold", call > at)
                    assertEquals("$label: the array isn't sized for the instance and every argument",
                        method.parameterTypes.size + 1, (hook[at] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals("$label: no array", Opcode.NEW_ARRAY, hook[at + 1].opcode)
                    val stores = hook.subList(at, call).count { it.opcode == Opcode.APUT_OBJECT }
                    val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
                    assertEquals("$label: not every argument goes into the array", method.parameterTypes.size + self, stores)
                    assertEquals("$label: the wrong kind", handler.kind, (hook[call - 2] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals("$label: the wrong descriptor", descriptorOf(method),
                        ((hook[call - 1] as ReferenceInstruction).reference as StringReference).string)
                    val args = hook[call] as FiveRegisterInstruction
                    assertEquals("$label: hold gets the wrong registers", listOf(0, 1, 2),
                        listOf(args.registerC, args.registerD, args.registerE))
                    assertEquals("$label: the answer isn't tested",
                        listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ), hook.subList(call + 1, call + 3).map { it.opcode })
                    assertEquals("$label: a no doesn't go to Facebook's code", grown, hook[call + 2].target())
                    val exit = hook.drop(call + 3)
                    if (method.returnType == "V") {
                        assertEquals("$label: a held tap doesn't return", listOf(Opcode.RETURN_VOID), exit.map { it.opcode })
                    } else {
                        assertTrue("$label: only the dispatcher returns a value", handler.returnsNull)
                        assertEquals("$label: a held tap doesn't return null",
                            listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT), exit.map { it.opcode })
                        assertEquals("$label: a held tap returns something other than null", 0,
                            (exit[0] as NarrowLiteralInstruction).narrowLiteral)
                    }
                }
                checked += name
            }
        }
        assertTrue("no fixture bundle for ${versions.joinToString()}", checked.isNotEmpty())
    }

    @Test
    fun `a build missing a Follow handler changes none of them`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val gone = holding(bundle, FOLLOW_LINK).map { it.type }.toSet()
                val kept = listOf(REEL_FOLLOW_LOG, POST_FOLLOW_EVENT).flatMap { holding(bundle, it) }.filterNot { it.type in gone }
                val context = contextOf(kept)
                val stock = kept.flatMap { it.methods }.filter { method ->
                    method.implementation?.instructions?.any { it.loads(REEL_FOLLOW_LOG) || it.loads(POST_FOLLOW_EVENT) } == true
                }
                assertTrue("$name: no Follow handler kept to watch", stock.isNotEmpty())
                try {
                    context.askBeforeFollow()
                    fail("$name: a build without the attachment's Follow was patched")
                } catch (expected: PatchException) {
                    assertTrue("$name: ${expected.message}", expected.message!!.contains(FOLLOW_LINK))
                }
                stock.forEach { method ->
                    assertEquals("$name: ${method.definingClass}->${method.name} changed anyway",
                        method.code().map { it.opcode }, context.patched(method).map { it.opcode })
                }
                checked += name
            }
        }
        assertTrue("no fixture bundle for ${versions.joinToString()}", checked.isNotEmpty())
    }
}
