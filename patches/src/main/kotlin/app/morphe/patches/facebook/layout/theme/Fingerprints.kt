/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/amoledtheme/Fingerprints.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.WeakHashMap

/**
 * The four resolvers, which answer the first of the four routes that a colour takes.
 *
 * Facebook has two colour systems and they share no code. Mig is the older one, and each of its
 * ~120 getters ends in one method per colour scheme. FDS is the newer one and carries most of the
 * app: `FDSColors` answers Litho, and a theme resolver with a Redex name (`LX/1tN` on 577, `LX/20i`
 * on 580) answers the view code, found through the view resolver in front of it.
 *
 * R8 keeps the owner class names but renames the methods on every release. Thus each fingerprint
 * uses the class, the return type, the parameters and the shape of the body, and never a name.
 * 584 renamed `FDSColors` and 588 split it up, so its resolver is found by its shape ([fdsColors]).
 */

internal const val DARK_COLOR_SCHEME = "Lcom/facebook/mig/scheme/schemes/DarkColorScheme;"
internal const val LIGHT_COLOR_SCHEME = "Lcom/facebook/mig/scheme/schemes/LightColorScheme;"
internal const val FDS_COLOR_SCHEME = "Lcom/facebook/mig/scheme/schemes/fds/FdsColorScheme;"
internal const val STATUS_BAR_UTIL = "Lcom/facebook/navigation/statusbar/StatusBarUtil;"
internal const val SET_STATUS_BAR_COLOR = "Landroid/view/Window;->setStatusBarColor(I)V"
internal const val SYSTEM_BARS_CONTROLLER = "Lcom/facebook/navigation/statusbar/controller/SystemBarsController;"

internal const val THEME_PREFERENCES_STATE = "Lcom/facebook/prefs/theme/ThemePreferences\$State;"

private val fdsColorsTypes = WeakHashMap<BytecodePatchContext, String>()

/**
 * `FDSColors`, the class answering Litho's colour tokens. Redex kept its name up to 583
 * (`Lcom/facebook/fds/core/theme/component/FDSColors;`) and renamed it on 584 (`LX/29y`). 588 took it
 * apart: its resolver is a static method on a class of its own, so what's looked for here is the
 * class holding that method ([fdsColourSource]). One class has it, or the lookup fails.
 */
internal fun BytecodePatchContext.fdsColors(): String = fdsColorsTypes.getOrPut(this) {
    val found = mutableListOf<String>()
    classDefForEach { classDef ->
        if (!classDef.type.startsWith(EXTENSION_PACKAGE) && fdsColourSource(classDef) != null) found += classDef.type
    }
    found.singleOrNull() ?: throw PatchException(
        "expected one FDS colour resolver (a static int method taking a Context, a token and a palette that hands the token to the palette's colour step), found $found",
    )
}

/**
 * FDS's colour resolver in [classDef], else null: the static method answering an int from a Context,
 * a token and a palette, which hands the token to a static method taking the token, an int and a
 * boolean, the step reading the palette's colour. 588 (`LX/02IY;->A00`) folded the old `Integer`
 * colour source and its instance resolvers into this one method, which the resolvers' callers now
 * all reach. The token type is its second parameter.
 */
internal fun fdsColourSource(classDef: ClassDef): Method? = classDef.methods.singleOrNull { method ->
    val parameters = method.parameterTypes.map { it.toString() }
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" &&
        parameters.size == 3 && parameters[0] == "Landroid/content/Context;" &&
        method.implementation?.instructions?.any { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            instruction.opcode == Opcode.INVOKE_STATIC && call?.returnType == "Ljava/lang/Integer;" &&
                call.parameterTypes.map { it.toString() } == listOf(parameters[1], "I", "Z")
        } == true
}

/**
 * Facebook's dark mode controller answering whether its dark mode is on, for the whole app: the
 * Dark mode setting, or the system's night mode when the setting follows the system (580
 * `LX/1QV;->A05`, 577 `LX/1L7;->A05`). It's the only method holding the end-to-end tests' dark
 * mode switch, and it reads the setting's ThemePreferences state. An activity's own theme can force
 * dark or light without it, which is why the answer comes from here and not from the method that
 * applies an activity's theme.
 */
internal object DarkModeFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("fb.e2e.enable_dark_mode"),
    filters = listOf(fieldAccess(type = THEME_PREFERENCES_STATE)),
)

internal const val BOX_DECORATION_BORDER_ERROR = "Error parsing border color in BoxDecoration"

/**
 * Bloks' BoxDecoration border reader (582 `LX/4Nl;->A00`): the one method that logs a border
 * colour it can't parse. Its class also builds each box, and the Accent color patch finds the
 * builder there ([isBoxBuilder]).
 */
internal object BoxDecorationBorderFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("L", "L"),
    strings = listOf(BOX_DECORATION_BORDER_ERROR),
)

internal const val NATIVE_TEMPLATE_BOX = "NTBox3Builder#build"

/**
 * Native Templates' component builder (584 `LX/4Kb;->D9f(LX/3tD;LX/5en;LX/4Km;)LX/3T3;`, 585 the
 * static `LX/04d0;->A00(LX/01PR;LX/062H;LX/04e3;I)LX/03G2;`): the
 * method that builds each node of a native template by its kind, such as the Menu's profile card,
 * and traces a box as [NATIVE_TEMPLATE_BOX]. It and its child builder set a box's server colour
 * with Litho's backgroundColor ([hookNativeTemplateBackgrounds]).
 */
internal object NativeTemplateBuilderFingerprint : Fingerprint(
    strings = listOf(NATIVE_TEMPLATE_BOX),
)

/**
 * The Data mode banner of Facebook Flex, issue #86: the one method that logs both of its own steps,
 * as it starts and when the banner's wrapper isn't there. It paints the wrapper with a card's token.
 */
internal object FlexBannerFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("start_redesigned_freemium_banner", "banner_wrapper_is_null"),
)

/**
 * The navigation bar's painter: SystemNavigationBarUtil's static (Activity, Window, int) method that
 * calls `Window.setNavigationBarColor`, and on Android 15 and newer paints Facebook's own navigation
 * bar view instead. Its class has a Redex name, so the fingerprint uses the shape and the framework
 * call.
 *
 * 584 (LX/2LF;->A00) takes its own class as a third parameter, (Activity, Window, LX/2LF, int): the
 * Kotlin object's instance, passed in once the companion was inlined. The colour is the last
 * parameter either way ([navigationBarColourIndex]).
 */
internal object NavigationBarPainterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "Landroid/view/Window;", name = "setNavigationBarColor"),
    ),
    custom = { method, _ ->
        val parameters = method.parameterTypes.map(CharSequence::toString)
        parameters.size in 3..4 && parameters[0] == "Landroid/app/Activity;" && parameters[1] == "Landroid/view/Window;" &&
            parameters.last() == "I" && (parameters.size == 3 || parameters[2] == method.definingClass)
    },
)

/** The register-parameter index of the colour in the navigation bar's painter: its last parameter (2 up to 583, 3 on 584). */
internal fun navigationBarColourIndex(painter: Method): Int = painter.parameterTypes.size - 1

/**
 * The Mig dark scheme resolver. The return type and the interface call remove the three sibling
 * methods. The light scheme has the same shape, thus the class is part of the fingerprint.
 */
internal object DarkSchemeResolveFingerprint : Fingerprint(
    definingClass = DARK_COLOR_SCHEME,
    returnType = "I",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_INTERFACE, returnType = "I"),
        opcode(Opcode.MOVE_RESULT),
        opcode(Opcode.RETURN),
    ),
)

/**
 * The Mig light scheme's resolver, for the Light background color row (#214): the dark one's shape
 * on the light scheme's class. Its sibling that takes an Integer has the same shape and asks the
 * scheme through the interface, so the call has to be on the token's own type.
 */
internal object LightSchemeResolveFingerprint : Fingerprint(
    definingClass = LIGHT_COLOR_SCHEME,
    returnType = "I",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_INTERFACE, returnType = "I"),
        opcode(Opcode.MOVE_RESULT),
        opcode(Opcode.RETURN),
    ),
    custom = { method, _ ->
        val token = method.parameterTypes.singleOrNull()?.toString()
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.INVOKE_INTERFACE &&
                ((instruction as ReferenceInstruction).reference as MethodReference).definingClass == token
        } == true
    },
)

/**
 * The step to the resolver of the view code. That resolver has a Redex name and no literal, thus
 * the patch matches this wrapper and reads the descriptor of its `invoke-static`. The wrapper is
 * the only method that reads the one `Context` field.
 */
internal object FdsSchemeResolveFingerprint : Fingerprint(
    definingClass = FDS_COLOR_SCHEME,
    returnType = "I",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(definingClass = FDS_COLOR_SCHEME, type = "Landroid/content/Context;"),
        methodCall(opcode = Opcode.INVOKE_STATIC, returnType = "I"),
        opcode(Opcode.MOVE_RESULT),
        opcode(Opcode.RETURN),
    ),
)
