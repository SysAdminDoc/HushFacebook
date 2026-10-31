/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.order

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Most viewed first on a profile's Reels tab (#232), read from 585 on 2026-10-31. The obfuscated
 * names are for reviewers; the code finds each spot by a kept string or a shape.
 *
 * The tab is a section, the class that loads "FbShortsProfileViewerParadesSection" (585 arm64
 * LX/0NF6). Its one instance method of one parameter that loads that string (A0o) copies the
 * profile's loaded reel nodes into an ArrayList, then passes it, with its own parameter, to a
 * static (parameter, List) builder that makes the tiles. That call is reached only by falling
 * through from a null check on the list, so the hook goes in right before it and sorts the list in
 * place by the extension. The play count is the one static method of one argument that answers an
 * Integer and holds the GraphQL key hash of "play_count" (1911031876; 585 LX/0Wof;->A01 on arm64,
 * LX/0WoB;->A01 on armeabi-v7a). The extension finds the path from a list node to its argument by
 * shape, so nothing else is written down.
 */

private const val PROFILE_SORT = "Newest reels first (profile reels order)"
private const val SORT_CLASS = "$EXTENSION_PACKAGE/reels/ProfileReelsSort;"
private const val SORT_CALL =
    "$SORT_CLASS->sort(Ljava/util/List;Ljava/lang/Class;Ljava/lang/String;)V"

/** The kept name of the section that lists a profile's reels. */
internal const val PROFILE_REELS_SECTION = "FbShortsProfileViewerParadesSection"

/** The Java hash of the GraphQL field "play_count", as the play count reader loads it. */
internal const val PLAY_COUNT_KEY = 1911031876

private const val LIST_TYPE = "Ljava/util/List;"

private fun refuseProfile(detail: String): Nothing = throw PatchException("$PROFILE_SORT: $detail")

/** Where the sort hook goes, and what it is handed. */
internal class ProfileReelsSites(
    val section: Method,
    val hookAt: Int,
    val listRegister: Int,
    val classRegister: Int,
    val nameRegister: Int,
    val holder: String,
    val reader: String,
)

/** Whether [method] reads a play count: a static (one argument) method answering an Integer, holding the key. */
internal fun isPlayCountReader(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/lang/Integer;" &&
        method.parameterTypes.size == 1 &&
        method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == PLAY_COUNT_KEY } == true

/**
 * Where the sort goes, from [sections] (the methods loading [PROFILE_REELS_SECTION]) and
 * [readers] (the methods [isPlayCountReader] accepts). Refuses unless each is found exactly once.
 */
internal fun profileReelsSites(sections: List<Method>, readers: List<Method>): ProfileReelsSites {
    val candidates = sections.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.name != "<init>" && it.parameterTypes.size == 1 &&
            it.implementation != null
    }
    val section = candidates.singleOrNull() ?: refuseProfile("expected one section method of one parameter loading \"$PROFILE_REELS_SECTION\", found ${candidates.size}")
    val parameter = section.parameterTypes[0].toString()
    val code = section.implementation!!.instructions.toList()
    val builds = code.withIndex().filter { (_, instruction) ->
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@filter false
        val call = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@filter false
        call.parameterTypes.map { it.toString() } == listOf(parameter, LIST_TYPE)
    }
    val build = builds.singleOrNull() ?: refuseProfile("expected one ($parameter, List) builder call in ${section.name}, found ${builds.size}")
    val listRegister = when (val instruction = build.value) {
        is FiveRegisterInstruction -> instruction.registerD
        is RegisterRangeInstruction -> instruction.startRegister + 1
        else -> refuseProfile("the builder call is neither a five-register nor a range call")
    }
    if (listRegister > 15) refuseProfile("the list is in v$listRegister, past the registers an invoke can name")
    val free = section.freeLocalsAt(PROFILE_SORT, build.index, 2)
    val reader = readers.singleOrNull() ?: refuseProfile("expected one play count reader, found ${readers.size}")
    return ProfileReelsSites(section, build.index, listRegister, free[0], free[1], reader.definingClass, reader.name)
}

/** Puts the sort in front of the builder call, [mutable] reading the class to change. */
internal fun hookProfileReels(sites: ProfileReelsSites, mutable: (String) -> ClassDef) {
    val method = mutable(sites.section.definingClass).methods.single { it.descriptor() == sites.section.descriptor() } as MutableMethod
    method.addInstructions(
        sites.hookAt,
        """
            const-class v${sites.classRegister}, ${sites.holder}
            const-string v${sites.nameRegister}, "${sites.reader}"
            invoke-static {v${sites.listRegister}, v${sites.classRegister}, v${sites.nameRegister}}, $SORT_CALL
        """,
    )
}

/**
 * The profile Reels sort goes in. Nameless: Newest reels first carries it, and its fixture tests
 * run it on its own. Everything is found before anything changes.
 */
internal val profileReelsSortPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        val sections = classDefByStrings(PROFILE_REELS_SECTION, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { owner -> owner.methods.filter { holdsString(it, PROFILE_REELS_SECTION) } }
        val readers = mutableListOf<Method>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
            readers += classDef.methods.filter { isPlayCountReader(it) }
        }
        hookProfileReels(profileReelsSites(sections, readers)) { type -> mutableClassDefBy(type) }
    }
}
