/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Always use Clean mode on every Facebook build the bundle declares: the one Clean mode event, the
 * store whose flag it sets beside the event, and one read of that flag in each of the three viewer
 * parts, kept by a move-result as a boolean or, through Facebook's boxing helper, a Boolean. Then
 * the patch on those classes: the extension right after each move-result on the same register,
 * and nothing else in the method moved. And the Clean mode button (#304): the sticky check in the
 * store's setter and in the button's tap, the overlay's listener boxing its new value, and the
 * button builder's last read of the overlay's value before it makes the button that only its
 * "no button" check uses (586 builds the button in the overlay's own render and reads the value
 * once more, for the button's state).
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelCleanModeFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build reads the remembered Clean mode flag once in each viewer part, and the hook keeps the register`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val literals = listOf(CLEAR_MODE_EVENT) + CLEAN_MODE_PARTS
                val holding = literals.associateWith { literal ->
                    FixtureDex.classesHolding(bundle, literal).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                }
                // Every class owning an AtomicBoolean a part's method reads could be the store.
                val owners = CLEAN_MODE_PARTS.flatMap { part ->
                    holding.getValue(part).flatMap { methodsHolding(it, part) }.flatMap { method ->
                        method.code().mapNotNull { instruction ->
                            ((instruction as? ReferenceInstruction)?.reference as? FieldReference)
                                ?.takeIf { instruction.opcode == Opcode.IGET_OBJECT && it.type == ATOMIC_BOOLEAN }?.definingClass
                        }
                    }
                }.toSet()
                val candidates = FixtureDex.classes(bundle, owners)
                val hooks = cleanModeHooks(holders = holding::getValue, classOf = candidates::get)
                assertEquals("$name: one read per part", CLEAN_MODE_PARTS.size, hooks.size)

                val event = clearModeEvent(holding.getValue(CLEAR_MODE_EVENT))!!
                val stores = candidates.values.filter { store ->
                    store.fields.any { field -> isCleanModeFlag(store, field, event) }
                }
                assertEquals("$name: the Clean mode store", 1, stores.size)

                // A boxed read goes through a static helper that only boxes the flag's get().
                val helpers = hooks.filter { it.third.boxed }.map { (_, method, read) ->
                    method.code()[read.moveResult - 1].called()!!
                }
                val helperClasses = FixtureDex.classes(bundle, helpers.map { it.definingClass }.toSet())
                for (helper in helpers) {
                    val body = helperClasses.getValue(helper.definingClass).methods.single {
                        it.name == helper.name && it.parameterTypes.map(CharSequence::toString) == listOf(ATOMIC_BOOLEAN)
                    }
                    assertTrue("$name: $helper isn't static", AccessFlags.STATIC.isSet(body.accessFlags))
                    assertEquals("$name: $helper does more than box the flag",
                        listOf("$ATOMIC_BOOLEAN->get()Z", "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;"),
                        body.code().mapNotNull { it.called()?.toString() })
                }

                val pool: Collection<ClassDef> = (holding.values.flatten() + candidates.values).associateBy { it.type }.values
                val context = PatchContexts.of(pool)
                with(context) { startReelsInCleanMode() }
                for ((owner, method, read) in hooks) {
                    val where = "$name: ${owner.type}->${method.name}"
                    val original = method.code()
                    val patched = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(method) }.code()
                    val at = read.moveResult + 1
                    assertEquals("$where gains two instructions", original.size + 2, patched.size)
                    assertEquals("$where: Facebook's code up to the read stays", original.take(at).map { it.opcode },
                        patched.take(at).map { it.opcode })
                    val call = patched[at]
                    assertEquals("$where: the extension is asked", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                    assertEquals("$where: the extension is asked", if (read.boxed) START_CLEAN_BOXED else START_CLEAN,
                        call.called().toString())
                    assertEquals("$where: with the flag's answer", read.register, (call as RegisterRangeInstruction).startRegister)
                    assertEquals("$where: with the flag's answer", 1, call.registerCount)
                    assertEquals("$where: its answer goes back in the same register",
                        if (read.boxed) Opcode.MOVE_RESULT_OBJECT else Opcode.MOVE_RESULT, patched[at + 1].opcode)
                    assertEquals("$where: its answer goes back in the same register", read.register,
                        (patched[at + 1] as OneRegisterInstruction).registerA)
                    assertEquals("$where: Facebook's code after the hook stays", original.drop(at).map { it.opcode },
                        patched.drop(at + 2).map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    /** The index of the instruction [jump] at [at] lands on. */
    private fun List<Instruction>.target(at: Int, jump: OffsetInstruction): Int {
        val starts = runningFold(0) { offset, instruction -> offset + instruction.codeUnits }
        return starts.indexOf(starts[at] + jump.codeOffset)
    }

    @Test
    fun `each declared build's Clean mode button takes the sticky way and stays for a reel taken out`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val overlayPart = CLEAN_MODE_PARTS.first()
                val literals = listOf(CLEAR_MODE_EVENT, CLEAR_MODE_TAP, OVERLAY_CLEAR_MODE_UPDATE, overlayPart) + FLOAT_LITERALS
                val holding = literals.associateWith { literal ->
                    FixtureDex.classesHolding(bundle, literal).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                }
                val candidates = buttonClasses(bundle, holding)
                val hooks = cleanModeButtonHooks(holders = holding::getValue, classOf = candidates::get)
                assertEquals("$name: the setter, the tap, the listener and the builder",
                    listOf(STICKY_CLEAN, STICKY_CLEAN, MARK_LEFT_CLEAN, CLEAN_BUTTON_STATE), hooks.map { it.call })
                val (setter, tap, listener, builder) = hooks

                // Both sticky hooks follow the same no-argument check, and only the setter makes the event.
                val gates = listOf(setter, tap).map { it.method.code()[it.moveResult - 1].called()!! }
                assertEquals("$name: the tap asks the setter's check", gates[0].toString(), gates[1].toString())
                assertTrue("$name: the check takes arguments", gates[0].parameterTypes.isEmpty() && gates[0].returnType == "Z")
                val event = clearModeEvent(holding.getValue(CLEAR_MODE_EVENT))!!
                assertTrue("$name: the setter doesn't make the Clean mode event", setter.method.code().any {
                    it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == event
                })

                // The listener's boxing answers an Object[] the state update carries.
                assertEquals("$name: the listener's boxing", Opcode.MOVE_RESULT_OBJECT, listener.method.code()[listener.moveResult].opcode)

                // The builder's check of the reference's value is followed by a jump on its answer past the
                // button: to returning nothing (585), or, in the Android 9 build's inlined builder, to right
                // after the button's row is made, with something else standing in the row's register.
                val code = builder.method.code()
                val answer = (code[builder.moveResult + 2] as OneRegisterInstruction).registerA
                val jumpAt = (builder.moveResult + 3 until minOf(code.size, builder.moveResult + 6)).firstOrNull {
                    code[it].opcode == Opcode.IF_NEZ && (code[it] as OneRegisterInstruction).registerA == answer
                }
                assertTrue("$name: the builder doesn't jump on its check", jumpAt != null)
                val madeAt = code.indexOfFirst {
                    it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == builder.made
                }
                assertTrue("$name: the builder makes the button before its check", madeAt > jumpAt!!)
                val landingAt = code.target(jumpAt, code[jumpAt] as OffsetInstruction)
                val landing = code[landingAt]
                if (landing.opcode == Opcode.RETURN_OBJECT) {
                    val returned = (landing as OneRegisterInstruction).registerA
                    assertTrue("$name: what the builder returns there isn't null", code.take(jumpAt).last {
                        it is OneRegisterInstruction && it.registerA == returned && it.opcode != Opcode.IF_NEZ
                    }.let { it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 0 })
                } else {
                    assertTrue("$name: the builder's check leads to ${landing.opcode}", landing.opcode in OBJECT_MOVES)
                    val goto = code[landingAt + 1]
                    assertTrue("$name: the builder's no doesn't go on", goto.opcode in GOTOS)
                    val next = code.target(landingAt + 1, goto as OffsetInstruction)
                    val rowMade = code[next - 1]
                    assertTrue("$name: the builder's no doesn't land right after a row is made", next > madeAt &&
                        rowMade.opcode in DIRECT_CALLS && rowMade.called()?.name == "<init>")
                    assertTrue("$name: what stands in for the row isn't used where the no lands",
                        (landing as TwoRegisterInstruction).registerA in code[next].named())
                }

                // The patch also lays a kept button over the details (#329), with Litho's helpers.
                val float = floatFor(bundle, builder, holding, candidates)
                assertTrue("$name: the builder's own hook comes before the float", builder.moveResult + 1 < float.at)
                val helpers = FixtureDex.classes(bundle, setOf(float.positionType, float.position).map { it.substringBefore("->") }.toSet())

                // #391: where the sidebar's button switches the store, read before the patch.
                val switch = cleanModeStoreSwitch(holders = holding::getValue, classOf = candidates::get)
                assertEquals("$name: the store is the setter's own class", setter.owner.type, switch.store.type)
                assertEquals("$name: the switch copies the setter", setter.method.name, switch.setter.name)
                assertEquals("$name: the tap's source is a static of the setter's first type",
                    switch.setter.parameterTypes.first().toString(), switch.source.type)
                val setterCode = setter.method.code()
                assertEquals("$name: the work starts at the flag's read", Opcode.IGET_OBJECT, setterCode[switch.workAt].opcode)
                assertTrue("$name: the work comes after the setter's checks", switch.workAt > setter.moveResult)

                val pool: Collection<ClassDef> = (holding.values.flatten() + candidates.values + helpers.values).associateBy { it.type }.values +
                    extensionStub()
                val context = PatchContexts.of(pool)
                with(context) { toggleReelCleanMode() }

                val store = context.mutableClassDefBy(switch.store.type)
                val init = store.methods.single { it.name == "<init>" }.code()
                assertEquals("$name: the store keeps itself as it's made", KEEP_STORE, init[init.size - 2].called().toString())
                assertEquals("$name: and still returns", Opcode.RETURN_VOID, init.last().opcode)
                val copy = store.methods.single { it.name == STORE_SWITCH }
                assertTrue("$name: the copy is public", AccessFlags.PUBLIC.isSet(copy.accessFlags))
                assertTrue("$name: the copy isn't static", !AccessFlags.STATIC.isSet(copy.accessFlags))
                val copyCode = copy.code()
                val sourceRegister = copy.implementation!!.registerCount - 3
                assertEquals("$name: the copy reads the tap's source into the source's place", Opcode.SGET_OBJECT, copyCode[0].opcode)
                assertEquals("$name: the copy reads the tap's source", switch.source.toString(),
                    ((copyCode[0] as ReferenceInstruction).reference as FieldReference).toString())
                assertEquals("$name: into the source's place", sourceRegister, (copyCode[0] as OneRegisterInstruction).registerA)
                assertEquals("$name: then jumps to the work", switch.workAt + 2, copyCode.target(1, copyCode[1] as OffsetInstruction))
                assertEquals("$name: the copy's work is the setter's own", setterCode.drop(switch.workAt).map { it.opcode },
                    copyCode.drop(switch.workAt + 2).map { it.opcode })
                val stub = context.mutableClassDefBy(CLEAN_MODE_EXTENSION).methods.single { it.name == "switchStore" }.code()
                assertEquals("$name: the stub casts the store and calls the copy",
                    listOf(Opcode.CHECK_CAST, Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID),
                    stub.take(4).map { it.opcode })
                assertEquals("$name: the stub calls the copy",
                    "${switch.store.type}->$STORE_SWITCH(${switch.setter.parameterTypes.joinToString("")})V",
                    stub[2].called().toString())
                for (hook in hooks) {
                    val where = "$name: ${hook.owner.type}->${hook.method.name}"
                    val original = hook.method.code()
                    val patched = context.mutableClassDefBy(hook.owner.type).methods.single { it.sameAs(hook.method) }.code()
                    val at = hook.moveResult + 1
                    val block = if (hook === builder) floatSmaliSize(float) else 0
                    assertEquals("$where gains two instructions, and the builder its float", original.size + 2 + block, patched.size)
                    val call = patched[at]
                    assertEquals("$where: the extension is asked", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                    assertEquals("$where: the extension is asked", hook.call, call.called().toString())
                    assertEquals("$where: with Facebook's value", hook.register, (call as RegisterRangeInstruction).startRegister)
                    assertEquals("$where: with Facebook's value", 1, call.registerCount)
                    assertEquals("$where: its answer goes back in the same register", original[hook.moveResult].opcode, patched[at + 1].opcode)
                    assertEquals("$where: its answer goes back in the same register", hook.register,
                        (patched[at + 1] as OneRegisterInstruction).registerA)
                    val unhooked = patched.take(at) + patched.drop(at + 2)
                    if (block > 0) {
                        assertEquals("$where: the float is asked where the row has its style", FLOAT_CLEAN_BUTTON,
                            unhooked[float.at].called().toString())
                    }
                    val facebooks = if (block > 0) unhooked.take(float.at) + unhooked.drop(float.at + block) else unhooked
                    assertEquals("$where: Facebook's code stays", original.map { it.opcode }, facebooks.map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    private val CLEAN_MODE_EXTENSION = "Lapp/morphe/extension/facebook/reels/ReelCleanMode;"

    /** The extension's empty switchStore stub, as the patch finds it in a built extension. */
    private fun extensionStub(): ClassDef = ImmutableClassDef(
        CLEAN_MODE_EXTENSION, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                CLEAN_MODE_EXTENSION, "switchStore",
                listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Z").map { ImmutableMethodParameter(it, null, null) },
                "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(3, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
            ),
        ),
    )

    /** Whether [method] names [type] in its parameters or in a field its code reads. */
    private fun names(method: Method, type: String) = method.parameterTypes.any { it.toString() == type } ||
        method.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass == type } == true

    /** The literals the float's search starts from: Yoga's absolute position type and an edge name. */
    private val FLOAT_LITERALS = listOf("ABSOLUTE", POSITION_BOTTOM)

    /** The classes the button's search looks into: what the tap calls, and what the overlay calls with an AtomicReference. */
    private fun buttonClasses(bundle: File, holding: Map<String, List<ClassDef>>): MutableMap<String, ClassDef> {
        val tapCalls = holding.getValue(CLEAR_MODE_TAP).flatMap { methodsHolding(it, CLEAR_MODE_TAP) }
            .flatMap { method -> method.code().mapNotNull { it.called()?.definingClass } }
        val overlayCalls = holding.getValue(CLEAN_MODE_PARTS.first()).flatMap { it.methods }.flatMap { method ->
            method.code().mapNotNull { instruction ->
                instruction.called()?.takeIf { ATOMIC_REFERENCE in it.parameterTypes.map(CharSequence::toString) }?.definingClass
            }
        }
        return FixtureDex.classes(bundle, (tapCalls + overlayCalls).toSet()).toMutableMap()
    }

    private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
    private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
    private val DIRECT_CALLS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

    /** The registers a call names. */
    private fun Instruction.named(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    /** The float for [builder], looking up the dimension class into [candidates] as the patch would. */
    private fun floatFor(bundle: File, builder: ButtonHook, holding: Map<String, List<ClassDef>>, candidates: MutableMap<String, ClassDef>): FloatHook {
        val classOf = { type: String ->
            candidates[type] ?: FixtureDex.classes(bundle, setOf(type))[type]?.also { candidates[type] = it }
        }
        val staticsNaming = { type: String ->
            FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == type } }) { isStaticMethod(it) && names(it, type) }
        }
        return cleanButtonFloat(builder, holding::getValue, classOf, staticsNaming)
    }

    private fun floatSmaliSize(hook: FloatHook) = floatSmali(hook).lines().count { it.isNotBlank() }

    /** Litho's Dimension keeps px and sp in NaN payloads under these bits, and dp as a plain double. */
    private val DIMENSION_NAN_BITS = 0x7ff8000000000000L

    @Test
    fun `each declared build's kept Clean mode button is laid over the details, not under them`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val literals = listOf(CLEAR_MODE_EVENT, CLEAR_MODE_TAP, OVERLAY_CLEAR_MODE_UPDATE, CLEAN_MODE_PARTS.first()) + FLOAT_LITERALS
                val holding = literals.associateWith { literal ->
                    FixtureDex.classesHolding(bundle, literal).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                }
                val candidates = buttonClasses(bundle, holding)
                val builder = cleanModeButtonHooks(holders = holding::getValue, classOf = candidates::get).last()
                assertEquals("$name: the last hook is the builder's", CLEAN_BUTTON_STATE, builder.call)
                val float = floatFor(bundle, builder, holding, candidates)

                // The row's style goes from the margin helper to the row's constructor untouched.
                val original = builder.method.code()
                assertEquals("$name: the block doesn't follow the style's move-result", Opcode.MOVE_RESULT_OBJECT, original[float.at - 1].opcode)
                assertEquals("$name: the style register", float.style, (original[float.at - 1] as OneRegisterInstruction).registerA)
                // Nothing reads the registers the block borrows before writing them again.
                val live = RegisterLiveness.of(builder.method).liveInto(float.at)
                assertTrue("$name: the block borrows live registers", (float.range..float.range + EDGES).none { it in live })
                assertTrue("$name: the block's registers run past v255", float.range + EDGES <= 255)
                assertTrue("$name: the end and bottom insets", float.end in 1..EDGES && float.bottom in 1..EDGES && float.end != float.bottom)
                assertTrue("$name: the position type is Yoga's absolute one", float.absolute.contains("->") && float.absolute.endsWith(";"))

                val context = PatchContexts.of((holding.values.flatten() + candidates.values).associateBy { it.type }.values)
                val mutable = context.mutableClassDefBy(builder.owner.type).methods.single { it.sameAs(builder.method) }
                mutable.floatKeptButton(float)
                val patched = mutable.code()
                val added = patched.size - original.size
                val where = "$name: ${builder.owner.type}->${builder.method.name}"
                assertEquals("$where: Facebook's code up to the block stays", original.take(float.at).map { it.opcode },
                    patched.take(float.at).map { it.opcode })
                assertEquals("$where: Facebook's code after the block stays", original.drop(float.at).map { it.opcode },
                    patched.drop(float.at + added).map { it.opcode })
                val block = patched.subList(float.at, float.at + added)
                assertEquals("$where: the extension is asked first", FLOAT_CLEAN_BUTTON, block[0].called().toString())
                val jumpAt = float.at + 2
                assertEquals("$where: a no jumps", Opcode.IF_EQZ, patched[jumpAt].opcode)
                assertEquals("$where: a no lands on Facebook's next instruction", float.at + added,
                    patched.target(jumpAt, patched[jumpAt] as OffsetInstruction))
                val calls = block.mapNotNull { it.called()?.toString() }
                assertEquals("$where: the block's calls", listOf(FLOAT_CLEAN_BUTTON, float.positionType,
                    "${float.dimension}-><init>(J)V", "${float.dimension}-><init>(J)V", float.position), calls)
                // The end inset is 0 and the bottom one the lift, in dp, which the dimension holds as a double's bits.
                val wides = block.filter { it.opcode == Opcode.CONST_WIDE_16 || it.opcode == Opcode.CONST_WIDE }
                    .map { (it as WideLiteralInstruction).wideLiteral }
                assertEquals("$where: the insets' values", listOf(0L, CLEAN_BUTTON_LIFT_DP.toRawBits()), wides)
                val dimension = FixtureDex.classes(bundle, setOf(float.dimension)).getValue(float.dimension)
                assertTrue("$name: ${float.dimension} still tells dp from px and sp by the NaN bits", dimension.methods.any { method ->
                    val code = method.code()
                    code.any { (it as? WideLiteralInstruction)?.wideLiteral == DIMENSION_NAN_BITS } &&
                        code.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == " dp" }
                })
                val last = block.last()
                assertEquals("$where: the style goes back to the row", Opcode.MOVE_RESULT_OBJECT, last.opcode)
                assertEquals("$where: the style goes back to the row", float.style, (last as OneRegisterInstruction).registerA)
                val insets = block.single { it.called()?.toString() == float.position } as RegisterRangeInstruction
                assertEquals("$where: the position helper gets the style and nine dimensions", float.range, insets.startRegister)
                assertEquals("$where: the position helper gets the style and nine dimensions", EDGES + 1, insets.registerCount)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
