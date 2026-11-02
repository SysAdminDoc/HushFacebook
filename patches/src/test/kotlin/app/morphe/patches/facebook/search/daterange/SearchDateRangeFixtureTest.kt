/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.daterange

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search date range on every Facebook build the bundle declares, both ABIs of each. One method
 * holds the filter tap mutation, reads a filter's kind through an interface call answering the enum
 * that holds all three kinds, and then reads the bottom sheet flag with a mobile config call tested
 * with if-nez. Before that read, the filter sheet's own callback is tested by an if-eqz jumping to
 * it, and with a callback the tap goes there. The filter's interface keeps the getName the extension
 * reads by reflection. Patched, the callback, the kind and the flag each go through the extension
 * first, and the rest of Facebook's code stays as it was.
 */
class SearchDateRangeFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun tap(bundle: File) {
        val holders = FixtureDex.classesHolding(bundle, TAP_FILTER).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        // The kind enum lives in another dex of the bundle, so every class the tap handlers name is fetched. String and
        // the other framework types they answer stay out of the pool, as they are out of Facebook's, and the patch's
        // own lookup has to pass over them.
        val named = holders.flatMap { methodsHolding(it, TAP_FILTER) }.flatMap { method ->
            method.implementation!!.instructions
                .mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.returnType }
        }.filter { it.startsWith("L") }.toSet()
        val found = PatchContexts.of((holders + FixtureDex.classes(bundle, named).values).distinctBy { it.type })
            .kindReads(holders)
        assertEquals("${bundle.name}: one method reads a filter's kind and then the flag, found ${found.map { it.first.descriptor() }}",
            1, found.size)
        val (method, read) = found.single()
        val where = "${bundle.name}: ${method.descriptor()}"
        val original = method.implementation!!.instructions.toList()

        // The kind is what Facebook sends on to its screens: the ordinal of that register is read.
        val ordinal = original.drop(read.flagResult).any { instruction ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL && (instruction as ReferenceInstruction).reference.let {
                it is MethodReference && it.name == "ordinal" && it.definingClass == "Ljava/lang/Enum;"
            }
        }
        assertTrue("$where reads the kind's ordinal after the flag", ordinal)

        // The filter's interface keeps getName(), which the extension reads by reflection.
        val filterInterface = ((original[read.call] as ReferenceInstruction).reference as MethodReference).definingClass
        val filter = FixtureDex.classes(bundle, setOf(filterInterface)).values.single()
        assertTrue("$where: the filter interface ${filter.type} keeps a getName()String",
            filter.methods.any { it.name == "getName" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" })

        // The filter sheet's own callback is tested before the kind read, and with one the tap goes to it.
        val sheet = callbackTest(method, read)
        assertTrue("$where tests the filter sheet's callback right before its kind read", sheet != null)
        sheet!!
        val invoked = original[sheet.test + 1] as ReferenceInstruction
        assertTrue("$where: the callback is what runs when it's there",
            original[sheet.test + 1].opcode == Opcode.INVOKE_INTERFACE &&
                (invoked as FiveRegisterInstruction).registerC == sheet.callback &&
                (invoked.reference as MethodReference).definingClass == sheet.callbackType)

        val owner = FixtureDex.classes(bundle, setOf(method.definingClass)).values.single()
        val context = PatchContexts.of(listOf(owner))
        val mutable = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == method.descriptor() }
        mutable.hookFlag(read)
        mutable.hookKind(read)
        mutable.hookCallback(sheet)
        val patched = mutable.implementation!!.instructions.toList().filter { it.opcode != Opcode.NOP }
        val stock = original.filter { it.opcode != Opcode.NOP }
        assertEquals("$where gains the callback's three, the kind's three and the flag's two instructions",
            stock.size + 8, patched.size)
        assertEquals("$where: the callback is handed over", CALLBACK,
            (patched[sheet.test] as ReferenceInstruction).reference.toString())
        assertEquals("$where: its answer goes back to the callback's register", sheet.callback,
            (patched[sheet.test + 1] as OneRegisterInstruction).registerA)
        assertEquals("$where: and is cast to the callback", Opcode.CHECK_CAST, patched[sheet.test + 2].opcode)
        assertEquals("$where: then Facebook tests it", Opcode.IF_EQZ, patched[sheet.test + 3].opcode)
        val atKind = read.call + 3 + 1
        assertEquals("$where: the kind is handed over", KIND, (patched[atKind + 1] as ReferenceInstruction).reference.toString())
        assertEquals("$where: its answer goes back to the kind's register", read.kind,
            (patched[atKind + 2] as OneRegisterInstruction).registerA)
        assertEquals("$where: and is cast to the kind", Opcode.CHECK_CAST, patched[atKind + 3].opcode)
        assertEquals("$where: the flag is handed over", BOTTOM_SHEET,
            (patched[read.flagResult + 7] as ReferenceInstruction).reference.toString())
        assertEquals("$where: the rest of Facebook's code stays",
            stock.map { it.opcode },
            patched.filterIndexed { index, _ ->
                index !in (sheet.test..sheet.test + 2) && index !in (atKind + 1..atKind + 3) &&
                    index !in (read.flagResult + 7..read.flagResult + 8)
            }.map { it.opcode })
    }

    private fun days(bundle: File) {
        // The picker hands back its view from one return, which the day wheels can wrap.
        val picker = FixtureDex.classes(bundle, setOf(PICKER)).values.singleOrNull()
        assertTrue("${bundle.name}: $PICKER is there", picker != null)
        val view = picker!!.methods.single(::isPickerView)
        val returns = pickerReturns(view)
        assertEquals("${bundle.name}: ${view.descriptor()} hands back its view once", 1, returns?.size)
        val stockView = view.implementation!!.instructions.toList()
        val viewContext = PatchContexts.of(listOf(picker))
        val mutableView = viewContext.mutableClassDefBy(PICKER).methods.single { it.descriptor() == view.descriptor() }
        mutableView.hookPickerView(returns!!)
        val wrapped = mutableView.implementation!!.instructions.toList()
        assertEquals("${bundle.name}: the view goes through the extension right before it's returned", DAY_ROW,
            (wrapped[returns.single()] as ReferenceInstruction).reference.toString())
        assertEquals(stockView.size + 2, wrapped.size)

        // One method writes a range's months and then its label, and sends both on.
        val holders = FixtureDex.classesHolding(bundle, RANGE_MONTHS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val writes = holders.flatMap { methodsHolding(it, RANGE_MONTHS) }.mapNotNull { m -> rangeWrite(m)?.let { m to it } }
        assertEquals("${bundle.name}: one method writes a range's months and label, found ${writes.map { it.first.descriptor() }}",
            1, writes.size)
        val (sender, write) = writes.single()
        val where = "${bundle.name}: ${sender.descriptor()}"
        val stock = sender.implementation!!.instructions.toList()
        // The months text is what goes into the creation_time filter's args.
        val wrapsMonths = stock.drop(write.monthsResult + 1).take(4).any { instruction ->
            instruction.opcode == Opcode.INVOKE_STATIC && (instruction as FiveRegisterInstruction).registerCount == 2 &&
                instruction.registerD == write.months
        }
        assertTrue("$where hands the months text to the call writing the filter's args", wrapsMonths)
        // The label is the one a FilterPersistentState is built with.
        val built = stock.drop(write.labelResult + 1).firstOrNull { instruction ->
            instruction.opcode == Opcode.INVOKE_DIRECT && ((instruction as ReferenceInstruction).reference as MethodReference)
                .definingClass.endsWith("/FilterPersistentState;")
        } as FiveRegisterInstruction?
        // Its constructor takes the filter's name, then the label.
        assertTrue("$where builds a FilterPersistentState with the label", built != null && built.registerE == write.label)

        val owner = FixtureDex.classes(bundle, setOf(sender.definingClass)).values.single()
        val mutable = PatchContexts.of(listOf(owner)).mutableClassDefBy(owner.type).methods
            .single { it.descriptor() == sender.descriptor() }
        mutable.hookRangeWrite(write)
        val patched = mutable.implementation!!.instructions.toList()
        assertEquals("$where gains two instructions for the months and two for the label", stock.size + 4, patched.size)
        assertEquals("$where: the months are handed over", DAYS,
            (patched[write.monthsResult + 1] as ReferenceInstruction).reference.toString())
        assertEquals("$where: their answer goes back", write.months,
            (patched[write.monthsResult + 2] as OneRegisterInstruction).registerA)
        assertEquals("$where: the label is handed over", LABEL,
            (patched[write.labelResult + 3] as ReferenceInstruction).reference.toString())
        assertEquals("$where: with the DateFormat", write.format, (patched[write.labelResult + 3] as FiveRegisterInstruction).registerD)
        assertEquals("$where: the rest of Facebook's code stays", stock.map { it.opcode },
            patched.filterIndexed { index, _ ->
                index !in (write.monthsResult + 1..write.monthsResult + 2) && index !in (write.labelResult + 3..write.labelResult + 4)
            }.map { it.opcode })
    }

    private fun state(bundle: File) {
        val holder = FixtureDex.classes(bundle, setOf(FILTER_STATE)).values.singleOrNull()
        assertTrue("${bundle.name}: $FILTER_STATE is there", holder != null)
        val constructor = holder!!.methods.singleOrNull(::isStateConstructor)
        assertTrue("${bundle.name}: $FILTER_STATE has one constructor from four Strings", constructor != null)
        val where = "${bundle.name}: ${constructor!!.descriptor()}"
        val stock = constructor.implementation!!.instructions.toList()
        // The parameters sit in the last five registers, this first.
        val first = constructor.implementation!!.registerCount - 5
        // Facebook sends the first String as the filter's name and the third as its value.
        fun sent(key: String) = stock.indices.firstNotNullOfOrNull { at ->
            val named = ((stock[at] as? ReferenceInstruction)?.reference as? StringReference)?.string == key
            if (!named) return@firstNotNullOfOrNull null
            (stock.drop(at + 1).firstOrNull { it.opcode == Opcode.INVOKE_VIRTUAL } as? FiveRegisterInstruction)?.registerE
        }
        assertEquals("$where sends its first String as the name", first + 1, sent("name"))
        assertEquals("$where sends its third String as the value", first + 3, sent("value"))
        // The second is kept as the label, in a field of its own.
        assertTrue("$where keeps its second String in a field", stock.any {
            it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == first + 2 &&
                ((it as ReferenceInstruction).reference as FieldReference).type == "Ljava/lang/String;"
        })

        val mutable = PatchContexts.of(listOf(holder)).mutableClassDefBy(FILTER_STATE).methods
            .single { it.descriptor() == constructor.descriptor() }
        mutable.hookStateLabel()
        val patched = mutable.implementation!!.instructions.toList()
        assertEquals("$where gains two instructions", stock.size + 2, patched.size)
        val call = patched[0] as FiveRegisterInstruction
        assertEquals("$where: the name, label and value are handed over first", STATE_LABEL,
            (call as ReferenceInstruction).reference.toString())
        assertEquals("$where: in that order", listOf(first + 1, first + 2, first + 3),
            listOf(call.registerC, call.registerD, call.registerE))
        assertEquals("$where: the answer becomes the label", first + 2, (patched[1] as OneRegisterInstruction).registerA)
        assertEquals("$where: the rest of Facebook's code stays", stock.map { it.opcode }, patched.drop(2).map { it.opcode })
    }

    private fun reset(bundle: File) {
        val holder = FixtureDex.classes(bundle, setOf(FILTER_STATE)).values.single()
        val fields = stateFields(holder.methods.single(::isStateConstructor))
        assertTrue("${bundle.name}: $FILTER_STATE keeps its name, label, value and key in fields", fields != null)
        fields!!
        val resets = FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.fieldSection.any { it.definingClass == FILTER_STATE } }) {
            !it.definingClass.startsWith(EXTENSION_CLASSES) && statesReset(it, fields) != null
        }
        assertEquals("${bundle.name}: one method fills the filter states from the server's answer, found " +
            "${resets.map { it.descriptor() }}", 1, resets.size)
        val reset = resets.single()
        val where = "${bundle.name}: ${reset.descriptor()}"
        val kept = statesReset(reset, fields)!!
        val stock = reset.implementation!!.instructions.toList()
        // Facebook hands the refilled states on to the filter screen's listeners.
        assertTrue("$where tells its listeners", stock.any { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            instruction.opcode == Opcode.INVOKE_INTERFACE && call != null && call.returnType == "V" &&
                call.parameterTypes.firstOrNull()?.toString() == IMMUTABLE_LIST
        })

        val owner = FixtureDex.classes(bundle, setOf(reset.definingClass)).values.single()
        val mutable = PatchContexts.of(listOf(owner)).mutableClassDefBy(owner.type).methods
            .single { it.descriptor() == reset.descriptor() }
        mutable.hookStatesReset(kept)
        val patched = mutable.implementation!!.instructions.toList()
        assertEquals("$where gains six instructions", stock.size + 6, patched.size)
        assertEquals("$where: the kept map, the call, and its answer copied over the incoming list",
            listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_OBJECT),
            patched.take(6).map { it.opcode })
        assertEquals("$where: the map handed over is the one it clears", kept.toString(),
            (patched[0] as ReferenceInstruction).reference.toString())
        assertEquals("$where: the extension is handed it", KEPT_STATES, (patched[1] as ReferenceInstruction).reference.toString())
        assertEquals("$where: its answer is copied", COPY_OF, (patched[4] as ReferenceInstruction).reference.toString())
        val list = mutable.implementation!!.registerCount - 1
        assertEquals("$where: with the incoming list", list, (patched[1] as FiveRegisterInstruction).registerD)
        assertEquals("$where: and the copy replaces it", list, (patched[5] as OneRegisterInstruction).registerA)
        val address = IntArray(patched.size + 1)
        for (at in patched.indices) address[at + 1] = address[at] + patched[at].codeUnits
        assertEquals("$where: with no answer, Facebook's own first instruction runs", address[6],
            address[3] + (patched[3] as OffsetInstruction).codeOffset)
        assertEquals("$where: the rest of Facebook's code stays", stock.map { it.opcode }, patched.drop(6).map { it.opcode })
    }

    private fun bridge(bundle: File) {
        val holder = FixtureDex.classes(bundle, setOf(FILTER_STATE)).values.single()
        val fields = stateFields(holder.methods.single(::isStateConstructor))!!
        val context = PatchContexts.of(listOf(holder))
        context.bridgeFilterState(fields)
        context.bridgeFilterState(fields)
        val state = context.mutableClassDefBy(FILTER_STATE)
        assertTrue("${bundle.name}: $FILTER_STATE is made a $STATE_BRIDGE", STATE_BRIDGE in state.interfaces)
        for ((name, field) in listOf(STATE_NAME to fields.name, STATE_VALUE to fields.value)) {
            val methods = state.methods.filter { it.name == name }
            assertEquals("${bundle.name}: $FILTER_STATE gains $name once", 1, methods.size)
            val method = methods.single()
            assertTrue("${bundle.name}: $name is public, takes nothing and answers a String",
                method.accessFlags == AccessFlags.PUBLIC.value && method.parameterTypes.isEmpty() &&
                    method.returnType == "Ljava/lang/String;")
            val code = method.implementation!!.instructions.toList()
            assertEquals("${bundle.name}: $name answers its field", listOf(Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT),
                code.map { it.opcode })
            assertEquals("${bundle.name}: $name reads ${field.name}", field.toString(),
                (code[0] as ReferenceInstruction).reference.toString())
        }
    }

    @Test
    fun `each declared build fills its filter states from the server's answer in one place, and a kept range can stay`() =
        bundles(::reset)

    @Test
    fun `each declared build's filter state can answer its name and value to the extension`() = bundles(::bridge)

    @Test
    fun `each declared build reads a filter's kind, the sheet flag and the sheet callback in one place and hooks all three`() =
        bundles(::tap)

    @Test
    fun `each declared build hands back the range picker's view and writes a range's months and label where they can be hooked`() =
        bundles(::days)

    @Test
    fun `each declared build builds a filter state from its name, label and value, and the label can be handed back`() =
        bundles(::state)
}
