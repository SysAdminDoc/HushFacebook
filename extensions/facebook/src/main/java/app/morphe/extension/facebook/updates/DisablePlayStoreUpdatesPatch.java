/*
 * Modified for Hushfacebook (Facebook), 2026: moved into the Facebook extension's updates package,
 * so it ships in Hushfacebook's own extension instead of the change-version-code one, and the raw
 * version code is read through reflection.
 *
 * Forked from:
 * https://github.com/MorpheApp/morphe-patches/blob/1bcd0bcedf1238e71b0bd8815da2c10df7ef5bd2/extensions/all/change-version-code/src/main/java/app/morphe/extension/all/versioncode/DisablePlayStoreUpdatesPatch.java
 *
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2470
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

package app.morphe.extension.facebook.updates;

import android.content.pm.PackageInfo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@SuppressWarnings("unused")
public class DisablePlayStoreUpdatesPatch {

    // The raw version code is read through reflection, never as a versionCode field read or a
    // getLongVersionCode() call. Morphe's own Disable Play Store updates (2.4.0-dev.5 and newer)
    // sends every such read outside its own class through its helper, and this patch sends that
    // helper's reads here, so with both picked a direct read here made the two helpers call each
    // other until Facebook died of a StackOverflowError (#561).
    private static final Field VERSION_CODE;
    private static final Method LONG_VERSION_CODE;

    static {
        try {
            VERSION_CODE = PackageInfo.class.getField("versionCode");
            LONG_VERSION_CODE = PackageInfo.class.getMethod("getLongVersionCode");
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static int originalVersionCode() {
        return 0; // Return value is changed during patching.
    }

    private static int rawVersionCode(PackageInfo info) {
        try {
            return VERSION_CODE.getInt(info);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long rawLongVersionCode(PackageInfo info) {
        try {
            return (Long) LONG_VERSION_CODE.invoke(info);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        }
    }

    /**
     * Injection point.
     */
    public static int getVersionCode(PackageInfo info) {
        final int versionCode = rawVersionCode(info);

        if (versionCode == Integer.MAX_VALUE) {
            return originalVersionCode();
        }
        return versionCode;
    }

    /**
     * Injection point.
     */
    public static long getVersionCodeLong(PackageInfo info) {
        final long versionCode = rawLongVersionCode(info);

        final long lowerBitMask = 0x00000000FFFFFFFFL;
        if ((versionCode & lowerBitMask) == Integer.MAX_VALUE) {
            // Keep the upper 32 bits (versionCodeMajor) untouched,
            // swap in the original version code for the lower 32 bits.
            return (versionCode & 0xFFFFFFFF00000000L)
                    | (originalVersionCode() & lowerBitMask);
        }
        return versionCode;
    }
}
