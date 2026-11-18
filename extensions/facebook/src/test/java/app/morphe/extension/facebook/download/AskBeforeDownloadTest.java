/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;

import com.facebook.video.engine.api.VideoPlayerParams;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Ask before downloading (#511), first as the question alone, then through the three buttons a
 * stray tap lands on: a reel's Download, and the Download under a video post and a photo post.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AskBeforeDownloadTest {
    private static final String CLIP = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_720p.mp4?oh=1&oe=2";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity screen;

    /** Saves the question let through, counted at the moment a video save starts. */
    private final AtomicInteger saves = new AtomicInteger();

    private int asked;

    private final Runnable download = () -> asked++;

    @Before
    public void setUp() throws Exception {
        screen = Robolectric.buildActivity(Activity.class).setup().get();
        LogBufferManager.clearLogBuffer();
        Settings.DOWNLOAD_VIDEOS.save(true);
        Settings.DOWNLOAD_PHOTOS.save(true);
        Settings.POST_DOWNLOAD_BUTTON.save(true);
        PostDownloadButton.videoInBuildForTests = true;
        PostDownloadButton.photoInBuildForTests = true;
        MediaDownload.detailsForTests = details -> saves.incrementAndGet();
        // Every Meta name answers a private address, so each save stops before it connects.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
    }

    @After
    public void restore() throws Exception {
        AlertDialog question = ShadowAlertDialog.getLatestAlertDialog();
        if (question != null && question.isShowing()) question.dismiss();
        idle();
        waitForSaves();
        MediaDownload.detailsForTests = null;
        MediaDownload.policyForTests = null;
        PostDownloadButton.videoInBuildForTests = null;
        PostDownloadButton.photoInBuildForTests = null;
        PauseForTests.resume();
        Settings.ASK_BEFORE_DOWNLOAD.resetToDefault();
        Settings.DOWNLOAD_QUALITY_ASK.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        Settings.POST_DOWNLOAD_BUTTON.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaDownload.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /** The question showing now, or null. */
    private static AlertDialog showing() {
        idle();
        AlertDialog question = ShadowAlertDialog.getLatestAlertDialog();
        return question != null && question.isShowing() ? question : null;
    }

    private static void press(AlertDialog question, int button) {
        assertTrue(question.getButton(button).performClick());
        idle();
    }

    private PostDownloadButton buttonUnder(com.facebook.graphql.model.GraphQLStory post) {
        PostDownloadButton button = PostDownloadButton.click(post, screen, VideoMenuItemForTests.ATTACHMENTS,
                VideoMenuItemForTests.MEDIA, VideoMenuItemForTests.ATTACHED_STORY, PhotoMenuItemForTests.SUBATTACHMENTS);
        assertNotNull("no Download under the post", button);
        return button;
    }

    private ReelDownload reelButton() {
        return new ReelDownload(new FileNameTemplateTest.ReelSourceParams(new VideoPlayerParams(
                "VideoId: 2233445566778899", new FileNameTemplateTest.ReelSource(CLIP))),
                screen, "hd", "sd", "manifest", 1, true);
    }

    // ---- The question -----------------------------------------------------------------------------

    @Test
    public void offNothingAsksAndTheCallerSaves() {
        assertFalse(Settings.ASK_BEFORE_DOWNLOAD.defaultValue);
        assertFalse(AskBeforeDownload.ask(screen, download));
        assertFalse(AskBeforeDownload.askForVideo(screen, download));
        assertNull("a question with the switch off", showing());
        assertEquals("the question ran the save itself", 0, asked);
    }

    @Test
    public void continueRunsTheDownloadOnce() {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        assertTrue(AskBeforeDownload.ask(screen, download));
        AlertDialog question = showing();
        assertNotNull("no question", question);
        assertEquals("nothing may start before the answer", 0, asked);

        press(question, DialogInterface.BUTTON_POSITIVE);
        assertNull("the question stayed up", showing());
        assertEquals(1, asked);
    }

    @Test
    public void cancelSavesNothing() {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        assertTrue(AskBeforeDownload.ask(screen, download));
        AlertDialog question = showing();
        assertNotNull(question);

        press(question, DialogInterface.BUTTON_NEGATIVE);
        assertNull(showing());
        assertEquals("Cancel started a save", 0, asked);
    }

    @Test
    public void aSecondTapWhileAskingDoesNotStackAnotherQuestion() {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        assertTrue(AskBeforeDownload.ask(screen, download));
        AlertDialog first = showing();
        assertNotNull(first);

        assertTrue("the second tap fell through to a save", AskBeforeDownload.ask(screen, download));
        assertSame("a second question opened", first, showing());

        press(first, DialogInterface.BUTTON_NEGATIVE);
        assertTrue(AskBeforeDownload.ask(screen, download));
        assertNotSame("the next tap got the closed question", first, showing());
    }

    @Test
    public void pausedTheCallerSaves() {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(AskBeforeDownload.ask(screen, download));
        assertNull("a question while paused", showing());
    }

    @Test
    public void aDownloadThatThrowsAfterContinueStaysInside() {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        assertTrue(AskBeforeDownload.ask(screen, () -> {
            throw new IllegalStateException("the save broke");
        }));
        AlertDialog question = showing();
        assertNotNull(question);
        press(question, DialogInterface.BUTTON_POSITIVE);
        assertNull(showing());
    }

    @Test
    public void theListOfQualitiesDoesTheAskingForAVideoButNotForPhotos() {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        Settings.DOWNLOAD_QUALITY_ASK.save(true);
        assertFalse("a video would be asked about twice", AskBeforeDownload.askForVideo(screen, download));
        assertNull(showing());
        assertTrue("photos have no list to ask for them", AskBeforeDownload.ask(screen, download));
        assertNotNull(showing());
    }

    // ---- The buttons ------------------------------------------------------------------------------

    @Test
    public void aReelsDownloadButtonWaitsForContinue() throws Exception {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        reelButton().invoke(null);
        AlertDialog question = showing();
        assertNotNull("the reel's Download didn't ask", question);
        waitForSaves();
        assertEquals("the reel saved before the answer", 0, saves.get());

        press(question, DialogInterface.BUTTON_POSITIVE);
        waitForSaves();
        assertEquals(1, saves.get());
    }

    @Test
    public void cancelOnAReelSavesNothing() throws Exception {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        reelButton().invoke(null);
        AlertDialog question = showing();
        assertNotNull(question);
        press(question, DialogInterface.BUTTON_NEGATIVE);
        waitForSaves();
        assertEquals(0, saves.get());
    }

    @Test
    public void aReelSavesAtOnceWithTheSwitchOff() throws Exception {
        reelButton().invoke(null);
        assertNull(showing());
        waitForSaves();
        assertEquals(1, saves.get());
    }

    @Test
    public void theDownloadUnderAVideoPostWaitsForContinue() throws Exception {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        buttonUnder(VideoMenuItemForTests.videoPost()).invoke(null);
        AlertDialog question = showing();
        assertNotNull("the video's Download didn't ask", question);
        waitForSaves();
        assertEquals("the video saved before the answer", 0, saves.get());

        press(question, DialogInterface.BUTTON_POSITIVE);
        waitForSaves();
        assertEquals(1, saves.get());
    }

    @Test
    public void theDownloadUnderAPhotoPostWaitsForContinue() throws Exception {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        buttonUnder(PhotoMenuItemForTests.photoPost()).invoke(null);
        AlertDialog question = showing();
        assertNotNull("the photo's Download didn't ask", question);
        assertFalse("the photo saved before the answer",
                LogBufferManager.buildExportText().contains("Save photo tapped"));

        press(question, DialogInterface.BUTTON_POSITIVE);
        waitForSaves();
        assertTrue("Continue didn't save the photo",
                LogBufferManager.buildExportText().contains("Save photo tapped under a post"));
    }
}
