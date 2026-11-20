/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.morphe.extension.facebook.media.RecentlyWatched;
import app.morphe.extension.facebook.media.RecentlyWatchedForTests;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * The Recently watched row on the Playback page and the list it opens (#183): newest first, a chip
 * for each kind, a tap that opens the entry through Facebook's own link handler, and Clear, which
 * empties it at once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class RecentlyWatchedScreenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.RESUME_LONG_VIDEOS);
        RecentlyWatchedForTests.install();
        ShadowAlertDialog.reset();
        ShadowToast.reset();
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        RecentlyWatchedForTests.forget();
        Settings.RECENTLY_WATCHED.resetToDefault();
        Settings.RECENTLY_WATCHED_KEEP.resetToDefault();
    }

    private static HushfacebookPreferenceFragment page(ActivityController<Activity> controller) {
        HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        return page;
    }

    private static List<Preference> rowsOf(HushfacebookPreferenceFragment page) {
        List<Preference> rows = new ArrayList<>();
        collect(page.getPreferenceScreen(), rows);
        return rows;
    }

    private static void collect(PreferenceGroup group, List<Preference> rows) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (row instanceof PreferenceGroup) collect((PreferenceGroup) row, rows);
            else rows.add(row);
        }
    }

    private static int indexOfKey(List<Preference> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rows.get(i).getKey())) return i;
        }
        return -1;
    }

    /** Opens the list from its row. */
    private static AlertDialog openList(HushfacebookPreferenceFragment page) {
        List<Preference> rows = rowsOf(page);
        Preference row = rows.get(indexOfKey(rows, RecentlyWatchedScreen.ROW));
        ShadowAlertDialog.reset();
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the row opened nothing", dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static <T extends View> T find(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ListView listOf(AlertDialog dialog) {
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        assertNotNull(list);
        return list;
    }

    /** Each row's first line, as the list shows them. */
    private static List<String> titles(ListView list) {
        ListAdapter adapter = list.getAdapter();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < adapter.getCount(); i++) {
            LinearLayout row = (LinearLayout) adapter.getView(i, null, list);
            titles.add(printable(line(row, 0).getText()));
        }
        return titles;
    }

    /** A row's title (0), detail (1) or caption (2). */
    private static TextView line(LinearLayout row, int index) {
        return (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(index);
    }

    /** A row's remove button. */
    private static ImageButton removeButton(ListView list, int position) {
        LinearLayout row = (LinearLayout) list.getAdapter().getView(position, null, list);
        return (ImageButton) row.getChildAt(1);
    }

    private static String printable(CharSequence text) {
        return String.valueOf(text).replaceAll("[^\\p{Print}]", "");
    }

    private static Button undoButton(AlertDialog dialog) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(dialog.getWindow().getDecorView(), buttons);
        for (Button button : buttons) {
            if ("Undo".contentEquals(button.getText())) return button;
        }
        throw new AssertionError("no Undo button");
    }

    private static void collectButtons(View root, List<Button> buttons) {
        if (root instanceof Button) buttons.add((Button) root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collectButtons(group.getChildAt(i), buttons);
        }
    }

    /** What the bar over Undo says. */
    private static TextView removedLine(Button undo) {
        return (TextView) ((ViewGroup) undo.getParent()).getChildAt(0);
    }

    private static void pickChip(AlertDialog dialog, int index) {
        RadioGroup chips = find(dialog.getWindow().getDecorView(), RadioGroup.class);
        assertNotNull(chips);
        chips.getChildAt(index).performClick();
    }

    private static void watchedThree() {
        long now = System.currentTimeMillis();
        RecentlyWatchedForTests.watched(RecentlyWatched.STORY, "s1", "Ana Lee", null, "b1", now - 30_000, 0);
        RecentlyWatchedForTests.watched(RecentlyWatched.VIDEO, "v1", null, "Sunset over the lake", null, now - 20_000, 61_000);
        RecentlyWatchedForTests.watched(RecentlyWatched.REEL, "r1", null, null, null, now - 10_000, 15_000);
    }

    @Test
    public void theSwitchAndTheRowSitUnderResumeLongVideos() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int resume = indexOfKey(rows, Settings.RESUME_LONG_VIDEOS.key);
            int remember = indexOfKey(rows, Settings.RECENTLY_WATCHED.key);
            int list = indexOfKey(rows, RecentlyWatchedScreen.ROW);
            int keep = indexOfKey(rows, Settings.RECENTLY_WATCHED_KEEP.key);
            assertTrue("Resume long videos is missing", resume >= 0);
            assertEquals(resume + 1, remember);
            assertEquals(remember + 1, list);
            assertEquals(list + 1, keep);
            assertEquals("Remember what you watch", String.valueOf(rows.get(remember).getTitle()));
            assertEquals("Recently watched", String.valueOf(rows.get(list).getTitle()));
            assertFalse("the row writes a setting", rows.get(list).isPersistent());
            assertEquals("Keep watched entries for", String.valueOf(rows.get(keep).getTitle()));
            assertEquals("Reels, videos and stories leave the list 30 days after you watch them.",
                    String.valueOf(rows.get(keep).getSummary()));
            assertTrue("the time can't be picked with recording off", rows.get(keep).isEnabled());
        }
    }

    @Test
    public void withoutResumeLongVideosInTheBuildNeitherShows() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            assertEquals(-1, indexOfKey(rows, Settings.RECENTLY_WATCHED.key));
            assertEquals(-1, indexOfKey(rows, RecentlyWatchedScreen.ROW));
            assertEquals(-1, indexOfKey(rows, Settings.RECENTLY_WATCHED_KEEP.key));
        }
    }

    @Test
    public void theListShowsTheOneWatchedLastFirst() {
        watchedThree();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            assertEquals("Recently watched", String.valueOf(Shadows.shadowOf(dialog).getTitle()));
            ListView list = listOf(dialog);
            assertEquals("[Reel, Video, Ana Lee]", titles(list).toString());

            LinearLayout video = (LinearLayout) list.getAdapter().getView(1, null, list);
            assertTrue(line(video, 1).getText().toString().contains("1:01"));
            TextView caption = line(video, 2);
            assertEquals("Sunset over the lake", caption.getText().toString());
            assertEquals(View.VISIBLE, caption.getVisibility());

            LinearLayout story = (LinearLayout) list.getAdapter().getView(2, null, list);
            assertTrue("a row named by its poster doesn't say its kind",
                    line(story, 1).getText().toString().startsWith("Story"));
            assertEquals(View.GONE, line(story, 2).getVisibility());
        }
    }

    @Test
    public void underAMinuteAgoReadsJustNow() {
        long now = 50_000_000L;
        RecentlyWatchedForTests.clockAt(now);
        RecentlyWatchedForTests.watched(RecentlyWatched.REEL, "r1", null, null, null, now - 59_999, 0);
        assertEquals("Just now", RecentlyWatchedScreen.detail(RecentlyWatchedForTests.entries().get(0), now));
        RecentlyWatchedForTests.watched(RecentlyWatched.REEL, "r2", null, null, null, now + 5_000, 0);
        assertEquals("a clock that went back", "Just now",
                RecentlyWatchedScreen.detail(RecentlyWatchedForTests.entries().get(0), now));
        RecentlyWatchedForTests.watched(RecentlyWatched.REEL, "r4", null, null, null, now - 60_000, 0);
        String minute = RecentlyWatchedScreen.detail(RecentlyWatchedForTests.entries().get(0), now);
        assertTrue("a whole minute is Android's own words: " + minute, minute.startsWith("1 min"));
        RecentlyWatchedForTests.watched(RecentlyWatched.REEL, "r3", null, null, null, now - 3 * 60_000, 0);
        String older = RecentlyWatchedScreen.detail(RecentlyWatchedForTests.entries().get(0), now);
        assertTrue(older, older.startsWith("3 min"));
    }

    @Test
    public void theChipsShowOneKind() {
        watchedThree();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            ListView list = listOf(dialog);
            pickChip(dialog, 1);
            assertEquals("[Reel]", titles(list).toString());
            pickChip(dialog, 2);
            assertEquals("[Video]", titles(list).toString());
            pickChip(dialog, 3);
            assertEquals("[Ana Lee]", titles(list).toString());
            pickChip(dialog, 0);
            assertEquals(3, list.getAdapter().getCount());
        }
    }

    @Test
    public void aTapOpensItInFacebookAndClosesTheList() {
        watchedThree();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            ListView list = listOf(dialog);
            list.performItemClick(null, 0, 0);

            Intent opened = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertNotNull("nothing was opened", opened);
            assertEquals(Intent.ACTION_VIEW, opened.getAction());
            assertEquals("https://www.facebook.com/reel/r1", String.valueOf(opened.getData()));
            assertEquals(controller.get().getPackageName(), opened.getComponent().getPackageName());
            assertEquals(RecentlyWatchedScreen.URI_HANDLER, opened.getComponent().getClassName());
            assertEquals("a new task from an activity", 0, opened.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
            assertFalse("the list stayed over what it opened", dialog.isShowing());
        }
    }

    @Test
    public void overFacebooksOwnScreenTheListShowsWithNoSettingsUnderIt() {
        watchedThree();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity menu = controller.get();
            ShadowAlertDialog.reset();
            assertTrue(RecentlyWatchedScreen.openOver(menu));
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("nothing showed", dialog);
            assertTrue(dialog.isShowing());
            assertEquals("Recently watched", String.valueOf(Shadows.shadowOf(dialog).getTitle()));
            ListView list = listOf(dialog);
            assertEquals("[Reel, Video, Ana Lee]", titles(list).toString());
            assertNull("the settings opened under it",
                    menu.getFragmentManager().findFragmentByTag("hushfacebook_settings"));

            list.performItemClick(null, 0, 0);
            Intent opened = Shadows.shadowOf(menu).getNextStartedActivity();
            assertNotNull("nothing was opened", opened);
            assertEquals("https://www.facebook.com/reel/r1", String.valueOf(opened.getData()));
            assertEquals(RecentlyWatchedScreen.URI_HANDLER, opened.getComponent().getClassName());
            assertEquals("a new task from an activity", 0, opened.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
            assertFalse("the list stayed over what it opened", dialog.isShowing());
        }
    }

    @Test
    public void aGoingScreenGetsNoList() {
        assertFalse(RecentlyWatchedScreen.openOver(null));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            controller.get().finish();
            ShadowAlertDialog.reset();
            assertFalse(RecentlyWatchedScreen.openOver(controller.get()));
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test
    public void theMenuRowIsWantedOnlyWithItsSwitchOnAndResumeLongVideosRunning() {
        assertFalse("it starts off", RecentlyWatchedScreen.menuRowWanted());
        Settings.RECENTLY_WATCHED_MENU_ROW.save(true);
        try {
            assertTrue(RecentlyWatchedScreen.menuRowWanted());
            PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
            assertFalse("Resume long videos isn't in the build", RecentlyWatchedScreen.menuRowWanted());
            PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.RESUME_LONG_VIDEOS);
            app.morphe.extension.shared.settings.PauseForTests.pause(
                    app.morphe.extension.shared.settings.HushfacebookPause.Reason.SWITCH);
            assertFalse("paused", RecentlyWatchedScreen.menuRowWanted());
        } finally {
            app.morphe.extension.shared.settings.PauseForTests.resume();
            Settings.RECENTLY_WATCHED_MENU_ROW.resetToDefault();
        }
    }

    @Test
    public void aStoryOpensUnderItsBucket() {
        watchedThree();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            listOf(dialog).performItemClick(null, 2, 2);
            Intent opened = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertEquals("https://www.facebook.com/stories/b1/s1/", String.valueOf(opened.getData()));
        }
    }

    @Test
    public void aStoryWithNowhereToOpenSaysSoAndStays() {
        RecentlyWatchedForTests.watched(RecentlyWatched.STORY, "s9", null, null, null, System.currentTimeMillis(), 0);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            listOf(dialog).performItemClick(null, 0, 0);
            assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivity());
            assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()).startsWith("No app on this phone can open the link"));
            assertTrue(dialog.isShowing());
        }
    }

    @Test
    public void viewIntentFromAServiceStartsANewTask() {
        Intent view = RecentlyWatchedScreen.viewIntent(RuntimeEnvironment.getApplication(), "https://www.facebook.com/reel/1");
        assertTrue((view.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void clearEmptiesTheListAtOnce() {
        watchedThree();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            ListView list = listOf(dialog);
            Button clear = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            assertEquals("Clear", clear.getText().toString());
            assertTrue(clear.isEnabled());
            clear.performClick();

            assertTrue(RecentlyWatched.entries().isEmpty());
            assertEquals(0, list.getAdapter().getCount());
            assertEquals("List cleared", ShadowToast.getTextOfLatestToast());
            assertFalse("Clear stayed on with nothing to clear", clear.isEnabled());
            assertTrue("Clear closed the list", dialog.isShowing());
            assertTrue(((TextView) list.getEmptyView()).getText().toString().startsWith("Nothing here yet."));
        }
    }

    @Test
    public void aRowsRemoveButtonTakesItOffAndUndoPutsItBack() {
        watchedThree();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            ListView list = listOf(dialog);
            Button undo = undoButton(dialog);
            View bar = (View) undo.getParent();
            assertEquals("Undo showed with nothing removed", View.GONE, bar.getVisibility());

            ImageButton remove = removeButton(list, 1);
            assertEquals("Remove Video", printable(remove.getContentDescription()));
            assertFalse("the button keeps the row's taps from the list", remove.isFocusable());
            remove.performClick();

            assertEquals("[Reel, Ana Lee]", titles(list).toString());
            assertEquals(2, RecentlyWatched.entries().size());
            assertEquals(View.VISIBLE, bar.getVisibility());
            assertEquals("Removed Video", printable(removedLine(undo).getText()));
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, removedLine(undo).getAccessibilityLiveRegion());
            assertTrue("removing one closed the list", dialog.isShowing());

            undo.performClick();
            assertEquals("[Reel, Video, Ana Lee]", titles(list).toString());
            assertEquals(View.GONE, bar.getVisibility());
            assertEquals("Sunset over the lake", RecentlyWatched.entries().get(1).caption);
        }
    }

    @Test
    public void undoLeavesANewerWatchAsItIs() {
        watchedThree();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            ListView list = listOf(dialog);
            removeButton(list, 0).performClick();
            long again = System.currentTimeMillis();
            RecentlyWatchedForTests.watched(RecentlyWatched.REEL, "r1", null, null, null, again, 15_000);

            undoButton(dialog).performClick();
            assertEquals("It wasn't put back. It was watched again since, or its time on the list is up.",
                    ShadowToast.getTextOfLatestToast());
            assertEquals(3, list.getAdapter().getCount());
            assertEquals(again, RecentlyWatched.entries().get(0).watchedAt);
        }
    }

    @Test
    public void clearPutsUndoAway() {
        watchedThree();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            removeButton(listOf(dialog), 0).performClick();
            View bar = (View) undoButton(dialog).getParent();
            assertEquals(View.VISIBLE, bar.getVisibility());
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
            assertEquals(View.GONE, bar.getVisibility());
            assertTrue(RecentlyWatched.entries().isEmpty());
        }
    }

    @Test
    public void anEmptyListSaysHowToFillIt() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            ListView list = listOf(dialog);
            assertEquals(0, list.getAdapter().getCount());
            assertEquals("Nothing here yet. With Remember what you watch on, the reels, videos and stories you "
                    + "watch show up here.", ((TextView) list.getEmptyView()).getText().toString());
            assertFalse(dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled());
        }
    }

    @Test
    public void aChipWithNothingSaysSo() {
        RecentlyWatchedForTests.watched(RecentlyWatched.REEL, "r1", null, null, null, System.currentTimeMillis(), 0);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            pickChip(dialog, 3);
            ListView list = listOf(dialog);
            assertEquals(0, list.getAdapter().getCount());
            assertEquals("Nothing of this kind yet.", ((TextView) list.getEmptyView()).getText().toString());
        }
    }

    @Test
    public void theListClosesWhenThePageGoes() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = openList(page(controller));
            controller.recreate();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertFalse("the list outlived the page", dialog.isShowing());
        }
    }
}
