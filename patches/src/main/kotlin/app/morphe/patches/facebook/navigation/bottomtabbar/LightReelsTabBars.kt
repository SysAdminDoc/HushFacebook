/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.layout.theme.DarkModeFingerprint
import app.morphe.patches.facebook.layout.theme.hookDarkModeAnswer
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.WORDMARK_NAVIGATION_BAR
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Light bars on the Reels tab (#423). Read from 585, 2026-11-08; the obfuscated names are for
 * reviewers, the code never writes one down.
 *
 * In light mode Facebook darkens three things on the Reels tab, its Video tab (id 0x8ea18579):
 *
 * - The tab bar. A static (Context, FbUserSession, Long) method (585 `LX/033L;->A00`) makes the tab
 *   bar's colours for the selected tab's id: the Video tab's dark ones for 0x8ea18579, everyone
 *   else's for any other id or null. First thing in it, the extension is handed the id and answers
 *   null for the Video tab. That only decides it when the tab's own fragment has no say: the tab bar
 *   (585 `LX/02Js;->A04`) first asks the selected fragment, and the kept VideoHomeRootFragment's
 *   answer (a (TabTag)Z, 585 `Jgc`) is yes when its reels show in the tab and the TabTag's id is the
 *   Video tab's. That id read goes through the extension too, which answers 0 for the Video tab.
 * - The main screen's status bar. The kept FbChromeFragment's getSystemBarsConfig reads the selected
 *   TabTag's id and compares it with 0x8ea18579, building a dark status bar for the Video tab and
 *   asking a no-arg static (585 `LX/02Xs;->A00`) for the other tabs' light one. The id read goes
 *   through the extension, which answers 0 for the Video tab.
 * - The Video tab's own bars. The kept VideoHomeRootFragment builds its status bar in a method of a
 *   helper (585 `LX/0Ao9;->A02`) that its own getSystemBarsConfig and its window setup call, and
 *   paints the status bar (StatusBarUtil) and the top bar (WordmarkNavigationBar's (ZI)V, 585
 *   `Jd2`) dark wherever an interface of the tab's (585 `LX/0DhY;->BWR`) says its reels show in
 *   the tab. The helper hands back the other tabs' status bar first thing while the extension says
 *   so, and each of those answers goes through the extension, which says no.
 */

internal const val LIGHT_REELS_TAB_BARS = "Lapp/morphe/extension/facebook/navigation/LightReelsTabBars;"

/** Facebook's id for the Video tab, the one its tab bar shows as Reels. */
internal const val VIDEO_TAB_ID = 0x8ea18579L

internal const val FB_CHROME_FRAGMENT = "Lcom/facebook/katana/fragment/FbChromeFragment;"
internal const val VIDEO_HOME_ROOT_FRAGMENT = "Lcom/facebook/video/videohome/fragment/VideoHomeRootFragment;"
internal const val TAB_TAG = "Lcom/facebook/navigation/tabbar/state/model/TabTag;"
internal const val SYSTEM_BARS_CONFIG = "getSystemBarsConfig"

private const val TAB_BAR_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

/** The tab bar colour picker's parameters: the context, the user session and the selected tab's id. */
internal val TAB_BAR_PICKER_PARAMETERS = listOf("Landroid/content/Context;", TAB_BAR_SESSION, "Ljava/lang/Long;")

private val CONSTANT_LOADS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)

private val TESTS = setOf(
    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
)

private val BRANCH_ENDS = TESTS + setOf(
    Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32, Opcode.THROW,
    Opcode.RETURN, Opcode.RETURN_VOID, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE,
)

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodCall(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** Whether [method] is the tab bar colour picker: static, [TAB_BAR_PICKER_PARAMETERS], loading the Video tab's id. */
internal fun isTabBarPicker(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.map(CharSequence::toString) == TAB_BAR_PICKER_PARAMETERS &&
        method.body().any { (it as? WideLiteralInstruction)?.wideLiteral == VIDEO_TAB_ID }

/** The first parameter of the constructor [method] calls on its own return type: the status bar config's type. */
internal fun statusBarConfigType(method: Method): String? = method.body().firstNotNullOfOrNull { instruction ->
    instruction.methodCall()?.takeIf {
        instruction.opcode == Opcode.INVOKE_DIRECT && it.name == "<init>" && it.definingClass == method.returnType
    }?.parameterTypes?.firstOrNull()?.toString()
}

/**
 * Where [method] reads a tab's id to compare it with the Video tab's (the main screen's
 * getSystemBarsConfig, the Video tab's tab bar answer): the index of the `iget-wide` of TabTag's long
 * that a `const-wide` of the Video tab's id follows. Null unless there's exactly one.
 */
internal fun barsTabRead(method: Method): Int? {
    val body = method.body()
    return body.indices.filter { at ->
        val field = (body[at] as? ReferenceInstruction)?.reference as? FieldReference
        body[at].opcode == Opcode.IGET_WIDE && field?.definingClass == TAB_TAG && field.type == "J" &&
            (body.getOrNull(at + 1) as? WideLiteralInstruction)?.wideLiteral == VIDEO_TAB_ID
    }.singleOrNull()
}

/** The no-arg statics [method] calls for the status bar config of [type]: the other tabs' status bar. */
internal fun otherTabsStatusBar(method: Method, type: String): List<MethodReference> = method.body().mapNotNull { instruction ->
    instruction.methodCall()?.takeIf {
        instruction.opcode == Opcode.INVOKE_STATIC && it.parameterTypes.isEmpty() && it.returnType == type
    }
}.distinctBy { "${it.definingClass}->${it.name}" }

/** The calls the Video tab's getSystemBarsConfig makes for its status bar config of [type]. */
internal fun reelsStatusBars(method: Method, type: String): List<MethodReference> = method.body().mapNotNull { instruction ->
    instruction.methodCall()?.takeIf { instruction.opcode == Opcode.INVOKE_VIRTUAL && it.returnType == type }
}.distinctBy { "${it.definingClass}->${it.name}" }

/** What the Video tab's dark branch paints. */
internal enum class DarkPaint { STATUS_BAR, TOP_BAR }

/**
 * One read in [method] of an answer of the tab's: the `move-result` at [resultIndex] keeps it in
 * [register], and the code an answer of true falls into paints [paints].
 */
internal class DarkBarRead(val method: Method, val resultIndex: Int, val register: Int, val name: String, val paints: DarkPaint)

private fun paintOf(call: MethodReference): DarkPaint? {
    val parameters = call.parameterTypes.map(CharSequence::toString)
    if (call.returnType != "V") return null
    if (call.definingClass == STATUS_BAR_UTIL && parameters == listOf("Landroid/view/Window;", "I")) return DarkPaint.STATUS_BAR
    if (call.definingClass == WORDMARK_NAVIGATION_BAR && parameters == listOf("Z", "I")) return DarkPaint.TOP_BAR
    return null
}

/**
 * Each read in [method] of a no-arg boolean of [tabInterface] that an `if` tests right after (past
 * `const` loads), where the code the `if` falls into paints the status bar or the top bar before it
 * branches again.
 */
internal fun darkBarReads(method: Method, tabInterface: String): List<DarkBarRead> {
    val body = method.body()
    return body.indices.mapNotNull { at ->
        val call = body[at].methodCall() ?: return@mapNotNull null
        if (body[at].opcode != Opcode.INVOKE_INTERFACE || call.definingClass != tabInterface ||
            call.parameterTypes.isNotEmpty() || call.returnType != "Z"
        ) {
            return@mapNotNull null
        }
        val result = body.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } ?: return@mapNotNull null
        val register = (result as OneRegisterInstruction).registerA
        var test = at + 2
        while (test < body.size && body[test].opcode in CONSTANT_LOADS) test++
        if (body.getOrNull(test)?.let { it.opcode in TESTS && register in it.namedRegisters() } != true) return@mapNotNull null
        var paints: DarkPaint? = null
        for (next in test + 1 until body.size) {
            paints = body[next].methodCall()?.let(::paintOf)
            if (paints != null || body[next].opcode in BRANCH_ENDS) break
        }
        paints?.let { DarkBarRead(method, at + 1, register, call.name, it) }
    }
}

/**
 * The reads [darkBarReads] finds in [videoHome] of the one answer whose true branch paints the top
 * bar. Empty unless that's one answer, painting the top bar and the status bar.
 */
internal fun reelsInTabReads(videoHome: ClassDef, tabInterface: String): List<DarkBarRead> {
    val reads = videoHome.methods.flatMap { darkBarReads(it, tabInterface) }
    val name = reads.filter { it.paints == DarkPaint.TOP_BAR }.map { it.name }.distinct().singleOrNull() ?: return emptyList()
    val chosen = reads.filter { it.name == name }
    return if (chosen.any { it.paints == DarkPaint.STATUS_BAR }) chosen else emptyList()
}

/** The local registers [method] has below its parameters. */
private fun localsOf(method: Method): Int {
    val parameters = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2L else 1L }.toInt() +
        if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    return (method.implementation?.registerCount ?: 0) - parameters
}

/** Whether [method] is the Video tab's answer to the tab bar: an instance (TabTag)Z reading a TabTag's id to compare with the Video tab's. */
internal fun isReelsTabBarAnswer(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(TAB_TAG) && barsTabRead(method) != null

/**
 * Everything [hookLightReelsTabBars] changes, found before anything changes: the tab bar colour
 * picker, the Video tab's answer to the tab bar [reelsTabBar] with its id read at [reelsTabBarRead],
 * the main screen's status bar config with its id read at [chromeRead] and the other tabs' status
 * bar [otherTabs] of [statusBarType], the Video tab's status bar helper [reelsBars], and the reads of
 * its reels-in-tab answer.
 */
internal class LightReelsSites(
    val picker: Method,
    val reelsTabBar: Method,
    val reelsTabBarRead: Int,
    val chromeBars: Method,
    val chromeRead: Int,
    val statusBarType: String,
    val otherTabs: MethodReference,
    val reelsBars: Method,
    val darkReads: List<DarkBarRead>,
)

private fun skipLight(why: String): LightReelsSites? {
    patchLog.warning("$PATCH: $why, so Light bars on the Reels tab does nothing on this build")
    return null
}

/** Finds [LightReelsSites], or says what's missing and answers null. */
internal fun BytecodePatchContext.lightReelsSites(): LightReelsSites? {
    val pickers = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(TAB_BAR_SESSION)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(pickers, ::isTabBarPicker)
    }
    val picker = pickers.singleOrNull()
        ?: return skipLight("expected one tab bar colour picker taking $TAB_BAR_PICKER_PARAMETERS, found ${pickers.size}")

    val chrome = classDefByOrNull(FB_CHROME_FRAGMENT) ?: return skipLight("$FB_CHROME_FRAGMENT isn't in this build")
    val chromeBars = chrome.methods.singleOrNull { it.name == SYSTEM_BARS_CONFIG && it.parameterTypes.size == 1 }
        ?: return skipLight("expected one $FB_CHROME_FRAGMENT->$SYSTEM_BARS_CONFIG")
    val statusBarType = statusBarConfigType(chromeBars)
        ?: return skipLight("$FB_CHROME_FRAGMENT->$SYSTEM_BARS_CONFIG builds no ${chromeBars.returnType}")
    val otherTabs = otherTabsStatusBar(chromeBars, statusBarType).singleOrNull()
        ?: return skipLight("expected one no-arg static answering $statusBarType in $FB_CHROME_FRAGMENT->$SYSTEM_BARS_CONFIG")
    val chromeRead = barsTabRead(chromeBars)
        ?: return skipLight("expected one read of $TAB_TAG's id compared with the Video tab's in $FB_CHROME_FRAGMENT")

    val videoHome = classDefByOrNull(VIDEO_HOME_ROOT_FRAGMENT) ?: return skipLight("$VIDEO_HOME_ROOT_FRAGMENT isn't in this build")
    val videoBars = videoHome.methods.singleOrNull { it.name == SYSTEM_BARS_CONFIG && it.parameterTypes.size == 1 }
        ?: return skipLight("expected one $VIDEO_HOME_ROOT_FRAGMENT->$SYSTEM_BARS_CONFIG")
    val reelsTabBar = videoHome.methods.singleOrNull(::isReelsTabBarAnswer)
        ?: return skipLight("expected one ($TAB_TAG)Z in $VIDEO_HOME_ROOT_FRAGMENT comparing the tab's id with the Video tab's")
    val reelsTabBarRead = barsTabRead(reelsTabBar)!!
    val reelsCall = reelsStatusBars(videoBars, statusBarType).singleOrNull()
        ?: return skipLight("expected one call answering $statusBarType in $VIDEO_HOME_ROOT_FRAGMENT->$SYSTEM_BARS_CONFIG")
    val reelsBars = classDefByOrNull(reelsCall.definingClass)?.methods?.singleOrNull {
        it.name == reelsCall.name && it.returnType == statusBarType &&
            it.parameterTypes.map(CharSequence::toString) == reelsCall.parameterTypes.map(CharSequence::toString)
    } ?: return skipLight("$reelsCall isn't in this build")
    if (localsOf(reelsBars) < 1) return skipLight("$reelsCall has no local register to borrow")
    val tabInterface = reelsCall.parameterTypes.firstOrNull()?.toString() ?: return skipLight("$reelsCall takes no tab")
    val darkReads = reelsInTabReads(videoHome, tabInterface)
    if (darkReads.isEmpty()) {
        return skipLight("expected one boolean of $tabInterface that $VIDEO_HOME_ROOT_FRAGMENT reads before painting the top bar and the status bar")
    }
    // Last, so a build missing the rest never matches the fingerprint.
    if (DarkModeFingerprint.methodOrNull == null) return skipLight("Facebook's dark mode answer isn't in this build")
    return LightReelsSites(picker, reelsTabBar, reelsTabBarRead, chromeBars, chromeRead, statusBarType, otherTabs, reelsBars, darkReads)
}

/** Has the id [method] reads at [read] go through [extensionMethod] (a (J)J) before the comparison. */
private fun BytecodePatchContext.hookTabIdRead(method: Method, read: Int, extensionMethod: String) {
    val tab = (method.body()[read] as OneRegisterInstruction).registerA
    mutableClassDefBy(method.definingClass).findMutableMethodOf(method).addInstructions(
        read + 1,
        """
            invoke-static/range { v$tab .. v${tab + 1} }, $LIGHT_REELS_TAB_BARS->$extensionMethod(J)J
            move-result-wide v$tab
        """,
    )
}

/** Hooks the five places the Reels tab turns its bars dark into [LIGHT_REELS_TAB_BARS], or none of them. */
internal fun BytecodePatchContext.hookLightReelsTabBars() {
    val sites = lightReelsSites() ?: return
    // The switch asks Facebook's own answer for dark mode.
    hookDarkModeAnswer()

    // The tab bar's colours.
    mutableClassDefBy(sites.picker.definingClass).findMutableMethodOf(sites.picker).addInstructions(
        0,
        """
            invoke-static { p2 }, $LIGHT_REELS_TAB_BARS->tabBarTab(Ljava/lang/Long;)Ljava/lang/Long;
            move-result-object p2
        """,
    )

    // The Video tab's answer, which the tab bar takes over the picker's.
    hookTabIdRead(sites.reelsTabBar, sites.reelsTabBarRead, "reelsTabBarTab")

    // The main screen's status bar.
    hookTabIdRead(sites.chromeBars, sites.chromeRead, "barsTab")

    // The Video tab's own status bar config. v0 is a local nothing has written yet.
    val otherTabs = sites.otherTabs
    mutableClassDefBy(sites.reelsBars.definingClass).findMutableMethodOf(sites.reelsBars).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $LIGHT_REELS_TAB_BARS->lightStatusBar()Z
                move-result v0
                if-eqz v0, :keep
                invoke-static { }, ${otherTabs.definingClass}->${otherTabs.name}()${sites.statusBarType}
                move-result-object v0
                return-object v0
            """,
            ExternalLabel("keep", getInstruction(0)),
        )
    }

    // The Video tab's dark status bar and top bar.
    sites.darkReads.groupBy { it.method }.forEach { (method, reads) ->
        val target = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
        reads.sortedByDescending { it.resultIndex }.forEach { site ->
            target.addInstructions(
                site.resultIndex + 1,
                """
                    invoke-static/range { v${site.register} .. v${site.register} }, $LIGHT_REELS_TAB_BARS->darkBars(Z)Z
                    move-result v${site.register}
                """,
            )
        }
    }
}
