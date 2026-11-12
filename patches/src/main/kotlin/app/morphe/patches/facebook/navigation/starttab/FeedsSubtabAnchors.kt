/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.starttab

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.feedsheader.FEED_FILTERS_FRAGMENT
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * How the Feeds tab picks one of its filters from an intent, on the 577 and 580 builds (#56).
 *
 * The Feeds tab is com.facebook.feed.fragment.FeedFiltersFragment, whether it's on the tab bar or
 * opened from the Menu, and Redex keeps the names below. Its handleDeeplinkFromMainActivity(Intent)
 * is how the main screen hands it an intent: with one, it reads the feed type the tab last showed,
 * a static FeedType a usage tracker keeps (loaded once from the preference
 * "/feed_filters/last_feed_used"), asks the tab's state where that feed type is among its filters,
 * a lookup that answers -1 when they haven't got it, and has the filter controller pick the filter
 * at that position. The state holds two lists of filters, the usual one and the one for the most
 * recent posts, and the lookup searches whichever it has a flag for. The main screen calls the
 * handler only from onNewIntent, for an intent asking for it or a tab that always wants one, so a
 * start from the launcher icon never does.
 *
 * The patch puts the extension's FeedsSubtabRoute in three places. Right after the handler reads
 * the last feed type, the extension's answer takes its place, the chosen filter's feed type while
 * the extension asks and the same one the rest of the time, cast back to a FeedType before the
 * lookup reads it. Right after the lookup answers, the answer goes through the extension unchanged,
 * which is how it knows the tab had the feed type it asked for. And at each return of the tab's
 * onResume, the extension is handed the tab, so it can call the handler once after a start that
 * asked for a filter. It finds each feed type among FeedType's public constants by the name it keeps.
 */

/** Facebook's feed type. Redex keeps the name, and each feed type's own name. */
internal const val FEED_TYPE = "Lcom/facebook/api/feedtype/FeedType;"

/** The Feeds tab's handler for an intent the main screen hands it. */
internal const val FEEDS_HANDLER = "handleDeeplinkFromMainActivity"

/** The extension's hook at each return of the Feeds tab's onResume. */
internal const val FEEDS_RESUMED = "$EXTENSION_PACKAGE/navigation/FeedsSubtabRoute;->feedsResumed(Ljava/lang/Object;)V"

/** The extension's answer where the Feeds tab's handler reads the feed type it last showed. */
internal const val FEED_TYPE_ASKED =
    "$EXTENSION_PACKAGE/navigation/FeedsSubtabRoute;->feedType(Ljava/lang/Object;)Ljava/lang/Object;"

/** The extension's hook on the answer of the handler's lookup. */
internal const val FILTER_FOUND = "$EXTENSION_PACKAGE/navigation/FeedsSubtabRoute;->filterFound(I)I"

/** How far past the read the filter list lookup may sit: the state manager's field, its get, its cast, the lookup. */
private const val FILTER_LOOKUP_WINDOW = 8

private val Instruction.call: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

/** Whether [instruction] writes [register], as itself or as the second half of a wide value. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister()) return false
    val written = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (instruction.opcode.setsWideRegister() && written + 1 == register)
}

/**
 * Where the Feeds tab's handler reads the feed type it last showed, and where its lookup's answer
 * is kept: both indexes into its code.
 */
internal data class FeedsHandlerAnchors(val read: Int, val answer: Int)

/**
 * Where [method] is the Feeds tab's handler, the indexes it's hooked at, or null when it isn't.
 *
 * The handler takes an intent and returns nothing. The read is the one static read of a FeedType
 * whose register, unchanged, is the feed type handed to a lookup that takes a FeedType and answers
 * an int, a few instructions on. The answer is the `move-result` right after that lookup.
 */
internal fun feedsHandlerAnchors(method: Method): FeedsHandlerAnchors? {
    if (method.name != FEEDS_HANDLER || method.returnType != "V" ||
        method.parameterTypes.map { it.toString() } != listOf(INTENT)
    ) {
        return null
    }
    val code = method.implementation?.instructions?.toList() ?: return null
    val found = code.indices.mapNotNull { index ->
        val read = code[index]
        if (read.opcode != Opcode.SGET_OBJECT ||
            ((read as ReferenceInstruction).reference as FieldReference).type != FEED_TYPE
        ) {
            return@mapNotNull null
        }
        val register = (read as OneRegisterInstruction).registerA
        val end = minOf(code.size, index + 1 + FILTER_LOOKUP_WINDOW)
        val lookup = (index + 1 until end).firstOrNull { at ->
            val call = code[at].call
            call != null && call.parameterTypes.map { it.toString() } == listOf(FEED_TYPE) && call.returnType == "I"
        } ?: return@mapNotNull null
        if ((index + 1 until lookup).any { writes(code[it], register) }) return@mapNotNull null
        if (code[lookup].callRegisters().lastOrNull() != register) return@mapNotNull null
        if (code.getOrNull(lookup + 1)?.opcode != Opcode.MOVE_RESULT) return@mapNotNull null
        FeedsHandlerAnchors(index, lookup + 1)
    }
    return found.singleOrNull()
}

/**
 * Why [fragment] can't take the Feeds filter hooks, or null when it can: it declares one public
 * handler with one read of the last feed type and a lookup whose answer is kept, an onResume
 * that returns and leaves its `this` register alone, which the hook reads at the end, and an
 * onBackPressed with code nothing jumps back to the start of, where the Back hook goes.
 */
internal fun feedsFragmentRefusal(fragment: ClassDef): String? {
    val handlers = fragment.methods.filter { it.name == FEEDS_HANDLER }
    val handler = handlers.singleOrNull() ?: return "expected one $FEED_FILTERS_FRAGMENT->$FEEDS_HANDLER, found ${handlers.size}"
    if (!AccessFlags.PUBLIC.isSet(handler.accessFlags)) return "$FEED_FILTERS_FRAGMENT->$FEEDS_HANDLER isn't public"
    if (feedsHandlerAnchors(handler) == null) {
        return "$FEED_FILTERS_FRAGMENT->$FEEDS_HANDLER reads no last feed type for a lookup whose answer it keeps"
    }
    val resume = fragment.methods.singleOrNull { it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        ?: return "$FEED_FILTERS_FRAGMENT declares no onResume()V"
    val implementation = resume.implementation ?: return "$FEED_FILTERS_FRAGMENT->onResume has no code"
    val code = implementation.instructions.toList()
    if (code.none { it.opcode == Opcode.RETURN_VOID }) return "$FEED_FILTERS_FRAGMENT->onResume never returns"
    val self = implementation.registerCount - 1
    if (code.any { writes(it, self) }) return "$FEED_FILTERS_FRAGMENT->onResume reuses the register its this is in"
    val back = fragment.methods.singleOrNull { it.name == "onBackPressed" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
        ?: return "$FEED_FILTERS_FRAGMENT declares no onBackPressed()Z"
    val backCode = back.implementation?.instructions?.toList() ?: return "$FEED_FILTERS_FRAGMENT->onBackPressed has no code"
    // A jump to the start would land past the hook, on Facebook's first instruction.
    var at = 0
    val jumpsToStart = backCode.any { instruction ->
        val target = (instruction as? OffsetInstruction)?.let { at + it.codeOffset }
        at += instruction.codeUnits
        target == 0
    }
    if (jumpsToStart) return "$FEED_FILTERS_FRAGMENT->onBackPressed jumps back to its start"
    return null
}

/**
 * Hands the feed type the handler read to the extension and keeps the extension's answer, cast
 * back to a FeedType, in the same register before anything reads it, then hands the lookup's
 * answer through the extension the same way. Nothing may jump past either: code arriving there
 * would skip the call. Both are checked before either goes in.
 */
internal fun MutableMethod.askExtensionForFilter(anchors: FeedsHandlerAnchors) {
    val instructions = implementation!!.instructions
    listOf(anchors.read + 1 to "read of the last feed type", anchors.answer + 1 to "lookup's answer").forEach { (at, what) ->
        if ((instructions[at] as BuilderInstruction).location.labels.isNotEmpty()) {
            throw PatchException("$PATCH: $definingClass->$name has a jump past its $what.")
        }
    }
    // The later one first, so the earlier index still points where it did.
    val answer = (instructions[anchors.answer] as OneRegisterInstruction).registerA
    addInstructions(
        anchors.answer + 1,
        """
            invoke-static/range { v$answer .. v$answer }, $FILTER_FOUND
            move-result v$answer
        """,
    )
    val register = (instructions[anchors.read] as OneRegisterInstruction).registerA
    addInstructions(
        anchors.read + 1,
        """
            invoke-static/range { v$register .. v$register }, $FEED_TYPE_ASKED
            move-result-object v$register
            check-cast v$register, $FEED_TYPE
        """,
    )
}

/**
 * Hands the tab to the extension at each return of its onResume. The call takes the return's
 * place, so a branch to the return goes through it, and a new return follows.
 */
internal fun MutableMethod.tellExtensionAtResumeReturns() {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    returns.reversed().forEach { index ->
        replaceInstruction(index, "invoke-static/range { p0 .. p0 }, $FEEDS_RESUMED")
        addInstruction(index + 1, "return-void")
    }
}
