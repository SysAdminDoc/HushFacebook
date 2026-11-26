/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/** What the tab bar logs as it handles a long press on a tab, whichever tab it is. */
internal const val TAB_HIDING_LONG_PRESS = "tab_hiding_longpress"

internal const val LONG_CLICK_LISTENER = "Landroid/view/View\$OnLongClickListener;"
internal const val PROFILE_TAB_HOLD = "Lapp/morphe/extension/facebook/navigation/ProfileTabHold;"
internal const val OWN_TAB_HELD = "$PROFILE_TAB_HOLD->held(Landroid/view/View;Ljava/lang/Object;)Z"

/** The tab bar's long-click listener: its onLongClick and the field keeping the pressed tab. */
internal class TabHoldSite(val method: Method, val tab: FieldReference)

/**
 * [classDef] as the long-click listener Facebook's tab bar gives each tab's view (588 `LX/02L4;`):
 * a View.OnLongClickListener with one TabTag field, the tab it was made for, whose onLongClick logs
 * [TAB_HIDING_LONG_PRESS]. For the profile tab, and the Menu tab where an account has it in the
 * profile tab's place, that press fetches your accounts and opens the account switcher. Null for any
 * other class.
 */
internal fun tabHoldSite(classDef: ClassDef): TabHoldSite? {
    if (LONG_CLICK_LISTENER !in classDef.interfaces) return null
    val tab = classDef.instanceFields.filter { it.type == TAB_TAG }.singleOrNull() ?: return null
    val press = classDef.methods.singleOrNull { method ->
        method.name == "onLongClick" && method.returnType == "Z" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;") &&
            !AccessFlags.STATIC.isSet(method.accessFlags)
    } ?: return null
    if (!holdsString(press, TAB_HIDING_LONG_PRESS)) return null
    return TabHoldSite(press, tab)
}

/**
 * The tab bar's long-click listener. Throws when this build doesn't have exactly one, or its
 * onLongClick has fewer than the two locals the hook borrows.
 */
internal fun BytecodePatchContext.profileTabHoldSite(): TabHoldSite {
    val sites = classDefByStrings(TAB_HIDING_LONG_PRESS, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .mapNotNull(::tabHoldSite)
    val site = sites.singleOrNull()
        ?: throw PatchException("$PATCH: expected one tab long-click listener keeping a TabTag, found ${sites.size}")
    val locals = site.method.localRegisterCount()
    if (locals < 2) {
        throw PatchException("$PATCH: ${site.method.definingClass}->onLongClick has $locals locals, the hold needs 2")
    }
    return site
}

/**
 * Hands the pressed tab's view and TabTag to the extension first thing in onLongClick. Its yes
 * answers the press as taken, before Facebook fetches anything; its no runs Facebook's own press.
 * Borrows v0 and v1, which Facebook writes before it reads them, and reads `this` and the view from
 * their parameter registers, which nothing has written yet.
 */
internal fun BytecodePatchContext.hookProfileTabHold(site: TabHoldSite) {
    val method = mutableClassDefBy(site.method.definingClass).findMutableMethodOf(site.method)
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${site.tab}
            move-object/from16 v1, p1
            invoke-static { v1, v0 }, $OWN_TAB_HELD
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x1
            return v0
        """,
        ExternalLabel("facebook", method.getInstruction(0)),
    )
}
