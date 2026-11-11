/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #445: on every declared build one method names the tray's tiles by asking a public tile
 * interface for its kind, with ADD_TO_STORY_TILE and ADD_TO_STORY_SPLIT_TILE as kinds 0 and 1, and
 * one tile list builder makes its list from an ImmutableList.Builder and stores it. The patch hands
 * that list to the extension straight after it's made, and fills the stub with the kind check.
 */
class CreateStoryTilesFixtureTest {
    private fun body(method: Method): List<Instruction> = method.implementation!!.instructions.toList()

    private fun reference(instruction: Instruction): String {
        val call = (instruction as ReferenceInstruction).reference as MethodReference
        return "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
    }

    @Test
    fun `each declared build's tile list goes through the extension and the stub knows both Create story tiles`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val namers = FixtureDex.classesHolding(bundle, ADD_TO_STORY_SPLIT_TILE)
                val asked = namers.flatMap { it.methods }
                    .filter { holdsString(it, ADD_TO_STORY_TILE) && holdsString(it, ADD_TO_STORY_SPLIT_TILE) }
                    .flatMap { body(it) }
                    .filter { it.opcode == Opcode.INVOKE_INTERFACE }
                    .map { ((it as ReferenceInstruction).reference as MethodReference).definingClass }
                    .toSet()
                val tileType = asked.single()
                val classes = (namers + FixtureDex.classes(bundle, setOf(tileType)).values +
                    FixtureDex.classesHolding(bundle, SET_TILE_LIST) + ExtensionDex.classDef(CREATE_STORY_TILE))
                    .distinctBy { it.type }
                val context = PatchContexts.of(classes)

                val tiles = with(context) { trayTiles() }
                assertEquals("${bundle.name}: the tile interface", tileType, tiles.tile)
                assertEquals("${bundle.name}: ADD_TO_STORY_TILE and ADD_TO_STORY_SPLIT_TILE", setOf(0, 1), tiles.kinds)
                val (builder, index, register) = with(context) { tileListBuilt() }
                val before = body(builder)

                with(context) { hideCreateStoryTile() }
                val parameters = builder.parameterTypes.map { it.toString() }
                val hooked = body(with(context) { mutableClassDefBy(builder.definingClass) }.methods.single {
                    it.name == builder.name && it.parameterTypes.map { p -> p.toString() } == parameters
                })
                assertEquals("${bundle.name}: three instructions in the builder", before.size + 3, hooked.size)
                assertEquals("${bundle.name}: Facebook's own code up to the list", before.subList(0, index + 1).map { it.opcode },
                    hooked.subList(0, index + 1).map { it.opcode })
                assertEquals(KEPT_TILES, reference(hooked[index + 1]))
                val handed = hooked[index + 1] as RegisterRangeInstruction
                assertEquals("${bundle.name}: the list handed over", register to 1, handed.startRegister to handed.registerCount)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, hooked[index + 2].opcode)
                assertEquals("${bundle.name}: the answer back in the list's register", register,
                    (hooked[index + 2] as OneRegisterInstruction).registerA)
                assertEquals(Opcode.CHECK_CAST, hooked[index + 3].opcode)
                assertEquals(IMMUTABLE_LIST, ((hooked[index + 3] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("${bundle.name}: Facebook's own code after it", before.drop(index + 1).map { it.opcode },
                    hooked.drop(index + 4).map { it.opcode })

                val stub = body(with(context) { mutableClassDefBy(CREATE_STORY_TILE) }.methods.single { it.name == CREATE_STORY_STUB })
                assertEquals(Opcode.INSTANCE_OF, stub[0].opcode)
                assertEquals(tileType, ((stub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("${tiles.tile}->${tiles.kind}()I", reference(stub.first { it.opcode == Opcode.INVOKE_INTERFACE }))
                // The kinds load with const and the stub's answers with const/4, so only the kinds count here.
                val compared = stub.filter { it.opcode == Opcode.CONST }.map { (it as NarrowLiteralInstruction).narrowLiteral }
                assertEquals("${bundle.name}: both kinds compared", listOf(0, 1), compared.sorted())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
