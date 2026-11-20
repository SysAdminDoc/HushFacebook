/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

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
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.DataType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Light mode's Video tab, on each declared build. The tab stays dark with Facebook's dark mode off:
 * its bottom bar is `Context.getColor` of Facebook's #252728, a colour resource only the default
 * configuration defines and that FDS's dark style points at too. AMOLED's route two writes that
 * resource black, and Material You never saw it, so the fix needs two things of the patch: the bar's
 * read goes through the extension, and the extension has Facebook's own answer for dark mode. In dark
 * mode the same read colours the navigation area on Android 15 and newer, for every tab.
 */
class VideoTabColourFixtureTest {
    @Before
    @After
    fun forgetMatches() {
        DarkModeFingerprint.clearMatch()
        FdsSchemeResolveFingerprint.clearMatch()
    }

    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /**
     * The dark mode controller's answer (580 `LX/1QV;->A05`, 577 `LX/1L7;->A05`): each return hands
     * the extension the register it returns and returns what the extension answers. A second theme
     * in the build finds the hook in and adds none.
     */
    @Test
    fun `Facebook's dark mode answer goes through the extension at each return, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                forgetMatches()
                val controller = FixtureDex.classesHolding(bundle, "fb.e2e.enable_dark_mode").single()
                val answer = controller.methods.single { holdsString(it, "fb.e2e.enable_dark_mode") }
                assertTrue("${bundle.name}: the answer reads the setting's state", answer.body().any {
                    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.type == THEME_PREFERENCES_STATE
                })
                val returns = answer.body().count { it.opcode == Opcode.RETURN }
                assertTrue("${bundle.name}: ${answer.name} has $returns returns", returns >= 3)

                val context = PatchContexts.of(listOf(controller))
                with(context) {
                    hookDarkModeAnswer()
                    hookDarkModeAnswer()
                }
                val hooked = context.mutableClassDefBy(controller.type).methods.single {
                    it.name == answer.name && it.returnType == "Z" && it.parameterTypes.isEmpty()
                }.body()
                assertEquals("${bundle.name}: one hook per return", returns, hooked.count { it.reference() == DARK_MODE_ANSWER })
                hooked.withIndex().filter { it.value.opcode == Opcode.RETURN }.forEach { (at, instruction) ->
                    val register = (instruction as OneRegisterInstruction).registerA
                    val call = hooked[at - 2] as FiveRegisterInstruction
                    assertEquals("${bundle.name}: before the return at $at", DARK_MODE_ANSWER, hooked[at - 2].reference())
                    assertEquals("${bundle.name}: the call gets the returned register", listOf(register), listOf(call.registerC))
                    assertEquals("${bundle.name}: and returns its answer", Opcode.MOVE_RESULT, hooked[at - 1].opcode)
                    assertEquals(register, (hooked[at - 1] as OneRegisterInstruction).registerA)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The Video tab's id among the tabs, which Facebook's tab bar picks the tab's colours by. */
    private val videoTab = 0x8ea18579L

    /**
     * The tab bar's colours for the Video tab: the class the tab bar picker (580 `LX/287;->A00`, 577
     * `LX/2CP;->A00`) makes when the tab id is the Video tab's (580 `LX/4KA`, 577 `LX/4Bb`).
     */
    private fun videoTabColours(bundle: File): String {
        val pickers = FixtureDex.methodsWhere(bundle, { true }) { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.parameterTypes.map(CharSequence::toString) ==
                listOf("Landroid/content/Context;", "Lcom/facebook/auth/usersession/FbUserSession;", "Ljava/lang/Long;") &&
                method.body().any { (it as? WideLiteralInstruction)?.wideLiteral == videoTab }
        }
        val picker = pickers.single().body()
        val video = picker.indexOfFirst { (it as? WideLiteralInstruction)?.wideLiteral == videoTab }
        val made = picker.drop(video).filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        assertEquals("the picker makes the Video tab's colours or everyone else's", 2, made.distinct().size)
        return made.first()
    }

    /**
     * The Video tab's colours and the class above them, the tab bar's colour provider. 582's Redex
     * moved the Video tab class's methods up into the provider (582 `LX/4RM;` into `LX/5wY;`, whose
     * bodies cast themselves back), so its reads are found in either. 581's provider only declares
     * them.
     */
    private fun videoTabColourClasses(bundle: File, type: String): List<ClassDef> {
        val colours = FixtureDex.classes(bundle, setOf(type)).getValue(type)
        val provider = colours.superclass?.let { FixtureDex.classes(bundle, setOf(it))[it] }
        return listOfNotNull(colours, provider)
    }

    /** Each colour resource [method] reads with `Context.getColor`, from the literal id it loads. */
    private fun resourceReads(method: Method): Set<Int> {
        val body = method.body()
        if (body.none { it.reference() == CONTEXT_GET_COLOR }) return emptySet()
        return body.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }
            .filter { it ushr 16 == 0x7f06 }
            .toSet()
    }

    /** The values of resource [id] in [apk]'s table, in each configuration that defines it. */
    private fun colourValues(table: TableBlock, id: Int): List<Pair<Boolean, Int?>> {
        for (block in table.listPackages()) {
            for (pair in block.listSpecTypePairs()) {
                for (resource in pair.resources) {
                    if (resource == null || resource.resourceId != id) continue
                    return resource.iterator().asSequence().filter { it != null && !it.isNull }.map { entry ->
                        val value = entry.resValue
                        val colour = if (value.dataType in setOf(DataType.COLOR_ARGB8, DataType.COLOR_RGB8)) value.data else null
                        entry.resConfig.isDefault to colour
                    }.toList()
                }
            }
        }
        return emptyList()
    }

    private fun table(bundle: File): TableBlock = ZipFile(bundle).use { zip ->
        ZipInputStream(zip.getInputStream(zip.getEntry("base.apk")).buffered()).use { apk ->
            generateSequence { apk.nextEntry }.first { it.name == TableBlock.FILE_NAME }
            TableBlock.load(apk.readBytes().inputStream())
        }
    }

    /**
     * The bottom bar: the Video tab's colours read Facebook's #252728 with `Context.getColor`, from a
     * resource only the default configuration defines, which route two writes black and the
     * extension can give back in light mode. Every such read in the class goes to AMOLED's stand-in
     * on the same registers, then to Material You's when both are in the build, or straight to
     * Material You's without AMOLED.
     */
    @Test
    fun `the Video tab's bottom bar reads its colour through the extension, on each declared build`() {
        val videoGrey = -0xdad8d8
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val type = videoTabColours(bundle)
                val classes = videoTabColourClasses(bundle, type)
                val reads = classes.flatMap { it.methods }.flatMap { resourceReads(it) }.toSet()
                val resources = table(bundle)
                val bar = reads.filter { id -> colourValues(resources, id) == listOf(true to videoGrey) }
                assertEquals("$name: the bar's #252728, defined in the default configuration only, among " +
                    reads.joinToString { Integer.toHexString(it) }, 1, bar.size)

                val direct = classes.flatMap { it.methods }.sumOf { method -> method.body().count { it.reference() == CONTEXT_GET_COLOR } }
                for ((amoled, you) in listOf(true to false, false to true, true to true)) {
                    val themes = "$name, AMOLED $amoled, Material You $you"
                    val context = PatchContexts.of(classes)
                    val sent = with(context) {
                        val byAmoled = if (amoled) rerouteColourCalls(AMOLED_COLOUR_CALLS).getValue(CONTEXT_GET_COLOR) else 0
                        val byYou = if (you) rerouteColourCalls(YOU_COLOUR_CALLS).let {
                            it.getValue(CONTEXT_GET_COLOR) + it.getValue(AMOLED_COLOUR_CALLS.getValue(CONTEXT_GET_COLOR))
                        } else 0
                        listOf(byAmoled, byYou).filter { it > 0 }
                    }
                    val stand = if (you) standIn(MATERIAL_YOU, CONTEXT_GET_COLOR) else AMOLED_COLOUR_CALLS.getValue(CONTEXT_GET_COLOR)
                    for (colours in classes) for (method in context.mutableClassDefBy(colours.type).methods) {
                        val before = colours.methods.single {
                            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
                        }.body()
                        val after = method.body()
                        assertEquals("$themes: ${method.name} keeps its length", before.size, after.size)
                        for ((at, instruction) in before.withIndex()) {
                            if (instruction.reference() != CONTEXT_GET_COLOR) continue
                            assertEquals("$themes: ${method.name} at $at", stand, after[at].reference())
                            assertEquals("$themes: ${method.name} at $at, static", Opcode.INVOKE_STATIC, after[at].opcode)
                            val framework = instruction as FiveRegisterInstruction
                            val extension = after[at] as FiveRegisterInstruction
                            assertEquals("$themes: ${method.name} at $at, registers",
                                listOf(framework.registerC, framework.registerD), listOf(extension.registerC, extension.registerD))
                        }
                    }
                    assertEquals("$themes: every Context.getColor, by each theme in the build",
                        listOf(amoled, you).count { it }.let { themesIn -> List(themesIn) { direct } }, sent)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private val setNavigationBarColor = "Landroid/view/Window;->setNavigationBarColor(I)V"

    /** FDS's dark check, found the way the patch finds it: on the class of the view resolver FdsColorScheme asks. */
    private fun darkCheck(bundle: File): String {
        val scheme = FixtureDex.classes(bundle, setOf(FDS_COLOR_SCHEME)).values
        FdsSchemeResolveFingerprint.clearMatch()
        val resolver = with(PatchContexts.of(scheme)) {
            ((FdsSchemeResolveFingerprint.instructionMatches[1].instruction as ReferenceInstruction).reference as MethodReference)
                .definingClass
        }
        FdsSchemeResolveFingerprint.clearMatch()
        return with(PatchContexts.of(scheme + FixtureDex.classes(bundle, setOf(resolver)).values)) { fdsDarkCheck() }
    }

    /**
     * The navigation area on Android 15 and newer. Facebook targets SDK 35 and up, so there the system
     * draws the window's navigation bar colour at 80% over the app's own bottom edge, which is
     * Facebook's navigation bar view (NAV_BAR_BACKGROUND, route one), whenever three-button
     * navigation keeps its contrast scrim. In dark mode the tab bar sets that colour for every tab
     * (580 `LX/268;->A0F`, 577 `LX/29f;->A0F`) straight from the Video tab's colours: the bar's
     * #252728 read, which the test above sends through the extension. That read is how the strip
     * takes AMOLED's black and Material You's palette. The painter hook never sees it, since the tab
     * bar calls the framework itself.
     */
    @Test
    fun `the tab bar paints the navigation bar with the Video tab's bar colour, on each declared build`() {
        val videoGrey = -0xdad8d8
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val type = videoTabColours(bundle)
                val classes = videoTabColourClasses(bundle, type)
                val colours = classes.first()
                val resources = table(bundle)
                val barReads = classes.flatMap { it.methods }.filter { method ->
                    method.parameterTypes.isEmpty() && method.returnType == "I" &&
                        resourceReads(method).any { colourValues(resources, it) == listOf(true to videoGrey) }
                }.map { it.name }.toSet()
                assertEquals("$name: the Video tab's colours read the bar's #252728 in one method", 1, barReads.size)
                val owners = setOfNotNull(type, colours.superclass)
                // 583 calls the framework's setNavigationBarColor with the read. 584 hands the read to
                // a static (Window, int) method of the navigation bar painter's class (LX/2LF;->A02),
                // which reaches the painter, so the bar hook sees this colour there.
                val paintsBar = navigationPainterOf(bundle).definingClass

                val painters = FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.typeSection.any { it == type } }) { method ->
                    val body = method.body()
                    body.any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == type } &&
                        body.withIndex().any { (at, instruction) ->
                            val painted = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            if (painted == null || at < 2 || instruction !is FiveRegisterInstruction) return@any false
                            if (painted.toString() != setNavigationBarColor && !(instruction.opcode == Opcode.INVOKE_STATIC &&
                                    painted.definingClass == paintsBar && painted.returnType == "V" &&
                                    painted.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/Window;", "I"))) {
                                return@any false
                            }
                            val colour = instruction.registerD
                            val result = body[at - 1]
                            val read = (body[at - 2] as? ReferenceInstruction)?.reference as? MethodReference
                            result.opcode == Opcode.MOVE_RESULT && (result as OneRegisterInstruction).registerA == colour &&
                                read != null && read.definingClass in owners && read.name in barReads &&
                                read.parameterTypes.isEmpty() && read.returnType == "I"
                        }
                }
                assertEquals("$name: the tab bar's navigation bar colour from the Video tab's bar read", 1, painters.size)

                val check = darkCheck(bundle)
                val painter = painters.single()
                // 588's Android 9 build drops the painter's own ask with its plain colours' route, and
                // asks in the tab bar's own repaint (`LX/02KS;->A0G`), which the painter calls last.
                val ownCalls = painter.body().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                    .filter { it.definingClass == painter.definingClass }.map { it.toString() }.toSet()
                val own = FixtureDex.classes(bundle, setOf(painter.definingClass)).values.single().methods
                    .filter { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" in ownCalls }
                assertTrue("$name: the tab bar asks FDS's dark check $check where it picks that colour",
                    painter.body().any { it.reference() == check } || own.any { method -> method.body().any { it.reference() == check } })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
