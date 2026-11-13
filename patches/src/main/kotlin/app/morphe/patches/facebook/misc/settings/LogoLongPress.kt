/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.requireThisIntact
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * The home feed's top bar, the view that builds the Facebook logo. The class keeps this name in
 * every build checked (573, 577 and 580) while Redex renames its methods: the one that builds the
 * logo is `A0C` in 573, `A0D` in 577 and `A0I` in 580. Whichever layout the bar shows, the logo is
 * the view that method builds, since the newer bar (`com.facebook.navigation.navbar.NavigationBar`)
 * takes that view from it too.
 */
internal const val WORDMARK_NAVIGATION_BAR = "Lcom/facebook/navigation/navbar/legacy/search/WordmarkNavigationBar;"

/**
 * The bar across the top of Facebook's tabs. Notifications, Friends, Marketplace and the other tabs
 * draw a menu button and their title in it (#374), and the home feed's logo bar sits in one too.
 * Facebook keeps the name in 585. Its constructors that take fewer arguments hand on to the one
 * taking a context, attributes and a style.
 */
internal const val NAVIGATION_BAR = "Lcom/facebook/navigation/navbar/NavigationBar;"

/** SettingsEntry's method each new [NAVIGATION_BAR] is handed to as its constructor ends. */
internal const val TITLE_BAR_MADE = "$ENTRY->watchTitleBar(Landroid/view/View;)V"

/** The trace section the bar opens while it builds the logo. Only the method that does holds it. */
internal const val CREATE_WORDMARK_VIEW = "WordmarkNavigationBar#createWordmarkView"

private const val ON_CLICK_LISTENER = "Landroid/view/View\$OnClickListener;"
private const val ON_TOUCH_LISTENER = "Landroid/view/View\$OnTouchListener;"

/** How Facebook gives the logo its tap. The call right after it gives the logo its touch listener. */
internal const val LOGO_CLICK_CALL = "Landroid/view/View;->setOnClickListener($ON_CLICK_LISTENER)V"

/**
 * SettingsEntry's stand-in for Facebook's call that gives the logo its touch listener: the logo,
 * then Facebook's listener, which is null unless Facebook reads the logo's gestures itself.
 */
internal const val LOGO_TOUCH_STAND_IN = "$ENTRY->setLogoTouchListener(Landroid/view/View;$ON_TOUCH_LISTENER)V"

/**
 * SettingsEntry's stand-in for the call that hides the logo from accessibility on the builder's
 * path that gives it no tap: the logo, then Facebook's importance. 583 takes that path in Arabic,
 * jumping past the tap, the touch listener and the description, so the long press goes in here.
 */
internal const val LOGO_WITHOUT_TAP_STAND_IN = "$ENTRY->setLogoWithoutTap(Landroid/view/View;I)V"

/** Whether this is a framework View call named [name] that takes one [listener] and answers nothing. */
private fun Instruction.setsListener(name: String, listener: String): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return call.name == name && call.returnType == "V" && call.definingClass.startsWith("Landroid/") &&
        call.parameterTypes.map { it.toString() } == listOf(listener)
}

/** The register a call is made on: its first. */
private fun Instruction.receiver(): Int = when (this) {
    is RegisterRangeInstruction -> startRegister
    is FiveRegisterInstruction -> registerC
    else -> -1
}

/**
 * Where [builder], the bar's method that builds the logo, gives the logo its touch listener: the
 * instruction right after the method's one click listener, on the same view. 573, 577 and 580 set
 * the logo's click listener, then its touch listener, then its content description, in that order.
 * The click listener is Facebook's tap, and it stays as it is.
 */
internal fun logoTouchListenerIndex(builder: Method): Int {
    val where = "${builder.definingClass}->${builder.name}"
    val instructions = builder.implementation?.instructions?.toList()
        ?: throw PatchException("$where, the method building the Facebook logo, has no body")
    val clicks = instructions.indices.filter { instructions[it].setsListener("setOnClickListener", ON_CLICK_LISTENER) }
    val click = clicks.singleOrNull() ?: throw PatchException(
        "Expected $where to give one view a click listener, the Facebook logo; it gives ${clicks.size}",
    )
    val touch = instructions.getOrNull(click + 1)
    if (touch == null || !touch.setsListener("setOnTouchListener", ON_TOUCH_LISTENER)) {
        throw PatchException(
            "In $where the Facebook logo's click listener is no longer followed by its touch listener, " +
                "the call the long press goes in through",
        )
    }
    if (touch.receiver() != instructions[click].receiver()) {
        throw PatchException(
            "In $where the touch listener after the Facebook logo's click listener goes to another view " +
                "(v${touch.receiver()}, not v${instructions[click].receiver()})",
        )
    }
    return click + 1
}

/**
 * Where [builder] hides the logo from accessibility on its path that gives the logo no tap, or
 * null when it has no such path. 583 has one: when none of its three ways to read the logo's tap
 * is on (seen in Arabic), it makes the logo unimportant for accessibility and jumps past its tap,
 * its touch listener and its description, so the logo stays disabled with no listener. It's the
 * one `setImportantForAccessibility` call on the logo ahead of the logo's tap. 587 jumps the other
 * way, to a call below the description that the path with the tap jumps over (`A0I` at 140), so a
 * call after the touch listener counts too while the logo's register still holds the logo.
 */
internal fun logoWithoutTapIndex(builder: Method): Int? {
    val where = "${builder.definingClass}->${builder.name}"
    val touch = logoTouchListenerIndex(builder)
    val click = touch - 1
    val instructions = builder.implementation!!.instructions.toList()
    val logo = instructions[click].receiver()
    val below = (touch + 1 until instructions.size).takeWhile { !writes(instructions[it], logo) }
    val hides = ((0 until click) + below).filter { index ->
        val instruction = instructions[index]
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@filter false
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        call.name == "setImportantForAccessibility" && call.definingClass.startsWith("Landroid/") &&
            call.parameterTypes.map { it.toString() } == listOf("I") && call.returnType == "V" &&
            instruction.receiver() == logo
    }
    if (hides.size > 1) {
        throw PatchException(
            "Expected $where to hide the Facebook logo from accessibility at most once around its tap; it does ${hides.size} times",
        )
    }
    return hides.singleOrNull()
}

/** Whether [instruction] can write [register], a wide write counting for both halves. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    val opcode = instruction.opcode
    if (!opcode.setsRegister() || instruction !is OneRegisterInstruction) return false
    return instruction.registerA == register || (opcode.setsWideRegister() && instruction.registerA + 1 == register)
}

/**
 * Sends Facebook's call that gives the Facebook logo its touch listener to SettingsEntry, which
 * makes the call with a listener that opens the Hushfacebook screen on a long press, or with
 * Facebook's own when Facebook passes one. On a build whose builder has a path giving the logo no
 * tap, it sends that path's accessibility call to SettingsEntry too, which puts the long press on
 * the logo there. Every name used is kept: the bar's class, the trace section its logo builder
 * opens and the framework's view calls. Stops the patch, naming what it missed, when the evidence
 * isn't there.
 */
internal fun BytecodePatchContext.hookLogoLongPress() {
    val bar = mutableClassDefByOrNull(WORDMARK_NAVIGATION_BAR)
        ?: throw PatchException("This Facebook has no $WORDMARK_NAVIGATION_BAR, the home feed's top bar")
    val builders = bar.methods.filter { holdsString(it, CREATE_WORDMARK_VIEW) }
    val builder = builders.singleOrNull() ?: throw PatchException(
        "Expected one method of $WORDMARK_NAVIGATION_BAR holding \"$CREATE_WORDMARK_VIEW\", found ${builders.size}",
    )
    val touch = logoTouchListenerIndex(builder)
    val withoutTap = logoWithoutTapIndex(builder)
    builder.sendToStandIn(touch, LOGO_TOUCH_STAND_IN)
    withoutTap?.let { builder.sendToStandIn(it, LOGO_WITHOUT_TAP_STAND_IN) }
}

/** The constructors of [NAVIGATION_BAR] that build the bar themselves, not by calling another of its own. */
internal fun titleBarConstructors(bar: ClassDef): List<Method> = bar.methods.filter { method ->
    method.name == "<init>" && method.implementation?.instructions?.none { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            call?.name == "<init>" && call.definingClass == NAVIGATION_BAR
    } == true
}

/**
 * Hands each new title bar to SettingsEntry as its constructor ends, which gives the bar the same
 * long press as the logo (#374). The call goes in at each return of each constructor that builds
 * the bar itself, so a bar is handed over once whichever constructor Facebook calls. The call
 * replaces the return and a new return follows it, so a jump to the old return reaches the call
 * too. `this` is the register past the locals, and nothing may write it before a return. Stops the
 * patch, naming what it missed, when the evidence isn't there.
 */
internal fun BytecodePatchContext.hookTitleBarLongPress() {
    val bar = mutableClassDefByOrNull(NAVIGATION_BAR)
        ?: throw PatchException("This Facebook has no $NAVIGATION_BAR, the bar across the top of its tabs")
    val builds = titleBarConstructors(bar).map { it as MutableMethod }
    if (builds.isEmpty()) throw PatchException("$NAVIGATION_BAR has no constructor that builds the bar itself")
    builds.forEach { constructor ->
        val where = "$NAVIGATION_BAR-><init>(${constructor.parameterTypes.joinToString("")})"
        val code = constructor.implementation!!.instructions.toList()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        if (returns.isEmpty()) throw PatchException("$where never returns")
        constructor.requireThisIntact("Title bar long press, $where", returns)
        val self = constructor.localRegisterCount()
        returns.asReversed().forEach { at ->
            constructor.replaceInstruction(at, "invoke-static/range { v$self .. v$self }, $TITLE_BAR_MADE")
            constructor.addInstruction(at + 1, "return-void")
        }
    }
}
