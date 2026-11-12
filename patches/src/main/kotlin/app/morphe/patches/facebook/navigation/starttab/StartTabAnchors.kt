/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.starttab

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.settings.MAIN_TAB_ACTIVITY
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.patches.facebook.shared.readsMobileConfig
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * How Facebook picks the tab its main screen opens on, on the 577 and 580 builds.
 *
 * The launcher icon starts com.facebook.katana.activity.FbMainTabActivity, through the manifest's
 * LoginActivity alias. Its tabs are subclasses of com.facebook.navigation.tabbar.state.model.TabTag
 * (FeedTab, WatchTab, FriendRequestsTab, MarketplaceTab, NotificationsTab, BookmarkTab, FeedsTab
 * and more), names Redex keeps, and each hands TabTag's constructor a long: the tab's bookmark id,
 * 1606854132932955 for Marketplace on both builds. The extension's FacebookTabs holds the ids.
 *
 * One method picks the start tab from an intent. It takes the context, the intent and the user
 * session and answers a tab id. When the intent carries the extra "target_tab_id" and the tab bar
 * has that tab, that's the answer. When the bar doesn't have it, the answer is the bar's first
 * tab, Home. With no such extra, the answer comes from Facebook's own rules. The kept
 * com.facebook.startup.destination.StartupDestinationRouter and several warm-up jobs ask it too,
 * but only to decide what to fetch early.
 *
 * The picker's answer doesn't decide the tab on a cold start by itself. The main screen's
 * creation runs a list of named start-up steps, and three of them stand between the launcher's
 * intent and the tab that shows:
 *
 * 1. "sanitize_intent" replaces the intent of a start another app sent with a new one that keeps
 *    only its action, its link and three extras, unless it carries a PendingIntent Facebook made or
 *    has no extras. A launcher is another app, so the tab asked for goes with the rest. Facebook's
 *    own tab shortcuts put the tab in a link for that reason, and its notifications carry the
 *    PendingIntent. The patch hands the step's copy to the extension, which asks for the tab
 *    again when it asked for one in the first place. Up to 584 the step ran inside the dispatcher
 *    that compares step names; 585 moves its body into a method of its own the case calls.
 * 2. "setup_main_view_controllers" asks the picker about the (now sanitized) intent, keeps the
 *    answer as the main screen's start tab and gives it to the tab bar. The tab bar's
 *    "TabBarController.determineStartingTabPosition" then uses it only when one of Facebook's
 *    MobileConfig booleans says so, and otherwise opens on the configuration's own first tab. On
 *    an account where that setting is off, a notification or shortcut that reaches a tab does it
 *    later, through the same deep link route onNewIntent takes. The patch puts the extension after
 *    that boolean read, which answers yes for a start it asked a tab for.
 * 3. After the tab bar is set up, the main screen puts its own start tab back to the default
 *    unless a static check agrees the intent asked for exactly that tab (and two more of
 *    Facebook's settings allow it). The patch passes that check's answer through the extension
 *    the same way, so the screen's idea of its start tab matches the tab that shows. 585 runs the
 *    check inline in the dispatcher, so there the patch asks where its no resets the start tab.
 *
 * None of this necessarily runs inside Android's onCreate. FbFragmentActivity.onCreate, where the
 * route hook goes first, hands on to the screen's delegate, and on a cold start the main screen's
 * instantiateDelegateImpl can hand out a stand-in that queues every call ("product_delegate_enqueued_")
 * behind a splash screen until the app is ready. Android then creates and resumes the screen, and
 * the queue replays the real delegate's onCreate, with the three steps above, afterwards.
 *
 * Every other start keeps Facebook's own answers: the extension says yes only while the main
 * screen it asked a tab for is being built, decided from that screen's own intent, and only for a
 * plain start from the launcher icon. The MobileConfig ids differ between builds, so no hook
 * anchors on one.
 */
internal const val PATCH = "Open on a chosen tab"

/** The intent extra Facebook's main screen opens a tab by. */
internal const val TARGET_TAB_ID = "target_tab_id"

internal const val INTENT = "Landroid/content/Intent;"

/** The extension's hook, first thing in every Facebook activity's onCreate. */
internal const val ROUTE =
    "$EXTENSION_PACKAGE/navigation/StartTabRoute;->onActivityCreate(Landroid/app/Activity;Landroid/os/Bundle;)V"

/** The base class of every tab. Redex keeps the name. */
internal const val TAB_TAG = "Lcom/facebook/navigation/tabbar/state/model/TabTag;"

/** The router that predicts a start's destination, and asks the picker about the main screen's. */
internal const val STARTUP_DESTINATION_ROUTER = "Lcom/facebook/startup/destination/StartupDestinationRouter;"

/** The trace section the tab bar opens while it works out the position its main screen starts on. */
internal const val START_POSITION = "TabBarController.determineStartingTabPosition"

/** The name of the start-up step that sanitizes the main screen's intent. */
internal const val SANITIZE_INTENT = "sanitize_intent"

/** The last of the three extras the sanitizing step copies over, right before it hands the copy over. */
internal const val KEPT_EXTRA = "app_switch_source_account"

/** The extension's answer at the tab bar's start position gate. */
internal const val START_ON_ASKED_TAB = "$EXTENSION_PACKAGE/navigation/StartTabRoute;->startOnAskedTab(Z)Z"

/** The extension's answer at the main screen's check that it keeps its start tab. */
internal const val KEEP_ASKED_START_TAB = "$EXTENSION_PACKAGE/navigation/StartTabRoute;->keepAskedStartTab(Z)Z"

/** The extension's stand-in for the sanitizing step's Activity.setIntent. */
internal const val SET_SANITIZED_INTENT =
    "$EXTENSION_PACKAGE/navigation/StartTabRoute;->setSanitizedIntent(Landroid/app/Activity;Landroid/content/Intent;)V"

/**
 * The extension's hook first thing in the Feeds tab's onBackPressed. It answers the tab when
 * Facebook's own Back should run, and null when it closed the Feeds page a start opened.
 */
internal const val FEEDS_PAGE_BACK =
    "$EXTENSION_PACKAGE/navigation/StartTabRoute;->feedsPageBack(Ljava/lang/Object;)Ljava/lang/Object;"

/** How far past the gate's branch the start tab lookup may sit: the long, the tab bar state, its cast, the lookup. */
private const val LOOKUP_WINDOW = 8

/** How far past the last kept extra the sanitizing step hands the copy over. */
private const val HAND_OVER_WINDOW = 8

/** How far before the kept extras the copy is built: action, link, the copy, and the first two extras. */
private const val COPY_WINDOW = 24

private const val ACTIVITY = "Landroid/app/Activity;"

private val Instruction.call: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.parameters() = parameterTypes.map { it.toString() }

/** The registers a call reads, in order, whether it's written as a range or not. */
internal fun Instruction.callRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Method.calls(definingClass: String, name: String, parameters: List<String>, returnType: String) =
    implementation?.instructions?.any { instruction ->
        val call = instruction.call ?: return@any false
        call.definingClass == definingClass && call.name == name && call.returnType == returnType &&
            call.parameters() == parameters
    } == true

/** Whether [method] reads a long field of its own class. */
private fun readsOwnLong(method: Method) = method.implementation?.instructions?.any {
    it.opcode == Opcode.IGET_WIDE && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
        field.definingClass == method.definingClass && field.type == "J"
    }
} == true

/**
 * Whether [method] is Facebook's start tab picker: it answers a long, takes an intent, loads
 * "target_tab_id", asks the intent whether it has that extra and reads it as a long. The
 * extension's own classes, which load the same literal to write it, don't count.
 */
internal fun picksStartTab(method: Method): Boolean =
    !method.definingClass.startsWith(EXTENSION_PACKAGE) &&
        method.returnType == "J" &&
        method.parameterTypes.any { it.toString() == INTENT } &&
        holdsString(method, TARGET_TAB_ID) &&
        method.calls(INTENT, "hasExtra", listOf("Ljava/lang/String;"), "Z") &&
        method.calls(INTENT, "getLongExtra", listOf("Ljava/lang/String;", "J"), "J")

/**
 * Where [method] is the tab bar's start position gate, the index of the `move-result` that keeps
 * the gate's answer, or null when it isn't.
 *
 * The method takes one boolean, returns nothing and opens the trace section [START_POSITION]. The
 * gate is its first MobileConfig boolean read (a static call up to 581, the config's interface
 * method on a cast in 582's `LX/2Fo;->A06`). Its answer goes into a register the next
 * instruction branches on, and when the answer is yes, the code reads the start tab its own class
 * was built with, a long, and hands that long to a lookup that answers the tab's position as an
 * Integer. That's what the extension's yes turns on.
 */
internal fun startPositionGate(method: Method): Int? {
    if (method.returnType != "V" || method.parameterTypes.map { it.toString() } != listOf("Z")) return null
    if (!holdsString(method, START_POSITION)) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val call = code.indexOfFirst { readsMobileConfig(it, "Z") }
    if (call < 0) return null
    val result = code.getOrNull(call + 1)
    if (result?.opcode != Opcode.MOVE_RESULT) return null
    val answer = (result as OneRegisterInstruction).registerA
    val branch = code.getOrNull(call + 2)
    if (branch?.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != answer) return null
    val read = code.getOrNull(call + 3)
    if (read?.opcode != Opcode.IGET_WIDE) return null
    val field = (read as ReferenceInstruction).reference as FieldReference
    if (field.definingClass != method.definingClass || field.type != "J") return null
    val startTab = (read as TwoRegisterInstruction).registerA
    val lookup = code.subList(call + 4, minOf(code.size, call + 4 + LOOKUP_WINDOW)).firstOrNull {
        val target = it.call
        target != null && target.parameters() == listOf("J") && target.returnType == "Ljava/lang/Integer;"
    } ?: return null
    val receiver = if (lookup.opcode == Opcode.INVOKE_STATIC || lookup.opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1
    if (lookup.callRegisters().getOrNull(receiver) != startTab) return null
    return call + 1
}

/**
 * Whether [method] is the main screen's check that it keeps the start tab it picked: static,
 * answers a boolean, takes the main screen and its own class, reads the long the intent's
 * "target_tab_id" holds and a long its own class keeps.
 */
internal fun keepsAskedStartTab(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
        method.parameterTypes.map { it.toString() } == listOf(MAIN_TAB_ACTIVITY, method.definingClass) &&
        holdsString(method, TARGET_TAB_ID) &&
        method.calls(INTENT, "getLongExtra", listOf("Ljava/lang/String;", "J"), "J") &&
        readsOwnLong(method)

/** How far before the read of the intent's start tab the extra's name may be loaded: hasExtra, its answer and branch, the default. */
private const val TAB_EXTRA_WINDOW = 8

/**
 * Where [method] runs the main screen's keep check inline, the index of the `iput-wide` that puts
 * the screen's start tab back to the default when the check says no, or null when it doesn't.
 *
 * 585 no longer has 584's static check (`LX/1ym;->A09(FbMainTabActivity;LX/1ym;)Z`): the start-up
 * dispatcher (`LX/03HL;->A00` arm64) runs its body in place. It reads the intent's
 * "target_tab_id" as a long, compares it with the start tab the screen keeps
 * (`LX/028u;->A01:J`), and when they differ branches to `iput-wide` of the default into that
 * field, the same instruction the check's other no answers and its MobileConfig gates reach. A
 * yes jumps to the instruction after it.
 */
internal fun inlineKeepReset(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val resets = code.indices.mapNotNull { index ->
        val call = code[index].call ?: return@mapNotNull null
        if (call.definingClass != INTENT || call.name != "getLongExtra" || call.returnType != "J" ||
            call.parameters() != listOf("Ljava/lang/String;", "J")
        ) return@mapNotNull null
        // The extra read has to be the start tab's: its name register was loaded with "target_tab_id" just before.
        val name = code[index].callRegisters().getOrNull(1) ?: return@mapNotNull null
        val named = (maxOf(0, index - TAB_EXTRA_WINDOW) until index).any {
            val load = code[it]
            (load.opcode == Opcode.CONST_STRING || load.opcode == Opcode.CONST_STRING_JUMBO) &&
                (load as OneRegisterInstruction).registerA == name &&
                ((load as ReferenceInstruction).reference as StringReference).string == TARGET_TAB_ID
        }
        if (!named) return@mapNotNull null
        val asked = code.getOrNull(index + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_WIDE } ?: return@mapNotNull null
        // The check compares the asked tab with a long field the screen keeps, and differing branches away.
        val kept = code.getOrNull(index + 2)?.takeIf { it.opcode == Opcode.IGET_WIDE } ?: return@mapNotNull null
        val field = (kept as ReferenceInstruction).reference as FieldReference
        if (field.type != "J") return@mapNotNull null
        val compare = code.getOrNull(index + 3)?.takeIf { it.opcode == Opcode.CMP_LONG } as? ThreeRegisterInstruction
            ?: return@mapNotNull null
        val longs = setOf(compare.registerB, compare.registerC)
        if (longs != setOf((asked as OneRegisterInstruction).registerA, (kept as TwoRegisterInstruction).registerA)) {
            return@mapNotNull null
        }
        val branch = code.getOrNull(index + 4)
        if (branch?.opcode != Opcode.IF_NEZ || (branch as OneRegisterInstruction).registerA != compare.registerA) {
            return@mapNotNull null
        }
        val reset = branchTarget(code, index + 4) ?: return@mapNotNull null
        // Where the no lands puts the default back into that same field of that same object.
        val write = code[reset].takeIf { it.opcode == Opcode.IPUT_WIDE } ?: return@mapNotNull null
        val written = (write as ReferenceInstruction).reference as FieldReference
        val same = written.definingClass == field.definingClass && written.name == field.name && written.type == "J" &&
            (write as TwoRegisterInstruction).registerB == (kept as TwoRegisterInstruction).registerB
        if (same && reset + 1 < code.size) reset else null
    }
    return resets.distinct().singleOrNull()
}

/**
 * Where [method] runs the start-up step [SANITIZE_INTENT], the index of the `Activity.setIntent`
 * that hands the main screen the sanitized copy of its intent, or null when it doesn't.
 *
 * The step builds the copy as a new Intent from an action and a link, copies over three extras,
 * [KEPT_EXTRA] last, and then hands it over: the first `setIntent` after that extra is loaded, on
 * the register the copy was built in.
 */
internal fun sanitizedIntentHandOver(method: Method): Int? =
    if (holdsString(method, SANITIZE_INTENT)) sanitizedCopyHandOver(method) else null

/** What ends a case of the start-up dispatcher: the jump to its shared end, or a return. */
private val CASE_ENDS = setOf(
    Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32, Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT,
    Opcode.RETURN_WIDE, Opcode.THROW,
)

/**
 * The calls [method] makes in the start-up dispatcher's case for [SANITIZE_INTENT]: from the
 * `String.equals` that compares the step's name to the end of its case.
 *
 * 585 (`LX/03HL;->A00` arm64, about 6,000 instructions) no longer runs the step inline as 584's
 * `LX/3UL;->A00` did: its case only fetches a holder and calls `LX/01i1;->A00(FragmentActivity)V`,
 * the step's body, which builds and hands over the copy exactly as before.
 */
internal fun sanitizeStepCalls(method: Method): List<MethodReference> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    // A case starts by comparing the step's name, so a load of the literal elsewhere doesn't count.
    val starts = code.indices.filter { index ->
        ((code[index] as? ReferenceInstruction)?.reference as? StringReference)?.string == SANITIZE_INTENT &&
            code.getOrNull(index + 1)?.call?.let { it.definingClass == "Ljava/lang/String;" && it.name == "equals" } == true
    }
    return starts.flatMap { start ->
        val end = (start + 2 until code.size).firstOrNull { code[it].opcode in CASE_ENDS } ?: code.size
        (start + 2 until end).mapNotNull { code[it].call }
    }
}

/** The method of [owner] that [call] names, if it has code. */
internal fun stepBody(owner: ClassDef, call: MethodReference): Method? = owner.methods.firstOrNull {
    it.implementation != null && it.name == call.name && it.returnType == call.returnType &&
        it.parameterTypes.map(CharSequence::toString) == call.parameters()
}

/** [sanitizedIntentHandOver] without the step's name: the copy's hand-over alone, for a step body moved out of the dispatcher. */
internal fun sanitizedCopyHandOver(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val kept = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == KEPT_EXTRA }
    if (kept < 0) return null
    val set = (kept + 1 until minOf(code.size, kept + 1 + HAND_OVER_WINDOW)).firstOrNull { index ->
        val call = code[index].call
        (code[index].opcode == Opcode.INVOKE_VIRTUAL || code[index].opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            call != null && call.definingClass == ACTIVITY && call.name == "setIntent" &&
            call.parameters() == listOf(INTENT) && call.returnType == "V"
    } ?: return null
    val copy = code[set].callRegisters().getOrNull(1) ?: return null
    val built = (maxOf(0, kept - COPY_WINDOW) until kept).any { index ->
        val call = code[index].call
        code[index].opcode == Opcode.INVOKE_DIRECT && call != null && call.definingClass == INTENT &&
            call.name == "<init>" && call.parameters() == listOf("Ljava/lang/String;", "Landroid/net/Uri;") &&
            code[index].callRegisters().firstOrNull() == copy
    }
    return if (built) set else null
}
