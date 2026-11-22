/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.upload

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Where a Story's upload gets its quality, read from 582 (#123).
 *
 * The publisher's getUploadOperationForCreate (a Kotlin internal name, so it keeps its module
 * suffix and survives the obfuscator) builds the UploadOperation for a new post. When the post
 * goes to a Story and not to a feed, it walks the post's MediaPostParams and gives each a Bundle
 * of upload settings, among them the media's upload quality under "video_upload_quality":
 *
 *     const-string vKey, "video_upload_quality"
 *     iget-object vValue, vMedia, MediaPostParam->...:String
 *     invoke-virtual {vBundle, vKey, vValue}, BaseBundle->putString(String, String)
 *
 * Facebook's video uploader reads that back, takes high, low, medium, standard or raw, and keeps
 * only high when it asks the upload server how to encode the video (the session's "quality").
 * Everything else, its default "standard" too, goes as no hint. The hook hands the value to the
 * extension right before the putString, which answers high while the switch is on.
 */

internal const val STORY_UPLOAD_PATCH = "Upload Stories in HD"

internal const val VIDEO_UPLOAD_QUALITY = "video_upload_quality"
internal const val UPLOAD_FOR_CREATE = "getUploadOperationForCreate"
internal const val UPLOAD_OPERATION = "Lcom/facebook/photos/upload/operation/UploadOperation;"
internal const val PUBLISH_POST_PARAMS = "Lcom/facebook/composer/publish/api/model/PublishPostParams;"
internal const val STORY_DESTINATION = "Lcom/facebook/composer/publish/api/model/StoryDestinationParams;"
internal const val PUT_STRING = "Landroid/os/BaseBundle;->putString(Ljava/lang/String;Ljava/lang/String;)V"
internal const val QUALITY_CALL = "$EXTENSION_PACKAGE/stories/StoryUpload;->quality(Ljava/lang/String;)Ljava/lang/String;"

/** How far after the key's load its putString may be: the value's own load sits between them. */
private const val PUT_REACH = 4

private fun refuse(detail: String): Nothing = throw PatchException("$STORY_UPLOAD_PATCH: $detail")

/** The publisher's method that builds a new post's upload, by its kept name and shape. */
internal fun isUploadForCreate(method: Method) =
    method.name.startsWith(UPLOAD_FOR_CREATE) && method.returnType == UPLOAD_OPERATION &&
        method.parameterTypes.lastOrNull()?.toString() == PUBLISH_POST_PARAMS && method.implementation != null

/** The putString of a Story's upload quality: its index and the register holding the value. */
internal class QualityPut(val method: Method, val put: Int, val value: Int)

private fun Method.code() = implementation?.instructions?.toList() ?: refuse("$name has no code")

/** Whether this method reads which Story a post goes to, the condition the quality loop runs under. */
internal fun Method.readsStoryDestination() = code().any {
    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.type == STORY_DESTINATION
}

/**
 * The one place [this] puts "video_upload_quality" into a Bundle, or a refusal naming what moved.
 * The value's register must be safe to overwrite: nothing reads it after the put before writing
 * it again, and nothing jumps straight onto the put past the hook.
 */
internal fun Method.qualityPut(): QualityPut {
    val code = code()
    val sites = code.indices.mapNotNull { at ->
        val load = code[at]
        if (load.opcode != Opcode.CONST_STRING && load.opcode != Opcode.CONST_STRING_JUMBO) return@mapNotNull null
        if (((load as ReferenceInstruction).reference as StringReference).string != VIDEO_UPLOAD_QUALITY) return@mapNotNull null
        val key = (load as OneRegisterInstruction).registerA
        val put = (at + 1..minOf(at + PUT_REACH, code.size - 1)).firstOrNull { index ->
            val call = code[index]
            call.opcode == Opcode.INVOKE_VIRTUAL && ((call as ReferenceInstruction).reference as MethodReference).toString() == PUT_STRING &&
                (call as FiveRegisterInstruction).registerD == key
        } ?: return@mapNotNull null
        // Straight-line from the key's load to the put: no branch out between them.
        if ((at + 1 until put).any { code[it] is OffsetInstruction }) return@mapNotNull null
        put
    }
    val put = sites.singleOrNull() ?: refuse("expected one putString of \"$VIDEO_UPLOAD_QUALITY\" in $name, found ${sites.size}")
    val value = (code[put] as FiveRegisterInstruction).registerE
    if (put in branchTargets(code)) refuse("the putString in $name is a jump target")
    if (readsAfter(put, value).isNotEmpty()) refuse("$name reads the upload quality again after the putString")
    return QualityPut(this, put, value)
}

/** The publisher's quality put, found before anything changes. */
internal fun BytecodePatchContext.findQualityPut(): QualityPut {
    val methods = classDefByStrings(VIDEO_UPLOAD_QUALITY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { owner -> owner.methods.filter(::isUploadForCreate) }
    val method = methods.singleOrNull()
        ?: refuse("expected one $UPLOAD_FOR_CREATE holding \"$VIDEO_UPLOAD_QUALITY\", found ${methods.size}")
    if (!method.readsStoryDestination()) refuse("${method.definingClass}->${method.name} no longer reads a Story destination")
    return method.qualityPut()
}

/** Hands the value to the extension right before the putString, and puts its answer in the value's register. */
internal fun MutableMethod.askBeforeQualityPut(put: QualityPut) {
    addInstructions(
        put.put,
        """
            invoke-static/range { v${put.value} .. v${put.value} }, $QUALITY_CALL
            move-result-object v${put.value}
        """,
    )
}

/**
 * Story videos ask the upload server for high quality. See the notes above for where, and the
 * extension's StoryUpload for when.
 *
 * In Morphe Manager's default selection with the switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val uploadStoriesInHdPatch = patch(
    name = "Upload Stories in HD",
    description = "Videos you post to your Story ask Facebook for its high upload quality instead of standard, so " +
        "they can come out sharper. Starts off. Turn it on in Hushfacebook settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        requireStatusMethod("storyUploadHd")
        val put = findQualityPut()
        mutableClassDefBy(put.method.definingClass).findMutableMethodOf(put.method).askBeforeQualityPut(put)
        enableStatus("storyUploadHd")
    }
}
