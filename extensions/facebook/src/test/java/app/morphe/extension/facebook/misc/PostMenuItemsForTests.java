/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

/** Lets a test outside this package start Post menu items over. */
public final class PostMenuItemsForTests {
    private PostMenuItemsForTests() {
    }

    /** Forgets the kinds written down and the shapes read, so the next menu reads them again. */
    public static void forget() {
        PostMenuItems.forgetForTests();
    }
}
