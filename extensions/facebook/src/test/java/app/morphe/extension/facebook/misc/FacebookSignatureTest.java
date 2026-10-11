/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Process;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;

import java.security.MessageDigest;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Which packages Restore screens on re-signed builds answers for: the running app itself, under
 * Facebook's name or a clone's, and nothing else. Facebook reads these packages from
 * PackageManager, which fills in their ApplicationInfo.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FacebookSignatureTest {
    private static final int OTHER_UID = Process.myUid() + 1;

    @Before
    @After
    public void clearStatus() {
        HookStatus.clear();
    }

    private static PackageInfo installed(String packageName, int uid) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = packageName;
        app.uid = uid;
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = app;
        return info;
    }

    private static PackageInfo self(String packageName) {
        return installed(packageName, Process.myUid());
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.RESTORE_TRUST + ":")) return line;
        }
        return null;
    }

    private static void answers(PackageInfo info) throws Exception {
        List<Signature> signers = FacebookSignature.originalSigners(info);
        assertNotNull(info.packageName + " kept its re-signed signers", signers);
        assertEquals(1, signers.size());
        // Facebook's original certificate, whose SHA-1 the class states.
        byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(signers.get(0).toByteArray());
        StringBuilder hex = new StringBuilder();
        for (byte b : sha1) hex.append(String.format("%02x", b));
        assertEquals("8a3c4b26", hex.substring(0, 8));
        assertEquals("fa2b9", hex.substring(hex.length() - 5));
    }

    private static void keepsItsOwn(PackageInfo info) {
        assertNull((info == null ? "null" : info.packageName) + " got Facebook's certificate",
                FacebookSignature.originalSigners(info));
    }

    @Test
    public void facebookItselfGetsItsOriginalCertificate() throws Exception {
        answers(self("com.facebook.katana"));
        assertEquals(FamilyNames.RESTORE_TRUST + ": invoked 1, 0 found, 0 missing", statusLine());
    }

    /** Morphe's Clone app patch renames the package; the app is still this one (#16). */
    @Test
    public void aRenamedCloneOfThisAppGetsItToo() throws Exception {
        answers(self("com.facebook.katana.morphe"));
        answers(self("com.facebook.katana.hush"));
    }

    /**
     * Stock Facebook installed beside a clone has Facebook's name but its own uid. Its signers are
     * what the phone says they are, as are Messenger's and those of any name that only looks like it.
     */
    @Test
    public void anotherAppKeepsItsOwnSigners() {
        keepsItsOwn(installed("com.facebook.katana", OTHER_UID));
        keepsItsOwn(installed("com.facebook.orca", OTHER_UID));
        keepsItsOwn(installed("com.facebook.katanax", OTHER_UID));
        keepsItsOwn(installed("com.facebook.katana.evil", OTHER_UID));
        keepsItsOwn(installed("com.facebook.katana.morphe", OTHER_UID));
        assertNull("counted a package it didn't answer for", statusLine());
    }

    /** A package whose name disagrees with its own ApplicationInfo isn't known to be this app. */
    @Test
    public void aNameThatIsntItsApplicationsKeepsItsOwnSigners() {
        PackageInfo info = self("com.facebook.katana");
        info.packageName = "com.facebook.orca";
        keepsItsOwn(info);
    }

    @Test
    public void missingFactsKeepTheSystemsAnswer() {
        keepsItsOwn(null);
        PackageInfo noName = self("com.facebook.katana");
        noName.packageName = null;
        keepsItsOwn(noName);
        assertNull(statusLine());
    }

    /**
     * An isolated process, like the in-app browser's renderers or a service started from Facebook's
     * app zygote, runs under a uid of its own rather than the app's. There only the name is known,
     * and the answer stays the one builds before the clone fix gave. A clone can't be told apart
     * there, which is no worse than before.
     */
    @Test
    public void inAnIsolatedProcessOnlyFacebooksNameIsAnswered() throws Exception {
        int app = Process.myUid();
        try {
            ShadowProcess.setUid(99001);
            answers(installed("com.facebook.katana", app));
            keepsItsOwn(installed("com.facebook.katana.morphe", app));
            keepsItsOwn(installed("com.facebook.orca", OTHER_UID));
            keepsItsOwn(installed("com.facebook.katanax", app));
        } finally {
            ShadowProcess.setUid(app);
        }
    }

    /**
     * The push service's own read (#112): this app, under its name or a clone's, gets Facebook's
     * certificate in place of what the system read, and any other package keeps the system's array,
     * the very one it was handed.
     */
    @Test
    public void thePushServiceCheckGetsTheOriginalCertificateForThisAppOnly() throws Exception {
        Signature[] reported = {new Signature(new byte[] {1, 2, 3})};
        for (String name : new String[] {"com.facebook.katana", "com.facebook.katana.morphe"}) {
            Signature[] answered = FacebookSignature.fbnsSigners(self(name), reported);
            assertEquals(name, 1, answered.length);
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(answered[0].toByteArray());
            assertEquals(name, (byte) 0x8a, sha1[0]);
            assertEquals(name, (byte) 0x3c, sha1[1]);
        }
        for (PackageInfo other : new PackageInfo[] {
                installed("com.facebook.katana", OTHER_UID), installed("com.facebook.orca", OTHER_UID), null}) {
            assertSame(other == null ? "null" : other.packageName, reported, FacebookSignature.fbnsSigners(other, reported));
        }
        assertNull("a missing array stays missing", FacebookSignature.fbnsSigners(installed("com.facebook.orca", OTHER_UID), null));
    }

    /**
     * PackageManager always fills in ApplicationInfo. Without it only the name is known, and the
     * answer stays the one builds before the clone fix gave: Facebook's own name and nothing else.
     */
    @Test
    public void withoutApplicationInfoOnlyFacebooksNameIsAnswered() throws Exception {
        PackageInfo facebook = new PackageInfo();
        facebook.packageName = "com.facebook.katana";
        answers(facebook);
        for (String name : new String[] {"com.facebook.katana.morphe", "com.facebook.orca", "com.facebook.katanax"}) {
            PackageInfo other = new PackageInfo();
            other.packageName = name;
            keepsItsOwn(other);
        }
    }
}
