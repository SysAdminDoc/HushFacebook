/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Default comment order on every Facebook build the bundle declares: one builder of a comment
 * request reads the order, the feedback id and the focused comment id from FetchFeedbackParams;
 * one constructor of the params stores all three from its own parameters; one pick handler fetches
 * the order-change request from a copy of the sheet's FeedbackParams; one sort menu header is
 * built by the comment section's renderer and filed under its key; the three tokens the extension
 * asks for are in the build; and the patch, run on those classes, asks the extension first in the
 * constructor and tells it first in the handler and the header. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class DefaultCommentOrderFixtureTest {
    private val feedbackParams = "Lcom/facebook/ufiservices/flyout/params/FeedbackParams;"

    /** Facebook's names for Most relevant, Newest and All comments, which DefaultCommentOrderTest pins too. */
    private val tokens = listOf(
        "RANKED_FILTERED_INTENT_V1",
        "RECENT_ACTIVITY_INTENT_V1",
        "RANKED_UNFILTERED_CHRONOLOGICAL_REPLIES_INTENT_V1",
    )

    private fun Instruction.callRegisters(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Method.parameters() = parameterTypes.map { it.toString() }

    /** A string's interface getters, and the classes the patch reads to find them: the literal's holders and the models. */
    private class Getters(val signatures: Set<String>, val classes: List<ClassDef>)

    /** The interface getters whose models name [literal], held or asked of a string table, as the patch finds them. */
    private fun sheetGetters(bundle: File, literal: String): Getters {
        val holders = FixtureDex.classesHolding(bundle, literal)
        val tables = holders.associateBy { it.type }
        val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
        val getters = holders.flatMap { holder -> holder.methods.filter { isSheetGetter(it) && holdsString(it, literal) } } +
            FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in tables } }) {
                isSheetGetter(it) && !holdsString(it, literal) && namesString(it, literal, resolve)
            }
        val models = FixtureDex.classes(bundle, getters.map { it.definingClass }.toSet()).values
        return Getters(interfaceGetters(models, getters), holders + models)
    }

    private fun makesFeedbackParams(method: Method): Boolean = method.body().any {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        it.opcode == Opcode.INVOKE_DIRECT && call?.definingClass == feedbackParams && call.name == "<init>"
    }

    @Test
    fun `each declared build builds its comment requests the way the patch reads them, and the patch goes in`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                // 586's Android 9 build's builder asks a string table for each variable's name, so the
                // builders are looked for among the classes naming the order's holders, as the patch does.
                val variableHolders = listOf(ORDER_VARIABLE, FEEDBACK_ID_VARIABLE, FOCUSED_COMMENT_VARIABLE)
                    .flatMap { FixtureDex.classesHolding(bundle, it) }.distinctBy { it.type }
                val tables = variableHolders.associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val orderHolders = FixtureDex.classesHolding(bundle, ORDER_VARIABLE).map { it.type }.toSet()
                val requestMethods = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in orderHolders } }) {
                    requestFields(it, resolve) != null || storyOrderField(it, resolve) != null
                }
                val builders = requestMethods.mapNotNull { method -> requestFields(method, resolve)?.let { method to it } }
                assertEquals("$name: request builders reading the three variables", 1, builders.size)
                val (builder, fields) = builders.single()
                assertTrue("$name: the builder takes the session",
                    USER_SESSION in builder.parameters() && FETCH_FEEDBACK_PARAMS in builder.parameters())

                val params = FixtureDex.classes(bundle, setOf(FETCH_FEEDBACK_PARAMS))[FETCH_FEEDBACK_PARAMS]
                    ?: throw AssertionError("$name: no $FETCH_FEEDBACK_PARAMS")
                val constructors = params.methods.mapNotNull { method -> requestRegisters(method, fields)?.let { method to it } }
                assertEquals("$name: params constructors storing the three from parameters", 1, constructors.size)
                assertEquals("$name: params constructors", 1, params.methods.count { it.name == "<init>" })
                val (constructor, registers) = constructors.single()
                val read = listOf(registers.order, registers.feedbackId, registers.focusedComment)
                assertTrue("$name: a register past v15: $read", read.all { it <= 15 })

                val handlerOwners = FixtureDex.classesHolding(bundle, PICK_QUERY)
                val handlers = handlerOwners.flatMap { owner -> owner.methods.filter(::isPickHandler) }
                assertEquals("$name: comment sheet pick handlers", 1, handlers.size)
                val handler = handlers.single()
                assertTrue("$name: the pick handler makes no FeedbackParams copy", makesFeedbackParams(handler))

                for (token in tokens) {
                    assertTrue("$name: $token isn't in this build", FixtureDex.classesHolding(bundle, token).isNotEmpty())
                }

                val keyOwners = FixtureDex.classesHolding(bundle, SELECTOR_KEY)
                val keyMaps = selectorKeyMaps(keyOwners)
                assertTrue("$name: no static map beside \"$SELECTOR_KEY\"", keyMaps.isNotEmpty())
                // 585's renderer holds neither string, so it's looked for among the methods naming the key's class.
                val keyTypes = keyOwners.map { it.type }.toSet()
                val intent = sheetGetters(bundle, INTENT_FLAG)
                val interranked = sheetGetters(bundle, INTERRANKED)
                val renders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in keyTypes } }) {
                    sortHeaderIn(it, keyMaps, intent.signatures, interranked.signatures) != null
                }
                val headers = renders.mapNotNull { sortHeaderIn(it, keyMaps, intent.signatures, interranked.signatures) }
                    .distinctBy { it.toString() }
                assertEquals("$name: comment sort menu headers", 1, headers.size)
                val renderers = FixtureDex.classes(bundle, renders.map { it.definingClass }.toSet()).values
                val headerClass = FixtureDex.classes(bundle, setOf(headers.single().definingClass))
                    .getValue(headers.single().definingClass)
                val headerConstructor = headerClass.methods.single {
                    it.name == "<init>" && it.parameters() == headers.single().parameterTypes.map { type -> type.toString() }
                }

                // #192: the post page's request builder among the same methods, its params'
                // constructor, and the one link-to-page builder calling that constructor.
                val storyBuilders = requestMethods.mapNotNull { method -> storyOrderField(method, resolve)?.let { method to it } }
                assertEquals("$name: post page request builders reading the order", 1, storyBuilders.size)
                val (storyBuilder, orderField) = storyBuilders.single()
                assertTrue("$name: the page's builder takes the session", USER_SESSION in storyBuilder.parameters())
                assertTrue("$name: the page's builder reads the order from its params, not $orderField",
                    orderField.startsWith("$FETCH_SINGLE_STORY_PARAMS->"))
                val storyParams = FixtureDex.classes(bundle, setOf(FETCH_SINGLE_STORY_PARAMS))[FETCH_SINGLE_STORY_PARAMS]
                    ?: throw AssertionError("$name: no $FETCH_SINGLE_STORY_PARAMS")
                val storyConstructors = storyParams.methods.mapNotNull { method ->
                    storyOrderParameter(method, orderField)?.let { method to it }
                }
                assertEquals("$name: page params constructors storing the order from a parameter", 1, storyConstructors.size)
                val (storyConstructor, orderParameter) = storyConstructors.single()
                assertEquals("$name: the order is a String parameter", "Ljava/lang/String;",
                    storyConstructor.parameterTypes[orderParameter].toString())
                val pageBuilds = FixtureDex.methodsWhere(
                    bundle,
                    { dex -> dex.methodSection.any { it.definingClass == FETCH_SINGLE_STORY_PARAMS && it.name == "<init>" } },
                ) { method -> pageBuildIn(method, storyConstructor, orderParameter) != null }
                assertEquals("$name: link-to-page builders", 1, pageBuilds.size)
                val pageBuilder = pageBuilds.single()
                val pageBuild = pageBuildIn(pageBuilder, storyConstructor, orderParameter)!!
                assertTrue("$name: the order's register past v255: v${pageBuild.order}", pageBuild.order <= 255)
                val pageOwner = FixtureDex.classes(bundle, setOf(pageBuilder.definingClass)).getValue(pageBuilder.definingClass)

                val owners = mutableMapOf<String, ClassDef>()
                owners[params.type] = params
                owners[storyParams.type] = storyParams
                val requestOwners = FixtureDex.classes(bundle, requestMethods.map { it.definingClass }.toSet()).values
                (variableHolders + requestOwners + handlerOwners + keyOwners + renderers + intent.classes + interranked.classes)
                    .forEach { owners[it.type] = it }
                owners[headerClass.type] = headerClass
                owners[pageOwner.type] = pageOwner
                val context = PatchContexts.of(owners.values + ExtensionDex.classDef(SETTINGS_STATUS))
                defaultCommentOrderPatch.execute(context)

                val patchedPage = context.mutableClassDefBy(pageOwner.type).methods.single {
                    it.name == pageBuilder.name && it.parameters() == pageBuilder.parameters()
                }.body()
                val pageAsks = patchedPage[pageBuild.index]
                assertEquals(Opcode.INVOKE_STATIC_RANGE, pageAsks.opcode)
                assertEquals("$name: the page builder's call", PAGE_ORDER, (pageAsks as ReferenceInstruction).reference.toString())
                assertEquals("$name: it hands over the link's order", listOf(pageBuild.order), pageAsks.callRegisters())
                assertEquals(Opcode.MOVE_RESULT_OBJECT, patchedPage[pageBuild.index + 1].opcode)
                assertEquals("$name: where the page's answer goes", pageBuild.order,
                    (patchedPage[pageBuild.index + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the constructor call follows", Opcode.INVOKE_DIRECT_RANGE, patchedPage[pageBuild.index + 2].opcode)
                assertEquals("$name: the page builder around the call", pageBuilder.body().map { it.opcode },
                    patchedPage.take(pageBuild.index).map { it.opcode } + patchedPage.drop(pageBuild.index + 2).map { it.opcode })

                val patchedConstructor = context.mutableClassDefBy(FETCH_FEEDBACK_PARAMS).methods.single {
                    it.name == "<init>" && it.parameters() == constructor.parameters()
                }.body()
                val asks = patchedConstructor[0]
                assertEquals("$name: the constructor's call", REQUESTED_ORDER, (asks as ReferenceInstruction).reference.toString())
                assertEquals("$name: what the call reads", read, asks.callRegisters())
                assertEquals(Opcode.MOVE_RESULT_OBJECT, patchedConstructor[1].opcode)
                assertEquals("$name: where the answer goes", registers.order,
                    (patchedConstructor[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the constructor after the call", constructor.body().map { it.opcode },
                    patchedConstructor.drop(2).map { it.opcode })

                val patchedHandler = context.mutableClassDefBy(handler.definingClass).methods.single {
                    it.name == handler.name && it.parameters() == handler.parameters()
                }.body()
                val tells = patchedHandler[0]
                assertEquals(Opcode.INVOKE_STATIC_RANGE, tells.opcode)
                assertEquals("$name: the handler's call", PICKED, (tells as ReferenceInstruction).reference.toString())
                val token = handler.implementation!!.registerCount - 1
                assertEquals("$name: the handler hands on the picked token", listOf(token), tells.callRegisters())
                assertEquals("$name: the handler after the call", handler.body().map { it.opcode },
                    patchedHandler.drop(1).map { it.opcode })

                val patchedHeader = context.mutableClassDefBy(headerClass.type).methods.single {
                    it.name == "<init>" && it.parameters() == headerConstructor.parameters()
                }.body()
                assertEquals("$name: the header's call", SORT_MENU, (patchedHeader[0] as ReferenceInstruction).reference.toString())
                val locals = headerConstructor.implementation!!.registerCount - 5
                assertEquals("$name: the header hands on the feedback and the pick", listOf(locals + 2, locals + 4),
                    patchedHeader[0].callRegisters())
                assertEquals("$name: the header after the call", headerConstructor.body().map { it.opcode },
                    patchedHeader.drop(1).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
