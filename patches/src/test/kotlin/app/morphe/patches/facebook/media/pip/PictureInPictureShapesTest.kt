/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.pip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Video tab's inlined copy of the Reels surface gate, with and without lambda cases, beside the near misses refused. */
class PictureInPictureShapesTest {
    private val surface = "Lfixture/Surface;"
    private val settings = "Lfixture/Settings;"
    private val util = "Lfixture/Util;"
    private val fetch = "invoke-interface {p0}, $surface->settings()$settings"

    private val check: Method = ImmutableMethod(
        util, "check", listOf(ImmutableMethodParameter(ACTIVITY, null, null)), "Z", AccessFlags.PUBLIC.value, null, null, null,
    )

    private fun method(name: String, parameters: List<String>, returnType: String, body: String) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Home;", name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(parameters.size + 3, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private fun gate(body: String) = method("gate", listOf(surface), "Z", body)

    /** The arming: its copy of the gate in [copy], then the check. */
    private fun home(copy: String) = method("arm", listOf(surface, ACTIVITY, util), "V", """
        $copy
        invoke-virtual {p2, p1}, $util->check($ACTIVITY)Z
        move-result v0
        :done
        return-void
    """)

    /** 586 (`LX/09vD;->A01`): the answer comes straight off the settings, no lambda cases. */
    private val caselessGate = """
        const/4 v2, 0x0
        if-eqz p0, :no
        $fetch
        move-result-object v0
        if-eqz v0, :no
        invoke-virtual {v0}, $settings->pip()Z
        move-result v1
        const/4 v0, 0x1
        if-ne v1, v0, :no
        return v0
        :no
        return v2
    """

    private fun caselessCopy(answer: String) = """
        $fetch
        move-result-object v0
        if-eqz v0, :done
        invoke-virtual {v0}, $settings->$answer()Z
        move-result v0
        const/4 v1, 0x1
        if-ne v0, v1, :done
    """

    /** Up to 585 (581 `LX/Cr0;->CKK()`) the gate reads through a lambda chosen by two case numbers. */
    private val casesGate = """
        $fetch
        move-result-object v0
        const/16 v1, 0x27
        const/16 v2, 0x28
        invoke-static {v0, v1, v2}, Lfixture/Lambdas;->read(${settings}II)Z
        move-result v0
        return v0
    """

    private fun casesCopy(second: Int) = """
        $fetch
        move-result-object v0
        const/16 v1, 0x27
        const/16 v2, $second
        invoke-static {v0, v1, v2}, Lfixture/Lambdas;->read(${settings}II)Z
        move-result v0
        if-eqz v0, :done
    """

    @Test
    fun `586's caseless copy is found at its answer`() {
        assertEquals(4, homeGateAt(home(caselessCopy("pip")), gate(caselessGate), check))
    }

    /** With no cases to compare, a copy reading another setting isn't the gate's. */
    @Test
    fun `a caseless copy asking another setting is refused`() {
        assertEquals(-1, homeGateAt(home(caselessCopy("other")), gate(caselessGate), check))
    }

    @Test
    fun `585's copy with the gate's cases is found at its answer`() {
        assertEquals(5, homeGateAt(home(casesCopy(0x28)), gate(casesGate), check))
    }

    @Test
    fun `a copy with other cases is refused`() {
        assertEquals(-1, homeGateAt(home(casesCopy(0x29)), gate(casesGate), check))
    }

    /** A gate with cases doesn't match a copy that has none. */
    @Test
    fun `a caseless copy of a gate with cases is refused`() {
        assertEquals(-1, homeGateAt(home(caselessCopy("pip")), gate(casesGate), check))
    }
}
