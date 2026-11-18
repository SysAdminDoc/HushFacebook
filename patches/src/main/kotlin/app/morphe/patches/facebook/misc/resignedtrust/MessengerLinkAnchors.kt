/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.resignedtrust

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val MESSENGER_LINK_CHECK = "Lapp/morphe/extension/facebook/coexist/MessengerLinkCheck;"
internal const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

/** The flags Facebook's message expiration job reads at startup, each through its own method. */
internal const val OPT_OUT_FLAG = "me_opt_out_flag"
internal const val TRIGGERED_FLAG = "me_triggered_flag"

internal const val PATCHED_STUB = "patched"
internal const val SESSION_STUB = "userSession"
internal const val READER_STUB = "flagReader"
internal const val OPT_OUT_STUB = "readOptOutFlag"
internal const val TRIGGERED_STUB = "readTriggeredFlag"

private const val CONTEXT = "Landroid/content/Context;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * What the Messenger link test calls: one of Facebook's Context-to-session lookups, and the flag
 * reader with its two reads.
 */
internal class MessengerLinkAnchors(
    val session: Method,
    val reader: ClassDef,
    val optOut: Method,
    val triggered: Method,
)

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private val Instruction.call: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.key() =
    definingClass + "->" + name + parameterTypes.joinToString("", "(", ")") + returnType

private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

/**
 * The reader's read of one flag: an instance method taking nothing, answering an object and holding
 * the flag's name. Both builds have one for each flag, beside a writer taking a boolean.
 */
internal fun flagReads(reader: ClassDef, flag: String): List<Method> = reader.methods.filter {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType.startsWith("L") &&
        holdsString(it, flag)
}

/** Whether [reader] has one constructor, public, taking the session alone. */
internal fun builtFromSession(reader: ClassDef): Boolean {
    val constructors = reader.methods.filter { it.name == "<init>" }
    val constructor = constructors.singleOrNull() ?: return false
    return AccessFlags.PUBLIC.isSet(constructor.accessFlags) && constructor.parameters() == listOf(FB_USER_SESSION)
}

/**
 * The call a Context-to-session lookup ends with, or null when [method] isn't one. A lookup is a
 * public static (Context) -> FbUserSession of a public class, a few instructions long, that asks
 * one interface about the context and returns what a static call makes of the answer. Facebook
 * keeps several copies of it, which all end with the same call.
 */
internal fun sessionLookupEnd(owner: ClassDef, method: Method): String? {
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags)) return null
    if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || !AccessFlags.STATIC.isSet(method.accessFlags)) return null
    if (method.returnType != FB_USER_SESSION || method.parameters() != listOf(CONTEXT)) return null
    val code = method.code()
    if (code.isEmpty() || code.size > MAX_LOOKUP_LENGTH || code.last().opcode != Opcode.RETURN_OBJECT) return null
    val asks = code.filter { it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE }
        .mapNotNull { it.call }
    val ask = asks.singleOrNull()?.takeIf { it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT) }
        ?: return null
    val last = code.last { it.call != null }
    if (last.opcode != Opcode.INVOKE_STATIC && last.opcode != Opcode.INVOKE_STATIC_RANGE) return null
    val end = last.call!!
    if (end.returnType != FB_USER_SESSION) return null
    if (end.parameterTypes.map(CharSequence::toString) != listOf(ask.returnType)) return null
    return end.key()
}

/** Both builds' lookups are eight instructions. */
private const val MAX_LOOKUP_LENGTH = 12

private fun missing(detail: String): Nothing = throw PatchException(detail)

/**
 * The flag reader, the class holding both flags' names whose one constructor takes the session and
 * whose one read of each flag is public, and a session lookup. When the lookups don't all end with
 * the same call, which one a test uses would matter, so none is taken. Changes nothing.
 */
internal fun BytecodePatchContext.findMessengerLinkAnchors(): MessengerLinkAnchors {
    val readers = classDefByStrings(OPT_OUT_FLAG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter { flagReads(it, OPT_OUT_FLAG).size == 1 && flagReads(it, TRIGGERED_FLAG).size == 1 && builtFromSession(it) }
    val reader = readers.singleOrNull() ?: missing(
        "expected one flag reader holding \"$OPT_OUT_FLAG\" and \"$TRIGGERED_FLAG\" and built from an " +
            "FbUserSession, found ${readers.size}",
    )
    if (!AccessFlags.PUBLIC.isSet(reader.accessFlags)) missing("${reader.type} isn't public")
    val optOut = flagReads(reader, OPT_OUT_FLAG).single()
    val triggered = flagReads(reader, TRIGGERED_FLAG).single()
    listOf(optOut, triggered).forEach { read ->
        if (!AccessFlags.PUBLIC.isSet(read.accessFlags)) missing("${reader.type}->${read.name} isn't public")
    }

    return MessengerLinkAnchors(sessionLookup(), reader, optOut, triggered)
}

/**
 * One of Facebook's Context-to-session lookups. They all have to end with the same call, or which
 * one is used would matter and none is taken.
 */
internal fun BytecodePatchContext.sessionLookup(): Method {
    val lookups = mutableListOf<Pair<Method, String>>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method -> sessionLookupEnd(classDef, method)?.let { lookups += method to it } }
    }
    if (lookups.isEmpty()) missing("found no public static (Context) lookup of the FbUserSession")
    val ends = lookups.map { it.second }.toSet()
    if (ends.size != 1) missing("the session lookups end with ${ends.size} different calls: ${ends.joinToString()}")
    return lookups.map { it.first }.minBy { it.definingClass + "->" + it.name }
}

/**
 * Fills MessengerLinkCheck's stubs. Each uses only its parameter registers, cast to the type the
 * call needs: a stub whose unfilled body only returns null can compile to the parameter's one
 * register, so there's no local to borrow. The reader stub builds the reader in its spare
 * parameter.
 */
internal fun BytecodePatchContext.fillMessengerLinkStubs(anchors: MessengerLinkAnchors) {
    val extension = mutableClassDefBy(MESSENGER_LINK_CHECK)
    fun stub(name: String, parameters: List<String>, answer: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: missing("$MESSENGER_LINK_CHECK has no static $answer $name(${parameters.joinToString("")})")

    val reader = anchors.reader.type
    val session = anchors.session
    stub(SESSION_STUB, listOf(CONTEXT), OBJECT).addInstructions(
        0,
        """
            invoke-static/range { p0 .. p0 }, ${session.definingClass}->${session.name}($CONTEXT)$FB_USER_SESSION
            move-result-object p0
            return-object p0
        """,
    )
    stub(READER_STUB, listOf(OBJECT, OBJECT), OBJECT).addInstructions(
        0,
        """
            check-cast p0, $FB_USER_SESSION
            new-instance p1, $reader
            invoke-direct { p1, p0 }, $reader-><init>($FB_USER_SESSION)V
            return-object p1
        """,
    )
    for ((name, read) in listOf(OPT_OUT_STUB to anchors.optOut, TRIGGERED_STUB to anchors.triggered)) {
        stub(name, listOf(OBJECT), OBJECT).addInstructions(
            0,
            """
                check-cast p0, $reader
                invoke-virtual/range { p0 .. p0 }, $reader->${read.name}()${read.returnType}
                move-result-object p0
                return-object p0
            """,
        )
    }
    // Last, so a stub this build couldn't fill never runs.
    stub(PATCHED_STUB, emptyList(), "Z").returnEarly(true)
}
