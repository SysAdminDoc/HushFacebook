/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.media.taptoplay.isEnumNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

internal const val HIDE_SECTION = "$EXTENSION_PACKAGE/menu/MenuSections;->hideSection(Ljava/lang/Object;)Z"
internal const val HIDE_SERVER_SECTION = "$EXTENSION_PACKAGE/menu/MenuSections;->hideServerSection(Ljava/lang/Object;)Z"
internal const val SECTION_FOLDED = "$EXTENSION_PACKAGE/menu/MenuSections;->collapsed(ZLjava/lang/Object;)Z"
internal const val MUSE_DISMISSED ="$EXTENSION_PACKAGE/menu/MuseCard;->dismissed(ZLjava/lang/Object;)Z"
internal const val SHORTCUTS_KEPT = "$EXTENSION_PACKAGE/menu/MenuShortcuts;->kept(Ljava/util/List;)Ljava/util/List;"
internal const val HELP_ROWS_KEPT = "$EXTENSION_PACKAGE/menu/MenuRows;->help(Ljava/util/List;)Ljava/util/List;"
internal const val SETTINGS_ROWS_KEPT = "$EXTENSION_PACKAGE/menu/MenuRows;->settings(Ljava/util/List;)Ljava/util/List;"
internal const val LIST_COPY_OF = "$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST"
internal const val HEADER_KEPT_OPEN = "$EXTENSION_PACKAGE/menu/MenuSections;->keepsHeaderOpen(Ljava/lang/Object;)Z"

/** The boolean the patch adds to the Menu's group header, set on a header whose group is kept open. */
internal const val HEADER_FLAG = "hushKeptOpen"

/**
 * Both sections of a Menu group ask the extension about the group as soon as they know it, and
 * hand back no children when it says the group goes. See MenuSectionAnchors.kt for how the Menu is
 * built. Help and support goes only by its own switch (issue #224), and every other group, Settings
 * included, builds as Facebook built it.
 *
 * Right after the native section reads its group, whether the group is folded goes to the extension
 * too, which can answer that Settings and privacy is open, so it builds its rows without a tap
 * (issue #224). A tap on its header leaves it open too, since the next build asks again. Whether the
 * group starts open, which seeds its header's chevron and label, gets the same answer, so the header
 * opens with it. After that the header keeps whether it's collapsed in its own state and flips it on
 * every tap, so the section also marks the header it makes for a group kept open, and the header's
 * render and tap handler take a marked header as expanded. If Facebook moved the fold or the header,
 * the rest of the patch goes on without it.
 *
 * The Muse card is a bookmark, not a group: right after its component reads whether the card was
 * dismissed, the answer and the bookmark go to the extension, which can answer yes instead. See
 * MuseCardAnchors.kt.
 *
 * The Meta AI shortcut is a bookmark too, one of the Menu's shortcut rows: right after their
 * section reads its list of bookmarks, the list goes through the extension, which can leave Meta
 * AI's out, and back into an ImmutableList. See MenuShortcutAnchors.kt.
 *
 * The Help and support and Settings and privacy rows come to the native section from two builders.
 * Right after each call the list goes through the extension, which leaves out the rows picked in
 * Menu tiles to hide, and back into an ImmutableList. 584 inlined both builders into the section,
 * so there the hooks go right after it reads or writes each kept list. See MenuRowAnchors.kt.
 *
 * Accounts whose Menu slides in from the side can get the server-drawn Bloks Menu there, which none
 * of the hooks above reach (#184). The server's answer to which Menu goes in the panel goes to the
 * extension, which answers the native one while any Menu switch acts. See MenuPanelAnchors.kt.
 */
@Suppress("unused")
val hideMenuPromotionsPatch = bytecodePatch(
    name = "Hide Menu promotions",
    description = "Hides the Upgrades and Also from Meta sections and the Muse card in Facebook's Menu, so the " +
        "Menu has fewer ads for Meta's products. Settings, Help and your shortcuts stay. On by default. Turn it " +
        "off in Hushfacebook settings > Menu. Switches there, off by default, also hide the Meta AI shortcut or the " +
        "whole Help and support section and keep Settings and privacy open, and a list there hides the Menu tiles " +
        "and Help and Settings rows you pick, such as Gaming or Orders and payments.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, menuSubpagesPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val isGroupEnum = { type: String -> classDefByOrNull(type)?.let { isEnumNaming(it, SECTION_NAMES) } == true }

        val native = single(NATIVE_SECTION_KEY, "native Menu group section", ::isNativeSectionChildren)
        val nativeRead = nativeGroupRead(native, isGroupEnum) ?: throw PatchException(
            "$PATCH: ${native.definingClass}->${native.name} doesn't make one call answering the Menu's group enum " +
                "(${SECTION_NAMES.joinToString()}) and keep its answer",
        )
        val server = single(SERVER_POSITIONS.first(), "server Menu group section", ::isServerSectionChildren)
        val serverRead = serverGroupRead(server, isGroupEnum) ?: throw PatchException(
            "$PATCH: ${server.definingClass}->${server.name} doesn't read one group field of its own class",
        )
        if (serverRead.type != nativeRead.type) {
            throw PatchException(
                "$PATCH: the native section's group is ${nativeRead.type} and the server section's ${serverRead.type}",
            )
        }

        val rows = rowListHooks(native)
        val nativeMethod = mutableClassDefBy(native.definingClass).findMutableMethodOf(native)
        val fold = foldRead(native, nativeRead)
        if (fold == null) {
            patchLog.warning(
                "$PATCH: ${native.definingClass}->${native.name} doesn't read whether its group is folded once, " +
                    "from a state it casts, before its group. Settings and privacy builds folded as Facebook builds it.",
            )
        } else {
            val start = startRead(native, nativeRead)
            if (start == null) {
                patchLog.warning(
                    "$PATCH: ${native.definingClass}->${native.name} doesn't hand its header whether its group " +
                        "starts open from one flag of its own. A Settings and privacy kept open shows a folded header.",
                )
            } else {
                // Before askAboutFolding, whose code goes in above the seed and would move it.
                keepHeaderOpen(native, nativeMethod, nativeRead, start)
            }
            nativeMethod.askAboutFolding(nativeRead, fold, start)
        }
        nativeMethod.buildNothingWhenHidden(nativeRead, children(native), HIDE_SECTION)
        // On 586 the lists are kept in the static method beside the section that makes the builds.
        val site = rowListSite(native) { classDefByOrNull(it) }
        val siteMethod = if (site === native) nativeMethod else mutableClassDefBy(site.definingClass).findMutableMethodOf(site)
        siteMethod.passRowListsThrough(rows)
        mutableClassDefBy(server.definingClass).findMutableMethodOf(server)
            .buildNothingWhenHidden(serverRead, children(server), HIDE_SERVER_SECTION)

        val render = single(NT_DISMISSED_UPDATE, "Menu bookmark render", ::isBookmarkRender)
        val component = mutableClassDefBy(render.definingClass)
        val dismissal = dismissalRead(component, render) ?: throw PatchException(
            "$PATCH: ${render.definingClass}->${render.name} doesn't make one dismissal read before " +
                "\"$NT_DISMISSED_UPDATE\" and compare its answer at once",
        )
        component.findMutableMethodOf(render).askAboutDismissal(dismissal)

        // 585's folder section hands its build to a singleton, so the builder is found through the body it calls.
        val folders = folderSectionBuilders(
            classDefByStrings(FOLDER_SECTION, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val folder = folders.singleOrNull()
            ?: throw PatchException("$PATCH: expected one Menu bookmarks folder section loading \"$FOLDER_SECTION\", found ${folders.size}")
        val listRead = bookmarkListRead(folder) ?: throw PatchException(
            "$PATCH: ${folder.definingClass}->${folder.name} doesn't read one bookmark list of its own class",
        )
        mutableClassDefBy(folder.definingClass).findMutableMethodOf(folder).passBookmarksThroughShortcuts(listRead)
        askAboutBloksPanel()
        askAboutBloksMenuPage()
        enableStatus("menuPromotions")
    }
}

/** The one children builder of a class loading [string] that [wanted] takes, outside the extension. */
private fun BytecodePatchContext.single(string: String, what: String, wanted: (Method) -> Boolean): Method {
    val found = classDefByStrings(string, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter(wanted) }
    return found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $what loading \"$string\", found ${found.size}")
}

private fun BytecodePatchContext.children(method: Method): ChildrenList = childrenList(method) { call ->
    classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) }
} ?: throw PatchException(
    "$PATCH: ${method.definingClass}->${method.name} doesn't read its children from one list it builds",
)

/**
 * Right after the section puts its group in a register, the group goes to [hook], and a yes hands
 * back a new, empty list of children built the way the section builds its own. A no goes on with
 * the section's own next instruction. The list's field is read through a 4-bit operand, so the
 * one register borrowed is a free local no higher than v15; the group goes through the range form,
 * which names any register.
 */
internal fun MutableMethod.buildNothingWhenHidden(read: GroupRead, list: ChildrenList, hook: String) {
    val index = read.index + 1
    val answer = freeLocalsAt(PATCH, index, 1).single()
    addInstructionsWithLabels(
        index,
        """
            invoke-static/range { v${read.register} .. v${read.register} }, $hook
            move-result v$answer
            if-eqz v$answer, :facebook
            new-instance v$answer, ${list.builder}
            invoke-direct/range { v$answer .. v$answer }, ${list.builder}-><init>()V
            iget-object v$answer, v$answer, ${list.children}
            return-object v$answer
        """.trimIndent(),
        ExternalLabel("facebook", getInstruction(index)),
    )
}

/**
 * Right after the native section puts its group in a register, whether the group is folded and the
 * group go to [SECTION_FOLDED], and its answer replaces the one the section read at [fold]. With a
 * [start], the flag the header is seeded from, which says whether the group starts open, goes the
 * same way turned around and comes back turned around again, so the header shows what the rows do.
 * Nothing between the reads and the group writes those registers, which [foldRead] and [startRead]
 * make sure of. The call's operands are 4-bit, so the two registers borrowed are free locals no
 * higher than v15, and the moves name any register.
 */
internal fun MutableMethod.askAboutFolding(read: GroupRead, fold: FoldRead, start: StartRead? = null) {
    val index = read.index + 1
    val (folded, group) = freeLocalsAt(PATCH, index, 2)
    // The start flag says whether the group starts open, so it's turned into a fold for the
    // question and the answer is turned back.
    val header = start?.let {
        """
            xor-int/lit8 v$folded, v${it.register}, 0x1
            invoke-static { v$folded, v$group }, $SECTION_FOLDED
            move-result v$folded
            xor-int/lit8 v$folded, v$folded, 0x1
            move/from16 v${it.register}, v$folded
        """.trimIndent()
    }.orEmpty()
    addInstructions(
        index,
        """
            move/from16 v$folded, v${fold.register}
            move-object/from16 v$group, v${read.register}
            invoke-static { v$folded, v$group }, $SECTION_FOLDED
            move-result v$folded
            move/from16 v${fold.register}, v$folded
        """.trimIndent() + "\n" + header,
    )
}

/**
 * Marks the header the native section makes for a group the extension keeps open, with a
 * [HEADER_FLAG] the patch adds to the header's class (false on every header nothing marks), and has
 * each of the header's reads of whether it's collapsed take a marked header as expanded, so a tap
 * leaves its arrow up and its label on "Section is expanded" (#224 follow-up). When the seed, the
 * header or its reads aren't where they were, the header goes as Facebook draws it and the rest of
 * the patch goes on.
 */
private fun BytecodePatchContext.keepHeaderOpen(native: Method, nativeMethod: MutableMethod, group: GroupRead, start: StartRead) {
    val seed = headerSeed(native, group, start)
    val headerClass = seed?.takeIf { classDefByOrNull(it.headerClass) != null }?.let { mutableClassDefBy(it.headerClass) }
    val reads = headerClass?.let(::headerStateReads)
    if (seed == null || headerClass == null || reads == null || reads.count { it.click } != 1 || reads.all { it.click } ||
        headerClass.fields.any { it.name == HEADER_FLAG }
    ) {
        patchLog.warning(
            "$PATCH: ${native.definingClass}->${native.name} doesn't seed one header whose render and tap handler each " +
                "read its collapsed state once. A tap on a Settings and privacy kept open turns its arrow down.",
        )
        return
    }
    headerClass.instanceFields.add(
        ImmutableField(headerClass.type, HEADER_FLAG, "Z", AccessFlags.PUBLIC.value, null, null, null).toMutable(),
    )
    nativeMethod.seedHeaderFlag(seed)
    reads.forEach { headerClass.findMutableMethodOf(it.method).keepReadOpen(it, headerClass.type) }
}

/**
 * Right after the native section seeds its header, the group goes to [HEADER_KEPT_OPEN] and the
 * answer into the header's [HEADER_FLAG]. The group goes through the range form, which names any
 * register. The store's operands are 4-bit, so the one register borrowed is a free local no higher
 * than v15, and the header's register is already one, the seed's own store naming it.
 */
internal fun MutableMethod.seedHeaderFlag(seed: HeaderSeed) {
    val index = seed.index + 1
    val flag = freeLocalsAt(PATCH, index, 1).single()
    addInstructions(
        index,
        """
            invoke-static/range { v${seed.group} .. v${seed.group} }, $HEADER_KEPT_OPEN
            move-result v$flag
            iput-boolean v$flag, v${seed.header}, ${seed.headerClass}->$HEADER_FLAG:Z
        """.trimIndent(),
    )
}

/**
 * Right before [read]'s stash the header's [HEADER_FLAG] goes into the read's free local, and right
 * after the read a marked header reads as expanded: the render clears the collapsed state it reads,
 * and the tap handler sets it, so the flip it hands on and the label it sets both say expanded.
 * Booleans stay booleans through `and-int`, `or-int` and an `xor-int/lit8` with 1, so the method
 * verifies as before. The code after the read goes in first, so the stash stays where it was found.
 */
internal fun MutableMethod.keepReadOpen(read: HeaderStateRead, headerClass: String) {
    val mark = read.free
    val state = read.register
    val force = if (read.click) {
        "or-int v$state, v$state, v$mark"
    } else {
        """
            xor-int/lit8 v$mark, v$mark, 0x1
            and-int v$state, v$state, v$mark
        """.trimIndent()
    }
    addInstructions(read.read + 1, force)
    addInstructions(read.stash, "iget-boolean v$mark, v${read.header}, $headerClass->$HEADER_FLAG:Z")
}

/**
 * Right after the folder section reads its bookmarks, a list that's there goes through
 * [SHORTCUTS_KEPT], which never answers null for one, and back through ImmutableList.copyOf into
 * the same register. `copyOf` hands an ImmutableList back as it is, so a list the extension left
 * alone is Facebook's own. A missing list goes on to the section's own null check untouched.
 */
internal fun MutableMethod.passBookmarksThroughShortcuts(read: BookmarkListRead) =
    passListThrough(read.index, read.register, SHORTCUTS_KEPT)

/**
 * Right after the instruction at [at] puts a list in [register], a list that's there goes through
 * [hook] and back through ImmutableList.copyOf into the same register. A missing list goes on to
 * the method's own next instruction untouched.
 */
internal fun MutableMethod.passListThrough(at: Int, register: Int, hook: String) {
    val index = at + 1
    addInstructionsWithLabels(
        index,
        """
            if-eqz v$register, :facebook
            invoke-static/range { v$register .. v$register }, $hook
            move-result-object v$register
            invoke-static/range { v$register .. v$register }, $LIST_COPY_OF
            move-result-object v$register
        """.trimIndent(),
        ExternalLabel("facebook", getInstruction(index)),
    )
}

/**
 * A row list build the native section calls, by its signature, and the extension hook its list goes
 * through. On 584, where the section keeps the list itself, [call] names the field [kept] instead.
 */
internal data class RowListHook(val call: String, val hook: String, val kept: FieldReference? = null)

private fun signature(call: MethodReference) =
    "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"

/**
 * The native section's two row list builds, Settings and privacy's then Help and support's, each
 * with its hook, once both are found once on one class and share the one row item MenuRows can
 * read. 584 calls no build: its two kept lists ([keptRowLists]) take the hooks, once the section
 * builds the one row item MenuRows can read. So do those of 586's Android 9 build, kept in the
 * static method beside the section. [classDef] finds a class of the app by type.
 */
internal fun rowListHooks(native: Method, classDef: (String) -> ClassDef?): List<RowListHook> {
    // On 586 a static method beside the section makes the builds or keeps the lists ([rowListSite]).
    val site = rowListSite(native, classDef)
    val builds = rowListBuilds(site)
    if (builds.isEmpty()) {
        val kept = keptRowLists(site) ?: throw PatchException(
            "$PATCH: expected ${site.definingClass}->${site.name} to build one Settings and privacy row list " +
                "or keep it beside the Help and support one, found neither",
        )
        val items = constructedTypes(site).filter { type -> classDef(type)?.let(::isReadableRowItem) == true }
        if (items.size != 1) {
            throw PatchException("$PATCH: ${site.definingClass}->${site.name} builds ${items.size} readable row items, not one")
        }
        return listOf(
            RowListHook(reference(kept.settings), SETTINGS_ROWS_KEPT, kept.settings),
            RowListHook(reference(kept.help), HELP_ROWS_KEPT, kept.help),
        )
    }
    val settings = builds.singleOrNull() ?: throw PatchException(
        "$PATCH: expected ${site.definingClass}->${site.name} to build one Settings and privacy row list, found ${builds.size}",
    )
    val helps = site.implementation!!.instructions
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { isHelpRowListBuild(it, settings.definingClass) }
        .distinctBy(::signature)
    val help = helps.singleOrNull() ?: throw PatchException(
        "$PATCH: expected ${site.definingClass}->${site.name} to build one Help and support row list, found ${helps.size}",
    )
    val owner = classDef(settings.definingClass)
        ?: throw PatchException("$PATCH: the row list builder ${settings.definingClass} isn't in this build")
    val builders = listOf(settings, help).map { call ->
        owner.methods.singleOrNull { signature(it) == signature(call) && it.implementation != null }
            ?: throw PatchException("$PATCH: ${signature(call)} has no body in this build")
    }
    val items = builders.map(::constructedTypes).reduce { one, other -> one intersect other }
        .filter { type -> classDef(type)?.let(::isReadableRowItem) == true }
    if (items.size != 1) {
        throw PatchException("$PATCH: the Help and Settings row builders share ${items.size} readable row items, not one")
    }
    return listOf(RowListHook(signature(settings), SETTINGS_ROWS_KEPT), RowListHook(signature(help), HELP_ROWS_KEPT))
}

private fun BytecodePatchContext.rowListHooks(native: Method) = rowListHooks(native) { classDefByOrNull(it) }

/**
 * Each of [hooks]' lists, right after the section keeps it, goes through its hook: after the build's
 * call, or on 584 after the one read of the kept list and the one write of a list just built, so the
 * kept list stays Facebook's own. The reads are found on this method as it is now, after the group
 * hook moved its code down, and hooked from the last up so the earlier ones stay where they were found.
 */
internal fun MutableMethod.passRowListsThrough(hooks: List<RowListHook>) {
    hooks.flatMap { hook ->
        val kept = hook.kept
        if (kept != null) {
            val reads = keptListReads(this, kept) ?: throw PatchException(
                "$PATCH: $definingClass->$name has another way into the code after it reads or writes ${hook.call}",
            )
            if (reads.size != 2) {
                throw PatchException("$PATCH: expected $definingClass->$name to read ${hook.call} once and write it once, found ${reads.size}")
            }
            return@flatMap reads.map { it to hook.hook }
        }
        val reads = rowListReads(this) { signature(it) == hook.call }
            ?: throw PatchException("$PATCH: $definingClass->$name doesn't keep the list ${hook.call} hands back")
        val read = reads.singleOrNull()
            ?: throw PatchException("$PATCH: expected $definingClass->$name to call ${hook.call} once, found ${reads.size}")
        listOf(read to hook.hook)
    }.sortedByDescending { it.first.index }.forEach { (read, hook) -> passListThrough(read.index, read.register, hook) }
}

/**
 * Right after the bookmark component reads whether its card was dismissed, the answer and the
 * bookmark go to [MUSE_DISMISSED], and what it says replaces Facebook's answer before the render
 * compares it with the card's state. Both registers fit the non-range call's 4-bit operands, which
 * [dismissalRead] makes sure of.
 */
internal fun MutableMethod.askAboutDismissal(read: DismissalRead) = addInstructions(
    read.index + 1,
    """
        invoke-static { v${read.answer}, v${read.bookmark} }, $MUSE_DISMISSED
        move-result v${read.answer}
    """.trimIndent(),
)
