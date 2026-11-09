/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.quality

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PATCH = "Default playback quality"

/**
 * Videos, reels and video stories start at the quality picked in Hushfacebook's settings. See
 * QualityAnchors.kt for how Facebook's player applies a quality, and the extension's QualityChoice
 * for when it keeps Facebook's own.
 *
 * In the default selection with its switch off and the quality as Facebook's own, so nothing
 * changes until the switch is turned on and a quality is chosen.
 */
@Suppress("unused")
val defaultPlaybackQualityPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Default playback quality",
    description = "Plays videos, reels and video stories at the quality you choose, such as Data saver or up to " +
        "720p, instead of what Facebook picks. Lower saves data, higher looks sharper. Starts off. Turn it on " +
        "and pick a quality in Hushfacebook settings > Video and audio.",
) {
    category("Playback")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        applyQualityAnchors(findQualityAnchors())
        keepQualityRow(findQualityListStore().getOrElse { refuse(it.message.orEmpty()) })
        enableStatus("defaultPlaybackQuality")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** What [findQualityAnchors] found, for [applyQualityAnchors] to change. */
internal class QualityAnchors(
    val evaluator: ClassDef,
    val constructor: Method,
    val firstChoice: PreselectedRead,
    val preselected: FieldReference,
    val formats: FieldReference,
    val labelOf: MethodReference,
    /** The String field of what [labelOf] answers, or null when it answers the String itself. */
    val label: FieldReference?,
    val configuration: FieldReference,
    val preferences: FieldReference,
    val origin: FieldReference,
    val subOrigin: FieldReference,
)

/**
 * The DASH format evaluator, found as the class whose track array the method holding
 * "HeroServicePlayer.setCustomQualityInternal" reads, its constructor, the preselected label its
 * first choice compares with the tracks' labels, the tracks and how a track's label is read.
 * Changes nothing.
 */
internal fun BytecodePatchContext.findQualityAnchors(): QualityAnchors {
    val holding = classDefByStrings(SET_CUSTOM_QUALITY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { holder -> holder.methods.filter { holdsString(it, SET_CUSTOM_QUALITY) } }
    val method = holding.singleOrNull()
        ?: refuse("expected one method holding \"$SET_CUSTOM_QUALITY\", found ${holding.size}")
    // 586 calls the evaluator's custom-quality setter there, 585 inlined it: the tracks and their labels are read in whichever holds them.
    val setters = customQualitySetters(method) { classDefByOrNull(it) }
    if (setters.size > 1) {
        refuse("expected at most one custom-quality setter called in the method holding \"$SET_CUSTOM_QUALITY\", found ${setters.size}")
    }
    val setter = setters.singleOrNull()
    val reader = setter ?: method
    val arrays = trackArrayReads(reader)
    val evaluators = arrays.mapNotNull { classDefByOrNull(it.definingClass) }.filter(::takesAbrConfiguration).distinctBy { it.type }
    val evaluator = evaluators.singleOrNull()
        ?: refuse("expected one array of tracks of a class built with $ABR_CONFIGURATION read in ${reader.definingClass}->${reader.name}, found ${evaluators.size}")
    val constructors = evaluator.methods.filter { it.name == "<init>" }
    val constructor = constructors.singleOrNull() ?: refuse("expected one constructor of ${evaluator.type}, found ${constructors.size}")

    val formatsFields = arrays.filter { it.definingClass == evaluator.type }
    val formats = formatsFields.singleOrNull()
        ?: refuse("expected ${reader.definingClass}->${reader.name} to read one array of tracks of ${evaluator.type}, found ${formatsFields.size}")
    val format = formats.type.removePrefix("[")
    val reads = labelReads(reader, format).distinctBy { it.toString() }
    val (labelOf, label) = reads.singleOrNull()
        ?: refuse("expected ${reader.definingClass}->${reader.name} to read a track's label one way, found ${reads.size}")

    val preselects = preselectedReads(evaluator, constructor, labelOf, setter)
    val firstChoice = preselects.singleOrNull()
        ?: refuse("expected one label ${evaluator.type} fills in and compares with the tracks' labels, found ${preselects.size}")
    val preselected = firstChoice.field
    val labelRead = firstChoice.method.implementation!!.instructions.elementAt(firstChoice.index) as TwoRegisterInstruction
    if (labelRead.registerA > 15 || labelRead.registerB > 15) {
        refuse("the first choice reads the preselected label in v${labelRead.registerA}/v${labelRead.registerB}, too high for the hook's call")
    }

    // The extension's stubs cast to these types and read these members from outside Facebook's package.
    val formatClass = classDefByOrNull(format) ?: refuse("this build has no $format")
    val labelClass = classDefByOrNull(labelOf.definingClass) ?: refuse("this build has no ${labelOf.definingClass}")
    val infoClass = label?.let { classDefByOrNull(it.definingClass) ?: refuse("this build has no ${it.definingClass}") }
    listOfNotNull(evaluator, formatClass, labelClass, infoClass).forEach {
        if (!AccessFlags.PUBLIC.isSet(it.accessFlags)) refuse("${it.type} isn't public, so the extension can't reach it")
    }
    val members = listOfNotNull(
        preselected.toString() to evaluator.fields.singleOrNull { it.name == preselected.name && it.type == preselected.type }?.accessFlags,
        formats.toString() to evaluator.fields.singleOrNull { it.name == formats.name && it.type == formats.type }?.accessFlags,
        label?.let { field ->
            field.toString() to infoClass!!.fields.singleOrNull { it.name == field.name && it.type == field.type }?.accessFlags
        },
        labelOf.toString() to labelClass.methods.singleOrNull {
            it.name == labelOf.name && it.returnType == labelOf.returnType &&
                it.parameterTypes.map(CharSequence::toString) == listOf(format)
        }?.accessFlags,
    )
    members.forEach { (name, flags) ->
        if (flags == null || !AccessFlags.PUBLIC.isSet(flags)) refuse("$name isn't public, so the extension can't reach it")
    }
    val (configuration, preferences, origin, subOrigin) = findOriginAnchors(evaluator, constructor)
    return QualityAnchors(evaluator, constructor, firstChoice, preselected, formats, labelOf, label,
        configuration, preferences, origin, subOrigin)
}

/**
 * Where the extension reads a video's player origin and sub-origin: the evaluator's one
 * AbrContextAwareConfiguration field, which its constructor fills in, that configuration's
 * playbackPreferences, and the two String fields of those the configuration compares with
 * "fb_stories" and "fb_shorts_viewer". Changes nothing.
 */
private fun BytecodePatchContext.findOriginAnchors(evaluator: ClassDef, constructor: Method): List<FieldReference> {
    val configurations = evaluator.fields.filter { it.type == ABR_CONFIGURATION && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val configuration = configurations.singleOrNull()
        ?: refuse("expected one $ABR_CONFIGURATION field in ${evaluator.type}, found ${configurations.size}")
    val stored = constructor.implementation?.instructions?.any {
        it.opcode == Opcode.IPUT_OBJECT && ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { field ->
            field.definingClass == evaluator.type && field.name == configuration.name && field.type == configuration.type
        } == true
    } == true
    if (!stored) refuse("${evaluator.type}'s constructor doesn't fill in ${configuration.name}")

    val config = classDefByOrNull(ABR_CONFIGURATION) ?: refuse("this build has no $ABR_CONFIGURATION")
    val preferences = config.fields.singleOrNull { it.name == PLAYBACK_PREFERENCES && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuse("$ABR_CONFIGURATION has no $PLAYBACK_PREFERENCES")
    val tables = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    val origins = originReads(config, preferences.type, STORIES_ORIGIN, tables)
    val origin = origins.singleOrNull()
        ?: refuse("expected one origin $ABR_CONFIGURATION compares with \"$STORIES_ORIGIN\", found ${origins.size}")
    val subOrigins = originReads(config, preferences.type, REELS_SUB_ORIGIN, tables)
    val subOrigin = subOrigins.singleOrNull()
        ?: refuse("expected one sub-origin $ABR_CONFIGURATION compares with \"$REELS_SUB_ORIGIN\", found ${subOrigins.size}")
    if (origin.name == subOrigin.name) refuse("the origin and the sub-origin are both ${origin.name}")

    // The stubs read these from outside Facebook's package.
    val preferencesClass = classDefByOrNull(preferences.type) ?: refuse("this build has no ${preferences.type}")
    listOf(config, preferencesClass).forEach {
        if (!AccessFlags.PUBLIC.isSet(it.accessFlags)) refuse("${it.type} isn't public, so the extension can't reach it")
    }
    val members = listOf(
        configuration.toString() to configuration.accessFlags,
        "${config.type}->${preferences.name}" to preferences.accessFlags,
        origin.toString() to preferencesClass.fields.singleOrNull { it.name == origin.name && it.type == origin.type }?.accessFlags,
        subOrigin.toString() to preferencesClass.fields.singleOrNull { it.name == subOrigin.name && it.type == subOrigin.type }?.accessFlags,
    )
    members.forEach { (name, flags) ->
        if (flags == null || !AccessFlags.PUBLIC.isSet(flags)) refuse("$name isn't public, so the extension can't reach it")
    }
    return listOf(configuration, preferences, origin, subOrigin)
}

/**
 * The first choice asks the extension right after it reads the preselected label, with the
 * evaluator it read it from and the label, and the answer takes the label's place. The hook is at
 * the read's own successor, so a branch that went there runs it too. The constructor tells the
 * extension about its evaluator before each return, at the return's own label, so a branch that
 * went straight to the return runs the hook too.
 */
internal fun BytecodePatchContext.applyQualityAnchors(anchors: QualityAnchors) {
    val owner = mutableClassDefBy(anchors.evaluator.type)
    val choice = owner.findMutableMethodOf(anchors.firstChoice.method)
    val read = choice.implementation!!.instructions.elementAt(anchors.firstChoice.index) as TwoRegisterInstruction
    choice.addInstructionsAtControlFlowLabel(
        anchors.firstChoice.index + 1,
        """
            invoke-static { v${read.registerB}, v${read.registerA} }, $CUSTOM_QUALITY
            move-result-object v${read.registerA}
        """,
    )
    val constructor = owner.findMutableMethodOf(anchors.constructor)
    val returns = constructor.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) refuse("${anchors.evaluator.type}'s constructor never returns")
    returns.asReversed().forEach { index ->
        constructor.addInstructionsAtControlFlowLabel(index, "invoke-static/range { p0 .. p0 }, $EVALUATOR_BUILT")
    }
    fillStubs(anchors)
}

/** Fills the extension's stubs. Each reads only its parameter registers, cast to Facebook's own type. */
private fun BytecodePatchContext.fillStubs(anchors: QualityAnchors) {
    val extension = mutableClassDefBy(QUALITY_CHOICE)
    fun stub(name: String, parameters: List<String>, answer: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: refuse("$QUALITY_CHOICE has no static $answer $name(${parameters.joinToString("")})")

    val evaluator = anchors.evaluator.type
    val objectType = "Ljava/lang/Object;"
    val string = "Ljava/lang/String;"
    stub(PRESELECTED_STUB, listOf(objectType), string).addInstructions(
        0,
        """
            check-cast p0, $evaluator
            iget-object p0, p0, ${anchors.preselected}
            return-object p0
        """,
    )
    stub(PRESELECT_STUB, listOf(objectType, string), "V").addInstructions(
        0,
        """
            check-cast p0, $evaluator
            iput-object p1, p0, ${anchors.preselected}
            return-void
        """,
    )
    stub(FORMATS_STUB, listOf(objectType), "[$objectType").addInstructions(
        0,
        """
            check-cast p0, $evaluator
            iget-object p0, p0, ${anchors.formats}
            return-object p0
        """,
    )
    stub(ORIGIN_STUB, listOf(objectType), string).addInstructionsWithLabels(0, originRead(anchors, anchors.origin))
    stub(SUB_ORIGIN_STUB, listOf(objectType), string).addInstructionsWithLabels(0, originRead(anchors, anchors.subOrigin))
    stub(LABEL_STUB, listOf(objectType), string).addInstructions(
        0,
        """
            check-cast p0, ${anchors.formats.type.removePrefix("[")}
            invoke-static { p0 }, ${anchors.labelOf}
            move-result-object p0
            ${anchors.label?.let { "iget-object p0, p0, $it" }.orEmpty()}
            return-object p0
        """,
    )
}

/**
 * An origin stub's body: the evaluator's configuration, its playback preferences, then [field].
 * A missing link answers null, a fresh constant so the answer is a String whichever read stopped.
 */
internal fun originRead(anchors: QualityAnchors, field: FieldReference) =
    """
        check-cast p0, ${anchors.evaluator.type}
        iget-object p0, p0, ${anchors.configuration}
        if-eqz p0, :no_origin
        iget-object p0, p0, ${anchors.preferences}
        if-eqz p0, :no_origin
        iget-object p0, p0, $field
        return-object p0
        :no_origin
        const/4 p0, 0x0
        return-object p0
    """
