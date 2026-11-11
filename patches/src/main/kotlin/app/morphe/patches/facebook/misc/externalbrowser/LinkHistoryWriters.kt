/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.externalbrowser

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Facebook's browser's two link history writers, read from 582 on 2026-10-10. The obfuscated names
 * here are for reviewers; the code finds each factory by the kept writer class it creates.
 *
 * Each writer has one factory, an instance method taking the browser's launch state and answering
 * the writer, or null when the launch intent and Facebook's server config leave the feature off
 * (582 LX/IeR;->ATc for the signals writer, LX/IeS;->ATc for the page data writer). Neither factory
 * reads your Enhanced browsing setting. That's read later, where the writers' records are sent
 * (CONTRIBUTING.md has the map). The extension's LinkHistory.hold() goes first in each factory, and a
 * yes answers null, the factory's own off answer, so the writer never exists.
 *
 * The page data goes out through one upload, read from 583 on 2026-10-20: an instance method
 * returning void (583 LX/Ipa;->A00) that builds IABLinkHistorySaveMutation from the page's address,
 * title, session and times and runs it, or logs "No callback executor for link history request" and
 * returns. Two places call it, the browser callback service and a newer link history event handler
 * (583 LX/R3K;->CXX) that the writer hook may not cover, and both hand it a callback that only logs.
 * LinkHistory.holdUpload() goes first in it, and a yes returns before anything is built or sent.
 *
 * Filling in a form on a page sends two autofill reports, read from 583 on 2026-10-20. The browser's
 * event endpoint (583 BrowserLiteCallbackImpl.ELZ, 582's EHj) and the autofill prompt's handler
 * (583 LX/R7p;->CVv) log the form report through one static void method (583 LX/QYk;->A00, 29
 * parameters: current_url, origin_host, the form's field lists, ad_id, experiments), and ELZ and
 * the browser's event handler (583 Ddy) log iab_1pd_event through another (583 LX/QYl;->A00). Each
 * only builds an analytics event, checks its sampling and logs it, so returning first changes
 * nothing else; autofill keeps what you declined in its preferences elsewhere (583 LX/P5s;->A05).
 * LinkHistory.holdAutofillReport() goes first in each. ELZ's ad signal (583 LX/9JZ;->A00, the
 * "client_ad_delivery_on_device_value" event, shared with other ad code) and its DARTS signal
 * (583 LX/2CA;->A0E, an on-device signal store) aren't held here.
 *
 * Every other report the browser makes goes through one event logger, read from 586 on 2026-11-11:
 * BrowserLiteCallbackImpl.IFg(Bundle, IABEvent), "iab_unified", 3,507 instructions behind a switch
 * on the event's type. It logs landing pages and scroll depth, login and payment forms, checkouts,
 * ad pixels, structured data and more, each as an analytics event that's built, asked whether it's
 * sampled (00dZ.isSampled() itself, or a static wrapper that only asks it, 586 LX/04X9;->A1Y) and
 * filled and logged only on a yes. The same method also hands page data to the link history writers
 * (586 LX/0rwT;->A01), feeds an on-device signal store (586 LX/02Gp;->A0F) and more, none of it
 * behind a sampling answer. So LinkHistory.sendBrowserEvent() goes right after each sampling answer
 * (39 on 586) and turns a yes into a no, and nothing else the logger does changes.
 *
 * Ads open in the browser report through one more callback, read from 586 on 2026-11-11:
 * BrowserLiteCallbackImpl.HWf(Bundle, String, Map), a switch on the event's name. Its ATTENTION_FRAMEWORK
 * branch logs the ad's attention events (ad_id, the browser session, a tracking token). Its call
 * extension branches log an ad's call button being drawn and tapped (ad_id, page_id, its tracking
 * codes), and hand the confirm dialog's steps to the click-to-call app state logger (586
 * CTCAppStateLogger.A0D), which logs them only once the tap has started its session. Nothing else
 * happens in it, so LinkHistory.holdAdEvent() goes first and a yes returns before any of it.
 *
 * Its sibling of the same shape, read from 586 on 2026-11-11: B6n(Bundle, String, Map), 813
 * instructions, two switches on the event's name (its String parameter's hashCode). Most branches do
 * things: autofill's JS bridge answers, the Page name fetch for a call extension, page translation,
 * Buy with Prime's sheet and the Facebook login dialog. Three only report the page to Facebook's ad
 * integrity checks. INTEGRITY_LOGGER starts CloakingDetectionService with the page's data,
 * INTEGRITY_SIGNALS_EVENT logs the redirect chain, tracking codes and comment id, and
 * INTEGRITY_OUTBOUND_CLICK_EVENT logs each hop's domain and its MD5, the page's images and their
 * sizes, three sim hashes of its text and layout, and its tracking codes. So
 * LinkHistory.holdIntegrityReport(name) goes first with the event's name, and returns only for those.
 */

private const val PATCH = "Open links in external browser"

internal val LINK_HISTORY_WRITERS = listOf(
    // Records the links you open with when each page started, became usable and finished loading.
    "Lcom/facebook/browser/lite/extensions/browserhistory/LinkHistorySignalsWriter;",
    // Records each page's address and title, which Facebook saves as your link history.
    "Lcom/facebook/browser/lite/extensions/browserhistory/LinkHistoryUiDataWriter;",
)

internal const val HOLD_LINK_HISTORY = "Lapp/morphe/extension/facebook/misc/LinkHistory;->hold()Z"

internal const val HOLD_LINK_HISTORY_UPLOAD = "Lapp/morphe/extension/facebook/misc/LinkHistory;->holdUpload()Z"

/** What the link history upload logs when it has nowhere to send its answer. */
internal const val LINK_HISTORY_UPLOAD_ANCHOR = "No callback executor for link history request"

/** The save request the upload builds, which its cast check names. */
internal const val LINK_HISTORY_SAVE_MUTATION = "IABLinkHistorySaveMutation"

internal const val HOLD_AUTOFILL_REPORT = "Lapp/morphe/extension/facebook/misc/LinkHistory;->holdAutofillReport()Z"

/** Strings each autofill report holds, the first the one to look it up by. */
internal val AUTOFILL_REPORTS = listOf(
    // The form report: the page, the form's fields and the ad. Other autofill code holds these too,
    // never in a static void method that samples. Its other keys can sit in Redex's string table
    // instead (583 armeabi-v7a reads edited_fields from LX/000;->A00(I)), so they aren't matched.
    listOf("selected_field_tag", "has_helium"),
    // The first-party data event, the one method naming it.
    listOf("iab_1pd_event"),
)

internal const val SEND_BROWSER_EVENT = "Lapp/morphe/extension/facebook/misc/LinkHistory;->sendBrowserEvent(I)Z"

/** What the browser's event logger names its unified event, the string to look it up by. */
internal const val BROWSER_EVENT_LOGGER_ANCHOR = "iab_unified"

/** The kept type of the events the logger is handed. */
internal const val BROWSER_EVENT = "Lcom/facebook/iabeventlogging/model/IABEvent;"

internal const val HOLD_AD_EVENT = "Lapp/morphe/extension/facebook/misc/LinkHistory;->holdAdEvent()Z"

/** What the ad event callback names a call button's tap, the string to look it up by. */
internal const val AD_EVENT_CALLBACK_ANCHOR = "CALL_EXTENSION_CTA_ENTRY"

internal const val HOLD_INTEGRITY_REPORT =
    "Lapp/morphe/extension/facebook/misc/LinkHistory;->holdIntegrityReport(Ljava/lang/String;)Z"

/**
 * What the integrity callback names an outbound click's hashed domain, the string to look it up by.
 * Its event names can sit in Redex's string table instead (586 minapi28 reads them from LX/0R17;->A00(I)).
 */
internal const val INTEGRITY_CALLBACK_ANCHOR = "md5Domain"

/**
 * Whether [method] is a factory of [writer]: an instance method outside it that takes one parameter
 * (the browser's launch state), creates the writer, and can answer null on its own, which is what
 * makes the hook's null an answer its callers already handle.
 */
internal fun isWriterFactory(method: Method, writer: String): Boolean {
    if (method.definingClass == writer || !method.returnType.startsWith("L") || method.parameterTypes.size != 1 ||
        AccessFlags.STATIC.isSet(method.accessFlags)
    ) {
        return false
    }
    val code = method.implementation?.instructions?.toList() ?: return false
    val creates = code.any {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == writer
    }
    return creates && answersNull(code)
}

/** Whether [code] returns a register it loaded with 0. */
private fun answersNull(code: List<Instruction>): Boolean {
    val nulls = code.filter { it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 0 }
        .map { (it as OneRegisterInstruction).registerA }.toSet()
    return code.any { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA in nulls }
}

/**
 * The one factory of each writer in [LINK_HISTORY_WRITERS], found in one walk over the app. None, or
 * two, for either writer stops the patch rather than ship a switch that leaves one writer running.
 */
internal fun BytecodePatchContext.linkHistoryFactories(): List<Method> {
    val found = LINK_HISTORY_WRITERS.associateWith { mutableListOf<Method>() }
    // A factory makes its writer.
    classDefForEachReferencing(types = LINK_HISTORY_WRITERS) { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEachReferencing
        for (method in classDef.methods) {
            for (writer in LINK_HISTORY_WRITERS) if (isWriterFactory(method, writer)) found.getValue(writer) += method
        }
    }
    val wrong = found.filterValues { it.size != 1 }
    if (wrong.isNotEmpty()) {
        throw PatchException(
            "$PATCH: expected one factory of each link history writer, found " +
                wrong.entries.joinToString { (writer, factories) -> "${factories.size} of $writer" },
        )
    }
    return found.values.map { it.single() }
}

/** The mutable copy of [factory] (or the upload, or an autofill report), to hook. */
internal fun BytecodePatchContext.mutableFactory(factory: Method): MutableMethod =
    mutableClassDefBy(factory.definingClass).findMutableMethodOf(factory)

/** First thing in a writer's factory: ask the extension, and answer null on a yes. Otherwise the factory runs as before. */
internal fun MutableMethod.holdLinkHistory() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_LINK_HISTORY
            move-result v0
            if-eqz v0, :build
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("build", getInstruction(0)),
    )
}

/**
 * Whether [method] is the link history upload: an instance method returning void that holds
 * [LINK_HISTORY_UPLOAD_ANCHOR] and names [LINK_HISTORY_SAVE_MUTATION].
 */
internal fun isLinkHistoryUpload(method: Method): Boolean {
    if (method.returnType != "V" || AccessFlags.STATIC.isSet(method.accessFlags)) return false
    val strings = method.implementation?.instructions
        ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
    return LINK_HISTORY_UPLOAD_ANCHOR in strings && strings.any { LINK_HISTORY_SAVE_MUTATION in it }
}

/** The one link history upload, or a refusal. */
internal fun BytecodePatchContext.linkHistoryUpload(): Method {
    val uploads = classDefByStrings(LINK_HISTORY_UPLOAD_ANCHOR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }
        .flatMap { it.methods }.filter(::isLinkHistoryUpload)
    return uploads.singleOrNull()
        ?: throw PatchException("$PATCH: expected one link history upload, found ${uploads.size}")
}

/** First thing in the upload: ask the extension, and return on a yes. Otherwise it runs as before. */
internal fun MutableMethod.holdLinkHistoryUpload() = returnFirstOnYes(HOLD_LINK_HISTORY_UPLOAD)

/**
 * Whether [method] is the autofill report named by [strings]: a static method returning void that
 * holds every one of them and checks an analytics event's sampling, which is all such a report does
 * before it logs.
 */
internal fun isAutofillReport(method: Method, strings: List<String>): Boolean {
    if (method.returnType != "V" || !AccessFlags.STATIC.isSet(method.accessFlags)) return false
    val code = method.implementation?.instructions?.toList() ?: return false
    val held = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
    val samples = code.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "isSampled" }
    return samples && strings.all { it in held }
}

/** The one method of each of [AUTOFILL_REPORTS], or a refusal naming the report that isn't one. */
internal fun BytecodePatchContext.autofillReports(): List<Method> = AUTOFILL_REPORTS.map { strings ->
    val reports = classDefByStrings(strings.first(), StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }
        .flatMap { it.methods }.filter { isAutofillReport(it, strings) }
    reports.singleOrNull()
        ?: throw PatchException("$PATCH: expected one autofill report holding ${strings.first()}, found ${reports.size}")
}

/** First thing in an autofill report: ask the extension, and return on a yes. Otherwise it logs as before. */
internal fun MutableMethod.holdAutofillReport() = returnFirstOnYes(HOLD_AUTOFILL_REPORT)

/**
 * Whether [method] is the browser's event logger: an instance method returning void that takes the
 * browser's launch bundle and one [BROWSER_EVENT], and names [BROWSER_EVENT_LOGGER_ANCHOR].
 */
internal fun isBrowserEventLogger(method: Method): Boolean {
    if (method.returnType != "V" || AccessFlags.STATIC.isSet(method.accessFlags)) return false
    if (method.parameterTypes.map(CharSequence::toString) != listOf("Landroid/os/Bundle;", BROWSER_EVENT)) return false
    return method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == BROWSER_EVENT_LOGGER_ANCHOR
    } == true
}

/** The one browser event logger, or a refusal. */
internal fun BytecodePatchContext.browserEventLogger(): Method {
    val loggers = classDefByStrings(BROWSER_EVENT_LOGGER_ANCHOR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }
        .flatMap { it.methods }.filter(::isBrowserEventLogger)
    return loggers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one browser event logger, found ${loggers.size}")
}

/** Whether [instruction] asks an analytics event whether it's sampled. */
private fun asksSampling(instruction: Instruction): Boolean {
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.name == "isSampled" && reference.parameterTypes.isEmpty() && reference.returnType == "Z"
}

/**
 * Whether [method] only answers an event's sampling: a static method taking one object and returning
 * a boolean that reads the event out of it and answers what isSampled says (586 LX/04X9;->A1Y).
 */
internal fun isSamplingWrapper(method: Method): Boolean {
    if (method.returnType != "Z" || !AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.size != 1) {
        return false
    }
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.map { it.opcode } == listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT, Opcode.RETURN) &&
        asksSampling(code[1])
}

/**
 * The index in [code] of each sampling answer's move-result: after a call to isSampled itself, or to
 * a static method taking one object that [isWrapper] says only asks it.
 */
internal fun samplingAnswers(code: List<Instruction>, isWrapper: (MethodReference) -> Boolean): List<Int> =
    code.indices.filter { at ->
        if (at + 1 >= code.size || code[at + 1].opcode != Opcode.MOVE_RESULT) return@filter false
        val instruction = code[at]
        if (asksSampling(instruction)) return@filter true
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@filter false
        val reference = (instruction as ReferenceInstruction).reference as MethodReference
        reference.returnType == "Z" && reference.parameterTypes.size == 1 && isWrapper(reference)
    }.map { it + 1 }

/** The sampling answers in [logger]'s code, a wrapper read from the class that defines it. */
internal fun BytecodePatchContext.browserEventSamplingAnswers(logger: Method): List<Int> {
    val wrappers = mutableMapOf<String, Boolean>()
    val code = logger.implementation?.instructions?.toList().orEmpty()
    val answers = samplingAnswers(code) { reference ->
        wrappers.getOrPut(reference.toString()) {
            classDefByOrNull(reference.definingClass)?.methods?.any {
                it.name == reference.name && it.parameterTypes.map(CharSequence::toString) ==
                    reference.parameterTypes.map(CharSequence::toString) && isSamplingWrapper(it)
            } == true
        }
    }
    if (answers.isEmpty()) throw PatchException("$PATCH: the browser event logger asks no sampling")
    // A jump to the instruction after an answer would arrive with the hook behind it.
    val targets = branchTargets(code)
    answers.firstOrNull { it + 1 in targets }?.let {
        throw PatchException("$PATCH: a jump lands right after the browser event logger's sampling answer at $it")
    }
    return answers
}

/**
 * After each sampling answer at [answers] (move-result indices in this method's code), ask the
 * extension with that answer and keep its reply in the same register, so a held event reads as one
 * the sampling turned away. Works from the last so earlier indices stay put.
 */
internal fun MutableMethod.holdBrowserEvents(answers: List<Int>) {
    for (at in answers.sortedDescending()) {
        val register = (getInstruction(at) as OneRegisterInstruction).registerA
        addInstructions(
            at + 1,
            """
                invoke-static/range { v$register .. v$register }, $SEND_BROWSER_EVENT
                move-result v$register
            """,
        )
    }
}

/**
 * Whether [method] is the browser's ad event callback: an instance method returning void that takes
 * the browser's launch bundle, the event's name and its values, and names [AD_EVENT_CALLBACK_ANCHOR].
 * Its class has another method of that shape (586 B6n), which names no call button.
 */
internal fun isAdEventCallback(method: Method) = isBrowserCallback(method, AD_EVENT_CALLBACK_ANCHOR)

/** The one ad event callback, or a refusal. */
internal fun BytecodePatchContext.adEventCallback() = browserCallback(AD_EVENT_CALLBACK_ANCHOR, "ad event callback")

/** First thing in the ad event callback: ask the extension, and return on a yes. Otherwise it logs as before. */
internal fun MutableMethod.holdAdEvent() = returnFirstOnYes(HOLD_AD_EVENT)

/** Whether [method] is the browser's integrity callback: the ad event callback's shape, naming [INTEGRITY_CALLBACK_ANCHOR]. */
internal fun isIntegrityCallback(method: Method) = isBrowserCallback(method, INTEGRITY_CALLBACK_ANCHOR)

/** The one integrity callback, or a refusal. */
internal fun BytecodePatchContext.integrityCallback() = browserCallback(INTEGRITY_CALLBACK_ANCHOR, "integrity callback")

/**
 * First thing in the integrity callback: hand the extension the event's name, and return on a yes.
 * Every other event (autofill, translation, the login dialog) runs as before.
 */
internal fun MutableMethod.holdIntegrityReport() {
    requireLocals(PATCH, 1)
    // The name is the second of three parameters after this, so the last register but one.
    val name = implementation!!.registerCount - 2
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { v$name .. v$name }, $HOLD_INTEGRITY_REPORT
            move-result v0
            if-eqz v0, :run
            return-void
        """,
        ExternalLabel("run", getInstruction(0)),
    )
}

/**
 * Whether [method] is one of the browser's callbacks that takes its launch bundle, an event's name and
 * its values, returns void, and names [anchor].
 */
private fun isBrowserCallback(method: Method, anchor: String): Boolean {
    if (method.returnType != "V" || AccessFlags.STATIC.isSet(method.accessFlags)) return false
    if (method.parameterTypes.map(CharSequence::toString) != listOf("Landroid/os/Bundle;", "Ljava/lang/String;", "Ljava/util/Map;")) {
        return false
    }
    return method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == anchor
    } == true
}

private fun BytecodePatchContext.browserCallback(anchor: String, what: String): Method {
    val callbacks = classDefByStrings(anchor, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }
        .flatMap { it.methods }.filter { isBrowserCallback(it, anchor) }
    return callbacks.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $what, found ${callbacks.size}")
}

/** Asks [hook] before this void method's first instruction and returns on a yes. */
private fun MutableMethod.returnFirstOnYes(hook: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :run
            return-void
        """,
        ExternalLabel("run", getInstruction(0)),
    )
}
