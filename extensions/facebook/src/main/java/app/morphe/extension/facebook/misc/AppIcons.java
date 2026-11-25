/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import app.morphe.extension.shared.Logger;

/**
 * Facebook's launcher entry and its app icons. Facebook ships every alternate launcher icon as an
 * activity-alias of {@link #LAUNCHER}, and its own App icon page switches between them on the phone.
 */
public final class AppIcons {
    /** The launcher entry. Every app icon is an activity-alias of it named {@code LAUNCHER + ".<icon>"}. */
    public static final String LAUNCHER = "com.facebook.katana.LoginActivity";

    private AppIcons() {
    }

    /**
     * The launcher entry that's on now, for the intents that open Facebook on the patches' behalf:
     * the settings shortcut, a notification's buttons and a saved file's. Picking an icon turns
     * {@link #LAUNCHER} off and the icon's alias on, and Android starts nothing for an intent that
     * names a component that's off, so after an icon change those taps did nothing (#119). Every
     * alias points at the same screen as {@link #LAUNCHER}. {@link #LAUNCHER} itself when Android
     * lists none of them, or on any failure. Never throws.
     */
    public static ComponentName launcher(Context context) {
        String packageName = context.getPackageName();
        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName);
            // Components that are off aren't listed without MATCH_DISABLED_COMPONENTS.
            for (ResolveInfo entry : context.getPackageManager().queryIntentActivities(main, 0)) {
                String name = entry.activityInfo == null ? null : entry.activityInfo.name;
                if (name != null && (name.equals(LAUNCHER) || name.startsWith(LAUNCHER + "."))) {
                    return new ComponentName(packageName, name);
                }
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "App icons: could not find the launcher entry that's on", failure);
        }
        return new ComponentName(packageName, LAUNCHER);
    }
}
