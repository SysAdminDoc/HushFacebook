/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.ads.sponsoredsearch.enumConstantFields
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Block button on comments (#271), read from 585 on 2026-10-31. The obfuscated names are for
 * reviewers; the code finds every spot by a kept name, a kept string or a shape.
 *
 * The thumbs-down under a comment is a component whose class the dislike plugin's builder
 * ([COMMENT_DOWNVOTE]) returns (585 arm64 LX/09zR). Its `render` draws an icon constant of an icon
 * enum, HAND_THUMBS_DOWN, and builds a label-and-click pair with `<init>(String, Function1)`.
 * Its click is a case of a shared lambda class, the static method of two objects that loads
 * "CommentsDownvotePluginKComponent.undoReactionThenDownvote" (LX/0azF;->A06). The profile Block
 * row's own helper is the one instance method that loads "FBBlockProfileBottomsheetGraphQLQuery"
 * (LX/0Nuv;->A01): it takes (Context, source enum, session, profile interface, long, long, boolean)
 * and opens Facebook's block sheet, which asks before it blocks.
 *
 * Three hooks, all answering to one switch in the extension: the icon becomes FRIEND_BLOCK, the
 * label goes through the extension, and the click asks the extension first. The extension builds a
 * stand-in profile, so the helper is never handed anything but a name, and the confirmation sheet
 * stays Facebook's.
 *
 * A fourth hook only reads: the block sheet's failure handler, the one method loading
 * "timeline_block_user_failed" (LX/0ONh;->Hxd(Throwable)), hands its throwable to the extension
 * first, so a sheet that fails after a tap here leaves its reason in the report. Facebook's
 * "There was a problem blocking this user" toast is all the screen says, and its own reports
 * never reach the log.
 */

private const val PATCH = "Comment sheet options (block button)"
private const val COMMENT_BLOCK_CLASS = "$EXTENSION_PACKAGE/comments/CommentBlock;"
private const val BLOCK_ICON_CALL = "$COMMENT_BLOCK_CLASS->blockIcon()Z"
private const val BLOCK_LABEL_CALL = "$COMMENT_BLOCK_CLASS->blockLabel(Ljava/lang/String;)Ljava/lang/String;"
private const val BLOCK_TAP_CALL =
    "$COMMENT_BLOCK_CLASS->tap(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/Class;" +
        "Ljava/lang/Class;Ljava/lang/Class;)Z"
private const val BLOCK_FAILED_CALL = "$COMMENT_BLOCK_CLASS->sheetFailed(Ljava/lang/Throwable;)V"

/** The string only the block sheet's failure handler loads. */
internal const val BLOCK_FAILED = "timeline_block_user_failed"

/** The string the thumbs-down's click loads, once. */
internal const val DOWNVOTE_CLICK = "CommentsDownvotePluginKComponent.undoReactionThenDownvote"

/** The string only the profile Block helper loads. */
internal const val BLOCK_SHEET_QUERY = "FBBlockProfileBottomsheetGraphQLQuery"

private const val THUMBS_DOWN = "HAND_THUMBS_DOWN"
private const val BLOCK_ICON = "FRIEND_BLOCK"
private const val PROFILE_SOURCE = "PROFILE"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Where the four hooks go, and the classes the extension is handed. */
internal class CommentBlockSites(
    val render: Method,
    val iconAt: Int,
    val iconRegister: Int,
    val scratchRegister: Int,
    val blockIcon: String,
    val labelAt: Int,
    val labelRegister: Int,
    val click: Method,
    val unit: String,
    val component: String,
    val helper: String,
    val profile: String,
    val source: String,
    val failure: Method?,
)

/**
 * Everything the block button needs, from [builder] (the dislike plugin's builder), [clicks] (the
 * methods loading [DOWNVOTE_CLICK]), [helpers] (the methods loading [BLOCK_SHEET_QUERY]),
 * [failures] (the methods loading [BLOCK_FAILED]) and [classOf]. Refuses unless each part of the
 * button is found exactly once; the failure handler only reads, so without a single one it's left
 * out.
 */
internal fun commentBlockSites(
    builder: Method,
    clicks: List<Method>,
    helpers: List<Method>,
    failures: List<Method>,
    classOf: (String) -> ClassDef?,
): CommentBlockSites {
    val component = classOf(builder.returnType) ?: refuse("no class ${builder.returnType}")
    val render = component.methods.singleOrNull { it.name == "render" && it.implementation != null }
        ?: refuse("${component.type} has no single render")
    val code = render.implementation!!.instructions.toList()

    // The icon: the sget-object of the icon enum's thumbs-down constant.
    val iconMatches = code.withIndex().filter { (_, instruction) ->
        if (instruction.opcode != Opcode.SGET_OBJECT) return@filter false
        val field = (instruction as ReferenceInstruction).reference as? FieldReference ?: return@filter false
        if (field.definingClass != field.type) return@filter false
        val enum = classOf(field.definingClass) ?: return@filter false
        enumConstantFields(enum)[field.name] == THUMBS_DOWN
    }
    val icon = iconMatches.singleOrNull() ?: refuse("expected one $THUMBS_DOWN in ${render.name}, found ${iconMatches.size}")
    val iconField = (icon.value as ReferenceInstruction).reference as FieldReference
    val iconEnum = classOf(iconField.definingClass)!!
    val blockName = enumConstantFields(iconEnum).entries.singleOrNull { it.value == BLOCK_ICON }?.key
        ?: refuse("${iconEnum.type} has no single $BLOCK_ICON")
    val next = code.getOrNull(icon.index + 1)
    if (next == null || next.opcode != Opcode.SGET_OBJECT) refuse("$THUMBS_DOWN isn't followed by a load that frees a register")
    val scratch = (next as OneRegisterInstruction).registerA
    val iconRegister = (icon.value as OneRegisterInstruction).registerA
    if (scratch == iconRegister || scratch > 255 || iconRegister > 255) refuse("no free register at $THUMBS_DOWN")

    // The label: the (String, Function1) pair the button builds.
    val labels = code.withIndex().filter { (_, instruction) ->
        if (instruction.opcode != Opcode.INVOKE_DIRECT) return@filter false
        val call = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@filter false
        call.name == "<init>" && call.parameterTypes.map { it.toString() } ==
            listOf("Ljava/lang/String;", "Lkotlin/jvm/functions/Function1;")
    }
    val label = labels.singleOrNull() ?: refuse("expected one label and click pair in ${render.name}, found ${labels.size}")
    val labelRegister = (label.value as? FiveRegisterInstruction)?.registerD ?: refuse("the label call isn't a five-register call")

    // The click.
    val click = clicks.singleOrNull {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
    } ?: refuse("expected one thumbs-down click, found ${clicks.size}")
    if (click.localRegisterCount() < 6) refuse("${click.name} has no registers for the hook")
    val clickCode = click.implementation!!.instructions.toList()
    val ret = clickCode.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
    val unitLoad = clickCode.getOrNull(ret - 1)
    if (ret < 1 || unitLoad?.opcode != Opcode.SGET_OBJECT ||
        (unitLoad as OneRegisterInstruction).registerA != (clickCode[ret] as OneRegisterInstruction).registerA
    ) {
        refuse("${click.name} doesn't end by returning a loaded constant")
    }
    val unit = ((unitLoad as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" }

    // The block helper.
    val helper = helpers.singleOrNull {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.size == 7 &&
            it.parameterTypes[0].toString() == "Landroid/content/Context;" &&
            it.parameterTypes[2].toString() == "Lcom/facebook/auth/usersession/FbUserSession;" &&
            it.parameterTypes.drop(4).map { type -> type.toString() } == listOf("J", "J", "Z")
    } ?: refuse("expected one block helper, found ${helpers.size}")
    val sourceType = helper.parameterTypes[1].toString()
    val source = classOf(sourceType) ?: refuse("no class $sourceType")
    if (source.superclass != "Ljava/lang/Enum;" || PROFILE_SOURCE !in enumConstantFields(source).values) {
        refuse("$sourceType isn't an enum with $PROFILE_SOURCE")
    }
    val profileType = helper.parameterTypes[3].toString()
    val profile = classOf(profileType) ?: refuse("no class $profileType")
    if (!AccessFlags.INTERFACE.isSet(profile.accessFlags)) refuse("$profileType isn't an interface")
    if (helper.definingClass == click.definingClass) refuse("the helper and the click are in one class")

    // The sheet's failure handler: an instance (Throwable)V method.
    val failure = failures.singleOrNull {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Throwable;")
    }

    return CommentBlockSites(
        render, icon.index, iconRegister, scratch, "${iconField.definingClass}->$blockName:${iconField.type}",
        label.index, labelRegister, click, unit, component.type, helper.definingClass, profileType, sourceType,
        failure,
    )
}

/**
 * The block button's hooks go in. Nameless, so Comment sheet options carries it and its
 * fixture tests run it on its own. Everything is found before anything changes.
 */
internal val commentBlockPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        val sites = commentBlockSites(
            dislikeBuilder(classDefByOrNull(COMMENT_DOWNVOTE)),
            blockHolders(DOWNVOTE_CLICK).flatMap { holder -> holder.methods.filter { holdsString(it, DOWNVOTE_CLICK) } },
            blockHolders(BLOCK_SHEET_QUERY).flatMap { holder -> holder.methods.filter { holdsString(it, BLOCK_SHEET_QUERY) } },
            blockHolders(BLOCK_FAILED).flatMap { holder -> holder.methods.filter { holdsString(it, BLOCK_FAILED) } },
        ) { type -> classDefByOrNull(type) }
        hookCommentBlock(sites) { type -> mutableClassDefBy(type) }
    }
}

private fun BytecodePatchContext.blockHolders(string: String) =
    classDefByStrings(string, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

/** Puts the hooks into [sites]' methods, [mutable] reading the class to change. */
internal fun hookCommentBlock(sites: CommentBlockSites, mutable: (String) -> ClassDef) {
    val render = mutable(sites.render.definingClass).methods.single { it.descriptor() == sites.render.descriptor() } as MutableMethod
    // Later spot first, so the earlier index stays where it was.
    render.addInstructions(
        sites.labelAt,
        """
            invoke-static {v${sites.labelRegister}}, $BLOCK_LABEL_CALL
            move-result-object v${sites.labelRegister}
        """,
    )
    val after = sites.iconAt + 1
    render.addInstructionsWithLabels(
        after,
        """
            invoke-static {}, $BLOCK_ICON_CALL
            move-result v${sites.scratchRegister}
            if-eqz v${sites.scratchRegister}, :keep
            sget-object v${sites.iconRegister}, ${sites.blockIcon}
        """,
        ExternalLabel("keep", render.getInstruction(after)),
    )
    val click = mutable(sites.click.definingClass).methods.single { it.descriptor() == sites.click.descriptor() } as MutableMethod
    click.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p1
            const-class v2, ${sites.component}
            const-class v3, ${sites.helper}
            const-class v4, ${sites.profile}
            const-class v5, ${sites.source}
            invoke-static/range {v0 .. v5}, $BLOCK_TAP_CALL
            move-result v0
            if-eqz v0, :tap
            sget-object v0, ${sites.unit}
            return-object v0
        """,
        ExternalLabel("tap", click.getInstruction(0)),
    )
    val failure = sites.failure ?: return
    val handler = mutable(failure.definingClass).methods.single { it.descriptor() == failure.descriptor() } as MutableMethod
    handler.addInstructions(0, "invoke-static/range {p1 .. p1}, $BLOCK_FAILED_CALL")
}

