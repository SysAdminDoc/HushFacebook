/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

internal const val CHAT_BACKGROUND_PATCH = "Chat background"

internal const val CHAT_BACKGROUND_CLASS = "$EXTENSION_PACKAGE/chats/ChatBackground;"

/** The extension's hook: the chat screen's fragment and its root view. */
internal const val CHAT_BACKGROUND_HOOK = "$CHAT_BACKGROUND_CLASS->threadView(Ljava/lang/Object;Landroid/view/View;)V"

/** The name Redex keeps on the chat screen's fragment (588: `LX/0H6u;`). */
internal const val THREAD_VIEW_FRAGMENT = "MsysThreadViewFragment"

/** A performance mark the fragment's onCreateView logs, held by no other class (588), so only it is read. */
internal const val THREAD_VIEW_CREATED_MARK = "threadview_oncreateview_end"

/** The thread key type the extension reads the chat's key from. */
internal const val THREAD_KEY = "Lcom/facebook/messaging/model/threadkey/ThreadKey;"

/** Whether [classDef] is the chat screen's fragment: the class Redex names, with a thread key of its own. */
internal fun isThreadViewFragment(classDef: ClassDef): Boolean =
    redexOriginalName(classDef) == THREAD_VIEW_FRAGMENT &&
        classDef.instanceFields.any { it.type == THREAD_KEY }

/** The fragment's own onViewCreated(View, Bundle), which gets the chat screen's root view first. */
internal fun threadViewCreated(fragment: ClassDef): List<Method> = fragment.methods.filter {
    it.name == "onViewCreated" && it.returnType == "V" &&
        it.parameterTypes.map { type -> type.toString() } == listOf("Landroid/view/View;", "Landroid/os/Bundle;") &&
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
}

/**
 * Chat background (#573): a color of your own behind the messages of chats inside Facebook, drawn on
 * this phone only. MsysThreadViewFragment.onViewCreated hands the extension's ChatBackground the
 * fragment and its root view first, and the extension paints the message list from then on. Nothing
 * is sent, so the chat's shared theme and the people in it are left alone.
 *
 * Selected by default: nothing changes until a color is picked.
 */
@Suppress("unused")
val chatBackgroundPatch = patch(
    // The README table check reads this literal; CHAT_BACKGROUND_PATCH carries the same text for the messages.
    name = "Chat background",
    description = "Gives chats that open inside Facebook a background color of your own, one for every chat or one " +
        "for a single chat, or a fade between two. Only your phone shows it. Nobody in the chat is told, and the " +
        "chat's theme stays as it is for everyone else. Nothing changes until you pick a color in Hushfacebook " +
        "settings > Chats and Messenger.",
    default = true,
) {
    category("Chats")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        chatBackground()
        enableStatus("chatBackground")
    }
}

/** Puts the hook first in the chat screen fragment's onViewCreated. */
internal fun BytecodePatchContext.chatBackground() {
    val fragments = mutableListOf<ClassDef>()
    classDefForEachReferencing(strings = listOf(THREAD_VIEW_CREATED_MARK)) { classDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES) && isThreadViewFragment(classDef)) fragments += classDef
    }
    val fragment = fragments.singleOrNull() ?: throw PatchException(
        "$CHAT_BACKGROUND_PATCH: expected one $THREAD_VIEW_FRAGMENT holding \"$THREAD_VIEW_CREATED_MARK\", found ${fragments.size}",
    )
    val created = threadViewCreated(fragment).singleOrNull() ?: throw PatchException(
        "$CHAT_BACKGROUND_PATCH: expected ${fragment.type} to declare one onViewCreated(View, Bundle)",
    )
    // The fragment and its view are p0 and p1, next to each other, so the range form passes both.
    mutableClassDefBy(fragment.type).findMutableMethodOf(created)
        .addInstruction(0, "invoke-static/range { p0 .. p1 }, $CHAT_BACKGROUND_HOOK")
}
