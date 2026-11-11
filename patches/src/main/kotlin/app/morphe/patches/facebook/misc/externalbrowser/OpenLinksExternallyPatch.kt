/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/externalbrowser/ForceExternalBrowserPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.misc.externalbrowser

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.namedRegisters
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patches.facebook.misc.settings.settingsPatch

private const val EXTENSION_CLASS = "Lapp/morphe/extension/facebook/misc/ExternalBrowser;"

private const val REDIRECT =
    "$EXTENSION_CLASS->redirect(Landroid/app/Activity;Landroid/content/Intent;)Z"

private const val PATCH = "Open links in external browser"

/**
 * Facebook ships **two** in-app browsers. The newer one opens a tapped link.
 *
 * `handleByBrowserLite` sets the component of the launch intent, and it names the `litev2`
 * activity. Nothing else can redirect an explicit component bind. Thus a hook on the original
 * browser alone leaves each ordinary link in the app. The first device round showed this. The hook
 * was correct in the build, and it never ran.
 *
 * Both names are kept names, and `AndroidManifest.xml` declares them. Each has a subclass that
 * comes through `super.onCreate`. Thus these two entries cover four activities.
 */
internal val IN_APP_BROWSERS = listOf(
    // The newer browser. Ordinary links go to this one.
    "Lcom/facebook/browser/litev2/lite/BrowserLiteDIActivity;",
    // The original. Some surfaces still reach it, thus it keeps its hook.
    "Lcom/facebook/browser/lite/BrowserLiteActivity;",
)

@Suppress("unused")
val openLinksExternallyPatch = bytecodePatch(
    name = "Open links in external browser",
    description = "Opens web links in your own browser instead of Facebook's built-in one, without Facebook " +
        "tracking the click. Facebook pages still open in the app. On by default. Turn it off in Hushfacebook " +
        "settings > Links and sharing. A second switch, on by default under Privacy, stops Facebook's browser keeping a " +
        "history of the links you open in it. A Page's Events link opens on that Page's Events tab instead of Home, " +
        "whichever way the switches are set.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())
    dependsOn(facebookExtensionPatch)

    // Each hook goes in after the superclass call. At that point the activity is a valid Activity
    // for the extension, and the browser is not built yet. The extension returns false when the
    // link must stay in the app. The injected branch then continues into the original code.
    execute {
        val hooked = IN_APP_BROWSERS.sumOf { descriptor ->
            val classDef = mutableClassDefByOrNull(descriptor) ?: return@sumOf 0

            // onCreate: the URL is the data of the launch intent. Read it from the activity.
            val onCreate = lifecycleMethod(classDef.methods, descriptor, "onCreate", "Landroid/os/Bundle;")
            onCreate.hookRedirect(intentFromActivity = true)

            // onNewIntent: the new URL comes in as the parameter. getIntent() still returns the
            // intent that started the browser, which holds the previous link.
            val onNewIntent = lifecycleMethod(classDef.methods, descriptor, "onNewIntent", "Landroid/content/Intent;")
            onNewIntent.hookRedirect(intentFromActivity = false)

            2
        }

        check(hooked > 0) {
            "No in-app browser activity. The com.facebook.browser packages have new names."
        }

        // A link that stays in Facebook's browser still feeds its link history. The second switch
        // stops both writers before they exist, the upload of what they recorded, which other
        // parts of the browser reach too, the two autofill reports that name the page a form was
        // filled on, every analytics event the browser's event logger sends, the ad events its ad
        // callback logs, and the page reports to Facebook's ad integrity checks (see
        // LinkHistoryWriters.kt).
        linkHistoryFactories().forEach { mutableFactory(it).holdLinkHistory() }
        mutableFactory(linkHistoryUpload()).holdLinkHistoryUpload()
        autofillReports().forEach { mutableFactory(it).holdAutofillReport() }
        mutableFactory(adEventCallback()).holdAdEvent()
        mutableFactory(integrityCallback()).holdIntegrityReport()
        // The event logger is one long method that changes from build to build. A build where it has
        // another shape keeps the rest of the patch and sends its browser events.
        try {
            val logger = browserEventLogger()
            mutableFactory(logger).holdBrowserEvents(browserEventSamplingAnswers(logger))
        } catch (missing: PatchException) {
            patchLog.warning("${missing.message}. Facebook's browser keeps sending its analytics events.")
        }

        // A Page's Events link, which Facebook's server sends to Home, opens the Page's Events tab
        // (#319, PageEventsLinkHook.kt). A build whose mapper has another shape keeps the rest.
        try {
            UriIntentMapperFingerprint.method.routePageEventsLinks()
        } catch (missing: Exception) {
            val why = if (missing is PatchException) missing.message else "${missing::class.java.name}: ${missing.message}"
            patchLog.warning("$PATCH: $why. Page events links stay as Facebook opens them.")
        }

        enableStatus("externalBrowser")
    }
}

/** The browser activity's [name] method taking one [parameter], or a refusal naming it. */
internal fun <T : Method> lifecycleMethod(methods: Iterable<T>, activity: String, name: String, parameter: String): T =
    methods.filter { it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf(parameter) }
        .singleOrPatchException("$PATCH: $activity's $name($parameter)")

/**
 * Put the redirect after the superclass call of this method.
 *
 * With [intentFromActivity] the redirect asks the activity for its intent, as onCreate has to;
 * without it the intent is the method's parameter, as onNewIntent gets it.
 *
 * The redirect must go after the superclass call. Before it, the superclass call does not run, and
 * Android answers with `SuperNotCalledException`.
 */
internal fun MutableMethod.hookRedirect(intentFromActivity: Boolean) {
    val superCall = ownSuperCallIndex()
    val injectIndex = superCall + 1

    // The calls below name p0, and onNewIntent's p1, in operands that only reach v15. The
    // patcher's smali compiler leaves out an instruction whose register doesn't fit, without a
    // word, so a method with its parameters higher up has to stop the patch here instead.
    val highest = localRegisterCount() + if (intentFromActivity) 0 else 1
    if (highest > 15) {
        throw PatchException(
            "$PATCH: $definingClass->$name holds a parameter the redirect names in v$highest, above v15",
        )
    }
    requireSuperCallPassesParameters(superCall, if (intentFromActivity) 1 else 2)

    val traceClose = traceCloseIndex()
    val register = redirectRegister(injectIndex, traceClose)
    val intent = if (intentFromActivity) "v$register" else "p1"
    val loadIntent = if (intentFromActivity) {
        """
            invoke-virtual { p0 }, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
            move-result-object v$register
        """
    } else {
        ""
    }
    val call = """
        $loadIntent
        invoke-static { p0, $intent }, $REDIRECT
        move-result v$register
    """

    if (traceClose != null) {
        // The method opens a trace section in its prologue. A direct return leaves that section
        // open. Thus the branch jumps to the instruction that loads the marker of the close call.
        addInstructionsWithLabels(
            injectIndex,
            "$call\nif-nez v$register, :handled",
            ExternalLabel("handled", getInstruction(traceClose)),
        )
    } else {
        // There is no trace section to balance, thus the redirect returns. The label binds to the
        // real instruction that comes after, and never to one inside the injected block.
        addInstructionsWithLabels(
            injectIndex,
            "$call\nif-eqz v$register, :keepInApp\nreturn-void",
            ExternalLabel("keepInApp", getInstruction(injectIndex)),
        )
    }
}

/**
 * The register the redirect borrows after the super call at [injectIndex] - 1: the lowest local,
 * v15 or below since the redirect call names it, that nothing reads from there on. When the
 * redirect jumps to the trace section's close at [traceClose], nothing may read it from there
 * either. The redirect sits in the middle of the method, where a local can still hold something
 * the rest of it wants, so v0 isn't taken on trust.
 */
internal fun Method.redirectRegister(injectIndex: Int, traceClose: Int?): Int =
    freeLocalsAt(PATCH, injectIndex, 1, targets = listOfNotNull(traceClose)).single()

/**
 * Throws unless the super call at [superCall] passes the method's own first [count] parameter
 * registers, `this` and then the rest, in order. The redirect right after it reads p0, and
 * onNewIntent's p1, as the activity and the new intent. A method that had put something else in
 * one of them would still hand the super call the real ones from another register, and the
 * redirect would read the other value without a word.
 */
internal fun Method.requireSuperCallPassesParameters(superCall: Int, count: Int) {
    val call = implementation!!.instructions.elementAt(superCall)
    val passed = call.namedRegisters()
    val parameters = (0 until count).map { localRegisterCount() + it }
    if (passed.take(count) != parameters) {
        throw PatchException(
            "$PATCH: $definingClass->$name's super call passes ${passed.joinToString { "v$it" }}, not its own " +
                parameters.joinToString { "v$it" } + ", so they may hold something else where the redirect reads them",
        )
    }
}

/**
 * Where this method calls the method it overrides: the one `invoke-super` with its own name,
 * parameters and return type.
 *
 * Not simply the first super call. `BrowserLiteDIActivity.onCreate` makes two on both builds,
 * `onCreate` and then `getResources()`, so the first is only right while they stay in that order.
 * A redirect after a super call that comes first would finish the activity before
 * `super.onCreate` ran. None, or two, stops the patch.
 */
internal fun MutableMethod.ownSuperCallIndex(): Int =
    instructions().withIndex().filter { (_, instruction) ->
        val call = instruction.methodReferenceOrNull()
        (instruction.opcode == Opcode.INVOKE_SUPER || instruction.opcode == Opcode.INVOKE_SUPER_RANGE) &&
            call != null && call.name == name && call.returnType == returnType &&
            call.parameterTypes.map(CharSequence::toString) == parameterTypes.map(CharSequence::toString)
    }.map { it.index }
        .singleOrPatchException("$PATCH: $definingClass->$name's call to super.$name")

/**
 * Where the trace section of the method closes, or null when the method opens none.
 *
 * Facebook wraps these methods in a section. A static call that returns the marker of the section
 * opens it. A static call on the same class that takes the marker back closes it.
 *
 * The index is the instruction that feeds the close call. A jump to that instruction keeps the
 * section balanced and keeps the value of the marker.
 */
private fun MutableMethod.traceCloseIndex(): Int? {
    val instructions = instructions()

    val tracer = instructions.firstNotNullOfOrNull { instruction ->
        instruction.methodReferenceOrNull()
            ?.takeIf { instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == "I" }
            ?.definingClass
    } ?: return null

    val closeIndex = instructions.indexOfLast { instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC &&
            instruction.methodReferenceOrNull()
                ?.let { it.definingClass == tracer && it.returnType == "V" } == true
    }

    return if (closeIndex > 0) closeIndex - 1 else null
}

private fun MutableMethod.instructions(): List<Instruction> =
    implementation?.instructions?.toList()
        ?: throw IllegalStateException("$definingClass->$name has no body to patch")

private fun Instruction.methodReferenceOrNull() =
    (this as? ReferenceInstruction)?.reference as? MethodReference
