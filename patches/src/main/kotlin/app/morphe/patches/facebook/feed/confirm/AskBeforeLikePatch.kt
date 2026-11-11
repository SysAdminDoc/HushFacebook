/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.confirm

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The Like handlers (#437, #443). Read from 586, 2026-11-10, arm64; the obfuscated names here are
 * for reviewers, the code never writes one down.
 *
 * - A feed post's footer built as a litho component likes through the one method holding the
 *   BaseReactionsFooterHelper litho trace name (LX/0Cab;->A0B), an instance method returning void.
 * - That footer's Like button is clicked through a lambda (LX/03kN;->invoke, taking the click
 *   event) that calls A0B and then paints the button liked and keeps that in the button's
 *   controller. Held at A0B alone, the like stayed off but the button showed liked until the post
 *   loaded again, so the lambda asks first too: the one invoke(Object) returning Object that calls
 *   A0B.
 * - The footer built from UFI plugins likes through a merged Kotlin lambda (LX/04CD;->invoke). Its
 *   int field picks the arm right at the top, and the LikeButtonFeedUFIFooterItemPlugin trace name
 *   sits in the Like arm, so the hook only asks when the field picks that arm.
 * - The same footer's touch and click handlers like through a static helper holding that trace
 *   name too (LX/04LZ;->A02, taking the session, the feedback and the component context). It takes
 *   no view, so the extension finds the screen in a context one of its arguments keeps.
 * - A reel's sidebar Like is the static (View, its own type, boolean) method holding the
 *   LikeButtonComponent name (LX/0CAK;->A00).
 * - A comment's Like is the lambda holding the LikePluginUtil trace name (LX/04om;->invoke), which
 *   returns Unit.
 *
 * The comment actions drawn inline under a feed post (LX/069v, a lambda merged behind a packed
 * switch) aren't hooked.
 *
 * Each hook goes first thing. It hands the extension the handler's own descriptor, the instance and
 * the arguments, and returns at once when the extension holds the tap. Continue calls the same
 * handler again by reflection, which the extension lets through.
 */

private const val PATCH = "Ask before a like"

internal const val ASK_BEFORE_LIKE = "$EXTENSION_PACKAGE/feed/AskBeforeLike;"
internal const val HOLD = "$ASK_BEFORE_LIKE->hold(ILjava/lang/String;[Ljava/lang/Object;)Z"

internal const val LITHO_FOOTER_TRACE = "BaseReactionsFooterHelper.onLikeButtonClicked_litho_footer"
internal const val PLUGIN_FOOTER_TRACE = "LikeButtonFeedUFIFooterItemPlugin.onReactionSelected"
internal const val REEL_LIKE_NAME = "LikeButtonComponent"
internal const val COMMENT_LIKE_TRACE = "LikePluginUtil.onReactionLikeClicked"

/** The kinds AskBeforeLike tells apart, its own constants. */
internal const val POST_FOOTER = 0
internal const val POST_PLUGIN = 1
internal const val REEL = 2
internal const val COMMENT = 3
internal const val POST_TOUCH = 4
internal const val POST_CLICK = 5

private const val VIEW = "Landroid/view/View;"
private const val SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val FEEDBACK = "Lcom/facebook/graphql/model/GraphQLFeedback;"
private const val OBJECT = "Ljava/lang/Object;"
private const val UNIT = "Lkotlin/Unit;"

/** Registers the hook borrows: v0 to v4, free before the handler's own first instruction. */
private const val BORROWED = 5

private val CONST_STRINGS = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)

/** The int field read at the top of a merged lambda and the branch that skips the hook when it picks the other arm. */
internal data class Arm(val field: String, val skip: String)

/** A Like handler of [kind], with the [arm] guard a merged lambda needs. */
internal data class LikeHandler(val kind: Int, val method: Method, val arm: Arm? = null)

/**
 * Asks "Like or unlike this post?" before a tap on Like goes through, with a switch each for posts,
 * reels and comments (#437, #443).
 *
 * In the default selection with all three switches off, so nothing changes until one is turned on.
 */
@Suppress("unused")
val askBeforeLikePatch = bytecodePatch(
    name = "Ask before a like",
    description = "Switches that make a tap on Like under a post, beside a reel or under a comment ask first, and " +
        "only Continue likes or unlikes it, so a stray tap doesn't. All three start off. Turn them on in " +
        "Hushfacebook settings > News feed, Reels and Watch, and Comments and writing.",
) {
    category("Feed")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("askBeforeLike")
        askBeforeLike()
        enableStatus("askBeforeLike")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.code() = implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.loads(string: String) =
    opcode in CONST_STRINGS && ((this as ReferenceInstruction).reference as StringReference).string == string

/** The methods of the app holding [string]. */
private fun BytecodePatchContext.methodsHolding(string: String): List<Method> =
    classDefByStrings(string, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef -> classDef.methods.filter { holdsString(it, string) } }

/** The one method of the app holding [string]. */
private fun BytecodePatchContext.methodHolding(string: String, what: String): Method {
    val found = methodsHolding(string)
    return found.singleOrNull() ?: refuse("expected one $what holding \"$string\", found ${found.size}")
}

/** The reel's Like: static, taking the view, an instance of its own class and a boolean. */
internal fun isReelLike(method: Method) =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.map(Any::toString) == listOf(VIEW, method.definingClass, "Z")

/** The plugin footer's merged lambda: invoke(Object, Object) returning Object. */
internal fun isMergedLambda(method: Method) =
    method.name == "invoke" && method.returnType == OBJECT &&
        method.parameterTypes.map(Any::toString) == listOf(OBJECT, OBJECT)

/** The plugin footer's static helper that sends the like: session first, the feedback among its arguments. */
internal fun isLikeSender(method: Method) =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.firstOrNull()?.toString() == SESSION && method.parameterTypes.any { it.toString() == FEEDBACK }

/** Whether this instruction calls [method]. */
private fun Instruction.calls(method: Method): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.definingClass == method.definingClass && reference.name == method.name &&
        reference.returnType == method.returnType &&
        reference.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
}

/** The litho footer's click lambda: invoke(Object) returning Object, calling the footer's Like [footer]. */
internal fun isClickLambda(method: Method, footer: Method) =
    method.name == "invoke" && method.returnType == OBJECT &&
        method.parameterTypes.map(Any::toString) == listOf(OBJECT) && method.code().any { it.calls(footer) }

/** The descriptor the extension finds the handler again by, Lowner;->name(params)ret. */
internal fun descriptorOf(method: Method) =
    "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

/** Each instruction's index from its code address. */
private fun indexOfAddress(code: List<Instruction>): Map<Int, Int> =
    code.runningFold(0) { address, it -> address + it.codeUnits }.withIndex().associate { (index, address) -> address to index }

/**
 * The guard for a merged lambda: the int field of its own class it reads right at the top, from
 * `this`, and the branch on it that leads away from [marker]. The hook only asks on the arm holding
 * [marker], so a tap on the lambda's other arm stays Facebook's own.
 */
internal fun armOf(method: Method, marker: String): Arm {
    val code = method.code()
    val markerAt = code.indexOfFirst { it.loads(marker) }
    if (markerAt < 0) refuse("${method.definingClass}->${method.name} doesn't load \"$marker\"")
    val readAt = code.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IGET &&
            ((instruction as ReferenceInstruction).reference as FieldReference).let {
                it.definingClass == method.definingClass && it.type == "I"
            }
    }
    if (readAt !in 0 until markerAt) refuse("the merged Like lambda reads no arm number before its Like")
    val read = code[readAt] as TwoRegisterInstruction
    val self = method.localRegisterCount()
    val fromThis = read.registerB == self || code.take(readAt).any {
        (it.opcode == Opcode.MOVE_OBJECT || it.opcode == Opcode.MOVE_OBJECT_FROM16 || it.opcode == Opcode.MOVE_OBJECT_16) &&
            (it as TwoRegisterInstruction).registerA == read.registerB && it.registerB == self
    }
    if (!fromThis) refuse("the merged Like lambda reads its arm number from something other than itself")
    val branch = code.getOrNull(readAt + 1)
    if (branch == null || (branch.opcode != Opcode.IF_EQZ && branch.opcode != Opcode.IF_NEZ) ||
        (branch as OneRegisterInstruction).registerA != read.registerA
    ) {
        refuse("the merged Like lambda doesn't branch on its arm number right away")
    }
    val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
    val target = indexOfAddress(code)[addresses[readAt + 1] + (branch as OffsetInstruction).codeOffset]
        ?: refuse("the merged Like lambda's arm branch lands inside an instruction")
    if (target <= readAt + 1) refuse("the merged Like lambda's arm branch jumps back")
    val onFallThrough = markerAt < target
    val field = (code[readAt] as ReferenceInstruction).reference as FieldReference
    return Arm(
        "${field.definingClass}->${field.name}:${field.type}",
        if ((branch.opcode == Opcode.IF_EQZ) == onFallThrough) "if-eqz" else "if-nez",
    )
}

/** Throws unless the hook can go first thing in [method]. */
private fun checkHandler(method: Method, what: String) {
    val name = "$what ${method.definingClass}->${method.name}"
    if (method.localRegisterCount() < BORROWED) refuse("$name has fewer than $BORROWED locals")
    method.parameterTypes.forEachIndexed { index, type ->
        if (type.toString() != "Z" && !type.startsWith("L") && !type.startsWith("[")) {
            refuse("$name takes a ${type}, which the hook can't hand over")
        }
        if (method.parameterRegisterNumber(index) > 255) refuse("$name keeps parameter $index out of reach of an array store")
    }
    when (method.returnType) {
        "V" -> Unit
        OBJECT -> if (method.code().none {
                it.opcode == Opcode.SGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).definingClass == UNIT
            }
        ) {
            refuse("$name returns something other than Unit")
        }
        else -> refuse("$name returns ${method.returnType}")
    }
}

/** All six handlers, found and checked before anything changes. */
internal fun BytecodePatchContext.likeHandlers(): List<LikeHandler> {
    val footer = methodHolding(LITHO_FOOTER_TRACE, "litho footer Like")
    val clicks = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(footer.definingClass)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(clicks) { isClickLambda(it, footer) }
    }
    val click = clicks.singleOrNull()
        ?: refuse("expected one click lambda calling ${descriptorOf(footer)}, found ${clicks.size}")
    val plugins = methodsHolding(PLUGIN_FOOTER_TRACE)
    val plugin = plugins.filter(::isMergedLambda).singleOrNull()
    val touch = plugins.filter(::isLikeSender).singleOrNull()
    if (plugin == null || touch == null || plugins.size != 2) {
        refuse("expected the plugin footer's lambda and its static like sender holding \"$PLUGIN_FOOTER_TRACE\", " +
            "found ${plugins.joinToString { descriptorOf(it) }}")
    }
    val reels = methodsHolding(REEL_LIKE_NAME).filter(::isReelLike)
    val reel = reels.singleOrNull() ?: refuse("expected one reel Like holding \"$REEL_LIKE_NAME\", found ${reels.size}")
    val comment = methodHolding(COMMENT_LIKE_TRACE, "comment Like")
    val handlers = listOf(
        LikeHandler(POST_FOOTER, footer),
        LikeHandler(POST_PLUGIN, plugin, armOf(plugin, PLUGIN_FOOTER_TRACE)),
        LikeHandler(REEL, reel),
        LikeHandler(COMMENT, comment),
        LikeHandler(POST_TOUCH, touch),
        LikeHandler(POST_CLICK, click),
    )
    handlers.forEach { checkHandler(it.method, "the Like handler of kind ${it.kind}") }
    if (handlers.map { descriptorOf(it.method) }.toSet().size != handlers.size) refuse("two kinds of Like share a handler")
    return handlers
}

/** The hook going first in [handler]'s method. */
internal fun likeHook(handler: LikeHandler): String {
    val method = handler.method
    val guard = handler.arm?.let {
        """
            move-object/from16 v1, p0
            iget v0, v1, ${it.field}
            ${it.skip} v0, :facebook
        """
    }.orEmpty()
    val fill = buildString {
        if (!AccessFlags.STATIC.isSet(method.accessFlags)) {
            appendLine("const/4 v3, 0x0")
            appendLine("aput-object p0, v2, v3")
        }
        method.parameterTypes.forEachIndexed { index, type ->
            val p = method.parameterRegister(index)
            appendLine("const/16 v3, ${index + 1}")
            if (type.toString() == "Z") {
                appendLine("invoke-static/range { $p .. $p }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;")
                appendLine("move-result-object v4")
                appendLine("aput-object v4, v2, v3")
            } else {
                appendLine("aput-object $p, v2, v3")
            }
        }
    }
    val exit = if (method.returnType == "V") {
        "return-void"
    } else {
        """
            sget-object v0, $UNIT->INSTANCE:$UNIT
            return-object v0
        """
    }
    return """
        $guard
        const/16 v3, ${method.parameterTypes.size + 1}
        new-array v2, v3, [$OBJECT
        $fill
        const/4 v0, ${handler.kind}
        const-string v1, "${descriptorOf(method)}"
        invoke-static { v0, v1, v2 }, $HOLD
        move-result v0
        if-eqz v0, :facebook
        $exit
    """
}

/** Finds all six handlers first, so a build missing one refuses before any code changes. */
internal fun BytecodePatchContext.askBeforeLike() {
    likeHandlers().forEach { handler ->
        mutableClassDefBy(handler.method.definingClass).findMutableMethodOf(handler.method).apply {
            addInstructionsWithLabels(0, likeHook(handler), ExternalLabel("facebook", getInstruction(0)))
        }
    }
}
