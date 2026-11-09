/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.isStringTableCall
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.reels.callsMethod
import app.morphe.patches.facebook.feed.reels.categoryNames
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide Meta AI in search on every Facebook build the bundle declares: one answer page builder whose
 * two callers ask one question before it; one result role enum naming every role the extension
 * leaves out; one result module class with one role accessor; the answer page built from that
 * class's modules (on 585 through the page class's short constructor); one results router, one route field and one parser storing it from the server;
 * and the explicit ways in (the Meta AI button's AI mode start and the Meta AI tab's own answer)
 * going around all three. Then the patch itself, run on those classes. Reads the fixture bundles
 * from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class MetaAiSearchFixtureTest {
    /**
     * The key the parser reads a suggestion's route flag under, the same on both builds. The field's
     * GraphQL name is in neither dex, so the key is recorded rather than worked out from a name.
     */
    private val routeFlagKey = 0xc35386fc.toInt()

    /** The Meta AI button's AI mode start, and the Meta AI tab's answer module. */
    private val aiModeStart = "start_in_ai_mode_new_thread"
    private val metaAiTabModule = "search_meta_ai_answer_module"

    private val strings = listOf(ANSWER_PAGE, ROLE_PARSE_FAILURE, META_AI_TAB, KEYWORD_TYPE_FAILURE, aiModeStart, metaAiTabModule)

    /** Whether the answer page builder calls the full page constructor itself, per build: 585 went through its short one, 586 calls it. */
    private val directPageCall = mapOf(
        "586.0.0.52.53" to true,
    )

    /** Whether the parser reads the route flag through the suggestion model's accessor, per build: 585 does, not TreeJNI itself. */
    private val readThroughModel = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to true,
    )

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private val Instruction.field: FieldReference?
        get() = (this as? ReferenceInstruction)?.reference as? FieldReference

    @Test
    fun `each declared build has every anchor once, and the patch goes in on its own classes`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name

                // One pass for the classes holding the anchors' strings.
                val holders = mutableMapOf<String, MutableList<ClassDef>>()
                FixtureDex.forEach(fixture) { dex ->
                    val held = strings.filter { string -> dex.stringSection.any { it == string } }
                    if (held.isEmpty()) return@forEach
                    for (classDef in dex.classes) {
                        for (string in held) {
                            if (methodsHolding(classDef, string).isNotEmpty()) {
                                holders.getOrPut(string) { mutableListOf() } += ImmutableClassDef.of(classDef)
                            }
                        }
                    }
                }
                fun held(string: String) = holders[string].orEmpty()

                val builders = held(ANSWER_PAGE).flatMap { methodsHolding(it, ANSWER_PAGE) }.filter(::isAnswerPageBuilder)
                assertEquals("$name: answer page builders", 1, builders.size)
                val builder = builders.single()
                val roleParsers = held(ROLE_PARSE_FAILURE)
                val roles = roleParsers.flatMap { methodsHolding(it, ROLE_PARSE_FAILURE) }.mapNotNull(::roleEnumType).distinct()
                assertEquals("$name: result role enums", 1, roles.size)
                val role = roles.single()

                // A second pass for the builder's callers and the result module.
                val callers = mutableListOf<Method>()
                val callerClasses = mutableListOf<ClassDef>()
                val modules = mutableListOf<ClassDef>()
                // Each method loading the route flag's key, as "name(params)return".
                val keyReaders = mutableListOf<String>()
                FixtureDex.forEach(fixture) { dex ->
                    for (classDef in dex.classes) {
                        var calls = false
                        for (method in classDef.methods) {
                            if (callsMethod(method, builder)) {
                                callers += ImmutableMethod.of(method)
                                calls = true
                            }
                            if (method.implementation?.instructions?.any {
                                    it is NarrowLiteralInstruction && it.narrowLiteral == routeFlagKey
                                } == true
                            ) {
                                keyReaders += "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
                            }
                            if (isModuleConstructor(method, role)) modules += ImmutableClassDef.of(classDef)
                        }
                        if (calls) callerClasses += ImmutableClassDef.of(classDef)
                    }
                }
                assertEquals("$name: callers of the answer page builder", 2, callers.size)
                val questions = callers.map { answerQuestion(it, builder) }
                assertTrue("$name: a caller builds the answer page without asking first", questions.all { it != null })
                val question = questions.distinctBy { "${it!!.definingClass}->${it.name}" }.single()!!
                assertEquals("$name: result module classes", 1, modules.map { it.type }.distinct().size)
                val module = modules.first()
                val accessors = roleAccessors(module, role)
                assertEquals("$name: the module's role accessors", 1, accessors.size)
                // 585's builders build their pages through a short constructor, read from its own class.
                val tabAnswers = held(metaAiTabModule).flatMap { methodsHolding(it, metaAiTabModule) }
                val constructorTypes = (tabAnswers + builder).flatMap { method ->
                    method.implementation!!.instructions.filter { it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE }
                        .mapNotNull { instruction -> instruction.call?.takeIf { it.name == "<init>" }?.definingClass }
                }.toSet()
                val constructorClasses = FixtureDex.classes(fixture, constructorTypes)
                val resolve = { call: MethodReference ->
                    constructorClasses[call.definingClass]?.methods?.firstOrNull { isConstructor(it, call) }
                }
                val page = answerPageConstructor(builder, module.type, resolve)
                assertNotNull("$name: the answer page isn't built from the module class's list", page)
                assertEquals("$name: the answer page builder calls the page constructor itself", directPageCall.getValue(version),
                    builder.implementation!!.instructions.any { it.call?.let(::isPageConstructor) == true })

                val kept = FixtureDex.classes(fixture, setOf(question.definingClass, role, page!!.definingClass, IMMUTABLE_LIST,
                    QUERY_SPEC_IMPL))
                assertEquals("$name: classes missing", emptySet<String>(),
                    setOf(question.definingClass, role, page.definingClass, IMMUTABLE_LIST, QUERY_SPEC_IMPL) - kept.keys)
                val gate = kept.getValue(question.definingClass).methods.single { isAnswerQuestion(it, question) }
                assertTrue("$name: the answer question has no local", gate.localRegisterCount() >= 1)
                val roleNames = categoryNames(kept.getValue(role))
                assertTrue("$name: ${roleNames.size} roles", roleNames.containsAll(listOf("ENTITY_USER", "ENTITY_GROUPS",
                    "ENTITY_PAGES", "PUBLIC_POSTS")))
                assertEquals("$name: roles the patch needs", emptyList<String>(),
                    HIDDEN_ROLES.filterNot(roleNames::contains))
                assertTrue("$name: the role Facebook gives what it can't name", "UNSET_OR_UNRECOGNIZED_ENUM_VALUE" in roleNames)
                val pageConstructor = kept.getValue(page.definingClass).methods.single { isConstructor(it, page) }
                assertTrue("$name: the page constructor has no local", pageConstructor.localRegisterCount() >= 1)
                assertTrue("$name: the page keeps its modules or name past v15",
                    listOf(PAGE_MODULES, PAGE_NAME).all { pageConstructor.parameterRegisterNumber(it) <= 15 })
                assertEquals("$name: the page's name argument", "Ljava/lang/String;", pageConstructor.parameterTypes[PAGE_NAME].toString())
                assertTrue("$name: ImmutableList.copyOf(Collection)", hasCollectionCopy(kept.getValue(IMMUTABLE_LIST)))

                // The suggestion route.
                val routeQuestions = held(META_AI_TAB).flatMap { it.methods.mapNotNull(::routeQuestion) }
                assertEquals("$name: results routers", 1, routeQuestions.size)
                val impl = kept.getValue(QUERY_SPEC_IMPL)
                val field = routeField(impl, routeQuestions.single())
                assertNotNull("$name: the spec's route field", field)
                val builderField = builderRouteField(impl, field!!)
                assertNotNull("$name: the builder's route field", builderField)
                // 581 asks a string table for the typeahead source the parser files suggestions under.
                val logging = held(KEYWORD_TYPE_FAILURE).flatMap { methodsHolding(it, KEYWORD_TYPE_FAILURE) }
                val tableTypes = logging.flatMap { it.implementation!!.instructions.filter(::isStringTableCall) }
                    .mapNotNull { it.call?.definingClass }.toSet()
                val tables = FixtureDex.classes(fixture, tableTypes)
                val parsers = logging.filter { method ->
                    isSuggestionParser(method) { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                }
                assertEquals("$name: suggestion parsers", 1, parsers.size)
                val parser = parsers.single()
                val stores = routeStores(parser, builderField!!)
                assertEquals("$name: stores of the route flag from the server", 1, stores.size)
                val store = stores.single()
                val parserCode = parser.implementation!!.instructions.toList()
                if (readThroughModel.getValue(version)) {
                    // The accessor the parser calls is the one method (constructors aside) loading the key.
                    val read = parserCode[store.store - 2].call!!
                    val accessor = "${read.name}()Z"
                    assertTrue("$name: no class reads the flag's key in $accessor", accessor in keyReaders)
                    assertEquals("$name: methods reading the flag's key that aren't the accessor or a constructor",
                        emptyList<String>(), keyReaders.filterNot { it == accessor || it.startsWith("<init>(") })
                } else {
                    assertEquals("$name: the flag's key", routeFlagKey,
                        (parserCode[store.store - 3] as NarrowLiteralInstruction).narrowLiteral)
                }
                assertTrue("$name: the flag is kept past v15", store.register <= 15)

                // The explicit ways in go around the hooks: the AI mode start sets the builder's
                // field with a constant true, and the Meta AI tab builds its answer without the question.
                val starts = held(aiModeStart).flatMap { methodsHolding(it, aiModeStart) }.filter { method ->
                    val code = method.implementation!!.instructions.toList()
                    code.indices.any { index ->
                        code[index].opcode == Opcode.IPUT_BOOLEAN && code[index].field?.let { sameField(it, builderField) } == true &&
                            code.getOrNull(index - 1)?.let {
                                it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 1 &&
                                    (it as OneRegisterInstruction).registerA == (code[index] as TwoRegisterInstruction).registerA
                            } == true
                    }
                }
                assertEquals("$name: AI mode starts setting the route with a constant", 1, starts.size)
                assertEquals("$name: the Meta AI tab's answer", 1, tabAnswers.size)
                assertFalse("$name: the Meta AI tab asks the answer question",
                    tabAnswers.single().implementation!!.instructions.any { instruction ->
                        instruction.call?.let { it.definingClass == question.definingClass && it.name == question.name } == true
                    })
                assertTrue("$name: the Meta AI tab builds a page too",
                    tabAnswers.single().implementation!!.instructions.any { instruction ->
                        instruction.call?.let { pageCall(it, resolve)?.page }?.let { isConstructor(pageConstructor, it) } == true
                    })
                assertTrue("$name: the Meta AI tab doesn't name its page \"$META_AI_TAB_PAGE\"",
                    namesPage(tabAnswers.single(), page, META_AI_TAB_PAGE, resolve))
                assertEquals("$name: the Meta AI tab's page name is its builder's literal", metaAiTabModule, META_AI_TAB_PAGE)

                // The patch, on this build's own classes.
                val classes = (held(ANSWER_PAGE) + roleParsers + held(META_AI_TAB) + held(KEYWORD_TYPE_FAILURE) +
                    held(metaAiTabModule) +
                    callerClasses + module + kept.values + tables.values +
                    listOf(ExtensionDex.classDef(SETTINGS_STATUS), ExtensionDex.classDef(META_AI_SEARCH)))
                    .distinctBy { it.type }
                val context = PatchContexts.of(classes)
                hideMetaAiInSearchPatch.execute(context)
                fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }

                val asked = patched(gate).implementation!!.instructions.toList()
                assertEquals("$name: the answer question's hook", HIDE_ANSWER, asked[0].call.toString())
                assertEquals("$name: the answer question grew by the hook",
                    gate.implementation!!.instructions.count() + 5, asked.size)

                val built = patched(pageConstructor).implementation!!.instructions.toList()
                assertEquals("$name: the page's hook", listOf(KEPT_MODULES, COPY_OF), listOf(built[0], built[3]).map { it.call.toString() })
                assertEquals("$name: the modules and the name read",
                    listOf(PAGE_MODULES, PAGE_NAME).map { pageConstructor.parameterRegisterNumber(it) }, built[0].callArguments())
                assertEquals("$name: the copy goes back to the modules", pageConstructor.parameterRegisterNumber(1),
                    (built[4] as OneRegisterInstruction).registerA)
                assertEquals("$name: the page's own first instruction after the hook",
                    pageConstructor.implementation!!.instructions.first().opcode, built[5].opcode)

                val stored = patched(parser).implementation!!.instructions.toList()
                assertEquals("$name: the route's hook", OPENS_META_AI, stored[store.store].call.toString())
                assertEquals("$name: the flag read", listOf(store.register), stored[store.store].callArguments())
                assertEquals(Opcode.MOVE_RESULT, stored[store.store + 1].opcode)
                assertEquals(store.register, (stored[store.store + 1] as OneRegisterInstruction).registerA)
                assertEquals(Opcode.IPUT_BOOLEAN, stored[store.store + 2].opcode)
                assertTrue("$name: a register past v15", stored[store.store].callArguments().all { it <= 15 })

                val stub = context.mutableClassDefBy(META_AI_SEARCH).methods.single { it.name == ROLE_STUB }
                    .implementation!!.instructions.toList()
                assertEquals("$name: the stub's cast", module.type, ((stub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the stub's call", "${module.type}->${accessors.single().name}()$role", stub[1].call.toString())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
