/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.order

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * How Facebook picks the next reel in the Reels tab, on the 584 build.
 *
 * The Reels tab is ranked on the phone. Facebook keeps a pool of loaded reels, and whenever it
 * places the next one it reranks the rest of the pool and takes the head of the list. The ranker
 * that does it logs as FbShortsUDDCSRRanker. Its rerank, EmL(context, list, position), sorts the
 * list in place with one static List.sort(Comparator) call and then reorders parts of it; the four
 * callers then read the head as the next reel. So the list as EmL returns is what decides.
 *
 * 585 (Redex): the rerank (05ev.JBd on arm64, 05f1.JBb on armeabi-v7a) grew to about 2,600
 * instructions and 200 registers and makes two static List.sort calls. One sorts a copy it builds
 * for the Odin scoring tier, the other sorts a register that is the List parameter itself (through
 * a copy register, 133 on arm64) or a copy of a side list. A recording pass after the sort only
 * reads the list. The callers (064A.A0X and three more) still take get(0) of the list they passed.
 * So the hook anchors on the parameter instead of on a sort's register. 586 (05ZB.JNX) reuses the
 * parameter's register late on one branch, so the parameter goes to the extension at the start and
 * each return only says the rerank is done.
 *
 * 587 split the rerank. JnY (04xj on arm64) keeps the (context, list, position) shape but loads no
 * tag and sorts nothing: it packs its parameters into a state object (0BuN, the list in A1P) and
 * ends by calling two private methods of its own class with it. Both load the tag; the second (A06)
 * sorts the state's list in place, or a copy of a side list when there is one. The hook stays on
 * JnY, the method the callers call, found by following its list into the state and on to that sort.
 *
 * The list holds ranking signals, one per reel, which Facebook makes from each pool item with
 * createSignalForModel(model). The signal carries a key and scores but no posting time. The model
 * does: its edge (the interface's one no-argument Object getter) is the reel's GraphQL edge, whose
 * node is the reel's story, with creation_time in seconds. The model's FeedType field names the
 * feed, fb_shorts_tab for the Reels tab. The signal's equals and hashCode follow its key, so a
 * signal made again for the same reel finds the same entry.
 *
 * So the patch hands each signal to the extension as it's made, with its model's FeedType and edge,
 * and hands the rerank's list to the extension as EmL returns. 584's createSignalForModel is on
 * two classes, the base the Reels pool uses and a subclass Home's feed uses; both get the hook,
 * and the extension keeps only signals from the Reels feeds.
 */
internal const val PATCH = "Newest reels first"

/** The tag the Reels ranker logs under, loaded by its rerank. */
internal const val RANKER_TAG = "FbShortsUDDCSRRanker"

/** The kept name of the method that makes a pool item's ranking signal. */
internal const val SIGNAL_MAKER = "createSignalForModel"

internal const val FEED_TYPE = "Lcom/facebook/api/feedtype/FeedType;"
internal const val LIST = "Ljava/util/List;"
private const val COMPARATOR = "Ljava/util/Comparator;"
private const val OBJECT = "Ljava/lang/Object;"

private const val NEWEST_REELS = "$EXTENSION_PACKAGE/reels/NewestReels;"

/** Whether the signal hook should go on, given the model's FeedType. */
internal const val RECORDS = "$NEWEST_REELS->records($OBJECT)Z"

/** Keeps a signal's posting time: the FeedType, the edge, then the signal. */
internal const val POSTED = "$NEWEST_REELS->posted($OBJECT$OBJECT$OBJECT)V"

internal const val EDGE_HELPER = "hushfacebookReelEdge"
internal const val EDGE_FAILED = "$NEWEST_REELS->edgeFailed(Ljava/lang/Throwable;)V"

/** Keeps the list the rerank was handed, as it starts. */
internal const val RERANKING = "$NEWEST_REELS->reranking($LIST)V"

/** Reorders the list the rerank started with, as it returns. */
internal const val RERANKED = "$NEWEST_REELS->reranked()V"

internal fun refuse(message: String): Nothing = throw PatchException("$PATCH: $message")

/** The registers a call reads, in order, whether it's written as a range or not. */
internal fun Instruction.callRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** Whether this is a static `(List, Comparator)V` call: the rerank's in-place sort. */
internal fun Instruction.isListSort(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return call.returnType == "V" && call.parameterTypes.map(CharSequence::toString) == listOf(LIST, COMPARATOR)
}

private fun Instruction.isObjectMove(): Boolean =
    opcode == Opcode.MOVE_OBJECT || opcode == Opcode.MOVE_OBJECT_FROM16 || opcode == Opcode.MOVE_OBJECT_16

/**
 * Whether [register] holds the List parameter [listParameter] when the instruction at [at] runs on
 * some path: the parameter itself, never written, or a chain of at most [hops] move-objects from it.
 */
private fun Method.holdsListParameter(code: List<Instruction>, at: Int, register: Int, listParameter: Int, hops: Int): Boolean {
    if (register == listParameter) return writersReaching(at, register) == listOf(-1)
    if (hops == 0) return false
    return writersReaching(at, register).any { writer ->
        writer >= 0 && code[writer].isObjectMove() &&
            holdsListParameter(code, writer, (code[writer] as TwoRegisterInstruction).registerB, listParameter, hops - 1)
    }
}

/**
 * The List parameter's register, or null when [method] isn't the rerank: a void instance method
 * taking (object, List, int) that loads [RANKER_TAG] and sorts that list in place with a static
 * `(List, Comparator)V` call on some path (straight from the parameter or through a copy register),
 * or from 587 on hands it to a method of its own class that does ([handsListToRanker], which reads
 * classes through [classOf]). The hook hands the parameter over as the rerank starts and tells the
 * extension at each return, so it sorts the list the callers take the head of whichever sort ran,
 * and the 585 rerank's other sort, which orders a copy, doesn't matter. 586's rerank (05ZB.JNX)
 * writes a long over the parameter's register on its recordRankPass branch, so the returns can't
 * read it there.
 */
internal fun rerankList(method: Method, classOf: (String) -> ClassDef? = { null }): Int? {
    if (method.returnType != "V" || AccessFlags.STATIC.isSet(method.accessFlags)) return null
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (parameters.size != 3 || !parameters[0].startsWith("L") || parameters[1] != LIST || parameters[2] != "I") return null
    val code = method.implementation?.instructions?.toList() ?: return null
    // this, the context, then the list.
    val listParameter = method.localRegisterCount() + 2
    if (code.none { it.opcode == Opcode.RETURN_VOID }) return null
    if (!holdsString(method, RANKER_TAG)) {
        return if (handsListToRanker(method, code, listParameter, classOf)) listParameter else null
    }
    val sortsParameter = code.indices.filter { code[it].isListSort() }.any { index ->
        method.holdsListParameter(code, index, code[index].callRegisters().first(), listParameter, 3)
    }
    return if (sortsParameter) listParameter else null
}

/**
 * The ranker method 587's rerank hands its list to: the one method of [rerank]'s own class it calls
 * directly with only a state object, that loads [RANKER_TAG] and sorts a list read from the state's
 * [field] in place. Null when there isn't exactly one. [classOf] finds the rerank's class.
 */
internal fun rankerOf(rerank: Method, field: String, classOf: (String) -> ClassDef?): Method? {
    val own = classOf(rerank.definingClass) ?: return null
    val state = field.substringBefore("->")
    val code = rerank.implementation?.instructions?.toList() ?: return null
    val callees = code.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) return@mapNotNull null
        val call = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        if (call.definingClass != rerank.definingClass || call.returnType != "V" ||
            call.parameterTypes.map(CharSequence::toString) != listOf(state)) return@mapNotNull null
        own.methods.singleOrNull {
            it.name == call.name && it.returnType == "V" && it.implementation != null &&
                it.parameterTypes.map(CharSequence::toString) == listOf(state)
        }
    }.distinct()
    return callees.filter { callee ->
        if (!holdsString(callee, RANKER_TAG)) return@filter false
        val body = callee.implementation!!.instructions.toList()
        body.indices.filter { body[it].isListSort() }.any { index ->
            callee.readsField(body, index, body[index].callRegisters().first(), field, 3)
        }
    }.singleOrNull()
}

/**
 * Whether [register] holds a value read from [field] (an `iget-object`) when the instruction at [at]
 * runs, on some path, straight or through a chain of at most [hops] move-objects.
 */
private fun Method.readsField(code: List<Instruction>, at: Int, register: Int, field: String, hops: Int): Boolean =
    writersReaching(at, register).any { writer ->
        if (writer < 0) return@any false
        val instruction = code[writer]
        when {
            instruction.opcode == Opcode.IGET_OBJECT -> (instruction as ReferenceInstruction).reference.toString() == field
            hops > 0 && instruction.isObjectMove() ->
                readsField(code, writer, (instruction as TwoRegisterInstruction).registerB, field, hops - 1)
            else -> false
        }
    }

/**
 * The List fields of the state objects [method] builds from its List parameter ([listParameter]):
 * for each constructor call handed the parameter (straight or through a copy register, with nothing
 * written over it before), the field of the state's class that its constructor stores that argument
 * in. [classOf] finds the state's class.
 */
internal fun listStateFields(method: Method, listParameter: Int, classOf: (String) -> ClassDef?): List<String> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    fun width(type: String) = if (type == "J" || type == "D") 2 else 1
    return code.indices.flatMap { index ->
        val init = code[index]
        if (init.opcode != Opcode.INVOKE_DIRECT && init.opcode != Opcode.INVOKE_DIRECT_RANGE) return@flatMap emptyList()
        val constructor = (init as ReferenceInstruction).reference as? MethodReference ?: return@flatMap emptyList()
        if (constructor.name != "<init>") return@flatMap emptyList()
        val types = constructor.parameterTypes.map(CharSequence::toString)
        val registers = init.callRegisters()
        types.indices.filter { types[it] == LIST }.mapNotNull { at ->
            // The receiver comes first, then each argument takes one register, or two when wide.
            val position = 1 + types.take(at).sumOf(::width)
            val passed = registers.getOrNull(position) ?: return@mapNotNull null
            if (!method.holdsListParameter(code, index, passed, listParameter, 3)) return@mapNotNull null
            val stateClass = classOf(constructor.definingClass) ?: return@mapNotNull null
            val body = stateClass.methods.singleOrNull {
                it.name == "<init>" && it.implementation != null && it.parameterTypes.map(CharSequence::toString) == types
            } ?: return@mapNotNull null
            val self = body.localRegisterCount()
            val argument = self + position
            val stores = body.implementation!!.instructions.toList()
            stores.indices.mapNotNull { store ->
                val put = stores[store]
                if (put.opcode != Opcode.IPUT_OBJECT) return@mapNotNull null
                val field = (put as ReferenceInstruction).reference.toString()
                val registersOf = put as TwoRegisterInstruction
                field.takeIf {
                    registersOf.registerA == argument && registersOf.registerB == self &&
                        it.startsWith("${constructor.definingClass}->") && it.endsWith(":$LIST") &&
                        body.writersReaching(store, argument) == listOf(-1)
                }
            }.singleOrNull()
        }
    }.distinct()
}

/**
 * Whether [method], which loads no [RANKER_TAG] itself, hands its List parameter to the ranker: 587's
 * rerank (04xj.JnY on arm64) builds a state object from its three parameters, whose constructor keeps
 * the list in a List field, and calls the private method of its own class that loads the tag and
 * sorts that field's list in place (A06; it sorts a copy of a side list instead when there is one).
 */
private fun handsListToRanker(method: Method, code: List<Instruction>, listParameter: Int, classOf: (String) -> ClassDef?): Boolean {
    if (code.none { it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE }) return false
    val fields = listStateFields(method, listParameter, classOf)
    return fields.mapNotNull { rankerOf(method, it, classOf) }.distinct().size == 1
}

/**
 * Hands the rerank's [list] to the extension as [method] starts, before anything can write its
 * register, and tells the extension at each return, branches into them included.
 */
internal fun MutableMethod.sortWhenReranked(list: Int) {
    val code = implementation!!.instructions.toList()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    for (index in returns.asReversed()) {
        addInstructionsAtControlFlowLabel(index, "invoke-static { }, $RERANKED")
    }
    addInstructions(0, "invoke-static/range { v$list .. v$list }, $RERANKING")
}

/** What the signal hook reads from a model, found on its class and the parameter's interface. */
internal class SignalReads(
    /** The model's class, which the method casts its parameter to. */
    val model: String,
    /** The model's FeedType field, as a field reference. */
    val feedType: String,
    /** The interface's no-argument Object getter that hands back the model's edge. */
    val edge: String,
)

/**
 * The model class [method] casts its parameter to: the type of the one check-cast of the parameter
 * itself or of a move-object copy of it made right before. Null when there isn't exactly one.
 */
internal fun castModel(method: Method): String? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val parameter = method.localRegisterCount() + 1
    val casts = code.indices.mapNotNull { index ->
        val cast = code[index]
        if (cast.opcode != Opcode.CHECK_CAST) return@mapNotNull null
        val register = (cast as OneRegisterInstruction).registerA
        val copy = code.getOrNull(index - 1)
        val ofParameter = register == parameter || (copy != null &&
            (copy.opcode == Opcode.MOVE_OBJECT || copy.opcode == Opcode.MOVE_OBJECT_FROM16 || copy.opcode == Opcode.MOVE_OBJECT_16) &&
            (copy as TwoRegisterInstruction).registerA == register && copy.registerB == parameter)
        if (ofParameter) ((cast as ReferenceInstruction).reference as TypeReference).type else null
    }
    return casts.distinct().singleOrNull()
}

/**
 * Whether [method] has the shape of a signal maker: an instance [SIGNAL_MAKER] with a body that
 * takes one object and returns one.
 */
internal fun isSignalMaker(method: Method): Boolean =
    method.name == SIGNAL_MAKER && !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
        method.parameterTypes.size == 1 && method.parameterTypes[0].startsWith("L") && method.returnType.startsWith("L")

/**
 * What the hook reads in [method], with [classOf] looking a class up by type: the model class it
 * casts to, that class's one FeedType instance field, and its parameter interface's one abstract
 * no-argument Object getter, which the model implements. Null when any of them isn't exactly one,
 * when the field can't be read from [method]'s class, or when the parameter's register is written
 * on any way to a return, since the hook reads it there and a cast of something else would throw
 * out of Facebook's own method.
 */
internal fun signalReads(method: Method, classOf: (String) -> ClassDef?): SignalReads? {
    if (!isSignalMaker(method)) return null
    val code = method.implementation!!.instructions.toList()
    val parameter = method.localRegisterCount() + 1
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty() || returns.any { method.writersReaching(it, parameter) != listOf(-1) }) return null
    val model = castModel(method) ?: return null
    val modelClass = classOf(model) ?: return null
    val face = method.parameterTypes[0].toString()
    if (modelClass.interfaces.none { it.toString() == face }) return null
    val feedType = modelClass.fields.singleOrNull {
        it.type == FEED_TYPE && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: return null
    if (!readableFrom(feedType.accessFlags, model, method.definingClass)) return null
    val faceClass = classOf(face) ?: return null
    if (!AccessFlags.INTERFACE.isSet(faceClass.accessFlags)) return null
    val edge = faceClass.methods.singleOrNull {
        AccessFlags.ABSTRACT.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == OBJECT
    } ?: return null
    return SignalReads(model, "$model->${feedType.name}:$FEED_TYPE", "$face->${edge.name}()$OBJECT")
}

/** Whether a member of [owner] with [flags] can be read from [reader]: public, or not private and in its package. */
internal fun readableFrom(flags: Int, owner: String, reader: String): Boolean {
    if (AccessFlags.PUBLIC.isSet(flags)) return true
    if (AccessFlags.PRIVATE.isSet(flags)) return false
    return owner.substringBeforeLast('/') == reader.substringBeforeLast('/')
}

/** Reads only the optional edge. A lazy model may have no inflated edge yet (#234). */
internal fun edgeHelper(owner: String, reads: SignalReads): MutableMethod = ImmutableMethod(
    owner,
    EDGE_HELPER,
    listOf(ImmutableMethodParameter(reads.edge.substringBefore("->"), null, null)),
    OBJECT,
    AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(2),
).toMutable().apply {
    addInstructions(
        0,
        """
            invoke-interface { p0 }, ${reads.edge}
            move-result-object v0
            return-object v0
            move-exception v0
            invoke-static { v0 }, $EDGE_FAILED
            const/4 v0, 0x0
            return-object v0
        """,
    )
    val body = implementation!!
    body.addCatch(body.newLabelForIndex(0), body.newLabelForIndex(2), body.newLabelForIndex(3))
}

/**
 * Hands each signal [this] returns to the extension, with its model's FeedType and edge, when
 * the extension says the feed is one it keeps. Three locals below the parameters do the work;
 * nothing reads them after a return, and the returned register is left alone.
 */
internal fun MutableMethod.recordAtReturns(reads: SignalReads) {
    val code = implementation!!.instructions.toList()
    val parameter = localRegisterCount() + 1
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) refuse("$definingClass->$name never returns")
    for (index in returns.asReversed()) {
        val answer = (code[index] as OneRegisterInstruction).registerA
        val scratch = (0 until localRegisterCount()).filter { it != answer }.take(3)
        if (scratch.size < 3 || scratch.last() > 15) {
            refuse("$definingClass->$name has no three locals under v16 beside its answer v$answer")
        }
        val (model, feed, value) = scratch
        addInstructionsAtControlFlowLabel(
            index,
            """
                move-object/from16 v$model, v$parameter
                check-cast v$model, ${reads.model}
                iget-object v$feed, v$model, ${reads.feedType}
                invoke-static { v$feed }, $RECORDS
                move-result v$value
                if-eqz v$value, :facebook
                invoke-static { v$model }, $definingClass->$EDGE_HELPER(${reads.edge.substringBefore("->")})$OBJECT
                move-result-object v$value
                move-object/from16 v$model, v$answer
                invoke-static { v$feed, v$value, v$model }, $POSTED
                :facebook
                nop
            """,
        )
    }
}
