/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.carousel

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.addressed
import app.morphe.patches.facebook.feed.switchTarget
import app.morphe.patches.facebook.feed.tableIndices
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

internal const val PATCH = "Photo carousel"

/** The swipeable row a post's photos and videos show in, by the name Facebook's album layouts go by. */
internal const val CAROUSEL_PLUGIN = "com.facebook.feedplugins.attachments.album.MultiMediaAttachmentHScrollPlugin"

internal const val PHOTO_CAROUSEL = "Lapp/morphe/extension/facebook/feed/PhotoCarousel;"

/** The extension's answer to whether the row may show in this feed, handed Facebook's. */
internal const val LET_CAROUSEL_IN = "$PHOTO_CAROUSEL->carousel(I)Z"

/** The extension's answer to whether an album layout is passed over, handed its name. */
internal const val SKIP_LAYOUT = "$PHOTO_CAROUSEL->skipLayout(Ljava/lang/String;)Z"

/** The name Facebook gives the photo in the swipeable row, in that photo's render. */
internal const val CAROUSEL_PHOTO = "MultimediaHScrollPhotoComponent"

internal const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"

private const val CANVAS = "Landroid/graphics/Canvas;"

/** The extension's count over a row, handed each RecyclerView and its canvas once the row has drawn. */
internal const val DRAW_COUNT = "$PHOTO_CAROUSEL->drawCount(Landroid/view/ViewGroup;$CANVAS)V"

/**
 * The photo in the swipeable row: the render of the Litho component that names it, `LX/0cNH;` in
 * 585. A lambda and a name table hold the name too, and neither is a render.
 */
internal object CarouselPhotoFingerprint : Fingerprint(
    strings = listOf(CAROUSEL_PHOTO, "multi_media_photo"),
    custom = { method, _ -> method.name == "render" && method.parameterTypes.size == 1 },
)

/** [type] as Java names the class, `X.0cNH` for `LX/0cNH;`. */
internal fun javaName(type: String) = type.removePrefix("L").removeSuffix(";").replace('/', '.')

/** Whether [method] is RecyclerView's own draw(Canvas). */
internal fun isRowDraw(method: Method) =
    method.name == "draw" && method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(CANVAS) &&
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null

/**
 * Calls [DRAW_COUNT] last thing in androidx's RecyclerView.draw, before each return, so the count
 * goes over a row's photos and anything its decorations drew. Litho's row inherits that draw, and a
 * scroll draws it again. The names are Android's and androidx's, so Redex keeps them.
 */
internal fun BytecodePatchContext.hookRowDraw() {
    val recycler = mutableClassDefByOrNull(RECYCLER_VIEW) ?: throw PatchException("$PATCH: this build has no $RECYCLER_VIEW")
    val draws = recycler.methods.filter(::isRowDraw)
    val draw = draws.singleOrNull()
        ?: throw PatchException("$PATCH: expected $RECYCLER_VIEW to declare one draw($CANVAS)V, found ${draws.size}")
    val code = draw.implementation!!.instructions.toList()
    // The row and the canvas are p0 and p1, next to each other, so the range form passes both
    // without borrowing a register, as long as the method never reuses either.
    val self = draw.implementation!!.registerCount - 2
    if (code.any { writesRegister(it, self) || writesRegister(it, self + 1) }) {
        throw PatchException("$PATCH: $RECYCLER_VIEW's draw writes its row or canvas register")
    }
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    if (returns.isEmpty()) throw PatchException("$PATCH: $RECYCLER_VIEW's draw never returns")
    // At the label, so a branch to the return draws the count too. Last first, as each insert
    // moves the later indices.
    returns.asReversed().forEach { draw.addInstructionsAtControlFlowLabel(it, "invoke-static/range { p0 .. p1 }, $DRAW_COUNT") }
}

/** Fills in PhotoCarousel.photoClass with the class name of the row's photo. */
internal fun BytecodePatchContext.fillPhotoClass(photo: String) {
    val stub = mutableClassDefBy(PHOTO_CAROUSEL).methods.singleOrNull {
        it.name == "photoClass" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("$PATCH: PhotoCarousel has no photoClass() for the photo count")
    stub.returnEarly(javaName(photo))
}

/**
 * The name table of Facebook's album layouts: a static (int) method answering each layout's class
 * name, `LX/04Xj;->A0E` in 585. The class beside it holds the check of whether each one applies.
 */
internal object AlbumLayoutNamesFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("I"),
    strings = listOf(CAROUSEL_PLUGIN),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * Facebook's album layouts, as this patch hooks them: the name table, the check of whether a layout
 * applies to a post (a static (props, int) boolean that switches on the layout), and in that check,
 * the index of the move-result taking the answer of the swipeable row's feed check.
 */
internal class AlbumLayouts(val names: Method, val applies: Method, val feedAnswer: Int) {
    /** The register the feed check's answer is in. */
    val register: Int get() = (applies.implementation!!.instructions.elementAt(feedAnswer) as OneRegisterInstruction).registerA
}

private fun ReferenceInstruction.method() = reference as? MethodReference

/**
 * Whether [instruction] is the swipeable row's feed check: a static call answering a boolean about
 * one Facebook object, which is how 585 asks whether the row is on in this feed. The calls before
 * it in the row's case take an Object or none, or aren't static.
 */
private fun isFeedCheck(instruction: Instruction): Boolean {
    if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val call = (instruction as ReferenceInstruction).method() ?: return false
    val parameters = call.parameterTypes.map(CharSequence::toString)
    return call.returnType == "Z" && parameters.size == 1 && parameters[0].startsWith("L") && parameters[0] != "Ljava/lang/Object;"
}

/**
 * Reads [holder]'s album layouts around [names]. Throws when the check of whether a layout applies
 * isn't the one static (props, int) boolean with a switch, when the row has no case in it, or when
 * the row's case doesn't end in a static boolean feed check whose answer is tested and returned.
 */
internal fun albumLayouts(names: Method, holder: ClassDef): AlbumLayouts {
    val applies = holder.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
            method.parameterTypes.map(CharSequence::toString).let { it.size == 2 && it[0].startsWith("L") && it[1] == "I" } &&
            method.implementation?.instructions?.any { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH } == true
    }.singleOrNull() ?: throw PatchException("$PATCH: ${holder.type} has no single static (props, int) boolean with a switch")

    val key = tableIndices(names, CAROUSEL_PLUGIN).singleOrNull()
        ?: throw PatchException("$PATCH: ${names.definingClass}->${names.name} doesn't name the swipeable row once")
    val byAddress = addressed(applies)
    val target = switchTarget(byAddress, key)
        ?: throw PatchException("$PATCH: ${applies.definingClass}->${applies.name} has no case for the swipeable row ($key)")
    val code = applies.implementation!!.instructions.toList()
    val start = byAddress.keys.indexOf(target)
    val end = (start until code.size).firstOrNull {
        code[it].opcode == Opcode.RETURN || code[it].opcode == Opcode.RETURN_OBJECT || code[it].opcode == Opcode.RETURN_VOID
    } ?: throw PatchException("$PATCH: the swipeable row's case doesn't return")
    val check = (start until end).lastOrNull { isFeedCheck(code[it]) }
        ?: throw PatchException("$PATCH: the swipeable row's case asks no feed check")
    val answer = code.getOrNull(check + 1) as? OneRegisterInstruction
    val test = code.getOrNull(check + 2) as? OneRegisterInstruction
    if (answer == null || code[check + 1].opcode != Opcode.MOVE_RESULT ||
        test == null || code[check + 2].opcode != Opcode.IF_EQZ || test.registerA != answer.registerA
    ) {
        throw PatchException("$PATCH: the feed check's answer in ${applies.definingClass}->${applies.name} isn't tested straight away")
    }
    return AlbumLayouts(names, applies, check + 1)
}

/**
 * Photo carousel (#345). Facebook picks how a post with several photos or videos looks by asking
 * its album layouts in turn whether each applies, and takes the first that does. The swipeable row
 * applies only in the feeds and tests Facebook turns it on for, and three layouts a poster can
 * choose are asked before it. The row's feed check gets the extension's answer, and the check of
 * whether a layout applies starts by asking the extension whether to pass the layout over.
 */
@Suppress("unused")
val photoCarouselPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Photo carousel",
    description = "Shows a post's photos and videos in a row you swipe through, one at a time with a 4/10 count and dots, " +
        "instead of a grid. " +
        "Starts off. Turn it on in Hushfacebook settings > News feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val names = AlbumLayoutNamesFingerprint.method
        val layouts = albumLayouts(names, classDefBy(names.definingClass))
        val applies = mutableClassDefBy(names.definingClass).methods.single {
            MethodUtil.methodSignaturesMatch(it, layouts.applies)
        }
        val register = layouts.register

        // Later in the method first, so the index read above still holds.
        applies.addInstructions(
            layouts.feedAnswer + 1,
            """
                invoke-static/range { v$register .. v$register }, $LET_CAROUSEL_IN
                move-result v$register
            """,
        )

        applies.requireLocals(PATCH, 1)
        val layout = applies.parameterRegisterNumber(1)
        applies.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { v$layout .. v$layout }, ${names.definingClass}->${names.name}(I)Ljava/lang/String;
                move-result-object v0
                invoke-static/range { v0 .. v0 }, $SKIP_LAYOUT
                move-result v0
                if-eqz v0, :facebook
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("facebook", applies.getInstruction(0)),
        )

        fillPhotoClass(CarouselPhotoFingerprint.method.definingClass)
        hookRowDraw()
        enableStatus("photoCarousel")
    }
}
