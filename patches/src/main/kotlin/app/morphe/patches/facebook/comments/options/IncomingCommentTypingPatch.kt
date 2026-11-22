/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The "someone is typing" line under a comment box, read from 583 on 2026-10-17 (#170). The
 * obfuscated names are for reviewers; the code finds the spot by a kept string and a method shape.
 *
 * Typers arrive on the feedback typing subscription. Its result handler (583 arm64 LX/CoF;->ACm,
 * armv7 LX/Cps;->ACX) reads the typer count off the payload with an `iget` of an int, and when the
 * count is above zero builds the event class named "CommentTypingDetected" with `<init>(I)V` and
 * hands it to the comment box's listener. At zero it hands over the "nobody is typing" event
 * instead. The hook goes right after that `iget`: the extension returns zero while the switch is on,
 * so the box only ever hears "nobody is typing". Sending your own typing is a different path.
 */

private const val EVENT_NAME = "CommentTypingDetected"

internal const val COMMENT_TYPING_INCOMING = "$EXTENSION_PACKAGE/comments/CommentSheetOptions;"
internal const val INCOMING_TYPERS = "$COMMENT_TYPING_INCOMING->incomingTypers(I)I"

private const val PATCH = "Comment sheet options (incoming typing)"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The incoming typer count goes through the extension. Nameless, so Comment sheet options carries
 * it and its fixture tests run it on its own. It finds the spot before changing anything and
 * refuses unless there's exactly one.
 */
internal val incomingCommentTypingPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        hookIncomingTyping()
    }
}

/** Where the typer count is read: the `iget`'s index and the register it fills. */
internal data class TyperCount(val method: Method, val index: Int, val register: Int)

internal fun BytecodePatchContext.hookIncomingTyping() {
    val classes = mutableListOf<ClassDef>()
    classDefForEach { classDef: ClassDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES)) classes += classDef
    }
    val count = incomingTyperCount(classes)
    val method = mutableClassDefBy(count.method.definingClass).findMutableMethodOf(count.method)
    method.addInstructions(
        count.index + 1,
        """
            invoke-static/range { v${count.register} .. v${count.register} }, $INCOMING_TYPERS
            move-result v${count.register}
        """,
    )
}

private fun isEventInit(method: Method) = method.name == "<init>" &&
    method.parameterTypes.map(CharSequence::toString) == listOf("I") && holdsString(method, EVENT_NAME)

/**
 * The one place the typer count is read before the "CommentTypingDetected" event is built from it,
 * in [classes]. The event class is the one whose `<init>(I)V` loads the kept name. Its constructor
 * is called from one non-static `(Object)V` handler, and the register it is handed was last written
 * by an `iget` of an int.
 */
internal fun incomingTyperCount(classes: Iterable<ClassDef>): TyperCount {
    val events = mutableSetOf<String>()
    for (classDef in classes) {
        if (classDef.methods.any(::isEventInit)) events += classDef.type
    }
    if (events.size != 1) refuse("expected one class named $EVENT_NAME, found ${events.size}")
    val event = events.single()

    val found = mutableListOf<TyperCount>()
    for (classDef in classes) {
        for (method in classDef.methods) {
            if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" ||
                method.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;")
            ) continue
            val code = method.implementation?.instructions?.toList() ?: continue
            for ((index, instruction) in code.withIndex()) {
                val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (instruction.opcode != Opcode.INVOKE_DIRECT || call.definingClass != event || call.name != "<init>" ||
                    call.parameterTypes.map(CharSequence::toString) != listOf("I")
                ) continue
                val count = (instruction as? FiveRegisterInstruction)?.takeIf { it.registerCount == 2 }?.registerD
                    ?: (instruction as? RegisterRangeInstruction)?.takeIf { it.registerCount == 2 }?.startRegister?.plus(1)
                    ?: continue
                val writer = (index - 1 downTo 0).firstOrNull { writesRegister(code[it], count) } ?: continue
                val read = code[writer]
                val field = (read as? ReferenceInstruction)?.reference as? FieldReference
                if (read.opcode == Opcode.IGET && field?.type == "I" && (read as OneRegisterInstruction).registerA == count) {
                    found += TyperCount(method, writer, count)
                }
            }
        }
    }
    return found.singleOrNull() ?: refuse("expected one handler reading the typer count, found ${found.size}")
}
