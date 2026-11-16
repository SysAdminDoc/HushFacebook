/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

/** Reaches {@link ProgressBar}'s test seams from other packages' tests. */
public final class ProgressBarForTests {
    private ProgressBarForTests() {
    }

    /**
     * Sends the bar's seek on a live video to the player's hook, with Facebook's live latency setter
     * in its place, and answers whether the video was marked rewound.
     */
    public static boolean rewindsForTheBarsSeek() {
        boolean[] rewound = {false};
        ProgressBar.forgetLiveSeeksForTests();
        ProgressBar.liveRewoundForTests = (player, value) -> rewound[0] = value;
        try {
            ProgressBar.liveBarSeek(FrameStepForTests.Trigger.BY_SEEKBAR_CONTROLLER, new Object());
        } finally {
            ProgressBar.liveRewoundForTests = null;
        }
        return rewound[0];
    }
}
