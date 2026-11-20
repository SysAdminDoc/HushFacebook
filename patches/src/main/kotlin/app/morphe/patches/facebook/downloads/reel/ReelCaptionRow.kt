/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.reel

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireFreeAt
import app.morphe.patches.facebook.misc.upsells.enumConstant
import app.morphe.patches.facebook.media.pip.sheetContext
import app.morphe.util.findMutableMethodOf
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

/** The extension class that keeps reel captions, answers the row and handles its tap. */
internal const val CAPTION_HANDLER = "Lapp/morphe/extension/facebook/download/ReelCaption;"

/** The id the added row carries, in the place Facebook's rows carry theirs. */
internal const val CAPTION_ROW_ID = "hushfacebook_copy_caption"

/** The helper this adds to the sheet builder's class. */
internal const val CAPTION_ROW_HELPER = "hushfacebookCaptionRow"

/** The name a reel caption's render gives the text component it builds. */
internal const val DESCRIPTION_COMPONENT = "FbShortsViewerDescriptionComponentSpec_Text_Component_"

/** The names Kotlin keeps on the caption's tap lambda for the caption and the reel it was handed. */
internal const val BASE_DESCRIPTION_FIELD = "\$baseDescriptionText"
internal const val UNIT_FIELD = "\$shortFormVideoUnit"

/** The name of the icon constant the row draws: Facebook's copy icon. */
internal const val COPY_ICON = "COPY"

private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val CONTEXT = "Landroid/content/Context;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"

/** The helper this adds to the caption's tap lambda class. */
internal const val CAPTION_REMEMBER_HELPER = "hushfacebookRememberCaption"

/**
 * A reel caption's render, as the patch finds it: the component that draws the caption under a reel
 * (#240), its render method, and the tap lambda it builds there with the caption it shows. Kotlin
 * keeps that lambda's captured names, `$baseDescriptionText` and `$shortFormVideoUnit`, so the reel
 * and its whole caption are read off the lambda by name. Nothing obfuscated is written down.
 *
 * The lambda is the place to read them: the component's own CharSequence field is only a caption
 * handed in from outside, null in the Reels viewer, where the render builds the caption itself.
 */
internal class CaptionRender(
    val render: MutableMethod,
    val lambda: String,
    val unit: FieldReference,
    val caption: FieldReference,
)

internal fun BytecodePatchContext.captionRender(patch: String): CaptionRender {
    // Each type built once looked up: the table route below reads every method of many classes.
    val lambdas = HashMap<String, ClassDef?>()
    fun lambdaBuilt(method: Method): ClassDef? = method.implementation?.instructions?.asSequence()
        ?.filter { it.opcode == Opcode.NEW_INSTANCE }
        ?.mapNotNull { instruction ->
            val type = ((instruction as ReferenceInstruction).reference as TypeReference).type
            if (type !in lambdas) {
                lambdas[type] = classDefByOrNull(type)?.takeIf { built -> built.fields.any { it.name == BASE_DESCRIPTION_FIELD } }
            }
            lambdas[type]
        }?.firstOrNull()
    val holding = classDefByStrings(DESCRIPTION_COMPONENT, StringComparisonType.EQUALS)
        .flatMap { classDef ->
            classDef.methods.mapNotNull { method ->
                val instructions = method.implementation?.instructions ?: return@mapNotNull null
                if (instructions.none { it.stringValue() == DESCRIPTION_COMPONENT }) return@mapNotNull null
                lambdaBuilt(method)?.let { Triple(classDef.type, method, it) }
            }
        }
    // 588's Android 9 build renders the caption in a helper class (`LX/09TV;->A02`) that asks a string
    // table for the component's name (`LX/0vfR;->A00(I)String`) instead of loading it.
    val (owner, render, lambda) = holding.ifEmpty {
        methodsNaming(DESCRIPTION_COMPONENT) { lambdaBuilt(it) != null }
            .map { method -> Triple(method.definingClass, method, lambdaBuilt(method)!!) }
    }.singleOrPatchException("$patch: the one reel caption render naming \"$DESCRIPTION_COMPONENT\" and building its tap lambda")
    return CaptionRender(
        render = mutableClassDefBy(owner).findMutableMethodOf(render),
        lambda = lambda.type,
        unit = lambdaField(patch, lambda, UNIT_FIELD),
        caption = lambdaField(patch, lambda, BASE_DESCRIPTION_FIELD),
    )
}

private fun lambdaField(patch: String, lambda: ClassDef, name: String): FieldReference =
    lambda.fields.filter { it.name == name && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .singleOrPatchException("$patch: the caption's tap lambda ${lambda.type} keeping one $name")
        .let { ImmutableFieldReference(lambda.type, it.name, it.type) }

/** The first register of a call, ranged or not. */
private fun Instruction.firstRegister(): Int = when (this) {
    is RegisterRangeInstruction -> startRegister
    is FiveRegisterInstruction -> registerC
    else -> throw IllegalArgumentException("$opcode carries no registers")
}

/**
 * Hands each reel caption the render draws to [CAPTION_HANDLER], with the reel it belongs to, right
 * after the render builds the caption's tap lambda: a helper on the lambda's class reads both off it.
 * The extension keeps the text by the reel's video id for the row below.
 */
internal fun BytecodePatchContext.rememberCaptions(patch: String) {
    val caption = captionRender(patch)
    val lambda = caption.lambda
    if (caption.caption.type != CHAR_SEQUENCE) {
        throw PatchException("$patch: the caption's tap lambda $lambda keeps its caption as ${caption.caption.type}")
    }
    val helper = ImmutableMethod(
        lambda,
        CAPTION_REMEMBER_HELPER,
        listOf(ImmutableMethodParameter(lambda, null, null)),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                iget-object v0, p0, ${caption.unit}
                iget-object v1, p0, ${caption.caption}
                invoke-static { v0, v1 }, $CAPTION_HANDLER->remember($OBJECT$CHAR_SEQUENCE)V
                return-void
            """,
        )
    }
    mutableClassDefBy(lambda).methods.add(helper)

    val built = caption.render.implementation!!.instructions.withIndex().filter { (_, instruction) ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            reference?.definingClass == lambda && reference.name == "<init>"
    }.map { (index, instruction) -> index to instruction.firstRegister() }
    if (built.isEmpty()) {
        throw PatchException("$patch: the reel caption render ${caption.render.definingClass}->${caption.render.name} never builds $lambda")
    }
    // Last first, so the earlier indexes still point where they did.
    for ((index, register) in built.asReversed()) {
        caption.render.addInstructions(
            index + 1,
            "invoke-static/range { v$register .. v$register }, $lambda->$CAPTION_REMEMBER_HELPER($lambda)V",
        )
    }
}

/**
 * Puts a Copy caption row in a reel's More sheet, right before Clear mode, drawn with Facebook's copy
 * icon (Clear mode's when the build names none). A helper on the builder's class hands the sheet's
 * Context and the reel's video id to [CAPTION_HANDLER] and builds the row when it gets a handler back,
 * which it does only when a caption was seen for that very video. The handler class is made to
 * implement the row's click interface, with the interface's one method calling its `run()`.
 */
internal fun BytecodePatchContext.addCaptionRow(patch: String, sheet: MoreSheet) {
    val builder = sheet.builder
    val parameters = builder.parameterTypes.map(CharSequence::toString)
    val context = sheetContext(sheet)
    val id = parameters.indices.filter { parameters[it] == STRING }
        .singleOrPatchException("$patch: the one video id parameter of ${builder.definingClass}->${builder.name}")
    val list = parameters.indices.filter { parameters[it] == LIST }
        .singleOrPatchException("$patch: the one row list parameter of ${builder.definingClass}->${builder.name}")
    val wrap = sheet.iconWrap
    val icon = classDefByOrNull(wrap.parameterTypes.single().toString())
        ?.let { runCatching { enumConstant(it, COPY_ICON) }.getOrNull() }
        ?: sheet.clearModeIcon

    // The three values go to v0 to v2 first thing in the builder, proved free there before anything changes.
    builder.requireFreeAt(patch, 0, listOf(0, 1, 2))
    implementClick(sheet, CAPTION_HANDLER)

    // The helper, with fresh registers: v0 to v7 for the handler, v8 up for the row.
    val rowParameters = sheet.row.parameterTypes.map(CharSequence::toString)
    val helperParameters = listOf(context.type, STRING, LIST)
    val locals = maxOf(16, 9 + rowParameters.size)
    val helper = ImmutableMethod(
        builder.definingClass,
        CAPTION_ROW_HELPER,
        helperParameters.map { ImmutableMethodParameter(it, null, null) },
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(locals + helperParameters.size),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                move-object/from16 v0, p0
                if-eqz v0, :done
                iget-object v1, v0, ${context.field}
                move-object/from16 v2, p1
                invoke-static { v1, v2 }, $CAPTION_HANDLER->forVideo($CONTEXT$STRING)$CAPTION_HANDLER
                move-result-object v7
                if-eqz v7, :done
                check-cast v7, ${sheet.click}
                sget-object v6, ${icon.definingClass}->${icon.name}:${icon.type}
                invoke-static { v6 }, ${wrap.definingClass}->${wrap.name}(${wrap.parameterTypes.single()})${wrap.returnType}
                move-result-object v6
                invoke-static { }, $CAPTION_HANDLER->label()$STRING
                move-result-object v5
                new-instance v8, ${sheet.row.definingClass}
${rowArguments(rowParameters, sheet.click, wrap.returnType, CAPTION_ROW_ID)}
                invoke-direct/range { v8 .. v${8 + rowParameters.size} }, ${sheet.row.definingClass}-><init>(${rowParameters.joinToString("")})V
                move-object/from16 v0, p2
                invoke-interface { v0, v8 }, $LIST->add($OBJECT)Z
                :done
                return-void
            """,
        )
    }
    mutableClassDefBy(builder.definingClass).methods.add(helper)

    // First thing in the builder, before its own checks.
    builder.addInstructions(
        0,
        """
            move-object/from16 v0, ${builder.parameterRegister(context.parameter)}
            move-object/from16 v1, ${builder.parameterRegister(id)}
            move-object/from16 v2, ${builder.parameterRegister(list)}
            invoke-static/range { v0 .. v2 }, ${builder.definingClass}->$CAPTION_ROW_HELPER(${helperParameters.joinToString("")})V
        """,
    )
}

private fun Instruction.stringValue() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
