/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.reelspeed

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.media.resume.TRACK_START
import app.morphe.patches.facebook.media.resume.VIDEO_PLAYER_PARAMS
import app.morphe.patches.facebook.media.resume.paramsGetters
import app.morphe.patches.facebook.media.resume.reportedValues
import app.morphe.patches.facebook.media.resume.trackers
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.reels.hold.hookSpeedSetter
import app.morphe.patches.facebook.reels.hold.SPEED_SET as GUARD_SPEED_SET
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep the reel speed's anchors on every Facebook build the bundle declares: FbGrootPlayer's speed
 * setter, PlayerOrigin getter, maybeTrackVideoStart and VideoPlayerParams getter, each there once;
 * the params' one debug dump reporting isFbShorts, isSponsored and isLiveNow, each a public boolean;
 * and the Reels menu's speed toast, the only method
 * holding its selector's name, which only FbShortsInlinePlaybackSpeedUtil's two pickers call. The
 * gear menu's speed sheet sets its pick with the same setter. The Reels menu's two speed pickers each
 * list their Float[] of speeds in one place, where the slower speeds go in, and the gear menu's speed
 * sheet builder reads a speed for each label in one place, where they go in too. Then the patch itself, run on those
 * classes: each hook first in its method, reading the method's own arguments, and each stub calling
 * the method or reading the field it stands for. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class KeepReelSpeedFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun key(method: MethodReference) =
        method.definingClass + "->" + method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build has every anchor once, and the patch goes in on its own classes`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, GROOT_PLAY).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val plays = holders.flatMap(::grootPlays)
                assertEquals("$name: players' plays holding \"$GROOT_PLAY\"", 1, plays.size)
                val play = plays.single()
                val owner = holders.single { it.type == play.definingClass }
                val trigger = play.parameterTypes.single().toString()
                assertTrue("$name: ${owner.type} isn't public", AccessFlags.PUBLIC.isSet(owner.accessFlags))

                val setters = speedSetters(owner)
                assertEquals("$name: speed setters reading the speed cache switch", 1, setters.size)
                val origins = originGetters(owner)
                assertEquals("$name: PlayerOrigin getters", 1, origins.size)
                val starts = trackers(owner, TRACK_START, trigger)
                assertEquals("$name: $TRACK_START", 1, starts.size)
                val paramsGetter = paramsGetters(owner)
                assertEquals("$name: VideoPlayerParams getters", 1, paramsGetter.size)
                val setter = setters.single()
                val origin = origins.single()
                for (method in listOf(setter, origin, paramsGetter.single())) {
                    assertTrue("$name: ${method.name} isn't public", AccessFlags.PUBLIC.isSet(method.accessFlags))
                }

                // The params' debug dump names the three booleans the rule reads.
                val paramsClass = FixtureDex.classes(bundle, setOf(VIDEO_PLAYER_PARAMS)).values.single()
                assertTrue("$name: $VIDEO_PLAYER_PARAMS isn't public", AccessFlags.PUBLIC.isSet(paramsClass.accessFlags))
                val dumps = paramsClass.methods.filter { m -> REEL_PARAM_STUBS.keys.all { holdsString(m, it) } }
                assertEquals("$name: methods reporting ${REEL_PARAM_STUBS.keys}", 1, dumps.size)
                val reported = reportedValues(dumps.single())
                val flagFields = REEL_PARAM_STUBS.entries.associate { (reportedName, stub) ->
                    val field = reported[reportedName]
                    assertTrue("$name: the dump doesn't report $reportedName", field != null)
                    val declared = paramsClass.fields.singleOrNull { it.name == field!!.name && it.type == "Z" }
                    assertTrue("$name: $reportedName, ${field!!.name}, isn't a public boolean",
                        declared != null && AccessFlags.PUBLIC.isSet(declared.accessFlags) && !AccessFlags.STATIC.isSet(declared.accessFlags))
                    stub to field.name
                }
                assertEquals("$name: the fields reported as ${REEL_PARAM_STUBS.keys}", 3, flagFields.values.toSet().size)
                // The same dump names the video's id, which the post menu's speed row matches its player by (#429).
                val videoId = reported[VIDEO_ID_REPORTED]
                assertTrue("$name: the dump doesn't report $VIDEO_ID_REPORTED", videoId != null)
                assertTrue("$name: $VIDEO_ID_REPORTED, ${videoId!!.name}, isn't a public String",
                    paramsClass.fields.any {
                        it.name == videoId.name && it.type == "Ljava/lang/String;" &&
                            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
                    })

                // The only method of the build holding the selector's name, and the Reels pickers' runnables
                // are all that call it.
                val toastHolders = FixtureDex.classesHolding(bundle, SPEED_TOAST)
                val holding = toastHolders.flatMap { it.methods }.filter { holdsString(it, SPEED_TOAST) }
                assertEquals("$name: methods holding \"$SPEED_TOAST\"", 1, holding.size)
                val toast = holding.single()
                val toastClass = toastHolders.single { it.type == toast.definingClass }
                // 585 made the toast a Runnable's run() keeping the speed in a float field, built by one items lambda.
                val toastRuns = !isSpeedToast(toast)
                assertTrue("$name: ${toast.definingClass}->${toast.name} isn't the speed toast",
                    toastRuns && isSpeedToastRun(toastClass, toast) || !toastRuns)
                val toastKey = if (toastRuns) toast.definingClass + "-><init>" else toast.definingClass + "->" + toast.name
                val callers = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.methodSection.any { it.definingClass == toast.definingClass && (toastRuns || it.name == toast.name) }
                }) { method -> method.implementation?.instructions?.any { it.call?.let { c -> c.definingClass + "->" + c.name } == toastKey } == true }
                val callerClasses = FixtureDex.classes(bundle, callers.map { it.definingClass }.toSet()).values
                assertEquals("$name: the toast's callers", if (toastRuns) 1 else 2, callers.size)
                // 585's items lambda has no original name, but the Runnable it builds keeps the util's.
                val namedBy = if (toastRuns) listOf(toastClass) else callerClasses
                assertTrue("$name: a caller of the toast isn't one of FbShortsInlinePlaybackSpeedUtil's pickers: " +
                    namedBy.map { it.type + " " + redexOriginalName(it) },
                    namedBy.all { redexOriginalName(it)?.startsWith("FbShortsInlinePlaybackSpeedUtil\$") == true })

                // The gear menu's speed sheet sets a pick through the same setter, and shows no toast.
                val gearHolders = FixtureDex.classesHolding(bundle, GEAR_PICK)
                val gear = gearHolders.flatMap { it.methods }.filter { holdsString(it, GEAR_PICK) }
                assertTrue("$name: the gear menu's speed pick doesn't set the speed with ${setter.name}", gear.any { method ->
                    method.code().any { it.call?.let(::key) == "${owner.type}->${setter.name}(F)V" }
                })
                val gearPick = gear.single { setterCalls(it, owner.type, setter).isNotEmpty() }
                val gearCalls = setterCalls(gearPick, owner.type, setter)
                val gearClass = gearHolders.single { it.type == gearPick.definingClass }

                // The Reels menu's speed lists: each picker fills a Float[] and lists it in one place (#95). Up to
                // 584 the selector sits in the toast's class and the dropdown fills its own; 585 has the dropdown
                // call the selector, which sits in a class of its own.
                val selectorHolders = FixtureDex.methodsWhere(bundle, { true }) {
                    AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == ATTRIBUTE_SELECTOR
                }
                val selectorClasses = FixtureDex.classes(bundle, selectorHolders.map { it.definingClass }.toSet()).values
                    .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val selectors = selectorClasses.flatMap { it.methods }.filter {
                    AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == ATTRIBUTE_SELECTOR && speedLists(it).isNotEmpty()
                }
                assertEquals("$name: speed selectors answering $ATTRIBUTE_SELECTOR", 1, selectors.size)
                val dropdownHolders = FixtureDex.classesHolding(bundle, SPEED_DROPDOWN).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val menus = dropdownHolders.flatMap { it.methods }.filter { holdsString(it, SPEED_DROPDOWN) }
                assertEquals("$name: speed dropdowns holding \"$SPEED_DROPDOWN\"", 1, menus.size)
                val dropdowns = menus.filter { speedLists(it).isNotEmpty() }
                if (dropdowns.isEmpty()) {
                    assertTrue("$name: the dropdown fills no list and doesn't call the selector", menus.single().code().any {
                        it.call?.let { c -> c.definingClass == selectors.single().definingClass && c.name == selectors.single().name } == true
                    })
                }
                val pickers = selectors + dropdowns
                for (picker in pickers) {
                    assertEquals("$name: the lists of speeds ${picker.definingClass}->${picker.name} makes", 1, speedLists(picker).size)
                }
                val dropdownClass = dropdownHolders.single { it.type == menus.single().definingClass }

                // The gear menu's speed sheet builder makes the gear pick's class, takes its flag for
                // reading speeds from floats last (584 reads it from MobileConfig), and reads a speed for
                // each label in one place (#95).
                val sheetHolders = FixtureDex.classesHolding(bundle, GEAR_SHEET).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val sheets = sheetHolders.flatMap { it.methods }.filter { isGearSheet(it, gearPick.definingClass) }
                assertEquals("$name: gear speed sheet builders making ${gearPick.definingClass}", 1, sheets.size)
                val sheet = sheets.single()
                val meets = gearMeets(sheet)
                assertEquals("$name: places ${sheet.definingClass}->${sheet.name} reads a speed for each label", 1, meets.size)
                val meet = meets.single()
                val sheetClass = sheetHolders.single { it.type == sheet.definingClass }
                // With no player found it checks normal speed, loaded where the getter's path goes on (#199).
                val misses = gearMisses(sheet, owner.type)
                assertEquals("$name: places ${sheet.definingClass}->${sheet.name} checks normal speed for a player it can't find",
                    1, misses.size)
                val miss = misses.single()
                val speedGetter = owner.methods.singleOrNull {
                    it.name == miss.getter.name && it.returnType == "F" && it.parameterTypes.isEmpty()
                }
                assertTrue("$name: ${owner.type} has no public ()F ${miss.getter.name}",
                    speedGetter != null && AccessFlags.PUBLIC.isSet(speedGetter.accessFlags))

                // HeroManager's setPlaybackSpeed loads its 0.25x floor once and keeps speeds over it.
                val rangeHolders = FixtureDex.classesHolding(bundle, SPEED_RANGE_LOG).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val ranged = rangeHolders.flatMap { it.methods }.filter { holdsString(it, SPEED_RANGE_LOG) }
                assertEquals("$name: methods holding \"$SPEED_RANGE_LOG\"", 1, ranged.size)
                val speedRange = ranged.single()
                assertEquals("$name: loads of the ${HERO_FLOOR}f floor", 1, speedFloors(speedRange).size)
                // 582 keeps it through an outlined static float clamp.
                val clamps = FixtureDex.classes(bundle, speedRange.code().filter { it.opcode == Opcode.INVOKE_STATIC }
                    .mapNotNull { it.call?.takeIf { call -> call.returnType == "F" }?.definingClass }.toSet()).values
                val resolveClamp = { call: MethodReference -> clamps.singleOrNull { it.type == call.definingClass }?.let { resolveStatic(it, call) } }
                assertTrue("$name: the floor isn't kept with Math.max", callsFloatMax(speedRange, resolveClamp))
                val rangeClass = rangeHolders.single { it.type == speedRange.definingClass }

                fun classes() = (listOf(owner, toastClass, gearClass) + selectorClasses + listOf( paramsClass, dropdownClass, sheetClass, rangeClass) + clamps +
                    listOf(ExtensionDex.classDef(REEL_SPEED), ExtensionDex.classDef(SETTINGS_STATUS))).distinctBy { it.type }
                val context = PatchContexts.of(classes())
                keepReelSpeedPatch.execute(context)

                fun patched(type: String, method: Method): List<Instruction> = context.mutableClassDefBy(type).methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }.code()

                fun assertFirst(what: String, method: Method, hook: String, registers: List<Int>) {
                    val first = patched(method.definingClass, method)[0]
                    assertEquals("$name: the $what's first instruction", Opcode.INVOKE_STATIC_RANGE, first.opcode)
                    assertEquals("$name: the $what's first call", hook, first.call.toString())
                    assertEquals("$name: the registers the $what hands over", registers, first.registers())
                }
                val self = setter.localRegisterCount()
                assertFirst("speed setter", setter, SPEED_SET, listOf(self, self + 1))
                assertFirst("start", starts.single(), STARTED, listOf(starts.single().localRegisterCount()))
                if (toastRuns) {
                    val hooked = patched(toast.definingClass, toast)
                    val field = speedToastField(toastClass, toast)!!
                    assertEquals("$name: the toast's first instruction", Opcode.IGET, hooked[0].opcode)
                    assertEquals("$name: the toast's speed field", field.toString(), (hooked[0] as ReferenceInstruction).reference.toString())
                    assertEquals("$name: the Runnable it reads from", toast.localRegisterCount(), (hooked[0] as TwoRegisterInstruction).registerB)
                    assertEquals("$name: the toast's second instruction", Opcode.INVOKE_STATIC_RANGE, hooked[1].opcode)
                    assertEquals("$name: the toast's call", PICKED, hooked[1].call.toString())
                    assertEquals("$name: the speed it hands over", listOf((hooked[0] as TwoRegisterInstruction).registerA), hooked[1].registers())
                    assertEquals("$name: the toast gains two instructions", toast.code().size + 2, hooked.size)
                } else {
                    assertFirst("toast", toast, PICKED, listOf(toast.localRegisterCount() + 1))
                }

                // Straight after each of the gear pick's setter calls, the pick, with the speed that call handed over.
                val gearCode = patched(gearPick.definingClass, gearPick)
                val hooked = gearCode.withIndex().filter { it.value.call?.toString() == GEAR_PICKED }
                assertEquals("$name: the gear pick's hooks", gearCalls.size, hooked.size)
                for ((index, instruction) in hooked) {
                    assertEquals("$name: the gear pick's hook doesn't follow a setter call",
                        "${owner.type}->${setter.name}(F)V", gearCode[index - 1].call?.let(::key))
                    assertEquals("$name: the speed the gear pick's hook gets", gearCode[index - 1].registers().last(),
                        instruction.registers().single())
                }

                // Each picker's list goes to the extension straight after asList makes it, and the picker
                // builds its items from the list that comes back, in the same register.
                for (picker in pickers) {
                    val (result, list) = speedLists(picker).single()
                    val code = patched(picker.definingClass, picker)
                    val what = "${picker.definingClass}->${picker.name}"
                    assertEquals("$name: $what's list doesn't go to the extension", SPEED_CHOICES, code[result + 1].call.toString())
                    assertEquals("$name: the register $what hands over", listOf(list), code[result + 1].registers())
                    assertEquals("$name: $what doesn't take the list back", Opcode.MOVE_RESULT_OBJECT, code[result + 2].opcode)
                    assertEquals("$name: the register $what takes it back in", list, (code[result + 2] as OneRegisterInstruction).registerA)
                }

                // The sheet hands its flag over as soon as it has it and takes it back in the same register.
                // Where it walks its labels it hands over the speeds and then the labels, taking each back in
                // its own register. Each hook moves what comes after it: the flag's two instructions, the
                // normal speed's two and the walk's four.
                val sheetCode = patched(sheet.definingClass, sheet)
                fun assertHook(at: Int, what: String, hook: String, register: Int, result: Opcode) {
                    assertEquals("$name: the sheet's $what hook", hook, sheetCode[at].call.toString())
                    assertEquals("$name: the register the sheet hands over as its $what", listOf(register), sheetCode[at].registers())
                    assertEquals("$name: the sheet doesn't take its $what back", result, sheetCode[at + 1].opcode)
                    assertEquals("$name: the register the sheet takes its $what back in", register,
                        (sheetCode[at + 1] as OneRegisterInstruction).registerA)
                }
                val flagAt = meet.valuesAt + if (miss.index < meet.valuesAt) 2 else 0
                assertHook(flagAt, "flag", GEAR_VALUES, meet.values, Opcode.MOVE_RESULT)
                if (meet.valuesAt > 0) {
                    assertEquals("$name: the flag isn't handed over as it's read", Opcode.MOVE_RESULT, sheetCode[flagAt - 1].opcode)
                }
                val walk = meet.index + 2 + if (miss.index < meet.index) 2 else 0
                assertHook(walk, "speeds", GEAR_SPEEDS, meet.speeds, Opcode.MOVE_RESULT_OBJECT)
                assertHook(walk + 2, "labels", GEAR_LABELS, meet.labels, Opcode.MOVE_RESULT_OBJECT)
                assertEquals("$name: the walk doesn't follow the hooks", Opcode.ARRAY_LENGTH, sheetCode[walk + 4].opcode)
                // Its normal speed goes to the extension straight after it's loaded and comes back in the same
                // register, ahead of the goto back to the getter's path.
                val normalAt = miss.index + (if (meet.valuesAt <= miss.index) 2 else 0) + if (miss.index > meet.index) 4 else 0
                assertEquals("$name: the sheet's normal speed", Opcode.CONST_HIGH16, sheetCode[normalAt].opcode)
                assertHook(normalAt + 1, "normal speed", GEAR_SHEET_SPEED, miss.speed, Opcode.MOVE_RESULT)
                assertTrue("$name: the sheet doesn't go back after the normal speed hook",
                    sheetCode[normalAt + 3].opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32))

                val stubs = context.mutableClassDefBy(REEL_SPEED)
                val setStub = stubs.methods.single { it.name == SET_SPEED_STUB }.code()
                assertEquals("$name: the setter stub's cast", owner.type, ((setStub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the setter stub's call", "${owner.type}->${setter.name}(F)V", setStub[1].call.toString())
                assertEquals("$name: the setter stub's registers", listOf(0, 1), setStub[1].registers())
                val originStub = stubs.methods.single { it.name == ORIGIN_STUB }.code()
                assertEquals("$name: the origin stub's call", "${owner.type}->${origin.name}()$PLAYER_ORIGIN",
                    originStub[1].call.toString())
                assertEquals("$name: the origin stub's answer", Opcode.RETURN_OBJECT, originStub[3].opcode)
                val paramsStub = stubs.methods.single { it.name == REEL_PARAMS_STUB }.code()
                assertEquals("$name: the params stub's call", "${owner.type}->${paramsGetter.single().name}()$VIDEO_PLAYER_PARAMS",
                    paramsStub[1].call.toString())
                assertEquals("$name: the params stub's answer", Opcode.RETURN_OBJECT, paramsStub[3].opcode)
                val speedStub = stubs.methods.single { it.name == SPEED_STUB }.code()
                assertEquals("$name: the speed stub's cast", owner.type, ((speedStub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the speed stub's call", "${owner.type}->${miss.getter.name}()F", speedStub[1].call.toString())
                assertEquals("$name: the speed stub's answer", Opcode.RETURN, speedStub[3].opcode)
                for ((stub, field) in flagFields) {
                    val code = stubs.methods.single { it.name == stub }.code()
                    assertEquals("$name: the $stub stub's cast", VIDEO_PLAYER_PARAMS, ((code[0] as ReferenceInstruction).reference as TypeReference).type)
                    assertEquals("$name: the $stub stub's read", Opcode.IGET_BOOLEAN, code[1].opcode)
                    assertEquals("$name: the $stub stub's field", "$VIDEO_PLAYER_PARAMS->$field:Z",
                        ((code[1] as ReferenceInstruction).reference as FieldReference).toString())
                    assertEquals("$name: the $stub stub reads its argument", 0, (code[1] as TwoRegisterInstruction).registerB)
                    assertEquals("$name: the $stub stub's answer", Opcode.RETURN, code[2].opcode)
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "keepReelSpeed" }
                assertEquals("$name: SettingsStatus.keepReelSpeed() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)

                // As the patcher runs it, after the release guard it brings: the guard's answer stays
                // first in the setter, and the hook after it reads the speed the player gets.
                val guarded = PatchContexts.of(classes())
                guarded.hookSpeedSetter(setter)
                keepReelSpeedPatch.execute(guarded)
                val chain = guarded.mutableClassDefBy(owner.type).methods.single {
                    it.name == setter.name && it.parameterTypes.map(CharSequence::toString) == listOf("F")
                }.code()
                assertEquals("$name: the guard's hook isn't first in the setter", GUARD_SPEED_SET, chain[0].call.toString())
                assertEquals("$name: the guard's answer isn't taken", Opcode.MOVE_RESULT, chain[1].opcode)
                assertEquals("$name: the hook after the guard's answer", SPEED_SET, chain[2].call.toString())
                assertEquals("$name: the registers the hook after the guard hands over", listOf(self, self + 1), chain[2].registers())

                // The floor loads 0.1f into the same register, and nothing else in the method moved.
                val floorAt = speedFloors(speedRange).single()
                val lowered = patched(speedRange.definingClass, speedRange)
                assertEquals("$name: the lowered floor", Opcode.CONST, lowered[floorAt].opcode)
                assertEquals("$name: the lowered floor's value", SLOWEST.toRawBits(), (lowered[floorAt] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: the lowered floor's register", (speedRange.code()[floorAt] as OneRegisterInstruction).registerA,
                    (lowered[floorAt] as OneRegisterInstruction).registerA)
                assertEquals("$name: the setter's other instructions", speedRange.code().map { it.opcode }.filterIndexed { i, _ -> i != floorAt },
                    lowered.map { it.opcode }.filterIndexed { i, _ -> i != floorAt })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
