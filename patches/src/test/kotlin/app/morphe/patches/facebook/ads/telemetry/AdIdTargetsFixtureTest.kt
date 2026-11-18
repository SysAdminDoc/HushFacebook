/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.telemetry

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.tableIndexAt
import app.morphe.patches.facebook.feed.tableIndices
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Block ad telemetry's advertising ID targets on each declared Facebook build. Each job has to keep
 * a void method to stop, and each sender string has to name exactly one void method, directly or
 * through a string table, which is the method the patch returns early. A missing one is only an info
 * line in the patch log, so this is where a new build that moved one shows up.
 */
class AdIdTargetsFixtureTest {
    private val DISPATCH = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.INSTANCE_OF)

    /** The void methods naming [marker], the way the patch's search finds them. */
    private fun senders(bundle: File, marker: String): List<Method> {
        val holders = FixtureDex.classesHolding(bundle, marker)
        val direct = holders.flatMap { owner -> owner.methods.filter { it.returnType == "V" && holdsString(it, marker) } }
        val tables: Map<String, ClassDef> = holders
            .filter { owner -> owner.methods.any { isStringTable(it) && holdsString(it, marker) } }
            .associateBy { it.type }
        if (tables.isEmpty()) return direct
        val indices = HashMap<MethodReference, Set<Int>>()
        fun asksTable(method: Method): Boolean {
            val code = method.implementation?.instructions?.toList() ?: return false
            return code.indices.any { at ->
                val index = tableIndexAt(code, at) ?: return@any false
                val call = (code[at] as ReferenceInstruction).reference as MethodReference
                val table = tables[call.definingClass] ?: return@any false
                index in indices.getOrPut(call) { resolveStatic(table, call)?.let { tableIndices(it, marker) }.orEmpty() }
            }
        }
        return direct + FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.definingClass in tables } }) {
            it.returnType == "V" && !holdsString(it, marker) && asksTable(it)
        }
    }

    @Test
    fun `each advertising ID job and sender is there to stop on every declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val jobs = FixtureDex.classes(bundle, AD_ID_JOBS.toSet())
                for (type in AD_ID_JOBS) {
                    val stoppable = jobs[type]?.methods?.filter { method ->
                        method.returnType == "V" && method.name != "<init>" && method.name != "<clinit>" &&
                            method.implementation != null
                    }.orEmpty()
                    assertTrue("${bundle.name}: $type has a void method to stop", stoppable.isNotEmpty())
                }
                for (marker in AD_ID_SENDERS) {
                    val senders = senders(bundle, marker)
                    assertEquals(
                        "${bundle.name}: void methods naming \"$marker\" " +
                            senders.joinToString { "${it.definingClass}->${it.name}" },
                        1,
                        senders.size,
                    )
                    // The patch returns at the top, which stops everything in the method, so it
                    // can't be sibling classes' code merged behind a dispatch (#447).
                    val code = senders.single().implementation!!.instructions.toList()
                    assertTrue("${bundle.name}: the sender of \"$marker\" dispatches", code.none { it.opcode in DISPATCH })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
