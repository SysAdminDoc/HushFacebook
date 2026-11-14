/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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
 *
 * Follow and Subscribe got their own switches (#434). Read from 586 and 587, 2026-11-14, arm64. The
 * follow text plugin's check says yes for a creator the header shows Subscribe or Subscribed for. The
 * header's plugin picker (one static (props, case) -> Z method behind a packed-switch, 586 `LX/01xJ;->A28`,
 * 587 `LX/02MD;->A2F`) calls it and leaves with yes on `if-nez`. Right after that branch starts the
 * picker's own Follow route: it reads a mobile config flag and the story's FollowProfileActionLink and
 * can still answer yes, and the title builder then appends Follow, Follow back or Following (or, for a
 * Subscribe it was told no about, Follow). So the check's hook covers Subscribe only, and a call to the
 * extension at the start of that route answers no from the picker when the Follow switch is on, or when
 * the check's hook just held a Subscribe link. Nothing branches onto the route's first instruction on
 * either build.
 */

internal const val COLLAB_FOLLOW_PLUGIN =
    "Lcom/facebook/feedplugins/plugins/header/titletext/collabfollow/CollabFollowTitleTextPlugin;"
internal const val DUAL_HEADER_FOLLOW_PLUGIN =
    "Lcom/facebook/feedplugins/plugins/header/titletext/partnershipads/FBPADualHeaderFollowTextPlugin;"
internal const val FOLLOW_TEXT = "$POST_PROMPTS->followText(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;"
internal const val HIDE_FOLLOW_ROUTE = "$POST_PROMPTS->hideFollowRoute()Z"
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

/** A method as an invoke names it: `Lclass;->name(params)return`. */
private fun descriptor(definingClass: String, name: String, parameters: List<CharSequence>, returnType: String) =
    "$definingClass->$name(${parameters.joinToString("")})$returnType"

/**
 * Where [method] takes the Follow route after [check] said no: for each call of [check] followed by
 * `move-result vX` and `if-nez vX`, the index right after the branch paired with X.
 */
internal fun followRouteStarts(method: Method, check: Method): List<Pair<Int, Int>> {
    val body = method.implementation?.instructions?.toList() ?: return emptyList()
    val wanted = descriptor(check.definingClass, check.name, check.parameterTypes, check.returnType)
    return body.withIndex().mapNotNull { (index, instruction) ->
        val call = instruction.methodCall() ?: return@mapNotNull null
        if (descriptor(call.definingClass, call.name, call.parameterTypes, call.returnType) != wanted) return@mapNotNull null
        val result = body.getOrNull(index + 1) as? OneRegisterInstruction ?: return@mapNotNull null
        val branch = body.getOrNull(index + 2) as? OneRegisterInstruction ?: return@mapNotNull null
        if (result.opcode != Opcode.MOVE_RESULT || branch.opcode != Opcode.IF_NEZ) return@mapNotNull null
        if (result.registerA != branch.registerA || index + 3 >= body.size) return@mapNotNull null
        index + 3 to result.registerA
    }
}

/**
 * The header plugin picker: the one method outside the plugin that answers Z and takes a Follow route
 * after [check]. Null, with a warning, when there isn't exactly one. Changes nothing.
 */
internal fun BytecodePatchContext.findFollowRoute(check: Method): Pair<Method, Pair<Int, Int>>? {
    val routes = mutableListOf<Pair<Method, Pair<Int, Int>>>()
    classDefForEachReferencing(types = listOf(FOLLOW_TEXT_PLUGIN)) { classDef ->
        if (classDef.type == FOLLOW_TEXT_PLUGIN || classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        for (method in classDef.methods) {
            if (method.returnType != "Z") continue
            followRouteStarts(method, check).forEach { routes += method to it }
        }
    }
    routes.singleOrNull()?.let { return it }
    patchLog.warning(
        "$PATCH: expected one plugin picker taking a Follow route after ${check.definingClass}->${check.name}, " +
            "found ${routes.size}, so Hide the Follow link on posts leaves the Follow link in post headers.",
    )
    return null
}

/** Sends the header plugin picker's Follow route through the extension, which can answer no from the picker. */
internal fun BytecodePatchContext.hideFollowRoute(check: Method) {
    val (picker, start) = findFollowRoute(check) ?: return
    val (index, answer) = start
    if (index in branchTargets(picker.implementation!!.instructions.toList())) {
        patchLog.warning(
            "$PATCH: something branches onto the Follow route in ${picker.definingClass}->${picker.name}, " +
                "so Hide the Follow link on posts leaves the Follow link in post headers.",
        )
        return
    }
    val mutable = mutableClassDefBy(picker.definingClass).findMutableMethodOf(picker)
    mutable.addInstructionsWithLabels(
        index,
        """
            invoke-static { }, $HIDE_FOLLOW_ROUTE
            move-result v$answer
            if-eqz v$answer, :facebooks_follow_route
            const/16 v$answer, 0x0
            return v$answer
        """,
        ExternalLabel("facebooks_follow_route", mutable.getInstruction(index)),
    )
}
