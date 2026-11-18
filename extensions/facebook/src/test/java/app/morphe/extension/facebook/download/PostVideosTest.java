/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.facebook.graphql.model.GraphQLMedia;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.model.GraphQLStoryAttachment;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The videos of a post with more than one thing in it (#509): a video behind a photo, an album of
 * videos, and a post mixing the two, through the post's menu and the button under the post.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostVideosTest {
    private static final String ATTACHMENTS = VideoMenuItemForTests.ATTACHMENTS;
    private static final String MEDIA = VideoMenuItemForTests.MEDIA;
    private static final String SHARED = VideoMenuItemForTests.ATTACHED_STORY;
    private static final String SUBATTACHMENTS = PhotoMenuItemForTests.SUBATTACHMENTS;

    private static final String NO_FILE_MESSAGE = "Couldn't save this video. Play it for a moment, then try again.";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<PostDetails> saved = Collections.synchronizedList(new ArrayList<>());

    private Activity screen;

    @Before
    public void setUp() throws Exception {
        screen = Robolectric.buildActivity(Activity.class).setup().get();
        Settings.DOWNLOAD_VIDEOS.save(true);
        Settings.DOWNLOAD_PHOTOS.save(true);
        Settings.POST_DOWNLOAD_BUTTON.save(true);
        PostDownloadButton.videoInBuildForTests = true;
        PostDownloadButton.photoInBuildForTests = true;
        MediaDownload.detailsForTests = saved::add;
        // Every Meta name answers a private address, so each save stops before it connects.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
        ShadowToast.reset();
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() throws Exception {
        AlertDialog question = ShadowAlertDialog.getLatestAlertDialog();
        if (question != null && question.isShowing()) question.dismiss();
        idle();
        waitForChains();
        MediaDownload.detailsForTests = null;
        MediaDownload.policyForTests = null;
        PostDownloadButton.videoInBuildForTests = null;
        PostDownloadButton.photoInBuildForTests = null;
        PauseForTests.resume();
        Settings.ASK_BEFORE_DOWNLOAD.resetToDefault();
        Settings.DOWNLOAD_QUALITY_ASK.resetToDefault();
        Settings.DOWNLOAD_ACTION.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        Settings.POST_DOWNLOAD_BUTTON.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Waits for every post's chain of saves, and then for the last save of each to let go. */
    private static void waitForChains() throws InterruptedException {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("hushfacebook-post-saves".equals(thread.getName())) {
                thread.join(20_000);
                assertFalse("a post's saves never finished", thread.isAlive());
            }
        }
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaDownload.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /** A video whose post names its file. */
    private static GraphQLMedia video(String id) {
        GraphQLMedia media = noFile(id);
        media.with("playable_url", "https://video.xx.fbcdn.net/v/t42.1790-2/" + id + ".mp4?_nc_cat=1");
        return media;
    }

    /** A video the post names no file for, as one that hasn't been played can be. */
    private static GraphQLMedia noFile(String id) {
        GraphQLMedia media = new GraphQLMedia("Video");
        media.with("id", id);
        return media;
    }

    /** One attachment listing [media], as an album's attachment lists one per item. */
    private static GraphQLStoryAttachment listing(GraphQLMedia cover, GraphQLMedia... media) {
        GraphQLStoryAttachment[] each = new GraphQLStoryAttachment[media.length];
        for (int i = 0; i < media.length; i++) each[i] = new GraphQLStoryAttachment(media[i]);
        return new GraphQLStoryAttachment(cover, each);
    }

    private static GraphQLStory album(GraphQLMedia... media) {
        return new GraphQLStory(null, listing(null, media));
    }

    private static List<String> idsOf(GraphQLStory post) {
        List<String> ids = new ArrayList<>();
        for (VideoMenuItem.Video video : VideoMenuItem.videosOf(post, ATTACHMENTS, MEDIA, SHARED, SUBATTACHMENTS)) {
            ids.add(video.id);
        }
        return ids;
    }

    private Menu menuOf(GraphQLStory post) {
        Menu menu = VideoMenuItemForTests.facebooksMenu(screen);
        VideoMenuItem.add(menu, new View(screen), post, VideoMenuItemForTests.ICON, ATTACHMENTS, MEDIA, SHARED, SUBATTACHMENTS);
        return menu;
    }

    private static MenuItem row(Menu menu, String title) {
        for (int i = 0; i < menu.size(); i++) {
            if (title.equals(String.valueOf(menu.getItem(i).getTitle()))) return menu.getItem(i);
        }
        return null;
    }

    private static void tap(Menu menu, MenuItem row) {
        assertNotNull("the row isn't there", row);
        menu.performIdentifierAction(row.getItemId(), 0);
    }

    private PostDownloadButton button(GraphQLStory post) {
        return PostDownloadButton.click(post, screen, ATTACHMENTS, MEDIA, SHARED, SUBATTACHMENTS);
    }

    /** The ids of the videos saved so far, in the order their saves began. */
    private List<String> savedVideos() {
        List<String> ids = new ArrayList<>();
        synchronized (saved) {
            for (PostDetails details : saved) ids.add(details.videoId);
        }
        return ids;
    }

    /** The question showing now, or null. */
    private static AlertDialog question() {
        idle();
        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        return latest != null && latest.isShowing() ? latest : null;
    }

    @Test
    public void aVideoBehindAPhotoGetsTheRow() throws Exception {
        GraphQLStory post = new GraphQLStory(null,
                new GraphQLStoryAttachment(PhotoMenuItemForTests.photo(1)),
                new GraphQLStoryAttachment(video("21")));

        Menu menu = menuOf(post);
        assertNull(row(menu, "Save all videos"));
        tap(menu, row(menu, "Download to phone"));
        waitForChains();

        assertEquals(Collections.singletonList("21"), savedVideos());
    }

    @Test
    public void anAlbumOfVideosSavesThemAllInOrder() throws Exception {
        Menu menu = menuOf(album(video("21"), video("22")));

        assertNull("one row stands for the whole album", row(menu, "Download to phone"));
        tap(menu, row(menu, "Save all videos"));
        waitForChains();

        assertEquals(Arrays.asList("21", "22"), savedVideos());
    }

    @Test
    public void videosKeepThePostsOrderAndARepeatCountsOnce() {
        GraphQLStory post = new GraphQLStory(null,
                new GraphQLStoryAttachment(video("21")),
                listing(null, video("22"), video("21"), PhotoMenuItemForTests.photo(1), video("23")));

        assertEquals(Arrays.asList("21", "22", "23"), idsOf(post));
    }

    @Test
    public void anAlbumsOwnVideoCountsOnlyWhenItsListHoldsNone() {
        GraphQLStory photos = new GraphQLStory(null, listing(video("20"), PhotoMenuItemForTests.photo(1)));
        GraphQLStory videos = new GraphQLStory(null, listing(video("20"), video("21"), video("22")));

        assertEquals(Collections.singletonList("20"), idsOf(photos));
        assertEquals(Arrays.asList("21", "22"), idsOf(videos));
    }

    @Test
    public void onePostGivesAtMostTheLimit() {
        GraphQLMedia[] media = new GraphQLMedia[VideoMenuItem.MAX_VIDEOS + 1];
        for (int i = 0; i < media.length; i++) media[i] = video(String.valueOf(1000 + i));

        assertEquals(VideoMenuItem.MAX_VIDEOS, idsOf(album(media)).size());
    }

    @Test
    public void sendToAppHandsOverEveryLinkTogether() throws Exception {
        Settings.DOWNLOAD_ACTION.save(SendLink.Action.SEND);

        Menu menu = menuOf(album(video("21"), video("22")));
        tap(menu, row(menu, "Send to app"));
        idle();

        Application application = RuntimeEnvironment.getApplication();
        Intent chooser = Shadows.shadowOf(application).getNextStartedActivity();
        assertNotNull("nothing was handed over", chooser);
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals("https://www.facebook.com/watch/?v=21\nhttps://www.facebook.com/watch/?v=22",
                send.getStringExtra(Intent.EXTRA_TEXT));
        waitForChains();
        assertTrue(saved.isEmpty());
    }

    @Test
    public void theButtonUnderAnAlbumOfVideosSavesThemAll() throws Exception {
        PostDownloadButton button = button(album(video("21"), video("22")));

        assertNotNull("an album of videos lost its button", button);
        button.invoke(null);
        waitForChains();

        assertEquals(Arrays.asList("21", "22"), savedVideos());
    }

    @Test
    public void theButtonUnderAPhotoAndAVideoSavesBoth() throws Exception {
        PostDownloadButton button = button(album(PhotoMenuItemForTests.photo(1), video("21")));

        assertNotNull(button);
        button.invoke(null);
        waitForChains();

        assertEquals("the photo and then the video", 2, saved.size());
        assertEquals("21", saved.get(1).videoId);
    }

    @Test
    public void withPhotosOffTheButtonSavesOnlyTheVideo() throws Exception {
        Settings.DOWNLOAD_PHOTOS.save(false);

        button(album(PhotoMenuItemForTests.photo(1), video("21"))).invoke(null);
        waitForChains();

        assertEquals(Collections.singletonList("21"), savedVideos());
    }

    @Test
    public void oneQuestionCoversTheWholeAlbum() throws Exception {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);

        Menu menu = menuOf(album(video("21"), video("22")));
        tap(menu, row(menu, "Save all videos"));

        AlertDialog asked = question();
        assertNotNull("nothing asked", asked);
        assertTrue("it saved before the answer", saved.isEmpty());

        asked.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        waitForChains();
        idle();

        assertEquals(Arrays.asList("21", "22"), savedVideos());
        assertNull("a second question came up", question());
    }

    @Test
    public void cancellingTheQuestionSavesNothing() throws Exception {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);

        Menu menu = menuOf(album(video("21"), video("22")));
        tap(menu, row(menu, "Save all videos"));

        AlertDialog asked = question();
        assertNotNull("nothing asked", asked);
        asked.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        idle();
        waitForChains();

        assertTrue(saved.isEmpty());
    }

    @Test
    public void cancellingOneSaveLeavesTheRestUnsaved() throws Exception {
        CountDownLatch looking = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> {
            looking.countDown();
            try {
                answer.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException(host);
            }
            return new InetAddress[] { InetAddress.getByName("10.9.8.7") };
        });
        List<VideoMenuItem.Video> videos =
                VideoMenuItem.videosOf(album(video("21"), video("22")), ATTACHMENTS, MEDIA, SHARED, SUBATTACHMENTS);

        Thread chain = MediaDownload.savePost(screen, Collections.emptyList(), videos);
        assertNotNull(chain);
        try {
            assertTrue("the first save never got going", looking.await(20, TimeUnit.SECONDS));
            assertTrue(SaveControl.cancel(SaveControl.running().get(0).id));
        } finally {
            answer.countDown();
        }
        chain.join(20_000);
        waitForChains();

        assertEquals(Collections.singletonList("21"), savedVideos());
    }

    @Test
    public void aVideoWithNoFileIsPassedAndTheNextStillSaves() throws Exception {
        button(album(noFile("21"), video("22"))).invoke(null);
        waitForChains();
        idle();

        assertEquals(Collections.singletonList("22"), savedVideos());
        assertEquals(NO_FILE_MESSAGE, ShadowToast.getTextOfLatestToast());
    }
}
