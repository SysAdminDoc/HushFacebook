/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.gesture

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every clipboard write Facebook makes goes to the extension's stand-in, in the same registers, so
 * a hold's copy of a post's text can open the box without reaching the clipboard (#342). Every toast
 * it shows goes to a second stand-in, so the "Text copied" toast after that copy can be dropped.
 */
class ClipboardCopiesFixtureTest {
    private val postTextSelect = "$EXTENSION_PACKAGE/reels/PostTextSelect;"

    private fun registers(instruction: Instruction): List<Int> = when (instruction) {
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD).take(instruction.registerCount)
        else -> throw AssertionError("$instruction is no invoke")
    }

    private fun key(name: String, parameters: List<CharSequence>, returnType: String) =
        "$name${parameters.joinToString("", "(", ")")}$returnType"

    private fun callsIn(classDef: ClassDef, call: (Instruction) -> Boolean): Map<String, List<Pair<Int, List<Int>>>> =
        classDef.methods.associate { method ->
            key(method.name, method.parameterTypes, method.returnType) to
                method.implementation?.instructions?.withIndex()
                    ?.filter { (_, instruction) -> call(instruction) }
                    ?.map { (index, instruction) -> index to registers(instruction) }.orEmpty()
        }.filterValues { it.isNotEmpty() }

    /**
     * On each declared build: every class outside the extension that makes [call] is what [find]
     * finds, [send] rewrites each such call in place to [standIn] in the same registers, and the
     * extension's own [call] is left alone. [marker] is a string a dex holding such a call has.
     */
    private fun eachCallGoesToItsStandIn(
        call: (Instruction) -> Boolean,
        marker: String,
        fewest: Int,
        standIn: String,
        find: BytecodePatchContext.() -> List<String>,
        send: BytecodePatchContext.(List<String>) -> Int,
    ) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val callers = linkedMapOf<String, ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.stringSection.none { it == marker }) return@forEach
                    for (classDef in dex.classes) {
                        if (classDef.type in callers) continue
                        if (classDef.methods.any { method -> method.implementation?.instructions?.any(call) == true }) {
                            callers[classDef.type] = ImmutableClassDef.of(classDef)
                        }
                    }
                }
                assertTrue("$name: ${callers.size} classes make the call", callers.size >= fewest)
                val before = callers.mapValues { (_, classDef) -> callsIn(classDef, call) }
                val calls = before.values.sumOf { methods -> methods.values.sumOf { it.size } }

                val context = PatchContexts.of(callers.values + ExtensionDex.classDef(postTextSelect))
                val found = context.find()
                assertEquals("$name: the classes found", callers.keys, found.toSet())
                assertEquals("$name: the calls sent", calls, context.send(found))

                for ((type, methods) in before) {
                    val patched = context.mutableClassDefBy(type).methods
                    for ((method, sites) in methods) {
                        val code = patched.single { key(it.name, it.parameterTypes, it.returnType) == method }
                            .implementation!!.instructions.toList()
                        assertTrue("$name: $type->$method still makes the call", code.none(call))
                        for ((index, held) in sites) {
                            val sent = code[index]
                            assertTrue("$name: $type->$method at $index is ${sent.opcode}",
                                sent.opcode == Opcode.INVOKE_STATIC || sent.opcode == Opcode.INVOKE_STATIC_RANGE)
                            assertEquals("$name: $type->$method at $index", standIn, (sent as ReferenceInstruction).reference.toString())
                            assertEquals("$name: $type->$method at $index passes what the call did", held, registers(sent))
                        }
                    }
                }
                val own = context.mutableClassDefBy(postTextSelect).methods.any { method ->
                    method.implementation?.instructions?.any(call) == true
                }
                assertTrue("$name: the stand-in's own call is left alone", own)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `on each declared build every clipboard write outside the extension goes to the stand-in in its registers`() =
        eachCallGoesToItsStandIn(::isClipboardWrite, "setPrimaryClip", FEWEST_CLIPBOARD_WRITERS, TEXT_COPY,
            { clipboardWriters() }, { sendClipboardWrites(it) })

    @Test
    fun `on each declared build every toast outside the extension goes to its stand-in in its register`() =
        eachCallGoesToItsStandIn(::isToastShow, "Landroid/widget/Toast;", FEWEST_TOAST_SHOWERS, TOAST_STAND_IN,
            { toastShowers() }, { sendToastShows(it) })

    @Test
    fun `the extension has both stand-ins`() {
        for ((name, signature) in listOf("copy" to TEXT_COPY, "toast" to TOAST_STAND_IN)) {
            val method = ExtensionDex.classDef(postTextSelect).methods.singleOrNull { it.name == name }
            assertNotNull("PostTextSelect has no $name", method)
            assertTrue("$name isn't public static", AccessFlags.PUBLIC.isSet(method!!.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
            assertEquals(signature, "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}")
        }
    }
}
