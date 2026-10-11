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

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Looper;
import android.provider.MediaStore;
import android.service.notification.StatusBarNotification;

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
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.util.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * A saved video WhatsApp and some editors may refuse says so, with Save videos other apps can open
 * off: #11's failed file was AV1, and WhatsApp stopped at 40%. What decides it is the file's own
 * tracks, read back after the save, and the message points at the switch that avoids them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class RefusedFormatTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String REFUSED = "Saved, but WhatsApp and some editors may not accept it";
    private static final String NOWHERE_TO_TAP = "Saved, but WhatsApp may not accept it. Open Downloads in Hushfacebook settings to fix that.";

    private LocalServer server;
    private String origin;
    private Context context;
    private MediaSaveTest.Gallery gallery;
    private long row;

    @Before
    public void setUp() throws IOException {
        server = new LocalServer();
        origin = server.origin();
        context = RuntimeEnvironment.getApplication();
        gallery = Robolectric.setupContentProvider(MediaSaveTest.Gallery.class, MediaStore.AUTHORITY);
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
        MediaDownload.policyForTests = null;
        ShadowMediaExtractor.reset();
        Settings.DOWNLOAD_COMPATIBLE.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    private static MediaFormat picture(String mime) {
        return MediaFormat.createVideoFormat(mime, 1080, 1920);
    }

    private static MediaFormat sound(int aacType) {
        MediaFormat sound = MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2);
        sound.setInteger(MediaFormat.KEY_AAC_PROFILE, aacType);
        return sound;
    }

    private static final int LC = MediaCodecInfo.CodecProfileLevel.AACObjectLC;
    private static final int XHE = MediaCodecInfo.CodecProfileLevel.AACObjectXHE;

    /**
     * Saves one video whose file reads back as [formats], and answers the toast it ended with. The
     * policy lets the local server through and, as the address is checked, describes the save's
     * work file to Robolectric's extractor.
     */
    private String save(MediaFormat... formats) throws InterruptedException {
        row++;
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(row),
                new ByteArrayOutputStream());
        String path = "/clip" + row + ".mp4";
        server.serve(path, 200, "video/mp4", mp4(4096), 4096);
        int port = server.port();
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                for (File file : DashSave.workFolder(context).listFiles()) {
                    if (!file.getName().endsWith(".part")) continue;
                    for (MediaFormat format : formats) {
                        ShadowMediaExtractor.addTrack(DataSource.toDataSource(file.getPath()), format, new byte[1]);
                    }
                }
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        ShadowToast.reset();
        Thread worker = MediaDownload.start(context, true, MediaDownload.fileJob(context, origin + path,
                Downloader.Kind.VIDEO));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        byte[] head = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2' };
        System.arraycopy(head, 0, body, 0, head.length);
        return body;
    }

    private NotificationManager notifications() {
        return context.getSystemService(NotificationManager.class);
    }

    /** The finished card of a save the card says may be refused, or null. Fails when a save left two cards. */
    private Notification note() {
        Notification found = null;
        int cards = 0;
        for (StatusBarNotification up : notifications().getActiveNotifications()) {
            if (up.getTag() == null || !up.getTag().startsWith(SavedFileActions.TAG)) continue;
            Notification card = up.getNotification();
            if (card.actions != null && card.actions.length == 3) {
                found = card;
                cards++;
            }
        }
        assertTrue("more than one card says a save may be refused", cards <= 1);
        return found;
    }

    @Test
    public void av1Vp9HevcAndXheAacAreTheOnesOthersRefuse() {
        assertTrue(DashSave.othersMayRefuse("video/av01", null));
        assertTrue(DashSave.othersMayRefuse("video/x-vnd.on2.vp9", null));
        assertTrue(DashSave.othersMayRefuse("video/hevc", null));
        assertTrue(DashSave.othersMayRefuse("audio/mp4a-latm", XHE));

        assertFalse(DashSave.othersMayRefuse("video/avc", null));
        assertFalse(DashSave.othersMayRefuse("audio/mp4a-latm", LC));
        assertFalse(DashSave.othersMayRefuse("audio/mp4a-latm", MediaCodecInfo.CodecProfileLevel.AACObjectHE));
        assertFalse(DashSave.othersMayRefuse("audio/mp4a-latm", MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS));
        assertFalse("an AAC type the file doesn't declare is a guess", DashSave.othersMayRefuse("audio/mp4a-latm", null));
        assertFalse(DashSave.othersMayRefuse(null, XHE));
    }

    /**
     * #11's AV1 reel: the toast says WhatsApp and some editors may refuse it, and the save's one
     * card says where it went, keeps Open and Share, and has a third button that opens the
     * settings at the switch.
     */
    @Test
    public void anAv1SaveWithTheSwitchOffSaysSoAndLinksTheSwitch() throws Exception {
        assertFalse(Settings.DOWNLOAD_COMPATIBLE.get());
        assertEquals(REFUSED, save(picture("video/av01"), sound(LC)));

        Notification note = note();
        assertNotNull("no card with a button to the switch", note);
        assertEquals("one save, one card", 1, notifications().getActiveNotifications().length);
        assertEquals("WhatsApp and some editors may not accept this video",
                String.valueOf(note.extras.getCharSequence(Notification.EXTRA_TITLE)));
        String text = String.valueOf(note.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        assertTrue(text, text.startsWith("Saved to "));
        assertTrue(text, text.endsWith("\nTurn on \u2068Save videos other apps can open\u2069 to save videos that WhatsApp and these editors accept."));
        assertEquals("Open", String.valueOf(note.actions[0].title));
        assertEquals("Share", String.valueOf(note.actions[1].title));
        assertEquals("Open the setting", String.valueOf(note.actions[2].title));

        Intent open = Shadows.shadowOf(note.actions[2].actionIntent).getSavedIntent();
        assertTrue(Shadows.shadowOf(note.actions[2].actionIntent).isActivityIntent());
        assertTrue(open.getBooleanExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, false));
        assertEquals(Settings.DOWNLOAD_COMPATIBLE.key, open.getStringExtra(SettingsEntry.EXTRA_SHOW_SETTING));
        assertEquals("com.facebook.katana.LoginActivity", open.getComponent().getClassName());
        assertEquals(context.getPackageName(), open.getComponent().getPackageName());
        // A tap on the card itself opens the file, as on every finished card.
        assertTrue(Shadows.shadowOf(note.contentIntent).getSavedIntent()
                .filterEquals(Shadows.shadowOf(note.actions[0].actionIntent).getSavedIntent()));

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the saved file has a track WhatsApp and some editors refuse, with Save "
                + "videos other apps can open off"));
    }

    /** VP9 and HEVC pictures, and xHE-AAC sound under an H.264 picture, say the same. */
    @Test
    public void vp9HevcAndXheAacSavesSayItToo() throws Exception {
        assertEquals("VP9", REFUSED, save(picture("video/x-vnd.on2.vp9"), sound(LC)));
        assertEquals("HEVC", REFUSED, save(picture("video/hevc"), sound(LC)));
        assertEquals("xHE-AAC", REFUSED, save(picture("video/avc"), sound(XHE)));
        // A sound track past the four the report describes still counts.
        assertEquals("a fifth track", REFUSED, save(picture("video/avc"), sound(LC), sound(LC), sound(LC), sound(XHE)));
    }

    /** H.264 with AAC keeps today's message and leaves no note. */
    @Test
    public void anH264AacSaveKeepsTodaysMessage() throws Exception {
        String toast = save(picture("video/avc"), sound(LC));
        assertTrue(toast, toast.startsWith("Saved to ") && !toast.contains("WhatsApp"));
        toast = save(picture("video/avc"), sound(MediaCodecInfo.CodecProfileLevel.AACObjectHE));
        assertTrue(toast, toast.startsWith("Saved to ") && !toast.contains("WhatsApp"));
        assertNull("an H.264 save left a note", note());
        // A file the phone can't read back is no reason to warn.
        toast = save();
        assertTrue(toast, toast.startsWith("Saved to ") && !toast.contains("WhatsApp"));
        assertNull(note());
    }

    /** With the switch on, an AV1 file is one it couldn't avoid, and nothing changes. */
    @Test
    public void withTheSwitchOnNothingChanges() throws Exception {
        Settings.DOWNLOAD_COMPATIBLE.save(true);
        String toast = save(picture("video/av01"), sound(XHE));
        assertTrue(toast, toast.startsWith("Saved to ") && !toast.contains("WhatsApp"));
        assertNull("the switch is on and a note still asked to turn it on", note());
    }

    /** With Facebook's notifications off there's no button, so the toast says where the switch is. */
    @Test
    public void withoutNotificationsTheToastSaysWhereTheSwitchIs() throws Exception {
        Shadows.shadowOf(notifications()).setNotificationsEnabled(false);
        assertEquals(NOWHERE_TO_TAP, save(picture("video/av01"), sound(LC)));
        assertNull(note());
    }
}
