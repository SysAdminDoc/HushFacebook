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
import app.morphe.patches.facebook.misc.extension.patchLog
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
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
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
 * - The comment actions drawn inline under a feed post are text spans. Their Like calls a merged
 *   Kotlin lambda (LX/069v;->invoke) with the tapped view, and the lambda picks its arm with a
 *   packed switch on its int field. The InlineCommentActionsComponentSpec trace name sits in the
 *   Like arm, so the hook only asks when the field holds that arm's key.
 *
 * The Follow handlers (#477). Read from 587, 2026-11-14, arm64.
 *
 * - A reel's author row follows through an instance method taking the tapped view, the component
 *   context and the component (LX/08j3;->A0F), the one returning void that holds the "Missing
 *   ShortFormVideoContext" log line. Its event dispatcher (LX/08j3;->A1D) calls it from the case of
 *   the click event.
 * - A reel posted with collaborators draws another author row, and its follow is written into that
 *   component's event dispatcher (LX/08iy;->A1D, taking the event handler and the event, returning
 *   Object), the other method of that shape holding that log line (a third holder maps an int to its
 *   message and is neither shape). It picks the event with a sparse switch on the
 *   handler's int id, so the hook only asks when the id is the key of the case holding the line,
 *   and a held tap returns null, as Facebook's click cases do.
 * - The " · Follow" link beside a poster's name is a text span (LX/03zW;->onClick) holding the
 *   story_header_follow_profile event name.
 * - A post attachment's Follow button clicks through a merged lambda (LX/0QN4;->onClick) whose
 *   int field picks the case of a packed switch; the FollowProfileActionLink name sits in the
 *   Follow case. The method reads its view into another register between the field and the switch.
 *   The armv7 build's has two arms, so there it branches with if-nez instead, and the guard takes
 *   that branch's way past the Follow arm.
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
internal const val INLINE_COMMENT_LIKE_TRACE = "InlineCommentActionsComponentSpec.onReactionLikeClicked"
internal const val REEL_FOLLOW_LOG = "Missing ShortFormVideoContext. Cannot follow profile."
internal const val POST_FOLLOW_EVENT = "story_header_follow_profile"
internal const val FOLLOW_LINK = "FollowProfileActionLink"

/** The kinds AskBeforeLike tells apart, its own constants. */
internal const val POST_FOOTER = 0
internal const val POST_PLUGIN = 1
internal const val REEL = 2
internal const val COMMENT = 3
internal const val POST_TOUCH = 4
internal const val POST_CLICK = 5
internal const val COMMENT_INLINE = 6
internal const val REEL_FOLLOW = 7
internal const val REEL_COLLAB_FOLLOW = 8
internal const val POST_FOLLOW = 9
internal const val POST_ATTACHMENT_FOLLOW = 10

private const val VIEW = "Landroid/view/View;"
private const val SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val FEEDBACK = "Lcom/facebook/graphql/model/GraphQLFeedback;"
private const val OBJECT = "Ljava/lang/Object;"
private const val UNIT = "Lkotlin/Unit;"

/** Registers the hook borrows: v0 to v4, free before the handler's own first instruction. */
private const val BORROWED = 5

private val CONST_STRINGS = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)

/** What a merged lambda does with its arm number right after reading it. */
private val ARM_BRANCHES = setOf(Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH)

/** What may sit between reading the arm number and branching on it, as long as it leaves the number alone. */
private val ARM_SETUP = setOf(
    Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST,
)

/** How many of those the guard reads past. */
private const val ARM_SETUP_MAX = 4

/**
 * The int field read at the top of a merged lambda (or an event dispatcher) and the guard lines that
 * skip the hook when it picks another arm. They read the field from the register [from], test it
 * in v0 and may use v1.
 */
internal data class Arm(val field: String, val skip: String, val from: String = "p0")

/**
 * A Like or Follow handler of [kind], with the [arm] guard a merged lambda needs. A held tap in a
 * handler returning Object returns Unit, or null when [returnsNull] (an event dispatcher).
 */
internal data class LikeHandler(val kind: Int, val method: Method, val arm: Arm? = null, val returnsNull: Boolean = false)

/**
 * Asks "Like or unlike this post?" before a tap on Like goes through, with a switch each for posts,
 * reels and comments (#437, #443), and "Follow or unfollow this account?" before a tap on Follow in
 * a post or beside a reel, with a switch each for posts and reels (#477).
 *
 * In the default selection with all five switches off, so nothing changes until one is turned on.
 * A build missing a Follow handler keeps the Like questions and warns.
 */
@Suppress("unused")
val askBeforeLikePatch = bytecodePatch(
    name = "Ask before a like",
    description = "Switches that make a tap on Like under a post, beside a reel or under a comment, or on Follow in a " +
        "post or beside a reel, ask first, and only Continue goes ahead, so a stray tap doesn't like or follow. All " +
        "five start off. Turn them on in Hushfacebook settings > News feed, Reels and Watch, and Comments and writing.",
) {
    // Feed holds 15 already. A like is public, so the guard sits with the other switches that keep a
    // stray tap or a glance from showing up for someone else.
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("askBeforeLike")
        askBeforeLike()
        try {
            askBeforeFollow()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so a tap on Follow goes through without asking.")
        }
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
 * `this`, and what Facebook does with it next. A two-armed lambda branches on it, and the guard
 * takes the branch that leads away from [marker]. One with more arms runs a packed switch on it,
 * and the guard skips unless the field holds the key of the case [marker] sits in. The hook only
 * asks on the arm holding [marker], so a tap on the lambda's other arms stays Facebook's own.
 */
internal fun armOf(method: Method, marker: String): Arm = switchArmOf(method, marker, parameter = null)

/**
 * The guard for a litho event dispatcher, (handler, event) returning Object: the int id it reads
 * from the event handler, its first parameter, and the case of its switch [marker] sits in. The
 * hook only asks for that event, so the component's other events stay Facebook's own.
 */
internal fun eventArmOf(method: Method, marker: String): Arm = switchArmOf(method, marker, parameter = 0)

/**
 * [armOf] reading the arm number from `this` when [parameter] is null, else from that declared
 * parameter, an instance of its declared type.
 */
private fun switchArmOf(method: Method, marker: String, parameter: Int?): Arm {
    val what = if (parameter == null) "the merged lambda ${method.definingClass}" else "the event dispatcher ${method.definingClass}"
    val code = method.code()
    val markerAt = code.indexOfFirst { it.loads(marker) }
    if (markerAt < 0) refuse("${method.definingClass}->${method.name} doesn't load \"$marker\"")
    val owner = if (parameter == null) method.definingClass else method.parameterTypes[parameter].toString()
    val ownerRegister = if (parameter == null) method.localRegisterCount() else method.parameterRegisterNumber(parameter)
    val readAt = code.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IGET &&
            ((instruction as ReferenceInstruction).reference as FieldReference).let {
                it.definingClass == owner && it.type == "I"
            }
    }
    if (readAt !in 0 until markerAt) refuse("$what reads no arm number before \"$marker\"")
    val read = code[readAt] as TwoRegisterInstruction
    val fromOwner = read.registerB == ownerRegister || code.take(readAt).any {
        (it.opcode == Opcode.MOVE_OBJECT || it.opcode == Opcode.MOVE_OBJECT_FROM16 || it.opcode == Opcode.MOVE_OBJECT_16) &&
            (it as TwoRegisterInstruction).registerA == read.registerB && it.registerB == ownerRegister
    }
    if (!fromOwner) refuse("$what reads its arm number from something other than ${parameter?.let { "parameter $it" } ?: "itself"}")
    val field = (code[readAt] as ReferenceInstruction).reference as FieldReference
    val name = "${field.definingClass}->${field.name}:${field.type}"
    // Moves and constants may sit between the read and the branch, as long as they keep the number.
    val branchIndex = (readAt + 1 until code.size).firstOrNull { code[it].opcode !in ARM_SETUP }
        ?: refuse("$what doesn't branch on its arm number")
    val branch = code[branchIndex]
    if (branchIndex - readAt - 1 > ARM_SETUP_MAX || branch.opcode !in ARM_BRANCHES ||
        (branch as OneRegisterInstruction).registerA != read.registerA ||
        code.subList(readAt + 1, branchIndex).any { (it as OneRegisterInstruction).registerA == read.registerA }
    ) {
        refuse("$what doesn't branch on its arm number right away")
    }
    val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
    val indexAt = indexOfAddress(code)
    val branchAt = addresses[branchIndex]
    if (branch.opcode == Opcode.PACKED_SWITCH || branch.opcode == Opcode.SPARSE_SWITCH) {
        val payload = indexAt[branchAt + (branch as OffsetInstruction).codeOffset]?.let { code[it] } as? SwitchPayload
            ?: refuse("$what's switch has no payload")
        val cases = payload.switchElements.map { case ->
            case.key to (indexAt[branchAt + case.offset] ?: refuse("a case of $what lands inside an instruction"))
        }
        if (cases.any { (_, start) -> start <= branchIndex }) refuse("a case of $what jumps back")
        // The arm is the case starting last at or before the marker.
        val (key, _) = cases.filter { (_, start) -> start <= markerAt }.maxByOrNull { (_, start) -> start }
            ?: refuse("$what's \"$marker\" sits on no case of its switch")
        val load = if (key in Short.MIN_VALUE..Short.MAX_VALUE) "const/16" else "const"
        return Arm(name, "$load v1, $key\nif-ne v0, v1, :facebook", if (parameter == null) "p0" else method.parameterRegister(parameter))
    }
    if (parameter != null) refuse("$what branches on its event id instead of switching on it")
    val target = indexAt[branchAt + (branch as OffsetInstruction).codeOffset]
        ?: refuse("$what's arm branch lands inside an instruction")
    if (target <= branchIndex) refuse("$what's arm branch jumps back")
    val onFallThrough = markerAt < target
    return Arm(name, "${if ((branch.opcode == Opcode.IF_EQZ) == onFallThrough) "if-eqz" else "if-nez"} v0, :facebook")
}

/** Throws unless the hook can go first thing in [handler]'s method. */
private fun checkHandler(handler: LikeHandler, what: String) {
    val method = handler.method
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
        OBJECT -> if (!handler.returnsNull && method.code().none {
                it.opcode == Opcode.SGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).definingClass == UNIT
            }
        ) {
            refuse("$name returns something other than Unit")
        }
        else -> refuse("$name returns ${method.returnType}")
    }
}

/** All seven handlers, found and checked before anything changes. */
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
    val inlines = methodsHolding(INLINE_COMMENT_LIKE_TRACE).filter(::isMergedLambda)
    val inline = inlines.singleOrNull()
        ?: refuse("expected one merged lambda holding \"$INLINE_COMMENT_LIKE_TRACE\", found ${inlines.size}")
    val handlers = listOf(
        LikeHandler(POST_FOOTER, footer),
        LikeHandler(POST_PLUGIN, plugin, armOf(plugin, PLUGIN_FOOTER_TRACE)),
        LikeHandler(REEL, reel),
        LikeHandler(COMMENT, comment),
        LikeHandler(POST_TOUCH, touch),
        LikeHandler(POST_CLICK, click),
        LikeHandler(COMMENT_INLINE, inline, armOf(inline, INLINE_COMMENT_LIKE_TRACE)),
    )
    handlers.forEach { checkHandler(it, "the Like handler of kind ${it.kind}") }
    if (handlers.map { descriptorOf(it.method) }.toSet().size != handlers.size) refuse("two kinds of Like share a handler")
    return handlers
}

/** The reel author row's Follow: returns void and takes the tapped view first. */
internal fun isReelFollow(method: Method) =
    method.returnType == "V" && method.parameterTypes.firstOrNull()?.toString() == VIEW

/** A litho component's event dispatcher: an instance method taking the event handler and the event, returning Object. */
internal fun isEventDispatcher(method: Method) =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == OBJECT && method.parameterTypes.size == 2 &&
        method.parameterTypes[0].startsWith("L") && method.parameterTypes[1].toString() == OBJECT

/** A click listener's or a text span's onClick(View) returning void. */
internal fun isOnClick(method: Method) =
    method.name == "onClick" && method.returnType == "V" && method.parameterTypes.map(Any::toString) == listOf(VIEW)

/** All four Follow handlers, found and checked before anything changes. */
internal fun BytecodePatchContext.followHandlers(): List<LikeHandler> {
    // A third holder on 587 maps an int to its log line and is neither shape, so only the shapes count.
    val reels = methodsHolding(REEL_FOLLOW_LOG)
    val reel = reels.filter(::isReelFollow).singleOrNull()
    val collab = reels.filter(::isEventDispatcher).singleOrNull()
    if (reel == null || collab == null) {
        refuse("expected the reel author row's Follow and the collaborator row's event dispatcher holding " +
            "\"$REEL_FOLLOW_LOG\", found ${reels.joinToString { descriptorOf(it) }}")
    }
    val headers = methodsHolding(POST_FOLLOW_EVENT).filter(::isOnClick)
    val header = headers.singleOrNull()
        ?: refuse("expected one onClick holding \"$POST_FOLLOW_EVENT\", found ${headers.size}")
    val attachments = methodsHolding(FOLLOW_LINK).filter(::isOnClick)
    val attachment = attachments.singleOrNull()
        ?: refuse("expected one onClick holding \"$FOLLOW_LINK\", found ${attachments.size}")
    val handlers = listOf(
        LikeHandler(REEL_FOLLOW, reel),
        LikeHandler(REEL_COLLAB_FOLLOW, collab, eventArmOf(collab, REEL_FOLLOW_LOG), returnsNull = true),
        LikeHandler(POST_FOLLOW, header),
        LikeHandler(POST_ATTACHMENT_FOLLOW, attachment, armOf(attachment, FOLLOW_LINK)),
    )
    handlers.forEach { checkHandler(it, "the Follow handler of kind ${it.kind}") }
    if (handlers.map { descriptorOf(it.method) }.toSet().size != handlers.size) refuse("two kinds of Follow share a handler")
    return handlers
}

/** The hook going first in [handler]'s method. */
internal fun likeHook(handler: LikeHandler): String {
    val method = handler.method
    val guard = handler.arm?.let {
        """
            move-object/from16 v1, ${it.from}
            iget v0, v1, ${it.field}
            ${it.skip}
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
    } else if (handler.returnsNull) {
        """
            const/4 v0, 0x0
            return-object v0
        """
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
        ${if (handler.kind <= 7) "const/4" else "const/16"} v0, ${handler.kind}
        const-string v1, "${descriptorOf(method)}"
        invoke-static { v0, v1, v2 }, $HOLD
        move-result v0
        if-eqz v0, :facebook
        $exit
    """
}

/** Finds all seven handlers first, so a build missing one refuses before any code changes. */
internal fun BytecodePatchContext.askBeforeLike() = hookAll(likeHandlers())

/** Finds all four Follow handlers first, so a build missing one refuses before any code changes. */
internal fun BytecodePatchContext.askBeforeFollow() = hookAll(followHandlers())

private fun BytecodePatchContext.hookAll(handlers: List<LikeHandler>) {
    handlers.forEach { handler ->
        mutableClassDefBy(handler.method.definingClass).findMutableMethodOf(handler.method).apply {
            addInstructionsWithLabels(0, likeHook(handler), ExternalLabel("facebook", getInstruction(0)))
        }
    }
}
