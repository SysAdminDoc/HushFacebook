/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.theme.AccentColor.Preset;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Issue #272: Facebook builds Home's feed as it starts, on the application's resources, before its
 * first activity, and the plus on Create story in its stories tray kept the wallpaper's accent under
 * a picked one. The accent's tables reach the application's resources as Facebook starts, not only
 * each activity's as it's created.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AccentResourcesStartTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final AccentResources.LoaderMaker MAKER = AccentResources.maker;
    private final ResourcesLoader made = new ResourcesLoader();

    @After
    public void restore() {
        AccentResources.onAssets(application()).removeLoaders(made);
        AccentResources.forgetLoadersForTests();
        AccentResources.maker = MAKER;
        AccentResources.useTable(null);
        Settings.ACCENT_COLOR.resetToDefault();
    }

    @Test
    public void theApplicationsResourcesTakeTheAccentAsFacebookStarts() throws Exception {
        AccentResources.useTable("7f0601d5=ff0866ff:ACCENT,PRIMARY_BUTTON_BACKGROUND");
        AccentResources.maker = (name, table) -> made;

        AccentResources.load();
        assertFalse("Facebook blue adds nothing", loaders(application()).contains(made));

        Settings.ACCENT_COLOR.save(Preset.TEAL);
        AccentResources.load();
        assertTrue("the application's assets take the accent before any activity", loaders(application()).contains(made));
    }

    private static Resources application() {
        return RuntimeEnvironment.getApplication().getResources();
    }

    /** The loaders on {@code resources}' assets: hidden in the SDK, public in the framework Robolectric runs. */
    @SuppressWarnings("unchecked")
    private static List<ResourcesLoader> loaders(Resources resources) throws ReflectiveOperationException {
        return (List<ResourcesLoader>) Resources.class.getMethod("getLoaders").invoke(resources);
    }
}
