/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.util.ControlFlow
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import app.morphe.util.readsAfter
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** What a post's date component is made from: the session and the post's time (588 `LX/0HGL;`). */
internal const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

/** The helper the box adds to the full-screen player's details class, wrapping the date in a boxed style (#490). */
internal const val VIEWER_DATE_HELPER = "hushfacebookViewerDateBox"

internal const val FOR_VIEWER_DATE = "$CAPTION_BACKDROP->forViewerDate()$DRAWABLE"

/**
 * How Facebook lays a component out in a Litho style of its own, as an open story's title does with its
 * name ([findOpenStoryNames]): [call] takes the [component], the [style] and one value [maker] makes from
 * the component [context] (588 `LX/04eM;->A0R`, `LX/02H2;->A01`).
 */
internal class StyleWrap(val call: MethodReference, val component: String, val style: String, val context: String, val maker: MethodReference)

/**
 * Where the details of a video in the full-screen player put the post's date into a list, the date's
 * component in [place]'s register, with [scratch] and the register after it free right there and the
 * component context in v[context].
 */
internal class ViewerDate(val place: Place, val scratch: Int, val context: Int) {
    override fun toString() = "$place, scratch v$scratch, context v$context"
}

private fun isStatic(instruction: Instruction) =
    instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE

private fun isPublic(flags: Int) = AccessFlags.PUBLIC.isSet(flags)

/** Whether [type] is [of] or a class under it. */
private fun BytecodePatchContext.isA(type: String, of: String): Boolean =
    generateSequence(type) { classDefByOrNull(it)?.superclass }.any { it == of }

/**
 * Reads the call the title an open story shows wraps its name in a style with (of [openStory], the places
 * [findOpenStoryNames] gives, the one call taking its method's own component first) as a [StyleWrap]: a
 * public static call taking a component, a [styleType] style and one value made right there by a public
 * static call from a component context, and giving back a component.
 */
internal fun BytecodePatchContext.findStyleWrap(openStory: List<Place>, styleType: String): StyleWrap {
    val shown = openStory.filter { place ->
        val call = (place.method.implementation!!.instructions.elementAt(place.index) as? ReferenceInstruction)?.reference as? MethodReference
        call?.parameterTypes?.firstOrNull()?.toString() == place.method.returnType
    }.singleOrNull() ?: refuse("expected one open story title to wrap its own component in a style")
    val code = shown.method.implementation!!.instructions.toList()
    val call = (code[shown.index] as ReferenceInstruction).reference as MethodReference
    val where = "${shown.method.definingClass}->${shown.method.name}@${shown.index}"
    val types = call.parameterTypes.map { it.toString() }
    val component = shown.method.returnType
    if (!isStatic(code[shown.index]) || types.size != 3 || types[0] != component || types[1] != styleType) {
        refuse("expected $where to wrap a $component in a $styleType and one more value")
    }
    if (!isA(call.returnType, component)) refuse("$where's wrap gives back a ${call.returnType}, not a $component")
    val extra = argument(code[shown.index], 2) ?: refuse("$where hands its wrap no third value")
    val writer = shown.method.writersReaching(shown.index, extra).singleOrNull()?.takeIf { it >= 1 }
        ?: refuse("expected one value to reach $where's wrap")
    val maker = (code[writer - 1] as? ReferenceInstruction)?.reference as? MethodReference
    if (code[writer].opcode != Opcode.MOVE_RESULT_OBJECT || !isStatic(code[writer - 1]) || maker == null ||
        maker.parameterTypes.size != 1 || maker.returnType != types[2]
    ) {
        refuse("expected $where's wrap to take a ${types[2]} made from a component context")
    }
    listOf(call, maker).forEach { reference ->
        val owner = classDefByOrNull(reference.definingClass) ?: refuse("${reference.definingClass} isn't in this build")
        val method = owner.methods.singleOrNull { descriptor(it) == "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}" }
        if (!isPublic(owner.accessFlags) || method == null || !isPublic(method.accessFlags)) {
            refuse("${reference.definingClass}->${reference.name} can't be called from another class")
        }
    }
    return StyleWrap(call, component, styleType, maker.parameterTypes[0].toString(), maker)
}

/**
 * Finds where the details of a video in the full-screen player take the post's date (#490). The one
 * method calling [caption], the method building a reel caption's text ([findCaptionBackdrop]), lays out
 * the details under the video (588 `LX/09Og;->A10`). Wherever it shows the date it makes a component of
 * the session and the post's time (588 `LX/0HGL;`, the time from `getTimeValue`) and puts it straight into
 * the list of the column it ends up in (588 at 1477 and 1733). The time's two registers are free from
 * there on. The method copies its component context parameter into a register of its own as it starts
 * and then reuses the parameter's register (588 v3 at 0, p1 written at 10), so the context is the one
 * register only that copy reaches there.
 */
internal fun BytecodePatchContext.findViewerDates(caption: Method, wrap: StyleWrap): List<ViewerDate> {
    val target = descriptor(caption)
    val callers = mutableListOf<Method>()
    classDefForEachReferencing(types = setOf(caption.definingClass)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(callers) { method ->
            method.implementation?.instructions?.any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == target
                } == true
            } == true
        }
    }
    val viewer = callers.singleOrNull() ?: refuse("expected one method to lay out a reel caption's text, found ${callers.size}")
    val where = "${viewer.definingClass}->${viewer.name}"
    val code = viewer.implementation!!.instructions.toList()

    val types = viewer.parameterTypes.map { it.toString() }
    if (types.count { it == wrap.context } != 1) refuse("expected $where to take one ${wrap.context}")
    // p0 is the method's own class unless it's static; a long or a double takes two registers.
    val context = (if (AccessFlags.STATIC.isSet(viewer.accessFlags)) 0 else 1) +
        types.take(types.indexOf(wrap.context)).sumOf { if (it == "J" || it == "D") 2 else 1 }
    val parameterRegisters = (if (AccessFlags.STATIC.isSet(viewer.accessFlags)) 0 else 1) +
        types.sumOf { if (it == "J" || it == "D") 2 else 1 }
    val contextParameter = viewer.implementation!!.registerCount - parameterRegisters + context
    val moves = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
    // Copies of the context made while its parameter still holds it.
    val copies = code.indices.filter { at ->
        code[at].opcode in moves && (code[at] as TwoRegisterInstruction).registerB == contextParameter &&
            viewer.writersReaching(at, contextParameter) == listOf(-1)
    }
    fun contextAt(at: Int): Int? {
        if (viewer.writersReaching(at, contextParameter) == listOf(-1)) return contextParameter
        return copies.map { (code[it] as TwoRegisterInstruction).registerA }.distinct()
            .singleOrNull { register -> viewer.writersReaching(at, register).let { it.isNotEmpty() && it.all { writer -> writer in copies } } }
    }

    val flow = ControlFlow.of(viewer)
    val dates = code.indices.filter { at ->
        isConstructorCall(code[at]) && ((code[at] as ReferenceInstruction).reference as MethodReference).let { made ->
            made.parameterTypes.map { it.toString() } == listOf(FB_USER_SESSION, "J") && isA(made.definingClass, wrap.component)
        }
    }.map { at ->
        val date = argument(code[at], 0)!!
        val add = (code.getOrNull(at + 1) as? ReferenceInstruction)?.reference as? MethodReference
        if (add == null || add.name != "add" || add.parameterTypes.map { it.toString() } != listOf("Ljava/lang/Object;") ||
            add.returnType != "Z" || argument(code[at + 1], 1) != date
        ) {
            refuse("expected $where to put the date it makes at $at straight into a list")
        }
        if (flow.normal.withIndex().any { (from, to) -> at + 1 in to && from != at }) refuse("a branch in $where jumps to where it adds a date")
        val scratch = argument(code[at], 2)!!
        if (date > 255 || scratch + 1 > 255) refuse("$where keeps its date at $at in registers a short move can't reach")
        if (listOf(scratch, scratch + 1).any { viewer.readsAfter(at, it).isNotEmpty() }) {
            refuse("$where reads the time it made a date from at $at again")
        }
        val held = contextAt(at + 1) ?: refuse("expected one register of $where to hold its ${wrap.context} at its date at $at")
        if (held in listOf(scratch, scratch + 1)) refuse("$where keeps its ${wrap.context} in the time's registers at $at")
        ViewerDate(Place(viewer, at + 1, date), scratch, held)
    }
    if (dates.isEmpty()) refuse("$where never puts a post's date into a list")
    return dates
}

/**
 * Boxes the date under a full-screen video's caption (#490): a helper on the details' class asks
 * [CAPTION_BACKDROP] for a box and, given one, lays the date out in a background style made from it
 * ([wrap]); right before each of [dates] goes into its list, the date goes through that helper.
 */
internal fun BytecodePatchContext.addViewerDateBoxes(dates: List<ViewerDate>, wrap: StyleWrap, style: BoxStyle) {
    val host = dates.map { it.place.method.definingClass }.toSet().single()
    requireReachable(style, host)
    if (style.background.substringAfterLast(')') != wrap.style) refuse("the box's style isn't the one $host wraps a component in")
    if (classDefByOrNull(CAPTION_BACKDROP)?.methods?.none { it.name == "forViewerDate" } != false) {
        refuse("the extension has no $CAPTION_BACKDROP->forViewerDate()")
    }
    val owner = mutableClassDefBy(host)
    if (owner.methods.any { it.name == VIEWER_DATE_HELPER }) refuse("$host already has $VIEWER_DATE_HELPER")
    val helper = "$host->$VIEWER_DATE_HELPER(${wrap.component}${wrap.context})${wrap.component}"
    owner.methods.add(
        ImmutableMethod(
            host,
            VIEWER_DATE_HELPER,
            listOf(ImmutableMethodParameter(wrap.component, null, null), ImmutableMethodParameter(wrap.context, null, null)),
            wrap.component,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(5),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    invoke-static { }, $FOR_VIEWER_DATE
                    move-result-object v0
                    if-eqz v0, :facebook
                    sget-object v1, ${style.emptyStyle}
                    invoke-static { v0, v1 }, ${style.background}
                    move-result-object v1
                    invoke-static { p1 }, ${wrap.maker}
                    move-result-object v2
                    invoke-static { p0, v1, v2 }, ${wrap.call}
                    move-result-object p0
                    :facebook
                    return-object p0
                """,
            )
        },
    )
    dates.groupBy { it.place.method }.forEach { (method, at) ->
        val mutable = owner.findMutableMethodOf(method)
        // The last first, so the earlier indices still hold.
        at.sortedByDescending { it.place.index }.forEach { date ->
            mutable.addInstructions(
                date.place.index,
                """
                    move-object/from16 v${date.scratch}, v${date.place.register}
                    move-object/from16 v${date.scratch + 1}, v${date.context}
                    invoke-static/range { v${date.scratch} .. v${date.scratch + 1} }, $helper
                    move-result-object v${date.place.register}
                """,
            )
        }
    }
}
