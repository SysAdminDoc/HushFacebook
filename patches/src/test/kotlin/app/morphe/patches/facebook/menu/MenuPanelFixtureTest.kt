/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The side panel's Menu (#184) on every Facebook bundle of every declared build: one method sets the
 * panel up, it asks MobileConfig once whether the panel is the Bloks Menu, and the patch hands that
 * answer to the extension in its own register right before Facebook tests it. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class MenuPanelFixtureTest {
    /**
     * The MobileConfig flag the panel asks per build, read from 583's arm64 and armv7 dex and 584's arm64.
     * 586's was read from both of its bundles, in the panel setup and the bookmarks fragment factory.
     * 587's from its arm64 panel setup (`LX/020D;->A02`).
     */
    private val bloksPanelFlags = mapOf(
        "585.0.0.47.54" to 0x101019f0058167fL,
        "586.0.0.52.53" to 0x101019f005816c8L,
        "587.0.0.39.55" to 0x101019f00581702L,
    )

    /** The flag [bundle]'s build asks, by the version in its name. */
    private fun bloksPanelFlag(bundle: String): Long =
        bloksPanelFlags.entries.single { bundle.contains("-${it.key}-") }.value

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    @Test
    fun `each declared bundle asks the extension which Menu goes in the side panel`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
        val checked = mutableMapOf<String, String>()
        for (bundle in bundles) {
            val name = bundle.name
            // The panel key's holders too: 586's Android 9 build asks a string table for it.
            val classes = (FixtureDex.classesHolding(bundle, PANEL_SETUP) + FixtureDex.classesHolding(bundle, PANEL_KEY))
                .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
            val context = PatchContexts.of(classes)
            val setups = context.menuPanelSetups()
            assertEquals("$name: methods setting the panel up ${setups.map { "${it.definingClass}->${it.name}" }}", 1, setups.size)
            val method = setups.single()
            val code = method.code()
            val answer = bloksPanelAnswer(method)
            assertNotNull("$name: ${method.definingClass}->${method.name} doesn't ask once whether its panel is the Bloks Menu " +
                "(MobileConfig answers at ${mobileConfigAnswers(code)})", answer)
            // Positive control: the answer is the one for the panel's flag, by its id.
            val flag = code.subList(maxOf(0, answer!! - 4), answer).filterIsInstance<WideLiteralInstruction>()
                .map { it.wideLiteral }
            assertTrue("$name: the answer at $answer isn't for flag ${bloksPanelFlag(name).toString(16)}, saw $flag",
                bloksPanelFlag(name) in flag)
            val register = (code[answer] as OneRegisterInstruction).registerA

            context.askAboutBloksPanel()
            val patched = context.mutableClassDefBy(method.definingClass).methods
                .single { patchedMethod ->
                    patchedMethod.name == method.name &&
                        patchedMethod.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }.code()
            assertEquals("$name: two instructions added", code.size + 2, patched.size)
            val ask = patched[answer + 1]
            assertEquals("$name: the extension asked", BLOKS_PANEL, ask.called()?.toString())
            assertEquals("$name: handed the answer",
                listOf(Opcode.INVOKE_STATIC_RANGE, register, 1),
                listOf(ask.opcode, (ask as RegisterRangeInstruction).startRegister, ask.registerCount))
            assertEquals("$name: its answer back in the same register",
                listOf(Opcode.MOVE_RESULT, register),
                listOf(patched[answer + 2].opcode, (patched[answer + 2] as OneRegisterInstruction).registerA))
            assertEquals("$name: Facebook's test right after", listOf(Opcode.IF_EQZ, register),
                listOf(patched[answer + 3].opcode, (patched[answer + 3] as OneRegisterInstruction).registerA))
            val rest = patched.filterIndexed { index, _ -> index != answer + 1 && index != answer + 2 }
            assertEquals("$name: the rest is Facebook's", code.map { it.opcode }, rest.map { it.opcode })
            checked[name] = "${method.definingClass}->${method.name} answer at $answer in v$register"
        }
        assertEquals("a declared bundle went unchecked: $checked", bundles.map { it.name }.toSet(), checked.keys)
        assertTrue("no declared build has a fixture bundle", checked.isNotEmpty())
    }

    /**
     * #184's 0.16.0 report: the native Menu the panel swap asks for is the Menu page, whose factory
     * asks the same flag and builds the Bloks Menu page on a yes. Its answer goes to the extension too.
     */
    @Test
    fun `each declared bundle asks the extension whether the Menu page is the Bloks one`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
        val checked = mutableMapOf<String, String>()
        for (bundle in bundles) {
            val name = bundle.name
            val context = PatchContexts.of(FixtureDex.classes(bundle, setOf(MENU_FACTORY)).values.toList())
            val method = context.menuPageFactory()
            assertNotNull("$name: no $MENU_FACTORY->$CREATE_FRAGMENT", method)
            val code = method!!.code()
            val answer = bloksMenuPageAnswer(method)
            assertNotNull("$name: $CREATE_FRAGMENT doesn't ask once whether to build $BLOKS_MENU_PAGE", answer)
            // Positive control: the page asks the panel's flag, by its id.
            val flag = code.subList(maxOf(0, answer!! - 4), answer).filterIsInstance<WideLiteralInstruction>()
                .map { it.wideLiteral }
            assertTrue("$name: the answer at $answer isn't for flag ${bloksPanelFlag(name).toString(16)}, saw $flag",
                bloksPanelFlag(name) in flag)
            val register = (code[answer] as OneRegisterInstruction).registerA

            context.askAboutBloksMenuPage()
            val patched = context.mutableClassDefBy(MENU_FACTORY).methods.single { it.name == CREATE_FRAGMENT }.code()
            assertEquals("$name: two instructions added", code.size + 2, patched.size)
            val ask = patched[answer + 1]
            assertEquals("$name: the extension asked", BLOKS_MENU, ask.called()?.toString())
            assertEquals("$name: handed the answer",
                listOf(Opcode.INVOKE_STATIC_RANGE, register, 1),
                listOf(ask.opcode, (ask as RegisterRangeInstruction).startRegister, ask.registerCount))
            assertEquals("$name: its answer back in the same register",
                listOf(Opcode.MOVE_RESULT, register),
                listOf(patched[answer + 2].opcode, (patched[answer + 2] as OneRegisterInstruction).registerA))
            assertEquals("$name: Facebook's test right after", listOf(Opcode.IF_EQZ, register),
                listOf(patched[answer + 3].opcode, (patched[answer + 3] as OneRegisterInstruction).registerA))
            assertEquals("$name: a yes still builds the Bloks page", BLOKS_MENU_PAGE,
                ((patched[answer + 4] as ReferenceInstruction).reference as TypeReference).type)
            val rest = patched.filterIndexed { index, _ -> index != answer + 1 && index != answer + 2 }
            assertEquals("$name: the rest is Facebook's", code.map { it.opcode }, rest.map { it.opcode })
            checked[name] = "answer at $answer in v$register"
        }
        assertEquals("a declared bundle went unchecked: $checked", bundles.map { it.name }.toSet(), checked.keys)
        assertTrue("no declared build has a fixture bundle", checked.isNotEmpty())
    }

    @Test
    fun `the extension has the hooks the patch calls`() {
        val methods = ExtensionDex.classDef("$EXTENSION_PACKAGE/menu/MenuPanel;").methods
        for ((name, shape) in listOf("bloksPanel" to BLOKS_PANEL, "bloksMenu" to BLOKS_MENU)) {
            val hook = methods.singleOrNull { it.name == name }
            assertNotNull("the extension has no MenuPanel.$name", hook)
            assertEquals("$name's shape", shape,
                "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
        }
    }
}
