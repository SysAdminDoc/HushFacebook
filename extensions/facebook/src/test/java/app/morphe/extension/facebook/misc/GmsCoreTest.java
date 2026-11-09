/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class GmsCoreTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @After public void removeMicroG() {
        shadowOf(context.getPackageManager()).removePackage(GmsCore.PACKAGE);
    }

    @Test public void withMicroGTheCheckPassesSoFacebookUsesIt() {
        PackageInfo microG = new PackageInfo();
        microG.packageName = GmsCore.PACKAGE;
        shadowOf(context.getPackageManager()).installPackage(microG);
        assertEquals("ConnectionResult.SUCCESS", 0, GmsCore.availability(context));
    }

    @Test public void withoutMicroGTheCheckSaysPlayServicesIsMissing() {
        assertEquals("ConnectionResult.SERVICE_MISSING", 1, GmsCore.availability(context));
    }

    @Test public void realPlayServicesAloneDoesNotCount() {
        PackageInfo google = new PackageInfo();
        google.packageName = "com.google.android.gms";
        shadowOf(context.getPackageManager()).installPackage(google);
        try {
            assertEquals("the reroute only talks to microG-RE", 1, GmsCore.availability(context));
        } finally {
            shadowOf(context.getPackageManager()).removePackage(google.packageName);
        }
    }
}
