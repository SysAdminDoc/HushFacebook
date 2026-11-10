/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dark box behind open captions (#419) on every Facebook build the bundle declares: the one static
 * method building a reel caption's text, the Litho text builder it sets the line limit on, and
 * Litho's background style. Then the patch on them: a helper on the method's class gets the builder
 * right before it's built, reads its line limit, and hands the extension's drawable to the builder as
 * a background style. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class CaptionBackdropFixtureTest {
    /** Where one build keeps the caption's builder method, its text builder, the style type and the background style. */
    private data class Place(val host: String, val builder: String, val style: String, val background: String)

    /** Each declared build's places, by ABI, read from the dex with the probe. */
    private val expected = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to Place("LX/08vv;", "LX/042D;", "LX/01Vy;", "LX/02fC;"),
        ),
    )

    /** Each declared build's class holding the caption's builder method, by ABI, read from the dex with the probe. */
    private val expectedHost = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to "LX/08vv;",
            "armeabi-v7a" to "LX/08vv;",
            "arm64-v8a-minapi28-storeless" to "LX/08xY;",
        ),
    )

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.names(call: String) = (this as? ReferenceInstruction)?.reference?.toString() == call

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun captionBuilder(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags) &&
        loadsString(method, CAPTION_COMPONENT) && loadsString(method, CAPTION_ROLE) && loadsString(method, CAPTION_DESCRIPTION)

    /** Every static method shaped like a background style anywhere in the app: a drawable and a style in, that style out. */
    private fun backgroundShaped(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.size == 2 && method.parameterTypes[0].toString() == DRAWABLE &&
        method.parameterTypes[1].toString() == method.returnType && method.returnType.startsWith("LX/")

    /** [type] and its superclasses the bundle carries. */
    private fun hierarchy(bundle: File, type: String): Map<String, ClassDef> {
        val found = linkedMapOf<String, ClassDef>()
        var next: String? = type
        while (next != null && next !in found) {
            val classDef = FixtureDex.classes(bundle, setOf(next))[next] ?: break
            found[next] = classDef
            next = classDef.superclass
        }
        return found
    }

    @Test
    fun `each declared build builds a reel caption once, and an open one gets its background through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val abi = name.substringAfter("-$version-").substringBefore(".apkm")
                val found = FixtureDex.methodsWhere(bundle, { true }) {
                    !it.definingClass.startsWith(EXTENSION_CLASSES) && (captionBuilder(it) || backgroundShaped(it))
                }
                val builders = found.filter(::captionBuilder)
                assertEquals("$name: static methods building a reel caption's text", 1, builders.size)
                val host = builders.single().definingClass
                assertEquals("$name: the caption's builder method's class", expectedHost[version]?.get(abi), host)
                val limit = builders.single().code().single {
                    it.opcode == Opcode.IPUT && ((it as ReferenceInstruction).reference as FieldReference).type == "I"
                }
                val builderType = ((limit as ReferenceInstruction).reference as FieldReference).definingClass
                val chain = hierarchy(bundle, builderType)
                val backgroundClasses = found.filter(::backgroundShaped).map { it.definingClass }.toSet()
                val styleTypes = found.filter(::backgroundShaped).map { it.returnType }.toSet()
                val styles = FixtureDex.classes(bundle, styleTypes)
                val emptyStyles = styles.values.flatMap { it.staticFields }.map { it.type }.filter { it.startsWith("LX/") }.toSet()
                val classes = FixtureDex.classes(bundle, setOf(host) + backgroundClasses + emptyStyles) + chain + styles
                val extension = listOf(ExtensionDex.classDef(CAPTION_BACKDROP))

                val context = PatchContexts.of(classes.values.toList() + extension)
                val backdrop = context.findCaptionBackdrop()
                val background = backdrop.background.substringBefore("->")
                val style = backdrop.emptyStyle.substringBefore("->")
                expected[version]?.get(abi)?.let { place ->
                    assertEquals("$name: where the caption's text and its background style are built", place,
                        Place(backdrop.builder.definingClass, backdrop.builderType, style, background))
                }
                assertEquals("$name: the caption's builder method", host, backdrop.builder.definingClass)
                assertEquals("$name: the text builder", builderType, backdrop.builderType)
                assertTrue("$name: the setter is on the text builder's classes", backdrop.setter.substringBefore("->") in chain)
                assertEquals("$name: the setter takes the style", "($style)V", backdrop.setter.substringAfter(backdrop.setter.substringBefore("(")))

                // Negative control: without Litho's background style, nothing is hooked.
                val bare = PatchContexts.of(classes.filterKeys { it != background }.values.toList() + extension)
                assertThrows("$name: a background style was found without its class", PatchException::class.java) {
                    bare.findCaptionBackdrop()
                }

                val original = backdrop.builder.code()
                val build = original[backdrop.buildIndex]
                context.addCaptionBackdrop(backdrop)

                // The helper reads the line limit, asks the extension, and sets a drawable as a background style.
                val helper = context.mutableClassDefBy(host).methods.singleOrNull { it.name == BACKDROP_HELPER }
                assertNotNull("$name: no helper on $host", helper)
                assertEquals("$name: the helper takes the text builder", listOf(builderType), helper!!.parameterTypes.map { it.toString() })
                val helperCode = helper.code()
                assertEquals("$name: the helper reads the line limit first", backdrop.maxLines,
                    (helperCode[0] as ReferenceInstruction).reference.toString())
                assertTrue("$name: the helper asks the extension", helperCode[1].names(FOR_CAPTION))
                assertEquals("$name: a null answer leaves the caption alone", Opcode.IF_EQZ, helperCode[3].opcode)
                assertTrue("$name: the empty style under the background", helperCode[4].names(backdrop.emptyStyle))
                assertTrue("$name: the background style is made", helperCode[5].names(backdrop.background))
                assertTrue("$name: the style goes on the builder", helperCode[7].names(backdrop.setter))
                assertEquals(Opcode.RETURN_VOID, helperCode.last().opcode)

                // The builder goes through the helper right before it's built, and nothing else moves.
                val patched = context.mutableClassDefBy(host).methods.single {
                    it.name == backdrop.builder.name && it.parameterTypes == backdrop.builder.parameterTypes
                }.code()
                assertEquals("$name: one instruction for the hook", original.size + 1, patched.size)
                val call = patched[backdrop.buildIndex]
                assertTrue("$name: the hook calls the helper", call.names("$host->$BACKDROP_HELPER($builderType)V"))
                assertEquals("$name: the text builder handed over", backdrop.register to 1,
                    (call as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
                assertEquals("$name: the build right after", (build as ReferenceInstruction).reference.toString(),
                    (patched[backdrop.buildIndex + 1] as ReferenceInstruction).reference.toString())
                assertEquals("$name: the rest is Facebook's", original.map { it.opcode },
                    patched.filterIndexed { at, _ -> at != backdrop.buildIndex }.map { it.opcode })

                val inBuild = context.mutableClassDefBy(CAPTION_BACKDROP).methods.single { it.name == "inBuild" }.code()
                assertEquals("$name: CaptionBackdrop.inBuild() isn't switched on", 1,
                    (inBuild[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
