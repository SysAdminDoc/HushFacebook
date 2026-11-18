/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.video

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.downloads.photo.MENU_LISTENER
import app.morphe.patches.facebook.downloads.photo.SAVE_PHOTO_ACTION
import app.morphe.patches.facebook.downloads.photo.isSavePhotoAction
import app.morphe.patches.facebook.downloads.photo.photoDownloadGates
import app.morphe.patches.facebook.downloads.photo.unlockPhotoSave
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo and video viewer's three-dot menu (#509), on the real build of every declared ABI. Its
 * builder is found through Save photo's kept action, and the row goes in where every filled menu
 * passes, whether or not Download any photo has already hooked the same method.
 */
class ViewerVideoMenuFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun makesTheAction(method: Method) =
        method.implementation?.instructions?.any { it.call()?.name == SAVE_PHOTO_ACTION } == true

    private fun redexName(classDef: ClassDef) = classDef.staticFields
        .firstOrNull { it.name == "__redex_internal_original_name" }
        ?.let { (it.initialValue as? StringEncodedValue)?.value }

    private fun kind(instruction: Instruction) = when (instruction.opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> instruction.opcode
    }

    private fun kinds(code: List<Instruction>) = code.map(::kind).filter { it != Opcode.NOP }

    /** The icon constant loaded for Save photo's row, read here without the patch's own walk. */
    private fun savePhotoIcon(code: List<Instruction>): Int {
        val shape = listOf(MENU, MENU_LISTENER, "I", "I", "I")
        val action = code.indexOfFirst { it.call()?.name == SAVE_PHOTO_ACTION }
        val row = (action + 1 until code.size).first { index ->
            code[index].opcode == Opcode.INVOKE_STATIC &&
                code[index].call()?.parameterTypes?.map(CharSequence::toString) == shape
        }
        val register = (code[row] as FiveRegisterInstruction).registerG
        return (row - 1 downTo action).firstNotNullOf { index ->
            val load = code[index]
            if (load is NarrowLiteralInstruction && (load as OneRegisterInstruction).registerA == register) {
                load.narrowLiteral
            } else {
                null
            }
        }
    }

    private fun assertRowAdded(name: String, context: BytecodePatchContext, stock: Method): List<Instruction> {
        val owner = context.mutableClassDefBy(stock.definingClass)
        val patched = owner.methods.single { it.name == stock.name && it.parameterTypes == stock.parameterTypes }
        val after = patched.implementation!!.instructions.toList()
        val end = after.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        val hook = after[end - 1]

        assertEquals("$name: the helper runs right before the last return", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals("$name: it's the helper", VIEWER_VIDEO_HELPER, hook.call()!!.name)
        assertEquals("$name: on the builder's class", stock.definingClass, hook.call()!!.definingClass)
        val p1 = stock.implementation!!.registerCount - MethodUtil.getParameterRegisterCount(stock) + 1
        assertEquals("$name: from the menu parameter", p1, (hook as RegisterRangeInstruction).startRegister)
        assertEquals("$name: through the viewer's item", 5, hook.registerCount)

        val flow = ControlFlow.of(patched)
        val into = after.indices.filter { end - 1 in flow.normal[it] || end - 1 in flow.exceptional[it] }
        assertEquals("$name: nothing jumps past or onto the hook", listOf(end - 2), into)

        val helper = owner.methods.single { it.name == VIEWER_VIDEO_HELPER }
        assertTrue("$name: the helper is static", AccessFlags.STATIC.isSet(helper.accessFlags))
        assertEquals(
            "$name: the helper takes the builder's first five parameters",
            stock.parameterTypes.take(5).map(CharSequence::toString),
            helper.parameterTypes.map(CharSequence::toString),
        )
        val body = helper.implementation!!.instructions.toList()
        val stockCode = stock.implementation!!.instructions.toList()
        assertEquals("$name: with Save photo's icon", savePhotoIcon(stockCode), (body[0] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$name: into the extension", ADD_VIEWER_ITEM, body[1].call().toString())
        assertEquals("$name: and back", Opcode.RETURN_VOID, body[2].opcode)
        return after.filterIndexed { index, _ -> index != end - 1 }
    }

    @Test
    fun `the viewer's menu gets the row at its end, on every declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val builders = mutableListOf<ClassDef>()
                val photoClasses = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        val builds = classDef.methods.any(::makesTheAction)
                        if (builds) builders += ImmutableClassDef.of(classDef)
                        if (builds || classDef.methods.any(::isSavePhotoAction) ||
                            classDef.methods.any { photoDownloadGates(it).isNotEmpty() }
                        ) {
                            photoClasses += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                assertEquals("$name: one class makes Save photo's action", 1, builders.size)
                assertEquals("$name: the viewer's menu helper", "MediaGalleryMenuHelper", redexName(builders.single()))
                val stock = builders.single().methods.single(::makesTheAction)
                val stockCode = stock.implementation!!.instructions.toList()

                val alone = PatchContexts.of(builders)
                with(alone) { addViewerVideoItem(viewerMenu()) }
                val withoutHook = assertRowAdded(name, alone, stock)
                assertEquals("$name: Facebook's own code is unchanged", kinds(stockCode), kinds(withoutHook))

                // Download any photo goes first when both are picked, and shifts the builder's code.
                val withPhotos = PatchContexts.of(photoClasses.distinctBy { it.type })
                with(withPhotos) {
                    unlockPhotoSave()
                    addViewerVideoItem(viewerMenu())
                }
                assertRowAdded("$name with Download any photo", withPhotos, stock)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
