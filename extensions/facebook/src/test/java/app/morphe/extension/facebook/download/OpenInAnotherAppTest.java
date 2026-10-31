/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Open in another app (#182): a second item in a video post's menu, added only while its switch
 * is on and Hushfacebook runs, that hands the video's best single file to Android's chooser as
 * a video, and says so in a toast when there is no file or no app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OpenInAnotherAppTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String ITEM = "Open in another app";

    private static final String SD = "https://video.xx.fbcdn.net/v/t42.1790-2/sd.mp4?_nc_cat=1";
    private static final String HD = "https://video.xx.fbcdn.net/v/t42.1790-2/hd.mp4?_nc_cat=1";

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Settings.OPEN_IN_ANOTHER_APP.save(true);
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        Settings.OPEN_IN_ANOTHER_APP.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.FEED_VIDEO_CAST_BUTTON.resetToDefault();
        Settings.REEL_CAST_BUTTON.resetToDefault();
        Settings.DOWNLOAD_REELS.resetToDefault();
        Shadows.shadowOf((Application) context).checkActivities(false);
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    private Menu filled(Object post) {
        Menu menu = VideoMenuItemForTests.facebooksMenu(context);
        VideoMenuItem.add(menu, new View(context), post, VideoMenuItemForTests.ICON,
                VideoMenuItemForTests.ATTACHMENTS, VideoMenuItemForTests.MEDIA, VideoMenuItemForTests.ATTACHED_STORY);
        return menu;
    }

    private static GraphQLStory post(String sd, String hd) {
        GraphQLMedia media = new GraphQLMedia("Video");
        media.with("id", "1234567890123456");
        if (sd != null) media.with("playable_url", sd);
        if (hd != null) media.with("hd_playable_url", hd);
        return new GraphQLStory(null, new GraphQLStoryAttachment(media));
    }

    private static MenuItem entry(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            if (ITEM.equals(String.valueOf(menu.getItem(i).getTitle()))) return menu.getItem(i);
        }
        return null;
    }

    private static void tap(Menu menu) {
        MenuItem item = entry(menu);
        assertNotNull("the item wasn't added", item);
        assertTrue(menu.performIdentifierAction(item.getItemId(), 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void switchOffAddsNothing() {
        Settings.OPEN_IN_ANOTHER_APP.save(false);
        assertEquals(2, filled(post(SD, null)).size());
    }

    @Test
    public void pausedBehavesLikeOff() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{HushfacebookPause.Reason.SWITCH,
                HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertEquals("paused by " + why, 2, filled(post(SD, null)).size());
        }
        PauseForTests.resume();
        assertEquals("running again", 3, filled(post(SD, null)).size());
    }

    @Test
    public void onAddsTheItemAloneWithoutTheDownloadSwitch() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        Menu menu = filled(post(SD, null));
        assertEquals(3, menu.size());
        assertNotNull(entry(menu));
        assertNull("Download to phone follows its own switch", menuTitled(menu, "Download to phone"));
    }

    @Test
    public void bothSwitchesAddBothItems() {
        Settings.DOWNLOAD_VIDEOS.save(true);
        Menu menu = filled(post(SD, null));
        assertEquals(4, menu.size());
        assertNotNull(menuTitled(menu, "Download to phone"));
        assertNotNull(entry(menu));
    }

    @Test
    public void aPostWithoutAVideoGetsNothing() {
        GraphQLMedia photo = new GraphQLMedia("Photo");
        photo.with("id", "3234567890123456");
        assertEquals(2, filled(new GraphQLStory(null, new GraphQLStoryAttachment(photo))).size());
        assertEquals(2, filled(new GraphQLStory(null)).size());
    }

    @Test
    public void aTapOpensTheChooserWithTheBestFileAsMp4() {
        tap(filled(post(SD, HD)));

        Intent started = Shadows.shadowOf((Application) context).getNextStartedActivity();
        assertNotNull("no chooser opened", started);
        assertEquals(Intent.ACTION_CHOOSER, started.getAction());
        Intent view = started.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(view);
        assertEquals(Intent.ACTION_VIEW, view.getAction());
        assertEquals("video/mp4", view.getType());
        assertEquals("the better file of the two", HD, view.getData().toString());
    }

    @Test
    public void theOnlyFileIsHandedOver() {
        tap(filled(post(SD, null)));

        Intent view = Shadows.shadowOf((Application) context).getNextStartedActivity()
                .getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(SD, view.getData().toString());
        assertEquals(OpenInAnotherApp.VIDEO_TYPE, view.getType());
    }

    @Test
    public void aVideoWithNoFileSaysSoAndOpensNothing() {
        tap(filled(post(null, null)));

        assertNull(Shadows.shadowOf((Application) context).getNextStartedActivity());
        assertEquals("Couldn't find a file for this video. Play it for a moment, then try again.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aFileOffMetasServersIsNotHandedOver() {
        tap(filled(post("https://example.com/clip.mp4", null)));

        assertNull(Shadows.shadowOf((Application) context).getNextStartedActivity());
        assertEquals(1, ShadowToast.shownToastCount());
    }

    @Test
    public void noAppToOpenItSaysSoWithoutThrowing() {
        Menu menu = filled(post(SD, null));
        Shadows.shadowOf((Application) context).checkActivities(true);

        tap(menu);

        assertEquals("No app on this phone can open this video", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aTapAfterTheSwitchWentOffDoesNothing() {
        Menu menu = filled(post(SD, null));
        Settings.OPEN_IN_ANOTHER_APP.save(false);

        tap(menu);

        assertNull(Shadows.shadowOf((Application) context).getNextStartedActivity());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    /** Records a player for [id] through the video patch's recorder, the way a feed video does. */
    private static void played(String id, String hd) {
        PlayerSources.rememberVideo(new PlayerSourcesForTests.Params(id,
                new PlayerSourcesForTests.HdSource(hd, null)), "videoId", "hd", "manifest");
    }

    private void cast(String videoId) {
        OpenInAnotherApp.cast(context, videoId);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void castButtonHandsOverTheFileItsPlayerRecordedWithDownloadOff() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        Settings.FEED_VIDEO_CAST_BUTTON.save(true);
        played("2718281828459045", HD);

        cast("2718281828459045");

        Intent started = Shadows.shadowOf((Application) context).getNextStartedActivity();
        assertNotNull("the cast button's switch alone records the player", started);
        Intent view = started.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(HD, view.getData().toString());
        assertEquals(OpenInAnotherApp.VIDEO_TYPE, view.getType());
    }

    @Test
    public void castButtonHandsOverOnlyThatVideosFile() {
        Settings.FEED_VIDEO_CAST_BUTTON.save(true);
        played("3141592653589793", HD);

        cast("1414213562373095");

        assertNull("another video's file never goes out", Shadows.shadowOf((Application) context).getNextStartedActivity());
        assertEquals("Couldn't find a file for this video. Play it for a moment, then try again.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void castButtonWithItsSwitchOffDoesNothingAndRecordsNothing() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        Settings.FEED_VIDEO_CAST_BUTTON.save(false);
        played("1618033988749894", HD);
        assertNull("both switches off, no player work", PlayerSources.byId("1618033988749894"));

        cast("1618033988749894");

        assertNull(Shadows.shadowOf((Application) context).getNextStartedActivity());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test
    public void castButtonPausedDoesNothing() {
        Settings.FEED_VIDEO_CAST_BUTTON.save(true);
        played("1732050807568877", HD);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);

        cast("1732050807568877");

        assertNull(Shadows.shadowOf((Application) context).getNextStartedActivity());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    private void castReel(String hd, String sd) {
        OpenInAnotherApp.castReel(context, "2236067977499789", hd, sd);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Issue #182: the reel Cast button hands over the better of the two files the reel's player holds. */
    @Test
    public void reelCastHandsOverTheBetterFileWithDownloadsOff() {
        Settings.DOWNLOAD_REELS.save(false);
        Settings.REEL_CAST_BUTTON.save(true);

        castReel(HD, SD);

        Intent started = Shadows.shadowOf((Application) context).getNextStartedActivity();
        assertNotNull("the reel Cast switch alone hands the reel over", started);
        Intent view = started.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(HD, view.getData().toString());
        assertEquals(OpenInAnotherApp.VIDEO_TYPE, view.getType());
    }

    @Test
    public void reelCastWithItsSwitchOffOrPausedDoesNothing() {
        Settings.REEL_CAST_BUTTON.save(false);
        castReel(HD, SD);
        Settings.REEL_CAST_BUTTON.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        castReel(HD, SD);

        assertNull(Shadows.shadowOf((Application) context).getNextStartedActivity());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test
    public void reelCastWithNoFileSaysSo() {
        Settings.REEL_CAST_BUTTON.save(true);

        castReel(null, "https://example.com/clip.mp4");

        assertNull(Shadows.shadowOf((Application) context).getNextStartedActivity());
        assertEquals("Couldn't find a file for this video. Play it for a moment, then try again.",
                ShadowToast.getTextOfLatestToast());
    }

    private static MenuItem menuTitled(Menu menu, String title) {
        for (int i = 0; i < menu.size(); i++) {
            if (title.equals(String.valueOf(menu.getItem(i).getTitle()))) return menu.getItem(i);
        }
        return null;
    }
}
