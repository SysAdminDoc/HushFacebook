/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Friends tab's People you may know hook (#600) on every Facebook build the bundle declares: the
 * section naming itself "FriendingJewelContentSection" reads its rows off a load result once, in the
 * method that sorts them by type and reads a header's section type by "type".hashCode(). Right after
 * that read the list goes through the extension and back through ImmutableList.copyOf into the same
 * register, and a null list skips both and lands on Facebook's next instruction. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FriendsTabSuggestionsFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun Instruction.call() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }

    @Test
    fun `each declared build hands the Friends tab's rows to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val sections = FixtureDex.classesHolding(bundle, FRIENDS_TAB_SECTION)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                // The load results the section reads lists off, so the patch can see their Throwable.
                val owners = sections.flatMap { methodsHolding(it, FRIENDS_TAB_HEADER) }
                    .flatMap { it.code() }
                    .filter { it.opcode == Opcode.IGET_OBJECT }
                    .mapNotNull { ((it as ReferenceInstruction).reference as FieldReference).takeIf { field ->
                        field.type == "Lcom/google/common/collect/ImmutableList;" }?.definingClass }
                    .toSet()
                val context = PatchContexts.of(sections + FixtureDex.classes(bundle, owners).values
                    .filter { owner -> sections.none { it.type == owner.type } })
                val reads = context.friendsTabRowsReads()
                assertEquals("$name: reads of the tab's rows", 1, reads.size)
                val read = reads.single()
                val code = read.method.code()
                // Positive control: the method sorts the rows by type and reads a header's section type
                // with the key the extension uses.
                assertTrue("$name: ${read.method.name} doesn't know the suggestions' rows",
                    code.any { it.string() == "PeopleYouMayKnowFriendingTabRow" })
                val typeAt = code.indexOfFirst { it.string() == "GraphQLFriendingTabSectionType" }
                assertTrue("$name: no section type read", typeAt > 0)
                assertTrue("$name: the section type isn't read by \"type\".hashCode()",
                    code.subList(0, typeAt).any { (it as? NarrowLiteralInstruction)?.narrowLiteral == "type".hashCode() })
                val register = (code[read.index] as TwoRegisterInstruction).registerA
                assertEquals("$name: the read's register", register, read.register)

                context.hookFriendsTabSuggestions()
                val method = context.mutableClassDefBy(read.method.definingClass).methods.single { patched ->
                    patched.name == read.method.name &&
                        patched.parameterTypes.map(CharSequence::toString) == read.method.parameterTypes.map(CharSequence::toString)
                }
                val patched = method.code()
                val at = read.index
                assertEquals("$name: five instructions added", code.size + 5, patched.size)
                assertEquals("$name: Facebook's read stays first", Opcode.IGET_OBJECT, patched[at].opcode)
                val skip = patched[at + 1]
                assertEquals("$name: a null list skips the hook", listOf(Opcode.IF_EQZ, register),
                    listOf(skip.opcode, (skip as OneRegisterInstruction).registerA))
                assertEquals("$name: and lands on Facebook's next instruction", at + 6,
                    (skip as BuilderOffsetInstruction).target.location.index)
                for ((offset, hook) in listOf(2 to FRIENDS_TAB_ROWS,
                    4 to "Lcom/google/common/collect/ImmutableList;->copyOf(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableList;")) {
                    val call = patched[at + offset]
                    assertEquals("$name: calls $hook", hook, call.call())
                    assertEquals("$name: with the rows", listOf(Opcode.INVOKE_STATIC_RANGE, register, 1),
                        listOf(call.opcode, (call as RegisterRangeInstruction).startRegister, call.registerCount))
                    val answer = patched[at + offset + 1]
                    assertEquals("$name: its answer back in the rows' register", listOf(Opcode.MOVE_RESULT_OBJECT, register),
                        listOf(answer.opcode, (answer as OneRegisterInstruction).registerA))
                }
                val jumps = method.implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>()
                assertTrue("$name: nothing jumps into the hook",
                    jumps.none { it.target.location.index in at + 1..at + 5 })
                assertEquals("$name: the rest is Facebook's", code.map { it.opcode },
                    patched.filterIndexed { index, _ -> index !in at + 1..at + 5 }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val type = FRIENDS_TAB_ROWS.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "rows" }
        assertNotNull("the extension has no FriendsTabSuggestions.rows", hook)
        assertEquals("rows' shape", FRIENDS_TAB_ROWS,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("rows isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
