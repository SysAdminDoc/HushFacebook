/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.BASE_MODEL_WITH_TREE
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.TREE_JNI
import app.morphe.patches.facebook.feed.aidetected.GRAPHQL_STORY_ATTACHMENT
import app.morphe.patches.facebook.feed.aidetected.isModelListAccessor
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isModelAccessor
import app.morphe.patches.facebook.feed.isStoryModelAccessor
import app.morphe.patches.facebook.feed.loadedClass
import app.morphe.patches.facebook.feed.modelAccessors
import app.morphe.patches.facebook.feed.treeTypeTag
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide posts from groups you're not in (#195) on every Facebook build the bundle declares: one
 * GraphQLStory accessor of `to` as a Profile and one of `target_group` as a Group answering the
 * same class, its model a BaseModelWithTree with one accessor of viewer_join_state, and the enum
 * answering it naming every state the extension reads. The patch fills the extension's three stubs
 * with calls to those accessors, and hands Home's MobileConfig answer for
 * "should_include_viewer_join_state" to the extension. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class GroupJoinStateFixtureTest {
    /** Every name of Facebook 583's join state enum, the two that hide a post and the four that keep it. */
    private val allStates = listOf(
        "CAN_JOIN", "CAN_REQUEST", "MEMBER", "REQUESTED", "CANNOT_JOIN_OR_REQUEST", "UNSET_OR_UNRECOGNIZED_ENUM_VALUE",
    )

    private fun ClassDef.stub(name: String): Method = methods.single { it.name == name }

    /** The first two instructions of a filled stub: the cast to the accessor's class, and the call. */
    private fun Method.castAndCall(): Pair<String, String> {
        val code = implementation!!.instructions.toList()
        assertEquals("$name: cast first", Opcode.CHECK_CAST, code[0].opcode)
        assertEquals("$name: call second", Opcode.INVOKE_VIRTUAL, code[1].opcode)
        val cast = ((code[0] as ReferenceInstruction).reference as TypeReference).type
        val call = (code[1] as ReferenceInstruction).reference as MethodReference
        return cast to "${call.definingClass}->${call.name}()${call.returnType}"
    }

    @Test
    fun `each declared build has one group target and one join state, and the stubs call them`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val story = FixtureDex.classes(bundle, setOf(GRAPHQL_STORY))[GRAPHQL_STORY]
                    ?: throw AssertionError("$name has no $GRAPHQL_STORY")
                val targets = storyTargetAccessors(story)
                assertEquals("$name: ${targets.map { it.name }}", 1, targets.size)
                val target = targets.single()
                // The control: the story's other accessors of the same model class load other keys,
                // so only `to` matches.
                val siblings = story.methods.filter {
                    it.name != target.name && it.parameterTypes.isEmpty() && it.returnType == target.returnType
                }
                for (sibling in siblings) {
                    assertFalse("$name: ${sibling.name}() matches too",
                        isStoryModelAccessor(sibling, STORY_TARGET_FIELD, STORY_TARGET_TYPE))
                }
                // The group a story names as its target_group, read as the same class as its `to`
                // (the fourth report on #195); the same control against its siblings.
                val groups = storyTargetGroupAccessors(story)
                assertEquals("$name: target_group ${groups.map { it.name }}", 1, groups.size)
                val group = groups.single()
                assertEquals("$name: ${group.name}() doesn't answer the class `to` does", target.returnType, group.returnType)
                for (sibling in story.methods.filter {
                    it.name != group.name && it.parameterTypes.isEmpty() && it.returnType == group.returnType
                }) {
                    assertFalse("$name: ${sibling.name}() matches target_group too",
                        isStoryModelAccessor(sibling, STORY_TARGET_GROUP_FIELD, STORY_TARGET_GROUP_TYPE))
                }

                val profile = FixtureDex.classes(bundle, setOf(target.returnType))[target.returnType]
                    ?: throw AssertionError("$name has no ${target.returnType}")
                assertEquals("$name: ${profile.type} answers getTypeName as a tree model", BASE_MODEL_WITH_TREE,
                    profile.superclass)
                val states = joinStateAccessors(profile)
                assertEquals("$name: ${states.map { it.name }}", 1, states.size)
                val state = states.single()
                val enum = FixtureDex.classes(bundle, setOf(state.returnType))[state.returnType]
                    ?: throw AssertionError("$name has no ${state.returnType}")
                assertTrue("$name: ${enum.type} isn't the join state enum", isJoinStateEnum(enum))
                val init = enum.methods.single { it.name == "<clinit>" }
                for (constant in allStates) {
                    assertTrue("$name: ${enum.type} doesn't name $constant", holdsString(init, constant))
                }

                val context = PatchContexts.of(listOf(story, profile, enum, ExtensionDex.classDef(GROUP_POSTS)))
                val found = context.fillGroupJoinStubs()
                assertEquals("$name: the target_group accessor the patch took", group.name, found.targetGroup?.name)
                val filled = context.mutableClassDefBy(GROUP_POSTS)
                assertEquals("$name: storyTarget",
                    GRAPHQL_STORY to "$GRAPHQL_STORY->${target.name}()${target.returnType}",
                    filled.stub(STORY_TARGET_STUB).castAndCall())
                assertEquals("$name: storyTargetGroup",
                    GRAPHQL_STORY to "$GRAPHQL_STORY->${group.name}()${group.returnType}",
                    filled.stub(STORY_TARGET_GROUP_STUB).castAndCall())
                assertEquals("$name: joinState",
                    profile.type to "${profile.type}->${state.name}()${state.returnType}",
                    filled.stub(JOIN_STATE_STUB).castAndCall())
                checked[version] = "${target.name}()${target.returnType} ${group.name}() -> ${state.name}()${state.returnType}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    /**
     * Debug logging's shape of a story posted to no profile (#195): one accessor each of the story's
     * title and shareable, the title class's ranges list, the range class's entity and the attachment's
     * target, the target read as the class the story's `to` is (so the join state stub takes it), and
     * the five stubs calling them. The control: the title's and the attachment's siblings of the same
     * return type load other keys.
     */
    @Test
    fun `each declared build has one accessor for each piece of a story's shape, and the stubs call them`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val first = FixtureDex.classes(bundle, setOf(GRAPHQL_STORY, GRAPHQL_STORY_ATTACHMENT))
                val story = first[GRAPHQL_STORY] ?: throw AssertionError("$name has no $GRAPHQL_STORY")
                val attachment = first[GRAPHQL_STORY_ATTACHMENT]
                    ?: throw AssertionError("$name has no $GRAPHQL_STORY_ATTACHMENT")
                val titles = modelAccessors(story, STORY_TITLE_FIELD, STORY_TITLE_TYPE)
                assertEquals("$name: title ${titles.map { it.name }}", 1, titles.size)
                val shareables = modelAccessors(story, STORY_SHAREABLE_FIELD, STORY_SHAREABLE_TYPE)
                assertEquals("$name: shareable ${shareables.map { it.name }}", 1, shareables.size)
                val title = titles.single()
                for (sibling in story.methods.filter {
                    it.name != title.name && it.parameterTypes.isEmpty() && it.returnType == title.returnType
                }) {
                    assertFalse("$name: ${sibling.name}() matches title too",
                        isModelAccessor(sibling, GRAPHQL_STORY, STORY_TITLE_FIELD, STORY_TITLE_TYPE))
                }

                val text = FixtureDex.classes(bundle, setOf(title.returnType))[title.returnType]
                    ?: throw AssertionError("$name has no ${title.returnType}")
                val ranges = text.methods.filter { isModelListAccessor(it, text.type, TITLE_RANGES_FIELD, TITLE_RANGE_TYPE) }
                assertEquals("$name: ranges ${ranges.map { it.name }}", 1, ranges.size)
                val rangeClass = ranges.single().loadedClass()
                assertNotNull("$name: ${ranges.single().name} names no element class", rangeClass)
                val range = FixtureDex.classes(bundle, setOf(rangeClass!!))[rangeClass]
                    ?: throw AssertionError("$name has no $rangeClass")
                val entities = modelAccessors(range, RANGE_ENTITY_FIELD, RANGE_ENTITY_TYPE)
                assertEquals("$name: entity ${entities.map { it.name }}", 1, entities.size)

                val targets = modelAccessors(attachment, ATTACHMENT_TARGET_FIELD, ATTACHMENT_TARGET_TYPE)
                assertEquals("$name: target ${targets.map { it.name }}", 1, targets.size)
                val target = targets.single()
                for (sibling in attachment.methods.filter {
                    it.name != target.name && it.parameterTypes.isEmpty() && it.returnType == target.returnType
                }) {
                    assertFalse("$name: ${sibling.name}() matches target too",
                        isModelAccessor(sibling, GRAPHQL_STORY_ATTACHMENT, ATTACHMENT_TARGET_FIELD, ATTACHMENT_TARGET_TYPE))
                }
                assertEquals("$name: the attachment's target isn't read as the story's `to`",
                    storyTargetAccessors(story).single().returnType, target.returnType)

                val context = PatchContexts.of(listOf(story, text, range, attachment, ExtensionDex.classDef(GROUP_POSTS)))
                context.fillStoryShapeStubs()
                val filled = context.mutableClassDefBy(GROUP_POSTS)
                for ((stub, accessor) in listOf(
                    STORY_TITLE_STUB to title, STORY_SHAREABLE_STUB to shareables.single(),
                    TITLE_RANGES_STUB to ranges.single(), RANGE_ENTITY_STUB to entities.single(),
                    ATTACHMENT_TARGET_STUB to target,
                )) {
                    assertEquals("$name: $stub",
                        accessor.definingClass to "${accessor.definingClass}->${accessor.name}()${accessor.returnType}",
                        filled.stub(stub).castAndCall())
                }
                checked[version] = "${title.name} ${shareables.single().name} ${ranges.single().name} " +
                    "${entities.single().name} ${target.name}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    /**
     * A group a story's title names (#195) is a bare entity, read again as the class the story's `to`
     * is read as through TreeJNI's public reinterpret(Class, int), which every declared build keeps.
     * The patch turns the load of the extension's storyTargetClass stub into a `const-class` of that
     * class, which the stub's own return hands back.
     */
    @Test
    fun `each declared build reinterprets a tree as another class, and the stub answers the class of to`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val first = FixtureDex.classes(bundle, setOf(GRAPHQL_STORY, TREE_JNI))
                val story = first[GRAPHQL_STORY] ?: throw AssertionError("$name has no $GRAPHQL_STORY")
                val tree = first[TREE_JNI] ?: throw AssertionError("$name has no $TREE_JNI")
                assertTrue("$name: TreeJNI has no public $TREE_REINTERPRET(Class, int)", hasPublicReinterpret(tree))
                val target = storyTargetAccessors(story).single()
                val profile = FixtureDex.classes(bundle, setOf(target.returnType))[target.returnType]
                    ?: throw AssertionError("$name has no ${target.returnType}")

                val context = PatchContexts.of(listOf(story, tree, profile, ExtensionDex.classDef(GROUP_POSTS)))
                context.fillStoryTargetClassStub()
                val code = context.mutableClassDefBy(GROUP_POSTS).stub(TARGET_CLASS_STUB).code()
                assertEquals("$name: the stub's load replaced, its return kept", 2, code.size)
                assertEquals("$name: const-class first", Opcode.CONST_CLASS, code[0].opcode)
                assertEquals("$name: the class `to` is read as", target.returnType,
                    ((code[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: answered second", Opcode.RETURN_OBJECT, code[1].opcode)
                assertEquals("$name: answered from the register it loaded",
                    (code[0] as OneRegisterInstruction).registerA, (code[1] as OneRegisterInstruction).registerA)
                checked[version] = target.returnType
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    /**
     * The control: a TreeJNI whose reinterpret is missing, static, private, takes other arguments or
     * answers something else doesn't pass, and the patch refuses a tree without one before it asks
     * anything of the story.
     */
    @Test
    fun `a tree without a public reinterpret(Class, int) is refused`() {
        fun tree(vararg methods: Method): ClassDef =
            ImmutableClassDef(TREE_JNI, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())
        fun reinterpret(flags: Int, vararg parameters: String, returnType: String = TREE_JNI): Method = ImmutableMethod(
            TREE_JNI, TREE_REINTERPRET, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags,
            null, null, null,
        )
        val native = AccessFlags.PUBLIC.value or AccessFlags.NATIVE.value
        assertTrue("Facebook's", hasPublicReinterpret(tree(reinterpret(native, "Ljava/lang/Class;", "I"))))
        assertFalse("none", hasPublicReinterpret(tree()))
        assertFalse("static", hasPublicReinterpret(tree(reinterpret(native or AccessFlags.STATIC.value, "Ljava/lang/Class;", "I"))))
        assertFalse("private", hasPublicReinterpret(
            tree(reinterpret(AccessFlags.PRIVATE.value or AccessFlags.NATIVE.value, "Ljava/lang/Class;", "I"))))
        assertFalse("other arguments", hasPublicReinterpret(tree(reinterpret(native, "I"))))
        assertFalse("another answer", hasPublicReinterpret(
            tree(reinterpret(native, "Ljava/lang/Class;", "I", returnType = "Ljava/lang/Object;"))))
        val refusal = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(tree(), ExtensionDex.classDef(GROUP_POSTS))).fillStoryTargetClassStub()
        }.message!!
        assertTrue(refusal, refusal.contains(TREE_REINTERPRET))
    }

    /** Whether [called] is MobileConfig's own, or a static helper whose body casts to it and asks it. */
    private fun asksMobileConfig(bundle: File, called: MethodReference): Boolean {
        val config = "/MobileConfigUnsafeContext;"
        if (called.definingClass.endsWith(config)) return true
        val helper = FixtureDex.classes(bundle, setOf(called.definingClass))[called.definingClass]?.methods?.singleOrNull {
            it.name == called.name && it.returnType == called.returnType &&
                it.parameterTypes.map(CharSequence::toString) == called.parameterTypes.map(CharSequence::toString)
        } ?: return false
        val body = helper.implementation?.instructions?.toList() ?: return false
        return body.any {
            it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type.endsWith(config)
        } && body.any { it.opcode == Opcode.INVOKE_INTERFACE && it.called()?.definingClass?.endsWith(config) == true }
    }

    @Test
    fun `each declared build asks Home for join states through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = FixtureDex.classesHolding(bundle, JOIN_STATE_QUERY_FLAG)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val holders = context.joinStateFlagHolders()
                assertEquals("$name: methods setting \"$JOIN_STATE_QUERY_FLAG\"", 1, holders.size)
                val method = holders.single()
                val answer = joinStateFlagAnswer(method)
                assertNotNull("$name: the flag isn't set from a boolean answer", answer)
                val code = method.code()
                // Positive control: the answer is MobileConfig's (asked of the interface, of its static
                // helper since 584, or on 588 of an outlined helper that casts to it and asks it), and
                // the name goes to a setter with it.
                val asked = code[answer!! - 1]
                assertTrue("$name: answer asked with ${asked.opcode}",
                    asked.opcode == Opcode.INVOKE_INTERFACE || asked.opcode == Opcode.INVOKE_STATIC)
                assertTrue("$name: ${asked.called()} isn't MobileConfig",
                    asksMobileConfig(bundle, asked.called()!!))
                val register = (code[answer] as OneRegisterInstruction).registerA
                val setter = code[answer + 2].called()
                assertEquals("$name: $setter doesn't take the name and the answer",
                    listOf("Ljava/lang/String;", "Z"), setter?.parameterTypes?.takeLast(2)?.map(CharSequence::toString))
                assertEquals("$name: the setter's last register is the answer", register,
                    (code[answer + 2] as FiveRegisterInstruction).registerE)

                context.askForJoinState()
                val patched = context.mutableClassDefBy(method.definingClass).methods
                    .single { patchedMethod ->
                        patchedMethod.name == method.name &&
                            patchedMethod.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                    }.code()
                assertEquals("$name: two instructions added", code.size + 2, patched.size)
                val ask = patched[answer + 1]
                assertEquals("$name: the extension asked", ASK_FOR_JOIN_STATE, ask.called()?.toString())
                assertEquals("$name: handed the boolean",
                    listOf(Opcode.INVOKE_STATIC_RANGE, register, 1),
                    listOf(ask.opcode, (ask as RegisterRangeInstruction).startRegister, ask.registerCount))
                assertEquals("$name: its answer back in the same register",
                    listOf(Opcode.MOVE_RESULT, register),
                    listOf(patched[answer + 2].opcode, (patched[answer + 2] as OneRegisterInstruction).registerA))
                assertEquals("$name: the name right after", JOIN_STATE_QUERY_FLAG,
                    ((patched[answer + 3] as ReferenceInstruction).reference as StringReference).string)
                val rest = patched.filterIndexed { index, _ -> index != answer + 1 && index != answer + 2 }
                assertEquals("$name: the rest is Facebook's", code.map { it.opcode }, rest.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The field names and the group type the extension reads by are the ones the patch finds by. */
    @Test
    fun `the extension reads the names the patch anchors on`() {
        assertEquals(STORY_TARGET_FIELD, ExtensionDex.stringConstant(GROUP_POSTS, "TARGET_FIELD"))
        assertEquals(STORY_TARGET_GROUP_FIELD, ExtensionDex.stringConstant(GROUP_POSTS, "TARGET_GROUP_FIELD"))
        assertEquals(VIEWER_JOIN_STATE_FIELD, ExtensionDex.stringConstant(GROUP_POSTS, "JOIN_STATE_FIELD"))
        assertEquals(STORY_TARGET_GROUP_TYPE, ExtensionDex.stringConstant(GROUP_POSTS, "GROUP_TYPE"))
        assertEquals(TREE_REINTERPRET, ExtensionDex.stringConstant(GROUP_POSTS, "REINTERPRET"))
        assertEquals("the Group type tag a title's group is read as", treeTypeTag(STORY_TARGET_GROUP_TYPE),
            ExtensionDex.intConstant(GROUP_POSTS, "GROUP_TYPE_TAG"))
        val stubs = ExtensionDex.classDef(GROUP_POSTS).methods.map { it.name }
        for (stub in listOf(STORY_TARGET_STUB, STORY_TARGET_GROUP_STUB, JOIN_STATE_STUB, STORY_TITLE_STUB,
            STORY_SHAREABLE_STUB, TITLE_RANGES_STUB, RANGE_ENTITY_STUB, ATTACHMENT_TARGET_STUB, TARGET_CLASS_STUB)) {
            assertTrue("the extension has no $stub", stub in stubs)
        }
        val hook = ExtensionDex.classDef(GROUP_POSTS).methods.singleOrNull { it.name == "askForJoinState" }
        assertNotNull("the extension has no GroupPosts.askForJoinState", hook)
        assertEquals("askForJoinState's shape", ASK_FOR_JOIN_STATE,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("askForJoinState isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
