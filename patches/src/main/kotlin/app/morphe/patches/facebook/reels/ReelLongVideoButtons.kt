/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/*
 * Like, Comment, Share and Save down a long video's right side in the Reels viewer (#525). Read
 * from 588 arm64 and seen on the S22; obfuscated names here are for reviewers, the code never
 * writes one down.
 *
 * - A long video (a 57:55 live replay from Saved, the reporter's films from the Reels library) gets
 *   a viewer config with a preset button list. Facebook's config providers (LX/0Cbt, LX/06Cn and
 *   LX/06HY ->HTL) hand a story their long-form check says yes to the config LX/06gB;->A00 makes,
 *   whose list is DOTS_3_HORIZONTAL alone. The others get a config without a list.
 * - The side column's list builder (LX/09rg;->A03) returns a config's list as it is, ahead of
 *   building its usual one (LIKE, COMMENT, SHARE, BOOKMARK and the dots), so a long video's column
 *   walks the three dots and nothing else. Its Like, Comment, Share and Save need nothing else:
 *   handed the usual list, the column draws them with their counts, as for a short reel.
 * - Right after the builder reads the config's list, the extension is handed it and answers null
 *   for a preset holding none of the four, while the switch is on. The builder's own null check
 *   then goes on to build the usual list. Everything else, the switch off included, keeps the list.
 */

internal const val PRESET_BUTTONS =
    "$EXTENSION_PACKAGE/reels/ReelDeclutter;->presetButtons(Ljava/lang/Object;)Ljava/lang/Object;"

/** The viewer config a side column's list builder takes its preset buttons from. Not obfuscated. */
internal const val VIEWER_CONFIG = "Lcom/facebook/fbshorts/viewer/ui/config/api/VDDViewerUIConfig;"
private const val BUTTON_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val PATCH = "Clean up Reels"

/**
 * Where [method] hands back the viewer config's own button list: the list's read, an if-eqz on it
 * right after, and its return right after that. Null unless that happens exactly once.
 */
internal fun presetReturn(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    return code.indices.filter { at ->
        val read = code[at]
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference
        if (read.opcode != Opcode.IGET_OBJECT || field?.definingClass != VIEWER_CONFIG || field.type != BUTTON_LIST) {
            return@filter false
        }
        val list = (read as TwoRegisterInstruction).registerA
        val check = code.getOrNull(at + 1)
        val back = code.getOrNull(at + 2)
        check?.opcode == Opcode.IF_EQZ && (check as OneRegisterInstruction).registerA == list &&
            back?.opcode == Opcode.RETURN_OBJECT && (back as OneRegisterInstruction).registerA == list
    }.singleOrNull()
}

/**
 * Has a long video's side column built from Facebook's usual button list instead of its
 * three-dots preset, so it gets Like, Comment, Share and Save. Refuses unless exactly one method
 * hands back a viewer config's list, and that list sits in a register a plain call can name.
 */
internal fun BytecodePatchContext.showLongVideoButtons() {
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEachReferencing(types = listOf(VIEWER_CONFIG)) { classDef ->
        classDef.methods.filter { it.returnType == BUTTON_LIST && presetReturn(it) != null }.forEach { found += classDef.type to it }
    }
    val (owner, builder) = found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method handing back the $VIEWER_CONFIG button list, found ${found.size}")
    val method = mutableClassDefBy(owner).methods.single {
        it.name == builder.name && it.parameterTypes == builder.parameterTypes && it.returnType == builder.returnType
    }
    val at = presetReturn(method)!!
    val list = (method.getInstruction(at) as TwoRegisterInstruction).registerA
    if (list > 15) throw PatchException("$PATCH: the preset list sits in v$list, past what a plain call can name")
    method.addInstructions(
        at + 1,
        """
            invoke-static { v$list }, $PRESET_BUTTONS
            move-result-object v$list
            check-cast v$list, $BUTTON_LIST
        """,
    )
}
