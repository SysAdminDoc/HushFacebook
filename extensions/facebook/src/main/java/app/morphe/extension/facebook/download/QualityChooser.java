/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.reels.PostTextSelect;
import app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.ShimmerTitle;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Choose the quality on each save (#448): with the switch on, a tap on a reel's Download button or
 * a feed or Watch video's Download to phone opens the list of qualities first, the one Download
 * quality is set to checked, and the save takes the one picked, for that save alone. Download
 * quality keeps its value. Canceled, nothing is saved.
 *
 * <p>Off, paused, before the settings are ready, with no screen to show the list on, or anything
 * here failing, the save starts at once at Download quality, as every save did before the switch.
 */
public final class QualityChooser {
    /** A save the list starts once a quality is picked. Answers whether it started. */
    public interface Save {
        boolean start();
    }

    private QualityChooser() {
    }

    /** Whether the switch is on, not paused, and the settings ready. */
    static boolean on() {
        return Utils.settingsReady() && Settings.DOWNLOAD_QUALITY_ASK.get();
    }

    /**
     * Shows the list of qualities over the screen [context] belongs to, and starts [save] at the one
     * picked. Answers whether the list is showing: false means the caller starts the save itself,
     * at Download quality. Never throws: it runs inside Facebook's click dispatch.
     */
    public static boolean ask(@Nullable Context context, Save save) {
        try {
            if (!on()) return false;
            Activity activity = activityOf(context);
            if (activity == null) activity = Utils.getActivity();
            if (activity == null || activity.isFinishing()) {
                Logger.printDebug(() -> "Quality chooser: no screen to show the qualities on, saving at Download quality");
                return false;
            }
            DownloadQuality[] qualities = DownloadQuality.values();
            String[] labels = new String[qualities.length];
            for (int i = 0; i < qualities.length; i++) labels[i] = HushfacebookPreferenceFragment.qualityLabel(qualities[i]);
            AlertDialog dialog = new AlertDialog.Builder(activity, PostTextSelect.dark(activity)
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle(L10n.t(activity, "Download quality"))
                    .setSingleChoiceItems(labels, MediaDownload.quality().ordinal(), (box, which) -> {
                        box.dismiss();
                        picked(qualities[which], save);
                    })
                    .setNegativeButton(L10n.t(activity, "Cancel"), null)
                    .create();
            dialog.show();
            ShimmerTitle.wearTitleOf(dialog);
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "Quality chooser: the list could not open, saving at Download quality", failure);
            return false;
        }
    }

    /** [quality] picked: [save] starts at it. Never throws. */
    static void picked(DownloadQuality quality, Save save) {
        try {
            Logger.printDebug(() -> "Quality chooser: saving at " + quality.name());
            MediaDownload.withQuality(quality, save);
        } catch (Throwable failure) {
            Logger.printException(() -> "Quality chooser: the save at the picked quality failed", failure);
        }
    }

    /** The activity a view's context wraps, or null. The depth guards against a wrapper that wraps itself. */
    @Nullable
    private static Activity activityOf(@Nullable Context context) {
        for (int depth = 0; context != null && depth < 20; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
