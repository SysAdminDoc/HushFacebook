/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.reels.gesture.COMPOSER_ACTION
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video viewer's comment bar as 587 writes it: the render no longer loads the sticker button's
 * name, it asks a static label method on another class for it (arm64
 * `LX/0DrQ;->getStickerContentDescription`), then hands the answer to the button builder; the GIF
 * button is built from the flyout composer action's constant.
 */
class ViewerButtonsShapesTest {
    private val labelType = "Lfixture/Label;"
    private val tableType = "Lfixture/Table;"
    private val barType = "Lfixture/Bar;"
    private val gifAction = "$COMPOSER_ACTION->A02:$COMPOSER_ACTION"

    private fun method(definingClass: String, name: String, parameters: List<String>, returnType: String, registers: Int,
        smali: String, static: Boolean): MutableMethod = MutableMethod(
        ImmutableMethod(
            definingClass,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0),
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private fun classDef(type: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods.toList())

    private fun names(owner: String, name: String, parameters: List<String>) =
        method(owner, name, parameters, "Ljava/lang/String;", 1 + parameters.size, """
            const-string v0, "$VIEWER_STICKER"
            return-object v0
        """, static = true)

    /** The label class: a static `(Context)String` naming the sticker. */
    private fun label() = classDef(labelType, names(labelType, "sticker", listOf("Landroid/content/Context;")))

    /** A string table holding the same name, a static `(I)String`, which is not a label. */
    private fun table() = classDef(tableType, names(tableType, "A00", listOf("I")))

    /** The bar's render, asking [ask] for the sticker's name (loading [load] first when it takes one). */
    private fun bar(ask: String, load: String = "") = classDef(barType, method(barType, "render", listOf("Landroid/content/Context;"),
        "Ljava/lang/Object;", 6, """
            $load
            $ask
            move-result-object v0
            invoke-static { v0 }, $barType->button(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v1
            sget-object v0, $gifAction
            invoke-static { v0 }, $barType->button(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v2
            invoke-static { v1, v2 }, $barType->row(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """, static = false))

    private val asksLabel = "invoke-static { p1 }, $labelType->sticker(Landroid/content/Context;)Ljava/lang/String;"

    @Test
    fun `587's render names the sticker through a static label and still finds both buttons`() {
        val label = label()
        assertTrue(isStickerLabel(label.methods.single()))
        val found = viewerButtons(listOf(label), gifAction, listOf(bar(asksLabel)))
        assertEquals("the render", "render", found.render.name)
        assertEquals("the sticker button", 1, found.sticker)
        assertEquals("the GIF button", 2, found.gif)
        assertEquals("the hook goes after the GIF button", 7, found.at)
        assertEquals("a register nothing reads there", 0, found.free)
    }

    @Test
    fun `a render asking no label, or asking a string table, names no sticker`() {
        // Without the label's callers the render isn't among the classes looked at.
        assertThrows(PatchException::class.java) { viewerButtons(listOf(label()), gifAction) }
        // A string table holding the name answers by index, so a call to it doesn't name the sticker.
        val table = table()
        assertFalse(isStickerLabel(table.methods.single()))
        val asksTable = "invoke-static { v0 }, $tableType->A00(I)Ljava/lang/String;"
        val error = assertThrows(PatchException::class.java) {
            viewerButtons(listOf(table), gifAction, listOf(bar(asksTable, load = "const/4 v0, 0x1")))
        }
        assertTrue(error.message, error.message!!.contains("found 0"))
    }
}
