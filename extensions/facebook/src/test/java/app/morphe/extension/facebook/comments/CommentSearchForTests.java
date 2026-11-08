/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.view.View;

/** Hands a comment sheet to Search comments the way Facebook's sheet hook does, for tests in other packages. */
public final class CommentSearchForTests {
    private CommentSearchForTests() {
    }

    public static void open(View sheet) {
        CommentSearch.sheetOpened(sheet);
    }

    public static boolean watching(View sheet) {
        return CommentSearch.watching(sheet);
    }
}
