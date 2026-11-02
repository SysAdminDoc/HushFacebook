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
import android.os.Build;
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
 * of it, with no way to pick a part. With the switch on, that hold opens the text in a box whose
 * text can be selected, so a word or a line can be copied on its own, and the box's Copy all
 * button copies the whole of it.
 *
 * <p>The text comes from the clip Facebook makes, so it's the whole text even when the post shows
 * See more, and a reel's caption copies the same way. {@link PostLongPress} times every press: one
 * that reaches the long-press time arms this, and the next clip made within {@link #WINDOW} of that
 * opens the box. Which view the hold was on isn't asked, since post text isn't always drawn by a
 * view that says it holds text. A long press that copies nothing (a photo, a comment's menu) opens
 * nothing, and the armed hold runs out; a copy picked from a menu comes later than the window.
 *
 * <p>The patch sends each of Facebook's clipboard writes to {@link #copy}, which opens the box in
 * place of the copy a hold makes, so the clipboard keeps what it held (#342). A copy that reaches
 * the clipboard some other way still opens the box once it lands: the clipboard is listened to from
 * the first press with the switch on, since that copy can land in the same moment as the hold's
 * timer. Facebook follows its copy with a "Text copied to clipboard" popup of its own drawing (not an
 * Android toast), so the patch asks {@link #dropsPopup} in front of it, which drops the one that
 * comes right after a copy the box took.
 */
public final class PostTextSelect {
    private static final String FAMILY = FamilyNames.REEL_GESTURES;

    /** Counted for each hold that armed the box. */
    static final String ARMED = "press held for a copy to select";
    /** Counted for each box opened on the text Facebook copied. */
    static final String OPENED = "post text opened to select";
    /** Counted for each copy a hold made that opened the box instead of reaching the clipboard. */
    static final String KEPT_OFF = "hold's copy kept off the clipboard";
    /** Counted for each tap of the box's Copy all. */
    static final String COPIED_ALL = "post text copied whole";
    /** Counted for each of Facebook's "copied" popups dropped because the copy it announced was kept off. */
    static final String POPUP_DROPPED = "kept-off copy's popup dropped";

    /** How long after a hold on text, in milliseconds, Facebook's copy still opens the box. */
    static final long WINDOW = 1500L;
    /** How long after a copy kept off the clipboard, in milliseconds, Facebook's popup for it is dropped. */
    static final long POPUP_WINDOW = 1000L;

    /** The extension's own classes, which a copy passes through on its way here and the logger adds to. */
    private static final String EXTENSION = "app.morphe.extension.";
    /** How many of Facebook's frames name a copy's maker. */
    private static final int COPIER_FRAMES = 8;

    /** When, in uptime, a hold on text armed the box; 0 when nothing is armed. */
    private static volatile long armedAt;
    /** When, in uptime, a hold's copy was last kept off the clipboard; 0 once its popup is dealt with. */
    private static volatile long keptAt;
    /** When, in uptime, the box last opened; 0 before it ever has. */
    private static volatile long openedAt;
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
        // Facebook's copy can open the box a moment before this hold's timer runs out, and the
        // hold mustn't arm the box again for a copy made from inside it.
        if (openedAt != 0L && SystemClock.uptimeMillis() - openedAt < WINDOW) return;
        armedAt = SystemClock.uptimeMillis();
        HookStatus.counted(FAMILY, ARMED);
    }

    /**
     * A hold on a post's text lifted on its Translate line and goes to the post's long press now
     * (#332). Facebook's copy comes after the lift, so a hold that armed the box is timed from here.
     */
    public static void heldThroughLink() {
        if (armedAt != 0L) armedAt = SystemClock.uptimeMillis();
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

    /**
     * Injection point, in place of each of Facebook's ClipboardManager.setPrimaryClip calls, with
     * the same two arguments. A copy a hold on a post's text made opens the box instead, and the
     * clipboard keeps what it held. Every other copy goes to the clipboard as Facebook made it.
     */
    public static void copy(ClipboardManager clipboard, ClipData clip) {
        if (opensInstead(clip)) return;
        clipboard.setPrimaryClip(clip);
    }

    /** Whether [clip] is the copy a hold just made, and the box opened on its text. Never throws. */
    static boolean opensInstead(@Nullable ClipData clip) {
        try {
            long at = armedAt;
            boolean held = at != 0L && SystemClock.uptimeMillis() - at <= WINDOW;
            if (!held && !PostLongPress.heldLong()) return false;
            if (!on()) return false;
            Activity activity = screen.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            CharSequence text = textOf(clip);
            if (text == null || text.toString().trim().isEmpty()) return false;
            armedAt = 0L;
            keptAt = SystemClock.uptimeMillis();
            String whole = text.toString();
            HookStatus.counted(FAMILY, KEPT_OFF);
            String copiedBy = copiers(Thread.currentThread().getStackTrace());
            Logger.printDebug(() -> "Post text select: " + KEPT_OFF + ", copied by " + copiedBy);
            Utils.runOnMainThreadNowOrLater(() -> {
                try {
                    if (!activity.isFinishing() && !activity.isDestroyed()) show(activity, whole);
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, "post text select", failure);
                }
            });
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "post text select", failure);
            return false;
        }
    }

    /**
     * Injection point, in front of each "Text copied to clipboard" popup Facebook shows right after
     * its copy. Answers whether to skip it: the first within {@link #POPUP_WINDOW} of a copy the box
     * took says the text was copied, which it wasn't. Every other popup shows. Never throws.
     */
    public static boolean dropsPopup() {
        try {
            long at = keptAt;
            if (at == 0L) return false;
            keptAt = 0L;
            if (SystemClock.uptimeMillis() - at > POPUP_WINDOW) return false;
            HookStatus.counted(FAMILY, POPUP_DROPPED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "post text select popup", failure);
            return false;
        }
    }

    /**
     * The first few of Facebook's methods on [stack], outermost last, past the extension's and the
     * runtime's own frames: where a copy came from, and so where the popup that follows it is shown.
     */
    static String copiers(StackTraceElement[] stack) {
        StringBuilder found = new StringBuilder();
        int kept = 0;
        for (StackTraceElement frame : stack) {
            String type = frame.getClassName();
            if (type.startsWith("java.") || type.startsWith("dalvik.") || type.startsWith(EXTENSION)) continue;
            if (kept++ == COPIER_FRAMES) break;
            if (found.length() > 0) found.append(" < ");
            found.append(type).append('.').append(frame.getMethodName());
        }
        return found.toString();
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
                .setNeutralButton(L10n.t(activity, "Copy all"), (box, which) -> copyAll(activity, text))
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
        openedAt = SystemClock.uptimeMillis();
        dialog.show();
        ShimmerTitle.wearTitleOf(dialog);
        HookStatus.counted(FAMILY, OPENED);
        Logger.printDebug(() -> "Post text select: " + OPENED + ", " + text.length() + " characters");
    }

    /**
     * Puts all of [text] on the clipboard. Android 13 and up shows its own line for a copy, so the
     * toast is only for older phones. Never throws.
     */
    static void copyAll(Activity activity, String text) {
        try {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText(null, text));
            HookStatus.counted(FAMILY, COPIED_ALL);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Utils.showToastShort(L10n.t(activity, "Copied"));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "post text copy all", failure);
        }
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
        keptAt = 0L;
        openedAt = 0L;
        screen = new WeakReference<>(null);
        if (listening != null) listening.removePrimaryClipChangedListener(CHANGED);
        listening = null;
        AlertDialog open = showing.get();
        if (open != null && open.isShowing()) open.dismiss();
        showing = new WeakReference<>(null);
    }
}
