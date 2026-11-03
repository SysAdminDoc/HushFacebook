/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val REACT_COLOURS = "Lapp/morphe/extension/facebook/theme/ReactColours;"

/** A view's background colour, which every React view manager but ART's takes from here. */
internal const val REACT_BASE_VIEW_MANAGER = "Lcom/facebook/react/uimanager/BaseViewManager;"

/** The border colours of React's plain views. */
internal const val REACT_VIEW_MANAGER = "Lcom/facebook/react/views/view/ReactViewManager;"

/** React's image view, whose tint colours icons such as Marketplace's location pin. */
internal const val REACT_IMAGE_MANAGER = "Lcom/facebook/fresco/vito/rn/ReactVitoImageManager;"

/** React's text view manager, which hands each text's spans to a callback of its own interface. */
internal const val REACT_TEXT_MANAGER = "Lcom/facebook/react/views/text/PreparedLayoutTextViewManager;"

internal const val FOREGROUND_COLOR_SPAN = "Landroid/text/style/ForegroundColorSpan;"
private const val SPANNABLE = "Landroid/text/Spannable;"
private const val TEXT_PAINT = "Landroid/text/TextPaint;"
private const val INTEGER = "Ljava/lang/Integer;"

/**
 * React Native screens, Marketplace home among them, get their colours from the screen's
 * JavaScript as ints on view, text and image props, so none of the four routes or the FDS night
 * styles reach them. Each setter hands its colour to ReactColours first thing, and React's text
 * colour span asks it for the colour it paints with. AMOLED and Material You both call this; the
 * second call finds the hooks in place and leaves them, and ReactColours decides at runtime which
 * themes are in the build.
 */
internal fun BytecodePatchContext.hookReactColours() {
    val background = reactMethod(REACT_BASE_VIEW_MANAGER, "setBackgroundColor") { it == listOf("Landroid/view/View;", "I") }
    if (background.implementation!!.instructions.any { it.referenceText()?.startsWith(REACT_COLOURS) == true }) return

    background.passThrough(1, "invoke-static/range", "move-result", "$REACT_COLOURS->background(I)I")
    reactMethod(REACT_VIEW_MANAGER, "setBorderColor") { it.size == 3 && it[1] == "I" && it[2] == INTEGER }
        .passThrough(2, "invoke-static/range", "move-result-object", "$REACT_COLOURS->colour($INTEGER)$INTEGER")
    reactMethod(REACT_IMAGE_MANAGER, "setTintColor") { it.size == 2 && it[1] == INTEGER }
        .passThrough(1, "invoke-static/range", "move-result-object", "$REACT_COLOURS->colour($INTEGER)$INTEGER")

    val span = reactTextSpan()
    val spanClass = mutableClassDefBy(span)
    if (spanClass.methods.any { it.name == "updateDrawState" }) {
        throw PatchException("$span already paints its own colour, so React's text would keep Facebook's")
    }
    // ForegroundColorSpan.updateDrawState sets the paint's colour to the span's, and nothing else.
    ImmutableMethod(
        span,
        "updateDrawState",
        listOf(ImmutableMethodParameter(TEXT_PAINT, null, null)),
        "V",
        AccessFlags.PUBLIC.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                invoke-virtual { p0 }, $FOREGROUND_COLOR_SPAN->getForegroundColor()I
                move-result v0
                invoke-static { v0 }, $REACT_COLOURS->text(I)I
                move-result v0
                invoke-virtual { p1, v0 }, $TEXT_PAINT->setColor(I)V
                return-void
            """,
        )
        spanClass.methods.add(this)
    }
}

/** [name] on [type], the one whose parameter types [matches] allows. */
private fun BytecodePatchContext.reactMethod(type: String, name: String, matches: (List<String>) -> Boolean): MutableMethod =
    mutableClassDefByOrNull(type)?.methods
        ?.singleOrNull { it.name == name && it.implementation != null && matches(it.parameterTypes.map(CharSequence::toString)) }
        ?: throw PatchException("This build has no React $type->$name to theme")

/** Sends parameter [index] through [call] first thing, into its own register, where nothing has read it yet. */
private fun MutableMethod.passThrough(index: Int, invoke: String, moveResult: String, call: String) {
    val register = parameterRegisterNumber(index)
    addInstructions(
        0,
        """
            $invoke { v$register .. v$register }, $call
            $moveResult v$register
        """,
    )
}

/**
 * React's text colour span: the ForegroundColorSpan subclass the span builders create, found from
 * the text view manager's callback for built text (the interface it implements with one
 * `(Spannable)V` method) and the static builders that take that callback and return the Spannable.
 */
internal fun BytecodePatchContext.reactTextSpan(): String {
    val (callback, builders) = reactTextBuilders()
    val spans = builders.flatMap { builder ->
        builder.implementation?.instructions?.toList().orEmpty()
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { (it as ReferenceInstruction).reference.toString() }
            .filter { classDefByOrNull(it)?.superclass == FOREGROUND_COLOR_SPAN }
    }.toSet()
    return spans.singleOrNull()
        ?: throw PatchException("React's span builders for $callback create ${spans.size} ForegroundColorSpan types, not one: $spans")
}

/**
 * The text view manager's callback for built text (the interface it implements with one
 * `(Spannable)V` method), and React's static builders that take it and return the Spannable they
 * built: what Fabric both measures and draws.
 */
internal fun BytecodePatchContext.reactTextBuilders(): Pair<String, List<Method>> {
    val manager = classDefByOrNull(REACT_TEXT_MANAGER) ?: throw PatchException("This build has no $REACT_TEXT_MANAGER")
    val callback = manager.interfaces.singleOrNull { type ->
        classDefByOrNull(type)?.methods?.toList()?.let { methods ->
            methods.size == 1 && methods.single().let { it.parameterTypes.map(CharSequence::toString) == listOf(SPANNABLE) && it.returnType == "V" }
        } == true
    } ?: throw PatchException("$REACT_TEXT_MANAGER implements no single (Spannable)V callback")

    val builders = mutableListOf<Method>()
    // A builder takes the callback.
    classDefForEachReferencing(types = listOf(callback)) { classDef: ClassDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(builders) { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == SPANNABLE &&
                method.parameterTypes.any { it.toString() == callback }
        }
    }
    return callback to builders
}

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
