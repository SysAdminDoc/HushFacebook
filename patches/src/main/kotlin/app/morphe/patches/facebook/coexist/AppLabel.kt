/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/*
 * The App name option of Hushfacebook settings (#105): a name picked while patching, shown under
 * the icon and in App info instead of Facebook's, so a patched copy is easy to tell apart. It
 * changes labels only. Clone app is what changes the package so a copy installs beside Facebook.
 *
 * Both 582 manifests list ten launcher aliases of FbMainTabActivity, the one enabled and nine
 * icon choices Facebook turns on in its place, and none has a label of its own, so they all show
 * the application's. The name goes in a string resource that the application, every launcher
 * entry and every screen labeled with the application's own label point at. A label written into
 * the manifest as text is read as a reference when it starts with @ or ?, and punctuation in it
 * is up to the manifest's own parsing, so the resource is quoted with escapes instead.
 *
 * The recent apps screen shows the task's own label, and Facebook sets it from its brand string,
 * so Hushfacebook settings also swaps the label at each task description Facebook makes
 * ([taskLabels]). Android 16 read "<name> Facebook" there before that (2026-10-12).
 *
 * Root mounting keeps the stock app's manifest, so a mounted install keeps Facebook's name.
 */

/** The string resource the labels point at. Added only when a name was picked. */
internal const val APP_NAME_STRING = "hushfacebook_app_name"

/** Longer than any launcher shows on one line, and long enough for any name a person picks. */
internal const val MAX_APP_NAME = 50

private const val OPTION = "Hushfacebook settings, App name"
private const val LABEL = "android:label"
private const val STRINGS = "res/values/strings.xml"

/** The picked name as it will be shown, or null to keep Facebook's labels. Refuses one no label can hold. */
internal fun pickedAppName(raw: String?): String? {
    val name = raw?.trim().orEmpty()
    if (name.isEmpty()) return null
    if (name.any { it.isISOControl() }) throw PatchException("$OPTION: the name can't hold a line break or a tab.")
    if (name.codePointCount(0, name.length) > MAX_APP_NAME) {
        throw PatchException("$OPTION: the name is ${name.codePointCount(0, name.length)} characters, and the limit is $MAX_APP_NAME.")
    }
    return name
}

/**
 * [name] as a string resource's text that Android reads back exactly as typed: in double quotes,
 * so spaces and apostrophes stay, with a backslash and a double quote escaped, and a leading @ or ?
 * escaped so it's never taken for a reference. The XML writer escapes & and < itself.
 */
internal fun resourceText(name: String): String {
    val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"")
    val unreferenced = if (escaped.startsWith("@") || escaped.startsWith("?")) "\\$escaped" else escaped
    return "\"$unreferenced\""
}

private fun NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

private fun Element.childElements(tag: String) = getElementsByTagName(tag).elements()

/** Whether this activity or alias is one the launcher lists: MAIN and LAUNCHER in one intent filter. */
internal fun Element.isLauncherEntry(): Boolean = childElements("intent-filter").any { filter ->
    filter.childElements("action").any { it.getAttribute("android:name") == "android.intent.action.MAIN" } &&
        filter.childElements("category").any { it.getAttribute("android:name") == "android.intent.category.LAUNCHER" }
}

/**
 * Points the application's label, every launcher entry's and every screen's that showed the
 * application's own label at [reference]. Answers how many entries and screens it relabeled.
 * Refuses, before changing anything, a manifest with no application label or no launcher entry.
 */
internal fun Document.labelWith(reference: String): Int {
    val application = getElementsByTagName("application").elements().singleOrNull()
        ?: throw PatchException("$OPTION: Facebook's manifest has no single application element.")
    val stock = application.getAttribute(LABEL)
    if (stock.isEmpty()) throw PatchException("$OPTION: Facebook's application has no label to replace.")
    val entries = getElementsByTagName("activity").elements() + getElementsByTagName("activity-alias").elements()
    val relabeled = entries.filter { it.isLauncherEntry() || it.getAttribute(LABEL) == stock }
    if (relabeled.none { it.isLauncherEntry() }) throw PatchException("$OPTION: Facebook's manifest has no launcher entry.")
    application.setAttribute(LABEL, reference)
    relabeled.forEach { it.setAttribute(LABEL, reference) }
    return relabeled.size
}

/** Adds the [stringName] resource holding [name], or rewrites it when a build already has one. */
internal fun Document.putAppName(name: String, stringName: String = APP_NAME_STRING) {
    val resources = documentElement
    val string = resources.childElements("string").firstOrNull { it.getAttribute("name") == stringName }
        ?: createElement("string").also {
            it.setAttribute("name", stringName)
            resources.appendChild(it)
        }
    string.setAttribute("translatable", "false")
    string.setAttribute("formatted", "false")
    string.textContent = resourceText(name)
}

/**
 * Writes the name [appName] answers into the labels. With no name picked, it opens no file, so
 * the manifest and the resources stay byte for byte as they were.
 */
internal fun appLabelPatch(appName: () -> String?) = resourcePatch {
    execute {
        val name = pickedAppName(appName()) ?: return@execute
        // Checked before the manifest changes, so a build without it is refused untouched.
        // Patcher 2.0 decodes only the files a patch asks for: an undecoded file does not exist yet.
        decode(STRINGS)
        if (!get(STRINGS, false).exists()) throw PatchException("$OPTION: Facebook has no $STRINGS to hold the name.")
        val relabeled = document("AndroidManifest.xml").use { it.labelWith("@string/$APP_NAME_STRING") }
        document(STRINGS).use { it.putAppName(name) }
        patchLog.info("$OPTION: the app is called \"$name\" under its icon and in App info ($relabeled launcher entries and screens).")
    }
}

/** The extension's answer for the recent apps screen's label. */
internal const val TASK_LABEL =
    "Lapp/morphe/extension/facebook/coexist/AppName;->taskLabel(Ljava/lang/String;)Ljava/lang/String;"

private const val TASK_DESCRIPTION = "Landroid/app/ActivityManager\$TaskDescription;"

/** A task description Facebook makes from a label: the [method], the constructor at [at] and the label's register. */
internal class TaskLabel(val method: Method, val at: Int, val label: Int)

/**
 * Every place in [holder] that makes a task description, which carries the label the recent apps
 * screen shows, from a String: a TaskDescription constructor whose first parameter is the label.
 * 582 makes its main task's in `LX/2NP;->run` from Facebook's brand string, the one its About and
 * login screens show, so the label is swapped at the call and the string is left alone. Refuses a
 * range call, whose label register isn't read here, and a call whose label register is another of
 * its arguments too or is read again afterwards, since the swap writes the app's name into it.
 */
internal fun taskLabels(holder: ClassDef): List<TaskLabel> = holder.methods.flatMap { method ->
    val code = method.implementation?.instructions?.toList().orEmpty()
    code.indices.mapNotNull { at ->
        val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        if (called.definingClass != TASK_DESCRIPTION || called.name != "<init>" ||
            called.parameterTypes.firstOrNull()?.toString() != "Ljava/lang/String;"
        ) return@mapNotNull null
        val where = "$OPTION: ${holder.type}->${method.name}"
        if (code[at].opcode != Opcode.INVOKE_DIRECT) throw PatchException("$where makes a task description with ${code[at].opcode.name}")
        val call = code[at] as FiveRegisterInstruction
        val label = call.registerD
        val others = listOf(call.registerC, call.registerE, call.registerF, call.registerG).take(call.registerCount - 1)
        if (label in others) throw PatchException("$where passes its task's label register as another argument too")
        if (at + 1 < code.size && label in RegisterLiveness.of(method).liveInto(at + 1)) {
            throw PatchException("$where reads its task's label register again after the task description")
        }
        TaskLabel(method, at, label)
    }
}

/**
 * Right before the constructor at [at]: the label in [label] goes through [TASK_LABEL] and comes
 * back as the app's own, so the recent apps screen shows the picked name. Refuses a jump straight
 * to the constructor, which would skip the swap.
 */
internal fun MutableMethod.labelsTaskWithAppName(at: Int, label: Int) {
    val call = implementation!!.instructions[at] as BuilderInstruction
    if (call.location.labels.isNotEmpty()) throw PatchException("$OPTION: $definingClass->$name jumps straight to its task description")
    addInstructions(
        at,
        """
            invoke-static { v$label }, $TASK_LABEL
            move-result-object v$label
        """,
    )
}
