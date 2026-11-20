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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil
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
     * right before that store, on the same register with a register holding the maker, and changes
     * nothing else. The tab bar padding it logs after " tabPad=" is one of its own public int fields,
     * set in its constructor, and the extension's stub comes out reading exactly that field. The one
     * helper it calls with the Activity for an Integer finds the list by a literal id and reads its
     * height, and the extension's list id stub comes out answering that id. On 588's Android 9 build
     * a static of the logging class logs what its one caller, the maker, hands it.
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
                val logged = holder.methods.single { method ->
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == ROW_HEIGHT_LOG
                    } == true
                }
                // 588's Android 9 build logs in a static of its own that the maker calls with each value.
                val logger = logged.takeIf { AccessFlags.STATIC.isSet(it.accessFlags) && reelRowStore(it) == null }
                val maker = if (logger == null) logged else FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == holder.type } }) { method ->
                    method.implementation?.instructions?.any { instruction ->
                        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        ref != null && ref.definingClass == holder.type && MethodUtil.methodSignaturesMatch(ref, logger)
                    } == true
                }.singleOrNull() ?: throw AssertionError("$name: ${holder.type}->${logged.name} has no single caller")
                val owner = if (logger == null) holder else FixtureDex.classes(bundle, setOf(maker.definingClass)).getValue(maker.definingClass)
                val before = maker.implementation!!.instructions.toList()
                val (store, register) = (if (logger == null) reelRowStore(maker) else reelRowStore(maker, logger))
                    ?: throw AssertionError("$name: ${maker.name} has no single store of the logged row height")
                // Positive control: the store is into an int field of a section the maker makes itself.
                val field = (before[store] as ReferenceInstruction).reference as FieldReference
                assertEquals("$name: the row height's field type", "I", field.type)
                val made = before.filter { it.opcode == Opcode.NEW_INSTANCE }
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                assertTrue("$name: ${field.definingClass} is made in ${maker.name}", field.definingClass in made)
                // Positive control: the maker register is `this` or its one copy, and the padding a public int field the constructor sets.
                val self = makerRegister(maker) ?: throw AssertionError("$name: ${maker.name} keeps itself in no low register")
                val parameters = maker.implementation!!.registerCount - (maker.parameterTypes.size + 1)
                assertTrue("$name: v$self holds ${maker.name}'s this", self == parameters || before.any {
                    it.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
                        (it as TwoRegisterInstruction).registerA == self && it.registerB == parameters
                })
                val padding = (if (logger == null) tabPadField(maker) else tabPadField(maker, logger))
                    ?: throw AssertionError("$name: ${maker.name} logs no tab bar padding field")
                assertTrue("$name: ${padding.name} is a public int field of ${owner.type}", owner.fields.any {
                    it.name == padding.name && it.type == "I" && AccessFlags.PUBLIC.isSet(it.accessFlags)
                } && AccessFlags.PUBLIC.isSet(owner.accessFlags))
                val constructor = owner.methods.single { it.name == "<init>" }.implementation!!.instructions
                assertTrue("$name: the constructor sets ${padding.name}", constructor.any {
                    it.opcode == Opcode.IPUT && ((it as ReferenceInstruction).reference as FieldReference).toString() == padding.toString()
                })

                // Positive control: the list height helper finds a view by a literal id and reads that view's height.
                val ask = listHeightHelper(maker) ?: throw AssertionError("$name: ${maker.name} calls no single list height helper")
                val helperClass = FixtureDex.classes(bundle, setOf(ask.definingClass)).getValue(ask.definingClass)
                val helper = helperClass.methods.single { MethodUtil.methodSignaturesMatch(it, ask) }
                val listId = firstViewId(helper) ?: throw AssertionError("$name: $ask finds no view by a literal id first")
                val helperCode = helper.implementation!!.instructions.toList()
                val find = helperCode.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "findViewById" }
                val list = (helperCode[find + 1] as OneRegisterInstruction).registerA
                assertTrue("$name: $ask reads the height of the view it finds", helperCode.drop(find + 2).take(4).any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getHeight" &&
                        (it as FiveRegisterInstruction).registerC == list
                })

                val extension = ExtensionDex.classDef(TAB_BAR_SCROLL_AWAY)
                val context = PatchContexts.of(listOf(holder, owner, helperClass, extension).distinctBy { it.type })
                context.hookReelRow { why -> throw AssertionError("$name: $why") }
                val after = context.mutableClassDefBy(owner.type).methods.single { it.name == maker.name &&
                    it.parameterTypes.map(Any::toString) == maker.parameterTypes.map(Any::toString) }
                    .implementation!!.instructions.toList()
                assertEquals("$name: ${maker.name} gained two instructions", before.opcodes().size + 2, after.opcodes().size)
                val hand = after[store] as FiveRegisterInstruction
                assertEquals("$name: the row height and the maker go to the extension right before the store",
                    listOf(Opcode.INVOKE_STATIC, REEL_ROW, 2, register, self),
                    listOf(hand.opcode, ((hand as ReferenceInstruction).reference as MethodReference).toString(), hand.registerCount,
                        hand.registerC, hand.registerD))
                val stub = context.mutableClassDefBy(TAB_BAR_SCROLL_AWAY).methods.single { it.name == TAB_PADDING_STUB }
                    .implementation!!.instructions.toList()
                assertEquals("$name: the stub reads the maker's padding",
                    listOf(Opcode.CHECK_CAST, owner.type, Opcode.IGET, padding.toString(), Opcode.RETURN),
                    listOf(stub[0].opcode, ((stub[0] as ReferenceInstruction).reference as TypeReference).type,
                        stub[1].opcode, ((stub[1] as ReferenceInstruction).reference as FieldReference).toString(), stub[2].opcode))
                val idStub = context.mutableClassDefBy(TAB_BAR_SCROLL_AWAY).methods.single { it.name == REELS_LIST_ID_STUB }
                    .implementation!!.instructions.toList()
                assertEquals("$name: the list id stub answers the id the helper finds",
                    listOf(Opcode.CONST, listId, Opcode.RETURN),
                    listOf(idStub[0].opcode, (idStub[0] as NarrowLiteralInstruction).narrowLiteral, idStub[1].opcode))
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
        val stub = ExtensionDex.classDef(type).methods.singleOrNull {
            it.name == TAB_PADDING_STUB && it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
        }
        assertNotNull("the extension has no static int $TAB_PADDING_STUB(Object) stub", stub)
        val idStub = ExtensionDex.classDef(type).methods.singleOrNull {
            it.name == REELS_LIST_ID_STUB && it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty()
        }
        assertNotNull("the extension has no static int $REELS_LIST_ID_STUB() stub", idStub)
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
