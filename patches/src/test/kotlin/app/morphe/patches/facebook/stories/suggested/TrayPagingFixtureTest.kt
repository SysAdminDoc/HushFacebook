/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #317: on every declared build one class outside the extension handles the Stories tray's
 * tiles coming into view, with a (String, int) method holding "placeholder_bucket_id" whose
 * placeholder path reads a MobileConfig flag before loading the next page, and one placeholder tile
 * calls it. The patch hands the extension every tile first thing in that method, lets the flag
 * answer through the extension, adds the Pager method that shows the handler a placeholder again,
 * and tells the extension first thing when a placeholder leaves view.
 */
class TrayPagingFixtureTest {
    /** The tile handler, its tile method and the placeholder tile's leaving method, per build and then by the bundle's ABI. */
    private val expected = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to listOf("LX/02g6;", "IBz", "LX/04ck;->Iwx"),
            "armeabi-v7a" to listOf("LX/02g6;", "IBz", "LX/04ck;->Iwx"),
        ),
    )

    /** The placeholder-load flag the handler reads on 586 (585's was 0x101029900041f52). */
    private val flagKey = 0x101029900041fabL

    private fun <T> Map<String, Map<String, T>>.pin(version: String, bundle: File): T =
        getValue(version)[bundle.name.substringAfter("-$version-").substringBefore(".apkm")]
            ?: throw AssertionError("${bundle.name} has no pin")

    private fun body(method: Method): List<Instruction> = method.implementation!!.instructions.toList()

    private fun call(instruction: Instruction) = (instruction as ReferenceInstruction).reference as MethodReference

    private fun reference(instruction: Instruction): String {
        val call = call(instruction)
        return "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
    }

    private fun literal(instruction: Instruction): Long? = when (instruction) {
        is WideLiteralInstruction -> instruction.wideLiteral
        is NarrowLiteralInstruction -> instruction.narrowLiteral.toLong()
        else -> null
    }

    @Test
    fun `each declared build's tray tile handler loads the next page for a placeholder on the extension's word`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("the declared builds", expected.keys, versions)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val (handler, shownName, hiddenName) = expected.pin(version, bundle)
                val context = PatchContexts.of(FixtureDex.classesHolding(bundle, PLACEHOLDER_BUCKET) + ExtensionDex.classDef(TRAY_PAGING))
                val anchors = with(context) { trayPagingAnchors() }
                assertEquals("${bundle.name}: the tile handler", handler, anchors.handler)
                assertEquals("${bundle.name}: its tile method", shownName, anchors.shown.name)
                assertEquals("${bundle.name}: the placeholder leaving view", hiddenName, "${anchors.hidden.definingClass}->${anchors.hidden.name}")
                assertEquals("${bundle.name}: a MobileConfig boolean", listOf(MOBILE_CONFIG, "Z"), listOf(anchors.flag.definingClass, anchors.flag.returnType))

                val shown = body(anchors.shown)
                val read = anchors.flagResult - 1
                val key = shown.subList(0, read).mapNotNull(::literal).lastOrNull()
                assertEquals("${bundle.name}: the placeholder-load flag", flagKey, key)
                val flagRegister = (shown[anchors.flagResult] as OneRegisterInstruction).registerA
                val hiddenBody = body(anchors.hidden)

                with(context) { pageShortTrays(anchors) }
                val mutable = with(context) { mutableClassDefBy(handler) }
                assertTrue("${bundle.name}: the handler is a Pager", TRAY_PAGER in mutable.interfaces)

                val hooked = body(mutable.methods.single { it.name == shownName && isTileShown(it) })
                assertEquals("${bundle.name}: three instructions in the tile method", shown.size + 3, hooked.size)
                assertEquals(TILE_SHOWN, reference(hooked[0]))
                val handed = hooked[0] as RegisterRangeInstruction
                val registers = anchors.shown.implementation!!.registerCount
                assertEquals("${bundle.name}: the handler, the bucket id and the position", (registers - 3) to 3, handed.startRegister to handed.registerCount)
                assertEquals("${bundle.name}: Facebook's own code up to the flag", shown.subList(0, anchors.flagResult + 1).map { it.opcode },
                    hooked.subList(1, anchors.flagResult + 2).map { it.opcode })
                val asked = hooked[anchors.flagResult + 2]
                assertEquals(PLACEHOLDER_PAGES, reference(asked))
                assertEquals("${bundle.name}: the flag in", flagRegister to 1,
                    (asked as RegisterRangeInstruction).startRegister to asked.registerCount)
                val answer = hooked[anchors.flagResult + 3]
                assertEquals(Opcode.MOVE_RESULT, answer.opcode)
                assertEquals("${bundle.name}: the answer back in the flag's register", flagRegister, (answer as OneRegisterInstruction).registerA)
                assertEquals("${bundle.name}: Facebook's own code after it", shown.drop(anchors.flagResult + 1).map { it.opcode },
                    hooked.drop(anchors.flagResult + 4).map { it.opcode })

                val made = mutable.methods.single { it.name == SHOW_PLACEHOLDER_METHOD }
                assertEquals("${bundle.name}: the Pager method", listOf("I") to "V", made.parameterTypes.map { it.toString() } to made.returnType)
                val showing = body(made)
                assertEquals(PLACEHOLDER_BUCKET, ((showing[0] as ReferenceInstruction).reference as StringReference).string)
                assertEquals("$handler->$shownName(Ljava/lang/String;I)V", reference(showing[1]))
                val passed = showing[1] as FiveRegisterInstruction
                assertEquals("${bundle.name}: the handler, the id, then the position", listOf(1, 0, 2),
                    listOf(passed.registerC, passed.registerD, passed.registerE))

                val tile = with(context) { mutableClassDefBy(anchors.hidden.definingClass) }
                val leaving = body(tile.methods.single { it.name == anchors.hidden.name && it.parameterTypes.size == anchors.hidden.parameterTypes.size })
                assertEquals("${bundle.name}: one instruction in the leaving method", hiddenBody.size + 1, leaving.size)
                assertEquals(PLACEHOLDER_HIDDEN, reference(leaving[0]))
                assertEquals("${bundle.name}: Facebook's own code follows", hiddenBody.map { it.opcode }, leaving.drop(1).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
