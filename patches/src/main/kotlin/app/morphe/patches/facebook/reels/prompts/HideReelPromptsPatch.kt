/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/16464b2ba606745de0ebe9cc52f18ef42e6a05d8/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidereelprompts/HideReelPromptsPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: each answer goes through a switch instead of
 * being replaced.
 */
package app.morphe.patches.facebook.reels.prompts

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.filterBooleanReturns
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PATCH = "Hide reel interest prompts"

/** The reel overlay enum's constant for the interest prompt, and one more of its names. */
internal const val INTEREST_PROMPT = "INTERESTED_OR_NOT_INTERESTED_BUMPER"
internal const val TUNE_YOUR_ALGORITHM = "TUNE_YOUR_ALGORITHM"

internal const val ENUM = "Ljava/lang/Enum;"

internal const val REEL_PROMPTS = "Lapp/morphe/extension/facebook/reels/ReelPrompts;"
internal const val KEEP = "$REEL_PROMPTS->keep(I)Z"

/**
 * The "Are you interested in this reel?" prompt goes. The things Facebook can lay over a reel (a
 * banner, a place, a poll, this prompt) are one enum (582 `LX/8KS;`, 580 `LX/7fq;`, 577 `LX/7ZW;`),
 * which names its constants in its static initializer. One predicate (582 `LX/90Q;->A0N`, 580
 * `LX/8O4;->A0N`, 577 `LX/8qp;->A0O`) reads the prompt's constant, takes the reel first and answers
 * Z: whether the reel gets the prompt, from the reel's own flag and two server settings, and on 582
 * the reel's position too. The reel overlay asks it before building the prompt
 * and before keeping a place for it, so a no leaves the reel as one without a prompt. Each of its
 * answers goes through the extension, which turns a yes into a no while the switch is on.
 */
@Suppress("unused")
val hideReelPromptsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide reel interest prompts",
    description = "Removes the \"Are you interested in this reel?\" prompt, so reels play without the " +
        "interruption. On by default. Turn it off in Hushfacebook settings > Reels and Watch.",
) {
    category("Reels")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val check = findPromptCheck()
        mutableClassDefBy(check.definingClass).findMutableMethodOf(check).filterBooleanReturns(PATCH, KEEP)
        enableStatus("reelPrompts")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Whether [classDef] is the reel overlay enum: an enum whose static initializer names both constants. */
internal fun isOverlayEnum(classDef: ClassDef): Boolean = classDef.superclass == ENUM && classDef.methods.any {
    it.name == "<clinit>" && holdsString(it, INTEREST_PROMPT) && holdsString(it, TUNE_YOUR_ALGORITHM)
}

/**
 * Whether [method] is the prompt predicate: answering Z, loading [prompt] (a field reference) and
 * handing it straight on, with a value of its first parameter's type (the reel), to a boolean check
 * taking the two. 581 took the reel alone. 582 (`LX/90Q;->A0N`) also takes the player's context and
 * the reel's position after it, for a gate on how far into the feed the prompt may show.
 */
internal fun isPromptCheck(method: Method, prompt: String): Boolean {
    if (method.returnType != "Z" || method.parameterTypes.isEmpty()) return false
    val reel = method.parameterTypes.first().toString()
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.indices.any { index ->
        val load = code[index]
        if (load.opcode != Opcode.SGET_OBJECT) return@any false
        val constant = (load as ReferenceInstruction).reference as? FieldReference ?: return@any false
        if (constant.toString() != prompt) return@any false
        val next = code.getOrNull(index + 1) ?: return@any false
        val call = (next as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        next.opcode.name.startsWith("invoke") && call.returnType == "Z" &&
            call.parameterTypes.map(CharSequence::toString) == listOf(constant.type, reel)
    }
}

/** The reel overlay enum. Hide affiliate product links reads its product card constant too. Changes nothing. */
internal fun BytecodePatchContext.findOverlayEnum(): ClassDef {
    val enums = classDefByStrings(INTEREST_PROMPT, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter(::isOverlayEnum)
    return enums.singleOrNull()
        ?: refuse("expected one enum naming $INTEREST_PROMPT and $TUNE_YOUR_ALGORITHM, found ${enums.size}")
}

/** The prompt's constant on the reel overlay enum, as a field reference. Changes nothing. */
internal fun BytecodePatchContext.findPromptConstant(): String {
    val overlay = findOverlayEnum()
    return enumConstant(overlay, INTEREST_PROMPT) ?: refuse("${overlay.type} stores no $INTEREST_PROMPT constant")
}

/** The one-argument boolean predicate that reads the prompt's constant. Changes nothing. */
internal fun BytecodePatchContext.findPromptCheck(): Method {
    val prompt = findPromptConstant()
    val checks = mutableListOf<Method>()
    // By the enum the check reads the constant from (#216).
    classDefForEachReferencing(types = listOf(prompt.substringBefore("->"))) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(checks) { isPromptCheck(it, prompt) }
    }
    return checks.singleOrNull() ?: refuse(
        "expected one (reel) -> Z method reading $prompt, found " +
            checks.joinToString { "${it.definingClass}->${it.name}" }.ifEmpty { "none" },
    )
}
