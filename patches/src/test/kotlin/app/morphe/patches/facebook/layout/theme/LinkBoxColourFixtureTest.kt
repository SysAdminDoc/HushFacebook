/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.shared.MOBILE_CONFIG
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #37's link box, the site and title under a link post's picture, on each declared build. In
 * Facebook's dark mode each kind of link attachment (ten on 582) picks its colour by reading
 * [LINK_BOX_COLOUR_CONFIG]'s string and parsing it with `Color.parseColor` once. After route four, with AMOLED first or not, that one
 * parse goes to Material You's link box stand-in on the text's register, and every other parse in
 * those classes stays route four's.
 */
class LinkBoxColourFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.call(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Instruction.textRegister(): Int = when (this) {
        is RegisterRangeInstruction -> startRegister
        is FiveRegisterInstruction -> registerC
        else -> error("unexpected call form $opcode")
    }

    @Test
    fun `each method reading the link box's dark colour parses it once, and that parse goes to Material You's stand-in`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val owners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any(::readsLinkBoxColour)) owners += ImmutableClassDef.of(classDef)
                    }
                }
                val readers = owners.flatMap { it.methods }.filter(::readsLinkBoxColour)
                assertTrue("$name: the footer and FDS's link attachment, at least, read the link box's colour (${readers.size})",
                    readers.size >= 2)

                val parses = readers.associate { reader ->
                    val calls = reader.body().withIndex().filter { it.value.call()?.toString() == PARSE_COLOR }
                    assertEquals("$name: ${reader.descriptor()} parses one colour", 1, calls.size)
                    assertTrue("$name: ${reader.descriptor()} reads the config as a string", reader.body().any { instruction ->
                        val call = instruction.call() ?: return@any false
                        call.definingClass == MOBILE_CONFIG && call.returnType == "Ljava/lang/String;" &&
                            call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "J")
                    })
                    reader.descriptor() to calls.single()
                }
                val otherParses = owners.flatMap { it.methods }.filterNot(::readsLinkBoxColour)
                    .sumOf { method -> method.body().count { it.call()?.toString() == PARSE_COLOR } }

                for (amoled in listOf(false, true)) {
                    val themes = "$name, AMOLED $amoled"
                    val context = PatchContexts.of(owners)
                    with(context) {
                        if (amoled) rerouteColourCalls(AMOLED_COLOUR_CALLS)
                        rerouteColourCalls(YOU_COLOUR_CALLS)
                        assertEquals("$themes: each reader's parse goes to the stand-in", readers.size, hookLinkBoxColour())
                    }
                    val after = owners.flatMap { owner -> context.mutableClassDefBy(owner.type).methods }
                    for ((descriptor, parse) in parses) {
                        val call = after.single { it.descriptor() == descriptor }.body()[parse.index]
                        assertEquals("$themes: $descriptor's parse", PARSE_LINK_BOX, call.call()?.toString())
                        assertEquals("$themes: static", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                        assertEquals("$themes: on the text's register", parse.value.textRegister(), call.textRegister())
                    }
                    val youParser = standIn(MATERIAL_YOU, PARSE_COLOR)
                    assertEquals("$themes: every other parse is route four's", otherParses,
                        after.sumOf { method -> method.body().count { it.call()?.toString() == youParser } })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
