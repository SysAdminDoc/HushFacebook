/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.profile

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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #597: on each declared build and ABI, the All friends section's event method casts the
 * section, then asks the list's load for its PaginableList in the "all_friends_list" case. After the
 * patch, the section goes to FriendList.section right after the cast, and the list, from the
 * register it moved into, goes to FriendList.allFriendsPage right after it's read, with the rest of
 * the method kept. The own-list flag is the one the build method reads, and the empty-search flag is
 * the event method's other one. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class FriendListFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.shape() = parameterTypes.map(CharSequence::toString)

    private fun kind(instruction: Instruction) = when (instruction.opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> instruction.opcode
    }

    private fun kinds(code: List<Instruction>) = code.filter { it.opcode != Opcode.NOP }.map(::kind)

    private fun handed(hook: Instruction, where: String): Int {
        assertEquals("$where: a static call", Opcode.INVOKE_STATIC, hook.opcode)
        val call = hook as FiveRegisterInstruction
        assertEquals("$where: with one register", 1, call.registerCount)
        return call.registerC
    }

    @Test
    fun `your friends list and its section go to the extension where the section reads it, on each declared build`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        var bundles = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, ALL_FRIENDS_SECTION)
                assertTrue("$name: nothing names \"$ALL_FRIENDS_SECTION\"", holders.isNotEmpty())
                val context = PatchContexts.of(holders)
                val sites = context.findFriendListSites()
                val where = "$name ${sites.event.definingClass}->${sites.event.name}"
                val stock = sites.event.body()

                // The place: the cast to the section, the list's name, then the read of its PaginableList.
                assertEquals("$where: the cast is to the section", sites.event.definingClass,
                    ((stock[sites.cast] as ReferenceInstruction).reference as TypeReference).type)
                val named = stock.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == ALL_FRIENDS_LIST }
                assertTrue("$where: the cast comes before the list's name", sites.cast < named)
                assertTrue("$where: the read comes after it", sites.read > named)
                assertEquals("$where: the read answers the list", PAGINABLE_LIST, stock[sites.read].method()!!.returnType)
                assertEquals("$where: the list moves into the hooked register",
                    (stock[sites.read + 1] as OneRegisterInstruction).registerA, sites.page)
                // The two flags: different fields of the section, the filters' flag being neither.
                assertNotEquals("$where: the two flags are different fields", sites.ownField, sites.noSearchField)
                val filters = stock.subList(sites.cast + 1, named).mapNotNull {
                    if (it.opcode != Opcode.IGET_BOOLEAN) null else ((it as ReferenceInstruction).reference as FieldReference).name
                }
                assertTrue("$where: the filters' flag is read between the cast and the name", filters.isNotEmpty())
                assertTrue("$where: and it's neither of the two", filters.none { it == sites.ownField || it == sites.noSearchField })

                assertThrows("$name: a build with no such section must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).findFriendListSites()
                }

                context.hookFriendList(sites)
                val after = context.mutableClassDefBy(sites.event.definingClass).methods.single {
                    it.name == sites.event.name && it.returnType == sites.event.returnType && it.shape() == sites.event.shape()
                }.body()
                assertEquals("$where: the cast stays where it was", Opcode.CHECK_CAST, after[sites.cast].opcode)
                val sectionHook = after[sites.cast + 1]
                assertEquals("$where: right after it, the section goes to FriendList.section", SECTION_HOOK, sectionHook.method().toString())
                assertEquals("$where: from the register it was cast in", sites.section, handed(sectionHook, where))
                // One instruction went in before the read, so it moved down by one.
                val read = sites.read + 1
                assertEquals("$where: the read stays", PAGINABLE_LIST, after[read].method()!!.returnType)
                assertEquals("$where: and its result", Opcode.MOVE_RESULT_OBJECT, after[read + 1].opcode)
                val pageHook = after[read + 2]
                assertEquals("$where: right after, the list goes to FriendList.allFriendsPage", PAGE_HOOK, pageHook.method().toString())
                assertEquals("$where: from the register it moved into", sites.page, handed(pageHook, where))
                assertEquals("$where: the rest of the method is kept", kinds(stock),
                    kinds(after.filterIndexed { index, _ -> index != sites.cast + 1 && index != read + 2 }))
                bundles++
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertTrue("only $bundles bundles checked", bundles >= versions.size)
    }
}
