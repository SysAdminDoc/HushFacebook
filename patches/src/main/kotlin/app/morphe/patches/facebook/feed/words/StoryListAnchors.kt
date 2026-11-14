/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.words

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The extension class that judges a group's rows, its entry point and the two stubs this patch fills in. */
internal const val TIMELINE_WORDS_CLASS = "$EXTENSION_PACKAGE/feed/TimelineWords;"
internal const val HIDE_IN_LIST = "$TIMELINE_WORDS_CLASS->hideInList(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val LIST_TYPE_STUB = "listType"
internal const val LIST_NAME_STUB = "listName"

/** The name Facebook's story component gives itself in its constructor. Only that class holds exactly it. */
internal const val STORY_COMPONENT_NAME = "GraphQLStoryComponent"

/**
 * Names Facebook's enum of feed list names (FeedListName, renamed) gives its constants, all held by
 * its static initializer: the group lists the extension judges, and two it leaves alone.
 */
internal val LIST_NAME_MARKERS = listOf(
    "GROUPS", "GROUPS_PINNED", "GROUPS_MEMBER_BIO_FEED", "CROSS_GROUP_FEED", "GROUP_SET_FEED", "PERMALINK", "SEARCH_RESULTS",
)

/**
 * What the patch found in Facebook's story component: its render method, the field holding the
 * row's props and the props' field the render method casts to GraphQLStory, the field holding the
 * row's environment, and the two getters that name the row's list from it.
 */
internal class StoryComponent(
    val render: Method,
    val props: Field,
    val story: FieldReference,
    val environment: Field,
    val listType: Method,
    val listName: Method,
)

/**
 * Facebook's story component, the Litho component that draws a post on every surface (the News
 * feed, groups, profiles, search, an opened post), held to the evidence the hook rests on:
 * - it's the one class whose constructor holds exactly [STORY_COMPONENT_NAME];
 * - its one instance render method reads an Object field of its props and casts it to GraphQLStory;
 * - the props are in the one instance field of the props' type, which the render method reads;
 * - exactly one instance field's type reaches, through itself, its superclasses and its interfaces,
 *   a getter of a list type whose own getter answers an enum holding every name of
 *   [LIST_NAME_MARKERS] (FeedListName).
 */
internal fun BytecodePatchContext.storyComponent(): StoryComponent {
    val components = classDefByStrings(STORY_COMPONENT_NAME, StringComparisonType.EQUALS)
        .filter { classDef -> methodsHolding(classDef, STORY_COMPONENT_NAME).any { it.name == "<init>" } }
        .distinctBy { it.type }
    val component = components.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one class whose constructor holds \"$STORY_COMPONENT_NAME\", found " +
            components.joinToString { it.type }.ifEmpty { "none" },
    )

    val renders = component.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType.startsWith("L") }
        .mapNotNull { method -> storyRead(method)?.let { method to it } }
    val (render, story) = renders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method of ${component.type} that casts an Object field of its props to GraphQLStory, " +
            "found ${renders.joinToString { it.first.name }.ifEmpty { "none" }}",
    )

    val instanceFields = component.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
    val props = instanceFields.filter { it.type == story.definingClass }
    val propsField = props.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one field of ${component.type} typed ${story.definingClass}, found ${props.size}",
    )
    if (!readsField(render, propsField)) {
        throw PatchException("$PATCH: ${component.type}->${render.name} doesn't read ${propsField.name}")
    }

    val enums = mutableMapOf<String, Boolean>()
    val environments = instanceFields.mapNotNull { field -> listGetters(field.type, enums)?.let { field to it } }
    val (environment, getters) = environments.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one field of ${component.type} whose type names the row's list, found " +
            environments.joinToString { it.first.name }.ifEmpty { "none" },
    )
    if (!readsField(render, environment)) {
        throw PatchException("$PATCH: ${component.type}->${render.name} doesn't read ${environment.name}")
    }
    return StoryComponent(render, propsField, story, environment, getters.first, getters.second)
}

/** The render method [found] names, as the method a patch can add to. */
internal fun BytecodePatchContext.mutableStoryRender(found: StoryComponent): MutableMethod =
    mutableClassDefBy(found.render.definingClass).methods.single {
        it.name == found.render.name && it.returnType == found.render.returnType &&
            it.parameterTypes.map(Any::toString) == found.render.parameterTypes.map(Any::toString)
    }

/**
 * Where [method] reads the story: the Object field of an `iget-object` whose result the next
 * instruction casts to GraphQLStory. Null when it has none.
 */
internal fun storyRead(method: Method): FieldReference? {
    val code = method.implementation?.instructions?.toList() ?: return null
    for (index in 1 until code.size) {
        val cast = code[index]
        if (cast.opcode != Opcode.CHECK_CAST) continue
        if (((cast as ReferenceInstruction).reference as TypeReference).type != GRAPHQL_STORY) continue
        val read = code[index - 1]
        if (read.opcode != Opcode.IGET_OBJECT) continue
        val field = (read as ReferenceInstruction).reference as FieldReference
        if (field.type != "Ljava/lang/Object;") continue
        if ((read as TwoRegisterInstruction).registerA != (cast as OneRegisterInstruction).registerA) continue
        return field
    }
    return null
}

private fun readsField(method: Method, field: Field): Boolean =
    method.implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.IGET_OBJECT &&
            ((instruction as ReferenceInstruction).reference as FieldReference).let {
                it.definingClass == field.definingClass && it.name == field.name && it.type == field.type
            }
    } == true

/**
 * The getter of a list type that [type] answers, through itself, its superclasses and its
 * interfaces, with the list type's getter of the list's name: null unless there's exactly one of
 * each. A getter declared on two of the interfaces counts once.
 */
private fun BytecodePatchContext.listGetters(type: String, enums: MutableMap<String, Boolean>): Pair<Method, Method>? {
    if (!type.startsWith("L")) return null
    val found = linkedMapOf<String, Pair<Method, Method>>()
    for (classDef in closure(type)) {
        for (getter in classDef.methods) {
            if (AccessFlags.STATIC.isSet(getter.accessFlags) || getter.parameterTypes.isNotEmpty()) continue
            if (!getter.returnType.startsWith("L")) continue
            val listType = classDefByOrNull(getter.returnType) ?: continue
            val names = listType.methods.filter {
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && isListNameEnum(it.returnType, enums)
            }
            val name = names.singleOrNull() ?: continue
            found.putIfAbsent("${getter.name}()${getter.returnType}", getter to name)
        }
    }
    return found.values.singleOrNull()
}

/** [type], its superclasses and every interface any of them implements, those the app carries. */
private fun BytecodePatchContext.closure(type: String): List<ClassDef> {
    val seen = linkedMapOf<String, ClassDef>()
    val pending = ArrayDeque(listOf(type))
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (next in seen) continue
        val classDef = classDefByOrNull(next) ?: continue
        seen[next] = classDef
        classDef.superclass?.let(pending::add)
        pending += classDef.interfaces
    }
    return seen.values.toList()
}

/** Whether [type] is an enum whose static initializer holds every name of [LIST_NAME_MARKERS], kept in [seen]. */
private fun BytecodePatchContext.isListNameEnum(type: String, seen: MutableMap<String, Boolean>): Boolean =
    seen.getOrPut(type) {
        val classDef = classDefByOrNull(type)
        val init = classDef?.takeIf { it.superclass == "Ljava/lang/Enum;" }?.methods?.firstOrNull { it.name == "<clinit>" }
        init != null && LIST_NAME_MARKERS.all { holdsString(init, it) }
    }

/**
 * First thing in the story component's render method: hand the extension the story this row
 * draws and the row's environment, and answer no component when it says the row goes. Otherwise
 * the render method runs from its first instruction. v0 and v1 are free at index 0. `this` is
 * copied down into v0 because `iget-object` names its registers in four bits. A row without props
 * goes straight to Facebook's own code.
 */
internal fun MutableMethod.judgeStoryRows(found: StoryComponent) {
    if (AccessFlags.STATIC.isSet(accessFlags)) throw PatchException("$PATCH: $definingClass->$name is static")
    val props = found.props
    val environment = found.environment
    val story = found.story
    requireLocals(PATCH, 2)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v1, v0, ${environment.definingClass}->${environment.name}:${environment.type}
            iget-object v0, v0, ${props.definingClass}->${props.name}:${props.type}
            if-eqz v0, :render
            iget-object v0, v0, ${story.definingClass}->${story.name}:${story.type}
            invoke-static { v0, v1 }, $HIDE_IN_LIST
            move-result v0
            if-eqz v0, :render
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("render", getInstruction(0)),
    )
}
