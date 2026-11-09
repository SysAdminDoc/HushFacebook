/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Where a tap on the Messenger icon in Facebook's top bar goes, on the 577 and 580 builds.
 *
 * The icon's click listener and its long-click listener both call one static tap method with the
 * context, the user session, four renamed helpers, the surface the icon sits on and three flags.
 * The second flag is the long press: the click listener hands it 0 and the long-click listener 1.
 * The long-click listener only exists behind a MobileConfig flag (580 0x8105b2000f2771, 577
 * 0x8105b8000f276a), which also gives the top bar's touch listener the gesture detector that calls
 * it. Without the flag, a press lifted on the icon is a click however long it was held, so a long
 * press reaches the tap with 0.
 * The method is the only one in either build that loads both
 * "entry_point_navbar_global_icon_reels_tab" and "entry_point_navbar_global_icon_", the entry
 * points it names the tap by.
 *
 * Behind a MobileConfig flag the tap goes on to a shared static Messenger button handler,
 * (Context, FbUserSession, String entry point, Z long press, Z)V, which the older title bar and
 * the Professional dashboard's Messenger icon call too. It logs "long_press" only for a long press,
 * and it's the one static method of that shape loading that string. Without the flag the tap opens
 * Chats itself. Both routes end in Facebook's own Chats (InboxActivity), and a long press there
 * opens a preview sheet of chats where Facebook offers one.
 *
 * Every class and method name here is Redex's (580's tap is LX/21h;->A01 and its handler
 * LX/cuh;->A00, 577's LX/3Ee;->A01 and LX/ZsI;->A00), and the Messenger activity's own name moved
 * from the tap into the handler between the two builds. So the tap is found by its entry points
 * and its shape, and the handler as the one static method of the handler's shape the tap calls.
 * 582 (tap LX/28V;->A01) puts a cooldown wrapper of the same shape in front of it, an instance
 * method on the handler class's singleton, so the tap's call is followed through that wrapper.
 * 583's wrapper (LX/bEQ;->A02) hands a handler that takes one more String, a null from the
 * wrapper, after the entry point (LX/bEQ;->A01), and the "long_press" log moved out of the handler
 * into a static helper of its class that the handler hands its long press (LX/bEQ;->A00).
 */
internal const val ICON_PATCH = "Open Messenger from the top bar"

/** The prefix the tap puts in front of the surface to name its entry point. */
internal const val NAVBAR_ENTRY = "entry_point_navbar_global_icon_"

/** The entry point the tap hands the handler for the icon on the Reels tab. */
internal const val REELS_TAB_ENTRY = "entry_point_navbar_global_icon_reels_tab"

/** The key the handler logs a long press under. */
internal const val LONG_PRESS = "long_press"

/**
 * The tap's long-press parameter up to 585: 0 from the click listener, 1 from the long-click
 * listener. 586 swaps the last two flags, so the patch reads it per build with [tapLongPress].
 */
internal const val TAP_LONG_PRESS = 8

private const val ANDROID_CONTEXT = "Landroid/content/Context;"
private const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

internal val BUTTON_PARAMETERS = listOf(ANDROID_CONTEXT, FB_USER_SESSION, "Ljava/lang/String;", "Z", "Z")

/** The handler's shapes: [BUTTON_PARAMETERS] up to 582, and 583's with a second String after the entry point. */
internal val HANDLER_SHAPES = listOf(BUTTON_PARAMETERS, listOf(ANDROID_CONTEXT, FB_USER_SESSION, "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Z"))

/** A handler's (or a call of one's) long-press parameter, the first of its two closing flags, which the tap fills from its own. */
internal fun buttonLongPress(handler: MethodReference): Int = handler.parameterTypes.size - 2

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun Method.isStaticVoidWithBody(): Boolean =
    implementation != null && AccessFlags.STATIC.isSet(accessFlags) && returnType == "V"

/**
 * The icon tap's shape: a static void method over the context, the user session, four helpers, the
 * surface and three flags.
 */
internal fun isIconTapShaped(method: Method): Boolean {
    val parameters = method.parameters()
    return method.isStaticVoidWithBody() && parameters.size == 10 && parameters[0] == ANDROID_CONTEXT &&
        parameters[1] == FB_USER_SESSION && parameters[6] == "Ljava/lang/String;" &&
        parameters.subList(7, 10).all { it == "Z" }
}

/**
 * The icon's tap: [isIconTapShaped], naming both entry points. 586 asks a Redex string table for the
 * Reels tab's (`LX/07fb;->A00` on arm64, tap `LX/02GY;->A01`), which [resolve] finds.
 */
internal fun isIconTap(method: Method, resolve: (MethodReference) -> Method? = { null }): Boolean =
    isIconTapShaped(method) && namesString(method, NAVBAR_ENTRY, resolve) && namesString(method, REELS_TAB_ENTRY, resolve)

/** Whether [method] has a Messenger button handler's shape: static void over one of [HANDLER_SHAPES]. */
internal fun isButtonHandlerShaped(method: Method): Boolean =
    method.isStaticVoidWithBody() && method.parameters() in HANDLER_SHAPES

/**
 * Where [handler], a method of [owner], loads "long_press": the handler itself up to 582, and on
 * 583 the one static helper of [owner] it calls with a flag that loads it. The helper isn't
 * handler-shaped itself, so a method that only calls the handler doesn't log through it. Null when
 * neither does.
 */
internal fun longPressLog(handler: Method, owner: ClassDef): Method? {
    if (holdsString(handler, LONG_PRESS)) return handler
    return handler.implementation?.instructions?.toList().orEmpty().filter { it.opcode in STATIC_CALLS }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter { it.definingClass == owner.type && it.parameterTypes.any { type -> type.toString() == "Z" } }
        .distinctBy { it.toString() }
        .mapNotNull { resolveStatic(owner, it) }
        .filter { !isButtonHandlerShaped(it) && holdsString(it, LONG_PRESS) }
        .singleOrNull()
}

/** The Messenger button handler: shaped like one ([isButtonHandlerShaped]) and logging a long press ([longPressLog]). */
internal fun isButtonHandler(method: Method, owner: ClassDef): Boolean =
    isButtonHandlerShaped(method) && longPressLog(method, owner) != null

private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

private val PLAIN_MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

private fun MethodReference.sameAs(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

/** The register holding argument [index] of [call], all of whose arguments up to it are narrow. */
private fun argumentRegister(call: Instruction, index: Int): Int? = when (call) {
    is RegisterRangeInstruction -> call.startRegister + index
    is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).getOrNull(index)
    else -> null
}

/**
 * The tap's long-press parameter: the flag it hands [call], its button handler call, as the long
 * press, followed back through plain moves to the parameter it came from. 580 to 585 take it as
 * parameter 8 ([TAP_LONG_PRESS]), and 586 as 9 (`LX/02GY;->A01` on arm64 moves v28 down for
 * `LX/0hea;->A02`). Null when that argument isn't one of the tap's flags.
 */
internal fun tapLongPress(tap: Method, call: MethodReference): Int? {
    val code = tap.implementation?.instructions?.toList() ?: return null
    val at = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.sameAs(call) == true }
    if (at < 0) return null
    val receiver = if (code[at].opcode in STATIC_CALLS) 0 else 1
    var register = argumentRegister(code[at], buttonLongPress(call) + receiver) ?: return null
    var before = at
    while (true) {
        val write = (before - 1 downTo 0).firstOrNull {
            code[it].opcode.setsRegister() && (code[it] as? OneRegisterInstruction)?.registerA == register
        } ?: break
        if (code[write].opcode !in PLAIN_MOVES) return null
        register = (code[write] as TwoRegisterInstruction).registerB
        before = write
    }
    return tap.parameterTypes.indices.firstOrNull { tap.parameterRegisterNumber(it) == register }
        ?.takeIf { tap.parameterTypes[it].toString() == "Z" }
}

/** The calls in [method] of a method with one of [shapes], answering nothing, among [opcodes]. */
private fun handlerShapedCalls(method: Method, opcodes: Set<Opcode>, shapes: List<List<String>>): List<MethodReference> =
    method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        if (instruction.opcode !in opcodes) return@mapNotNull null
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        call.takeIf { it.returnType == "V" && it.parameterTypes.map { type -> type.toString() } in shapes }
    }

/**
 * The calls in [tap] of a method with the button handler's shape: static up to 581, and from 582 a
 * virtual call on the handler's singleton (see [buttonHandler]).
 */
internal fun buttonHandlerCalls(tap: Method): List<MethodReference> =
    handlerShapedCalls(tap, STATIC_CALLS + setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE), HANDLER_SHAPES)

/**
 * The button handler the tap's [call] reaches in [owner], the call's class: the method it names
 * when that's the handler, or else, when it names an instance method of the handler's shape with
 * exactly one static call of a handler's shape into [owner], the method that call names. 582's tap
 * calls such a wrapper on a singleton (LX/eh9;->A01), which waits out a MobileConfig cooldown and
 * then calls the static handler (LX/eh9;->A00) with its own arguments. 583's wrapper adds a null
 * String to them.
 */
internal fun buttonHandler(owner: ClassDef, call: MethodReference): Method? {
    val named = owner.methods.firstOrNull {
        it.name == call.name && it.returnType == call.returnType &&
            it.parameters() == call.parameterTypes.map { type -> type.toString() }
    } ?: return null
    if (isButtonHandler(named, owner)) return named
    if (AccessFlags.STATIC.isSet(named.accessFlags)) return null
    val inner = handlerShapedCalls(named, STATIC_CALLS, HANDLER_SHAPES).filter { it.definingClass == owner.type }
        .distinctBy { it.toString() }
    return inner.singleOrNull()?.let { resolveStatic(owner, it) }?.takeIf { isButtonHandler(it, owner) }
}
