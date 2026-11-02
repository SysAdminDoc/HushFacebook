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
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every clipboard write Facebook makes goes to the extension's stand-in, in the same registers, so
 * a hold's copy of a post's text can open the box without reaching the clipboard (#342). The
 * "Text copied" popup Facebook shows after that copy is shown only past the extension's check, so
 * it can be dropped.
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

    private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.names() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    @Test
    fun `on each declared build the popup after a hold's copy shows only past the extension's check`() {
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
                        if (classDef.type.startsWith(EXTENSION_PACKAGE) || classDef.type in writers) continue
                        if (classDef.methods.any { it.implementation?.instructions?.any(::isClipboardWrite) == true }) {
                            writers[classDef.type] = ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val helpers = writers.values.flatMap { it.methods }
                    .filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.code().any(::isClipboardWrite) }
                    .mapTo(mutableSetOf()) { it.signature() }
                assertTrue("$name: no static clipboard helper", helpers.isNotEmpty())
                val users = linkedMapOf<String, ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.type in writers || classDef.type in users) continue
                        if (classDef.methods.any { method -> method.implementation?.instructions?.any { it.names() in helpers } == true }) {
                            users[classDef.type] = ImmutableClassDef.of(classDef)
                        }
                    }
                }

                val context = PatchContexts.of(writers.values + users.values + ExtensionDex.classDef(postTextSelect))
                val popups = context.copyPopups(context.clipboardWriters())
                // Positive control: one of them is the hold on a post's text, the static answering a
                // boolean that takes the held view first and reads the post as a GraphQLStory.
                val holds = popups.filter { popup ->
                    val method = popup.method
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
                        method.parameterTypes.firstOrNull()?.toString() == VIEW &&
                        method.code().any { it.opcode == Opcode.CHECK_CAST && (it as ReferenceInstruction).reference.toString() == GRAPHQL_STORY }
                }
                assertEquals("$name: popups after the hold's copy", 1, holds.size)
                val shows = popups.mapTo(mutableSetOf()) { it.method.code()[it.show].names() }
                assertEquals("$name: every copy shows the same popup", 1, shows.size)

                val before = popups.groupBy { it.method.signature() }.mapValues { (_, sites) -> sites.first().method.code() }
                context.guardCopyPopups(popups)
                for ((signature, sites) in popups.groupBy { it.method.signature() }) {
                    val method = sites.first().method
                    val code = before.getValue(signature)
                    val patched = context.mutableClassDefBy(method.definingClass).methods
                        .single { it.signature() == signature }.implementation!!.instructions.toList()
                    assertEquals("$name: $signature has three instructions a popup", code.size + 3 * sites.size, patched.size)
                    for ((earlier, site) in sites.sortedBy { it.show }.withIndex()) {
                        val at = site.show + 3 * earlier
                        val ask = patched[at]
                        assertEquals("$name: $signature asks the extension at ${site.show}", DROPS_POPUP, ask.names())
                        assertEquals("$name: $signature keeps the answer in the borrowed local",
                            site.borrowed, (patched[at + 1] as OneRegisterInstruction).registerA)
                        val branch = patched[at + 2] as BuilderOffsetInstruction
                        assertEquals("$name: $signature tests the answer", Opcode.IF_NEZ, branch.opcode)
                        assertEquals("$name: $signature tests the borrowed local", site.borrowed, (branch as OneRegisterInstruction).registerA)
                        assertEquals("$name: $signature jumps past the show", at + 4, branch.target.location.index)
                        assertEquals("$name: $signature still shows Facebook's popup", code[site.show].names(), patched[at + 3].names())
                    }
                    val asks = shiftedAsks(sites.map { it.show })
                    assertEquals("$name: the rest of $signature is Facebook's", code.map { it.opcode },
                        patched.filterIndexed { index, _ -> index !in asks }.map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The indexes the three-instruction checks land at once each of [shows] has one in front of it. */
    private fun shiftedAsks(shows: List<Int>): Set<Int> =
        shows.sorted().withIndex().flatMap { (earlier, show) -> (0..2).map { show + 3 * earlier + it } }.toSet()

    @Test
    fun `the extension has the stand-in and the check`() {
        for ((name, signature) in listOf("copy" to TEXT_COPY, "dropsPopup" to DROPS_POPUP)) {
            val method = ExtensionDex.classDef(postTextSelect).methods.singleOrNull { it.name == name }
            assertNotNull("PostTextSelect has no $name", method)
            assertTrue("$name isn't public static", AccessFlags.PUBLIC.isSet(method!!.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
            assertEquals(signature, method.signature())
        }
    }
}
