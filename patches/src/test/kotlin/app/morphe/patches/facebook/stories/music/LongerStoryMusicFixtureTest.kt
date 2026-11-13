/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.music

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Longer music on photo stories on each declared Facebook build: the composer's one music clip
 * length asks the extension right before it returns a photo's 15000 and right before it returns
 * the MobileConfig length, into the returned register, and the rest of the method stays as
 * Facebook wrote it. SettingsStatus says the patch is in.
 *
 * Read from 584 (2026-10-23): `LX/KkG;->A02(FbUserSession, LX/LeZ;)I`, its MobileConfig return at
 * 41 and its 15000 at 42 and 43. None of those names is used here.
 */
class LongerStoryMusicFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun same(a: Method, b: Method) = a.name == b.name && a.returnType == b.returnType &&
        a.parameterTypes.map(CharSequence::toString) == b.parameterTypes.map(CharSequence::toString)

    @Test
    fun `each declared build's photo music length asks the extension before both returns`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex -> dex.classes.filterTo(owners) { it.methods.any(::isClipLength) } }
                val methods = owners.flatMap { it.methods.filter(::isClipLength) }
                assertEquals("$name: one music clip length, found ${methods.map { "${it.definingClass}->${it.name}" }}",
                    1, methods.size)
                val method = methods.single()
                val code = method.implementation!!.instructions.toList()
                // 587's 32-bit build reads the MobileConfig length through a static helper on a utility
                // class, so the classes the method calls statically go into the context with it.
                val callees = code.filter { it.opcode == Opcode.INVOKE_STATIC }
                    .mapNotNull { ((it as ReferenceInstruction).reference as? MethodReference)?.definingClass }
                    .filter { it != method.definingClass }.toSet()
                val helpers = FixtureDex.classes(bundle, callees)
                val returns = method.clipReturns { type -> helpers[type] }
                assertEquals("$name: the 15000 sits right before its return", PHOTO_CLIP_MS,
                    (code[returns.fifteen - 1] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: the MobileConfig length is turned into an int before its return", true,
                    (returns.config - 3 until returns.config).any { code[it].opcode == Opcode.DOUBLE_TO_INT })

                val context = PatchContexts.of(owners + helpers.values.filter { helper -> owners.none { it.type == helper.type } } +
                    ExtensionDex.classDef(SETTINGS_STATUS))
                longerStoryMusicPatch.execute(context)

                val body = context.mutableClassDefBy(method.definingClass).methods.single { same(it, method) }
                    .implementation!!.instructions.toList()
                val where = "$name: ${method.definingClass}->${method.name}"
                assertEquals("$where: two instructions added before each photo return", code.size + 4, body.size)
                // The MobileConfig return comes first, so its ask moves the 15000 return down two.
                for ((original, moved) in listOf(returns.config to returns.config, returns.fifteen to returns.fifteen + 2)) {
                    val register = (code[original] as OneRegisterInstruction).registerA
                    val ask = body[moved]
                    assertEquals("$where: the ask's form at $original", Opcode.INVOKE_STATIC_RANGE, ask.opcode)
                    assertEquals("$where: the ask at $original", CLIP_LENGTH_CALL, (ask as ReferenceInstruction).reference.toString())
                    assertEquals("$where: the ask at $original reads the length", register, (ask as RegisterRangeInstruction).startRegister)
                    assertEquals("$where: the ask at $original reads one register", 1, ask.registerCount)
                    val answer = body[moved + 1]
                    assertEquals("$where: the answer at $original", Opcode.MOVE_RESULT, answer.opcode)
                    assertEquals("$where: the answer's register at $original", register, (answer as OneRegisterInstruction).registerA)
                    assertEquals("$where: the return at $original", Opcode.RETURN, body[moved + 2].opcode)
                    assertEquals("$where: the return's register at $original", register,
                        (body[moved + 2] as OneRegisterInstruction).registerA)
                }
                val kept = body.indices.filterNot { it in returns.config until returns.config + 2 || it in returns.fifteen + 2 until returns.fifteen + 4 }
                assertEquals("$where: everything else as Facebook wrote it", code.map { it.opcode }, kept.map { body[it].opcode })

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "storyMusicLength" }
                    .implementation!!.instructions.toList()
                assertEquals("$name: SettingsStatus answers first", listOf(Opcode.CONST_4, Opcode.RETURN),
                    status.take(2).map { it.opcode })
                assertEquals("$name: SettingsStatus says the patch is in", 1L,
                    (status.first() as WideLiteralInstruction).wideLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
