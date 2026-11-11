/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * Show only comments that match, read from 586 on 2026-11-10. The obfuscated names are for
 * reviewers; the code finds each spot by a kept name or string and a shape.
 *
 * A post's comments, and an open thread's replies, are a Litho section list (`LX/0Ajv;` arm64). Its
 * createChildren is `A0s(LX/01ze;)LX/02rc;`, and its event dispatch (`A0t(LX/060E;Object)Object`)
 * makes one comment section (`LX/09W9;`) per comment. Once it has paged to the end, that dispatch
 * sends the list a state update, named [LIST_STOP_PAGING]: it asks the section context for its
 * section (`LX/01ze;->A0Q`), builds the update from a value and a number
 * (`LX/04XB;->A0X(Object, I)LX/034Q;`) and queues it on the same context (`LX/01S3;->A0O`, which
 * `01ze` routes to the section tree by the section's global key). The list's state (`LX/0Ajy;`, read
 * off `LX/05y1;->A03` and cast at the top of createChildren) applies an update by its number in
 * `A01(LX/034Q;)V`, an if chain over 0 to 3.
 *
 * So the filter builds a list's rows again without fetching anything: the list's createChildren
 * hands the extension the list and its context, and a helper added to the list queues an update with
 * a number of the patch's own, [REFRESH_UPDATE], that the state's apply now returns on at once. The
 * section runtime (`LX/03GL;->A0n(LX/05y1;LX/05y1;)Z`) only takes a new section's rows when the old
 * one isn't the same object and either fails `A16`, which calls the section's `A15(LX/05y1;)Z`, or
 * has other state. An update that changes nothing leaves the state alone, so both the list's and
 * each comment section's `A15` ask the extension first: the list with the two lists, the comment
 * section through a second helper added to it that reads both sides' comments. A list built, or a
 * comment drawn, under older search words answers "changed", so the list's rows, the comment's
 * draw and hidesComment with it run again.
 *
 * A filtered list is often short enough to show its end at once, and a list set to page on by
 * itself (its prop `0Ajv.A0W`, the flag an open reply thread writes) then fetches the next page:
 * createChildren hands that prop to the GraphQLPaginationSection it builds (`LX/09gg;->A0S`, three
 * writes, each right after a `hasNextPage` read). So the build's read of the prop asks the extension
 * whether the filter holds paging, and reads false while it does.
 */

/** The state update the comment list sends itself once it has paged to the end, named where it's sent. */
internal const val LIST_STOP_PAGING = "updateState:CommentListSection.updateShouldStopAutoPaginateNextPage"

/** The name the filter's own update goes by, for Facebook's logs. */
internal const val LIST_REFRESH_NAME = "updateState:CommentListSection.hushfacebookRefresh"

/** The number of the filter's update. The list's state has no update with it, and returns on it at once. */
internal const val REFRESH_UPDATE = 1001

/** The helper added to the comment list, kept in step with CommentSearch.REFRESH. */
internal const val REFRESH_LIST = "hushfacebookRefreshList"

/** The helper added to the comment section, asking the extension about both sides' comments. */
internal const val REDRAWS_HELPER = "hushfacebookRedraws"

private const val COMMENT_SEARCH = "$EXTENSION_PACKAGE/comments/CommentSearch;"
internal const val LIST_DRAWN = "$COMMENT_SEARCH->listDrawn(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val REDRAWS = "$COMMENT_SEARCH->redraws(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val PAGING_HELD = "$COMMENT_SEARCH->pagingHeld()Z"

/** The kept name of the page info's flag for more pages, which each paging write reads first. */
private const val HAS_NEXT_PAGE = "hasNextPage"

private const val PATCH = "Comment sheet options (comment search filter)"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/**
 * Every spot the filter hooks: the comment list's createChildren, [build], and its section context
 * type, [context]; the context's section getter, [scope]; the update factory, [factory], and the
 * context's queue, [queue]; the list state's apply method, [apply], and the int field of the update
 * it reads the number from, [number]; the list's equality method, [listEqual]; and the comment
 * section's, [equal], with the draw the patch already found, [drawn]; and the list's prop for
 * paging on by itself, [autoPaging], which the build reads once.
 */
internal class CommentFilter(
    val build: Method,
    val context: String,
    val scope: MethodReference,
    val factory: MethodReference,
    val queue: MethodReference,
    val apply: Method,
    val number: FieldReference,
    val listEqual: Method,
    val equal: Method,
    val drawn: CommentDraw,
    val autoPaging: FieldReference,
)

private fun Instruction.methodCall() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun FieldReference.smali() = "$definingClass->$name:$type"

private fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun MethodReference.parameters() = parameterTypes.map(CharSequence::toString)

/** Whether no instruction of [method] jumps back to its first one, so a hook there runs once, on entry. */
private fun entersOnce(method: Method): Boolean {
    val flow = ControlFlow.of(method)
    return flow.normal.indices.none { 0 in flow.normal[it] || 0 in flow.exceptional[it] }
}

/** Whether only the instruction before [index] of [method] goes on to it, so a hook placed there runs every time. */
private fun reachedInLine(method: Method, index: Int): Boolean {
    val flow = ControlFlow.of(method)
    return flow.normal.indices.none { (index in flow.normal[it] && it != index - 1) || index in flow.exceptional[it] }
}

/** The last instruction before [index] in [code] that writes [register], or null. */
private fun lastWrite(code: List<Instruction>, index: Int, register: Int) = (index - 1 downTo 0).firstOrNull {
    val write = code[it] as? OneRegisterInstruction ?: return@firstOrNull false
    (code[it].opcode.setsRegister() && write.registerA == register) ||
        (code[it].opcode.setsWideRegister() && write.registerA + 1 == register)
}

/**
 * The boolean props of [list] that the paging writes in [code], its build, test: a paging write is
 * an iput-boolean within five instructions after a [HAS_NEXT_PAGE] read, and the branches right
 * before that read (past a move of the page info into place) test what else it needs. Each tested
 * register is followed back to its last write: a read of a boolean field of the list, or a move
 * from one.
 */
private fun autoPagingProps(list: String, code: List<Instruction>): List<FieldReference> {
    fun prop(index: Int?): FieldReference? = code.getOrNull(index ?: return null)?.takeIf { it.opcode == Opcode.IGET_BOOLEAN }
        ?.field()?.takeIf { it.definingClass == list && it.type == "Z" }
    val props = mutableListOf<FieldReference>()
    for (read in code.indices) {
        if (code[read].opcode != Opcode.IGET_BOOLEAN || code[read].field()?.name != HAS_NEXT_PAGE) continue
        if ((read + 1..minOf(read + 5, code.lastIndex)).none { code[it].opcode == Opcode.IPUT_BOOLEAN }) continue
        var at = read - 1
        while (at >= 0 && code[at].opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)) at--
        while (at >= 0 && code[at].opcode == Opcode.IF_EQZ) {
            val tested = (code[at] as OneRegisterInstruction).registerA
            val write = lastWrite(code, at, tested)
            val moved = write?.takeIf { code[it].opcode in setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16) }
            val found = if (moved == null) prop(write)
            else prop(lastWrite(code, moved, (code[moved] as TwoRegisterInstruction).registerB))
            found?.let { props += it }
            at--
        }
    }
    return props.distinctBy { it.smali() }
}

/**
 * The filter's spots, from [lists], the classes loading [COMMENT_LIST], and [drawn], the comment
 * section's draw. [classes] reads the classes of the types it's given.
 *
 * The list is the one class whose constructor names itself [COMMENT_LIST]. In its one instance
 * method naming [LIST_STOP_PAGING], the call right after that name is the queue, (update, String)V;
 * the last static (Object, I) call before it returning the update's type is the factory; and the
 * last no-argument call before that, on the register the update is queued on and returning the
 * list's superclass, is the section getter, whose class is the section context. The build is the
 * list's one instance method taking that context and returning an object, and the prop for paging
 * on by itself the one boolean of the list its paging writes test, read there once and not jumped
 * past. The state is the one type the build casts a read
 * of a field of the list's superclass to, and its apply method its one instance (update)V method,
 * with the number read from the update's one int field. The list's and the comment section's
 * equality methods are each one's one instance (superclass)Z method, overriding one of the
 * superclass. A build where any of it moved
 * keeps the rest of the patch, with Show only comments that match left doing nothing and a warning
 * saying why.
 */
internal fun commentFilter(
    lists: List<ClassDef>,
    drawn: CommentDraw,
    classes: (Set<String>) -> Map<String, ClassDef>,
): CommentFilter? {
    fun skip(why: String): CommentFilter? {
        patchLog.warning("$PATCH: $why. Show only comments that match does nothing in this build")
        return null
    }
    val named = lists.filter { holder -> holder.methods.any { it.name == "<init>" && holdsString(it, COMMENT_LIST) } }
        .distinctBy { it.type }
    val list = named.singleOrNull() ?: return skip("expected one class naming itself \"$COMMENT_LIST\", found ${named.size}")
    val pagers = methodsHolding(list, LIST_STOP_PAGING).filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
        .distinctBy { it.descriptor() }
    val pager = pagers.singleOrNull()
        ?: return skip("expected one method of ${list.type} naming \"$LIST_STOP_PAGING\", found ${pagers.size}")
    val pagerWhere = pager.descriptor()
    val pagerCode = pager.implementation!!.instructions.toList()
    val names = pagerCode.indices.filter {
        ((pagerCode[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == LIST_STOP_PAGING
    }
    val name = names.singleOrNull() ?: return skip("$pagerWhere names \"$LIST_STOP_PAGING\" ${names.size} times")

    val queued = (pagerCode.getOrNull(name + 1)?.takeIf { it.opcode == Opcode.INVOKE_VIRTUAL } as? FiveRegisterInstruction)
        ?.takeIf { call -> call.methodCall()?.let { it.returnType == "V" && it.parameters().size == 2 && it.parameters()[1] == STRING } == true }
        ?: return skip("$pagerWhere doesn't queue its update right after naming it")
    val queue = queued.methodCall()!!
    val update = queue.parameters()[0]
    val factoryAt = (name - 1 downTo 0).firstOrNull { index ->
        val call = pagerCode[index].methodCall()
        (pagerCode[index].opcode == Opcode.INVOKE_STATIC || pagerCode[index].opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call != null && call.returnType == update && call.parameters() == listOf(OBJECT, "I")
    } ?: return skip("$pagerWhere builds no $update from an Object and a number before naming it")
    val factory = pagerCode[factoryAt].methodCall()!!
    // The section getter is asked of the same register the update is queued on, and its class is the context's.
    val scope = (factoryAt - 1 downTo 0).map { pagerCode[it] }.firstOrNull { instruction ->
        val call = instruction.methodCall()
        instruction.opcode == Opcode.INVOKE_VIRTUAL && call != null && call.parameterTypes.isEmpty() &&
            call.returnType == list.superclass && (instruction as FiveRegisterInstruction).registerC == queued.registerC
    }?.methodCall() ?: return skip("$pagerWhere doesn't ask the context it queues on for its section before the update")
    val context = scope.definingClass

    val builds = list.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null &&
            it.parameterTypes.map(CharSequence::toString) == listOf(context) && it.returnType.startsWith("L")
    }.distinctBy { it.descriptor() }
    val build = builds.singleOrNull()
        ?: return skip("expected one build of ${list.type} taking a $context, found ${builds.size}")
    val where = build.descriptor()
    val code = build.implementation!!.instructions.toList()

    val props = autoPagingProps(list.type, code)
    val autoPaging = props.singleOrNull()
        ?: return skip("$where tests ${props.size} boolean props of ${list.type} before its paging writes, not one")
    val propReads = code.indices.filter { code[it].opcode == Opcode.IGET_BOOLEAN && code[it].field()?.smali() == autoPaging.smali() }
    val propRead = propReads.singleOrNull() ?: return skip("$where reads ${autoPaging.name} ${propReads.size} times, not once")
    if (propRead + 1 > code.lastIndex || !reachedInLine(build, propRead + 1)) {
        return skip("$where has a jump to the instruction after its read of ${autoPaging.name}")
    }

    val states = code.indices.mapNotNull { index ->
        val read = code[index]
        val cast = code.getOrNull(index + 1) ?: return@mapNotNull null
        if (read.opcode != Opcode.IGET_OBJECT || read.field()?.definingClass != list.superclass) return@mapNotNull null
        if (cast.opcode != Opcode.CHECK_CAST) return@mapNotNull null
        if ((cast as OneRegisterInstruction).registerA != (read as TwoRegisterInstruction).registerA) return@mapNotNull null
        ((cast as ReferenceInstruction).reference as? TypeReference)?.type
    }.distinct()
    val stateType = states.singleOrNull() ?: return skip("$where casts its state to ${states.size} types, not one")
    val state = classes(setOf(stateType))[stateType] ?: return skip("$stateType isn't in the app")
    val applies = state.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(update) && it.implementation != null
    }
    val apply = applies.singleOrNull() ?: return skip("expected one update method on $stateType, found ${applies.size}")
    val applyCode = apply.implementation!!.instructions.toList()
    val numbers = applyCode.filter { it.opcode == Opcode.IGET && it.field()?.let { f -> f.definingClass == update && f.type == "I" } == true }
        .mapNotNull { it.field() }.distinctBy { it.smali() }
    val number = numbers.singleOrNull() ?: return skip("${apply.descriptor()} reads ${numbers.size} int fields of the update, not one")
    if (applyCode.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == REFRESH_UPDATE }) {
        return skip("${apply.descriptor()} already knows update $REFRESH_UPDATE")
    }
    if (apply.localRegisterCount() < 2) return skip("${apply.descriptor()} has fewer than two local registers for the hook")

    val section = classes(setOf(drawn.render.definingClass))[drawn.render.definingClass]
        ?: return skip("${drawn.render.definingClass} isn't in the app")
    val equalities = listOf(list, section).map { owner ->
        val base = owner.superclass ?: return skip("${owner.type} has no superclass")
        fun equality(method: Method) = !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(base)
        val equals = owner.methods.filter { equality(it) && it.implementation != null }
        val equal = equals.singleOrNull() ?: return skip("expected one ($base)Z method on ${owner.type}, found ${equals.size}")
        val overridden = classes(setOf(base))[base]?.methods?.any { equality(it) && it.name == equal.name } == true
        if (!overridden) return skip("${equal.descriptor()} overrides nothing of $base")
        if (equal.localRegisterCount() < 1) return skip("${equal.descriptor()} has no local register for the hook")
        equal
    }
    val (listEqual, equal) = equalities

    listOf(build, apply, listEqual, equal).firstOrNull { !entersOnce(it) }?.let {
        return skip("${it.descriptor()} jumps back to its start")
    }
    return CommentFilter(build, context, scope, factory, queue, apply, number, listEqual, equal, drawn, autoPaging)
}

/**
 * The helper added to the comment list: (Object)Z, true once it queued the filter's update on the
 * section context it was given. A context that isn't one, or has lost its section, gets nothing.
 */
internal fun refreshListHelper(filter: CommentFilter) = ImmutableMethod(
    filter.build.definingClass,
    REFRESH_LIST,
    listOf(ImmutableMethodParameter(OBJECT, null, null)),
    "Z",
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(5),
).toMutable().apply {
    addInstructionsWithLabels(
        0,
        """
            const/4 v0, 0x0
            instance-of v1, p0, ${filter.context}
            if-eqz v1, :no
            move-object v1, p0
            check-cast v1, ${filter.context}
            invoke-virtual { v1 }, ${filter.scope.smali()}
            move-result-object v2
            if-eqz v2, :no
            const/4 v2, 0x0
            const/16 v3, $REFRESH_UPDATE
            invoke-static { v2, v3 }, ${filter.factory.smali()}
            move-result-object v2
            const-string v3, "$LIST_REFRESH_NAME"
            invoke-virtual { v1, v2, v3 }, ${filter.queue.smali()}
            const/4 v0, 0x1
            :no
            return v0
        """,
    )
}

/**
 * The helper added to the comment section: (Object, Object)Z, the extension's answer for the
 * comments of the two sections it was given, each null when it isn't a comment section or has no
 * props yet.
 */
internal fun redrawsHelper(filter: CommentFilter): MutableMethod {
    val section = filter.equal.definingClass
    val props = filter.drawn.props.smali()
    val comment = filter.drawn.comment.smali()
    return ImmutableMethod(
        section,
        REDRAWS_HELPER,
        listOf(ImmutableMethodParameter(OBJECT, null, null), ImmutableMethodParameter(OBJECT, null, null)),
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
                instance-of v1, p0, $section
                if-eqz v1, :after
                move-object v1, p0
                check-cast v1, $section
                iget-object v1, v1, $props
                if-eqz v1, :after
                iget-object v0, v1, $comment
                :after
                const/4 v2, 0x0
                instance-of v1, p1, $section
                if-eqz v1, :ask
                move-object v1, p1
                check-cast v1, $section
                iget-object v1, v1, $props
                if-eqz v1, :ask
                iget-object v2, v1, $comment
                :ask
                invoke-static { v0, v2 }, $REDRAWS
                move-result v0
                return v0
            """,
        )
    }
}

/** First thing in the list's build: the extension gets the list and its context, as a range. */
internal fun MutableMethod.handOverList() {
    addInstructions(0, "invoke-static/range { p0 .. p1 }, $LIST_DRAWN")
}

/** First thing in the state's apply: the filter's update returns at once, and any other goes on to Facebook's code. */
internal fun MutableMethod.skipRefreshUpdate(filter: CommentFilter) {
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            iget v0, v0, ${filter.number.smali()}
            const/16 v1, $REFRESH_UPDATE
            if-ne v0, v1, :apply
            return-void
        """,
        ExternalLabel("apply", getInstruction(0)),
    )
}

/**
 * Right after the build reads the list's prop for paging on by itself: a true value asks the
 * extension, and reads false while it holds paging. The value stays a boolean for the verifier, so
 * it's set from literals rather than flipped.
 */
internal fun MutableMethod.holdPaging(filter: CommentFilter) {
    val field = filter.autoPaging.smali()
    val read = implementation!!.instructions.indexOfFirst { it.opcode == Opcode.IGET_BOOLEAN && it.field()?.smali() == field }
    val value = getInstruction<TwoRegisterInstruction>(read).registerA
    addInstructionsWithLabels(
        read + 1,
        """
            if-eqz v$value, :keep
            invoke-static {}, $PAGING_HELD
            move-result v$value
            if-eqz v$value, :pages
            const/16 v$value, 0x0
            goto :keep
            :pages
            const/16 v$value, 0x1
        """,
        ExternalLabel("keep", getInstruction(read + 1)),
    )
}

/** The comment section's helper, as the equality hook calls it. */
internal fun redrawsHelperCall(filter: CommentFilter) = "${filter.equal.definingClass}->$REDRAWS_HELPER($OBJECT$OBJECT)Z"

/**
 * First thing in an equality method: [ask] is handed the section and the one it's compared with, as
 * a range, and a yes answers "not equal". A no goes on to Facebook's code.
 */
internal fun MutableMethod.askRedraws(ask: String) {
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p1 }, $ask
            move-result v0
            if-eqz v0, :same
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("same", getInstruction(0)),
    )
}

/** Every hook and helper of the filter, each on its method as the patch found it. */
internal fun BytecodePatchContext.filterComments(filter: CommentFilter) {
    fun mutable(method: Method) = mutableClassDefBy(method.definingClass).methods.single { it.descriptor() == method.descriptor() }
    mutable(filter.build).holdPaging(filter)
    mutable(filter.build).handOverList()
    mutable(filter.apply).skipRefreshUpdate(filter)
    mutable(filter.listEqual).askRedraws(REDRAWS)
    mutable(filter.equal).askRedraws(redrawsHelperCall(filter))
    mutableClassDefBy(filter.build.definingClass).methods.add(refreshListHelper(filter))
    mutableClassDefBy(filter.equal.definingClass).methods.add(redrawsHelper(filter))
}
