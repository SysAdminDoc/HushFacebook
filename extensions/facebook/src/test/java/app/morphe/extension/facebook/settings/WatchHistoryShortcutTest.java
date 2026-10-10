/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The Watch history shortcut: off by default, a launcher entry for Facebook's Videos you've
 * watched route while on and this build opens it, never in place of another entry, gone while
 * paused or off, and side by side with the Saved one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class WatchHistoryShortcutTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @After public void restore() {
        Settings.WATCH_HISTORY_SHORTCUT.resetToDefault();
        Settings.SAVED_SHORTCUT.resetToDefault();
        PauseForTests.resume();
    }

    static boolean aStartPublishes() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        manager.removeDynamicShortcuts(Collections.singletonList(WatchHistoryShortcut.ID));
        addRoute(context, WatchHistoryShortcut.intent(context));
        WatchHistoryShortcut.refreshNow(context);
        return shortcut(manager, WatchHistoryShortcut.ID) != null;
    }

    private static void addRoute(Context context, Intent intent) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = context.getPackageName();
        info.activityInfo.name = context.getPackageName() + ".IntentUriHandler";
        shadowOf(context.getPackageManager()).addResolveInfoForIntent(intent, info);
    }

    private static ShortcutInfo shortcut(ShortcutManager manager, String id) {
        for (ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
            if (id.equals(shortcut.getId())) return shortcut;
        }
        return null;
    }

    private static ShortcutInfo stock(Context context, String id, int rank) {
        return new ShortcutInfo.Builder(context, id).setShortLabel(id)
                .setIntent(new Intent(Intent.ACTION_MAIN).setPackage(context.getPackageName()))
                .setRank(rank).build();
    }

    @Test public void defaultOffLeavesFacebooksEntriesAlone() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        ShortcutInfo entry = stock(context, "notifications", 2);
        manager.addDynamicShortcuts(Collections.singletonList(entry));
        assertFalse(Settings.WATCH_HISTORY_SHORTCUT.savedValue());
        assertEquals(SavedShortcut.Result.OFF, WatchHistoryShortcut.refreshNow(context));
        assertEquals(Collections.singletonList(entry), manager.getDynamicShortcuts());
    }

    @Test public void publishedEntryOpensTheVideosYouWatchedInThisInstall() {
        Settings.WATCH_HISTORY_SHORTCUT.save(true);
        assertTrue(aStartPublishes());
        Context context = RuntimeEnvironment.getApplication();
        ShortcutInfo watched = shortcut(context.getSystemService(ShortcutManager.class), WatchHistoryShortcut.ID);
        assertEquals("fb://activitylog?category_key=VIDEOWATCH", watched.getIntent().getDataString());
        assertEquals(Intent.ACTION_VIEW, watched.getIntent().getAction());
        assertEquals(context.getPackageName(), watched.getIntent().getComponent().getPackageName());
        assertEquals("Watch history", String.valueOf(watched.getShortLabel()));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, watched.getIntent().getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test public void itSitsBesideTheSavedEntryWithoutTakingItsPlace() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        Settings.SAVED_SHORTCUT.save(true);
        Settings.WATCH_HISTORY_SHORTCUT.save(true);
        addRoute(context, SavedShortcut.intent(context));
        assertEquals(SavedShortcut.Result.PUBLISHED, SavedShortcut.refreshNow(context));
        assertTrue(aStartPublishes());
        ShortcutInfo saved = shortcut(manager, SavedShortcut.ID);
        ShortcutInfo watched = shortcut(manager, WatchHistoryShortcut.ID);
        assertNotNull(saved);
        assertNotNull(watched);
        assertNotEquals(saved.getRank(), watched.getRank());
        Settings.WATCH_HISTORY_SHORTCUT.save(false);
        assertEquals(SavedShortcut.Result.OFF, WatchHistoryShortcut.refreshNow(context));
        assertNull(shortcut(manager, WatchHistoryShortcut.ID));
        assertNotNull("turning Watch history off took Saved with it", shortcut(manager, SavedShortcut.ID));
    }

    @Test public void fullLauncherNeverEvictsFacebooksEntries() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        List<ShortcutInfo> entries = new ArrayList<>();
        for (int i = 0; i < manager.getMaxShortcutCountPerActivity(); i++) entries.add(stock(context, "stock" + i, i));
        manager.addDynamicShortcuts(entries);
        List<ShortcutInfo> before = new ArrayList<>(manager.getDynamicShortcuts());
        addRoute(context, WatchHistoryShortcut.intent(context));
        Settings.WATCH_HISTORY_SHORTCUT.save(true);
        assertEquals(SavedShortcut.Result.NO_ROOM, WatchHistoryShortcut.refreshNow(context));
        assertEquals(before, manager.getDynamicShortcuts());
        assertNull(shortcut(manager, WatchHistoryShortcut.ID));
    }

    @Test public void missingRouteLeavesOtherShortcutsIntact() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        ShortcutInfo entry = stock(context, "notifications", 0);
        manager.addDynamicShortcuts(Collections.singletonList(entry));
        Settings.WATCH_HISTORY_SHORTCUT.save(true);
        assertEquals(SavedShortcut.Result.UNAVAILABLE, WatchHistoryShortcut.refreshNow(context));
        assertEquals(Collections.singletonList(entry), manager.getDynamicShortcuts());
        assertFalse(WatchHistoryShortcut.wanted(context));
        assertFalse(WatchHistoryShortcut.open(context));
    }

    @Test public void pauseRemovesTheEntryAndResumeKeepsTheChoice() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        Settings.WATCH_HISTORY_SHORTCUT.save(true);
        assertTrue(aStartPublishes());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(SavedShortcut.Result.OFF, WatchHistoryShortcut.refreshNow(context));
        assertNull(shortcut(manager, WatchHistoryShortcut.ID));
        assertTrue(Settings.WATCH_HISTORY_SHORTCUT.savedValue());
        PauseForTests.resume();
        assertTrue(aStartPublishes());
    }

    @Test public void languageChangeRelabelsTheEntry() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        Settings.WATCH_HISTORY_SHORTCUT.save(true);
        assertTrue(aStartPublishes());
        RuntimeEnvironment.setQualifiers("+de");
        assertEquals(SavedShortcut.Result.PUBLISHED, WatchHistoryShortcut.refreshNow(context));
        assertEquals("Wiedergabeverlauf", String.valueOf(shortcut(manager, WatchHistoryShortcut.ID).getShortLabel()));
        assertEquals(1, manager.getDynamicShortcuts().size());
    }
}
