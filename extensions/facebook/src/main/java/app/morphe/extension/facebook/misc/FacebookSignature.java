/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/FacebookSignature.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.misc;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Process;
import android.util.Base64;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.coexist.FamilySignatureTrust;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Facebook "[Fix] Restore screens on re-signed builds" patch.
 *
 * <p>Facebook's security code compares the signing certificate of a package with a table of Meta
 * certificates, and it does this for its own package too. A re-signed build has a different
 * certificate, so Facebook does not trust itself and drops a tap on some screens without a message.
 * The patch gives the check the original certificate of Facebook when the package is this app.
 *
 * <p>The name alone doesn't say that. Morphe's Clone app patch renames the package, and the stock
 * Facebook it was cloned from can stay installed beside it (#16). The uid does: Android gives every
 * installed app its own (Facebook's manifest shares it with no other), and the process has it before
 * any code runs, so the check needs no context even while content providers start.
 *
 * <p>The same reader gives Facebook a caller's signers when it builds the caller's identity for a
 * guarded component. On a re-signed build a Meta family app the user patched with the same key is
 * answered Meta's certificate here too, so Facebook's caller rules judge it exactly as the Meta-signed
 * app. {@link FamilySignatureTrust} makes that decision, and does it whenever this patch runs: the
 * security boundary is the same-key check this reader already carries, so a same-key Messenger,
 * Messenger Lite or Facebook Lite gets it whether or not Install beside Meta's apps is picked too.
 */
public final class FacebookSignature {

    private FacebookSignature() {}

    private static final String PACKAGE = "com.facebook.katana";

    /** The original signing certificate of Facebook, DER in Base64. Its SHA-1 is 8a3c4b26…fa2b9. */
    private static final String CERTIFICATE =
        "MIICaDCCAdECBEqcRhAwDQYJKoZIhvcNAQEEBQAwejELMAkGA1UEBhMCVVMxCzAJBgNVBAgTAkNBMRIwEAYDVQQH"
        + "EwlQYWxvIEFsdG8xGDAWBgNVBAoTD0ZhY2Vib29rIE1vYmlsZTERMA8GA1UECxMIRmFjZWJvb2sxHTAbBgNVBAMT"
        + "FEZhY2Vib29rIENvcnBvcmF0aW9uMCAXDTA5MDgzMTIxNTIxNloYDzIwNTAwOTI1MjE1MjE2WjB6MQswCQYDVQQG"
        + "EwJVUzELMAkGA1UECBMCQ0ExEjAQBgNVBAcTCVBhbG8gQWx0bzEYMBYGA1UEChMPRmFjZWJvb2sgTW9iaWxlMREw"
        + "DwYDVQQLEwhGYWNlYm9vazEdMBsGA1UEAxMURmFjZWJvb2sgQ29ycG9yYXRpb24wgZ8wDQYJKoZIhvcNAQEBBQAD"
        + "gY0AMIGJAoGBAMIH1R3464yX2TugyMEALJKPqwDcG0L8peZumcwwI+0tIU2CK8WejjXdz19Ex66K3lDX4MQ09QDm"
        + "wTH0ooNPmH/EZAYRXeIBjruw1aPCYb2XWBzP73avxxNabVnohV7NfqzI+HN+eUxgp2HFNrcrEfrI5gP12hotVKoQ"
        + "O4oTwNvBAgMBAAEwDQYJKoZIhvcNAQEEBQADgYEAXum+i8uyUGSNO3QSkKgqHJ3C52oK8vIijx2fnEAHUpxEanAX"
        + "XFqQDVFBgShm20a+ZVniFBYWSDmYIR9KZzFJ+yIyoQ0kdmOyapAx4V+EvBx00UH/mKAtdvhbLIqyVxtkabIy2Odo"
        + "p/fKBPer5Kd1YVkWwHlAZWtYcXRXtCvZKKI=";

    private static volatile List<Signature> original;

    /**
     * The original signers of Facebook if [info] is this app, or a Meta family app the user patched
     * with this build's key that is calling in, or {@code null} to keep the signers the system
     * reports.
     */
    public static List<Signature> originalSigners(PackageInfo info) {
        if (isThisApp(info)) {
            // Counted when it answers for this app. The name is a compile-time constant: this can run
            // while content providers start, before Hushfacebook has a context, and Hook status reads
            // no setting.
            HookStatus.invoked(FamilyNames.RESTORE_TRUST);
            return meta();
        }

        // A Meta family app re-signed with this build's key, calling a guarded component over Binder:
        // answer Meta's certificate for it too, so Facebook's caller checks judge it as the Meta-signed
        // app. This runs on any build carrying this patch, only on a re-signed build and only for the
        // app the current IPC comes from. FamilySignatureTrust counts it under this same patch and
        // takes Facebook's own path for anyone else.
        if (FamilySignatureTrust.isSameKeyFamilyCaller(info)) {
            return meta();
        }
        return null;
    }

    /**
     * The signers Facebook's push service package check hashes: Facebook's original certificate
     * when [info] is this app, and [reported], what the system read, for any other package. The
     * check reads {@code PackageInfo.signatures} itself rather than through the method
     * {@link #originalSigners} answers for. On a re-signed build it failed for Facebook's own
     * package, and the push service stopped and was started again in a loop that left threads
     * running each round (#112, HushThreads #6). It runs in Facebook's {@code :notification}
     * process, so it counts nothing in a report the main process writes.
     */
    public static Signature[] fbnsSigners(PackageInfo info, Signature[] reported) {
        if (!isThisApp(info)) return reported;
        return meta().toArray(new Signature[0]);
    }

    /** Facebook's original certificate, read once. */
    private static List<Signature> meta() {
        List<Signature> signers = original;
        if (signers == null) {
            signers = Collections.singletonList(new Signature(Base64.decode(CERTIFICATE, Base64.DEFAULT)));
            original = signers;
        }
        return signers;
    }

    /**
     * Whether [info] describes the running app, under Facebook's name or a clone's. A package with
     * another uid is another app, whatever its name. PackageManager always fills in the
     * ApplicationInfo; without one only the name is known, and the answer stays the one builds
     * before the clone fix gave. The same goes for an isolated process, like the browser's
     * renderers or a service from Facebook's app zygote: it runs under a uid of its own, not the
     * app's, so a clone can't be told apart there.
     */
    private static boolean isThisApp(PackageInfo info) {
        if (info == null || info.packageName == null) return false;
        ApplicationInfo app = info.applicationInfo;
        if (app == null || Process.isIsolated()) return PACKAGE.equals(info.packageName);
        return app.uid == Process.myUid() && info.packageName.equals(app.packageName);
    }
}
