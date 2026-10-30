/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredmarketplace

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.liveAcrossInjection
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Keeps the ads out of Marketplace's feed and its search results: the feed's query asks Facebook's
 * servers to skip them, the queries that fetch only ads aren't sent, and the ads that come back
 * among search results are taken out of the answer before JavaScript sees it. A video ad that
 * comes back anyway isn't drawn (MarketplaceVideoAdAnchors.kt). See
 * MarketplaceRequestAnchors.kt and MarketplaceResponseAnchors.kt for where the requests and their
 * answers pass through Java, and the extension's MarketplaceAdFilter for what it changes.
 *
 * The same two hooks carry Marketplace's advanced search, with a switch of its own: the extension's
 * MarketplaceSearchSyntax takes the rules out of a search's text on the way out and the listings
 * that break them out of its answer, and MarketplaceFilterButton puts the Adv. Filters button on the
 * results. It adds no hook of its own.
 */
@Suppress("unused")
val hideSponsoredMarketplaceListingsPatch = bytecodePatch(
    name = "Hide sponsored Marketplace listings",
    description = "Removes ads and boosted listings from Marketplace's feed and search results, so you see more " +
        "of what people are selling. Listings people post stay. It also reads rules typed into Marketplace " +
        "search: \"ram\" keeps titles with ram, -dell hides titles with dell and in:melbourne keeps listings " +
        "in Melbourne, and an Adv. Filters button writes them for you. On by default. Turn either off in " +
        "Hushfacebook settings > Marketplace.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val sends = classDefByStrings(NETWORKING_TAG, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { owner -> owner.methods.filter(::isSendRequest) }
        val send = sends.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one React Native $SEND_REQUEST holding \"$NETWORKING_TAG\" and \"$TRACKING_NAME\", " +
                "found ${sends.size}",
        )
        val read = bodyRead(send) ?: throw PatchException(
            "$PATCH: ${send.definingClass}->$SEND_REQUEST no longer reads its POST body from the request data's " +
                "\"$BODY_KEY\" right before the one StringEntity it builds, from the data it reads \"$TRACKING_NAME\" from",
        )
        mutableClassDefBy(send.definingClass).findMutableMethodOf(send).askAboutTheBody(read)

        val callbacks = classDefByOrNull(CALLBACKS) ?: throw PatchException("$PATCH: this build has no $CALLBACKS")
        val inBuild = { reference: MethodReference -> method(classDefByOrNull(reference.definingClass)?.methods, reference) }
        val handOffs = textHandOffs(callbacks, inBuild)
        val pieces = handOffs.count { !it.whole }
        val wholes = handOffs.count { it.whole }
        if (pieces != 1 || wholes != 1) {
            throw PatchException(
                "$PATCH: expected $CALLBACKS to hand an answer's text to JavaScript once in pieces and once whole, " +
                    "each from a toString() with the request's state at hand, found $pieces and $wholes",
            )
        }
        val stateType = handOffs.map { it.stateType }.distinct().singleOrNull()
            ?: throw PatchException("$PATCH: $CALLBACKS reads two request states: ${handOffs.map { it.stateType }}")
        val tracking = trackingField(send, stateType, inBuild) ?: throw PatchException(
            "$PATCH: ${send.definingClass}->$SEND_REQUEST no longer builds $stateType with the \"$TRACKING_NAME\" it " +
                "reads, kept in one String field",
        )
        val ends = answerEnds(callbacks, stateType, inBuild)
        val end = ends.singleOrNull() ?: throw PatchException(
            "$PATCH: expected $CALLBACKS to report an answer complete once, with the request's state at hand, " +
                "found ${ends.size}",
        )
        val piece = handOffs.single { !it.whole }
        if (end.emitter.parameterTypes.first().toString() != piece.emitter.parameterTypes.first().toString()) {
            throw PatchException("$PATCH: ${end.emitter} and ${piece.emitter} take different contexts")
        }
        // The end goes in first. A hand-off after it in the same method would lose its place.
        if (handOffs.any { it.method == end.method && it.landed >= end.call }) {
            throw PatchException("$PATCH: ${end.method.name} hands text on after it reports the answer complete")
        }
        val mutableCallbacks = mutableClassDefBy(CALLBACKS)
        mutableCallbacks.findMutableMethodOf(end.method).handTheRestOver(end, piece.emitter)
        handOffs.forEach { mutableCallbacks.findMutableMethodOf(it.method).handTheTextOver(it, tracking) }

        // A video ad that reaches the feed anyway isn't drawn. A build without one of its two drawers
        // goes on without that one: the requests above are what keep the ads out.
        VideoAdDrawer.entries.forEach { drawer ->
            val found = classDefByStrings(drawer.strings.first(), StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                .flatMap { owner -> owner.methods.filter { draws(it, drawer) } }
            val method = found.singleOrNull() ?: return@forEach patchLog.warning(
                "$PATCH: found ${found.size} Marketplace video ad drawers holding ${drawer.strings}, not one, so " +
                    "a video ad there is drawn.",
            )
            mutableClassDefBy(method.definingClass).findMutableMethodOf(method).drawNothingWhenHidden()
        }
        enableStatus("sponsoredMarketplace")
    }
}

/**
 * Right after sendRequest reads the POST body, the body and the request data go to the extension,
 * and its answer takes the body's register. Nothing else in the method changes. Both registers
 * were checked to fit an invoke's four bits.
 */
internal fun MutableMethod.askAboutTheBody(read: BodyRead) {
    if (read.body > 15 || read.data > 15) {
        throw PatchException("$PATCH: $definingClass->$name keeps the body or the data past v15: ${read.body}, ${read.data}")
    }
    addInstructions(
        read.index + 1,
        """
            invoke-static { v${read.body}, v${read.data} }, $REQUEST_BODY
            move-result-object v${read.body}
        """,
    )
}

/** The method of [methods] that [reference] names, or null. */
private fun method(methods: Iterable<Method>?, reference: MethodReference): Method? = methods?.firstOrNull {
    it.name == reference.name && it.returnType == reference.returnType &&
        it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
}

/**
 * Right after [handOff]'s text lands, the tracking name goes into a local nothing reads afterwards,
 * the text, the name and for a piece the request's state go to the extension, and its answer takes
 * the text's register. Nothing else in the method changes.
 */
internal fun MutableMethod.handTheTextOver(handOff: TextHandOff, tracking: FieldReference) {
    val at = handOff.landed + 1
    val name = freeLocalsAt(PATCH, at, 1).single()
    val call = if (handOff.whole) {
        "invoke-static { v${handOff.text}, v$name }, $RESPONSE_WHOLE"
    } else {
        "invoke-static { v${handOff.text}, v$name, v${handOff.state} }, $RESPONSE_PIECE"
    }
    addInstructions(
        at,
        """
            iget-object v$name, v${handOff.state}, $tracking
            $call
            move-result-object v${handOff.text}
        """,
    )
}

/**
 * Right before [end]'s call reports an answer complete, the extension is asked for the text still
 * waiting there for a payload to finish. When it hands some back, the text goes to JavaScript
 * through [emitter], the piece emitter, as one last piece, with the context, the request id and the
 * request's number the end call is given and no length. It takes eight locals in a row that nothing
 * reads afterwards; the end call and the rest of the method run as they did.
 */
internal fun MutableMethod.handTheRestOver(end: AnswerEnd, emitter: MethodReference) {
    val live = liveAcrossInjection(end.call)
    val first = (0..minOf(localRegisterCount(), 256) - 8).firstOrNull { start -> (start until start + 8).none { it in live } }
        ?: throw PatchException("$PATCH: $definingClass->$name has no eight locals in a row free before instruction ${end.call}")
    addInstructionsWithLabels(
        end.call,
        """
            invoke-static/range { v${end.state} .. v${end.state} }, $RESPONSE_END
            move-result-object v${first + 2}
            if-eqz v${first + 2}, :complete
            move-object/from16 v$first, v${end.context}
            move-object/from16 v${first + 1}, v${end.id}
            move/from16 v${first + 3}, v${end.number}
            const-wide/16 v${first + 4}, 0x0
            const-wide/16 v${first + 6}, 0x0
            invoke-static/range { v$first .. v${first + 7} }, $emitter
        """,
        ExternalLabel("complete", getInstruction(end.call)),
    )
}
