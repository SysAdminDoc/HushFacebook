/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.taptoplay

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Centered full screen videos (#242) on every Facebook build the bundle declares: one quiet mode
 * setup holding its double-setup complaint, its manager's one AtomicBoolean, one full screen player
 * Redex left named GrootFullscreenVideoPlayer, and that player setting quiet mode up once. Then the
 * hook, run on those classes, with the extension's call right after the setup, handed the setup's
 * own manager and never-quiet registers, the flag stub reading the manager's flag, and the notice
 * stub having the manager tell its listeners with the reason the Hide chevron gives. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class CenteredFullscreenFixtureTest {
    @Test
    fun `each declared build's full screen player starts quiet mode through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name
                val managers = mutableListOf<ClassDef>()
                val players = mutableListOf<ClassDef>()
                FixtureDex.forEach(fixture) { dex ->
                    val holdsSetup = dex.stringSection.any { it == QUIET_DOUBLE_SETUP }
                    for (classDef in dex.classes) {
                        if (classDef.type.startsWith(EXTENSION_CLASSES)) continue
                        if (redexOriginalName(classDef) == GROOT_FULLSCREEN) players += ImmutableClassDef.of(classDef)
                        if (holdsSetup && classDef.methods.any { holdsString(it, QUIET_DOUBLE_SETUP) }) {
                            managers += ImmutableClassDef.of(classDef)
                        }
                    }
                }

                val setups = managers.flatMap(::quietSetups)
                assertEquals("$name: quiet mode setups holding \"$QUIET_DOUBLE_SETUP\"", 1, setups.size)
                val setup = setups.single()
                val manager = managers.single { it.type == setup.definingClass }
                assertEquals("$name: the manager's AtomicBooleans", 1, quietFlags(manager).size)
                val flag = quietFlags(manager).single()
                assertEquals("$name: players named $GROOT_FULLSCREEN", 1, players.size)
                val player = players.single()
                val calls = setupCalls(player, setup)
                assertEquals("$name: the player's quiet mode setups", 1, calls.size)
                val (method, index) = calls.single()
                val original = method.implementation!!.instructions.elementAt(index) as FiveRegisterInstruction

                // The Hide chevron that logs quiet mode entered, then the class holding the reason it gives.
                val notice = quietNotices(manager).single()
                val tappers = mutableListOf<ClassDef>()
                FixtureDex.forEach(fixture) { dex ->
                    if (dex.stringSection.any { it == QUIET_TAP_LOG }) {
                        dex.classes.filter { classDef ->
                            !classDef.type.startsWith(EXTENSION_CLASSES) && classDef.methods.any { holdsString(it, QUIET_TAP_LOG) }
                        }.forEach { tappers += ImmutableClassDef.of(it) }
                    }
                }
                val reasons = tappers.flatMap { tapReasons(it, notice) }.distinct()
                assertEquals("$name: the reasons the Hide chevron gives", 1, reasons.size)
                val reason = reasons.single()
                val reasonType = reason.substringBefore("->")
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(fixture) { dex ->
                    dex.classes.filter { it.type == reasonType }.forEach { holders += ImmutableClassDef.of(it) }
                }

                val context = PatchContexts.of(
                    listOf(manager, player, ExtensionDex.classDef(CENTERED_FULLSCREEN)) + tappers + holders,
                )
                context.hookCenteredFullscreen()

                val patched = context.mutableClassDefBy(player.type).methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }.implementation!!.instructions.toList()
                val setupCall = patched[index]
                assertEquals("$name: the setup moved", setup.name,
                    ((setupCall as ReferenceInstruction).reference as MethodReference).name)
                val started = patched[index + 1]
                assertEquals("$name: the hook's opcode", Opcode.INVOKE_STATIC, started.opcode)
                val reference = (started as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the hook's target", QUIET_STARTED,
                    "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}")
                started as FiveRegisterInstruction
                assertEquals("$name: the hook's arguments", listOf(original.registerC, original.registerG),
                    listOf(started.registerC, started.registerD).take(started.registerCount))

                val stub = context.mutableClassDefBy(CENTERED_FULLSCREEN).methods.single { it.name == QUIET_FLAG_STUB }
                val code = stub.implementation!!.instructions.toList()
                assertEquals("$name: the stub's cast", Opcode.CHECK_CAST, code[0].opcode)
                assertEquals("$name: the stub casts to the manager", manager.type,
                    ((code[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the stub reads a field", Opcode.IGET_OBJECT, code[1].opcode)
                val read = (code[1] as ReferenceInstruction).reference as FieldReference
                assertEquals("$name: the stub's field", "${manager.type}->${flag.name}", "${read.definingClass}->${read.name}")
                assertEquals("$name: the stub returns it", Opcode.RETURN_OBJECT, code[2].opcode)

                // The notice stub: the manager tells its listeners, with the chevron's reason, and answers true.
                val told = context.mutableClassDefBy(CENTERED_FULLSCREEN).methods.single { it.name == QUIET_CHANGED_STUB }
                val notify = told.implementation!!.instructions.toList()
                assertEquals("$name: the notice stub's cast", manager.type,
                    ((notify[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the notice stub reads the reason", Opcode.SGET_OBJECT, notify[1].opcode)
                val given = (notify[1] as ReferenceInstruction).reference as FieldReference
                assertEquals("$name: the reason", reason, "${given.definingClass}->${given.name}")
                assertEquals("$name: the notice", Opcode.INVOKE_STATIC, notify[2].opcode)
                val tell = (notify[2] as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the notice's target", "${manager.type}->${notice.name}", "${tell.definingClass}->${tell.name}")
                assertEquals("$name: the notice stub answers", Opcode.CONST_4, notify[3].opcode)
                assertEquals("$name: the notice stub returns", Opcode.RETURN, notify[4].opcode)

                // A listener arriving late: the add-listener method hands its manager over first.
                val adds = quietListenerAdds(manager)
                assertEquals("$name: the manager's add-listener methods", 1, adds.size)
                val add = adds.single()
                val addCode = context.mutableClassDefBy(manager.type).methods.single {
                    it.name == add.name && it.parameterTypes.map(CharSequence::toString) == add.parameterTypes.map(CharSequence::toString)
                }.implementation!!
                val heard = addCode.instructions.first()
                assertEquals("$name: the listener hook's opcode", Opcode.INVOKE_STATIC, heard.opcode)
                val heardRef = (heard as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the listener hook's target", QUIET_LISTENING,
                    "${heardRef.definingClass}->${heardRef.name}(${heardRef.parameterTypes.joinToString("")})${heardRef.returnType}")
                assertEquals("$name: the listener hook hands over the manager", addCode.registerCount - 2,
                    (heard as FiveRegisterInstruction).registerC)
                assertEquals("$name: the add still adds", true, addCode.instructions.drop(1).any {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    call?.definingClass == "Ljava/util/List;" && call.name == "add"
                })
                checked += version
            }
        }
        assertEquals("builds checked", versions, checked)
    }
}
