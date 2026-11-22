/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The "3 new comments" pill that pops up over an open comment list, read from 583 on 2026-10-18
 * (#185). The obfuscated names are for reviewers; the code finds the spot by its shape.
 *
 * The comment list's adapter observer (583 arm64 LX/W52;->A06(II)V, armv7 LX/Vfc;->A06(II)V) hears
 * each insert. When the list was at its end it scrolls to the new comment with
 * LinearLayoutManager.scrollToPositionWithOffset; otherwise it counts the new ones and writes the
 * pill's text with the plural helper, a static `(Resources, int, int) -> String` call on the
 * "%1$d new comments" plural. It then checks that text's length and returns before the pill is
 * built when it's empty. The hook goes right after the text comes back: the extension answers an
 * empty text while the switch is on, so Facebook's own check skips the pill.
 *
 * 583 has a newer pill as well, read on 2026-10-19 after the switch left it showing (#185). Its
 * Kotlin component's builder (583 arm64 LX/BZ1;->A0G) keeps its own name,
 * "NewCommentsPillKComponent.parsePillData", as a string: a static method taking the Context, its
 * state holder and the GraphQLComment that just came in, which counts the new comments, gathers
 * their names and avatars, writes the text with the plural helper and always returns the pill's
 * data. Its one caller, a lambda, returns that data or null when the comment is missing, and a
 * null draws no pill. The hook goes right before each return in the builder: the extension answers
 * null while the switch is on, which the caller already takes. The answer comes back as an Object,
 * so it's cast back to the builder's declared return type before the return: without that cast ART
 * rejects the whole builder class, and every comment sheet that draws the pill crashes with a
 * VerifyError whether the switch is on or not (0.16.0, #203, #204).
 */

private const val LIST_LAYOUT = "Landroidx/recyclerview/widget/LinearLayoutManager;"

/** The name the newer pill's builder keeps as a string. */
internal const val PILL_DATA_NAME = "NewCommentsPillKComponent.parsePillData"

internal const val NEW_COMMENTS_PILL = "$COMMENT_SHEET_OPTIONS->newCommentsPill(Ljava/lang/String;)Ljava/lang/String;"
internal const val NEW_COMMENTS_PILL_DATA = "$COMMENT_SHEET_OPTIONS->newCommentsPillData(Ljava/lang/Object;)Ljava/lang/Object;"

private const val PATCH = "Comment sheet options (new comments pill)"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The pill's text goes through the extension. Nameless, so Comment sheet options carries it and its
 * fixture tests run it on its own. It finds the spot before changing anything and refuses unless
 * there's exactly one.
 */
internal val newCommentsPillPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        val classes = mutableListOf<ClassDef>()
        classDefForEach { classDef: ClassDef ->
            if (!classDef.type.startsWith(EXTENSION_CLASSES)) classes += classDef
        }
        hookNewCommentsPill(classes)
        hookNewCommentsPillData(classes)
    }
}

/** Where the pill's text comes back: the `move-result-object`'s index and the register it fills. */
internal data class PillText(val method: Method, val index: Int, val register: Int)

/** Where the newer pill's data goes back: each `return-object`'s index and register, in order. */
internal data class PillData(val method: Method, val returns: List<Pair<Int, Int>>)

/** The older pill's text goes through the extension, found among [classes]. */
internal fun BytecodePatchContext.hookNewCommentsPill(classes: Iterable<ClassDef>) {
    val text = newCommentsPillText(classes)
    val method = mutableClassDefBy(text.method.definingClass).findMutableMethodOf(text.method)
    method.addInstructions(
        text.index + 1,
        """
            invoke-static/range { v${text.register} .. v${text.register} }, $NEW_COMMENTS_PILL
            move-result-object v${text.register}
        """,
    )
}

/**
 * The newer pill's data goes through the extension before each return, found among [classes], and
 * is cast back to the builder's return type so the verifier sees the type the method declares.
 */
internal fun BytecodePatchContext.hookNewCommentsPillData(classes: Iterable<ClassDef>) {
    val data = newCommentsPillData(classes)
    val method = mutableClassDefBy(data.method.definingClass).findMutableMethodOf(data.method)
    // Last return first, so the earlier indexes still hold.
    for ((index, register) in data.returns.sortedByDescending { it.first }) {
        method.addInstructions(
            index,
            """
                invoke-static/range { v$register .. v$register }, $NEW_COMMENTS_PILL_DATA
                move-result-object v$register
                check-cast v$register, ${data.method.returnType}
            """,
        )
    }
}

private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)

private fun calls(code: List<Instruction>, owner: String, name: String) =
    code.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call -> call.definingClass == owner && call.name == name } == true }

/** A static `(Resources, int, int) -> String` call, the shape of Facebook's plural helper. */
private fun isPluralText(call: MethodReference) = call.returnType == "Ljava/lang/String;" &&
    call.params() == listOf("Landroid/content/res/Resources;", "I", "I")

/**
 * The one place in [classes] the pill's text is written. The method is a non-static `(II)V` insert
 * callback that calls both LinearLayoutManager.findLastCompletelyVisibleItemPosition and
 * scrollToPositionWithOffset, and it holds one static plural-text call whose result is taken by a
 * `move-result-object` and then measured with String.length() before anything else reads it.
 */
internal fun newCommentsPillText(classes: Iterable<ClassDef>): PillText {
    val found = mutableListOf<PillText>()
    for (classDef in classes) {
        for (method in classDef.methods) {
            if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" ||
                method.parameterTypes.map(CharSequence::toString) != listOf("I", "I")
            ) continue
            val code = method.implementation?.instructions?.toList() ?: continue
            if (!calls(code, LIST_LAYOUT, "findLastCompletelyVisibleItemPosition") ||
                !calls(code, LIST_LAYOUT, "scrollToPositionWithOffset")
            ) continue
            for ((index, instruction) in code.withIndex()) {
                val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (instruction.opcode != Opcode.INVOKE_STATIC || !isPluralText(call)) continue
                val result = code.getOrNull(index + 1) ?: continue
                if (result.opcode != Opcode.MOVE_RESULT_OBJECT) continue
                val register = (result as OneRegisterInstruction).registerA
                val measured = code.drop(index + 2).take(3).any { next ->
                    val read = (next as? ReferenceInstruction)?.reference as? MethodReference
                    next.opcode == Opcode.INVOKE_VIRTUAL && read?.definingClass == "Ljava/lang/String;" &&
                        read.name == "length" && (next as FiveRegisterInstruction).registerC == register
                }
                if (measured) found += PillText(method, index + 1, register)
            }
        }
    }
    return found.singleOrNull() ?: refuse("expected one comment list writing the new comments text, found ${found.size}")
}

/**
 * The one place in [classes] the newer pill's data is built: the static method holding its kept
 * name, taking a GraphQLComment and returning an object, with the index and register of each of
 * its `return-object`s.
 */
internal fun newCommentsPillData(classes: Iterable<ClassDef>): PillData {
    val found = classes.flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
                method.parameterTypes.any { it.toString() == GRAPHQL_COMMENT } && holdsString(method, PILL_DATA_NAME)
        }
    }
    val method = found.singleOrNull()
        ?: refuse("expected one pill builder holding \"$PILL_DATA_NAME\" that takes a GraphQLComment, found ${found.size}")
    val code = method.implementation!!.instructions.toList()
    val returns = code.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) refuse("${method.definingClass}->${method.name} returns nothing")
    return PillData(method, returns)
}
