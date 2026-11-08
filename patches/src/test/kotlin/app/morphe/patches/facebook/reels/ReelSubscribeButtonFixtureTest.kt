/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.tableIndexAt
import app.morphe.patches.facebook.feed.tableIndices
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.RegisterLiveness
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Subscribe pill beside a full-screen video's author (#291): in each declared build the viewer's
 * author row hands its pill flag to the extension where it sets it, the shared pill builder notes
 * the owner answer before its tag, every row slot holding that builder's pill asks the extension
 * whether to keep it, and the row with a pill of its own asks before it builds it.
 */
class ReelSubscribeButtonFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    private val Instruction.call get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.loads(string: String) = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    private fun ClassDef.method(like: Method) = methods.single {
        it.name == like.name && it.parameterTypes.map(CharSequence::toString) == like.parameterTypes.map(CharSequence::toString)
    }

    /** The methods asking one of [holders]' string tables for [literal], as the Android 9+ build's rows do (#102). */
    private fun asking(bundle: File, holders: List<ClassDef>, literal: String): List<Method> {
        val tables = holders.flatMap { owner -> owner.methods.filter { isStringTable(it) && holdsString(it, literal) } }
        val indices = tables.associate { "${it.definingClass}->${it.name}" to tableIndices(it, literal) }
        val types = tables.map { it.definingClass }.toSet()
        if (types.isEmpty()) return emptyList()
        return FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in types } }) { method ->
            val code = method.implementation?.instructions?.toList() ?: return@methodsWhere false
            code.indices.any { at ->
                val index = tableIndexAt(code, at) ?: return@any false
                val call = code[at].call!!
                indices["${call.definingClass}->${call.name}"]?.contains(index) == true
            }
        }
    }

    @Test
    fun `each declared build's author pill asks the extension in the row, the builder and every slot`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, AUTHOR_PILL_TAG)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val authorAskers = asking(bundle, holders, AUTHOR_PILL_TAG)
                val holderMethods = holders.flatMap { owner -> owner.methods.filter { it.loads(AUTHOR_PILL_TAG) } } + authorAskers
                val footerHolders = FixtureDex.classesHolding(bundle, FOOTER_PILL_TAG)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val footerAskers = asking(bundle, footerHolders, FOOTER_PILL_TAG)
                val footerMethods = footerHolders.flatMap { owner -> owner.methods.filter { it.loads(FOOTER_PILL_TAG) } } + footerAskers
                val tables = (holders + footerHolders).associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val called = FixtureDex.classes(bundle, (holderMethods + footerMethods).flatMap { it.code() }
                    .mapNotNull { it.call?.takeIf { call -> call.returnType == "Z" }?.definingClass }.toSet())
                val isOwnerCheck = { call: MethodReference ->
                    called[call.definingClass]?.methods?.firstOrNull {
                        it.name == call.name && it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
                    }?.let(::isSubscribeOwnerCheck) == true
                }
                val pills = holderMethods.mapNotNull { authorPill(it, isOwnerCheck, resolve) }
                assertEquals("$name: author rows building the pill", 1, pills.count { !it.returned })
                assertEquals("$name: builders returning the pill", 1, pills.count { it.returned })
                val row = pills.single { !it.returned }
                val builder = pills.single { it.returned }
                assertNotNull("$name: the row's pill flag", pillFlag(row))
                assertNotNull("$name: the row's slot key", slotKey(row))
                val ownerClass = called.values.single { owner -> owner.methods.any(::isSubscribeOwnerCheck) }

                val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.contains(builder.method.definingClass) }) { method ->
                    method.implementation?.instructions?.any { it.call?.definingClass == builder.method.definingClass } == true
                }.map { it.definingClass }.toSet()
                val footers = footerMethods.mapNotNull { footerPill(it, isOwnerCheck, resolve) }
                assertEquals("$name: rows building their own pill", 1, footers.size)
                val footer = footers.single()
                val fanHolders = FixtureDex.classesHolding(bundle, FAN_PILL_TAG)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val fanBuilder = fanPillBuilders(fanHolders.flatMap { owner -> owner.methods.filter { it.loads(FAN_PILL_TAG) } }).singleOrNull()
                assertNotNull("$name: one builder returning the fan-subscription pill", fanBuilder)
                val fanCallers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.contains(fanBuilder!!.definingClass) }) { method ->
                    method.implementation?.instructions?.any {
                        it.call?.let { call -> call.definingClass == fanBuilder!!.definingClass && call.name == fanBuilder.name } == true
                    } == true
                }.map { it.definingClass }.toSet()
                val askers = (authorAskers + footerAskers).map { it.definingClass }.toSet()
                val classes = (holders + footerHolders + fanHolders + called.values +
                    FixtureDex.classes(bundle, callers + fanCallers + askers).values).distinctBy { it.type }
                assertThrows("$name: no owner check must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(classes.filter { it.type != ownerClass.type }).hideSubscribePill()
                }
                val context = PatchContexts.of(classes)
                context.hideSubscribePill()

                val patchedRow = context.mutableClassDefBy(row.method.definingClass).method(row.method)
                val rowCode = patchedRow.code()
                val shows = rowCode.indices.filter { rowCode[it].call?.toString() == SHOW_AUTHOR_PILL }
                assertEquals("$name: one pill flag hook in the row", 1, shows.size)
                val show = shows.single()
                val flag = (rowCode[show + 1] as OneRegisterInstruction).registerA
                assertEquals("$name: the extension's answer replaces the flag", Opcode.MOVE_RESULT, rowCode[show + 1].opcode)
                // A flag past v15, as on the Android 9+ build (#102), goes over in a low register the row
                // doesn't read after the hook.
                val passed = arguments(rowCode[show])[1]
                val set = if (passed == flag) show - 1 else show - 2
                if (passed != flag) {
                    assertTrue("$name: the flag sits past v15", flag > 15)
                    assertEquals("$name: the flag is copied low", Opcode.MOVE_FROM16, rowCode[show - 1].opcode)
                    assertEquals("$name: from the flag", listOf(passed, flag), rowCode[show - 1].namedRegisters())
                    assertTrue("$name: the row doesn't read the low register after the hook",
                        passed !in RegisterLiveness.of(patchedRow).liveInto(show + 2))
                }
                assertTrue("$name: the hook follows the flag's 1", rowCode[set].opcode in setOf(Opcode.CONST_4, Opcode.CONST_16))
                assertEquals("$name: into the flag's register", flag, (rowCode[set] as OneRegisterInstruction).registerA)
                assertEquals("$name: set to 1", 1, (rowCode[set] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: the row gains its hook",
                    row.method.code().size + show - set + 1,
                    rowCode.size - rowCode.count { it.call?.toString() == AUTHOR_PILL_SLOT || it.call?.toString() == FAN_PILL_SLOT } * 3)

                val footerCode = context.mutableClassDefBy(footer.method.definingClass).method(footer.method).code()
                val answers = footerCode.indices.filter { footerCode[it].call?.toString() == AUTHOR_PILL_ANSWER }
                assertEquals("$name: one owner answer hook in the row", 1, answers.size)
                val answer = answers.single()
                assertEquals("$name: the hook follows the owner answer", Opcode.MOVE_RESULT, footerCode[answer - 1].opcode)
                assertEquals("$name: the hook passes the owner answer", footer.answer, arguments(footerCode[answer]).single())
                assertEquals("$name: the extension's answer replaces it", Opcode.MOVE_RESULT, footerCode[answer + 1].opcode)
                assertEquals("$name: -1 takes the way for no pill", Opcode.IF_LTZ, footerCode[answer + 2].opcode)
                assertEquals("$name: the row's own branch still follows", Opcode.IF_EQZ, footerCode[answer + 3].opcode)
                val footerAt = footerCode.runningFold(0) { sum, instruction -> sum + instruction.codeUnits }
                val noPill = footerAt.indexOf(footerAt[answer + 2] + (footerCode[answer + 2] as OffsetInstruction).codeOffset)
                assertEquals("$name: the way for no pill is the one the row already had",
                    footer.method.code()[footer.noPill].let { it.opcode to it.namedRegisters() }, footerCode[noPill].let { it.opcode to it.namedRegisters() })

                val builderCode = context.mutableClassDefBy(builder.method.definingClass).method(builder.method).code()
                val notes = builderCode.indices.filter { builderCode[it].call?.toString() == NOTE_AUTHOR_PILL }
                assertEquals("$name: one owner note in the builder", 1, notes.size)
                val noted = notes.single() + 1
                assertEquals("$name: the note comes right before the tag", AUTHOR_PILL_TAG,
                    ((builderCode[noted] as? ReferenceInstruction)?.reference as? StringReference)?.string
                        ?: tableStringAt(builderCode, noted + 1, resolve))

                var slots = 0
                for (type in callers + row.method.definingClass) {
                    for (method in context.mutableClassDefBy(type).methods) {
                        val code = method.implementation?.instructions?.toList() ?: continue
                        for (at in code.indices.filter { code[it].call?.toString() == AUTHOR_PILL_SLOT }) {
                            val init = code[at - 1].call
                            assertEquals("$name: a slot hook follows a constructor", "<init>", init?.name)
                            val register = arguments(code[at]).single()
                            assertEquals("$name: the hook passes the new slot", arguments(code[at - 1]).first(), register)
                            assertEquals("$name: the extension's answer replaces the slot", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
                            assertEquals("$name: into the same register", register, (code[at + 1] as OneRegisterInstruction).registerA)
                            assertEquals("$name: cast back to the slot's class", init!!.definingClass,
                                ((code[at + 2] as ReferenceInstruction).reference as TypeReference).type)
                            slots++
                        }
                    }
                }
                // 585: the viewer row's slot after the wrapper, and two in the reel footer's render.
                assertTrue("$name: fewer than three pill slots hooked ($slots)", slots >= 3)

                var fanSlots = 0
                for (type in fanCallers) {
                    for (method in context.mutableClassDefBy(type).methods) {
                        val code = method.implementation?.instructions?.toList() ?: continue
                        for (at in code.indices.filter { code[it].call?.toString() == FAN_PILL_SLOT }) {
                            val init = code[at - 1].call
                            assertEquals("$name: a fan slot hook follows a constructor", "<init>", init?.name)
                            assertEquals("$name: of a row slot", slotKey(row), init!!.parameterTypes.first().toString())
                            val register = arguments(code[at]).single()
                            assertEquals("$name: the hook passes the new slot", arguments(code[at - 1]).first(), register)
                            assertEquals("$name: the extension's answer replaces the slot", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
                            assertEquals("$name: into the same register", register, (code[at + 1] as OneRegisterInstruction).registerA)
                            assertEquals("$name: cast back to the slot's class", init.definingClass,
                                ((code[at + 2] as ReferenceInstruction).reference as TypeReference).type)
                            assertTrue("$name: the fan slot comes after the fan pill is built", code.subList(0, at).any {
                                it.call?.let { call -> call.definingClass == fanBuilder!!.definingClass && call.name == fanBuilder.name } == true
                            })
                            fanSlots++
                        }
                    }
                }
                // 585: the viewer row puts the fan pill in its own slot and, through a getter, in a second one.
                assertEquals("$name: fan-subscription pill slots hooked", 2, fanSlots)
                checked += version
                // 585's Android 9 build ("arm64-v8a-minapi28-storeless") is another arm64-v8a bundle.
                abis += name.substringAfter("-$version-").substringBefore(".apkm").substringBefore("-minapi")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), abis)
    }

    @Test
    fun `the extension has the hooks`() {
        for (descriptor in listOf(SHOW_AUTHOR_PILL, NOTE_AUTHOR_PILL, AUTHOR_PILL_SLOT, AUTHOR_PILL_ANSWER, FAN_PILL_SLOT)) {
            val name = descriptor.substringAfter("->").substringBefore("(")
            val hook = ExtensionDex.classDef(descriptor.substringBefore("->")).methods.singleOrNull { it.name == name }
            assertNotNull("the extension has no ReelDeclutter.$name", hook)
            assertEquals("$name's shape", descriptor,
                "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
        }
    }
}
