/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.quality

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTableCall
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
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
 * Default playback quality's anchors on every Facebook build the bundle declares: the one (String)V
 * call in the method holding "HeroServicePlayer.setCustomQualityInternal", on a DASH format
 * evaluator built with an AbrContextAwareConfiguration; that setter's one array of tracks and its
 * one way of reading a track's label; the one String field the evaluator hands its own setter,
 * which its constructor fills in, the first choice empties right after, and nothing else in the
 * build writes; and the setter's only callers, that first choice and the Hero player's. Then the
 * patch itself, run on those classes: the extension asked first in the setter with its own two
 * arguments, told before every return of the constructor, where the constructor's own branches now
 * land, and each stub reading the member it stands for. The origin stubs reach the player origin
 * and sub-origin through the evaluator's one AbrContextAwareConfiguration, which its constructor
 * fills in, and that configuration's playbackPreferences, reading the two fields the configuration
 * compares with "fb_stories" and "fb_shorts_viewer". Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class DefaultPlaybackQualityFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private val Instruction.field: FieldReference?
        get() = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    /** The builds whose method holding the custom-quality trace calls the evaluator's setter instead of inlining it. */
    private val callsSetter = setOf("586.0.0.52.53")

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
                val holders = FixtureDex.classesHolding(bundle, SET_CUSTOM_QUALITY).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val holding = holders.flatMap { it.methods }.filter { holdsString(it, SET_CUSTOM_QUALITY) }
                assertEquals("$name: methods holding \"$SET_CUSTOM_QUALITY\"", 1, holding.size)
                // 586 calls the evaluator's custom-quality setter there, 585 inlined it.
                val setters = customQualitySetters(holding.single()) { type -> FixtureDex.classes(bundle, setOf(type)).values.singleOrNull() }
                assertEquals("$name: custom-quality setters called there", if (version in callsSetter) 1 else 0, setters.size)
                val setter = setters.singleOrNull()
                val reader = setter ?: holding.single()
                val arrays = trackArrayReads(reader)
                assertEquals("$name: arrays of tracks read there", 1, arrays.size)
                val formats = arrays.single()
                val format = formats.type.removePrefix("[")

                val evaluator = FixtureDex.classes(bundle, setOf(formats.definingClass)).values.single()
                assertTrue("$name: ${evaluator.type} isn't public", AccessFlags.PUBLIC.isSet(evaluator.accessFlags))
                assertTrue("$name: ${evaluator.type} isn't built with an $ABR_CONFIGURATION", takesAbrConfiguration(evaluator))
                val constructor = evaluator.methods.single { it.name == "<init>" }

                val labelReads = labelReads(reader, format)
                assertEquals("$name: ways the method reads a track's label", 1, labelReads.size)
                val (labelOf, label) = labelReads.single()

                val preselects = preselectedReads(evaluator, constructor, labelOf, setter)
                assertEquals("$name: labels the evaluator compares with the tracks' labels", 1, preselects.size)
                val firstChoice = preselects.single()
                val preselected = firstChoice.field
                assertTrue("$name: the constructor doesn't fill in ${preselected.name}",
                    constructor.code().any { it.opcode == Opcode.IPUT_OBJECT && it.field.toString() == preselected.toString() })

                // The first choice looks the label up once and empties the field in the same method.
                val choiceCode = firstChoice.method.code()
                assertTrue("$name: ${firstChoice.method.name} doesn't empty ${preselected.name} after reading it",
                    choiceCode.drop(firstChoice.index).any { it.opcode == Opcode.IPUT_OBJECT && it.field.toString() == preselected.toString() })

                // Nothing else in the build writes the label.
                val writers = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.fieldSection.any { it.definingClass == evaluator.type && it.name == preselected.name }
                }) { method ->
                    method.implementation?.instructions?.any { it.opcode == Opcode.IPUT_OBJECT && it.field.toString() == preselected.toString() } == true
                }
                assertEquals("$name: methods writing ${preselected.name}",
                    setOf("<init>", firstChoice.method.name), writers.map { it.name }.toSet())
                assertTrue("$name: a writer of ${preselected.name} outside ${evaluator.type}", writers.all { it.definingClass == evaluator.type })

                // Where the video plays: the configuration the evaluator keeps, and the two names its
                // playback preferences carry, each compared once by the configuration's constructor.
                val configurations = evaluator.fields.filter { it.type == ABR_CONFIGURATION && !AccessFlags.STATIC.isSet(it.accessFlags) }
                assertEquals("$name: configurations ${evaluator.type} keeps", 1, configurations.size)
                val configurationField = configurations.single()
                assertTrue("$name: ${configurationField.name} isn't public", AccessFlags.PUBLIC.isSet(configurationField.accessFlags))
                assertTrue("$name: the constructor doesn't fill in ${configurationField.name}", constructor.code().any {
                    it.opcode == Opcode.IPUT_OBJECT && it.field?.name == configurationField.name && it.field?.definingClass == evaluator.type
                })
                val configuration = FixtureDex.classes(bundle, setOf(ABR_CONFIGURATION)).values.single()
                val preferences = configuration.fields.single { it.name == PLAYBACK_PREFERENCES }
                val preferencesClass = FixtureDex.classes(bundle, setOf(preferences.type)).values.single()
                // 582 asks a string table for the sub-origin names.
                val tables = FixtureDex.classes(bundle, configuration.methods.flatMap { method ->
                    method.implementation?.instructions?.filter(::isStringTableCall)?.mapNotNull { it.call?.definingClass }.orEmpty()
                }.toSet())
                val resolve = { call: MethodReference -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val origins = originReads(configuration, preferences.type, STORIES_ORIGIN, resolve)
                val subOrigins = originReads(configuration, preferences.type, REELS_SUB_ORIGIN, resolve)
                assertEquals("$name: origins compared with \"$STORIES_ORIGIN\"", 1, origins.size)
                assertEquals("$name: sub-origins compared with \"$REELS_SUB_ORIGIN\"", 1, subOrigins.size)
                assertEquals("$name: sub-origins compared with \"fb_shorts_native_in_feed_unit\"", subOrigins,
                    originReads(configuration, preferences.type, "fb_shorts_native_in_feed_unit", resolve))
                val origin = origins.single()
                val subOrigin = subOrigins.single()
                assertTrue("$name: the origin and the sub-origin are one field", origin.name != subOrigin.name)
                for (field in listOf(origin, subOrigin)) {
                    assertTrue("$name: $field isn't public",
                        AccessFlags.PUBLIC.isSet(preferencesClass.fields.single { it.name == field.name && it.type == field.type }.accessFlags))
                }
                assertTrue("$name: ${preferencesClass.type} isn't public", AccessFlags.PUBLIC.isSet(preferencesClass.accessFlags))

                val formatClass = FixtureDex.classes(bundle, setOf(format)).values.single()
                val labelClass = FixtureDex.classes(bundle, setOf(labelOf.definingClass)).values.single()
                val infoClass = label?.let { FixtureDex.classes(bundle, setOf(it.definingClass)).values.single() }
                val context = PatchContexts.of((listOfNotNull(holders.single(), evaluator, formatClass, labelClass, infoClass, configuration, preferencesClass) +
                    tables.values + FixtureDex.classesHolding(bundle, FORMAT_SUMMARY)).distinctBy { it.type } +
                    listOf(ExtensionDex.classDef(QUALITY_CHOICE), ExtensionDex.classDef(QUALITY_LIST), ExtensionDex.classDef(SETTINGS_STATUS)))
                defaultPlaybackQualityPatch.execute(context)

                fun patched(method: Method): List<Instruction> = context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }.implementation!!.instructions.toList()

                // Right after the first choice reads the label: the extension gets the evaluator and the label and answers the label.
                val labelRead = firstChoice.method.code()[firstChoice.index] as TwoRegisterInstruction
                val patchedChoice = patched(firstChoice.method)
                val hookAt = firstChoice.index + 1
                assertEquals("$name: the first choice's read of ${preselected.name}", Opcode.IGET_OBJECT to preselected.toString(),
                    patchedChoice[firstChoice.index].opcode to patchedChoice[firstChoice.index].field.toString())
                assertEquals("$name: the hook's call", Opcode.INVOKE_STATIC, patchedChoice[hookAt].opcode)
                assertEquals("$name: the hook's callee", CUSTOM_QUALITY, patchedChoice[hookAt].call.toString())
                assertEquals("$name: the hook hands over the evaluator and the label", listOf(labelRead.registerB, labelRead.registerA),
                    patchedChoice[hookAt].registers())
                assertEquals("$name: the answer takes the label's place", Opcode.MOVE_RESULT_OBJECT to labelRead.registerA,
                    patchedChoice[hookAt + 1].opcode to (patchedChoice[hookAt + 1] as OneRegisterInstruction).registerA)
                val stock = firstChoice.method.code().filter { it.opcode != Opcode.NOP }
                assertEquals("$name: the first choice grew by the hook alone", stock.size + 2, patchedChoice.count { it.opcode != Opcode.NOP })

                // Before every return of the constructor, and a branch that went to a return lands on its hook.
                val original = constructor.code()
                val addresses = original.runningFold(0) { address, instruction -> address + instruction.codeUnits }
                val branchesToReturns = original.indices.count { index ->
                    val branch = original[index] as? OffsetInstruction ?: return@count false
                    val target = addresses.indexOf(addresses[index] + branch.codeOffset)
                    original.getOrNull(target)?.opcode == Opcode.RETURN_VOID
                }
                val built = patched(constructor)
                val returns = built.indices.filter { built[it].opcode == Opcode.RETURN_VOID }
                assertEquals("$name: the constructor's returns", original.count { it.opcode == Opcode.RETURN_VOID }, returns.size)
                val branches = built.filterIsInstance<BuilderOffsetInstruction>()
                var branchesToHooks = 0
                for (index in returns) {
                    val hook = built[index - 1]
                    assertEquals("$name: the call before the constructor's return", EVALUATOR_BUILT, hook.call.toString())
                    assertEquals("$name: the register the constructor hands over", listOf(constructor.localRegisterCount()), hook.registers())
                    assertTrue("$name: a branch of the constructor skips the hook", branches.none { it.target.location.instruction === built[index] })
                    branchesToHooks += branches.count { it.target.location.instruction === hook }
                }
                assertEquals("$name: branches that went to a return now going to its hook", branchesToReturns, branchesToHooks)

                // Each stub reads or writes what it stands for, from its own arguments.
                val stubs = context.mutableClassDefBy(QUALITY_CHOICE)
                fun stub(stubName: String) = stubs.methods.single { it.name == stubName }.implementation!!.instructions.toList()
                fun cast(instruction: Instruction) = ((instruction as ReferenceInstruction).reference as TypeReference).type

                val read = stub(PRESELECTED_STUB)
                assertEquals("$name: the preselected stub's cast", evaluator.type, cast(read[0]))
                assertEquals("$name: the preselected stub's read", Opcode.IGET_OBJECT to preselected.toString(), read[1].opcode to read[1].field.toString())
                assertEquals("$name: the preselected stub's answer", Opcode.RETURN_OBJECT, read[2].opcode)
                val write = stub(PRESELECT_STUB)
                assertEquals("$name: the preselect stub's cast", evaluator.type, cast(write[0]))
                assertEquals("$name: the preselect stub's write", Opcode.IPUT_OBJECT to preselected.toString(), write[1].opcode to write[1].field.toString())
                assertEquals("$name: the preselect stub writes its label into its evaluator", 1 to 0,
                    (write[1] as TwoRegisterInstruction).let { it.registerA to it.registerB })
                assertEquals("$name: the preselect stub's end", Opcode.RETURN_VOID, write[2].opcode)
                val tracks = stub(FORMATS_STUB)
                assertEquals("$name: the tracks stub's cast", evaluator.type, cast(tracks[0]))
                assertEquals("$name: the tracks stub's read", Opcode.IGET_OBJECT to formats.toString(), tracks[1].opcode to tracks[1].field.toString())
                assertEquals("$name: the tracks stub's answer", Opcode.RETURN_OBJECT, tracks[2].opcode)
                val labelStub = stub(LABEL_STUB)
                assertEquals("$name: the label stub's cast", format, cast(labelStub[0]))
                assertEquals("$name: the label stub's call", labelOf.toString(), labelStub[1].call.toString())
                if (label != null) {
                    assertEquals("$name: the label stub's read", Opcode.IGET_OBJECT to label.toString(), labelStub[3].opcode to labelStub[3].field.toString())
                }
                assertEquals("$name: the label stub's answer", Opcode.RETURN_OBJECT, labelStub.last().opcode)
                for ((stubName, field) in listOf(ORIGIN_STUB to origin, SUB_ORIGIN_STUB to subOrigin)) {
                    val stubMethod = stubs.methods.single { it.name == stubName }
                    val parameter = stubMethod.implementation!!.registerCount - 1
                    val all = stubMethod.implementation!!.instructions.toList()
                    val reads = all.subList(0, all.indices.filter { all[it].opcode == Opcode.RETURN_OBJECT }[1] + 1)
                    assertEquals("$name: the $stubName stub's cast", evaluator.type, cast(reads[0]))
                    assertEquals("$name: the $stubName stub's reads",
                        listOf(configurationField.name, PLAYBACK_PREFERENCES, field.name),
                        reads.filter { it.opcode == Opcode.IGET_OBJECT }.map { it.field!!.name })
                    assertEquals("$name: the $stubName stub's read of the name", field.toString(),
                        reads.filter { it.opcode == Opcode.IGET_OBJECT }.last().field.toString())
                    assertEquals("$name: the $stubName stub's reads stay in its one register", setOf(parameter),
                        reads.filterIsInstance<OneRegisterInstruction>().map { it.registerA }.toSet())
                    assertEquals("$name: the $stubName stub's answers", 2, reads.count { it.opcode == Opcode.RETURN_OBJECT })
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "defaultPlaybackQuality" }
                assertEquals("$name: SettingsStatus.defaultPlaybackQuality() isn't switched on", 1,
                    (status.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
