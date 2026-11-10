/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.layout.theme.TAB_BAR_CONTAINER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the tab bar on the Reels tab on every Facebook build the bundle declares: one tab bar restyle
 * stores one flag on the TabBarContainerLayout, and the extension is handed that container and the
 * TabTag right before it; FbChromeFragment has one page margin set from the scroll-away controller's
 * int, and the extension gets that int and the fragment's TabTag right after it's read. Nothing else
 * changes. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelsTabBarHiddenFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    // A jump the hook's instructions push out of a goto's reach comes back as goto/16, the same jump.
    private fun List<Instruction>.opcodes() = map { if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode }
        .filter { it != Opcode.NOP }

    private fun Instruction.call(): String = ((this as ReferenceInstruction).reference as MethodReference).toString()

    @Test
    fun `each declared build hands the bar, its tab and the page's margin to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val restyles = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == TAB_BAR_CONTAINER } }) {
                    tabRestyleSite(it) != null
                }
                assertEquals("$name: tab bar restyles", 1, restyles.size)
                val owner = FixtureDex.classes(bundle, setOf(restyles.single().definingClass)).values.single()
                val chrome = FixtureDex.classes(bundle, setOf(FB_CHROME_FRAGMENT)).values.single()
                val height = chrome.methods.mapNotNull(::pageMarginSite).single().let { site ->
                    (site.method.body()[site.read - 1] as ReferenceInstruction).reference as MethodReference
                }
                val sizers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == height.definingClass } }) {
                    reelHeightSite(it, height) != null
                }
                assertEquals("$name: reel heights taking the bottom bars off last", 1, sizers.size)
                val sizer = FixtureDex.classes(bundle, setOf(sizers.single().definingClass)).values.single()

                val context = PatchContexts.of(listOf(owner, chrome, sizer).distinctBy { it.type })
                val sites = with(context) { reelsTabBarSites() }
                assertNotNull("$name: Hide the tab bar on the Reels tab found all three sites", sites)
                val (restyle, margin, tabField, reel) = sites!!
                val restyleBefore = restyle.method.body()
                val marginBefore = margin.method.body()
                val reelBefore = reel.method.body()

                // Positive controls: the store is on the container, the tab is the TabTag parameter,
                // the margin is the controller's no-argument int stored as the bottom margin, the
                // fragment is the margin method's last parameter and the TabTag field is its own.
                val store = (restyleBefore[restyle.at] as ReferenceInstruction).reference as FieldReference
                assertEquals("$name: the store is on the container", TAB_BAR_CONTAINER, store.definingClass)
                assertEquals("$name: the tab is the TabTag parameter", restyle.method.implementation!!.registerCount - 2, restyle.tab)
                assertEquals("$name: FbChromeFragment has one page margin", 1, chrome.methods.mapNotNull(::pageMarginSite).size)
                val ask = (marginBefore[margin.read - 1] as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the margin is the controller's int", listOf(Opcode.INVOKE_VIRTUAL, "I", 0),
                    listOf(marginBefore[margin.read - 1].opcode, ask.returnType, ask.parameterTypes.size))
                val stored = marginBefore.indexOfFirst { it.opcode == Opcode.IPUT }
                assertEquals("$name: the margin is stored as bottomMargin", listOf("bottomMargin", margin.height),
                    listOf(((marginBefore[stored] as ReferenceInstruction).reference as FieldReference).name,
                        (marginBefore[stored] as TwoRegisterInstruction).registerA))
                assertEquals("$name: the fragment is the last parameter", FB_CHROME_FRAGMENT, margin.method.parameterTypes.last().toString())
                assertEquals("$name: the TabTag field is FbChromeFragment's", listOf(FB_CHROME_FRAGMENT, TAB_TAG),
                    listOf(tabField.definingClass, tabField.type))
                val reelAsk = (reelBefore[reel.read - 1] as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the reel takes off the same height as the page margin", height.toString(), reelAsk.toString())
                assertEquals("$name: the reel subtracts that height and returns the rest", listOf(Opcode.SUB_INT_2ADDR, Opcode.RETURN),
                    listOf(reelBefore[reel.read + 1].opcode, reelBefore[reel.read + 2].opcode))

                with(context) { hookReelsTabBarHidden() }

                val restyleAfter = context.mutableClassDefBy(restyle.method.definingClass).methods
                    .single { it.name == restyle.method.name && it.parameterTypes == restyle.method.parameterTypes }.body()
                assertEquals("$name: the restyle gained one instruction", restyleBefore.opcodes().size + 1, restyleAfter.opcodes().size)
                val hand = restyleAfter[restyle.at] as FiveRegisterInstruction
                assertEquals("$name: the restyle hands the bar and its tab over before the store",
                    listOf("$REELS_TAB_BAR_HIDDEN->tabShown(Landroid/view/View;Ljava/lang/Object;)V", 2, restyle.bar, restyle.tab),
                    listOf(hand.call(), hand.registerCount, hand.registerC, hand.registerD))
                assertEquals("$name: the store follows", Opcode.IPUT_BOOLEAN, restyleAfter[restyle.at + 1].opcode)
                assertEquals("$name: the rest of the restyle is Facebook's", restyleBefore.opcodes(),
                    restyleAfter.filterIndexed { i, _ -> i != restyle.at }.opcodes())

                val marginAfter = context.mutableClassDefBy(FB_CHROME_FRAGMENT).methods
                    .single { it.name == margin.method.name && it.parameterTypes == margin.method.parameterTypes }.body()
                val tab = marginAfter[margin.read + 1] as TwoRegisterInstruction
                assertEquals("$name: the page's tab is read off the fragment into the free register",
                    listOf(Opcode.IGET_OBJECT, margin.scratch, margin.chrome, tabField.toString()),
                    listOf(marginAfter[margin.read + 1].opcode, tab.registerA, tab.registerB,
                        ((tab as ReferenceInstruction).reference as FieldReference).toString()))
                val pass = marginAfter[margin.read + 2] as FiveRegisterInstruction
                assertEquals("$name: the margin and the tab go through the extension",
                    listOf("$REELS_TAB_BAR_HIDDEN->pageBottomMargin(ILjava/lang/Object;)I", 2, margin.height, margin.scratch),
                    listOf(pass.call(), pass.registerCount, pass.registerC, pass.registerD))
                assertEquals("$name: and comes back in the same register", listOf(Opcode.MOVE_RESULT, margin.height),
                    listOf(marginAfter[margin.read + 3].opcode, (marginAfter[margin.read + 3] as OneRegisterInstruction).registerA))
                assertEquals("$name: the margin's own read of bottomMargin overwrites the free register next",
                    listOf(Opcode.IGET, margin.scratch), listOf(marginAfter[margin.read + 4].opcode,
                        (marginAfter[margin.read + 4] as TwoRegisterInstruction).registerA))
                assertEquals("$name: the rest of the margin method is Facebook's", marginBefore.opcodes(),
                    marginAfter.filterIndexed { i, _ -> i !in margin.read + 1..margin.read + 3 }.opcodes())

                val reelAfter = context.mutableClassDefBy(reel.method.definingClass).methods
                    .single { it.name == reel.method.name && it.parameterTypes == reel.method.parameterTypes }.body()
                val shorten = reelAfter[reel.read + 1] as FiveRegisterInstruction
                assertEquals("$name: the reel's bottom bars go through the extension",
                    listOf("$REELS_TAB_BAR_HIDDEN->reelBottomBars(I)I", 1, reel.register),
                    listOf(shorten.call(), shorten.registerCount, shorten.registerC))
                assertEquals("$name: and come back in the same register before the subtraction",
                    listOf(Opcode.MOVE_RESULT, reel.register, Opcode.SUB_INT_2ADDR),
                    listOf(reelAfter[reel.read + 2].opcode, (reelAfter[reel.read + 2] as OneRegisterInstruction).registerA,
                        reelAfter[reel.read + 3].opcode))
                assertEquals("$name: the rest of the reel height is Facebook's", reelBefore.opcodes(),
                    reelAfter.filterIndexed { i, _ -> i != reel.read + 1 && i != reel.read + 2 }.opcodes())
            }
        }
    }

    @Test
    fun `the extension has all three hooks`() {
        val methods = ExtensionDex.classDef(REELS_TAB_BAR_HIDDEN).methods
        for (signature in listOf(
            "tabShown(Landroid/view/View;Ljava/lang/Object;)V",
            "pageBottomMargin(ILjava/lang/Object;)I",
            "reelBottomBars(I)I",
        )) {
            val hook = methods.singleOrNull { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == signature }
            assertNotNull("the extension has no ReelsTabBarHidden.$signature", hook)
            assertTrue("$signature isn't static", AccessFlags.STATIC.isSet(hook!!.accessFlags))
        }
    }
}
