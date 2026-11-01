/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.affiliate

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.reels.prompts.INTEREST_PROMPT
import app.morphe.patches.facebook.reels.prompts.isOverlayEnum
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide affiliate product links' four anchors on every Facebook build the bundle declares: the reel
 * overlay's one Z predicate in front of adding AFFILIATE_EYEBROW, the one string lookup the feed
 * video plugin's isFooterHidden… makes, the floating card plugin's one static card reader (on 585 its
 * one static Z card check), and the
 * reel overlay's one filterCtasForCategorySuppression (on 585, which dropped that name, the one list
 * filter beside the overlay's kind sets, called from the overlay's builder). Then the patch: every answer each of them
 * gives goes through the extension on its own register, cast back where the hook answers a wider
 * type, the filter's with the list it was given beside it, and no branch skips the hook. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HideAffiliateLinksFixtureTest {
    private val REC_PILL = "hasAffiliateRecPill"

    /** How many Z predicates the reel overlay asks before adding the card, and how many are [REC_PILL], per build. */
    private val expectedReelChecks = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to (2 to 1),
    )

    /** Whether the CTA filter still carries its kept name, per build: 585 dropped it. */
    private val namedCtaFilter = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to false,
    )

    /** Whether the floating card plugin asks a Z card check instead of reading the model, per build: 585 does. */
    private val commentCardCheck = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to true,
    )

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    /** The index each branch of [code] jumps to, by the branch's index. */
    private fun branchTargets(code: List<Instruction>): Map<Int, Int> {
        var address = 0
        val addresses = code.map { instruction -> address.also { address += instruction.codeUnits } }
        return code.withIndex().filter { it.value is OffsetInstruction && it.value.opcode != Opcode.FILL_ARRAY_DATA }
            .associate { (index, branch) -> index to addresses.indexOf(addresses[index] + (branch as OffsetInstruction).codeOffset) }
    }

    /**
     * Checks that each of [original]'s returns in [patched] reads what [hook] answered for the
     * register it was about to return, with a check-cast to [castTo] in between when given.
     */
    private fun assertFiltered(name: String, original: List<Instruction>, patched: List<Instruction>, hook: String, castTo: String?) {
        val returnOpcodes = setOf(Opcode.RETURN, Opcode.RETURN_OBJECT)
        val returns = original.filter { it.opcode in returnOpcodes }.map { (it as OneRegisterInstruction).registerA }
        assertTrue("$name: never returns", returns.isNotEmpty())
        val added = if (castTo == null) 2 else 3
        assertEquals("$name: $added instructions in front of each return", original.size + added * returns.size, patched.size)
        val patchedReturns = patched.withIndex().filter { it.value.opcode in returnOpcodes }.map { it.index }
        assertEquals("$name: returns", returns.size, patchedReturns.size)
        val injected = mutableSetOf<Int>()
        for ((at, answer) in patchedReturns.zip(returns)) {
            val call = patched[at - added]
            assertEquals("$name: the call before return v$answer", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("$name: the call before return v$answer", hook, ((call as ReferenceInstruction).reference as MethodReference).toString())
            assertEquals("$name: the register handed over", answer, (call as RegisterRangeInstruction).startRegister)
            assertEquals("$name: one register handed over", 1, call.registerCount)
            val result = patched[at - added + 1]
            assertTrue("$name: the extension's answer", result.opcode == Opcode.MOVE_RESULT || result.opcode == Opcode.MOVE_RESULT_OBJECT)
            assertEquals("$name: the extension's answer lands where the return reads it", answer, (result as OneRegisterInstruction).registerA)
            if (castTo != null) {
                val cast = patched[at - 1]
                assertEquals("$name: the cast back", Opcode.CHECK_CAST, cast.opcode)
                assertEquals("$name: the cast back", answer, (cast as OneRegisterInstruction).registerA)
                assertEquals("$name: the cast back", castTo, ((cast as ReferenceInstruction).reference as TypeReference).type)
            }
            assertEquals("$name: the return", answer, (patched[at] as OneRegisterInstruction).registerA)
            for (step in 1 until added) injected += at - added + step
            injected += at
        }
        for ((branch, target) in branchTargets(patched)) {
            assertTrue("$name: the branch at $branch skips the extension", target !in injected)
        }
    }

    /**
     * Checks that each of the CTA filter's returns in [patched] reads what [KEEP_REEL_CTAS] answered
     * for the register it was about to return and the untouched list in [list].
     */
    private fun assertCtaFiltered(name: String, original: List<Instruction>, patched: List<Instruction>, list: Int) {
        val returns = original.filter { it.opcode == Opcode.RETURN_OBJECT }.map { (it as OneRegisterInstruction).registerA }
        assertTrue("$name: never returns", returns.isNotEmpty())
        assertTrue("$name: v$list, the list, is written", original.none { writesRegister(it, list) })
        assertEquals("$name: 2 instructions in front of each return", original.size + 2 * returns.size, patched.size)
        val patchedReturns = patched.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
        assertEquals("$name: returns", returns.size, patchedReturns.size)
        val injected = mutableSetOf<Int>()
        for ((at, answer) in patchedReturns.zip(returns)) {
            val call = patched[at - 2]
            assertEquals("$name: the call before return v$answer", Opcode.INVOKE_STATIC, call.opcode)
            assertEquals("$name: the call before return v$answer", KEEP_REEL_CTAS, ((call as ReferenceInstruction).reference as MethodReference).toString())
            call as FiveRegisterInstruction
            assertEquals("$name: two registers handed over", 2, call.registerCount)
            assertEquals("$name: the answer handed over", answer, call.registerC)
            assertEquals("$name: the full list handed over", list, call.registerD)
            val result = patched[at - 1]
            assertEquals("$name: the extension's answer", Opcode.MOVE_RESULT_OBJECT, result.opcode)
            assertEquals("$name: the extension's answer lands where the return reads it", answer, (result as OneRegisterInstruction).registerA)
            assertEquals("$name: the return", answer, (patched[at] as OneRegisterInstruction).registerA)
            injected += listOf(at - 1, at)
        }
        for ((branch, target) in branchTargets(patched)) {
            assertTrue("$name: the branch at $branch skips the extension", target !in injected)
        }
    }

    /** Right after the flag's read keeps its answer, that answer goes through the extension and comes back to it. */
    private fun assertLabelHooked(name: String, method: Method, flag: Int) {
        val call = flagReads(method).single { it.second == flag }.first
        val code = method.code()
        val answer = (code[call + 1] as OneRegisterInstruction).registerA
        val hook = code[call + 2] as RegisterRangeInstruction
        assertEquals("$name: the hook", KEEP_COMMISSION_LABEL, (hook as ReferenceInstruction).reference.toString())
        assertEquals("$name: the hook's register", answer, hook.startRegister)
        assertEquals("$name: the hook's answer", Opcode.MOVE_RESULT, code[call + 3].opcode)
        assertEquals("$name: the hook's answer's register", answer, (code[call + 3] as OneRegisterInstruction).registerA)
    }

    @Test
    fun `each declared build has the four anchors once, and every answer goes through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val enums = FixtureDex.classesHolding(bundle, INTEREST_PROMPT)
                    .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.filter(::isOverlayEnum)
                assertEquals("$name: reel overlay enums", 1, enums.size)
                val overlay = enums.single()
                val eyebrow = enumConstant(overlay, AFFILIATE_EYEBROW)
                assertNotNull("$name: ${overlay.type} stores no $AFFILIATE_EYEBROW", eyebrow)

                val builders = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.fieldSection.any { it.toString() == eyebrow }
                }) { reelCardChecks(it, eyebrow!!).isNotEmpty() }
                val reelChecks = builders.flatMap { reelCardChecks(it, eyebrow!!) }.map { site -> site.map { it.toString() } }.toSet()
                assertEquals("$name: places asking Z predicates before adding $eyebrow", 1, reelChecks.size)
                // 585 asks the old predicate and the kept hasAffiliateRecPill, and adds the card on either yes.
                val (predicates, kept) = expectedReelChecks.getValue(version)
                assertEquals("$name: Z predicates in front of adding $eyebrow", predicates, reelChecks.single().size)
                assertEquals("$name: the kept rec pill predicate", kept, reelChecks.single().count { it.contains("->$REC_PILL(") })

                val footerChecks = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.methodSection.any { it.name.startsWith(FOOTER_HIDDEN) }
                }) { isFooterHidden(it) }
                val footerIds = footerChecks.flatMap { footerIdCalls(it) }.map { it.toString() }.toSet()
                assertEquals("$name: string lookups in $FOOTER_HIDDEN…", 1, footerIds.size)

                // The CTA filter by its kept name, or (585) by what it does beside the overlay's kind sets.
                val named = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.methodSection.any { it.name == CTA_FILTER }
                }) { isCtaFilter(it) }
                assertEquals("$name: the filter keeps the name $CTA_FILTER", namedCtaFilter.getValue(version), named.isNotEmpty())
                val ctaFilters = named.ifEmpty {
                    val found = mutableListOf<Method>()
                    FixtureDex.forEach(bundle) { dex ->
                        if (dex.typeSection.none { it.toString() == "[${overlay.type}" }) return@forEach
                        for (classDef in dex.classes) {
                            val kindSets = overlayKindSets(classDef, overlay.type)
                            if (kindSets.isNotEmpty()) classDef.methods.filterTo(found) { isCategoryCtaFilter(it, kindSets) }
                        }
                    }
                    found
                }
                assertEquals("$name: CTA filters", 1, ctaFilters.size)
                val ctaFilter = ctaFilters.single()
                if (named.isEmpty()) {
                    // The overlay's own builder, where the card checks sit, runs its kinds through it.
                    assertTrue("$name: the reel overlay's builder never calls ${ctaFilter.name}", builders.any { builder ->
                        builder.code().any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                            MethodUtil.methodSignaturesMatch(call, ctaFilter) && call.definingClass == ctaFilter.definingClass
                        } == true }
                    })
                }
                val list = ctaListRegister(ctaFilter)
                // Every parameter is an object, so the first sits as many registers from the end as there are parameters.
                assertEquals("$name: the list is the filter's first parameter",
                    ctaFilter.implementation!!.registerCount - ctaFilter.parameterTypes.size, list)

                val reelCheck = builders.flatMap { reelCardChecks(it, eyebrow!!) }.first()
                val footerId = footerChecks.flatMap { footerIdCalls(it) }.first()
                // The label's row: exactly one render names it, and it reads one flag.
                val labelRenders = FixtureDex.classesHolding(bundle, COMMISSION_IMPRESSION)
                    .flatMap { it.methods.filter(::isDisclosureRender) }
                assertEquals("$name: renders naming $DISCLOSURE_COMPONENT", 1, labelRenders.size)
                val labelRender = labelRenders.single()
                val labelGate = disclosureGate(labelRender)
                assertNotNull("$name: the render asks no session check first", labelGate)
                val types = (builders + footerChecks).map { it.definingClass }.toSet() + reelCheck.map { it.definingClass } +
                    setOf(footerId.definingClass, FLOATING_CARD_PLUGIN, ctaFilter.definingClass, labelRender.definingClass,
                        labelGate!!.definingClass)
                val classes = FixtureDex.classes(bundle, types)
                assertEquals("$name: classes read", types, classes.keys)
                val plugin = classes.getValue(FLOATING_CARD_PLUGIN)
                val readers = plugin.methods.filter(::isCommentCardReader)
                val cardChecks = plugin.methods.filter(::isCommentCardCheck)
                val asksCheck = commentCardCheck.getValue(version)
                assertEquals("$name: static card readers on the floating card plugin", if (asksCheck) 0 else 1, readers.size)
                if (asksCheck) assertEquals("$name: static Z card checks on the floating card plugin", 1, cardChecks.size)
                val commentCard = if (asksCheck) cardChecks.single() else readers.single()

                fun original(reference: MethodReference) = classes.getValue(reference.definingClass).methods
                    .single { MethodUtil.methodSignaturesMatch(it, reference) }.code()
                val reelOriginals = reelCheck.map(::original)
                val originals = listOf(original(footerId), commentCard.code(), original(ctaFilter))

                val context = PatchContexts.of(listOf(overlay) + classes.values + ExtensionDex.classDef(SETTINGS_STATUS))
                hideAffiliateLinksPatch.execute(context)

                fun patched(reference: MethodReference) = context.mutableClassDefBy(reference.definingClass).methods
                    .single { MethodUtil.methodSignaturesMatch(it, reference) }.code()
                reelCheck.zip(reelOriginals).forEach { (check, before) ->
                    assertFiltered("$name reel card check ${check.name}", before, patched(check), KEEP_REEL_CARD, null)
                }
                assertFiltered("$name footer id", originals[0], patched(footerId), KEEP_FOOTER, null)
                if (asksCheck) {
                    assertFiltered("$name comment card check", originals[1], patched(commentCard), KEEP_COMMENT_CARD_CHECK, null)
                } else {
                    assertFiltered("$name comment card", originals[1], patched(commentCard), KEEP_COMMENT_CARD, commentCard.returnType)
                }
                assertCtaFiltered("$name CTA filter", originals[2], patched(ctaFilter), list)
                val labelFlag = flagReads(labelRender).single().second
                for (read in listOf<MethodReference>(labelRender, labelGate)) {
                    val hooked = context.mutableClassDefBy(read.definingClass).methods
                        .single { MethodUtil.methodSignaturesMatch(it, read) }
                    assertLabelHooked("$name label ${read.definingClass}", hooked, labelFlag)
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "affiliateLinks" }
                assertEquals("$name: SettingsStatus.affiliateLinks() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
