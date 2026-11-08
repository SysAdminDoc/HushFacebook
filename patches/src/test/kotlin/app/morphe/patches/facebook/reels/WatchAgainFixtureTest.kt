/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Watch again hooks (#403) on every Facebook build the bundle declares: the methods
 * logging a Watch again tap agree on one opens-elsewhere ask and one replay field, three methods ask
 * it on Watch again (the feed's end screen, its overlay button and the Shorts end screen, read from
 * the dex dumps of 585 and its Android 9 build), and two of them ask first whether the video opens in
 * the deep dive viewer. After the patch each of those answers goes through the extension right after
 * Facebook takes it, and the rest of each method is Facebook's. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class WatchAgainFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** Every class in [bundle] with a method logging a Watch again tap or calling a method shaped like the ask. */
    private fun holders(bundle: java.io.File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(found) { classDef ->
                !classDef.type.startsWith(EXTENSION_PACKAGE) && classDef.methods.any { method ->
                    WATCH_AGAIN_MARKERS.any { holdsString(method, it) } || method.body().any { instruction ->
                        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let(::isOpensElsewhereAsk) == true
                    }
                }
            }
        }
        return found.map(ImmutableClassDef::of)
    }

    @Test
    fun `each declared build's Watch again asks the extension before it opens Reels`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = holders(bundle)
                val context = PatchContexts.of(classes)
                val taps = context.watchAgainTaps()
                assertTrue("$name: no method logging Watch again asks whether the video opens elsewhere", taps.isNotEmpty())
                val (ask, replay) = watchAgainAnchors(taps)
                val asked = described(ask)

                val sites = classes.flatMap { owner ->
                    owner.methods.mapNotNull { method ->
                        watchAgainSites(method, asked, replay).takeIf { it.isNotEmpty() }?.let { Triple(owner, method, it) }
                    }
                }
                assertEquals("$name: methods asking on Watch again: ${sites.map { "${it.first.type}->${it.second.name}" }}",
                    3, sites.size)
                assertTrue("$name: a method asks twice on Watch again", sites.all { it.third.size == 1 })
                // Positive control: More videos asks the same method, and its no reads another field first,
                // so the replay field is what keeps it out.
                val elsewhere = classes.sumOf { owner ->
                    owner.methods.sumOf { method ->
                        branchedAnswers(method) { described(it) == asked }.count { replay !in staticReads(method, it.whenFalse) }
                    }
                }
                assertTrue("$name: every ask replays, so the replay field tells nothing apart", elsewhere > 0)

                val deepDives = sites.associate { (owner, method, found) -> "${owner.type}->${method.name}" to deepDiveAsks(method, found) }
                assertEquals("$name: deep dive asks running into Watch again's: $deepDives", 2, deepDives.values.sumOf { it.size })
                for ((where, asks) in deepDives) {
                    val method = sites.single { "${it.first.type}->${it.second.name}" == where }.second
                    assertTrue("$name: $where's deep dive ask isn't static", asks.all { answer ->
                        method.body()[answer.call].opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
                    })
                }

                context.replayWatchAgainInPlace(ask to replay)
                for ((owner, method, found) in sites) {
                    val where = "$name: ${owner.type}->${method.name}"
                    val hooked = (found + deepDiveAsks(method, found)).sortedBy { it.call }
                    val patched = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(method) }.body()
                    assertEquals("$where: two instructions added per ask", method.body().size + 2 * hooked.size, patched.size)
                    val added = mutableSetOf<Int>()
                    hooked.forEachIndexed { done, answer ->
                        val at = answer.call + 2 + 2 * done
                        val call = patched[at]
                        assertEquals("$where: the ask at ${answer.call} hands its answer over", OPENS_ELSEWHERE, call.called())
                        call as RegisterRangeInstruction
                        assertEquals("$where: from the register Facebook took it in",
                            listOf(1, answer.register), listOf(call.registerCount, call.startRegister))
                        val back = patched[at + 1]
                        assertEquals("$where: the hook's answer goes back in that register",
                            listOf(Opcode.MOVE_RESULT, answer.register), listOf(back.opcode, (back as OneRegisterInstruction).registerA))
                        val branch = patched[at + 2]
                        assertEquals("$where: and Facebook's branch reads it",
                            listOf(Opcode.IF_EQZ, answer.register), listOf(branch.opcode, (branch as OneRegisterInstruction).registerA))
                        added += at
                        added += at + 1
                    }
                    assertEquals("$where: the rest is Facebook's",
                        method.body().map { it.opcode }, patched.filterIndexed { at, _ -> at !in added }.map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val hook = ExtensionDex.classDef(WATCH_AGAIN).methods.singleOrNull { it.name == "opensElsewhere" }
        assertNotNull("the extension has no $OPENS_ELSEWHERE", hook)
        assertEquals("the hook's shape", OPENS_ELSEWHERE,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("the hook isn't public static",
            AccessFlags.STATIC.isSet(hook.accessFlags) && AccessFlags.PUBLIC.isSet(hook.accessFlags))
    }
}
