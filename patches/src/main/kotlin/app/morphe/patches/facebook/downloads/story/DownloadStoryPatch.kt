/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/downloadstory/DownloadStoryPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.downloads.story

import app.morphe.patches.facebook.downloads.photo.fillCdnResize
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.shared.reportedFieldNames
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import app.morphe.patches.facebook.misc.settings.settingsPatch

/**
 * The extension call that saves the story, or the photo it was made from when the item tapped is
 * Save photo. It answers whether it took the job.
 */
private const val SAVE_STORY = "Lapp/morphe/extension/facebook/download/MediaDownload;->" +
    "saveStory(Landroid/content/Context;Ljava/lang/Object;Ljava/lang/Object;)Z"

/** The extension call that titles Save photo for a story card, or answers null when the menu gets no such item. */
private const val PHOTO_TITLE = "Lapp/morphe/extension/facebook/download/StoryPhoto;->" +
    "menuTitle(Ljava/lang/Object;)Ljava/lang/String;"

/** The extension call that remembers a Save photo item, so a tap on it saves the photo. */
private const val PHOTO_OFFERED = "Lapp/morphe/extension/facebook/download/StoryPhoto;->offered(Ljava/lang/Object;)V"

private const val SAVE_TITLE = "Lapp/morphe/extension/facebook/download/StoryPhoto;->" +
    "saveTitle(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;"
private const val SAVE_TITLE_HELPER = "hushfacebookStorySaveTitle"

/** The extension call that answers the menu's "can this story be saved": the switch, or Facebook's own. */
private const val OFFERS_SAVE = "Lapp/morphe/extension/facebook/download/MediaDownload;->offersSave(Z)Z"

/** The extension call that records the source of each player that the app builds. */
private const val REMEMBER_SOURCE = "Lapp/morphe/extension/facebook/download/PlayerSources;->" +
    "remember(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"

/** The patch's name, for its warnings. The README table check reads the literal in the patch below. */
internal const val STORY_PATCH = "Download any story"

/** What a story's Save photo does when Facebook's photo address modifier isn't found, for the patch log. */
internal const val STORY_WITHOUT_CDN = "a story's Save photo saves the photo at the size the story holds"

/** The helper that this patch adds to the player params. Each constructor then gets one call. */
private const val REMEMBER_HELPER = "hushfacebookRememberSource"

@Suppress("unused")
val downloadStoryPatch = bytecodePatch(
    name = "Download any story",
    description = "Adds Save to the menu of any story, including stories with music, so you can keep the ones " +
        "you like. Videos save at the quality you set, best by default. On by default. Turn it off in " +
        "Hushfacebook settings > Stories.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    dependsOn(facebookExtensionPatch)

    // The patch has two halves, because Facebook limits this feature twice.
    //
    // The first limit decides whether the save item appears at all. The "More" menu of the story
    // viewer asks one capability question before it offers the item, and that question means "is
    // this story mine". Everything after it is unconditional, and the surface that the menu reads
    // next only decides which label the item gets. Answering yes to that one question shows the
    // item. The answer is the Save any story switch's, so off or paused only your own stories
    // offer it, as unpatched.
    //
    // The second limit decides whether tapping the item does anything, and it is the reason this
    // patch no longer uses Facebook's save code. That code checks the story for licensed music
    // first. On a story that has any it shows a copyright warning and then saves nothing, whatever
    // the user answers, and not even a copy without the sound (issue #110). The check is the first
    // thing the handler does, so there is nothing to route around. The handler runs a download of
    // our own instead.
    //
    // Both halves stay because they are different limits. The first still has to be answered, or
    // there is no item to tap.
    execute {
        // The class of the action that the menu creates. The patch finds it through the event that
        // the action reports, because Redex gives the class a new name on every Facebook release.
        val saveAction = run {
            val tables = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
            val matches = typesCreated(classDefBy(STORY_VIEWER_MORE_MENU)).mapNotNull(::classDefByOrNull)
                .flatMap { owner -> owner.methods.filter { isSaveStoryAction(it, tables) } }

            check(matches.size == 1) {
                "Expected 1 save-story action among the types the story menu creates, found ${matches.size}: " +
                    matches.joinToString { "${it.definingClass}->${it.name}" }
            }

            matches.single().definingClass
        }

        val menuBuilders = mutableClassDefBy(STORY_VIEWER_MORE_MENU).methods.filter { method ->
            method.instructionsOrEmpty().any {
                it.opcode == Opcode.NEW_INSTANCE && it.typeReferenceOrNull() == saveAction
            }
        }

        check(menuBuilders.size == 1) {
            "Expected 1 menu builder creating $saveAction, found ${menuBuilders.size}"
        }

        val builder = menuBuilders.single()
        val instructions = builder.instructionsOrEmpty()

        // The capability is the only call in the builder that takes nothing and answers a boolean.
        // Boolean.booleanValue is the one exception, and it is a framework call. The builder caches
        // the answer in a field and reads it back later. Thus one forced answer settles both reads.
        val capabilityCalls = instructions.withIndex().filter { (_, instruction) ->
            val reference = instruction.methodReferenceOrNull()

            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                reference != null &&
                reference.returnType == "Z" &&
                reference.parameterTypes.isEmpty() &&
                !reference.definingClass.startsWith("Ljava/") &&
                !reference.definingClass.startsWith("Landroid/")
        }

        check(capabilityCalls.size == 1) {
            "Expected 1 save capability check in ${builder.name}, found ${capabilityCalls.size}: " +
                capabilityCalls.joinToString { (_, it) -> "${it.methodReferenceOrNull()?.name}" }
        }

        builder.answerWithTheSwitch(instructions, capabilityCalls.single().index)

        // Second half. The item now appears. This half decides what a tap on it does.
        val action = mutableClassDefBy(saveAction)

        // Both fields are picked by their type. The action holds one context and one story, so
        // the type says which is which and no Redex name is written down.
        fun fieldNamed(type: String): String {
            val matches = action.fields.filter { it.type.toString() == type }

            check(matches.size == 1) {
                "Expected 1 field of $type on $saveAction, found ${matches.size}"
            }

            return matches.single().name
        }

        val contextField = fieldNamed("Landroid/content/Context;")
        val storyCardField = fieldNamed(STORY_CARD)

        // The action exists to be one entry of one menu, so besides its constructor it carries
        // only the method that runs on a tap. Its shape identifies it without its name.
        val handlers = action.methods.filter {
            it.name != "<init>" && it.returnType == "V" && it.parameterTypes.size == 1
        }

        check(handlers.size == 1) {
            "Expected 1 tap handler on $saveAction, found ${handlers.size}: " +
                handlers.joinToString { it.name }
        }

        val handler = handlers.single()
        val implementation = handler.implementation

        check(implementation != null) { "$saveAction->${handler.name} has no body" }

        // v0 and v1 are locals of the handler. Every instruction that reads them now sits after
        // a return that always fires, so borrowing them cannot disturb anything that still runs.
        val parameterRegisters = 1 + handler.parameterTypes.sumOf {
            if (it == "J" || it == "D") 2 else 1
        }

        check(implementation.registerCount - parameterRegisters >= 2) {
            "$saveAction->${handler.name} has too few registers to borrow two"
        }

        // The tapped item, the handler's one parameter, goes to the save too, so Save photo saves
        // the photo. invoke-static names its arguments in four bits.
        check(implementation.registerCount - parameterRegisters + 1 < 16) {
            "$saveAction->${handler.name}: the tapped item's register is out of four-bit range"
        }

        // The download answers whether it took the job. It can decline: an old Android, no
        // address on the story, or too many saves at once. Then the original body still runs, so
        // the patch never leaves the item doing nothing at all.
        //
        // The label binds to a real instruction. A label written inside an injected block is
        // resolved against the addresses of the block, and it branches into the middle of an
        // earlier instruction.
        handler.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, $saveAction->$contextField:Landroid/content/Context;
                iget-object v1, p0, $saveAction->$storyCardField:$STORY_CARD
                invoke-static { v0, v1, p1 }, $SAVE_STORY
                move-result v0
                if-eqz v0, :original
                return-void
            """,
            ExternalLabel("original", handler.getInstruction(0)),
        )

        action.methods.add(addStorySaveTitle(builder, action, storyCardField))
        addSavePhotoItem(builder, action, storyCardField)

        // #38: Save photo on a story asks Meta's CDN for the photo at its full size, as Save photo
        // on a post does. Download any photo fills the same stub when it's in; whichever runs
        // second leaves it. Its warnings name this patch, since it's this one asking.
        fillCdnResize(STORY_PATCH, STORY_WITHOUT_CDN)

        rememberPlayerSources()

        enableStatus("storyDownload")
    }
}

/**
 * Record the source of each player that the app builds, by its video id.
 *
 * The story card holds one video address, and it is 360p. The player of the same story holds a
 * DASH manifest with tracks up to 1080p. The save action cannot get to the player. But the card
 * holds the video id of the player, so the save finds the source by that id.
 *
 * The id is the key because the app builds the next players early. A record of the last player
 * built then holds a different video from the one on the screen.
 *
 * The field names come from the debug dumps of the two classes. No Redex name is in this patch.
 *
 * The video patch records through the same code under its own [helperName] and [recorder], so
 * each call asks its own patch's switch. Both in, each constructor calls the two helpers in turn.
 */
internal fun BytecodePatchContext.rememberPlayerSources(
    helperName: String = REMEMBER_HELPER,
    recorder: String = REMEMBER_SOURCE,
) {
    val sourceNames = reportedFieldNames(VIDEO_DATA_SOURCE, marker = "abrManifestContent")
    val paramNames = reportedFieldNames(VIDEO_PLAYER_PARAMS, marker = "videoId")

    val videoId = paramNames["videoId"]
    val hd = sourceNames["videoHdUri"]
    val manifest = sourceNames["abrManifestContent"]

    check(videoId != null && hd != null && manifest != null) {
        "Unresolved field names: source=${sourceNames.keys} params=${paramNames.keys}"
    }

    val params = mutableClassDefBy(VIDEO_PLAYER_PARAMS)

    // A new method has its own registers. A constructor then needs only one range call, and a
    // range call can read `p0` at any register number.
    val helper = ImmutableMethod(
        VIDEO_PLAYER_PARAMS,
        helperName,
        listOf(ImmutableMethodParameter(VIDEO_PLAYER_PARAMS, null, null)),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(4),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                const-string v0, "$videoId"
                const-string v1, "$hd"
                const-string v2, "$manifest"
                invoke-static { p0, v0, v1, v2 }, $recorder
                return-void
            """,
        )
    }

    params.methods.add(helper)

    // Each constructor, at each return. The call replaces the return, and a new return comes after
    // it. A branch to the old return thus goes to the call, so no exit skips the record.
    val constructors = params.methods.filter { it.name == "<init>" }
    var hooked = 0

    constructors.forEach { constructor ->
        val returns = constructor.instructionsOrEmpty().withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }
            .reversed()

        returns.forEach { index ->
            constructor.replaceInstruction(
                index,
                "invoke-static/range { p0 .. p0 }, " +
                    "$VIDEO_PLAYER_PARAMS->$helperName($VIDEO_PLAYER_PARAMS)V",
            )
            constructor.addInstruction(index + 1, "return-void")
            hooked++
        }
    }

    check(hooked >= constructors.size && constructors.isNotEmpty()) {
        "Hooked $hooked return(s) across ${constructors.size} constructor(s) of $VIDEO_PLAYER_PARAMS"
    }
}

/**
 * Build Save photo beside Save for a photo Story with music (#38). Right after [builder] adds its
 * Save item, the extension is asked for a title from the story [action] carries in
 * [storyCardField]; with one, a second item is constructed from the same arguments with that title
 * in place of the first String, remembered, and added. The story menu draws a set title instead of
 * the item's string resource, and a tap on either item reaches the same action with the item
 * tapped. With no title, Save any story off or paused, or a Story not made from a photo, the menu
 * is Facebook's own.
 *
 * The title register is the call's own until the item's constructor reads it, so it holds the card
 * first. The method returns right after ([saveItemSite]), and the branch past the new item lands
 * on that return, a real instruction.
 */
internal fun addSavePhotoItem(builder: MutableMethod, action: ClassDef, storyCardField: String) {
    val site = saveItemSite(builder, action)
    val title = "v${site.titleRegister}"
    val item = "v${site.itemRegister}"

    builder.addInstructionsWithLabels(
        site.addIndex + 1,
        """
            iget-object $title, v${site.actionRegister}, ${action.type}->$storyCardField:$STORY_CARD
            invoke-static { $title }, $PHOTO_TITLE
            move-result-object $title
            if-eqz $title, :menu_done
            new-instance $item, ${site.init.definingClass}
            invoke-direct/range { $item .. v${site.lastRegister} }, ${site.init}
            invoke-static { $item }, $PHOTO_OFFERED
            invoke-virtual { v${site.listRegister}, $item }, $LIST_BUILDER->add(Ljava/lang/Object;)$LIST_BUILDER
        """,
        ExternalLabel("menu_done", builder.getInstruction(site.addIndex + 1)),
    )
}

/** Overrides only the primary item's optional title, before its original constructor reads it. */
internal fun addStorySaveTitle(builder: MutableMethod, action: ClassDef, storyCardField: String): MutableMethod {
    check(action.methods.none { it.name == SAVE_TITLE_HELPER }) { "${action.type}: save-title helper already exists" }
    val site = saveItemSite(builder, action)
    // Read the action's card in its own class, where a private field remains accessible. The
    // builder can call the public bridge without borrowing the item's uninitialized register.
    val helper = ImmutableMethod(
        action.type, SAVE_TITLE_HELPER,
        listOf(ImmutableMethodParameter(action.type, null, null), ImmutableMethodParameter("Ljava/lang/String;", null, null)),
        "Ljava/lang/String;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructions(0, """
            if-nez p0, :has_action
            return-object p1
            :has_action
            iget-object v0, p0, ${action.type}->$storyCardField:$STORY_CARD
            invoke-static { v0, p1 }, $SAVE_TITLE
            move-result-object v0
            return-object v0
        """)
    }
    builder.addInstructions(site.initIndex, """
        invoke-static { v${site.actionRegister}, v${site.titleRegister} }, ${action.type}->$SAVE_TITLE_HELPER(${action.type}Ljava/lang/String;)Ljava/lang/String;
        move-result-object v${site.titleRegister}
    """)
    return helper
}

/**
 * Hand the answer of the call at [index] to the extension, which answers yes while Save any story
 * is on and passes Facebook's own answer through when it's off, paused, or not ready yet. It
 * used to be overwritten with `true`, so a paused Facebook still offered Save on every story.
 *
 * The call goes in right after the `move-result`, reading and writing the register that
 * instruction already writes, so the branch that reads the answer reads the switch's. The
 * capability call still runs.
 */
private fun MutableMethod.answerWithTheSwitch(instructions: List<Instruction>, index: Int) {
    val moveResult = instructions.getOrNull(index + 1)
    check(moveResult?.opcode == Opcode.MOVE_RESULT) {
        "$definingClass->$name: the capability check no longer stores its result"
    }

    // invoke-static names its arguments in four bits.
    val register = (moveResult as OneRegisterInstruction).registerA
    check(register < 16) { "$definingClass->$name: result register v$register is out of range" }

    addInstructions(
        index + 2,
        """
            invoke-static { v$register }, $OFFERS_SAVE
            move-result v$register
        """,
    )
}

private fun MutableMethod.instructionsOrEmpty(): List<Instruction> =
    implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.methodReferenceOrNull() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.typeReferenceOrNull() =
    ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type
