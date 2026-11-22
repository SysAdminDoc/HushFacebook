/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.executeBytecode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * Why Home could stop loading with most of it hidden (read from 582, 2026-10-14).
 *
 * Home's scroll fetcher, NewsFeedListViewScrollFetcher, runs onScroll on every scroll of the list.
 * It asks the feed loader for the next page (loadAfterData, "tailFetchListener") only when the post
 * on screen has moved past the one it last asked at. The feed guard turns edges away in
 * addNewEdgeToCollection, and when it turns away most or all of a page, nothing new reaches the
 * list: the post on screen stays where it was, the fetcher never asks again, and Home sits on its
 * loading row however far it's scrolled. A heap dump at such a stall showed the feed collection and
 * the list holding the same two edges, onScroll still running on every swipe, and its log state
 * reading shouldFetch=false, atTail=true.
 *
 * So onScroll's branch that doesn't fetch asks the extension first, with the last row on screen and
 * the number of rows, and goes to the ask Facebook would have made when told to. The ask is the
 * block that logs "dispatchTailFetch"; its "isTailVisible" value reads those same two fields off the
 * fetcher, and the code here reads them the same way.
 *
 * onScroll runs only when the list scrolls or its rows on screen change (#493, read from 588,
 * 2026-11-19). Its one other caller is the feed's own scroll to the top, which hands it the first
 * row on screen, the rows on screen and the rows in the list off the list itself. On a cold start
 * the guard can empty the first pages with the end of the list already on screen, and then nothing
 * runs onScroll and Home waits for a swipe. So onScroll hands the extension the fetcher and what it
 * was given first thing, and the fetcher gets a method the extension calls to run onScroll again
 * with the same.
 */

/** Kept literal: the trace section onScroll opens with, which no other method loads. */
internal const val SCROLL_FETCHER_TRACE = "NewsFeedListViewScrollFetcher.onScroll"

/** Kept literal: the log line of the ask onScroll makes. */
internal const val DISPATCH_TAIL_FETCH = "dispatchTailFetch"

/** Kept literal: the value the ask logs with its line, whether the list is at its end. */
internal const val TAIL_VISIBLE = "isTailVisible"

internal const val ASK_AGAIN = "$EXTENSION_PACKAGE/feed/TailFetch;->askAgain(II)Z"

/** What onScroll hands the extension first thing: the fetcher, the list and its three rows. */
internal const val SCROLLED = "$EXTENSION_PACKAGE/feed/TailFetch;->scrolled(Ljava/lang/Object;Ljava/lang/Object;III)V"

/** The extension's view of the fetcher, which the patch makes it implement. */
internal const val SCROLLER = "$EXTENSION_PACKAGE/feed/TailFetch\$Scroller;"

/** The method the fetcher gets, which runs onScroll again with what it's handed. */
internal const val SCROLL_AGAIN = "hushfacebookScroll"

/** How a refusal of the tail fetch hook names it. */
private const val TAIL_FETCH = "Feed filter (tail fetch)"

/**
 * Where the tail fetch hook goes in onScroll, [method]: [index], the first instruction of the branch
 * that doesn't fetch; [ask], the first instruction of the ask; [fetcher], the register holding the
 * fetcher there; [lastRow] and [rows], the fetcher's two fields the ask's "isTailVisible" reads.
 */
internal data class TailFetchSite(
    val method: Method,
    val index: Int,
    val ask: Int,
    val fetcher: Int,
    val lastRow: FieldReference,
    val rows: FieldReference,
)

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/**
 * The tail fetch site in [owner], or null when it isn't the scroll fetcher: onScroll, an instance
 * (object, int, int, int) method holding [SCROLL_FETCHER_TRACE] and one [DISPATCH_TAIL_FETCH] log,
 * reached through one forward if-nez whose other way is the branch that doesn't fetch.
 */
internal fun tailFetchSite(owner: ClassDef): TailFetchSite? {
    val onScroll = owner.methods.singleOrNull {
        it.returnType == "V" && !AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.size == 4 && it.parameterTypes.drop(1).all { type -> type.toString() == "I" } &&
            holdsString(it, SCROLL_FETCHER_TRACE)
    } ?: return null
    val code = onScroll.implementation?.instructions?.toList() ?: return null

    val log = code.indices.singleOrNull { code[it].string() == DISPATCH_TAIL_FETCH } ?: return null
    // The branch into the ask: the last if-nez before its log that jumps forward to a point before it.
    val flow = ControlFlow.of(onScroll)
    val branch = (log - 1 downTo 0).firstOrNull { at ->
        code[at].opcode == Opcode.IF_NEZ && flow.normal[at].any { it != at + 1 && it in at + 2..log }
    } ?: return null
    val ask = flow.normal[branch].single { it != branch + 1 }

    // The ask opens by reading the fetcher's data loader off the fetcher.
    val first = code[ask]
    if (first.opcode != Opcode.IGET_OBJECT) return null
    val loader = (first as ReferenceInstruction).reference as FieldReference
    val fetcher = (first as TwoRegisterInstruction).registerB
    if (loader.definingClass != owner.type) return null

    // Its "isTailVisible" value comes from the last row on screen and the rows, both off the fetcher.
    val tail = (ask until code.size).firstOrNull { code[it].string() == TAIL_VISIBLE } ?: return null
    val reads = (tail until code.size).map { code[it] }.filter {
        it.opcode == Opcode.IGET && (it as TwoRegisterInstruction).registerB == fetcher
    }.take(2).map { (it as ReferenceInstruction).reference as FieldReference }
    if (reads.size != 2 || reads.any { it.definingClass != owner.type || it.type != "I" }) return null

    return TailFetchSite(onScroll, branch + 1, ask, fetcher, reads[0], reads[1])
}

/**
 * The scroll fetcher's tail fetch site: exactly one class holding [SCROLL_FETCHER_TRACE] answers
 * [tailFetchSite]. Without it the hook would have nowhere to go, and with two it could go on the
 * wrong one, so either stops the patch.
 */
internal fun BytecodePatchContext.tailFetchSite(): TailFetchSite {
    val holders = classDefByStrings(SCROLL_FETCHER_TRACE, StringComparisonType.EQUALS)
    val sites = holders.mapNotNull(::tailFetchSite)
    return sites.singleOrNull() ?: throw PatchException(
        "$TAIL_FETCH: expected one scroll fetcher whose onScroll holds \"$SCROLL_FETCHER_TRACE\" and asks " +
            "through \"$DISPATCH_TAIL_FETCH\", found ${sites.size} among ${holders.count()} class(es) holding the string.",
    )
}

/**
 * In front of onScroll's branch that doesn't fetch, hands the extension the last row on screen and
 * the rows, and goes to the ask when it says yes. The code goes on the branch's control flow label,
 * so every way into the branch runs it. Its two borrowed registers are free locals no higher than
 * v15, by liveness at the branch and at the ask.
 */
internal fun MutableMethod.askAgainAtTail(site: TailFetchSite) {
    if (site.fetcher > 15) throw PatchException("$TAIL_FETCH: the fetcher is in v${site.fetcher}, above v15")
    val (lastRow, rows) = freeLocalsAt(TAIL_FETCH, site.index, 2, listOf(site.ask))
    val fetcher = "v${site.fetcher}"
    addInstructionsAtControlFlowLabel(
        site.index,
        """
            iget v$lastRow, $fetcher, ${site.lastRow}
            iget v$rows, $fetcher, ${site.rows}
            invoke-static { v$lastRow, v$rows }, $ASK_AGAIN
            move-result v$lastRow
            if-nez v$lastRow, :ask
        """,
        ExternalLabel("ask", getInstruction(site.ask)),
    )
}

/**
 * Lets the extension run [onScroll] again with no scroll: [onScroll] opens by handing [SCROLLED] the
 * fetcher and its four arguments, and the fetcher implements [SCROLLER] with a [SCROLL_AGAIN] that
 * casts the list back and calls [onScroll] with them. Goes on after [askAgainAtTail], whose indices
 * are the stock ones. Nothing in onScroll jumps back to its first instruction (the fixture test
 * reads that), so the hook runs once for each call.
 */
internal fun MutableClass.letScrollAgain(onScroll: MutableMethod) {
    val parameters = onScroll.parameterTypes.map { it.toString() }
    if (parameters.size != 4 || parameters.drop(1).any { it != "I" }) {
        throw PatchException("$TAIL_FETCH: $type's onScroll takes $parameters, not a list and three rows")
    }
    if (methods.any { it.name == SCROLL_AGAIN }) throw PatchException("$TAIL_FETCH: $type already has a $SCROLL_AGAIN")

    onScroll.addInstructions(0, "invoke-static/range { p0 .. p4 }, $SCROLLED")

    if (SCROLLER !in interfaces) interfaces.add(SCROLLER)
    val invoke = if (AccessFlags.PRIVATE.isSet(onScroll.accessFlags)) "invoke-direct" else "invoke-virtual"
    val target = "$type->${onScroll.name}(${parameters.joinToString("")})V"
    ImmutableMethod(
        type,
        SCROLL_AGAIN,
        listOf("Ljava/lang/Object;", "I", "I", "I").map { ImmutableMethodParameter(it, null, null) },
        "V",
        AccessFlags.PUBLIC.value,
        null,
        null,
        MutableMethodImplementation(5),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                check-cast p1, ${parameters[0]}
                $invoke { p0, p1, p2, p3, p4 }, $target
                return-void
            """,
        )
        methods.add(this)
    }
}

/**
 * Keeps Home asking for posts when the feed guard empties a page (TailFetch in the extension). Every
 * feed patch brings it with the guard, so it runs only where something can hide an edge, and the
 * extension asks only after one was hidden and never while paused.
 */
internal val homeTailFetchPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        val site = tailFetchSite()
        val fetcher = mutableClassDefBy(site.method.definingClass)
        val onScroll = fetcher.methods.single {
            it.name == site.method.name && it.returnType == "V" &&
                it.parameterTypes.map { type -> type.toString() } == site.method.parameterTypes.map { type -> type.toString() }
        }
        onScroll.askAgainAtTail(site)
        fetcher.letScrollAgain(onScroll)
    }
}
