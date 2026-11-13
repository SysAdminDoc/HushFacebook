/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.order

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
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

/*
 * Most viewed first on a profile's Reels tab (#232), read from 585 on 2026-10-31. The obfuscated
 * names are for reviewers; the code finds each spot by a kept string or a shape.
 *
 * The tab is a section, the class that loads "FbShortsProfileViewerParadesSection" (585 arm64
 * LX/0NF6). Its one instance method of one parameter that loads that string (A0o) copies the
 * profile's loaded reel nodes into an ArrayList, then passes it, with its own parameter, to a
 * static (parameter, List) builder that makes the tiles. That call is reached only by falling
 * through from a null check on the list, so the hook goes in right before it and sorts the list in
 * place by the extension. The play count is the one static method of one argument that answers an
 * Integer and holds the GraphQL key hash of "play_count" (1911031876; 585 LX/0Wof;->A01 on arm64,
 * LX/0WoB;->A01 on armeabi-v7a). The extension finds the path from a list node to its argument by
 * shape, so nothing else is written down.
 *
 * The Reels tab of a Page carries its counts elsewhere: a tab item (585 arm64 LX/0Xsd) reaches the
 * reader's argument only through an empty branch (ANw().ANb().Ax8()), while its own view badge reads
 * AO1().FXV().Eq8(). Facebook's tree models name every play count getter alike, a no-argument int
 * method holding the key (Eq8 on 585, all 24 of them), so that name goes to the extension too, as
 * "reader/getter", and the extension tries chains ending at that getter first.
 *
 * 587 (arm64 0lvQ.A0E) answers an int instead of an Integer, from a tree argument, and the getters
 * are named FCg. One static int method taking a GraphQLStory holds the key as well, so the reader's
 * argument has to be something other than a GraphQL model class. Some arm64 getters (0ma6.FCg) call
 * the reader instead of holding the key. 587's armeabi-v7a build has no reader at all: each of its
 * getters (FCe) holds the key, so the extension gets the getter alone there.
 */

private const val PROFILE_SORT = "Newest reels first (profile reels order)"
private const val SORT_CLASS = "$EXTENSION_PACKAGE/reels/ProfileReelsSort;"
private const val SORT_CALL =
    "$SORT_CLASS->sort(Ljava/util/List;Ljava/lang/Class;Ljava/lang/String;)V"

/** The kept name of the section that lists a profile's reels. */
internal const val PROFILE_REELS_SECTION = "FbShortsProfileViewerParadesSection"

/** The Java hash of the GraphQL field "play_count", as the play count reader loads it. */
internal const val PLAY_COUNT_KEY = 1911031876

private const val LIST_TYPE = "Ljava/util/List;"

private fun refuseProfile(detail: String): Nothing = throw PatchException("$PROFILE_SORT: $detail")

/** Where the sort hook goes, and what it is handed. */
internal class ProfileReelsSites(
    val section: Method,
    val hookAt: Int,
    val listRegister: Int,
    val classRegister: Int,
    val nameRegister: Int,
    val holder: String,
    val names: String,
)

private fun holdsPlayCountKey(method: Method): Boolean =
    method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == PLAY_COUNT_KEY } == true

/**
 * Whether [method] reads a play count: a static (one argument) method answering an Integer, or from
 * 587 on an int, holding the key. Its argument is a tree model, never a GraphQL model class: 587 also
 * has static int helpers holding the key that take one of those.
 */
internal fun isPlayCountReader(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType in PLAY_COUNT_TYPES &&
        method.parameterTypes.size == 1 && !method.parameterTypes[0].toString().startsWith(GRAPHQL_MODELS) &&
        holdsPlayCountKey(method)

/** What a play count reader answers: an Integer up to 586, an int from 587 on. */
private val PLAY_COUNT_TYPES = setOf("Ljava/lang/Integer;", "I")

private const val GRAPHQL_MODELS = "Lcom/facebook/graphql/model/"

/** Whether [method] is a tree model's play count getter: an instance method of no arguments answering an int, holding the key. */
internal fun isPlayCountGetter(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" && method.parameterTypes.isEmpty() &&
        holdsPlayCountKey(method)

/** What the extension is handed to read a play count: the reader's class, and "reader/getter". */
internal class PlayCount(val holder: String, val names: String)

/**
 * What a build with no reader hands over as the reader's class: one the extension finds no reader on,
 * so it reads the getter alone. 587's armeabi-v7a build has no reader: Redex folded it into each
 * getter, and every getter holds the key itself.
 */
internal const val NO_READER = "Ljava/lang/Object;"

/**
 * The play count, from [methods] (those [isPlayCountReader] or [isPlayCountGetter] accepts). Refuses
 * unless there is at most one reader and every getter shares one name. With no reader, the getter
 * alone is handed over, on [NO_READER].
 */
internal fun playCount(methods: List<Method>): PlayCount {
    val readers = methods.filter { isPlayCountReader(it) }
    if (readers.size > 1) refuseProfile("expected one play count reader at most, found ${readers.size}")
    val getters = methods.filter { isPlayCountGetter(it) }.map { it.name }.toSortedSet()
    val getter = getters.singleOrNull() ?: refuseProfile("expected the play count getters to share one name, found $getters")
    val reader = readers.singleOrNull() ?: return PlayCount(NO_READER, "/$getter")
    return PlayCount(reader.definingClass, "${reader.name}/$getter")
}

/**
 * Where the sort goes, from [sections] (the methods loading [PROFILE_REELS_SECTION]) and [count].
 * Refuses unless each part is found exactly once.
 */
internal fun profileReelsSites(sections: List<Method>, count: PlayCount): ProfileReelsSites {
    val candidates = sections.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.name != "<init>" && it.parameterTypes.size == 1 &&
            it.implementation != null
    }
    val section = candidates.singleOrNull() ?: refuseProfile("expected one section method of one parameter loading \"$PROFILE_REELS_SECTION\", found ${candidates.size}")
    val parameter = section.parameterTypes[0].toString()
    val code = section.implementation!!.instructions.toList()
    val calls = code.map { (it as? ReferenceInstruction)?.reference as? MethodReference }
    val statics = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
    // A static (context, List) call builds the tiles from the list, up to 586 and on 587 arm64.
    // 587's armeabi-v7a build inlines it: a static (context) call makes the builder, and the list
    // is handed to it by a (List)V call (`LX/04C5;->A09`). The list is the second register either way.
    val builders = code.indices.filter { code[it].opcode in statics }.mapNotNull { calls[it] }
        .filter { call -> call.parameterTypes.map { it.toString() } == listOf(parameter) }.map { it.returnType }.toSet()
    val builds = code.withIndex().filter { (index, instruction) ->
        val call = calls[index] ?: return@filter false
        val types = call.parameterTypes.map { it.toString() }
        when (instruction.opcode) {
            in statics -> types == listOf(parameter, LIST_TYPE)
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE ->
                types == listOf(LIST_TYPE) && call.returnType == "V" && call.definingClass in builders
            else -> false
        }
    }
    val build = builds.singleOrNull()
        ?: refuseProfile("expected one ($parameter, List) builder call, or one List handed to a builder made from $parameter, in ${section.name}, found ${builds.size}")
    val listRegister = when (val instruction = build.value) {
        is FiveRegisterInstruction -> instruction.registerD
        is RegisterRangeInstruction -> instruction.startRegister + 1
        else -> refuseProfile("the builder call is neither a five-register nor a range call")
    }
    if (listRegister > 15) refuseProfile("the list is in v$listRegister, past the registers an invoke can name")
    val free = section.freeLocalsAt(PROFILE_SORT, build.index, 2)
    return ProfileReelsSites(section, build.index, listRegister, free[0], free[1], count.holder, count.names)
}

/** Puts the sort in front of the builder call, [mutable] reading the class to change. */
internal fun hookProfileReels(sites: ProfileReelsSites, mutable: (String) -> ClassDef) {
    val method = mutable(sites.section.definingClass).methods.single { it.descriptor() == sites.section.descriptor() } as MutableMethod
    method.addInstructions(
        sites.hookAt,
        """
            const-class v${sites.classRegister}, ${sites.holder}
            const-string v${sites.nameRegister}, "${sites.names}"
            invoke-static {v${sites.listRegister}, v${sites.classRegister}, v${sites.nameRegister}}, $SORT_CALL
        """,
    )
}


/** The kept name of the section that draws a profile's Reels tab as a grid of tiles. */
internal const val PROFILE_GRID_SECTION = "FbShortsVideoGridContentSection"

internal const val PAGINABLE = "Lcom/facebook/graphservice/interfaces/PaginableList;"
private const val LIST_CALL =
    "$SORT_CLASS->list(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;"

/**
 * The types a paginated list is passed around as, from [paginable] (the app's [PAGINABLE]): that
 * class, and from 586 the Kotlin base it extends (586 `LX/0mew`), which holds the rows, the paging
 * and `underlyingList`, and which the grid, the tab's lambda and its builder now take or answer.
 */
internal fun paginatedTypes(paginable: ClassDef?): Set<String> =
    setOfNotNull(PAGINABLE, paginable?.superclass?.takeIf { it != "Ljava/lang/Object;" })

/**
 * Where the grid hook goes: right after [method] keeps its paginated list in [list], at [after].
 * [type] is what the call answers, which the extension's answer is cast back to.
 */
internal class ProfileGridSite(
    val method: Method,
    val after: Int,
    val list: Int,
    val classRegister: Int,
    val nameRegister: Int,
    val holder: String,
    val names: String,
    val type: String,
)

/**
 * The grid hook's site, from [sections] (the methods loading [PROFILE_GRID_SECTION]) and [count].
 * It is the first no-argument call in the one section method of one parameter that answers a
 * paginated list (one of [paginated]), with its result kept. Refuses unless each part is found
 * exactly once.
 */
internal fun profileGridSite(sections: List<Method>, count: PlayCount, paginated: Set<String>): ProfileGridSite {
    val candidates = sections.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.name != "<init>" && it.parameterTypes.size == 1 &&
            it.implementation != null
    }
    val section = candidates.singleOrNull() ?: refuseProfile("expected one grid method of one parameter loading \"$PROFILE_GRID_SECTION\", found ${candidates.size}")
    val code = section.implementation!!.instructions.toList()
    val at = code.indices.firstOrNull { index ->
        val call = code[index]
        if (call.opcode != Opcode.INVOKE_VIRTUAL && call.opcode != Opcode.INVOKE_INTERFACE) return@firstOrNull false
        val ref = (call as ReferenceInstruction).reference as? MethodReference ?: return@firstOrNull false
        ref.returnType in paginated && ref.parameterTypes.isEmpty() &&
            code.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    } ?: refuseProfile("${section.name} never keeps a paginated list")
    val list = (code[at + 1] as OneRegisterInstruction).registerA
    if (list > 15) refuseProfile("the grid list is in v$list, past the registers an invoke can name")
    val free = section.freeLocalsAt(PROFILE_SORT, at + 2, 2)
    val type = ((code[at] as ReferenceInstruction).reference as MethodReference).returnType
    return ProfileGridSite(section, at + 2, list, free[0], free[1], count.holder, count.names, type)
}

/** Hands the grid's list to the extension and keeps its answer as the list. */
internal fun hookProfileGrid(site: ProfileGridSite, mutable: (String) -> ClassDef) {
    val method = mutable(site.method.definingClass).methods.single { it.descriptor() == site.method.descriptor() } as MutableMethod
    method.addInstructions(
        site.after,
        """
            const-class v${site.classRegister}, ${site.holder}
            const-string v${site.nameRegister}, "${site.names}"
            invoke-static {v${site.list}, v${site.classRegister}, v${site.nameRegister}}, $LIST_CALL
            move-result-object v${site.list}
            check-cast v${site.list}, ${site.type}
        """,
    )
}

/*
 * The Reels tab of a profile or a Page is its own section, read from 585 on 2026-10-31: the spec that
 * loads "FbReelsProfileTabContentSection" (585 arm64 LX/0CvE;->A00) asks a static (FbUserSession, tab
 * data) helper (LX/0CvS;->A00) for the tab's paginated list and hands it to a lambda it builds right
 * after (LX/0CvU), which turns each GraphQL result into the grid's items. That list only lends the
 * items its paging: the lambda reads the tiles from the result's own model, either as a state's
 * paginated list (LX/0iIH;->A04, holders of one tab item each) or, under one MobileConfig flag,
 * through a static (result, list, prefix items) builder (LX/0CvW;->A01) that walks the model's rows.
 * Each reads its rows once, as a paginated list's "list" field, and the hooks hand those rows to the
 * extension before Facebook numbers the tiles. A tile's tap opens the viewer by the reel's id and
 * "load more" asks the helper again, so neither depends on the order. The other holder of the name
 * (LX/0CvE;->A01) returns nothing and only logs the tab's state.
 */

/** The kept name of the section that draws the Reels tab of a profile or a Page. */
internal const val PROFILE_TAB_SECTION = "FbReelsProfileTabContentSection"

private const val SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

private const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val ROWS_CALL =
    "$SORT_CLASS->rows(Ljava/util/List;Ljava/lang/Class;Ljava/lang/String;)Ljava/util/List;"

/**
 * Where a tab rows hook goes: right after [method] reads a paginated list's rows into [rows], at
 * [after]. The rows are copied to [copyRegister] to be handed over, since they can sit past v15.
 */
internal class TabRowsSite(
    val method: Method,
    val after: Int,
    val rows: Int,
    val copyRegister: Int,
    val classRegister: Int,
    val nameRegister: Int,
    val holder: String,
    val names: String,
)

private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun MethodReference.parameters() = parameterTypes.map { it.toString() }

/**
 * The class the tab hands its paginated list to, from [tabs] (the methods loading
 * [PROFILE_TAB_SECTION]): the one static (FbUserSession, one more) call answering a paginated list
 * (one of [paginated]), with its result kept, then the first constructor after it that is given that
 * result as a paginated list. Refuses unless each part is found exactly once.
 */
internal fun profileTabLambda(tabs: List<Method>, paginated: Set<String>): String {
    val calls = tabs.filter { it.returnType != "V" && it.implementation != null }.flatMap { method ->
        val code = method.implementation!!.instructions.toList()
        code.indices.filter { index ->
            val call = code[index]
            if (call.opcode != Opcode.INVOKE_STATIC && call.opcode != Opcode.INVOKE_STATIC_RANGE) return@filter false
            val ref = (call as ReferenceInstruction).reference as? MethodReference ?: return@filter false
            ref.returnType in paginated && ref.parameterTypes.size == 2 && ref.parameterTypes[0].toString() == SESSION &&
                code.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
        }.map { index -> method to index }
    }
    val (method, at) = calls.singleOrNull() ?: refuseProfile("expected one (FbUserSession, x) paginated list kept by a method loading \"$PROFILE_TAB_SECTION\", found ${calls.size}")
    val code = method.implementation!!.instructions.toList()
    val list = (code[at + 1] as OneRegisterInstruction).registerA
    val init = code.drop(at + 2).firstOrNull { call ->
        if (call.opcode != Opcode.INVOKE_DIRECT && call.opcode != Opcode.INVOKE_DIRECT_RANGE) return@firstOrNull false
        val ref = (call as ReferenceInstruction).reference as? MethodReference ?: return@firstOrNull false
        ref.name == "<init>" && ref.parameters().any { it in paginated } && list in call.registers()
    } ?: refuseProfile("${method.name} hands its paginated list to no constructor")
    return ((init as ReferenceInstruction).reference as MethodReference).definingClass
}

/** The one read of a paginated list's rows in [method], as a site. Refuses unless there is exactly one. */
private fun rowsSite(method: Method, count: PlayCount): TabRowsSite {
    val code = method.implementation?.instructions?.toList() ?: refuseProfile("${method.definingClass}->${method.name} has no code")
    val reads = code.indices.filter { index ->
        val read = code[index]
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference
        read.opcode == Opcode.IGET_OBJECT && field != null && field.name == "list" && field.type == IMMUTABLE_LIST
    }
    val at = reads.singleOrNull() ?: refuseProfile("expected one read of a paginated list's rows in ${method.definingClass}->${method.name}, found ${reads.size}")
    val rows = (code[at] as TwoRegisterInstruction).registerA
    val free = method.freeLocalsAt(PROFILE_SORT, at + 1, 3)
    return TabRowsSite(method, at + 1, rows, free[0], free[1], free[2], count.holder, count.names)
}

/**
 * The tab's two rows sites, from [lambda] (the class [profileTabLambda] names) and [classBy]: the
 * rows its invoke reads from a state's paginated list, then those read by the one static builder it
 * calls that answers a paginated list from one (each one of [paginated]). Refuses unless each is
 * found exactly once.
 */
internal fun profileTabRowsSites(lambda: ClassDef, classBy: (String) -> ClassDef?, count: PlayCount, paginated: Set<String>): List<TabRowsSite> {
    val invoke = lambda.methods.singleOrNull { it.name == "invoke" && it.parameterTypes.size == 1 && it.implementation != null }
        ?: refuseProfile("${lambda.type} has no invoke")
    val builders = invoke.implementation!!.instructions.mapNotNull { call ->
        if (call.opcode != Opcode.INVOKE_STATIC && call.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        val ref = (call as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        ref.takeIf { it.definingClass !in paginated && it.returnType in paginated && it.parameters().any { type -> type in paginated } }
    }.distinctBy { it.toString() }
    val ref = builders.singleOrNull() ?: refuseProfile("expected one static paginated list builder in ${lambda.type}->invoke, found ${builders.size}")
    val builder = classBy(ref.definingClass)?.methods?.singleOrNull {
        it.name == ref.name && it.returnType == ref.returnType && it.parameterTypes.map { type -> type.toString() } == ref.parameters()
    } ?: refuseProfile("$ref isn't in the app")
    return listOf(rowsSite(invoke, count), rowsSite(builder, count))
}

/** Hands the tab's rows to the extension and keeps its answer as the rows. */
internal fun hookProfileTabRows(site: TabRowsSite, mutable: (String) -> ClassDef) {
    val method = mutable(site.method.definingClass).methods.single { it.descriptor() == site.method.descriptor() } as MutableMethod
    method.addInstructions(
        site.after,
        """
            move-object/from16 v${site.copyRegister}, v${site.rows}
            const-class v${site.classRegister}, ${site.holder}
            const-string v${site.nameRegister}, "${site.names}"
            invoke-static {v${site.copyRegister}, v${site.classRegister}, v${site.nameRegister}}, $ROWS_CALL
            move-result-object v${site.rows}
            check-cast v${site.rows}, $IMMUTABLE_LIST
        """,
    )
}

/**
 * The profile Reels sort goes in. Nameless: Newest reels first carries it, and its fixture tests
 * run it on its own. Everything is found before anything changes.
 */
internal val profileReelsSortPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        val sections = classDefByStrings(PROFILE_REELS_SECTION, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { owner -> owner.methods.filter { holdsString(it, PROFILE_REELS_SECTION) } }
        val counters = mutableListOf<Method>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
            counters += classDef.methods.filter { isPlayCountReader(it) || isPlayCountGetter(it) }
        }
        val count = playCount(counters)
        val grids = classDefByStrings(PROFILE_GRID_SECTION, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { owner -> owner.methods.filter { holdsString(it, PROFILE_GRID_SECTION) } }
        val tabs = classDefByStrings(PROFILE_TAB_SECTION, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { owner -> owner.methods.filter { holdsString(it, PROFILE_TAB_SECTION) } }
        val paginated = paginatedTypes(classDefByOrNull(PAGINABLE))
        val gridSite = profileGridSite(grids, count, paginated)
        val lambda = profileTabLambda(tabs, paginated)
        val tabSites = profileTabRowsSites(
            classDefByOrNull(lambda) ?: refuseProfile("$lambda isn't in the app"),
            { type -> classDefByOrNull(type) },
            count,
            paginated,
        )
        val sites = profileReelsSites(sections, count)
        hookProfileReels(sites) { type -> mutableClassDefBy(type) }
        hookProfileGrid(gridSite) { type -> mutableClassDefBy(type) }
        tabSites.forEach { site -> hookProfileTabRows(site) { type -> mutableClassDefBy(type) } }
    }
}
