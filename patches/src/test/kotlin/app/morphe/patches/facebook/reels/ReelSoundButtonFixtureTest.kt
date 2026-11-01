/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Hide the speaker button on reels hooks (#190) on every Facebook build the bundle
 * declares (arm64 and armv7). In the video controls, one static check's answer goes straight into an
 * if-eqz that skips the reel sound toggle built with "mute_button_transition_key", and the patch
 * hands that answer to the extension before the branch. In the footer, see the footer test. Reads
 * the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelSoundButtonFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    private fun outsideExtension(type: String) = !type.startsWith(EXTENSION_PACKAGE)

    /**
     * The footer's speaker hook (#190) on every Facebook build the bundle declares (arm64 and
     * armv7): one piece the footer news up calls a static builder of the sound toggle, moves its
     * answer and null-checks it, and the patch hands that answer to the extension before the check.
     */
    @Test
    fun `each declared build's reel footer hands the extension the speaker it builds`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val toggleClasses = FixtureDex.classesHolding(bundle, SOUND_TOGGLE_STRING).filter { outsideExtension(it.type) }
                val toggles = toggleClasses.map { it.type }.toSet()
                assertTrue("$name: no class loads the sound toggle's string", toggles.isNotEmpty())
                val footers = FixtureDex.classesHolding(bundle, FOOTER_COMPONENT).filter { outsideExtension(it.type) }.distinctBy { it.type }
                val pieceTypes = footers.flatMap { methodsHolding(it, FOOTER_COMPONENT) }.flatMap(::newInstanceTypes).filter(::outsideExtension).toSet()
                val pieces = FixtureDex.classes(bundle, pieceTypes).values.toList()
                val callees = FixtureDex.classes(bundle, pieces.flatMap { it.methods }.flatMap(::staticCalls).map { it.definingClass }.toSet())
                val buildsSpeaker = { call: MethodReference ->
                    callees[call.definingClass]?.methods?.any { isCalledBy(it, call) && newInstanceTypes(it).any(toggles::contains) } == true
                }
                val found = pieces.flatMap { piece -> piece.methods.flatMap { m -> speakerBuilds(m, buildsSpeaker).map { Triple(piece, m, it) } } }
                assertEquals("$name: pieces of the footer that build the speaker", 1, found.size)
                val (owner, method, build) = found.single()
                val original = method.code()
                // Negative control: the call-move-check shape alone matches more than the speaker, so
                // the builder's toggle is what picks it.
                val shapes = pieces.sumOf { piece -> piece.methods.sumOf { m -> speakerBuilds(m) { true }.size } }
                assertTrue("$name: only the speaker's call has the shape, so the toggle check proves nothing", shapes > 1)
                // No branch or switch case lands on the null check, so every path to it passes the hook.
                assertTrue("$name: a branch lands on the null check", build.call + 2 !in branchTargets(original))
                val builder = (original[build.call] as ReferenceInstruction).reference as MethodReference
                assertTrue("$name: the builder isn't static", callees.getValue(builder.definingClass).methods.single { isCalledBy(it, builder) }
                    .let { AccessFlags.STATIC.isSet(it.accessFlags) })

                val context = PatchContexts.of((footers + pieces + callees.values + toggleClasses).distinctBy { it.type })
                context.hideReelFooterSoundButton()
                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == method.name &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }.code()
                val at = build.call + 2
                assertEquals("$name: three instructions added", original.size + 3, patched.size)
                assertEquals("$name: shape of the hook",
                    listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.IF_EQZ),
                    patched.subList(at - 1, at + 4).map { it.opcode })
                val call = patched[at] as RegisterRangeInstruction
                assertEquals("$name: the extension is asked", REEL_FOOTER_SOUND_BUTTON,
                    ((call as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the hook passes one register", 1, call.registerCount)
                assertEquals("$name: the hook reads what was built", build.register, call.startRegister)
                assertEquals("$name: the extension's answer goes back in its register", build.register,
                    (patched[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the answer is cast back to the builder's type", build.type,
                    ((patched[at + 2] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the null check reads the extension's answer", build.register,
                    (patched[at + 3] as OneRegisterInstruction).registerA)
                assertEquals("$name: the rest is Facebook's", original.map { it.opcode },
                    patched.take(at).map { it.opcode } + patched.drop(at + 3).map { it.opcode })

                // The builder's check for whether the controls build the speaker goes through the extension (#331).
                val builderMethod = callees.getValue(builder.definingClass).methods.single { isCalledBy(it, builder) }
                val builderCode = builderMethod.code()
                val left = footerSpeakerCheck(builderMethod, toggles) ?: throw AssertionError("$name: no check in front of the toggle")
                assertTrue("$name: a branch lands on the builder's check", left !in branchTargets(builderCode))
                val answer = (builderCode[left] as OneRegisterInstruction).registerA
                // Negative control: without its toggle the same builder has no check to take.
                assertEquals("$name: the toggle doesn't pick the check", null, footerSpeakerCheck(builderMethod, emptySet()))
                val patchedBuilder = context.mutableClassDefBy(builder.definingClass).methods.single { isCalledBy(it, builder) }.code()
                assertEquals("$name: shape of the builder's hook", listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_NEZ),
                    patchedBuilder.subList(left, left + 3).map { it.opcode })
                val asked = patchedBuilder[left] as RegisterRangeInstruction
                assertEquals("$name: the extension is asked by the builder", REEL_FOOTER_LEAVES_SPEAKER,
                    ((asked as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the builder's hook reads Facebook's answer", answer, asked.startRegister)
                assertEquals("$name: the builder's answer goes back", answer, (patchedBuilder[left + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the builder's branch reads the extension's answer", answer, (patchedBuilder[left + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: the rest of the builder is Facebook's", unpadded(builderCode).map { it.opcode },
                    unpadded(patchedBuilder.take(left) + patchedBuilder.drop(left + 2)).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), abis)
    }

    private fun unpadded(code: List<Instruction>) = code.filter { it.opcode != Opcode.NOP }

    /**
     * 585's video controls (both ABIs): the control component's render calls an instance builder on a
     * singleton that holds the key and news up the sound toggle, moves its answer and null-checks it, and
     * the patch hands the answer to the extension before the check.
     */
    private fun checkControlsBuild(bundle: File) {
        val name = bundle.name
        val toggleClasses = FixtureDex.classesHolding(bundle, SOUND_TOGGLE_STRING).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }
        val toggles = toggleClasses.map { it.type }.toSet()
        assertTrue("$name: no class loads the sound toggle's string", toggles.isNotEmpty())
        val controls = FixtureDex.classesHolding(bundle, SPEAKER_CONTROLS).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        val callees = FixtureDex.classes(bundle, controls.flatMap { it.methods }.flatMap(::staticOrVirtualCalls).map { it.definingClass }.toSet())
        val buildsSpeaker = { call: MethodReference ->
            callees[call.definingClass]?.methods?.any { buildsControlsSpeaker(it, call, toggles) } == true
        }
        val found = controls.flatMap { control ->
            methodsHolding(control, SPEAKER_CONTROLS).flatMap { m -> controlSpeakerBuilds(m, buildsSpeaker).map { Triple(control, m, it) } }
        }
        assertEquals("$name: methods of the controls that build the speaker", 1, found.size)
        val (owner, method, build) = found.single()
        val original = method.code()
        // Negative control: with no sound toggle to build, the same call shape picks nothing.
        val noToggle = { call: MethodReference ->
            callees[call.definingClass]?.methods?.any { buildsControlsSpeaker(it, call, emptySet()) } == true
        }
        assertTrue("$name: the toggle doesn't pick the builder", controlSpeakerBuilds(method, noToggle).isEmpty())
        // No branch or switch case lands on the null check, so every path to it passes the hook.
        assertTrue("$name: a branch lands on the null check", build.call + 2 !in branchTargets(original))
        val context = PatchContexts.of((controls + callees.values + toggleClasses).distinctBy { it.type })
        context.hideReelSoundButton()
        val patched = context.mutableClassDefBy(owner.type).methods.single {
            it.name == method.name &&
                it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }.code()
        val at = build.call + 2
        assertEquals("$name: three instructions added", unpadded(original).size + 3, unpadded(patched).size)
        assertEquals("$name: shape of the hook",
            listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.IF_EQZ),
            patched.subList(at - 1, at + 4).map { it.opcode })
        val call = patched[at] as RegisterRangeInstruction
        assertEquals("$name: the extension is asked", REEL_CONTROLS_SOUND_BUTTON,
            ((call as ReferenceInstruction).reference as MethodReference).toString())
        assertEquals("$name: the hook passes one register", 1, call.registerCount)
        assertEquals("$name: the hook reads what was built", build.register, call.startRegister)
        assertEquals("$name: the extension's answer goes back in its register", build.register,
            (patched[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: the answer is cast back to the builder's type", build.type,
            ((patched[at + 2] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$name: the null check reads the extension's answer", build.register,
            (patched[at + 3] as OneRegisterInstruction).registerA)
        assertEquals("$name: the rest is Facebook's", unpadded(original).map { it.opcode },
            unpadded(patched.take(at)).map { it.opcode } + unpadded(patched.drop(at + 3)).map { it.opcode })
    }

    @Test
    fun `each declared build's reel viewer asks the extension before it builds the speaker`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, SOUND_BUTTON_KEY)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                    .flatMap { owner -> methodsHolding(owner, SOUND_BUTTON_KEY).map { owner to it } }
                val gated = holders.flatMap { (owner, method) -> soundButtonGates(method).map { Triple(owner, method, it) } }
                if (gated.isEmpty()) {
                    // 585: no check in front of the speaker, the controls hook the builder's answer.
                    checkControlsBuild(bundle)
                    checked += version
                    abis += name.substringAfter("-$version-").substringBefore(".apkm")
                    continue
                }
                assertEquals("$name: checks whose false skips the speaker", 1, gated.size)
                val (owner, method, gate) = gated.single()
                val original = method.code()
                // The check's false skips the speaker's own component: the toggle, the class whose
                // render loads the copyright force-mute string, is built inside the gate.
                val toggles = FixtureDex.classesHolding(bundle, SOUND_TOGGLE_STRING).map { it.type }.toSet()
                assertTrue("$name: no sound toggle built inside the gate", original.subList(gate.branch + 1, gate.target).any {
                    it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type in toggles
                })
                // No branch or switch case lands on the if-eqz itself, so every path to it passes the hook.
                assertTrue("$name: a branch lands on the gate's if-eqz", gate.branch !in branchTargets(original))
                // Negative control: the key's other holder (A1E) loads it with no check in front, and
                // the matcher finds nothing there.
                assertTrue("$name: no other method loads the key to check the matcher against",
                    holders.any { (_, other) -> other != method && soundButtonGates(other).isEmpty() })

                val context = PatchContexts.of(listOf(owner))
                context.hideReelSoundButton()
                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == method.name &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }.code()
                val at = gate.branch
                assertEquals("$name: two instructions added", original.size + 2, patched.size)
                assertEquals("$name: shape of the hook", listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ),
                    patched.subList(at, at + 3).map { it.opcode })
                val call = patched[at] as RegisterRangeInstruction
                assertEquals("$name: the extension is asked", REEL_SOUND_BUTTON,
                    ((call as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the hook passes one register", 1, call.registerCount)
                assertEquals("$name: the hook reads Facebook's answer", gate.answer, call.startRegister)
                assertEquals("$name: the extension's answer goes back in its register", gate.answer,
                    (patched[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the branch reads the extension's answer", gate.answer,
                    (patched[at + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: the branch still skips the speaker", gate.target + 2, branchTarget(patched, at + 2))
                assertEquals("$name: the rest is Facebook's", original.map { it.opcode },
                    patched.take(at).map { it.opcode } + patched.drop(at + 2).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        // Redex names the viewer's class separately per ABI, so every declared build has to resolve.
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), abis)
    }

    @Test
    fun `the extension has the hooks`() {
        for (descriptor in listOf(REEL_SOUND_BUTTON, REEL_FOOTER_SOUND_BUTTON, REEL_CONTROLS_SOUND_BUTTON, REEL_FOOTER_LEAVES_SPEAKER)) {
            val type = descriptor.substringBefore("->")
            val name = descriptor.substringAfter("->").substringBefore("(")
            val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == name }
            assertNotNull("the extension has no ReelDeclutter.$name", hook)
            assertEquals("$name's shape", descriptor,
                "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
        }
    }
}
