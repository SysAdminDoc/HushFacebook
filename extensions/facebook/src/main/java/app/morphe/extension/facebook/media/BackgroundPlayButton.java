/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Icon;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The picture-in-picture window's headphones button (#455): tap it and the window closes while the
 * video's sound carries on, in Facebook's own background player with its notification.
 *
 * <p>Facebook builds the window's buttons in one place, a rewind, play or pause, and a forward, and
 * Android takes no more than three. With the button's switch on, the forward gives way to headphones
 * ({@link #windowActions}). Their tap comes back here through a broadcast only this app can send,
 * which takes the window's video on as one you started and pauses it
 * ({@link BackgroundPlay#leaveWindow}), then sends the window's screen to the back. As the screen
 * stops, Facebook's background manager asks the extension's handler about that pause, which carries
 * the video on even with Keep playing in the background's own switch off.
 *
 * <p>Off, paused, before the settings are ready, or when anything here throws, the window keeps
 * Facebook's own buttons.
 */
public final class BackgroundPlayButton {
    static final String ACTION = "app.morphe.extension.facebook.BACKGROUND_PLAY";
    static final String EXTRA_TOKEN = "app.morphe.extension.facebook.BACKGROUND_PLAY_TOKEN";

    /** The window's request code, apart from Facebook's own (2001 to 2003). */
    static final int REQUEST = 0x4846;

    /** How many buttons Android shows in the window when the screen to ask is gone. */
    static final int MOST_ACTIONS = 3;

    /** Counted each time the button sent the window's screen away. */
    static final String LEFT = "window left for background play";

    /** Counted each time a tap found no screen in the window. */
    static final String NO_WINDOW = "tap with no window";

    private static final String FAMILY = FamilyNames.BACKGROUND_PLAY;

    /** Only this process's own button carries it, as the receiver is open to every app below Android 13. */
    private static final String TOKEN = UUID.randomUUID().toString();

    @Nullable
    private static Context listeningOn;

    private BackgroundPlayButton() { }

    /**
     * The hook, before each return of Facebook's builder of the window's buttons. Answers
     * [facebooks] with headphones in place of its last button when the window has no room left, or
     * after them, or [facebooks] itself.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List windowActions(@Nullable List facebooks) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.BACKGROUND_PLAY_BUTTON.get()) return facebooks;
            Context context = Utils.getContext();
            if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return facebooks;
            Context application = context.getApplicationContext();
            listen(application);
            List actions = new ArrayList();
            if (facebooks != null) {
                for (Object action : facebooks) if (action != null) actions.add(action);
            }
            int most = mostActions();
            while (!actions.isEmpty() && actions.size() >= most) actions.remove(actions.size() - 1);
            actions.add(action(application));
            HookStatus.bound(FAMILY, "window button");
            return actions;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "window button", failure);
            return facebooks;
        }
    }

    /** How many buttons the window takes: the armed screen's answer, or Android's usual three. */
    static int mostActions() {
        Activity screen = PictureInPicture.armedScreen();
        if (screen == null) return MOST_ACTIONS;
        int most = screen.getMaxNumPictureInPictureActions();
        return most > 0 ? most : MOST_ACTIONS;
    }

    static RemoteAction action(Context application) {
        String label = L10n.t(application, "Keep playing in the background");
        Intent intent = new Intent(ACTION).setPackage(application.getPackageName()).putExtra(EXTRA_TOKEN, TOKEN);
        PendingIntent tap = PendingIntent.getBroadcast(application, REQUEST, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new RemoteAction(icon(application), label, label, tap);
    }

    /** The headphones, white on clear, as the window draws its own buttons. */
    static Icon icon(Context context) {
        int size = Math.max(1, Math.round(24 * context.getResources().getDisplayMetrics().density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(size / 24f, size / 24f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawPath(headphones(), paint);
        return Icon.createWithBitmap(bitmap);
    }

    /** A band over the head and a cup at each ear, on a 24 by 24 grid. */
    static Path headphones() {
        Path path = new Path();
        path.moveTo(12f, 1f);
        path.cubicTo(7.03f, 1f, 3f, 5.03f, 3f, 10f);
        path.lineTo(3f, 17f);
        path.cubicTo(3f, 18.66f, 4.34f, 20f, 6f, 20f);
        path.lineTo(9f, 20f);
        path.lineTo(9f, 12f);
        path.lineTo(5f, 12f);
        path.lineTo(5f, 10f);
        path.cubicTo(5f, 6.13f, 8.13f, 3f, 12f, 3f);
        path.cubicTo(15.87f, 3f, 19f, 6.13f, 19f, 10f);
        path.lineTo(19f, 12f);
        path.lineTo(15f, 12f);
        path.lineTo(15f, 20f);
        path.lineTo(18f, 20f);
        path.cubicTo(19.66f, 20f, 21f, 18.66f, 21f, 17f);
        path.lineTo(21f, 10f);
        path.cubicTo(21f, 5.03f, 16.97f, 1f, 12f, 1f);
        path.close();
        return path;
    }

    private static synchronized void listen(Context application) {
        if (listeningOn == application) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN))) return;
                tapped();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            application.registerReceiver(receiver, filter);
        }
        listeningOn = application;
    }

    /**
     * The button's tap, on the main thread: carries the video on and sends the window's screen to
     * the back. True when it went.
     */
    static boolean tapped() {
        try {
            Activity screen = PictureInPicture.armedScreen();
            if (screen == null || !screen.isInPictureInPictureMode()) {
                HookStatus.counted(FAMILY, NO_WINDOW);
                Logger.printDebug(() -> "Background play: the window's button found no window");
                return false;
            }
            BackgroundPlay.leaveWindow(PictureInPicture.currentPlayer(), screen.getApplicationContext());
            boolean left = screen.moveTaskToBack(true);
            HookStatus.bound(FAMILY, "window button tap");
            if (left) HookStatus.counted(FAMILY, LEFT);
            Logger.printDebug(() -> "Background play: the window's button " + (left ? "left" : "couldn't leave") + " the window");
            return left;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "window button tap", failure);
            return false;
        }
    }

    /** Forgets the receiver, so the next button registers one again. For tests. */
    static synchronized void forget() {
        listeningOn = null;
    }
}
