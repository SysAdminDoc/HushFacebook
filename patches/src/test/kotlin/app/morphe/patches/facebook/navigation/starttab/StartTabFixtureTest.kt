/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.starttab

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.feedsheader.FEED_FILTERS_FRAGMENT
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.settings.MAIN_TAB_ACTIVITY
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route Open on a chosen tab asks by, on every Facebook build the bundle declares: one method
 * picks the main screen's start tab from an intent's "target_tab_id", checks the tab bar has it
 * and otherwise answers a tab of the bar's own; the kept start-up router asks it about the main
 * screen; each tab the extension offers is the kept TabTag subclass whose constructor hands TabTag
 * the id the extension asks for; each of the three start-up steps the patch hooks is there once,
 * in the shape it's found by; and what the landing check reads is there under the names it reads.
 * Then the patch itself, run on the build's own main screen classes and start-up steps, with each
 * call where it belongs. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class StartTabFixtureTest {
    private val facebookTabs = "Lapp/morphe/extension/facebook/navigation/FacebookTabs;"
    private val fragmentActivity = "Lcom/facebook/base/activity/FbFragmentActivity;"
    private val delegate = "Lcom/facebook/katana/activity/FbMainTabActivityDelegate;"
    private val navigationConfig = "Lcom/facebook/navigation/tabbar/state/model/NavigationConfig;"
    private val bundle = "Landroid/os/Bundle;"

    /** The marker a queueing stand-in delegate sets as it queues a call of the main screen's. */
    private val queuedDelegateWork = "product_delegate_enqueued_"
    private val wideConstants = setOf(Opcode.CONST_WIDE, Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE_HIGH16)

    /** The feed types the extension's FeedsSubtab asks for, by the names Facebook keeps. */
    private val subtabFeedTypes = listOf("favorites", "most_recent_favorites", "most_recent_friend",
        "most_recent_group", "most_recent_page")

    /** The tabs the extension offers, by the class Facebook keeps, with the id it asks for. */
    private val offered: Map<String, Long> by lazy {
        fun descriptor(javaName: String) = "L" + javaName.replace('.', '/') + ";"
        val tabs = listOf("HOME", "FEEDS", "VIDEO", "FRIENDS", "MARKETPLACE", "NOTIFICATIONS", "MENU").associate { tab ->
            descriptor(ExtensionDex.stringConstant(facebookTabs, "${tab}_CLASS")) to
                ExtensionDex.longConstant(facebookTabs, "${tab}_ID")
        }
        tabs + (descriptor(ExtensionDex.stringConstant(facebookTabs, "MOST_RECENT_CLASS")) to
            ExtensionDex.longConstant(facebookTabs, "FEEDS_ID"))
    }

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    /** The registers a call reads, in order, whether it's written as a range or not. */
    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun code(method: Method) = method.implementation!!.instructions.toList()

    /** How many branches in [code] land on instruction [target]. */
    private fun branchesOnto(code: List<Instruction>, target: Int): Int {
        val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
        return code.indices.count { index ->
            val branch = code[index] as? OffsetInstruction
            branch != null && addresses[index] + branch.codeOffset == addresses[target]
        }
    }

    /**
     * Whether [tab]'s constructor hands TabTag's constructor [id] as the tab's id: the one long
     * TabTag takes is loaded with it, into the registers the call passes the long in.
     */
    private fun handsItsId(tab: ClassDef, id: Long): Boolean = tab.methods.filter { it.name == "<init>" }.any { init ->
        val body = code(init)
        body.any { instruction ->
            val call = instruction.call ?: return@any false
            if (call.definingClass != TAB_TAG || call.name != "<init>") return@any false
            val types = call.parameterTypes.map { it.toString() }
            if (types.count { it == "J" } != 1) return@any false
            val at = 1 + types.takeWhile { it != "J" }.map { if (it == "D") 2 else 1 }.sum()
            val register = instruction.registers().getOrNull(at) ?: return@any false
            body.any {
                it.opcode in wideConstants && (it as OneRegisterInstruction).registerA == register &&
                    (it as WideLiteralInstruction).wideLiteral == id
            }
        }
    }

    @Test
    fun `each declared build opens the tab an intent's target_tab_id names, and the patch goes in on its main screen`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        assertEquals("the tabs the extension offers", 8, offered.size)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name

                // One picker, shaped (context, intent, session, callback) -> tab id. 586 added the
                // callback; the patch only needs the picker to be there.
                val pickers = FixtureDex.methodsWhere(fixture, { dex -> dex.stringSection.any { it == TARGET_TAB_ID } }, ::picksStartTab)
                assertEquals("$name: start tab pickers", 1, pickers.size)
                val picker = pickers.single()
                assertEquals(
                    "$name: the picker's parameters",
                    listOf("Landroid/content/Context;", INTENT, "Lcom/facebook/auth/usersession/FbUserSession;",
                        "Lkotlin/jvm/functions/Function1;"),
                    picker.parameterTypes.map { it.toString() },
                )
                val pickerCode = code(picker)
                // When the extra names a tab the bar hasn't got, the answer is a tab of the bar's
                // own, by TabTag's id: the picker reads that long off a TabTag.
                assertTrue(
                    "$name: the picker answers no TabTag's id",
                    pickerCode.any { instruction ->
                        instruction.opcode == Opcode.IGET_WIDE &&
                            ((instruction as ReferenceInstruction).reference as FieldReference).let {
                                it.definingClass == TAB_TAG && it.type == "J"
                            }
                    },
                )

                // The three start-up steps the patch hooks, one of each, found in one pass.
                val handOvers = mutableListOf<Pair<Method, Int>>()
                val gates = mutableListOf<Pair<Method, Int>>()
                val checks = mutableListOf<Method>()
                val inlineChecks = mutableListOf<Pair<Method, Int>>()
                val queues = mutableSetOf<String>()
                val stepCalls = mutableListOf<Pair<String, MethodReference>>()
                FixtureDex.forEach(fixture) { dex ->
                    val strings = dex.stringSection.toHashSet()
                    val sanitizes = SANITIZE_INTENT in strings
                    val traces = START_POSITION in strings
                    val targets = TARGET_TAB_ID in strings
                    val queued = queuedDelegateWork in strings
                    if (!sanitizes && !traces && !targets && !queued) return@forEach
                    for (classDef in dex.classes) {
                        if (queued && classDef.methods.any { holdsString(it, queuedDelegateWork) }) queues += classDef.type
                        for (method in classDef.methods) {
                            if (sanitizes) {
                                sanitizedIntentHandOver(method)?.let { handOvers += ImmutableMethod.of(method) to it }
                                sanitizeStepCalls(method).forEach { stepCalls += classDef.type to ImmutableMethodReference.of(it) }
                            }
                            if (traces) startPositionGate(method)?.let { gates += ImmutableMethod.of(method) to it }
                            if (targets && keepsAskedStartTab(method)) checks += ImmutableMethod.of(method)
                            if (targets) inlineKeepReset(method)?.let { inlineChecks += ImmutableMethod.of(method) to it }
                        }
                    }
                }
                // From 585 the dispatcher's case only calls the step's body, a method of its own.
                val dispatchers = mutableSetOf<String>()
                if (handOvers.isEmpty()) {
                    val owners = FixtureDex.classes(fixture, stepCalls.map { it.second.definingClass }.toSet())
                    stepCalls.distinctBy { it.toString() }.forEach { (dispatcher, call) ->
                        val body = owners[call.definingClass]?.let { stepBody(it, call) } ?: return@forEach
                        assertEquals("$name: ${body.definingClass}->${body.name} names the step itself", null,
                            sanitizedIntentHandOver(body))
                        sanitizedCopyHandOver(body)?.let {
                            handOvers += ImmutableMethod.of(body) to it
                            dispatchers += dispatcher
                        }
                    }
                    assertEquals("$name: dispatchers whose \"$SANITIZE_INTENT\" case calls the step's body", 1, dispatchers.size)
                }
                assertEquals("$name: start-up steps that sanitize the main screen's intent", 1, handOvers.size)
                assertEquals("$name: tab bar start position gates", 1, gates.size)
                // Up to 584 a static check; 585 runs it inline in the start-up dispatcher, and the patch looks there only without one.
                val inline = if (checks.isEmpty()) inlineChecks else emptyList()
                assertEquals("$name: main screen checks that it keeps its start tab, static or inline", 1,
                    checks.size + inline.size)
                val (sanitizer, handOver) = handOvers.single()
                val (position, gate) = gates.single()
                val check = checks.singleOrNull()
                val inlineCheck = inline.singleOrNull()
                val checkOwner = check?.definingClass ?: inlineCheck!!.first.definingClass
                val handedOverRegisters = code(sanitizer)[handOver].registers()
                val gateRegister = (code(position)[gate] as OneRegisterInstruction).registerA
                val returns = check?.let { c -> code(c).count { it.opcode == Opcode.RETURN } }
                val resetBranches = inlineCheck?.let { (method, reset) -> branchesOnto(code(method), reset) }
                inlineCheck?.let { (method, reset) ->
                    assertTrue("$name: no branch lands on the inline check's reset", resetBranches!! > 0)
                    assertEquals("$name: the inline check's reset", Opcode.IPUT_WIDE, code(method)[reset].opcode)
                }

                val kept = setOf(MAIN_TAB_ACTIVITY, fragmentActivity, delegate, navigationConfig, TAB_TAG,
                    STARTUP_DESTINATION_ROUTER, picker.definingClass, sanitizer.definingClass, position.definingClass,
                    checkOwner, FEED_FILTERS_FRAGMENT, FEED_TYPE) + offered.keys + dispatchers
                val classes = FixtureDex.classes(fixture, kept)
                assertEquals("$name: classes missing", emptySet<String>(), kept - classes.keys)

                // The Feeds tab takes the filter hooks, and FeedType keeps, as constants, a feed
                // type under each name the extension asks for, told apart by what toString answers.
                // Facebook's lookup compares with the feed type asked for, so a constant is enough.
                val feeds = classes.getValue(FEED_FILTERS_FRAGMENT)
                assertEquals("$name: the Feeds tab", null, feedsFragmentRefusal(feeds))
                val feedType = classes.getValue(FEED_TYPE)
                assertTrue(
                    "$name: FeedType keeps too few feed types as constants",
                    feedType.fields.count {
                        it.type == FEED_TYPE && AccessFlags.STATIC.isSet(it.accessFlags) &&
                            AccessFlags.FINAL.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
                    } >= subtabFeedTypes.size,
                )
                val clinit = feedType.methods.single { it.name == "<clinit>" }
                subtabFeedTypes.forEach { assertTrue("$name: FeedType names no $it", holdsString(clinit, it)) }
                val toString = code(feedType.methods.single { it.name == "toString" && it.parameterTypes.isEmpty() })
                assertTrue(
                    "$name: FeedType.toString doesn't answer its id's",
                    toString.any { it.call?.let { c -> c.definingClass == "Ljava/lang/Object;" && c.name == "toString" } == true },
                )
                val handlerAnchors = feedsHandlerAnchors(feeds.methods.single { it.name == FEEDS_HANDLER })!!
                val resumeReturns = code(feeds.methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() })
                    .count { it.opcode == Opcode.RETURN_VOID }

                // The router Facebook keeps predicts the main screen's destination by asking the picker,
                // on the Android 9 build through a method of the picker's own class that asks it.
                val router = classes.getValue(STARTUP_DESTINATION_ROUTER).methods.single { it.name == "getDestination" }
                val asksPicker = { method: Method ->
                    code(method).any { it.call?.let { c -> c.definingClass == picker.definingClass && c.name == picker.name } == true }
                }
                val askers = classes.getValue(picker.definingClass).methods.filter { it.name != picker.name && asksPicker(it) }.map { it.name }
                assertTrue(
                    "$name: getDestination doesn't ask the picker",
                    asksPicker(router) || code(router).any { it.call?.let { c -> c.definingClass == picker.definingClass && c.name in askers } == true },
                )

                // Every tab the extension offers, by the id it asks for.
                offered.forEach { (type, id) ->
                    val tab = classes.getValue(type)
                    assertEquals("$name: $type extends", TAB_TAG, tab.superclass)
                    assertTrue("$name: $type doesn't hand TabTag the id $id", handsItsId(tab, id))
                }

                // What the landing check reads: TabTag's one long, the screen's current tab, the
                // delegate's lazy tab bar state, and the configuration's list.
                val tabTag = classes.getValue(TAB_TAG)
                assertEquals("$name: TabTag's longs", 1,
                    tabTag.fields.count { it.type == "J" && !AccessFlags.STATIC.isSet(it.accessFlags) })
                assertTrue(
                    "$name: FbMainTabActivity keeps no getCurrentTab()",
                    classes.getValue(MAIN_TAB_ACTIVITY).methods.any {
                        it.name == "getCurrentTab" && it.parameterTypes.isEmpty() && it.returnType == TAB_TAG
                    },
                )
                assertTrue(
                    "$name: the delegate keeps no tab bar state",
                    classes.getValue(delegate).fields.any { it.name == "tabBarStateManager\$delegate" },
                )
                assertTrue(
                    "$name: NavigationConfig holds no list",
                    classes.getValue(navigationConfig).fields.any { it.type == "Lcom/google/common/collect/ImmutableList;" },
                )

                // The patch, on this build's main screen classes, picker and start-up steps.
                val context = PatchContexts.of(
                    listOf(MAIN_TAB_ACTIVITY, fragmentActivity, picker.definingClass, sanitizer.definingClass,
                        position.definingClass, checkOwner, FEED_FILTERS_FRAGMENT).plus(dispatchers).distinct()
                        .map(classes::getValue) +
                        ExtensionDex.classDef(SETTINGS_STATUS),
                )
                openOnChosenTabPatch.execute(context)
                fun patched(method: Method) = code(context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.parameterTypes.map(CharSequence::toString) ==
                        method.parameterTypes.map(CharSequence::toString) && it.returnType == method.returnType
                })

                // The sanitizing step hands the screen and the copy to the extension instead of setIntent.
                val handedOver = patched(sanitizer)[handOver]
                assertEquals("$name: the sanitizing step's call", SET_SANITIZED_INTENT, handedOver.call.toString())
                assertEquals("$name: what the sanitizing step hands over", handedOverRegisters, handedOver.registers())

                // The extension answers right after the gate's read, into the register the branch reads.
                val gated = patched(position)
                val asks = gated[gate + 1]
                assertEquals("$name: the gate's call", START_ON_ASKED_TAB, asks.call.toString())
                assertEquals("$name: what the gate's call reads", listOf(gateRegister), asks.registers())
                listOf(gate + 2, gate + 3).forEach {
                    assertEquals("$name: ${gated[it].opcode} after the gate's call", gateRegister,
                        (gated[it] as OneRegisterInstruction).registerA)
                }
                assertEquals(Opcode.MOVE_RESULT, gated[gate + 2].opcode)
                assertEquals(Opcode.IF_EQZ, gated[gate + 3].opcode)

                // Every answer the check returns goes through the extension first.
                if (check != null) {
                    val guarded = patched(check)
                    val answers = guarded.withIndex().filter { it.value.opcode == Opcode.RETURN }
                    assertEquals("$name: the check's returns", returns, answers.size)
                    answers.forEach { (index, answer) ->
                        val register = (answer as OneRegisterInstruction).registerA
                        assertEquals("$name: the check's call", KEEP_ASKED_START_TAB, guarded[index - 2].call.toString())
                        assertEquals("$name: what the check's call reads", listOf(register), guarded[index - 2].registers())
                        assertEquals(Opcode.MOVE_RESULT, guarded[index - 1].opcode)
                        assertEquals(register, (guarded[index - 1] as OneRegisterInstruction).registerA)
                    }
                }
                // Inline, every branch to the reset reaches the extension's call first, and its yes
                // jumps to where Facebook's own yes goes, past the reset.
                inlineCheck?.let { (method, reset) ->
                    val original = code(method).filter { it.opcode != Opcode.NOP }
                    val guarded = patched(method)
                    assertEquals("$name: instructions the inline check gained", original.size + 4,
                        guarded.count { it.opcode != Opcode.NOP })
                    val flag = (guarded[reset] as OneRegisterInstruction).registerA
                    assertEquals(Opcode.CONST_16, guarded[reset].opcode)
                    assertEquals("$name: the inline check's call", KEEP_ASKED_START_TAB, guarded[reset + 1].call.toString())
                    assertEquals("$name: what the inline check's call reads", listOf(flag), guarded[reset + 1].registers())
                    assertEquals(Opcode.MOVE_RESULT, guarded[reset + 2].opcode)
                    assertEquals(flag, (guarded[reset + 2] as OneRegisterInstruction).registerA)
                    assertEquals(Opcode.IF_NEZ, guarded[reset + 3].opcode)
                    assertEquals(flag, (guarded[reset + 3] as OneRegisterInstruction).registerA)
                    assertEquals("$name: where the extension's yes goes", reset + 5, branchTarget(guarded, reset + 3))
                    assertEquals("$name: the reset after the call", Opcode.IPUT_WIDE, guarded[reset + 4].opcode)
                    assertEquals("$name: branches to the call", resetBranches, branchesOnto(guarded, reset))
                    assertEquals("$name: branches still on the reset itself", 0, branchesOnto(guarded, reset + 4))
                }

                // The Feeds tab's handler asks the extension right after its read and right after
                // its lookup's answer, and each of onResume's returns hands the extension the tab first.
                val handled = patched(feeds.methods.single { it.name == FEEDS_HANDLER })
                val read = handlerAnchors.read
                assertEquals("$name: the Feeds tab handler's call", FEED_TYPE_ASKED, handled[read + 1].call.toString())
                assertEquals(Opcode.MOVE_RESULT_OBJECT, handled[read + 2].opcode)
                assertEquals(Opcode.CHECK_CAST, handled[read + 3].opcode)
                // The three instructions the read's hook added come before the answer.
                val answer = handlerAnchors.answer + 3
                assertEquals(Opcode.MOVE_RESULT, handled[answer].opcode)
                assertEquals("$name: the call on the lookup's answer", FILTER_FOUND, handled[answer + 1].call.toString())
                assertEquals(Opcode.MOVE_RESULT, handled[answer + 2].opcode)
                val resumed = patched(feeds.methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() })
                val resumedReturns = resumed.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
                assertEquals("$name: onResume's returns", resumeReturns, resumedReturns.size)
                resumedReturns.forEach { (index, _) ->
                    assertEquals("$name: the call before onResume's return", FEEDS_RESUMED, resumed[index - 1].call.toString())
                }

                assertTrue(
                    "$name: the main screen declares an onCreate of its own",
                    classes.getValue(MAIN_TAB_ACTIVITY).methods.none { it.name == "onCreate" },
                )

                // Why the extension's help can't end when the screen first shows: the main screen
                // can hand its work to a stand-in delegate that queues it, Facebook's start-up
                // steps included, until the app is ready. The onCreate the route hook goes first
                // in hands on to whichever delegate the screen got.
                assertEquals("$name: delegates that queue their work", 1, queues.size)
                val instantiate = classes.getValue(MAIN_TAB_ACTIVITY).methods.single { it.name == "instantiateDelegateImpl" }
                assertTrue(
                    "$name: the main screen never hands its work to the queueing stand-in",
                    code(instantiate).any {
                        it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == queues.single()
                    },
                )
                assertTrue(
                    "$name: the base onCreate doesn't hand on to the screen's delegate",
                    code(classes.getValue(fragmentActivity).methods.single {
                        it.name == "onCreate" && it.parameterTypes.map(CharSequence::toString) == listOf(bundle)
                    }).any { it.call?.let { c -> c.name == "getFragmentActivityDelegate" } == true },
                )
                val onCreate = context.mutableClassDefBy(fragmentActivity).methods.single {
                    it.name == "onCreate" && it.parameterTypes.map(CharSequence::toString) == listOf(bundle)
                }
                val first = onCreate.implementation!!.instructions.first()
                assertEquals("$name: the hook's call", ROUTE, (first as ReferenceInstruction).reference.toString())
                val registers = onCreate.implementation!!.registerCount
                assertEquals("$name: the hook reads this and the saved state", (registers - 2) to 2,
                    (first as RegisterRangeInstruction).startRegister to first.registerCount)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
