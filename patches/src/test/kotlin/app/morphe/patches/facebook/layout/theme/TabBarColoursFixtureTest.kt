/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Material You's selected tab on every Facebook build the bundle declares: the one colour
 * TabBarContainerLayout asks its abstract colour provider for and paints the selected tab's line
 * with, and its two readers, the layout and the tab icon's tint method, which picks between it and
 * the unselected colour and puts it on the icon. Then the hook on those classes: the extension
 * right after each move-result on the same register, nothing else moved. Reads the fixture bundles
 * from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TabBarColoursFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build asks one selected tab colour, read by the layout and the tab icons`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val layout = FixtureDex.classes(bundle, setOf(TAB_BAR_CONTAINER)).getValue(TAB_BAR_CONTAINER)
                // Next to last before the tab id, or last since 587 dropped the tab id.
                val providers = FixtureDex.classes(bundle, layout.methods.mapNotNull { method ->
                    method.parameterTypes.map(CharSequence::toString).takeIf { method.returnType == "V" && it.isNotEmpty() }
                        ?.let { if (it.last() == "J") it.getOrNull(it.size - 2) else it.last() }?.takeIf { it.startsWith("L") }
                }.toSet())
                val colour = selectedTabColour(layout) { type ->
                    providers[type]?.let { AccessFlags.ABSTRACT.isSet(it.accessFlags) && !AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
                }
                val provider = providers.getValue(colour.definingClass)
                // The provider's own virtual method, which its subclasses answer. 581 left it abstract;
                // 582's Redex moved one subclass's body into the provider and the other overrides it.
                val declared = provider.methods.single {
                    it.name == colour.name && it.parameterTypes.map(CharSequence::toString) == colour.parameterTypes.map(CharSequence::toString)
                }
                assertTrue("$name: $colour isn't a method the provider's subclasses can answer",
                    listOf(AccessFlags.STATIC, AccessFlags.PRIVATE, AccessFlags.FINAL).none { it.isSet(declared.accessFlags) })

                val readers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == colour.toString() } }) {
                    selectedTabColourReads(it, colour).isNotEmpty()
                }
                assertEquals("$name: the selected tab colour's readers", 2, readers.size)
                assertEquals("$name: the layout reads it once", 1, readers.count { it.definingClass == TAB_BAR_CONTAINER })
                // The tab icon's tint: static, told whether its tab is selected, asks the provider for
                // the other colour when it isn't, and filters the icon's drawable with the answer.
                val tint = readers.single { it.definingClass != TAB_BAR_CONTAINER }
                assertTrue("$name: ${tint.definingClass}->${tint.name} isn't static", AccessFlags.STATIC.isSet(tint.accessFlags))
                assertTrue("$name: the tint isn't told whether its tab is selected", "Z" in tint.parameterTypes.map(CharSequence::toString))
                val tintCalls = tint.code().mapNotNull { it.called() }
                assertTrue("$name: the tint asks for no unselected colour", tintCalls.any {
                    it.definingClass == colour.definingClass && it.returnType == "I" && it.name != colour.name
                })
                assertTrue("$name: the tint colours no drawable", tintCalls.any {
                    it.toString() == "Landroid/graphics/drawable/Drawable;->setColorFilter(Landroid/graphics/ColorFilter;)V"
                })

                // The hook also needs the inflater that first fills the line's Paint, or it refuses.
                val paint = lineColourPaint(layout)
                val inflaters = FixtureDex.methodsWhere(bundle, { dex -> dex.fieldSection.any { it.toString() == paint.toString() } }) {
                    it.definingClass != TAB_BAR_CONTAINER && lineColourReads(it, paint).isNotEmpty()
                }
                val owners = FixtureDex.classes(bundle, (readers + inflaters).map { it.definingClass }.toSet())
                val pool: Collection<ClassDef> = (owners.values + provider + layout).associateBy { it.type }.values
                val context = PatchContexts.of(pool)
                with(context) { selectedTabColourHook()() }
                for (reader in readers) {
                    val where = "$name: ${reader.definingClass}->${reader.name}"
                    val original = reader.code()
                    val at = selectedTabColourReads(reader, colour).single() + 1
                    val register = (original[at - 1] as OneRegisterInstruction).registerA
                    val patched = context.mutableClassDefBy(reader.definingClass).methods.single { it.sameAs(reader) }.code()
                    assertEquals("$where gains two instructions", original.size + 2, patched.size)
                    assertEquals("$where: Facebook's code up to the read stays", original.take(at).map { it.opcode },
                        patched.take(at).map { it.opcode })
                    assertEquals("$where: the extension is asked", Opcode.INVOKE_STATIC_RANGE, patched[at].opcode)
                    assertEquals("$where: the extension is asked", TAB_BAR_SELECTED, patched[at].called().toString())
                    assertEquals("$where: with the colour", register, (patched[at] as RegisterRangeInstruction).startRegister)
                    assertEquals("$where: its answer goes back in the same register", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                    assertEquals("$where: its answer goes back in the same register", register,
                        (patched[at + 1] as OneRegisterInstruction).registerA)
                    assertEquals("$where: Facebook's code after the hook stays", original.drop(at).map { it.opcode },
                        patched.drop(at + 2).map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `each declared build fills the line's paint from a token as the bar inflates, and the hook reaches it`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val layout = FixtureDex.classes(bundle, setOf(TAB_BAR_CONTAINER)).getValue(TAB_BAR_CONTAINER)
                val paint = lineColourPaint(layout)
                val inflaters = FixtureDex.methodsWhere(bundle, { dex -> dex.fieldSection.any { it.toString() == paint.toString() } }) {
                    lineColourReads(it, paint).isNotEmpty()
                }
                val colour = selectedTabColour(layout) { type ->
                    FixtureDex.classes(bundle, setOf(type))[type]?.let { AccessFlags.ABSTRACT.isSet(it.accessFlags) && !AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
                }
                // The provider's setter reads the Paint too, with the provider's own colour. 585 moved the
                // inflater into the layout as well, so the setter is the one that asks the provider.
                val setters = inflaters.filter { it.definingClass == TAB_BAR_CONTAINER && selectedTabColourReads(it, colour).isNotEmpty() }
                val inflater = inflaters.filter { candidate ->
                    setters.none { it.definingClass == candidate.definingClass && it.sameAs(candidate) }
                }.single()
                assertEquals("$name: the layout's own setter", 1, setters.size)
                val sites = lineColourReads(inflater, paint)
                assertEquals("$name: one colour goes on the line's Paint when it's made", 1, sites.size)
                val original = inflater.code()
                val at = sites.single() + 1
                val register = (original[at - 1] as OneRegisterInstruction).registerA
                val colourCall = original.take(at - 1).last { it.called() != null }.called()!!
                // 585's inflater asks a static resolver that takes FDSColors' own token instead of FDSColors.
                val colors = fdsColorsOf(bundle)
                val token = fdsColourSource(colors)!!.parameterTypes[1].toString()
                assertTrue("$name: the colour comes from a token resolver, not $colourCall", colourCall.definingClass == colors.type ||
                    token in colourCall.parameterTypes.map(CharSequence::toString))
                assertEquals("$name: the colour comes from a token resolver", "I", colourCall.returnType)

                val readers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == colour.toString() } }) {
                    selectedTabColourReads(it, colour).isNotEmpty()
                }
                val owners = FixtureDex.classes(bundle, (readers + inflater).map { it.definingClass }.toSet() + colour.definingClass)
                val context = PatchContexts.of((owners.values + layout).associateBy { it.type }.values)
                // Material You and Badge and logo colors both ask for the hook (#297); it goes in once.
                with(context) { selectedTabColourHook()() }
                with(context) { selectedTabColourHook()() }
                val patched = context.mutableClassDefBy(inflater.definingClass).methods.single { it.sameAs(inflater) }.code()
                val where = "$name: ${inflater.definingClass}->${inflater.name}"
                assertEquals("$where gains two instructions", original.size + 2, patched.size)
                assertEquals("$where: the extension is asked", Opcode.INVOKE_STATIC_RANGE, patched[at].opcode)
                assertEquals("$where: the extension is asked", TAB_BAR_SELECTED, patched[at].called().toString())
                assertEquals("$where: with the colour", register, (patched[at] as RegisterRangeInstruction).startRegister)
                assertEquals("$where: with the colour", 1, (patched[at] as RegisterRangeInstruction).registerCount)
                assertEquals("$where: its answer goes back in the same register", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                assertEquals("$where: its answer goes back in the same register", register, (patched[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$where: the Paint is read next", paint.toString(), (patched[at + 2] as ReferenceInstruction).reference.toString())
                assertEquals("$where: Facebook's code after the hook stays", original.drop(at).map { it.opcode }, patched.drop(at + 2).map { it.opcode })
                // The setter's colour is handed over once, not twice.
                val setter = setters.single()
                val patchedSetter = context.mutableClassDefBy(TAB_BAR_CONTAINER).methods.single { it.sameAs(setter) }.code()
                assertEquals("$name: the setter's Paint isn't hooked a second time", setter.code().size + 2, patchedSetter.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
