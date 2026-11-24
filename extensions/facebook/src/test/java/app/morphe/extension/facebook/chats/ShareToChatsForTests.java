/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.robolectric.RuntimeEnvironment;

/** Share to Facebook chats as the settings package's tests ask it. */
public final class ShareToChatsForTests {
    private ShareToChatsForTests() {
    }

    /** Whether the share screen goes in the share menu now, read back from the package manager. */
    public static boolean putsItInTheShareMenu() {
        Context app = RuntimeEnvironment.getApplication();
        try {
            ShareToChats.changed(app);
            ComponentName screen = new ComponentName(app.getPackageName(), ShareToChats.class.getName());
            return ShareToChats.wanted()
                    && app.getPackageManager().getComponentEnabledSetting(screen) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } finally {
            ShareToChats.forgetForTests();
        }
    }
}
