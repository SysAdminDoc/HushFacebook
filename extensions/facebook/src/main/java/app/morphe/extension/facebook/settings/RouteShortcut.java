/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * An optional launcher entry for one of Facebook's own routes, and whether its Menu row is wanted:
 * {@link SavedShortcut} and {@link WatchHistoryShortcut}. Each publishes only while its switch is
 * on and this build opens the route itself, and never evicts another shortcut. The shortcuts hand
 * in their text through calls of their own, so every string stays a literal the translation check
 * can see.
 */
final class RouteShortcut {
    final String id;
    private final String name;
    private final String uri;
    private final BooleanSetting setting;
    private final Function<Context, String> label;
    private final Supplier<String> unavailable;
    private final int iconResource;
    private final AtomicBoolean queued = new AtomicBoolean();

    RouteShortcut(String id, String name, String uri, BooleanSetting setting, Function<Context, String> label,
            Supplier<String> unavailable, int iconResource) {
        this.id = id;
        this.name = name;
        this.uri = uri;
        this.setting = setting;
        this.label = label;
        this.unavailable = unavailable;
        this.iconResource = iconResource;
    }

    Intent intent(Context context) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .setPackage(context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Whether the row belongs in the Menu: the switch is on and this build opens the route itself. */
    boolean wanted(Context context) {
        return Utils.settingsReady() && setting.get() && destination(context) != null;
    }

    /**
     * Opens the route from [context], inside the current task when it's an activity. False when this
     * build has no such route of its own or Android refused to start it.
     */
    boolean open(Context context) {
        try {
            Intent route = intent(context);
            ComponentName destination = destination(context);
            if (destination == null) return false;
            route.setComponent(destination);
            if (context instanceof Activity) route.setFlags(route.getFlags() & ~Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(route);
            return true;
        } catch (RuntimeException failure) {
            Logger.printException(() -> name + " shortcut: could not open " + uri, failure);
            return false;
        }
    }

    /** The activity of this package that takes the route, or null. */
    private ComponentName destination(Context context) {
        ComponentName destination = intent(context).resolveActivity(context.getPackageManager());
        return destination != null && context.getPackageName().equals(destination.getPackageName()) ? destination : null;
    }

    void refresh(Context context) {
        if (!queued.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            try {
                refreshNow(app == null ? context : app);
            } finally {
                queued.set(false);
            }
        });
        if (!accepted) queued.set(false);
    }

    /** Settings changes give immediate feedback; application starts quietly retry when needed. */
    void changed(Context context) {
        Utils.runOnBackgroundThread(() -> {
            SavedShortcut.Result result = refreshNow(context);
            if (result == SavedShortcut.Result.NO_ROOM) {
                Utils.showToastShort(L10n.t("Your launcher has no room for another shortcut."));
            } else if (result == SavedShortcut.Result.UNAVAILABLE) {
                Utils.showToastShort(unavailable.get());
            }
        });
    }

    /** The toast for a row whose route this build doesn't open. */
    String unavailable() {
        return unavailable.get();
    }

    /**
     * Every route shortcut refreshes under one lock: each reads the launcher's list to find a free
     * rank and room, so two at once could take the same rank or both count the last free slot.
     */
    SavedShortcut.Result refreshNow(Context context) {
        synchronized (RouteShortcut.class) {
            return refreshLocked(context);
        }
    }

    private SavedShortcut.Result refreshLocked(Context context) {
        try {
            ShortcutManager manager = context.getSystemService(ShortcutManager.class);
            if (manager == null) return SavedShortcut.Result.UNAVAILABLE;
            if (!Utils.settingsReady() || !setting.get()) {
                manager.removeDynamicShortcuts(Collections.singletonList(id));
                return SavedShortcut.Result.OFF;
            }
            Intent route = intent(context);
            ComponentName destination = destination(context);
            if (destination == null) {
                manager.removeDynamicShortcuts(Collections.singletonList(id));
                return SavedShortcut.Result.UNAVAILABLE;
            }
            route.setComponent(destination);
            List<ShortcutInfo> shortcuts = manager.getDynamicShortcuts();
            ShortcutInfo existing = null;
            int rank = 0;
            for (ShortcutInfo shortcut : shortcuts) {
                if (id.equals(shortcut.getId())) existing = shortcut;
                else rank = Math.max(rank, shortcut.getRank() + 1);
            }
            String text = label.apply(context);
            if (existing != null && text.contentEquals(existing.getShortLabel())
                    && route.filterEquals(existing.getIntent())) return SavedShortcut.Result.PUBLISHED;
            if (existing == null && shortcuts.size() + manager.getManifestShortcuts().size()
                    >= manager.getMaxShortcutCountPerActivity()) return SavedShortcut.Result.NO_ROOM;
            ShortcutInfo shortcut = new ShortcutInfo.Builder(context, id)
                    .setShortLabel(text).setLongLabel(text)
                    .setIcon(icon(context))
                    .setIntent(route).setRank(existing == null ? rank : existing.getRank()).build();
            // addDynamicShortcuts refuses a full activity. pushDynamicShortcut would evict its last entry.
            boolean published = existing == null
                    ? manager.addDynamicShortcuts(Collections.singletonList(shortcut))
                    : manager.updateShortcuts(Collections.singletonList(shortcut));
            return published ? SavedShortcut.Result.PUBLISHED : SavedShortcut.Result.UNAVAILABLE;
        } catch (RuntimeException failure) {
            Logger.printException(() -> name + " shortcut: could not update the launcher entry", failure);
            return SavedShortcut.Result.UNAVAILABLE;
        }
    }

    private Icon icon(Context context) {
        int size = Math.max(1, Math.round(48 * context.getResources().getDisplayMetrics().density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Drawable drawable = context.getDrawable(iconResource);
        if (drawable == null) throw new IllegalStateException(name + " icon unavailable");
        drawable.setBounds(0, 0, size, size);
        drawable.draw(new Canvas(bitmap));
        // Android rejects resource icons from a package other than the shortcut's owner.
        return Icon.createWithBitmap(bitmap);
    }
}
