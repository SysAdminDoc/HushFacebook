/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.autotranslation

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.util.MethodUtil

internal const val PATCH = "Turn off auto-translation"

internal const val AUTO_TRANSLATION_EXTENSION = "Lapp/morphe/extension/facebook/feed/AutoTranslation;"
internal const val TRANSLATION_TYPE_HOOK =
    "$AUTO_TRANSLATION_EXTENSION->translationType(Ljava/lang/Enum;)Ljava/lang/Enum;"
internal const val CAPTION_HOOK = "$AUTO_TRANSLATION_EXTENSION->captionAutoTranslates(Z)Z"
internal const val COMMENT_HOOK =
    "$AUTO_TRANSLATION_EXTENSION->commentTranslation(Ljava/lang/Enum;)Ljava/lang/Enum;"

/** Two constants of Facebook's GraphQLTranslatabilityType, kept as the enum's names. */
internal const val AUTO_TRANSLATION = "AUTO_TRANSLATION"
internal const val SEE_TRANSLATION = "SEE_TRANSLATION"

/** The tree key of the translatability model's `translation_type` field: "translation_type".hashCode(). */
internal const val TRANSLATION_TYPE_KEY = -1684513784L

internal const val BASE_MODEL = "Lcom/facebook/graphql/modelutil/BaseModelWithTree;"
internal const val TREE_JNI = "Lcom/facebook/graphservice/tree/TreeJNI;"
internal const val GET_BOOLEAN_VALUE = "$TREE_JNI->getBooleanValue(I)Z"

/** The state update the reel footer makes as it asks Facebook to translate the reel's caption. */
internal const val CAPTION_TRIGGER = "updateState:FbShortsViewerFooterComponent.updateAutoTranslateTriggered"

/**
 * The tree key of the reel's boolean field the footer reads before it asks for a caption
 * translation, on its render and on the step that makes the request. The field's name isn't in the
 * APK; the key is the same on 577, 580 and 581.
 */
internal const val CAPTION_TRANSLATABLE_KEY = 143667788L

/** Three constants of the comment's translation type enum, kept as the enum's names. */
internal const val AUTO_TRANSLATE = "AUTO_TRANSLATE"
internal const val MACHINE_TRANSLATION = "MACHINE_TRANSLATION"
internal const val ORIGINAL = "ORIGINAL"

/** The state update the comment body makes as See translation or See original is tapped. */
internal const val COMMENT_TOGGLE = "updateState:CommentBodyComponent.updateIsTranslated"

/**
 * Posts, comments and reel captions stay in the language they were written in, with Facebook's own
 * "See translation" still there.
 *
 * Posts: Facebook marks each post's translatability with a GraphQLTranslatabilityType, and the feed
 * puts the auto-translated text plugin (FeedStoryAutoTranslatePlugin) on posts it marks
 * AUTO_TRANSLATION and the "See translation" plugin (FeedStorySeeTranslationPlugin) on posts it
 * marks SEE_TRANSLATION. Every one of those choices reads the type through one getter on the
 * translatability model (581 `LX/40F;->A0V`): a no-argument method returning the enum, reading
 * [TRANSLATION_TYPE_KEY] with BaseModelWithTree.getCachedEnum. The extension sees what it returns
 * and answers SEE_TRANSLATION for AUTO_TRANSLATION while the switch is on, so the post shows its
 * own text with the link under it.
 *
 * Reels: a reel's caption isn't translated by the server. The reels viewer's footer
 * (FbShortsViewerFooterComponent) asks for a translation when the reel's field under
 * [CAPTION_TRANSLATABLE_KEY] is true, once from its render and once from the step that records
 * [CAPTION_TRIGGER] (581 `LX/Au4;` A1A and A1F, 580 `LX/Av9;` A1F and A1K, 577 `LX/AxP;` A1N and
 * A1K). Each read goes through the extension, which answers false while the switch is on, so no
 * request is made and the caption keeps its own words.
 *
 * Comments (#372): the comment body (CommentBodyComponent, 585 arm64 `LX/0AA5;`) reads the comment's
 * own translation type, an enum of AUTO_TRANSLATE, MACHINE_TRANSLATION and ORIGINAL, in two places.
 * Its starting state (A1F) shows the translated text Facebook sent along when the type isn't
 * ORIGINAL, and its render (A17) picks See original or See translation by it. Both answers go
 * through the extension, which says ORIGINAL while the switch is on, so the comment starts in its
 * own words and See translation fetches the translation when tapped, as on comments Facebook never
 * translated.
 */
@Suppress("unused")
val turnOffAutoTranslationPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Turn off auto-translation",
    description = "Shows posts, comments and reel captions in the language they were written in, instead of " +
        "Facebook's automatic translation, so you read people's own words. See translation still works. Starts " +
        "off. Turn it on in Hushfacebook settings > News feed.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("autoTranslation")
        val getter = findTranslationTypeGetter()
        val reads = findCaptionReads()
        val comments = findCommentTypeReads()
        hookTranslationType(getter)
        reads.forEach { hookCaptionRead(it) }
        comments.forEach { hookCommentTypeRead(it) }
        enableStatus("autoTranslation")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun stringOf(instruction: Instruction): String? =
    ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun literalOf(instruction: Instruction): Long? = (instruction as? WideLiteralInstruction)?.wideLiteral

/** The translatability enum's getter, with the enum type it returns. */
internal class TranslationTypeGetter(val method: Method, val enumType: String, val returnIndex: Int, val register: Int)

/** One read of the reel's caption field: its method and where the answer lands. */
internal class CaptionRead(val method: Method, val resultIndex: Int, val register: Int)

/** One read of a comment's translation type: its method, where the answer lands, and the enum. */
internal class CommentTypeRead(val method: Method, val resultIndex: Int, val register: Int, val enumType: String)

/** Whether [classDef] is GraphQLTranslatabilityType: an enum whose static setup names both constants. */
internal fun isTranslatabilityEnum(classDef: ClassDef): Boolean =
    classDef.superclass == "Ljava/lang/Enum;" && classDef.methods.any {
        it.name == "<clinit>" && holdsString(it, AUTO_TRANSLATION) && holdsString(it, SEE_TRANSLATION)
    }

/** Whether [method] is the model getter of [enumType] that reads [TRANSLATION_TYPE_KEY]. */
internal fun isTranslationTypeGetter(method: Method, enumType: String): Boolean {
    if (method.returnType != enumType || method.parameterTypes.isNotEmpty()) return false
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.any { literalOf(it) == TRANSLATION_TYPE_KEY } && code.any {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        call?.name == "getCachedEnum" && call.definingClass == BASE_MODEL
    }
}

/**
 * The one getter of the translatability type, found by its enum and the key it reads. Refuses
 * unless there's one enum, one getter on a BaseModelWithTree model, and one return in it, which
 * hands back a register. Changes nothing.
 */
internal fun BytecodePatchContext.findTranslationTypeGetter(): TranslationTypeGetter {
    val enums = classDefByStrings(AUTO_TRANSLATION, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter(::isTranslatabilityEnum)
        .map { it.type }
        .distinct()
    val enumType = enums.singleOrNull()
        ?: refuse("expected one enum naming $AUTO_TRANSLATION and $SEE_TRANSLATION, found ${enums.size}")
    val getters = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES) || classDef.superclass != BASE_MODEL) return@classDefForEach
        classDef.methods.filterTo(getters) { isTranslationTypeGetter(it, enumType) }
    }
    val getter = getters.singleOrNull()
        ?: refuse("expected one model getter of $enumType reading translation_type, found ${getters.size}")
    val code = getter.implementation!!.instructions.toList()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    val returnIndex = returns.singleOrNull()
        ?: refuse("${getter.definingClass}->${getter.name} returns in ${returns.size} places, expected one")
    return TranslationTypeGetter(getter, enumType, returnIndex, (code[returnIndex] as OneRegisterInstruction).registerA)
}

/**
 * The reads of the reel's caption field in the reels footer: the class with the method that loads
 * [CAPTION_TRIGGER], and in it each `getBooleanValue` whose key is a constant [CAPTION_TRANSLATABLE_KEY]
 * loaded just before, followed by the move-result that takes its answer. When the class's own
 * methods don't hold both reads, the [outlinedBodies] they hand their work to count as theirs: 585's
 * Android 9 build reads it in `LX/0B94;->A1A`, the trigger, and in `LX/0DSJ;->A00`, a static method
 * the render `LX/0B94;->A15(LX/01QC;)LX/01Jx;` hands everything to. Refuses unless the method with
 * the trigger reads it once and the class reads it twice. Changes nothing.
 */
internal fun BytecodePatchContext.findCaptionReads(): List<CaptionRead> {
    val footers = classDefByStrings(CAPTION_TRIGGER, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter { footer -> footer.methods.any { holdsString(it, CAPTION_TRIGGER) } }
        .distinctBy { it.type }
    val footer = footers.singleOrNull()
        ?: refuse("expected one class loading \"$CAPTION_TRIGGER\", found ${footers.size}")
    var reads = footer.methods.flatMap(::captionReads)
    val triggers = footer.methods.filter { holdsString(it, CAPTION_TRIGGER) }
    var inTrigger = reads.count { it.method in triggers }
    if (inTrigger != 1 || reads.size != 2) {
        val outlined = footer.methods.flatMap { caller ->
            outlinedBodies(caller).flatMap { body -> captionReads(body).map { caller to it } }
        }
        reads = reads + outlined.map { (_, read) -> read }
        inTrigger += outlined.count { (caller, _) -> caller in triggers }
    }
    if (inTrigger != 1 || reads.size != 2) {
        refuse("${footer.type} reads the caption field $inTrigger times where it asks for a translation and " +
            "${reads.size} times in all, expected 1 and 2")
    }
    return reads
}

/**
 * The static methods of other classes [caller] hands its work to: each one [caller] calls that
 * returns what [caller] returns, never void, and takes [caller]'s first parameter first.
 */
internal fun BytecodePatchContext.outlinedBodies(caller: Method): List<Method> {
    val first = caller.parameterTypes.firstOrNull()?.toString() ?: return emptyList()
    if (caller.returnType == "V") return emptyList()
    val code = caller.implementation?.instructions?.toList() ?: return emptyList()
    return code.filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter {
            it.definingClass != caller.definingClass && it.returnType == caller.returnType &&
                it.parameterTypes.firstOrNull()?.toString() == first
        }
        .distinctBy { it.toString() }
        .mapNotNull { call -> classDefByOrNull(call.definingClass)?.methods?.firstOrNull { MethodUtil.methodSignaturesMatch(it, call) } }
}

/** The reads of [CAPTION_TRANSLATABLE_KEY] in [method]: const, getBooleanValue with that key, move-result. */
internal fun captionReads(method: Method): List<CaptionRead> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { index ->
        val key = code[index]
        if (literalOf(key) != CAPTION_TRANSLATABLE_KEY || !key.opcode.name.startsWith("const")) return@mapNotNull null
        val call = code.getOrNull(index + 1) as? FiveRegisterInstruction ?: return@mapNotNull null
        if (call.opcode != Opcode.INVOKE_VIRTUAL || (call as ReferenceInstruction).reference.toString() != GET_BOOLEAN_VALUE) {
            return@mapNotNull null
        }
        if (call.registerCount != 2 || call.registerD != (key as OneRegisterInstruction).registerA) return@mapNotNull null
        val result = code.getOrNull(index + 2)
        if (result?.opcode != Opcode.MOVE_RESULT) return@mapNotNull null
        CaptionRead(method, index + 2, (result as OneRegisterInstruction).registerA)
    }
}

/** The calls a model read can be: an interface call or a virtual one, in either register form. */
private val READ_CALLS = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/** Whether [classDef] is the comment's translation type: an enum whose static setup names its three constants. */
internal fun isCommentTranslationEnum(classDef: ClassDef): Boolean =
    classDef.superclass == "Ljava/lang/Enum;" && classDef.methods.any {
        it.name == "<clinit>" && holdsString(it, AUTO_TRANSLATE) && holdsString(it, MACHINE_TRANSLATION) &&
            holdsString(it, ORIGINAL)
    }

/** The reads of [enumType] in [method]: a no-argument call returning it, then the move-result that takes it. */
internal fun commentTypeReads(method: Method, enumType: String): List<CommentTypeRead> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { index ->
        if (code[index].opcode !in READ_CALLS) return@mapNotNull null
        val call = (code[index] as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        if (call.returnType != enumType || call.parameterTypes.isNotEmpty()) return@mapNotNull null
        val result = code.getOrNull(index + 1)
        if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
        CommentTypeRead(method, index + 1, (result as OneRegisterInstruction).registerA, enumType)
    }
}

/**
 * The comment body's reads of the comment's translation type: the one enum naming [AUTO_TRANSLATE],
 * [MACHINE_TRANSLATION] and [ORIGINAL], and the class that records [COMMENT_TOGGLE] and reads that
 * enum. Refuses unless there's one such enum and one such class, which reads it once in each of two
 * methods. Changes nothing.
 */
internal fun BytecodePatchContext.findCommentTypeReads(): List<CommentTypeRead> {
    val enums = classDefByStrings(MACHINE_TRANSLATION, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter(::isCommentTranslationEnum)
        .map { it.type }
        .distinct()
    val enumType = enums.singleOrNull()
        ?: refuse("expected one enum naming $AUTO_TRANSLATE, $MACHINE_TRANSLATION and $ORIGINAL, found ${enums.size}")
    val bodies = classDefByStrings(COMMENT_TOGGLE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .map { body -> body to body.methods.flatMap { commentTypeReads(it, enumType) } }
        .filter { (_, reads) -> reads.isNotEmpty() }
    val (body, reads) = bodies.singleOrNull()
        ?: refuse("expected one class loading \"$COMMENT_TOGGLE\" that reads $enumType, found ${bodies.size}")
    if (reads.size != 2 || reads.map { it.method }.distinct().size != 2) {
        refuse("${body.type} reads $enumType ${reads.size} times in ${reads.map { it.method }.distinct().size} " +
            "methods, expected once in each of two")
    }
    return reads
}

/**
 * The type the getter is about to return goes through the extension, and the answer, cast back to
 * the enum, is what it returns. A range call takes any register.
 */
internal fun BytecodePatchContext.hookTranslationType(getter: TranslationTypeGetter) {
    val method = mutableClassDefBy(getter.method.definingClass).findMutableMethodOf(getter.method)
    val register = getter.register
    method.addInstructions(
        getter.returnIndex,
        """
            invoke-static/range { v$register .. v$register }, $TRANSLATION_TYPE_HOOK
            move-result-object v$register
            check-cast v$register, ${getter.enumType}
        """,
    )
}

/** The footer's answer about the caption field goes through the extension, right after it lands. */
internal fun BytecodePatchContext.hookCaptionRead(read: CaptionRead) {
    val method = mutableClassDefBy(read.method.definingClass).findMutableMethodOf(read.method)
    method.addInstructions(
        read.resultIndex + 1,
        """
            invoke-static/range { v${read.register} .. v${read.register} }, $CAPTION_HOOK
            move-result v${read.register}
        """,
    )
}

/** The comment body's answer about a comment's translation type goes through the extension, cast back to the enum. */
internal fun BytecodePatchContext.hookCommentTypeRead(read: CommentTypeRead) {
    val method = mutableClassDefBy(read.method.definingClass).findMutableMethodOf(read.method)
    method.addInstructions(
        read.resultIndex + 1,
        """
            invoke-static/range { v${read.register} .. v${read.register} }, $COMMENT_HOOK
            move-result-object v${read.register}
            check-cast v${read.register}, ${read.enumType}
        """,
    )
}
