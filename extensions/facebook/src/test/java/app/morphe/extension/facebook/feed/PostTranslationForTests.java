/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import java.io.IOException;

/** Keeps Translate posts off the network, for tests outside this package that run every hook. */
public final class PostTranslationForTests {
    private PostTranslationForTests() {
    }

    public static void noNetwork() {
        PostTranslation.detectorForTests(text -> null);
        PostTranslation.translatorForTests((text, target) -> {
            throw new IOException("no network in a test");
        });
    }
}
