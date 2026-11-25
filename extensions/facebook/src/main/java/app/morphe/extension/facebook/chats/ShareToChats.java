/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

import app.morphe.extension.facebook.download.GalleryCopy;
import app.morphe.extension.facebook.navigation.TopBarButtons;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Facebook chats, a share target (#568). With chats inside Facebook, Android's share menu had no way
 * to send a photo, a video or a link to a chat: Facebook declares Messenger's share screens, but
 * their code never ships in the app, so they stay turned off. This one takes what was shared, puts
 * its text on the clipboard and its photos and videos in the gallery, then opens Facebook and
 * presses its Messaging button, the way a tap on it opens Chats. A short note says where the shared
 * things are waiting, since Facebook's own chat screen has no way to be handed them.
 *
 * <p>The patch declares the screen turned off, and the switch turns it on ({@link #sync}), so it's
 * in the share menu only for someone who asked for it. A pause takes it out again.
 */
public final class ShareToChats extends Activity {
    private static final String FAMILY = FamilyNames.SHARE_TO_CHATS;
    static final String HOOK = "Facebook chats in the share menu";

    /** How long after a share the next Facebook screen to come up presses Messaging. */
    static final long OPEN_WITHIN_MS = 30_000;
    /** How long a screen keeps looking for its Messaging button, and how often. */
    static final long LOOK_FOR_MS = 8_000;
    static final long LOOK_EVERY_MS = 250;
    /** How far up from the button its click listener can sit. */
    private static final int CLICK_LEVELS = 4;

    private static final long NONE = Long.MIN_VALUE;
    private static volatile long chatsAskedAt = NONE;
    /** The state this process last gave the share screen, or null before it gave one. */
    @Nullable private static volatile Boolean componentOn;
    @Nullable static volatile LongSupplier clockForTests;

    /** What the share brought: its text, and the photos and videos in it. */
    static final class Shared {
        @Nullable final String text;
        final List<Uri> media;
        final List<String> types;

        Shared(@Nullable String text, List<Uri> media, List<String> types) {
            this.text = text;
            this.media = media;
            this.types = types;
        }

        boolean empty() {
            return text == null && media.isEmpty();
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Context app = getApplicationContext();
        Shared shared;
        try {
            if (!wanted()) {
                toast(app, L10n.t(this, "Share to Facebook chats is off."));
                finish();
                return;
            }
            HookStatus.bound(FAMILY, HOOK);
            shared = read(getIntent(), this);
            if (shared.empty()) {
                toast(app, L10n.t(this, "Nothing in it to send."));
                finish();
                return;
            }
            if (shared.text != null) copy(this, shared.text);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            finish();
            return;
        }
        if (shared.media.isEmpty()) {
            done(app, shared, 0);
            return;
        }
        // The app that shared them granted this screen alone the right to read them, so it stays
        // until the copies are in.
        boolean queued = Utils.runOnBackgroundThread(() -> {
            int saved = 0;
            for (int i = 0; i < shared.media.size(); i++) {
                Uri item = shared.media.get(i);
                try {
                    if (!inGallery(item)) GalleryCopy.save(app, item, shared.types.get(i));
                    saved++;
                } catch (Throwable failure) {
                    Logger.printException(() -> "Share to Facebook chats: could not save a shared file to the gallery", failure);
                }
            }
            int savedCount = saved;
            Utils.runOnMainThread(() -> done(app, shared, savedCount));
        });
        // A full queue saves nothing, and says so unless the text went to the clipboard.
        if (!queued) done(app, shared, 0);
    }

    private void done(Context app, Shared shared, int saved) {
        try {
            if (saved == 0 && shared.text == null) {
                toast(app, L10n.t(app, "Couldn't save it to your gallery."));
            } else {
                openChats(app);
                toast(app, note(app, shared.text != null, saved > 0));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
        finish();
    }

    /** What the note says once Chats opens: where the text and the files went. */
    static String note(Context context, boolean text, boolean files) {
        if (text && files) return L10n.t(context, "Copied and saved. Pick a chat.");
        if (files) return L10n.t(context, "In your gallery now. Pick a chat.");
        return L10n.t(context, "Copied. Pick a chat and paste it.");
    }

    /** Whether the share screen is wanted: the switch on, the settings ready and Hushfacebook not paused. */
    static boolean wanted() {
        return Utils.settingsReady() && Settings.SHARE_TO_CHATS.get();
    }

    /**
     * The text and the photos and videos [shared] carries: its text, or its subject without one, and
     * every content address in its streams or its clip whose type is a photo's or a video's.
     */
    static Shared read(@Nullable Intent shared, Context context) {
        if (shared == null) return new Shared(null, Collections.emptyList(), Collections.emptyList());
        CharSequence body = shared.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (body == null || body.toString().trim().isEmpty()) body = shared.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        String text = body == null || body.toString().trim().isEmpty() ? null : body.toString();

        List<Uri> found = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(shared.getAction())) {
            ArrayList<Uri> streams = streams(shared);
            if (streams != null) found.addAll(streams);
        } else {
            Uri stream = stream(shared);
            if (stream != null) found.add(stream);
        }
        ClipData clip = shared.getClipData();
        if (found.isEmpty() && clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri item = clip.getItemAt(i).getUri();
                if (item != null) found.add(item);
            }
        }
        List<Uri> media = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (Uri item : found) {
            // Another app's file address can't be read from here, and a repeat is one file.
            if (!"content".equals(item.getScheme()) || media.contains(item) || ownProvider(context, item)) continue;
            String type = null;
            try {
                type = context.getContentResolver().getType(item);
            } catch (Throwable ignored) {
                // A provider that refuses to say leaves the share's own type.
            }
            if (type == null || type.endsWith("/*")) type = specific(type, shared.getType());
            if (type == null || !(type.startsWith("image/") || type.startsWith("video/"))) continue;
            media.add(item);
            types.add(type);
        }
        return new Shared(text, media, types);
    }

    /** The type a file says it has, or the share's when the file says nothing more specific. */
    @Nullable
    private static String specific(@Nullable String fromFile, @Nullable String fromShare) {
        if (fromFile != null && fromShare != null && fromShare.endsWith("/*")) return fromFile;
        return fromShare != null ? fromShare : fromFile;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static Uri stream(Intent shared) {
        if (Build.VERSION.SDK_INT >= 33) return shared.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        return shared.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static ArrayList<Uri> streams(Intent shared) {
        if (Build.VERSION.SDK_INT >= 33) return shared.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        return shared.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    }

    /**
     * Whether [item] names one of this app's own content providers. Facebook can read all of them,
     * exported or not, so a share naming one would have this screen copy Facebook's private files
     * into the gallery for whichever app sent it. When the app's providers can't be read, every
     * address counts as one.
     */
    static boolean ownProvider(Context context, Uri item) {
        String authority = item.getAuthority();
        if (authority == null) return true;
        // Android reads "10@authority" as that authority for user 10.
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        PackageInfo own;
        try {
            own = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    PackageManager.GET_PROVIDERS | PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (Throwable failure) {
            return true;
        }
        if (own.providers == null) return false;
        for (ProviderInfo provider : own.providers) {
            if (provider.authority == null) continue;
            for (String name : provider.authority.split(";")) {
                if (name.equalsIgnoreCase(authority)) return true;
            }
        }
        return false;
    }

    /** Whether [item] is already a gallery entry, so it isn't copied there again. */
    static boolean inGallery(Uri item) {
        return "media".equals(item.getAuthority());
    }

    private static void copy(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(null, text));
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    /** Brings Facebook forward, and asks the next screen of it to come up to press Messaging. */
    private static void openChats(Context context) {
        Intent main = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (main == null) return;
        chatsAskedAt = now();
        context.startActivity(main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * Called as each Facebook screen comes up: puts the share screen in or out of the share menu
     * by the switch, once per process and after each change, and on the first screen after a
     * share, presses its Messaging button. Never throws.
     */
    public static void activityResumed(Activity activity) {
        try {
            sync(activity);
            if (activity instanceof ShareToChats || !asked()) return;
            look(activity, now());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /** The switch changed: the share screen follows it now. Never throws. */
    public static void changed(Context context) {
        try {
            componentOn = null;
            sync(context);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /** Turns the share screen on or off to match the switch, unless this process already did. */
    static void sync(Context context) {
        boolean on = Utils.settingsReady() && Settings.SHARE_TO_CHATS.get();
        Boolean was = componentOn;
        if (was != null && was == on) return;
        PackageManager manager = context.getPackageManager();
        ComponentName screen = new ComponentName(context.getPackageName(), ShareToChats.class.getName());
        int state = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (manager.getComponentEnabledSetting(screen) != state) {
            manager.setComponentEnabledSetting(screen, state, PackageManager.DONT_KILL_APP);
            Logger.printDebug(() -> "Share to Facebook chats: " + (on ? "in" : "out of") + " the share menu");
        }
        componentOn = on;
    }

    private static boolean asked() {
        long at = chatsAskedAt;
        return at != NONE && now() - at <= OPEN_WITHIN_MS;
    }

    /** Presses [activity]'s Messaging button once it's laid out, or says where to tap after a while. */
    private static void look(Activity activity, long since) {
        if (!asked() || activity.isFinishing()) return;
        View button = TopBarButtons.messengerButtonIn(activity);
        if (button != null && press(button)) {
            chatsAskedAt = NONE;
            HookStatus.counted(FAMILY, "chats opened");
            Logger.printDebug(() -> "Share to Facebook chats: pressed Messaging");
            return;
        }
        if (now() - since >= LOOK_FOR_MS) {
            chatsAskedAt = NONE;
            toast(activity.getApplicationContext(), L10n.t(activity, "Open your chats to pick one."));
            Logger.printDebug(() -> "Share to Facebook chats: no Messaging button came up");
            return;
        }
        activity.getWindow().getDecorView().postDelayed(() -> {
            try {
                look(activity, since);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, HOOK, failure);
            }
        }, LOOK_EVERY_MS);
    }

    /** Clicks [button], or the nearest view above it that takes its clicks. */
    static boolean press(View button) {
        View view = button;
        for (int level = 0; view != null && level <= CLICK_LEVELS; level++) {
            if (view.hasOnClickListeners()) return view.performClick();
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static long now() {
        LongSupplier clock = clockForTests;
        return clock != null ? clock.getAsLong() : SystemClock.elapsedRealtime();
    }

    /** Forgets a pending press and the share screen's state, for tests. */
    static void forgetForTests() {
        chatsAskedAt = NONE;
        componentOn = null;
        clockForTests = null;
    }

    /** As if a share had just asked for Chats, for tests. */
    static void askForTests() {
        chatsAskedAt = now();
    }
}
