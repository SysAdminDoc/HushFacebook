/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.poststab

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Search posts tab"

/** A tab name the chip row's builder switches on to label a chip. */
internal const val CHIP_TAB_NAME = "BLENDED_VIDEOS"

internal const val LIST_BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"

internal const val IMMUTABLE_MAP = "Lcom/google/common/collect/ImmutableMap;"

internal const val CONTEXT = "Landroid/content/Context;"

internal const val LIST = "Ljava/util/List;"

internal const val POSTS_TAB_CLASS = "$EXTENSION_PACKAGE/search/SearchPostsTab;"

internal const val CHIPS = "$POSTS_TAB_CLASS->chips($LIST)$LIST"

/** The extension's stub this patch fills with a call to Facebook's table of tab labels. */
internal const val TAB_LABELS_STUB = "tabLabels"

/**
 * Search posts tab (#339).
 *
 * The chips over search results are built by one method that walks the search answer's tabs and
 * labels each one, switching on the tab's name ([CHIP_TAB_NAME] among them): it makes each chip
 * with a `(selected, id, label)` constructor, adds it to an ImmutableList builder and hands the row
 * to the chip row's static draw method (585 arm64: built in `LX/0Czv;->A00(LX/0D1n;)V`, chips
 * `LX/0Dw4;`, drawn by `LX/0Dw3;->A02(FbUserSession, LX/0Dw3;, List, Z)V`). Every row goes through
 * that draw method: the answer's, the one rebuilt after a tap from a stored list or Facebook's
 * fallback row, and the reset. A tap looks the chip's label up in Facebook's table of tab labels
 * (`LX/0Dvk;->A02(Context)ImmutableMap`, label to tab) to open that tab. Posts (BLENDED_STORIES)
 * is still in that table, but answers stopped listing it.
 *
 * The draw method hands its list of chips to the extension first and draws what comes back. The
 * extension's [TAB_LABELS_STUB] is filled with a call to the table, so with the switch on every
 * row gets a chip labelled as the table labels Posts, after Reels, and a tap on it opens the posts
 * results.
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
        val holders = classDefByStrings(CHIP_TAB_NAME, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val found = chipRows(holders)
        val (builder, row) = found.singleOrNull()
            ?: throw PatchException(
                "$PATCH: expected one method building the search chip row, found ${found.size}: " +
                    found.joinToString { it.first.descriptor() },
            )
        val rowClass = mutableClassDefBy(row.draw.definingClass)
        val tables = tabLabelTables(rowClass)
        val table = tables.singleOrNull()
            ?: throw PatchException("$PATCH: expected one table of tab labels called from ${rowClass.type}, found $tables")
        val draw = rowClass.methods.singleOrNull { it.descriptor() == row.draw.descriptor() }
            ?: throw PatchException("$PATCH: ${builder.descriptor()} draws its row with ${row.draw.descriptor()}, which isn't there")
        draw.handChipsOver()
        tabLabelsStub(mutableClassDefBy(POSTS_TAB_CLASS)).fillTabLabels(table)
        enableStatus("searchPostsTab")
    }
}

/** A chip row's builder: what its chips are and the static method that draws the row. */
internal class ChipRow(
    /** The chips' type. */
    val chipType: String,
    /** The chip row class's static method the row goes to, taking that class and the List of chips. */
    val draw: MethodReference,
)

/** Each method of [holders] loading [CHIP_TAB_NAME] that builds a chip row, with its chips and draw method. */
internal fun chipRows(holders: List<ClassDef>) =
    holders.flatMap { holder -> methodsHolding(holder, CHIP_TAB_NAME) }
        .mapNotNull { method -> chipRow(method)?.let { method to it } }

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.parameters() = parameterTypes.map { it.toString() }

/**
 * [method]'s chip row, or null when it builds none: a chip made with a `(Z, String, String)`
 * constructor and added to a list builder, then, after that, a call to a static void method of a
 * class taking that class and a List, which draws the row.
 */
internal fun chipRow(method: Method): ChipRow? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val add = (1 until code.size).firstOrNull { at ->
        val call = code[at].methodRef() ?: return@firstOrNull false
        val made = code[at - 1].methodRef() ?: return@firstOrNull false
        code[at].opcode == Opcode.INVOKE_VIRTUAL && call.definingClass == LIST_BUILDER && call.name == "add" &&
            code[at - 1].opcode == Opcode.INVOKE_DIRECT && made.name == "<init>" &&
            made.parameters() == listOf("Z", "Ljava/lang/String;", "Ljava/lang/String;")
    } ?: return null
    val chipType = code[add - 1].methodRef()!!.definingClass
    val draw = (add + 1 until code.size).firstNotNullOfOrNull { at ->
        code[at].methodRef()?.takeIf {
            code[at].opcode == Opcode.INVOKE_STATIC && it.returnType == "V" &&
                it.definingClass in it.parameters() && it.parameters().count { type -> type == LIST } == 1
        }
    } ?: return null
    return ChipRow(chipType, draw)
}

/** The static `(Context)ImmutableMap` methods [rowClass] calls: Facebook's table of tab labels. */
internal fun tabLabelTables(rowClass: ClassDef): List<MethodReference> =
    rowClass.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }
        .filter { it.opcode == Opcode.INVOKE_STATIC }
        .mapNotNull { it.methodRef() }
        .filter { it.parameters() == listOf(CONTEXT) && it.returnType == IMMUTABLE_MAP }
        .distinctBy { it.toString() }

/** The parameter register holding a static method's one List parameter. */
internal fun Method.listParameter(): Int {
    val types = parameterTypes.map { it.toString() }
    return types.take(types.indexOf(LIST)).map { if (it == "J" || it == "D") 2 else 1 }.sum()
}

/** The draw method hands its chips to the extension first and draws the list that comes back. */
internal fun MutableMethod.handChipsOver() {
    val list = listParameter()
    addInstructions(
        0,
        """
            invoke-static/range { p$list .. p$list }, $CHIPS
            move-result-object p$list
        """,
    )
}

/** The extension's static `Map tabLabels(Context)` stub in [extension]. */
internal fun tabLabelsStub(extension: MutableClass): MutableMethod = extension.methods.singleOrNull {
    it.name == TAB_LABELS_STUB && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Ljava/util/Map;" &&
        it.parameterTypes.map { type -> type.toString() } == listOf(CONTEXT)
} ?: throw PatchException("$PATCH: $POSTS_TAB_CLASS has no static Map $TAB_LABELS_STUB(Context)")

/** The stub answers [table]'s map for its context. It uses only its parameter register. */
internal fun MutableMethod.fillTabLabels(table: MethodReference) {
    addInstructions(
        0,
        """
            invoke-static { p0 }, $table
            move-result-object p0
            return-object p0
        """,
    )
}
