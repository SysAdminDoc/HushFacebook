/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutableAndPreserveParameters
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val CHAT_LIST = "$EXTENSION_PACKAGE/chats/ChatList;"
internal const val NOTES_TILES_HOOK = "$CHAT_LIST->notesTiles(Ljava/util/List;)Ljava/util/List;"
internal const val HIDES_NOTES_ROW = "$CHAT_LIST->hidesNotesRow(Ljava/lang/Object;)Z"
internal const val HIDES_PROMOTION = "$CHAT_LIST->hidesPromotion()Z"
internal const val META_AI_BUTTON_HOOK = "$CHAT_LIST->metaAiButton(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val PICKER_META_AI_HOOK = "$CHAT_LIST->showsSearchMetaAiButton(Z)Z"
internal const val IMMUTABLE_COPY_OF = "$IMMUTABLE_LIST_TYPE->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST_TYPE"

/**
 * Three switches for the list inside Facebook's own Chats: the row of notes and active friends
 * above the chats (its tiles and the section drawing it), the promotion banners at the top, and the Meta AI button in the search row and
 * on the search screen. See
 * ChatListAnchors.kt for what each hook sits on and why only these pieces are here.
 *
 * Every hook asks its switch when Chats builds the piece, so the patch can stay in a build with
 * the switches off. Every anchor is found before the first edit.
 */
@Suppress("unused")
val cleanUpChatListPatch = bytecodePatch(
    // The README table check reads this literal; CHAT_LIST_PATCH carries the same text for the messages.
    name = "Clean up Facebook's chat list",
    description = "Tidies Chats inside Facebook. One switch empties the row of notes and who's active above your " +
        "chats, and another removes promotional banners like the one asking you to turn on notifications. A third " +
        "takes the Meta AI button off the search row and the search screen. Your chats stay. Starts off. Turn on the parts you want in " +
        "Hushfacebook settings > Chats and Messenger.",
    default = true,
) {
    category("Chats")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val store = classDefByStrings(NOTES_TILES, StringComparisonType.EQUALS).mapNotNull(::notesTrayOf).singleOrNull()
            ?: throw PatchException(
                "$CHAT_LIST_PATCH: expected one tile state whose constructor checks \"$NOTES_TILES\" and stores " +
                    "an ImmutableList, found none or several",
            )
        val sections = classDefByStrings(NOTES_SECTION, StringComparisonType.EQUALS)
            .flatMap { owner -> owner.methods.mapNotNull(::notesRowExit) }
        val section = sections.singleOrNull() ?: throw PatchException(
            "$CHAT_LIST_PATCH: expected one render loading \"$NOTES_SECTION\" and \"$NOTES_SECTION_KEY\" with an " +
                "empty way out, found ${sections.size}",
        )
        val inbox = classDefByOrNull(section.method.definingClass)?.let(::notesSectionInbox) ?: throw PatchException(
            "$CHAT_LIST_PATCH: expected the notes section ${section.method.definingClass} to keep one " +
                "ThreadListParams, so Dating's row can be told apart, found none or several",
        )
        val questions = PROMOTION_TAGS.map { tag ->
            val found = classDefByStrings(tag, StringComparisonType.EQUALS).mapNotNull { bannerQuestion(it, tag) }
            found.singleOrNull() ?: throw PatchException(
                "$CHAT_LIST_PATCH: expected one Chats plugin whose builder loads \"$tag\" and whose show question " +
                    "takes ThreadListParams, found ${found.size}",
            )
        }
        val openers = classDefByStrings(META_AI_ICON_CLICKED, StringComparisonType.EQUALS)
            .filter(::opensMetaAiFromSearchRow).map { it.type }.toSet()
        // 583's 64-bit tap asks a string table for its entry, so the taps are found by naming it; 584's
        // search row hands its button to an outlined helper, read through the same lookup.
        val resolve: (MethodReference) -> Method? = { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
        val buttons = classDefByStrings(SEARCH_BAR_TAG, StringComparisonType.EQUALS)
            .flatMap { owner -> owner.methods.mapNotNull { metaAiButtonSite(it, openers, resolve) } }
        val button = buttons.singleOrNull() ?: throw PatchException(
            "$CHAT_LIST_PATCH: expected one search row that tags \"$SEARCH_BAR_TAG\" and builds a Meta AI button " +
                "reporting \"$META_AI_ICON_CLICKED\", found ${buttons.size}",
        )
        val pickers = methodsNaming(PEOPLE_PICKER_ENTRY, ::callsMetaAiLauncher).map { it.definingClass }.distinct()
            .mapNotNull { type -> classDefByOrNull(type)?.let { opener -> pickerTapOf(opener, resolve)?.let { tap -> type to tap } } }
        val (pickerOpener, pickerTap) = pickers.singleOrNull() ?: throw PatchException(
            "$CHAT_LIST_PATCH: expected one Meta AI tap opening \"$PEOPLE_PICKER_ENTRY\" after reading its row's " +
                "CallerContext, found ${pickers.size}",
        )
        val pickerRow = pickerTap.row
        val gates = classDefByOrNull(pickerRow)?.methods?.mapNotNull { pickerButtonGate(it, pickerOpener, pickerTap.case) }.orEmpty()
        val gate = gates.singleOrNull() ?: throw PatchException(
            "$CHAT_LIST_PATCH: expected one method of $pickerRow that sets its Meta AI flag once and builds the " +
                "button behind it, found ${gates.size}",
        )
        // Everything that edits needs room first, so a build without it is refused before any edit.
        val tray = mutableClassDefBy(store.method.definingClass).methods.single {
            it.name == store.method.name && it.parameterTypes.map(Any::toString) == store.method.parameterTypes.map(Any::toString)
        }
        val render = mutableClassDefBy(section.method.definingClass).methods.single {
            it.name == section.method.name && it.parameterTypes.map(Any::toString) == section.method.parameterTypes.map(Any::toString)
        }
        render.requireLocals(CHAT_LIST_PATCH, 1)
        val asks = questions.map { question ->
            mutableClassDefBy(question.definingClass).methods.single {
                it.name == question.name && it.parameterTypes.map(Any::toString) == question.parameterTypes.map(Any::toString)
            }
        }
        val row = mutableClassDefBy(button.method.definingClass).methods.single {
            it.name == button.method.name && it.parameterTypes.map(Any::toString) == button.method.parameterTypes.map(Any::toString)
        }
        val picker = mutableClassDefBy(gate.method.definingClass).methods.single {
            it.name == gate.method.name && it.parameterTypes.map(Any::toString) == gate.method.parameterTypes.map(Any::toString)
        }
        tray.emptyTilesWhileHidden(store.store, store.list)
        render.leaveNotesRowEmptyWhileHidden(section.exit, inbox)
        row.dropMetaAiButtonWhileHidden(button)
        picker.lowerMetaAiFlagWhileHidden(gate)
        // 577's diode question has no local at all (it works in its parameter registers), so a
        // question without one is replaced by a copy with its parameters moved down, freeing v0.
        asks.forEach { ask ->
            val target = if (ask.localRegisterCount() >= 1) ask
            else ask.cloneMutableAndPreserveParameters(mutableClassDefBy(ask.definingClass))
            target.answerNoWhileHidingPromotions()
        }
        enableStatus("chatListCleanup")
    }
}

/**
 * Before the constructor stores its tile list, the list goes through the extension and back
 * through ImmutableList.copyOf, which hands the same list back unless the switch asks for none.
 * A range call, because the list's register can sit anywhere in a method with many registers.
 */
internal fun MutableMethod.emptyTilesWhileHidden(store: Int, list: Int) {
    if (getInstruction<OneRegisterInstruction>(store).registerA != list) {
        throw PatchException("$CHAT_LIST_PATCH: $definingClass->$name doesn't store the list from v$list at $store")
    }
    addInstructions(
        store,
        """
            invoke-static/range { v$list .. v$list }, $NOTES_TILES_HOOK
            move-result-object v$list
            invoke-static/range { v$list .. v$list }, $IMMUTABLE_COPY_OF
            move-result-object v$list
        """,
    )
}

/**
 * First thing in the notes row's render: hand the extension the section's [inbox] params, and while
 * the switch says the row goes, jump to the render's own empty way out at [exit], so the row isn't
 * drawn at all, your own note tile included. Otherwise Facebook's render runs from its first
 * instruction. The params tell Dating's Matches screen, which draws its new matches with this
 * section, from Chats. `this` is copied down first, since a big render's `p0` sits past `iget-object`'s
 * reach.
 */
internal fun MutableMethod.leaveNotesRowEmptyWhileHidden(exit: Int, inbox: FieldReference) {
    requireLocals(CHAT_LIST_PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${inbox.definingClass}->${inbox.name}:${inbox.type}
            invoke-static { v0 }, $HIDES_NOTES_ROW
            move-result v0
            if-nez v0, :empty
        """,
        ExternalLabel("empty", getInstruction(exit)),
    )
}

/**
 * Right after the search row builds its Meta AI button, the button goes through the extension,
 * which hands back null while the switch is on; the row's own null check then leaves the button
 * out. The cast gives the register back its type for whatever reads it next.
 *
 * Where the build is outlined (584), the button's builder goes through the extension right before
 * the call to the helper, into the site's free local, and a null skips the call, so the button is
 * neither built nor added. The builder's own register is left as it was. Any jump to the call runs
 * the check too.
 */
internal fun MutableMethod.dropMetaAiButtonWhileHidden(site: MetaAiButtonSite) {
    val register = site.register
    if (site.helper != null) {
        val answer = site.answer ?: throw PatchException("$CHAT_LIST_PATCH: no free local before the outlined button build")
        addInstructionsAtControlFlowLabel(
            site.result,
            """
                invoke-static/range { v$register .. v$register }, $META_AI_BUTTON_HOOK
                move-result-object v$answer
                if-eqz v$answer, :built
            """,
            ExternalLabel("built", getInstruction(site.result + 1)),
        )
        return
    }
    addInstructions(
        site.result + 1,
        """
            invoke-static/range { v$register .. v$register }, $META_AI_BUTTON_HOOK
            move-result-object v$register
            check-cast v$register, ${site.type}
        """,
    )
}

/**
 * Right before the search screen's row tests its Meta AI flag to build the button, the flag goes
 * through the extension, which answers 0 while the switch is on and keeps a 0 a 0. The row's earlier
 * test still sees Facebook's own flag, so the search box keeps the end margin it has beside the
 * button instead of running to the screen's edge. No jump lands on the test, so every way there asks.
 */
internal fun MutableMethod.lowerMetaAiFlagWhileHidden(gate: PickerButtonGate) {
    val flag = gate.register
    addInstructions(
        gate.test,
        """
            invoke-static/range { v$flag .. v$flag }, $PICKER_META_AI_HOOK
            move-result v$flag
        """,
    )
}

/**
 * First thing in a promotion banner's show question: ask the extension, and answer no when the
 * switch says the banner goes. Otherwise Facebook's own question runs from its first instruction.
 */
internal fun MutableMethod.answerNoWhileHidingPromotions() {
    requireLocals(CHAT_LIST_PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDES_PROMOTION
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}
