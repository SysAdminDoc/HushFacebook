/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.music

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.shared.MOBILE_CONFIG
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo clip length's two returns, as 587's 32-bit build writes the MobileConfig one: the length
 * is read through a utility class's static `(Object, J)D` that casts the config and asks it.
 */
class LongerStoryMusicShapesTest {
    private val helperType = "Lfixture/Configs;"

    private fun method(definingClass: String, name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) =
        MutableMethod(
            ImmutableMethod(
                definingClass,
                name,
                parameters.map { ImmutableMethodParameter(it, null, null) },
                returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali) }

    /** A static `(Object, J)D` on [helperType]; [casts] says whether it casts the Object to the config first. */
    private fun helper(casts: Boolean): ClassDef {
        val read = method(helperType, "A00", listOf("Ljava/lang/Object;", "J"), "D", 5, buildString {
            if (casts) appendLine("check-cast p0, $MOBILE_CONFIG")
            appendLine("invoke-interface { p0, p1, p2 }, $MOBILE_CONFIG->D3v(J)D")
            appendLine("move-result-wide v0")
            appendLine("return-wide v0")
        })
        return ImmutableClassDef(helperType, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(read))
    }

    /** 587v7's clip length, cut down: 30000 for a shared feed post, the helper's length when above zero, else 15000. */
    private fun clipLength() = method("Lfixture/Clip;", "A02", listOf(USER_SESSION, "Lfixture/State;"), "I", 6, """
        const/16 v0, $FEED_POST_CLIP_MS
        if-eqz p1, :photo
        return v0
        :photo
        invoke-static {}, $helperType->config()Ljava/lang/Object;
        move-result-object v0
        const-wide v1, 0x7L
        invoke-static { v0, v1, v2 }, $helperType->A00(Ljava/lang/Object;J)D
        move-result-wide v0
        double-to-int v2, v0
        if-lez v2, :fifteen
        return v2
        :fifteen
        const/16 v2, $PHOTO_CLIP_MS
        return v2
    """)

    @Test
    fun `a MobileConfig length read through a casting helper counts once its body is read`() {
        val method = clipLength()
        val helper = helper(casts = true)
        val returns = method.clipReturns { type -> helper.takeIf { it.type == type } }
        assertEquals("the MobileConfig length's return", 10, returns.config)
        assertEquals("the 15000's return", 12, returns.fifteen)
        // Negative controls: with no way to read the helper, and with a helper that never casts to
        // the config, nothing says the double is a MobileConfig value.
        val unread = assertThrows(PatchException::class.java) { method.clipReturns() }
        assertTrue(unread.message, unread.message!!.contains("expected one return of a MobileConfig length in A02, found 0"))
        val uncast = helper(casts = false)
        assertThrows(PatchException::class.java) { method.clipReturns { type -> uncast.takeIf { it.type == type } } }
    }
}
