/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.tableIndices
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The extension class that takes the Create story tile out of the tray's tile list, its hook and its stub. */
internal const val CREATE_STORY_TILE = "$EXTENSION_PACKAGE/stories/CreateStoryTile;"
internal const val KEPT_TILES = "$CREATE_STORY_TILE->keptTiles(Ljava/util/List;)Ljava/lang/Object;"
internal const val CREATE_STORY_STUB = "createStoryTile"

/** The names Facebook's tile namer gives the two forms of the Create story tile. */
internal const val ADD_TO_STORY_TILE = "ADD_TO_STORY_TILE"
internal const val ADD_TO_STORY_SPLIT_TILE = "ADD_TO_STORY_SPLIT_TILE"

/** A literal only the tray's tile list builder holds, in the event it logs once it has set the list. */
internal const val SET_TILE_LIST = "ran_set_tile_list_"

private const val IMMUTABLE_LIST_BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"

/** The tile interface, its kind method and the kinds the namer calls a Create story tile. */
internal class TrayTiles(val tile: String, val kind: String, val kinds: Set<Int>)

/**
 * Hide Create story (#445). The Create story tile at the start of Home's Stories tray isn't a
 * bucket, so the bucket filter never sees it: the tray's tile list builder puts it with the story
 * tiles in one ImmutableList. The hook goes straight after the builder makes that list, before any
 * path stores it, and the extension answers the list without the tiles whose kind the namer calls
 * ADD_TO_STORY_TILE or ADD_TO_STORY_SPLIT_TILE. Anything not found leaves the tray as Facebook
 * builds it, with a warning, since the rest of Hide suggested stories doesn't depend on it.
 */
internal fun BytecodePatchContext.hideCreateStoryTile() {
    try {
        val tiles = trayTiles()
        val (builder, index, register) = tileListBuilt()
        fillTileStub(tiles)
        val parameters = builder.parameterTypes.map { it.toString() }
        mutableClassDefBy(builder.definingClass).methods.single {
            it.name == builder.name && it.returnType == builder.returnType &&
                it.parameterTypes.map { p -> p.toString() } == parameters
        }.addInstructions(
            index + 1,
            """
                invoke-static/range { v$register .. v$register }, $KEPT_TILES
                move-result-object v$register
                check-cast v$register, $IMMUTABLE_LIST
            """,
        )
    } catch (missing: PatchException) {
        patchLog.warning("${missing.message}. Hide Create story leaves the tray's Create story tile in this build")
    }
}

/**
 * The tile interface and the Create story kinds, from the one method that names a tray tile: it
 * asks the tile's interface for its kind and switches on it, and the cases that answer
 * [ADD_TO_STORY_TILE] and [ADD_TO_STORY_SPLIT_TILE] give the kinds.
 */
internal fun BytecodePatchContext.trayTiles(): TrayTiles {
    val namers = classDefByStrings(ADD_TO_STORY_SPLIT_TILE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef ->
            classDef.methods.filter {
                it.returnType == "Ljava/lang/String;" && holdsString(it, ADD_TO_STORY_TILE) && holdsString(it, ADD_TO_STORY_SPLIT_TILE)
            }
        }
    val namer = namers.singleOrNull()
        ?: throw PatchException("$PATCH: ${namers.size} methods name both forms of the Create story tile, not one")
    val asked = namer.implementation!!.instructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) return@mapNotNull null
        ((instruction as ReferenceInstruction).reference as MethodReference).takeIf { it.returnType == "I" && it.parameterTypes.isEmpty() }
    }.distinctBy { "${it.definingClass}->${it.name}" }
    val kind = asked.singleOrNull()
        ?: throw PatchException("$PATCH: the tile namer ${namer.definingClass} asks ${asked.size} int methods, not one")
    val tile = classDefByOrNull(kind.definingClass)
    if (tile == null || !AccessFlags.INTERFACE.isSet(tile.accessFlags) || !AccessFlags.PUBLIC.isSet(tile.accessFlags)) {
        throw PatchException("$PATCH: the tray tile type ${kind.definingClass} isn't a public interface the extension can ask")
    }
    val kinds = tableIndices(namer, ADD_TO_STORY_TILE) + tableIndices(namer, ADD_TO_STORY_SPLIT_TILE)
    if (kinds.isEmpty()) throw PatchException("$PATCH: the tile namer's switch names neither Create story tile")
    return TrayTiles(kind.definingClass, kind.name, kinds)
}

/**
 * The tray's tile list builder, the index of the `move-result-object` that takes the list from
 * ImmutableList.Builder, and its register. The register is the one the builder stores in its
 * ImmutableList field straight after handing it to a `(List)V` setter, so the hook lands on the
 * list that's kept. The instruction after it can't be a branch target, or a path that never built
 * the list would run the hook.
 */
internal fun BytecodePatchContext.tileListBuilt(): Triple<Method, Int, Int> {
    val builders = classDefByStrings(SET_TILE_LIST, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter { holdsString(it, SET_TILE_LIST) } }
    val builder = builders.singleOrNull()
        ?: throw PatchException("$PATCH: ${builders.size} methods hold \"$SET_TILE_LIST\", not one")
    val code = builder.implementation!!.instructions.toList()

    val stored = code.indices.drop(1).mapNotNull { i ->
        val store = code[i]
        if (store.opcode != Opcode.IPUT_OBJECT) return@mapNotNull null
        if (((store as ReferenceInstruction).reference as FieldReference).type != IMMUTABLE_LIST) return@mapNotNull null
        val set = code[i - 1]
        val call = set.methodCall(Opcode.INVOKE_VIRTUAL) ?: return@mapNotNull null
        val register = (store as TwoRegisterInstruction).registerA
        val passed = set as FiveRegisterInstruction
        register.takeIf {
            call.returnType == "V" && call.parameterTypes.map { p -> p.toString() } == listOf("Ljava/util/List;") &&
                passed.registerCount == 2 && passed.registerD == register
        }
    }.distinct()
    val register = stored.singleOrNull()
        ?: throw PatchException("$PATCH: the tile list builder stores ${stored.size} lists it hands a setter, not one")

    val built = code.indices.drop(1).filter { i ->
        val result = code[i]
        val call = code[i - 1].methodCall(Opcode.INVOKE_STATIC, Opcode.INVOKE_VIRTUAL)
        result.opcode == Opcode.MOVE_RESULT_OBJECT && (result as OneRegisterInstruction).registerA == register &&
            call != null && call.returnType == IMMUTABLE_LIST &&
            call.parameterTypes.map { it.toString() } == listOf(IMMUTABLE_LIST_BUILDER)
    }
    val index = built.singleOrNull()
        ?: throw PatchException("$PATCH: the tile list builder makes v$register from a Builder ${built.size} times, not once")
    if (index + 1 in branchTargets(code)) {
        throw PatchException("$PATCH: a branch lands straight after the tile list builder makes its list")
    }
    if (register > 255) throw PatchException("$PATCH: the tile list is in v$register, past what move-result-object can name")
    return Triple(builder, index, register)
}

private fun Instruction.methodCall(vararg opcodes: Opcode): MethodReference? =
    if (opcode in opcodes) (this as ReferenceInstruction).reference as? MethodReference else null

/**
 * The stub answers whether a tray tile is the Create story tile: an instance of the tile
 * interface whose kind is one of the Create story kinds. The kind goes in the spare int parameter's
 * register, which nothing reads, so the stub needs no more registers than it has.
 */
internal fun BytecodePatchContext.fillTileStub(tiles: TrayTiles) {
    val stub = mutableClassDefBy(CREATE_STORY_TILE).methods.singleOrNull {
        it.name == CREATE_STORY_STUB && it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;", "I")
    } ?: throw PatchException("$CREATE_STORY_TILE has no static int $CREATE_STORY_STUB(Object, int)")
    val compares = tiles.kinds.sorted().joinToString("\n") { kind ->
        """
            const p1, $kind
            if-eq p0, p1, :create
        """
    }
    stub.addInstructions(
        0,
        """
            instance-of p1, p0, ${tiles.tile}
            if-eqz p1, :other
            check-cast p0, ${tiles.tile}
            invoke-interface { p0 }, ${tiles.tile}->${tiles.kind}()I
            move-result p0
            $compares
            :other
            const/4 p0, 0x0
            return p0
            :create
            const/4 p0, 0x1
            return p0
        """,
    )
}
