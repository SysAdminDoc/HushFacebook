/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.layout.theme.DARK_MODE_ANSWER
import app.morphe.patches.facebook.layout.theme.DarkModeFingerprint
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Light bars on the Reels tab (#423) on every Facebook build the bundle declares: the tab bar's
 * colour picker, the Video tab's own answer to the tab bar, the main screen's status bar config and its read of the selected tab's id, the
 * Video tab's status bar helper and the reads of its reels-in-tab answer before a status bar and a
 * top bar paint. Then the hooks on those classes. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class LightReelsTabBarsFixtureTest {
    @Before
    @After
    fun forgetMatches() {
        DarkModeFingerprint.clearMatch()
    }

    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    /** The classes the hooks read: the picker's, both fragments, the Video tab's helper and the dark mode controller. */
    private fun classesFor(bundle: File): List<ClassDef> {
        val pickers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == "Lcom/facebook/auth/usersession/FbUserSession;" } }) {
            isTabBarPicker(it)
        }
        assertEquals("${bundle.name}: tab bar colour pickers", 1, pickers.size)
        val fragments = FixtureDex.classes(bundle, setOf(pickers.single().definingClass, FB_CHROME_FRAGMENT, VIDEO_HOME_ROOT_FRAGMENT))
        val videoBars = fragments.getValue(VIDEO_HOME_ROOT_FRAGMENT).methods.single { it.name == SYSTEM_BARS_CONFIG && it.parameterTypes.size == 1 }
        val helpers = videoBars.body().mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass }
            .filter { it.startsWith("LX/") }.toSet()
        val controller = FixtureDex.classesHolding(bundle, "fb.e2e.enable_dark_mode").single()
        return (fragments + FixtureDex.classes(bundle, helpers)).values.distinctBy { it.type } + controller
    }

    @Test
    fun `each declared build has every site, and each hook lands where it should`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                forgetMatches()
                val name = bundle.name
                val context = PatchContexts.of(classesFor(bundle))
                val sites = with(context) { lightReelsSites() }
                assertNotNull("$name: Light bars on the Reels tab found everything", sites)
                sites!!

                val chromeBody = sites.chromeBars.body()
                assertEquals("$name: the id read is compared with the Video tab's", VIDEO_TAB_ID,
                    (chromeBody[sites.chromeRead + 1] as WideLiteralInstruction).wideLiteral)
                assertTrue("$name: the other tabs' status bar is a no-arg static",
                    sites.otherTabs.parameterTypes.isEmpty() && sites.otherTabs.returnType == sites.statusBarType)
                assertEquals("$name: the Video tab's helper answers the status bar", sites.statusBarType, sites.reelsBars.returnType)
                assertEquals("$name: one reels-in-tab answer", 1, sites.darkReads.map { it.name }.distinct().size)
                assertEquals("$name: its reads paint both bars", DarkPaint.values().toSet(), sites.darkReads.map { it.paints }.toSet())
                assertTrue("$name: every read sits in the Video tab",
                    sites.darkReads.all { it.method.definingClass == VIDEO_HOME_ROOT_FRAGMENT })

                with(context) {
                    hookLightReelsTabBars()
                }
                fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single { it.sameAs(method) }.body()

                // The picker hands the extension its id first thing, and keeps the answer.
                val picker = patched(sites.picker)
                assertEquals("$name: the picker asks first", "$LIGHT_REELS_TAB_BARS->tabBarTab(Ljava/lang/Long;)Ljava/lang/Long;",
                    picker[0].reference())
                val pickerRegisters = sites.picker.implementation!!.registerCount
                assertEquals("$name: the picker hands over p2", pickerRegisters - 1, (picker[0] as FiveRegisterInstruction).registerC)
                assertEquals("$name: and keeps the answer in p2", pickerRegisters - 1, (picker[1] as OneRegisterInstruction).registerA)

                // The Video tab's answer to the tab bar reads its id through the extension before the comparison.
                assertTrue("$name: the Video tab's answer to the tab bar is its own", sites.reelsTabBar.definingClass == VIDEO_HOME_ROOT_FRAGMENT)
                val answer = patched(sites.reelsTabBar)
                val answerTab = (sites.reelsTabBar.body()[sites.reelsTabBarRead] as OneRegisterInstruction).registerA
                val asked = answer[sites.reelsTabBarRead + 1]
                assertEquals("$name: the answer's id goes through the extension", "$LIGHT_REELS_TAB_BARS->reelsTabBarTab(J)J", asked.reference())
                assertEquals("$name: both halves of the answer's id", answerTab to 2,
                    (asked as RegisterRangeInstruction).startRegister to asked.registerCount)
                assertEquals("$name: the answer's id lands back", Opcode.MOVE_RESULT_WIDE, answer[sites.reelsTabBarRead + 2].opcode)
                assertEquals("$name: the answer's comparison follows", VIDEO_TAB_ID,
                    (answer[sites.reelsTabBarRead + 3] as WideLiteralInstruction).wideLiteral)

                // The main screen's id read goes through the extension before the comparison.
                val chrome = patched(sites.chromeBars)
                val tab = (chromeBody[sites.chromeRead] as OneRegisterInstruction).registerA
                val call = chrome[sites.chromeRead + 1]
                assertEquals("$name: the id goes through the extension", "$LIGHT_REELS_TAB_BARS->barsTab(J)J", call.reference())
                assertEquals("$name: both halves of the id", tab to 2,
                    (call as RegisterRangeInstruction).startRegister to call.registerCount)
                assertEquals("$name: the answer lands back", Opcode.MOVE_RESULT_WIDE, chrome[sites.chromeRead + 2].opcode)
                assertEquals("$name: the comparison follows", VIDEO_TAB_ID, (chrome[sites.chromeRead + 3] as WideLiteralInstruction).wideLiteral)

                // The Video tab's status bar helper hands back the other tabs' status bar while the switch says so.
                val helper = patched(sites.reelsBars)
                assertEquals("$name: the helper asks first", "$LIGHT_REELS_TAB_BARS->lightStatusBar()Z", helper[0].reference())
                assertEquals("$name: then the other tabs' status bar",
                    "${sites.otherTabs.definingClass}->${sites.otherTabs.name}()${sites.statusBarType}", helper[3].reference())
                assertEquals("$name: which it returns", Opcode.RETURN_OBJECT, helper[5].opcode)
                assertEquals("$name: Facebook's own code follows", sites.reelsBars.body()[0].opcode, helper[6].opcode)

                // Each reels-in-tab read goes through the extension, back into its register.
                for ((method, reads) in sites.darkReads.groupBy { it.method }) {
                    val after = patched(method)
                    assertEquals("$name: ${method.name} gains two instructions a read", method.body().size + 2 * reads.size, after.size)
                    var shift = 0
                    for (site in reads.sortedBy { it.resultIndex }) {
                        val at = site.resultIndex + shift
                        assertEquals("$name: ${method.name} keeps the answer", Opcode.MOVE_RESULT, after[at].opcode)
                        val asked = after[at + 1]
                        assertEquals("$name: ${method.name} asks the extension", "$LIGHT_REELS_TAB_BARS->darkBars(Z)Z", asked.reference())
                        assertEquals("$name: ${method.name} hands over the answer", site.register,
                            (asked as RegisterRangeInstruction).startRegister)
                        assertEquals("$name: ${method.name} keeps the extension's", site.register,
                            (after[at + 2] as OneRegisterInstruction).registerA)
                        shift += 2
                    }
                }

                // The switch reads Facebook's own dark mode answer.
                val controller = context.mutableClassDefBy(with(context) { DarkModeFingerprint.method }.definingClass)
                assertTrue("$name: the dark mode answer goes to the extension",
                    controller.methods.any { method -> method.body().any { it.reference() == DARK_MODE_ANSWER } })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
