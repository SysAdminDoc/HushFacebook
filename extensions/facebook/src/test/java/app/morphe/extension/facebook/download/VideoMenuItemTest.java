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

import android.content.Context;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.facebook.graphql.model.GraphQLMedia;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.model.GraphQLStoryAttachment;
import com.facebook.graphservice.tree.TreeJNI;
import com.facebook.video.engine.api.VideoDataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.media.ResumePlaybackForTests;
import app.morphe.extension.facebook.media.SkipSilenceForTests;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Download to phone in a post's menu: added below Facebook's own rows only for a post holding a
 * video, only while its switch is on and Hushfacebook runs, and a tap that saves through the one
 * pipeline every save uses or says it couldn't, without an address in the report.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class VideoMenuItemTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String ATTACHMENTS = VideoMenuItemForTests.ATTACHMENTS;
    private static final String MEDIA = VideoMenuItemForTests.MEDIA;
    private static final String ATTACHED_STORY = VideoMenuItemForTests.ATTACHED_STORY;
    private static final int ICON = VideoMenuItemForTests.ICON;

    /** A video file on Meta's CDN, named the way its files are: the middle number is an id. */
    private static final String CLIP = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/"
            + "449237416_1234567890123456_1234567890123456789_n.mp4?efg=x&oh=1&oe=2";

    /** The same video's 720p track, as the player's manifest lists it. */
    private static final String TRACK = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/"
            + "449237417_2234567890123456_2234567890123456789_n.mp4?efg=y&oh=1&oe=2";

    /** A picture on the same CDN, the kind of address a post also reaches: its thumbnail. */
    private static final String THUMBNAIL = "https://scontent-iad3-1.xx.fbcdn.net/v/t15.5256-10/"
            + "475148478_1134540631592283_1316146539584337463_n.jpg?_nc_cat=1&oh=00_AYA&oe=66F0A1B2";

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Settings.DOWNLOAD_VIDEOS.save(true);
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
        ShadowToast.reset();
        // Every Meta name answers a private address, so a save that starts is refused before a
        // socket opens, and the report says which route it took.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
    }

    @After
    public void tearDown() throws InterruptedException {
        waitForSaves();
        MediaDownload.policyForTests = null;
        MediaDownload.detailsForTests = null;
        PauseForTests.resume();
        SkipSilenceForTests.forget();
        Settings.SKIP_SILENT_PARTS.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.POST_MENU_DOWNLOAD_FIRST.resetToDefault();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaDownload.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    private Menu filled(Object post) {
        Menu menu = VideoMenuItemForTests.facebooksMenu(context);
        VideoMenuItem.add(menu, new View(context), post, ICON, ATTACHMENTS, MEDIA, ATTACHED_STORY, PhotoMenuItemForTests.SUBATTACHMENTS);
        return menu;
    }

    private static GraphQLStory post(GraphQLMedia media) {
        return new GraphQLStory(null, new GraphQLStoryAttachment(media));
    }

    private static GraphQLMedia video(String id) {
        GraphQLMedia media = new GraphQLMedia("Video");
        media.with("id", id);
        return media;
    }

    /** Taps the item the hook added, the last row, the way the menu dispatches a tap. */
    private static void tap(Menu menu) {
        MenuItem item = menu.getItem(menu.size() - 1);
        assertEquals("Download to phone", String.valueOf(item.getTitle()));
        assertTrue("the tap wasn't taken", menu.performIdentifierAction(item.getItemId(), 0));
    }

    private static String report() throws InterruptedException {
        waitForSaves();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return LogBufferManager.buildExportText();
    }

    // ---- The menu -------------------------------------------------------------------------------

    @Test
    public void aVideoPostGetsTheItemBelowFacebooksOwnRows() {
        Menu menu = filled(VideoMenuItemForTests.videoPost());

        assertEquals(3, menu.size());
        assertEquals("Facebook's Save row moved or changed", "Save video", String.valueOf(menu.getItem(0).getTitle()));
        assertEquals(1, menu.getItem(0).getItemId());
        assertEquals("Facebook's own Download row moved or changed", "Download video",
                String.valueOf(menu.getItem(1).getTitle()));
        assertEquals(2, menu.getItem(1).getItemId());
        assertEquals("Download to phone", String.valueOf(menu.getItem(2).getTitle()));
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().contains("Download any video: invoked 1, 1 found, 0 missing"));
    }

    /** Download first (#151): the item rides above Facebook's rows, which keep their order and ids. */
    @Test
    public void downloadFirstPutsTheItemOnTop() {
        Settings.POST_MENU_DOWNLOAD_FIRST.save(true);
        Menu menu = filled(VideoMenuItemForTests.videoPost());

        assertEquals(3, menu.size());
        assertEquals("Download to phone", String.valueOf(menu.getItem(0).getTitle()));
        assertEquals("Save video", String.valueOf(menu.getItem(1).getTitle()));
        assertEquals("Download video", String.valueOf(menu.getItem(2).getTitle()));

        Settings.POST_MENU_DOWNLOAD_FIRST.save(false);
        assertEquals("Download to phone", String.valueOf(filled(VideoMenuItemForTests.videoPost()).getItem(2).getTitle()));
    }

    /**
     * Facebook's own post menu sorts rows by their plain order number, where Android's top category
     * is a big number that sinks the row to the bottom (S22, 583, 2026-10-18). The row still goes on
     * top there, and below Facebook's rows with the switch off.
     */
    @Test
    public void downloadFirstTopsFacebooksOwnSortedMenu() {
        Settings.POST_MENU_DOWNLOAD_FIRST.save(true);
        Menu menu = VideoMenuItemForTests.facebooksSortedMenu();
        VideoMenuItem.add(menu, new View(context), VideoMenuItemForTests.videoPost(), ICON, ATTACHMENTS, MEDIA, ATTACHED_STORY, PhotoMenuItemForTests.SUBATTACHMENTS);

        assertEquals(3, menu.size());
        assertEquals("Download to phone", String.valueOf(menu.getItem(0).getTitle()));
        assertEquals("Save video", String.valueOf(menu.getItem(1).getTitle()));
        assertEquals("Download video", String.valueOf(menu.getItem(2).getTitle()));

        Settings.POST_MENU_DOWNLOAD_FIRST.save(false);
        Menu below = VideoMenuItemForTests.facebooksSortedMenu();
        VideoMenuItem.add(below, new View(context), VideoMenuItemForTests.videoPost(), ICON, ATTACHMENTS, MEDIA, ATTACHED_STORY, PhotoMenuItemForTests.SUBATTACHMENTS);
        assertEquals("Download to phone", String.valueOf(below.getItem(2).getTitle()));
    }

    /** A post with nothing to save gets no row on top either, and a pause keeps the menu Facebook's. */
    @Test
    public void downloadFirstAddsNothingWithNothingToSaveOrWhenPaused() {
        Settings.POST_MENU_DOWNLOAD_FIRST.save(true);
        assertEquals(2, filled(new GraphQLStory(null)).size());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(2, filled(VideoMenuItemForTests.videoPost()).size());
    }

    @Test
    public void offOrPausedTheMenuIsFacebooksOwn() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        assertEquals("the switch is off", 2, filled(VideoMenuItemForTests.videoPost()).size());

        Settings.DOWNLOAD_VIDEOS.save(true);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{HushfacebookPause.Reason.SWITCH,
                HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertEquals("paused by " + why, 2, filled(VideoMenuItemForTests.videoPost()).size());
        }
        PauseForTests.resume();
        assertEquals("running again", 3, filled(VideoMenuItemForTests.videoPost()).size());
    }

    /** Skip silent parts (#144): its row goes below Facebook's, and a tap keeps that video's silent parts. */
    @Test
    public void skipSilentPartsAddsItsRowBelowTheDownloadItem() {
        Settings.SKIP_SILENT_PARTS.save(true);
        SkipSilenceForTests.playing("1234567890123456");
        Menu menu = filled(VideoMenuItemForTests.videoPost());

        assertEquals(4, menu.size());
        assertEquals("Download to phone", String.valueOf(menu.getItem(2).getTitle()));
        assertEquals("Skip silent parts: on", String.valueOf(menu.getItem(3).getTitle()));
    }

    /** Alone, the row is the menu's only one without an id, so a tap by id reaches it. */
    @Test
    public void skipSilentPartsAloneGetsItsRowAndNoDownloadCount() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        Settings.SKIP_SILENT_PARTS.save(true);
        assertEquals("a row before any video started", 2, filled(VideoMenuItemForTests.videoPost()).size());

        ResumePlaybackForTests.Player player = SkipSilenceForTests.playing("1234567890123456");
        Menu menu = filled(VideoMenuItemForTests.videoPost());
        assertEquals(3, menu.size());
        MenuItem row = menu.getItem(2);
        assertEquals("Skip silent parts: on", String.valueOf(row.getTitle()));
        assertTrue("the tap wasn't taken", menu.performIdentifierAction(row.getItemId(), 0));
        assertEquals("the playing video wasn't sought in place", Collections.singletonList("BY_PLAYER@12000"), player.seeks);
        assertEquals("Skip silent parts: off for this video",
                String.valueOf(filled(VideoMenuItemForTests.videoPost()).getItem(2).getTitle()));
        assertEquals("a post without a video got the row", 2, filled(new GraphQLStory(null)).size());
        for (String line : HookStatus.report()) {
            assertFalse("Download any video counted a menu it added nothing to: " + line,
                    line.startsWith("Download any video:"));
        }

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused, the menu got the row", 2, filled(VideoMenuItemForTests.videoPost()).size());
    }

    @Test
    public void aPostWithoutAVideoKeepsFacebooksMenu() {
        // A photo post: its media is a Photo and names no video file.
        GraphQLMedia photo = new GraphQLMedia("Photo");
        photo.with("id", "3234567890123456");
        assertEquals(2, filled(post(photo)).size());

        // A post with no attachment, a post with an empty attachment, and something that isn't a post.
        assertEquals(2, filled(new GraphQLStory(null)).size());
        assertEquals(2, filled(new GraphQLStory(null, new GraphQLStoryAttachment(null))).size());
        assertEquals(2, filled(new Object()).size());
        assertEquals(2, filled(null).size());
        assertTrue("an ordinary post is not a miss", String.join("\n", HookStatus.report()).contains("0 missing"));
    }

    /** A share carries the video in the post it shares, and Facebook's own menu looks there. */
    @Test
    public void aSharedVideoIsFoundInThePostItShares() {
        GraphQLStory shared = VideoMenuItemForTests.videoPost();
        GraphQLStory share = new GraphQLStory(shared);
        assertEquals(3, filled(share).size());
        assertEquals(3, filled(new GraphQLStory(share)).size());
    }

    /** A menu built for one attachment of a post rather than for the post. */
    @Test
    public void anAttachmentsMenuFindsItsVideo() {
        assertEquals(3, filled(new GraphQLStoryAttachment(video("4234567890123456"))).size());
    }

    /**
     * The media says it's a video even when nothing to save is known yet, and the item is still
     * offered: the tap then says what to do. A model whose tree is gone isn't asked its type.
     */
    @Test
    public void aVideoTheMediaNamesGetsTheItemAndAReleasedOneIsNotAsked() {
        assertEquals(3, filled(post(video("5234567890123456"))).size());
        GraphQLMedia released = video("6234567890123456");
        released.released();
        assertEquals("a released model was taken for a video", 2, filled(post(released)).size());
        assertFalse("the type of a released model was read", released.readAfterRelease);
    }

    /** A getter this build doesn't have is a patch that went wrong, so it's a miss with a name. */
    @Test
    public void aGetterThisBuildMovedIsAMissNamedInHookStatus() {
        Menu menu = VideoMenuItemForTests.facebooksMenu(context);
        VideoMenuItem.add(menu, new View(context), VideoMenuItemForTests.videoPost(), ICON, "A9z", MEDIA,
                ATTACHED_STORY, PhotoMenuItemForTests.SUBATTACHMENTS);

        assertEquals(2, menu.size());
        assertEquals(java.util.Collections.singletonList("Download any video: invoked 1, 0 found, 1 missing. "
                + "First missing: method " + GraphQLStory.class.getName() + "#A9z"), HookStatus.report());
    }

    /** A menu that throws on add is Facebook's problem, never a crash from the hook. */
    @Test
    public void aMenuThatRefusesTheItemIsReportedAndLeftAlone() {
        Menu refusing = (Menu) java.lang.reflect.Proxy.newProxyInstance(Menu.class.getClassLoader(),
                new Class<?>[]{Menu.class}, (proxy, method, args) -> {
                    if (method.getName().equals("add")) throw new IllegalStateException("menu closed");
                    return null;
                });
        VideoMenuItem.add(refusing, new View(context), VideoMenuItemForTests.videoPost(), ICON, ATTACHMENTS, MEDIA,
                ATTACHED_STORY, PhotoMenuItemForTests.SUBATTACHMENTS);

        assertTrue(LogBufferManager.buildExportText().contains("could not add Download to phone to a post's menu"));
    }

    // ---- A tap ----------------------------------------------------------------------------------

    /**
     * No player was recorded and the post names no file: nothing is fetched, the person is told
     * what to do, and the report says why without naming anything of the video.
     */
    @Test
    public void aTapWithNothingToSaveSaysSo() throws Exception {
        Menu menu = filled(post(video("7234567890123456")));
        tap(menu);

        String report = report();
        assertEquals("Couldn't save this video. Play it for a moment, then try again.",
                ShadowToast.getTextOfLatestToast());
        assertTrue(report, report.contains("Download to phone tapped"));
        assertTrue(report, report.contains("nothing to save: no player of this video was recorded and the post names no file"));
        assertFalse(report, report.contains("7234567890123456"));
    }

    /**
     * While links are sent (#41), the item is Send to app, and a tap hands the video's watch link
     * to another app instead of saving it. A video naming no id says it has no link.
     */
    @Test
    public void whileLinksAreSentTheItemSendsTheVideosLink() throws Exception {
        Settings.DOWNLOAD_ACTION.save(SendLink.Action.SEND);
        try {
            Menu menu = filled(VideoMenuItemForTests.videoPost());
            assertEquals(3, menu.size());
            MenuItem item = menu.getItem(2);
            assertEquals("Send to app", String.valueOf(item.getTitle()));
            assertTrue(menu.performIdentifierAction(item.getItemId(), 0));

            android.content.Intent chooser = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.getAction());
            android.content.Intent send = chooser.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
            assertEquals("https://www.facebook.com/watch/?v=1234567890123456",
                    send.getStringExtra(android.content.Intent.EXTRA_TEXT));
            assertEquals("a sent video started a save", 0, MediaDownload.savesInFlight());
            String report = report();
            assertTrue(report, report.contains("Send to app tapped"));
            assertFalse(report, report.contains("Download to phone tapped"));

            GraphQLMedia noId = new GraphQLMedia("Video");
            noId.with("playable_url", CLIP);
            Menu without = filled(post(noId));
            assertTrue(without.performIdentifierAction(without.getItem(without.size() - 1).getItemId(), 0));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Couldn't find this video's link", ShadowToast.getTextOfLatestToast());
        } finally {
            Settings.DOWNLOAD_ACTION.resetToDefault();
        }
    }

    /**
     * The player of the same video recorded its manifest, which lists a better track than the
     * post's single file: the save takes the manifest, through the same policy every save uses,
     * which refuses it here. It falls back to the single file, refused the same way. Neither an
     * address, a file name nor an id reaches the report.
     */
    @Test
    public void aTapTakesThePlayersManifestFirstAndNamesNoAddress() throws Exception {
        String id = "8234567890123456";
        String manifest = "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">"
                + "<Representation codecs=\"avc1.64001f\" width=\"1280\" height=\"720\" bandwidth=\"900000\">"
                + "<BaseURL>" + TRACK.replace("&", "&amp;") + "</BaseURL></Representation>"
                + "</AdaptationSet></Period></MPD>";
        PlayerSources.rememberVideo(new Params(id, new Source(null, manifest)), "videoId", "hd", "manifest");
        GraphQLMedia media = video(id);
        media.with("playable_url", CLIP);

        tap(filled(post(media)));

        String report = report();
        assertTrue(report, report.contains("saving the video from its DASH manifest"));
        assertTrue(report, report.contains("the DASH save ended with NETWORK_ERROR"));
        assertNoAddressIn(report);
    }

    /** No player, two single files named by the post: the better one, and only as a video. */
    @Test
    public void aTapWithOnlyThePostsFilesSavesTheBetterOne() throws Exception {
        GraphQLMedia media = video("9234567890123456");
        media.with("playable_url", CLIP).with("hd_playable_url", TRACK);

        tap(filled(post(media)));

        String report = report();
        assertTrue(report, report.contains("saving video mp4 ("));
        assertTrue(report, report.contains("from 2 candidate(s)"));
        assertTrue(report, report.contains("save finished: NETWORK_ERROR"));
        assertNoAddressIn(report);
    }

    /** A post whose only reachable address is a picture never saves the picture from this item. */
    @Test
    public void aThumbnailIsNeverSavedInPlaceOfTheVideo() throws Exception {
        GraphQLMedia media = video("1034567890123456");
        media.with("playable_url", THUMBNAIL);

        tap(filled(post(media)));

        String report = report();
        assertTrue(report, report.contains("nothing to save: none of the 1 addresses was a video file"));
        assertFalse(report, report.contains("saving image"));
        assertEquals("Couldn't save this video. Play it for a moment, then try again.",
                ShadowToast.getTextOfLatestToast());
        assertNoAddressIn(report);
    }

    /**
     * A tap while saves hold every slot says so and starts nothing, instead of the missing-file
     * message a refused start used to fall through to.
     */
    @Test
    public void aTapWhileEverySlotIsTakenSaysWhy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> held = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                CountDownLatch entered = new CountDownLatch(1);
                Thread worker = MediaDownload.start(context, false, (writer, progress) -> {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                    return Downloader.Result.fail(Downloader.Status.CANCELLED, "test job ended");
                });
                assertNotNull("a holding save didn't start", worker);
                held.add(worker);
                assertTrue("a holding save never ran", entered.await(10, TimeUnit.SECONDS));
            }
            assertEquals(3, MediaDownload.savesInFlight());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ShadowToast.reset();

            tap(filled(VideoMenuItemForTests.videoPost()));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("Too many saves at once. Try again when one finishes.", ShadowToast.getTextOfLatestToast());
            assertEquals("the tap took a fourth slot", 3, MediaDownload.savesInFlight());
        } finally {
            release.countDown();
            for (Thread worker : held) worker.join(10_000);
        }
        String report = report();
        assertTrue(report, report.contains("a tap found every save slot taken, so nothing started"));
        assertFalse(report, report.contains("saving video"));
        assertFalse(report, report.contains("too many saves at once"));
    }

    /** A tap after the switch went off, from a menu still open, saves nothing and says nothing. */
    @Test
    public void aTapAfterTheSwitchWentOffSavesNothing() throws Exception {
        Menu menu = filled(VideoMenuItemForTests.videoPost());
        Settings.DOWNLOAD_VIDEOS.save(false);
        tap(menu);

        String report = report();
        assertTrue(report, report.contains("Download to phone tapped after its switch went off"));
        assertFalse(report, report.contains("saving"));
        assertNull("a tap on a switched-off item showed a message", ShadowToast.getTextOfLatestToast());
    }

    /**
     * The item hands the save who posted the video and when, read off the video's owner and the
     * post's actors and creation time by the hashes Facebook's own getters use. A share is named
     * after the post it shares, whose video it is, not after the sharer. The report names nobody.
     */
    @Test
    public void aTapNamesTheVideoAfterItsPosterAndTheDayItWentUp() throws Exception {
        List<PostDetails> handed = new ArrayList<>();
        MediaDownload.detailsForTests = details -> {
            synchronized (handed) {
                handed.add(details);
            }
        };
        long seconds = 1_756_728_000L;

        GraphQLMedia media = video("2134567890123456");
        media.with("playable_url", CLIP).with("owner", new TreeJNI().with("name", "Page Owner"));
        GraphQLStory post = post(media);
        post.with("actors", Collections.singletonList(new TreeJNI().with("name", "Post Actor"))).with("creation_time", seconds);
        tap(filled(post));
        report();

        GraphQLMedia original = video("3134567890123456");
        original.with("playable_url", CLIP);
        GraphQLStory shared = post(original);
        shared.with("actors", Collections.singletonList(new TreeJNI().with("name", "Original Poster")))
                .with("creation_time", seconds);
        GraphQLStory share = new GraphQLStory(shared);
        share.with("actors", Collections.singletonList(new TreeJNI().with("name", "Sharer"))).with("creation_time", seconds + 86_400);
        tap(filled(share));
        String report = report();

        assertEquals(2, handed.size());
        assertEquals("2134567890123456", handed.get(0).videoId);
        assertEquals("Page Owner", handed.get(0).owner);
        assertEquals(new Date(seconds * 1000), handed.get(0).posted);
        assertEquals("3134567890123456", handed.get(1).videoId);
        assertEquals("Original Poster", handed.get(1).owner);
        assertEquals(new Date(seconds * 1000), handed.get(1).posted);
        assertFalse(report, report.contains("Page Owner") || report.contains("Original Poster") || report.contains("Sharer"));
        assertNoAddressIn(report);
    }

    private static void assertNoAddressIn(String report) {
        assertFalse(report, report.contains("https://"));
        assertFalse(report, report.contains("fbcdn"));
        assertFalse(report, report.contains("_n.mp4") || report.contains("_n.jpg"));
        assertFalse(report, Pattern.compile("\\d{15}").matcher(report).find());
    }

    /** A player's params: its video's id and its source, named as the patch passes them. */
    static final class Params {
        final String videoId;
        final VideoDataSource source;

        Params(String videoId, VideoDataSource source) {
            this.videoId = videoId;
            this.source = source;
        }
    }

    static final class Source extends VideoDataSource {
        final String hd;
        final String manifest;

        Source(String hd, String manifest) {
            this.hd = hd;
            this.manifest = manifest;
        }
    }
}
