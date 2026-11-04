/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The bar along a story's bottom: it goes on the story's card under the segments and fills with the
 * progress, from a view of segments or a drawable, and off, paused, before the settings are ready
 * or with no card to put it on, there's no bar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoryProgressBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private StoryProgressBarForTests.Story story;

    @Before
    public void start() {
        HookStatus.clear();
        story = StoryProgressBarForTests.story();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.STORY_PROGRESS_BAR.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.STORY_VIEWER_CLEANUP + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndBelongsToItsPatch() {
        assertFalse("the progress bar doesn't start off", Settings.STORY_PROGRESS_BAR.defaultValue);
        assertTrue(PatchFamily.STORY_VIEWER_CLEANUP.switches.contains(Settings.STORY_PROGRESS_BAR));
    }

    @Test
    public void theBarGoesUnderTheSegmentsAndFillsWithTheStory() {
        Settings.STORY_PROGRESS_BAR.save(true);
        StoryProgressBar.segmentsMoved(story.segments, 250);
        StoryProgressBar.Bar bar = StoryProgressBar.barOn(story.card);
        assertNotNull("the card got no bar", bar);
        assertEquals(0.25f, bar.progress, 0f);
        // Where the card's overlay draws: under the segments' padded part from 49 to 1039, 12 above the
        // card's bottom (2000 tall, scrolled 3 down), 2 thick.
        assertEquals(new Rect(49, 1989, 1039, 1991), bar.getBounds());
        assertNotNull("the bar isn't in the card's overlay", bar.getCallback());

        StoryProgressBar.segmentsMoved(story.segments, 750);
        assertSame("a second move made a second bar", bar, StoryProgressBar.barOn(story.card));
        assertEquals(0.75f, bar.progress, 0f);
        StoryProgressBar.segmentsMoved(story.segments, 4000);
        assertEquals("a progress past the end overfilled the bar", 1f, bar.progress, 0f);
        String line = statusLine();
        assertTrue("the bar wasn't counted once: " + line, line != null && line.endsWith(StoryProgressBar.ADDED + " 1"));
    }

    @Test
    public void aSegmentsDrawableLinesTheBarUpWithItsBounds() {
        Settings.STORY_PROGRESS_BAR.save(true);
        ColorDrawable segments = new ColorDrawable(0xFFFFFFFF);
        segments.setBounds(40, 0, 440, 8);
        segments.setCallback(story.segments);
        StoryProgressBar.segmentsMoved(segments, 500);
        StoryProgressBar.Bar bar = StoryProgressBar.barOn(story.card);
        assertNotNull("the card got no bar from the drawable", bar);
        assertEquals(new Rect(85, 1989, 485, 1991), bar.getBounds());
        assertEquals(0.5f, bar.progress, 0f);
    }

    @Test
    @Config(qualifiers = "w1080dp-h2200dp") // A window the story's size, so the card is still the first half-height view.
    public void aCardLeavingTheWindowTakesItsBarOff() {
        Settings.STORY_PROGRESS_BAR.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(story.root);
        shadowOf(Looper.getMainLooper()).idle();
        story.layOut();
        assertTrue(story.card.isAttachedToWindow());
        StoryProgressBar.segmentsMoved(story.segments, 500);
        StoryProgressBar.Bar bar = StoryProgressBar.barOn(story.card);
        assertNotNull("the attached card got no bar", bar);

        // Facebook pools the card, so it could be put back anywhere.
        story.root.removeView(story.card);
        assertNull("a card out of the window kept its bar", StoryProgressBar.barOn(story.card));
        assertNull("a card out of the window kept the bar in its overlay", bar.getCallback());
        story.root.addView(story.card);
        assertNull("the bar came back on its own", StoryProgressBar.barOn(story.card));
    }

    @Test
    public void offPausedOrColdThereIsNoBar() {
        StoryProgressBar.segmentsMoved(story.segments, 500);
        assertNull("off, the card got a bar", StoryProgressBar.barOn(story.card));

        Settings.STORY_PROGRESS_BAR.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            StoryProgressBar.segmentsMoved(story.segments, 500);
            assertNull("a Hushfacebook paused by " + reason + " drew a bar", StoryProgressBar.barOn(story.card));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            StoryProgressBar.segmentsMoved(story.segments, 500);
            assertNull("a bar went on before the settings were ready", StoryProgressBar.barOn(story.card));
        });
        String line = statusLine();
        assertFalse("a story left to Facebook was counted: " + line, line != null && line.contains("Counted"));

        StoryProgressBar.segmentsMoved(story.segments, 500);
        StoryProgressBar.Bar bar = StoryProgressBar.barOn(story.card);
        assertNotNull("on again, the card got no bar", bar);
        Settings.STORY_PROGRESS_BAR.save(false);
        StoryProgressBar.segmentsMoved(story.segments, 600);
        assertNull("turned off, the card kept its bar", StoryProgressBar.barOn(story.card));
        assertNull("turned off, the bar stayed in the card's overlay", bar.getCallback());
    }

    @Test
    public void nothingToPutItOnAndNothingBreaks() {
        Settings.STORY_PROGRESS_BAR.save(true);
        StoryProgressBar.segmentsMoved(null, 500);
        StoryProgressBar.segmentsMoved(new Object(), 500);
        StoryProgressBar.segmentsMoved(new ColorDrawable(0xFFFFFFFF), 500);
        View loose = new View(story.card.getContext());
        StoryProgressBar.segmentsMoved(loose, 500);
        assertNull("a view on its own got a bar", StoryProgressBar.barOn(loose));
        String line = statusLine();
        assertTrue("something threw: " + line, line != null && line.contains(" 0 missing"));
        assertFalse("something was counted: " + line, line.contains("Counted"));
    }
}
