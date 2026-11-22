/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.feedsheader

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import app.morphe.util.literalReads
import app.morphe.util.namedRegisters
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val PATCH = "Hide the Feeds header"

/** The fragment the Feeds tab shows. Facebook's builds keep its name and its members' names. */
internal const val FEED_FILTERS_FRAGMENT = "Lcom/facebook/feed/fragment/FeedFiltersFragment;"

/** The fragment's answer to whether its tab gets a title row. */
internal const val NAV_BAR_QUESTION = "shouldInitializeNavBar"

/** The fragment's field for the container its filters go in. */
internal const val FILTERS_FIELD = "tabBarContainer"

/** The fragment's field for the controller that puts its posts under the filters. */
internal const val CONTAINER_CONTROLLER_FIELD = "feedFiltersFragmentContainerController"

/**
 * The name Redex keeps for the runnable that controller posts to move the posts down by the
 * filters' height when they show, and back to the top when they don't.
 */
internal const val ROOM_RUNNABLE = "FeedFiltersFragmentContainerController\$setViewPagerTopMargin\$1"

internal const val ON_CREATE_VIEW = "onCreateView"
internal val ON_CREATE_VIEW_PARAMETERS = listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;", "Landroid/os/Bundle;")

internal const val FEEDS_HEADER = "Lapp/morphe/extension/facebook/feed/FeedsHeader;"
internal const val NAV_BAR = "$FEEDS_HEADER->navBar(Z)Z"
internal const val HIDES_FILTERS = "$FEEDS_HEADER->hidesFilters(Landroid/view/View;)Z"
internal const val ROOM = "$FEEDS_HEADER->roomForFilters(Z)Z"

internal const val GET_CONTEXT = "Landroid/view/View;->getContext()Landroid/content/Context;"
private const val CONTEXT = "Landroid/content/Context;"

/**
 * The Feeds tab opens on its posts, without the title row (Feeds, the menu and search) or the
 * filters under it, All, Favorites, Friends and the rest (issue #34). Both belong to Facebook's
 * FeedFiltersFragment, whose names 577 and 580 keep. The tab's chrome builds a title row only when
 * the fragment's [NAV_BAR_QUESTION] answers yes, so each answer it returns goes through the
 * extension, which says no while the switch is on. The filters go in the container the fragment
 * keeps in [FILTERS_FIELD], which it adds to its view hidden and hands, in [ON_CREATE_VIEW], to the
 * controller that fills it and shows it once the filters load (580 `LX/OHa;`, 577 `LX/aEY;`). Right
 * before that controller is made, while the switch is on, the container in the register it gets is
 * swapped for a new one built the way the fragment builds its own, which is never put on screen, so
 * Facebook's container stays hidden. The posts would still sit as far down as the filters are tall,
 * since the controller in [CONTAINER_CONTROLLER_FIELD] moves them there once it hears the filters
 * show, through the runnable Redex names [ROOM_RUNNABLE] (582 `LX/fU7;`). That runnable's answer to
 * whether they show goes through the extension too, which says no while the switch is on, so the
 * posts start at the top.
 */
@Suppress("unused")
val hideFeedsHeaderPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide the Feeds header",
    description = "Takes the title row and the All, Favorites, Friends, Groups and Pages filters off the top of " +
        "the Feeds tab, so it opens straight on posts. Starts off. Turn it on in Hushfacebook settings > News " +
        "feed, then restart Facebook.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val fragment = classDefByOrNull(FEED_FILTERS_FRAGMENT) ?: refuse("this build has no $FEED_FILTERS_FRAGMENT")
        val answers = navBarAnswers(fragment)
        val handOver = filtersHandOver(fragment)
        val room = filtersRoom(fragment) { classDefByOrNull(it) }
        val mutable = mutableClassDefBy(FEED_FILTERS_FRAGMENT)
        mutable.findMutableMethodOf(answers.method).hookNavBarAnswers(answers)
        mutable.findMutableMethodOf(handOver.method).hookFiltersHandOver(handOver)
        mutableClassDefBy(room.runnable.type).findMutableMethodOf(room.method).hookFiltersRoom(room)
        enableStatus("feedsHeader")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Whether a try block of [method] covers the instruction at [index], or starts or ends there.
 * Depending on how a hook goes in at [index], one of those can take the hook's calls in, and its
 * handler would then get the registers the hook writes.
 */
private fun tryBlockAt(method: Method, index: Int): Boolean {
    val implementation = method.implementation!!
    val address = implementation.instructions.take(index).sumOf { it.codeUnits }
    return implementation.tryBlocks.any { address in it.startCodeAddress..(it.startCodeAddress + it.codeUnitCount) }
}

/** The fragment's [NAV_BAR_QUESTION], and where it returns its answer: each return's index and register. */
internal class NavBarAnswers(val method: Method, val returns: List<Pair<Int, Int>>)

/**
 * Reads the returns of [fragment]'s [NAV_BAR_QUESTION]. Refuses unless the fragment declares it
 * taking nothing and answering a boolean, with code that returns, and unless no try block covers
 * a return or starts or ends at one, since the hook's call goes in at each.
 */
internal fun navBarAnswers(fragment: ClassDef): NavBarAnswers {
    val method = fragment.methods.singleOrNull { it.name == NAV_BAR_QUESTION && it.parameterTypes.isEmpty() && it.returnType == "Z" }
        ?: refuse("${fragment.type} has no $NAV_BAR_QUESTION()Z")
    val code = method.implementation?.instructions?.toList() ?: refuse("${fragment.type}->$NAV_BAR_QUESTION has no code")
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN }.map { it to (code[it] as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) refuse("${fragment.type}->$NAV_BAR_QUESTION never returns an answer")
    val caught = returns.map { it.first }.filter { tryBlockAt(method, it) }
    if (caught.isNotEmpty()) refuse("${fragment.type}->$NAV_BAR_QUESTION returns at $caught in a try block or at one's edge")
    return NavBarAnswers(method, returns)
}

/**
 * Hands each answer to the extension right before it's returned, at the return's own label so a
 * jump to the return goes through the hook too, and returns what the extension says. A range call
 * names the register whatever its number.
 */
internal fun MutableMethod.hookNavBarAnswers(answers: NavBarAnswers) {
    for ((index, register) in answers.returns.sortedByDescending { it.first }) {
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $NAV_BAR
                move-result v$register
            """,
        )
    }
}

/**
 * Where [ON_CREATE_VIEW] hands the filters' container to the controller that fills and shows it:
 * the controller's new-instance at [index], into [controllerRegister], with the container in
 * [containerRegister]. [type] is the container's type and [constructor] the call the fragment
 * builds its own container with.
 */
internal class FiltersHandOver(
    val method: Method,
    val index: Int,
    val controllerRegister: Int,
    val containerRegister: Int,
    val type: String,
    val constructor: MethodReference,
)

/**
 * Reads the hand-over out of [fragment]. Refuses unless [FILTERS_FIELD] is written once, with a
 * container made and built from a Context alone right before, and unless [ON_CREATE_VIEW] hands
 * what it reads from the field, as an argument of the field's type, to the constructor of one
 * other class made right before that call, and reads that register nowhere after it. The hook goes
 * in above that new-instance, so nothing but the instruction above may lead there, and no try
 * block may cover it or end at it. What the hook leaves in the controller's register is gone once
 * the new-instance runs. Both registers fit the hook's plain calls: the container's comes from an
 * iget-object, which names only the first sixteen, and a range call lists the controller's before
 * it.
 */
internal fun filtersHandOver(fragment: ClassDef): FiltersHandOver {
    val where = fragment.type
    val field = fragment.fields.singleOrNull { it.name == FILTERS_FIELD } ?: refuse("$where has no field $FILTERS_FIELD")
    val type = field.type
    fun isTheField(instruction: Instruction) = ((instruction as? ReferenceInstruction)?.reference as? FieldReference)
        ?.let { it.definingClass == where && it.name == FILTERS_FIELD && it.type == type } == true

    val writes = fragment.methods.flatMap { method ->
        val code = method.implementation?.instructions?.toList().orEmpty()
        code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && isTheField(code[it]) }.map { method to it }
    }
    val (builder, write) = writes.singleOrNull() ?: refuse("expected one write of $where->$FILTERS_FIELD, found ${writes.size}")
    val built = builder.implementation!!.instructions.toList()
    val stored = (built[write] as TwoRegisterInstruction).registerA
    val made = built.getOrNull(write - 2)
    val init = built.getOrNull(write - 1)
    val constructor = (init as? ReferenceInstruction)?.reference as? MethodReference
    val madeHere = made != null && made.opcode == Opcode.NEW_INSTANCE && (made as OneRegisterInstruction).registerA == stored &&
        ((made as ReferenceInstruction).reference as TypeReference).type == type
    val builtHere = init != null && init.opcode == Opcode.INVOKE_DIRECT && constructor != null && constructor.name == "<init>" &&
        constructor.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT) && init.namedRegisters().firstOrNull() == stored
    if (!madeHere || !builtHere) {
        refuse("$where->${builder.name} doesn't make and build the $FILTERS_FIELD container from a Context right before storing it")
    }

    val view = fragment.methods.singleOrNull {
        it.name == ON_CREATE_VIEW && it.parameterTypes.map(CharSequence::toString) == ON_CREATE_VIEW_PARAMETERS
    } ?: refuse("$where has no $ON_CREATE_VIEW")
    val inView = "$where->$ON_CREATE_VIEW"
    val code = view.implementation?.instructions?.toList() ?: refuse("$inView has no code")
    val handOvers = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT && isTheField(code[it]) }.flatMap { read ->
        val register = (code[read] as TwoRegisterInstruction).registerA
        view.literalReads(read).filter { at ->
            val callee = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
            (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE) && callee != null &&
                callee.name == "<init>" && callee.definingClass != type && passesAs(code[at], callee, register, type)
        }.map { register to it }
    }
    val (container, call) = handOvers.singleOrNull()
        ?: refuse("expected one constructor in $inView handed $FILTERS_FIELD, found ${handOvers.size}")
    val controller = code[call].namedRegisters().first()
    val controllerType = ((code[call] as ReferenceInstruction).reference as MethodReference).definingClass
    val index = call - 1
    val instance = code.getOrNull(index)
    val madeRightBefore = instance != null && instance.opcode == Opcode.NEW_INSTANCE &&
        (instance as OneRegisterInstruction).registerA == controller &&
        ((instance as ReferenceInstruction).reference as TypeReference).type == controllerType
    if (!madeRightBefore) refuse("in $inView the $controllerType handed $FILTERS_FIELD isn't made right before its constructor")
    val after = view.readsAfter(call, container)
    if (after.isNotEmpty()) refuse("in $inView v$container is read at $after, after it's handed to $controllerType")
    val flow = ControlFlow.of(view)
    val into = flow.normal.indices.filter { index in flow.normal[it] } +
        flow.exceptional.indices.filter { index in flow.exceptional[it] }
    if (into != listOf(index - 1)) {
        refuse("in $inView the $controllerType's new-instance can be reached from $into, not only from above it")
    }
    // The hook goes in above the new-instance, and dexlib2 keeps a try block's edges on the
    // new-instance itself, so a try block over it or ending at it would take the hook's calls in
    // and hand its handler the registers the hook writes.
    if (tryBlockAt(view, index)) refuse("in $inView the $controllerType's new-instance is in a try block or at one's edge")
    return FiltersHandOver(view, index, controller, container, type, constructor)
}

/** Whether [call] passes [register] where [callee] declares a parameter of [type]. */
private fun passesAs(call: Instruction, callee: MethodReference, register: Int, type: String): Boolean {
    val registers = call.namedRegisters()
    // The constructor's own instance comes first.
    var slot = 1
    for (parameter in callee.parameterTypes.map(CharSequence::toString)) {
        if (parameter == type && registers.getOrNull(slot) == register) return true
        slot += if (parameter == "J" || parameter == "D") 2 else 1
    }
    return false
}

/**
 * Asks the extension right before the controller is made, with the container it's about to get.
 * On a yes, the container's register gets a new container of the same type, built from the
 * container's own Context by the constructor the fragment uses, and the controller is made with
 * that one. The answer and the Context go in the controller's register, which its new-instance
 * writes right after. On a no, the code goes on to the new-instance as before.
 */
internal fun MutableMethod.hookFiltersHandOver(handOver: FiltersHandOver) {
    val container = handOver.containerRegister
    val controller = handOver.controllerRegister
    addInstructionsWithLabels(
        handOver.index,
        """
            invoke-static { v$container }, $HIDES_FILTERS
            move-result v$controller
            if-eqz v$controller, :facebooks
            invoke-virtual { v$container }, $GET_CONTEXT
            move-result-object v$controller
            new-instance v$container, ${handOver.type}
            invoke-direct { v$container, v$controller }, ${handOver.constructor}
        """,
        ExternalLabel("facebooks", getInstruction(handOver.index)),
    )
}

/**
 * Where [ROOM_RUNNABLE]'s run() reads whether the filters show, right before it branches between
 * moving the posts down by the filters' height and putting them at the top: the iget-boolean at
 * [index], into [register].
 */
internal class FiltersRoom(val runnable: ClassDef, val method: Method, val index: Int, val register: Int)

/**
 * Reads where the posts get their room for the filters, finding classes by type with [classDefOf].
 * Refuses unless the class of [fragment]'s [CONTAINER_CONTROLLER_FIELD] makes exactly one runnable
 * Redex names [ROOM_RUNNABLE], and unless that runnable's run() reads one boolean field of its own
 * and branches on it with the if-eqz right after, which nothing else leads to and no try block
 * covers or starts or ends at.
 */
internal fun filtersRoom(fragment: ClassDef, classDefOf: (String) -> ClassDef?): FiltersRoom {
    val where = fragment.type
    val field = fragment.fields.singleOrNull { it.name == CONTAINER_CONTROLLER_FIELD }
        ?: refuse("$where has no field $CONTAINER_CONTROLLER_FIELD")
    val controller = classDefOf(field.type)
        ?: refuse("this build has no ${field.type}, the type of $where->$CONTAINER_CONTROLLER_FIELD")
    val made = controller.methods.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
    }.distinct()
    val runnables = made.mapNotNull(classDefOf).filter { redexOriginalName(it) == ROOM_RUNNABLE }
    val runnable = runnables.singleOrNull()
        ?: refuse("expected ${controller.type} to make one $ROOM_RUNNABLE, found ${runnables.size}")
    val run = runnable.methods.singleOrNull { it.name == "run" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        ?: refuse("${runnable.type} has no run()V")
    val inRun = "${runnable.type}->run"
    val implementation = run.implementation ?: refuse("$inRun has no code")
    val code = implementation.instructions.toList()
    // run() takes nothing, so its own instance is in the last register.
    val self = implementation.registerCount - 1
    val reads = code.indices.filter { at ->
        val reference = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.IGET_BOOLEAN && reference != null && reference.definingClass == runnable.type &&
            (code[at] as TwoRegisterInstruction).registerB == self
    }
    val index = reads.singleOrNull() ?: refuse("expected $inRun to read one boolean of its own, found ${reads.size}")
    val register = (code[index] as TwoRegisterInstruction).registerA
    val branch = code.getOrNull(index + 1)
    if (branch == null || branch.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != register) {
        refuse("in $inRun the boolean read at $index isn't branched on right after")
    }
    val flow = ControlFlow.of(run)
    val into = flow.normal.indices.filter { index + 1 in flow.normal[it] } +
        flow.exceptional.indices.filter { index + 1 in flow.exceptional[it] }
    if (into != listOf(index)) {
        refuse("in $inRun the branch on whether the filters show can be reached from $into, not only from its read")
    }
    // The hook goes in above the branch, and dexlib2 keeps a try block's edges on the branch
    // itself, so a try block over it or ending at it would take the hook's call in.
    if (tryBlockAt(run, index + 1)) refuse("in $inRun the branch on whether the filters show is in a try block or at one's edge")
    return FiltersRoom(runnable, run, index, register)
}

/**
 * Hands the runnable's answer to whether the filters show to the extension, between the read and
 * the branch, and branches on what the extension says. On a no the posts go to the top, where the
 * runnable puts them when Facebook has no filters to show. A range call names the register
 * whatever its number.
 */
internal fun MutableMethod.hookFiltersRoom(room: FiltersRoom) {
    addInstructions(
        room.index + 1,
        """
            invoke-static/range { v${room.register} .. v${room.register} }, $ROOM
            move-result v${room.register}
        """,
    )
}
