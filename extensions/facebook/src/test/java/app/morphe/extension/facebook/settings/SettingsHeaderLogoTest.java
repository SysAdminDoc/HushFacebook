/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The header's icon is the logo the settings patch writes into res/drawable-nodpi, looked up by the
 * name the patch gives it. A build without that drawable still gets an icon.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsHeaderLogoTest {
    /** An id in the app's own range, the way aapt numbers a drawable a patch adds. */
    private static final int LOGO_ID = 0x7f08f00d;

    @Test public void theHeaderShowsTheLogoThePatchBundles() {
        Drawable logo = new BitmapDrawable(RuntimeEnvironment.getApplication().getResources(),
                Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888));
        Drawable shown = SettingsHeader.icon(withLogo(logo), ScreenColors.DEFAULT);
        assertSame("the bundled logo", logo, shown);
    }

    @Test public void aBuildWithoutTheLogoStillHasAnIcon() {
        assertNotNull("no icon at all", SettingsHeader.icon(RuntimeEnvironment.getApplication(), ScreenColors.DEFAULT));
    }

    /** The test app with the drawable the settings patch adds, under the name SettingsLogo.kt writes. */
    private static Context withLogo(Drawable logo) {
        Context base = RuntimeEnvironment.getApplication();
        Resources real = base.getResources();
        Resources patched = new Resources(real.getAssets(), real.getDisplayMetrics(), real.getConfiguration()) {
            @Override
            public int getIdentifier(String name, String defType, String defPackage) {
                if ("hushfacebook_settings_logo".equals(name) && "drawable".equals(defType)
                        && base.getPackageName().equals(defPackage)) {
                    return LOGO_ID;
                }
                return super.getIdentifier(name, defType, defPackage);
            }

            @Override
            public Drawable getDrawable(int id, Theme theme) {
                return id == LOGO_ID ? logo : super.getDrawable(id, theme);
            }
        };
        return new ContextWrapper(base) {
            @Override
            public Resources getResources() {
                return patched;
            }
        };
    }
}
