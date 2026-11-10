/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/*
 * Which Menu the side panel shows (#184). Read from 583, 2026-10-20; the obfuscated names are for
 * reviewers, the code never writes one down.
 *
 * Some accounts open the Menu as a panel sliding in from the side. The panel is set up by
 * MainViewControllers.maybeSetUpSlidingPanelController (arm64 and armv7 `LX/23C;->A03`), which asks
 * MobileConfig 0x10101a200581627 once through MobileConfigUnsafeContext (arm64 `B68(J)Z`, armv7
 * `B5r(J)Z`). A yes reads the Bloks Menu's singleton (arm64 `LX/4f7;`, armv7 `LX/4fW;`) into the panel,
 * a server-drawn page (BloksBookmarksMenuPanelFragment, arm64 `6IV`, armv7 `6Ix`) none of the Menu
 * hooks reach. A no jumps to `new LX/23f;(FbUserSession, LX/23R;)`, the native Menu the Menu tab shows
 * (fragment type 20), the one Hide Menu promotions edits. The Bloks Menu is shown nowhere else: its
 * prefetch job (BloksBookmarksMenuPrefetchAppJob) asks the same flag. A later static read of the flag
 * in the same method only names a perf marker.
 *
 * 584 (2026-10-22) asks flag 0x10101a20058164c instead, in arm64 `LX/1ym;->A02`, through a static
 * helper on MobileConfigUnsafeContext that takes the context as an Object and casts it itself
 * (`A0b(Object, J)Z`, the one the factory below now uses too). The rest is 583's: a yes reads
 * `LX/4Ms;`'s singleton, a no builds `LX/1yv;` from the session.
 *
 * The method is found by its trace name and the panel key it loads, and the answer by its shape: the
 * one `move-result` of a MobileConfigUnsafeContext call asking a boolean by a long (an interface
 * call taking the long, or 584's static helper taking the context and the long), tested at once by
 * an `if-eqz`, whose fall-through soon reads a singleton (a static field typed as its own class) and
 * whose branch soon builds something from the user session.
 *
 * The native Menu `LX/23f;` puts in the panel is fragment type 20, and its kept factory,
 * BookmarksFragmentFactory.createFragment, asks the same flag again through a static helper (arm64
 * `LX/3gt;->A1b(Object, J)Z`): a yes builds the kept BloksBookmarksMenuTabFragment, a no the native
 * Menu (arm64 `LX/Bdh;`). So an account the server gives the Bloks Menu got it back inside the swapped
 * panel, and in its Menu tab too (#184's 0.16.0 report). That answer is found by its shape: a boolean
 * `move-result` tested at once by an `if-eqz` whose fall-through builds BloksBookmarksMenuTabFragment.
 */
internal const val PANEL_SETUP = "MainViewControllers.maybeSetUpSlidingPanelController"
internal const val PANEL_KEY = "bookmarks_panel"
internal const val BLOKS_PANEL = "$EXTENSION_PACKAGE/menu/MenuPanel;->bloksPanel(Z)Z"
internal const val MENU_FACTORY = "Lcom/facebook/bookmark/fragment/BookmarksFragmentFactory;"
internal const val BLOKS_MENU_PAGE = "Lcom/facebook/bookmark/fragment/BloksBookmarksMenuTabFragment;"
internal const val BLOKS_MENU = "$EXTENSION_PACKAGE/menu/MenuPanel;->bloksMenu(Z)Z"
internal const val CREATE_FRAGMENT = "createFragment"

private const val MOBILE_CONFIG = "/MobileConfigUnsafeContext;"

/** How far past the test, and past the branch's landing, the singleton read and the session build are looked for. */
private const val NEAR = 6

/**
 * The methods outside the extension naming [PANEL_KEY], straight or by asking a string table for it
 * ([methodsNaming]), that load [PANEL_SETUP] or that a method loading it calls directly in its own
 * class. 586's Android 9 build does both: its setup (`LX/02Kv;->A03`) only wraps the trace around a
 * private worker (`A02`), which asks a table for the key and the flag (0x101019f005816c8).
 */
internal fun BytecodePatchContext.menuPanelSetups(): List<Method> {
    val setups = classDefByStrings(PANEL_SETUP, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { owner -> owner.methods.filter { holdsString(it, PANEL_SETUP) } }
    val workers = setups.flatMap { setup ->
        setup.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
            if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) return@mapNotNull null
            ((instruction as ReferenceInstruction).reference as? MethodReference)?.takeIf { it.definingClass == setup.definingClass }
        }
    }
    val owners = setups.map { it.definingClass }.toSet()
    return methodsNaming(PANEL_KEY) { method ->
        method.definingClass in owners &&
            (holdsString(method, PANEL_SETUP) || workers.any { it.definingClass == method.definingClass && MethodUtil.methodSignaturesMatch(it, method) })
    }
}

/** The index of the `move-result` holding the panel's Bloks answer in [method], or null unless there's exactly one. */
internal fun bloksPanelAnswer(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    return code.indices.filter { isBloksPanelAnswer(code, it) }.singleOrNull()
}

/** The `move-result`s in [code] of a MobileConfig boolean asked by a long. */
internal fun mobileConfigAnswers(code: List<Instruction>): List<Int> = code.indices.filter { isMobileConfigAnswer(code, it) }

private fun isMobileConfigAnswer(code: List<Instruction>, index: Int): Boolean {
    if (index < 1 || code[index].opcode != Opcode.MOVE_RESULT) return false
    val asked = code[index - 1]
    val parameters = when (asked.opcode) {
        Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> listOf("J")
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> listOf("Ljava/lang/Object;", "J")
        else -> return false
    }
    val call = (asked as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.definingClass.endsWith(MOBILE_CONFIG) && call.returnType == "Z" &&
        call.parameterTypes.map(CharSequence::toString) == parameters
}

private fun isBloksPanelAnswer(code: List<Instruction>, index: Int): Boolean {
    if (!isMobileConfigAnswer(code, index)) return false
    val register = (code[index] as OneRegisterInstruction).registerA
    val test = code.getOrNull(index + 1) ?: return false
    if (test.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != register) return false
    val native = branchTarget(code, index + 1) ?: return false
    return code.near(index + 2).any(::readsSingleton) && code.near(native).any(::buildsFromSession)
}

private fun List<Instruction>.near(from: Int) = subList(from, minOf(from + NEAR, size))

/** An `sget-object` of a field typed as the class holding it. */
private fun readsSingleton(instruction: Instruction): Boolean {
    if (instruction.opcode != Opcode.SGET_OBJECT) return false
    val field = (instruction as ReferenceInstruction).reference as? FieldReference ?: return false
    return field.type == field.definingClass
}

/** A constructor call whose first parameter is the user session. */
private fun buildsFromSession(instruction: Instruction): Boolean {
    if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) return false
    val call = (instruction as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.name == "<init>" && call.parameterTypes.firstOrNull()?.toString() == USER_SESSION
}

/**
 * Hands the panel's Bloks answer to the extension as it comes back, in its own register, so a no
 * puts the native Menu in the panel. Refuses unless one method sets the panel up, its answer has the
 * shape above, and nothing jumps to the test past the hook.
 */
internal fun BytecodePatchContext.askAboutBloksPanel() {
    val setups = menuPanelSetups()
    val method = setups.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method loading \"$PANEL_SETUP\" and \"$PANEL_KEY\", found ${setups.size}")
    val answer = bloksPanelAnswer(method)
        ?: throw PatchException("$PATCH: ${method.definingClass}->${method.name} doesn't ask once whether its panel is the Bloks Menu")
    askAt(method, answer, BLOKS_PANEL, "the panel's")
}

/** The Menu page factory's `createFragment`, or null when the build has no such factory. */
internal fun BytecodePatchContext.menuPageFactory(): Method? =
    classDefByOrNull(MENU_FACTORY)?.methods?.singleOrNull { it.name == CREATE_FRAGMENT }

/** The index of the `move-result` deciding whether [method] builds the Bloks Menu page, or null unless there's exactly one. */
internal fun bloksMenuPageAnswer(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    return code.indices.filter { isBloksMenuPageAnswer(code, it) }.singleOrNull()
}

private fun isBloksMenuPageAnswer(code: List<Instruction>, index: Int): Boolean {
    if (index < 1 || code[index].opcode != Opcode.MOVE_RESULT) return false
    val asked = (code[index - 1] as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (asked.returnType != "Z") return false
    val register = (code[index] as OneRegisterInstruction).registerA
    val test = code.getOrNull(index + 1) ?: return false
    if (test.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != register) return false
    val built = code.getOrNull(index + 2) ?: return false
    return built.opcode == Opcode.NEW_INSTANCE &&
        ((built as ReferenceInstruction).reference as? TypeReference)?.type == BLOKS_MENU_PAGE
}

/**
 * Hands the Menu page factory's Bloks answer to the extension the same way, so a no builds the native
 * Menu page in the Menu tab and in a swapped panel. Refuses unless the factory has one such answer and
 * nothing jumps to its test past the hook.
 */
internal fun BytecodePatchContext.askAboutBloksMenuPage() {
    val method = menuPageFactory()
        ?: throw PatchException("$PATCH: expected $MENU_FACTORY with one $CREATE_FRAGMENT")
    val answer = bloksMenuPageAnswer(method)
        ?: throw PatchException("$PATCH: ${method.definingClass}->${method.name} doesn't ask once whether to build $BLOKS_MENU_PAGE")
    askAt(method, answer, BLOKS_MENU, "the Menu page's")
}

private fun BytecodePatchContext.askAt(method: Method, answer: Int, hook: String, whose: String) {
    val register = (method.implementation!!.instructions.elementAt(answer) as OneRegisterInstruction).registerA
    val mutable = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
    val test = mutable.implementation!!.instructions[answer + 1] as BuilderInstruction
    if (test.location.labels.isNotEmpty()) {
        throw PatchException("$PATCH: ${method.definingClass}->${method.name} jumps to $whose test past its answer")
    }
    mutable.addInstructions(
        answer + 1,
        """
            invoke-static/range { v$register .. v$register }, $hook
            move-result v$register
        """,
    )
}
