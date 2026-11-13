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
import app.morphe.patches.facebook.feed.addressed
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The old Save fixture checked only the UDD render. The direct WhatsApp button (#243) also comes
 * from the alternate sidebar and both experiment-selected list builders. Trace its actual enum
 * case to whatsapp_button, then require the same pre-build skip in all four factory callers.
 */
class ReelSaveButtonFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    /** Static factory callers by build, where it isn't both list builders. */
    private val staticListBuilders = mapOf("586.0.0.52.53" to 1, "587.0.0.39.55" to 1)

    /** Classes holding the four callers by bundle, where each isn't in its own. 586's Android 9 build puts two in one. */
    private val callerOwners = mapOf("facebook-586.0.0.52.53-arm64-v8a-minapi28-storeless.apkm" to 3)

    private fun Method.loads(string: String) = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    @Test
    fun `each declared build's four sidebar paths ask about the proven WhatsApp type before building it`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val factories = FixtureDex.classesHolding(bundle, WHATSAPP_BUTTON_ID)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                    .flatMap { owner -> owner.methods.filter {
                        it.loads(WHATSAPP_BUTTON_ID) && it.loads(SIDEBAR) && it.loads(SHORTS_SIDEBAR)
                    }.map { owner to it } }
                assertEquals("$name: shared WhatsApp sidebar factories", 1, factories.size)
                val (factoryClass, factory) = factories.single()
                val parameters = FixtureDex.classes(bundle, factory.parameterTypes.map(CharSequence::toString).toSet())
                val icons = parameters.values.single { it.superclass == "Ljava/lang/Enum;" &&
                    it.methods.any { method -> method.name == "<clinit>" && method.loads(WHATSAPP_SIDEBAR_TYPE) } }
                assertTrue("$name: ${icons.type} doesn't name BOOKMARK", icons.methods.single { it.name == "<clinit>" }.loads(SAVE_TYPE))
                assertWhatsAppCase(name, icons.methods.single { it.name == "<clinit>" }, factory)

                fun callsFactory(method: Method) = method.implementation?.instructions?.any {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    call != null && call.definingClass == factory.definingClass && isCalledBy(factory, call)
                } == true
                val callers = FixtureDex.methodsWhere(bundle,
                    { dex -> dex.methodSection.any { it.definingClass == factory.definingClass && isCalledBy(factory, it) } },
                    { !it.definingClass.startsWith(EXTENSION_PACKAGE) && callsFactory(it) })
                assertEquals("$name: shared sidebar factory callers", 4, callers.size)
                val owners = FixtureDex.classes(bundle, callers.map { it.definingClass }.toSet())
                assertEquals("$name: caller owners", callerOwners[name] ?: 4, owners.size)
                // 586 made the VddVerticalUfi list builder (LX/0C7w;->A01) an instance method; 587 keeps it so.
                assertEquals("$name: two renders and two list builders, static as on that build", staticListBuilders[version] ?: 2,
                    callers.count { AccessFlags.STATIC.isSet(it.accessFlags) })
                assertThrows("$name: missing an alternate caller must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(owners.values.drop(1) + factoryClass + icons).hideReelSaveButton()
                }

                val context = PatchContexts.of((owners.values + factoryClass + icons).distinctBy { it.type })
                context.hideReelSaveButton()
                for (builder in callers) {
                    val walk = buttonTypeWalk(builder) { it == icons.type }
                    assertNotNull("$name: ${builder.definingClass} has no sidebar enum walk", walk)
                    assertNull("$name: a walk found without an enum", buttonTypeWalk(builder) { false })
                    val patched = context.mutableClassDefBy(builder.definingClass).methods.single {
                        it.name == builder.name &&
                            it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
                    }.code()
                    val original = builder.code()
                    val at = walk!!.cast + 1
                    // Payload alignment may insert or remove a NOP. The executable body is unchanged.
                    assertEquals("$name: three executable instructions added in ${builder.definingClass}",
                        original.count { it.opcode != Opcode.NOP } + 3, patched.count { it.opcode != Opcode.NOP })
                    assertEquals("$name: shape of the hook", listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_NEZ),
                        patched.subList(at, at + 3).map { it.opcode })
                    val call = patched[at] as RegisterRangeInstruction
                    assertEquals("$name: the extension is asked", REEL_SIDEBAR_BUTTON,
                        ((call as ReferenceInstruction).reference as MethodReference).toString())
                    assertEquals("$name: the hook passes one register", 1, call.registerCount)
                    assertEquals("$name: the hook reads the cast button type", walk.type, call.startRegister)
                    val answer = (patched[at + 1] as OneRegisterInstruction).registerA
                    assertNotEquals("$name: the answer overwrites the button type", walk.type, answer)
                    assertEquals("$name: the branch reads the answer", answer, (patched[at + 2] as OneRegisterInstruction).registerA)
                    assertEquals("$name: true doesn't go back to hasNext()", walk.hasNext,
                        (patched[at + 2] as BuilderOffsetInstruction).target.location.index)
                    assertEquals("$name: the rest is Facebook's", original.filter { it.opcode != Opcode.NOP }.map { it.opcode },
                        (patched.take(at) + patched.drop(at + 3)).filter { it.opcode != Opcode.NOP }.map { it.opcode })
                }
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        // Redex names the builder's class separately per ABI, so every declared build has to resolve.
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    /** The actual APP_WHATSAPP constructor ordinal must select the case that names its button. */
    private fun assertWhatsAppCase(name: String, clinit: Method, factory: Method) {
        val code = clinit.code()
        val constant = code.indices.single { ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == WHATSAPP_SIDEBAR_TYPE }
        val ordinal = (code[constant + 1] as NarrowLiteralInstruction).narrowLiteral
        val byAddress = addressed(factory)
        val (at, switch) = byAddress.entries.first { it.value.opcode == Opcode.SPARSE_SWITCH || it.value.opcode == Opcode.PACKED_SWITCH }
        val payload = byAddress[at + (switch as OffsetInstruction).codeOffset] as SwitchPayload
        val target = at + payload.switchElements.single { it.key == ordinal }.offset
        val nextCase = payload.switchElements.map { at + it.offset }.filter { it > target }.minOrNull() ?: Int.MAX_VALUE
        val id = byAddress.entries.single {
            ((it.value as? ReferenceInstruction)?.reference as? StringReference)?.string == WHATSAPP_BUTTON_ID
        }.key
        assertTrue("$name: APP_WHATSAPP doesn't select whatsapp_button", id in target until nextCase)
    }

    @Test
    fun `the extension has the hook`() {
        val type = REEL_SIDEBAR_BUTTON.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideSidebarButton" }
        assertNotNull("the extension has no ReelDeclutter.hideSidebarButton", hook)
        assertEquals("hideSidebarButton's shape", REEL_SIDEBAR_BUTTON,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideSidebarButton isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }

    private companion object {
        const val SIDEBAR = "UDDSideBarComponent"
        const val SAVE_TYPE = "BOOKMARK"
    }
}
