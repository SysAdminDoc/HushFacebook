/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Looper;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * Soft icons (#365): Facebook patched from the 32-bit download has only xhdpi images, and on a
 * 480 dpi screen its bitmap icons are drawn half again as large. The emulator on 2026-11-02 showed
 * it at 480 dpi with that download's splits, and the full 64-bit download was sharp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "xxhdpi")
public class IconDensityTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final String[] abis64 = Build.SUPPORTED_64_BIT_ABIS;

    @After
    public void restore() {
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_64_BIT_ABIS", abis64);
        IconDensity.imagesForTests = null;
        IconDensity.installedAtForTests = null;
        IconDensity.Stored.NOTED_FOR.resetToDefault();
    }

    @Test
    public void imagesAQuarterOrMoreCoarserThanTheScreenAreSoft() {
        assertTrue("the 32-bit download on a 480 dpi phone", IconDensity.soft(320, 480));
        assertTrue("and on a 420 dpi one", IconDensity.soft(320, 420));
        assertTrue(IconDensity.soft(320, 400));
        assertFalse("its own density", IconDensity.soft(320, 320));
        assertFalse("a sixth larger doesn't show", IconDensity.soft(480, 560));
        assertFalse("finer than the screen", IconDensity.soft(640, 480));
        assertFalse("no probe image", IconDensity.soft(0, 480));
        assertFalse("an image made for no density", IconDensity.soft(0xffff, 480));
    }

    @Test
    public void onlyA64BitPhoneHasASharperDownload() {
        assertTrue(IconDensity.runs64Bit(new String[] {"arm64-v8a"}));
        assertFalse(IconDensity.runs64Bit(new String[0]));
        assertFalse(IconDensity.runs64Bit(null));
    }

    @Test
    public void theSettingsLineShowsOnlyForCoarseImagesOnA64BitPhone() {
        Context app = RuntimeEnvironment.getApplication();
        assertEquals(480, app.getResources().getDisplayMetrics().densityDpi);
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_64_BIT_ABIS", new String[] {"arm64-v8a"});
        IconDensity.imagesForTests = 320;
        assertTrue(IconDensity.patchSharper(app));
        IconDensity.imagesForTests = 480;
        assertFalse("the full download", IconDensity.patchSharper(app));
        IconDensity.imagesForTests = null;
        assertFalse("an app without the probe image", IconDensity.patchSharper(app));

        IconDensity.imagesForTests = 320;
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_64_BIT_ABIS", new String[0]);
        assertFalse("a 32-bit phone has nothing sharper to take", IconDensity.patchSharper(app));
    }

    @Test
    public void theNoteShowsOncePerInstall() {
        assertTrue(IconDensity.note(1_000L));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Icons look soft on this screen. Repatch with the 120-640dpi bundle.",
                ShadowToast.getTextOfLatestToast());
        assertEquals(1_000L, (long) IconDensity.Stored.NOTED_FOR.savedValue());

        ShadowToast.reset();
        assertFalse("a later start of the same install", IconDensity.note(1_000L));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowToast.getTextOfLatestToast());

        assertTrue("a repatch that still lacks the images", IconDensity.note(2_000L));
    }

    @Test
    public void aSoftStartNotesItWhenTheFirstScreenShows() {
        Context app = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_64_BIT_ABIS", new String[] {"arm64-v8a"});
        IconDensity.installedAtForTests = 1_000L;
        IconDensity.imagesForTests = 480;
        IconDensity.onFacebookStart(app);
        Robolectric.buildActivity(Activity.class).setup();
        assertEquals("sharp images say nothing", 0L, (long) IconDensity.Stored.NOTED_FOR.savedValue());

        IconDensity.imagesForTests = 320;
        IconDensity.onFacebookStart(app);
        assertEquals("nothing before a screen shows", 0L, (long) IconDensity.Stored.NOTED_FOR.savedValue());
        Robolectric.buildActivity(Activity.class).setup();
        assertEquals(1_000L, (long) IconDensity.Stored.NOTED_FOR.savedValue());
    }
}
