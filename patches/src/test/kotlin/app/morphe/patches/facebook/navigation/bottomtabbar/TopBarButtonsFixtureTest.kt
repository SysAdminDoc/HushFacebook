/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.settings.CREATE_WORDMARK_VIEW
import app.morphe.patches.facebook.misc.settings.LOGO_TOUCH_STAND_IN
import app.morphe.patches.facebook.misc.settings.WORDMARK_NAVIGATION_BAR
import app.morphe.patches.facebook.misc.settings.logoTouchListenerIndex
import app.morphe.patches.facebook.misc.settings.sendToStandIn
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The top bar's three hides (#181) on every Facebook build the bundle declares (every ABI with a
 * fixture, whose Redex names differ): the bar's builder holding setupMessengerGlyphView, the Messenger button
 * class it makes and adds, that class's two count setters, and the logo site. The Create (#257)
 * and search (#321) buttons. The newer bar some accounts get instead: NavigationBar's spec-to-button
 * method, its glyph, that glyph's count setters and its search glyph. Then the hooks go in on the
 * real classes, which also assembles their smali. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TopBarButtonsFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }

    @Test
    fun `each declared build resolves the button, its count and the logo and takes the hooks`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableListOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val bar = FixtureDex.classes(bundle, setOf(WORDMARK_NAVIGATION_BAR))[WORDMARK_NAVIGATION_BAR]
                    ?: throw AssertionError("$name: no $WORDMARK_NAVIGATION_BAR")

                val glyphBuilders = buildersHolding(bar, SETUP_MESSENGER_GLYPH_VIEW)
                assertEquals("$name: builders holding $SETUP_MESSENGER_GLYPH_VIEW", 1, glyphBuilders.size)
                val glyphBuilder = glyphBuilders.single()
                val logoBuilders = buildersHolding(bar, CREATE_WORDMARK_VIEW)
                assertEquals("$name: builders holding $CREATE_WORDMARK_VIEW", 1, logoBuilders.size)
                val logoBuilder = logoBuilders.single()

                // Messenger button: the class the builder makes holds the kept badgableGlyphView string
                // (positive control), and the bar's addView of it is found once, after the string.
                val site = messengerButtonSite(glyphBuilder)
                val originalCode = glyphBuilder.code()
                assertTrue("$name: the addView follows the glyph string", site.addView > originalCode.indexOfFirst {
                    (it as? ReferenceInstruction)?.reference?.toString()?.contains(SETUP_MESSENGER_GLYPH_VIEW) == true
                })
                val holders = FixtureDex.classesHolding(bundle, BADGABLE_GLYPH_VIEW).map { it.type }
                assertTrue("$name: ${site.buttonType} doesn't hold $BADGABLE_GLYPH_VIEW", site.buttonType in holders)
                val button = FixtureDex.classes(bundle, setOf(site.buttonType)).getValue(site.buttonType)
                val counts = countMethods(button)
                assertEquals("$name: the count setters", 2, counts.size)
                assertEquals("$name: the (I)V setter's int", 1, counts[0].intRegister)
                assertEquals("$name: the session setter's int", 2, counts[1].intRegister)
                // The setters really are the ones that set a count: the (I)V one is what the bar's own
                // EzX calls, so the bar holds a call to it.
                val countName = counts[0].name
                assertTrue("$name: nothing in the bar calls ${button.type}->$countName(I)V",
                    bar.methods.any { m -> m.implementation?.instructions?.any { it.call() == "${button.type}->$countName(I)V" } == true })

                // Create button (#257): one builder holds the kept setupCreateGlyphView string, and the
                // bar's addView of the field it keeps the button in is found once, after the string.
                val createBuilders = buildersHolding(bar, SETUP_CREATE_GLYPH_VIEW)
                assertEquals("$name: builders holding $SETUP_CREATE_GLYPH_VIEW", 1, createBuilders.size)
                val createBuilder = createBuilders.single()
                val create = createButtonSite(createBuilder)
                assertEquals("$name: the Create button's field is the bar's", WORDMARK_NAVIGATION_BAR, create.field.definingClass)
                assertNotEquals("$name: the Create button isn't the Messenger button", site.buttonType, create.field.type)
                assertTrue("$name: the Create addView follows its glyph string", create.addView > createBuilder.code().indexOfFirst {
                    (it as? ReferenceInstruction)?.reference?.toString()?.contains(SETUP_CREATE_GLYPH_VIEW) == true
                })
                val readers = bar.methods.filter { m ->
                    m.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == create.field.toString() } == true
                }
                assertEquals("$name: only the Create builder touches its field", listOf(createBuilder.name), readers.map { it.name })

                // Search button (#321): one builder holds the kept addSearchGlyphView string, and the bar
                // adds a field of its own right after it. Positive control: that field holds the glyph
                // class the newer bar makes its buttons and its search glyph from, which the newer
                // bar keeps in its one field of that class.
                val searchBuilders = buildersHolding(bar, ADD_SEARCH_GLYPH_VIEW)
                assertEquals("$name: builders holding $ADD_SEARCH_GLYPH_VIEW", 1, searchBuilders.size)
                val searchBuilder = searchBuilders.single()
                val search = searchButtonSite(searchBuilder)
                assertEquals("$name: the search button's field is the bar's", WORDMARK_NAVIGATION_BAR, search.field.definingClass)
                val newer = newerBar(bundle)
                assertEquals("$name: the search button is the newer bar's glyph class", newer.glyph.type, search.field.type)
                assertNotEquals("$name: the search button isn't the Create button", create.field.toString(), search.field.toString())
                val newerSearch = searchGlyphSite(newer.bar, newer.glyph.type)
                val newerSearchCode = newerSearch.method.code()
                assertEquals("$name: the newer bar keeps a glyph", newer.glyph.type,
                    ((newerSearchCode[newerSearch.kept] as ReferenceInstruction).reference as FieldReference).type)
                assertNotEquals("$name: the search glyph isn't made in the spec method", specButtonSite(newer.bar).method.name, newerSearch.method.name)
                // Positive control for telling Home's bar from Marketplace's: the classic bar names the
                // newer bar inside it, and the field that name lands in is a String of the newer bar's.
                assertTrue("$name: the classic bar names Home's newer bar", homeBarScreenCalls(bar, newer.bar, newerSearch) >= 1)
                assertEquals("$name: the screen field is the newer bar's", NAVIGATION_BAR, newerSearch.screenField.definingClass)
                assertEquals("$name: the screen field holds a name", "Ljava/lang/String;", newerSearch.screenField.type)

                // Apply the whole hook set the way the patch does: settings has already sent the logo's
                // touch listener through its stand-in when this patch runs.
                val context = PatchContexts.of(listOf(bar, button) + newer.classes)
                val mutableBar = context.mutableClassDefBy(WORDMARK_NAVIGATION_BAR)
                val mutableLogo = mutableBar.findMutableMethodOf(logoBuilder)
                mutableLogo.sendToStandIn(logoTouchListenerIndex(logoBuilder), LOGO_TOUCH_STAND_IN)
                val logoBefore = mutableLogo.code().size
                val glyphBefore = mutableBar.findMutableMethodOf(glyphBuilder).code().size
                // this.name: a bare name would be the fixture's, the local above.
                fun Method.isLike(other: Method) =
                    this.name == other.name && parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)
                val sameMethod = glyphBuilder.isLike(logoBuilder)
                val createInLogo = createBuilder.isLike(logoBuilder)
                val searchInLogo = searchBuilder.isLike(logoBuilder)
                val createBefore = mutableBar.findMutableMethodOf(createBuilder).code().size
                val searchInGlyph = searchBuilder.isLike(glyphBuilder)
                val searchBefore = mutableBar.findMutableMethodOf(searchBuilder).code().size
                val newerSearchBefore = context.mutableClassDefBy(NAVIGATION_BAR).findMutableMethodOf(newerSearch.method).code()
                    .count { it.opcode != Opcode.NOP }
                val countBefore = counts.map { c ->
                    context.mutableClassDefBy(button.type).methods.single {
                        it.name == c.name && it.parameterTypes.map(CharSequence::toString) == c.parameters
                    }.code().size
                }
                context.hideTopBarButtons()

                val patchedBar = context.mutableClassDefBy(WORDMARK_NAVIGATION_BAR)
                val builderAfter = patchedBar.findMutableMethodOf(glyphBuilder).code()
                val buttonHooks = builderAfter.withIndex().filter { it.value.call() == HIDE_MESSENGER_BUTTON_HOOK }
                assertEquals("$name: button hooks", 1, buttonHooks.size)
                val hookAt = buttonHooks.single().index
                assertEquals("$name: the button hook is range invoke-static", Opcode.INVOKE_STATIC_RANGE, builderAfter[hookAt].opcode)
                assertEquals("$name: the button hook hands over the register addView took",
                    site.register, (builderAfter[hookAt] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the hook sits right after the addView", ADD_VIEW_CALL, builderAfter[hookAt - 1].call())

                val logoAfter = patchedBar.findMutableMethodOf(logoBuilder).code()
                val logoHooks = logoAfter.withIndex().filter { it.value.call() == HIDE_FACEBOOK_LOGO_HOOK }
                assertEquals("$name: logo hooks", 1, logoHooks.size)
                assertEquals("$name: the logo hook is range invoke-static", Opcode.INVOKE_STATIC_RANGE, logoAfter[logoHooks.single().index].opcode)
                val expected = (if (sameMethod) glyphBefore else logoBefore) + (if (sameMethod) 2 else 1) + (if (createInLogo) 1 else 0) +
                    (if (searchInLogo) 1 else 0)
                assertEquals("$name: the builder grew by its hooks only", expected, logoAfter.size)

                val createAfter = patchedBar.findMutableMethodOf(createBuilder).code()
                val createHooks = createAfter.withIndex().filter { it.value.call() == HIDE_CREATE_BUTTON_HOOK }
                assertEquals("$name: Create button hooks", 1, createHooks.size)
                val createAt = createHooks.single().index
                assertEquals("$name: the Create hook is range invoke-static", Opcode.INVOKE_STATIC_RANGE, createAfter[createAt].opcode)
                assertEquals("$name: the Create hook hands over the register addView took",
                    create.register, (createAfter[createAt] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the Create hook sits right after the addView", ADD_VIEW_CALL, createAfter[createAt - 1].call())
                assertEquals("$name: the Create hook comes after the button's own field load",
                    create.field.toString(), (createAfter[createAt - 2] as ReferenceInstruction).reference.toString())
                if (!createBuilder.isLike(glyphBuilder) && !createInLogo && !createBuilder.isLike(searchBuilder)) {
                    assertEquals("$name: the Create builder grew by its hook only", createBefore + 1, createAfter.size)
                }

                val searchAfter = patchedBar.findMutableMethodOf(searchBuilder).code()
                val searchHooks = searchAfter.withIndex().filter { it.value.call() == HIDE_SEARCH_BUTTON_HOOK }
                assertEquals("$name: search button hooks", 1, searchHooks.size)
                val searchAt = searchHooks.single().index
                assertEquals("$name: the search hook is range invoke-static", Opcode.INVOKE_STATIC_RANGE, searchAfter[searchAt].opcode)
                assertEquals("$name: the search hook hands over the register addView took",
                    search.register, (searchAfter[searchAt] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the search hook sits right after the addView", ADD_VIEW_CALL, searchAfter[searchAt - 1].call())
                assertEquals("$name: the search hook comes after the button's own field load",
                    search.field.toString(), (searchAfter[searchAt - 2] as ReferenceInstruction).reference.toString())
                if (!searchInGlyph && !searchInLogo && !createBuilder.isLike(searchBuilder)) {
                    assertEquals("$name: the search builder grew by its hook only", searchBefore + 1, searchAfter.size)
                }

                // The newer bar's search glyph: right after the bar keeps it, the bar's screen name is read
                // and the hook gets both.
                val newerSearchAfter = context.mutableClassDefBy(NAVIGATION_BAR).findMutableMethodOf(newerSearch.method).code()
                // Not counting nops: the method ends in a switch payload, which the hook can shift off its alignment.
                assertEquals("$name: the newer search method grew by its read and hook only", newerSearchBefore + 2,
                    newerSearchAfter.count { it.opcode != Opcode.NOP })
                val screenRead = newerSearchAfter[newerSearch.kept + 1]
                assertEquals("$name: the screen name is read right after the iput-object", Opcode.IGET_OBJECT, screenRead.opcode)
                assertEquals("$name: the read takes the bar's screen field", newerSearch.screenField.name,
                    ((screenRead as ReferenceInstruction).reference as FieldReference).name)
                val newerHook = newerSearchAfter[newerSearch.kept + 2]
                assertEquals("$name: the newer search hook follows the read", BAR_SEARCH_BUTTON_HOOK, newerHook.call())
                assertEquals("$name: the newer search hook is invoke-static", Opcode.INVOKE_STATIC, newerHook.opcode)
                assertEquals("$name: the newer search hook hands over the glyph",
                    newerSearch.register, (newerHook as FiveRegisterInstruction).registerC)
                assertEquals("$name: the newer search hook hands over the name",
                    (screenRead as TwoRegisterInstruction).registerA, (newerHook as FiveRegisterInstruction).registerD)
                assertNotEquals("$name: the read doesn't overwrite the glyph", newerSearch.register, screenRead.registerA)
                assertEquals("$name: the newer search hook comes after the glyph's iput-object", Opcode.IPUT_OBJECT,
                    newerSearchAfter[newerSearch.kept].opcode)

                counts.forEachIndexed { i, c ->
                    val after = context.mutableClassDefBy(button.type).methods.single {
                        it.name == c.name && it.parameterTypes.map(CharSequence::toString) == c.parameters
                    }.code()
                    assertEquals("$name: ${c.name} grew by two", countBefore[i] + 2, after.size)
                    assertEquals("$name: ${c.name} asks first", HIDE_MESSENGER_COUNT_HOOK, after[0].call())
                    assertEquals("$name: ${c.name} keeps the answer in the int", Opcode.MOVE_RESULT, after[1].opcode)
                    assertEquals("$name: ${c.name} hands over the int", registerOf(context.mutableClassDefBy(button.type).methods.single {
                        it.name == c.name && it.parameterTypes.map(CharSequence::toString) == c.parameters
                    }, c.intRegister),
                        (after[0] as RegisterRangeInstruction).startRegister)
                }
                assertNotEquals("$name: the button class and the bar differ", site.buttonType, WORDMARK_NAVIGATION_BAR)
                checked += "$name"
            }
        }
        // 584 has an arm64 bundle only so far, so a declared build needs one fixture, not one per ABI.
        assertTrue("a declared build has no fixture, got $checked", versions.all { v -> checked.any { "-$v-" in it } })
    }

    /** The newer bar, its button glyph class and the interfaces the glyph implements, out of [bundle]. */
    private class NewerBar(val bar: ClassDef, val glyph: ClassDef, val faces: List<ClassDef>) {
        val classes get() = listOf(bar, glyph) + faces
    }

    private fun newerBar(bundle: File): NewerBar {
        val bar = FixtureDex.classes(bundle, setOf(NAVIGATION_BAR))[NAVIGATION_BAR]
            ?: throw AssertionError("${bundle.name}: no $NAVIGATION_BAR")
        val glyphType = specButtonSite(bar).glyphType
        val glyph = FixtureDex.classes(bundle, setOf(glyphType)).getValue(glyphType)
        return NewerBar(bar, glyph, FixtureDex.classes(bundle, glyph.interfaces.toSet()).values.toList())
    }

    @Test
    fun `each declared build resolves the newer bar's Messenger glyph and its counts and takes the hooks`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableListOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val newer = newerBar(bundle)
                val site = specButtonSite(newer.bar)
                val code = site.method.code()
                assertEquals("$name: the glyph is a View", "Landroid/view/View;", newer.glyph.superclass)

                // Positive control for the name: a spec builder loads "messenger" and returns the
                // spec type the button method takes, and the name field is that spec's.
                val spec = site.method.parameterTypes.single().toString()
                assertEquals("$name: the name field is the spec's", spec, site.nameField.definingClass)
                val builders = FixtureDex.classesHolding(bundle, MESSENGER_SPEC).flatMap { it.methods }
                    .filter { it.returnType == spec && holdsString(it, MESSENGER_SPEC) }
                assertTrue("$name: no method loads \"$MESSENGER_SPEC\" and returns $spec", builders.isNotEmpty())
                // The same for the Create button's spec (#257).
                val createBuilders = FixtureDex.classesHolding(bundle, CREATE_SPEC).flatMap { it.methods }
                    .filter { it.returnType == spec && holdsString(it, CREATE_SPEC) }
                assertTrue("$name: no method loads \"$CREATE_SPEC\" and returns $spec", createBuilders.isNotEmpty())

                val counts = glyphCountMethods(newer.glyph, newer.faces)
                assertEquals("$name: the (I)V setter's int", 1, counts[0].intRegister)
                assertEquals("$name: the session setter's int", 2, counts[1].intRegister)
                // The glyph gets its first count in the button method after it is made, through the
                // (I)V setter: that's why the button hook has to sit right after the constructor.
                val firstCount = code.indexOfFirst { it.call() == "${site.glyphType}->${counts[0].name}(I)V" }
                assertTrue("$name: the first count follows the glyph's constructor", firstCount > site.made)

                val context = PatchContexts.of(newer.classes)
                val before = context.mutableClassDefBy(NAVIGATION_BAR).findMutableMethodOf(site.method).code().size
                val countBefore = counts.map { c -> countMethod(context, site.glyphType, c).code().size }
                context.run {
                    val bar = mutableClassDefBy(NAVIGATION_BAR)
                    bar.findMutableMethodOf(site.method).markSpecButtonAfter(site)
                    val glyph = mutableClassDefBy(site.glyphType)
                    counts.forEach { c -> glyph.methods.single { it.name == c.name && it.parameterTypes.map(CharSequence::toString) == c.parameters }.gateGlyphCountFirst(c.intRegister) }
                }

                val after = context.mutableClassDefBy(NAVIGATION_BAR).findMutableMethodOf(site.method).code()
                assertEquals("$name: the button method grew by the hook only", before + 2, after.size)
                val hookAt = after.indexOfFirst { it.call() == BAR_BUTTON_HOOK }
                assertEquals("$name: the hook sits right after the name load after the constructor", site.made + 2, hookAt)
                val load = after[site.made + 1]
                assertEquals("$name: the name is loaded first", Opcode.IGET_OBJECT, load.opcode)
                assertEquals("$name: off the spec's name field", site.nameField.toString(), (load as ReferenceInstruction).reference.toString())
                val hook = after[hookAt] as FiveRegisterInstruction
                assertEquals("$name: the glyph goes first", site.glyph, hook.registerC)
                assertEquals("$name: the name goes second", (load as OneRegisterInstruction).registerA, hook.registerD)
                assertNotEquals("$name: the name doesn't overwrite the glyph", site.glyph, load.registerA)

                counts.forEachIndexed { i, c ->
                    val method = countMethod(context, site.glyphType, c)
                    val body = method.code()
                    assertEquals("$name: ${c.name} grew by two", countBefore[i] + 2, body.size)
                    assertEquals("$name: ${c.name} asks first", BAR_BUTTON_COUNT_HOOK, body[0].call())
                    val ask = body[0] as FiveRegisterInstruction
                    assertEquals("$name: ${c.name} hands over the glyph", registerOf(method, 0), ask.registerC)
                    assertEquals("$name: ${c.name} hands over the int", registerOf(method, c.intRegister), ask.registerD)
                    assertEquals("$name: ${c.name} keeps the answer in the int", Opcode.MOVE_RESULT, body[1].opcode)
                }
                checked += name
            }
        }
        assertTrue("a declared build has no fixture, got $checked", versions.all { v -> checked.any { "-$v-" in it } })
    }

    private fun countMethod(context: BytecodePatchContext, type: String, count: CountMethod) =
        context.mutableClassDefBy(type).methods.single {
            it.name == count.name && it.parameterTypes.map(CharSequence::toString) == count.parameters
        }

    /** The register smali calls p[parameter]: the method's registers less its parameter registers (this included), plus parameter. */
    private fun registerOf(method: com.android.tools.smali.dexlib2.iface.Method, parameter: Int): Int =
        method.implementation!!.registerCount - (method.parameterTypes.size + 1) + parameter

    @Test
    fun `the extension has the three hooks as public statics`() {
        val calls = ExtensionDex.classDef(TOP_BAR_BUTTONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(
            HIDE_MESSENGER_BUTTON_HOOK, HIDE_MESSENGER_COUNT_HOOK, HIDE_FACEBOOK_LOGO_HOOK, BAR_BUTTON_HOOK, BAR_BUTTON_COUNT_HOOK,
            HIDE_CREATE_BUTTON_HOOK, HIDE_SEARCH_BUTTON_HOOK, BAR_SEARCH_BUTTON_HOOK,
        )) {
            assertTrue("the extension declares no public static $hook: $calls", hook in calls)
        }
        assertEquals("the extension looks for the screen name the patch checked for",
            HOME_BAR_SCREEN, ExtensionDex.stringConstant(TOP_BAR_BUTTONS, "HOME_BAR_SCREEN"))
        assertEquals("the extension looks for the spec name the fixtures hold",
            MESSENGER_SPEC, ExtensionDex.stringConstant(TOP_BAR_BUTTONS, "MESSENGER_SPEC"))
        assertEquals("the extension looks for the Create spec name the fixtures hold",
            CREATE_SPEC, ExtensionDex.stringConstant(TOP_BAR_BUTTONS, "CREATE_SPEC"))
    }

    private companion object {
        const val ADD_VIEW_CALL = "Landroid/view/ViewGroup;->addView(Landroid/view/View;)V"
        const val MESSENGER_SPEC = "messenger"
        const val CREATE_SPEC = "trep-composer-button"
    }
}
