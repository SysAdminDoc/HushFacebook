/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.font

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.util.literalReads
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * The refusal the typeface repository's resolver throws for a family with no source. It is the
 * one literal that method keeps, and the repository class keeps nothing else a patch can carry.
 */
internal const val NO_BACKING_SOURCE = "The requested font, %s, does not have a backing source. " +
    "You need to provide either a systemFontName, assetFontName, or a fileDescriptor."

internal const val TYPEFACE = "Landroid/graphics/Typeface;"
internal const val CONTEXT = "Landroid/content/Context;"
internal const val ENUM = "Ljava/lang/Enum;"
internal const val STRING = "Ljava/lang/String;"
internal const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"

/** Meta's typeface builder factory, a class Redex keeps by name, and the kept method that makes builders. */
internal const val TYPEFACE_BUILDERS = "Lcom/meta/foa/typefacebuilder/ApiUtils;"
internal const val BUILDER_FACTORY = "createTypefaceBuilderFor26Api"

/**
 * What React Native's typeface utilities log for a font variation string Android won't parse,
 * under the tag "ReactNative". Redex merges those utilities into one class of static methods, and
 * the two that parse and apply the string keep this literal in both builds, beside the resolver
 * every piece of React Native text asks for its typeface.
 */
internal const val INVALID_FONT_VARIATION = "Invalid fontVariationSettings: "
internal const val ASSET_MANAGER = "Landroid/content/res/AssetManager;"
/** How React Native's font manager loads a family it finds among the app's font assets. */
private const val CREATE_FROM_ASSET = "createFromAsset"

/**
 * The parameter of React Native's resolver that names the family: after the asset manager and
 * the base typeface, and before the style and the weight.
 */
internal const val REACT_FAMILY = 2

/**
 * What Facebook logs when it can't build the Roboto its own text engine gives text that asks for
 * none of Meta's fonts. The builder, a static method answering a Typeface for a Context and a
 * weight, is the one method that keeps it (580 `LX/2do;->A01`).
 */
internal const val NO_ROBOTO = "Unable to create roboto typeface: %s"

/**
 * Android's default typefaces as a read of the framework's fields names them, each with the name
 * of the extension getter that read becomes. The spans that bold a name in a post's header or a
 * notification read DEFAULT_BOLD themselves (580 `LX/MEz;->updateDrawState`).
 */
internal val DEFAULT_TYPEFACES = mapOf(
    "$TYPEFACE->DEFAULT:$TYPEFACE" to "defaultTypeface",
    "$TYPEFACE->DEFAULT_BOLD:$TYPEFACE" to "defaultBold",
    "$TYPEFACE->SANS_SERIF:$TYPEFACE" to "sansSerif",
)

/** The framework call answering Android's default typeface for a style. */
internal const val DEFAULT_FROM_STYLE = "$TYPEFACE->defaultFromStyle(I)$TYPEFACE"

/** The framework calls answering a typeface of a family by name, and of a typeface at a style or a weight. */
internal const val CREATE_FROM_NAME = "$TYPEFACE->create(Ljava/lang/String;I)$TYPEFACE"
internal const val CREATE_FROM_TYPEFACE = "$TYPEFACE->create(${TYPEFACE}I)$TYPEFACE"
internal const val CREATE_AT_WEIGHT = "$TYPEFACE->create(${TYPEFACE}IZ)$TYPEFACE"

/**
 * The framework calls that can answer one of the phone's typefaces. Each goes to the extension's
 * static method of the same name and arguments ([ownCall]), which makes the call itself first.
 * The "sans-serif-medium" Facebook asks for by name (580 `LX/3st;-><clinit>` among twenty) and the
 * spans that bold a word with Typeface.create(paint's typeface, style) go through these.
 */
internal val DEFAULT_CALLS = setOf(DEFAULT_FROM_STYLE, CREATE_FROM_NAME, CREATE_FROM_TYPEFACE, CREATE_AT_WEIGHT)

/** The extension's stand-in for [call], one of [DEFAULT_CALLS]. */
internal fun ownCall(call: String): String = call.replaceFirst("$TYPEFACE->", "$OWN_FONT->")

private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val TEXT_PAINT = "Landroid/text/TextPaint;"
internal const val LAYOUT_BUILDER = "Landroid/text/StaticLayout\$Builder;"
private const val BORING_LAYOUT = "Landroid/text/BoringLayout;"
private const val BORING_MAKE = "(${CHAR_SEQUENCE}${TEXT_PAINT}ILandroid/text/Layout\$Alignment;FFLandroid/text/BoringLayout\$Metrics;Z"

/** How Facebook starts each multi-line text layout, with the paint the text is drawn in. */
internal const val OBTAIN_LAYOUT = "$LAYOUT_BUILDER->obtain(${CHAR_SEQUENCE}II${TEXT_PAINT}I)$LAYOUT_BUILDER"

/** Where it chooses the font padding for one, mostly none. */
internal const val SET_INCLUDE_PAD = "$LAYOUT_BUILDER->setIncludePad(Z)$LAYOUT_BUILDER"

/** Where it builds one, which is when the line-height spans in its text choose each line's height. */
internal const val BUILD_LAYOUT = "$LAYOUT_BUILDER->build()Landroid/text/StaticLayout;"

/**
 * Android's text layout calls whose font padding the extension decides (#96), each with its
 * stand-in, a static method of the extension taking the very same arguments, a builder's call
 * taking the builder first. Facebook's TextLayoutBuilder, which Litho text is drawn through
 * (581 `LX/2ni;`), and Compose's layouts make every multi-line layout with [OBTAIN_LAYOUT], and
 * TextLayoutBuilder makes one-line text with BoringLayout.make. The thirty-odd uses of
 * StaticLayout's old constructor take the padding inside a register range, and stay Facebook's.
 * [BUILD_LAYOUT] goes through the extension so its line-height spans know a padded layout
 * ([LINE_EDGES]).
 */
internal val LAYOUT_CALLS = mapOf(
    OBTAIN_LAYOUT to "$OWN_FONT->obtain(${CHAR_SEQUENCE}II${TEXT_PAINT}I)$LAYOUT_BUILDER",
    SET_INCLUDE_PAD to "$OWN_FONT->setIncludePad(${LAYOUT_BUILDER}Z)$LAYOUT_BUILDER",
    BUILD_LAYOUT to "$OWN_FONT->build($LAYOUT_BUILDER)Landroid/text/StaticLayout;",
    "$BORING_LAYOUT->make$BORING_MAKE)$BORING_LAYOUT" to "$OWN_FONT->make$BORING_MAKE)$BORING_LAYOUT",
    "$BORING_LAYOUT->make${BORING_MAKE}Landroid/text/TextUtils\$TruncateAt;I)$BORING_LAYOUT" to
        "$OWN_FONT->make${BORING_MAKE}Landroid/text/TextUtils\$TruncateAt;I)$BORING_LAYOUT",
)

internal val LAYOUT_OWNERS = LAYOUT_CALLS.keys.map { it.substringBefore("->") }.toSet()

/**
 * [instruction]'s whole reference as text when the class it names is one of [owners], else null.
 * The class is one string to read where the whole reference is several, and the font scans ask
 * this of every call and field access in the app (#216).
 */
private fun referenceOn(instruction: Instruction, owners: Set<String>): String? {
    val reference = (instruction as ReferenceInstruction).reference
    val owner = when (reference) {
        is MethodReference -> reference.definingClass
        is FieldReference -> reference.definingClass
        else -> return null
    }
    return if (owner in owners) reference.toString() else null
}

/** The one of [LAYOUT_CALLS] [instruction] makes, as its static or virtual call, or null. */
internal fun layoutCall(instruction: Instruction): String? = when (instruction.opcode) {
    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE ->
        referenceOn(instruction, LAYOUT_OWNERS)?.takeIf { it in LAYOUT_CALLS }
    else -> null
}

internal const val FONT_METRICS_INT = "Landroid/graphics/Paint\$FontMetricsInt;"

/**
 * The parameters of LineHeightSpan.chooseHeight, the framework method a line-height span fits each
 * line to its height in, without and with the paint the WithDensity form adds.
 */
private val CHOOSE_HEIGHT = setOf(
    "${CHAR_SEQUENCE}IIII$FONT_METRICS_INT",
    "${CHAR_SEQUENCE}IIII$FONT_METRICS_INT$TEXT_PAINT",
)

/** Whether [method] is a line-height span's chooseHeight, by the framework's name and parameters. */
internal fun isChooseHeight(method: Method): Boolean =
    method.name == "chooseHeight" && method.returnType == "V" &&
        method.parameterTypes.joinToString("") in CHOOSE_HEIGHT

/**
 * A line's top and bottom, which a padded layout's first and last line take their font padding
 * from, each with the extension's stand-in for a span's write of it (#96). The stand-in takes the
 * value and the metrics in the write's own two registers.
 */
internal val LINE_EDGES = mapOf(
    "$FONT_METRICS_INT->top:I" to "$OWN_FONT->lineTop(I$FONT_METRICS_INT)V",
    "$FONT_METRICS_INT->bottom:I" to "$OWN_FONT->lineBottom(I$FONT_METRICS_INT)V",
)

/** The one of [LINE_EDGES] [instruction] writes, or null. */
internal fun lineEdgeWrite(instruction: Instruction): String? =
    if (instruction.opcode != Opcode.IPUT) {
        null
    } else {
        referenceOn(instruction, FONT_METRICS_OWNER)?.takeIf { it in LINE_EDGES }
    }

private val FONT_METRICS_OWNER = setOf(FONT_METRICS_INT)

/**
 * Whether every use of the typeface the field read at [index] loads is a comparison with another
 * typeface: an if-eq or if-ne, an equals call it's handed to, or Kotlin's areEqual, a static call
 * on two objects answering a boolean under whatever name R8 gave it that [isEquality] says only
 * compares them ([isEqualityCheck]). Each such check asks whether a typeface is Android's own
 * default, and keeps asking that. Litho's text sets a typeface on its paint only when it isn't
 * Typeface.DEFAULT (580 `LX/3qU;->A00`), so with both sides of that check the picked font, plain
 * text would never get it. The post text takes its own default branch only for Typeface.DEFAULT
 * itself (580 `LX/302;->A0k`), and on the other one it sets the typeface it holds, which can be
 * Android's from a caller. A read nothing uses isn't one, and nor is one a static call of any
 * other kind takes: that call may keep or hand on the typeface, so it gets the picked file.
 */
internal fun Method.onlyCompared(index: Int, isEquality: (MethodReference) -> Boolean): Boolean {
    val code = implementation!!.instructions.toList()
    val uses = literalReads(index)
    return uses.isNotEmpty() && uses.all { at ->
        when (code[at].opcode) {
            Opcode.IF_EQ, Opcode.IF_NE -> true
            else -> ((code[at] as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                call.returnType == "Z" && call.parameterTypes.all { it.toString() == OBJECT } && when (code[at].opcode) {
                    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> call.parameterTypes.size == 2 && isEquality(call)
                    else -> call.name == "equals"
                }
            } == true
        }
    }
}

/** Java's own check of whether two objects are equal, which isn't in Facebook's code to look at. */
internal const val OBJECTS_EQUALS = "Ljava/util/Objects;->equals($OBJECT$OBJECT)Z"

private const val OBJECT_EQUALS = "$OBJECT->equals($OBJECT)Z"

/**
 * Whether [method], a static method on two objects answering a boolean, does nothing but ask
 * whether they're equal, as Kotlin's areEqual does under whatever name R8 gave it: it tests either
 * one for null, compares the two, hands them to equals, and answers a constant or what equals
 * answered. A field it reads, any other call or a value it builds, and it isn't one.
 */
internal fun isEqualityCheck(method: Method): Boolean {
    if (!method.isStatic() || method.returnType != "Z" || method.parameterTypeNames() != listOf(OBJECT, OBJECT)) return false
    val implementation = method.implementation ?: return false
    // A static method's parameters sit in its last registers.
    val first = implementation.registerCount - 2
    val parameters = setOf(first, first + 1)
    var compares = false
    for (instruction in implementation.instructions) {
        when (instruction.opcode) {
            Opcode.IF_EQZ, Opcode.IF_NEZ ->
                if ((instruction as OneRegisterInstruction).registerA !in parameters) return false
            Opcode.IF_EQ, Opcode.IF_NE -> {
                val pair = instruction as TwoRegisterInstruction
                if (setOf(pair.registerA, pair.registerB) != parameters) return false
                compares = true
            }
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> {
                if ((instruction as ReferenceInstruction).reference.toString() != OBJECT_EQUALS) return false
                val registers = when (instruction) {
                    is RegisterRangeInstruction -> List(instruction.registerCount) { instruction.startRegister + it }
                    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD).take(instruction.registerCount)
                    else -> return false
                }
                if (registers.size != 2 || registers.toSet() != parameters) return false
                compares = true
            }
            Opcode.CONST_4, Opcode.CONST_16 ->
                if ((instruction as NarrowLiteralInstruction).narrowLiteral !in 0..1) return false
            Opcode.MOVE_RESULT, Opcode.RETURN, Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32 -> Unit
            else -> return false
        }
    }
    return compares
}

/** The field or call [instruction] reads one of Android's default typefaces through, or null when it reads none. */
internal fun defaultRead(instruction: Instruction): String? = when (instruction.opcode) {
    Opcode.SGET_OBJECT -> referenceOn(instruction, TYPEFACE_OWNER)?.takeIf { it in DEFAULT_TYPEFACES }
    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> referenceOn(instruction, TYPEFACE_OWNER)?.takeIf { it in DEFAULT_CALLS }
    else -> null
}

private val TYPEFACE_OWNER = setOf(TYPEFACE)

/**
 * Android's text views, the ones Facebook builds with `new` or extends. Each one's constructor
 * reads its typeface from the layout or leaves it unset, and Android's own code does that, where
 * no rewrite of Facebook's reaches.
 */
internal val FRAMEWORK_TEXT_VIEWS = setOf(
    "TextView", "EditText", "Button", "AutoCompleteTextView", "MultiAutoCompleteTextView", "CheckedTextView",
    "CompoundButton", "CheckBox", "RadioButton", "Switch", "ToggleButton",
).map { "Landroid/widget/$it;" }.toSet()

internal const val TEXT_VIEW = "Landroid/widget/TextView;"
internal const val VIEW = "Landroid/view/View;"

/** How Facebook's layout inflaters make a view of one of Android's classes from a layout's tag. */
internal const val CREATE_VIEW = "Landroid/view/LayoutInflater;->createView($STRING${STRING}Landroid/util/AttributeSet;)$VIEW"

/**
 * The register holding the text view [instruction] builds, when it's the constructor call of one
 * of [FRAMEWORK_TEXT_VIEWS], as `new` or as a view's super call. Null for anything else.
 */
internal fun builtTextView(instruction: Instruction): Int? {
    if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) return null
    val called = (instruction as ReferenceInstruction).reference as? MethodReference ?: return null
    if (called.name != "<init>" || called.definingClass !in FRAMEWORK_TEXT_VIEWS) return null
    return when (instruction) {
        is RegisterRangeInstruction -> instruction.startRegister
        is FiveRegisterInstruction -> instruction.registerC
        else -> null
    }
}

/** Whether [instruction] is a layout inflater's call of [CREATE_VIEW]. */
internal fun makesView(instruction: Instruction): Boolean =
    (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
        referenceOn(instruction, INFLATER_OWNER) == CREATE_VIEW

private val INFLATER_OWNER = setOf(CREATE_VIEW.substringBefore("->"))

/**
 * The family constants the extension swaps: Meta's interface families, by the names the enum
 * keeps, less the monospace ones. 587 adds OPTIMISTIC_MONO, which code and other fixed-width text
 * ask for so their columns line up; the picked font would break that, so it keeps Meta's face.
 * OwnFont.isInterfaceFamily is the same rule on the phone.
 */
internal fun isInterfaceFamily(name: String): Boolean =
    (name.startsWith("OPTIMISTIC") || name == "FACEBOOK_SANS_VARIABLE") && !isMonospaceFamily(name)

/** Whether [name], a family constant, is a monospace face: one of its words starts with MONO. */
internal fun isMonospaceFamily(name: String): Boolean = name.split('_').any { it.startsWith("MONO") }

/** An enum constant's name as Redex leaves it: capitals, digits and underscores. */
private val CONSTANT_NAME = Regex("[A-Z][A-Z0-9_]{2,}")

private fun Method.strings(): List<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

private fun Method.parameterTypeNames() = parameterTypes.map { it.toString() }

/**
 * The static resolvers of [owner]: a Typeface answered for a family (the first parameter) at a
 * weight (the last), ending in the no-source refusal. The patch wants exactly one.
 */
internal fun typefaceResolvers(owner: ClassDef): List<Method> = owner.methods.filter { method ->
    method.isStatic() && method.returnType == TYPEFACE && holdsString(method, NO_BACKING_SOURCE) &&
        method.parameterTypes.size >= 2 && method.parameterTypeNames().last() == "I" &&
        method.parameterTypeNames().first().startsWith("L")
}

/**
 * The static methods of [owner] that answer a Typeface for a Context and end in the [NO_ROBOTO]
 * log: Facebook's Roboto builder. The patch wants exactly one.
 */
internal fun robotoBuilders(owner: ClassDef): List<Method> = owner.methods.filter { method ->
    method.isStatic() && method.returnType == TYPEFACE && method.parameterTypeNames().firstOrNull() == CONTEXT &&
        holdsString(method, NO_ROBOTO)
}

/** The font family type [resolver] takes: its first parameter. */
internal fun familyType(resolver: Method): String = resolver.parameterTypeNames().first()

/** The constant names [family] declares: the upper-case literals its constructors and static initializer load. */
internal fun familyNames(family: ClassDef): List<String> =
    family.methods.filter { it.name == "<clinit>" || it.name == "<init>" }
        .flatMap { it.strings() }.filter { CONSTANT_NAME.matches(it) }.distinct()

/**
 * The constants of [family] built with a list of variable axes: each constructor call in the
 * static initializer that takes a List, named by the last constant literal loaded before it,
 * or, for a constructor that carries its own name, by the literal in its body.
 */
internal fun familiesWithAxes(family: ClassDef): List<String> {
    val initializer = family.methods.firstOrNull { it.name == "<clinit>" } ?: return emptyList()
    val found = mutableListOf<String>()
    var lastName: String? = null
    for (instruction in initializer.implementation?.instructions?.toList().orEmpty()) {
        val reference = (instruction as? ReferenceInstruction)?.reference
        (reference as? StringReference)?.string?.let { if (CONSTANT_NAME.matches(it)) lastName = it }
        val call = reference as? MethodReference ?: continue
        if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) continue
        if (call.definingClass != family.type || call.name != "<init>") continue
        val parameters = call.parameterTypes.map { it.toString() }
        if (LIST !in parameters) continue
        if (STRING in parameters) {
            lastName?.let { found += it }
        } else {
            family.methods.filter { it.name == "<init>" && it.parameterTypeNames() == parameters }
                .flatMap { it.strings() }.filter { CONSTANT_NAME.matches(it) }.forEach { found += it }
        }
    }
    return found.distinct()
}

/** The classes the builder factory's methods instantiate: the asset builder and the font file builder. */
internal fun builderClasses(apiUtils: ClassDef): List<String> =
    apiUtils.methods.filter { it.name == BUILDER_FACTORY }.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
            if (instruction.opcode != Opcode.NEW_INSTANCE) null
            else ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type
        }
    }.distinct()

/** Whether [instruction] calls the builder factory. */
internal fun callsBuilderFactory(instruction: Instruction): Boolean {
    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return call.definingClass == TYPEFACE_BUILDERS && call.name == BUILDER_FACTORY
}

/** The methods of [classDef] that make a builder: each one calling the factory. */
internal fun builderMakers(classDef: ClassDef): List<Method> = classDef.methods.filter { method ->
    method.implementation?.instructions?.any(::callsBuilderFactory) == true
}

/** A builder's build: its instance method answering a Typeface for a Context. */
internal fun buildMethods(classDef: ClassDef): List<Method> = classDef.methods.filter {
    !it.isStatic() && it.returnType == TYPEFACE && it.parameterTypeNames() == listOf(CONTEXT)
}

/** A builder's variation setter: its instance method taking one String and answering nothing. */
internal fun variationSetters(classDef: ClassDef): List<Method> = classDef.methods.filter {
    !it.isStatic() && it.returnType == "V" && it.parameterTypeNames() == listOf(STRING)
}

/**
 * React Native's typeface resolvers among [owner]'s methods: static, answering a Typeface for an
 * asset manager, a base typeface, a family name, a style and a weight, and loading a family it
 * finds among the app's font assets itself. That's React Native's applyStyles with its font
 * manager's lookup inlined. The patch wants exactly one.
 */
internal fun reactNativeResolvers(owner: ClassDef): List<Method> = owner.methods.filter { method ->
    method.isStatic() && method.returnType == TYPEFACE &&
        method.parameterTypeNames() == listOf(ASSET_MANAGER, TYPEFACE, STRING, "I", "I") &&
        method.implementation?.instructions?.any { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            call?.definingClass == TYPEFACE && call.name == CREATE_FROM_ASSET
        } == true
}

/** Whether [type] is the root every chain ends at. */
internal fun isObject(type: String) = type == OBJECT

/** How many of [method]'s registers are locals: the frame less `this` and the parameters. */
internal fun localRegisters(method: Method): Int {
    val implementation = method.implementation ?: return 0
    val self = if (method.isStatic()) 0 else 1
    val parameters = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return implementation.registerCount - self - parameters
}

/** The indices of [method]'s object returns, in order. */
internal fun objectReturns(method: Method): List<Int> = method.implementation?.instructions
    ?.withIndex()?.filter { it.value.opcode == Opcode.RETURN_OBJECT }?.map { it.index }.orEmpty()
