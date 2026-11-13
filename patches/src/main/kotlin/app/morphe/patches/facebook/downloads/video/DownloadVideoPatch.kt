/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.downloads.photo.commentSheetDialogPatch
import app.morphe.patches.facebook.downloads.photo.commentSheetVideoRow
import app.morphe.patches.facebook.downloads.story.rememberPlayerSources
import app.morphe.patches.facebook.feed.addressed
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.switchTarget
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.namedRegisters
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** The extension call that adds the item to a post's menu once Facebook has filled it. */
private const val ADD_ITEM = "Lapp/morphe/extension/facebook/download/VideoMenuItem;->add(" +
    "Landroid/view/Menu;Landroid/view/View;Ljava/lang/Object;I" +
    "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"

/** The video patch's own recorder of player sources, behind its own switch. */
private const val REMEMBER_VIDEO_SOURCE = "Lapp/morphe/extension/facebook/download/PlayerSources;->" +
    "rememberVideo(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"

/** The helper this patch adds to the class that builds every post menu. */
private const val MENU_HELPER = "hushfacebookVideoMenuItem"

/** The helper this patch adds to the player params, beside the story patch's. */
private const val REMEMBER_HELPER = "hushfacebookRememberVideoSource"

/**
 * Adds Download to phone to the menu of a post that holds a video, in the feed and in Watch.
 *
 * Facebook's own Download video row is in the video feed's menu too, but only for videos whose
 * owner allows it, and 573-era patches that saved any video took over the player's own Save
 * button to do it. This adds an item of its own instead, after Facebook has filled the menu, so
 * Facebook's rows, its Save and its Download included, stay exactly as they were.
 *
 * Every post menu, the news feed's and Watch's alike, is filled by one virtual call to the base
 * of the menu builders, from one method that then shows the sheet. The call goes in right after
 * it, when the menu holds all of Facebook's rows. It hands the extension the menu, the view that
 * opened it and what the menu is for, and the extension adds the item only when that holds a
 * video, and only while Download any video is on, not paused, and the settings are ready.
 *
 * The save reuses the story save's pipeline whole: the player of the same video records its
 * DASH manifest under the video's id, and the post's media carries that id.
 *
 * A video someone posted in a comment gets Save video on the comment's long-press sheet (#318), a
 * row built the way Download any photo's Save GIF is, at the same point.
 */
@Suppress("unused")
val downloadVideoPatch = bytecodePatch(
    name = "Download any video",
    description = "Adds Download to phone to the menu of videos in the feed and in Watch, so you can save them " +
        "to watch later. A video comment gets Save video when you long-press it. Videos save at the quality you " +
        "set, best by default. Starts off. Turn it on in Hushfacebook settings > Downloads.",
) {
    category("Downloads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())
    dependsOn(facebookExtensionPatch, commentSheetDialogPatch)

    execute {
        val menu = postMenu()
        // Found before any hook goes in, so a build without the sheet fails whole.
        val addCommentVideoRow = commentSheetVideoRow()
        callAfterMenuFill(
            menu,
            postMenuHelper(menu, MENU_HELPER, ADD_ITEM, listOf(menu.attachments, menu.media, menu.attachedStory)),
        )

        // ---- the players' sources, for the manifest a post's media doesn't carry -------------------
        rememberPlayerSources(helperName = REMEMBER_HELPER, recorder = REMEMBER_VIDEO_SOURCE)

        addCommentVideoRow()
        enableStatus("videoDownload")
    }
}

/**
 * Where Facebook fills every post menu, and what an item added there needs from this build: the
 * method and the index of the call that fills the menu, the register the menu sits in (the view
 * and the props follow it), the props' class and the field holding what the menu is for, Facebook's
 * own Download row icon, and the real names of the post's attachment list, an attachment's media
 * and the post a share wraps.
 *
 * Download any video and Download any photo each add a helper of their own there, so either works
 * without the other, and with both in, each item asks its own switch.
 */
internal class PostMenu(
    val creatorType: String,
    val creatorMethod: Method,
    val callIndex: Int,
    val menuRegister: Int,
    val propsType: String,
    val itemField: String,
    val icon: Int,
    val attachments: String,
    val media: String,
    val attachedStory: String,
)

/** Finds the [PostMenu] of this build. */
internal fun BytecodePatchContext.postMenu(): PostMenu {
    // ---- the video feed's menu, and the base every post menu is filled through -----------
    val videoMenu = VideoFeedStoryMenuFingerprint.method
    val optionsType = videoMenu.definingClass
    val baseType = classDefBy(optionsType).superclass
        ?: throw PatchException("$optionsType has no superclass to fill menus through")
    val signature = videoMenu.parameterTypes.map(CharSequence::toString)

    val base = classDefBy(baseType)
    check(base.methods.count { it.name == videoMenu.name && it.parameters() == signature && it.returnType == "V" } == 1) {
        "$baseType declares no ${videoMenu.name}${signature.joinToString("", "(", ")V")} for $optionsType to override"
    }

    val propsType = signature[2]
    val itemField = classDefBy(propsType).fields.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "Ljava/lang/Object;"
    }
    check(itemField.size == 1) {
        "Expected 1 field of Object on $propsType, what a menu is for, found ${itemField.size}"
    }

    // ---- where Facebook fills a post menu -----------------------------------------------------
    //
    // Every override calls its base with invoke-super. The one invoke-virtual of the base is
    // the builder that fills the menu and shows it (580: a static method of the feed menu
    // helper that returns the sheet; 577: the same method under another Redex name). 586
    // fills through an interface the base implements, and a second builder there fills the
    // menu of promotion and carousel units; only the post's builder takes the player origin.
    val sites = mutableListOf<Triple<String, Method, Int>>()
    classDefForEachReferencing(types = listOf(baseType) + base.interfaces) { classDef ->
        classDef.methods.forEach { method ->
            if (!opensPostMenu(method, signature)) return@forEach
            method.instructionsOrEmpty().forEachIndexed { index, instruction ->
                if (fillsPostMenu(instruction, base, videoMenu.name, signature)) {
                    sites += Triple(classDef.type, method, index)
                }
            }
        }
    }

    check(sites.size == 1) {
        "Expected 1 place that fills a post menu through $baseType->${videoMenu.name} or an interface of it, " +
            "found ${sites.size}: " +
            sites.joinToString { "${it.first}->${it.second.name}" }
    }

    val (creatorType, creatorMethod, callIndex) = sites.single()

    // The call takes the builder and seven arguments, so it's always the range form, and the
    // menu, the view and the props sit in the three registers after the builder's. A range
    // call leaves its registers as they were, so they still hold those three right after it.
    val call = creatorMethod.instructionsOrEmpty()[callIndex] as? RegisterRangeInstruction
        ?: throw PatchException("$creatorType->${creatorMethod.name} fills the menu without a range call")

    // ---- what the extension needs, read from this build --------------------------------------
    return PostMenu(
        creatorType = creatorType,
        creatorMethod = creatorMethod,
        callIndex = callIndex,
        menuRegister = call.startRegister + 1,
        propsType = propsType,
        itemField = itemField.single().name,
        icon = stockDownloadIcon(videoMenu, "$optionsType->${videoMenu.name}") { type -> classDefByOrNull(type) },
        attachments = graphQlGetter(GRAPHQL_STORY, IMMUTABLE_LIST, ATTACHMENTS_FIELD, "attachments"),
        media = graphQlGetter(GRAPHQL_STORY_ATTACHMENT, GRAPHQL_MEDIA, MEDIA_FIELD, "media"),
        attachedStory = graphQlGetter(GRAPHQL_STORY, GRAPHQL_STORY, ATTACHED_STORY_FIELD, "attached_story"),
    )
}

/**
 * A static helper for the creator's class, [name], taking the menu, the view and the props, that
 * hands the extension call [extension] the menu, the view, what the menu is for, Facebook's
 * Download row icon and [getters] as strings, in that order. Its own method, so it has fresh
 * registers: the builder runs with more than eighty.
 */
internal fun postMenuHelper(menu: PostMenu, name: String, extension: String, getters: List<String>): MutableMethod {
    val last = 3 + getters.size
    val names = getters.withIndex().joinToString("\n") { (index, getter) -> "const-string v${4 + index}, \"$getter\"" }
    return ImmutableMethod(
        menu.creatorType,
        name,
        listOf(
            ImmutableMethodParameter(MENU, null, null),
            ImmutableMethodParameter(VIEW, null, null),
            ImmutableMethodParameter(menu.propsType, null, null),
        ),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(last + 1 + 3),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                if-eqz p2, :none
                move-object v0, p0
                move-object v1, p1
                iget-object v2, p2, ${menu.propsType}->${menu.itemField}:Ljava/lang/Object;
                const v3, ${menu.icon}
                $names
                invoke-static/range { v0 .. v$last }, $extension
                :none
                return-void
            """,
        )
    }
}

/**
 * Adds [helper] to the creator's class and calls it right after Facebook has filled the menu and
 * before it counts the rows. The extension asks the switch first, so off, paused or not ready,
 * the menu is Facebook's own.
 */
internal fun BytecodePatchContext.callAfterMenuFill(menu: PostMenu, helper: MutableMethod) {
    val creatorClass = mutableClassDefBy(menu.creatorType)
    val creator = creatorClass.methods.single {
        it.name == menu.creatorMethod.name && it.parameters() == menu.creatorMethod.parameters() &&
            it.returnType == menu.creatorMethod.returnType
    }
    creatorClass.methods.add(helper)
    creator.addInstructions(
        menu.callIndex + 1,
        "invoke-static/range { v${menu.menuRegister} .. v${menu.menuRegister + 2} }, " +
            "${menu.creatorType}->${helper.name}($MENU$VIEW${menu.propsType})V",
    )
}

/**
 * The name of [owner]'s public getter that returns [returnType] and reads the GraphQL field whose
 * name hashes to [field]. [owner] is a kept model class; the getter's own name is Redex's.
 */
internal fun BytecodePatchContext.graphQlGetter(owner: String, returnType: String, field: Int, what: String): String {
    val getters = classDefBy(owner).methods.filter { method ->
        method.parameterTypes.isEmpty() && method.returnType == returnType &&
            AccessFlags.PUBLIC.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.instructionsOrEmpty().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == field }
    }
    check(getters.size == 1) {
        "Expected 1 getter of $owner that reads $what, found ${getters.size}: " + getters.joinToString { it.name }
    }
    return getters.single().name
}

/**
 * The drawable Facebook's own Download video row sets, read from where that row is built: the
 * `setIcon(I)` the row calls just before it reports itself as [STOCK_DOWNLOAD_ROW], and the
 * constant that call is given. 587 builds the row in a helper of its own ([helperRowIcon]).
 */
private fun stockDownloadIcon(menu: Method, where: String, classOf: (String) -> ClassDef?): Int {
    val instructions = menu.instructionsOrEmpty()
    val resolve: (MethodReference) -> Method? = { call -> classOf(call.definingClass)?.let { resolveStatic(it, call) } }
    // 585 asks a string table for the row's name instead of holding the literal, so both forms count.
    val row = instructions.indices.firstOrNull { index ->
        instructions[index].stringReference() == STOCK_DOWNLOAD_ROW ||
            tableStringAt(instructions, index, resolve) == STOCK_DOWNLOAD_ROW
    } ?: -1
    if (row < 0) {
        val icons = helperRowIcon(menu, classOf, resolve)
        check(icons.size == 1) {
            "$where does not name its $STOCK_DOWNLOAD_ROW row, and ${icons.size} helpers it calls set a row's icon " +
                "before handing the row to a lambda that names it"
        }
        return icons.single()
    }
    check(row > 0) { "$where does not name its $STOCK_DOWNLOAD_ROW row" }

    val setIcon = (row - 1 downTo maxOf(0, row - 16)).firstOrNull { index ->
        val reference = instructions[index].methodReference()
        reference != null && reference.definingClass == MENU_ITEM && reference.name == "setIcon" &&
            reference.parameterTypes.map(CharSequence::toString) == listOf("I")
    } ?: throw PatchException("$where sets no icon on its $STOCK_DOWNLOAD_ROW row")

    val iconRegister = (instructions[setIcon] as FiveRegisterInstruction).registerD
    val writer = (setIcon - 1 downTo maxOf(0, setIcon - 8)).firstOrNull { index ->
        (instructions[index] as? OneRegisterInstruction)?.registerA == iconRegister
    } ?: throw PatchException("$where: the $STOCK_DOWNLOAD_ROW icon comes from no nearby constant")

    val icon = (instructions[writer] as? NarrowLiteralInstruction)?.narrowLiteral
    check(icon != null && icon ushr 24 == 0x7f) {
        "$where: the $STOCK_DOWNLOAD_ROW icon isn't an app resource constant: ${instructions[writer].opcode}"
    }
    return icon
}

/**
 * Every icon 587's way of building the row sets: the video menu hands a helper a Kotlin lambda made
 * with a constant key, the case of the lambda's `invoke(Object)` for that key names
 * [STOCK_DOWNLOAD_ROW] (it reports the row), and the helper sets an icon on a new item right before
 * it hands the item to that lambda. The icon is the app resource constant that reaches `setIcon(I)`.
 */
private fun helperRowIcon(menu: Method, classOf: (String) -> ClassDef?, resolve: (MethodReference) -> Method?): Set<Int> {
    val code = menu.instructionsOrEmpty()
    val icons = mutableSetOf<Int>()
    code.forEachIndexed { at, instruction ->
        val call = instruction.methodReference()?.takeIf { it.name != "<init>" } ?: return@forEachIndexed
        val passed = argumentRegisters(call, instruction.namedRegisters(), instruction.opcode in STATIC_CALLS)
        call.parameterTypes.map(CharSequence::toString).forEachIndexed parameter@{ slot, type ->
            if (type != FUNCTION1 || passed[slot] < 0) return@parameter
            val (made, _) = menu.origin(at, passed[slot])?.takeIf { (writer, _) ->
                writer >= 0 && code[writer].opcode == Opcode.NEW_INSTANCE
            } ?: return@parameter
            val key = menu.lambdaKey(made) ?: return@parameter
            val lambda = classOf(((code[made] as ReferenceInstruction).reference as TypeReference).type) ?: return@parameter
            if (!caseNamesRow(lambda, key, resolve)) return@parameter
            val helper = classOf(call.definingClass)?.methods?.firstOrNull {
                it.name == call.name && it.parameters() == call.parameterTypes.map(CharSequence::toString) &&
                    it.returnType == call.returnType && it.implementation != null
            } ?: return@parameter
            helper.iconBeforeInvoking(slot)?.let { icons += it }
        }
    }
    return icons
}

/** The register each parameter of [call] goes in, from the invoke's [registers]; a wide one takes two. -1 if missing. */
private fun argumentRegisters(call: MethodReference, registers: List<Int>, static: Boolean): List<Int> {
    var next = if (static) 0 else 1
    return call.parameterTypes.map { type ->
        registers.getOrElse(next) { -1 }.also { next += width(type.toString()) }
    }
}

private fun width(type: String) = if (type == "J" || type == "D") 2 else 1

/**
 * Where the value [register] holds at [index] was written, through plain object moves: the writer's
 * index (-1 for the method's entry) and the register it wrote. Null when more than one write reaches.
 */
private fun Method.origin(index: Int, register: Int): Pair<Int, Int>? {
    val code = instructionsOrEmpty()
    var at = index
    var holder = register
    repeat(8) {
        val writer = writersReaching(at, holder).singleOrNull() ?: return null
        if (writer < 0 || code[writer].opcode !in OBJECT_MOVES) return writer to holder
        at = writer
        holder = (code[writer] as TwoRegisterInstruction).registerB
    }
    return null
}

/** The constant the lambda made at [made] is built with, the first argument of its `<init>(I...)`. */
private fun Method.lambdaKey(made: Int): Int? {
    val code = instructionsOrEmpty()
    val lambda = (code[made] as OneRegisterInstruction).registerA
    val init = (made + 1 until minOf(code.size, made + 8)).firstOrNull { at ->
        code[at].methodReference()?.name == "<init>" && code[at].namedRegisters().firstOrNull() == lambda
    } ?: return null
    if (code[init].methodReference()!!.parameterTypes.firstOrNull()?.toString() != "I") return null
    val key = code[init].namedRegisters().getOrNull(1) ?: return null
    val writer = writersReaching(init, key).singleOrNull()?.takeIf { it >= 0 } ?: return null
    return (code[writer] as? NarrowLiteralInstruction)?.narrowLiteral
}

/** Whether [lambda]'s `invoke(Object)` case for [key] names [STOCK_DOWNLOAD_ROW] before it jumps away or returns. */
private fun caseNamesRow(lambda: ClassDef, key: Int, resolve: (MethodReference) -> Method?): Boolean {
    val invoke = lambda.methods.singleOrNull {
        it.name == "invoke" && it.parameters() == listOf(OBJECT) && it.returnType == OBJECT && it.implementation != null
    } ?: return false
    val byAddress = addressed(invoke)
    val target = switchTarget(byAddress, key) ?: return false
    val code = byAddress.values.toList()
    for (at in byAddress.keys.indexOf(target) until code.size) {
        if (code[at].stringReference() == STOCK_DOWNLOAD_ROW || tableStringAt(code, at, resolve) == STOCK_DOWNLOAD_ROW) return true
        if (code[at].opcode in CASE_ENDS) return false
    }
    return false
}

/**
 * The icon this helper sets on an item right before it hands the item to its lambda parameter at
 * [slot]: `setIcon(I)` on the item, then `Function1.invoke` on that parameter with the same item.
 */
private fun Method.iconBeforeInvoking(slot: Int): Int? {
    val code = instructionsOrEmpty()
    val widths = parameterTypes.map { width(it.toString()) }
    // Parameters sit in the last registers, `this` (if any) just before them.
    val parameter = implementation!!.registerCount - widths.sum() + widths.take(slot).sum()
    val icons = code.indices.mapNotNull { at ->
        if (code[at].methodReference()?.toString() != FUNCTION1_INVOKE) return@mapNotNull null
        val (lambda, item) = code[at].namedRegisters().takeIf { it.size == 2 } ?: return@mapNotNull null
        if (origin(at, lambda) != (-1 to parameter)) return@mapNotNull null
        val setIcon = (at - 1 downTo maxOf(0, at - 4)).firstOrNull { index ->
            code[index].methodReference()?.let {
                it.definingClass == MENU_ITEM && it.name == "setIcon" && it.parameterTypes.map(CharSequence::toString) == listOf("I")
            } == true && code[index].namedRegisters().first() == item
        } ?: return@mapNotNull null
        if (writersReaching(setIcon, item) != writersReaching(at, item)) return@mapNotNull null
        val writer = writersReaching(setIcon, code[setIcon].namedRegisters()[1]).singleOrNull()?.takeIf { it >= 0 }
            ?: return@mapNotNull null
        (code[writer] as? NarrowLiteralInstruction)?.narrowLiteral?.takeIf { it ushr 24 == 0x7f }
    }
    return icons.distinct().singleOrNull()
}

private const val OBJECT = "Ljava/lang/Object;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val FUNCTION1_INVOKE = "$FUNCTION1->invoke($OBJECT)$OBJECT"
private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val CASE_ENDS = setOf(
    Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_VOID,
    Opcode.RETURN_WIDE, Opcode.THROW,
)

/**
 * Whether [instruction] fills a post menu through the menu builders' [base], calling [name] with
 * [signature]: a virtual call on the base, or (586) an interface call on an interface it implements.
 */
internal fun fillsPostMenu(instruction: Instruction, base: ClassDef, name: String, signature: List<String>): Boolean {
    val virtual = instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE
    val throughInterface = instruction.opcode == Opcode.INVOKE_INTERFACE || instruction.opcode == Opcode.INVOKE_INTERFACE_RANGE
    if (!virtual && !throughInterface) return false
    val reference = instruction.methodReference() ?: return false
    val owner = if (virtual) reference.definingClass == base.type else reference.definingClass in base.interfaces
    return owner && reference.name == name && reference.parameterTypes.map(CharSequence::toString) == signature
}

/**
 * Whether [method] can be where a post's menu is filled: it takes the player origin the fill
 * passes on, the last of [signature]. 586's builder for promotion and carousel units doesn't.
 */
internal fun opensPostMenu(method: Method, signature: List<String>): Boolean = signature.last() in method.parameters()

private fun Method.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun Method.instructionsOrEmpty(): List<Instruction> =
    implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.methodReference() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.stringReference() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
