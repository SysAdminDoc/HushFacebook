/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.media.taptoplay.isEnumNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PATCH = "Hide suggested stories"

/** The extension class that filters the tray's buckets, its injection point and the stubs this patch fills. */
internal const val SUGGESTED_STORIES = "$EXTENSION_PACKAGE/stories/SuggestedStories;"
internal const val KEPT_BUCKETS = "$SUGGESTED_STORIES->keptBuckets(Ljava/util/List;)Ljava/lang/Object;"
internal const val IS_BUCKET_STUB = "isBucket"
internal const val SUGGESTED_STUB = "suggested"
internal const val LABEL_STUB = "label"
internal const val TYPE_STUB = "bucketType"
internal const val COPY_STUB = "immutableCopy"
internal const val SEEN_STUB = "seen"
internal const val OWNER_TYPE_STUB = "ownerType"
internal const val BUCKET_ID_STUB = "bucketId"
internal const val EXPIRES_STUB = "expires"

/** The extension method each tray fetch asks before it sets [SKIP_PROMPT_CARDS]. */
internal const val SKIP_PROMPT_CARDS_HOOK = "$SUGGESTED_STORIES->skipPromptCards(Z)Z"

/** What the patch found. See SuggestedStoryAnchors.kt for what each is on 577 and 580. */
internal class TrayBuckets(
    /** The tray data class's one constructor, and which of its parameters is the bucket list. */
    val constructor: Method,
    val list: Int,
    /** The bucket interface, and its method answering the suggested flag. */
    val bucket: String,
    val flag: String,
    /** The interface's method answering the bucket's type, and the type enum it answers. */
    val type: String,
    val typeEnum: String,
    /** Facebook's static helper answering a bucket's first label, and the label enum. */
    val labelHelper: Method,
    val label: String,
    /** The interface's method answering is_bucket_seen_by_viewer, or null when this build has none. */
    val seen: String? = null,
    /** The interface's method answering the bucket's owner and the owner interface it answers, or null. */
    val owner: String? = null,
    val ownerType: String? = null,
    /** Whether the interface's getId() answers the bucket's id, and its method answering expiration_time, or null. */
    val id: Boolean = false,
    val expires: String? = null,
)

/**
 * Takes the Stories Facebook suggests out of the Stories tray. The hook goes first in the one
 * constructor of the tray data, which every answer of the tray's fetch goes through, and hands the
 * bucket list to the extension. What comes back is the same list, or an ImmutableList without the
 * buckets Facebook marks as suggested, the way the tray's own card decides to say "Suggested":
 * the bucket's is_story_bucket_suggested flag, or SUGGESTED as its first label. The tray's cards go
 * by their bucket type, each behind its own switch: the People you may know cards and the Find
 * friends from contacts card. Everything else in the list stays.
 *
 * Home's tray comes inside the feed and never goes through that constructor, so the same filter
 * also goes first in the parser that turns its buckets, and a story shown in the feed, into what the
 * tray draws (issues #313 and #317). Home's tray loads its next page when one of its last buckets
 * comes into view, so when the filter leaves its row a bucket or two and then gray placeholders, the
 * extension lets a placeholder coming into view load it (TrayPaging.kt).
 *
 * The cards beside Create story, such as "Share music you love", come in a list of their own that
 * the tray's fetch can ask the server to leave out. Each fetch's answer to that goes through the
 * extension first, which asks for it when Hide story prompts is on (issue #21).
 *
 * The Create story tile isn't a bucket. It goes into the tray's tile list with the story tiles, and
 * Hide Create story takes it out there (CreateStoryTiles.kt, issue #445).
 */
@Suppress("unused")
val hideSuggestedStoriesPatch = bytecodePatch(
    name = "Hide suggested stories",
    description = "Removes stories Facebook suggests from people and Pages you don't follow, and the \"Find " +
        "friends from contacts\" card, so the Stories tray sticks to people you know. On by default. Turn it off " +
        "in Hushfacebook settings > Stories, where more switches, all off at first, also hide the Create story " +
        "tile, the Pages you follow and the stories you've already watched, or keep only the ones you've watched.",
    default = true,
) {
    category("Stories")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val tray = trayBuckets()
        fillBucketStubs(tray)
        mutableClassDefBy(tray.constructor.definingClass).methods
            .single { it.name == "<init>" }
            .keepOnlyUnsuggestedBuckets(tray.list)
        val (parser, list) = trayParser()
        val parameters = parser.parameterTypes.map { it.toString() }
        mutableClassDefBy(parser.definingClass).methods
            .single { it.name == parser.name && it.returnType == parser.returnType && it.parameterTypes.map { p -> p.toString() } == parameters }
            .keepOnlyUnsuggestedBuckets(list)
        pageShortTrays(trayPagingAnchors())
        hookPromptCardSkips()
        hideCreateStoryTile()
        enableStatus("suggestedStories")
    }
}

/**
 * The tray data's constructor, its bucket list, the bucket interface with its flag and Facebook's
 * label helper, held to the evidence the filter rests on: one post-processing method builds the
 * tray data through its one constructor, which keeps its one ImmutableList in a field; the classic
 * tray's receiver takes the tray data and reads that field; of the flags it asks each bucket, one
 * is answered by the interface's tree class with is_story_bucket_suggested; one enum names the
 * labels and one static helper answers it for that interface; one method of the interface answers
 * the bucket type enum naming the tray's cards, and the tree class answers it with story_bucket_type.
 * Each has to be public for the extension's stubs to reach it.
 */
internal fun BytecodePatchContext.trayBuckets(): TrayBuckets {
    val posts = classDefByStrings(POST_PROCESS_RESULT, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter(::isPostProcess) }
    val post = posts.singleOrPatchException("$PATCH: the tray fetch's post-processing holding \"$POST_PROCESS_RESULT\"")

    val data = classDefBy(post.returnType)
    val constructor = data.methods.filter { it.name == "<init>" }
        .singleOrPatchException("$PATCH: the one constructor of the tray data ${data.type}")
    val list = listParameters(constructor)
        .singleOrPatchException("$PATCH: the one ImmutableList ${data.type}'s constructor takes")
    if (!constructs(post, constructor)) {
        throw PatchException("$PATCH: ${post.definingClass}->${post.name} doesn't build its ${data.type} through its constructor")
    }
    val field = listField(constructor, list)
        ?: throw PatchException("$PATCH: ${data.type}'s constructor doesn't keep its bucket list in one field of its own")

    val receivers = classDefByStrings(OPTIMISTIC_RECEIVES, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef ->
            classDef.methods.filter { method ->
                data.type in method.parameterTypes.map { it.toString() } && holdsString(method, OPTIMISTIC_RECEIVES)
            }
        }
    val receiver = receivers.singleOrPatchException("$PATCH: the tray receiver holding \"$OPTIMISTIC_RECEIVES\" and taking ${data.type}")
    if (!readsField(receiver, field)) {
        throw PatchException("$PATCH: ${receiver.definingClass}->${receiver.name} doesn't read ${data.type}->${field.name}, the bucket list")
    }
    val calls = interfaceFlagCalls(receiver)
    val interfaces = calls.map { it.definingClass }.toSet()

    val labels = classDefByStrings(LABEL_NAMES.first(), StringComparisonType.EQUALS)
        .filter { isEnumNaming(it, LABEL_NAMES) }
    val label = labels.singleOrPatchException("$PATCH: the bucket label enum naming ${LABEL_NAMES.joinToString()}")

    val flagged = mutableSetOf<String>()
    val typed = mutableSetOf<String>()
    val watched = mutableSetOf<String>()
    val owned = mutableSetOf<String>()
    val identified = mutableSetOf<String>()
    val expiring = mutableSetOf<String>()
    val helpers = mutableListOf<Method>()
    // A tree class implements a bucket interface, and a label helper answers the label enum.
    classDefForEachReferencing(types = interfaces + label.type) { classDef ->
        for (call in calls) {
            if (call.definingClass in classDef.interfaces &&
                classDef.methods.any { it.name == call.name && isTreeFlagReader(it, SUGGESTED_FLAG_FIELD) }
            ) {
                flagged += "${call.definingClass}->${call.name}"
            }
        }
        for (bucket in interfaces.filter { it in classDef.interfaces }) {
            classDef.methods.filter { isTreeEnumReader(it, BUCKET_TYPE_FIELD) }.mapTo(typed) { "$bucket->${it.name}()${it.returnType}" }
            classDef.methods.filter { isTreeFlagReader(it, SEEN_FIELD) }.mapTo(watched) { "$bucket->${it.name}()Z" }
            treeNodeAnswers(classDef, OWNER_FIELD).mapTo(owned) { "$bucket->${it.name}()${it.returnType}" }
            classDef.methods.filter { isTreeStringReader(it, ID_FIELD) }.mapTo(identified) { "$bucket->${it.name}()${it.returnType}" }
            classDef.methods.filter { isTreeTimeReader(it, EXPIRATION_FIELD) }.mapTo(expiring) { "$bucket->${it.name}()J" }
        }
        classDef.methods.filterTo(helpers) { isLabelHelper(it, interfaces, label.type) }
    }
    val flag: MethodReference = calls.filter { "${it.definingClass}->${it.name}" in flagged }
        .singleOrPatchException("$PATCH: the flag the tray receiver asks a bucket that a tree class answers with $SUGGESTED_FLAG_FIELD")
    val helper = helpers.filter { it.parameterTypes.single().toString() == flag.definingClass }
        .singleOrPatchException("$PATCH: the static helper answering ${label.type} for a ${flag.definingClass}")

    val typeEnums = classDefByStrings(BUCKET_TYPE_NAMES.first(), StringComparisonType.EQUALS)
        .filter { isEnumNaming(it, BUCKET_TYPE_NAMES) }
        .map { it.type }.toSet()
    val type = classDefBy(flag.definingClass).methods
        .filter { it.parameterTypes.isEmpty() && it.returnType in typeEnums }
        .singleOrPatchException("$PATCH: the method of ${flag.definingClass} answering an enum naming ${BUCKET_TYPE_NAMES.joinToString()}")
    if ("${flag.definingClass}->${type.name}()${type.returnType}" !in typed) {
        throw PatchException("$PATCH: no tree class answers ${flag.definingClass}->${type.name} with $BUCKET_TYPE_FIELD")
    }
    // Hide stories from Pages and Hide seen stories need these, hiding suggested stories doesn't. A
    // build without exactly one of each leaves its stub alone, and that switch says it can't work.
    val declared = classDefBy(flag.definingClass).methods.filter { it.parameterTypes.isEmpty() }
    val seen = declared.filter { "${flag.definingClass}->${it.name}()${it.returnType}" in watched }.singleOrNull()
    val owner = declared.filter {
        "${flag.definingClass}->${it.name}()${it.returnType}" in owned && classDefByOrNull(it.returnType)?.let(::namesItsType) == true
    }.singleOrNull()
    // A bucket watched with View stories anonymously on is looked up by these two (#218).
    val id = "${flag.definingClass}->$GET_ID()Ljava/lang/String;" in identified &&
        declared.any { it.name == GET_ID && it.returnType == "Ljava/lang/String;" }
    val expires = declared.filter { it.returnType == "J" && "${flag.definingClass}->${it.name}()J" in expiring }.singleOrNull()

    requirePublic(classDefBy(flag.definingClass), "the bucket interface")
    requirePublic(classDefBy(helper.definingClass), "the class of the label helper")
    if (!AccessFlags.PUBLIC.isSet(helper.accessFlags)) {
        throw PatchException("$PATCH: ${helper.definingClass}->${helper.name}, the label helper, isn't public")
    }
    val copy = classDefBy(IMMUTABLE_LIST).methods.any {
        it.name == "copyOf" && it.returnType == IMMUTABLE_LIST && AccessFlags.STATIC.isSet(it.accessFlags) &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/util/Collection;")
    }
    if (!copy) throw PatchException("$PATCH: ImmutableList has no public static copyOf(Collection) to rebuild a tray with")

    return TrayBuckets(
        constructor, list, flag.definingClass, flag.name, type.name, type.returnType, helper, label.type,
        seen?.name, owner?.name, owner?.returnType, id, expires?.name,
    )
}

/**
 * The tray parser Home's tray and a story shown in the feed build their buckets with, and which of
 * its parameters is the tree bucket list: the one method holding [TRAY_PARSE], with one ImmutableList
 * in and one back, that hands the list on.
 */
internal fun BytecodePatchContext.trayParser(): Pair<Method, Int> {
    val parsers = classDefByStrings(TRAY_PARSE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter(::isTrayParse) }
    val parser = parsers.singleOrPatchException("$PATCH: the tray parser holding \"$TRAY_PARSE\" with an ImmutableList in and out")
    val list = listParameters(parser).single()
    if (!passesOn(parser, list)) {
        throw PatchException("$PATCH: ${parser.definingClass}->${parser.name} doesn't hand its bucket list on")
    }
    return parser to list
}

private fun requirePublic(classDef: ClassDef, what: String) {
    if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) {
        throw PatchException("$PATCH: ${classDef.type}, $what, isn't public, so the extension can't reach it")
    }
}

/**
 * Fills the extension's stubs: whether an item is a bucket, its type, its suggested flag, its first
 * label, an ImmutableList of what's kept, and, when the build has them, its seen flag, its owner's
 * type name, its id and its expiration_time. Each uses only its parameter registers, so the stub's
 * own register count doesn't matter.
 */
internal fun BytecodePatchContext.fillBucketStubs(tray: TrayBuckets) {
    val filter = mutableClassDefBy(SUGGESTED_STORIES)
    fun stub(name: String, returnType: String, vararg parameters: String) = filter.methods.singleOrNull {
        it.name == name && it.returnType == returnType && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map { type -> type.toString() } == parameters.toList()
    } ?: throw PatchException("$SUGGESTED_STORIES has no static $returnType $name(${parameters.joinToString("")})")

    val helper = tray.labelHelper
    stub(IS_BUCKET_STUB, "Z", "Ljava/lang/Object;").addInstructions(
        0,
        """
            instance-of p0, p0, ${tray.bucket}
            return p0
        """,
    )
    stub(TYPE_STUB, "Ljava/lang/Object;", "Ljava/lang/Object;").addInstructions(
        0,
        """
            check-cast p0, ${tray.bucket}
            invoke-interface { p0 }, ${tray.bucket}->${tray.type}()${tray.typeEnum}
            move-result-object p0
            return-object p0
        """,
    )
    stub(SUGGESTED_STUB, "Ljava/lang/Object;", "Ljava/lang/Object;").addInstructions(
        0,
        """
            check-cast p0, ${tray.bucket}
            invoke-interface { p0 }, ${tray.bucket}->${tray.flag}()Z
            move-result p0
            invoke-static { p0 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object p0
            return-object p0
        """,
    )
    stub(LABEL_STUB, "Ljava/lang/Object;", "Ljava/lang/Object;").addInstructions(
        0,
        """
            check-cast p0, ${tray.bucket}
            invoke-static { p0 }, ${helper.definingClass}->${helper.name}(${tray.bucket})${tray.label}
            move-result-object p0
            return-object p0
        """,
    )
    stub(COPY_STUB, "Ljava/lang/Object;", "Ljava/util/List;").addInstructions(
        0,
        """
            invoke-static { p0 }, $IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST
            move-result-object p0
            return-object p0
        """,
    )
    if (tray.seen != null) {
        stub(SEEN_STUB, "Ljava/lang/Object;", "Ljava/lang/Object;").addInstructions(
            0,
            """
                check-cast p0, ${tray.bucket}
                invoke-interface { p0 }, ${tray.bucket}->${tray.seen}()Z
                move-result p0
                invoke-static { p0 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                move-result-object p0
                return-object p0
            """,
        )
    }
    if (tray.owner != null && tray.ownerType != null) {
        stub(OWNER_TYPE_STUB, "Ljava/lang/Object;", "Ljava/lang/Object;").addInstructions(
            0,
            """
                check-cast p0, ${tray.bucket}
                invoke-interface { p0 }, ${tray.bucket}->${tray.owner}()${tray.ownerType}
                move-result-object p0
                if-eqz p0, :none
                invoke-interface { p0 }, ${tray.ownerType}->$TYPE_NAME()Ljava/lang/String;
                move-result-object p0
                :none
                return-object p0
            """,
        )
    }
    if (tray.id) {
        stub(BUCKET_ID_STUB, "Ljava/lang/Object;", "Ljava/lang/Object;").addInstructions(
            0,
            """
                check-cast p0, ${tray.bucket}
                invoke-interface { p0 }, ${tray.bucket}->$GET_ID()Ljava/lang/String;
                move-result-object p0
                return-object p0
            """,
        )
    }
    if (tray.expires != null) {
        // The long goes in the pair of the stub's spare long parameter, which nothing reads.
        stub(EXPIRES_STUB, "Ljava/lang/Object;", "Ljava/lang/Object;", "J").addInstructions(
            0,
            """
                check-cast p0, ${tray.bucket}
                invoke-interface { p0 }, ${tray.bucket}->${tray.expires}()J
                move-result-wide p1
                invoke-static { p1, p2 }, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
                move-result-object p0
                return-object p0
            """,
        )
    }
}

/**
 * Before each tray fetch sets [SKIP_PROMPT_CARDS], its boolean goes through the extension and the
 * answer takes its place. Two query builders set it on every declared build, each once. Answers how
 * many places were hooked.
 */
internal fun BytecodePatchContext.hookPromptCardSkips(): Int {
    val methods = classDefByStrings(SKIP_PROMPT_CARDS, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter { holdsString(it, SKIP_PROMPT_CARDS) }.map { classDef.type to it } }
    if (methods.isEmpty()) throw PatchException("$PATCH: no tray fetch sets \"$SKIP_PROMPT_CARDS\"")

    var hooked = 0
    for ((type, found) in methods) {
        val sites = promptCardSkips(found) ?: throw PatchException(
            "$PATCH: $type->${found.name} doesn't pass \"$SKIP_PROMPT_CARDS\" straight to a (String, boolean) setter",
        )
        val parameters = found.parameterTypes.map { it.toString() }
        val method = mutableClassDefBy(type).methods.single {
            it.name == found.name && it.returnType == found.returnType && it.parameterTypes.map { p -> p.toString() } == parameters
        }
        // Last first, so each index still points at its setter.
        for ((index, register) in sites.asReversed()) {
            method.addInstructions(
                index,
                """
                    invoke-static/range { v$register .. v$register }, $SKIP_PROMPT_CARDS_HOOK
                    move-result v$register
                """,
            )
        }
        hooked += sites.size
    }
    return hooked
}

/**
 * First in the tray data's constructor, before anything reads the bucket list: the list goes to
 * the extension and its answer, the same list or a filtered copy, takes the list's place. The range
 * form names any register, and the cast holds the answer to the type the constructor stores.
 */
internal fun MutableMethod.keepOnlyUnsuggestedBuckets(list: Int) {
    val register = parameterRegisterNumber(list)
    if (register > 255) {
        throw PatchException("$PATCH: $definingClass's bucket list is in v$register, past what move-result-object can name")
    }
    addInstructions(
        0,
        """
            invoke-static/range { v$register .. v$register }, $KEPT_BUCKETS
            move-result-object v$register
            check-cast v$register, $IMMUTABLE_LIST
        """,
    )
}
