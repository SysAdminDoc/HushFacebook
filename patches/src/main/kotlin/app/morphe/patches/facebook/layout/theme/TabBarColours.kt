/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.Collections
import java.util.WeakHashMap

/*
 * The selected tab's colour in Facebook's tab bar, for Material You (#65). Read from 577, 580 and
 * 581, 2026-10-07; the obfuscated names in these comments are for reviewers, the code never writes
 * one down.
 *
 * The tab bar takes its colours from one abstract provider (581 LX/3W1) with two subclasses each.
 * 582's (LX/5wY) holds one subclass's colours itself, so they're no longer abstract, and the other
 * subclass (LX/2Ex) overrides them. Every caller still asks the provider.
 * TabBarContainerLayout, a class Redex keeps, is handed the provider with the tab id in a setter
 * (581 FGg(FbUserSession, LX/3W1, J), 577 FAb(LX/3aF, J)), and the first colour it asks the
 * provider for goes straight into the Paint of the line it draws over the selected tab. That's the
 * selected tab's colour (581 LX/3W1;->A04, the TAB_BAR_ACTIVE_ICON, PRIMARY_ICON or ACCENT token by
 * Facebook's own switches). Its only other caller is the tab view's tint method, which colours the
 * selected tab's icon with it and the others with the unselected colour (581 LX/2Bf;->A06). 580 and
 * 581 pass the session, 577 passes nothing, so the method is found by the Paint it fills, not by
 * its parameters.
 *
 * That setter doesn't paint the line the first time, though. The layout's inflater (a Redex-named
 * switch method, 581 LX/3Ra;->A00) makes the line's Paint and fills it straight from an FDSColors
 * token (A2c on 581 and 580, A3X on 577), a colour the provider never hears about. The setter then
 * finds that Paint, and sets it again, only when the theme changes, so on the phone the line kept
 * the token's near white (#65). The line's Paint is the one field of the layout's own that its
 * drawing hands to Canvas.drawPath: onDraw does it up to 582, and 583 (inflater `LX/3OT;->A00`,
 * setter `FOe`) moved it into a private method of the layout, `A01(Canvas, boolean)`, that onDraw
 * calls. The same three-instruction shape sits in all three builds: an int call
 * and its move-result, an iget-object of that field, and Paint.setColor. The provider's setter has
 * that shape too, with the colour already handed over, so it's left out.
 */

internal const val TAB_BAR_CONTAINER = "Lcom/facebook/navigation/tabbar/ui/TabBarContainerLayout;"

private const val PAINT_SET_COLOR = "Landroid/graphics/Paint;->setColor(I)V"

/** How far after the colour's move-result the setter may hand it to the Paint. 581 does it three later. */
private const val PAINT_WINDOW = 4

/**
 * Where the selected tab's colour goes: the extension's SelectedTab, which hands it to Material You
 * and to Badge and logo colors' Selected tab color (#297), whichever are in the build.
 */
internal const val TAB_BAR_SELECTED = "Lapp/morphe/extension/facebook/theme/SelectedTab;->colour(I)I"

private const val PATCH = "Selected tab colour"

/** The patch contexts the hook went into already, so the second patch that wants it adds nothing. */
private val hookedContexts: MutableSet<BytecodePatchContext> = Collections.newSetFromMap(WeakHashMap())

private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)

private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

private fun MethodReference.sameAs(other: MethodReference) =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

/**
 * The selected tab's colour method: in each of [layout]'s setters that take the colour provider,
 * an abstract class [isAbstract] answers for, next to last and the tab id (a long) last, the first
 * call to the provider answering an int whose move-result goes into `Paint.setColor` within
 * [PAINT_WINDOW] instructions. Refuses unless exactly one setter has one.
 */
internal fun selectedTabColour(layout: ClassDef, isAbstract: (String) -> Boolean): MethodReference {
    val found = layout.methods.mapNotNull { setter ->
        val parameters = setter.parameterTypes.map { it.toString() }
        if (setter.returnType != "V" || parameters.size < 2 || parameters.last() != "J") return@mapNotNull null
        val provider = parameters[parameters.size - 2]
        if (!provider.startsWith("L") || !isAbstract(provider)) return@mapNotNull null
        val code = setter.implementation?.instructions?.toList() ?: return@mapNotNull null
        code.indices.firstNotNullOfOrNull { at ->
            val call = code[at].called()?.takeIf { it.definingClass == provider && it.returnType == "I" }
                ?: return@firstNotNullOfOrNull null
            val kept = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction
                ?: return@firstNotNullOfOrNull null
            val paints = (at + 2..minOf(code.lastIndex, at + 1 + PAINT_WINDOW)).any { next ->
                code[next].called()?.toString() == PAINT_SET_COLOR && kept.registerA in code[next].registers()
            }
            call.takeIf { paints }
        }
    }.distinctBy { it.toString() }
    return found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one colour $TAB_BAR_CONTAINER asks its colour provider for and paints the selected tab's " +
            "line with, found ${found.size}",
    )
}

private const val PAINT = "Landroid/graphics/Paint;"
private const val DRAW_PATH = "Landroid/graphics/Canvas;->drawPath(Landroid/graphics/Path;Landroid/graphics/Paint;)V"

/** How far back from the Paint's field read to look for the move-result of the colour put on it. */
private const val COLOUR_LOOKBACK = 12

/** How far after the Paint's field read Paint.setColor may come (a null check sits between). */
private const val SET_COLOR_WINDOW = 3

private fun Method.sameSignature(other: Method) = name == other.name && returnType == other.returnType &&
    parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

private fun Instruction.field() = ((this as? ReferenceInstruction)?.reference as? FieldReference)

/**
 * The methods [layout] draws in: its onDraw and the layout's own methods onDraw calls. Up to 582
 * onDraw draws the line itself; 583 moved the drawing into a private method (`A01(Canvas, boolean)`)
 * that onDraw hands the canvas to.
 */
private fun drawingMethods(layout: ClassDef): List<Method> {
    val onDraw = layout.methods.filter { it.name == "onDraw" }
    val called = onDraw.flatMap { method -> method.implementation?.instructions?.mapNotNull { it.called() }.orEmpty() }
        .filter { it.definingClass == layout.type }
    return onDraw + layout.methods.filter { candidate -> candidate.name != "onDraw" && called.any { candidate.sameAs(it) } }
}

/**
 * The field [layout] draws its line with: the one Paint field of the layout's own that its onDraw,
 * or a method of the layout onDraw calls, hands to `Canvas.drawPath`. Refuses unless there is
 * exactly one.
 */
internal fun lineColourPaint(layout: ClassDef): FieldReference {
    val found = drawingMethods(layout).flatMap { drawer ->
        val code = drawer.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        if (code.none { it.called()?.toString() == DRAW_PATH }) return@flatMap emptyList()
        code.mapNotNull { it.field() }.filter { it.definingClass == layout.type && it.type == PAINT }
    }.distinctBy { it.toString() }
    return found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one Paint field $TAB_BAR_CONTAINER draws its line with (in onDraw or a method it calls), found ${found.size}",
    )
}

/**
 * The index of the move-result of each colour [method] puts on [paint] with Paint.setColor: right
 * after a read of the field, within [SET_COLOR_WINDOW] instructions, the call whose colour register
 * was last written, going back at most [COLOUR_LOOKBACK], by a move-result.
 */
internal fun lineColourReads(method: Method, paint: FieldReference): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { at ->
        if (code[at].opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        val field = code[at].field()
        if (field?.name != paint.name || field.toString() != paint.toString()) return@mapNotNull null
        val holder = (code[at] as OneRegisterInstruction).registerA
        val colour = (at + 1..minOf(code.lastIndex, at + SET_COLOR_WINDOW)).firstNotNullOfOrNull { next ->
            if (code[next].called()?.toString() != PAINT_SET_COLOR) return@firstNotNullOfOrNull null
            code[next].registers().takeIf { it.size == 2 && it[0] == holder }?.get(1)
        } ?: return@mapNotNull null
        (at - 1 downTo maxOf(0, at - COLOUR_LOOKBACK)).firstOrNull { back ->
            (code[back] as? OneRegisterInstruction)?.registerA == colour &&
                code[back].opcode == Opcode.MOVE_RESULT
        }
    }
}

/** The index of the move-result after each call to [colour] in [method] that keeps the answer. */
internal fun selectedTabColourReads(method: Method, colour: MethodReference): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        code[at].called()?.sameAs(colour) == true && code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT
    }.map { it + 1 }
}

/**
 * Every place that asks for the selected tab's colour hands the answer to the extension, which
 * gives back the palette's accent in the dark theme. Refuses unless the setter's own read and at
 * least one other, the tab icon's tint, are found, and exactly one place that first fills the line's
 * Paint, which is handed the answer too. It only finds them: the step it returns makes
 * the change, so the theme can find this before its other hooks go in and a build without it
 * fails before anything is half done. Material You and Badge and logo colors both ask for it; once
 * one has found it, the other's step does nothing, since a second hook would hand the colour over
 * twice and push the setter's Paint out of the window it's found by.
 *
 * 585, read 2026-10-30: the place that first fills the Paint moved into the layout itself
 * (arm64 `A0c(FbUserSession, LX/03LO, Z)`), colouring it from the static `LX/02It;->A00(LX/029b, LX/01PR)I`
 * with a null check between the field read and setColor; the setter is `Jxm(FbUserSession, LX/03LO, J)`.
 */
internal fun BytecodePatchContext.selectedTabColourHook(): () -> Unit {
    if (this in hookedContexts) return {}
    val layout = classDefByOrNull(TAB_BAR_CONTAINER)
        ?: throw PatchException("$PATCH: this Facebook build has no $TAB_BAR_CONTAINER")
    val colour = selectedTabColour(layout) { type ->
        classDefByOrNull(type)?.let { AccessFlags.ABSTRACT.isSet(it.accessFlags) && !AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
    }
    val readers = mutableListOf<Pair<String, Method>>()
    // By the class the colour is asked of, which a read names too: its name is an obfuscated one like
    // A00, which nearly every class names (#216).
    classDefForEachReferencing(types = listOf(colour.definingClass)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(mutableListOf()) { selectedTabColourReads(it, colour).isNotEmpty() }
            .forEach { readers += classDef.type to it }
    }
    if (readers.none { it.first == TAB_BAR_CONTAINER } || readers.none { it.first != TAB_BAR_CONTAINER }) {
        throw PatchException(
            "$PATCH: expected $colour read by $TAB_BAR_CONTAINER and by the tab icons, found ${readers.map { it.first }}",
        )
    }
    val paint = lineColourPaint(layout)
    val lines = mutableListOf<Pair<String, Method>>()
    classDefForEachReferencing(types = listOf(paint.definingClass)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        // The provider's setter also colours this Paint, with the colour read above; it's done already.
        classDef.methods.filter { method ->
            lineColourReads(method, paint).isNotEmpty() && readers.none { it.first == classDef.type && it.second.sameSignature(method) }
        }.forEach { lines += classDef.type to it }
    }
    val sites = lines.sumOf { lineColourReads(it.second, paint).size }
    if (sites != 1) {
        throw PatchException("$PATCH: expected one place that colours the line's Paint $paint, found $sites in ${lines.map { it.first }}")
    }
    hookedContexts += this
    return {
        readers.forEach { (type, method) ->
            mutableClassDefBy(type).findMutableMethodOf(method).recolourSelectedTab(colour)
        }
        lines.forEach { (type, method) ->
            mutableClassDefBy(type).findMutableMethodOf(method).recolourSelectedTabLine(paint)
        }
    }
}

/**
 * Puts the extension right after each move-result keeping the selected tab's colour, on the same
 * register, so nothing is borrowed. Last first, so the earlier indices stay right.
 */
internal fun MutableMethod.recolourSelectedTab(colour: MethodReference) =
    handToExtension(selectedTabColourReads(this, colour))

/** The same for the colour the line's Paint is first given. */
internal fun MutableMethod.recolourSelectedTabLine(paint: FieldReference) =
    handToExtension(lineColourReads(this, paint))

private fun MutableMethod.handToExtension(moveResults: List<Int>) {
    moveResults.sortedDescending().forEach { moveResult ->
        val register = (implementation!!.instructions[moveResult] as OneRegisterInstruction).registerA
        addInstructions(
            moveResult + 1,
            """
                invoke-static/range { v$register .. v$register }, $TAB_BAR_SELECTED
                move-result v$register
            """,
        )
    }
}
