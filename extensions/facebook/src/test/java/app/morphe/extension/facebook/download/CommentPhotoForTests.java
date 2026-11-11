/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import com.facebook.graphservice.tree.TreeJNI;

import java.util.Collections;

/**
 * The comment sheet's Save photo hook, for a test outside this package: one photo comment, handed
 * to the hook the way the patch hands it over.
 */
public final class CommentPhotoForTests {
    private CommentPhotoForTests() {
    }

    /** A comment holding one photo on Meta's CDN. */
    static TreeJNI photoComment() {
        TreeJNI image = new TreeJNI().with("uri", "https://scontent-mia3-1.xx.fbcdn.net/v/t39.30808-6/1_n.jpg?stp=dst-jpg_s2048x2048_tt6&oh=00_A&oe=6A1B2C3D")
            .number("width", 2048).number("height", 1536);
        TreeJNI attachment = new TreeJNI().with("media", new TreeJNI("Photo").with("id", "123").with("image", image));
        return new TreeJNI("Comment").with("attachments", Collections.singletonList(attachment));
    }

    /** Asks the hook about a photo comment and says whether it answered a row. */
    public static boolean addsARow() {
        return CommentPhoto.clickFor(photoComment()) != null;
    }
}
