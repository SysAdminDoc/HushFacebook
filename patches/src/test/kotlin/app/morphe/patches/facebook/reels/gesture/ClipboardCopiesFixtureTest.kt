/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.gesture

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
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
 * a hold's copy of a post's text can open the box without reaching the clipboard (#342).
 */
class ClipboardCopiesFixtureTest {
    private val postTextSelect = "$EXTENSION_PACKAGE/reels/PostTextSelect;"

    private fun registers(instruction: Instruction): List<Int> = when (instruction) {
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD).take(instruction.registerCount)
        else -> throw AssertionError("$instruction is no invoke")
    }

    private fun writesIn(classDef: ClassDef): Map<String, List<Pair<Int, List<Int>>>> =
        classDef.methods.associate { method ->
            "${method.name}${method.parameterTypes.joinToString("", "(", ")")}${method.returnType}" to
                method.implementation?.instructions?.withIndex()
                    ?.filter { (_, instruction) -> isClipboardWrite(instruction) }
                    ?.map { (index, instruction) -> index to registers(instruction) }.orEmpty()
        }.filterValues { it.isNotEmpty() }

    @Test
    fun `on each declared build every clipboard write outside the extension goes to the stand-in in its registers`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val writers = linkedMapOf<String, ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.stringSection.none { it == "setPrimaryClip" }) return@forEach
                    for (classDef in dex.classes) {
                        if (classDef.type in writers) continue
                        if (classDef.methods.any { method -> method.implementation?.instructions?.any(::isClipboardWrite) == true }) {
                            writers[classDef.type] = ImmutableClassDef.of(classDef)
                        }
                    }
                }
                assertTrue("$name: ${writers.size} classes write the clipboard", writers.size >= FEWEST_CLIPBOARD_WRITERS)
                val before = writers.mapValues { (_, classDef) -> writesIn(classDef) }
                val calls = before.values.sumOf { methods -> methods.values.sumOf { it.size } }

                val context = PatchContexts.of(writers.values + ExtensionDex.classDef(postTextSelect))
                val found = context.clipboardWriters()
                assertEquals("$name: the classes found", writers.keys, found.toSet())
                assertEquals("$name: the calls sent", calls, context.sendClipboardWrites(found))

                for ((type, methods) in before) {
                    val patched = context.mutableClassDefBy(type).methods
                    for ((key, sites) in methods) {
                        val code = patched.single { "${it.name}${it.parameterTypes.joinToString("", "(", ")")}${it.returnType}" == key }
                            .implementation!!.instructions.toList()
                        assertTrue("$name: $type->$key still writes the clipboard", code.none(::isClipboardWrite))
                        for ((index, held) in sites) {
                            val call = code[index]
                            assertTrue("$name: $type->$key at $index is ${call.opcode}",
                                call.opcode == Opcode.INVOKE_STATIC || call.opcode == Opcode.INVOKE_STATIC_RANGE)
                            assertEquals("$name: $type->$key at $index", TEXT_COPY, (call as ReferenceInstruction).reference.toString())
                            assertEquals("$name: $type->$key at $index passes what the write did", held, registers(call))
                        }
                    }
                }
                val ownWrite = context.mutableClassDefBy(postTextSelect).methods.any { method ->
                    method.implementation?.instructions?.any(::isClipboardWrite) == true
                }
                assertTrue("$name: the stand-in's own write is left alone", ownWrite)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the stand-in`() {
        val copy = ExtensionDex.classDef(postTextSelect).methods.singleOrNull { it.name == "copy" }
        assertNotNull("PostTextSelect has no copy", copy)
        assertTrue("copy isn't public static", AccessFlags.PUBLIC.isSet(copy!!.accessFlags) && AccessFlags.STATIC.isSet(copy.accessFlags))
        assertEquals(TEXT_COPY, "${copy.definingClass}->${copy.name}(${copy.parameterTypes.joinToString("")})${copy.returnType}")
    }
}
