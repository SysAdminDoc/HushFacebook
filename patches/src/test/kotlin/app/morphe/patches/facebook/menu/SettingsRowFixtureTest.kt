/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchors of Hushfacebook in the Menu on every declared Facebook build: the native group
 * section builds one row list, from a builder that makes row items (584 inlined it, so the section
 * makes them and reads and writes the kept list once each); one tap handler takes that row
 * item and picks by its id; two loggers take it too; the item has one full constructor storing
 * each argument, the id last, and Facebook's own rows pass their address first.
 */
class SettingsRowFixtureTest {
    /**
     * How many row loggers each declared build's tap class has, by ABI. 582's 32-bit build dropped
     * both; 584's 32-bit build keeps them, as its arm64 build does. 585 inlines the tap handler
     * into a lambda on both, as 583's 32-bit build did, so neither has a tap class to log from.
     */
    private val loggerCount = mapOf(
        "585.0.0.47.54" to mapOf("arm64-v8a" to 0, "armeabi-v7a" to 0, "arm64-v8a-minapi28-storeless" to 0),
    )

    private fun Method.instructionList() = implementation?.instructions?.toList().orEmpty()

    /** The argument registers of an invoke, in order. */
    private fun arguments(instruction: Any): List<Int> = when (instruction) {
        is RegisterRangeInstruction ->
            (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG).take(instruction.registerCount)
        else -> emptyList()
    }

    /** The strings [method] passes as the first address of a row it builds with [constructor]. */
    private fun firstAddresses(method: Method, constructor: String): List<String> {
        val instructions = method.instructionList()
        val found = mutableListOf<String>()
        instructions.forEachIndexed { index, instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
            if ("${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})V" != constructor) return@forEachIndexed
            // this, the title, then the address.
            val register = arguments(instruction).getOrNull(2) ?: return@forEachIndexed
            val load = instructions.subList(0, index).lastOrNull {
                it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
            }
            ((load as? ReferenceInstruction)?.reference as? StringReference)?.let { found += it.string }
        }
        return found
    }

    @Test
    fun `each declared build has one row list, one row tap handler and the row item they share`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val natives = mutableListOf<Method>()
                FixtureDex.forEach(bundle) { dex ->
                    if (NATIVE_SECTION_KEY !in dex.stringSection.toSet()) return@forEach
                    for (classDef in dex.classes) {
                        for (method in classDef.methods) {
                            if (isNativeSectionChildren(method)) natives += ImmutableMethod.of(method)
                        }
                    }
                }
                // 582 asks a string table for the trace, so a tap's dex can hold no copy of it.
                val tables = FixtureDex.classesHolding(bundle, ROW_TAP_TRACE).associateBy { it.type }
                val table: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val tracing = { dex: DexBackedDexFile ->
                    ROW_TAP_TRACE in dex.stringSection || dex.methodSection.any { it.definingClass in tables }
                }
                val taps = FixtureDex.methodsWhere(bundle, tracing) { isRowTap(it, table) }
                assertEquals("${bundle.name}: native Menu group sections", 1, natives.size)
                val builds = rowListBuilds(natives.single())
                // 584 inlined the builder into the section, which keeps the Settings list itself.
                val kept = if (builds.isEmpty()) keptRowLists(natives.single()) else null
                assertEquals("${bundle.name}: row lists the native section builds or keeps", 1, builds.size + listOfNotNull(kept).size)
                val build = builds.singleOrNull()
                if (kept != null) {
                    val sites = keptListReads(natives.single(), kept.settings)
                    assertNotNull("${bundle.name}: another way into the code after a read or write of ${kept.settings}", sites)
                    assertEquals("${bundle.name}: ${kept.settings} read, then written", listOf(Opcode.IGET_OBJECT, Opcode.IPUT_OBJECT),
                        sites!!.map { natives.single().instructionList()[it.index].opcode })
                }
                val abi = bundle.name.substringAfter("-$version-").substringBefore(".apkm")

                // 583's 32-bit build inlined the tap handler into a lambda, so that's where the row is caught.
                val inlined = if (taps.isNotEmpty()) emptyList() else {
                    val rowItems = mutableMapOf<String, Boolean>()
                    val isRowItem = { type: String ->
                        rowItems.getOrPut(type) {
                            FixtureDex.classes(bundle, setOf(type))[type]?.let { fullConstructor(it) != null && idField(it) != null } == true
                        }
                    }
                    FixtureDex.methodsWhere(bundle, tracing) { isLambdaInvoke(it) && namesString(it, ROW_TAP_TRACE, table) }
                        .mapNotNull { inlinedRowTap(it, table, isRowItem) }
                }
                assertEquals("${bundle.name}: Menu row taps, handlers or inlined", 1, taps.size + inlined.size)
                val tap = taps.singleOrNull()
                val item = tap?.let(::rowItemType) ?: inlined.single().itemType

                val classes = FixtureDex.classes(bundle, setOfNotNull(item, build?.definingClass, tap?.definingClass))
                val itemClass = classes.getValue(item)
                val builder = build?.let { call ->
                    classes.getValue(call.definingClass).methods.single {
                        it.name == call.name && it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
                    }
                } ?: natives.single()
                assertTrue("${bundle.name}: the row list builder makes no $item", builder.instructionList().any {
                    it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == item
                })
                assertTrue("${bundle.name}: the row list builder hands back nothing",
                    builder.instructionList().any { it.opcode == Opcode.RETURN_OBJECT })

                // The item: one full constructor storing every argument, the id last.
                val constructor = fullConstructor(itemClass)
                assertNotNull("${bundle.name}: $item's full constructor", constructor)
                val fields = constructorFields(constructor!!)
                assertNotNull("${bundle.name}: $item's constructor fields", fields)
                val id = idField(itemClass)
                assertNotNull("${bundle.name}: $item's id field", id)
                assertEquals("${bundle.name}: the constructor's id field", id!!.name, fields!!.last().name)
                assertTrue("${bundle.name}: the factory would need ${rowFactoryLocals(constructor)} locals",
                    rowFactoryLocals(constructor) <= 15)
                assertTrue("${bundle.name}: $item already has a method the patch adds",
                    itemClass.methods.none { it.name == ROW_FACTORY || it.name == ROW_ID_READER })

                // Facebook's own rows pass the address a tap opens first: Scam protection center
                // is built with its web address there.
                val full = "${constructor.definingClass}-><init>(${constructor.parameterTypes.joinToString("")})V"
                val addresses = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.typeSection.any { it == item }
                }) { method -> method.instructionList().any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.definingClass == item && it.name == "<init>" && it.parameterTypes.size == constructor.parameterTypes.size
                    } == true
                } }.flatMap { firstAddresses(it, full) }
                assertTrue("${bundle.name}: no row built with a web address first: $addresses",
                    addresses.any { it.startsWith("https://www.facebook.com/") })

                if (tap == null) {
                    // The inlined tap takes the patch's six instructions where it found the row, ahead of Facebook's.
                    assertEquals("${bundle.name}: row loggers", loggerCount[version]?.get(abi), 0)
                    val found = inlined.single()
                    val code = found.method.instructionList()
                    val patched = MutableMethod(found.method).apply { skipInlinedRow(found) }.instructionList()
                    assertEquals("${bundle.name}: instructions the inlined tap gains", code.size + 6, patched.size)
                    val onTap = patched[found.hook + 3]
                    assertEquals("${bundle.name}: the inlined tap's hook", ON_TAP,
                        ((onTap as ReferenceInstruction).reference as MethodReference).let {
                            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
                        })
                    for (index in found.hook until code.size) {
                        assertEquals("${bundle.name}: inlined tap instruction $index moved", code[index].opcode, patched[index + 6].opcode)
                    }
                    checked += version
                    continue
                }

                // The tap handler picks by the row's id, and the loggers of its class take the row.
                assertTrue("${bundle.name}: the tap handler never reads the row's id", tap.instructionList().any {
                    it.opcode == Opcode.IGET_WIDE && ((it as ReferenceInstruction).reference as FieldReference).name == id.name
                })
                val owner = classes.getValue(tap.definingClass)
                val ownerTap = owner.methods.single { it.name == tap.name && it.parameterTypes.size == tap.parameterTypes.size }
                val loggers = rowLoggers(owner, ownerTap, item)
                assertEquals("${bundle.name}: row loggers", loggerCount[version]?.get(abi), loggers.size)
                for (method in loggers + ownerTap) {
                    assertTrue("${bundle.name}: ${method.name} has ${method.localRegisterCount()} locals",
                        method.localRegisterCount() >= 3)
                }
                assertTrue("${bundle.name}: a logger doesn't read the row's id", loggers.all { logger ->
                    logger.instructionList().any {
                        it.opcode == Opcode.IGET_WIDE && ((it as ReferenceInstruction).reference as FieldReference).name == id.name
                    }
                })
                assertTrue(namesString(tap, ROW_TAP_TRACE, table))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
