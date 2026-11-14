/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.words

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide posts by words in groups (#458), on every declared Facebook build: the story component is
 * found with its props, its story read and its environment, the environment's getters lead to
 * Facebook's enum of list names, and the render method takes the check first, which reads the
 * environment and the story off `this` and jumps to Facebook's own first instruction on a no.
 */
class StoryListsFixtureTest {
    /** [seeds] and every superclass and interface of theirs the bundle carries, by type. */
    private fun hierarchy(bundle: File, seeds: Collection<ClassDef>): Map<String, ClassDef> {
        val found = linkedMapOf<String, ClassDef>()
        seeds.forEach { found[it.type] = it }
        var wanted = seeds.flatMap { listOfNotNull(it.superclass) + it.interfaces }.toSet()
        while (true) {
            val missing = wanted.filter { it !in found && it.startsWith("L") }.toSet()
            if (missing.isEmpty()) break
            val loaded = FixtureDex.classes(bundle, missing)
            if (loaded.isEmpty()) break
            found += loaded
            wanted = loaded.values.flatMap { listOfNotNull(it.superclass) + it.interfaces }.toSet()
        }
        return found
    }

    private fun field(at: Instruction) = (at as ReferenceInstruction).reference as FieldReference

    @Test
    fun `each declared build finds the story component and checks its rows first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val components = FixtureDex.classesHolding(bundle, STORY_COMPONENT_NAME)
                val story = FixtureDex.classes(bundle, setOf(GRAPHQL_STORY)).values
                val fieldTypes = components.flatMap { classDef -> classDef.fields.map { it.type } }
                    .filter { it.startsWith("L") }.toSet()
                val closure = hierarchy(bundle, FixtureDex.classes(bundle, fieldTypes).values)
                val returned = closure.values.flatMap { classDef ->
                    classDef.methods.filter { it.parameterTypes.isEmpty() }.map { it.returnType }
                }.filter { it.startsWith("L") && it !in closure }.toSet()
                val listTypes = FixtureDex.classes(bundle, returned).values
                val enums = FixtureDex.classesHolding(bundle, "CROSS_GROUP_FEED")
                val classes = (components + story + closure.values + listTypes + enums).associateBy { it.type }.values

                val context = PatchContexts.of(classes)
                val found = with(context) { storyComponent() }
                val name = bundle.name
                assertEquals("$name: the props' story is an Object", "Ljava/lang/Object;", found.story.type)
                assertEquals("$name: the props field holds the story's class", found.story.definingClass, found.props.type)
                assertEquals("$name: the list type's getter is on what the environment's getter answers",
                    found.listType.returnType, found.listName.definingClass)
                val listNames = classes.single { it.type == found.listName.returnType }
                assertEquals("$name: the list name is an enum", "Ljava/lang/Enum;", listNames.superclass)
                val init = listNames.methods.single { it.name == "<clinit>" }
                for (list in LIST_NAME_MARKERS + listOf("FEED", "PROFILE_PAGE", "GROUPS_PENDING")) {
                    assertTrue("$name: the enum names $list", holdsString(init, list))
                }

                val own = with(context) { mutableStoryRender(found) }.implementation!!.instructions.toList()
                with(context) { mutableStoryRender(found) }.judgeStoryRows(found)
                val body = with(context) { mutableStoryRender(found) }.implementation!!.instructions.toList()
                assertEquals("$name: the check, then the render method's own code", own.size + 10, body.size)
                assertEquals(
                    name,
                    listOf(
                        Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.IGET_OBJECT,
                        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT,
                    ),
                    body.take(10).map { it.opcode },
                )
                assertEquals("$name: the environment", found.environment.name, field(body[1]).name)
                assertEquals("$name: the props", found.props.name, field(body[2]).name)
                assertEquals("$name: the story", found.story.name, field(body[4]).name)
                val call = (body[5] as ReferenceInstruction).reference as MethodReference
                assertEquals(name, HIDE_IN_LIST,
                    "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}")
                for (branch in listOf(3, 7)) {
                    assertSame("$name: a no at $branch goes on to Facebook's own code", body[10],
                        (body[branch] as BuilderOffsetInstruction).target.location.instruction)
                }
                assertEquals("$name: the render method's own code follows", own.first().opcode, body[10].opcode)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
