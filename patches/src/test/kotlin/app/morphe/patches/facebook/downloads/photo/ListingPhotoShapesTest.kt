/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.photo

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long press to save Marketplace photos over a stand-in React Native image manager: its typed
 * setSource and setSrc each hand their view and sources to the extension first, a bridge setter
 * taking any View is left alone, and the patch refuses a build with no manager, no typed setter, two
 * typed setters under one name, a view that isn't an ImageView, or where something jumps back to a
 * setter's first instruction, which would skip the call.
 */
class ListingPhotoShapesTest {
    private val view = "Lfixture/ImageView;"
    private val array = "Lcom/facebook/react/bridge/ReadableArray;"

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, locals: Int, smali: String): Method {
        val ins = parameters.size + 1
        return MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, AccessFlags.PUBLIC.value,
                null, null, ImmutableMethodImplementation(locals + ins, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali) }
    }

    private fun classOf(type: String, superclass: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, emptyList(), methods.toList())

    /** A setter as the manager writes it: check the view, hand it the sources. */
    private fun typedSetter(name: String, locals: Int = 1, loopToStart: Boolean = false, viewType: String = view) = method(
        VITO_IMAGE_MANAGER, name, listOf(viewType, array), "V", locals,
        if (loopToStart) {
            """
                :top
                const/4 v0, 0x0
                invoke-interface { p2 }, $array->size()I
                move-result v0
                if-eqz v0, :top
                return-void
            """
        } else {
            """
                const/4 v0, 0x0
                invoke-virtual { p1, p2 }, $viewType->load($array)V
                return-void
            """
        },
    )

    private fun bridgeSetSource() = method(
        VITO_IMAGE_MANAGER, "setSource", listOf("Landroid/view/View;", array), "V", 0,
        """
            check-cast p1, $view
            invoke-virtual { p0, p1, p2 }, $VITO_IMAGE_MANAGER->setSource($view$array)V
            return-void
        """,
    )

    private fun manager(vararg methods: Method) =
        classOf(VITO_IMAGE_MANAGER, "Lcom/facebook/react/uimanager/SimpleViewManager;", *methods)

    private fun imageView(type: String = view, superclass: String = "Landroid/widget/ImageView;") = classOf(type, superclass)

    private fun code(context: BytecodePatchContext, name: String, firstParameter: String) =
        context.mutableClassDefBy(VITO_IMAGE_MANAGER).methods.single {
            it.name == name && it.parameterTypes.first().toString() == firstParameter
        }.implementation!!.instructions.toList()

    private fun handsOver(context: BytecodePatchContext, name: String, start: Int) {
        val code = code(context, name, view)
        assertEquals("$name: the hook comes first", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals(name, LISTING_SOURCE, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$name: the view and the sources", start, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals(name, 2, (code[0] as RegisterRangeInstruction).registerCount)
        assertEquals("$name: the rest is kept", listOf(Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), code.drop(1).map { it.opcode })
    }

    @Test
    fun `setSource and setSrc each hand their view and sources over first`() {
        val context = PatchContexts.of(listOf(manager(typedSetter("setSource"), typedSetter("setSrc", locals = 3), bridgeSetSource()), imageView()))
        context.listingPhotoSave()()

        // One local, then p0 = v1, so the view and the sources are v2 and v3.
        handsOver(context, "setSource", 2)
        // Three locals: v4 and v5.
        handsOver(context, "setSrc", 4)
        val bridge = code(context, "setSource", "Landroid/view/View;")
        assertTrue("the bridge would hand the sources over twice", bridge.none {
            (it as? ReferenceInstruction)?.reference?.toString()?.startsWith(LISTING_PHOTO) == true
        })
    }

    @Test
    fun `one typed setter is enough`() {
        val context = PatchContexts.of(listOf(manager(typedSetter("setSrc")), imageView()))
        context.listingPhotoSave()()
        handsOver(context, "setSrc", 2)
    }

    @Test
    fun `nothing changes before the step runs`() {
        val context = PatchContexts.of(listOf(manager(typedSetter("setSource")), imageView()))
        context.listingPhotoSave()
        assertEquals(Opcode.CONST_4, code(context, "setSource", view).first().opcode)
    }

    @Test
    fun `a missing manager or typed setter is refused`() {
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(imageView())).listingPhotoSave()
        }
        assertThrows("only the bridge", PatchException::class.java) {
            PatchContexts.of(listOf(manager(bridgeSetSource()), imageView())).listingPhotoSave()
        }
        assertThrows("no view class", PatchException::class.java) {
            PatchContexts.of(listOf(manager(typedSetter("setSource")))).listingPhotoSave()
        }
    }

    @Test
    fun `two typed setters under one name are refused`() {
        val other = "Lfixture/OtherImageView;"
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(manager(typedSetter("setSource"), typedSetter("setSource", viewType = other)), imageView(), imageView(other)))
                .listingPhotoSave()
        }
    }

    @Test
    fun `a view that isn't an ImageView is refused`() {
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(manager(typedSetter("setSource")), imageView(superclass = "Landroid/view/View;"))).listingPhotoSave()
        }
    }

    @Test
    fun `a jump back to the first instruction is refused`() {
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(manager(typedSetter("setSource"), typedSetter("setSrc", loopToStart = true)), imageView())).listingPhotoSave()
        }
    }
}
