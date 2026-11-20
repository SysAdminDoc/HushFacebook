/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.confirm

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ask before a like on every declared build and ABI (#437, #443). Each of the seven Like handlers
 * now starts by handing the extension its own descriptor, its instance and every argument, and
 * returns right away when the extension holds the tap; otherwise it jumps to Facebook's own first
 * instruction, unmoved. The plugin footer lambda asks on every call where it has a class of its own,
 * and a merged lambda (the inline comment one, and the plugin footer's on 588's Android 9 build)
 * only on the case of its switch holding its Like.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class AskBeforeLikeFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
    private val markers = mapOf(
        POST_FOOTER to LITHO_FOOTER_TRACE,
        POST_PLUGIN to PLUGIN_FOOTER_TRACE,
        REEL to REEL_LIKE_NAME,
        COMMENT to COMMENT_LIKE_TRACE,
        POST_TOUCH to PLUGIN_FOOTER_TRACE,
        COMMENT_INLINE to INLINE_COMMENT_LIKE_TRACE,
    )

    private fun Method.code() = implementation!!.instructions.toList()

    /** [this] without the nop the dex writer puts before a payload to align it. */
    private fun List<Instruction>.unpadded() =
        filterIndexed { i, it -> it.opcode != Opcode.NOP || getOrNull(i + 1)?.opcode?.format?.isPayloadFormat != true }
    private fun Instruction.reference() = (this as ReferenceInstruction).reference.toString()
    private fun Instruction.loads(string: String) =
        (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
            ((this as ReferenceInstruction).reference as StringReference).string == string

    private fun holding(bundle: File, string: String): List<ClassDef> =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }

    /** The classes holding [string] and, where one of them is a string table, the classes asking it. */
    private fun naming(bundle: File, string: String): List<ClassDef> {
        val held = holding(bundle, string)
        val tables = held.filter { owner -> owner.methods.any { isStringTable(it) && holdsString(it, string) } }
            .associateBy { it.type }
        if (tables.isEmpty()) return held
        val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
        val asking = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in tables } }) {
            !isStringTable(it) && !holdsString(it, string) && namesString(it, string, resolve)
        }.map { it.definingClass }.toSet()
        return held + FixtureDex.classes(bundle, asking).values
    }

    private fun BytecodePatchContext.patched(method: Method) = mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }.code()

    private fun Instruction.target() = (this as BuilderOffsetInstruction).target.location.index

    /** The index [at]'s branch in unpatched [code] lands on, or the one [offset] from it. */
    private fun branchTarget(code: List<Instruction>, at: Int, offset: Int = (code[at] as OffsetInstruction).codeOffset): Int {
        val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
        return addresses.indexOf(addresses[at] + offset)
    }

    /**
     * The keys of the cases whose arm (up to the next case, or the end) loads [trace] in the unpatched
     * [code] of a merged lambda of [kind], which switches on [arm]'s field right after reading it.
     */
    private fun armCases(name: String, kind: Int, code: List<Instruction>, arm: Arm, trace: String): Set<Int> {
        val switchAt = code.indexOfFirst { it.opcode == Opcode.IGET && it.reference() == arm.field } + 1
        assertTrue("$name: kind $kind's lambda doesn't read its arm number at the top", switchAt in 1..4)
        assertEquals("$name: kind $kind's lambda doesn't switch on its arm number", Opcode.PACKED_SWITCH, code[switchAt].opcode)
        val payload = code[branchTarget(code, switchAt)] as SwitchPayload
        val starts = payload.switchElements.associate { it.key to branchTarget(code, switchAt, it.offset) }
        val bounds = (starts.values + (switchAt + 1) + code.size).toSortedSet()
        return starts.filter { (_, start) ->
            val end = bounds.first { it > start }
            code.subList(start, end).any { it.loads(trace) }
        }.keys
    }

    @Test
    fun `each declared build's Like handlers ask the extension first`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = markers.values.toSet().flatMap { naming(bundle, it) }.distinctBy { it.type }
                val byType = holders.associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> byType[call.definingClass]?.let { resolveStatic(it, call) } }
                val footer = holders.flatMap { it.methods }.single { method ->
                    method.implementation?.instructions?.any { it.loads(LITHO_FOOTER_TRACE) } == true
                }
                val clickOwners = FixtureDex.methodsWhere(bundle, { dex ->
                    dex.methodSection.any { it.definingClass == footer.definingClass && it.name == footer.name }
                }) { isClickLambda(it, footer) }.map { it.definingClass }.toSet()
                val context = PatchContexts.of(
                    (holders + FixtureDex.classes(bundle, clickOwners).values).distinctBy { it.type } +
                        ExtensionDex.classDef(SETTINGS_STATUS),
                )
                val handlers = context.likeHandlers()
                assertEquals("$name: not one handler per kind",
                    listOf(POST_FOOTER, POST_PLUGIN, REEL, COMMENT, POST_TOUCH, POST_CLICK, COMMENT_INLINE), handlers.map { it.kind })
                val before = handlers.associate { it.kind to it.method.code() }

                // Positive controls: each handler loads its own trace name, the reel's is the static
                // taking the view and its own type, the plugin footer's sender is the static taking
                // the session and the feedback, the click lambda takes the click event and calls the
                // litho footer's handler.
                handlers.filter { it.kind != POST_CLICK }.forEach { handler ->
                    assertTrue("$name: kind ${handler.kind} doesn't name its trace",
                        namesString(handler.method, markers.getValue(handler.kind), resolve))
                }
                val click = handlers.single { it.kind == POST_CLICK }.method
                assertEquals("$name: the click lambda isn't invoke(Object)", listOf("Ljava/lang/Object;"),
                    click.parameterTypes.map { it.toString() })
                assertTrue("$name: the click lambda doesn't call the litho footer's handler",
                    before.getValue(POST_CLICK).any {
                        (it as? ReferenceInstruction)?.reference is MethodReference &&
                            it.reference() == descriptorOf(handlers.single { handler -> handler.kind == POST_FOOTER }.method)
                    })
                val reel = handlers.single { it.kind == REEL }.method
                assertTrue("$name: the reel Like isn't static", AccessFlags.STATIC.isSet(reel.accessFlags))
                assertEquals("$name: the reel Like doesn't take the view first", "Landroid/view/View;", reel.parameterTypes[0].toString())
                val touch = handlers.single { it.kind == POST_TOUCH }.method
                assertTrue("$name: the plugin footer's sender isn't static", AccessFlags.STATIC.isSet(touch.accessFlags))
                assertEquals("$name: the plugin footer's sender doesn't take the session first",
                    "Lcom/facebook/auth/usersession/FbUserSession;", touch.parameterTypes[0].toString())
                assertTrue("$name: the plugin footer's sender takes no feedback",
                    touch.parameterTypes.any { it.toString() == "Lcom/facebook/graphql/model/GraphQLFeedback;" })
                // The plugin footer's lambda has a class of its own on the phone builds, and there it
                // gets no arm guard; 588's Android 9 build merges it with its siblings (`$t`), and
                // there the guard picks its arm.
                val plugin = handlers.single { it.kind == POST_PLUGIN }
                val pluginMerged = byType.getValue(plugin.method.definingClass).instanceFields.any { it.type == "I" }
                assertEquals("$name: the plugin footer's lambda got an arm guard exactly when its class merges arms",
                    pluginMerged, plugin.arm != null)

                // A merged lambda switches on its arm number right after reading it, and exactly one
                // case's arm loads its trace name; the inline comment lambda is merged on every build.
                val likeCases = handlers.filter { it.arm != null }.associate { handler ->
                    handler.kind to armCases(name, handler.kind, before.getValue(handler.kind), handler.arm!!, markers.getValue(handler.kind))
                }
                assertTrue("$name: the inline comment lambda got no arm guard", COMMENT_INLINE in likeCases)
                likeCases.forEach { (kind, cases) -> assertEquals("$name: not one case of kind $kind's lambda likes", 1, cases.size) }

                context.askBeforeLike()
                context.enableStatus("askBeforeLike")

                handlers.forEach { handler ->
                    val method = handler.method
                    val label = "$name: kind ${handler.kind}"
                    // A hook of an odd length moves a switch payload off its 4-byte boundary, so the
                    // writer pads it with a nop that isn't part of the hook.
                    val old = before.getValue(handler.kind).unpadded()
                    val after = context.patched(method).unpadded()
                    val grown = after.size - old.size
                    assertTrue("$label: no hook went in", grown > 0)
                    assertEquals("$label: Facebook's own first instruction moved or changed",
                        old.map { it.opcode }, after.drop(grown).map { it.opcode })
                    val hook = after.take(grown)

                    var at = 0
                    if (handler.arm != null) {
                        assertEquals("$label: no case guard",
                            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET, Opcode.CONST_16, Opcode.IF_NE), hook.take(4).map { it.opcode })
                        assertEquals("$label: the guard doesn't read this",
                            method.localRegisterCount(), (hook[0] as TwoRegisterInstruction).registerB)
                        assertEquals("$label: the guard reads another field", handler.arm!!.field, hook[1].reference())
                        assertEquals("$label: the guard waits for another case",
                            likeCases.getValue(handler.kind).single(), (hook[2] as NarrowLiteralInstruction).narrowLiteral)
                        assertEquals("$label: the guard doesn't skip to Facebook's code", grown, hook[3].target())
                        at = 4
                    }
                    val call = hook.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC && it.reference() == HOLD }
                    assertTrue("$label: the hook doesn't call hold", call > at)
                    assertEquals("$label: the array isn't sized for the instance and every argument",
                        method.parameterTypes.size + 1, (hook[at] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals("$label: no array", Opcode.NEW_ARRAY, hook[at + 1].opcode)
                    val stores = hook.subList(at, call).count { it.opcode == Opcode.APUT_OBJECT }
                    val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
                    assertEquals("$label: not every argument goes into the array", method.parameterTypes.size + self, stores)
                    assertEquals("$label: the wrong kind", handler.kind, (hook[call - 2] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals("$label: the wrong descriptor", descriptorOf(method),
                        ((hook[call - 1] as ReferenceInstruction).reference as StringReference).string)
                    val args = hook[call] as FiveRegisterInstruction
                    assertEquals("$label: hold gets the wrong registers", listOf(0, 1, 2),
                        listOf(args.registerC, args.registerD, args.registerE))
                    assertEquals("$label: the answer isn't tested",
                        listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ), hook.subList(call + 1, call + 3).map { it.opcode })
                    assertEquals("$label: a no doesn't go to Facebook's code", grown, hook[call + 2].target())
                    val exit = hook.drop(call + 3).map { it.opcode }
                    if (method.returnType == "V") {
                        assertEquals("$label: a held tap doesn't return", listOf(Opcode.RETURN_VOID), exit)
                    } else {
                        assertEquals("$label: a held tap doesn't return Unit", listOf(Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT), exit)
                        assertEquals("$label: a held tap returns something other than Unit",
                            "Lkotlin/Unit;", ((hook[call + 3] as ReferenceInstruction).reference as FieldReference).definingClass)
                    }
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "askBeforeLike" }.code()
                assertEquals("$name: the settings screen doesn't show the switches",
                    listOf(Opcode.CONST_4, Opcode.RETURN), status.take(2).map { it.opcode })
                assertEquals("$name: the settings screen doesn't show the switches",
                    1, (status[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += name
            }
        }
        assertTrue("no fixture bundle for ${versions.joinToString()}", checked.isNotEmpty())
    }
}
