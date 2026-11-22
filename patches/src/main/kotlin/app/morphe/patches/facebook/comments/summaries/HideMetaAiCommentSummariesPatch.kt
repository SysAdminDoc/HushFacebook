/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.summaries

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide Meta AI comment summaries"

/** The name the comment sheet's top content socket gives itself, in the methods that go through its plugins. */
internal const val SHEET_SOCKET = "CommentFlyoutTopContentSocket"

/** The name the socket under a post's buttons gives itself. */
internal const val POST_SOCKET = "FeedStoryBelowUFIFooterSocket"

/** The comment sheet's plain summary plugin, a kept class name its socket's name table loads. */
internal const val SHEET_SUMMARY =
    "com.facebook.feedback.comments.plugins.flyouttopcontent.genaicommentsummary.GenAICommentSummaryFlyoutTopContentPlugin"

/** The comment sheet's summary plugin with a deep dive card, in the same name table. */
internal const val SHEET_DEEP_DIVE =
    "com.facebook.feedback.comments.plugins.flyouttopcontent.genaideepdiveandcommentsummary." +
        "GenAIDeepDiveAndCommentSummaryFlyoutTopContentPlugin"

/** The summary under a post's buttons, a kept class name the other socket's name table loads. */
internal const val POST_SUMMARY =
    "com.facebook.feed.plugins.belowufifooter.impl.inlinecommentsummarywithgenai.InlineCommentSummaryWithGenAIPlugin"

internal const val META_AI_SUMMARIES = "$EXTENSION_PACKAGE/comments/MetaAiSummaries;"
internal const val HOLDS = "$META_AI_SUMMARIES->holds(Ljava/lang/String;)Z"

/**
 * Takes Meta AI's summaries of a post's comments out of the comment sheet and from under posts.
 *
 * Facebook draws both through plugin sockets. A socket numbers its plugins, turns a number into
 * the plugin's class name with a static (I)String name table, and asks a static check, with the
 * plugin's number last, whether that plugin applies. A no moves the socket on to the next plugin.
 * The comment sheet's top content socket ([SHEET_SOCKET]) has two summary plugins in its table (581
 * `LX/2Ap;->A0t`, 580 `LX/25t;->A0q`, 577 `LX/1xW;->A0s`) and its check is `A28`, `A25` and `A24`.
 * The socket under a post's buttons ([POST_SOCKET]) has the inline summary in its table (581
 * `LX/2Ap;->A0i`, 580 `LX/25t;->A0g`, 577 `LX/1xW;->A0i`) and its check is `A1s`, `A1q` and `A1o`.
 *
 * Each check is the one static boolean method of the table's class that a method naming the
 * socket calls along with the table, and whose switch goes over the same plugin numbers as the
 * table's. The extension goes first in each, with the plugin's name from the table, and while the
 * switch is on a summary plugin gets a no. Every other plugin is asked as before.
 *
 * In the default selection with its switch off: it only acts once the switch is turned on.
 */
@Suppress("unused")
val hideMetaAiCommentSummariesPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide Meta AI comment summaries",
    description = "Removes Meta AI's summary of the comments from the top of the comment sheet and from under " +
        "posts, so you read what people actually wrote. Starts off. Turn it on in Hushfacebook settings > " +
        "Comments and writing.",
    default = true,
) {
    category("Comments")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        val sheet = pluginSocket(SHEET_SOCKET, SHEET_DEEP_DIVE, listOf(SHEET_SUMMARY))
        val post = pluginSocket(POST_SOCKET, POST_SUMMARY)
        if (sheet.check.descriptor() == post.check.descriptor()) {
            refuse("both sockets lead to one check, ${sheet.check.descriptor()}")
        }
        for (socket in listOf(sheet, post)) {
            mutableClassDefBy(socket.check.definingClass).methods.single { it.descriptor() == socket.check.descriptor() }
                .holdSummaries(socket.table)
        }
        enableStatus("metaAiSummaries")
    }
}

private fun refuse(detail: String, patch: String = PATCH): Nothing = throw PatchException("$patch: $detail")

/** `Lclass;->name(params)return`, the way a call names the method. A method of a class is one too. */
internal fun MethodReference.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** A socket: its name table and its per-plugin check. */
internal class PluginSocket(val table: Method, val check: Method)

private fun BytecodePatchContext.holders(string: String) =
    classDefByStrings(string, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

/** Finds the socket named [socket] whose name table names [plugin] and [alsoNamed]. [patch] names who refuses. */
internal fun BytecodePatchContext.pluginSocket(
    socket: String,
    plugin: String,
    alsoNamed: List<String> = emptyList(),
    patch: String = PATCH,
): PluginSocket {
    val table = nameTable(holders(plugin), plugin, alsoNamed, patch)
    val owner = classDefByOrNull(table.definingClass) ?: refuse("${table.definingClass} isn't in this APK", patch)
    val check = socketCheck(table, owner, holders(socket).flatMap { methodsHolding(it, socket) }, patch)
    return PluginSocket(table, check)
}

/** Whether [method] is a name table: static, (I)String, opening with a switch on its one parameter. */
internal fun isNameTable(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Ljava/lang/String;") return false
    if (method.parameterTypes.map(CharSequence::toString) != listOf("I")) return false
    val implementation = method.implementation ?: return false
    val first = implementation.instructions.firstOrNull() ?: return false
    return (first.opcode == Opcode.PACKED_SWITCH || first.opcode == Opcode.SPARSE_SWITCH) &&
        (first as OneRegisterInstruction).registerA == implementation.registerCount - 1
}

/** The numbers each of [method]'s switches goes over, one set per switch. */
internal fun switchKeys(method: Method): List<Set<Int>> =
    method.implementation?.instructions?.toList().orEmpty().filterIsInstance<SwitchPayload>()
        .map { payload -> payload.switchElements.map { it.key }.toSet() }

/**
 * The one name table among [holders] that loads [plugin] and every name in [alsoNamed]. Refuses
 * unless there's exactly one.
 */
internal fun nameTable(
    holders: List<ClassDef>,
    plugin: String,
    alsoNamed: List<String> = emptyList(),
    patch: String = PATCH,
): Method {
    val tables = holders.flatMap { methodsHolding(it, plugin) }.filter(::isNameTable).distinctBy { it.descriptor() }
    val table = tables.singleOrNull() ?: refuse("expected one name table naming $plugin, found ${tables.size}", patch)
    alsoNamed.firstOrNull { !holdsString(table, it) }?.let { refuse("${table.descriptor()} names $plugin but not $it", patch) }
    return table
}

private fun calls(method: Method): List<MethodReference> =
    method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        if (!instruction.opcode.name.startsWith("invoke")) return@mapNotNull null
        (instruction as ReferenceInstruction).reference as? MethodReference
    }

/**
 * The socket's check of whether a plugin applies: a static boolean method of [owner], the table's
 * class, with an int last, that one of [sockets] calls along with [table], and with a switch over
 * the same plugin numbers as the table's. Refuses unless there's exactly one.
 */
internal fun socketCheck(table: Method, owner: ClassDef, sockets: List<Method>, patch: String = PATCH): Method {
    val tableCall = table.descriptor()
    val callers = sockets.filter { socket -> calls(socket).any { it.descriptor() == tableCall } }
    if (callers.isEmpty()) refuse("no method naming its socket calls $tableCall", patch)
    val numbers = switchKeys(table).singleOrNull() ?: refuse("$tableCall has more than one switch", patch)
    val called = callers.flatMap(::calls).filter { it.definingClass == owner.type }.map { it.descriptor() }.toSet()
    val checks = owner.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
            method.parameterTypes.lastOrNull()?.toString() == "I" && method.descriptor() in called &&
            numbers in switchKeys(method)
    }
    return checks.singleOrNull() ?: refuse(
        "expected one check of $tableCall's plugins, found ${checks.size}: ${checks.joinToString { it.name }}",
        patch,
    )
}

/**
 * First thing in the check: get the plugin's name from [table] with the check's own number, ask
 * the extension ([hook], a static (String)Z), and answer no when it holds that plugin. Otherwise
 * the check runs from its first instruction. The number is the last register, so the call takes
 * it as a range. [patch] names who refuses a check with no local register.
 */
internal fun MutableMethod.holdSummaries(table: Method, hook: String = HOLDS, patch: String = PATCH) {
    requireLocals(patch, 1)
    val number = implementation!!.registerCount - 1
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { v$number .. v$number }, ${table.descriptor()}
            move-result-object v0
            invoke-static { v0 }, $hook
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", getInstruction(0)),
    )
}
