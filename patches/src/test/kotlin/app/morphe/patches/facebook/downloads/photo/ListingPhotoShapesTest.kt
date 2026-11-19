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
 * Long press to save Marketplace photos over a stand-in React Native image manager and touch
 * dispatcher. The manager's typed setSource and setSrc each hand their view and sources to the
 * extension first, a bridge setter taking any View is left alone, and the patch refuses a build with
 * no manager, no typed setter, two typed setters under one name, a view that isn't an ImageView, or
 * where something jumps back to a setter's first instruction, which would skip the call. The
 * dispatcher's handleTouchEvent, an obfuscated method found by the line it logs, hands each touch
 * over first; a string table also holding that line is passed over, and the patch refuses a build
 * with no such method, two, one that doesn't take the touch first, or one that jumps back to its
 * first instruction.
 */
class ListingPhotoShapesTest {
    private val view = "Lfixture/ImageView;"
    private val array = "Lcom/facebook/react/bridge/ReadableArray;"
    private val dispatcher = "Lfixture/TouchDispatcher;"
    private val motionEvent = "Landroid/view/MotionEvent;"

    private fun method(
        owner: String, name: String, parameters: List<String>, returns: String, locals: Int, smali: String, static: Boolean = false,
    ): Method {
        val ins = parameters.size + if (static) 0 else 1
        val flags = AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0
        return MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags,
                null, null, ImmutableMethodImplementation(locals + ins, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali) }
    }

    /** handleTouchEvent as Facebook's React Native builds it: the touch, the bridge's context and the event dispatcher. */
    private fun handleTouchEvent(owner: String = dispatcher, locals: Int = 2, loopToStart: Boolean = false, first: String = motionEvent) = method(
        owner, "A02", listOf(first, "Ljava/lang/Object;", "Ljava/lang/Object;"), "V", locals,
        if (loopToStart) {
            """
                :top
                const/4 v0, 0x0
                const-string v1, "$TOUCH_DOWN_TWICE"
                if-eqz v0, :top
                return-void
            """
        } else {
            """
                invoke-virtual/range { p1 .. p1 }, $first->getAction()I
                move-result v0
                if-nez v0, :done
                const-string v1, "$TOUCH_DOWN_TWICE"
                :done
                return-void
            """
        },
    )

    /** A Redex string table that holds the line as well, the way 586 moved some of its strings. */
    private fun stringTable() = classOf(
        "Lfixture/Strings;", "Ljava/lang/Object;",
        method("Lfixture/Strings;", "A00", listOf("I"), "Ljava/lang/String;", 1,
            """
                const-string v0, "$TOUCH_DOWN_TWICE"
                return-object v0
            """, static = true),
    )

    private fun dispatcherClass(vararg methods: Method) = classOf(dispatcher, "Ljava/lang/Object;", *methods)

    private fun dispatcherCode(context: BytecodePatchContext) =
        context.mutableClassDefBy(dispatcher).methods.single { it.name == "A02" }.implementation!!.instructions.toList()

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

    // ---- React Native's touch dispatcher ----------------------------------------------------------

    @Test
    fun `the touch dispatcher hands every touch over first`() {
        val context = PatchContexts.of(listOf(dispatcherClass(handleTouchEvent()), stringTable()))
        val before = dispatcherCode(context).map { it.opcode }
        val step = context.listingPhotoTouches()
        assertEquals("nothing changes before the step runs", before, dispatcherCode(context).map { it.opcode })
        step()

        val code = dispatcherCode(context)
        assertEquals("the hook comes first", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals(LISTING_ROOT, (code[0] as ReferenceInstruction).reference.toString())
        // Two locals, then p0 = v2, so the touch is v3.
        assertEquals("the touch", 3, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals(1, (code[0] as RegisterRangeInstruction).registerCount)
        assertEquals("the rest is kept", before, code.drop(1).map { it.opcode })
        val table = context.mutableClassDefBy("Lfixture/Strings;").methods.single().implementation!!.instructions.toList()
        assertEquals("the string table was hooked", listOf(Opcode.CONST_STRING, Opcode.RETURN_OBJECT), table.map { it.opcode })
    }

    @Test
    fun `the touch is read from the dispatcher's own registers`() {
        val context = PatchContexts.of(listOf(dispatcherClass(handleTouchEvent(locals = 20))))
        context.listingPhotoTouches()()
        val call = dispatcherCode(context).first()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        // Past v15, so only a range call can hand it over.
        assertEquals(21, (call as RegisterRangeInstruction).startRegister)
    }

    @Test
    fun `no touch dispatcher or two are refused`() {
        assertThrows("none", PatchException::class.java) {
            PatchContexts.of(listOf(stringTable())).listingPhotoTouches()
        }
        assertThrows("two", PatchException::class.java) {
            PatchContexts.of(listOf(dispatcherClass(handleTouchEvent()), classOf("Lfixture/Other;", "Ljava/lang/Object;", handleTouchEvent("Lfixture/Other;"))))
                .listingPhotoTouches()
        }
    }

    @Test
    fun `a dispatcher that doesn't take the touch first is refused`() {
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(dispatcherClass(handleTouchEvent(first = "Landroid/view/KeyEvent;")))).listingPhotoTouches()
        }
    }

    @Test
    fun `a jump back to the dispatcher's first instruction is refused`() {
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(dispatcherClass(handleTouchEvent(loopToStart = true)))).listingPhotoTouches()
        }
    }
}
