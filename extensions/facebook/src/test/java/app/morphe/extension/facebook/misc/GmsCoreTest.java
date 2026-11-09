/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.PackageInfo;

import app.morphe.extension.shared.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class GmsCoreTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @After public void removeMicroG() {
        shadowOf(context.getPackageManager()).removePackage(GmsCore.PACKAGE);
    }

    private void installMicroG() {
        PackageInfo microG = new PackageInfo();
        microG.packageName = GmsCore.PACKAGE;
        shadowOf(context.getPackageManager()).installPackage(microG);
    }

    @Test public void withMicroGTheCheckPassesSoFacebookUsesIt() {
        installMicroG();
        assertEquals("ConnectionResult.SUCCESS", 0, GmsCore.availability(context));
    }

    @Test public void withMicroGTheFrameworkProviderStepsAsideForIt() {
        installMicroG();
        assertTrue("sign-in goes to microG-RE's provider", GmsCore.signsInThroughMicroG());
    }

    @Test public void withoutMicroGTheFrameworkProviderAnswersForItself() {
        assertFalse("a password manager can still offer a saved login", GmsCore.signsInThroughMicroG());
    }

    @Test public void beforeTheApplicationHasAContextTheFrameworkProviderAnswersForItself() {
        installMicroG();
        SettingsContextRule.withoutContext(() -> assertFalse(GmsCore.signsInThroughMicroG()));
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
