/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.util

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodProtoReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference index (#216) over stand-in classes, then over a large dex of each declared build:
 * a walk naming a type, a member or a string reaches every class that names it, in
 * classDefForEach's order, and a class a patch has changed is always reached.
 */
class ReferenceIndexTest {
    private val target = "Lfixture/Target;"

    private fun classOf(type: String, smali: String = "return-void", fields: List<String> = emptyList()): ClassDef {
        val method = MutableMethod(
            ImmutableMethod(
                type, "run", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali) }
        val declared = fields.mapIndexed { at, fieldType ->
            ImmutableField(type, "f$at", fieldType, AccessFlags.PUBLIC.value, null, null, null)
        }
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, declared, listOf(method),
        )
    }

    private val calls = classOf("Lfixture/Calls;", "invoke-static { }, $target->go()V\nreturn-void")
    private val holds = classOf("Lfixture/Holds;", "const-string v0, \"needle\"\nreturn-void")
    private val reads = classOf("Lfixture/Reads;", "sget v0, Lfixture/Other;->wanted:I\nreturn-void")
    private val unrelated = classOf("Lfixture/Unrelated;", "const-string v0, \"hay\"\nreturn-void")
    private val arrays = classOf("Lfixture/Arrays;", "const/4 v0, 0x1\nnew-array v1, v0, [$target\nreturn-void")
    private val declares = classOf("Lfixture/Declares;", fields = listOf(target))
    private val all = listOf(calls, holds, reads, unrelated, arrays, declares)

    private fun BytecodePatchContext.reached(
        types: List<String> = emptyList(),
        names: List<String> = emptyList(),
        strings: List<String> = emptyList(),
    ): List<String> = mutableListOf<String>().also { found ->
        classDefForEachReferencing(types, names, strings) { found += it.type }
    }

    private fun BytecodePatchContext.allTypes(): List<String> = mutableListOf<String>().also { found ->
        classDefForEach { found += it.type }
    }

    @Test
    fun `a walk reaches the classes naming a type, a member or a string, in order`() {
        val context = PatchContexts.of(all)
        val order = context.allTypes()
        assertEquals(order.filter { it in setOf(calls.type, arrays.type, declares.type) }, context.reached(types = listOf(target)))
        assertEquals(listOf(holds.type), context.reached(strings = listOf("needle")))
        assertEquals(listOf(reads.type), context.reached(names = listOf("wanted")))
        assertEquals(
            order.filter { it in setOf(holds.type, reads.type) },
            context.reached(names = listOf("wanted"), strings = listOf("needle")),
        )
        assertEquals(emptyList<String>(), context.reached(strings = listOf("absent")))
        // Enough keys that the walk reads each run once instead of searching it for each.
        assertEquals(
            order.filter { it in setOf(calls.type, holds.type, arrays.type, declares.type) },
            context.reached(types = listOf(target) + (1..6).map { "Lfixture/Absent$it;" }, strings = listOf("needle")),
        )
        assertNotNull(ReferenceIndex.of(context))
        assertSame(ReferenceIndex.of(context), ReferenceIndex.of(context))
    }

    @Test
    fun `a class a patch has changed is always reached`() {
        val context = PatchContexts.of(all)
        assertEquals(emptyList<String>(), context.reached(strings = listOf("absent")))
        context.mutableClassDefBy(unrelated.type)
        assertEquals(listOf(unrelated.type), context.reached(strings = listOf("absent")))
        assertTrue(unrelated.type in context.reached(types = listOf(target)))
        // An index first built after a patch changed a class reaches it too.
        val later = PatchContexts.of(all)
        later.mutableClassDefBy(unrelated.type)
        assertEquals(listOf(unrelated.type), later.reached(strings = listOf("absent")))
    }

    @Test
    fun `once memory pressure takes the index back, a walk with room builds it again, twice at most`() {
        val hasRoom = ReferenceIndex.hasRoom
        try {
            // The test JVM's heap may hold large fixture dexes from other tests.
            ReferenceIndex.hasRoom = { true }
            val context = PatchContexts.of(all)
            assertEquals(listOf(holds.type), context.reached(strings = listOf("needle")))
            repeat(2) {
                ReferenceIndex.drop(context)
                assertEquals(listOf(holds.type), context.reached(strings = listOf("needle")))
            }
            ReferenceIndex.drop(context)
            assertEquals(context.allTypes(), context.reached(strings = listOf("needle")))
            assertNull(ReferenceIndex.of(context))
        } finally {
            ReferenceIndex.hasRoom = hasRoom
        }
    }

    @Test
    fun `once memory pressure takes the index back, a walk without room reaches every class and builds nothing`() {
        val hasRoom = ReferenceIndex.hasRoom
        try {
            val context = PatchContexts.of(all)
            ReferenceIndex.hasRoom = { false }
            assertEquals(listOf(holds.type), context.reached(strings = listOf("needle")))
            ReferenceIndex.drop(context)
            assertEquals(context.allTypes(), context.reached(strings = listOf("needle")))
            assertNull(ReferenceIndex.of(context))
            ReferenceIndex.hasRoom = { true }
            assertEquals(listOf(holds.type), context.reached(strings = listOf("needle")))
        } finally {
            ReferenceIndex.hasRoom = hasRoom
        }
    }

    @Test
    fun `a primitive type is refused`() {
        assertThrows(IllegalArgumentException::class.java) { wantedKeys(types = listOf("I")) }
    }

    /** What [classDef] names, read the long way: the index's rule without its hashes. */
    private class Named(classDef: ClassDef) {
        val types = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        val strings = mutableSetOf<String>()
        var unknown = false

        private fun type(type: CharSequence) {
            types += type.toString()
            types += type.toString().trimStart('[')
        }

        private fun add(reference: Reference) {
            when (reference) {
                is StringReference -> strings += reference.string
                is TypeReference -> type(reference.type)
                is FieldReference -> {
                    type(reference.definingClass)
                    type(reference.type)
                    names += reference.name
                }
                is MethodReference -> {
                    type(reference.definingClass)
                    type(reference.returnType)
                    reference.parameterTypes.forEach(::type)
                    names += reference.name
                }
                is MethodProtoReference -> {
                    type(reference.returnType)
                    reference.parameterTypes.forEach(::type)
                }
                else -> unknown = true
            }
        }

        init {
            classDef.superclass?.let(::type)
            classDef.interfaces.forEach(::type)
            classDef.fields.forEach { type(it.type) }
            for (method in classDef.methods) {
                type(method.returnType)
                method.parameterTypes.forEach(::type)
                for (instruction in method.implementation?.instructions ?: emptyList()) {
                    if (instruction is ReferenceInstruction) add(instruction.reference)
                    if (instruction is DualReferenceInstruction) add(instruction.reference2)
                }
            }
            types.removeAll { it.length <= 1 }
        }
    }

    @Test
    fun `over a large dex of each declared build, a walk reaches every class naming what it asks for`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                var first = true
                FixtureDex.forEach(bundle) { dex ->
                    // The bundle lists a few small dex files too, too small to sample.
                    if (!first || dex.classes.size < 3000) return@forEach
                    first = false
                    val classes = dex.classes.toList()
                    val named = classes.associateWith(::Named)
                    // About 200 classes spread over the dex give the walk a type, a member name and a
                    // string each one names.
                    val step = classes.size / 200
                    val samples = classes.filterIndexed { at, _ -> at % step == 0 }.map { named.getValue(it) }
                    val types = samples.mapNotNull { it.types.maxOrNull() }.distinct()
                    val names = samples.mapNotNull { it.names.maxOrNull() }.distinct()
                    val strings = samples.mapNotNull { it.strings.maxOrNull() }.distinct()
                    assertTrue(
                        "${bundle.name}: too few samples in ${classes.size} classes, ${types.size} types, " +
                            "${names.size} names, ${strings.size} strings",
                        types.size > 20 && names.size > 20 && strings.size > 20,
                    )
                    val context = PatchContexts.of(classes)
                    var reachedTotal = 0
                    fun check(kind: String, key: String, reached: List<String>, holds: (Named) -> Boolean) {
                        val wanted = classes.filter { named.getValue(it).unknown || holds(named.getValue(it)) }.map { it.type }
                        val missed = wanted - reached.toSet()
                        assertTrue("${bundle.name}: the walk for $kind $key missed $missed", missed.isEmpty())
                        reachedTotal += reached.size
                    }
                    types.forEach { type -> check("type", type, context.reached(types = listOf(type))) { type in it.types } }
                    names.forEach { name -> check("name", name, context.reached(names = listOf(name))) { name in it.names } }
                    strings.forEach { string ->
                        check("string", string, context.reached(strings = listOf(string))) { string in it.strings }
                    }
                    // A walk wanting many keys at once (the clipboard writers' callers want dozens).
                    types.chunked(24).forEach { some ->
                        check("types", some.joinToString(), context.reached(types = some)) { held -> some.any { it in held.types } }
                    }
                    // The point of the index: a walk reaches a small part of the classes.
                    val walks = types.size + names.size + strings.size + types.chunked(24).size
                    assertTrue(
                        "${bundle.name}: walks reached $reachedTotal classes, ${classes.size} each",
                        reachedTotal < walks * classes.size / 10,
                    )
                    assertEquals(
                        "${bundle.name}: the walk's order",
                        context.allTypes().filter { it in context.reached(types = listOf(types.first())).toSet() },
                        context.reached(types = listOf(types.first())),
                    )
                    checked += version
                }
            }
        }
        assertEquals("a declared build has no fixture with a large dex", versions, checked)
    }
}
