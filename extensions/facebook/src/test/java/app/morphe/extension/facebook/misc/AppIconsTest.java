/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

/** Which of Facebook's launcher entries is on, for the intents the patches send. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AppIconsTest {
    private static ComponentName component(String className) {
        return new ComponentName(RuntimeEnvironment.getApplication().getPackageName(), className);
    }

    /**
     * Picking an icon turns the launcher entry off and the icon's alias on. The patches' own intents
     * named the entry and started nothing after that (#119), so they ask for the one that's on. A
     * launcher activity that only starts with the entry's name isn't one of its aliases.
     */
    @Test
    public void theLauncherEntryThatsOnIsThePickedIcons() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ComponentName entry = component(AppIcons.LAUNCHER);
        assertEquals("Facebook's launcher entry", "com.facebook.katana.LoginActivity", AppIcons.LAUNCHER);
        assertEquals("none listed: the entry Facebook ships", entry, AppIcons.launcher(context));

        ComponentName icon = component(AppIcons.LAUNCHER + ".vaporwave_ic");
        ComponentName lookalike = component(AppIcons.LAUNCHER + "Alias");
        ShadowPackageManager packages = shadowOf(context.getPackageManager());
        IntentFilter main = new IntentFilter(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ComponentName launcher : new ComponentName[] {lookalike, entry, icon}) {
            packages.addActivityIfNotPresent(launcher);
            packages.addIntentFilterForActivity(launcher, main);
        }
        PackageManager manager = context.getPackageManager();
        manager.setComponentEnabledSetting(icon, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        assertEquals("the default icon", entry, AppIcons.launcher(context));

        manager.setComponentEnabledSetting(icon, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        manager.setComponentEnabledSetting(entry, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        assertEquals("a picked icon", icon, AppIcons.launcher(context));
    }
}
