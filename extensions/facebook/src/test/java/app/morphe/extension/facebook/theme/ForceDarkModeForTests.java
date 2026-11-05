/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

/** Force dark mode's answer, asked from tests in other packages with the patch in the build. */
public final class ForceDarkModeForTests {
    private ForceDarkModeForTests() {
    }

    /** Puts the patch in the build, or takes it out, for a test in another package; null asks the build again. */
    public static void inBuild(Boolean inBuild) {
        ForceDarkMode.inBuildForTests = inBuild;
    }

    /** Whether the controller's light answer comes back dark. */
    public static boolean forcesDark() {
        ForceDarkMode.inBuildForTests = true;
        try {
            return ForceDarkMode.answer(false);
        } finally {
            ForceDarkMode.inBuildForTests = null;
        }
    }
}
