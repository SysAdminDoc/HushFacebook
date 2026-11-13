/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.media.taptoplay.isEnumNaming
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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
 * The anchors of Hide Menu promotions on every declared Facebook build: one native group section
 * and one server group section, each reading the Menu's group enum once and building its children
 * through the same list; a free local where the hooks go; the native section drawing the
 * collapsible header the phone reads out; and the Upgrades and Also from Meta groups being the
 * enum's UPSELL and PRODUCTS_FROM_FACEBOOK, where the root adds them.
 */
class MenuSectionsFixtureTest {
    /**
     * The id of "%1$s, header. Section is %2$s. Double-tap to %3$s the section." in each build, the
     * label TalkBack reads on the Menu's group headers ("Upgrades, header. Section is expanded.").
     * Read from Facebook's English string pack. Up to 581 that's assets/strings/default.frsc.xz. 582
     * ships it as default.frsc.spo (SuperPack, which nothing outside Facebook reads), and the app
     * unpacks it on first launch into app_strings/uncompressed_default.frsc.xz, a plain FRSC table,
     * where this id was read (stock 582 on an emulator, 2026-10-10: 0x7f140452). 583 packs it the same
     * way, so its id is 582's moved by the shift between the two builds' tables (5 here, found from
     * the locale packs both ship, which stay plain FRSC), and the sections that draw the header load
     * it. The unpacked 583 table is the check still owed. 584's header component (`LX/Lmm;`) loads
     * the same five ids as 583's (`LX/S3S;`) in the same order, each 9 higher, so its label is 0x7f140460.
     * 585's (`LX/0IqZ;->A17` arm64) loads 584's five in the same order, each 6 higher, so 0x7f140466.
     * 586's header asks the bookmark UI's kept `getHeadContentDescription` for its words, which formats
     * 0x7f14046c with the title, 585's two states (each 6 higher) and its two actions (each 8 higher),
     * since 586 adds a one-argument label behind a MobileConfig flag between them. 587's
     * `getHeadContentDescription` (`LX/0HF4;` arm64) is 586's with every id 12 higher, so 0x7f140478.
     */
    private val headerLabel = mapOf(
        "585.0.0.47.54" to 0x7f140466,
        "586.0.0.52.53" to 0x7f14046c,
        "587.0.0.39.55" to 0x7f140478,
    )

    private val testKeys = listOf(UPGRADES_TEST_KEY, ALSO_FROM_META_TEST_KEY)

    private fun Method.instructionList() = implementation?.instructions?.toList().orEmpty()

    private fun loadsLiteral(method: Method, value: Int): Boolean = method.instructionList().any {
        it.opcode in setOf(Opcode.CONST, Opcode.CONST_HIGH16) && (it as NarrowLiteralInstruction).narrowLiteral == value
    }

    private fun calledMethods(method: Method): List<MethodReference> = method.instructionList()
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }

    private fun created(method: Method): Set<String> = method.instructionList()
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .toSet()

    /**
     * The classes [method] builds: what it creates, and what the calls it makes hand back. Redex
     * moves some constructions into static factories of their own, as 577 does for both sections.
     */
    private fun built(method: Method): Set<String> = created(method) + method.instructionList()
        .filter { it.opcode.name.startsWith("invoke-") }
        .map { ((it as ReferenceInstruction).reference as MethodReference).returnType }

    /** The classes of the static calls [method] makes that answer an object, as a list factory does. */
    private fun objectStatics(method: Method): List<String> = method.instructionList()
        .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .map { (it as ReferenceInstruction).reference as MethodReference }
        .filter { it.returnType.startsWith("L") }
        .map { it.definingClass }

    private fun calls(instruction: Any, method: Method): Boolean =
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            it.definingClass == method.definingClass && it.name == method.name &&
                it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
        } == true

    private fun staticReads(method: Method): Set<String> = method.instructionList()
        .filter { it.opcode == Opcode.SGET_OBJECT }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .map { "${it.definingClass}->${it.name}" }
        .toSet()

    /** Every type [method] could hand the group in: what its calls answer and its fields hold. */
    private fun candidateTypes(method: Method): Set<String> = method.instructionList().mapNotNull {
        when (val reference = (it as? ReferenceInstruction)?.reference) {
            is MethodReference -> reference.returnType
            is FieldReference -> reference.type
            else -> null
        }
    }.filter { it.startsWith("L") }.toSet()

    /**
     * The static field each constant of [enum] is stored in, by the constant's name: the name
     * handed to the constructor of the instance its class initializer stores there.
     */
    private fun constantFields(enum: ClassDef): Map<String, String> {
        val initializer = enum.methods.single { it.name == "<clinit>" }
        // What each register holds as the initializer runs: a string, or the instance made at an index.
        val strings = mutableMapOf<Int, String>()
        val instances = mutableMapOf<Int, Int>()
        val names = mutableMapOf<Int, String>()
        val fields = mutableMapOf<String, String>()
        fun constructed(instance: Int?, name: String?) {
            if (instance != null && name != null) names[instance] = name
        }
        initializer.instructionList().forEachIndexed { index, instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference
            when (instruction.opcode) {
                Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> {
                    val register = (instruction as OneRegisterInstruction).registerA
                    strings[register] = (reference as StringReference).string
                    instances.remove(register)
                }
                Opcode.NEW_INSTANCE -> {
                    val register = (instruction as OneRegisterInstruction).registerA
                    instances[register] = index
                    strings.remove(register)
                }
                Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                    val copy = instruction as TwoRegisterInstruction
                    strings.remove(copy.registerA)
                    instances.remove(copy.registerA)
                    strings[copy.registerB]?.let { strings[copy.registerA] = it }
                    instances[copy.registerB]?.let { instances[copy.registerA] = it }
                }
                Opcode.INVOKE_DIRECT -> {
                    val invoke = instruction as FiveRegisterInstruction
                    val call = reference as MethodReference
                    if (call.name == "<init>" && call.definingClass == enum.type && invoke.registerCount >= 2) {
                        constructed(instances[invoke.registerC], strings[invoke.registerD])
                    }
                }
                Opcode.INVOKE_DIRECT_RANGE -> {
                    val invoke = instruction as RegisterRangeInstruction
                    val call = reference as MethodReference
                    if (call.name == "<init>" && call.definingClass == enum.type && invoke.registerCount >= 2) {
                        constructed(instances[invoke.startRegister], strings[invoke.startRegister + 1])
                    }
                }
                Opcode.SPUT_OBJECT -> {
                    val field = reference as FieldReference
                    val name = instances[(instruction as OneRegisterInstruction).registerA]?.let(names::get)
                    if (field.type == enum.type && name != null) fields[name] = "${field.definingClass}->${field.name}"
                }
                else -> Unit
            }
        }
        return fields
    }

    @Test
    fun `each declared build has one native and one server Menu group section, typed by the group enum`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            val label = headerLabel[version]
                ?: throw AssertionError("no header label id for $version: read it from that build's default.frsc.xz")
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val natives = mutableListOf<Method>()
                val servers = mutableListOf<Method>()
                val keyHolders = mutableMapOf<String, MutableList<Method>>()
                var nativeKeyHolders = 0
                FixtureDex.forEach(bundle) { dex ->
                    val strings = dex.stringSection.toSet()
                    if (listOf(NATIVE_SECTION_KEY, SERVER_POSITIONS.first()).plus(testKeys).none { it in strings }) {
                        return@forEach
                    }
                    for (classDef in dex.classes) {
                        for (method in classDef.methods) {
                            if (holdsString(method, NATIVE_SECTION_KEY)) nativeKeyHolders++
                            if (isNativeSectionChildren(method)) natives += ImmutableMethod.of(method)
                            if (isServerSectionChildren(method)) servers += ImmutableMethod.of(method)
                            for (key in testKeys) {
                                if (holdsString(method, key)) keyHolders.getOrPut(key, ::mutableListOf) += ImmutableMethod.of(method)
                            }
                        }
                    }
                }
                assertEquals("${bundle.name}: methods loading \"$NATIVE_SECTION_KEY\"", 1, nativeKeyHolders)
                assertEquals("${bundle.name}: native Menu group sections", 1, natives.size)
                assertEquals("${bundle.name}: server Menu group sections", 1, servers.size)
                val native = natives.single()
                val server = servers.single()

                val classes = FixtureDex.classes(bundle,
                    candidateTypes(native) + candidateTypes(server) + created(native))
                val isGroupEnum = { type: String -> classes[type]?.let { isEnumNaming(it, SECTION_NAMES) } == true }

                // Each section reads the group once, and both read the same enum.
                val nativeRead = nativeGroupRead(native, isGroupEnum)
                val serverRead = serverGroupRead(server, isGroupEnum)
                assertNotNull("${bundle.name}: the native section's one group call", nativeRead)
                assertNotNull("${bundle.name}: the server section's one group field", serverRead)
                assertEquals("${bundle.name}: the two sections' group enums", nativeRead!!.type, serverRead!!.type)
                val enum = classes.getValue(nativeRead.type)

                // Both hand their children back through the same list, which the hooks build empty.
                // 582 makes it through a static factory, and 586's Android 9 build through one taking two
                // arguments, so the classes of the sections' statics answering an object come along.
                val factories = FixtureDex.classes(bundle, (objectStatics(native) + objectStatics(server)).toSet())
                val factory: (MethodReference) -> Method? = { call -> factories[call.definingClass]?.let { resolveStatic(it, call) } }
                val nativeList = childrenList(native, factory)
                assertNotNull("${bundle.name}: the native section's children list", nativeList)
                assertEquals("${bundle.name}: the two sections' children lists", nativeList, childrenList(server, factory))

                // 582's native section (LX/cTm;) hands its build to a singleton's method (LX/cTw;->A01),
                // so the section the root builds is the one class calling it.
                val nativeSection = if (native.parameterTypes.size == 1) native.definingClass else {
                    val callers = FixtureDex.methodsWhere(bundle, { dex ->
                        dex.methodSection.any { it.definingClass == native.definingClass && it.name == native.name }
                    }) { method -> method.instructionList().any { calls(it, native) } }.map { it.definingClass }.toSet()
                    assertEquals("${bundle.name}: classes handing their build to ${native.definingClass}", 1, callers.size)
                    callers.single()
                }

                // A local no higher than v15 is free where each hook goes.
                for ((method, read) in listOf(native to nativeRead, server to serverRead)) {
                    val free = method.freeLocalsAt(PATCH, read.index + 1, 1)
                    assertTrue("${bundle.name}: v${free.single()} in ${method.name}", free.single() <= 15)
                }

                // The server section adds the bookmarks folder Facebook draws inside it, Your shortcuts
                // in the PROFILE group's, once after checking it's there, with a local free for the
                // folder hook both there and where the check lands (#445).
                val folderAdd = nativeFolderAdd(server, serverRead)
                assertNotNull("${bundle.name}: the server section's one native folder add", folderAdd)
                server.freeLocalsAt(PATCH, folderAdd!!.check, 1, targets = listOf(folderAdd.skip), highest = 255)

                // The native section reads whether its group is folded once, from the state it casts,
                // before the group, and two such locals are free for the fold hook (issue #224).
                assertNotNull("${bundle.name}: the native section's one fold read before its group", foldRead(native, nativeRead))
                // And it hands its header whether the group starts folded from one flag of its own.
                assertNotNull("${bundle.name}: the native section's header seed", startRead(native, nativeRead))
                val borrowed = native.freeLocalsAt(PATCH, nativeRead.index + 1, 2)
                assertTrue("${bundle.name}: v${borrowed.joinToString(", v")} in ${native.name}", borrowed.all { it <= 15 })

                // The header keeps its own collapsed state after the seed and flips it on a tap, so the
                // section marks the header it makes, with a local free right after the seed, and the
                // header's render and tap handler each read that state once where the mark can go in
                // beside it (#224 follow-up).
                val seed = headerSeed(native, nativeRead, startRead(native, nativeRead)!!)
                assertNotNull("${bundle.name}: the native section's store of its header seed", seed)
                val flag = native.freeLocalsAt(PATCH, seed!!.index + 1, 1).single()
                assertTrue("${bundle.name}: v$flag after the header seed", flag <= 15)
                val headerClass = FixtureDex.classes(bundle, setOf(seed.headerClass))[seed.headerClass]
                assertNotNull("${bundle.name}: the seeded header ${seed.headerClass}", headerClass)
                val stateReads = headerStateReads(headerClass!!)
                assertNotNull("${bundle.name}: ${seed.headerClass}'s reads of its collapsed state", stateReads)
                assertEquals("${bundle.name}: tap handlers reading the header's state", 1, stateReads!!.count { it.click })
                assertTrue("${bundle.name}: renders reading the header's state", stateReads.any { !it.click })

                // The native section draws the group's collapsible header, the one TalkBack reads
                // as "Upgrades, header. Section is expanded." on the phone.
                // 586's header asks a method of another class for those words, so the label may
                // load one call away.
                val components = created(native).mapNotNull(classes::get)
                val callees = FixtureDex.classes(bundle,
                    components.flatMap { component -> component.methods.flatMap(::calledMethods) }.map { it.definingClass }.toSet())
                val loadsLabel = { call: MethodReference ->
                    callees[call.definingClass]?.methods?.any {
                        it.name == call.name && it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString) &&
                            loadsLiteral(it, label)
                    } == true
                }
                val headers = components.filter { component ->
                    component.methods.any { method -> loadsLiteral(method, label) || calledMethods(method).any(loadsLabel) }
                }
                assertEquals("${bundle.name}: header components the native section creates", 1, headers.size)
                assertEquals("${bundle.name}: the header the seed goes to", headers.single().type, seed.headerClass)

                // Where the root adds the Upgrades and Also from Meta groups, it tags each with its
                // test key, reads the group's constant, and builds both hooked sections.
                val constants = constantFields(enum)
                for ((key, group) in listOf(UPGRADES_TEST_KEY to UPGRADES, ALSO_FROM_META_TEST_KEY to ALSO_FROM_META)) {
                    val holders = keyHolders[key].orEmpty()
                    assertEquals("${bundle.name}: methods loading \"$key\"", 1, holders.size)
                    val adder = holders.single()
                    val constant = constants[group] ?: throw AssertionError("${bundle.name}: ${enum.type} has no $group")
                    assertTrue("${bundle.name}: \"$key\"'s method doesn't read $group", constant in staticReads(adder))
                    assertTrue("${bundle.name}: \"$key\"'s method doesn't build both sections",
                        built(adder).containsAll(setOf(nativeSection, server.definingClass)))
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
