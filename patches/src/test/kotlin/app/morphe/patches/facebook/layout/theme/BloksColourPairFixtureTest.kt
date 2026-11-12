/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
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
 * Issue #37's Menu profile card on each declared build: the card at the top of Menu, with the
 * account's name and picture, is a Bloks colour pair, #333334 on its dark side (582 `LX/4Hb;->A00`
 * asks the screen's model for it, seen on the emulator). One model logs a pair it can't parse, its
 * interface names the others, and each one's single parse goes to Material You's Bloks stand-in on
 * the text's register after route four, with AMOLED first or not.
 */
class BloksColourPairFixtureTest {
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

    /** An instance (object, int, int, int) method answering an int, abstract or not. */
    private fun Method.pairShaped(): Boolean =
        !AccessFlags.STATIC.isSet(accessFlags) && returnType == "I" &&
            parameterTypes.map(CharSequence::toString).let { it.size == 4 && it[0].startsWith("L") && it.drop(1) == listOf("I", "I", "I") }

    @Test
    fun `each Bloks model's colour pair parse goes to Material You's stand-in`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val owners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.pairShaped() }) owners += ImmutableClassDef.of(classDef)
                    }
                }
                val anchor = owners.flatMap { it.methods }.filter(::isLoggingBloksColourPair).let { found ->
                    assertEquals("$name: one model logs a pair it can't parse (${found.map { it.descriptor() }})", 1, found.size)
                    found.single()
                }
                val accessor = owners.single { it.type == anchor.definingClass }.interfaces.single { type ->
                    owners.any { it.type == type && AccessFlags.INTERFACE.isSet(it.accessFlags) && it.methods.any { m -> m.sameAs(anchor) } }
                }
                // 585's models without colours of their own only answer the fallback, and 586's LX/0va1
                // hands its pair to the logging model; those are left alone.
                val models = owners.filter { accessor in it.interfaces }
                    .mapNotNull { model -> model.methods.singleOrNull { it.sameAs(anchor) && it.implementation != null } }
                    .filterNot(::answersFallbackOnly)
                val (relays, readers) = models.partition { handsColourPairOn(it, anchor) }
                assertTrue("$name: the logging model and the sparse one, at least (${readers.map { it.descriptor() }})", readers.size >= 2)
                for (relay in relays) {
                    val to = relay.body().mapNotNull { it.call() }.filter { it.name == anchor.name }.map { it.definingClass }
                    assertTrue("$name: ${relay.descriptor()} hands its pair to a model that parses ($to)",
                        to.isNotEmpty() && to.all { type -> readers.any { it.definingClass == type } })
                }
                val parses = readers.associate { reader ->
                    val calls = reader.body().withIndex().filter { it.value.call()?.toString() == PARSE_COLOR }
                    assertEquals("$name: ${reader.descriptor()} parses one colour", 1, calls.size)
                    reader.descriptor() to calls.single()
                }

                for (amoled in listOf(false, true)) {
                    val themes = "$name, AMOLED $amoled"
                    val context = PatchContexts.of(owners)
                    with(context) {
                        if (amoled) rerouteColourCalls(AMOLED_COLOUR_CALLS)
                        rerouteColourCalls(YOU_COLOUR_CALLS)
                        assertEquals("$themes: each model's parse goes to the stand-in", readers.size, hookBloksColourPairs())
                    }
                    for ((descriptor, parse) in parses) {
                        val owner = descriptor.substringBefore("->")
                        val call = context.mutableClassDefBy(owner).methods.single { it.descriptor() == descriptor }.body()[parse.index]
                        assertEquals("$themes: $descriptor's parse", PARSE_BLOKS, call.call()?.toString())
                        assertEquals("$themes: static", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                        assertEquals("$themes: on the text's register", parse.value.textRegister(), call.textRegister())
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Issue #34 on each declared build: AMOLED sends the same parse, after its route four, to its own
     * Bloks stand-in, which lifts a secondary text grey on a lighter Background colour. Material You
     * after it takes each of those calls over, on the same register, so the two never stack.
     */
    @Test
    fun `AMOLED's Bloks parse goes to its own stand-in, and Material You takes it over`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val owners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.pairShaped() }) owners += ImmutableClassDef.of(classDef)
                    }
                }
                fun Method.callsTo(target: String) = body().withIndex().filter { it.value.call()?.toString() == target }
                    .map { Triple(descriptor(), it.index, it.value.textRegister()) }
                val parsing = owners.filter { owner -> owner.methods.any { it.pairShaped() && it.callsTo(PARSE_COLOR).isNotEmpty() } }
                val parses = parsing.flatMap { owner -> owner.methods.flatMap { it.callsTo(PARSE_COLOR) } }.toSet()

                val context = PatchContexts.of(owners)
                fun patchedCalls(target: String) = parsing.flatMap { owner -> context.mutableClassDefBy(owner.type).methods.flatMap { it.callsTo(target) } }
                val hooked = with(context) {
                    rerouteColourCalls(AMOLED_COLOUR_CALLS)
                    hookBloksColourPairs(setOf(PARSE_COLOR_DARK), AMOLED_PARSE_BLOKS)
                }
                assertTrue("$name: the logging model and the sparse one, at least ($hooked)", hooked >= 2)
                val amoled = patchedCalls(AMOLED_PARSE_BLOKS)
                assertEquals("$name: one AMOLED Bloks parse per model", hooked, amoled.size)
                for (call in amoled) assertTrue("$name: $call was a parse, on the text's register", call in parses)

                with(context) {
                    rerouteColourCalls(YOU_COLOUR_CALLS)
                    assertEquals("$name: Material You takes each one over", hooked, hookBloksColourPairs())
                }
                assertEquals("$name: no AMOLED Bloks parse left", emptyList<Triple<String, Int, Int>>(), patchedCalls(AMOLED_PARSE_BLOKS))
                assertEquals("$name: Material You's, where AMOLED's were", amoled.toSet(), patchedCalls(PARSE_BLOKS).toSet())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
