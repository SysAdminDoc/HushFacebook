/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.updates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Disable Play Store updates: a version code Android reports as the raised one comes back as the
 * real one, which the patch writes into originalVersionCode(). Unpatched that answers 0, which
 * stands in for the real code here. Any other version code, another app's or an unpatched
 * Facebook's, comes back as it is, and the long form keeps its major half.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class DisablePlayStoreUpdatesPatchTest {
    private static final int FACEBOOK_587 = 707414333;
    private static final long MAJOR = 5L << 32;

    private static PackageInfo withCode(long code) {
        PackageInfo info = new PackageInfo();
        info.setLongVersionCode(code);
        return info;
    }

    @Test
    public void theRaisedCodeReadsAsTheRealOne() {
        assertEquals(0, DisablePlayStoreUpdatesPatch.getVersionCode(withCode(Integer.MAX_VALUE)));
        assertEquals(0L, DisablePlayStoreUpdatesPatch.getVersionCodeLong(withCode(Integer.MAX_VALUE)));
        assertEquals("the major half was lost", MAJOR,
                DisablePlayStoreUpdatesPatch.getVersionCodeLong(withCode(MAJOR | Integer.MAX_VALUE)));
    }

    @Test
    public void everyOtherCodeReadsAsItIs() {
        assertEquals(FACEBOOK_587, DisablePlayStoreUpdatesPatch.getVersionCode(withCode(FACEBOOK_587)));
        assertEquals(FACEBOOK_587, DisablePlayStoreUpdatesPatch.getVersionCodeLong(withCode(FACEBOOK_587)));
        assertEquals(MAJOR | FACEBOOK_587, DisablePlayStoreUpdatesPatch.getVersionCodeLong(withCode(MAJOR | FACEBOOK_587)));
        assertEquals(Integer.MAX_VALUE - 1,
                DisablePlayStoreUpdatesPatch.getVersionCode(withCode(Integer.MAX_VALUE - 1)));
    }

    @Test
    public void thePatchFindsTheStubItFillsIn() throws Exception {
        Method stub = DisablePlayStoreUpdatesPatch.class.getDeclaredMethod("originalVersionCode");
        assertEquals(int.class, stub.getReturnType());
        assertTrue("the patch writes into a static method", Modifier.isStatic(stub.getModifiers()));
    }
}
