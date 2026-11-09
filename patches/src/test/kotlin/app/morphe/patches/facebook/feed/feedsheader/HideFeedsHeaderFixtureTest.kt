/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.feedsheader

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide the Feeds header on every Facebook build the bundle declares: the Feeds fragment answers
 * whether its tab gets a title row at two returns, and its onCreateView hands the container it
 * built for the filters to the controller each build names, right after making it. Its container
 * controller makes one runnable Redex names [ROOM_RUNNABLE], whose run() reads whether the filters
 * show and branches on it right away. Then the patch on it: each answer goes through the extension,
 * the extension is asked right before the controller is made, and the runnable's answer goes
 * through the extension before its branch. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR
 * and skips without it.
 */
class HideFeedsHeaderFixtureTest {
    /**
     * Each declared build's container type, the controller's type, and the controller's and the
     * container's registers, by ABI. 585 handed the container over in v0; 586 hands it over in v12,
     * the last of a range call. Both of 586's builds share the container's type and the registers,
     * not the controller.
     */
    private val expected = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to Triple("LX/03sX;", "LX/0U1Y;", 7 to 12),
            "armeabi-v7a" to Triple("LX/03sX;", "LX/0U1b;", 7 to 12),
        ),
    )

    /** Each declared build's container controller and the runnable it posts with whether the filters show, by ABI. */
    private val room = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to ("LX/0UOm;" to "LX/0UWU;"),
            "armeabi-v7a" to ("LX/0UOq;" to "LX/0UWY;"),
        ),
    )

    private val frameInit = "Landroid/widget/FrameLayout;-><init>(Landroid/content/Context;)V"

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private val Instruction.reference: String? get() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build answers at two returns, hands the filters' container over once and reads their room once, all through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val abi = name.substringAfter("-$version-").substringBefore(".apkm")
                val (containerController, runnable) = room.getValue(version).getValue(abi)
                val fragment = FixtureDex.classes(bundle, setOf(FEED_FILTERS_FRAGMENT))[FEED_FILTERS_FRAGMENT]
                assertNotNull("$name: no $FEED_FILTERS_FRAGMENT", fragment)
                assertEquals("$name: the container controller's type", containerController,
                    fragment!!.fields.single { it.name == CONTAINER_CONTROLLER_FIELD }.type)
                val read = FixtureDex.classes(bundle, setOf(FEED_FILTERS_FRAGMENT, containerController))
                val controllerClass = read[containerController]
                assertNotNull("$name: no $containerController", controllerClass)
                val made = controllerClass!!.methods.flatMap { method ->
                    method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
                        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                }.toSet()
                val classes = read + FixtureDex.classes(bundle, made)
                val roomRead = filtersRoom(fragment) { classes[it] }
                assertEquals("$name: the runnable", runnable, roomRead.runnable.type)
                assertEquals("$name: the read of whether the filters show and its register", 9 to 0,
                    roomRead.index to roomRead.register)
                val answers = navBarAnswers(fragment)
                assertEquals("$name: the question's returns", listOf(7 to 0, 9 to 0), answers.returns)
                val handOver = filtersHandOver(fragment)
                val (type, controller, registers) = expected.getValue(version).getValue(abi)
                val (register, container) = registers
                val original = handOver.method.code()
                assertEquals("$name: the container's type", type, handOver.type)
                assertEquals("$name: the controller made", controller, original[handOver.index].reference)
                assertEquals("$name: the controller's and the container's registers", registers,
                    handOver.controllerRegister to handOver.containerRegister)
                assertEquals("$name: how the fragment builds its own container", frameInit, handOver.constructor.toString())

                val context = PatchContexts.of(classes.values + ExtensionDex.classDef(SETTINGS_STATUS))
                hideFeedsHeaderPatch.execute(context)
                val patched = context.mutableClassDefBy(FEED_FILTERS_FRAGMENT)

                val view = patched.methods.single {
                    it.name == ON_CREATE_VIEW && it.parameterTypes == handOver.method.parameterTypes
                }.code()
                val at = handOver.index
                assertEquals("$name: seven instructions for the filters' hook", original.size + 7, view.size)
                assertEquals("$name: the extension asked first", HIDES_FILTERS, view[at].reference)
                assertEquals("$name: about the container", listOf(container), view[at].namedRegisters())
                assertEquals("$name: a new container of the field's type", type, view[at + 5].reference)
                assertEquals("$name: built the way the fragment builds its own", frameInit, view[at + 6].reference)
                assertEquals("$name: from the Context in the controller's register", listOf(container, register), view[at + 6].namedRegisters())
                assertEquals("$name: Facebook's controller after the hook", controller, view[at + 7].reference)
                assertEquals("$name: its constructor next", original[at + 1].reference, view[at + 8].reference)

                val question = patched.methods.single { it.name == NAV_BAR_QUESTION && it.parameterTypes.isEmpty() }.code()
                val hooks = question.indices.filter { question[it].reference == NAV_BAR }
                assertEquals("$name: the question's hooks", listOf(7, 11), hooks)
                for (hook in hooks) {
                    assertEquals("$name: the answer handed over", listOf(0), question[hook].namedRegisters())
                    assertEquals("$name: then returned", Opcode.RETURN, question[hook + 2].opcode)
                }

                val stockRun = roomRead.method.code()
                val margin = context.mutableClassDefBy(runnable).methods.single { it.name == "run" && it.parameterTypes.isEmpty() }.code()
                assertEquals("$name: two instructions for the room's hook", stockRun.size + 2, margin.size)
                val readAt = roomRead.index
                assertEquals("$name: Facebook's read first", Opcode.IGET_BOOLEAN, margin[readAt].opcode)
                assertEquals("$name: then the extension", ROOM, margin[readAt + 1].reference)
                assertEquals("$name: handed whether the filters show", listOf(0), margin[readAt + 1].namedRegisters())
                assertEquals("$name: its answer", Opcode.MOVE_RESULT, margin[readAt + 2].opcode)
                assertEquals("$name: in Facebook's register", listOf(0), margin[readAt + 2].namedRegisters())
                assertEquals("$name: then Facebook's branch", Opcode.IF_EQZ, margin[readAt + 3].opcode)
                assertEquals("$name: on that register", listOf(0), margin[readAt + 3].namedRegisters())

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "feedsHeader" }
                assertEquals("$name: SettingsStatus.feedsHeader() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
