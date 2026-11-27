/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where each declared build picks whether the search screen before typing comes as one page the
 * server draws (#600): the global search's app job builds one supplier, and that supplier's
 * constructor has one boolean read tested at once whose untaken side puts a single supplier in a
 * list. The build still has the server page's supplier, named by its network. After the patch the
 * read's answer goes through the extension, in the same register, before the test, and nothing
 * else changes. Read from 588 (2026-11-26): the job's supplier is `LX/0BwI;`, the server page's
 * `LX/0BQe;`. None of those names is used here.
 */
class PreSearchPageFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Instruction.call() = ((this as ReferenceInstruction).reference as MethodReference).let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }

    private fun Method.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    @Test
    fun `each declared build's search screen asks the extension before it picks the server page`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                assertTrue("$name: no server page supplier",
                    FixtureDex.classesHolding(bundle, "nullstate_global_full_bloks_network").isNotEmpty())
                val job = FixtureDex.classes(bundle, setOf(SEARCH_GLOBAL_JOB))[SEARCH_GLOBAL_JOB]
                assertNotNull("$name: no $SEARCH_GLOBAL_JOB", job)
                val type = preSearchSupplierType(job!!)
                assertNotNull("$name: the app job builds no one supplier", type)
                val supplier = FixtureDex.classes(bundle, setOf(type!!)).getValue(type)
                val site = preSearchPageSite(supplier)
                assertNotNull("$name: no one read picking the server page", site)
                assertNull("$name: the app job has a read of its own like it", preSearchPageSite(job))
                val stock = site!!.method.implementation!!.instructions.toList()

                val context = PatchContexts.of(listOf(job, supplier))
                context.hookPreSearchPage()
                val code = context.mutableClassDefBy(type).methods.single { it.smali() == site.method.smali() }
                    .implementation!!.instructions.toList()
                val hook = site.read + 1
                assertEquals("$name: the constructor grew by other than the hook", stock.size + 2, code.size)
                assertEquals("$name: the hook's instructions", listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT),
                    code.subList(hook, hook + 2).map { it.opcode })
                assertEquals("$name: the hook calls something else", PRE_SEARCH_PAGE, code[hook].call())
                assertEquals("$name: the hook reads another register than the pick", site.register,
                    (code[hook] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the hook keeps its answer elsewhere", site.register,
                    (code[hook + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the test after the hook", Opcode.IF_EQZ, code[hook + 2].opcode)
                assertEquals("$name: the test reads another register", site.register,
                    (code[hook + 2] as OneRegisterInstruction).registerA)
                assertTrue("$name: a jump lands on the hook",
                    code.filterIsInstance<BuilderOffsetInstruction>().none { it.target.location.index == hook })
                assertEquals("$name: the constructor outside the hook is Facebook's", stock.map { it.opcode },
                    (code.take(hook) + code.drop(hook + 2)).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        val extension = ExtensionDex.classDef("$EXTENSION_PACKAGE/search/PreSearchSections;")
        val method = extension.methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == PRE_SEARCH_PAGE
        }
        assertTrue("the extension has no static $PRE_SEARCH_PAGE", method != null && AccessFlags.STATIC.isSet(method.accessFlags))
    }
}
