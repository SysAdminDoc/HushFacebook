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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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
 */

internal const val DARK_COLOR_SCHEME = "Lcom/facebook/mig/scheme/schemes/DarkColorScheme;"
internal const val FDS_COLORS = "Lcom/facebook/fds/core/theme/component/FDSColors;"
internal const val FDS_COLOR_SCHEME = "Lcom/facebook/mig/scheme/schemes/fds/FdsColorScheme;"
internal const val STATUS_BAR_UTIL = "Lcom/facebook/navigation/statusbar/StatusBarUtil;"
internal const val SET_STATUS_BAR_COLOR = "Landroid/view/Window;->setStatusBarColor(I)V"
internal const val SYSTEM_BARS_CONTROLLER = "Lcom/facebook/navigation/statusbar/controller/SystemBarsController;"

internal const val THEME_PREFERENCES_STATE = "Lcom/facebook/prefs/theme/ThemePreferences\$State;"

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
 */
internal object NavigationBarPainterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/app/Activity;", "Landroid/view/Window;", "I"),
    filters = listOf(
        methodCall(definingClass = "Landroid/view/Window;", name = "setNavigationBarColor"),
    ),
)

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
