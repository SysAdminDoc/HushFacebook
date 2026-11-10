/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Comment sheets ask for the order picked in Hushfacebook's settings where Facebook's servers
 * would choose, and a pick in a sheet's own menu stays for that post. See CommentOrderAnchors.kt
 * for how Facebook picks the order, and the extension's DefaultCommentOrder for when it keeps
 * Facebook's.
 *
 * In the default selection with its switch off and the order as Facebook's own, so nothing
 * changes until the switch is turned on and an order is chosen.
 */
@Suppress("unused")
val defaultCommentOrderPatch = bytecodePatch(
    name = "Default comment order",
    description = "Opens comments in the order you choose, Most relevant, Newest, All comments or Oldest, instead of the " +
        "one Facebook picks. Handy if you always want the newest first. Starts off. Turn it on and pick an order " +
        "in Hushfacebook settings > Comments and writing.",
) {
    category("Comments")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // 586's Android 9 build's builder asks a string table for each variable's name.
        val resolve = variableTables()
        val builders = methodsNaming(ORDER_VARIABLE) { requestFields(it, resolve) != null }
            .mapNotNull { method -> requestFields(method, resolve)?.let { method to it } }
        val (builder, fields) = builders.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one request builder taking FetchFeedbackParams that reads \"$ORDER_VARIABLE\", " +
                "\"$FEEDBACK_ID_VARIABLE\" and \"$FOCUSED_COMMENT_VARIABLE\" from its fields, found ${builders.size}",
        )
        val params = classDefByOrNull(FETCH_FEEDBACK_PARAMS)
            ?: throw PatchException("$PATCH: this build has no $FETCH_FEEDBACK_PARAMS")
        val constructors = params.methods.mapNotNull { method -> requestRegisters(method, fields)?.let { method to it } }
        val (constructor, registers) = constructors.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one constructor of $FETCH_FEEDBACK_PARAMS storing the fields ${builder.definingClass}->" +
                "${builder.name} reads from its own parameters, found ${constructors.size}",
        )
        mutableClassDefBy(FETCH_FEEDBACK_PARAMS).findMutableMethodOf(constructor).askForTheOrder(registers)

        val handlers = classDefByStrings(PICK_QUERY, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { owner -> owner.methods.filter(::isPickHandler) }
        val handler = handlers.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one comment sheet pick handler taking the session and a token that fetches " +
                "\"$PICK_QUERY\", found ${handlers.size}",
        )
        mutableClassDefBy(handler.definingClass).findMutableMethodOf(handler).tellThePick()

        val keyOwners = classDefByStrings(SELECTOR_KEY, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val keyMaps = selectorKeyMaps(keyOwners)
        val intentGetters = sheetGetters(INTENT_FLAG)
        val interrankedGetters = sheetGetters(INTERRANKED)
        val found = mutableListOf<MethodReference>()
        // The renderer reads the key's map, so it names the map's class; 585's holds neither string to look it up by.
        if (keyMaps.isNotEmpty()) classDefForEachReferencing(types = keyOwners.map { it.type }) { owner ->
            if (owner.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
            owner.methods.mapNotNullTo(found) { sortHeaderIn(it, keyMaps, intentGetters, interrankedGetters) }
        }
        val headers = found.distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }
        val header = headers.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one comment sort menu header built by the method naming \"$INTENT_FLAG\" and " +
                "\"$INTERRANKED\" (or calling a model's getter for each) and filed under \"$SELECTOR_KEY\", " +
                "found ${headers.size}",
        )
        val headerClass = mutableClassDefBy(header.definingClass)
        val headerConstructor = headerClass.methods.firstOrNull {
            it.name == "<init>" && it.parameterTypes.map { type -> type.toString() } == header.parameterTypes.map { type -> type.toString() }
        } ?: throw PatchException("$PATCH: ${header.definingClass} has no constructor ${header.parameterTypes}")
        headerConstructor.tellTheMenu()
        hookPostPage()
        enableStatus("defaultCommentOrder")
    }
}

/**
 * A post's own page asks the extension too (#192): right before the one link-to-page builder
 * constructs the page's request params, the order the link names goes to the extension and its
 * answer takes that parameter's place. See CommentOrderAnchors.kt for the shapes.
 */
internal fun BytecodePatchContext.hookPostPage() {
    val resolve = variableTables()
    val storyBuilders = methodsNaming(ORDER_VARIABLE) { storyOrderField(it, resolve) != null }
        .mapNotNull { method -> storyOrderField(method, resolve)?.let { method to it } }
    val (storyBuilder, orderField) = storyBuilders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one request builder taking $FETCH_SINGLE_STORY_PARAMS that reads \"$ORDER_VARIABLE\" " +
            "from one of its fields, found ${storyBuilders.size}",
    )
    val storyParams = classDefByOrNull(FETCH_SINGLE_STORY_PARAMS)
        ?: throw PatchException("$PATCH: this build has no $FETCH_SINGLE_STORY_PARAMS")
    val constructors = storyParams.methods.mapNotNull { method -> storyOrderParameter(method, orderField)?.let { method to it } }
    val (constructor, parameter) = constructors.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one constructor of $FETCH_SINGLE_STORY_PARAMS storing the field ${storyBuilder.definingClass}->" +
            "${storyBuilder.name} reads the order from, from one of its parameters, found ${constructors.size}",
    )
    val builds = mutableListOf<PageBuild>()
    // A page build calls the constructor.
    classDefForEachReferencing(types = listOf(constructor.definingClass)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.forEach { method -> pageBuildIn(method, constructor, parameter)?.let { builds += it } }
    }
    val build = builds.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method taking $PERMALINK_PARAMS that builds $FETCH_SINGLE_STORY_PARAMS with its " +
            "${constructor.parameterTypes.size}-parameter constructor, found ${builds.size}",
    )
    mutableClassDefBy(build.method.definingClass).findMutableMethodOf(build.method).askForThePageOrder(build)
}

/** The interface getters whose models name [literal], held or asked of a string table ([interfaceGetters]). */
private fun BytecodePatchContext.sheetGetters(literal: String): Set<String> {
    val getters = methodsNaming(literal, ::isSheetGetter)
    val models = getters.map { it.definingClass }.distinct().mapNotNull { classDefByOrNull(it) }
    return interfaceGetters(models, getters)
}

/**
 * Right before the link-to-page builder calls the params' constructor, the order the link names
 * goes to the extension and its answer goes back into the same register, which the call then
 * reads. The range form names any register, and the answer's move takes one up to v255.
 */
internal fun MutableMethod.askForThePageOrder(build: PageBuild) {
    // 583 keeps it in v31. Guarded in case a build pushes the constructor's registers past what move-result-object names.
    if (build.order > 255) {
        throw PatchException("$PATCH: $definingClass->$name keeps the page's order past v255: v${build.order}")
    }
    addInstructions(
        build.index,
        """
            invoke-static/range { v${build.order} .. v${build.order} }, $PAGE_ORDER
            move-result-object v${build.order}
        """,
    )
}

/**
 * First thing in the params' constructor, the order the request would carry goes to the extension
 * with the feedback id and the focused comment id, and its answer takes the order's place in the
 * parameter the constructor stores it from. Nothing else in the constructor changes.
 */
internal fun MutableMethod.askForTheOrder(registers: RequestRegisters) {
    val read = listOf(registers.order, registers.feedbackId, registers.focusedComment)
    // invoke-static names each register in four bits. The iput-object stores the finder read them
    // from name theirs in four bits too, so this holds wherever the finder matched (v4 to v7 on 577
    // and 580). It stays as the guard in case the finder ever follows a copy instead.
    if (read.any { it > 15 }) {
        throw PatchException("$PATCH: $definingClass->$name keeps the order or the ids past v15: $read")
    }
    addInstructions(
        0,
        """
            invoke-static { ${read.joinToString { "v$it" }} }, $REQUESTED_ORDER
            move-result-object v${registers.order}
        """,
    )
}

/**
 * First thing in the sort menu header's constructor, the post's feedback tree and the Function1 a
 * tap on a row calls go to the extension, which may call that Function1 later on the main thread.
 */
internal fun MutableMethod.tellTheMenu() {
    val feedback = parameterRegisterNumber(1)
    val pick = parameterRegisterNumber(3)
    // 582's constructor has five registers, so both are v2 and v4. Guarded in case a build grows it.
    if (feedback > 15 || pick > 15) {
        throw PatchException("$PATCH: $definingClass->$name keeps the feedback or the pick past v15: $feedback, $pick")
    }
    addInstructions(0, "invoke-static { v$feedback, v$pick }, $SORT_MENU")
}

/** First thing in the pick handler, the picked token goes to the extension, which only notes it. */
internal fun MutableMethod.tellThePick() {
    // The range form names any register, so the handler's register count doesn't matter.
    val token = parameterRegisterNumber(1)
    addInstructions(0, "invoke-static/range { v$token .. v$token }, $PICKED")
}
