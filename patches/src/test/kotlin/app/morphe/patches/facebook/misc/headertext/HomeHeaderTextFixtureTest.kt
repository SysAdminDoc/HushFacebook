/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.headertext

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.appicons.dressLogoAfter
import app.morphe.patches.facebook.misc.appicons.logoDressSite
import app.morphe.patches.facebook.misc.settings.CREATE_WORDMARK_VIEW
import app.morphe.patches.facebook.misc.settings.LOGO_TOUCH_STAND_IN
import app.morphe.patches.facebook.misc.settings.WORDMARK_NAVIGATION_BAR
import app.morphe.patches.facebook.misc.settings.logoTouchListenerIndex
import app.morphe.patches.facebook.misc.settings.sendToStandIn
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home header text's one hook on every declared Facebook build (#230): after the settings patch
 * sends the logo's touch listener through its stand-in, the text call goes in right after the stock
 * wordmark load, with the logo's register.
 */
class HomeHeaderTextFixtureTest {
    @Test
    fun `the header call is public and static in the extension`() {
        val calls = ExtensionDex.classDef("Lapp/morphe/extension/facebook/misc/HeaderText;").methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("the extension declares no public static $DRESS_HEADER: $calls", DRESS_HEADER in calls)
    }

    @Test
    fun `each declared build takes the text after its stock wordmark`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun check(bundle: File) {
        val name = bundle.name
        val bar = FixtureDex.classes(bundle, setOf(WORDMARK_NAVIGATION_BAR))[WORDMARK_NAVIGATION_BAR]
            ?: throw AssertionError("$name: no $WORDMARK_NAVIGATION_BAR")
        val builders = bar.methods.filter { holdsString(it, CREATE_WORDMARK_VIEW) }
        assertEquals("$name: logo builders", 1, builders.size)
        val logoBuilder = builders.single()
        val original = logoBuilder.implementation!!.instructions.toList()
        val touch = logoTouchListenerIndex(logoBuilder)
        val logo = original[touch].registers().first()

        // The text alone.
        val alone = PatchContexts.of(listOf(bar)).mutableClassDefBy(WORDMARK_NAVIGATION_BAR).findMutableMethodOf(logoBuilder)
        alone.sendToStandIn(touch, LOGO_TOUCH_STAND_IN)
        val site = logoDressSite(alone, PATCH)
        assertEquals("$name: the touch listener's logo register", logo, site.logo)
        val stockLoad = alone.implementation!!.instructions[site.stockLoad].call()
        alone.dressLogoAfter(site, DRESS_HEADER)
        val dressed = alone.implementation!!.instructions.toList()
        assertEquals("$name: one header call added", original.size + 1, dressed.size)
        assertEquals("$name: the stock wordmark still loads first", stockLoad, dressed[site.stockLoad].call())
        assertEquals("$name: the header text call", DRESS_HEADER, dressed[site.stockLoad + 1].call())
        assertEquals("$name: the header text call is static range", Opcode.INVOKE_STATIC_RANGE, dressed[site.stockLoad + 1].opcode)
        assertEquals("$name: the logo is handed over", listOf(logo), dressed[site.stockLoad + 1].registers())
    }

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun Instruction.call(): String? =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
        }
}
