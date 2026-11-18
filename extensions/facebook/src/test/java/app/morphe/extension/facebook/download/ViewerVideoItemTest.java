/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;

import com.facebook.graphservice.tree.TreeJNI;

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

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The Download to phone row in the photo and video viewer's three-dot menu (#509): what opens from
 * a post with several things in it, whose menu Facebook leaves with just Report.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ViewerVideoItemTest {

    private static final String ROW = "Download to phone";
    private static final String VIDEO_ID = "1234567890123456";
    private static final String NO_FILE_MESSAGE = "Couldn't save this video. Play it for a moment, then try again.";

    /** The viewer's screen, as far as the row sees it: AndroidX's kept getContext. */
    public static final class Screen {
        private final Context context;

        Screen(Context context) {
            this.context = context;
        }

        public Context getContext() {
            return context;
        }
    }

    @Rule
    public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<PostDetails> saved = Collections.synchronizedList(new ArrayList<>());
    private Activity screen;

    @Before
    public void setUp() {
        screen = Robolectric.buildActivity(Activity.class).setup().get();
        Settings.DOWNLOAD_VIDEOS.save(true);
        MediaDownload.detailsForTests = saved::add;
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] {InetAddress.getByName("10.9.8.7")});
        ShadowToast.reset();
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() throws Exception {
        AlertDialog open = ShadowAlertDialog.getLatestAlertDialog();
        if (open != null && open.isShowing()) open.dismiss();
        idle();
        waitForSaves();
        MediaDownload.detailsForTests = null;
        MediaDownload.policyForTests = null;
        Settings.ASK_BEFORE_DOWNLOAD.resetToDefault();
        Settings.DOWNLOAD_ACTION.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (MediaDownload.savesInFlight() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
    }

    private Menu viewerMenu() {
        Menu menu = new PopupMenu(screen, new View(screen)).getMenu();
        menu.add(Menu.NONE, 1, Menu.NONE, "Report");
        return menu;
    }

    private static TreeJNI video() {
        return new TreeJNI("Video").with("id", VIDEO_ID)
            .with("playable_url", "https://video.xx.fbcdn.net/v/t42.1790-2/" + VIDEO_ID + ".mp4?_nc_cat=1");
    }

    private static MenuItem row(Menu menu, String title) {
        for (int i = 0; i < menu.size(); i++) {
            if (title.contentEquals(menu.getItem(i).getTitle())) return menu.getItem(i);
        }
        return null;
    }

    private void add(Menu menu, Object media) {
        ViewerVideoItem.add(menu, new Screen(screen), null, media, VideoMenuItemForTests.ICON);
    }

    private static void tap(Menu menu, MenuItem row) {
        assertNotNull("the viewer's menu has no row to tap", row);
        menu.performIdentifierAction(row.getItemId(), 0);
    }

    private List<String> savedVideos() {
        List<String> ids = new ArrayList<>();
        synchronized (saved) {
            for (PostDetails details : saved) ids.add(details.videoId);
        }
        return ids;
    }

    private static AlertDialog question() {
        idle();
        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        return latest != null && latest.isShowing() ? latest : null;
    }

    @Test
    public void aVideoInTheViewerGetsTheRowAndSaves() throws Exception {
        Menu menu = viewerMenu();
        add(menu, video());

        assertNotNull("the viewer's menu had only Report", row(menu, ROW));
        assertNotNull("Facebook's own row went away", row(menu, "Report"));

        tap(menu, row(menu, ROW));
        idle();
        waitForSaves();
        idle();
        assertEquals(List.of(VIDEO_ID), savedVideos());
    }

    @Test
    public void aPhotoInTheViewerGetsNoRow() {
        Menu menu = viewerMenu();
        add(menu, new TreeJNI("Photo").with("id", VIDEO_ID));
        assertNull("a photo has Facebook's Save photo", row(menu, ROW));
    }

    @Test
    public void theSwitchOffLeavesTheMenuAlone() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        Menu menu = viewerMenu();
        add(menu, video());
        assertEquals(1, menu.size());
    }

    @Test
    public void aReleasedItemIsNotRead() {
        TreeJNI released = video().releasedTree();
        Menu menu = viewerMenu();
        add(menu, released);
        assertEquals(1, menu.size());
        assertFalse("a released tree was read", released.readAfterRelease);
    }

    @Test
    public void askBeforeDownloadingAsksOverTheViewer() throws Exception {
        Settings.ASK_BEFORE_DOWNLOAD.save(true);
        Menu menu = viewerMenu();
        add(menu, video());

        tap(menu, row(menu, ROW));
        AlertDialog asked = question();
        assertNotNull("nothing asked first", asked);
        assertSame("the question has to sit on the viewer's screen", screen,
            QualityChooser.activityOf(asked.getContext()));
        assertTrue("it saved before the answer", saved.isEmpty());

        asked.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        waitForSaves();
        idle();
        assertEquals(List.of(VIDEO_ID), savedVideos());
    }

    @Test
    public void sendToAppSharesTheVideosLink() {
        Settings.DOWNLOAD_ACTION.save(SendLink.Action.SEND);
        Menu menu = viewerMenu();
        add(menu, video());

        assertNull(row(menu, ROW));
        tap(menu, row(menu, "Send to app"));
        idle();

        Intent chooser = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertNotNull("no app was offered the link", chooser);
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals("https://www.facebook.com/watch/?v=" + VIDEO_ID, send.getStringExtra(Intent.EXTRA_TEXT));
        assertTrue(saved.isEmpty());
    }

    @Test
    public void aVideoWithNoFileYetSaysSo() throws Exception {
        Menu menu = viewerMenu();
        add(menu, new TreeJNI("Video").with("id", VIDEO_ID));

        tap(menu, row(menu, ROW));
        idle();
        waitForSaves();
        idle();
        assertEquals(NO_FILE_MESSAGE, ShadowToast.getTextOfLatestToast());
        assertTrue(saved.isEmpty());
    }

    @Test
    public void noScreenStillGivesAWorkingRow() throws Exception {
        Menu menu = viewerMenu();
        ViewerVideoItem.add(menu, null, null, video(), 0);

        tap(menu, row(menu, ROW));
        idle();
        waitForSaves();
        idle();
        assertEquals(List.of(VIDEO_ID), savedVideos());
    }

    @Test
    public void aMenuThatThrowsStaysInsideTheHook() {
        Menu broken = (Menu) Proxy.newProxyInstance(Menu.class.getClassLoader(), new Class<?>[] {Menu.class},
            (proxy, method, args) -> {
                throw new IllegalStateException("menu went away");
            });
        add(broken, video());
    }
}
