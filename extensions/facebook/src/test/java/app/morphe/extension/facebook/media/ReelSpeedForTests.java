/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** What tests outside this package need of Keep the reel speed: one pick and the next reel or video. */
public final class ReelSpeedForTests {
    private ReelSpeedForTests() { }

    /**
     * Picks 1.5x on a reel in the Reels viewer, then starts the next reel there. True when the next
     * reel got the picked speed. Leaves nothing kept behind.
     */
    public static boolean keepsAPickedSpeed() {
        ReelSpeed.forget();
        List<Float> set = new ArrayList<>();
        Map<Object, Object> params = new IdentityHashMap<>();
        ReelSpeed.access = new ReelSpeed.Player() {
            @Override
            public void setSpeed(Object player, float speed) {
                set.add(speed);
            }

            @Override
            public Object origin(Object player) {
                return "fb_shorts_viewer";
            }

            @Override
            public Object params(Object player) {
                return params.computeIfAbsent(player, p -> new Object());
            }

            @Override
            public boolean reel(Object videoParams) {
                return true;
            }

            @Override
            public boolean ad(Object videoParams) {
                return false;
            }

            @Override
            public boolean live(Object videoParams) {
                return false;
            }

            @Override
            public String videoId(Object videoParams) {
                return null;
            }

            @Override
            public float currentSpeed(Object player) {
                return ReelSpeed.NORMAL;
            }
        };
        try {
            ReelSpeed.speedSet(new Object(), 1.5f);
            ReelSpeed.picked(1.5f);
            ReelSpeed.started(new Object());
            return !set.isEmpty();
        } finally {
            ReelSpeed.forget();
        }
    }

    /**
     * Picks 1.5x in the gear menu of a feed video, then starts the next feed video. True when the next
     * video got the picked speed, which takes Keep the video speed on. Leaves nothing kept behind.
     */
    public static boolean keepsAPickedVideoSpeed() {
        ReelSpeed.forget();
        List<Float> set = new ArrayList<>();
        Map<Object, Object> params = new IdentityHashMap<>();
        ReelSpeed.access = new ReelSpeed.Player() {
            @Override
            public void setSpeed(Object player, float speed) {
                set.add(speed);
            }

            @Override
            public Object origin(Object player) {
                return "newsfeed";
            }

            @Override
            public Object params(Object player) {
                return params.computeIfAbsent(player, p -> new Object());
            }

            @Override
            public boolean reel(Object videoParams) {
                return false;
            }

            @Override
            public boolean ad(Object videoParams) {
                return false;
            }

            @Override
            public boolean live(Object videoParams) {
                return false;
            }

            @Override
            public String videoId(Object videoParams) {
                return null;
            }

            @Override
            public float currentSpeed(Object player) {
                return ReelSpeed.NORMAL;
            }
        };
        try {
            ReelSpeed.speedSet(new Object(), 1.5f);
            ReelSpeed.gearPicked(1.5f);
            ReelSpeed.started(new Object());
            return !set.isEmpty();
        } finally {
            ReelSpeed.forget();
        }
    }

    /** True when a Reels speed picker's list comes back with slower speeds ahead of Facebook's. */
    public static boolean offersSlowerSpeeds() {
        List<Float> facebooks = Arrays.asList(0.5f, 1f, 2f);
        return ReelSpeed.speedChoices(facebooks).size() > facebooks.size();
    }

    /** True when the gear menu's speed sheet reads its floats or gets slower speeds ahead of Facebook's. */
    public static boolean gearOffersSlowerSpeeds() {
        float[] facebooks = {0.5f, 1f, 2f};
        boolean values = ReelSpeed.gearValues(false);
        boolean longer = ReelSpeed.gearSpeeds(facebooks).length > facebooks.length;
        boolean labelled = ReelSpeed.gearLabels(new String[] {"0.5", "1", "2"}).length > facebooks.length;
        return values || longer || labelled;
    }
}
