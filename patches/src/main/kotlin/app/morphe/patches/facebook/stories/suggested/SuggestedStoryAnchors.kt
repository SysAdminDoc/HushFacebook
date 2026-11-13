/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.patches.facebook.feed.TREE_JNI
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.treeFieldKey
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * How a Stories tray gets its buckets, read from Facebook 577 and 580 (2026-09-27).
 *
 * One fetch controller feeds every Stories tray, the one at the top of the feed and the Friends
 * feed's. Each answer it gets, from the network or from the cache, goes through the one method
 * holding the trace name [POST_PROCESS_RESULT] (580 LX/1fA;->A03, 577 LX/1jf;->A03). That method
 * ranks and checks the buckets, one per person or Page with a story, and hands the list, as an
 * ImmutableList, to the one constructor of the tray data class it answers (580 LX/1yk;, 577
 * LX/1t4;). Nothing else builds that class. Both trays draw from it: the classic tray's receiver,
 * the one method holding [OPTIMISTIC_RECEIVES] (580 LX/1vu;->A05, 577 LX/2f1;->A05), copies the list
 * into the tray model it draws, and the unified tray's Stories tab hands the same object to that
 * receiver.
 *
 * Every bucket is a tree model behind one interface (580 LX/43P;, 577 LX/3Uy;). The tree class
 * implementing it answers one of the interface's ()Z methods (580 BZd, 577 BbH) with
 * TreeJNI.getBooleanValue of [SUGGESTED_FLAG_FIELD], and the receiver calls that method on the
 * buckets it counts. The card the tray draws for a bucket says "Suggested" (580 string 0x7f146c02,
 * 577 0x7f146a31) when that flag is true or when the bucket's first label is SUGGESTED, a constant
 * of the label enum that also names NEWFRIEND and TRENDING (580 LX/2yG;, 577 LX/2y8;). One static
 * helper answers that label for a bucket (580 LX/2Qy;->A00, 577 LX/2DS;->A00).
 *
 * Some buckets are cards, not stories (2026-09-30). The interface has one method answering the
 * bucket type enum (580 C9d()LX/2LX;, 577 CAm()LX/24X;), the one naming PYMK_STORY,
 * PYMK_PROFILE_FORWARD_STORY and CONTACT_IMPORTER_STORY among the tray's other types, and the tree
 * class answers it with getCachedEnum of [BUCKET_TYPE_FIELD]'s key. Two other enums name the same
 * three types, but no bucket method answers them.
 */

/** Kept trace name of the tray fetch's post-processing, the one builder of the tray data. */
internal const val POST_PROCESS_RESULT = "StoriesTrayLightFetchControllerQueryOps.postProcessResult"

/** Kept literal of the classic tray's receiver, which reads the tray data's bucket list. */
internal const val OPTIMISTIC_RECEIVES = "optimistic_data_adapter_receives"

/** The GraphQL field a suggested bucket carries as true. Its key is the name's String.hashCode(). */
internal const val SUGGESTED_FLAG_FIELD = "is_story_bucket_suggested"

/** Names the bucket label enum's static initializer holds. NEWFRIEND is in no other class. */
internal val LABEL_NAMES = listOf("NEWFRIEND", "SUGGESTED", "TRENDING")

/** The GraphQL field holding a bucket's type, which picks the card the tray draws for it. */
internal const val BUCKET_TYPE_FIELD = "story_bucket_type"

/** The card types the bucket type enum's static initializer names, among the tray's other types. */
internal val BUCKET_TYPE_NAMES = listOf("PYMK_PROFILE_FORWARD_STORY", "PYMK_STORY", "CONTACT_IMPORTER_STORY")

/*
 * Pages' stories and watched stories, read from 585 (2026-10-30, issues #313 and #317). The bucket
 * interface (585 LX/053g;) declares a ()Z method its tree class (585 LX/01jP;) answers with
 * TreeJNI.getBooleanValue of [SEEN_FIELD] (585 DtG), and a method answering the bucket's owner (585
 * Fli()LX/0eTt;), an interface declaring getTypeName(). The tree class answers that one through a
 * bridge to its own reader (585 A02()LX/028Y;), getCachedNullableTree of [OWNER_FIELD]'s key. Neither
 * is needed to hide suggested stories, so a build without them only leaves the two switches unable to
 * work.
 */

/** The GraphQL field a bucket the viewer has watched to the end carries as true. */
internal const val SEEN_FIELD = "is_bucket_seen_by_viewer"

/** The GraphQL field holding whoever posted a bucket's stories, a person or a Page. */
internal const val OWNER_FIELD = "story_bucket_owner"

/** The method a tree model's interface declares for its GraphQL type, "User" or "Page" for an owner. */
internal const val TYPE_NAME = "getTypeName"

/*
 * Stories watched with View stories anonymously on, read from 585 (2026-11-01, issue #218). The
 * servers never hear of those views, so [SEEN_FIELD] comes back false and Hide seen stories looks a
 * bucket up on the phone instead, by its id and when it expires. The bucket interface (585 LX/053g;,
 * v7a LX/053m;) declares getId(), which the tree class answers with getCachedNullableString of
 * [ID_FIELD]'s key, and a ()J method (585 D3I, v7a D3G) it answers with TreeJNI.getTimeValue of
 * [EXPIRATION_FIELD]'s key.
 */

/** The GraphQL fields a bucket is looked up by on the phone: its id, and when its newest story expires. */
internal const val ID_FIELD = "id"
internal const val EXPIRATION_FIELD = "expiration_time"

/** The id accessor a tree model's interface declares, a kept name. */
internal const val GET_ID = "getId"

internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * Whether this reference names [method]. The parameter lists are compared by their spelling: a
 * reference's immutable list and a method's don't compare equal even when they print the same.
 */
private fun MethodReference.refersTo(method: Method): Boolean =
    definingClass == method.definingClass && name == method.name && returnType == method.returnType &&
        parameterTypes.map { it.toString() } == method.parameters()

/** Whether [method] is the tray fetch's post-processing: the trace name, and an app object back. */
internal fun isPostProcess(method: Method): Boolean =
    method.returnType.startsWith("L") && !method.returnType.startsWith("Ljava/") &&
        holdsString(method, POST_PROCESS_RESULT)

/** The declared parameters of [method] that are an ImmutableList. */
internal fun listParameters(method: Method): List<Int> =
    method.parameters().indices.filter { method.parameters()[it] == IMMUTABLE_LIST }

/** Whether [method] builds an object through [constructor]: a new-instance of its class, then the call. */
internal fun constructs(method: Method, constructor: Method): Boolean {
    val body = method.body()
    return body.any {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == constructor.definingClass
    } && body.any {
        (it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE) && it.methodReference()?.refersTo(constructor) == true
    }
}

/**
 * The field of its own class that [constructor] keeps declared parameter [index] in, when it keeps
 * it in exactly one, typed ImmutableList.
 */
internal fun listField(constructor: Method, index: Int): FieldReference? {
    val register = constructor.parameterRegisterNumber(index)
    return constructor.body().filter { it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == register }
        .mapNotNull { it.fieldReference() }
        .filter { it.definingClass == constructor.definingClass && it.type == IMMUTABLE_LIST }
        .singleOrNull()
}

/** Whether [method] reads [field]. */
internal fun readsField(method: Method, field: FieldReference): Boolean = method.body().any {
    val read = it.fieldReference()
    it.opcode == Opcode.IGET_OBJECT && read != null && read.definingClass == field.definingClass &&
        read.name == field.name && read.type == field.type
}

/**
 * The interface methods of the app taking nothing and answering a boolean that [method] calls, each
 * once. A framework interface's, such as Iterator.hasNext, can't be a bucket's.
 */
internal fun interfaceFlagCalls(method: Method): List<MethodReference> = method.body()
    .filter { it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE }
    .mapNotNull { it.methodReference() }
    .filter { it.returnType == "Z" && it.parameterTypes.isEmpty() }
    .filterNot { it.definingClass.startsWith("Ljava/") || it.definingClass.startsWith("Landroid/") }
    .distinctBy { "${it.definingClass}->${it.name}" }

/**
 * Whether [instruction] reads a tree field: a virtual call named one of [names] (on [owner] when
 * given) taking [parameters] and answering [returnType] (any, when null), or the same shape asked
 * of an app interface. 587 keeps two kinds of tree class side by side: TreeJNI subclasses that ask
 * their reader by name, and delegating trees (super LX/02bA;) that ask the delegate they hold
 * through an interface whose methods are obfuscated (LX/0Ttg;->Ey4(I, Enum) for an enum, CZs(I)
 * for a boolean), so there only the shape tells the reads apart.
 */
private fun readsTreeField(
    instruction: Instruction,
    names: Set<String>,
    parameters: List<String>,
    returnType: String?,
    owner: String? = null,
): Boolean {
    val call = instruction.methodReference() ?: return false
    if (returnType != null && call.returnType != returnType || call.parameterTypes.map { it.toString() } != parameters) return false
    return when (instruction.opcode) {
        Opcode.INVOKE_VIRTUAL -> call.name in names && (owner == null || call.definingClass == owner)
        Opcode.INVOKE_INTERFACE -> !call.definingClass.startsWith("Ljava/") && !call.definingClass.startsWith("Landroid/")
        else -> false
    }
}

/**
 * Whether [method] answers the tree's boolean [field]: an instance method with no arguments and a
 * boolean back, loading the field's key and reading it with TreeJNI.getBooleanValue(int), or (587)
 * a delegate's (int) boolean read.
 */
internal fun isTreeFlagReader(method: Method, field: String): Boolean {
    if (method.isStatic() || method.parameterTypes.isNotEmpty() || method.returnType != "Z") return false
    val body = method.body()
    val key = treeFieldKey(field)
    return body.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == key } &&
        body.any { readsTreeField(it, setOf("getBooleanValue"), listOf("I"), "Z", owner = TREE_JNI) }
}

/**
 * Whether [method] answers the tree's enum [field]: an instance method with no arguments and an
 * object back, loading the field's key and reading it with getCachedEnum(int, Enum), or (587)
 * getCachedNullableEnum or a delegate's (int, Enum) enum read. The call names the tree class's own
 * superclass (580 LX/i17;), a TreeJNI subclass, so only its name and shape count.
 */
internal fun isTreeEnumReader(method: Method, field: String): Boolean {
    if (method.isStatic() || method.parameterTypes.isNotEmpty() || !method.returnType.startsWith("L")) return false
    val body = method.body()
    val key = treeFieldKey(field)
    return body.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == key } &&
        body.any { readsTreeField(it, setOf("getCachedEnum", "getCachedNullableEnum"), listOf("I", "Ljava/lang/Enum;"), "Ljava/lang/Enum;") }
}

/**
 * Whether [method] answers the tree's time [field]: an instance method with no arguments and a long
 * back, loading the field's key and reading it with getTimeValue(int), TreeJNI's on 585, or a
 * delegate's (int) long read. Only the call's name and shape count, as for an enum.
 */
internal fun isTreeTimeReader(method: Method, field: String): Boolean {
    if (method.isStatic() || method.parameterTypes.isNotEmpty() || method.returnType != "J") return false
    val body = method.body()
    val key = treeFieldKey(field)
    return body.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == key } &&
        body.any { readsTreeField(it, setOf("getTimeValue"), listOf("I"), "J") }
}

/**
 * Whether [method] answers the tree's string [field]: an instance method with no arguments and a
 * String back, loading the field's key and reading it with getCachedNullableString(int), which names
 * the tree class's own superclass, so only its name and shape count, or a delegate's (int) String read.
 */
internal fun isTreeStringReader(method: Method, field: String): Boolean {
    if (method.isStatic() || method.parameterTypes.isNotEmpty() || method.returnType != "Ljava/lang/String;") return false
    val body = method.body()
    val key = treeFieldKey(field)
    return body.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == key } &&
        body.any { readsTreeField(it, setOf("getCachedNullableString"), listOf("I"), "Ljava/lang/String;") }
}

/**
 * Whether [method] answers the tree's object [field]: an instance method with no arguments and an
 * object back, loading the field's key and reading it with getCachedNullableTree(int, Class, int), or
 * a delegate's read of the same shape. Like getCachedEnum, the call names the tree class's own
 * superclass, so only its name and shape count.
 */
internal fun isTreeNodeReader(method: Method, field: String): Boolean {
    if (method.isStatic() || method.parameterTypes.isNotEmpty() || !method.returnType.startsWith("L")) return false
    val body = method.body()
    val key = treeFieldKey(field)
    return body.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == key } &&
        body.any { readsTreeField(it, setOf("getCachedNullableTree"), listOf("I", "Ljava/lang/Class;", "I"), null) }
}

/**
 * The methods of [classDef] taking nothing that answer the tree's object [field]: its reader, and
 * each method that calls the reader, such as the interface's bridge (585 LX/01jP;->Fli calls A02).
 */
internal fun treeNodeAnswers(classDef: ClassDef, field: String): List<Method> {
    val readers = classDef.methods.filter { isTreeNodeReader(it, field) }
    if (readers.isEmpty()) return emptyList()
    return classDef.methods.filter { method ->
        !method.isStatic() && method.parameterTypes.isEmpty() &&
            (method in readers || method.body().any { instruction ->
                val call = instruction.methodReference()
                instruction.opcode == Opcode.INVOKE_VIRTUAL && call != null && readers.any { call.refersTo(it) }
            })
    }
}

/** Whether [classDef] is a public interface declaring [TYPE_NAME]()String, so the extension can ask it. */
internal fun namesItsType(classDef: ClassDef): Boolean =
    AccessFlags.INTERFACE.isSet(classDef.accessFlags) && AccessFlags.PUBLIC.isSet(classDef.accessFlags) &&
        classDef.methods.any { it.name == TYPE_NAME && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }

/** Whether [method] is a static helper that answers a [label] for one of [buckets]. */
internal fun isLabelHelper(method: Method, buckets: Set<String>, label: String): Boolean =
    method.isStatic() && method.implementation != null && method.returnType == label &&
        method.parameters().size == 1 && method.parameters().single() in buckets

/*
 * Home's tray on 585 (2026-10-30, issues #313 and #317). On Home the Stories tray comes inside the
 * feed as a feed unit, and so does a single story shown in the feed. Their builders (585
 * LX/0chr;->A01, the tray unit's parser, and LX/0C48;->A01, the inline story's component) hand the
 * unit's tree buckets to the one method holding the trace name [TRAY_PARSE] (585 LX/0C4B;->A00),
 * which turns them into the StoryBuckets the tray draws. That list never goes through the tray
 * data's constructor, so Home kept drawing the buckets the filter took out of the light fetch's copy.
 * Its elements are the same tree buckets, behind the bucket interface.
 */

/** Kept trace name of the parser that turns a tray's tree buckets into the buckets it draws. */
internal const val TRAY_PARSE = "StoriesTrayParser.parse"

/** Whether [method] is the tray parser: the trace name, one ImmutableList in and an ImmutableList back. */
internal fun isTrayParse(method: Method): Boolean =
    method.returnType == IMMUTABLE_LIST && listParameters(method).size == 1 && holdsString(method, TRAY_PARSE)

/** Whether [method] hands its declared parameter [index] to a call. */
internal fun passesOn(method: Method, index: Int): Boolean {
    val register = method.parameterRegisterNumber(index)
    return method.body().any { instruction ->
        instruction.methodReference() != null && when (instruction) {
            is FiveRegisterInstruction -> register in listOf(
                instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
            ).take(instruction.registerCount)
            is RegisterRangeInstruction -> register in instruction.startRegister until instruction.startRegister + instruction.registerCount
            else -> false
        }
    }
}

/*
 * The cards beside Create story (2026-10-04, issue #21). Facebook calls them the swipe right top
 * tray, SRTT: the "Share music you love" card (its tap is TAP_SRTT_CTA_OPEN_MUSIC_PICKER), a text
 * story, ready-made stories and the camera. They aren't buckets. The server sends them in a list of
 * their own, and the tray's two GraphQL query builders (581 LX/24x;->A03 and A07, 580 LX/1y6;->A03
 * and A07, 577 LX/1sa;->A03 and A08) each set the query variable [SKIP_PROMPT_CARDS] to leave it
 * out. Facebook sets it true for the Video tab's tray, and from a MobileConfig flag for the others:
 * a const-string of the name, then straight away the builder's (String, boolean) setter.
 */

/** The query variable that asks the server to leave the tray's creation cards out. */
internal const val SKIP_PROMPT_CARDS = "skip_srtt_item_list"

/**
 * Each place [method] sets [SKIP_PROMPT_CARDS]: the index of the setter call and the register of
 * the boolean it passes. Null when a const-string of the name isn't followed straight away by an
 * invoke-virtual of a (String, boolean) void method taking that string, so a build that moved the
 * value elsewhere refuses instead of setting something else.
 */
internal fun promptCardSkips(method: Method): List<Pair<Int, Int>>? {
    val body = method.body()
    val sites = mutableListOf<Pair<Int, Int>>()
    for ((index, instruction) in body.withIndex()) {
        if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) continue
        if (((instruction as ReferenceInstruction).reference as? StringReference)?.string != SKIP_PROMPT_CARDS) continue
        val call = body.getOrNull(index + 1) as? FiveRegisterInstruction ?: return null
        val target = call.methodReference() ?: return null
        val name = (instruction as OneRegisterInstruction).registerA
        val setter = call.opcode == Opcode.INVOKE_VIRTUAL && call.registerCount == 3 && call.registerD == name &&
            call.registerE != name && target.returnType == "V" &&
            target.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;", "Z")
        if (!setter) return null
        sites += (index + 1) to call.registerE
    }
    return sites
}
