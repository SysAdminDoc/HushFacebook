/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.extension.requireThisIntact
import app.morphe.patches.facebook.search.history.callRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Where Facebook reports the stories you've viewed, on the 577 and 580 builds.
 *
 * The story viewer's seen helper (StoryViewerSeenHelper in its log lines) queues the id of each
 * story card you view. When you leave the viewer, it pauses, or the queue reaches a hundred, the
 * helper hands the queue to one sender. The sender builds one GraphQL mutation, DirectSeenMutation
 * (persisted query 157429340, root field direct_message_thread_update_seen_state, on both builds),
 * with the cards' ids in story_ids_list, and gives it to Facebook's GraphQL executor. That mutation
 * is what puts you on a story's viewer list. Replies and reactions go out as a mutation of their
 * own, from another class, and aren't touched.
 *
 * The helper counts a card as reported, in a set it keeps in memory, before it sends, and only the
 * send's failure callback takes the card back out. A send that never happens leaves the card
 * counted, so it isn't queued again until Facebook restarts. Facebook itself returns from the
 * sender without sending when the queue is empty.
 *
 * Redex renames the sender and its class every build (580's is LX/AqQ;->A00, 577's LX/A5o;->A00),
 * and the sender loads no string at all. Its class keeps the builder's name, getRequest, but a
 * name Facebook could drop isn't what the patch goes by. The sender is found through what it
 * builds: the one class whose constructor names the mutation and its root field, the one method
 * that creates that class and fills story_ids_list, and the one other method of that method's
 * class that calls it.
 *
 * 586 has no query class for it (read from both 586 builds on 2026-11-08). The builder (586
 * LX/0CRf;->getRequest) makes Facebook's generic query itself, handing its constructor the result
 * class and then DirectSeenMutation as the query's name, and the root field goes as null, so that
 * literal isn't in the APK at all. The persisted query is the same. The builder is then the one
 * method filling story_ids_list that makes a query named DirectSeenMutation, and the sender is
 * found from it the same way.
 *
 * Mark as seen (#88, read from 577, 580 and 581 on 2026-10-07). The sender has the same shape on
 * all three (581 LX/AR6;->A00): a callback, the FbUserSession, three strings, the map of bucket
 * filters, the set of card ids and the peek flag. The hook hands all of it to the extension and
 * sends the set it answers, which can be a new set of only the marked cards; a null still returns
 * before anything is built. The filters map may be null, since the builder skips
 * bucket_to_story_card_id_filters then.
 *
 * The seen helper (581 LX/AGC, 582 arm64 LX/AxE;) is the one class that calls the sender and names
 * "story_preview". 582's armeabi-v7a build (LX/B1D;) asks a string table for that literal
 * (LX/198;->A00 with 0x427) instead of loading it, so the helper is picked out of the sender's
 * callers by the literal or the table entry. Its
 * static void (FbUserSession, StoryBucket, StoryCard, helper, boolean) method (A00 on all three)
 * runs for each card about to be counted as viewed, and reads the card's id through
 * StoryCard.getId(), a kept method. The hook goes first in it with the session, the bucket and the
 * card, so the button knows the card on screen. The extension's two stubs are filled to call the
 * sender and StoryCard.getId().
 *
 * The eye must follow the card on screen, which the helper doesn't promise: its caller skips the
 * counted method for some bucket types and some cards, and a late callback can count a card the
 * viewer has left. Facebook's story controllers share a base class whose onCardActivated method
 * (log strings "Received onCardActivated when not attached" and "Card object cannot be null", the
 * same method on 577, 580 and 581: 577 LX/CII;->A0I, 580 LX/CGa;->A0J, 581 LX/CGv;->A0I) checks its
 * state and stores the activated StoryCard in a field of its own class. The hook goes right after
 * that store and hands the card to the button, as a range over the register the store read.
 */
internal const val PATCH = "View stories anonymously"

/** The mutation's name, which its query class's constructor loads. */
internal const val SEEN_MUTATION = "DirectSeenMutation"

/** The mutation's root field, loaded by the same constructor. */
internal const val SEEN_ROOT_FIELD = "direct_message_thread_update_seen_state"

/** The input field the request builder fills with the viewed cards' ids. */
internal const val STORY_IDS = "story_ids_list"

private const val SET = "Ljava/util/Set;"

internal const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
internal const val STORY_BUCKET = "Lcom/facebook/stories/model/StoryBucket;"
internal const val STORY_CARD = "Lcom/facebook/stories/model/StoryCard;"
internal const val CARD_ID = "$STORY_CARD->getId()Ljava/lang/String;"

/** A literal the seen helper's flush names; the helper is the one class naming it that calls the sender. */
internal const val SEEN_HELPER_LITERAL = "story_preview"

/** What the sender takes after its callback, the same on 577, 580 and 581. */
internal val SENDER_SHAPE = listOf(
    FB_USER_SESSION, "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/Map;", SET, "Z",
)

internal const val STORY_SEEN = "$EXTENSION_PACKAGE/stories/StorySeen;"
internal const val STORY_SEEN_BUTTON = "$EXTENSION_PACKAGE/stories/StorySeenButton;"
private const val SEND_ARGUMENTS =
    "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;" +
        "Ljava/util/Map;Ljava/util/Set;Z"
internal const val TO_SEND = "$STORY_SEEN->toSend($SEND_ARGUMENTS)Ljava/util/Set;"
internal const val SEND_STUB = "send"
internal const val ON_CARD = "$STORY_SEEN_BUTTON->onCard(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val ON_ACTIVE = "$STORY_SEEN_BUTTON->onActive(Ljava/lang/Object;)V"
internal const val CARD_ID_STUB = "cardId"

/** Literals the controllers' onCardActivated method loads: its state check and its card check. */
internal const val ACTIVATE_STATE = "Received onCardActivated when not attached"
internal const val ACTIVATE_CARD = "Card object cannot be null"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Whether [classDef] is the seen mutation's query class: a constructor of it loads the name and the root field. */
internal fun isSeenMutation(classDef: ClassDef): Boolean =
    classDef.methods.any { it.name == "<init>" && holdsString(it, SEEN_MUTATION) && holdsString(it, SEEN_ROOT_FIELD) }

private const val JAVA_CLASS = "Ljava/lang/Class;"

/**
 * Whether [method] makes the seen query itself, the way 586 does with no query class: it creates an
 * instance and hands its constructor a class and then [SEEN_MUTATION], loaded into a register
 * nothing writes before that call.
 */
internal fun makesSeenQuery(method: Method): Boolean {
    val code = method.implementation?.instructions?.toList() ?: return false
    for ((load, instruction) in code.withIndex()) {
        if (instruction.string() != SEEN_MUTATION) continue
        val name = (instruction as OneRegisterInstruction).registerA
        for (at in load + 1 until code.size) {
            val call = code[at].call(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
            if (call != null && call.name == "<init>") {
                val registers = callRegisters(code[at])
                val created = code.subList(0, at).lastOrNull {
                    it.opcode == Opcode.NEW_INSTANCE && (it as OneRegisterInstruction).registerA == registers.first()
                }
                if (registers.getOrNull(2) == name &&
                    call.parameterTypes.take(2).map(Any::toString) == listOf(JAVA_CLASS, STRING) &&
                    ((created as? ReferenceInstruction)?.reference as? TypeReference)?.type == call.definingClass
                ) return true
            }
            val written = code[at]
            if (written.opcode.setsRegister() && (written as? OneRegisterInstruction)?.registerA?.let {
                    it == name || (written.opcode.setsWideRegister() && it + 1 == name)
                } == true
            ) break
        }
    }
    return false
}

/**
 * Whether [method] builds the seen request: an instance method handing back an object that loads
 * [STORY_IDS] and creates an instance of [mutation], the query class [isSeenMutation] found, or,
 * with [mutation] null (586, no query class), makes the query itself ([makesSeenQuery]).
 */
internal fun isRequestBuilder(method: Method, mutation: String?): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || !method.returnType.startsWith("L")) return false
    if (!holdsString(method, STORY_IDS)) return false
    if (mutation == null) return makesSeenQuery(method)
    return method.implementation?.instructions?.any {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == mutation
    } == true
}

private fun Method.key(): String = name + parameterTypes.joinToString("", "(", ")") + returnType

/** How many times [method] calls [target], a method of the same class. */
private fun callsTo(method: Method, target: Method): Int =
    method.implementation?.instructions?.count { instruction ->
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        ref != null && ref.definingClass == target.definingClass && ref.name == target.name &&
            ref.parameterTypes.map { it.toString() } == target.parameterTypes.map { it.toString() } &&
            ref.returnType == target.returnType
    } ?: 0

/**
 * The method in [owner] that sends the seen request, or null when [owner] isn't the sender's class:
 * [owner] has exactly one request builder for [mutation] (null where the builder makes the query
 * itself), exactly one other method of [owner] calls it, and that caller is a void instance method
 * taking the set of card ids and calling the builder once.
 */
internal fun seenSender(owner: ClassDef, mutation: String?): Method? {
    val builder = owner.methods.filter { isRequestBuilder(it, mutation) }.singleOrNull() ?: return null
    val callers = owner.methods.filter { it.key() != builder.key() && callsTo(it, builder) > 0 }
    val sender = callers.singleOrNull() ?: return null
    if (sender.returnType != "V" || AccessFlags.STATIC.isSet(sender.accessFlags) ||
        sender.parameterTypes.none { it.toString() == SET } || callsTo(sender, builder) != 1
    ) return null
    return sender
}

/** Whether [sender] takes a callback and then [SENDER_SHAPE], the arguments the hook hands over. */
internal fun hasSendShape(sender: Method): Boolean {
    val parameters = sender.parameterTypes.map { it.toString() }
    return parameters.size == SENDER_SHAPE.size + 1 && parameters.first().startsWith("L") &&
        parameters.drop(1) == SENDER_SHAPE
}

/** Whether [method] calls [target], a method of another class or its own. */
private fun calls(method: Method, target: Method): Boolean =
    method.implementation?.instructions?.any { instruction ->
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        ref != null && ref.definingClass == target.definingClass && ref.name == target.name &&
            ref.parameterTypes.map { it.toString() } == target.parameterTypes.map { it.toString() } &&
            ref.returnType == target.returnType
    } == true

/** Whether a method of [owner] calls [sender]. */
internal fun callsSender(owner: ClassDef, sender: Method): Boolean = owner.methods.any { calls(it, sender) }

/**
 * The seen helper: the one class among [callers] with a method calling [sender] and a method naming
 * [SEEN_HELPER_LITERAL], as a literal of its own or as a string table entry [resolve] reads.
 */
internal fun seenHelper(callers: List<ClassDef>, sender: Method, resolve: (MethodReference) -> Method?): ClassDef {
    val helpers = callers.filter { caller ->
        callsSender(caller, sender) && caller.methods.any { namesString(it, SEEN_HELPER_LITERAL, resolve) }
    }.distinctBy { it.type }
    return helpers.singleOrNull()
        ?: refuse("expected one class naming \"$SEEN_HELPER_LITERAL\" that calls the sender, found ${helpers.size}")
}

/** The seen helper's per-card method: its one static void (FbUserSession, StoryBucket, StoryCard, helper, boolean). */
internal fun cardSeen(helper: ClassDef): Method {
    val shape = listOf(FB_USER_SESSION, STORY_BUCKET, STORY_CARD, helper.type, "Z")
    val methods = helper.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.implementation != null &&
            it.parameterTypes.map { type -> type.toString() } == shape
    }
    return methods.singleOrNull() ?: refuse("expected one static per-card method in ${helper.type}, found ${methods.size}")
}

/**
 * First thing in the sender: hand the extension everything the sender was handed, and send the
 * set of card ids it answers in the set's place, or return without sending on null. When the
 * answer is a set of its own (only the marked cards), the card filters go as null too: Facebook
 * builds them from every card the viewer saw and writes them into the same report, so they'd
 * carry the held cards' ids along. Every argument goes as a range, since the sender's registers
 * run past v15, and the moves that reach p6 and p7 take the 16-bit forms for the same reason.
 */
internal fun MutableMethod.filterViews() {
    if (!hasSendShape(this)) {
        refuse("the sender $definingClass->$name doesn't take a callback and ${SENDER_SHAPE.joinToString("")}")
    }
    requireLocals(PATCH, 2)
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p8 }, $TO_SEND
            move-result-object v0
            if-nez v0, :answered
            return-void
            :answered
            move-object/from16 v1, p7
            if-eq v0, v1, :own
            const/4 v1, 0x0
            move-object/from16 p6, v1
            :own
            move-object/from16 p7, v0
        """,
    )
}

/** First thing in the helper's per-card method: tell the button which card is on screen, and with which session. */
internal fun MutableMethod.reportCard() {
    addInstructions(
        0,
        """
            invoke-static/range { p0 .. p2 }, $ON_CARD
        """,
    )
}

/** Whether [method] is the controllers' onCardActivated: an instance void taking two objects, with both literals. */
internal fun isCardActivation(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.parameterTypes.size == 2 &&
        holdsString(method, ACTIVATE_STATE) && holdsString(method, ACTIVATE_CARD)

/** The index of the store of the activated card in [method], an iput-object of a StoryCard into the method's own class, or -1. */
internal fun activeCardStore(method: Method): Int {
    val stores = method.implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        val ref = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        instruction.opcode == Opcode.IPUT_OBJECT && ref != null && ref.type == STORY_CARD &&
            ref.definingClass == method.definingClass
    }.orEmpty().toList()
    return stores.singleOrNull()?.index ?: -1
}

/** Right after the activated card is stored: hand the button the card, which the store's register still holds. */
internal fun MutableMethod.reportActive(store: Int) {
    val register = (getInstruction(store) as OneRegisterInstruction).registerA
    addInstructions(
        store + 1,
        """
            invoke-static/range { v$register .. v$register }, $ON_ACTIVE
        """,
    )
}

/** The extension's send stub calls [sender], whose class and callback type come from the sender itself. */
internal fun MutableMethod.fillSend(sender: Method) {
    val callback = sender.parameterTypes.first().toString()
    val call = "${sender.definingClass}->${sender.name}(${sender.parameterTypes.joinToString("")})V"
    addInstructions(
        0,
        """
            check-cast p0, ${sender.definingClass}
            check-cast p1, $callback
            check-cast p2, $FB_USER_SESSION
            invoke-virtual/range { p0 .. p8 }, $call
            return-void
        """,
    )
}

/** The extension's card id stub asks the card for the id the seen helper queues. */
internal fun MutableMethod.fillCardId() {
    addInstructions(
        0,
        """
            check-cast p0, $STORY_CARD
            invoke-virtual/range { p0 .. p0 }, $CARD_ID
            move-result-object p0
            return-object p0
        """,
    )
}

/*
 * Marking a watched story's ring gray on the phone (#61).
 *
 * When the servers take a report, Facebook publishes the bucket's new is_bucket_seen_by_viewer:
 * the session's GraphQLConsistency, a DirectMessageThreadBucket tree built with the bucket's id and
 * that flag, and publishWithFullConsistency, which reaches every copy of the bucket the app holds,
 * the tray ring's included. On 582 one success callback does it (arm64 LX/cPQ;->onSuccess,
 * armeabi-v7a LX/Z73;->onSuccess), and the patch makes the same five calls for a bucket watched
 * with the report held back. Every name involved is kept or a string except those five, which are
 * read off Facebook's own code in the order it makes them.
 *
 * The seen helper's per-card method ([cardSeen]) takes the session, the bucket, the card, the helper
 * and the peek flag, and that's where the patch hears that a bucket's newest card was watched.
 * Facebook's story bucket, RegularStoryBucket, keeps its name, and so do StoryBucket and StoryCard
 * with getId(). The bucket's cards come from StoryBucket's one public method answering an
 * ImmutableList, its session from its one FbUserSession field, and whether it has a story not
 * watched yet from the one method RegularStoryBucket answers by reading is_bucket_seen_by_viewer,
 * turned around. Read from both 582 builds on 2026-10-14.
 *
 * A Highlights tile's bucket isn't a DirectMessageThreadBucket (585 builds it from an
 * XFBStoryHighlightDeliveryBucket), so the publish goes out a second time under the tree's own type,
 * TreeJNI's getTypeName() off the RegularStoryBucket field [readsUnwatched]'s override reads (#165).
 */
internal const val REGULAR_BUCKET = "Lcom/facebook/audience/snacks/model/RegularStoryBucket;"
internal const val CONSISTENCY = "Lcom/facebook/graphservice/interfaces/GraphQLConsistency;"
internal const val PUBLISH = "publishWithFullConsistency"
internal const val TREE = "Lcom/facebook/graphservice/interfaces/Tree;"
internal const val TREE_BUILDER = "Lcom/facebook/graphservice/tree/TreeBuilderJNI;"
internal const val TREE_JNI = "Lcom/facebook/graphservice/tree/TreeJNI;"
internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/** The tree type and field Facebook publishes for a bucket when its servers take a report. */
internal const val BUCKET_TYPE = "DirectMessageThreadBucket"
internal const val BUCKET_SEEN_FIELD = "is_bucket_seen_by_viewer"

internal const val VIEWED = "$STORY_SEEN->viewed($OBJECT$OBJECT${OBJECT}Z)V"
internal const val BUCKET_MADE = "$STORY_SEEN->bucketMade($OBJECT)V"

/** The calls Facebook makes to publish a bucket's seen flag, in its order. */
internal data class SeenPublisher(
    /** Static: FbUserSession to its GraphQLConsistency. */
    val consistency: MethodReference,
    /** Static: a tree type's name to a new builder of it. */
    val factory: MethodReference,
    /** Static: sets a builder's id. */
    val setId: MethodReference,
    /** The builder's: sets a boolean field by name. */
    val setFlag: MethodReference,
    /** The builder's: the tree it built. */
    val build: MethodReference,
)

private fun Instruction.string(): String? =
    if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) {
        ((this as ReferenceInstruction).reference as StringReference).string
    } else null

private fun Instruction.call(vararg opcodes: Opcode): MethodReference? =
    if (opcode in opcodes) (this as ReferenceInstruction).reference as? MethodReference else null

private fun MethodReference.takes(vararg types: String) = parameterTypes.map { it.toString() } == types.toList()

/**
 * The calls [method] makes to publish a bucket's seen flag, or null when it doesn't: after it gets
 * the consistency from a session, it loads [BUCKET_TYPE] and makes a builder of it from that name,
 * sets the builder's id, loads [BUCKET_SEEN_FIELD] and sets that flag on the builder, builds it,
 * and publishes what it built with full consistency. Either name can come from a string table
 * [resolve] finds instead (583's 32-bit build asks one for the flag's).
 */
internal fun seenPublisher(method: Method, resolve: (MethodReference) -> Method? = { null }): SeenPublisher? {
    val code = method.implementation?.instructions?.toList() ?: return null
    fun names(at: Int, literal: String) = code[at].string() == literal || tableStringAt(code, at, resolve) == literal
    val type = code.indices.firstOrNull { names(it, BUCKET_TYPE) } ?: -1
    if (type < 0) return null
    val consistency = code.subList(0, type).mapNotNull { it.call(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) }
        .lastOrNull { it.returnType == CONSISTENCY && it.takes(FB_USER_SESSION) } ?: return null

    fun first(from: Int, opcodes: Array<Opcode>, wanted: (MethodReference) -> Boolean): Pair<Int, MethodReference>? {
        for (index in from until code.size) {
            val call = code[index].call(*opcodes) ?: continue
            if (wanted(call)) return index to call
        }
        return null
    }
    val static = arrayOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
    val virtual = arrayOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)
    val (made, factory) = first(type + 1, static) {
        it.takes(STRING) && it.returnType.startsWith("L") && it.returnType != STRING
    } ?: return null
    val builder = factory.returnType
    val (named, setId) = first(made + 1, static) { it.takes(TREE_BUILDER, STRING) && it.returnType == "V" } ?: return null
    val field = (named + 1 until code.size).firstOrNull { names(it, BUCKET_SEEN_FIELD) } ?: return null
    val (flagged, setFlag) = first(field + 1, virtual) {
        it.definingClass == builder && it.takes(STRING, "Z") && it.returnType == "V"
    } ?: return null
    val (built, build) = first(flagged + 1, virtual) {
        it.definingClass == builder && it.parameterTypes.isEmpty() && it.returnType.startsWith("L")
    } ?: return null
    first(built + 1, arrayOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)) {
        it.definingClass == CONSISTENCY && it.name == PUBLISH && it.takes(TREE) && it.returnType == "V"
    } ?: return null
    return SeenPublisher(consistency, factory, setId, setFlag, build)
}

/** What the patch calls on Facebook's story bucket. */
internal class StoryBuckets(
    /** StoryBucket's cards, oldest first. */
    val cards: Method,
    /** StoryBucket's session field. */
    val session: Field,
    /** StoryBucket's method answering whether a story isn't watched yet, which RegularStoryBucket overrides. */
    val hasUnwatched: Method,
    /** RegularStoryBucket's override of [hasUnwatched], reading is_bucket_seen_by_viewer. */
    val override: Method,
    /** RegularStoryBucket's tree, the field [override] reads the flag from (#165). */
    val tree: FieldReference,
)

/** The instructions that load a 32-bit constant into a register. */
private val CONSTANTS = setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16)

/**
 * Whether [method] answers "not watched yet" from is_bucket_seen_by_viewer: no parameters, a
 * boolean answer, and it reads that field from a tree by its id, Java's hash of its name, and turns
 * the answer around.
 */
internal fun readsUnwatched(method: Method): Boolean {
    if (method.parameterTypes.isNotEmpty() || method.returnType != "Z") return false
    val code = method.implementation?.instructions?.toList() ?: return false
    val loads = code.any { it.opcode in CONSTANTS && (it as NarrowLiteralInstruction).narrowLiteral == BUCKET_SEEN_FIELD.hashCode() }
    val reads = code.any { call ->
        call.call(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)?.let {
            it.definingClass == TREE_JNI && it.name == "getBooleanValue" && it.takes("I")
        } == true
    }
    val flips = code.any { it.opcode == Opcode.XOR_INT_LIT8 && (it as NarrowLiteralInstruction).narrowLiteral == 1 }
    return loads && reads && flips
}

/** [StoryBuckets] off StoryBucket and RegularStoryBucket, or null when either isn't shaped as described above. */
internal fun storyBuckets(bucket: ClassDef, regular: ClassDef): StoryBuckets? {
    if (regular.superclass != bucket.type) return null
    val cards = bucket.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            it.parameterTypes.isEmpty() && it.returnType == IMMUTABLE_LIST
    }.singleOrNull() ?: return null
    val session = bucket.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == FB_USER_SESSION }
        .singleOrNull() ?: return null
    val override = regular.methods.filter(::readsUnwatched).singleOrNull() ?: return null
    val hasUnwatched = bucket.methods.singleOrNull {
        it.name == override.name && it.parameterTypes.isEmpty() && it.returnType == "Z" &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && !AccessFlags.FINAL.isSet(it.accessFlags)
    } ?: return null
    if (bucket.methods.none { it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == STRING }) return null
    val tree = override.implementation!!.instructions.filter { it.opcode == Opcode.IGET_OBJECT }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .singleOrNull { it.definingClass == regular.type } ?: return null
    return StoryBuckets(cards, session, hasUnwatched, override, tree)
}

/**
 * First thing in the seen helper's per-card method: the bucket, the card, the helper and the peek
 * flag go to the extension. Nothing has written a parameter yet, and the range form names the four
 * registers wherever they sit.
 */
internal fun MutableMethod.hearViewedCards() {
    val first = parameterRegisterNumber(1)
    if (parameterRegisterNumber(4) != first + 3) refuse("$definingClass->$name's parameters aren't side by side")
    addInstructions(0, "invoke-static/range { v$first .. v${first + 3} }, $VIEWED")
}

/**
 * Last thing in RegularStoryBucket's constructor, in front of its one return: the built bucket goes
 * to the extension. The constructor runs straight through, so every way out passes the hook, and
 * nothing in it writes over this.
 */
internal fun MutableMethod.hearBucketMade() {
    val code = implementation!!.instructions.toList()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    val at = returns.singleOrNull() ?: refuse("$definingClass's constructor returns from ${returns.size} places")
    if (code.any { it is OffsetInstruction }) refuse("$definingClass's constructor branches")
    requireThisIntact(PATCH, listOf(at))
    val self = localRegisterCount()
    addInstructions(at, "invoke-static/range { v$self .. v$self }, $BUCKET_MADE")
}

/*
 * The ring from the first frame (#165, read from 585 arm64 on 2026-10-31).
 *
 * A publish only reaches the copies of a bucket that exist when it's made, and a cold start draws
 * the tray first with LightweightLoadingBucket (built by LightNodeQueryBucketSource from the tray's
 * light query), which never runs RegularStoryBucket's constructor. Its answer comes from
 * StoryBucket's own method, which reads its tree's is_bucket_seen_by_viewer for it and answers yes
 * outright for the end card, error and server-generated buckets; RegularStoryBucket overrides it
 * and doesn't call it. A hook goes first in both, so the tray, the viewer and their logging all
 * hear the same answer.
 */
internal const val READS_WATCHED = "$STORY_SEEN->readsWatched(${OBJECT})Z"

/**
 * First thing in a bucket's answer to whether it has a story not watched yet: a bucket the
 * extension reads as watched answers no, and every other bucket goes on to Facebook's own answer.
 * This is read before anything writes it, and the one local the answer needs is free there.
 */
internal fun MutableMethod.answerWatchedFirst() {
    requireLocals(PATCH, 1)
    val self = localRegisterCount()
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { v$self .. v$self }, $READS_WATCHED
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/*
 * Where a bucket opens (#218, read from 584 arm64 on 2026-10-22).
 *
 * The story viewer (StoryViewerFragment's caller LX/BJt;->A05 among others) asks a static method
 * (584 LX/B6M;->A01) for the card a bucket starts at. It goes through an interface Facebook
 * implements for StoryBucket (584 LX/B6N;, which also has an (Object, int)I method that casts to
 * StoryBucket). Past a card the launch names and a few upload states, it takes the first card its
 * (Object)Z method answers yes for: a StoryCard with media (getMedia()) whose seen flag (an
 * abstract ()Z on StoryCard, 584 A1d) is false. Facebook sets that flag in memory as cards play,
 * and its servers set it from their view records, which a held-back view never makes, so after a
 * restart a bucket watched partway opened at card 1 again. The hook goes first in that method and
 * answers no for a card the extension kept as watched, so the viewer starts at the first one that
 * wasn't. The flag isn't touched, so nothing else Facebook reads from it changes.
 */
internal const val WATCHED_HERE = "$STORY_SEEN->watchedHere(${OBJECT})Z"

/**
 * Whether [method] is the start check: an instance (Object)Z method that casts its argument to
 * StoryCard once and makes exactly two virtual calls, getMedia() and then one ()Z flag, both on
 * StoryCard. Static calls are let by, since Kotlin puts a null check first in the methods beside it.
 */
internal fun isStartCheck(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z" ||
        method.parameterTypes.map(Any::toString) != listOf(OBJECT)
    ) return false
    val code = method.implementation?.instructions?.toList() ?: return false
    val casts = code.count { it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == STORY_CARD }
    val calls = startCalls(method)
    val direct = code.count { it.call(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE) != null }
    if (casts != 1 || calls.size != 2 || direct != 0) return false
    val (media, flag) = calls
    return media.definingClass == STORY_CARD && media.name == "getMedia" && media.parameterTypes.isEmpty() &&
        flag.definingClass == STORY_CARD && flag.parameterTypes.isEmpty() && flag.returnType == "Z"
}

/** The start check's virtual calls, in order: on a start check, getMedia() and then the seen flag. */
internal fun startCalls(check: Method): List<MethodReference> =
    check.implementation?.instructions?.mapNotNull {
        it.call(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
    }.orEmpty()

/** Whether [method] is the interface's (Object, int)I method that casts its first argument to StoryBucket. */
private fun isBucketIndex(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" &&
        method.parameterTypes.map(Any::toString) == listOf(OBJECT, "I") &&
        method.implementation?.instructions?.any {
            it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == STORY_BUCKET
        } == true

/** [owner]'s start check, when it's StoryBucket's card interface: one start check and one bucket index method. */
internal fun startCheck(owner: ClassDef): Method? {
    val check = owner.methods.filter(::isStartCheck).singleOrNull() ?: return null
    return if (owner.methods.count(::isBucketIndex) == 1) check else null
}

/**
 * First thing in the start check: a card the extension kept as watched answers no, and every
 * other card goes on to Facebook's own check. The argument is read before anything writes it, and
 * the one local the check has is free there.
 */
internal fun MutableMethod.skipWatchedCards() {
    requireLocals(PATCH, 1)
    val card = parameterRegisterNumber(0)
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { v$card .. v$card }, $WATCHED_HERE
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/*
 * The method asking the start check (#218 again, read from 585 arm64 on 2026-11-06).
 *
 * The check is asked by a static method (585 LX/0C5S;->A01) taking the card interface, the bucket,
 * the id of a card the launch names, and two ints. With no card named and the interface's bucket
 * answer no (StoryBucket's hasUnwatched, which the extension answers first, #165), it returns the
 * first card or the last without asking the check at all. A named card wins over the check's pick,
 * and a tray launch names the card Facebook's own reading of the bucket starts at. Either way a
 * bucket watched partway opened at card 1 after a restart, and the check's hook never ran. The hook
 * goes first in that method, for StoryBucket's interface only (the other implementer reads trees),
 * and hands the bucket and the named card's id to the extension, whose answer is the id named.
 */
internal const val START_CARD = "$STORY_SEEN->startCard(${OBJECT}${STRING})${STRING}"

/** Whether [method] is the start picker: a static ([cards], Object, String, int, int)I method asking [cards]'s [check]. */
internal fun isStartPicker(method: Method, cards: String, check: String): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "I" ||
        method.parameterTypes.map(Any::toString) != listOf(cards, OBJECT, STRING, "I", "I")
    ) return false
    return method.implementation?.instructions?.any {
        it.call(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)?.let { call ->
            call.definingClass == cards && call.name == check && call.returnType == "Z" &&
                call.parameterTypes.map(Any::toString) == listOf(OBJECT)
        } == true
    } == true
}

/**
 * First thing in the start picker: when its card interface is [owner], StoryBucket's, the card id
 * it goes on with is the extension's answer. Every other interface goes straight on. The
 * parameters are read before anything writes them, and the one local the hook needs is free there.
 */
internal fun MutableMethod.nameStartCard(owner: String) {
    requireLocals(PATCH, 1)
    val cards = parameterRegisterNumber(0)
    val bucket = parameterRegisterNumber(1)
    val cardId = parameterRegisterNumber(2)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, v$cards
            instance-of v0, v0, $owner
            if-eqz v0, :facebook
            invoke-static/range { v$bucket .. v$cardId }, $START_CARD
            move-result-object v$cardId
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/** Fills the extension's stubs for Facebook's own start check from [check]'s two calls on the card. */
internal fun fillStartStubs(stub: (name: String, answer: String, parameters: List<String>) -> MutableMethod, check: Method) {
    val (media, flag) = startCalls(check)
    stub("mediaOf", OBJECT, listOf(OBJECT)).addInstructions(
        0,
        """
            check-cast p0, $STORY_CARD
            invoke-virtual { p0 }, ${media.smali()}
            move-result-object p0
            return-object p0
        """,
    )
    stub("seenFlagOf", "Z", listOf(OBJECT)).addInstructions(
        0,
        """
            check-cast p0, $STORY_CARD
            invoke-virtual { p0 }, ${flag.smali()}
            move-result p0
            return p0
        """,
    )
}

internal fun MethodReference.smali(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/*
 * The viewer taking in a new copy of the bucket on screen (#218 again, read from 585 arm64 and
 * armeabi-v7a on 2026-11-06, the same shape on both).
 *
 * A Page opened from its picture first shows a LightweightLoadingBucket with one stand-in card for its
 * first story, and the start picker above is asked about that bucket only. When the real one arrives,
 * the story navigation delegate's onDataChanged (585 LX/0C6n;->A0E) checks it's the same bucket with a
 * static (StoryBucket, StoryBucket)Z taking the new copy first. For a loading bucket it then looks for
 * the card it was on in the new copy by id and keeps the index found, story 1's. A static
 * (StoryCard, StoryCard)Z compares the card at that index with the card it was on. When they differ and
 * the card is a posted story, the delegate's own (reason, I)V "navigateToCard" moves the viewer to the
 * index kept. The picker is never asked about the real bucket (traced on the S22 the same day). One hook
 * goes right after the first lookup, while the old bucket is still in its register, and hands the
 * extension that bucket and the card. The next goes right before the card check and swaps the card it
 * was on for the extension's answer, and the last, right before that navigateToCard, swaps the index
 * for the answer's.
 */
internal const val DATA_CHANGED = "StoryViewerNavigationDelegate.onDataChanged"
internal const val REPLACING = "$STORY_SEEN->replacing($OBJECT$OBJECT)V"
internal const val CARD_AFTER_LOADING = "$STORY_SEEN->cardAfterLoading($OBJECT$OBJECT)$OBJECT"
internal const val INDEX_AFTER_LOADING = "$STORY_SEEN->indexAfterLoading($OBJECT${OBJECT}I)I"

/**
 * The hooks' places in onDataChanged: [REPLACING] goes before the instruction at [replacing], handed
 * [replaced] and [oldCard], [CARD_AFTER_LOADING] before the card check at [check], handed [bucket]
 * and [card], which takes its answer, and [INDEX_AFTER_LOADING] before the navigateToCard call at
 * [navigate], handed [bucket], [card] and [index], which takes its answer.
 */
internal class LoadingSwap(
    val replacing: Int,
    val replaced: Int,
    val oldCard: Int,
    val check: Int,
    val bucket: Int,
    val card: Int,
    val navigate: Int,
    val index: Int,
)

/** Whether [method] loads [text] as a constant string. */
internal fun loads(method: Method, text: String): Boolean =
    method.implementation?.instructions?.any { it.string() == text } == true

/**
 * The hooks' places in [method], onDataChanged, or null when it isn't shaped as described above: one
 * same-bucket check, then lookups of a card by id in the bucket that check takes first, with one card
 * check between the first lookup and the rest, and the rest looking up the card that check takes
 * second. After the card check, one call of the method's own class on `this`, taking an int it reads
 * from a field right before, moves the viewer. The old bucket's register isn't written between the
 * bucket check and the first hook, no branch lands where a hook goes, and the registers go in a plain
 * invoke, so each is below 16.
 */
internal fun loadingSwap(method: Method): LoadingSwap? {
    val code = method.implementation?.instructions?.toList() ?: return null
    fun staticCall(i: Int, returns: String, vararg types: String) =
        code[i].call(Opcode.INVOKE_STATIC)?.let { it.returnType == returns && it.takes(*types) } == true
    val bucketCheck = code.indices.singleOrNull { staticCall(it, "Z", STORY_BUCKET, STORY_BUCKET) } ?: return null
    val cardCheck = code.indices.singleOrNull { staticCall(it, "Z", STORY_CARD, STORY_CARD) } ?: return null
    val lookups = code.indices.filter { it > bucketCheck && staticCall(it, "I", STORY_BUCKET, STORY_CARD) }
    val first = lookups.firstOrNull() ?: return null
    val later = lookups.filter { it > cardCheck }.map { code[it] as FiveRegisterInstruction }
    if (first > cardCheck || later.isEmpty()) return null
    val same = code[bucketCheck] as FiveRegisterInstruction
    val firstLookup = code[first] as FiveRegisterInstruction
    val compare = code[cardCheck] as FiveRegisterInstruction
    if (lookups.any { (code[it] as FiveRegisterInstruction).registerC != same.registerC }) return null
    if (later.any { it.registerD != compare.registerD }) return null
    val result = code.getOrNull(first + 1)
    if (result?.opcode != Opcode.MOVE_RESULT || (result as OneRegisterInstruction).registerA in setOf(same.registerD, firstLookup.registerD)) return null
    val written = code.subList(bucketCheck + 1, first).any {
        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == same.registerD
    }
    val targets = branchTargets(code)
    if (written || first + 2 in targets || cardCheck in targets) return null
    if (AccessFlags.STATIC.isSet(method.accessFlags)) return null
    val self = method.localRegisterCount()
    val navigate = code.indices.singleOrNull { i ->
        i > cardCheck && code[i].call(Opcode.INVOKE_VIRTUAL)?.let {
            it.definingClass == method.definingClass && it.returnType == "V" && it.parameterTypes.size == 2 && it.parameterTypes[1].toString() == "I"
        } == true && (code[i] as FiveRegisterInstruction).registerC == self
    } ?: return null
    val move = code[navigate] as FiveRegisterInstruction
    val index = code[navigate - 1]
    if (index.opcode != Opcode.IGET || (index as TwoRegisterInstruction).registerA != move.registerE || navigate in targets) return null
    if (move.registerE in setOf(same.registerC, compare.registerD)) return null
    val swap = LoadingSwap(first + 2, same.registerD, firstLookup.registerD, cardCheck, same.registerC, compare.registerD, navigate, move.registerE)
    return if (maxOf(swap.replaced, swap.oldCard, swap.bucket, swap.card, swap.index) < 16) swap else null
}

/**
 * The three hooks in onDataChanged, the last place first so the earlier places stand: the index the
 * viewer moves to and the card it was on become the extension's answers, the card cast back to the
 * card type the check takes.
 */
internal fun MutableMethod.openAfterLoading(swap: LoadingSwap) {
    addInstructions(
        swap.navigate,
        """
            invoke-static { v${swap.bucket}, v${swap.card}, v${swap.index} }, $INDEX_AFTER_LOADING
            move-result v${swap.index}
        """,
    )
    addInstructions(
        swap.check,
        """
            invoke-static { v${swap.bucket}, v${swap.card} }, $CARD_AFTER_LOADING
            move-result-object v${swap.card}
            check-cast v${swap.card}, $STORY_CARD
        """,
    )
    addInstructions(swap.replacing, "invoke-static { v${swap.replaced}, v${swap.oldCard} }, $REPLACING")
}

/*
 * The eye's timing (#150, read from both 582 builds on 2026-10-14).
 *
 * The seen helper's (StoryBucket, StoryCard) method runs as a card becomes active. After the
 * bucket and card checks that decide a card is counted at all, it counts the card at once when the
 * story's progress bar plays ("StoryViewerProgressBarPlayManager is null" is its log line), and
 * otherwise makes a listener (constructor: StoryBucket, StoryCard, the helper, the peek flag) that
 * counts it once the bar starts. A story still loading got the eye only then, about half a
 * second late. The hook goes first in that listener's constructor and hands the bucket, the card
 * and the helper to the button, whose stub reads the helper's one FbUserSession field. The
 * constructor runs only for a card that passed the checks.
 *
 * Facebook keeps each story card's state in an immutable object that its two Litho state updates
 * take ("updateState:StoryCardComponent.updateStoryCardState", sync and async), and the header
 * (progress bar, name, menu) is drawn from it. Its long press flag is the boolean Facebook's own
 * state dump reads just before it loads "LONG_PRESS_ACTIVE". Facebook sets it when the story is held
 * and clears it on release, and the header hides meanwhile. The hook goes first in both updates
 * and hands the state to the button, whose stub reads the flag.
 */
internal const val CARD_STATE_UPDATE = "updateState:StoryCardComponent.updateStoryCardState"
internal const val LONG_PRESS_ACTIVE = "LONG_PRESS_ACTIVE"
internal const val ON_STATE = "$STORY_SEEN_BUTTON->onState(Ljava/lang/Object;)V"
internal const val ON_CARD_LATER = "$STORY_SEEN_BUTTON->onCardLater(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val LONG_PRESSED_STUB = "longPressed"
internal const val HELPER_SESSION_STUB = "helperSession"

/** Whether [method] is one of the story card state's Litho updates: an instance void taking the state, loading [CARD_STATE_UPDATE]. */
internal fun isCardStateUpdate(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.parameterTypes.size == 1 &&
        method.parameterTypes.single().toString().startsWith("L") && holdsString(method, CARD_STATE_UPDATE)

/**
 * The long press flag of [state] in [method], Facebook's state dump: the boolean field of [state]
 * read right before the branch that skips loading [LONG_PRESS_ACTIVE], or null when [method] isn't
 * shaped that way.
 */
internal fun longPressFlag(method: Method, state: String): FieldReference? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val literal = code.indexOfFirst { it.string() == LONG_PRESS_ACTIVE }
    if (literal < 2 || code[literal - 1].opcode != Opcode.IF_EQZ) return null
    val read = code[literal - 2]
    if (read.opcode != Opcode.IGET_BOOLEAN) return null
    if ((read as OneRegisterInstruction).registerA != (code[literal - 1] as OneRegisterInstruction).registerA) return null
    val field = (read as ReferenceInstruction).reference as FieldReference
    return if (field.definingClass == state && field.type == "Z") field else null
}

/** First thing in a card state update: hand the button the new state. */
internal fun MutableMethod.reportState() {
    addInstructions(0, "invoke-static/range { p1 .. p1 }, $ON_STATE")
}

/** The listener the seen helper makes to count a card once it plays, and the helper's session field. */
internal class CountLater(val listener: ClassDef, val constructor: Method, val session: Field)

/**
 * In [helper]'s instance void (StoryBucket, StoryCard) method: the one class it creates with a
 * constructor taking (StoryBucket, StoryCard, [helper], boolean), which [classes] looks up, and
 * [helper]'s one FbUserSession field. Null when the helper isn't shaped that way.
 */
internal fun countLater(helper: ClassDef, classes: (String) -> ClassDef?): CountLater? {
    val session = helper.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == FB_USER_SESSION }
        .singleOrNull() ?: return null
    val shape = listOf(STORY_BUCKET, STORY_CARD, helper.type, "Z")
    val made = helper.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(Any::toString) == listOf(STORY_BUCKET, STORY_CARD)
    }.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
            val call = instruction.call(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
            if (call != null && call.name == "<init>" && call.takes(*shape.toTypedArray())) call.definingClass else null
        }
    }.toSet()
    val type = made.singleOrNull() ?: return null
    val listener = classes(type) ?: return null
    val constructor = listener.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(Any::toString) == shape && it.implementation != null
    } ?: return null
    return CountLater(listener, constructor, session)
}

/** First thing in the listener's constructor: hand the button the bucket, the card and the helper. */
internal fun MutableMethod.reportCardLater() {
    addInstructions(0, "invoke-static/range { p1 .. p3 }, $ON_CARD_LATER")
}

/** The button's stubs: the long press flag of a card state, and the seen helper's session. */
internal fun fillEyeStubs(
    stub: (name: String, answer: String) -> MutableMethod,
    flag: FieldReference,
    helper: String,
    session: Field,
) {
    stub(LONG_PRESSED_STUB, "Z").addInstructions(
        0,
        """
            check-cast p0, ${flag.definingClass}
            iget-boolean p0, p0, ${flag.definingClass}->${flag.name}:Z
            return p0
        """,
    )
    stub(HELPER_SESSION_STUB, OBJECT).addInstructions(
        0,
        """
            check-cast p0, $helper
            iget-object p0, p0, $helper->${session.name}:$FB_USER_SESSION
            return-object p0
        """,
    )
}

/**
 * Fills the extension's stubs with the calls on Facebook's story classes and the publishing calls.
 * [stub] finds each by name, answer and parameters. Each uses only its parameter registers, so a
 * stub's own register count doesn't matter as long as its parameters sit in the first sixteen,
 * which the non-range calls name.
 */
internal fun fillSeenStubs(
    stub: (name: String, answer: String, parameters: List<String>) -> MutableMethod,
    buckets: StoryBuckets,
    publisher: SeenPublisher,
) {
    stub("bucketIdOf", OBJECT, listOf(OBJECT)).addInstructions(
        0,
        """
            check-cast p0, $STORY_BUCKET
            invoke-virtual { p0 }, $STORY_BUCKET->getId()$STRING
            move-result-object p0
            return-object p0
        """,
    )
    stub("cardsOf", OBJECT, listOf(OBJECT)).addInstructions(
        0,
        """
            check-cast p0, $STORY_BUCKET
            invoke-virtual { p0 }, $STORY_BUCKET->${buckets.cards.name}()$IMMUTABLE_LIST
            move-result-object p0
            return-object p0
        """,
    )
    stub("storyIdOf", OBJECT, listOf(OBJECT)).addInstructions(
        0,
        """
            check-cast p0, $STORY_CARD
            invoke-virtual { p0 }, $CARD_ID
            move-result-object p0
            return-object p0
        """,
    )
    stub("hasUnwatchedOf", "Z", listOf(OBJECT)).addInstructions(
        0,
        """
            check-cast p0, $STORY_BUCKET
            invoke-virtual { p0 }, $STORY_BUCKET->${buckets.hasUnwatched.name}()Z
            move-result p0
            return p0
        """,
    )
    stub("sessionOf", OBJECT, listOf(OBJECT)).addInstructions(
        0,
        """
            check-cast p0, $STORY_BUCKET
            iget-object p0, p0, $STORY_BUCKET->${buckets.session.name}:$FB_USER_SESSION
            return-object p0
        """,
    )
    stub("bucketTypeOf", OBJECT, listOf(OBJECT)).addInstructions(
        0,
        """
            check-cast p0, ${buckets.tree.definingClass}
            iget-object p0, p0, ${buckets.tree.definingClass}->${buckets.tree.name}:${buckets.tree.type}
            check-cast p0, $TREE_JNI
            invoke-virtual { p0 }, $TREE_JNI->getTypeName()$STRING
            move-result-object p0
            return-object p0
        """,
    )
    stub("publishSeen", "V", listOf(OBJECT, STRING, STRING, STRING, "Z")).addInstructions(
        0,
        """
            check-cast p0, $FB_USER_SESSION
            invoke-static { p0 }, ${publisher.consistency.smali()}
            move-result-object p0
            invoke-static { p2 }, ${publisher.factory.smali()}
            move-result-object p2
            invoke-static { p2, p1 }, ${publisher.setId.smali()}
            invoke-virtual { p2, p3, p4 }, ${publisher.setFlag.smali()}
            invoke-virtual { p2 }, ${publisher.build.smali()}
            move-result-object p2
            invoke-interface { p0, p2 }, $CONSISTENCY->$PUBLISH($TREE)V
            return-void
        """,
    )
}
