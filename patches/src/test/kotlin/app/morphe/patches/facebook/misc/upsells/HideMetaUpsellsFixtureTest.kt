/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.upsells

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.metaai.DROPS_DEFAULT_PILL
import app.morphe.patches.facebook.feed.metaai.META_AI_QUESTION_BUTTON
import app.morphe.patches.facebook.feed.metaai.hideMetaAiQuestionsPatch
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.sharesheet.shareSheetHookPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Meta upsells' anchors on every Facebook build the bundle declares: the Edits flags the
 * landing configuration's serializer writes and every read of them, the gates that set the Edits
 * pill's request parameter, the one getter of a feed video's Edits attribution the pill is drawn
 * from, the Threads cross-posting capability's one answer, the Meta Verified
 * sheet's eligibility check and the one place that asks for the label, the three avatar sticker
 * upsell components, Imagine's three (the post call-to-action selector's check, every question the
 * composer asks about its Imagine capability and Create story's tile builder) and the method that
 * picks the share sheet's items, with Guava's ImmutableList.copyOf beside it. Then the whole
 * patch on those classes, with shareSheetHookPatch, its dependency, run first: each hook where it
 * belongs, on the anchor's own register, and nothing else moved. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HideMetaUpsellsFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** The index the branch at [from] in [code] lands on. */
    private fun landing(code: List<Instruction>, from: Int): Int {
        val offset = (code[from] as OffsetInstruction).codeOffset
        var units = 0
        var at = from
        while (units < offset) units += code[at++].codeUnits
        return at
    }

    private fun Method.reference() =
        "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun ClassDef.method(like: Method): Method = methods.single {
        it.name == like.name && it.returnType == like.returnType &&
            it.parameterTypes.map(CharSequence::toString) == like.parameterTypes.map(CharSequence::toString)
    }

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    /** [hook] right after each of [anchors] in [original], on the anchor's register, and nothing else moved. */
    private fun assertAnsweredAfter(where: String, original: List<Instruction>, patched: List<Instruction>, anchors: List<Int>, hook: String) {
        assertTrue("$where: nothing to answer", anchors.isNotEmpty())
        assertEquals("$where: two instructions per anchor", original.size + 2 * anchors.size, patched.size)
        anchors.sorted().forEachIndexed { done, at ->
            val moved = at + 2 * done
            val register = (original[at] as OneRegisterInstruction).registerA
            assertEquals("$where: the anchor at $at", original[at].opcode, patched[moved].opcode)
            assertEquals("$where: the hook after $at", Opcode.INVOKE_STATIC_RANGE, patched[moved + 1].opcode)
            assertEquals("$where: the hook after $at", hook, patched[moved + 1].called())
            assertEquals("$where: handed v$register", register, (patched[moved + 1] as RegisterRangeInstruction).startRegister)
            assertEquals("$where: one register handed over", 1, (patched[moved + 1] as RegisterRangeInstruction).registerCount)
            assertTrue("$where: the answer comes back", patched[moved + 2].opcode in setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT))
            assertEquals("$where: into v$register", register, (patched[moved + 2] as OneRegisterInstruction).registerA)
        }
        val kept = patched.filterIndexed { index, _ ->
            anchors.sorted().withIndex().none { (done, at) -> index == at + 2 * done + 1 || index == at + 2 * done + 2 }
        }
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, kept.map { it.opcode })
    }

    /** [hook] in front of each of [returnOpcode] in [patched], on the returned register. */
    private fun assertReturnsAnswered(where: String, original: List<Instruction>, patched: List<Instruction>, returnOpcode: Opcode, hook: String) {
        val returns = original.count { it.opcode == returnOpcode }
        assertTrue("$where: never returns", returns > 0)
        assertEquals("$where: two instructions in front of each return", original.size + 2 * returns, patched.size)
        patched.withIndex().filter { it.value.opcode == returnOpcode }.forEach { (at, ret) ->
            val register = (ret as OneRegisterInstruction).registerA
            assertEquals("$where: the call before return v$register", hook, patched[at - 2].called())
            assertEquals("$where: handed v$register", register, (patched[at - 2] as RegisterRangeInstruction).startRegister)
            assertEquals("$where: the answer lands where the return reads it", register, (patched[at - 1] as OneRegisterInstruction).registerA)
        }
    }

    @Test
    fun `each declared build has every upsell anchor, and the patch answers each one through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val kept = FixtureDex.classes(bundle,
                    setOf(LANDING_SERIALIZER, VERIFIED_SHEET_HANDLER, VERIFIED_LABEL_PLUGIN, CAPTION_DEEP_DIVE_PLUGIN))
                assertEquals("$name: kept classes", 4, kept.size)

                // Edits: both flags, read outside the configuration's own classes too.
                val fields = editsFlagFields(kept.getValue(LANDING_SERIALIZER))
                val fieldNames = fields.map { it.toString() }.toSet()
                val flagReaders = FixtureDex.methodsWhere(bundle, { dex -> dex.fieldSection.any { it.toString() in fieldNames } }) {
                    editsFlagReads(it, fields).isNotEmpty()
                }
                assertTrue("$name: no screen reads the Edits flags",
                    flagReaders.any { !it.definingClass.startsWith(LANDING_CONFIG.removeSuffix(";")) })
                // 582's feed query asks a string table for the name, and the table holds it like any other holder.
                val pillClasses = FixtureDex.classesHolding(bundle, EDITS_PILL_PARAMETER)
                val tables = pillClasses.associateBy { it.type }
                val table: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val tableCallers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.definingClass in tables } }) {
                    editsPillGates(it, table).isNotEmpty()
                }
                val gates = (pillClasses.flatMap { it.methods } + tableCallers)
                    .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}${it.returnType}" }
                    .map { it to editsPillGates(it, table) }.filter { it.second.isNotEmpty() }
                assertTrue("$name: the feed's query sets the Edits pill from no gate", gates.any { (_, found) -> found.any { !it.boxed } })

                // Threads: one capability names itself, with one should-show answer.
                val capabilities = FixtureDex.classesHolding(bundle, THREADS_CAPABILITY).filter(::isThreadsCapability)
                assertEquals("$name: Threads cross-posting capabilities", 1, capabilities.size)
                val shouldShow = threadsShouldShow(capabilities.single())

                // Meta Verified: the eligibility check, and the one dispatcher ask for the label.
                val eligibility = verifiedEligibility(kept.getValue(VERIFIED_SHEET_HANDLER))
                val labelText = verifiedLabelText(kept.getValue(VERIFIED_LABEL_PLUGIN))
                val textReference = labelText.reference()
                val labelAskers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == textReference } }) {
                    verifiedLabelAsks(it, labelText).isNotEmpty()
                }
                assertEquals("$name: places asking for the Meta Verified label", 1, labelAskers.size)

                // Avatar stickers: each component names itself once and has one draw method.
                val components = AVATAR_UPSELL_COMPONENTS.map { spec ->
                    val holders = FixtureDex.classesHolding(bundle, spec).distinctBy { it.type }
                    assertEquals("$name: classes naming $spec", 1, holders.size)
                    holders.single()
                }
                val draws = components.associate { it.type to avatarUpsellDraw(it) }

                // Edits over feed videos (#314): one getter reads a video's Edits attribution, counted by its enum.
                val attributionEnums = FixtureDex.classesHolding(bundle, EDITS_ATTRIBUTION_GETTER)
                    .filter { isEnumNaming(it, VIDEO_ATTACHMENT_GETTERS) }
                assertEquals("$name: video attachment getter enums", 1, attributionEnums.size)
                val editsAttribution = enumConstant(attributionEnums.single(), EDITS_ATTRIBUTION_GETTER)
                val attributionGetters = FixtureDex.methodsWhere(bundle,
                    { dex -> dex.fieldSection.any { it.toString() == editsAttribution.toString() } }) {
                    isEditsAttributionGetter(it, editsAttribution)
                }
                assertEquals("$name: Edits attribution getters", 1, attributionGetters.size)
                // The pill under a feed video (#314): one builder names the plugin socket and reads its pill's type.
                val pillSocketHolders = FixtureDex.classesHolding(bundle, DEEP_DIVE_SOCKET).distinctBy { it.type }
                val pillBuilders = pillSocketHolders.flatMap { holder ->
                    methodsHolding(holder, DEEP_DIVE_SOCKET).filter { it.returnType.startsWith("L") && deepDivePillType(it) != null }
                }
                assertEquals("$name: builders of the pill under a feed video", 1, pillBuilders.size)
                val pillBuilder = pillBuilders.single()
                val (pill, pillType) = deepDivePillType(pillBuilder)!!
                assertEquals("$name: the pill's type is a String getter on the pill", pillBuilder.parameterTypes[pill].toString(),
                    pillType.definingClass)

                // Imagine: the selector's table and check, the composer's capability, Create story's tools.
                val ctaTableHolders = FixtureDex.classesHolding(bundle, IMAGINE_ME_PLUGIN)
                val ctaTable = ctaTable(ctaTableHolders)
                val ctaSocketHolders = FixtureDex.classesHolding(bundle, IMAGINE_CTA_SOCKET)
                val ctaSockets = ctaSocketHolders.flatMap { methodsHolding(it, IMAGINE_CTA_SOCKET) }
                val calledTypes = ctaSockets.flatMap { method ->
                    method.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.map { it.definingClass }
                }.toSet()
                val calledClasses = FixtureDex.classes(bundle, calledTypes)
                val ctaCheck = ctaCheck(ctaTable, ctaSockets) { calledClasses[it] }
                assertTrue("$name: the Imagine me check has no local register",
                    ctaCheck.implementation!!.registerCount - ctaCheck.parameterTypes.size >= 1)
                val composerEnums = FixtureDex.classesHolding(bundle, COMPOSER_IMAGINE).filter { isEnumNaming(it, COMPOSER_CAPABILITIES) }
                assertEquals("$name: composer capability enums", 1, composerEnums.size)
                val composerImagine = enumConstant(composerEnums.single(), COMPOSER_IMAGINE)
                val imagineAskers = FixtureDex.methodsWhere(bundle, { dex -> dex.fieldSection.any { it.toString() == composerImagine.toString() } }) {
                    capabilityAsks(it, composerImagine).isNotEmpty()
                }
                assertTrue("$name: nothing asks about $COMPOSER_IMAGINE", imagineAskers.isNotEmpty())
                val storyEnums = FixtureDex.classesHolding(bundle, STORY_IMAGINE).filter { isEnumNaming(it, STORY_TOOLS_NAMES) }
                assertEquals("$name: Create story tool enums", 1, storyEnums.size)
                val storyImagine = enumConstant(storyEnums.single(), STORY_IMAGINE)
                val storyBuilders = FixtureDex.methodsWhere(bundle, { dex -> dex.fieldSection.any { it.toString() == storyImagine.toString() } }) {
                    storyToolList(it, storyImagine) != null
                }
                assertEquals("$name: Create story tile builders", 1, storyBuilders.size)

                // Threads in the share sheet: the item enum, the one list of items, and Guava's copy.
                val shareEnums = FixtureDex.classesHolding(bundle, SHARE_TO_THREADS).filter { isEnumNaming(it, SHARE_ITEM_TYPES) }
                assertEquals("$name: share sheet item enums", 1, shareEnums.size)
                // Muse in the share sheet is a constant of the same enum, so the same list carries it.
                assertTrue("$name: the share sheet item enum has no SHARE_TO_HATCH (Muse)",
                    isEnumNaming(shareEnums.single(), listOf("SHARE_TO_HATCH")))
                val shareThreads = enumConstant(shareEnums.single(), SHARE_TO_THREADS)
                val shareLists = FixtureDex.methodsWhere(bundle, { dex -> dex.fieldSection.any { it.toString() == shareThreads.toString() } }) {
                    isShareItemList(it, shareThreads)
                }
                assertEquals("$name: share sheet item lists", 1, shareLists.size)
                val immutableList = FixtureDex.classes(bundle, setOf(IMMUTABLE_LIST)).values.single()
                assertTrue("$name: $IMMUTABLE_LIST has no copyOf(Collection)", definesImmutableCopy(immutableList))

                val readerClasses = FixtureDex.classes(bundle,
                    (flagReaders + labelAskers + imagineAskers + storyBuilders + shareLists + gates.map { it.first } +
                        attributionGetters).map { it.definingClass }.toSet())
                val pool = (kept.values + readerClasses.values + pillClasses + capabilities + components +
                    ctaTableHolders + ctaSocketHolders + listOfNotNull(calledClasses[ctaCheck.definingClass]) +
                    composerEnums + storyEnums + shareEnums + attributionEnums + pillSocketHolders + immutableList +
                    ExtensionDex.classDef(SETTINGS_STATUS)).associateBy { it.type }.values
                val context = PatchContexts.of(pool)
                // execute() runs only the patch it's called on, so the shared share sheet hook goes first.
                shareSheetHookPatch.execute(context)
                hideMetaUpsellsPatch.execute(context)
                fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).method(method).code()

                for (reader in flagReaders) {
                    assertAnsweredAfter("$name: ${reader.definingClass}->${reader.name}", reader.code(), patched(reader),
                        editsFlagReads(reader, fields), EDITS_HEADER)
                }
                for ((method, found) in gates) {
                    val where = "$name: ${method.definingClass}->${method.name}"
                    val unboxed = found.filter { !it.boxed }.map { it.moveResult }
                    val boxed = found.filter { it.boxed }.map { it.moveResult }
                    val original = method.code()
                    val after = patched(method)
                    assertEquals("$where: two instructions per gate", original.size + 2 * found.size, after.size)
                    found.sortedBy { it.moveResult }.forEachIndexed { done, gate ->
                        val hook = if (gate.boxed) FETCH_EDITS_PILL_BOXED else FETCH_EDITS_PILL
                        assertEquals("$where: the hook after ${gate.moveResult}", hook, after[gate.moveResult + 2 * done + 1].called())
                    }
                    assertTrue(where, unboxed.size + boxed.size == found.size)
                }
                assertReturnsAnswered("$name: Threads should-show", shouldShow.code(), patched(shouldShow), Opcode.RETURN, THREADS_ONBOARDING)
                assertReturnsAnswered("$name: Meta Verified eligibility", eligibility.code(), patched(eligibility),
                    Opcode.RETURN_OBJECT, META_VERIFIED_SHEET)
                val asker = labelAskers.single()
                assertAnsweredAfter("$name: ${asker.definingClass}->${asker.name}", asker.code(), patched(asker),
                    verifiedLabelAsks(asker, labelText), META_VERIFIED_LABEL)
                for ((type, draw) in draws) {
                    val after = patched(draw)
                    assertEquals("$name: $type draws nothing on a yes", HIDES_AVATAR_UPSELL, after[0].called())
                    assertEquals("$name: $type returns null on a yes", Opcode.RETURN_OBJECT, after[4].opcode)
                    assertEquals("$name: $type draws as before otherwise", draw.code().map { it.opcode }, after.drop(5).map { it.opcode })
                }
                val getter = attributionGetters.single()
                val answered = patched(getter)
                val getterName = "$name: ${getter.definingClass}->${getter.name}"
                assertEquals("$getterName asks about the Edits attribution first", HIDES_EDITS_ATTRIBUTION, answered[0].called())
                assertEquals("$getterName answers none on a yes", Opcode.RETURN_OBJECT, answered[4].opcode)
                assertEquals("$getterName reads as before otherwise", getter.code().map { it.opcode }, answered.drop(5).map { it.opcode })

                // The pill builder: with a pill, its type to the extension first, and nothing drawn on a yes.
                val pillCode = pillBuilder.code()
                val drawn = patched(pillBuilder)
                val builderName = "$name: ${pillBuilder.descriptor()}"
                val sizes = pillBuilder.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
                val pillRegister = pillBuilder.implementation!!.registerCount - sizes.sum() + sizes.take(pill).sum()
                assertTrue("$builderName is static", AccessFlags.STATIC.isSet(pillBuilder.accessFlags))
                assertEquals("$builderName gains eight instructions", pillCode.size + 8, drawn.size)
                assertEquals("$builderName checks there's a pill", listOf(Opcode.IF_EQZ, pillRegister),
                    listOf(drawn[0].opcode, (drawn[0] as OneRegisterInstruction).registerA))
                assertEquals("$builderName reads the pill's type", pillType.toString(), drawn[1].called())
                assertEquals("$builderName reads it off the pill", listOf(pillRegister, 1),
                    listOf((drawn[1] as RegisterRangeInstruction).startRegister, (drawn[1] as RegisterRangeInstruction).registerCount))
                assertEquals("$builderName hands the type over", HIDES_DEEP_DIVE_PILL, drawn[3].called())
                assertEquals("$builderName draws nothing on a yes", Opcode.RETURN_OBJECT, drawn[7].opcode)
                assertEquals("$builderName: no pill builds as before", 8, landing(drawn, 0))
                assertEquals("$builderName: a no builds as before", 8, landing(drawn, 5))
                assertEquals("$builderName builds as before otherwise", pillCode.map { it.opcode }, drawn.drop(8).map { it.opcode })
                // A build runs Hide Meta AI questions under posts first, which hooks the same builder
                // between its type read and the "stars" compare. The Edits pill still gets its hook then.
                val withQuestions = PatchContexts.of((pool + FixtureDex.classesHolding(bundle, META_AI_QUESTION_BUTTON))
                    .associateBy { it.type }.values)
                shareSheetHookPatch.execute(withQuestions)
                hideMetaAiQuestionsPatch.execute(withQuestions)
                hideMetaUpsellsPatch.execute(withQuestions)
                val drawnBoth = withQuestions.mutableClassDefBy(pillBuilder.definingClass).method(pillBuilder).code()
                assertEquals("$builderName after the Meta AI questions hook hands the type over first", HIDES_DEEP_DIVE_PILL,
                    drawnBoth[3].called())
                assertTrue("$builderName keeps the Meta AI questions hook", drawnBoth.drop(8).any { it.called() == DROPS_DEFAULT_PILL })

                // Imagine me: the plugin's name from the table with the check's own number, then the extension.
                val check = ctaCheck.code()
                val guarded = patched(ctaCheck)
                val where = "$name: ${ctaCheck.descriptor()}"
                assertEquals("$where gains seven instructions", check.size + 7, guarded.size)
                assertEquals("$where: the plugin's name comes from the table", Opcode.INVOKE_STATIC_RANGE, guarded[0].opcode)
                assertEquals("$where: the plugin's name comes from the table", ctaTable.descriptor(), guarded[0].called())
                assertEquals("$where: with the check's own number", ctaCheck.implementation!!.registerCount - 1,
                    (guarded[0] as RegisterRangeInstruction).startRegister)
                assertEquals("$where: the extension is asked", HIDES_IMAGINE_CTA, guarded[2].called())
                assertEquals("$where: Facebook's code stays", check.map { it.opcode }, guarded.drop(7).map { it.opcode })
                for (asker in imagineAskers) {
                    assertAnsweredAfter("$name: ${asker.definingClass}->${asker.name}", asker.code(), patched(asker),
                        capabilityAsks(asker, composerImagine), IMAGINE_CAPABILITY)
                }
                val builder = storyBuilders.single()
                val list = storyToolList(builder, storyImagine)!!
                val register = (builder.code()[list] as OneRegisterInstruction).registerA
                val built = patched(builder)
                assertEquals("$name: Create story's tools gain four instructions", builder.code().size + 4, built.size)
                listOf(STORY_TOOLS, IMMUTABLE_COPY).forEachIndexed { step, hook ->
                    val call = built[list + 1 + 2 * step]
                    assertEquals("$name: $hook", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                    assertEquals("$name: $hook", hook, call.called())
                    assertEquals("$name: $hook handed v$register", listOf(register, 1),
                        listOf((call as RegisterRangeInstruction).startRegister, call.registerCount))
                    val answer = built[list + 2 + 2 * step]
                    assertEquals("$name: $hook's answer in v$register", listOf(Opcode.MOVE_RESULT_OBJECT, register),
                        listOf(answer.opcode, (answer as OneRegisterInstruction).registerA))
                }
                assertEquals("$name: the rest of Create story's builder stays", builder.code().map { it.opcode },
                    built.take(list + 1).map { it.opcode } + built.drop(list + 5).map { it.opcode })

                // The caption deep dive: its getter asks the extension first, and runs as before after that.
                val deepDive = captionDeepDiveGetter(kept.getValue(CAPTION_DEEP_DIVE_PLUGIN))
                val dived = patched(deepDive)
                assertEquals("$name: the caption deep dive getter asks first", HIDES_CAPTION_DEEP_DIVE, dived[0].called())
                assertEquals("$name: and returns null on a yes", Opcode.RETURN_OBJECT, dived[4].opcode)
                assertEquals("$name: the getter reads as before otherwise", deepDive.code().map { it.opcode },
                    dived.drop(5).map { it.opcode })

                // The share sheet's items: in front of every return, the extension and the copy back.
                val shareList = shareLists.single()
                val picked = shareList.code()
                val shared = patched(shareList)
                val returns = picked.count { it.opcode == Opcode.RETURN_OBJECT }
                assertEquals("$name: four instructions in front of each of the item list's returns", picked.size + 4 * returns, shared.size)
                shared.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.forEach { (at, ret) ->
                    val answer = (ret as OneRegisterInstruction).registerA
                    listOf(SHARE_TARGETS, IMMUTABLE_COPY).forEachIndexed { step, hook ->
                        val call = shared[at - 4 + 2 * step]
                        assertEquals("$name: $hook before return v$answer", listOf(Opcode.INVOKE_STATIC_RANGE, hook),
                            listOf(call.opcode, call.called()))
                        assertEquals("$name: $hook handed v$answer", listOf(answer, 1),
                            listOf((call as RegisterRangeInstruction).startRegister, call.registerCount))
                        val result = shared[at - 3 + 2 * step]
                        assertEquals("$name: $hook's answer in v$answer", listOf(Opcode.MOVE_RESULT_OBJECT, answer),
                            listOf(result.opcode, (result as OneRegisterInstruction).registerA))
                    }
                }
                val inserted = shared.indices.filter { shared[it].opcode == Opcode.RETURN_OBJECT }.flatMap { (it - 4) until it }.toSet()
                val untouched = shared.filterIndexed { at, _ -> at !in inserted }
                assertEquals("$name: the rest of the item list stays", picked.map { it.opcode }, untouched.map { it.opcode })

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "metaUpsells" }
                assertEquals("$name: SettingsStatus.metaUpsells() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    /** Whether [method] loads the pill's name right after a config answer, a boolean or a Boolean asked by a long id. */
    private fun setsPillFromAnAnswer(method: Method): Boolean {
        val code = method.code()
        return (2 until code.size).any { at ->
            val load = code[at]
            if (load.opcode != Opcode.CONST_STRING && load.opcode != Opcode.CONST_STRING_JUMBO) return@any false
            if (((load as ReferenceInstruction).reference as StringReference).string != EDITS_PILL_PARAMETER) return@any false
            val asked = (code[at - 2] as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
            code[at - 1].opcode in setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT) &&
                asked.parameterTypes.lastOrNull()?.toString() == "J" && asked.returnType in setOf("Z", "Ljava/lang/Boolean;")
        }
    }

    /**
     * Every query handing the Edits pill's request parameter a config answer gets the hook (#314).
     * 588's two feed queries ask through a static helper taking the config as an Object, and were
     * left asking the server for the pill while the switch was on.
     */
    @Test
    fun `every query that sets the Edits pill from a config answer is hooked`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val pillClasses = FixtureDex.classesHolding(bundle, EDITS_PILL_PARAMETER)
                val tables = pillClasses.associateBy { it.type }
                val table: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val asked = pillClasses.flatMap { it.methods }.filter(::setsPillFromAnAnswer)
                assertTrue("$name: no query sets the Edits pill from a config answer", asked.size > 1)
                val called = asked.flatMap { method -> method.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference } }
                    .map { it.definingClass }.toSet()
                val helpers = FixtureDex.classes(bundle, called)
                val missed = asked.filter { editsPillGates(it, table) { type -> helpers[type] }.isEmpty() }
                assertTrue("$name: queries still asking for the pill: ${missed.map { "${it.definingClass}->${it.name}" }}", missed.isEmpty())
                val unboxed = asked.count { method -> editsPillGates(method, table) { type -> helpers[type] }.any { !it.boxed } }
                assertTrue("$name: no feed query passes the pill a plain boolean", unboxed > 0)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
