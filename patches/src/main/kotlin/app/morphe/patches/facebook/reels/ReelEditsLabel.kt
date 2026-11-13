/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * "Made with Edits" under a reel's caption (#314). Read from 585, 2026-10-30, arm64; the obfuscated
 * names here are for reviewers, the code never writes one down.
 *
 * - The line under the caption is one of three components a factory (LX/09dx;->A00) picks by a
 *   number a helper (LX/09dy;->A00) works out from the video. A video whose tree has
 *   is_made_with_edits_app true gets 1, and the factory builds LX/0cA4;.
 * - That component's render reads the same flag again and draws string 0x7f14289e when it's true.
 *   Otherwise it returns null, which draws nothing. The hook answers null first thing.
 * - The chips under a reel are a list of their own (XFBFBShortsEditsAppAttribution is one the chips
 *   switch already hides), so the chip filter never saw this line. It goes with the same switch.
 * - The other two readers of the flag are static helpers, not renders.
 * - 587 (2026-11-12) moved the read out of the render: it asks a tree model's interface for the flag
 *   (arm64 LX/0PTl;->render calls LX/0DXO;->HdZ()Z, armeabi-v7a LX/0DXe;->HdU()Z) and draws nothing
 *   unless that answers true. The interface has two tree classes behind it, a TreeJNI subclass
 *   reading the key itself (LX/0CTk;, v7 LX/0CTD;) and a delegating tree asking its delegate
 *   (LX/0md0;, v7 LX/0mbN;). The TreeJNI one names the interface method, so the render is the one
 *   asking it. The flag's other callers are still static helpers.
 */

internal const val HIDE_EDITS_LABEL = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideEditsLabel()Z"

/** The video flag the line is drawn for. Its tree key is the name's String.hashCode(). */
internal val MADE_WITH_EDITS = "is_made_with_edits_app".hashCode()

private const val PATCH = "Clean up Reels"

/** Whether [method] answers the Edits flag for a tree model: an instance ()Z reading [MADE_WITH_EDITS] and only that through TreeJNI. */
internal fun isEditsFlagGetter(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.isNotEmpty() || method.returnType != "Z") return false
    val reads = treeBooleanReads(method)
    return reads.isNotEmpty() && reads.all { it.keys == setOf(MADE_WITH_EDITS) }
}

/**
 * The calls that ask [classDef]'s Edits flag getters ([isEditsFlagGetter]), as `type->name`: the
 * getter on the class itself and on each interface it implements, which is what a render asks.
 */
internal fun editsGetterCalls(classDef: ClassDef): Set<String> = classDef.methods.filter(::isEditsFlagGetter)
    .flatMap { getter -> (classDef.interfaces + classDef.type).map { "$it->${getter.name}" } }.toSet()

/**
 * Whether [method] is the "Made with Edits" line's render: a Litho `render`, an instance method of one
 * argument with an object back, that reads [MADE_WITH_EDITS] and only that through TreeJNI.getBooleanValue,
 * or (587) asks one of [getters] ([editsGetterCalls]) and reads no other flag itself.
 */
internal fun isEditsLabelRender(method: Method, getters: Set<String> = emptySet()): Boolean {
    if (method.name != RENDER || AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.size != 1 ||
        !method.returnType.startsWith("L")
    ) return false
    val reads = treeBooleanReads(method)
    if (reads.any { it.keys != setOf(MADE_WITH_EDITS) }) return false
    return reads.isNotEmpty() || asksEditsGetter(method, getters)
}

private fun asksEditsGetter(method: Method, getters: Set<String>): Boolean = getters.isNotEmpty() &&
    (method.implementation?.instructions?.toList() ?: emptyList()).any { instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_VIRTUAL) return@any false
        val call = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@any false
        call.returnType == "Z" && call.parameterTypes.isEmpty() && "${call.definingClass}->${call.name}" in getters
    }

/** The line's render asks the extension first thing and answers null, Facebook's own answer for any other video. */
internal fun BytecodePatchContext.hideEditsLabel() {
    val renders = mutableListOf<Pair<String, Method>>()
    val getters = mutableSetOf<String>()
    classDefForEachReferencing(names = listOf("getBooleanValue")) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        getters += editsGetterCalls(classDef)
        classDef.methods.filter { isEditsLabelRender(it) }.mapTo(renders) { classDef.type to it }
    }
    // 587: the render asks the getter's interface instead of reading the tree.
    if (getters.isNotEmpty()) {
        classDefForEachReferencing(names = getters.map { it.substringAfter("->") }.distinct()) { classDef ->
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
            classDef.methods.filter { isEditsLabelRender(it, getters) }.mapTo(renders) { classDef.type to it }
        }
    }
    val (owner, render) = renders.distinctBy { (type, method) -> "$type->${method.name}${method.parameterTypes.map(CharSequence::toString)}" }
        .singleOrNull()
        ?: throw PatchException("$PATCH: expected one render reading only is_made_with_edits_app, found ${renders.size}")
    mutableClassDefBy(owner).methods.single {
        it.name == render.name && it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
    }.returnNullWhen(HIDE_EDITS_LABEL)
}
