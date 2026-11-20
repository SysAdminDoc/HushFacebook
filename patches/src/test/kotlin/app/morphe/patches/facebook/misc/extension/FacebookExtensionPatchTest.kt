/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.extension

import app.morphe.PatchContexts
import app.morphe.patches.facebook.misc.settings.SettingsPatchHosts
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Facebook's application start hands the extension its context first and then the crash switch, so
 * a start that never opens the feed still files the deaths it found (#528).
 */
class FacebookExtensionPatchTest {
    @Test
    fun `the application start hands over the context and then the crash switch`() {
        val context = PatchContexts.of(SettingsPatchHosts.appAndMainTab())
        facebookExtensionPatch.execute(context)

        val code = context.mutableClassDefBy(FACEBOOK_APPLICATION).methods
            .single { it.name == "onCreate" }.implementation!!.instructions.toList()
        val calls = code.take(2).map { (it as ReferenceInstruction).reference.toString() }
        assertEquals(
            listOf("$EXTENSION_CLASS_DESCRIPTOR->setContext(Landroid/content/Context;)V", CRASH_SWITCH_HAND_OVER),
            calls,
        )
        assertEquals(Opcode.INVOKE_STATIC, code[1].opcode)
        assertEquals("Facebook's own start code went missing", Opcode.RETURN_VOID, code.last().opcode)
        assertEquals(3, code.size)
    }
}
