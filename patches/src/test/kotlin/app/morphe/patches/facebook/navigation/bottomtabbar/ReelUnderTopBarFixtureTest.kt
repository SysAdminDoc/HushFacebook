/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reel under a top tab bar (#264), and the Reels header kept below it (#390), on every Facebook
 * build the bundle declares: the content
 * manager is the one class tracing the padding setup, it has exactly two top padding methods (each
 * ends by returning a static (controller, boolean) int answer), and the patch hands each answer to
 * the extension right before its return and changes nothing else. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelUnderTopBarFixtureTest {
    private fun List<Instruction>.opcodes() = map { it.opcode }.filter { it != Opcode.NOP }

    @Test
    fun `each declared build hands the content's top padding to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, CONTENT_PADDING_TRACE)
                    .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.distinctBy { it.type }
                assertEquals("$name: classes tracing \"$CONTENT_PADDING_TRACE\"", 1, holders.size)
                val holder = holders.single()
                val methods = topPaddingMethods(holder)
                assertEquals("$name: top padding methods", 2, methods.size)
                val before = methods.associate { (method, _) -> method.name to method.implementation!!.instructions.toList() }
                // Positive control: each ends by returning the answer of a (controller, boolean) int call.
                for ((method, register) in methods) {
                    val code = before.getValue(method.name)
                    val ask = (code[code.size - 3] as ReferenceInstruction).reference as MethodReference
                    assertEquals("$name: ${method.name} asks a static", Opcode.INVOKE_STATIC, code[code.size - 3].opcode)
                    assertEquals("$name: ${method.name}'s answer", "I", ask.returnType)
                    assertTrue("$name: ${method.name} returns what it asked", (code.last() as OneRegisterInstruction).registerA == register)
                }
                val context = PatchContexts.of(listOf(holder))
                context.hookReelUnderTopBar { why -> throw AssertionError("$name: $why") }
                val patched = context.mutableClassDefBy(holder.type)
                for ((method, register) in methods) {
                    val code = before.getValue(method.name)
                    val after = patched.methods.single { it.name == method.name && it.parameterTypes.isEmpty() }
                        .implementation!!.instructions.toList()
                    assertEquals("$name: ${method.name} gained two instructions", code.opcodes().size + 2, after.opcodes().size)
                    val hand = after[after.size - 3] as FiveRegisterInstruction
                    assertEquals("$name: ${method.name} hands the answer to the extension",
                        listOf(Opcode.INVOKE_STATIC, PAGES_TOP, 1, register),
                        listOf(hand.opcode, ((hand as ReferenceInstruction).reference as MethodReference).toString(), hand.registerCount, hand.registerC))
                    assertEquals("$name: ${method.name} takes the answer back",
                        listOf(Opcode.MOVE_RESULT, register, Opcode.RETURN, register),
                        listOf(after[after.size - 2].opcode, (after[after.size - 2] as OneRegisterInstruction).registerA,
                            after.last().opcode, (after.last() as OneRegisterInstruction).registerA))
                    assertEquals("$name: the rest of ${method.name} is Facebook's", code.opcodes().dropLast(1),
                        after.opcodes().dropLast(3))
                }
            }
        }
    }

    /**
     * #390: the Reels header. onCreateView finds it on four paths that all meet at one store into
     * a LithoView field, and the hook goes right after that store with the register it stored.
     */
    @Test
    fun `each declared build hands the Reels header to the extension right after storing it`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val fragment = FixtureDex.classes(bundle, setOf(VIDEO_HOME_ROOT_FRAGMENT)).getValue(VIDEO_HOME_ROOT_FRAGMENT)
                val onCreateView = fragment.methods.single { it.name == "onCreateView" }
                val before = onCreateView.implementation!!.instructions.toList()
                val (store, register) = reelsHeaderStore(onCreateView)
                    ?: throw AssertionError("$name: onCreateView has no single store of a LithoView field")
                // Positive control: the store is of a LithoView field of the fragment, and every LithoView cast in the method feeds it.
                val field = (before[store] as ReferenceInstruction).reference as FieldReference
                assertEquals("$name: the header's field", listOf(VIDEO_HOME_ROOT_FRAGMENT, "Lcom/facebook/litho/LithoView;"),
                    listOf(field.definingClass, field.type))
                val casts = before.filter { it.opcode == Opcode.CHECK_CAST &&
                    ((it as ReferenceInstruction).reference as TypeReference).type == "Lcom/facebook/litho/LithoView;" }
                assertTrue("$name: LithoView casts in onCreateView", casts.size >= 2)

                val context = PatchContexts.of(listOf(fragment))
                context.hookReelsHeader { why -> throw AssertionError("$name: $why") }
                val after = context.mutableClassDefBy(VIDEO_HOME_ROOT_FRAGMENT).methods.single { it.name == "onCreateView" }
                    .implementation!!.instructions.toList()
                assertEquals("$name: onCreateView gained one instruction", before.opcodes().size + 1, after.opcodes().size)
                val hand = after[store + 1] as FiveRegisterInstruction
                assertEquals("$name: the header goes to the extension right after the store",
                    listOf(Opcode.INVOKE_STATIC, REELS_HEADER_MADE, 1, register),
                    listOf(hand.opcode, ((hand as ReferenceInstruction).reference as MethodReference).toString(), hand.registerCount, hand.registerC))
                assertEquals("$name: the rest of onCreateView is Facebook's", before.opcodes(),
                    (after.take(store + 1) + after.drop(store + 2)).opcodes())
            }
        }
    }

    /**
     * #466: the Reels row height. The list's maker logs the height it worked out after " computed="
     * and stores that register once into an int field of the list section it makes; the hook goes
     * right before that store, on the same register, and changes nothing else.
     */
    @Test
    fun `each declared build hands the Reels row height to the extension right before storing it`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, ROW_HEIGHT_LOG)
                    .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.distinctBy { it.type }
                assertEquals("$name: classes logging \"$ROW_HEIGHT_LOG\"", 1, holders.size)
                val holder = holders.single()
                val maker = holder.methods.single { method ->
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == ROW_HEIGHT_LOG
                    } == true
                }
                val before = maker.implementation!!.instructions.toList()
                val (store, register) = reelRowStore(maker)
                    ?: throw AssertionError("$name: ${maker.name} has no single store of the logged row height")
                // Positive control: the store is into an int field of a section the maker makes itself.
                val field = (before[store] as ReferenceInstruction).reference as FieldReference
                assertEquals("$name: the row height's field type", "I", field.type)
                val made = before.filter { it.opcode == Opcode.NEW_INSTANCE }
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                assertTrue("$name: ${field.definingClass} is made in ${maker.name}", field.definingClass in made)

                val context = PatchContexts.of(listOf(holder))
                context.hookReelRow { why -> throw AssertionError("$name: $why") }
                val after = context.mutableClassDefBy(holder.type).methods.single { it.name == maker.name &&
                    it.parameterTypes.map(Any::toString) == maker.parameterTypes.map(Any::toString) }
                    .implementation!!.instructions.toList()
                assertEquals("$name: ${maker.name} gained two instructions", before.opcodes().size + 2, after.opcodes().size)
                val hand = after[store] as FiveRegisterInstruction
                assertEquals("$name: the row height goes to the extension right before the store",
                    listOf(Opcode.INVOKE_STATIC, REEL_ROW, 1, register),
                    listOf(hand.opcode, ((hand as ReferenceInstruction).reference as MethodReference).toString(), hand.registerCount, hand.registerC))
                assertEquals("$name: the answer is what gets stored",
                    listOf(Opcode.MOVE_RESULT, register, Opcode.IPUT, field.toString()),
                    listOf(after[store + 1].opcode, (after[store + 1] as OneRegisterInstruction).registerA,
                        after[store + 2].opcode, ((after[store + 2] as ReferenceInstruction).reference as FieldReference).toString()))
                assertEquals("$name: the rest of ${maker.name} is Facebook's", before.opcodes(),
                    (after.take(store) + after.drop(store + 2)).opcodes())
            }
        }
    }

    @Test
    fun `the extension has the row hook`() {
        val type = REEL_ROW.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "reelRow" }
        assertNotNull("the extension has no TabBarScrollAway.reelRow", hook)
        assertEquals(REEL_ROW, "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("reelRow isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }

    @Test
    fun `the extension has the header hook`() {
        val type = REELS_HEADER_MADE.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "headerMade" }
        assertNotNull("the extension has no ReelsHeaderClear.headerMade", hook)
        assertEquals(REELS_HEADER_MADE, "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("headerMade isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }

    @Test
    fun `the extension has the hook`() {
        val type = PAGES_TOP.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "pagesTop" }
        assertNotNull("the extension has no TabBarScrollAway.pagesTop", hook)
        assertEquals(PAGES_TOP, "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("pagesTop isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
