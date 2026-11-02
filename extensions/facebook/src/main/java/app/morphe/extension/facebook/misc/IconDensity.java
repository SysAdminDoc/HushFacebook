/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.settings.Setting;

/**
 * Soft icons (#365). Facebook's bundle on APKMirror keeps its images in one split per screen
 * density, and the 32-bit download of 585 carries only the xhdpi one. Patched from that, or from
 * any install missing the screen's split, Facebook draws its bitmap icons (Home, the bell, Profile,
 * Search, Messenger) from images made for a coarser screen and Android scales them up, so they
 * look soft while the vector ones (Video, Groups, Feeds) stay sharp. Nothing in the app can bring
 * the missing images back. A phone that can run the 64-bit download, which carries every density,
 * is told once per install to patch that one, and the settings say so under the status card for
 * as long as it holds. A 32-bit phone has no sharper download to take, so it only gets a log line.
 */
public final class IconDensity {
    /** A bitmap in every density split, so the density it resolves to is the best one installed. */
    static final String PROBE = "fb_launch_logo";

    /** The density the probe resolves to, or null to ask the resources. A test sets it. */
    @Nullable
    static volatile Integer imagesForTests;

    /** When the APK running now was installed, or null to ask the package manager. A test sets it. */
    @Nullable
    static volatile Long installedAtForTests;

    /**
     * The install the note was shown for, by its APK's install time, so a repatch that still lacks
     * the images says so again. Hushfacebook's own state, kept while paused and never in a settings
     * file. A class of its own, so nothing here loads a setting before the context is set.
     */
    static final class Stored {
        static final LongSetting NOTED_FOR = new LongSetting("hushfacebook_icon_density_noted", 0L, false, false);

        static {
            Setting.keepWhenPaused(NOTED_FOR);
        }

        private Stored() {
        }
    }

    private IconDensity() {
    }

    /**
     * Whether images made for [imageDpi] look soft on a [screenDpi] screen: drawn a quarter larger
     * or more. The 32-bit download's xhdpi images on a 480 dpi screen are half again as large, and
     * xxhdpi ones on a 560 dpi screen only a sixth, which doesn't show. No image (0) or one made for
     * no density at all says no.
     */
    static boolean soft(int imageDpi, int screenDpi) {
        if (imageDpi <= 0 || imageDpi >= TypedValue.DENSITY_NONE) return false;
        return screenDpi * 4L >= imageDpi * 5L;
    }

    /** Whether the phone runs 64-bit code, so the 64-bit download, with every density, fits it. */
    static boolean runs64Bit(@Nullable String[] abis64) {
        return abis64 != null && abis64.length > 0;
    }

    /**
     * Whether this install's images look soft on this screen, on a phone the 64-bit download fits.
     * The settings show their line while it's true. Never throws.
     */
    public static boolean patchSharper(Context context) {
        try {
            return runs64Bit(Build.SUPPORTED_64_BIT_ABIS) && soft(imageDensity(context), screenDensity(context));
        } catch (Throwable failure) {
            Logger.printException(() -> "Icon density: could not read this install's images", failure);
            return false;
        }
    }

    /**
     * Called once per Facebook start, from SettingsEntry.onApplicationCreate after the context is
     * set. The note waits for the first screen to come to the front, so a start for a notification
     * in the background never shows a toast over another app.
     */
    public static void onFacebookStart(Context context) {
        try {
            if (!Utils.settingsReady() || !Utils.isMainProcess()) return;
            int images = imageDensity(context);
            int screen = screenDensity(context);
            if (!soft(images, screen)) return;
            boolean sharper = runs64Bit(Build.SUPPORTED_64_BIT_ABIS);
            Log.i("morphe", "IconDensity: images for " + images + " dpi on a " + screen + " dpi screen"
                    + (sharper ? "" : ", and this 32-bit phone has no sharper download"));
            if (!sharper || !(context instanceof Application)) return;
            long installed = installedAt(context);
            if (Stored.NOTED_FOR.savedValue() == installed) return;
            ((Application) context).registerActivityLifecycleCallbacks(new NoteOnFirstScreen(installed));
        } catch (Throwable failure) {
            Logger.printException(() -> "Icon density: could not check this install's images", failure);
        }
    }

    /**
     * The note, once for [installed]. A paused Hushfacebook says nothing, and the note waits for
     * a start that isn't paused. Package-visible for tests.
     */
    static boolean note(long installed) {
        if (HushfacebookPause.isPaused() || Stored.NOTED_FOR.savedValue() == installed) return false;
        Stored.NOTED_FOR.save(installed);
        Utils.showToastLong(L10n.t("Icons look soft on this screen. Repatch with the 120-640dpi bundle."));
        return true;
    }

    /** The density the probe resolves to here, the best one this install has images for, or 0. */
    static int imageDensity(Context context) {
        Integer forced = imagesForTests;
        if (forced != null) return forced;
        int id = ResourceUtils.getIdentifier(context, ResourceType.DRAWABLE, PROBE);
        if (id == 0) return 0;
        TypedValue value = new TypedValue();
        context.getResources().getValue(id, value, true);
        // An image with no density in its folder name is drawn as mdpi.
        return value.density == TypedValue.DENSITY_DEFAULT ? DisplayMetrics.DENSITY_DEFAULT : value.density;
    }

    private static int screenDensity(Context context) {
        return context.getResources().getDisplayMetrics().densityDpi;
    }

    private static long installedAt(Context context) {
        Long forced = installedAtForTests;
        if (forced != null) return forced;
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            return 0;
        }
    }

    /** Shows the note when the first screen comes to the front, then stops watching. */
    static final class NoteOnFirstScreen implements Application.ActivityLifecycleCallbacks {
        private final long installed;

        NoteOnFirstScreen(long installed) {
            this.installed = installed;
        }

        @Override
        public void onActivityResumed(Activity activity) {
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            try {
                note(installed);
            } catch (Throwable failure) {
                Logger.printException(() -> "Icon density: could not show the note", failure);
            }
        }

        @Override public void onActivityCreated(Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }
}
