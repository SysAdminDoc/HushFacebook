/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The two other "Follow" texts a post header can carry (#396). Read from 585, 2026-11-06, arm64; the
 * obfuscated names are for reviewers only.
 *
 * - A collab post ("A and B") gets " · Follow" from CollabFollowTitleTextPlugin, a kept class name.
 *   Its one static builder, (props) -> title segment (585 `LX/02uv;`), appends the dot and the word
 *   to a SpannableStringBuilder and hands that to the segment's (click, CharSequence) constructor as
 *   its last step.
 * - A partnership ad with a second identity gets the same text from FBPADualHeaderFollowTextPlugin's
 *   instance builder, built the same way, with a span that opens the follow sheet.
 *
 * The title dispatcher (585 `LX/01xX;->A0Q`) appends whatever text the segment carries. The hook hands
 * the text to the extension right before the constructor takes it, so with the switch on the segment
 * carries an empty text: no dot, no word and nothing to tap. Neither builder reads the text after it.
 */

internal const val COLLAB_FOLLOW_PLUGIN =
    "Lcom/facebook/feedplugins/plugins/header/titletext/collabfollow/CollabFollowTitleTextPlugin;"
internal const val DUAL_HEADER_FOLLOW_PLUGIN =
    "Lcom/facebook/feedplugins/plugins/header/titletext/partnershipads/FBPADualHeaderFollowTextPlugin;"
internal const val FOLLOW_TEXT = "$POST_PROMPTS->followText(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"

private fun Instruction.methodCall(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The registers an invoke hands its method, in order, for both the plain and the range form. */
private fun invokeRegisters(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    else -> emptyList()
}

/**
 * Where [method] builds its answer from a text: the index of each direct call to a constructor of
 * the type [method] returns whose last argument is a CharSequence, paired with that argument's register.
 */
internal fun segmentConstructions(method: Method): List<Pair<Int, Int>> {
    val body = method.implementation?.instructions?.toList() ?: return emptyList()
    val answer = method.returnType
    if (!answer.startsWith("L")) return emptyList()
    return body.withIndex().mapNotNull { (index, instruction) ->
        if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) return@mapNotNull null
        val call = instruction.methodCall() ?: return@mapNotNull null
        if (call.name != "<init>" || call.definingClass != answer) return@mapNotNull null
        if (call.parameterTypes.lastOrNull()?.toString() != CHAR_SEQUENCE) return@mapNotNull null
        index to invokeRegisters(instruction).last()
    }
}

/** The plugin's text builder: the one method that builds its answer from a text. Changes nothing. */
internal fun followTextBuilder(plugin: ClassDef): Method {
    val builders = plugin.methods.filter { segmentConstructions(it).size == 1 }
    return builders.singleOrNull()
        ?: throw PatchException("$PATCH: expected one text builder on ${plugin.type}, found ${builders.size}")
}

/** Sends the collab and partnership Follow texts through the extension. */
internal fun BytecodePatchContext.hidePostFollowTexts() {
    for (type in listOf(COLLAB_FOLLOW_PLUGIN, DUAL_HEADER_FOLLOW_PLUGIN)) {
        val plugin = classDefByOrNull(type) ?: throw PatchException("$PATCH: $type isn't in this build")
        val builder = followTextBuilder(plugin)
        val (index, text) = segmentConstructions(builder).single()
        mutableClassDefBy(plugin.type).findMutableMethodOf(builder).addInstructions(
            index,
            """
                invoke-static/range { v$text .. v$text }, $FOLLOW_TEXT
                move-result-object v$text
            """,
        )
    }
}
