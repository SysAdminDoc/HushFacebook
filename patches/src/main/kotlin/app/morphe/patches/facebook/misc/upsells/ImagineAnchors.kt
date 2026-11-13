/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.upsells

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.comments.summaries.isNameTable
import app.morphe.patches.facebook.comments.summaries.switchKeys
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.requireLocals
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
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Where Meta AI's Imagine shows up outside Meta AI itself, read from 577, 580 and 581 on
 * 2026-10-07. The obfuscated names here are for reviewers; the code finds each by a kept name.
 *
 * The Imagine me button under posts. A post gets one call-to-action from the socket that names
 * itself FeedStoryCtaSelectorSocket. Its name table (581 LX/2du;->A0K) names ImagineMePlugin among
 * its plugins, and the socket shows the first plugin whose check answers yes. Unlike the comment
 * sockets, the check doesn't sit in the table's class but in the plugin dispatcher (581
 * LX/2Ap;->A1u): the one static (props, int)Z the socket's methods call whose switch goes over the
 * table's numbers. The extension goes first in it, the way Hide Meta AI comment summaries does it,
 * and the Imagine me plugin gets a no, so the socket goes on to the next button.
 *
 * The other Meta AI buttons under posts come from the same socket and the same check: the name
 * table also names AIStylesPlugin, GenAiDeepDiveCtaPlugin, GenAiDeepDiveUgcChatIcebreakerCtaPlugin,
 * BizAiAgentCtaPlugin and FindsVisualSearchCtaPlugin on all three builds (581 LX/2du;->A0K). The
 * hook already hands the extension every plugin's name, so those get a no under their own switch
 * with nothing more to find. The patch doesn't require them: a build that drops one just has
 * nothing to hide there.
 *
 * Meta AI's deep dive under a post's caption comes from another socket, the one that names itself
 * FeedStoryContentCollectorSocket, whose name table (581 LX/2kX;->A0K) names
 * GenAiDeepDiveBelowCaptionPlugin. Only 581 gives that socket a check method of its own
 * (LX/2kX;->A0M); 577 and 580 inline the check into the collector (LX/2lC;->A1N, LX/2dL;->A1F).
 * Every build's check, and the plugin's own row builder on 581, read the deep dive through the
 * plugin's one getter of a post's deep dive (static on a GraphQLStory until 586, an instance method
 * on the row's props since 587), a kept class, and treat a null as nothing to show. So
 * the extension goes first in that getter and answers null while the switch is on.
 *
 * The post composer's Imagine. The composer asks its capabilities, an enum whose constants include
 * AI_GEN_IMAGINE, CHECKIN and FEED_COMPOSER_REDESIGN (581 LX/Byc), whether Imagine is on, each time
 * loading AI_GEN_IMAGINE right before an (enum)Z call: the Imagine sprout's eligibility (581
 * LX/CH5;->A07), the sprout list (581 LX/KEb;->A05) and the composer's state (581 LX/BtQ;->A0N).
 * Each answer goes through the extension, which says no.
 *
 * Create story's Imagine tile. The story composer's row of tools comes from an enum (TEXT_BASE,
 * BOOMERANG, IMAGINE, TRY_IT, ADD_YOURS_TEMPLATES and more; 581 LX/6nj). One method reads IMAGINE
 * (581 LX/JMC;->A00): it builds a tile for each tool in the list it's handed first, an
 * ImmutableList, and returns the tiles through ImmutableList.copyOf. That list goes through the
 * extension, which takes IMAGINE out, and back through the same copyOf, so the method gets the type
 * it had.
 */

internal const val IMAGINE_CTA_SOCKET = "FeedStoryCtaSelectorSocket"
internal const val IMAGINE_ME_PLUGIN = "com.facebook.feed.plugins.calltoaction.impl.imagineme.ImagineMePlugin"

/** The other Meta AI post buttons the CTA table names, which the extension's hook also answers. */
internal val META_AI_POST_PLUGINS = listOf(
    "com.facebook.feed.plugins.calltoaction.impl.aistyles.AIStylesPlugin",
    "com.facebook.feed.plugins.calltoaction.impl.genaideepdive.GenAiDeepDiveCtaPlugin",
    "com.facebook.feed.plugins.calltoaction.impl.genaideedpdiveugcchaticebreakercta.GenAiDeepDiveUgcChatIcebreakerCtaPlugin",
    "com.facebook.feed.plugins.calltoaction.impl.bizaiagent.BizAiAgentCtaPlugin",
    "com.facebook.feed.plugins.calltoaction.impl.findsvisualsearch.FindsVisualSearchCtaPlugin",
)

/** The plugin for Meta AI's deep dive under a post's caption, a kept class. */
internal const val CAPTION_DEEP_DIVE_PLUGIN =
    "Lcom/facebook/feed/plugins/calltoaction/impl/genaideepdivebelowcaption/GenAiDeepDiveBelowCaptionPlugin;"
internal const val GRAPHQL_STORY = "Lcom/facebook/graphql/model/GraphQLStory;"

internal const val COMPOSER_IMAGINE = "AI_GEN_IMAGINE"
internal val COMPOSER_CAPABILITIES = listOf(COMPOSER_IMAGINE, "CHECKIN", "FEED_COMPOSER_REDESIGN")

internal const val STORY_IMAGINE = "IMAGINE"
internal val STORY_TOOLS_NAMES = listOf(STORY_IMAGINE, "TEXT_BASE", "BOOMERANG", "TRY_IT", "ADD_YOURS_TEMPLATES")

internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
internal const val IMMUTABLE_COPY = "$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST"

internal const val HIDES_IMAGINE_CTA = "$META_UPSELLS->hidesImagineCta(Ljava/lang/String;)Z"
internal const val HIDES_CAPTION_DEEP_DIVE = "$META_UPSELLS->hidesDeepDiveBelowCaption()Z"
internal const val IMAGINE_CAPABILITY = "$META_UPSELLS->imagineCapability(Z)Z"
internal const val STORY_TOOLS = "$META_UPSELLS->storyTools(Ljava/util/List;)Ljava/util/List;"

private fun refuse(detail: String, patch: String = PATCH): Nothing = throw PatchException("$patch: $detail")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

private fun calls(method: Method): List<MethodReference> = method.code().mapNotNull { it.called() }

/**
 * The CTA selector's name table: the one static (I)String among [holders] that names [plugin],
 * [IMAGINE_ME_PLUGIN] unless another of the table's plugins is given. A refusal names [patch].
 */
internal fun ctaTable(holders: List<ClassDef>, plugin: String = IMAGINE_ME_PLUGIN, patch: String = PATCH): Method {
    val tables = holders.flatMap { methodsHolding(it, plugin) }.filter(::isNameTable).distinctBy { it.descriptor() }
    return tables.singleOrNull() ?: refuse("expected one name table naming $plugin, found ${tables.size}", patch)
}

/**
 * The CTA selector's check of whether a plugin applies: among the static (..., int)Z methods that
 * [sockets] (the methods naming [IMAGINE_CTA_SOCKET]) call along with [table], the one whose switch
 * goes over the table's plugin numbers. [classDefOf] finds a class by type. Refuses, naming
 * [patch], unless there's exactly one.
 */
internal fun ctaCheck(
    table: Method,
    sockets: List<Method>,
    patch: String = PATCH,
    classDefOf: (String) -> ClassDef?,
): Method {
    val tableCall = table.descriptor()
    val callers = sockets.filter { socket -> calls(socket).any { it.descriptor() == tableCall } }
    if (callers.isEmpty()) refuse("no method naming $IMAGINE_CTA_SOCKET calls $tableCall", patch)
    val numbers = switchKeys(table).singleOrNull() ?: refuse("$tableCall has more than one switch", patch)
    val candidates = callers.flatMap(::calls).filter {
        it.returnType == "Z" && it.parameterTypes.lastOrNull()?.toString() == "I"
    }.distinctBy { it.descriptor() }
    val checks = candidates.mapNotNull { called ->
        classDefOf(called.definingClass)?.methods?.firstOrNull { it.descriptor() == called.descriptor() }
    }.filter { AccessFlags.STATIC.isSet(it.accessFlags) && numbers in switchKeys(it) }
    return checks.singleOrNull() ?: refuse(
        "expected one check of $tableCall's plugins, found ${checks.size}: ${checks.joinToString { it.descriptor() }}",
        patch,
    )
}

/**
 * The getter the caption deep dive's check and row read the deep dive through: [plugin]'s one
 * method answering an object from a post, either static taking a GraphQLStory or, since 587
 * (arm64 `A00(LX/03FD;)LX/0V49;`), an instance method taking the row's props, whose code casts what
 * the props carry to a GraphQLStory. Refuses, naming [patch], unless there's exactly one.
 */
internal fun captionDeepDiveGetter(plugin: ClassDef, patch: String = PATCH): Method {
    val getters = plugin.methods.filter {
        val parameters = it.parameterTypes.map(CharSequence::toString)
        it.implementation != null && it.returnType.startsWith("L") && if (AccessFlags.STATIC.isSet(it.accessFlags)) {
            parameters == listOf(GRAPHQL_STORY)
        } else {
            !it.name.startsWith("<") && parameters.size == 1 && parameters[0].startsWith("L") && castsToStory(it)
        }
    }
    return getters.singleOrNull()
        ?: refuse("expected one getter of a post's deep dive in ${plugin.type}, found ${getters.size}", patch)
}

private fun castsToStory(method: Method) = method.code().any {
    it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as? TypeReference)?.type == GRAPHQL_STORY
}

/**
 * First thing in the getter: ask the extension ([hook], a static ()Z), and answer null on a yes.
 * Otherwise the getter runs as before.
 */
internal fun MutableMethod.dropCaptionDeepDive(hook: String = HIDES_CAPTION_DEEP_DIVE, patch: String = PATCH) {
    requireLocals(patch, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :read
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("read", getInstruction(0)),
    )
}

/** Whether [classDef] is an enum whose static initializer loads every one of [names]. */
internal fun isEnumNaming(classDef: ClassDef, names: List<String>): Boolean =
    classDef.superclass == "Ljava/lang/Enum;" &&
        classDef.methods.any { it.name == "<clinit>" && names.all { name -> holdsString(it, name) } }

/**
 * The static field holding [enum]'s constant named [name]: the first field of the enum's own type
 * its static initializer stores into after loading the name, with no other string loaded between.
 * Builds differ in how they make the constant (a constructor call, a copy of the register, a
 * factory), but each stores it right after.
 */
internal fun enumConstant(enum: ClassDef, name: String): FieldReference {
    val code = enum.methods.single { it.name == "<clinit>" }.code()
    val load = code.indexOfFirst { it.string() == name }
    if (load < 0) refuse("${enum.type} doesn't name $name")
    val store = (load + 1 until code.size).firstOrNull { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.SPUT_OBJECT && field?.definingClass == enum.type && field.type == enum.type
    } ?: refuse("${enum.type} never stores its constant $name")
    if ((load + 1 until store).any { code[it].string() != null }) refuse("${enum.type} loads another string before storing $name")
    return (code[store] as ReferenceInstruction).reference as FieldReference
}

private fun Instruction.reads(field: FieldReference) =
    opcode == Opcode.SGET_OBJECT && (this as ReferenceInstruction).reference.toString() == field.toString()

/**
 * In [method], the move-result after each question about [constant], one of the composer's
 * capabilities: the constant loaded, then straight into a call answering a boolean whose last
 * parameter is the capability enum, then its answer kept.
 */
internal fun capabilityAsks(method: Method, constant: FieldReference): List<Int> {
    if (method.implementation?.instructions?.any { it.reads(constant) } != true) return emptyList()
    val code = method.code()
    return (0 until code.size - 2).filter { at ->
        if (!code[at].reads(constant)) return@filter false
        val ask = code[at + 1]
        val called = ask.called() ?: return@filter false
        called.returnType == "Z" && called.parameterTypes.lastOrNull()?.toString() == constant.type &&
            (code[at] as OneRegisterInstruction).registerA in ask.registers() && code[at + 2].opcode == Opcode.MOVE_RESULT
    }.map { it + 2 }
}

/**
 * Where Create story's tile builder keeps the list of tools it's handed: the move-result-object of
 * its first call answering an ImmutableList, in a method that answers an ImmutableList, reads
 * [imagine] after that list and returns through ImmutableList.copyOf. Null for any other method.
 */
internal fun storyToolList(method: Method, imagine: FieldReference): Int? {
    if (method.returnType != IMMUTABLE_LIST) return null
    val code = method.code()
    val read = code.indexOfFirst { it.reads(imagine) }
    if (read < 0 || code.none { it.called()?.toString() == IMMUTABLE_COPY }) return null
    val list = code.indexOfFirst { it.called()?.returnType == IMMUTABLE_LIST }
    if (list < 0 || list > read || code.getOrNull(list + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    return list + 1
}

/** After the move-result at [moveResult], the list of tools goes through the extension and back through copyOf. */
internal fun MutableMethod.filterStoryTools(moveResult: Int) {
    val register = getInstruction<OneRegisterInstruction>(moveResult).registerA
    addInstructions(
        moveResult + 1,
        """
            invoke-static/range { v$register .. v$register }, $STORY_TOOLS
            move-result-object v$register
            invoke-static/range { v$register .. v$register }, $IMMUTABLE_COPY
            move-result-object v$register
        """,
    )
}
