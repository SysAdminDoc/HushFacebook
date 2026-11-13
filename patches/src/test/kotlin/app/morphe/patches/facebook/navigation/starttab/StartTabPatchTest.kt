/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.starttab

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.feedsheader.FEED_FILTERS_FRAGMENT
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.settings.MAIN_TAB_ACTIVITY
import app.morphe.patches.facebook.shared.MOBILE_CONFIG
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Open on a chosen tab over a few stand-in classes: which method counts as Facebook's start tab
 * picker and as each of the three start-up steps the patch hooks, what the patch refuses, and the
 * calls it puts in each.
 */
class StartTabPatchTest {
    private val fragmentActivity = "Lcom/facebook/base/activity/FbFragmentActivity;"
    private val context = "Landroid/content/Context;"
    private val session = "Lcom/facebook/auth/usersession/FbUserSession;"
    private val hasExtra = ImmutableMethodReference(INTENT, "hasExtra", listOf("Ljava/lang/String;"), "Z")
    private val getLongExtra = ImmutableMethodReference(INTENT, "getLongExtra", listOf("Ljava/lang/String;", "J"), "J")

    private val startOps = "Lfixture/StartOps;"
    private val tabBar = "Lfixture/TabBar;"
    private val tabBarState = "Lfixture/TabBarState;"
    private val mainViews = "Lfixture/MainViews;"
    private val flag = "$MOBILE_CONFIG->flag(Ljava/lang/Object;J)Z"

    private fun parameters(types: List<String>) = types.map { ImmutableMethodParameter(it, null, null) }

    /**
     * A picker as Facebook writes it, trimmed: this, the context, the intent in v5 and the session;
     * the literal, the two reads of it, and the long it answers. Each part can be left out.
     */
    private fun picker(
        owner: String = "Lfixture/StartTabPicker;",
        literal: String = TARGET_TAB_ID,
        asks: Boolean = true,
        reads: Boolean = true,
        returnType: String = "J",
        parameterTypes: List<String> = listOf(context, INTENT, session),
    ): Method {
        val body = mutableListOf<Instruction>(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(literal)))
        if (asks) {
            body += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 5, 0, 0, 0, 0, hasExtra)
            body += ImmutableInstruction11x(Opcode.MOVE_RESULT, 1)
        }
        body += ImmutableInstruction21s(Opcode.CONST_WIDE_16, 1, -1)
        if (reads) {
            body += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 5, 0, 1, 2, 0, getLongExtra)
            body += ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 1)
        }
        body += ImmutableInstruction11x(Opcode.RETURN_WIDE, 1)
        return ImmutableMethod(
            owner, "A00", parameters(parameterTypes), returnType, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null, null, ImmutableMethodImplementation(7, body, null, null),
        )
    }

    private fun method(
        owner: String,
        name: String,
        parameterTypes: List<String>,
        returnType: String,
        registers: Int,
        static: Boolean,
        smali: String,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            owner, name, parameters(parameterTypes), returnType,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0),
            null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    /**
     * The start-up step that sanitizes the main screen's intent, as Facebook's dispatcher runs it
     * when the step's name comes up: the copy is built in v3 from the action and the link, the last
     * kept extra is copied over, and the screen in p0 gets the copy. Parts can be changed.
     */
    private fun sanitizer(
        step: String = SANITIZE_INTENT,
        kept: String = KEPT_EXTRA,
        copyBuiltFrom: String = "Ljava/lang/String;Landroid/net/Uri;",
        handedOver: String = "v3",
    ) = method(
        startOps, "run", listOf("Landroid/app/Activity;", "Ljava/lang/String;"), "V", 6, static = true,
        smali = """
            const-string v0, "$step"
            invoke-virtual { p1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :done
            invoke-virtual { p0 }, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
            move-result-object v1
            invoke-virtual { v1 }, Landroid/content/Intent;->getAction()Ljava/lang/String;
            move-result-object v2
            invoke-virtual { v1 }, Landroid/content/Intent;->getData()Landroid/net/Uri;
            move-result-object v0
            new-instance v3, Landroid/content/Intent;
            invoke-direct { v3, v2, v0 }, Landroid/content/Intent;-><init>($copyBuiltFrom)V
            const-string v2, "$kept"
            invoke-virtual { v1, v2 }, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            if-eqz v0, :set
            invoke-virtual { v3, v2, v0 }, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
            :set
            invoke-virtual { p0, $handedOver }, Landroid/app/Activity;->setIntent(Landroid/content/Intent;)V
            :done
            return-void
        """,
    )

    /**
     * 585's form of the step: the dispatcher's case for [step] only calls [body]'s method, which
     * builds the copy in v3 and hands it to the screen in p1, as the inline step does.
     */
    private fun outlinedSanitizer(step: String = SANITIZE_INTENT, kept: String = KEPT_EXTRA): List<Method> {
        val body = "Lfixture/Sanitizer;"
        return listOf(
            method(
                startOps, "run", listOf("Landroid/app/Activity;", "Ljava/lang/String;"), "V", 3, static = true,
                smali = """
                    const-string v0, "$step"
                    invoke-virtual { p1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :done
                    new-instance v0, $body
                    invoke-virtual { v0, p0 }, $body->apply(Landroid/app/Activity;)V
                    goto :done
                    :done
                    return-void
                """,
            ),
            method(
                body, "apply", listOf("Landroid/app/Activity;"), "V", 6, static = false,
                smali = """
                    invoke-virtual { p1 }, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
                    move-result-object v1
                    invoke-virtual { v1 }, Landroid/content/Intent;->getAction()Ljava/lang/String;
                    move-result-object v2
                    invoke-virtual { v1 }, Landroid/content/Intent;->getData()Landroid/net/Uri;
                    move-result-object v0
                    new-instance v3, Landroid/content/Intent;
                    invoke-direct { v3, v2, v0 }, Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V
                    const-string v2, "$kept"
                    invoke-virtual { v1, v2 }, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    if-eqz v0, :set
                    invoke-virtual { v3, v2, v0 }, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
                    :set
                    invoke-virtual { p1, v3 }, Landroid/app/Activity;->setIntent(Landroid/content/Intent;)V
                    return-void
                """,
            ),
        )
    }

    /**
     * 587's form of the step: the dispatcher's case for [step] asks a method of its own for the
     * copy and sets what it answers, after a null check unless [nullCheck] is false. The maker
     * builds the copy in v0 from the action and the link and copies [kept] over inline, or with
     * [copier] through a static method taking the original and the copy, then returns the copy
     * ([returnsCopy] false returns the original).
     */
    private fun copyMakingSanitizer(
        step: String = SANITIZE_INTENT,
        kept: String = KEPT_EXTRA,
        copier: Boolean = false,
        returnsCopy: Boolean = true,
        nullCheck: Boolean = true,
    ): List<Method> {
        val maker = "Lfixture/IntentSanitizer;"
        val extras = "Lfixture/IntentExtras;"
        val keep = if (copier) {
            "invoke-static { p1, v0 }, $extras->copy(Landroid/content/Intent;Landroid/content/Intent;)V"
        } else {
            """
                const-string v1, "$kept"
                invoke-virtual { p1, v1 }, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v2
                invoke-virtual { v0, v1, v2 }, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
            """
        }
        return listOfNotNull(
            method(
                startOps, "run", listOf("Landroid/app/Activity;", "Ljava/lang/String;"), "V", 4, static = true,
                smali = """
                    const-string v0, "$step"
                    invoke-virtual { p1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :done
                    invoke-virtual { p0 }, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
                    move-result-object v1
                    new-instance v0, $maker
                    invoke-virtual { v0, v1 }, $maker->sanitize(Landroid/content/Intent;)Landroid/content/Intent;
                    move-result-object v1
                    ${if (nullCheck) "if-eqz v1, :done" else ""}
                    invoke-virtual { p0, v1 }, Landroid/app/Activity;->setIntent(Landroid/content/Intent;)V
                    goto :done
                    :done
                    return-void
                """,
            ),
            method(
                maker, "sanitize", listOf(INTENT), INTENT, 5, static = false,
                smali = """
                    invoke-virtual { p1 }, Landroid/content/Intent;->getAction()Ljava/lang/String;
                    move-result-object v1
                    invoke-virtual { p1 }, Landroid/content/Intent;->getData()Landroid/net/Uri;
                    move-result-object v2
                    new-instance v0, Landroid/content/Intent;
                    invoke-direct { v0, v1, v2 }, Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V
                    $keep
                    return-object ${if (returnsCopy) "v0" else "p1"}
                """,
            ),
            if (!copier) null else method(
                extras, "copy", listOf(INTENT, INTENT), "V", 4, static = true,
                smali = """
                    const-string v0, "$kept"
                    invoke-virtual { p0, v0 }, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    invoke-virtual { p1, v0, v1 }, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
                    return-void
                """,
            ),
        )
    }

    /** What a call names among [methods], as the patch finds a method in the app by its class. */
    private fun named(methods: List<Method>) = { call: MethodReference ->
        methods.firstOrNull { it.definingClass == call.definingClass && it.name == call.name }
    }

    /**
     * The tab bar's start position, trimmed: with the boolean off it asks the MobileConfig gate, and
     * with the gate on it hands the start tab it was built with, a long, to a lookup answering the
     * tab's position. [branchOn] and [handed] name the registers the branch and the lookup read.
     */
    private fun startPosition(
        trace: String = START_POSITION,
        parameter: String = "Z",
        branchOn: String = "v0",
        startTabOwner: String = tabBar,
        handed: String = "v0, v1",
        jumpToBranch: Boolean = false,
    ) = method(
        tabBar, "position", listOf(parameter), "V", 6, static = false,
        smali = """
            const-string v0, "$trace"
            if-nez p1, :fallback
            invoke-static { }, Lfixture/Config;->get()Ljava/lang/Object;
            move-result-object v2
            const-wide v0, 0x101001e00e40150L
            invoke-static { v2, v0, v1 }, $flag
            move-result v0
            ${if (jumpToBranch) ":check" else ""}
            if-eqz $branchOn, :fallback
            iget-wide v0, p0, $startTabOwner->startTab:J
            invoke-static { }, $tabBarState->get()$tabBarState
            move-result-object v2
            invoke-virtual { v2, $handed }, $tabBarState->indexOf(J)Ljava/lang/Integer;
            move-result-object v0
            if-eqz v0, :fallback
            invoke-virtual { v0 }, Ljava/lang/Integer;->intValue()I
            move-result v1
            goto :set
            :fallback
            ${if (jumpToBranch) "if-nez p1, :check" else ""}
            const/4 v1, 0x0
            :set
            iput v1, p0, $tabBar->position:I
            return-void
        """,
    )

    /**
     * The main screen's check that it keeps its start tab, trimmed: static, the screen and its own
     * class, the intent's "target_tab_id" against the long it keeps. The answer ends up in p0, v5,
     * which Facebook's code reuses once it's done with the screen.
     */
    private fun keepsCheck(
        owner: String = mainViews,
        static: Boolean = true,
        literal: String = TARGET_TAB_ID,
        screen: String = MAIN_TAB_ACTIVITY,
        keptLongOwner: String = owner,
    ) = method(
        owner, "keeps", listOf(screen, owner), "Z", 7, static,
        smali = """
            invoke-virtual { p0 }, $fragmentActivity->getIntent()Landroid/content/Intent;
            move-result-object v3
            const/4 p0, 0x0
            if-eqz v3, :done
            const-string v2, "$literal"
            invoke-virtual { v3, v2 }, Landroid/content/Intent;->hasExtra(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :done
            const-wide/16 v0, -0x1
            invoke-virtual { v3, v2, v0, v1 }, Landroid/content/Intent;->getLongExtra(Ljava/lang/String;J)J
            move-result-wide v3
            iget-wide v1, p1, $keptLongOwner->startTab:J
            cmp-long v0, v3, v1
            if-nez v0, :done
            const/4 p0, 0x1
            :done
            return p0
        """,
    )

    /**
     * The keep check as 585 runs it inline in its start-up, trimmed: the intent's tab against the
     * long the main views in p1 keep, every no landing on the reset of that long to the default in
     * v4, a yes from the config past it. v0 is free at the reset: the next instruction writes it.
     * [resetField] makes the reset write another field, and [owner] names the class.
     */
    private fun inlineKeepCheck(
        owner: String = "Lfixture/StartUp;",
        resetField: String = "startTab",
        literal: String = TARGET_TAB_ID,
    ) = method(
        owner, "start", listOf(MAIN_TAB_ACTIVITY, mainViews), "V", 10, static = true,
        smali = """
            const-wide/16 v4, -0x1
            invoke-virtual { p0 }, $fragmentActivity->getIntent()Landroid/content/Intent;
            move-result-object v3
            if-eqz v3, :reset
            const-string v2, "$literal"
            invoke-virtual { v3, v2 }, Landroid/content/Intent;->hasExtra(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :reset
            const-wide/16 v0, -0x1
            invoke-virtual { v3, v2, v0, v1 }, Landroid/content/Intent;->getLongExtra(Ljava/lang/String;J)J
            move-result-wide v2
            iget-wide v0, p1, $mainViews->startTab:J
            cmp-long v0, v2, v0
            if-nez v0, :reset
            sget-boolean v0, Lfixture/Config;->keep:Z
            if-nez v0, :kept
            :reset
            iput-wide v4, p1, $mainViews->$resetField:J
            :kept
            iget-wide v0, p1, $mainViews->startTab:J
            invoke-static { v0, v1 }, Lfixture/Tabs;->show(J)V
            return-void
        """,
    )

    /**
     * The Feeds tab's handler, trimmed: with an intent, the last feed type is read into v1, handed
     * to the filter list's lookup, whose answer is kept in v2, and the filter at the answer picked.
     * [overwritten] writes v1 between the read and the lookup, [keepsAnswer] false drops the
     * lookup's answer, and [jumpPast] and [jumpPastAnswer] land a branch right after the read or
     * the answer.
     */
    private fun feedsHandler(
        name: String = FEEDS_HANDLER,
        lastType: String = FEED_TYPE,
        overwritten: Boolean = false,
        keepsAnswer: Boolean = true,
        jumpPast: Boolean = false,
        jumpPastAnswer: Boolean = false,
    ) = method(
        FEED_FILTERS_FRAGMENT, name, listOf(INTENT), "V", 5, static = false,
        smali = """
            if-eqz p1, :done
            ${if (jumpPast) "if-nez p1, :after" else ""}
            ${if (jumpPastAnswer) "if-nez p1, :answered" else ""}
            sget-object v1, Lfixture/Tracker;->last:$lastType
            ${if (jumpPast) ":after" else ""}
            iget-object v0, p0, $FEED_FILTERS_FRAGMENT->filters:Lfixture/Filters;
            ${if (overwritten) "const/4 v1, 0x0" else ""}
            invoke-virtual { v0, v1 }, Lfixture/Filters;->indexOf($FEED_TYPE)I
            ${if (keepsAnswer) "move-result v2" else "const/4 v2, 0x0"}
            ${if (jumpPastAnswer) ":answered" else ""}
            const/4 v0, -0x1
            if-eq v2, v0, :done
            iget-object v1, p0, $FEED_FILTERS_FRAGMENT->controller:Lfixture/Controller;
            invoke-virtual { v1, v2 }, Lfixture/Controller;->pick(I)V
            :done
            return-void
        """,
    )

    /** The Feeds tab's onResume, trimmed: two returns, one a branch lands on. [reusesThis] writes p0. */
    private fun feedsResume(reusesThis: Boolean = false) = method(
        FEED_FILTERS_FRAGMENT, "onResume", emptyList(), "V", 3, static = false,
        smali = """
            iget-boolean v0, p0, $FEED_FILTERS_FRAGMENT->ready:Z
            if-eqz v0, :late
            invoke-super { p0 }, Landroidx/fragment/app/Fragment;->onResume()V
            ${if (reusesThis) "const/4 p0, 0x0" else ""}
            return-void
            :late
            return-void
        """,
    )

    /**
     * The Feeds tab's onBackPressed as 586 writes it, one register, `this` reused for the shown
     * filter's page and its answer. [jumpsToStart] loops back to the first instruction.
     */
    private fun feedsBack(jumpsToStart: Boolean = false) = method(
        FEED_FILTERS_FRAGMENT, "onBackPressed", emptyList(), "Z", 1, static = false,
        smali = """
            :start
            invoke-direct { p0 }, $FEED_FILTERS_FRAGMENT->getFeedFiltersController()Lfixture/Controller;
            move-result-object p0
            invoke-virtual { p0 }, Lfixture/Controller;->shown()Lfixture/Page;
            move-result-object p0
            if-eqz p0, :none
            ${if (jumpsToStart) "goto :start" else ""}
            invoke-virtual { p0 }, Lfixture/Page;->onBackPressed()Z
            move-result p0
            return p0
            :none
            const/4 p0, 0x0
            return p0
        """,
    )

    /** The Feeds tab, each of its three methods replaceable or left out. */
    private fun feedsFragment(
        handler: Method? = feedsHandler(),
        resume: Method? = feedsResume(),
        back: Method? = feedsBack(),
    ): ClassDef = ImmutableClassDef(
        FEED_FILTERS_FRAGMENT, AccessFlags.PUBLIC.value, "Landroidx/fragment/app/Fragment;", null, null, null, null,
        listOfNotNull(handler, resume, back),
    )

    private fun classOf(vararg methods: Method): ClassDef = ImmutableClassDef(
        methods.first().definingClass, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        methods.toList(),
    )

    /** The main screen with no onCreate of its own, and the base activity that declares it. */
    private fun activities(): List<ClassDef> {
        val onCreate = ImmutableMethod(
            fragmentActivity, "onCreate", parameters(listOf("Landroid/os/Bundle;")), "V", AccessFlags.PUBLIC.value,
            null, null, ImmutableMethodImplementation(6, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        )
        return listOf(
            ImmutableClassDef(MAIN_TAB_ACTIVITY, AccessFlags.PUBLIC.value, fragmentActivity, null, null, null, null,
                emptyList<Method>()),
            ImmutableClassDef(fragmentActivity, AccessFlags.PUBLIC.value, "Landroidx/fragment/app/FragmentActivity;",
                null, null, null, null, listOf(onCreate)),
        )
    }

    /** Everything the patch looks for, each part replaceable. */
    private fun build(
        sanitizers: List<Method> = listOf(sanitizer()),
        positions: List<Method> = listOf(startPosition()),
        checks: List<Method> = listOf(keepsCheck()),
        feeds: ClassDef? = feedsFragment(),
    ): List<ClassDef> =
        activities() + classOf(picker()) + ExtensionDex.classDef(SETTINGS_STATUS) +
            (sanitizers + positions + checks).map { classOf(it) } + listOfNotNull(feeds)

    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private val Instruction.reference: String get() = (this as ReferenceInstruction).reference.toString()

    private val Instruction.register: Int get() = (this as OneRegisterInstruction).registerA

    @Test
    fun `the picker answers a long from an intent's target_tab_id, asked for and read`() {
        assertTrue(picksStartTab(picker()))
        assertFalse("another literal", picksStartTab(picker(literal = "target_tab")))
        assertFalse("never asks whether the intent has it", picksStartTab(picker(asks = false)))
        assertFalse("never reads it as a long", picksStartTab(picker(reads = false)))
        assertFalse("answers no tab id", picksStartTab(picker(returnType = "Z")))
        assertFalse("takes no intent", picksStartTab(picker(parameterTypes = listOf(context, session))))
        // The extension loads the same literal to write it, and must never be taken for Facebook's.
        assertFalse(picksStartTab(picker(owner = "Lapp/morphe/extension/facebook/navigation/Fixture;")))
    }

    @Test
    fun `the sanitizing step is the setIntent that hands over a copy built from the action and the link`() {
        val step = sanitizer()
        val index = sanitizedIntentHandOver(step)!!
        assertEquals("$ACTIVITY_SET_INTENT", step.body()[index].reference)
        assertNull("another step's name", sanitizedIntentHandOver(sanitizer(step = "sanitize_intents")))
        assertNull("no kept extra", sanitizedIntentHandOver(sanitizer(kept = "app_switch_source_user")))
        assertNull("a copy built another way", sanitizedIntentHandOver(sanitizer(copyBuiltFrom = "Landroid/net/Uri;Ljava/lang/String;")))
        assertNull("hands over another intent", sanitizedIntentHandOver(sanitizer(handedOver = "v1")))
    }

    @Test
    fun `a step body moved out of the dispatcher is the method its case calls, and the patch goes in there`() {
        val (dispatcher, body) = outlinedSanitizer()
        assertNull("the dispatcher builds no copy itself", sanitizedIntentHandOver(dispatcher))
        assertEquals(listOf("Lfixture/Sanitizer;->apply(Landroid/app/Activity;)V"), sanitizeStepCalls(dispatcher).map { it.toString() })
        assertNull("the body doesn't name the step", sanitizedIntentHandOver(body))
        assertEquals(ACTIVITY_SET_INTENT, body.body()[sanitizedCopyHandOver(body)!!].reference)
        assertEquals("another step's case", emptyList<Any>(), sanitizeStepCalls(outlinedSanitizer(step = "sanitize_intents").first()))

        val context = PatchContexts.of(build(sanitizers = outlinedSanitizer()))
        openOnChosenTabPatch.execute(context)
        val step = context.mutableClassDefBy("Lfixture/Sanitizer;").methods.single().body()
        val handOver = step.single { (it as? ReferenceInstruction)?.reference?.toString() == SET_SANITIZED_INTENT }
        // p1 of a six-register instance method is v5; the copy stays in v3.
        assertEquals(listOf(5, 3), handOver.callRegisters())
        assertTrue("the old setIntent is still there", step.none { (it as? ReferenceInstruction)?.reference?.toString() == ACTIVITY_SET_INTENT })
        assertEquals("the dispatcher changed", dispatcher.body().map { it.opcode },
            context.mutableClassDefBy(startOps).methods.single().body().map { it.opcode })

        val noCase = assertThrows(PatchException::class.java) {
            openOnChosenTabPatch.execute(PatchContexts.of(build(sanitizers = outlinedSanitizer(kept = "app_switch_source_user"))))
        }
        assertTrue(noCase.message, noCase.message!!.contains("\"$SANITIZE_INTENT\"), found 0"))
    }

    @Test
    fun `587's step case sets a copy a method of its own makes, and the patch goes in the case`() {
        for (copier in listOf(false, true)) {
            val form = if (copier) "extras copied by a static method" else "extras copied inline"
            val methods = copyMakingSanitizer(copier = copier)
            val (dispatcher, maker) = methods
            val makes = { call: MethodReference -> named(methods)(call)?.let { returnsSanitizedCopy(it, named(methods)) } == true }
            assertTrue("$form: the maker", returnsSanitizedCopy(maker, named(methods)))
            assertNull("$form: the maker hands nothing over itself", sanitizedCopyHandOver(maker))
            assertNull("$form: the dispatcher builds no copy itself", sanitizedIntentHandOver(dispatcher))
            assertEquals(form, ACTIVITY_SET_INTENT, dispatcher.body()[sanitizeCaseHandOver(dispatcher, makes)!!].reference)

            copyMakingSanitizer(kept = "app_switch_source_user", copier = copier).let {
                assertFalse("$form: no kept extra", returnsSanitizedCopy(it[1], named(it)))
            }
            copyMakingSanitizer(returnsCopy = false, copier = copier).let {
                assertFalse("$form: returns the original", returnsSanitizedCopy(it[1], named(it)))
            }
            copyMakingSanitizer(nullCheck = false, copier = copier).let {
                assertEquals("$form: without the null check", ACTIVITY_SET_INTENT, it[0].body()[sanitizeCaseHandOver(it[0], makes)!!].reference)
            }
            assertNull("$form: another step's case", sanitizeCaseHandOver(copyMakingSanitizer(step = "sanitize_intents", copier = copier)[0], makes))
            assertNull("$form: a case setting what no maker made", sanitizeCaseHandOver(dispatcher) { false })

            val context = PatchContexts.of(build(sanitizers = methods))
            openOnChosenTabPatch.execute(context)
            val step = context.mutableClassDefBy(startOps).methods.single().body()
            val handOver = step.single { (it as? ReferenceInstruction)?.reference?.toString() == SET_SANITIZED_INTENT }
            // p0 of a four-register static method of two parameters is v2; the copy is in v1.
            assertEquals(form, listOf(2, 1), handOver.callRegisters())
            assertTrue("$form: the old setIntent is still there", step.none { (it as? ReferenceInstruction)?.reference?.toString() == ACTIVITY_SET_INTENT })
            assertEquals("$form: the maker changed", maker.body().map { it.opcode },
                context.mutableClassDefBy(maker.definingClass).methods.single().body().map { it.opcode })
        }
        // Through a copier the build hasn't got, nothing says the kept extra is copied.
        val methods = copyMakingSanitizer(copier = true)
        assertFalse("the copier isn't there", returnsSanitizedCopy(methods[1]) { null })
    }

    @Test
    fun `the start position gate is the first MobileConfig read, branched on, guarding the start tab lookup`() {
        val position = startPosition()
        val index = startPositionGate(position)!!
        assertEquals(Opcode.MOVE_RESULT, position.body()[index].opcode)
        assertEquals(flag, position.body()[index - 1].reference)
        assertNull("no trace section", startPositionGate(startPosition(trace = "TabBarController.addTabs")))
        assertNull("another parameter", startPositionGate(startPosition(parameter = "I")))
        assertNull("the branch reads another register", startPositionGate(startPosition(branchOn = "v1")))
        assertNull("the long is another class's", startPositionGate(startPosition(startTabOwner = "Lfixture/Other;")))
        assertNull("the lookup isn't handed the start tab", startPositionGate(startPosition(handed = "v2, v3")))
    }

    @Test
    fun `the keep check is static, takes the screen and its own class, and compares the intent's tab with its own`() {
        assertTrue(keepsAskedStartTab(keepsCheck()))
        assertFalse("not static", keepsAskedStartTab(keepsCheck(static = false)))
        assertFalse("another literal", keepsAskedStartTab(keepsCheck(literal = "target_tab")))
        assertFalse("not the main screen", keepsAskedStartTab(keepsCheck(screen = fragmentActivity)))
        assertFalse("keeps no long of its own", keepsAskedStartTab(keepsCheck(keptLongOwner = "Lfixture/Other;")))
    }

    @Test
    fun `an inline keep check is the getLongExtra of the asked tab compared with the kept one, and the reset its no lands on`() {
        val inline = inlineKeepCheck()
        val reset = inlineKeepReset(inline)!!
        assertEquals(Opcode.IPUT_WIDE, inline.body()[reset].opcode)
        assertNull("the no resets another field", inlineKeepReset(inlineKeepCheck(resetField = "lastTab")))
        assertNull("another literal", inlineKeepReset(inlineKeepCheck(literal = "target_tab")))
        assertNull("the static check runs nothing inline", inlineKeepReset(keepsCheck()))
        assertNull("the picker", inlineKeepReset(picker()))
        assertFalse(keepsAskedStartTab(inline))

        val context = PatchContexts.of(build(checks = listOf(inline)))
        openOnChosenTabPatch.execute(context)
        val guarded = context.mutableClassDefBy("Lfixture/StartUp;").methods.single()
        val body = guarded.body()
        assertEquals(inline.body().size + 4, body.size)
        // v0 is the lowest register nothing reads at the reset or after it.
        assertEquals(Opcode.CONST_16, body[reset].opcode)
        assertEquals(0, body[reset].register)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, body[reset + 1].opcode)
        assertEquals(KEEP_ASKED_START_TAB, body[reset + 1].reference)
        assertEquals(listOf(0), body[reset + 1].callRegisters())
        assertEquals(Opcode.MOVE_RESULT, body[reset + 2].opcode)
        assertEquals(0, body[reset + 2].register)
        assertEquals(Opcode.IF_NEZ, body[reset + 3].opcode)
        assertEquals(0, body[reset + 3].register)
        assertEquals(Opcode.IPUT_WIDE, body[reset + 4].opcode)
        val branches = guarded.implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>()
        // The three noes reach the extension; the extension's yes and the config's go past the reset.
        assertEquals(listOf(reset, reset, reset, reset + 5, reset + 5), branches.map { it.target.location.index })

        // A static check wins; the inline one is left alone.
        val both = PatchContexts.of(build(checks = listOf(keepsCheck(), inlineKeepCheck())))
        openOnChosenTabPatch.execute(both)
        assertEquals(inline.body().map { it.opcode }, both.mutableClassDefBy("Lfixture/StartUp;").methods.single().body().map { it.opcode })
    }

    @Test
    fun `the patch puts each call where it belongs, reading only registers Facebook's code already uses`() {
        val context = PatchContexts.of(build())

        openOnChosenTabPatch.execute(context)

        // First in the onCreate the main screen inherits, with the parameters as they come.
        val onCreate = context.mutableClassDefBy(fragmentActivity).methods.single { it.name == "onCreate" }
        val body = onCreate.body()
        assertEquals(2, body.size)
        val call = body.first()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(ROUTE, call.reference)
        // p0 and p1 of a six-register method: this and the saved state, nothing borrowed.
        assertEquals(4 to 2, (call as RegisterRangeInstruction).startRegister to call.registerCount)
        assertEquals(Opcode.RETURN_VOID, body.last().opcode)

        // The sanitizing step's setIntent, now the extension's, on the screen and the copy.
        val step = context.mutableClassDefBy(startOps).methods.single().body()
        val handOver = step.single { (it as? ReferenceInstruction)?.reference?.toString() == SET_SANITIZED_INTENT }
        assertEquals(Opcode.INVOKE_STATIC, handOver.opcode)
        assertEquals(listOf(4, 3), handOver.callRegisters())
        assertTrue("the old setIntent is still there", step.none { (it as? ReferenceInstruction)?.reference?.toString() == ACTIVITY_SET_INTENT })

        // Right after the gate's move-result, and before the branch that reads it.
        val position = context.mutableClassDefBy(tabBar).methods.single().body()
        val gate = position.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == flag } + 1
        assertEquals(Opcode.MOVE_RESULT, position[gate].opcode)
        val asks = position[gate + 1]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, asks.opcode)
        assertEquals(START_ON_ASKED_TAB, asks.reference)
        assertEquals(listOf(0), asks.callRegisters())
        assertEquals(Opcode.MOVE_RESULT, position[gate + 2].opcode)
        assertEquals(0, position[gate + 2].register)
        assertEquals(Opcode.IF_EQZ, position[gate + 3].opcode)
        assertEquals(0, position[gate + 3].register)

        // In place of the check's return, so every branch to the return goes through it.
        val check = context.mutableClassDefBy(mainViews).methods.single()
        val checkBody = check.body()
        val keep = checkBody.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == KEEP_ASKED_START_TAB }
        assertEquals(listOf(5), checkBody[keep].callRegisters())
        assertEquals(Opcode.MOVE_RESULT, checkBody[keep + 1].opcode)
        assertEquals(5, checkBody[keep + 1].register)
        assertEquals(Opcode.RETURN, checkBody[keep + 2].opcode)
        assertEquals(5, checkBody[keep + 2].register)
        assertEquals("a return skips the extension", 1, checkBody.count { it.opcode == Opcode.RETURN })
        val branches = check.implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>()
            .filter { it.opcode == Opcode.IF_EQZ || it.opcode == Opcode.IF_NEZ }
        assertEquals(3, branches.size)
        branches.forEach { assertEquals("a branch lands past the extension", keep, it.target.location.index) }

        val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "startTab" }
        val answer = status.body().first { it is NarrowLiteralInstruction }
        assertEquals("the settings screen isn't told the patch is in", 1, (answer as NarrowLiteralInstruction).narrowLiteral)

        // Right after the Feeds tab's read of the last feed type, cast back before the lookup reads
        // it, and right after the lookup's answer, kept in the same register.
        val fragment = context.mutableClassDefBy(FEED_FILTERS_FRAGMENT)
        val handler = fragment.methods.single { it.name == FEEDS_HANDLER }.body()
        val read = handler.indexOfFirst { it.opcode == Opcode.SGET_OBJECT }
        assertEquals(1, handler[read].register)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, handler[read + 1].opcode)
        assertEquals(FEED_TYPE_ASKED, handler[read + 1].reference)
        assertEquals(listOf(1), handler[read + 1].callRegisters())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, handler[read + 2].opcode)
        assertEquals(1, handler[read + 2].register)
        assertEquals(Opcode.CHECK_CAST, handler[read + 3].opcode)
        assertEquals(1, handler[read + 3].register)
        assertEquals(FEED_TYPE, handler[read + 3].reference)
        assertEquals(Opcode.IGET_OBJECT, handler[read + 4].opcode)
        val lookup = handler.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString()?.endsWith("indexOf($FEED_TYPE)I") == true }
        assertEquals(Opcode.MOVE_RESULT, handler[lookup + 1].opcode)
        assertEquals(2, handler[lookup + 1].register)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, handler[lookup + 2].opcode)
        assertEquals(FILTER_FOUND, handler[lookup + 2].reference)
        assertEquals(listOf(2), handler[lookup + 2].callRegisters())
        assertEquals(Opcode.MOVE_RESULT, handler[lookup + 3].opcode)
        assertEquals(2, handler[lookup + 3].register)
        assertEquals(Opcode.CONST_4, handler[lookup + 4].opcode)

        // In place of each of onResume's returns, with the tab in p0 of a three-register method.
        val resume = fragment.methods.single { it.name == "onResume" }
        val resumeBody = resume.body()
        val returns = resumeBody.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        assertEquals(2, returns.size)
        returns.forEach { index ->
            assertEquals(Opcode.INVOKE_STATIC_RANGE, resumeBody[index - 1].opcode)
            assertEquals(FEEDS_RESUMED, resumeBody[index - 1].reference)
            assertEquals(listOf(2), resumeBody[index - 1].callRegisters())
        }
        val late = resume.implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().single()
        assertEquals("a branch lands past the extension", returns.last() - 1, late.target.location.index)

        // First in onBackPressed: the tab in p0 through the extension, cast back, on to Facebook's
        // own first instruction when it comes back, and a yes when it doesn't.
        val back = fragment.methods.single { it.name == "onBackPressed" }
        val backBody = back.body()
        assertEquals(1, back.implementation!!.registerCount)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, backBody[0].opcode)
        assertEquals(FEEDS_PAGE_BACK, backBody[0].reference)
        assertEquals(listOf(0), backBody[0].callRegisters())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, backBody[1].opcode)
        assertEquals(0, backBody[1].register)
        assertEquals(Opcode.CHECK_CAST, backBody[2].opcode)
        assertEquals(FEED_FILTERS_FRAGMENT, backBody[2].reference)
        assertEquals(Opcode.IF_NEZ, backBody[3].opcode)
        assertEquals("the tab goes on to Facebook's code", 6, (backBody[3] as BuilderOffsetInstruction).target.location.index)
        assertEquals(Opcode.CONST_4, backBody[4].opcode)
        assertEquals(1, (backBody[4] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, backBody[5].opcode)
        assertEquals(Opcode.INVOKE_DIRECT, backBody[6].opcode)
        assertEquals("$FEED_FILTERS_FRAGMENT->getFeedFiltersController()Lfixture/Controller;", backBody[6].reference)
    }

    @Test
    fun `the Feeds tab's last feed type is the one static read handed unchanged to a FeedType lookup whose answer it keeps`() {
        val handler = feedsHandler()
        val anchors = feedsHandlerAnchors(handler)!!
        assertEquals(Opcode.SGET_OBJECT, handler.body()[anchors.read].opcode)
        assertEquals("Lfixture/Tracker;->last:$FEED_TYPE", handler.body()[anchors.read].reference)
        assertEquals(Opcode.MOVE_RESULT, handler.body()[anchors.answer].opcode)
        assertEquals(2, handler.body()[anchors.answer].register)
        assertNull("another method", feedsHandlerAnchors(feedsHandler(name = "handleIntent")))
        assertNull("reads no feed type", feedsHandlerAnchors(feedsHandler(lastType = "Lfixture/Other;")))
        assertNull("the lookup gets another value", feedsHandlerAnchors(feedsHandler(overwritten = true)))
        assertNull("the lookup's answer isn't kept", feedsHandlerAnchors(feedsHandler(keepsAnswer = false)))

        assertNull(feedsFragmentRefusal(feedsFragment()))
        assertTrue(feedsFragmentRefusal(feedsFragment(handler = null))!!.contains("found 0"))
        assertTrue(feedsFragmentRefusal(feedsFragment(handler = feedsHandler(overwritten = true)))!!.contains("reads no last feed type"))
        assertTrue(feedsFragmentRefusal(feedsFragment(resume = null))!!.contains("declares no onResume()V"))
        assertTrue(feedsFragmentRefusal(feedsFragment(resume = feedsResume(reusesThis = true)))!!.contains("reuses the register"))
        assertTrue(feedsFragmentRefusal(feedsFragment(back = null))!!.contains("declares no onBackPressed()Z"))
        assertTrue(feedsFragmentRefusal(feedsFragment(back = feedsBack(jumpsToStart = true)))!!.contains("jumps back to its start"))
    }

    /** A build whose Feeds tab can't take the filter hooks is refused before anything changes. */
    @Test
    fun `a build whose Feeds tab can't take the filter hooks is refused, naming what's missing`() {
        fun refusal(classes: List<ClassDef>): String {
            val context = PatchContexts.of(classes)
            val message = assertThrows(PatchException::class.java) { openOnChosenTabPatch.execute(context) }.message!!
            val onCreate = context.mutableClassDefBy(fragmentActivity).methods.single { it.name == "onCreate" }
            assertEquals("$message, yet the patch went in", 1, onCreate.body().size)
            val feeds = context.mutableClassDefBy(FEED_FILTERS_FRAGMENT)
            assertTrue(
                "$message, yet the Feeds tab changed",
                feeds.methods.flatMap { it.body() }.none {
                    it is ReferenceInstruction && it.reference.toString() in
                        setOf(FEEDS_RESUMED, FEED_TYPE_ASKED, FILTER_FOUND, FEEDS_PAGE_BACK)
                },
            )
            return message
        }

        val context = PatchContexts.of(build(feeds = null))
        val noFeeds = assertThrows(PatchException::class.java) { openOnChosenTabPatch.execute(context) }.message!!
        assertTrue(noFeeds, noFeeds.contains("this build has no $FEED_FILTERS_FRAGMENT"))

        val noAnswer = refusal(build(feeds = feedsFragment(handler = feedsHandler(keepsAnswer = false))))
        assertTrue(noAnswer, noAnswer.contains("reads no last feed type for a lookup whose answer it keeps"))

        val jumped = refusal(build(feeds = feedsFragment(handler = feedsHandler(jumpPast = true))))
        assertTrue(jumped, jumped.contains("has a jump past its read of the last feed type"))

        val jumpedAnswer = refusal(build(feeds = feedsFragment(handler = feedsHandler(jumpPastAnswer = true))))
        assertTrue(jumpedAnswer, jumpedAnswer.contains("has a jump past its lookup's answer"))

        val backLoops = refusal(build(feeds = feedsFragment(back = feedsBack(jumpsToStart = true))))
        assertTrue(backLoops, backLoops.contains("onBackPressed jumps back to its start"))
    }

    @Test
    fun `a build whose picker can't be told apart is refused, naming the extra`() {
        val none = assertThrows(PatchException::class.java) {
            openOnChosenTabPatch.execute(PatchContexts.of(activities() + ExtensionDex.classDef(SETTINGS_STATUS)))
        }
        assertTrue(none.message, none.message!!.contains("\"$TARGET_TAB_ID\", found 0"))

        val two = assertThrows(PatchException::class.java) {
            openOnChosenTabPatch.execute(
                PatchContexts.of(build() + classOf(picker(owner = "Lfixture/SecondPicker;"))),
            )
        }
        assertTrue(two.message, two.message!!.contains("found 2"))
    }

    /** A build missing a start-up step, or with two, is refused before anything changes. */
    @Test
    fun `a build whose start-up steps can't be told apart is refused, naming the step`() {
        fun refusal(classes: List<ClassDef>): String {
            val context = PatchContexts.of(classes)
            val message = assertThrows(PatchException::class.java) { openOnChosenTabPatch.execute(context) }.message!!
            val onCreate = context.mutableClassDefBy(fragmentActivity).methods.single { it.name == "onCreate" }
            assertEquals("$message, yet the patch went in", 1, onCreate.body().size)
            return message
        }

        val noStep = refusal(build(sanitizers = emptyList()))
        assertTrue(noStep, noStep.contains("\"$SANITIZE_INTENT\"), found 0"))

        val noGate = refusal(build(positions = listOf(startPosition(branchOn = "v1"))))
        assertTrue(noGate, noGate.contains("\"$START_POSITION\"") && noGate.contains("found 0"))

        val twoChecks = refusal(build(checks = listOf(keepsCheck(), keepsCheck(owner = "Lfixture/OtherViews;"))))
        assertTrue(twoChecks, twoChecks.contains("keeps the start tab") && twoChecks.contains("found 2"))

        val noCheck = refusal(build(checks = listOf(inlineKeepCheck(resetField = "lastTab"))))
        assertTrue(noCheck, noCheck.contains("keeps the start tab") && noCheck.contains("found 0 and 0"))

        val twoInline = refusal(build(checks = listOf(inlineKeepCheck(), inlineKeepCheck(owner = "Lfixture/OtherStartUp;"))))
        assertTrue(twoInline, twoInline.contains("found 0 and 2"))
    }

    @Test
    fun `a gate whose branch something else jumps to is refused`() {
        val context = PatchContexts.of(build(positions = listOf(startPosition(jumpToBranch = true))))
        val refused = assertThrows(PatchException::class.java) { openOnChosenTabPatch.execute(context) }
        assertTrue(refused.message, refused.message!!.contains("has a jump to its start position gate's branch"))
    }

    private companion object {
        const val ACTIVITY_SET_INTENT = "Landroid/app/Activity;->setIntent(Landroid/content/Intent;)V"
    }
}
