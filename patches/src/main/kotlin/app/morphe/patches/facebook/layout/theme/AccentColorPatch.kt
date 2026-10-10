/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val ACCENT = "Lapp/morphe/extension/facebook/theme/AccentColor;"
internal const val ACCENT_MIG = "$ACCENT->mig(ILjava/lang/Object;)I"
internal const val ACCENT_FDS = "$ACCENT->fds(ILjava/lang/Object;)I"
internal const val ACCENT_BLOKS_FILL = "$ACCENT->bloksFill(I)I"
internal const val ACCENT_BLOKS_TEXT = "$ACCENT->bloksText(I)I"
private const val CONTEXT = "Landroid/content/Context;"
private const val COLOUR_SPAN = "Landroid/text/style/ForegroundColorSpan;"

/**
 * An accent colour for Facebook's blue (links, buttons, switches). It rides route one of the
 * themes: the Mig dark scheme and the FDS colour resolvers, hooked the way AMOLED and Material You
 * hook them. Route five ([accentResourcePatch]) lists the colour resources behind the FDS tokens for
 * the extension, which answers for them as each activity is created, for what Facebook inflates from
 * layout XML. Route six is its one anchor of its own: Bloks, whose box fills (the profile's Add to
 * story button) and text colours (a profile's bio link) Facebook's server sends as hex colours
 * ([hookBloksFills], [hookBloksText]). Route seven shares the themes' React Native hooks
 * ([hookReactColours]) for the colours a screen's JavaScript sets. The extension
 * answers Facebook's blue unchanged while the list says Facebook blue, while Hushfacebook is paused,
 * and while Material You is in the build, which decides every colour this would.
 */
@Suppress("unused")
val accentColorPatch = bytecodePatch(
    // The README table check reads this literal.
    name = "Accent color",
    description = "Swaps Facebook's blue on links, buttons, switches and the selected tab for a color you like " +
        "better. Nothing changes until you pick one. Pick a color in Hushfacebook settings > Appearance.",
) {
    category("Theme")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    dependsOn(facebookExtensionPatch)
    dependsOn(accentResourcePatch)

    execute {
        enableStatus("accentColor")
        fillResourceBlues(accentResourceBlues)
        hookBloksFills()
        hookBloksText()
        // Route seven: the React Native hooks the themes share, which leave hooks already in place.
        hookReactColours()
    }

    // After every patch's execute, as the themes do, so each hook here goes after theirs and gets
    // their colour.
    finalize {
        hookDarkModeAnswer()
        hookColourResolvers(mig = ACCENT_MIG, fds = ACCENT_FDS)
    }
}

/**
 * Route six: the fill of each Bloks box goes through [ACCENT_BLOKS_FILL] as the box's drawable is
 * built, in the class of [BoxDecorationBorderFingerprint].
 */
internal fun BytecodePatchContext.hookBloksFills() {
    val border = BoxDecorationBorderFingerprint.method
    val builders = mutableClassDefBy(border.definingClass).methods.filter { isBoxBuilder(it, border) }
    val builder = builders.singleOrNull() ?: throw PatchException(
        "Bloks' BoxDecoration class ${border.definingClass} has ${builders.size} box builders, expected one",
    )
    builder.hookBloksFill(ACCENT_BLOKS_FILL)
}

/**
 * Bloks' box builder (582 `LX/4Nl;->A01`): the static method beside the [border] reader that takes
 * the reader's two parameters and the fill, asks the reader for the border, and sets a Paint's colour
 * to the fill as it came in.
 */
internal fun isBoxBuilder(method: Method, border: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || !method.returnType.startsWith("L")) return false
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (parameters != border.parameterTypes.map(CharSequence::toString) + "I") return false
    val code = method.implementation?.instructions ?: return false
    val fill = method.parameterRegisterNumber(parameters.lastIndex)
    var asksBorder = false
    var paintsFill = false
    for (instruction in code) {
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (call.definingClass == border.definingClass && call.name == border.name && call.returnType == "I") asksBorder = true
        if (call.definingClass == "Landroid/graphics/Paint;" && call.name == "setColor" &&
            (instruction as? FiveRegisterInstruction)?.registerD == fill
        ) {
            paintsFill = true
        }
    }
    return asksBorder && paintsFill
}

/**
 * Route six for Bloks text: the colour of each span of Bloks text, such as a profile's bio link,
 * goes through [ACCENT_BLOKS_TEXT] before the span is made, in the builder [isBloksTextBuilder] finds.
 */
internal fun BytecodePatchContext.hookBloksText() {
    val reader = themedColourReader(BoxDecorationBorderFingerprint.method)
    val builders = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        classDef.methods.filter { isBloksTextBuilder(it, reader) }.mapTo(builders) { classDef.type to it }
    }
    val (owner, builder) = builders.singleOrNull() ?: throw PatchException(
        "Found ${builders.size} Bloks text span builders that ask $reader, expected one",
    )
    mutableClassDefBy(owner).methods.single { it.sameAs(builder) }.hookColourSpan(ACCENT_BLOKS_TEXT)
}

/**
 * Bloks' themed colour reader (582 `LX/4MD;->A00(LX/1Vp;LX/cKD;I)I`): the static (node, theme, int)
 * method the [border] reader asks for a border's colour. It picks the dark or the light hex string
 * and parses it.
 */
internal fun themedColourReader(border: Method): MethodReference {
    val node = border.parameterTypes.last().toString()
    val readers = (border.implementation?.instructions ?: emptyList())
        .filter { it.opcode == Opcode.INVOKE_STATIC }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter { call ->
            val parameters = call.parameterTypes.map(CharSequence::toString)
            call.returnType == "I" && parameters.size == 3 && parameters[0] == node && parameters[2] == "I"
        }
        .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }
    return readers.singleOrNull()
        ?: throw PatchException("BoxDecoration's border reader asks ${readers.size} themed colour readers, expected one")
}

/**
 * Bloks' text span builder (582 `LX/4MC;->A02`): the static (Context, node, theme, List) method that
 * asks the themed colour [reader] for a span's colour and makes the one ForegroundColorSpan it paints with.
 */
internal fun isBloksTextBuilder(method: Method, reader: MethodReference): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags)) return false
    val parameters = method.parameterTypes.map(CharSequence::toString)
    val node = reader.parameterTypes[0].toString()
    val theme = reader.parameterTypes[1].toString()
    if (parameters != listOf(CONTEXT, node, theme, "Ljava/util/List;")) return false
    val code = method.implementation?.instructions ?: return false
    val asks = code.any { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        call != null && call.definingClass == reader.definingClass && call.name == reader.name &&
            call.parameterTypes.map(CharSequence::toString) == reader.parameterTypes.map(CharSequence::toString)
    }
    return asks && code.count { it.makesColourSpan() } == 1
}

/** Whether this is `invoke-direct ForegroundColorSpan.<init>(int)`. */
internal fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.makesColourSpan(): Boolean {
    if (opcode != Opcode.INVOKE_DIRECT) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.definingClass == COLOUR_SPAN && call.name == "<init>" && call.parameterTypes.map(CharSequence::toString) == listOf("I")
}

/**
 * The colour the builder's one ForegroundColorSpan is made with becomes what [target] answers for
 * it, just before the constructor. It goes between the span's `new-instance` and its constructor,
 * which only a branch could jump past, so the instruction before has to be that `new-instance`.
 */
internal fun MutableMethod.hookColourSpan(target: String) {
    val code = implementation!!.instructions.toList()
    val init = code.indexOfFirst { it.makesColourSpan() }
    val made = code.getOrNull(init - 1)
    check(made?.opcode == Opcode.NEW_INSTANCE && ((made as ReferenceInstruction).reference.toString() == COLOUR_SPAN)) {
        "$definingClass->$name: the ForegroundColorSpan's constructor doesn't follow its new-instance"
    }
    val colour = (code[init] as FiveRegisterInstruction).registerD
    addInstructions(
        init,
        """
            invoke-static/range { v$colour .. v$colour }, $target
            move-result v$colour
        """,
    )
}

private fun Method.sameAs(other: Method): Boolean =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/** The fill parameter, the builder's last, becomes what [target] answers for it before the builder reads it. */
internal fun MutableMethod.hookBloksFill(target: String) {
    val fill = parameterRegister(parameterTypes.lastIndex)
    addInstructions(
        0,
        """
            invoke-static/range { $fill .. $fill }, $target
            move-result $fill
        """,
    )
}
