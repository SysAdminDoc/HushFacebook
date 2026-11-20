/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.confirm

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.stories.taps.PAUSE_BUTTON
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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ask before a story reaction on every declared build and ABI (#502). The story viewer's reaction
 * bar handler and the single reaction button's lambda each start by handing the extension their
 * descriptor, instance and arguments (the bar's int boxed), and return right away when it holds the
 * tap; otherwise they jump to Facebook's own first instruction, unmoved. The lambda only asks on
 * the arm holding its marker. The three pause stubs are filled with the route Facebook's own dialog
 * toggles pause a story by: the environment's getter, a cast to the pauser, ALERT_DIALOG_OPEN, and
 * the pause that adds that reason to the pauser's set and the resume that takes it out. A build
 * missing a handler changes nothing, and one whose toggles are gone still asks without the pause.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class AskBeforeStoryReactionFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
    private val markers = mapOf(STORY_REACTION to STORY_REACTION_NAME, STORY_SINGLE_REACTION to SINGLE_REACTION)

    private fun Method.stock() = implementation!!.instructions.toList()

    /** [this] without the nop the dex writer puts before a payload to align it. */
    private fun List<Instruction>.unpadded() =
        filterIndexed { i, it -> it.opcode != Opcode.NOP || getOrNull(i + 1)?.opcode?.format?.isPayloadFormat != true }

    /** An opcode with a goto widened by the insert read as the plain goto it was. */
    private fun Instruction.kind() = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode
    private fun Instruction.reference() = (this as ReferenceInstruction).reference.toString()
    private fun Instruction.loads(string: String) =
        (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
            ((this as ReferenceInstruction).reference as StringReference).string == string

    /** Whether [this] names [type]: a field, method or type reference with it in. */
    private fun Instruction.names(type: String) = when (val reference = (this as? ReferenceInstruction)?.reference) {
        is FieldReference -> reference.definingClass == type || reference.type == type
        is MethodReference -> reference.definingClass == type || reference.returnType == type ||
            reference.parameterTypes.any { it.toString() == type }
        is TypeReference -> reference.type == type
        else -> false
    }

    private fun holding(bundle: File, string: String): List<ClassDef> =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }

    /**
     * The classes holding [string] and, where one of them is a string table, the classes asking it:
     * 588's store-less build asks a table for "story_reaction".
     */
    private fun classesNaming(bundle: File, string: String): List<ClassDef> {
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
    }.implementation!!.instructions.toList()

    private fun BytecodePatchContext.stub(name: String) =
        mutableClassDefBy(ASK_BEFORE_LIKE).methods.single { it.name == name }.implementation!!

    private fun stockStub(name: String) =
        ExtensionDex.classDef(ASK_BEFORE_LIKE).methods.single { it.name == name }.stock().map { it.opcode }

    private fun Instruction.target() = (this as BuilderOffsetInstruction).target.location.index

    /** The index [at]'s branch in unpatched [code] lands on, or the one [offset] from it. */
    private fun branchTarget(code: List<Instruction>, at: Int, offset: Int = (code[at] as OffsetInstruction).codeOffset): Int {
        val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
        return addresses.indexOf(addresses[at] + offset)
    }

    /**
     * The case key stock [code] calls for, read on its own: Facebook switches on [field] right after
     * reading it, and the key is the one case whose arm (up to the next case, or the end) loads [marker].
     */
    private fun caseKeyOf(code: List<Instruction>, field: String, marker: String, label: String): Int {
        val readAt = code.indexOfFirst { it.opcode == Opcode.IGET && it.reference() == field }
        val markerAt = code.indexOfFirst { it.loads(marker) }
        assertTrue("$label: doesn't read its arm number before \"$marker\"", readAt in 0 until markerAt)
        val switchAt = (readAt + 1 until markerAt).firstOrNull {
            code[it].opcode == Opcode.PACKED_SWITCH || code[it].opcode == Opcode.SPARSE_SWITCH
        }
        assertTrue("$label: doesn't switch on its arm number right after reading it", switchAt != null && switchAt - readAt <= 5)
        val payload = code[branchTarget(code, switchAt!!)] as SwitchPayload
        val starts = payload.switchElements.associate { it.key to branchTarget(code, switchAt, it.offset) }
        val bounds = (starts.values + (switchAt + 1) + code.size).toSortedSet()
        val cases = starts.filter { (_, start) -> code.subList(start, bounds.first { it > start }).any { it.loads(marker) } }.keys
        assertEquals("$label: not one case of the switch holds \"$marker\"", 1, cases.size)
        return cases.single()
    }

    /** What a bundle hands the patch: the handlers' holders, the reason enum and every class naming it, and what the toggles reach. */
    private class Slice(val handlers: List<ClassDef>, val reason: ClassDef, val alert: String, val naming: List<ClassDef>, val reached: List<ClassDef>)

    private fun slice(bundle: File): Slice {
        val name = bundle.name
        val handlers = markers.values.flatMap { classesNaming(bundle, it) }.distinctBy { it.type }
        val reasons = holding(bundle, ALERT_DIALOG_OPEN)
            .filter { AccessFlags.ENUM.isSet(it.accessFlags) && enumConstant(it, PAUSE_BUTTON) != null }
        assertEquals("$name: not one pause reason enum keeps $ALERT_DIALOG_OPEN and $PAUSE_BUTTON", 1, reasons.size)
        val reason = reasons.single()
        val alert = enumConstant(reason, ALERT_DIALOG_OPEN)!!
        val naming = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.typeSection.none { it.toString() == reason.type }) return@forEach
            dex.classes.filter { c ->
                c.type != reason.type && c.methods.any { m -> m.implementation?.instructions?.any { it.names(reason.type) } == true }
            }.mapTo(naming) { ImmutableClassDef.of(it) }
        }
        // The classes a toggle calls, casts to or hands over, which the route's access checks read.
        val toggles = naming.flatMap { it.methods }.filter { isDialogPauseToggle(it, alert) }
        val types = toggles.flatMap { it.stock() }.flatMap { instruction ->
            when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                is MethodReference -> listOf(reference.definingClass) + reference.parameterTypes.map { it.toString() }
                is TypeReference -> listOf(reference.type)
                else -> emptyList()
            }
        }.filter { it.startsWith("L") }.toSet()
        return Slice(handlers, reason, alert, naming, FixtureDex.classes(bundle, types).values.toList())
    }

    private fun contextOf(classes: List<ClassDef>) = PatchContexts.of(
        classes.distinctBy { it.type } + ImmutableClassDef.of(ExtensionDex.classDef(ASK_BEFORE_LIKE)) +
            ExtensionDex.classDef(SETTINGS_STATUS),
    )

    @Test
    fun `each declared build's story reactions ask first and the question pauses the story`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val slice = slice(bundle)
                val all = slice.handlers + slice.reason + slice.naming + slice.reached
                val context = contextOf(all)
                val handlers = context.storyReactionHandlers()
                assertEquals("$name: not one handler per kind", listOf(STORY_REACTION, STORY_SINGLE_REACTION), handlers.map { it.kind })
                val before = handlers.associate { it.kind to it.method.stock() }

                // Positive controls: each handler loads its own marker, the bar handler takes the
                // StoryBucket and StoryCard and ends with an int, and keeps the story viewer's
                // environment in a field of its own; the lambda reads its arm number off itself.
                val byType = all.associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> byType[call.definingClass]?.let { resolveStatic(it, call) } }
                handlers.forEach { handler ->
                    assertTrue("$name: kind ${handler.kind} doesn't name \"${markers.getValue(handler.kind)}\"",
                        namesString(handler.method, markers.getValue(handler.kind), resolve))
                }
                val bar = handlers.single { it.kind == STORY_REACTION }.method
                assertEquals("$name: the bar handler's arguments",
                    listOf("Landroid/view/View;", "Landroid/view/ViewGroup;", "Lcom/facebook/stories/model/StoryBucket;",
                        "Lcom/facebook/stories/model/StoryCard;"),
                    bar.parameterTypes.take(4).map { it.toString() })
                assertEquals("$name: the bar handler doesn't end with an int", "I", bar.parameterTypes.last().toString())
                val single = handlers.single { it.kind == STORY_SINGLE_REACTION }
                assertEquals("$name: the single reaction isn't a lambda's invoke", "invoke", single.method.name)
                assertTrue("$name: the lambda's guard reads a field off something other than itself",
                    single.arm!!.field.startsWith(single.method.definingClass + "->") && single.arm!!.field.endsWith(":I"))
                val key = caseKeyOf(before.getValue(STORY_SINGLE_REACTION), single.arm!!.field, SINGLE_REACTION, "$name: the lambda")

                val route = context.storyPauseRoute()
                val toggles = slice.naming.flatMap { it.methods }.filter { isDialogPauseToggle(it, slice.alert) }
                assertTrue("$name: no dialog pause toggle to read the route off", toggles.isNotEmpty())
                assertEquals("$name: the route pauses under another reason", slice.alert, route.reason)
                val barClass = all.first { it.type == bar.definingClass }
                assertTrue("$name: the bar handler keeps no ${route.environment}, which the extension looks for",
                    barClass.fields.any { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == route.environment })
                // Each toggle gets the pauser from the getter, casts it and calls both.
                toggles.forEach { toggle ->
                    val references = toggle.stock().filterIsInstance<ReferenceInstruction>().map { it.reference.toString() }
                    listOf(route.getter, route.pauser, route.pause, route.resume).forEach {
                        assertTrue("$name: ${toggle.definingClass}->${toggle.name} doesn't use $it", it in references)
                    }
                }
                // Independently of the toggles' branch: the pause adds the reason to the pauser's set
                // and the resume takes it out, so a pause without its resume can't stick.
                val pauserClass = all.first { it.type == route.pauser }
                fun body(reference: String) = pauserClass.methods.single {
                    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == reference
                }.stock().filterIsInstance<ReferenceInstruction>().map { it.reference }.filterIsInstance<MethodReference>()
                    .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
                assertTrue("$name: the pause doesn't add its reason", "add(Ljava/lang/Object;)Z" in body(route.pause))
                assertTrue("$name: the pause takes a reason out", "remove(Ljava/lang/Object;)Z" !in body(route.pause))
                assertTrue("$name: the resume doesn't take its reason out", "remove(Ljava/lang/Object;)Z" in body(route.resume))
                assertNotEquals("$name: pause and resume are one", route.pause, route.resume)

                context.askBeforeStoryReaction()

                handlers.forEach { handler ->
                    val method = handler.method
                    val label = "$name: kind ${handler.kind}"
                    val old = before.getValue(handler.kind).unpadded()
                    val after = context.patched(method).unpadded()
                    val grown = after.size - old.size
                    assertTrue("$label: no hook went in", grown > 0)
                    assertEquals("$label: Facebook's own code moved or changed",
                        old.map { it.kind() }, after.drop(grown).map { it.kind() })
                    val hook = after.take(grown)

                    var at = 0
                    handler.arm?.let { arm ->
                        assertEquals("$label: no case guard", listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET), hook.take(2).map { it.opcode })
                        assertEquals("$label: the guard reads the wrong register", method.localRegisterCount(),
                            (hook[0] as TwoRegisterInstruction).registerB)
                        assertEquals("$label: the guard reads another field", arm.field, hook[1].reference())
                        assertTrue("$label: the guard loads no case key", hook[2].opcode == Opcode.CONST_16 || hook[2].opcode == Opcode.CONST)
                        assertEquals("$label: the guard waits for another case", key, (hook[2] as NarrowLiteralInstruction).narrowLiteral)
                        assertEquals("$label: the guard doesn't compare the case", Opcode.IF_NE, hook[3].opcode)
                        assertEquals("$label: the guard doesn't skip to Facebook's code", grown, hook[3].target())
                        at = 4
                    }
                    val call = hook.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC && it.reference() == HOLD }
                    assertTrue("$label: the hook doesn't call hold", call > at)
                    assertEquals("$label: the array isn't sized for the instance and every argument",
                        method.parameterTypes.size + 1, (hook[at] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals("$label: no array", Opcode.NEW_ARRAY, hook[at + 1].opcode)
                    val stores = hook.subList(at, call).filter { it.opcode == Opcode.APUT_OBJECT }
                    assertEquals("$label: not every argument goes into the array", method.parameterTypes.size + 1, stores.size)
                    // An int goes in boxed, from its own register.
                    val boxes = hook.indices.filter { hook[it].opcode == Opcode.INVOKE_STATIC_RANGE }
                    val ints = method.parameterTypes.indices.filter { method.parameterTypes[it].toString() == "I" }
                    assertEquals("$label: not one box per int", ints.size, boxes.size)
                    boxes.zip(ints).forEach { (box, index) ->
                        assertEquals("$label: the int isn't boxed as an Integer", "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;",
                            hook[box].reference())
                        val boxed = hook[box] as RegisterRangeInstruction
                        assertEquals("$label: another register is boxed", listOf(1, method.parameterRegisterNumber(index)),
                            listOf(boxed.registerCount, boxed.startRegister))
                        assertEquals("$label: the box isn't stored", listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.APUT_OBJECT),
                            hook.subList(box + 1, box + 3).map { it.opcode })
                        assertEquals("$label: something else is stored for the int", (hook[box + 1] as OneRegisterInstruction).registerA,
                            (hook[box + 2] as ThreeRegisterInstruction).registerA)
                    }
                    assertEquals("$label: the kind doesn't take a wide constant", Opcode.CONST_16, hook[call - 2].opcode)
                    assertEquals("$label: the wrong kind", handler.kind, (hook[call - 2] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals("$label: the wrong descriptor", descriptorOf(method),
                        ((hook[call - 1] as ReferenceInstruction).reference as StringReference).string)
                    val args = hook[call] as FiveRegisterInstruction
                    assertEquals("$label: hold gets the wrong registers", listOf(0, 1, 2), listOf(args.registerC, args.registerD, args.registerE))
                    assertEquals("$label: the answer isn't tested",
                        listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ), hook.subList(call + 1, call + 3).map { it.opcode })
                    assertEquals("$label: a no doesn't go to Facebook's code", grown, hook[call + 2].target())
                    val exit = hook.drop(call + 3)
                    if (method.returnType == "V") {
                        assertEquals("$label: a held tap doesn't return", listOf(Opcode.RETURN_VOID), exit.map { it.opcode })
                    } else {
                        assertEquals("$label: a held tap doesn't return Unit",
                            listOf(Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT), exit.map { it.opcode })
                        assertEquals("$label: a held tap returns something other than Unit",
                            "Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;", exit[0].reference())
                    }
                }

                // The stubs, filled with the route, each in two registers with the argument in the second.
                val pauserOf = context.stub(STORY_PAUSER_OF)
                assertEquals("$name: the pauser stub",
                    listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
                        Opcode.RETURN_OBJECT, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                    pauserOf.instructions.map { it.opcode })
                val pauserCode = pauserOf.instructions.toList()
                assertEquals("$name: tests for the environment", route.environment, pauserCode[0].reference())
                assertEquals("$name: casts to the environment", route.environment, pauserCode[2].reference())
                assertEquals("$name: asks the getter", route.getter, pauserCode[3].reference())
                for ((stubName, target) in listOf(PAUSE_STORY to route.pause, RESUME_STORY to route.resume)) {
                    val stub = context.stub(stubName)
                    val code = stub.instructions.toList()
                    assertEquals("$name: $stubName", listOf(Opcode.CHECK_CAST, Opcode.SGET_OBJECT, route.invoke, Opcode.RETURN_VOID),
                        code.map { it.opcode })
                    assertEquals("$name: $stubName casts to the pauser", route.pauser, code[0].reference())
                    assertEquals("$name: $stubName loads the reason", slice.alert, code[1].reference())
                    assertEquals("$name: $stubName calls another method", target, code[2].reference())
                    assertEquals("$name: $stubName works in another register count", 2, stub.registerCount)
                    assertEquals("$name: $stubName hands over other registers", listOf(1, 0),
                        listOf((code[2] as FiveRegisterInstruction).registerC, (code[2] as FiveRegisterInstruction).registerD))
                }
                checked += name
            }
        }
        assertTrue("no fixture bundle for ${versions.joinToString()}", checked.isNotEmpty())
    }

    @Test
    fun `a build missing a story reaction handler changes nothing, and one without the pause toggles still asks`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val slice = slice(bundle)
                val stubs = listOf(STORY_PAUSER_OF, PAUSE_STORY, RESUME_STORY)

                // No single reaction lambda: neither handler changes and the stubs stay stubs.
                val gone = holding(bundle, SINGLE_REACTION).map { it.type }.toSet()
                val kept = classesNaming(bundle, STORY_REACTION_NAME).filterNot { it.type in gone }
                val stock = kept.flatMap { it.methods }.filter(::isStoryReactionBar)
                assertEquals("$name: no bar handler kept to watch", 1, stock.size)
                val missing = contextOf(kept + slice.reason + slice.naming + slice.reached)
                try {
                    missing.askBeforeStoryReaction()
                    fail("$name: a build without the single reaction lambda was patched")
                } catch (expected: PatchException) {
                    assertTrue("$name: ${expected.message}", expected.message!!.contains(SINGLE_REACTION))
                }
                assertEquals("$name: the bar handler changed anyway", stock.single().stock().map { it.opcode },
                    missing.patched(stock.single()).map { it.opcode })
                stubs.forEach {
                    assertEquals("$name: $it was filled anyway", stockStub(it), missing.stub(it).instructions.map { i -> i.opcode })
                }

                // No toggles: both handlers ask, over a playing story.
                val playing = contextOf(slice.handlers + slice.reason)
                val found = playing.storyReactionHandlers()
                playing.askBeforeStoryReaction()
                found.forEach { handler ->
                    assertTrue("$name: kind ${handler.kind} wasn't hooked without the pause",
                        playing.patched(handler.method).any { it.opcode == Opcode.INVOKE_STATIC && it.reference() == HOLD })
                }
                stubs.forEach {
                    assertEquals("$name: $it was filled without a route", stockStub(it), playing.stub(it).instructions.map { i -> i.opcode })
                }
                checked += name
            }
        }
        assertTrue("no fixture bundle for ${versions.joinToString()}", checked.isNotEmpty())
    }
}
