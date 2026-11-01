/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.ShimmerTitle;
import app.morphe.extension.facebook.theme.DarkMode;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Select part of a post's text (issue #294). Facebook's long press on a feed post's text copies all
 * of it, with no way to pick a part. With the switch on, that copy also opens the text in a box
 * whose text can be selected, so a word or a line can be copied on its own.
 *
 * <p>The text comes from the clip Facebook makes, so it's the whole text even when the post shows
 * See more, and a reel's caption copies the same way. {@link PostLongPress} times every press: one
 * that reaches the long-press time arms this, and the next clip made within {@link #WINDOW} of that
 * opens the box. Which view the hold was on isn't asked, since post text isn't always drawn by a
 * view that says it holds text. A long press that copies nothing (a photo, a comment's menu) opens
 * nothing, and the armed hold runs out; a copy picked from a menu comes later than the window. The
 * clipboard is listened to from the first press with the switch on, since Facebook's copy can land
 * in the same moment as the hold's timer.
 */
public final class PostTextSelect {
    private static final String FAMILY = FamilyNames.REEL_GESTURES;

    /** Counted for each hold that armed the box. */
    static final String ARMED = "press held for a copy to select";
    /** Counted for each box opened on the text Facebook copied. */
    static final String OPENED = "post text opened to select";

    /** How long after a hold on text, in milliseconds, Facebook's copy still opens the box. */
    static final long WINDOW = 1500L;

    /** When, in uptime, a hold on text armed the box; 0 when nothing is armed. */
    private static volatile long armedAt;
    private static WeakReference<Activity> screen = new WeakReference<>(null);
    @Nullable private static ClipboardManager listening;
    private static final ClipboardManager.OnPrimaryClipChangedListener CHANGED = PostTextSelect::changed;
    private static WeakReference<AlertDialog> showing = new WeakReference<>(null);

    private PostTextSelect() {
    }

    /** Whether the switch is on. The readiness check comes first. */
    static boolean on() {
        return Utils.settingsReady() && Settings.SELECT_POST_TEXT.get();
    }

    /** A press went down on [context]'s screen with the switch on: listens to the clipboard from now on. */
    static void listen(Context context) {
        if (listening != null) return;
        ClipboardManager clipboard = context.getApplicationContext().getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.addPrimaryClipChangedListener(CHANGED);
        listening = clipboard;
    }

    /**
     * A press went down on [activity]. Kept from the hook's own argument, since the window's top view
     * has a context of its own that only wraps the app's.
     */
    static void pressedOn(Activity activity) {
        if (screen.get() != activity) screen = new WeakReference<>(activity);
    }

    /** A hold reached the long-press time on the screen last pressed. */
    static void armed() {
        if (screen.get() == null) return;
        armedAt = SystemClock.uptimeMillis();
        HookStatus.counted(FAMILY, ARMED);
    }

    /** The clipboard changed: Facebook's copy of the held text opens the box. Never throws. */
    static void changed() {
        try {
            long at = armedAt;
            if (at == 0L) return;
            armedAt = 0L;
            if (SystemClock.uptimeMillis() - at > WINDOW || !on()) return;
            Activity activity = screen.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || listening == null) return;
            CharSequence text = textOf(listening.getPrimaryClip());
            if (text == null || text.toString().trim().isEmpty()) return;
            show(activity, text.toString());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "post text select", failure);
        }
    }

    /** The text of [clip]'s first item, or null. */
    @Nullable
    static CharSequence textOf(@Nullable ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) return null;
        return clip.getItemAt(0).getText();
    }

    /** Opens [text] in a box where it can be selected. */
    static void show(Activity activity, String text) {
        AlertDialog open = showing.get();
        if (open != null && open.isShowing()) open.dismiss();
        // Not Facebook's dialog theme: it sets android:background, which every view built from it takes,
        // the selection handles and their toolbar included, so they'd cover the text with solid boxes (#294).
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, dark(activity)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(L10n.t(activity, "Select text"))
                .setPositiveButton(L10n.t(activity, "Done"), null);
        Context themed = builder.getContext();
        TextView body = new TextView(themed);
        body.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        body.setText(text);
        body.setTextIsSelectable(true);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                themed.getResources().getDisplayMetrics()));
        body.setPadding(pad, pad / 2, pad, 0);
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(body);
        AlertDialog dialog = builder.setView(scroll).create();
        showing = new WeakReference<>(dialog);
        dialog.show();
        ShimmerTitle.wearTitleOf(dialog);
        HookStatus.counted(FAMILY, OPENED);
        Logger.printDebug(() -> "Post text select: " + OPENED + ", " + text.length() + " characters");
    }

    /** Whether Facebook's dark mode is on, or the phone's night mode before Facebook has said. */
    public static boolean dark(Context context) {
        if (DarkMode.hasAnswered()) return DarkMode.on();
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The box on the screen, or null. For tests. */
    @Nullable
    static AlertDialog shownDialog() {
        AlertDialog dialog = showing.get();
        return dialog != null && dialog.isShowing() ? dialog : null;
    }

    /** For tests: forgets the armed hold, the clipboard listened to and the box shown. */
    static void reset() {
        armedAt = 0L;
        screen = new WeakReference<>(null);
        if (listening != null) listening.removePrimaryClipChangedListener(CHANGED);
        listening = null;
        AlertDialog open = showing.get();
        if (open != null && open.isShowing()) open.dismiss();
        showing = new WeakReference<>(null);
    }
}
