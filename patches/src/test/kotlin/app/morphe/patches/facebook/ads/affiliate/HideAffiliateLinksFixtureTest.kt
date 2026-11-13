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
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
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
 * one static Z card check, on 586 the comment sheet's plugin gate's one case for it), and the
 * reel overlay's one filterCtasForCategorySuppression (on 585, which dropped that name, the one list
 * filter beside the overlay's kind sets, called from the overlay's builder), plus the one store of a
 * profile's shop banner tag, which the reel's author tap calls (on 587 the one method that reads the tag
 * back and compares it with the reel's). Then the patch: every answer each of them
 * gives goes through the extension on its own register, cast back where the hook answers a wider
 * type, the filter's with the list it was given beside it, and no branch skips the hook. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HideAffiliateLinksFixtureTest {
    private val REC_PILL = "hasAffiliateRecPill"

    /** The tag a reel's author tap hands the profile it opens. */
    private val REEL_ENTRY_TAG = "REELS_VDD"

    /** How many Z predicates the reel overlay asks before adding the card, and how many are [REC_PILL], per build. */
    private val expectedReelChecks = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to (2 to 1),
    )

    /** Whether the CTA filter still carries its kept name, per build: 585 dropped it. */
    private val namedCtaFilter = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to false,
    )

    /**
     * How each build asks whether a comment sheet has its floating card: READER, the plugin's static
     * model reader (up to 584), CHECK, its static Z check (585), or CASE, the plugin gate's case for
     * the plugin (586, which keeps no static method on the plugin). 586's Android 9 build keeps the
     * plugin's check, so it's CHECK there.
     */
    private val commentCardForm = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to "CASE",
    )
    private val android9CommentCardForm = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to "CHECK",
    )

    /**
     * Whether a build's banner tag is hooked where the profile reads it back (587, which names the key
     * through a string table where it stores it, arm64 `LX/0Bb0;->A01`) rather than in its one store.
     */
    private val profileEntryRead = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to true,
    )

    /** Per build and ABI, how many times the profile's reader reads the banner tag (arm64 587: `LX/0Bb0;->A01` twice). */
    private val profileEntryReadCount = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf("arm64-v8a" to 2, "armeabi-v7a" to 2),
    )

    private val INSTANCE_CALLS = setOf(
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
        Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE,
    )
    private val ENDS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.THROW)

    /**
     * Whether a call in [code] takes the value read at [after] into [value] as its receiver before the
     * register is written again or the method ends. The extension answers null for a reel's tag, so
     * the reader must only hand it on (`"REELS_VDD".equals(value)`), never call on it.
     */
    private fun callsOnRead(code: List<Instruction>, after: Int, value: Int): Boolean {
        for (instruction in code.subList(after, code.size)) {
            val receiver = when (instruction) {
                is FiveRegisterInstruction -> instruction.registerC.takeIf { instruction.registerCount > 0 }
                is RegisterRangeInstruction -> instruction.startRegister.takeIf { instruction.registerCount > 0 }
                else -> null
            }
            if (instruction.opcode in INSTANCE_CALLS && receiver == value) return true
            if (writesRegister(instruction, value) || instruction.opcode in ENDS) return false
        }
        return false
    }

    private val PAYLOADS = setOf(Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD)

    /** The code a stock instruction's opcode stands for, with a goto that an insert widened read as the goto it was. */
    private fun kind(opcode: Opcode): Opcode = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode

    /**
     * Checks that the plugin gate's case for the floating card, at [at] in [gate], starts by asking
     * [KEEP_COMMENT_CARD_CHECK] with a yes and answers its no, that the switch still lands on that
     * question, and that the rest of [gate] is what [original] was.
     */
    private fun assertCaseHooked(name: String, original: Method, gate: Method, at: Int) {
        val before = original.code().withoutPadding()
        val after = gate.code()
        val padding = after.size - after.withoutPadding().size
        assertTrue("$name: padding only in front of a payload, at most one", padding <= 1 && after.indexOfFirst { it.opcode in PAYLOADS } > at + 5)
        assertEquals("$name: 6 instructions at the case", before.size + 6, after.size - padding)
        val asked = (after[at] as OneRegisterInstruction).registerA
        assertEquals("$name: the yes handed over", Opcode.CONST_4, after[at].opcode)
        assertEquals("$name: the yes handed over", 1, (after[at] as NarrowLiteralInstruction).narrowLiteral)
        assertTrue("$name: v$asked is a parameter", asked < original.localRegisterCount())
        val call = after[at + 1] as FiveRegisterInstruction
        assertEquals("$name: the hook", KEEP_COMMENT_CARD_CHECK, ((call as ReferenceInstruction).reference as MethodReference).toString())
        assertEquals("$name: one register handed over", 1, call.registerCount)
        assertEquals("$name: the register handed over", asked, call.registerC)
        assertEquals("$name: the hook's answer", Opcode.MOVE_RESULT, after[at + 2].opcode)
        assertEquals("$name: the hook's answer", asked, (after[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$name: a yes goes on", Opcode.IF_NEZ, after[at + 3].opcode)
        assertEquals("$name: a yes goes on", asked, (after[at + 3] as OneRegisterInstruction).registerA)
        assertEquals("$name: a yes goes on to Facebook's check", at + 5, branchTargets(after)[at + 3])
        assertEquals("$name: a no is the answer", Opcode.RETURN, after[at + 4].opcode)
        assertEquals("$name: a no is the answer", asked, (after[at + 4] as OneRegisterInstruction).registerA)
        assertEquals("$name: Facebook's check follows", Opcode.NOP, after[at + 5].opcode)
        assertEquals("$name: the rest is stock", before.map { kind(it.opcode) },
            (after.take(at) + after.drop(at + 6)).withoutPadding().map { kind(it.opcode) })
        assertEquals("$name: the switch lands on the question", lastIntSwitch(original).mapValues { (_, case) ->
            if (case > at) case + 6 else case
        }, lastIntSwitch(gate))
    }

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    /** [this] without the nop the dex writer puts in front of a payload to keep it 4-byte aligned. */
    private fun List<Instruction>.withoutPadding(): List<Instruction> =
        filterIndexed { index, instruction -> instruction.opcode != Opcode.NOP || getOrNull(index + 1)?.opcode !in PAYLOADS }

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
    private fun assertLabelHooked(name: String, method: Method, flag: Long) {
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
                            if (kindSets.isEmpty()) continue
                            // 586 sorts each kind through a static helper that reads the sets.
                            val sorters = overlayKindSorters(classDef, overlay.type, kindSets)
                            classDef.methods.filterTo(found) { isCategoryCtaFilter(it, kindSets, sorters) }
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
                // The label's row: exactly one render names it, and it reads one flag. The Android 9
                // build asks a string table for the impression's name, so the table goes along.
                val labelTables = FixtureDex.classesHolding(bundle, COMMISSION_IMPRESSION)
                    .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> labelTables[call.definingClass]?.let { resolveStatic(it, call) } }
                val labelRenders = FixtureDex.classesHolding(bundle, DISCLOSURE_COMPONENT)
                    .flatMap { classDef -> classDef.methods.filter { isDisclosureRender(it, resolve) } }
                assertEquals("$name: renders naming $DISCLOSURE_COMPONENT", 1, labelRenders.size)
                val labelRender = labelRenders.single()
                val labelGate = disclosureGate(labelRender)
                assertNotNull("$name: the render asks no session check first", labelGate)
                // The profile's shop banner tag: exactly one method stores it in a bundle. 587 names the key
                // through a string table where it stores it, and exactly one method reads it back instead.
                val entryHolders = FixtureDex.classesHolding(bundle, PROFILE_ENTRY_POINT)
                val entryWriters = entryHolders.flatMap { classDef -> classDef.methods.filter { profileEntryWrites(it).isNotEmpty() } }
                val readsEntry = profileEntryRead.getValue(version)
                assertEquals("$name: methods storing $PROFILE_ENTRY_POINT", if (readsEntry) 0 else 1, entryWriters.size)
                val entryWriter = if (readsEntry) {
                    // The reader compares what it read with the reel's tag itself (isProfileEntryReader), so
                    // that is the positive control here.
                    val entryReaders = entryHolders.flatMap { classDef -> classDef.methods.filter(::isProfileEntryReader) }
                    assertEquals("$name: methods reading $PROFILE_ENTRY_POINT to pick the banner", 1, entryReaders.size)
                    entryReaders.single()
                } else {
                    entryWriters.single()
                }
                if (!readsEntry) {
                    // Positive control: the reel's author tap is what hands it the reel's tag. 586's Android 9
                    // build asks a name table for the tag (04al.A00), so naming it through that table counts too.
                    val tagHolders = FixtureDex.classesHolding(bundle, REEL_ENTRY_TAG).associateBy { it.type }
                    val tagResolve: (MethodReference) -> Method? = { call -> tagHolders[call.definingClass]?.let { resolveStatic(it, call) } }
                    val reelTaps = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() in tagHolders } }) { method ->
                        method.implementation?.instructions?.any { instruction ->
                            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                                it.definingClass == entryWriter.definingClass && MethodUtil.methodSignaturesMatch(it, entryWriter)
                            } == true
                        } == true && namesString(method, REEL_ENTRY_TAG, tagResolve)
                    }
                    assertTrue("$name: nothing holding $REEL_ENTRY_TAG calls ${entryWriter.definingClass}->${entryWriter.name}", reelTaps.isNotEmpty())
                }
                val entryOriginal = entryWriter.code()
                val android9 = name.substringAfter("-$version-").contains("-minapi")
                val form = (if (android9) android9CommentCardForm else commentCardForm).getValue(version)
                // 586: the comment sheet's plugin gate checks for the card inline, in its case for the plugin's number.
                val gateCases = mutableListOf<Pair<Method, Int>>()
                if (form == "CASE") {
                    FixtureDex.forEach(bundle) { dex ->
                        if (dex.typeSection.none { it.toString() == FLOATING_CARD_PLUGIN }) return@forEach
                        for (classDef in dex.classes) {
                            if (classDef.type == FLOATING_CARD_PLUGIN || classDef.type.startsWith(EXTENSION_CLASSES)) continue
                            gateCases += floatingCardCases(classDef)
                        }
                    }
                    assertEquals("$name: plugin gate cases for the floating card", 1, gateCases.size)
                }
                val types = (builders + footerChecks).map { it.definingClass }.toSet() + reelCheck.map { it.definingClass } +
                    setOf(footerId.definingClass, FLOATING_CARD_PLUGIN, ctaFilter.definingClass, labelRender.definingClass,
                        labelGate!!.definingClass, entryWriter.definingClass) + labelTables.keys +
                    gateCases.map { it.first.definingClass }
                val classes = FixtureDex.classes(bundle, types)
                assertEquals("$name: classes read", types, classes.keys)
                val plugin = classes.getValue(FLOATING_CARD_PLUGIN)
                val readers = plugin.methods.filter(::isCommentCardReader)
                val cardChecks = plugin.methods.filter(::isCommentCardCheck)
                assertEquals("$name: static card readers on the floating card plugin", if (form == "READER") 1 else 0, readers.size)
                assertEquals("$name: static Z card checks on the floating card plugin", if (form == "CHECK") 1 else 0, cardChecks.size)
                val commentCard = when (form) {
                    "READER" -> readers.single()
                    "CHECK" -> cardChecks.single()
                    else -> {
                        val (gate, _) = gateCases.single()
                        classes.getValue(gate.definingClass).methods.single { MethodUtil.methodSignaturesMatch(it, gate) }
                    }
                }

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
                when (form) {
                    "READER" -> assertFiltered("$name comment card", originals[1], patched(commentCard), KEEP_COMMENT_CARD, commentCard.returnType)
                    "CHECK" -> assertFiltered("$name comment card check", originals[1], patched(commentCard), KEEP_COMMENT_CARD_CHECK, null)
                    else -> assertCaseHooked("$name comment card case", commentCard,
                        context.mutableClassDefBy(commentCard.definingClass).methods.single { MethodUtil.methodSignaturesMatch(it, commentCard) },
                        gateCases.single().second)
                }
                assertCtaFiltered("$name CTA filter", originals[2], patched(ctaFilter), list)
                // The flag is read in the render and its check up to 585, and on 586 in one static
                // helper of the check's class that both call; 587 reads it in both again, by MobileConfig id.
                val gateMethod = classes.getValue(labelGate.definingClass).methods.single { MethodUtil.methodSignaturesMatch(it, labelGate) }
                val labelReaders: List<MethodReference> = if (flagReads(labelRender).isNotEmpty()) listOf(labelRender, gateMethod) else
                    listOfNotNull(sharedFlagReader(labelRender, gateMethod) { call ->
                        classes[call.definingClass]?.methods?.firstOrNull { MethodUtil.methodSignaturesMatch(it, call) }
                    })
                assertEquals("$name: methods reading the label's flag", if (flagReads(labelRender).isNotEmpty()) 2 else 1, labelReaders.size)
                val labelFlag = flagReads(labelReaders.first() as Method).single().second
                for (read in labelReaders) {
                    val hooked = context.mutableClassDefBy(read.definingClass).methods
                        .single { MethodUtil.methodSignaturesMatch(it, read) }
                    assertLabelHooked("$name label ${read.definingClass}", hooked, labelFlag)
                }
                val entryPatched = context.mutableClassDefBy(entryWriter.definingClass).methods
                    .single { MethodUtil.methodSignaturesMatch(it, entryWriter) }.code()
                if (readsEntry) {
                    // Each value read under the key goes through the extension right after the read, and nothing jumps in between.
                    val reads = profileEntryReads(entryWriter)
                    val abi = name.substringAfter("-$version-").substringBefore(".apkm").substringBefore("-minapi")
                    assertEquals("$name: ${entryWriter.definingClass}->${entryWriter.name} reads of $PROFILE_ENTRY_POINT",
                        profileEntryReadCount.getValue(version)[abi] ?: throw AssertionError("$name has no pin"), reads.size)
                    for ((after, value) in reads) {
                        assertTrue("$name: ${entryWriter.definingClass}->${entryWriter.name} calls a method on the tag it read at $after",
                            !callsOnRead(entryOriginal, after, value))
                    }
                    assertEquals("$name: 2 instructions after each read", entryOriginal.size + 2 * reads.size, entryPatched.size)
                    val hooks = reads.mapIndexed { order, (after, value) ->
                        val at = after + 2 * order
                        val entryHook = entryPatched[at] as RegisterRangeInstruction
                        assertEquals("$name: the profile tag hook", KEEP_PROFILE_ENTRY, (entryHook as ReferenceInstruction).reference.toString())
                        assertEquals("$name: the profile tag handed over", value, entryHook.startRegister)
                        assertEquals("$name: one register handed over", 1, entryHook.registerCount)
                        assertEquals("$name: the extension's answer", Opcode.MOVE_RESULT_OBJECT, entryPatched[at + 1].opcode)
                        assertEquals("$name: the answer lands where the read left it", value, (entryPatched[at + 1] as OneRegisterInstruction).registerA)
                        assertEquals("$name: the read comes right before", "getString",
                            ((entryPatched[at - 2] as ReferenceInstruction).reference as MethodReference).name)
                        at
                    }
                    for ((branch, target) in branchTargets(entryPatched)) {
                        assertTrue("$name: the branch at $branch lands in a profile tag hook", hooks.none { target == it || target == it + 1 })
                    }
                } else {
                    // The value the store writes goes through the extension right in front of it, and nothing jumps past.
                    val (put, value) = profileEntryWrites(entryWriter).single()
                    assertEquals("$name: 2 instructions in front of the store", entryOriginal.size + 2, entryPatched.size)
                    val entryHook = entryPatched[put] as RegisterRangeInstruction
                    assertEquals("$name: the profile tag hook", KEEP_PROFILE_ENTRY, (entryHook as ReferenceInstruction).reference.toString())
                    assertEquals("$name: the profile tag handed over", value, entryHook.startRegister)
                    assertEquals("$name: one register handed over", 1, entryHook.registerCount)
                    assertEquals("$name: the extension's answer", Opcode.MOVE_RESULT_OBJECT, entryPatched[put + 1].opcode)
                    assertEquals("$name: the answer lands where the store reads it", value, (entryPatched[put + 1] as OneRegisterInstruction).registerA)
                    assertEquals("$name: the store follows", "putString",
                        ((entryPatched[put + 2] as ReferenceInstruction).reference as MethodReference).name)
                    for ((branch, target) in branchTargets(entryPatched)) {
                        assertTrue("$name: the branch at $branch skips the profile tag hook", target != put + 1 && target != put + 2)
                    }
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
