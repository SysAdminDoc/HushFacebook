/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What Hold the profile tab for Activity log does (#605): a long press on the profile tab, or on
 * the Menu tab of an account that has it in the profile tab's place, opens your Activity log
 * instead of Facebook's account switcher.
 *
 * <p>Facebook's tab bar gives each tab's view one long-click listener that keeps the tab's TabTag,
 * and for these two tabs it fetches your accounts and shows the switcher sheet (588
 * {@code LX/02L4;->onLongClick}). The hook first in it hands {@link #held} the view and that TabTag.
 * True takes the press, so the sheet never opens. Each tab is known by the class Facebook keeps
 * for it ({@link FacebookTabs}).
 *
 * <p>Off, paused, before the settings are ready, on any other tab, or when the Activity log won't
 * open, the long press is Facebook's own.
 */
public final class ProfileTabHold {
    /** Facebook's own Activity log route, the page Settings > Activity log opens. */
    static final String ROUTE = "fb://activitylog";

    /** Facebook's link handler, which takes its fb:// routes. */
    static final String URI_HANDLER = "com.facebook.katana.IntentUriHandler";

    /** Counted under the patch's name each time a long press opened the Activity log. */
    static final String OPENED = "Activity log opened from the profile tab";

    private static final String HOOK = "profile tab hold";
    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    private ProfileTabHold() { }

    /**
     * The hook, first in the tab bar's long-click listener, with the pressed tab's [view] and
     * TabTag. True when it opened the Activity log, which takes the press; false leaves it to
     * Facebook. Never throws.
     */
    public static boolean held(@Nullable View view, @Nullable Object tab) {
        try {
            HookStatus.invoked(FAMILY);
            if (view == null || tab == null || !Utils.settingsReady() || !Settings.PROFILE_TAB_ACTIVITY_LOG.get()) {
                return false;
            }
            String name = tab.getClass().getName();
            if (!FacebookTabs.PROFILE_CLASS.equals(name) && !FacebookTabs.MENU_CLASS.equals(name)) return false;
            HookStatus.bound(FAMILY, HOOK);
            if (!open(view.getContext())) return false;
            HookStatus.counted(FAMILY, OPENED);
            Logger.printDebug(() -> "Hold the profile tab: the Activity log opens from " + name);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return false;
        }
    }

    /** Opens the Activity log from [context], in its screen's task when one's under it. False when nothing took it. */
    static boolean open(Context context) {
        Activity screen = activityOf(context);
        Context from = screen != null ? screen : context;
        Intent route = new Intent(Intent.ACTION_VIEW, Uri.parse(ROUTE)).setClassName(from.getPackageName(), URI_HANDLER);
        if (screen == null) route.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            from.startActivity(route);
            return true;
        } catch (ActivityNotFoundException | SecurityException refused) {
            Logger.printInfo(() -> "Hold the profile tab: Facebook's link handler didn't take " + ROUTE);
            return false;
        }
    }

    @Nullable
    private static Activity activityOf(@Nullable Context context) {
        for (int depth = 0; context != null && depth < 8; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
