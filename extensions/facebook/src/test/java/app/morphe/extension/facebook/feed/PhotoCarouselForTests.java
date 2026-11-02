/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

/**
 * Facebook's album layouts as 585's name table gives them (`LX/04Xj;->A0E`), for the Photo
 * carousel tests.
 */
final class PhotoCarouselForTests {
    private PhotoCarouselForTests() {
    }

    private static final String ALBUM = "com.facebook.feedplugins.attachments.album.";

    /** The three layouts a poster picks, asked before the swipeable row. */
    static final String[] POSTER_LAYOUT_NAMES = {
            ALBUM + "PhotoLayoutColumnsPlugin",
            ALBUM + "PhotoLayoutBannerPlugin",
            ALBUM + "PhotoLayoutFramePlugin",
    };

    /** The swipeable row. */
    static final String CAROUSEL = ALBUM + "MultiMediaAttachmentHScrollPlugin";

    /** The cover over sensitive photos, asked first. */
    static final String SENSITIVE = ALBUM + "ObjectionableContentCollageAttachmentPlugin";

    /** Facebook's grid, asked after the row. */
    static final String GRID = ALBUM + "CollageAttachmentPlugin";
}
