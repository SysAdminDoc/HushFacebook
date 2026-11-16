/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

/** Keep feed position on return past its launch, for a test outside this package. */
public final class ReturnRefreshForTests {
    private ReturnRefreshForTests() {
    }

    /**
     * Takes this run's launch, so the next feed check is a return or a check inside the app. A
     * launch's checks always go to Facebook (#493), whatever the switch says.
     */
    public static void launched() {
        ReturnRefresh.launchedForTests(true);
    }
}
