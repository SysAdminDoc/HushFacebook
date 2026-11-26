/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

/** What tests outside this package need of Download first's rows: one put on top, and a clean slate. */
public final class MenuTopForTests {
    private MenuTopForTests() {
    }

    /** Remembers [row] as one Download first put on top of Facebook's post menu. */
    public static void putOnTop(Object row) {
        MenuTop.remember(row);
    }

    /** Forgets the rows put on top, as a new Facebook process would. */
    public static void forget() {
        MenuTop.forgetForTests();
    }
}
