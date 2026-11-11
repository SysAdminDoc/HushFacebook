/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.util.Arrays;
import java.util.List;

/** The rules every save picks its tracks by, for a test outside this package. */
public final class SaveRulesForTests {
    private SaveRulesForTests() {
    }

    private static final String BASE = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/";

    /** A 1080p AV1 picture and a 720p H.264 one, with AAC-LC sound: issue #11's choice. */
    private static final List<DashManifest.Track> AV1_OR_H264 = Arrays.asList(
            new DashManifest.Track("video/mp4", "av01.0.08m.08", 1080, 1920, 1_525_000, BASE + "av1.mp4", 1080),
            new DashManifest.Track("video/mp4", "avc1.64001f", 720, 1280, 1_200_000, BASE + "h264.mp4", 720),
            new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 64_000, BASE + "lc.mp4"));

    /**
     * Whether a save starting now takes the H.264 picture over the sharper AV1 one, as saves other
     * apps can open does. The AV1 track counts as writable, as on a phone with an AV1 decoder.
     */
    public static boolean picksAFileOtherAppsOpen() {
        DashManifest.Pick pick = DashManifest.pick(AV1_OR_H264, true, MediaDownload.quality(),
                MediaDownload.compatibleSaves());
        return pick != null && DashManifest.isCompatibleVideo(pick.video);
    }

    /**
     * Whether a save starting now converts the 1080p AV1 picture rather than keep the 720p H.264
     * one, as convert to keep the full size does. Any picture counts as one the phone converts.
     */
    public static boolean convertsTheFullSize() {
        DashManifest.Pick pick = DashManifest.pick(AV1_OR_H264, true, MediaDownload.quality(),
                MediaDownload.compatibleSaves(), false, track -> true, MediaDownload.convertSaves());
        return pick != null && pick.transcodeVideo;
    }

    /** Whether a save starting now would finish without its Saved to toast, as quiet saves does. */
    public static boolean finishesQuietly() {
        return !MediaDownload.endToast(MediaDownload.quietSaves(), false, false, false);
    }

    /** Whether a reel's or a video's save would ask for the quality first, as Choose quality on each save does. */
    public static boolean asksForTheQuality() {
        return QualityChooser.on();
    }
}
