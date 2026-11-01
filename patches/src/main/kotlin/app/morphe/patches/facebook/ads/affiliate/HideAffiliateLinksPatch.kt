/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/a934b40608e5645cf119dd4101ded7767a9cdc73/patches/src/main/kotlin/app/andrewliang/patches/facebook/hideaffiliatelinks/HideAffiliateLinksPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: each answer goes through a switch instead of
 * being replaced.
 */
package app.morphe.patches.facebook.ads.affiliate

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.filterBooleanReturns
import app.morphe.patches.facebook.misc.extension.filterObjectReturns
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.reels.prompts.findOverlayEnum
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil

internal const val PATCH = "Hide affiliate product links"

/** The reel overlay enum's constant for the product card. */
internal const val AFFILIATE_EYEBROW = "AFFILIATE_EYEBROW"

/** The comment sheet's floating product card, a plugin that keeps its class name. */
internal const val FLOATING_CARD_PLUGIN = "Lcom/facebook/feedback/comments/plugins/indicatorpill/" +
    "organicaffiliatefloatingcta/OrganicAffiliateFloatingCtaPlugin;"

/** The start of the name the feed video plugin's footer check keeps. */
internal const val FOOTER_HIDDEN = "isFooterHidden\$"

internal const val STRING = "Ljava/lang/String;"

/**
 * The reel overlay's own filter of its call-to-action kinds by category, a static method that keeps
 * its name: (kinds, categories to leave out) answers the kinds left, or null when no category is
 * left out and the overlay keeps its list.
 */
internal const val CTA_FILTER = "filterCtasForCategorySuppression"

internal const val LIST = "Ljava/util/List;"
internal const val SET = "Ljava/util/Set;"

internal const val AFFILIATE_LINKS = "Lapp/morphe/extension/facebook/ads/AffiliateLinks;"
internal const val KEEP_REEL_CARD = "$AFFILIATE_LINKS->keepReelCard(I)Z"
internal const val KEEP_FOOTER = "$AFFILIATE_LINKS->keepFooter($STRING)$STRING"
internal const val KEEP_COMMENT_CARD = "$AFFILIATE_LINKS->keepCommentCard(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val KEEP_COMMENT_CARD_CHECK = "$AFFILIATE_LINKS->keepCommentCard(I)Z"
internal const val KEEP_REEL_CTAS = "$AFFILIATE_LINKS->keepReelCtas($LIST$LIST)$LIST"
internal const val KEEP_COMMISSION_LABEL = "$AFFILIATE_LINKS->keepCommissionLabel(I)Z"

/** What the reel's disclosure row keeps: the component's name, and what it logs for the label. */
internal const val DISCLOSURE_COMPONENT = "FbShortsViewerDisclosureComponent"
internal const val COMMISSION_IMPRESSION = "commission_label_impression"

internal const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

/**
 * The product cards of the shop links a creator attaches to a post go, in the three places
 * Facebook shows them. Each place asks its own question first. The "Commission eligible" label has
 * a switch of its own, off by default.
 *
 * - The label (#329) is a row of the reel's disclosure component ([isDisclosureRender], 585 arm64
 *   `LX/0CRr;->render`, armv7 `LX/0CRu;->render`), which reads one boolean flag off the reel for it.
 *   The render first asks a static check (arm64 `LX/09b5;->A00`, armv7 `LX/09bA;->A00`) whether the
 *   reel has any disclosure at all, and that check reads the same flag. Both answers go through
 *   the extension, so a reel with nothing else to disclose loses the row and not only its text.
 *
 * - On a reel, the card is the overlay item AFFILIATE_EYEBROW of the reel overlay enum (580
 *   `LX/7fq;`, 577 `LX/7ZW;`). The overlay adds it to its list only when a static predicate (580
 *   `LX/8O4;->A0C`, 577 `LX/8qp;->A0C`) says yes, and asks the same predicate before building the
 *   card. 585 asks two and adds the card when either says yes: in arm64's `LX/0B8F;->A0C` (overlay
 *   enum `LX/09SZ;`, armv7 `LX/09Se;`) the old predicate `LX/0BX4;->A0B` (session, story, player
 *   origin), which the overlay's render asks again, lands in a flag, and the kept
 *   `LX/0B7J;->hasAffiliateRecPill` is asked after it. Both go through the extension.
 * - Under a feed post, the card is the attachment's server-built (Bloks) footer. One lookup (580
 *   `LX/30r;->A00`, 577 `LX/33g;->A00`) answers its id, or null for an attachment without one. The
 *   footer builder and the video plugin both ask it, and with null the builder makes the plain
 *   footer, which is empty for these posts. Facebook logs every render of the Bloks footer as
 *   affiliate_footer_rendered.
 * - In the comment sheet, the card that floats over the comment box is OrganicAffiliateFloatingCtaPlugin.
 *   Its one static reader of the card model answers null for a post without a card, and the sheet
 *   then leaves the plugin out. 585 asks a question instead: the plugin's one static method is a Z
 *   predicate (arm64 `A00(LX/0DkD;LX/0B0Z;)Z`, armv7 `A00(LX/0DkC;LX/0B0c;)Z`) that walks the
 *   model's chain and answers yes only when the card is there, and its one caller (arm64
 *   `LX/0B0e;->A0G`) leaves the plugin out on a no.
 * - On a reel again, the "Shop now" card above the creator's name (#89, a creator's own product on
 *   581) is one of the overlay's call-to-action kinds: products tagged on the reel (PRODUCT_TAGGING,
 *   PRODUCT_TAGGING_V2), the creator's storefront, Shop similar, or the affiliate card itself. The
 *   overlay sorts its kinds into categories and runs them through [CTA_FILTER], which Facebook's
 *   server uses to leave a whole category out (581 `LX/Aum;`, reached from the video info renderer
 *   through a list method that collects the categories to leave out, and a null answer keeps the
 *   list). Every answer it gives goes through the extension with the full list beside it, and the
 *   extension leaves the shop kinds out by their names. 585 drops the kept name and folds the
 *   category lookup in: arm64's `LX/09dN;->A06(List)List`, an instance method of the class whose
 *   static initializer fills four Sets with overlay kinds, reads the MobileConfig switches itself,
 *   answers null when none is on and otherwise drops each kind whose set's category is switched on.
 *   The overlay's builder (`LX/0B8F;->A0C`) and the rec pill's class (`LX/0B7J;->A00`) both call it.
 *
 * Every answer of those four goes through the extension, which turns a yes into a no, an id or a
 * model into null, and a list of reel cards into one without the shop kinds while the switch is on.
 */
@Suppress("unused")
val hideAffiliateLinksPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide affiliate product links",
    description = "Removes the shopping cards that affiliate links add to reels, feed posts and comments, plus " +
        "Shop now cards on reels, so you see fewer sales pitches while you scroll. On by default. Turn it off in " +
        "Hushfacebook settings > News feed.",
) {
    category("Ads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val anchors = findAffiliateAnchors()
        val label = findCommissionLabel()
        for (read in listOf(label.render, label.gate)) {
            mutableClassDefBy(read.definingClass).findMutableMethodOf(read).hookCommissionFlag(label.flag)
        }
        for (check in anchors.reelCardChecks) {
            mutableClassDefBy(check.definingClass).findMutableMethodOf(check).filterBooleanReturns(PATCH, KEEP_REEL_CARD)
        }
        mutableClassDefBy(anchors.footerId.definingClass).findMutableMethodOf(anchors.footerId)
            .filterObjectReturns(PATCH, KEEP_FOOTER)
        val commentCard = mutableClassDefBy(FLOATING_CARD_PLUGIN).findMutableMethodOf(anchors.commentCard)
        if (isCommentCardCheck(anchors.commentCard)) commentCard.filterBooleanReturns(PATCH, KEEP_COMMENT_CARD_CHECK)
        else commentCard.filterObjectReturns(PATCH, KEEP_COMMENT_CARD)
        mutableClassDefBy(anchors.ctaFilter.definingClass).findMutableMethodOf(anchors.ctaFilter)
            .filterCtaReturns()
        enableStatus("affiliateLinks")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The methods the patch hooks: every reel card predicate, then one of each other kind. */
internal class AffiliateAnchors(
    val reelCardChecks: List<MethodReference>,
    val footerId: MethodReference,
    val commentCard: Method,
    val ctaFilter: Method,
)

/** The reel disclosure row's render, the check in front of it, and the flag both read. */
internal class CommissionLabelAnchors(val render: Method, val gate: Method, val flag: Int)

/** Whether [method] is the reel disclosure row's render, which draws the "Commission eligible" label. */
internal fun isDisclosureRender(method: Method): Boolean =
    method.name == "render" && holdsString(method, DISCLOSURE_COMPONENT) && holdsString(method, COMMISSION_IMPRESSION)

/** The check [render] asks first: its first static call answering a boolean about the session. */
internal fun disclosureGate(render: Method): MethodReference? =
    render.implementation?.instructions?.firstNotNullOfOrNull { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        call?.takeIf {
            instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == "Z" &&
                it.parameterTypes.firstOrNull()?.toString() == FB_USER_SESSION
        }
    }

/** Each read in [method] of a reel's boolean flag by its number, as the read's index and the number. */
internal fun flagReads(method: Method): List<Pair<Int, Int>> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        val read = code[at] as? FiveRegisterInstruction
        if (call == null || read == null || call.name != "getBooleanValue" || call.returnType != "Z" ||
            call.parameterTypes.map { it.toString() } != listOf("I")
        ) return@mapNotNull null
        val number = (at - 1 downTo 0).map(code::get).firstOrNull {
            it is NarrowLiteralInstruction && it is OneRegisterInstruction && it.registerA == read.registerD
        } as? NarrowLiteralInstruction
        number?.let { at to it.narrowLiteral }
    }
}

/** The label's render and check, each sure to read the flag once. Changes nothing. */
internal fun BytecodePatchContext.findCommissionLabel(): CommissionLabelAnchors {
    val renders = classDefByStrings(COMMISSION_IMPRESSION, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter(::isDisclosureRender) }
    val render = renders.singleOrNull() ?: refuse("expected one render naming $DISCLOSURE_COMPONENT, found ${renders.size}")
    val flag = flagReads(render).singleOrNull()?.second
        ?: refuse("expected one flag read in ${render.definingClass}->render")
    val gateCall = disclosureGate(render) ?: refuse("${render.definingClass}->render asks no session check first")
    val gate = classDefBy(gateCall.definingClass).methods.firstOrNull { MethodUtil.methodSignaturesMatch(it, gateCall) }
        ?: refuse("${gateCall.definingClass} has no ${gateCall.name}")
    for (method in listOf(render, gate)) {
        val read = flagReads(method).singleOrNull { it.second == flag }?.first
            ?: refuse("expected one read of the flag in ${method.definingClass}->${method.name}")
        val answer = method.implementation!!.instructions.elementAt(read + 1)
        if (answer.opcode != Opcode.MOVE_RESULT) refuse("the flag read in ${method.definingClass}->${method.name} keeps no answer")
    }
    return CommissionLabelAnchors(render, gate, flag)
}

/**
 * Hands the answer to the read of [flag] through the extension. Plain [addInstructions] on purpose: a
 * jump that lands right after the read comes from a path that never read the flag, and it stays on
 * Facebook's next instruction with its own answer.
 */
private fun MutableMethod.hookCommissionFlag(flag: Int) {
    val read = flagReads(this).single { it.second == flag }.first
    val answer = (getInstruction(read + 1) as OneRegisterInstruction).registerA
    addInstructions(
        read + 2,
        """
            invoke-static/range { v$answer .. v$answer }, $KEEP_COMMISSION_LABEL
            move-result v$answer
        """,
    )
}

/** Whether [method] is the reel overlay's [CTA_FILTER]: static, (List, Set) to List, with code. */
internal fun isCtaFilter(method: Method): Boolean =
    method.name == CTA_FILTER && AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.map { it.toString() } == listOf(LIST, SET) && method.returnType == LIST &&
        method.implementation != null

/**
 * The static Set fields of [classDef] that its static initializer fills from an array of [overlay]
 * constants: the reel CTA kinds sorted into categories, which 585 keeps beside the CTA filter.
 */
internal fun overlayKindSets(classDef: ClassDef, overlay: String): Set<String> {
    val init = classDef.methods.firstOrNull { it.name == "<clinit>" }?.implementation ?: return emptySet()
    val sets = mutableSetOf<String>()
    var filled = false
    for (instruction in init.instructions) {
        when (instruction.opcode) {
            Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE ->
                filled = ((instruction as ReferenceInstruction).reference as TypeReference).type == "[$overlay"
            Opcode.SPUT_OBJECT -> {
                val field = (instruction as ReferenceInstruction).reference as FieldReference
                if (filled && field.definingClass == classDef.type && field.type == SET) sets += field.toString()
                filled = false
            }
            else -> Unit
        }
    }
    return sets
}

/**
 * Whether [method] is 585's CTA filter, which lost [CTA_FILTER]'s kept name and works out the
 * categories to leave out itself: it takes the list of kinds first and answers a list, reads one of
 * [kindSets] and asks `Set.contains`, and answers null straight from a `const/4 0` when there's no
 * category to leave out, which the extension reads as "the list as it came".
 */
internal fun isCategoryCtaFilter(method: Method, kindSets: Set<String>): Boolean {
    if (method.returnType != LIST || method.parameterTypes.firstOrNull()?.toString() != LIST) return false
    val code = method.implementation?.instructions?.toList() ?: return false
    val readsKinds = code.any { it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() in kindSets }
    val asksContains = code.any { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        call != null && call.definingClass == SET && call.name == "contains"
    }
    val answersNull = (1 until code.size).any { at ->
        val load = code[at - 1]
        code[at].opcode == Opcode.RETURN_OBJECT && load.opcode == Opcode.CONST_4 &&
            (load as NarrowLiteralInstruction).narrowLiteral == 0 &&
            (load as OneRegisterInstruction).registerA == (code[at] as OneRegisterInstruction).registerA
    }
    return readsKinds && asksContains && answersNull
}

/**
 * The register of [method]'s first parameter, the list of kinds, refusing unless every return and
 * that register fit a four-bit invoke and nothing in the method writes the register: each return
 * hands the extension the list as the method got it.
 */
internal fun ctaListRegister(method: Method): Int {
    val implementation = method.implementation!!
    // 585's filter is an instance method, so the list sits after `this` rather than first.
    val list = method.parameterRegisterNumber(0)
    for (instruction in implementation.instructions) {
        if (writesRegister(instruction, list)) {
            refuse("$CTA_FILTER writes v$list, its list of kinds, with ${instruction.opcode.name}")
        }
        if (instruction.opcode == Opcode.RETURN_OBJECT && (instruction as OneRegisterInstruction).registerA > 15) {
            refuse("$CTA_FILTER returns v${instruction.registerA}, past what a four-bit invoke reaches")
        }
    }
    if (list > 15) refuse("$CTA_FILTER keeps its list of kinds in v$list, past what a four-bit invoke reaches")
    return list
}

/** Whether [instruction] writes [register], a wide write into the register below it included. */
internal fun writesRegister(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister()) return false
    val first = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (instruction.opcode.setsWideRegister() && first + 1 == register)
}

/** Hands each of the filter's answers to the extension beside the full list, and returns what it gives back. */
private fun MutableMethod.filterCtaReturns() {
    val list = ctaListRegister(this)
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    if (returns.isEmpty()) refuse("$definingClass->$name never returns")
    for (index in returns.asReversed()) {
        val answer = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static { v$answer, v$list }, $KEEP_REEL_CTAS
                move-result-object v$answer
            """,
        )
    }
}

/**
 * The static Z predicates [method] asks before it adds [eyebrow] to a list, one list for each place
 * it does. Up to 584 a place asks one: `invoke-static … Z`, `move-result vX`, `if-eqz vX` past the
 * add, `sget-object` of the constant, then a call to `add` with it. 585 asks two and adds the card
 * when either says yes: the first answer lands in a flag (`const/4 vF 1`, `if-nez`, `const/4 vF 0`)
 * whose `if-nez` jumps onto the `sget-object`, and the second's `if-eqz` jumps past the add. A place
 * counts only when every branch onto or past the add reads a static Z answer and nothing else jumps
 * onto the constant.
 */
internal fun reelCardChecks(method: Method, eyebrow: String): List<List<MethodReference>> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { index ->
        if (code[index].opcode != Opcode.SGET_OBJECT) return@mapNotNull null
        if ((code[index] as ReferenceInstruction).reference.toString() != eyebrow) return@mapNotNull null
        val add = (code.getOrNull(index + 1) as? ReferenceInstruction)?.reference as? MethodReference
        if (add?.name != "add") return@mapNotNull null
        val jumps = branchEdges(code)
        // The last answer's no skips the add.
        var at = index - 1
        if (code.getOrNull(at)?.opcode != Opcode.IF_EQZ) return@mapNotNull null
        if (jumps.none { (from, to) -> from == at && to > index + 1 }) return@mapNotNull null
        val predicates = mutableListOf(staticAnswer(code, jumps, at) ?: return@mapNotNull null)
        // 585: each earlier answer's yes jumps straight onto the constant, and nothing else may.
        val onto = jumps.filter { (_, to) -> to == index }.map { it.first }.toSet()
        while (--at in onto && code[at].opcode == Opcode.IF_NEZ) {
            predicates += staticAnswer(code, jumps, at) ?: return@mapNotNull null
        }
        if (onto.any { it !in at + 1 until index }) return@mapNotNull null
        predicates.asReversed().distinctBy { it.toString() }
    }
}

/**
 * The static Z call whose answer the branch at [branch] tests: read straight from its `move-result`,
 * or through a flag set to 1 and then cleared unless the answer was yes. Null for anything else, or
 * when something outside that stretch of code jumps into it anywhere but onto the flag's clear.
 */
private fun staticAnswer(code: List<Instruction>, jumps: List<Pair<Int, Int>>, branch: Int): MethodReference? {
    fun lastWrite(register: Int, before: Int) = (before - 1 downTo 0).firstOrNull { writesRegister(code[it], register) }
    fun isConst(index: Int, register: Int, value: Int) = code.getOrNull(index)?.let {
        it.opcode == Opcode.CONST_4 && (it as OneRegisterInstruction).registerA == register &&
            (it as NarrowLiteralInstruction).narrowLiteral == value
    } == true
    fun answer(register: Int, before: Int): Pair<Int, MethodReference>? {
        val result = lastWrite(register, before) ?: return null
        if (result < 1 || code[result].opcode != Opcode.MOVE_RESULT) return null
        val call = code[result - 1]
        if (call.opcode != Opcode.INVOKE_STATIC && call.opcode != Opcode.INVOKE_STATIC_RANGE) return null
        return ((call as ReferenceInstruction).reference as MethodReference).takeIf { it.returnType == "Z" }?.let { result - 1 to it }
    }
    val register = (code[branch] as OneRegisterInstruction).registerA
    val write = lastWrite(register, branch) ?: return null
    val flag = isConst(write, register, 0)
    val (start, call) = if (flag) {
        // 585's flag: const/4 vF 1, if-nez on the answer past the clear, const/4 vF 0.
        val test = write - 1
        if (!isConst(test - 1, register, 1) || code[test].opcode != Opcode.IF_NEZ) return null
        if (jumps.none { (from, to) -> from == test && to == write + 1 }) return null
        answer((code[test] as OneRegisterInstruction).registerA, test - 1) ?: return null
    } else {
        answer(register, branch) ?: return null
    }
    // Every way into the stretch from the call to the branch starts inside it, except 585's
    // short-circuit checks in front of the predicate, which land on the flag's clear and so say no.
    if (jumps.any { (from, to) -> to in start + 1..branch && from !in start until branch && !(flag && to == write) }) return null
    return call
}

/** Each branch and switch case of [code] as (index it jumps from, index it lands on). */
private fun branchEdges(code: List<Instruction>): List<Pair<Int, Int>> {
    val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
    val indexOf = addresses.withIndex().associate { (index, address) -> address to index }
    return code.withIndex().flatMap { (index, instruction) ->
        if (instruction !is OffsetInstruction || instruction.opcode == Opcode.FILL_ARRAY_DATA) return@flatMap emptyList()
        val target = addresses[index] + instruction.codeOffset
        if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
            (code[indexOf.getValue(target)] as SwitchPayload).switchElements
                .mapNotNull { element -> indexOf[addresses[index] + element.offset]?.let { index to it } }
        } else {
            listOfNotNull(indexOf[target]?.let { index to it })
        }
    }
}

/** Whether [method] is the feed video plugin's footer check, which keeps its name. */
internal fun isFooterHidden(method: Method): Boolean =
    method.name.startsWith(FOOTER_HIDDEN) && method.returnType == "Z" && method.implementation != null

/** The static calls [method] makes that answer a string: in the footer check, only the footer id lookup. */
internal fun footerIdCalls(method: Method): List<MethodReference> =
    method.implementation!!.instructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        ((instruction as ReferenceInstruction).reference as MethodReference).takeIf { it.returnType == STRING }
    }

/** Whether [method] is the floating card plugin's reader of the card model: static, one argument, an object back. */
internal fun isCommentCardReader(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 1 &&
        method.returnType.startsWith("L") && method.implementation != null

/**
 * Whether [method] is 585's form of that reader: static, object arguments only, a boolean back.
 * Why: 585 folded the model read into a yes-or-no question, and the plugin keeps no other static method.
 */
internal fun isCommentCardCheck(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.parameterTypes.isNotEmpty() &&
        method.parameterTypes.all { it.startsWith("L") } && method.implementation != null

/** Finds all three anchors, each exactly once, in the app's classes. Changes nothing. */
internal fun BytecodePatchContext.findAffiliateAnchors(): AffiliateAnchors {
    val overlay = findOverlayEnum()
    val eyebrow = enumConstant(overlay, AFFILIATE_EYEBROW) ?: refuse("${overlay.type} stores no $AFFILIATE_EYEBROW constant")

    val reelChecks = mutableMapOf<String, List<MethodReference>>()
    val footerIds = mutableMapOf<String, MethodReference>()
    val ctaFilters = mutableListOf<Method>()
    // The footer check and the CTA filter by name, which reads code only where the name fits.
    classDefForEach { classDef: ClassDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
        for (method in classDef.methods) {
            if (isFooterHidden(method)) footerIdCalls(method).associateByTo(footerIds) { it.toString() }
            if (isCtaFilter(method)) ctaFilters += method
        }
    }
    // A reel card check reads the eyebrow off the overlay enum, and 585's CTA filter sits in the
    // class that sorts the overlay's kinds into static sets.
    val categoryFilters = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(overlay.type)) { classDef: ClassDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        for (method in classDef.methods) reelCardChecks(method, eyebrow).associateByTo(reelChecks) { it.joinToString(" or ") }
        val kindSets = overlayKindSets(classDef, overlay.type)
        if (kindSets.isNotEmpty()) categoryFilters += classDef.methods.filter { isCategoryCtaFilter(it, kindSets) }
    }
    // The kept name up to 584; 585 dropped it, so its filter is found by what it does.
    if (ctaFilters.isEmpty()) ctaFilters += categoryFilters
    val reelCheck = reelChecks.values.singleOrNull() ?: refuse(
        "expected one place asking Z predicates before adding $eyebrow, found ${reelChecks.keys.ifEmpty { setOf("none") }}",
    )
    val footerId = footerIds.values.singleOrNull() ?: refuse(
        "expected one string lookup in $FOOTER_HIDDEN…, found ${footerIds.keys.ifEmpty { setOf("none") }}",
    )
    // The model reader up to 584; 585 has none and asks its Z check instead.
    val readers = classDefBy(FLOATING_CARD_PLUGIN).methods.filter(::isCommentCardReader)
        .ifEmpty { classDefBy(FLOATING_CARD_PLUGIN).methods.filter(::isCommentCardCheck) }
    val commentCard = readers.singleOrNull()
        ?: refuse("expected one static card reader or Z card check on $FLOATING_CARD_PLUGIN, found ${readers.size}")
    val ctaFilter = ctaFilters.singleOrNull()
        ?: refuse(
            "expected one $CTA_FILTER($LIST$SET)$LIST, or one ($LIST…)$LIST beside the overlay's kind sets, found " +
                ctaFilters.joinToString { "${it.definingClass}->${it.name}" }.ifEmpty { "none" },
        )
    ctaListRegister(ctaFilter)
    return AffiliateAnchors(reelCheck, footerId, commentCard, ctaFilter)
}

