/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.file

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #590: on each declared build and ABI, the one method logging that a file's address isn't
 * http or https makes one DownloadManager request from the address and enqueues it with no
 * destination, which is why the file lands where no file manager reaches it. After the patch, the
 * instruction right after the request's constructor hands the request and the address, from the
 * constructor's own registers, to FileDownloads.toDownloads, and the rest of the method is kept.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SaveFilesToDownloadsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
    private val manager = "Landroid/app/DownloadManager;"
    private val request = "Landroid/app/DownloadManager\$Request;"

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.shape() = parameterTypes.map(CharSequence::toString)

    /** An opcode, with a goto an insert may have widened read as a plain one. */
    private fun kind(instruction: Instruction) = when (instruction.opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> instruction.opcode
    }

    private fun kinds(code: List<Instruction>) = code.filter { it.opcode != Opcode.NOP }.map(::kind)

    @Test
    fun `a file downloaded from a post goes to the extension right after its request is made, on each declared build`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        var bundles = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, NON_HTTP_FILE)
                assertTrue("$name: nothing logs \"$NON_HTTP_FILE\"", holders.isNotEmpty())
                val context = PatchContexts.of(holders)
                val file = context.findFileRequest()
                val where = "$name ${file.method.definingClass}->${file.method.name}"
                val stock = file.method.body()

                // What the patch is for: Facebook enqueues the request and never gives it a destination.
                val calls = stock.mapNotNull { it.method() }
                assertTrue("$where: never enqueues a download", calls.any {
                    it.definingClass == manager && it.name == "enqueue" && it.parameterTypes.map(CharSequence::toString) == listOf(request)
                })
                assertTrue("$where: already picks a destination", calls.none {
                    it.definingClass == request && it.name.startsWith("setDestination")
                })
                assertEquals("$where: the hook's place is the request's constructor", REQUEST_INIT, stock[file.init].method().toString())
                val made = stock.take(file.init).last {
                    it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == request
                }
                assertEquals("$where: the request register is the one the request was made in",
                    (made as OneRegisterInstruction).registerA, file.request)

                assertThrows("$name: a build with no such method must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).findFileRequest()
                }

                context.hookFileRequest(file)
                val after = context.mutableClassDefBy(file.method.definingClass).methods.single {
                    it.name == file.method.name && it.returnType == file.method.returnType && it.shape() == file.method.shape()
                }.body()
                assertEquals("$where: the constructor stays where it was", REQUEST_INIT, after[file.init].method().toString())
                val hook = after[file.init + 1]
                assertEquals("$where: right after it, the request goes to FileDownloads.toDownloads", TO_DOWNLOADS, hook.method().toString())
                val handed = when (hook) {
                    is FiveRegisterInstruction -> {
                        assertEquals("$where: a static call", Opcode.INVOKE_STATIC, hook.opcode)
                        assertEquals("$where: with two registers", 2, hook.registerCount)
                        listOf(hook.registerC, hook.registerD)
                    }
                    is RegisterRangeInstruction -> {
                        assertEquals("$where: a static call", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
                        assertEquals("$where: with two registers", 2, hook.registerCount)
                        listOf(hook.startRegister, hook.startRegister + 1)
                    }
                    else -> throw AssertionError("$where: the hook is ${hook.opcode}")
                }
                val init = stock[file.init]
                val passed = when (init) {
                    is FiveRegisterInstruction -> listOf(init.registerC, init.registerD)
                    is RegisterRangeInstruction -> listOf(init.startRegister, init.startRegister + 1)
                    else -> throw AssertionError("$where: the constructor is ${init.opcode}")
                }
                assertEquals("$where: the request and its address, as the constructor took them", passed, handed)
                assertEquals("$where: then what came after the constructor", stock[file.init + 1].opcode, after[file.init + 2].opcode)
                assertEquals("$where: the rest of the method is kept",
                    kinds(stock), kinds(after.take(file.init + 1) + after.drop(file.init + 2)))
                bundles++
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertTrue("only $bundles bundles checked", bundles >= versions.size)
    }
}
