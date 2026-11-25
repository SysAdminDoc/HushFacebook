/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.posttext.findCaptionBuild
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * The hashtags in a reel's caption (#594). Read from 588, 2026-11-25, arm64; the obfuscated names here
 * are for reviewers, the code never writes one down.
 *
 * - A reel's caption, collapsed or open, is built in one static method (LX/09GM;->A02), the one the
 *   dark box behind an open caption hooks too (#419, ShowFullPostTextPatch.kt's findCaptionBuild).
 *   It makes a Litho text builder (LX/0458;), puts the caption into its CharSequence field (A0A)
 *   right after, then sets the line limit and builds it.
 * - So one helper right after that put hands the caption to the extension and puts back what it
 *   answers. The box's helper reads the field at the build, later, so a caption left with nothing
 *   gets no box either.
 */

internal const val CAPTION_TEXT = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->captionText(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;"

/** The helper the hashtag hook adds to the class of the method that builds a reel caption's text. */
internal const val HASHTAGS_HELPER = "hushfacebookCaptionHashtags"

private const val PATCH = "Clean up Reels"

/**
 * Right after the caption's builder gets the caption, a helper on the builder method's class reads
 * the builder's text field, asks [CAPTION_TEXT] for the caption to build and puts that back. The
 * call goes in after the put, so a jump that skips the put skips the call too, and it only reads
 * the builder's register, which holds the builder from its making to its build.
 */
internal fun BytecodePatchContext.hideCaptionHashtags() {
    val caption = try {
        findCaptionBuild()
    } catch (exception: PatchException) {
        throw PatchException("$PATCH: ${exception.message?.substringAfter(": ")}")
    }
    val host = caption.builder.definingClass
    val extension = classDefByOrNull("$EXTENSION_PACKAGE/reels/ReelDeclutter;")
        ?: throw PatchException("$PATCH: the extension has no ReelDeclutter")
    if (extension.methods.none { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == CAPTION_TEXT }) {
        throw PatchException("$PATCH: the extension has no $CAPTION_TEXT")
    }
    if (classDefByOrNull(host)!!.methods.any { it.name == HASHTAGS_HELPER }) {
        throw PatchException("$PATCH: $host already has $HASHTAGS_HELPER")
    }
    val text = "${caption.text.definingClass}->${caption.text.name}:${caption.text.type}"
    val helper = ImmutableMethod(
        host,
        HASHTAGS_HELPER,
        listOf(ImmutableMethodParameter(caption.builderType, null, null)),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(2),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                iget-object v0, p0, $text
                invoke-static { v0 }, $CAPTION_TEXT
                move-result-object v0
                iput-object v0, p0, $text
                return-void
            """,
        )
    }
    val owner = mutableClassDefBy(host)
    owner.methods.add(helper)
    owner.findMutableMethodOf(caption.builder).addInstructions(
        caption.textIndex + 1,
        "invoke-static/range { v${caption.register} .. v${caption.register} }, $host->$HASHTAGS_HELPER(${caption.builderType})V",
    )
}
