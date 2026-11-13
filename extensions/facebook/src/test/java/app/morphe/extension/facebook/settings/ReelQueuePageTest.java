/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.download.ReelQueue;
import app.morphe.extension.facebook.download.ReelQueueForTests;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

/**
 * The reel queue on the Downloads page (#462): under the queue's own row, one row per reel in the
 * order they go, each with its own Pause or Resume and Cancel.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w390dp-h844dp-night-xhdpi")
@SuppressWarnings("deprecation")
public class ReelQueuePageTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private SettingsDialog dialog;

    @Before public void open() throws InterruptedException {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PatchFamily.inBuildForTests.remove(PatchFamily.MATERIAL_YOU_THEME);
        PauseForTests.resume();
        ReelQueueForTests.reset();
        ReelQueueForTests.holdEveryTurn();
    }

    @After public void close() throws InterruptedException {
        ReelQueueForTests.reset();
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
    }

    private void show() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = SettingsL10nTest.show(controller.get());
        SettingsL10nTest.pageOf(dialog).navigation.navigate("Downloads");
        layout();
    }

    private ListView list() {
        return dialog.getView().findViewById(android.R.id.list);
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        View root = dialog.getView();
        for (int pass = 0; pass < 2; pass++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 780, 1688);
            ShadowLooper.idleMainLooper();
        }
    }

    private String keyAt(int position) {
        return ((Preference) list().getItemAtPosition(position)).getKey();
    }

    /** The row with [key], scrolled into view, or null. */
    private View row(String key) {
        for (int position = 0; position < list().getCount(); position++) {
            if (!key.equals(keyAt(position))) continue;
            for (int tries = 0; tries < list().getCount(); tries++) {
                if (position < list().getFirstVisiblePosition()) list().scrollListBy(-list().getHeight() / 2);
                else if (position > list().getLastVisiblePosition()) list().scrollListBy(list().getHeight() / 2);
                else break;
                layout();
            }
            return list().getChildAt(position - list().getFirstVisiblePosition());
        }
        return null;
    }

    private static Button button(View row, int index) {
        ViewGroup frame = row.findViewById(android.R.id.widget_frame);
        assertEquals("a reel's row doesn't have two buttons", 2, frame.getChildCount());
        return (Button) frame.getChildAt(index);
    }

    private static String text(View row, int id) {
        return String.valueOf(((TextView) row.findViewById(id)).getText());
    }

    private static void waitUntilSaving() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ReelQueue.status() == null || !ReelQueue.status().saving) {
            assertTrue("the queue never started its first reel", System.nanoTime() < until);
            Thread.sleep(2);
        }
    }

    @Test public void eachQueuedReelHasItsOwnRowWithPauseResumeAndCancel() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        assertTrue(ReelQueueForTests.add(app, "1", null));
        waitUntilSaving();
        assertTrue(ReelQueueForTests.add(app, "2", "Ana"));
        assertTrue(ReelQueueForTests.add(app, "3", null));
        show();

        int downloads = -1;
        for (int i = 0; i < list().getCount(); i++) {
            if ("section_Downloads".equals(keyAt(i))) downloads = i;
        }
        assertTrue("Downloads has no expandable heading", downloads >= 0);
        assertEquals("the queue isn't the first row under Downloads", ReelQueue.ROW_KEY, keyAt(downloads + 1));
        assertEquals("queued_reel_1", keyAt(downloads + 2));
        assertEquals("queued_reel_2", keyAt(downloads + 3));
        assertEquals("queued_reel_3", keyAt(downloads + 4));

        View saving = row("queued_reel_1");
        assertNotNull(saving);
        assertEquals("Reel 1", text(saving, android.R.id.title));
        assertEquals("Saving now", text(saving, android.R.id.summary));

        View ana = row("queued_reel_2");
        assertNotNull(ana);
        assertEquals("Reel from " + L10n.isolate("Ana"), text(ana, android.R.id.title));
        assertEquals("Waiting", text(ana, android.R.id.summary));
        Button pause = button(ana, 0);
        assertEquals("Pause", pause.getText().toString());
        assertEquals("Pause this reel", String.valueOf(pause.getContentDescription()));
        assertEquals("Cancel this reel", String.valueOf(button(ana, 1).getContentDescription()));
        assertTrue("a screen reader reads the reel's name and state as one stop", ana.isScreenReaderFocusable());
        int touch = Math.round(48 * pause.getResources().getDisplayMetrics().density);
        Button cancel = button(ana, 1);
        String where = "row " + ana.getHeight() + ", frame " + ((View) pause.getParent()).getHeight();
        for (Button each : new Button[] {pause, cancel}) {
            assertTrue("a reel's " + each.getText() + " is " + each.getWidth() + "x" + each.getHeight()
                    + ", smaller than a finger's " + touch + "; " + where, each.getHeight() >= touch && each.getWidth() >= touch);
        }
        assertEquals("Cancel isn't beside Pause", pause.getTop(), cancel.getTop());
        assertTrue("Cancel isn't after Pause", cancel.getLeft() >= pause.getRight());

        assertTrue(pause.performClick());
        layout();
        ana = row("queued_reel_2");
        assertEquals("Paused", text(ana, android.R.id.summary));
        assertEquals("Resume", button(ana, 0).getText().toString());
        assertEquals("Resume this reel", String.valueOf(button(ana, 0).getContentDescription()));
        assertEquals(ReelQueue.Item.State.PAUSED, ReelQueue.items().get(1).state);

        assertTrue(button(ana, 0).performClick());
        layout();
        assertEquals("Waiting", text(row("queued_reel_2"), android.R.id.summary));
        assertEquals(ReelQueue.Item.State.WAITING, ReelQueue.items().get(1).state);

        View third = row("queued_reel_3");
        assertNotNull(third);
        assertTrue(button(third, 1).performClick());
        layout();
        assertNull("a cancelled reel is still listed", row("queued_reel_3"));
        assertEquals(2, ReelQueue.items().size());
        assertNotNull("cancelling one reel took the others' rows", row("queued_reel_2"));
    }

    @Test public void theRowsGoWhenTheQueueEnds() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        assertTrue(ReelQueueForTests.add(app, "1", null));
        waitUntilSaving();
        assertTrue(ReelQueueForTests.add(app, "2", null));
        show();
        assertNotNull(row("queued_reel_2"));

        ReelQueue.cancel();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ReelQueue.status() != null) {
            assertTrue("the queue never ended after Cancel", System.nanoTime() < until);
            Thread.sleep(2);
        }
        layout();
        assertNull("the queue's row stayed after Cancel", row(ReelQueue.ROW_KEY));
        assertNull("a reel's row stayed after the queue's Cancel", row("queued_reel_1"));
        assertNull(row("queued_reel_2"));
    }
}
