/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route six of Accent color: the profile's Add to story button is a Bloks box and its bio link is
 * Bloks text, and both colours come from Facebook's server as hex strings, so neither a resolver nor
 * a resource gives them. On each declared build the box builder beside BoxDecoration's border
 * reader has to hand its fill to AccentColor.bloksFill before it sets the fill Paint's colour, and
 * the text span builder has to hand its colour to AccentColor.bloksText before it makes the span.
 * AMOLED hooks the same builder for its Light background color (#214), with AmoledTheme.bloksFill,
 * and Material You for light mode's surface greys like the Settings search field (#141) and dark
 * mode's greys like the place box under a check-in post (#37).
 *
 * Read from 582 (2026-10-10): the border reader is `LX/4Nl;->A00(LX/4TW;LX/1Vp;)I`, the builder
 * `LX/4Nl;->A01(LX/4TW;LX/1Vp;I)LX/4Nm;`, whose drawable fills with Paint `A05`, the themed colour
 * reader `LX/4MD;->A00` and the text span builder `LX/4MC;->A02`. None of those names is used here.
 */
private const val COLOR_DRAWABLE = "Landroid/graphics/drawable/ColorDrawable;-><init>(I)V"

class BloksFillFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    @After
    fun forgetTheLastMatch() {
        BoxDecorationBorderFingerprint.clearMatch()
        NativeTemplateBuilderFingerprint.clearMatch()
    }

    @Test
    fun `each Bloks box's fill goes through the accent before it's painted, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, BOX_DECORATION_BORDER_ERROR)
                assertEquals("$name: one class logs a bad BoxDecoration border", 1, owners.size)
                val box = owners.single()
                val borders = box.methods.filter { holdsString(it, BOX_DECORATION_BORDER_ERROR) }
                assertEquals("$name: one border reader", 1, borders.size)
                val border = borders.single()
                assertEquals("$name: the border reader answers a colour", "I", border.returnType)

                // The builder, found here without the patch's own search: a static method that takes
                // the reader's parameters and an int, and sets a Paint's colour to that int.
                val builders = box.methods.filter { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 3 &&
                        method.parameterTypes.last().toString() == "I" &&
                        method.body().any { it.setsPaintColour(method.parameterRegisterNumber(2)) }
                }
                assertEquals("$name: one builder paints its int parameter", 1, builders.size)
                val builder = builders.single()
                assertTrue("$name: the patch's search finds the same builder", isBoxBuilder(builder, border))
                assertEquals("$name: and only it", 1, box.methods.count { isBoxBuilder(it, border) })
                val fill = builder.parameterRegisterNumber(2)

                // Accent color's route six, AMOLED's light mode surfaces (#214) and Material You's
                // light and dark mode greys (#141, #37) on the same builder.
                for (target in listOf(ACCENT_BLOKS_FILL, AMOLED_BLOKS_FILL, MATERIAL_YOU_BLOKS_FILL)) {
                    BoxDecorationBorderFingerprint.clearMatch()
                    val context = PatchContexts.of(listOf(box))
                    with(context) { hookBloksFills(target) }
                    val after = context.mutableClassDefBy(box.type).methods.single { it.descriptor() == builder.descriptor() }.body()

                    val hook = after[0]
                    assertEquals("$name: the builder starts with $target", target,
                        ((hook as ReferenceInstruction).reference as MethodReference).toString())
                    assertEquals("$name: on the fill's register", fill, (hook as RegisterRangeInstruction).startRegister)
                    assertEquals("$name: one register", 1, hook.registerCount)
                    assertEquals("$name: and the answer", Opcode.MOVE_RESULT, after[1].opcode)
                    assertEquals("$name: goes back into the fill", fill, (after[1] as OneRegisterInstruction).registerA)
                    assertEquals("$name: the rest of the builder is as it was", builder.body().size + 2, after.size)
                    assertTrue("$name: and still paints the fill", after.drop(2).any { it.setsPaintColour(fill) })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each Bloks text span's colour goes through the accent before the span is made, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val box = FixtureDex.classesHolding(bundle, BOX_DECORATION_BORDER_ERROR).single()
                val border = box.methods.single { holdsString(it, BOX_DECORATION_BORDER_ERROR) }
                val reader = themedColourReader(border)
                assertEquals("$name: the themed reader answers a colour", "I", reader.returnType)

                val builders = FixtureDex.methodsWhere(bundle, { true }) { isBloksTextBuilder(it, reader) }
                assertEquals("$name: one Bloks text span builder", 1, builders.size)
                val builder = builders.single()
                val init = builder.body().indexOfFirst { it.makesColourSpan() }
                val colour = (builder.body()[init] as FiveRegisterInstruction).registerD
                // The colour comes from the reader: its answer is read into the span's register before the span is made.
                val asked = builder.body().take(init).withIndex().any { (index, instruction) ->
                    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    call?.name == reader.name && call.definingClass == reader.definingClass &&
                        (builder.body()[index + 1] as? OneRegisterInstruction)?.registerA == colour
                }
                assertTrue("$name: the span's colour is the themed reader's answer", asked)

                BoxDecorationBorderFingerprint.clearMatch()
                val owner = FixtureDex.classes(bundle, setOf(builder.definingClass))[builder.definingClass]!!
                val context = PatchContexts.of(listOf(box, owner))
                with(context) { hookBloksText() }
                val after = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == builder.descriptor() }.body()

                assertEquals("$name: the span is still made first", Opcode.NEW_INSTANCE, after[init - 1].opcode)
                val hook = after[init]
                assertEquals("$name: then the colour goes to bloksText", ACCENT_BLOKS_TEXT,
                    ((hook as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: on its own register", colour, (hook as RegisterRangeInstruction).startRegister)
                assertEquals("$name: one register", 1, hook.registerCount)
                assertEquals("$name: and comes back there", colour, (after[init + 1] as OneRegisterInstruction).registerA)
                assertTrue("$name: before the span's constructor", after[init + 2].makesColourSpan())
                assertEquals("$name: the rest of the builder is as it was", builder.body().size + 2, after.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The Menu's profile card in light mode (#214) is a native template box. Its server colour never
     * reaches the box builder: the template builder that traces [NATIVE_TEMPLATE_BOX], and the child
     * builder it hands its own parameters to, give it to Litho's backgroundColor (584 `LX/3T3;->A1H`),
     * which wraps it in a ColorDrawable. Each of those colours has to go through AmoledTheme.bloksFill
     * just before the call, on the call's own register. On 585 the builder is a static taking the
     * node's kind as a trailing int, and the child builder takes the other parameters. On 586 the
     * child builder sets nothing itself and a static decorator it hands its component to does.
     */
    @Test
    fun `each native template box's background goes through the light background before it's set, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, NATIVE_TEMPLATE_BOX)
                assertEquals("$name: one class traces a native template box", 1, owners.size)
                val owner = owners.single()
                val builder = owner.methods.single { holdsString(it, NATIVE_TEMPLATE_BOX) }
                val parameters = builder.parameterTypes.map(CharSequence::toString)
                // 585's static builder takes the node's kind as a trailing int its child builder doesn't take.
                val childShapes = setOf(parameters, parameters.dropLastWhile { it == "I" })
                val childCalls = builder.body()
                    .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
                    .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
                    .filter { it.returnType == builder.returnType && it.parameterTypes.map(CharSequence::toString) in childShapes }
                    .filterNot { it.toString() == builder.descriptor() }
                    .distinctBy { it.toString() }
                assertTrue("$name: the builder hands its parameters to its child builders", childCalls.isNotEmpty())
                val childClasses = FixtureDex.classes(bundle, childCalls.map { it.definingClass }.toSet()).values.toList()
                val children = childCalls.map { call -> childClasses.flatMap { it.methods }.single { it.descriptor() == call.toString() } }
                // 586's child builder hands the component it built to a static decorator that sets the background.
                val decoratorCalls = children.flatMap { decoratorsOf(it, builder.returnType) }.distinctBy { it.toString() }
                    .filterNot { call -> childCalls.any { it.toString() == call.toString() } }
                val decoratorClasses = FixtureDex.classes(bundle, decoratorCalls.map { it.definingClass }.toSet()).values.toList()
                val decorators = decoratorCalls.map { call -> decoratorClasses.flatMap { it.methods }.single { it.descriptor() == call.toString() } }

                // Litho's component builder and the classes above it, as far as the bundle has them.
                val chain = mutableListOf<ClassDef>()
                var type: String? = builder.returnType
                while (type != null && chain.none { it.type == type }) {
                    val classDef = FixtureDex.classes(bundle, setOf(type))[type] ?: break
                    chain += classDef
                    type = classDef.superclass
                }
                val colourSetters = chain.flatMap { it.methods }.filter { method ->
                    method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == listOf("I") &&
                        method.body().any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == COLOR_DRAWABLE }
                }.map { it.name }.toSet()
                assertTrue("$name: Litho's component builder wraps a colour in a ColorDrawable", colourSetters.isNotEmpty())
                fun Instruction.setsBackground() = opcode == Opcode.INVOKE_VIRTUAL &&
                    ((this as ReferenceInstruction).reference as MethodReference).let { call ->
                        call.name in colourSetters && call.parameterTypes.map(CharSequence::toString) == listOf("I") &&
                            chain.any { it.type == call.definingClass }
                    }

                NativeTemplateBuilderFingerprint.clearMatch()
                val context = PatchContexts.of((listOf(owner) + childClasses + decoratorClasses + chain).distinctBy { it.type })
                with(context) { hookNativeTemplateBackgrounds() }

                var sites = 0
                for (method in listOf(builder) + children + decorators) {
                    val before = method.body()
                    val after = context.mutableClassDefBy(method.definingClass).methods.single { it.descriptor() == method.descriptor() }.body()
                    val calls = before.count { it.setsBackground() }
                    if (method == builder) assertTrue("$name: the box builder sets a background colour", calls > 0)
                    assertEquals("$name ${method.descriptor()}: two more instructions per colour", before.size + 2 * calls, after.size)
                    after.withIndex().filter { (_, instruction) -> instruction.setsBackground() }.forEach { (index, call) ->
                        val colour = (call as FiveRegisterInstruction).registerD
                        val hook = after[index - 2]
                        assertEquals("$name: the colour goes to bloksFill first", AMOLED_BLOKS_FILL,
                            ((hook as ReferenceInstruction).reference as MethodReference).toString())
                        assertEquals("$name: on the colour's register", colour, (hook as RegisterRangeInstruction).startRegister)
                        assertEquals("$name: one register", 1, hook.registerCount)
                        assertEquals("$name: and comes back there", colour, (after[index - 1] as OneRegisterInstruction).registerA)
                    }
                    sites += calls
                }
                assertTrue("$name: both builders' backgrounds are hooked", sites >= 2)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Instruction.setsPaintColour(register: Int): Boolean {
        val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return call.definingClass == "Landroid/graphics/Paint;" && call.name == "setColor" &&
            (this as FiveRegisterInstruction).registerD == register
    }
}
