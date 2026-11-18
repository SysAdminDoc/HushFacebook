/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.reels.PostTextSelect;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Ask before downloading (#511): with the switch on, a tap on a reel's Download button, the
 * Download button under a post or a video's Download to phone asks first, and only Continue starts
 * the save, so a stray tap saves nothing.
 *
 * <p>A video's save with Choose quality on each save on skips the question, since that list has its
 * own Cancel and asking twice is one tap too many. Photos have no such list, so they're always
 * asked about.
 *
 * <p>Off, paused, before the settings are ready, with no screen to ask on, or anything here failing,
 * the caller saves at once, as every save did before the switch.
 */
public final class AskBeforeDownload {
    private static final String SOURCE = "AskBeforeDownload";

    /** The question on screen, so a second tap that gets through doesn't stack another. */
    @Nullable
    private static WeakReference<AlertDialog> open;

    private AskBeforeDownload() {
    }

    /** Whether the switch is on, not paused, and the settings ready. */
    static boolean on() {
        return Utils.settingsReady() && Settings.ASK_BEFORE_DOWNLOAD.get();
    }

    /** {@link #ask} for a video's save, which leaves the asking to the list of qualities when that's on. */
    static boolean askForVideo(@Nullable Context context, Runnable download) {
        return !QualityChooser.on() && ask(context, download);
    }

    /**
     * Asks over the screen [context] belongs to, and runs [download] on Continue. Answers whether
     * the tap is taken care of: false means the caller saves now. Never throws: it runs inside
     * Facebook's click dispatch.
     */
    static boolean ask(@Nullable Context context, Runnable download) {
        try {
            if (!on()) return false;
            AlertDialog showing = open == null ? null : open.get();
            if (showing != null && showing.isShowing()) return true;
            Activity activity = QualityChooser.activityOf(context);
            if (activity == null) activity = Utils.getActivity();
            if (activity == null || activity.isFinishing()) {
                Logger.printDebug(() -> "Ask before downloading: no screen to ask on, saving now");
                return false;
            }
            AlertDialog dialog = new AlertDialog.Builder(activity, PostTextSelect.dark(activity)
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setMessage(L10n.t(activity, "Start this download?"))
                    .setPositiveButton(L10n.t(activity, "Continue"), (box, which) -> start(download))
                    .setNegativeButton(L10n.t(activity, "Cancel"), null)
                    .create();
            dialog.show();
            open = new WeakReference<>(dialog);
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "Ask before downloading: the question could not open, saving now", failure);
            return false;
        }
    }

    /** Continue: the save starts. Never throws, it runs in the dialog's click. */
    private static void start(Runnable download) {
        try {
            download.run();
        } catch (Throwable failure) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "the download after Continue failed", failure);
        }
    }
}
