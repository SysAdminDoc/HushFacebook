/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.RepoFiles
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.settings.SettingsPatchHosts.builder
import app.morphe.patches.facebook.misc.settings.SettingsPatchHosts.loads
import app.morphe.patches.facebook.misc.settings.SettingsPatchHosts.on
import app.morphe.patches.facebook.misc.settings.SettingsPatchHosts.setContentDescription
import app.morphe.patches.facebook.misc.settings.SettingsPatchHosts.setOnClickListener
import app.morphe.patches.facebook.misc.settings.SettingsPatchHosts.setOnTouchListener
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A long press on the Facebook logo at the top of the home feed opens the Hushfacebook screen, for
 * a launcher with no shortcut menu (#2). The settings patch sends the call that gives the logo its
 * touch listener, right after the one that gives it its tap, to SettingsEntry, in the one method
 * of the top bar that holds the logo's trace section. The other tabs' title bars get the same long
 * press (#374): each new one is handed to SettingsEntry as its constructor ends.
 */
class LogoLongPressTest {
    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference)?.toString()

    /** The bar's logo builder in [classes] after [hook] ran over them. */
    private fun patched(classes: List<ClassDef>, hook: app.morphe.patcher.patch.BytecodePatchContext.() -> Unit): List<Instruction> {
        val context = PatchContexts.of(classes)
        context.hook()
        return context.mutableClassDefBy(WORDMARK_NAVIGATION_BAR).methods
            .single { holdsString(it, CREATE_WORDMARK_VIEW) }.instructions()
    }

    private fun refusal(vararg classes: ClassDef): String = try {
        PatchContexts.of(classes.toList()).hookLogoLongPress()
        fail("the patch went on without its evidence")
        ""
    } catch (expected: PatchException) {
        expected.message.orEmpty()
    }

    @Test
    fun theLogosTouchListenerGoesToTheStandInWithTheSameRegisters() {
        val before = SettingsPatchHosts.bar().methods.single().instructions()

        val after = patched(listOf(SettingsPatchHosts.bar())) { hookLogoLongPress() }

        assertEquals("instruction count", before.size, after.size)
        assertEquals(Opcode.INVOKE_STATIC, after[3].opcode)
        assertEquals(LOGO_TOUCH_STAND_IN, after[3].call())
        assertEquals("the logo, then Facebook's listener", listOf(1, 3), after[3].registers())
        assertEquals("the logo's tap is Facebook's, as it was", LOGO_CLICK_CALL, after[2].call())
        assertEquals("the container's touch listener isn't the logo's", Opcode.INVOKE_VIRTUAL, after[1].opcode)
        assertEquals(before.map { it.call() }.filterIndexed { index, _ -> index != 3 },
            after.map { it.call() }.filterIndexed { index, _ -> index != 3 })
    }

    /**
     * 583's builder has a path that gives the logo no tap (#12, taken in Arabic): it hides the logo
     * from accessibility and jumps past the tap and the touch listener. That call on the logo goes
     * to its own stand-in on Facebook's registers; the same call on another view stays Facebook's.
     */
    @Test
    fun theLogosPathWithNoTapGoesToItsStandIn() {
        val hides = SettingsPatchHosts.viewCall("setImportantForAccessibility", "I")
        val bar = SettingsPatchHosts.bar(
            loads(0, CREATE_WORDMARK_VIEW),
            on(2, 4, hides),
            on(1, 4, hides),
            on(1, 2, setOnClickListener),
            on(1, 3, setOnTouchListener),
            on(1, 0, setContentDescription),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        val after = patched(listOf(bar)) { hookLogoLongPress() }

        assertEquals("instruction count", 7, after.size)
        assertEquals(Opcode.INVOKE_STATIC, after[2].opcode)
        assertEquals(LOGO_WITHOUT_TAP_STAND_IN, after[2].call())
        assertEquals("the logo, then Facebook's importance", listOf(1, 4), after[2].registers())
        assertEquals("another view's importance is Facebook's", Opcode.INVOKE_VIRTUAL, after[1].opcode)
        assertEquals(hides.toString(), after[1].call())
        assertEquals("the logo's tap is Facebook's, as it was", LOGO_CLICK_CALL, after[3].call())
        assertEquals(LOGO_TOUCH_STAND_IN, after[4].call())
    }

    @Test
    fun aRangeCallStaysARangeCall() {
        val bar = SettingsPatchHosts.barWith(builder("initContents", ImmutableMethodImplementation(20, listOf(
            loads(0, CREATE_WORDMARK_VIEW),
            ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 17, 2, setOnClickListener),
            ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 17, 2, setOnTouchListener),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ), null, null)))

        val after = patched(listOf(bar)) { hookLogoLongPress() }

        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[2].opcode)
        assertEquals(LOGO_TOUCH_STAND_IN, after[2].call())
        assertEquals(listOf(17, 18), after[2].registers())
    }

    /** Each piece of evidence the hook stands on, missing, stops the patch and says which. */
    @Test
    fun missingEvidenceStopsThePatch() {
        val returns = ImmutableInstruction10x(Opcode.RETURN_VOID)
        fun bar(vararg instructions: Instruction) = SettingsPatchHosts.bar(*instructions, returns)

        assertTrue(refusal().contains("has no $WORDMARK_NAVIGATION_BAR"))
        assertTrue(refusal(bar(on(1, 2, setOnClickListener), on(1, 3, setOnTouchListener)))
            .contains("holding \"$CREATE_WORDMARK_VIEW\", found 0"))
        val body = ImmutableMethodImplementation(6, listOf(
            loads(0, CREATE_WORDMARK_VIEW), on(1, 2, setOnClickListener), on(1, 3, setOnTouchListener), returns,
        ), null, null)
        assertTrue(refusal(SettingsPatchHosts.barWith(builder("a", body), builder("b", body))).contains("found 2"))
        val loaded = loads(0, CREATE_WORDMARK_VIEW)
        assertTrue(refusal(bar(loaded, on(1, 2, setOnClickListener), on(1, 3, setOnTouchListener), on(2, 3, setOnClickListener)))
            .contains("it gives 2"))
        assertTrue(refusal(bar(loaded, on(1, 2, setOnClickListener), on(1, 0, setContentDescription), on(1, 3, setOnTouchListener)))
            .contains("no longer followed by its touch listener"))
        assertTrue(refusal(bar(loaded, on(1, 2, setOnClickListener), on(2, 3, setOnTouchListener)))
            .contains("goes to another view (v2, not v1)"))
        val hides = SettingsPatchHosts.viewCall("setImportantForAccessibility", "I")
        assertTrue(refusal(bar(loaded, on(1, 4, hides), on(1, 4, hides), on(1, 2, setOnClickListener), on(1, 3, setOnTouchListener)))
            .contains("at most once around its tap; it does 2 times"))
        assertTrue(refusal(bar(loaded, on(1, 4, hides), on(1, 2, setOnClickListener), on(1, 3, setOnTouchListener), on(1, 4, hides)))
            .contains("at most once around its tap; it does 2 times"))
    }

    /**
     * 587's builder hides the logo below its description instead, where its path with no tap jumps
     * and its path with the tap jumps over (`A0I` at 140). That call goes to the stand-in too, and a
     * hide after the logo's register holds something else stays Facebook's.
     */
    @Test
    fun theLogosPathWithNoTapBelowItsDescriptionGoesToItsStandIn() {
        val hides = SettingsPatchHosts.viewCall("setImportantForAccessibility", "I")
        val bar = SettingsPatchHosts.bar(
            loads(0, CREATE_WORDMARK_VIEW),
            on(1, 2, setOnClickListener),
            on(1, 3, setOnTouchListener),
            on(1, 0, setContentDescription),
            on(1, 4, hides),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            on(1, 4, hides),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        val after = patched(listOf(bar)) { hookLogoLongPress() }

        assertEquals("instruction count", 8, after.size)
        assertEquals(LOGO_TOUCH_STAND_IN, after[2].call())
        assertEquals(Opcode.INVOKE_STATIC, after[4].opcode)
        assertEquals(LOGO_WITHOUT_TAP_STAND_IN, after[4].call())
        assertEquals("the logo, then Facebook's importance", listOf(1, 4), after[4].registers())
        assertEquals("a hide once the register holds something else is Facebook's", Opcode.INVOKE_VIRTUAL, after[6].opcode)
        assertEquals(hides.toString(), after[6].call())
    }

    /**
     * The stand-in the rewrite calls is in the SettingsEntry the bundle ships, public and static,
     * with exactly the descriptor the rewrite writes. Read from the compiled extension, so a Java
     * parameter that compiles to another type fails here rather than as a NoSuchMethodError when
     * Facebook builds its top bar.
     */
    @Test
    fun theStandInIsInTheExtension() {
        val declared = ExtensionDex.classDef(ENTRY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$ENTRY->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("SettingsEntry declares no public static $LOGO_TOUCH_STAND_IN: $declared", LOGO_TOUCH_STAND_IN in declared)
        assertTrue("SettingsEntry declares no public static $LOGO_WITHOUT_TAP_STAND_IN: $declared", LOGO_WITHOUT_TAP_STAND_IN in declared)
        assertTrue("SettingsEntry declares no public static $TITLE_BAR_MADE: $declared", TITLE_BAR_MADE in declared)
    }

    /** The title bar class in [classes] after the title bar hook ran over them. */
    private fun titleBarAfterHook(vararg classes: ClassDef): List<Method> {
        val context = PatchContexts.of(classes.toList())
        context.hookTitleBarLongPress()
        return context.mutableClassDefBy(NAVIGATION_BAR).methods.toList()
    }

    private fun Method.shape(): String = parameterTypes.joinToString("", "(", ")") + returnType

    private val buildsShape = "(${SettingsPatchHosts.CONTEXT}${SettingsPatchHosts.ATTRIBUTES}I)V"

    /**
     * Every other tab's top bar opens the settings on a long press too (#374). The constructor that
     * builds the bar hands `this`, the register past its locals, to SettingsEntry where it returned,
     * then returns. The one that hands on to it is left alone, so a bar is handed over once.
     */
    @Test
    fun eachTitleBarIsHandedOverAsItsConstructorEnds() {
        val methods = titleBarAfterHook(SettingsPatchHosts.titleBar())

        val builds = methods.single { it.shape() == buildsShape }.instructions()
        assertEquals("instruction count", 4, builds.size)
        assertEquals("Facebook's own code first", Opcode.INVOKE_DIRECT, builds[0].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, builds[2].opcode)
        assertEquals(TITLE_BAR_MADE, builds[2].call())
        assertEquals("this, past the one local", listOf(1), builds[2].registers())
        assertEquals(Opcode.RETURN_VOID, builds[3].opcode)
        val handsOn = methods.single { it.shape() != buildsShape }.instructions()
        assertEquals("the constructor handing on changed", 3, handsOn.size)
        assertTrue("the constructor handing on hands the bar over too", handsOn.none { it.call() == TITLE_BAR_MADE })
    }

    /** A jump to a return reaches the call too, since the call takes the return's place. */
    @Test
    fun aJumpToAReturnReachesTheCall() {
        val methods = titleBarAfterHook(SettingsPatchHosts.titleBar(
            ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 4, 1, 2, 3, 4, 0,
                ImmutableMethodReference("Landroid/widget/FrameLayout;", "<init>", listOf(SettingsPatchHosts.CONTEXT, SettingsPatchHosts.ATTRIBUTES, "I"), "V")),
            ImmutableInstruction21t(Opcode.IF_EQZ, 4, 3),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ))

        val builds = methods.single { it.shape() == buildsShape }.instructions()
        assertEquals("instruction count", 6, builds.size)
        assertEquals(listOf(2, 4), builds.indices.filter { builds[it].call() == TITLE_BAR_MADE })
        assertEquals(listOf(3, 5), builds.indices.filter { builds[it].opcode == Opcode.RETURN_VOID })
        assertEquals("the jump to the second return skips its call", 4, gotoTarget(builds, 1))
    }

    private fun titleBarRefusal(vararg classes: ClassDef): String = try {
        PatchContexts.of(classes.toList()).hookTitleBarLongPress()
        fail("the patch went on without its evidence")
        ""
    } catch (expected: PatchException) {
        expected.message.orEmpty()
    }

    /** Each piece of evidence the title bar hook stands on, missing, stops the patch and says which. */
    @Test
    fun aTitleBarItCantHookStopsThePatch() {
        assertTrue(titleBarRefusal().contains("has no $NAVIGATION_BAR"))
        val handsOnOnly = SettingsPatchHosts.titleBar().let { bar ->
            ImmutableClassDef(bar.type, bar.accessFlags, bar.superclass, null, null, null, null,
                bar.methods.filter { it.shape() != buildsShape }.map { ImmutableMethod.of(it) })
        }
        assertTrue(titleBarRefusal(handsOnOnly).contains("no constructor that builds the bar itself"))
        assertTrue(titleBarRefusal(SettingsPatchHosts.titleBar(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.THROW, 0),
        )).contains("never returns"))
        assertTrue(titleBarRefusal(SettingsPatchHosts.titleBar(
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )).contains("writes over this (v1)"))
    }

    /**
     * The receipt refuses a patched build whose logo call isn't the stand-in, right after the logo
     * gets its tap, in the one method holding both of the logo's trace sections with the builder's
     * shape, which the fixture test below pins on every declared build. The rule names what the
     * patch names, so a rename on one side can't leave the rule looking for something no build has.
     */
    @Test
    fun theContractFileHoldsTheLogoHook() {
        val rules = File(RepoFiles.root, "scripts/injected-mutation-contracts.txt").readLines()
            .map { it.trim() }
            .filter { it.startsWith("next-call ") && it.contains("->setLogoTouchListener(") }
        assertEquals(
            listOf(
                "next-call $LOGO_TOUCH_STAND_IN after $LOGO_CLICK_CALL in static $builderShape " +
                    "holding $CREATE_WORDMARK_VIEW $initContents",
            ),
            rules,
        )
    }

    /** The logo builder's shape on every declared build: static, a context and the bar in, nothing out. */
    private val builderShape = "(Landroid/content/Context;$WORDMARK_NAVIGATION_BAR)V"

    /** The builder's other trace section, which only it holds. */
    private val initContents = "WordmarkNavigationBar.initContents"

    /**
     * Where each declared bundle gives its logo the touch listener, and the registers it uses, then
     * where its path with no tap hides the logo from accessibility, and that call's registers.
     */
    private data class Pin(
        val index: Int,
        val registers: List<Int>,
        val withoutTap: Int,
        val withoutTapRegisters: List<Int>,
        val titleBarReturns: List<Int>,
    )

    /**
     * By build, then by the bundle's ABI. 581's two builds put the call at the same index; 582's
     * armeabi-v7a build had nine more instructions ahead of it, and 583's two put it at the same
     * index again (the builder is `A0J` on arm64 and `A0I` on armeabi-v7a, the click listener one
     * instruction before). 583's two hide the logo at 157 on the path with no tap, then jump past
     * the description. 584's two builds both have the builder at `A0G`, the touch listener at 123
     * and the logo hidden at 114, on the same registers. 585's two put them one earlier, at 122 and
     * 113, on new registers. 586's two build it in `A0I` with the touch listener at 136 and the logo
     * hidden at 127, on new registers again. 586's Android 9 build makes it a static `A0J` taking
     * the context and the bar, with the touch listener at 159 and the logo hidden at 150. 587's two
     * keep the static `A0I` with the touch listener at 135 and move the hide below the description,
     * to 140, where the path with no tap jumps and the path with the tap jumps over. Last, where the
     * title bar's constructor that builds it returns.
     */
    private val pins = mapOf(
        "587.0.0.39.55" to mapOf(
            "arm64-v8a" to Pin(135, listOf(5, 3), 140, listOf(5, 9), listOf(120)),
            "armeabi-v7a" to Pin(135, listOf(5, 3), 140, listOf(5, 9), listOf(118)),
        ),
    )

    private val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

    /** The index the goto at [index] of [instructions] jumps to. */
    private fun gotoTarget(instructions: List<Instruction>, index: Int): Int {
        val addresses = instructions.runningFold(0) { address, it -> address + it.codeUnits }
        val target = addresses[index] + (instructions[index] as OffsetInstruction).codeOffset
        return addresses.indexOf(target)
    }

    /**
     * Both declared builds keep the top bar's class name and give the logo its tap, its touch
     * listener and its content description in that order, in the one method holding both of its
     * trace sections. The settings patch, run over each build's bar, sends that touch listener
     * call to the stand-in on Facebook's registers and changes nothing else in the method.
     */
    @Test
    fun eachDeclaredBuildGivesTheLogoItsTouchListenerWhereThePatchLooks() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("a declared build has no pin", versions, pins.keys)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val pin = pins.getValue(version)[bundle.name.substringAfter("-$version-").substringBefore(".apkm")]
                    ?: throw AssertionError("${bundle.name} has no pin")
                val classes = FixtureDex.classes(bundle, setOf(WORDMARK_NAVIGATION_BAR, NAVIGATION_BAR))
                val bar = classes[WORDMARK_NAVIGATION_BAR]
                    ?: throw AssertionError("${bundle.name} has no $WORDMARK_NAVIGATION_BAR")
                val titleBar = classes[NAVIGATION_BAR] ?: throw AssertionError("${bundle.name} has no $NAVIGATION_BAR")
                val titleBarBuilds = titleBarConstructors(titleBar)
                assertEquals("${bundle.name}: the title bar's constructors that build it",
                    listOf(buildsShape), titleBarBuilds.map { it.shape() })
                val titleBarWas = titleBarBuilds.single().instructions()
                assertEquals("${bundle.name}: where the title bar's constructor returns", pin.titleBarReturns,
                    titleBarWas.indices.filter { titleBarWas[it].opcode == Opcode.RETURN_VOID })
                val builders = bar.methods.filter { holdsString(it, CREATE_WORDMARK_VIEW) }
                assertEquals("${bundle.name}: methods holding the logo's trace section", 1, builders.size)
                val builder = builders.single()
                assertTrue("${bundle.name}: the builder isn't initContents", holdsString(builder, initContents))
                assertEquals(
                    "${bundle.name}: the builder's shape",
                    builderShape,
                    builder.parameterTypes.joinToString("", "(", ")") + builder.returnType,
                )
                assertTrue("${bundle.name}: the builder isn't static", AccessFlags.STATIC.isSet(builder.accessFlags))
                val was = builder.instructions()
                val index = logoTouchListenerIndex(builder)
                assertEquals("${bundle.name}: where the logo gets its touch listener", pin.index, index)
                val logo = was[index].registers().first()
                assertEquals("${bundle.name}: the logo's tap", LOGO_CLICK_CALL, was[index - 1].call())
                assertEquals("${bundle.name}: the tap goes to the logo", logo, was[index - 1].registers().first())
                assertEquals("${bundle.name}: the logo's description comes next",
                    SettingsPatchHosts.setContentDescription.toString(), was[index + 1].call())
                assertEquals("${bundle.name}: the description goes to the logo", logo, was[index + 1].registers().first())
                val withoutTap = logoWithoutTapIndex(builder)
                assertEquals("${bundle.name}: where the path with no tap hides the logo", pin.withoutTap, withoutTap)
                assertEquals("${bundle.name}: the logo hidden there", logo, was[pin.withoutTap].registers().first())
                if (pin.withoutTap < index) {
                    assertTrue("${bundle.name}: no goto follows the logo being hidden", was[pin.withoutTap + 1].opcode in gotos)
                    assertTrue("${bundle.name}: the path with no tap doesn't jump past the logo's description",
                        gotoTarget(was, pin.withoutTap + 1) > index + 1)
                } else {
                    // 587: a goto ahead of the tap lands on the hide, and the path with the tap jumps over it.
                    assertTrue("${bundle.name}: no goto ahead of the logo's tap lands on the hide",
                        (0 until index - 1).any { was[it].opcode in gotos && gotoTarget(was, it) == pin.withoutTap })
                    assertTrue("${bundle.name}: the path with the tap doesn't jump over the hide",
                        was[pin.withoutTap - 1].opcode in gotos && gotoTarget(was, pin.withoutTap - 1) > pin.withoutTap)
                }

                val context = PatchContexts.of(SettingsPatchHosts.appAndMainTab() + bar + titleBar)
                settingsPatch.execute(context)

                val titleBarNow = context.mutableClassDefBy(NAVIGATION_BAR).methods
                    .single { it.name == "<init>" && it.shape() == buildsShape }.instructions()
                assertEquals("${bundle.name}: the title bar's instruction count",
                    titleBarWas.size + pin.titleBarReturns.size, titleBarNow.size)
                pin.titleBarReturns.forEachIndexed { added, at ->
                    assertEquals("${bundle.name}: the title bar handed over at $at", TITLE_BAR_MADE, titleBarNow[at + added].call())
                    assertEquals("${bundle.name}: this, past the locals",
                        listOf(titleBarBuilds.single().localRegisterCount()), titleBarNow[at + added].registers())
                    assertEquals("${bundle.name}: then the return", Opcode.RETURN_VOID, titleBarNow[at + added + 1].opcode)
                }

                val now = context.mutableClassDefBy(WORDMARK_NAVIGATION_BAR).methods
                    .single { holdsString(it, CREATE_WORDMARK_VIEW) }.instructions()
                assertEquals("${bundle.name}: instruction count", was.size, now.size)
                assertEquals("${bundle.name}: the stand-in", LOGO_TOUCH_STAND_IN, now[index].call())
                assertEquals("${bundle.name}: the static form", Opcode.INVOKE_STATIC, now[index].opcode)
                assertEquals("${bundle.name}: Facebook's registers", pin.registers, now[index].registers())
                assertEquals("${bundle.name}: the stand-in with no tap", LOGO_WITHOUT_TAP_STAND_IN, now[pin.withoutTap].call())
                assertEquals("${bundle.name}: its static form", Opcode.INVOKE_STATIC, now[pin.withoutTap].opcode)
                assertEquals("${bundle.name}: its registers", pin.withoutTapRegisters, now[pin.withoutTap].registers())
                was.indices.filter { it != index && it != pin.withoutTap }.forEach { i ->
                    assertEquals("${bundle.name} at $i", was[i].opcode, now[i].opcode)
                    assertEquals("${bundle.name} at $i", was[i].call(), now[i].call())
                    assertEquals("${bundle.name} at $i", was[i].registers(), now[i].registers())
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
