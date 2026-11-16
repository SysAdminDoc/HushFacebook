/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.stories.model;

/**
 * Stands in for Facebook's story card: the story log line finds the card by this class name and
 * its media type by an enum's names, since the getters' own names change with each build.
 */
public class StoryCard {
    /** Stands in for Facebook's media type, which keeps its names. */
    public enum Media { PHOTO, VIDEO, PLAIN_TEXT }

    private final Media media;

    public StoryCard(Media media) {
        this.media = media;
    }

    /** Facebook's media type getter. */
    public Media A0k() {
        return media;
    }

    /** A second getter of the same type that answers nothing, as Facebook's base card does. */
    public Media A0j() {
        return null;
    }
}
