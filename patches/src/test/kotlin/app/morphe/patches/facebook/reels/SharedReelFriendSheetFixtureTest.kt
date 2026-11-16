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
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Clean up Reels' Add friend sheet hook (#486) on every Facebook build the bundle declares (arm64
 * and armv7). From Facebook's own code: one method in the whole app names the sheet's Bloks screen,
 * and it's the run() of the Runnable Redex calls LinkSharingFriendingUpsellUtil's
 * maybeShowFriendingUpsell lambda, which also names the upsell. After the hook that run() asks the
 * extension first, returns on a yes, and otherwise lands on Facebook's first instruction with the
 * rest of its code unchanged. A build without that runnable is left as it was. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SharedReelFriendSheetFixtureTest {
    /** What Redex calls the runnable, read from its kept name field rather than from the finder. */
    private val upsellRunnable = "LinkSharingFriendingUpsellUtil\$maybeShowFriendingUpsell\$1"

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** Opcodes with payload-alignment NOPs dropped and a widened goto read as a goto. */
    private fun List<Instruction>.kinds() = filter { it.opcode != Opcode.NOP }.map {
        when (it.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> it.opcode
        }
    }

    private fun holders(bundle: File): List<ClassDef> =
        FixtureDex.classesHolding(bundle, FRIEND_SHEET_SCREEN).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .distinctBy { it.type }

    @Test
    fun `each declared build's Add friend sheet asks the extension first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val held = holders(bundle)

                // Facebook's own code: one method names the screen, the run() of the upsell's runnable.
                val naming = held.flatMap { classDef -> methodsHolding(classDef, FRIEND_SHEET_SCREEN).map { classDef to it } }
                assertEquals("$name: methods naming the sheet's screen", 1, naming.size)
                val (owner, stock) = naming.single()
                assertEquals("$name: the screen is named by the upsell's runnable", upsellRunnable, redexOriginalName(owner))
                assertTrue("$name: ${owner.type} isn't a Runnable", owner.interfaces.any { it.toString() == "Ljava/lang/Runnable;" })
                assertEquals("$name: the screen is named in run()", "run", stock.name)
                assertEquals("$name: run() answers nothing", "V", stock.returnType)
                assertTrue("$name: run() takes something", stock.parameterTypes.isEmpty())
                assertTrue("$name: run() is static", !AccessFlags.STATIC.isSet(stock.accessFlags))
                assertTrue("$name: run() doesn't name the upsell", holdsString(stock, FRIEND_SHEET_UPSELL))
                assertTrue("$name: run() has no local for the answer", stock.implementation!!.registerCount >= 2)

                // The finder agrees, and a class with the same run() that isn't a Runnable isn't taken.
                assertEquals("$name: the finder's run()", stock, friendSheetRun(owner))
                val notRunnable = ImmutableClassDef(
                    owner.type, owner.accessFlags, owner.superclass, emptyList(), owner.sourceFile, owner.annotations,
                    owner.staticFields, owner.instanceFields, owner.directMethods, owner.virtualMethods,
                )
                assertNull("$name: a class that isn't a Runnable was taken", friendSheetRun(notRunnable))

                val context = PatchContexts.of(held)
                with(context) { hideSharedReelFriendSheet() }
                val hooked = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == "run" && it.parameterTypes.isEmpty() && it.returnType == "V"
                }.body()
                val where = "$name: ${owner.type}->run()"
                assertEquals("$where asks first", HIDE_SHARED_REEL_FRIEND_SHEET, hooked[0].called())
                assertEquals("$where keeps the answer", Opcode.MOVE_RESULT, hooked[1].opcode)
                assertEquals("$where in the free register", 0, (hooked[1] as OneRegisterInstruction).registerA)
                assertEquals("$where tests it", Opcode.IF_EQZ, hooked[2].opcode)
                assertEquals("$where the same register", 0, (hooked[2] as OneRegisterInstruction).registerA)
                assertEquals("$where a no goes on to Facebook's first instruction", 4,
                    (hooked[2] as BuilderOffsetInstruction).target.location.index)
                assertEquals("$where a yes returns", Opcode.RETURN_VOID, hooked[3].opcode)
                assertEquals("$where the rest is Facebook's", stock.body().kinds(), hooked.drop(4).kinds())
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `a build without the sheet's runnable is left as it was`() {
        val version = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.first()
        val bundle = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }.firstOrNull() ?: return
        val held = holders(bundle)
        // The same classes with the runnable no longer a Runnable: nothing fits, so nothing changes.
        val stripped = held.map { classDef ->
            if (friendSheetRun(classDef) == null) classDef else ImmutableClassDef(
                classDef.type, classDef.accessFlags, classDef.superclass, emptyList(), classDef.sourceFile, classDef.annotations,
                classDef.staticFields, classDef.instanceFields, classDef.directMethods, classDef.virtualMethods,
            )
        }
        val context = PatchContexts.of(stripped)
        try {
            with(context) { hideSharedReelFriendSheet() }
            fail("${bundle.name}: a build without the sheet's runnable was patched")
        } catch (expected: PatchException) {
            assertTrue(expected.message, expected.message!!.contains(FRIEND_SHEET_SCREEN))
        }
        for (classDef in stripped) {
            for (stock in classDef.methods.filter { holdsString(it, FRIEND_SHEET_SCREEN) }) {
                val after = context.mutableClassDefBy(classDef.type).methods.single {
                    it.name == stock.name && it.parameterTypes.map(Any::toString) == stock.parameterTypes.map(Any::toString)
                }.body()
                assertEquals("${bundle.name}: ${classDef.type} changed though the patch stopped", stock.body().kinds(), after.kinds())
            }
        }
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_SHARED_REEL_FRIEND_SHEET.substringBefore("->")
        val method = HIDE_SHARED_REEL_FRIEND_SHEET.substringAfter("->").substringBefore("(")
        val found = ExtensionDex.classDef(type).methods.singleOrNull { it.name == method }
        assertNotNull("the extension has no ReelDeclutter.$method", found)
        assertEquals("$method's shape", HIDE_SHARED_REEL_FRIEND_SHEET,
            "${found!!.definingClass}->${found.name}(${found.parameterTypes.joinToString("")})${found.returnType}")
        assertTrue("$method isn't static", AccessFlags.STATIC.isSet(found.accessFlags))
    }
}
