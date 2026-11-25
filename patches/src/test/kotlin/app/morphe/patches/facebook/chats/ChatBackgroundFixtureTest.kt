/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chat background on every declared build: one chat screen fragment, and after the hook its
 * onViewCreated hands the extension the fragment and its root view before anything else.
 *
 * Read from 588 (2026-11-24): the fragment is `LX/0H6u;`, Redex's MsysThreadViewFragment, with a
 * `threadKey` field, and only its onCreateView logs the mark. No obfuscated name is used here.
 */
class ChatBackgroundFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    @Test
    fun `the chat screen hands its fragment and view to the extension first, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val fragments = FixtureDex.classesHolding(bundle, THREAD_VIEW_CREATED_MARK).filter(::isThreadViewFragment)
                assertEquals("$name: one chat screen fragment holds the mark", 1, fragments.size)
                val fragment = ImmutableClassDef.of(fragments.single())
                val created = threadViewCreated(fragment)
                assertEquals("$name: it declares one onViewCreated(View, Bundle)", 1, created.size)
                val before = created.single().implementation!!.instructions.toList()

                val context = PatchContexts.of(listOf(fragment))
                with(context) { chatBackground() }

                val after = threadViewCreated(context.mutableClassDefBy(fragment.type)).single().implementation!!.instructions.toList()
                val hook = after[0]
                assertEquals("$name: asks the extension first", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
                assertEquals("$name: the extension's hook", CHAT_BACKGROUND_HOOK,
                    ((hook as ReferenceInstruction).reference as MethodReference).toString())
                val registers = created.single().implementation!!.registerCount
                // p0 and p1 are the last three registers' first two: this, the view, then the Bundle.
                assertEquals("$name: from this", registers - 3, (hook as RegisterRangeInstruction).startRegister)
                assertEquals("$name: and the view", 2, hook.registerCount)
                assertEquals("$name: the rest is as it was", before.size + 1, after.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
