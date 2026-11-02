/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.tabbar

import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.navigation.starttab.TAB_TAG
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * How Facebook builds the tab bar, on the 577 and 580 builds.
 *
 * The account's tab bar state keeps two lists. NavigationConfig, a class Redex keeps, holds the
 * configured tabs in order, from Facebook's servers: on the test account FeedTab, WatchTab,
 * FriendRequestsTab, MarketplaceTab, NotificationsTab and TimelineTab (Profile). Its int is the
 * index of the tab a start opens on when nothing else decides, and it's always 0. The list the
 * tab bar, its ViewPager, the start tab picker and the tab switches read is a second one, which a
 * static method of the state class builds from the first: for each configured tab it asks a set of
 * tab ids (the tabs hidden in Facebook's own Settings, Tab bar, Customize the bar) whether it has
 * the tab's id as a string, and adds the tab to an ImmutableList.Builder when it hasn't. That's
 * how hiding Reels there takes the tab off at once. The method loads no strings.
 *
 * The tab bar filter asks the extension there too, right after Facebook's own set answers, so the
 * tabs Marketplace only or Hide the Reels tab drop go the same way a hidden Reels tab does. It's
 * one call for every patch that drops tabs, since a second call put in after the first would find
 * the answer's branch no longer right behind the set's answer. Home can't be hidden in Facebook's
 * editor, but Facebook's code looks tabs up by id and checks the answer: switching to Home, the back
 * press that returns to Home and the "reset to feed" of a notification all find no Home and leave
 * the current tab, and a start or a link asking for a tab the bar hasn't got opens the bar's first
 * tab. Nothing builds the feed's page when the bar has no Home, though the start-up still warms
 * the feed's data in the background. The older top navigation bar looks the Video tab up in the
 * shown list the same way and keeps -1 when it's gone. Links, Menu shortcuts and notifications ask
 * the configured list instead, which a dropped tab is still in: see TabLinkAnchors.kt.
 */
internal const val TAB_BAR_FILTER = "Tab bar filter"

/** The class of the tab bar's configured tabs and start index. Redex keeps the name. */
internal const val NAVIGATION_CONFIG = "Lcom/facebook/navigation/tabbar/state/model/NavigationConfig;"

internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
internal const val LIST_BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"

/** Guava keeps its names, so the builder's add is known by its whole signature. */
private const val LIST_BUILDER_ADD = "$LIST_BUILDER->add(Ljava/lang/Object;)$LIST_BUILDER"

/** Guava's copy of any list into an ImmutableList, which the reordered tabs go back through. */
internal const val IMMUTABLE_LIST_COPY_OF = "$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST"

/** The extension's order for the shown tabs, once the builder has its list (#341). */
internal const val TAB_ORDER_CALL = "$EXTENSION_PACKAGE/navigation/TabOrder;->order(Ljava/util/List;)Ljava/util/List;"

/** The extension's answer after Facebook's hidden-tab set has answered for one tab. */
internal const val HIDES_TAB =
    "$EXTENSION_PACKAGE/navigation/TabBarFilter;->hidesTab(ZLjava/lang/Object;Ljava/util/List;Ljava/util/Set;)Z"

/** Where the shown-tab list's builder asks the hidden set, and the registers the hook reads there. */
internal data class TabFilter(
    /** The `move-result` that keeps the hidden set's answer. */
    val result: Int,
    val answer: Int,
    val tab: Int,
    val configured: Int,
    val hidden: Int,
)

internal val Instruction.call: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

internal fun MethodReference.signature() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** The registers a call reads, in order, whether it's written as a range or not. */
internal fun Instruction.callRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** Whether [instruction] can write [register], a wide write counting for both halves. */
internal fun writes(instruction: Instruction, register: Int): Boolean {
    val opcode = instruction.opcode
    if (!opcode.setsRegister() || instruction !is OneRegisterInstruction) return false
    val first = instruction.registerA
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

/**
 * Where [method] builds the tab bar's shown tabs from the configured ones, or null when it doesn't.
 *
 * The method is static, returns nothing and takes its own class. It reads the configured tabs, the
 * ImmutableList of a NavigationConfig, casts each to TabTag, reads the tab's long id, turns it into
 * a String and asks a Set whether it contains it. The answer's `move-result` is followed by an
 * `if-nez` on it that skips the one ImmutableList.Builder.add of the same tab. The tab, the
 * configured list and the set are still in their registers where the answer is kept, and none of
 * them is the answer's register.
 */
internal fun shownTabFilter(method: Method): TabFilter? {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V") return null
    if (method.parameterTypes.map { it.toString() } != listOf(method.definingClass)) return null
    val code = method.implementation?.instructions?.toList() ?: return null

    val reads = code.withIndex().filter { (_, it) ->
        it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
            field.definingClass == NAVIGATION_CONFIG && field.type == IMMUTABLE_LIST
        }
    }
    val casts = code.withIndex().filter { (_, it) ->
        it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == TAB_TAG
    }
    val asks = code.withIndex().filter { (_, it) ->
        it.opcode == Opcode.INVOKE_INTERFACE && it.call?.signature() == "Ljava/util/Set;->contains(Ljava/lang/Object;)Z"
    }
    val adds = code.withIndex().filter { (_, it) ->
        it.call?.signature() == LIST_BUILDER_ADD
    }
    if (reads.size != 1 || casts.size != 1 || asks.size != 1 || adds.size != 1) return null
    val (read, cast, ask, add) = listOf(reads, casts, asks, adds).map { it.single().index }
    if (!(read < cast && cast < ask && ask + 3 == add)) return null
    if (code.subList(cast, ask).none { it.call?.signature() == "Ljava/lang/String;->valueOf(J)Ljava/lang/String;" }) {
        return null
    }

    val result = code[ask + 1]
    if (result.opcode != Opcode.MOVE_RESULT) return null
    val answer = (result as OneRegisterInstruction).registerA
    val branch = code[ask + 2]
    if (branch.opcode != Opcode.IF_NEZ || (branch as OneRegisterInstruction).registerA != answer) return null

    val tab = (code[cast] as OneRegisterInstruction).registerA
    val configured = (code[read] as OneRegisterInstruction).registerA
    val hidden = code[ask].callRegisters().first()
    // The builder adds the very tab the set was asked about.
    if (code[add].callRegisters().getOrNull(1) != tab) return null
    // Nothing between the cast and the answer writes the tab, and nothing between the list's
    // read and the answer writes the list. The set is the call's own receiver, so it's there.
    if (code.subList(cast + 1, ask + 1).any { writes(it, tab) }) return null
    if (code.subList(read + 1, ask + 1).any { writes(it, configured) }) return null
    if (answer in setOf(tab, configured, hidden)) return null
    return TabFilter(ask + 1, answer, tab, configured, hidden)
}

/**
 * Where [method] keeps the shown tabs it built, or null when it isn't the shown-tab builder: the
 * index of its one `iput-object` of an ImmutableList into its own class, after the builder's one
 * add. It's the builder [shownTabFilter] finds, told apart without the set's answer, since the
 * tab bar filter's call sits between that answer and the add once it's in. On 585 the builder
 * ends in `ImmutableList list = builder.build(); this.shown = list;` inside its monitor.
 */
internal fun shownTabStore(method: Method): Int? {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V") return null
    if (method.parameterTypes.map { it.toString() } != listOf(method.definingClass)) return null
    val code = method.implementation?.instructions?.toList() ?: return null

    val reads = code.withIndex().filter { (_, it) ->
        it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
            field.definingClass == NAVIGATION_CONFIG && field.type == IMMUTABLE_LIST
        }
    }
    val casts = code.withIndex().filter { (_, it) ->
        it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == TAB_TAG
    }
    val adds = code.withIndex().filter { (_, it) -> it.call?.signature() == LIST_BUILDER_ADD }
    val stores = code.withIndex().filter { (_, it) ->
        it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
            field.definingClass == method.definingClass && field.type == IMMUTABLE_LIST
        }
    }
    if (reads.size != 1 || casts.size != 1 || adds.size != 1 || stores.size != 1) return null
    val (read, cast, add, store) = listOf(reads, casts, adds, stores).map { it.single().index }
    if (!(read < cast && cast < add && add < store)) return null
    return store
}

