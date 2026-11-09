/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.content.Context;
import android.content.pm.PackageManager;

import app.morphe.extension.shared.Utils;

/**
 * GmsCore support's answers to Play services' availability check and to androidx Credential
 * Manager's framework provider. The patch points every Play services name at microG-RE, so the
 * check passes only while microG-RE is installed. Without it Facebook carries on the way it does on
 * a phone with no Play services at all, instead of going on to ask the package manager for
 * microG-RE's version, which closed the signed-out login screen.
 */
public final class GmsCore {
    /** microG-RE's package. The patch leaves Hushfacebook's own classes alone, so this stays as written. */
    static final String PACKAGE = "app.revanced.android.gms";
    /** Play services' ConnectionResult.SUCCESS. */
    static final int SUCCESS = 0;
    /** Play services' ConnectionResult.SERVICE_MISSING. */
    static final int SERVICE_MISSING = 1;

    private GmsCore() {
    }

    /** Runs in place of Play services' availability check: success with microG-RE, missing without. */
    public static int availability(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return SUCCESS;
        } catch (PackageManager.NameNotFoundException missing) {
            return SERVICE_MISSING;
        }
    }

    /**
     * Runs first in androidx Credential Manager's framework provider. True while microG-RE is
     * installed: the provider then answers unavailable, because Android hands Google's requests to
     * Google's own Play services, and androidx falls back to the Play services provider the patch
     * points at microG-RE. False without microG-RE, so the provider answers for itself and a
     * password manager can still offer a saved login. The provider is handed no Context, so this
     * reads the application's, and answers false before the application has set it.
     */
    public static boolean signsInThroughMicroG() {
        return Utils.settingsReady() && availability(Utils.getContext()) == SUCCESS;
    }
}
