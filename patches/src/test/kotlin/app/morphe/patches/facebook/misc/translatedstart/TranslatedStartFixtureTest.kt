/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.translatedstart

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Start on x86 devices' anchor on every Facebook build the bundle declares: the one method that
 * logs the cold start experiments' skip list, which also schedules MprotectCode, the task the
 * extension adds to that list. Then the patch: the list goes through the extension right before
 * the method stores it for the scheduler, and the store keeps what the extension answered. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TranslatedStartFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    @Before
    @After
    fun forgetTheLastMatch() = ApplicationDelegateFingerprint.clearMatch()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun isSetStore(instruction: Instruction) = instruction.opcode == Opcode.SPUT_OBJECT &&
        ((instruction as ReferenceInstruction).reference as FieldReference).type == "Ljava/util/Set;"

    @Test
    fun `each declared build logs the skip list in one method, which schedules MprotectCode, and the list goes through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, SKIP_APP_INITS_LOG).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val methods = owners.flatMap { owner -> owner.methods.filter { holdsString(it, SKIP_APP_INITS_LOG) } }
                assertEquals("$name: methods logging $SKIP_APP_INITS_LOG", 1, methods.size)
                val delegate = methods.single()
                assertEquals("$name: the delegate setup's shape", "V", delegate.returnType)
                assertTrue("$name: the delegate setup takes nothing", delegate.parameterTypes.isEmpty())
                assertTrue("$name: the delegate setup is an instance method", !AccessFlags.STATIC.isSet(delegate.accessFlags))
                // The Android 9 build schedules each start-up task from a static helper the setup calls.
                val schedulers = FixtureDex.classesHolding(bundle, "MprotectCode")
                    .flatMap { owner -> owner.methods.filter { holdsString(it, "MprotectCode") } }
                    .map { "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("", "(", ")")}${it.returnType}" }.toSet()
                val calls = delegate.code().mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() }
                assertTrue("$name: the delegate setup no longer schedules MprotectCode",
                    holdsString(delegate, "MprotectCode") || calls.any { it in schedulers })

                val original = delegate.code()
                val store = original.indexOfFirst(::isSetStore)
                val skipped = (original[store] as OneRegisterInstruction).registerA
                assertEquals("$name: set stores", 1, original.count(::isSetStore))

                val context = PatchContexts.of(owners + ExtensionDex.classDef(SETTINGS_STATUS))
                forgetTheLastMatch()
                translatedStartPatch.execute(context)

                val patched = context.mutableClassDefBy(delegate.definingClass).methods
                    .single { it.name == delegate.name && it.parameterTypes.isEmpty() && it.returnType == "V" }.code()
                assertEquals("$name: three instructions in", original.size + 3, patched.size)
                val copySet = patched[store] as TwoRegisterInstruction
                val call = patched[store + 1]
                val result = patched[store + 2] as OneRegisterInstruction
                assertEquals("$name: the set is copied down", Opcode.MOVE_OBJECT_FROM16, copySet.opcode)
                assertEquals("$name: the set is copied down", skipped, copySet.registerB)
                assertEquals("$name: the call", Opcode.INVOKE_STATIC, call.opcode)
                assertEquals("$name: the call", SKIP_APP_INITS, ((call as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the call passes the set alone", 1, (call as Instruction35c).registerCount)
                assertEquals("$name: the call passes the set", copySet.registerA, call.registerC)
                assertEquals("$name: the answer", Opcode.MOVE_RESULT_OBJECT, result.opcode)
                assertEquals("$name: the store keeps the answer", skipped, result.registerA)
                assertTrue("$name: the store follows", isSetStore(patched[store + 3]))
                assertEquals("$name: the store", skipped, (patched[store + 3] as OneRegisterInstruction).registerA)

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "translatedStart" }.code()
                assertEquals("$name: SettingsStatus.translatedStart() answers true", 1, (status[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `the extension has the hook the patch calls`() {
        val type = SKIP_APP_INITS.substringBefore("->")
        val hooks = ExtensionDex.classDef(type).methods.filter { method ->
            "$type->${method.name}${method.parameterTypes.joinToString("", "(", ")")}${method.returnType}" == SKIP_APP_INITS
        }
        assertEquals("$SKIP_APP_INITS in the extension", 1, hooks.size)
        assertTrue("the hook is static", AccessFlags.STATIC.isSet(hooks.single().accessFlags))
    }

    @Test
    fun `a delegate setup that stores no set refuses, naming the method`() {
        val type = "LX/0es;"
        val code = listOf(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(SKIP_APP_INITS_LOG)),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val method = ImmutableMethod(type, "A08", null, "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(2, code, null, null))
        val context = PatchContexts.of(listOf(ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            null, null, null, null, listOf(method))))
        val failure = assertThrows(PatchException::class.java) {
            context.mutableClassDefBy(type).methods.single().skipMoreAppInits()
        }
        assertEquals(
            "$TRANSLATED_START_NAME: $type->A08 stores 0 sets after $SKIP_APP_INITS_LOG, expected 1",
            failure.message,
        )
    }
}
