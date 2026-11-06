/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/downloadstory/Fingerprints.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.downloads.story

import app.morphe.patches.facebook.feed.namesString
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** Kept name. The "More" menu of the story viewer, where the save item is added. */
internal const val STORY_VIEWER_MORE_MENU =
    "Lcom/facebook/stories/viewer/ui/buckets/regular/topbar/menu/StoryViewerMoreButtonCallback;"

/**
 * Kept name. One story, and the object that the save action carries.
 *
 * Its `getMedia` is also a kept name, so the extension reaches the address of the story through
 * two names that Redex leaves alone and no name that it rewrites.
 */
internal const val STORY_CARD = "Lcom/facebook/stories/model/StoryCard;"

/** Kept name. The source of a player: its addresses and its DASH manifest. */
internal const val VIDEO_DATA_SOURCE = "Lcom/facebook/video/engine/api/VideoDataSource;"

/** Kept name. The app builds one for each player. It holds the video id and the source. */
internal const val VIDEO_PLAYER_PARAMS = "Lcom/facebook/video/engine/api/VideoPlayerParams;"

/** The analytics event the save action reports. */
internal const val SAVE_STORY_ATTEMPTED = "save_story_attempted"

/**
 * The action behind the save item: a `void` with one parameter that reports [SAVE_STORY_ATTEMPTED],
 * a literal of its own on 577 and 580 and a string table entry [resolve] reads on 581.
 *
 * The patch looks for it among the types the story viewer's "More" menu creates ([typesCreated]).
 * Elsewhere the event is in a string table, which returns a `String`, in the orchestrator further
 * down the chain, which takes eight parameters, and on 581 in the composer's
 * InspirationSaveButtonController, a `void` with one parameter the menu doesn't create.
 *
 * The patch does not edit this method. It reads the class of the action from it. That class is what
 * the menu builder creates, and thus what identifies the builder without a name that Redex moves.
 */
internal fun isSaveStoryAction(method: Method, resolve: (MethodReference) -> Method?): Boolean =
    method.returnType == "V" && method.parameterTypes.size == 1 && method.implementation != null &&
        namesString(method, SAVE_STORY_ATTEMPTED, resolve)

/** Kept name. The list builder the story menu's builder adds its items to. */
internal const val LIST_BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"

private val LIST_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * Where the menu builder adds the save item, for Save photo to be built the same way right after:
 * [addIndex] is the `ImmutableList$Builder.add` of the item, [init] the item's constructor at
 * [initIndex], whose range call puts the item in [itemRegister] and its arguments up to
 * [lastRegister], the first of them the save action in [actionRegister]. [titleRegister] holds the
 * first String the constructor takes, the title the story menu draws in place of the item's string
 * resource when it's set (the menu's renderer reads it first), and [listRegister] the list builder.
 */
internal class SaveItemSite(
    val addIndex: Int,
    val initIndex: Int,
    val init: MethodReference,
    val itemRegister: Int,
    val lastRegister: Int,
    val actionRegister: Int,
    val titleRegister: Int,
    val listRegister: Int,
)

/**
 * The [SaveItemSite] of [builder], the menu method that creates [action]. The item is constructed
 * by one range call, whose first argument after the item is the action the builder just created,
 * the list builder's `add` of the item follows at once (585's Android 9 build moves the list
 * builder into a low register in between) and the method returns right after, so reusing the
 * call's registers for a second item disturbs nothing that still runs.
 */
internal fun saveItemSite(builder: Method, action: ClassDef): SaveItemSite {
    val where = "${builder.definingClass}->${builder.name}"
    val instructions = builder.implementation?.instructions?.toList().orEmpty()

    val actionRegisters = instructions.filter {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as? TypeReference)?.type == action.type
    }.map { (it as OneRegisterInstruction).registerA }
    check(actionRegisters.size == 1) { "$where: expected 1 new ${action.type}, found ${actionRegisters.size}" }

    val adds = instructions.withIndex().filter { (_, it) ->
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        it.opcode == Opcode.INVOKE_VIRTUAL && call?.definingClass == LIST_BUILDER && call.name == "add"
    }
    check(adds.size == 1) { "$where: expected 1 add to the item list, found ${adds.size}" }
    val (addIndex, add) = adds.single()
    add as FiveRegisterInstruction

    // Only a copy of the list builder into the register the add reads may sit between the two.
    val listMove = instructions.getOrNull(addIndex - 1)?.takeIf {
        it.opcode in LIST_MOVES && (it as OneRegisterInstruction).registerA == add.registerC
    }
    val initIndex = if (listMove != null) addIndex - 2 else addIndex - 1
    val construction = instructions.getOrNull(initIndex)
    val init = (construction as? ReferenceInstruction)?.reference as? MethodReference
    check(construction is RegisterRangeInstruction && construction.opcode == Opcode.INVOKE_DIRECT_RANGE &&
        init != null && init.name == "<init>" && construction.startRegister == add.registerD) {
        "$where: the item list's add doesn't follow the item's range constructor"
    }
    check(add.registerC !in construction.startRegister until construction.startRegister + construction.registerCount) {
        "$where: the list builder sits in a register the item's constructor reads"
    }
    check(construction.startRegister + 1 == actionRegisters.single() &&
        init.parameterTypes.firstOrNull()?.toString() in action.interfaces) {
        "$where: ${init.definingClass}'s constructor doesn't take the save action first"
    }
    check(instructions.getOrNull(addIndex + 1)?.opcode == Opcode.RETURN_VOID) {
        "$where: the builder no longer returns right after adding the save item"
    }

    val parameters = init.parameterTypes.map { it.toString() }
    val title = parameters.indexOf("Ljava/lang/String;")
    check(title >= 0) { "$where: ${init.definingClass}'s constructor takes no String title" }
    val titleRegister = construction.startRegister + 1 +
        parameters.take(title).sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()

    // iget-object and a non-range invoke name their registers in four bits.
    val site = SaveItemSite(addIndex, initIndex, init, construction.startRegister,
        construction.startRegister + construction.registerCount - 1, actionRegisters.single(), titleRegister,
        add.registerC)
    check(maxOf(site.itemRegister, site.actionRegister, site.titleRegister, site.listRegister) < 16) {
        "$where: the save item's registers are out of four-bit range"
    }
    return site
}

/** The types [owner]'s methods create with new-instance. */
internal fun typesCreated(owner: ClassDef): Set<String> = owner.methods.flatMap { method ->
    method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapNotNull { ((it as ReferenceInstruction).reference as? TypeReference)?.type }
}.toSet()
