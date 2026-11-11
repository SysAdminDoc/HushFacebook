/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Document

/*
 * The styleable arrays, for route two's night styles.
 *
 * Besides resolving a token's attribute itself (dataReadTokens), Facebook's views and Litho
 * components read theme attributes through styleable arrays: a static int array of attributes
 * handed to obtainStyledAttributes, and the TypedArray read by index. Where that read is getInteger
 * or getInt, a colour state list can't be read: the framework resolves the attribute to the file's
 * name and refuses to make an integer of it, and a feed unit that hits that enough times takes the
 * app down. A style can also alias one attribute to another with `?attr/`, so the array's attribute
 * need not be a token for a token's value to be the one read. The likes line under a post reads its
 * text colour that way, through an attribute of its own that its text styles point at SECONDARY_TEXT
 * (issue #154, a crash on Android 12 and newer with Material You in dark mode). So the tokens those
 * reads reach keep a plain colour in the night styles, as the data-read tokens do.
 */

/**
 * What [integerReadAttributes] found: the attributes, the array fields each reader loads, by
 * reader, and what each of those arrays holds, by field.
 */
internal class IntegerReadScan(val attributes: Set<Int>, val readers: Map<String, Set<String>>, val arrays: Map<String, IntArray>)

private const val TYPED_ARRAY = "Landroid/content/res/TypedArray;"
private const val INT_ARRAY = "[I"
private val INTEGER_READS = setOf("$TYPED_ARRAY->getInteger(II)I", "$TYPED_ARRAY->getInt(II)I")

/**
 * This app's own theme attributes (type attr of package 0x7f) that some styleable array holds and
 * some reader of that array reads with `TypedArray.getInteger` or `getInt`: the method that loads
 * the array's static field, or a method it hands a TypedArray to, and so on down the calls. An
 * array is one a static initializer fills, by fill-array-data or filled-new-array, and stores in a
 * static int array field ([staticIntArrays]). Which index the integer read takes isn't followed, so
 * every attribute of such an array counts. [forEachClass] walks every class once. [forEachNaming],
 * when given, walks only the classes that may name one of the types it gets (the reference index):
 * an array's initializer and loader name the int array type, and a reader or a caller handing one
 * on names TypedArray, so that's every class this scan can keep anything from (#236).
 */
internal fun integerReadAttributes(
    forEachClass: ((ClassDef) -> Unit) -> Unit,
    forEachNaming: (Collection<String>, (ClassDef) -> Unit) -> Unit = { _, visit -> forEachClass(visit) },
): IntegerReadScan {
    val arrays = mutableMapOf<String, IntArray>()
    val loaders = mutableMapOf<String, MutableSet<String>>()
    val callees = mutableMapOf<String, MutableSet<String>>()
    val readers = mutableSetOf<String>()

    forEachNaming(listOf(INT_ARRAY, TYPED_ARRAY)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@forEachNaming
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue
            if (method.name == "<clinit>") arrays += staticIntArrays(instructions)
            // Written out only for a method that holds something this scan keeps (#236).
            val key by lazy(LazyThreadSafetyMode.NONE) { method.key() }
            for (instruction in instructions) {
                when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                    is FieldReference -> if (instruction.opcode == Opcode.SGET_OBJECT && reference.type == INT_ARRAY) {
                        loaders.getOrPut(key) { mutableSetOf() } += reference.key()
                    }
                    is MethodReference -> {
                        if (reference.definingClass == TYPED_ARRAY && reference.key() in INTEGER_READS) {
                            readers += key
                        } else if (reference.parameterTypes.any { it.toString() == TYPED_ARRAY }) {
                            callees.getOrPut(key) { mutableSetOf() } += reference.key()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    // A method whose TypedArray-taking callee reads integers reads them too, as far down as the calls go.
    var grown = true
    while (grown) {
        grown = false
        for ((method, called) in callees) {
            if (method in readers || called.none { it in readers }) continue
            readers += method
            grown = true
        }
    }

    val attributes = sortedSetOf<Int>()
    val found = sortedMapOf<String, MutableSet<String>>()
    val read = sortedMapOf<String, IntArray>()
    for ((method, fields) in loaders) {
        if (method !in readers) continue
        for (field in fields) {
            val array = arrays[field] ?: continue
            val own = array.filter { it ushr 16 == 0x7f04 }
            if (own.isEmpty()) continue
            attributes += own
            found.getOrPut(method) { sortedSetOf() } += field
            read[field] = array
        }
    }
    return IntegerReadScan(attributes, found, read)
}

/**
 * The static int array fields a static initializer's [instructions] fill with a literal array and
 * store, by `class->field`: new-array then fill-array-data, or filled-new-array of literals then
 * move-result-object, the array moved on through object moves to its sput-object.
 */
internal fun staticIntArrays(instructions: List<Instruction>): Map<String, IntArray> {
    val addresses = IntArray(instructions.size)
    var address = 0
    for ((index, instruction) in instructions.withIndex()) {
        addresses[index] = address
        address += instruction.codeUnits
    }
    val byAddress = addresses.withIndex().associate { (index, start) -> start to index }

    val literals = mutableMapOf<Int, Int>()
    val arrays = mutableMapOf<Int, IntArray>()
    var result: IntArray? = null
    val found = mutableMapOf<String, IntArray>()
    for ((index, instruction) in instructions.withIndex()) {
        val opcode = instruction.opcode
        when {
            opcode in LITERALS -> {
                instruction as OneRegisterInstruction
                literals[instruction.registerA] = (instruction as NarrowLiteralInstruction).narrowLiteral
                arrays.remove(instruction.registerA)
            }
            opcode == Opcode.FILL_ARRAY_DATA -> {
                instruction as OneRegisterInstruction
                val payload = byAddress[addresses[index] + (instruction as OffsetInstruction).codeOffset]
                    ?.let { instructions[it] as? ArrayPayload }
                val elements = payload?.takeIf { it.elementWidth == 4 }?.arrayElements?.map { it.toInt() }?.toIntArray()
                if (elements != null) arrays[instruction.registerA] = elements else arrays.remove(instruction.registerA)
            }
            opcode == Opcode.FILLED_NEW_ARRAY || opcode == Opcode.FILLED_NEW_ARRAY_RANGE -> {
                val type = ((instruction as ReferenceInstruction).reference as? TypeReference)?.type
                val registers = when (instruction) {
                    is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
                    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                        instruction.registerF, instruction.registerG).take(instruction.registerCount)
                    else -> emptyList()
                }
                val elements = registers.map { literals[it] }
                result = if (type == INT_ARRAY && elements.none { it == null }) elements.map { it!! }.toIntArray() else null
            }
            opcode == Opcode.MOVE_RESULT_OBJECT -> {
                instruction as OneRegisterInstruction
                result?.let { arrays[instruction.registerA] = it } ?: arrays.remove(instruction.registerA)
                result = null
            }
            opcode in OBJECT_MOVES -> {
                instruction as TwoRegisterInstruction
                arrays[instruction.registerB]?.let { arrays[instruction.registerA] = it } ?: arrays.remove(instruction.registerA)
            }
            opcode == Opcode.SPUT_OBJECT -> {
                instruction as OneRegisterInstruction
                val field = (instruction as ReferenceInstruction).reference as FieldReference
                val array = arrays[instruction.registerA]
                if (field.type == INT_ARRAY && array != null) found[field.key()] = array
            }
            opcode.setsRegister() -> {
                instruction as OneRegisterInstruction
                literals.remove(instruction.registerA)
                arrays.remove(instruction.registerA)
            }
        }
        if (opcode != Opcode.FILLED_NEW_ARRAY && opcode != Opcode.FILLED_NEW_ARRAY_RANGE && opcode != Opcode.MOVE_RESULT_OBJECT) {
            result = null
        }
    }
    return found
}

private val LITERALS = setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16)
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

private fun MethodReference.key(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun FieldReference.key(): String = "$definingClass->$name"

/**
 * The attributes a read of one of [read] can end up reading, through the `?attr/` aliases the
 * styles in [styles] set: [read] itself, each attribute an item of a read attribute points at, and
 * so on. An item's value is `?attr/name`, `?name` or `?package:attr/name`, as a resource decoder
 * writes a theme attribute reference, and the names are the decoder's, such as `attr_0x7f040fd6`.
 */
internal fun aliasedAttributes(styles: List<Document>, read: Set<String>): Set<String> {
    val aliases = mutableMapOf<String, MutableSet<String>>()
    for (document in styles) {
        for (style in document.documentElement.childElements()) {
            for (item in style.childElements()) {
                val value = item.textContent.trim()
                if (!value.startsWith("?")) continue
                val target = value.removePrefix("?").substringAfter(':').removePrefix("attr/")
                aliases.getOrPut(item.getAttribute("name")) { mutableSetOf() } += target
            }
        }
    }
    val reached = read.toMutableSet()
    val pending = ArrayDeque(read)
    while (pending.isNotEmpty()) {
        for (target in aliases[pending.removeFirst()].orEmpty()) if (reached.add(target)) pending += target
    }
    return reached
}

/**
 * The tokens, named by [tokens] from the decoder's attribute names, that the attributes in
 * [integerRead] reach through the aliases the styles in [styles] set. Their night style items keep
 * a plain colour.
 */
internal fun styleablePlainTokens(styles: List<Document>, tokens: Map<String, String>, integerRead: Set<Int>): Set<String> =
    aliasedAttributes(styles, integerRead.mapTo(mutableSetOf()) { "attr_0x%08x".format(it) })
        .mapNotNullTo(sortedSetOf()) { tokens[it] }
