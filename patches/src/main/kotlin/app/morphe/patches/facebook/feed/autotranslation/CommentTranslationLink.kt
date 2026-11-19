/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.autotranslation

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.util.findMutableMethodOf
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val TRANSLATE_COMMENTS_EXTENSION = "Lapp/morphe/extension/facebook/feed/TranslateComments;"
internal const val LINK_HOOK = "$TRANSLATE_COMMENTS_EXTENSION->link(Ljava/lang/Object;Ljava/lang/Class;)V"

/** The helper added to the comment body: (Object)V, hands a See translation handler and the body's class to [LINK_HOOK]. */
internal const val LINK_HELPER = "hushfacebookTranslateLink"

/**
 * The helper added to the comment body: (Object)Z, sends a See translation handler the click a tap
 * sends when its comment is neither translated nor tapped before, and says whether it did. The
 * extension calls it by this name.
 */
internal const val TAP_HELPER = "hushfacebookTapLink"

private const val LINK_OBJECT = "Ljava/lang/Object;"

private val LINK_INVOKES = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
    Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
    Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE,
)
private val LINK_CONSTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)

/**
 * The comment body's See translation link (#522), as [findCommentTranslationLink] finds it.
 *
 * [body] is the comment body class, [handler] the event handler class its render builds the link's
 * click handler as. Through [handlerScope] a handler holds the scope that names its component
 * context ([componentContext]); the event method reads the comment's [state] from that context with
 * [stateOf], then [withUpdates] (which applies queued lazy updates), and checks its two [flags]:
 * whether the comment shows its translation, and whether its link was tapped before. A tap goes to
 * the handler's [dispatch]. In [render], [builder] builds the click handler, which lands in
 * [register] at [moveResult].
 */
internal class CommentTranslationLink(
    val body: String,
    val handler: String,
    val handlerScope: FieldReference,
    val componentContext: FieldReference,
    val stateOf: MethodReference,
    val withUpdates: MethodReference,
    val state: String,
    val flags: List<FieldReference>,
    val dispatch: MethodReference,
    val render: Method,
    val builder: MethodReference,
    val moveResult: Int,
    val register: Int,
)

private fun refuseLink(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun linkMethodOf(instruction: Instruction?): MethodReference? =
    (instruction as? ReferenceInstruction)?.reference as? MethodReference

private fun linkFieldOf(instruction: Instruction): FieldReference? =
    (instruction as? ReferenceInstruction)?.reference as? FieldReference

private fun FieldReference.asSmali() = "$definingClass->$name:$type"

private fun MethodReference.asSmali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** The registers a call hands over, in order, in either register form. */
private fun argumentsOf(instruction: Instruction): List<Int>? = when (instruction) {
    is RegisterRangeInstruction -> List(instruction.registerCount) { instruction.startRegister + it }
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    else -> null
}

/** One read of a component's state: the static getter, the lazy-update step on the context, and the state's class. */
private class StateRead(val stateOf: MethodReference, val withUpdates: MethodReference, val state: String)

/**
 * The state reads in [code]: a static call taking one context and returning a container, its
 * move-result, a virtual call on that same context taking that container and returning one, its
 * move-result, and a cast of that answer to the state's class.
 */
private fun stateReads(code: List<Instruction>): List<StateRead> = code.indices.mapNotNull { index ->
    val first = code[index]
    if (first.opcode != Opcode.INVOKE_STATIC && first.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
    val stateOf = linkMethodOf(first) ?: return@mapNotNull null
    if (stateOf.parameterTypes.size != 1) return@mapNotNull null
    val context = stateOf.parameterTypes.single().toString()
    val container = stateOf.returnType
    if (!context.startsWith("L") || !container.startsWith("L")) return@mapNotNull null
    val taken = code.getOrNull(index + 1)
    if (taken?.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
    val second = code.getOrNull(index + 2) ?: return@mapNotNull null
    if (second.opcode != Opcode.INVOKE_VIRTUAL && second.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@mapNotNull null
    val withUpdates = linkMethodOf(second) ?: return@mapNotNull null
    if (withUpdates.definingClass != context || withUpdates.returnType != container ||
        withUpdates.parameterTypes.map { it.toString() } != listOf(container)
    ) {
        return@mapNotNull null
    }
    val contextRegister = argumentsOf(first)?.singleOrNull() ?: return@mapNotNull null
    if (argumentsOf(second) != listOf(contextRegister, (taken as OneRegisterInstruction).registerA)) return@mapNotNull null
    val result = code.getOrNull(index + 3)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
    val cast = code.getOrNull(index + 4)
    if (cast?.opcode != Opcode.CHECK_CAST) return@mapNotNull null
    if ((cast as OneRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA) return@mapNotNull null
    val state = ((cast as ReferenceInstruction).reference as? TypeReference)?.type ?: return@mapNotNull null
    StateRead(stateOf, withUpdates, state)
}

/**
 * The comment body's See translation link, found in [bodyType] (the class [findCommentTypeReads]
 * found): its event method, the one with two parameters, an Object answer and the [COMMENT_TOGGLE]
 * update, names the handler class first and reads the comment's state once, through a handler
 * field that holds the component context, and checks two of its boolean fields. The handler's own
 * dispatch, (Object)Object, is the one the body calls on handlers. Its render is the one method
 * that builds a handler, from a call whose last argument is a constant click id the event method
 * also loads, and whose answer lands where nothing branches to the instruction after it. Refuses
 * unless each part is there exactly once. Changes nothing.
 */
internal fun BytecodePatchContext.findCommentTranslationLink(bodyType: String): CommentTranslationLink {
    val body = classDefByOrNull(bodyType) ?: refuseLink("the comment body $bodyType isn't in the app")
    val events = body.methods.filter {
        it.returnType == LINK_OBJECT && it.parameterTypes.size == 2 &&
            it.parameterTypes[1].toString() == LINK_OBJECT && holdsString(it, COMMENT_TOGGLE)
    }
    val event = events.singleOrNull()
        ?: refuseLink("expected one event method in $bodyType recording \"$COMMENT_TOGGLE\", found ${events.size}")
    val handler = event.parameterTypes[0].toString()
    val eventCode = event.implementation?.instructions?.toList().orEmpty()

    val stateRead = stateReads(eventCode).distinctBy { it.state }.singleOrNull()
        ?: refuseLink("${event.definingClass}->${event.name} doesn't read one component state")
    val context = stateRead.stateOf.parameterTypes.single().toString()
    val objectReads = eventCode.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull(::linkFieldOf).distinctBy { it.toString() }
    val scopes = objectReads.filter { it.definingClass == handler }.flatMap { scope ->
        objectReads.filter { it.definingClass == scope.type && it.type == context }.map { scope to it }
    }
    val (scope, componentContext) = scopes.singleOrNull()
        ?: refuseLink("expected one $handler field holding the $context the event reads, found ${scopes.size}")
    val flags = eventCode.filter { it.opcode == Opcode.IGET_BOOLEAN }.mapNotNull(::linkFieldOf)
        .filter { it.definingClass == stateRead.state }.distinctBy { it.toString() }
    if (flags.size != 2) refuseLink("${event.definingClass}->${event.name} checks ${flags.size} flags of ${stateRead.state}, expected 2")

    val dispatches = body.methods.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty()
            .filter { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
            .mapNotNull(::linkMethodOf)
            .filter {
                it.definingClass == handler && it.returnType == LINK_OBJECT &&
                    it.parameterTypes.map { type -> type.toString() } == listOf(LINK_OBJECT)
            }
    }.distinctBy { it.toString() }
    val dispatch = dispatches.singleOrNull()
        ?: refuseLink("expected one $handler dispatch called from $bodyType, found ${dispatches.size}")

    val sites = body.methods.flatMap { method ->
        val code = method.implementation?.instructions?.toList().orEmpty()
        code.indices.filter { code[it].opcode in LINK_INVOKES && linkMethodOf(code[it])?.returnType == handler }
            .map { method to it }
    }
    val (render, call) = sites.singleOrNull()
        ?: refuseLink("expected one call building a $handler in $bodyType, found ${sites.size}")
    val renderCode = render.implementation!!.instructions.toList()
    val builder = linkMethodOf(renderCode[call])!!
    val landed = renderCode.getOrNull(call + 1)
    if (landed?.opcode != Opcode.MOVE_RESULT_OBJECT || call + 2 >= renderCode.size) {
        refuseLink("${render.definingClass}->${render.name} doesn't keep the $handler it builds")
    }
    if (call + 2 in branchTargets(renderCode)) {
        refuseLink("${render.definingClass}->${render.name} branches to the instruction after its $handler lands")
    }
    if (builder.parameterTypes.lastOrNull()?.toString() != "I") {
        refuseLink("${builder.asSmali()} doesn't take a click id last")
    }
    val id = argumentsOf(renderCode[call])?.lastOrNull()
        ?: refuseLink("${render.definingClass}->${render.name} hands ${builder.asSmali()} no click id")
    val writers = render.writersReaching(call, id)
    val literals = writers.map { at ->
        val writer = renderCode.getOrNull(at)
        if (writer == null || writer.opcode !in LINK_CONSTS) refuseLink("the click id given to ${builder.asSmali()} isn't a constant")
        (writer as WideLiteralInstruction).wideLiteral
    }.distinct()
    val clickId = literals.singleOrNull() ?: refuseLink("the click id given to ${builder.asSmali()} has ${literals.size} values")
    if (eventCode.none { it.opcode in LINK_CONSTS && (it as WideLiteralInstruction).wideLiteral == clickId }) {
        refuseLink("${event.definingClass}->${event.name} doesn't check the click id ${builder.asSmali()} is given")
    }

    return CommentTranslationLink(
        body = bodyType,
        handler = handler,
        handlerScope = scope,
        componentContext = componentContext,
        stateOf = stateRead.stateOf,
        withUpdates = stateRead.withUpdates,
        state = stateRead.state,
        flags = flags,
        dispatch = dispatch,
        render = render,
        builder = builder,
        moveResult = call + 1,
        register = (landed as OneRegisterInstruction).registerA,
    )
}

/**
 * The click handler goes to the body's link helper the moment the render has it, and the body gains
 * the link and tap helpers. The render is read again here, since the comment type hook moves its
 * instructions.
 */
internal fun BytecodePatchContext.hookCommentTranslationLink(link: CommentTranslationLink) {
    val body = mutableClassDefBy(link.body)
    val render = body.findMutableMethodOf(link.render)
    val code = render.implementation!!.instructions.toList()
    val builds = code.indices.filter { code[it].opcode in LINK_INVOKES && linkMethodOf(code[it])?.toString() == link.builder.toString() }
    val at = builds.singleOrNull()
        ?: refuseLink("${link.body}->${link.render.name} builds its $LINK_HELPER handler ${builds.size} times")
    val landed = code.getOrNull(at + 1)
    if (landed?.opcode != Opcode.MOVE_RESULT_OBJECT || (landed as OneRegisterInstruction).registerA != link.register) {
        refuseLink("${link.body}->${link.render.name} no longer keeps its handler in v${link.register}")
    }
    render.addInstructions(
        at + 2,
        """
            invoke-static/range { v${link.register} .. v${link.register} }, ${link.body}->$LINK_HELPER($LINK_OBJECT)V
        """,
    )
    body.methods.add(linkHelper(link))
    body.methods.add(tapHelper(link))
}

/** (Object)V on the body: the handler and the body's class go to the extension. */
internal fun linkHelper(link: CommentTranslationLink): MutableMethod = ImmutableMethod(
    link.body,
    LINK_HELPER,
    listOf(ImmutableMethodParameter(LINK_OBJECT, null, null)),
    "V",
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(2),
).toMutable().apply {
    addInstructions(
        0,
        """
            const-class v0, ${link.body}
            invoke-static { p0, v0 }, $LINK_HOOK
            return-void
        """,
    )
}

/**
 * (Object)Z on the body: for a handler of the link's class whose scope still names a component
 * context, reads the comment's state as the event method does, with lazy updates applied, so a tap
 * queued a moment ago counts. When neither flag is set, sends the handler a new event, which the
 * event method takes as a tap on See translation, and answers true. Anything else answers false.
 */
internal fun tapHelper(link: CommentTranslationLink): MutableMethod = ImmutableMethod(
    link.body,
    TAP_HELPER,
    listOf(ImmutableMethodParameter(LINK_OBJECT, null, null)),
    "Z",
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(6),
).toMutable().apply {
    addInstructionsWithLabels(
        0,
        """
            const/4 v0, 0x0
            instance-of v1, p0, ${link.handler}
            if-eqz v1, :no
            move-object v1, p0
            check-cast v1, ${link.handler}
            iget-object v2, v1, ${link.handlerScope.asSmali()}
            if-eqz v2, :no
            iget-object v2, v2, ${link.componentContext.asSmali()}
            if-eqz v2, :no
            invoke-static { v2 }, ${link.stateOf.asSmali()}
            move-result-object v3
            invoke-virtual { v2, v3 }, ${link.withUpdates.asSmali()}
            move-result-object v3
            instance-of v4, v3, ${link.state}
            if-eqz v4, :no
            check-cast v3, ${link.state}
            iget-boolean v4, v3, ${link.flags[0].asSmali()}
            if-nez v4, :no
            iget-boolean v4, v3, ${link.flags[1].asSmali()}
            if-nez v4, :no
            new-instance v4, $LINK_OBJECT
            invoke-direct { v4 }, $LINK_OBJECT-><init>()V
            invoke-virtual { v1, v4 }, ${link.dispatch.asSmali()}
            const/4 v0, 0x1
            :no
            return v0
        """,
    )
}
