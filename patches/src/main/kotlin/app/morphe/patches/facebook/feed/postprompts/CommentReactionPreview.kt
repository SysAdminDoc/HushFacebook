/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.ControlFlow
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.BitSet

/*
 * The reaction faces in the corner of a comment bubble (#158). Read from 583, 2026-10-18, arm64 and
 * armv7; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * Comments draw them with the same FDSInlineReactions component a post's button row uses, so one
 * Drawable (arm64 LX/3Ed;) paints both and the post switch alone would hide both. The comment
 * component (arm64 LX/SGh;) is the class whose static initializer loads "FDSComment" and whose
 * render loads "inline_reaction_transition_key". It builds its own FDSInlineReactions. The Drawable
 * is built later, by a lambda (arm64 LX/40q;) that holds that component, so the surface is carried
 * along:
 * - A boolean field is added to the component and to the Drawable.
 * - Right after the comment component's constructor call for the FDSInlineReactions, an added static
 *   method on the component sets its flag.
 * - Right before the lambda reads a field of the component, an added static method on the component
 *   leaves its flag with the extension for this thread, and right after the lambda gets the Drawable
 *   from the factory, an added static method on the Drawable takes it back and sets the Drawable's
 *   (nothing for a null Drawable). 585's lambda reuses the component's register and its own for the
 *   factory's arguments, so no register holds the component once the Drawable is back: the copy
 *   straight from the component's register that 584 took failed the verifier there.
 * - The Drawable's draw asks the extension with its flag, so each surface answers to its own switch.
 * The other caller of the factory (arm64 LX/cP4;->A0Q) reads no FDSInlineReactions, so it never gets
 * the flag and stays a post's.
 *
 * Facebook's newer flat comment row ("Reply, faces and count, thumbs up, thumbs down") isn't an
 * FDSComment. Its reactions count button (arm64 LX/4w0;, armv7 LX/4wP;) builds its own
 * FDSInlineReactions and is the one builder of it that a method taking a GraphQLComment returns
 * (arm64 LX/XlN;->A00, armv7 LX/XRt;->A00), so its constructions are marked the same way.
 */

internal const val COMMENT_COMPONENT_NAME = "FDSComment"
internal const val COMMENT_REACTION_KEY = "inline_reaction_transition_key"
internal const val COMMENT_FLAG = "hushCommentFaces"
internal const val COMMENT_MARK = "hushMarkCommentFaces"
internal const val COMMENT_STASH = "hushStashCommentFaces"
internal const val COMMENT_COPY = "hushCopyCommentFaces"
internal const val STASH_COMMENT_FACES = "$POST_PROMPTS->stashCommentFaces(Z)V"
internal const val TAKE_COMMENT_FACES = "$POST_PROMPTS->takeCommentFaces()Z"
internal const val HIDE_FACEPILE = "$POST_PROMPTS->hideReactionPreview(Landroid/graphics/drawable/Drawable;Z)Z"
internal const val GRAPHQL_COMMENT = "Lcom/facebook/graphql/model/GraphQLComment;"

/** Whether [method] takes a GraphQLComment, which is how the comment row's builder is told apart. */
internal fun takesComment(method: Method): Boolean =
    method.parameterTypes.any { it.toString() == GRAPHQL_COMMENT }

/**
 * The smali that asks the extension about the facepile Drawable, handing it the Drawable itself (for
 * Show reactions on the Like button, #541) and its own surface, answering in v0.
 */
internal fun facepileAsk(facepile: String): String =
    "iget-boolean v0, p0, $facepile->$COMMENT_FLAG:Z\n" +
        "invoke-static { p0, v0 }, $HIDE_FACEPILE"

internal fun isCommentComponent(classDef: ClassDef): Boolean =
    classDef.methods.any { holdsString(it, COMMENT_COMPONENT_NAME) } &&
        classDef.methods.any { holdsString(it, COMMENT_REACTION_KEY) }

/** The comment component: the one class holding both of its kept strings. */
internal fun BytecodePatchContext.findCommentComponent(): ClassDef {
    val found = classDefByStrings(COMMENT_REACTION_KEY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter(::isCommentComponent)
    return found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one class holding \"$COMMENT_COMPONENT_NAME\" and \"$COMMENT_REACTION_KEY\", found ${found.size}",
    )
}

private fun MethodReference.sameCall(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun constructedRegister(instruction: Instruction): Int? = when (instruction) {
    is RegisterRangeInstruction -> instruction.startRegister
    is FiveRegisterInstruction -> instruction.registerC
    else -> null
}

/**
 * Where [method] constructs [component]: the index just after each constructor call and the register
 * holding the new object.
 */
internal fun componentConstructions(method: Method, component: String): List<Pair<Int, Int>> {
    val found = mutableListOf<Pair<Int, Int>>()
    method.implementation?.instructions?.forEachIndexed { index, instruction ->
        if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) return@forEachIndexed
        val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@forEachIndexed
        if (reference.name != "<init>" || reference.definingClass != component) return@forEachIndexed
        constructedRegister(instruction)?.let { found += index + 1 to it }
    }
    return found
}

/**
 * A call of the factory: the last read of a component field before it and the register holding the
 * component there, then the index after the call's move-result-object and the Drawable's register.
 */
internal data class FaceCaller(val method: Method, val read: Int, val component: Int, val afterIndex: Int, val drawable: Int)

private val instanceReads = setOf(
    Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR,
    Opcode.IGET_SHORT,
)

/** Whether execution gets to [index] only by falling through from the instruction before it. */
private fun onlyFallsInto(flow: ControlFlow, index: Int): Boolean = flow.normal.indices.none { from ->
    (from != index - 1 && index in flow.normal[from]) || index in flow.exceptional[from]
}

/** Whether some way from the method's start gets to [target] without passing [gate]. */
private fun reachesAround(flow: ControlFlow, target: Int, gate: Int): Boolean {
    val seen = BitSet()
    val pending = ArrayDeque<Int>()
    fun enqueue(at: Int) {
        if (at != gate && !seen[at]) {
            seen.set(at)
            pending += at
        }
    }
    enqueue(0)
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at == target) return true
        flow.normal[at].forEach(::enqueue)
        flow.exceptional[at].forEach(::enqueue)
    }
    return false
}

/**
 * [method]'s call of [factory] if its result is taken with move-result-object and every way to it
 * reads a field of [component] first, where the read's object register holds the component. Null
 * for anything else. The flag is left before that read and taken after the move-result-object, so
 * neither may be a branch target: a way in there would skip the added call.
 */
internal fun faceCaller(method: Method, factory: MethodReference, component: String): FaceCaller? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val call = code.indexOfFirst {
        (it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            ((it as ReferenceInstruction).reference as? MethodReference)?.sameCall(factory) == true
    }
    if (call < 0 || call + 2 >= code.size || code[call + 1].opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val read = code.subList(0, call).indexOfLast {
        it.opcode in instanceReads && ((it as ReferenceInstruction).reference as? FieldReference)?.definingClass == component
    }
    if (read < 0) return null
    val flow = ControlFlow.of(method)
    if (!onlyFallsInto(flow, read) || !onlyFallsInto(flow, call + 2) || reachesAround(flow, call, read)) return null
    return FaceCaller(
        method, read, (code[read] as TwoRegisterInstruction).registerB,
        call + 2, (code[call + 1] as OneRegisterInstruction).registerA,
    )
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Adds the two flags and their two helpers, and calls them where the comment component and the comment
 * row's button build their FDSInlineReactions and where the lambda turns it into the Drawable. [component] is
 * FDSInlineReactions, [facepile] the Drawable its factory builds, [factory] that factory.
 */
internal fun BytecodePatchContext.tagCommentFaces(component: ClassDef, facepile: ClassDef, factory: MethodReference) {
    val comment = findCommentComponent()
    val constructions = comment.methods.mapNotNull { method ->
        componentConstructions(method, component.type).takeIf { it.isNotEmpty() }?.let { method to it }
    }
    if (constructions.isEmpty()) refuse("${comment.type} doesn't construct ${component.type}")

    val callers = mutableListOf<FaceCaller>()
    val builders = mutableMapOf<String, List<Pair<Method, List<Pair<Int, Int>>>>>()
    val commentReturns = mutableSetOf<String>()
    // A face caller reads a field of the component and a builder constructs it. A method taking a
    // comment names its type.
    classDefForEachReferencing(types = listOf(component.type, GRAPHQL_COMMENT)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE) || classDef.type == component.type) return@classDefForEachReferencing
        for (method in classDef.methods) {
            faceCaller(method, factory, component.type)?.let { callers += it }
            if (takesComment(method)) commentReturns += method.returnType
        }
        if (classDef.type == comment.type) return@classDefForEachReferencing
        val sites = classDef.methods.mapNotNull { method ->
            componentConstructions(method, component.type).takeIf { it.isNotEmpty() }?.let { method to it }
        }
        if (sites.isNotEmpty()) builders[classDef.type] = sites
    }
    val caller = callers.singleOrNull() ?: refuse(
        "expected one method that takes ${factory.name}'s Drawable beside a ${component.type}, found ${callers.size}",
    )
    val rows = builders.keys.filter { it in commentReturns }
    val row = rows.singleOrNull() ?: refuse(
        "expected one ${component.type} builder that a method taking a GraphQLComment returns, found $rows",
    )

    val componentClass = mutableClassDefBy(component.type)
    val facepileClass = mutableClassDefBy(facepile.type)
    for (target in listOf(componentClass, facepileClass)) {
        if (target.fields.any { it.name == COMMENT_FLAG }) refuse("${target.type} already has $COMMENT_FLAG")
        target.instanceFields.add(
            ImmutableField(target.type, COMMENT_FLAG, "Z", AccessFlags.PUBLIC.value, null, null, null).toMutable(),
        )
    }

    // One local (v0), then the parameters.
    componentClass.methods.add(
        ImmutableMethod(
            component.type, COMMENT_MARK, listOf(ImmutableMethodParameter(component.type, null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(2),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    iput-boolean v0, p0, ${component.type}->$COMMENT_FLAG:Z
                    return-void
                """,
            )
        },
    )
    componentClass.methods.add(
        ImmutableMethod(
            component.type, COMMENT_STASH, listOf(ImmutableMethodParameter(component.type, null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(2),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    iget-boolean v0, p0, ${component.type}->$COMMENT_FLAG:Z
                    invoke-static { v0 }, $STASH_COMMENT_FACES
                    return-void
                """,
            )
        },
    )
    // Takes the flag back even for a null Drawable, so it never waits on the thread for the next one.
    facepileClass.methods.add(
        ImmutableMethod(
            facepile.type, COMMENT_COPY, listOf(ImmutableMethodParameter(facepile.type, null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(2),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    invoke-static { }, $TAKE_COMMENT_FACES
                    move-result v0
                    if-eqz p0, :facebooks_none
                    iput-boolean v0, p0, ${facepile.type}->$COMMENT_FLAG:Z
                    :facebooks_none
                    return-void
                """,
            )
        },
    )

    for ((type, marked) in listOf(comment.type to constructions, row to builders.getValue(row))) {
        val markedClass = mutableClassDefBy(type)
        for ((method, sites) in marked) {
            val mutable = markedClass.methods.first { it.name == method.name && it.parameterTypes == method.parameterTypes }
            // Later sites first, so the earlier ones stay where they were found.
            for ((index, register) in sites.sortedByDescending { it.first }) {
                mutable.addInstructions(
                    index,
                    "invoke-static/range { v$register .. v$register }, ${component.type}->$COMMENT_MARK(${component.type})V",
                )
            }
        }
    }
    // The take after the factory first, so the read before it stays where it was found.
    mutableClassDefBy(caller.method.definingClass).methods
        .first { it.name == caller.method.name && it.parameterTypes == caller.method.parameterTypes }
        .apply {
            addInstructions(
                caller.afterIndex,
                "invoke-static/range { v${caller.drawable} .. v${caller.drawable} }, " +
                    "${facepile.type}->$COMMENT_COPY(${facepile.type})V",
            )
            addInstructions(
                caller.read,
                "invoke-static/range { v${caller.component} .. v${caller.component} }, " +
                    "${component.type}->$COMMENT_STASH(${component.type})V",
            )
        }
}

/*
 * The count beside a comment's faces (#483). Read from 588 arm64, 2026-11-24. FDSInlineReactions
 * (arm64 LX/03nV;) draws its own count: its render takes the count text from a String field (Facebook's
 * override) or formats its int count, calls length() on it and, when that is above zero, builds the
 * text component beside the faces. Facebook's own setting for reaction counts doesn't reach it. The
 * length goes through an added static helper on the component, handed the component, so a comment's
 * (flagged by tagCommentFaces) can answer 0 and take Facebook's no-count branch. TalkBack's
 * description is built elsewhere (arm64 A0D) and keeps the number.
 */

internal const val COMMENT_COUNT = "hushCommentCount"
internal const val COMMENT_REACTION_COUNT = "$POST_PROMPTS->commentReactionCount(IZ)I"
private const val STRING_TYPE = "Ljava/lang/String;"
private val objectMoves = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** The count text's length check in the render: the if-lez to hook, the length's register and the component's. */
internal data class CountCheck(val gate: Int, val length: Int, val self: Int)

/** The register an instance method gets `this` in. */
private fun thisRegister(method: Method): Int =
    method.implementation!!.registerCount - 1 - method.parameterTypes.size -
        method.parameterTypes.count { it.toString() == "J" || it.toString() == "D" }

/**
 * Whether [register] holds [method]'s `this` as the instruction at [index] starts, on every way in:
 * it is the parameter register itself, never written, or every write reaching there copies it from
 * that parameter register while that is still unwritten.
 */
private fun holdsThis(method: Method, index: Int, register: Int): Boolean {
    val self = thisRegister(method)
    val code = method.implementation!!.instructions.toList()
    val writers = method.writersReaching(index, register)
    return writers.isNotEmpty() && writers.all { writer ->
        if (writer == -1) return@all register == self
        val write = code[writer]
        write.opcode in objectMoves && (write as TwoRegisterInstruction).registerB == self &&
            method.writersReaching(writer, self) == listOf(-1)
    }
}

/**
 * [method]'s one `String.length()` whose result an if-lez tests right after, on text that a read of a
 * String field of [component] can supply, where the if-lez is reached only by falling through and the
 * read's object register holds `this` there. Null unless there is exactly one, or when a register is
 * past v15 (the hook's call takes them as they are).
 */
internal fun commentCountCheck(method: Method, component: String): CountCheck? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.definingClass != component) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val flow by lazy { ControlFlow.of(method) }
    val found = code.indices.mapNotNull { at ->
        val call = code[at]
        if (call.opcode != Opcode.INVOKE_VIRTUAL) return@mapNotNull null
        val ref = (call as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        if (ref.definingClass != STRING_TYPE || ref.name != "length" || ref.parameterTypes.isNotEmpty()) return@mapNotNull null
        val result = code.getOrNull(at + 1) ?: return@mapNotNull null
        val gate = code.getOrNull(at + 2) ?: return@mapNotNull null
        if (result.opcode != Opcode.MOVE_RESULT || gate.opcode != Opcode.IF_LEZ) return@mapNotNull null
        val length = (result as OneRegisterInstruction).registerA
        if ((gate as OneRegisterInstruction).registerA != length || !onlyFallsInto(flow, at + 2)) return@mapNotNull null
        val text = (call as FiveRegisterInstruction).registerC
        val read = method.writersReaching(at, text).filter { it >= 0 }.map { code[it] }.singleOrNull {
            it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as? FieldReference)?.let { field ->
                field.definingClass == component && field.type == STRING_TYPE
            } == true
        } ?: return@mapNotNull null
        val self = (read as TwoRegisterInstruction).registerB
        if (length > 15 || self > 15 || !holdsThis(method, at + 2, self)) return@mapNotNull null
        CountCheck(at + 2, length, self)
    }
    return found.singleOrNull()
}

/**
 * Hands the length of the count text in [component]'s render, with the component, to an added helper
 * that asks the extension with the component's comment flag, and keeps the answer in the length's
 * register before Facebook's if-lez. Needs the flag tagCommentFaces adds.
 */
internal fun BytecodePatchContext.hideCommentCounts(component: ClassDef) {
    val checks = component.methods.mapNotNull { method -> commentCountCheck(method, component.type)?.let { method to it } }
    val (render, check) = checks.singleOrNull() ?: refuse(
        "expected one method of ${component.type} testing its count text's length right after taking it, found ${checks.size}",
    )
    val componentClass = mutableClassDefBy(component.type)
    // instanceFields, as fields doesn't list one added in this run.
    if (componentClass.instanceFields.none { it.name == COMMENT_FLAG }) refuse("${component.type} has no $COMMENT_FLAG to ask with")
    if (componentClass.methods.any { it.name == COMMENT_COUNT }) refuse("${component.type} already has $COMMENT_COUNT")
    // One local (v0), then the length and the component.
    componentClass.methods.add(
        ImmutableMethod(
            component.type, COMMENT_COUNT,
            listOf(ImmutableMethodParameter("I", null, null), ImmutableMethodParameter(component.type, null, null)), "I",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    iget-boolean v0, p1, ${component.type}->$COMMENT_FLAG:Z
                    invoke-static { p0, v0 }, $COMMENT_REACTION_COUNT
                    move-result p0
                    return p0
                """,
            )
        },
    )
    componentClass.methods.first { it.name == render.name && it.parameterTypes == render.parameterTypes }.addInstructions(
        check.gate,
        """
            invoke-static { v${check.length}, v${check.self} }, ${component.type}->$COMMENT_COUNT(I${component.type})I
            move-result v${check.length}
        """,
    )
}
