/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Route one of the AMOLED and Material You themes as the patch runs it, on each declared build's
 * own colour classes: four resolvers and six returns (on 586 Mig's, FDSColors' one resolver the
 * others return, its static copy outside the class and the theme resolver), each hook reading the
 * colour token from a register that still holds it there. A build whose resolver reused the token's register before a
 * return would stop here, naming it. The status bar hook of the AMOLED theme is checked on the same
 * builds, with the path that paints a tab's bar again after Recent Apps.
 */
class ColourResolverFixtureTest {
    @Before
    @After
    fun forgetMatches() {
        DarkSchemeResolveFingerprint.clearMatch()
        LightSchemeResolveFingerprint.clearMatch()
        FdsSchemeResolveFingerprint.clearMatch()
        NavigationBarPainterFingerprint.clearMatch()
    }

    /** Builds that make the navigation bar's view in more than one method. */
    private val navigationBarMakers = mapOf("586.0.0.52.53" to 2, "587.0.0.39.55" to 2)

    /** The call the FdsColorScheme wrapper hands the context and token to: the view resolver. */
    private fun viewResolver(scheme: ClassDef): MethodReference = scheme.methods.mapNotNull { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val readsContext = instructions.any {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { field ->
                field.definingClass == FDS_COLOR_SCHEME && field.type == "Landroid/content/Context;"
            } == true
        }
        if (method.returnType != "I" || !readsContext) return@mapNotNull null
        instructions.firstNotNullOfOrNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                ?.takeIf { instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == "I" }
        }
    }.distinctBy { it.toString() }.single()

    /** The class the FdsColorScheme wrapper hands the context and token to: the view resolver's. */
    private fun viewResolverClass(scheme: ClassDef): String = viewResolver(scheme).definingClass

    /** The methods [method] calls that answer an int. */
    private fun intCalls(method: Method): List<MethodReference> = method.implementation!!.instructions
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { it.returnType == "I" }

    /**
     * Every class route one reads in [bundle]: the Mig dark scheme, FDSColors, the FdsColorScheme
     * wrapper, the view resolver's class, the class of the resolver the view resolver asks and the
     * classes of the [staticResolvers].
     */
    private fun routeOneClasses(bundle: File): Map<String, ClassDef> {
        val colors = fdsColorsOf(bundle)
        val named = FixtureDex.classes(bundle, setOf(DARK_COLOR_SCHEME, FDS_COLOR_SCHEME)) + (colors.type to colors)
        val view = viewResolver(named.getValue(FDS_COLOR_SCHEME))
        val viewClass = FixtureDex.classes(bundle, setOf(view.definingClass))
        val asked = intCalls(viewClass.getValue(view.definingClass).methods.single { it.descriptor() == view.toString() }).single()
        return named + viewClass + FixtureDex.classes(bundle, setOf(asked.definingClass)) +
            FixtureDex.classes(bundle, staticResolvers(bundle, colors).map { it.definingClass }.toSet())
    }

    /**
     * Every static int method outside FDSColors in [bundle] that asks its colour source itself (586
     * arm64 `LX/03A3;->A00`, which Compose's text fields call): a copy of its resolver.
     */
    private fun staticResolvers(bundle: File, colors: ClassDef): List<Method> {
        val source = fdsColourSource(colors)!!
        val descriptor = "${colors.type}->${source.name}(${source.parameterTypes.joinToString("")})${source.returnType}"
        return FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == descriptor } }) { method ->
            method.definingClass != colors.type && AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" &&
                method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == descriptor } == true
        }
    }

    @Test
    fun `route one hooks four resolvers and six returns, each token intact, on each declared build`() =
        routeOneIsHooked(mig = APPLY, fds = APPLY)

    /** The Accent color patch hooks the same seams with its own targets, on the same builds. */
    @Test
    fun `the accent color patch hooks route one on each declared build`() =
        routeOneIsHooked(mig = ACCENT_MIG, fds = ACCENT_FDS)

    private fun routeOneIsHooked(mig: String, fds: String) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetMatches()
                val classes = routeOneClasses(bundle)
                val context = PatchContexts.of(classes.values)

                with(context) { hookColourResolvers(mig = mig, fds = fds) }

                val hooked = classes.keys.flatMap { type -> context.mutableClassDefBy(type).methods }
                    .associate { method ->
                        "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})" to
                            (method.implementation?.instructions?.count {
                            (it as? ReferenceInstruction)?.reference?.toString() in setOf(mig, fds)
                        } ?: 0)
                    }
                    .filterValues { it > 0 }
                assertEquals("${bundle.name}: resolvers hooked, $hooked", 4, hooked.size)
                assertEquals("${bundle.name}: returns hooked, $hooked", 6, hooked.values.sum())

                // Every FDSColors resolver taking the token is hooked, or returns a hooked one's answer as it is.
                val colors = fdsColorsOf(bundle)
                val token = fdsColourSource(colors)!!.parameterTypes[1].toString()
                val signature = { method: Method -> "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})" }
                // And so is every static copy of it outside the class, which answers DSP colours route one didn't see (#141).
                val copies = staticResolvers(bundle, colors)
                if (version == "586.0.0.52.53") assertEquals("${bundle.name}: static copies of the resolver", 1, copies.size)
                copies.forEach { copy ->
                    assertTrue("${bundle.name}: ${signature(copy)} asks FDSColors' colour source and isn't hooked, $hooked", signature(copy) in hooked)
                }
                colors.methods.filter { method ->
                    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" &&
                        method.parameterTypes.any { it.toString() == token } && signature(method) !in hooked
                }.forEach { wrapper ->
                    val code = wrapper.implementation!!.instructions.toList()
                    val returned = code.indices.filter { code[it].opcode == Opcode.RETURN }
                    assertTrue("${bundle.name}: ${signature(wrapper)} returns", returned.isNotEmpty())
                    for (at in returned) {
                        val call = (code[at - 2] as? ReferenceInstruction)?.reference as? MethodReference
                        assertTrue("${bundle.name}: ${signature(wrapper)} returns something other than a hooked resolver's answer",
                            call != null && "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})" in hooked &&
                                code[at - 1].opcode == Opcode.MOVE_RESULT &&
                                (code[at - 1] as OneRegisterInstruction).registerA == (code[at] as OneRegisterInstruction).registerA)
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Issue #214's Mig screens: the Light background color's hook on Mig's light scheme. One resolver
     * and one return, reading its token from the parameter that still holds it, and that token is the
     * Mig colour interface the dark scheme's hook reads too, so the enum names AmoledTheme lists are
     * the ones it gets. The sibling that takes an Integer and asks the scheme again isn't hooked.
     */
    @Test
    fun `Mig's light scheme takes the light hook on one resolver and one return, its token intact, on each declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetMatches()
                val name = bundle.name
                val classes = FixtureDex.classes(bundle, setOf(DARK_COLOR_SCHEME, LIGHT_COLOR_SCHEME))
                val context = PatchContexts.of(classes.values)

                val darkToken = with(context) {
                    hookLightSchemeResolver()
                    DarkSchemeResolveFingerprint.method.parameterTypes.single().toString()
                }

                val hooked = context.mutableClassDefBy(LIGHT_COLOR_SCHEME).methods.filter { it.calls(APPLY_LIGHT) }
                assertEquals("$name: light scheme methods hooked, ${hooked.map { it.descriptor() }}", 1, hooked.size)
                val resolver = hooked.single()
                assertFalse("$name: the light resolver is static", AccessFlags.STATIC.isSet(resolver.accessFlags))
                assertEquals("$name: the light resolver's token", darkToken, resolver.parameterTypes.single().toString())
                val body = resolver.implementation!!
                val calls = body.instructions.filter { it.calls(APPLY_LIGHT) }
                assertEquals("$name: returns hooked", 1, calls.size)
                assertEquals("$name: the hook reads the token from its parameter", body.registerCount - 1,
                    (calls.single() as FiveRegisterInstruction).registerD)
                assertTrue("$name: the dark scheme takes no light hook",
                    context.mutableClassDefBy(DARK_COLOR_SCHEME).methods.none { it.calls(APPLY_LIGHT) })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private val systemBarsController ="Lcom/facebook/navigation/statusbar/controller/SystemBarsController;"

    private fun Method.calls(descriptor: String) = implementation?.instructions?.any {
        (it as? ReferenceInstruction)?.reference?.toString() == descriptor
    } == true

    private fun Method.descriptor() =
        "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    /**
     * Issue #22. Back from Recent Apps, SystemBarsController applies the tab's bar config again. A
     * config holding only a token gets its colour from an FDS resolver none of route one's hooks
     * reach (the Video tab's CARD_BACKGROUND_DARK is #333334), and the controller hands it to
     * StatusBarUtil's painter, which remembers it per window. The hook sits first in that painter,
     * so the colour painted and the colour remembered are both the extension's answer.
     */
    @Test
    fun `the status bar painter takes the hook on the path that reapplies a tab's bars, on each declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetMatches()
                val name = bundle.name
                val named = FixtureDex.classes(bundle, setOf(FDS_COLOR_SCHEME, STATUS_BAR_UTIL, systemBarsController))
                val viewResolver = viewResolverClass(named.getValue(FDS_COLOR_SCHEME))
                val classes = named + FixtureDex.classes(bundle, setOf(viewResolver))
                val context = PatchContexts.of(classes.values)

                val (darkCheck, resolver) = with(context) {
                    val check = fdsDarkCheck()
                    hookStatusBarColour(check)
                    check to fdsViewResolver().descriptor()
                }

                val hooked = context.mutableClassDefBy(STATUS_BAR_UTIL).methods.filter { it.calls(STATUS_BAR) }
                assertEquals("$name: StatusBarUtil methods hooked", 1, hooked.size)
                val painter = hooked.single()
                val original = classes.getValue(STATUS_BAR_UTIL).methods.single { it.descriptor() == painter.descriptor() }
                assertStatusBarHook(name, painter, darkCheck, original.implementation!!.instructions.toList())

                // The dark check is FDS's own: the question its colour picker asks before it takes a
                // colour's darkThemeColor over its lightThemeColor.
                val owner = classes.getValue(viewResolver)
                val question = owner.methods.single { it.descriptor() == darkCheck }.implementation!!.instructions
                    .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                    .single { it.returnType == "Z" }.toString()
                val picker = owner.methods.single { method ->
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == "darkThemeColor"
                    } == true
                }
                assertTrue("$name: the dark check asks $question, the darkThemeColor picker doesn't", picker.calls(question))

                // The path back from Recent Apps: the controller's apply step, which skips a config
                // "same as current for window", paints through the hooked painter and asks the same
                // dark check for the bar's icons, but no route-one resolver for the colour.
                val apply = classes.getValue(systemBarsController).methods.single {
                    holdsString(it, "applyConfig: skipped (same as current for window)")
                }
                assertTrue("$name: the controller doesn't paint through the hooked painter", apply.calls(painter.descriptor()))
                assertTrue("$name: the controller doesn't ask $darkCheck", apply.calls(darkCheck))
                assertFalse("$name: the controller now asks the hooked view resolver", apply.calls(resolver))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Material You's status bar on the same painter: its own hook when AMOLED isn't in the build,
     * and in place of AMOLED's call when it is, so the bar's colour goes through one hook either way.
     */
    @Test
    fun `the Material You status bar hook is first in the painter, with AMOLED or without, on each declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val named = FixtureDex.classes(bundle, setOf(FDS_COLOR_SCHEME, STATUS_BAR_UTIL))
                val viewResolver = viewResolverClass(named.getValue(FDS_COLOR_SCHEME))
                val classes = named + FixtureDex.classes(bundle, setOf(viewResolver))
                for (amoled in listOf(false, true)) {
                    forgetMatches()
                    val name = "${bundle.name}${if (amoled) " after AMOLED" else ""}"
                    val context = PatchContexts.of(classes.values)
                    val darkCheck = with(context) {
                        val check = fdsDarkCheck()
                        if (amoled) hookStatusBarColour(check)
                        hookMaterialYouStatusBar(check)
                        check
                    }

                    val hooked = context.mutableClassDefBy(STATUS_BAR_UTIL).methods
                        .filter { it.calls(STATUS_BAR_YOU) || it.calls(STATUS_BAR) }
                    assertEquals("$name: StatusBarUtil methods hooked", 1, hooked.size)
                    val painter = hooked.single()
                    val original = classes.getValue(STATUS_BAR_UTIL).methods.single { it.descriptor() == painter.descriptor() }
                    assertStatusBarHook(name, painter, darkCheck, original.implementation!!.instructions.toList(), STATUS_BAR_YOU)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Instruction.calls(descriptor: String) = (this as? ReferenceInstruction)?.reference?.toString() == descriptor

    /** The literal in [register] at instruction [at]: what the last instruction before it that wrote the register loaded. */
    private fun literalAt(body: List<Instruction>, register: Int, at: Int): Int? = (at - 1 downTo 0).map { body[it] }
        .firstOrNull { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register }
        .let { (it as? NarrowLiteralInstruction)?.narrowLiteral }

    /**
     * The navigation bar on Android 15 and newer. Facebook 577 and 580 draw it as a view of their own
     * in the content frame, and SystemNavigationBarUtil.setNavigationBarColor (the one static
     * (Activity, Window, int) method that calls setNavigationBarColor) repaints that view by its id
     * when a tab asks. The colour the view is made with, NAV_BAR_BACKGROUND's, stays until then. The
     * method that makes it asks the resolver behind the view code's own (580) or a static that just
     * returns that resolver's answer (577), so route one, as both themes run it, has to reach that
     * resolver for the bar to match the page from the start.
     */
    @Test
    fun `the navigation bar's view is made with a colour route one reaches, on each declared build`() {
        val setNavigationBarColor = "Landroid/view/Window;->setNavigationBarColor(I)V"
        val findViewById = "Landroid/view/Window;->findViewById(I)Landroid/view/View;"
        val setId = "Landroid/view/View;->setId(I)V"
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetMatches()
                val name = bundle.name
                val painters = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == setNavigationBarColor } }) {
                    it.isNavigationPainter()
                }
                assertEquals("$name: SystemNavigationBarUtil painters, ${painters.map { it.descriptor() }}", 1, painters.size)
                val painted = painters.single().implementation!!.instructions.toList()
                val lookup = painted.indexOfFirst { it.calls(findViewById) }
                assertTrue("$name: the painter looks up no view", lookup >= 0)
                val id = checkNotNull(literalAt(painted, (painted[lookup] as FiveRegisterInstruction).registerD, lookup)) {
                    "$name: the painter looks its view up by no literal id"
                }

                val makers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == setId } }) { method ->
                    val body = method.implementation?.instructions?.toList() ?: return@methodsWhere false
                    body.indices.any { at ->
                        val call = body[at] as? FiveRegisterInstruction
                        call != null && body[at].calls(setId) && literalAt(body, call.registerD, at) == id
                    }
                }
                // 586 makes it in two places (arm64 LX/02Pp;->A03, which takes Boolean overrides, and A04), each with its own colour call.
                // 587 too (arm64 LX/02Rz;->A03 and A04).
                // Its Android 9 build makes it in one (LX/02Qb;->A03).
                val android9 = name.substringAfter("-$version-").contains("-minapi")
                assertEquals("$name: methods giving a view the navigation bar's id, ${makers.map { it.descriptor() }}",
                    if (android9) 1 else navigationBarMakers[version] ?: 1, makers.size)
                val askedByMaker = makers.associate { maker ->
                    maker.descriptor() to intCalls(maker).single { it.parameterTypes.firstOrNull()?.toString() == "Landroid/content/Context;" }
                }

                val routeOne = routeOneClasses(bundle)
                val classes = routeOne + FixtureDex.classes(bundle, askedByMaker.values.map { it.definingClass }.toSet() - routeOne.keys)
                val context = PatchContexts.of(classes.values)
                with(context) { hookColourResolvers(mig = APPLY, fds = APPLY) }
                val hooked = classes.keys.flatMap { context.mutableClassDefBy(it).methods }
                    .filter { it.calls(APPLY) }.map { it.descriptor() }.toSet()

                // Each view's colour comes from a hooked resolver, or from a static in front of one
                // that asks one resolver and returns its answer as it is.
                for ((maker, asked) in askedByMaker) {
                    val front = classes.getValue(asked.definingClass).methods.single { it.descriptor() == asked.toString() }
                    val passesOn = AccessFlags.STATIC.isSet(front.accessFlags) && intCalls(front).size == 1 &&
                        front.implementation!!.instructions.toList().takeLast(2).map { it.opcode } == listOf(Opcode.MOVE_RESULT, Opcode.RETURN)
                    val answered = if (asked.toString() !in hooked && passesOn) intCalls(front).single().toString() else asked.toString()
                    assertTrue("$name: $maker makes the navigation bar's view with $answered, which route one doesn't reach ($hooked)",
                        answered in hooked)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The navigation bar's painter takes the bar hook on each declared build, on its window and its
     * colour: AMOLED's, Material You's, and Material You's in place of AMOLED's when both are in.
     */
    @Test
    fun `the navigation bar painter takes the bar hook, with AMOLED, Material You or both, on each declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val painter = navigationPainterOf(bundle)
                val named = FixtureDex.classes(bundle, setOf(FDS_COLOR_SCHEME, painter.definingClass))
                val classes = named + FixtureDex.classes(bundle, setOf(viewResolverClass(named.getValue(FDS_COLOR_SCHEME))))
                for ((amoled, you) in listOf(true to false, false to true, true to true)) {
                    forgetMatches()
                    val name = "${bundle.name}, AMOLED $amoled, Material You $you"
                    val context = PatchContexts.of(classes.values)
                    val darkCheck = with(context) {
                        val check = fdsDarkCheck()
                        if (amoled) hookNavigationBarColour(check)
                        if (you) hookMaterialYouNavigationBar(check)
                        check
                    }
                    val hooked = context.mutableClassDefBy(painter.definingClass).methods.single { it.descriptor() == painter.descriptor() }
                    assertStatusBarHook(name, hooked, darkCheck, painter.implementation!!.instructions.toList(),
                        if (you) NAVIGATION_BAR_YOU else NAVIGATION_BAR, 1, painter.parameterTypes.size - 1, setOf(NAVIGATION_BAR, NAVIGATION_BAR_YOU))
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Light mode on the Video tab. That tab keeps a dark surface in both themes, and Facebook writes
     * its bars' #252728 into code: in the tab's SystemBarsController config and in the calls that
     * paint its status bar and its navigation bar, or from 585 in a static helper whose answer the
     * tab hands to the status bar's painter. Route three, as AMOLED, Material You or both run
     * it, leaves those for the bar hooks, which ask Facebook's theme, and still rewrites every other
     * #252728 in the same classes.
     */
    @Test
    fun `the Video tab's bar colour reaches the bar painters as Facebook wrote it, on each declared build`() {
        val videoGrey = -0xdad8d8
        fun Method.writesVideoGrey() = implementation?.instructions?.count {
            it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction).narrowLiteral == videoGrey
        } ?: 0

        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val navigation = navigationPainterOf(bundle)
                val named = FixtureDex.classes(bundle, setOf(STATUS_BAR_UTIL, SYSTEM_BARS_CONTROLLER, navigation.definingClass))
                val status = named.getValue(STATUS_BAR_UTIL).methods.single {
                    AccessFlags.STATIC.isSet(it.accessFlags) && it.calls(SET_STATUS_BAR_COLOR)
                }.descriptor()
                val config = named.getValue(SYSTEM_BARS_CONTROLLER).methods.single {
                    holdsString(it, "applyConfig: skipped (same as current for window)")
                }.parameterTypes[1].toString()
                val painters = named.getValue(navigation.definingClass).methods.map { it.descriptor() }
                    .filter { it.endsWith("I)V") }.toSet() + status

                val handing = FixtureDex.methodsWhere(bundle, { dex ->
                    dex.methodSection.any { it.toString() in painters || it.definingClass == config && it.name == "<init>" }
                }) { method ->
                    method.implementation?.instructions?.any {
                        val reference = (it as? ReferenceInstruction)?.reference
                        reference.toString() in painters || it.opcode == Opcode.NEW_INSTANCE && reference.toString() == config
                    } == true
                }
                // 585 keeps the Video tab's grey in a static int helper whose answer the tab hands to the status bar's painter.
                val helperCalls = handing.associateWith { barColourHelpers(it, painters) }.filterValues { it.isNotEmpty() }
                val helpers = helperCalls.values.flatten().toSet()
                val helperMethods = if (helpers.isEmpty()) emptyList() else FixtureDex.methodsWhere(bundle, { dex ->
                    dex.methodSection.any { it.toString() in helpers }
                }) { it.descriptor() in helpers }
                val bars = (handing + helperMethods).filter { it.writesVideoGrey() > 0 }.distinctBy { it.descriptor() }
                val barNames = bars.map { it.descriptor() }.toSet()
                assertTrue("$name: Facebook writes the Video tab's bar colour in $barNames", bars.size >= 3)
                assertTrue("$name: FbChromeFragment's bar config isn't in $barNames",
                    bars.any { it.definingClass == "Lcom/facebook/katana/fragment/FbChromeFragment;" && it.name == "getSystemBarsConfig" })
                val videoTab = "Lcom/facebook/video/videohome/fragment/VideoHomeRootFragment;"
                // 587 builds the Video tab's status bar outside the fragment (arm64 `LX/09jn;->A02`, asked
                // through a lambda), and the grey's helper there (`LX/09jn;->A00`) is one the fragment
                // calls itself for its top bar (`A0A(Z)V`): a bar helper the Video tab calls is the tab's.
                val videoTabCalls = FixtureDex.classes(bundle, setOf(videoTab)).getValue(videoTab).methods.flatMap { method ->
                    method.implementation?.instructions?.mapNotNull {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString()
                    }.orEmpty()
                }.toSet()
                val tabHelpers = helperCalls.filterKeys { it.definingClass == videoTab }.values.flatten().toSet() +
                    (helpers intersect videoTabCalls)
                assertTrue("$name: no Video tab painter in $barNames",
                    bars.any { it.definingClass == videoTab || it.descriptor() in tabHelpers })
                // A helper is left out of the sweep for every caller it has, so one holding a dark grey
                // keeps Facebook's colour wherever else it's called. Only the Video tab's grey may.
                val keepsGrey = helperMethods.filter { it.hasDarkColor() }.map { it.descriptor() }.toSet()
                val videoGreyHelpers = helperMethods.filter { it.descriptor() in tabHelpers && it.writesVideoGrey() > 0 }
                    .map { it.descriptor() }.toSet()
                assertEquals("$name: bar colour helpers holding a dark grey", videoGreyHelpers, keepsGrey)

                // A helper's callers come along, so the sweep sees the painter its answer goes to.
                val callers = helperCalls.filterValues { calls -> calls.any { it in barNames } }.keys.map { it.definingClass }
                val classes = named + FixtureDex.classes(bundle, (bars.map { it.definingClass } + callers).toSet() - named.keys) +
                    (MATERIAL_YOU to ExtensionDex.classDef(MATERIAL_YOU))
                for ((amoled, you) in listOf(true to false, false to true, true to true)) {
                    forgetMatches()
                    val themes = "$name, AMOLED $amoled, Material You $you"
                    val context = PatchContexts.of(classes.values)
                    with(context) {
                        if (amoled) blackenColourLiterals()
                        if (you) readSurfaceLiterals()
                    }
                    for (type in classes.keys - MATERIAL_YOU) {
                        for (method in context.mutableClassDefBy(type).methods) {
                            val bar = bars.singleOrNull { it.descriptor() == method.descriptor() }
                            assertEquals("$themes: ${method.descriptor()} writes #252728", bar?.writesVideoGrey() ?: 0, method.writesVideoGrey())
                        }
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

/** The one static (Activity, Window, int) method of [bundle] that calls setNavigationBarColor: the navigation bar's painter. */
internal fun navigationPainterOf(bundle: File): Method {
    val setNavigationBarColor = "Landroid/view/Window;->setNavigationBarColor(I)V"
    return FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == setNavigationBarColor } }) {
        it.isNavigationPainter()
    }.single()
}

/**
 * A static (Activity, Window, int) method that calls setNavigationBarColor. 584 (LX/2LF;->A00)
 * also takes its own class as the third parameter, so it is (Activity, Window, LX/2LF, int).
 */
private fun Method.isNavigationPainter(): Boolean {
    val parameters = parameterTypes.map(CharSequence::toString)
    return AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
        parameters.size in 3..4 && parameters[0] == "Landroid/app/Activity;" && parameters[1] == "Landroid/view/Window;" &&
        parameters.last() == "I" && (parameters.size == 3 || parameters[2] == definingClass) &&
        implementation?.instructions?.any {
            (it as? ReferenceInstruction)?.reference?.toString() == "Landroid/view/Window;->setNavigationBarColor(I)V"
        } == true
}
