/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.savedbutton

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.extendsClass
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val PATCH = "Saved button in search"

/** The query a search bar is told to show, which only the bar's own setter takes twice over. */
internal const val GRAPH_SEARCH_QUERY = "Lcom/facebook/search/api/GraphSearchQuery;"

internal const val LINEAR_LAYOUT = "Landroid/widget/LinearLayout;"

internal const val ADD_TEXT_WATCHER = "Landroid/widget/TextView;->addTextChangedListener(Landroid/text/TextWatcher;)V"

internal const val SAVED_BUTTON_CLASS = "$EXTENSION_PACKAGE/search/SearchSavedButton;"

internal const val ATTACH = "$SAVED_BUTTON_CLASS->attach($LINEAR_LAYOUT)V"

/**
 * Saved button in search (#433).
 *
 * Facebook's search bar is a LinearLayout (586 arm64 `LX/05tk;`, under `LX/03ws;` and `LX/02Eg;`)
 * holding the search field, its clear button and two stubs. It's the one class with an instance
 * method taking `(GraphSearchQuery, GraphSearchQuery, String)`, the setter for what the bar shows.
 * Each of its three constructors finds those children, then hands the field its TextWatcher
 * (`TextView.addTextChangedListener`) and goes on to tidy the field for right to left text, which
 * ends on `return-void` as the branch's target.
 *
 * Right after the TextWatcher call, each constructor hands the bar to the extension, which puts a
 * Saved button at its end with the switch on.
 */
@Suppress("unused")
val searchSavedButtonPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Saved button in search",
    description = "Adds a Saved button at the end of the search bar, so your saved posts and videos are one tap " +
        "away. Starts off. Turn it on in Hushfacebook settings > Search.",
) {
    category("Navigation")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("searchSavedButton")
        val bars = searchBars()
        val bar = bars.singleOrNull()
            ?: throw PatchException("$PATCH: expected one search bar class, found ${bars.map { it.type }}")
        if (!extendsClass(bar.type, LINEAR_LAYOUT)) {
            throw PatchException("$PATCH: the search bar ${bar.type} is no longer a LinearLayout")
        }
        val constructors = mutableClassDefBy(bar.type).methods.filter { it.name == "<init>" }
        if (constructors.isEmpty()) throw PatchException("$PATCH: the search bar ${bar.type} has no constructor")
        for (constructor in constructors) {
            val watchers = textWatcherCalls(constructor)
            val at = watchers.singleOrNull() ?: throw PatchException(
                "$PATCH: expected ${bar.type}'s constructor ${constructor.parameterTypes} to hand its field one " +
                    "TextWatcher, found ${watchers.size}",
            )
            constructor.addInstruction(at + 1, "invoke-static { p0 }, $ATTACH")
        }
        enableStatus("searchSavedButton")
    }
}

/** Every class Facebook ships that [isSearchBar] picks. */
internal fun BytecodePatchContext.searchBars(): List<ClassDef> {
    val found = mutableListOf<ClassDef>()
    classDefForEachReferencing(types = listOf(GRAPH_SEARCH_QUERY)) { classDef ->
        if (isSearchBar(classDef)) found += classDef
    }
    return found
}

/** A class outside the extension with an instance `(GraphSearchQuery, GraphSearchQuery, String)V` method. */
internal fun isSearchBar(classDef: ClassDef): Boolean =
    !classDef.type.startsWith(EXTENSION_CLASSES) && classDef.methods.any { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map { it.toString() } == listOf(GRAPH_SEARCH_QUERY, GRAPH_SEARCH_QUERY, "Ljava/lang/String;")
    }

/** Where [method] calls `TextView.addTextChangedListener`. */
internal fun textWatcherCalls(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_VIRTUAL &&
            (code[at] as ReferenceInstruction).reference.toString() == ADD_TEXT_WATCHER
    }
}
