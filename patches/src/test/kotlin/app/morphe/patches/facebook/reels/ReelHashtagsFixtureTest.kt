/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.posttext.CAPTION_COMPONENT
import app.morphe.patches.facebook.feed.posttext.CAPTION_DESCRIPTION
import app.morphe.patches.facebook.feed.posttext.CAPTION_ROLE
import app.morphe.patches.facebook.feed.posttext.findCaptionBuild
import app.morphe.patches.facebook.feed.posttext.loadsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide hashtags in reel captions (#594) on every Facebook build the bundle declares: the one static
 * method building a reel caption's text hands the builder to a helper right after the caption goes
 * into it, and the helper puts back what the extension answers for that caption. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelHashtagsFixtureTest {
    private val declutter = "$EXTENSION_PACKAGE/reels/ReelDeclutter;"

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.names(call: String) = (this as? ReferenceInstruction)?.reference?.toString() == call

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun captionBuilder(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags) &&
        loadsString(method, CAPTION_COMPONENT) && loadsString(method, CAPTION_ROLE) && loadsString(method, CAPTION_DESCRIPTION)

    @Test
    fun `each declared build hands a reel caption to the extension right after its builder gets it`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val builders = FixtureDex.methodsWhere(bundle, { true }) {
                    !it.definingClass.startsWith(EXTENSION_CLASSES) && captionBuilder(it)
                }
                assertEquals("$name: static methods building a reel caption's text", 1, builders.size)
                val host = builders.single().definingClass
                val classes = FixtureDex.classes(bundle, setOf(host))
                val extension = ExtensionDex.classDef(declutter)

                // Negative control: an extension without the hook stops this part, and only this part.
                val bare = ImmutableClassDef(
                    extension.type, extension.accessFlags, extension.superclass, extension.interfaces, extension.sourceFile,
                    extension.annotations, extension.fields.toList(), extension.methods.filter { it.name != "captionText" },
                )
                assertThrows("$name: hooked with no captionText in the extension", PatchException::class.java) {
                    PatchContexts.of(classes.values.toList() + bare).hideCaptionHashtags()
                }

                val context = PatchContexts.of(classes.values.toList() + extension)
                val caption = context.findCaptionBuild()
                val original = caption.builder.code()
                val put = original[caption.textIndex]
                assertEquals("$name: the caption goes into the builder first", Opcode.IPUT_OBJECT, put.opcode)
                assertEquals("$name: into the builder's register", caption.register, (put as TwoRegisterInstruction).registerB)
                context.hideCaptionHashtags()

                // The helper reads the caption, asks the extension, and puts its answer back.
                val text = "${caption.text.definingClass}->${caption.text.name}:${caption.text.type}"
                val helper = context.mutableClassDefBy(host).methods.singleOrNull { it.name == HASHTAGS_HELPER }
                assertNotNull("$name: no helper on $host", helper)
                assertEquals("$name: the helper takes the text builder", listOf(caption.builderType),
                    helper!!.parameterTypes.map { it.toString() })
                val helperCode = helper.code()
                assertEquals("$name: the helper's body",
                    listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID),
                    helperCode.map { it.opcode })
                assertTrue("$name: it reads the caption", helperCode[0].names(text))
                assertTrue("$name: it asks the extension", helperCode[1].names(CAPTION_TEXT))
                assertTrue("$name: and puts the answer back", helperCode[3].names(text))

                // The builder goes through the helper right after the put, and nothing else moves.
                val patched = context.mutableClassDefBy(host).methods.single {
                    it.name == caption.builder.name && it.parameterTypes == caption.builder.parameterTypes
                }.code()
                assertEquals("$name: one instruction for the hook", original.size + 1, patched.size)
                val call = patched[caption.textIndex + 1]
                assertTrue("$name: the hook calls the helper", call.names("$host->$HASHTAGS_HELPER(${caption.builderType})V"))
                assertEquals("$name: the text builder handed over", caption.register to 1,
                    (call as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
                assertEquals("$name: the rest is Facebook's", original.map { it.opcode },
                    patched.filterIndexed { at, _ -> at != caption.textIndex + 1 }.map { it.opcode })

                // The dark box behind an open caption still finds the same build, one instruction on.
                val after = context.findCaptionBuild()
                assertEquals("$name: the caption's put", caption.textIndex, after.textIndex)
                assertEquals("$name: the caption's build", caption.buildIndex + 1, after.buildIndex)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
