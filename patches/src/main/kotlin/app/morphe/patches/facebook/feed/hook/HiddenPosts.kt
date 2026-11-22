/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.executeBytecode
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Kept literal: the field a post's hide, "Hide all from" and Undo publish, which few methods load. */
internal const val LOCAL_STORY_VISIBILITY = "local_story_visibility"

/** Kept message of the cast to the typed builder that sets the same field without naming it. */
internal const val VISIBILITY_BUILDER_CAST =
    "null cannot be cast to non-null type com.facebook.api.graphql.feed.clientfields.StoryVisibilityLocalFields.Builder"

private const val CONSISTENCY = "Lcom/facebook/graphservice/interfaces/GraphQLConsistency;"
private const val TREE = "Lcom/facebook/graphservice/interfaces/Tree;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val STRING = "Ljava/lang/String;"

/** How the hooks hand a publish to the extension: the post's id, then Facebook's visibility value. */
internal const val PUBLISHED = "$EXTENSION_PACKAGE/feed/HiddenPosts;->published($STRING$INTEGER)V"

/** The key Facebook's trees read a post's `id` under: the hash of the field's name. */
private val ID_KEY = "id".hashCode()

/**
 * Where [method] publishes a post's local visibility: the instruction the hook goes in front of,
 * and the registers holding the post's id and the visibility value there.
 */
internal data class HidePublish(val method: Method, val at: Int, val id: Int, val visibility: Int)

private fun Instruction.call(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The register Dalvik puts [method]'s parameter number [index] in. */
private fun parameterRegister(method: Method, index: Int): Int {
    val words = method.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return method.implementation!!.registerCount - words.sum() + words.take(index).sum()
}

/** Whether anything in [body] before [end] writes [register], an if on it included, to be safe. */
private fun writes(body: List<Instruction>, end: Int, register: Int): Boolean =
    body.subList(0, end).any { (it as? OneRegisterInstruction)?.registerA == register }

/**
 * The publish in [method], or null when it isn't one. A publish answers nothing, takes Facebook's
 * visibility as its one Integer and sets the field one of two ways: it names
 * [LOCAL_STORY_VISIBILITY], itself or through a string table [resolve] finds, and hands a tree to
 * GraphQLConsistency's publish, or it casts to the typed builder ([VISIBILITY_BUILDER_CAST]) and
 * hands that to publishBuilder. 588 has two, in one class:
 *
 * - by id, taking the post's id as its one String, through the typed builder: the hook goes first
 *   thing, on the parameters;
 * - by story, taking the post's model, through the string table: it reads the id off the model's
 *   tree with getString("id"), and the hook goes right after that read, on the register the read
 *   filled.
 *
 * Both hand Facebook's own value on, which its mapping names VISIBLE, CONTRACTING, HIDDEN or GONE.
 * StoryDeleteMutation's optimistic GONE answers a future, the tree parsers publish nothing, and the
 * menu's direct hide and the reel hides take no Integer.
 */
internal fun hidePublish(method: Method, resolve: (MethodReference) -> Method?): HidePublish? {
    if (method.returnType != "V") return null
    val body = method.implementation?.instructions?.toList() ?: return null
    val parameters = method.parameterTypes.map { it.toString() }
    val visibilityIndex = parameters.indexOf(INTEGER)
    if (visibilityIndex < 0 || parameters.lastIndexOf(INTEGER) != visibilityIndex) return null
    val published = body.mapNotNull { it.call() }.filter { it.definingClass == CONSISTENCY }.map { it.name }
    val setsField = "publish" in published && namesString(method, LOCAL_STORY_VISIBILITY, resolve) ||
        "publishBuilder" in published && holdsString(method, VISIBILITY_BUILDER_CAST)
    if (!setsField) return null
    val visibility = parameterRegister(method, visibilityIndex)

    val strings = parameters.indices.filter { parameters[it] == STRING }
    if (strings.size == 1) return HidePublish(method, 0, parameterRegister(method, strings.single()), visibility)
    if (strings.isNotEmpty()) return null

    for (index in body.indices) {
        val call = body[index].call() ?: continue
        if (call.definingClass != TREE || call.name != "getString" || call.parameterTypes.map { it.toString() } != listOf("I")) {
            continue
        }
        val key = (body[index] as FiveRegisterInstruction).registerD
        val loaded = body.subList(0, index).lastOrNull { (it as? OneRegisterInstruction)?.registerA == key }
        if ((loaded as? NarrowLiteralInstruction)?.narrowLiteral != ID_KEY) continue
        val result = body.getOrNull(index + 1) ?: return null
        if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return null
        val at = index + 2
        if (writes(body, at, visibility)) return null
        return HidePublish(method, at, (result as OneRegisterInstruction).registerA, visibility)
    }
    return null
}

/**
 * Remembers the posts the person hides, for the feed guard (#444).
 *
 * <p>With the guard in, "Hide all from" on a post and a pull to refresh brought the same post back
 * as a new edge, while paused it stayed hidden. Each publish of a post's local visibility hands the
 * post's id and the value to the extension's HiddenPosts, which the guard asks next to its seen
 * rule. The guard brings this along, so it runs wherever something can hide an edge.
 *
 * <p>A build that moved the publishes only loses this, with a warning, never the guard.
 */
internal val hiddenPostsPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        val naming = classDefByStrings(LOCAL_STORY_VISIBILITY, StringComparisonType.EQUALS).associateBy { it.type }
        val resolve = { call: MethodReference -> naming[call.definingClass]?.let { resolveStatic(it, call) } }
        val publishes = (naming.values + classDefByStrings(VISIBILITY_BUILDER_CAST, StringComparisonType.EQUALS))
            .distinctBy { it.type }
            .flatMap { owner -> owner.methods.mapNotNull { hidePublish(it, resolve) } }
            .filter { it.id <= 15 && it.visibility <= 15 }
        if (publishes.isEmpty()) {
            patchLog.warning("Hidden posts: no publish of $LOCAL_STORY_VISIBILITY found. A refresh can bring a hidden post back.")
            return@executeBytecode
        }
        for (publish in publishes) {
            val found = publish.method
            mutableClassDefBy(found.definingClass).methods.single {
                it.name == found.name && it.returnType == found.returnType &&
                    it.parameterTypes.map { type -> type.toString() } == found.parameterTypes.map { type -> type.toString() }
            }.addInstructions(publish.at, "invoke-static { v${publish.id}, v${publish.visibility} }, $PUBLISHED")
        }
    }
}
