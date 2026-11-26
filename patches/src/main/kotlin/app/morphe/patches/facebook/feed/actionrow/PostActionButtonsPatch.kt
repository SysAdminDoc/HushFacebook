/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.actionrow

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.downloads.photo.SUBATTACHMENTS_FIELD
import app.morphe.patches.facebook.downloads.video.ATTACHED_STORY_FIELD
import app.morphe.patches.facebook.downloads.video.ATTACHMENTS_FIELD
import app.morphe.patches.facebook.downloads.video.GRAPHQL_MEDIA
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY_ATTACHMENT
import app.morphe.patches.facebook.downloads.video.IMMUTABLE_LIST
import app.morphe.patches.facebook.downloads.video.MEDIA_FIELD
import app.morphe.patches.facebook.downloads.video.graphQlGetter
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.executeBytecode
import app.morphe.util.namedRegisters
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val POST_SAVE_BUTTON = "$EXTENSION_PACKAGE/feed/PostSaveButton;"
internal const val POST_DOWNLOAD_BUTTON = "$EXTENSION_PACKAGE/download/PostDownloadButton;"

/** What the row's own Download item is tagged with, next to Facebook's "Save", "Share" and so on. */
internal const val DOWNLOAD_TAG = "HushfacebookDownload"

internal const val TYPES_HELPER = "hushfacebookActionTypes"
internal const val DOWNLOAD_HELPER = "hushfacebookDownloadButton"

@Suppress("unused")
val postActionButtonsPatch = patch(
    name = "Post action buttons",
    description = "Adds Facebook's own Save button next to Like, Comment and Share under posts, and a Download " +
        "button on posts with a video or photos that saves them the way the post's menu does. It can also take " +
        "Like, Comment, Share, Send, Save or the Stars and gifts button out of that row, and keep a tap on the " +
        "row's empty part from opening the comments. Each has its own switch, and they all start off. Find them in " +
        "Hushfacebook settings > News feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        requireStatusMethod("postActionButtons")
        val row = actionRow()
        hookTypes(row)
        hookDownload(row)
        ignoreRowTaps()
        enableStatus("postActionButtons")
    }
}

/**
 * Save and the hide switches: the list of the row's button types goes through the extension on its
 * way out, which drops the types of hidden buttons and adds SAVE when its switch is on. The row builder already has a Save case (the one under a post's
 * menu), so the button looks and acts exactly like Facebook's, filled bookmark included.
 */
internal fun BytecodePatchContext.hookTypes(row: ActionRow) {
    val helper = ImmutableMethod(
        row.owner,
        TYPES_HELPER,
        listOf(ImmutableMethodParameter(LIST, null, null)),
        LIST,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(2),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                sget-object v0, ${row.save}
                invoke-static { p0, v0 }, $POST_SAVE_BUTTON->types($LIST$OBJECT)$LIST
                move-result-object v0
                return-object v0
            """,
        )
    }
    mutableClassDefBy(row.owner).methods.add(helper)

    val types = mutableMethodOf(row.types)
    row.typeReturns().sortedByDescending { it.first }.forEach { (index, register) ->
        types.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, ${row.owner}->$TYPES_HELPER($LIST)$LIST
                move-result-object v$register
            """,
        )
    }
}

/**
 * Download: where the row's walk over its types ends, the extension is asked whether this post
 * holds videos or photos, and when it does, an item built like Save's (Facebook's Download icon,
 * the label as text and description, the extension as the click) joins the row's list. The list is
 * held above v15 (588's v16), so it's copied first into a low register nothing reads from there on.
 */
internal fun BytecodePatchContext.hookDownload(row: ActionRow) {
    val getters = listOf(
        graphQlGetter(GRAPHQL_STORY, IMMUTABLE_LIST, ATTACHMENTS_FIELD, "attachments"),
        graphQlGetter(GRAPHQL_STORY_ATTACHMENT, GRAPHQL_MEDIA, MEDIA_FIELD, "media"),
        graphQlGetter(GRAPHQL_STORY, GRAPHQL_STORY, ATTACHED_STORY_FIELD, "attached_story"),
        graphQlGetter(GRAPHQL_STORY_ATTACHMENT, IMMUTABLE_LIST, SUBATTACHMENTS_FIELD, "subattachments"),
    )
    // The hook is chosen on Facebook's own method, before anything goes in.
    val end = row.rowEnd { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    if (end.state > 15) refuse("${row.items.name} holds its state in v${end.state}")
    val call = "${row.owner}->$DOWNLOAD_HELPER($LIST${row.state})V"
    val hook = if (end.list <= 15) {
        "invoke-static { v${end.list}, v${end.state} }, $call"
    } else {
        val landing = row.items.code()[end.index].namedRegisters()
        val scratch = (0..15).firstOrNull { it != end.state && it !in landing && row.items.readsAfter(end.index, it).isEmpty() }
            ?: refuse("${row.items.name} holds its items in v${end.list} with no free low register to copy them to")
        """
            move-object/from16 v$scratch, v${end.list}
            invoke-static { v$scratch, v${end.state} }, $call
        """
    }
    mutableClassDefBy(row.owner).methods.add(downloadHelper(row, getters, end.pair))
    mutableMethodOf(row.items).addInstructionsAtControlFlowLabel(end.index, hook)
}

/**
 * `static void hushfacebookDownloadButton(List items, <state> state)`, adding the item when it
 * applies, as a [pair] like Facebook's own. A pair's first half is the type it was built for, which
 * only the split around Share compares; Download has none.
 */
private fun downloadHelper(row: ActionRow, getters: List<String>, pair: MethodReference): MutableMethod {
    val itemParameters = row.itemInit.parameters()
    val last = 16 + itemParameters.size
    val locals = last + 1

    // The text's arguments: the icon in its slot, the words last, null elsewhere.
    fun text(words: Int) = row.textInit.parameters().indices.joinToString(", ") {
        when (it) {
            row.textIcon -> "v11"
            row.textInit.parameters().lastIndex -> "v$words"
            else -> "v10"
        }
    }

    // The item's arguments: the label, the two texts in order, the tag, null elsewhere.
    var texts = 0
    val moves = itemParameters.mapIndexed { index, type ->
        val source = when (type) {
            row.labelInit.definingClass -> "v13"
            row.textInit.definingClass -> if (texts++ == 0) "v12" else "v9"
            row.tagInit.definingClass -> "v8"
            else -> "v10"
        }
        "move-object/from16 v${17 + index}, $source"
    }.joinToString("\n")

    return ImmutableMethod(
        row.owner,
        DOWNLOAD_HELPER,
        listOf(ImmutableMethodParameter(LIST, null, null), ImmutableMethodParameter(row.state, null, null)),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(locals + 2),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                move-object/from16 v14, p1
                move-object/from16 v15, p0
                iget-object v0, v14, ${row.statePost}
                if-eqz v0, :none
                iget-object v0, v0, ${row.postValue}
                if-eqz v0, :none
                iget-object v1, v14, ${row.stateContext}
                if-eqz v1, :none
                const-string v2, "${getters[0]}"
                const-string v3, "${getters[1]}"
                const-string v4, "${getters[2]}"
                const-string v5, "${getters[3]}"
                invoke-static/range { v0 .. v5 }, $POST_DOWNLOAD_BUTTON->click($OBJECT$CONTEXT$STRING$STRING$STRING$STRING)$POST_DOWNLOAD_BUTTON
                move-result-object v6
                if-eqz v6, :none
                invoke-virtual { v6 }, $POST_DOWNLOAD_BUTTON->label()$STRING
                move-result-object v7
                sget-object v8, ${row.download}
                sget-object v9, ${row.outline}
                const/4 v10, 0x0
                new-instance v11, ${row.iconInit.definingClass}
                invoke-direct { v11, v8, v9, v10 }, ${row.iconInit}
                new-instance v12, ${row.textInit.definingClass}
                invoke-direct { v12, ${text(7)} }, ${row.textInit}
                const-string v8, ""
                new-instance v9, ${row.textInit.definingClass}
                invoke-direct { v9, ${text(8)} }, ${row.textInit}
                new-instance v13, ${row.labelInit.definingClass}
                invoke-direct { v13, v7, v6 }, ${row.labelInit}
                const-string v7, "$DOWNLOAD_TAG"
                new-instance v8, ${row.tagInit.definingClass}
                invoke-direct { v8, v7 }, ${row.tagInit}
                new-instance v16, ${row.itemInit.definingClass}
                $moves
                invoke-direct/range { v16 .. v${last} }, ${row.itemInit}
                move-object/from16 v2, v16
                new-instance v3, ${pair.definingClass}
                invoke-direct { v3, v10, v2 }, $pair
                invoke-interface { v15, v3 }, $LIST->add($OBJECT)Z
                :none
                return-void
            """,
        )
    }
}

private fun BytecodePatchContext.mutableMethodOf(method: Method) =
    mutableClassDefBy(method.definingClass).methods.first {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }
