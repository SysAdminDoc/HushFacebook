/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.poststab

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.reels.categoryNames
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Search posts tab"

/** The trace section Facebook opens while it reads a search answer's filters and tabs. */
internal const val FILTER_PARSE = "filter parse"

internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"

internal const val EDGES = "$EXTENSION_PACKAGE/search/SearchPostsTab;->edges(Ljava/util/List;)Ljava/util/List;"

internal const val COPY_OF = "$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST"

/** Tabs the enum an entry answers must hold, as its constants are named. */
internal val TAB_NAMES = listOf("POSTS", "REELS", "PEOPLE", "GROUPS")

/**
 * Search posts tab (#339).
 *
 * The chips over search results come from the search answer: a list of entries, each answering
 * the tab it stands for through an interface call (585 arm64: `LX/0DrM;->DE7()LX/0BfT;`, the tab
 * enum holding POSTS, REELS, PEOPLE, GROUPS and the rest). Facebook reads that list under the
 * [FILTER_PARSE] trace section, and for each entry naming a tab it builds the chip itself, with
 * the tab's own label, kind and query (`LX/0D0J;->A04`). POSTS is still one of them, with the
 * "Posts" label and the blended posts search, but answers stopped listing it.
 *
 * The method opening [FILTER_PARSE] casts the list to ImmutableList before it walks it. Right
 * after that cast the extension is handed the list, and what it answers goes back into the same
 * register as an ImmutableList (`copyOf` gives back the same list when nothing changed). With the
 * switch on it adds an entry naming POSTS after REELS, and Facebook builds that chip like the rest.
 *
 * The cast is found by shape: the first check-cast to ImmutableList after the trace string, in a
 * method that also reads an entry's tab with an interface call answering the enum holding all of
 * [TAB_NAMES] after it.
 */
@Suppress("unused")
val searchPostsTabPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Search posts tab",
    description = "Puts the Posts chip back in the row over search results, after Reels, so you can see only posts " +
        "for what you searched. Turn it off in Hushfacebook settings > Search.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("searchPostsTab")
        val holders = classDefByStrings(FILTER_PARSE, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val found = listReads(holders)
        val (parse, read) = found.singleOrNull()
            ?: throw PatchException(
                "$PATCH: expected one method opening \"$FILTER_PARSE\" that casts a list and reads each entry's tab, " +
                    "found ${found.size}: ${found.joinToString { it.first.descriptor() }}",
            )
        val method = mutableClassDefBy(parse.definingClass).methods.single { it.descriptor() == parse.descriptor() }
        method.hookEntries(read)
        enableStatus("searchPostsTab")
    }
}

/** Where a filter parse casts its list of entries, and how it reads an entry's tab. */
internal class ListRead(
    /** The index of the check-cast to ImmutableList. */
    val cast: Int,
    /** The register the list is in. */
    val list: Int,
    /** The interface call that reads an entry's tab. */
    val tabRead: MethodReference,
)

/**
 * Each method of [holders] opening [FILTER_PARSE], with where it casts its list. A type Facebook
 * doesn't define is looked up as no class.
 */
internal fun BytecodePatchContext.listReads(holders: List<ClassDef>) =
    holders.flatMap { holder -> methodsHolding(holder, FILTER_PARSE) }
        .mapNotNull { method -> listRead(method) { type -> classDefByOrNull(type) }?.let { method to it } }

/**
 * Where [method] casts the list it reads after [FILTER_PARSE], or null when it doesn't, or reads
 * no entry's tab after it. [classOf] looks a class up by type.
 */
internal fun listRead(method: Method, classOf: (String) -> ClassDef?): ListRead? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val trace = code.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == FILTER_PARSE
    }
    if (trace < 0) return null
    val cast = (trace + 1 until code.size).firstOrNull { at ->
        code[at].opcode == Opcode.CHECK_CAST &&
            ((code[at] as ReferenceInstruction).reference as? TypeReference)?.type == IMMUTABLE_LIST
    } ?: return null
    val list = (code[cast] as OneRegisterInstruction).registerA
    if (list > 15) return null
    val tabRead = (cast + 1 until code.size).firstNotNullOfOrNull { at ->
        val call = code[at]
        if (call.opcode != Opcode.INVOKE_INTERFACE) return@firstNotNullOfOrNull null
        val reference = (call as ReferenceInstruction).reference as? MethodReference ?: return@firstNotNullOfOrNull null
        if (reference.parameterTypes.isNotEmpty() || !reference.returnType.startsWith("L")) return@firstNotNullOfOrNull null
        if ((call as FiveRegisterInstruction).registerCount != 1) return@firstNotNullOfOrNull null
        val tabs = classOf(reference.returnType) ?: return@firstNotNullOfOrNull null
        if (!categoryNames(tabs).containsAll(TAB_NAMES)) return@firstNotNullOfOrNull null
        reference
    } ?: return null
    return ListRead(cast, list, tabRead)
}

/** After the list is cast, the extension is handed it and its answer goes back into the register as an ImmutableList. */
internal fun MutableMethod.hookEntries(read: ListRead) {
    addInstructions(
        read.cast + 1,
        """
            invoke-static { v${read.list} }, $EDGES
            move-result-object v${read.list}
            invoke-static { v${read.list} }, $COPY_OF
            move-result-object v${read.list}
        """,
    )
}
