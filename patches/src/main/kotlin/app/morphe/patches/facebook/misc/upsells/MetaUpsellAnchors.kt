/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.upsells

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.comments.summaries.holdSummaries
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.filterBooleanReturns
import app.morphe.patches.facebook.misc.extension.filterObjectReturns
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.patches.facebook.shared.readsMobileConfig
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Where Facebook pushes Meta's other products outside the Menu, read from 577, 580 and 581 on
 * 2026-10-07. The obfuscated names in these comments are for reviewers; the code finds every one by
 * a kept class name or a literal.
 *
 * Edits. The Reels composer's landing screen (InspirationReelsComposerLandingConfiguration, kept)
 * carries two flags, should_show_edits_app_in_header and should_show_edits_app_header_badge, the
 * Edits button in its header and the badge on it. Its kept JSON serializer writes each under its
 * name straight from the field (A09 and A08 on all three builds), and every screen that shows the
 * header reads the fields (581 LX/KPB's constructor, 580 LX/LXK, 577 LX/auZ). Each read answers the
 * extension's way. The pill under videos in the feed that opens Edits comes from the server, asked
 * for with the request parameter fetch_edits_app_deep_dive_pill, which Facebook sets from a
 * MobileConfig gate in the feed's query (581 LX/1bb;->A02), the async ad query (581 LX/2HF) and
 * three video queries that pass it boxed (581 LX/8Vc, LX/8Zf, LX/8aU). The gate's answer goes to
 * the extension before it's sent. 577's LX/3Xp reads the name from a response tree, not a gate, and
 * stays.
 *
 * Threads. The composer asks a capability whether to show its Threads cross-posting onboarding: the
 * class whose constructor names itself ComposerThreadsCrossPostOnboardingCapability (581 LX/Bsk),
 * with one no-argument boolean, its only interface method.
 *
 * Meta Verified. After you post, MetaVerifiedFbAfterPostUpsellBottomSheetHandlerImpl (kept) asks
 * the server whether you're eligible for its offer sheet with
 * MetaVerifiedFbAfterPostUpsellEligibility Query, in a suspend method answering a Boolean, and
 * shows the sheet only on a yes. The label under some posts' headers comes from
 * MetaVerifiedLabelPlugin (kept), whose static (props) -> String answers the label's text or null,
 * and the subtitle plugin dispatcher (581 LX/2Ap;->A1v) asks it once to decide whether the label is
 * wanted. Only that call is answered: the plugin's own draw asks again and throws on a null.
 *
 * Avatar stickers. Three Litho components draw the avatar sticker upsells, each naming itself:
 * AvatarStickerHorizonUpsellQPComponent and InstantAvatarNuxComponent (a render method) and
 * CommentAvatarStickerUpsellAttachmentComponent (the layout method). Each draws nothing when the
 * extension says so, and Litho leaves no room for a null.
 *
 * Imagine, Meta AI's image maker: the Imagine me button under posts, the post composer's Imagine
 * and Create story's Imagine tile. See ImagineAnchors.kt.
 *
 * Threads in the share sheet: the button that shares a post to Threads. See ShareSheetAnchors.kt.
 */

internal const val PATCH = "Hide Meta upsells"

internal const val META_UPSELLS = "$EXTENSION_PACKAGE/misc/MetaUpsells;"
internal const val EDITS_HEADER = "$META_UPSELLS->editsHeader(Z)Z"
internal const val FETCH_EDITS_PILL = "$META_UPSELLS->fetchEditsPill(Z)Z"
internal const val FETCH_EDITS_PILL_BOXED = "$META_UPSELLS->fetchEditsPill(Ljava/lang/Boolean;)Ljava/lang/Boolean;"
internal const val THREADS_ONBOARDING = "$META_UPSELLS->threadsOnboarding(I)Z"
internal const val META_VERIFIED_SHEET = "$META_UPSELLS->metaVerifiedSheet(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val META_VERIFIED_LABEL = "$META_UPSELLS->metaVerifiedLabel(Ljava/lang/String;)Ljava/lang/String;"
internal const val HIDES_AVATAR_UPSELL = "$META_UPSELLS->hidesAvatarUpsell()Z"

internal const val LANDING_CONFIG = "Lcom/facebook/ipc/inspiration/config/InspirationReelsComposerLandingConfiguration;"
internal const val LANDING_SERIALIZER = "Lcom/facebook/ipc/inspiration/config/InspirationReelsComposerLandingConfiguration\$Serializer;"
internal val EDITS_FLAGS = listOf("should_show_edits_app_in_header", "should_show_edits_app_header_badge")
internal const val EDITS_PILL_PARAMETER = "fetch_edits_app_deep_dive_pill"

internal const val THREADS_CAPABILITY = "ComposerThreadsCrossPostOnboardingCapability"

internal const val VERIFIED_SHEET_HANDLER =
    "Lcom/facebook/nme/fbafterpostupsell/impl/MetaVerifiedFbAfterPostUpsellBottomSheetHandlerImpl;"
internal const val VERIFIED_ELIGIBILITY_QUERY = "MetaVerifiedFbAfterPostUpsellEligibilityQuery"
internal const val VERIFIED_LABEL_PLUGIN =
    "Lcom/facebook/feed/plugins/header/subtitle/impl/metaverifiedlabel/MetaVerifiedLabelPlugin;"

internal val AVATAR_UPSELL_COMPONENTS = listOf(
    "AvatarStickerHorizonUpsellQPComponent",
    "InstantAvatarNuxComponent",
    "CommentAvatarStickerUpsellAttachmentComponent",
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

private fun MethodReference.sameAs(method: Method) = definingClass == method.definingClass && name == method.name &&
    returnType == method.returnType && parameterTypes.map { it.toString() } == method.parameterTypes.map { it.toString() }

/** The two landing configuration fields the serializer writes under [EDITS_FLAGS], by flag. */
internal fun editsFlagFields(serializer: ClassDef): List<FieldReference> {
    val fields = EDITS_FLAGS.map { flag ->
        serializer.methods.flatMap { method ->
            val code = method.code()
            code.indices.filter { code[it].string() == flag }.mapNotNull { at ->
                (at + 1..minOf(code.lastIndex, at + 2)).firstNotNullOfOrNull { next ->
                    code[next].takeIf { it.opcode == Opcode.IGET_BOOLEAN }
                        ?.let { (it as ReferenceInstruction).reference as FieldReference }
                }
            }
        }.distinctBy { it.toString() }.singleOrNull()
            ?: refuse("expected $LANDING_SERIALIZER to write one boolean field under \"$flag\"")
    }
    if (fields.any { it.definingClass != LANDING_CONFIG || it.type != "Z" } || fields.distinctBy { it.toString() }.size != 2) {
        refuse("the Edits flags aren't two boolean fields of $LANDING_CONFIG: $fields")
    }
    return fields
}

/** The indices of each read of one of [fields] in [method]. */
internal fun editsFlagReads(method: Method, fields: List<FieldReference>): List<Int> = editsFlagReads(method.code(), fields)

private fun editsFlagReads(code: List<Instruction>, fields: List<FieldReference>): List<Int> {
    val names = fields.mapTo(HashSet()) { it.name }
    val wanted = fields.mapTo(HashSet()) { it.toString() }
    return code.indices.filter {
        code[it].opcode == Opcode.IGET_BOOLEAN &&
            ((code[it] as ReferenceInstruction).reference as FieldReference).let { read -> read.name in names && read.toString() in wanted }
    }
}

/** Where a gate's answer is about to go out as [EDITS_PILL_PARAMETER]: the move-result's index, and whether it's boxed. */
internal data class PillGate(val moveResult: Int, val boxed: Boolean)

/**
 * In [method], each load of [EDITS_PILL_PARAMETER] right after a MobileConfig gate's move-result
 * whose register goes into the next call with the name: the feed's queries pass a boolean, the
 * video queries a Boolean. A name read any other way is left alone. 582's feed query
 * (`LX/1a0;->A01`) asks a string table for the name (`LX/6zX;->A00`, see [tableStringsAsked]),
 * which [resolve] finds, and asks the config's interface in place.
 */
internal fun editsPillGates(method: Method, resolve: (MethodReference) -> Method? = { null }): List<PillGate> =
    editsPillGates(method.code(), resolve)

private fun editsPillGates(code: List<Instruction>, resolve: (MethodReference) -> Method?): List<PillGate> =
    (2 until code.size - 1).mapNotNull { at ->
        val result = code[at - 1]
        val boxed = when {
            result.opcode == Opcode.MOVE_RESULT && readsMobileConfig(code[at - 2], "Z") -> false
            result.opcode == Opcode.MOVE_RESULT_OBJECT && readsMobileConfig(code[at - 2], "Ljava/lang/Boolean;") -> true
            else -> return@mapNotNull null
        }
        val (name, last) = pillNameLoad(code, at, resolve) ?: return@mapNotNull null
        val answer = (result as OneRegisterInstruction).registerA
        val sent = code.getOrNull(last + 1)?.registers().orEmpty()
        if (answer !in sent || name !in sent) return@mapNotNull null
        PillGate(at - 1, boxed)
    }

/**
 * Where [code] loads [EDITS_PILL_PARAMETER] from [at], the register it lands in and the load's last
 * index: a const-string, or an int constant, the string table call that takes it and the
 * move-result-object keeping the answer.
 */
private fun pillNameLoad(code: List<Instruction>, at: Int, resolve: (MethodReference) -> Method?): Pair<Int, Int>? {
    if (code[at].string() == EDITS_PILL_PARAMETER) return (code[at] as OneRegisterInstruction).registerA to at
    if (tableStringAt(code, at + 1, resolve) != EDITS_PILL_PARAMETER) return null
    val result = code.getOrNull(at + 2)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return null
    return (result as OneRegisterInstruction).registerA to at + 2
}

/** Whether [classDef] is the Threads cross-posting capability: its constructor names it. */
internal fun isThreadsCapability(classDef: ClassDef): Boolean = classDef.methods.any {
    it.name == "<init>" && holdsString(it, THREADS_CAPABILITY)
}

/** The capability's should-show answer: its one instance method taking nothing and answering a boolean. */
internal fun threadsShouldShow(capability: ClassDef): Method {
    if (capability.interfaces.size != 1) refuse("${capability.type} implements ${capability.interfaces}, not one capability")
    val answers = capability.methods.filter {
        it.returnType == "Z" && it.parameterTypes.isEmpty() && !it.name.startsWith("<") &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    }
    return answers.singleOrNull() ?: refuse("expected one should-show answer on ${capability.type}, found ${answers.size}")
}

/** The after-post sheet's eligibility check: the handler's method asking [VERIFIED_ELIGIBILITY_QUERY], a suspend answer. */
internal fun verifiedEligibility(handler: ClassDef): Method {
    val checks = handler.methods.filter { holdsString(it, VERIFIED_ELIGIBILITY_QUERY) && it.returnType == "Ljava/lang/Object;" }
    return checks.singleOrNull() ?: refuse("expected one eligibility check in $VERIFIED_SHEET_HANDLER, found ${checks.size}")
}

/** The label plugin's text: its one static method from the post's props to a String. */
internal fun verifiedLabelText(plugin: ClassDef): Method {
    val texts = plugin.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Ljava/lang/String;" && it.parameterTypes.size == 1
    }
    return texts.singleOrNull() ?: refuse("expected one static (props) -> String in $VERIFIED_LABEL_PLUGIN, found ${texts.size}")
}

/** The move-result index after each call to [text] in [method], when [method] isn't the plugin's own. */
internal fun verifiedLabelAsks(method: Method, text: Method): List<Int> = verifiedLabelAsks(method, method.code(), text)

private fun verifiedLabelAsks(method: Method, code: List<Instruction>, text: Method): List<Int> {
    if (method.definingClass == VERIFIED_LABEL_PLUGIN) return emptyList()
    return code.indices.filter { at ->
        code[at].called()?.sameAs(text) == true && code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }.map { it + 1 }
}

/** What draws an avatar upsell component: its one instance method from one object to an object. */
internal fun avatarUpsellDraw(component: ClassDef): Method {
    val draws = component.methods.filter {
        !it.name.startsWith("<") && !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null &&
            it.parameterTypes.size == 1 && it.parameterTypes[0].startsWith("L") && it.returnType.startsWith("L")
    }
    return draws.singleOrNull() ?: refuse("expected one draw method on ${component.type}, found ${draws.size}")
}

/** Puts [hook], a static (X)X, after the move-result at [moveResult], on its own register. */
internal fun MutableMethod.answerAfter(moveResult: Int, hook: String) {
    val instruction = getInstruction<OneRegisterInstruction>(moveResult)
    val register = instruction.registerA
    val move = if (instruction.opcode == Opcode.MOVE_RESULT_OBJECT) "move-result-object" else "move-result"
    addInstructions(
        moveResult + 1,
        """
            invoke-static/range { v$register .. v$register }, $hook
            $move v$register
        """,
    )
}

/** Puts [EDITS_HEADER] after each read of the Edits flags, on the read's own register. Last first. */
internal fun MutableMethod.answerEditsFlags(fields: List<FieldReference>) {
    editsFlagReads(this, fields).asReversed().forEach { read -> answerAfter(read, EDITS_HEADER) }
}

/** Puts the pill hook after each gate in this method, its name loaded plainly or from a table [resolve] finds. Last first. */
internal fun MutableMethod.answerEditsPill(resolve: (MethodReference) -> Method? = { null }) {
    editsPillGates(this, resolve).asReversed().forEach { gate ->
        answerAfter(gate.moveResult, if (gate.boxed) FETCH_EDITS_PILL_BOXED else FETCH_EDITS_PILL)
    }
}

/** Asks [HIDES_AVATAR_UPSELL] first thing and returns null, so nothing is drawn, on a yes. */
internal fun MutableMethod.drawNothingWhenHidden() {
    if (localRegisterCount() < 1) refuse("$definingClass->$name has no local register")
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDES_AVATAR_UPSELL
            move-result v0
            if-eqz v0, :draw
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("draw", getInstruction(0)),
    )
}

private fun BytecodePatchContext.holders(string: String) =
    classDefByStrings(string, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }

/** The one enum naming every one of [names], found by the first of them. */
private fun BytecodePatchContext.enumNaming(names: List<String>): ClassDef {
    val enums = holders(names.first()).filter { isEnumNaming(it, names) }
    return enums.singleOrNull() ?: refuse("expected one enum naming ${names.joinToString()}, found ${enums.size}")
}

/** Every hook of the patch. Refuses unless each part's anchor is found. */
internal fun BytecodePatchContext.hideMetaUpsells() {
    // Imagine, all found before anything changes: the CTA selector's check, the composer's
    // capability and Create story's tools.
    val ctaTable = ctaTable(holders(IMAGINE_ME_PLUGIN))
    val ctaSockets = holders(IMAGINE_CTA_SOCKET).flatMap { methodsHolding(it, IMAGINE_CTA_SOCKET) }
    val ctaCheck = ctaCheck(ctaTable, ctaSockets) { classDefByOrNull(it) }
    val captionPlugin = classDefByOrNull(CAPTION_DEEP_DIVE_PLUGIN) ?: refuse("this Facebook build has no $CAPTION_DEEP_DIVE_PLUGIN")
    val captionGetter = captionDeepDiveGetter(captionPlugin)
    // Both take a local register; checked here, before the Edits, Threads and Meta Verified hooks go in.
    if (ctaCheck.localRegisterCount() < 1) refuse("${ctaCheck.definingClass}->${ctaCheck.name} has no local register")
    if (captionGetter.localRegisterCount() < 1) refuse("${captionGetter.definingClass}->${captionGetter.name} has no local register")
    val composerImagine = enumConstant(enumNaming(COMPOSER_CAPABILITIES), COMPOSER_IMAGINE)
    val storyImagine = enumConstant(enumNaming(STORY_TOOLS_NAMES), STORY_IMAGINE)

    // Guava's copy Create story's tools hand back through. The share sheet's Threads button is
    // shareSheetHookPatch's, a dependency, which finds its own.
    val immutableList = classDefByOrNull(IMMUTABLE_LIST) ?: refuse("this Facebook build has no $IMMUTABLE_LIST")
    if (!definesImmutableCopy(immutableList)) refuse("$IMMUTABLE_LIST has no copyOf(Collection) here")

    // Edits: the header flags, read wherever they're read, and the pill's request parameter.
    val serializer = classDefByOrNull(LANDING_SERIALIZER) ?: refuse("this Facebook build has no $LANDING_SERIALIZER")
    val fields = editsFlagFields(serializer)
    val label = verifiedLabelText(classDefByOrNull(VERIFIED_LABEL_PLUGIN) ?: refuse("this Facebook build has no $VERIFIED_LABEL_PLUGIN"))
    // A string table holds the pill's name like any other holder, so a table call is looked up among them.
    val pillHolders = holders(EDITS_PILL_PARAMETER).associateBy { it.type }
    val pillTable: (MethodReference) -> Method? = { call -> pillHolders[call.definingClass]?.let { resolveStatic(it, call) } }
    val pillMethods = mutableListOf<Pair<String, Method>>()
    val flagReaders = mutableListOf<Pair<String, Method>>()
    val labelAskers = mutableListOf<Pair<String, Method>>()
    val imagineAskers = mutableListOf<Pair<String, Method>>()
    val storyBuilders = mutableListOf<Pair<String, Method>>()
    // Each finds a method naming one of these: the flags' class, a pill holder (the name itself or
    // a table it's asked from), the label plugin and the two enums the constants are read from.
    val named = listOf(LANDING_CONFIG, label.definingClass, composerImagine.definingClass, storyImagine.definingClass) +
        pillHolders.keys
    classDefForEachReferencing(types = named, strings = listOf(EDITS_PILL_PARAMETER)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        for (method in classDef.methods) {
            // Decoded once for the three checks that read the whole body (#216).
            val code = method.code()
            if (editsFlagReads(code, fields).isNotEmpty()) flagReaders += classDef.type to method
            if (editsPillGates(code, pillTable).isNotEmpty()) pillMethods += classDef.type to method
            if (verifiedLabelAsks(method, code, label).isNotEmpty()) labelAskers += classDef.type to method
            if (capabilityAsks(method, composerImagine).isNotEmpty()) imagineAskers += classDef.type to method
            if (storyToolList(method, storyImagine) != null) storyBuilders += classDef.type to method
        }
    }
    if (imagineAskers.isEmpty()) refuse("nothing asks the composer's capabilities about $COMPOSER_IMAGINE")
    val storyBuilder = storyBuilders.singleOrNull()
        ?: refuse("expected one Create story tile builder reading $storyImagine, found ${storyBuilders.map { it.first }}")
    if (flagReaders.none { !it.first.startsWith(LANDING_CONFIG.removeSuffix(";")) }) {
        refuse("nothing outside $LANDING_CONFIG reads the Edits flags")
    }
    flagReaders.forEach { (type, method) -> mutableClassDefBy(type).findMutableMethodOf(method).answerEditsFlags(fields) }

    if (pillMethods.none { (_, method) -> editsPillGates(method, pillTable).any { !it.boxed } }) {
        refuse("expected the feed's query to set \"$EDITS_PILL_PARAMETER\" from a MobileConfig gate, found ${pillMethods.size} gates")
    }
    pillMethods.forEach { (type, method) -> mutableClassDefBy(type).findMutableMethodOf(method).answerEditsPill(pillTable) }

    // Threads: the cross-posting onboarding's should-show answer.
    val capabilities = classDefByStrings(THREADS_CAPABILITY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter(::isThreadsCapability)
    val capability = capabilities.singleOrNull()
        ?: refuse("expected one class whose constructor names \"$THREADS_CAPABILITY\", found ${capabilities.size}")
    mutableClassDefBy(capability.type).findMutableMethodOf(threadsShouldShow(capability))
        .filterBooleanReturns(PATCH, THREADS_ONBOARDING)

    // Meta Verified: the after-post sheet's eligibility, and the dispatcher's ask for the label.
    val handler = classDefByOrNull(VERIFIED_SHEET_HANDLER) ?: refuse("this Facebook build has no $VERIFIED_SHEET_HANDLER")
    mutableClassDefBy(handler.type).findMutableMethodOf(verifiedEligibility(handler))
        .filterObjectReturns(PATCH, META_VERIFIED_SHEET)
    if (labelAskers.size != 1) {
        refuse("expected one place outside the plugin to ask $label for the label, found ${labelAskers.map { it.first }}")
    }
    labelAskers.forEach { (type, method) ->
        val mutable = mutableClassDefBy(type).findMutableMethodOf(method)
        verifiedLabelAsks(mutable, label).asReversed().forEach { mutable.answerAfter(it, META_VERIFIED_LABEL) }
    }

    // Avatar stickers: each upsell component draws nothing on a yes.
    for (spec in AVATAR_UPSELL_COMPONENTS) {
        val components = classDefByStrings(spec, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        val component = components.singleOrNull() ?: refuse("expected one class naming \"$spec\", found ${components.size}")
        mutableClassDefBy(component.type).findMutableMethodOf(avatarUpsellDraw(component)).drawNothingWhenHidden()
    }

    // Imagine: the Imagine me button's check, every capability question, and Create story's tools.
    mutableClassDefBy(ctaCheck.definingClass).methods.single { it.descriptor() == ctaCheck.descriptor() }
        .holdSummaries(ctaTable, HIDES_IMAGINE_CTA, PATCH)
    mutableClassDefBy(captionPlugin.type).methods.single { it.descriptor() == captionGetter.descriptor() }.dropCaptionDeepDive()
    imagineAskers.forEach { (type, method) ->
        val mutable = mutableClassDefBy(type).findMutableMethodOf(method)
        capabilityAsks(mutable, composerImagine).asReversed().forEach { mutable.answerAfter(it, IMAGINE_CAPABILITY) }
    }
    val builder = mutableClassDefBy(storyBuilder.first).findMutableMethodOf(storyBuilder.second)
    builder.filterStoryTools(storyToolList(builder, storyImagine)!!)
}
