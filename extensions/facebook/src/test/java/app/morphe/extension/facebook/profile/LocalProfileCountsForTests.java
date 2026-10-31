/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.profile;

import android.os.Bundle;

import com.facebook.auth.viewercontext.ViewerContext;

import app.morphe.extension.facebook.settings.Settings;

/** The profile screen and a count line as the local profile counts hook sees them. */
public final class LocalProfileCountsForTests {
    /** The signed-in user's id in these tests. */
    public static final String VIEWER = "1000123456789";

    private LocalProfileCountsForTests() {
    }

    /** Stands in for the profile screen: arguments, a resumed state and a ViewerContext field. */
    public static final class Screen {
        private final Bundle arguments = new Bundle();
        private final boolean resumed;
        private final ViewerContext viewer;

        public Screen(Long profileId, boolean resumed, String viewerId) {
            if (profileId != null) arguments.putLong(LocalProfileCounts.PROFILE_ID_ARGUMENT, profileId);
            this.resumed = resumed;
            this.viewer = viewerId == null ? null : new ViewerContext(viewerId);
        }

        public Bundle getArguments() {
            return arguments;
        }

        public boolean isResumed() {
            return resumed;
        }
    }

    /** Your own profile on top, shown by an id equal to the viewer's. */
    public static Screen own() {
        return new Screen(Long.parseLong(VIEWER), true, VIEWER);
    }

    /**
     * A count line asked about the way the patched getter asks, with 1234 typed for friends. True
     * when your own profile showed the typed number, which is the switch changing what Facebook
     * would have drawn.
     */
    public static boolean swapsAFriendsCount() {
        String before = Settings.LOCAL_FRIENDS.savedValue();
        try {
            Settings.LOCAL_FRIENDS.save("1234");
            LocalProfileCounts.opened(own());
            String shown = LocalProfileCounts.text("812 friends");
            return !"812 friends".equals(shown) && shown.contains("friends");
        } finally {
            Settings.LOCAL_FRIENDS.save(before);
            LocalProfileCounts.forgetForTests();
        }
    }
}
