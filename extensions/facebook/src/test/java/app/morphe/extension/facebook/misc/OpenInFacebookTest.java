/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * Open in Facebook (#111): shared text with a Facebook address in it opens that address through
 * Facebook's link handler, and anything else says there was no link. The screen never stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OpenInFacebookTest {
    private static Intent shared(String text) {
        return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
    }

    @Test
    public void theFirstFacebookAddressInSharedTextIsTheLink() {
        assertEquals("https://www.facebook.com/share/p/1AbC/",
                OpenInFacebook.link(shared("Look at this https://www.facebook.com/share/p/1AbC/.")));
        assertEquals("https://m.facebook.com/story.php?story_fbid=12&id=34",
                OpenInFacebook.link(shared("(m.facebook.com/story.php?story_fbid=12&id=34)")));
        assertEquals("https://fb.watch/abc123",
                OpenInFacebook.link(shared("HTTP://fb.watch/abc123 and https://www.facebook.com/later")));
        assertEquals("https://facebook.com", OpenInFacebook.link(shared("facebook.com")));
    }

    @Test
    public void anAddressInsideAnotherSitesIsNotOne() {
        assertNull(OpenInFacebook.link(shared("https://example.com/facebook.com/page")));
        assertNull(OpenInFacebook.link(shared("https://facebook.com.example.net/page")));
        assertNull(OpenInFacebook.link(shared("https://notfacebook.com/page")));
        assertNull(OpenInFacebook.link(shared("write to me@facebook.com")));
        assertNull(OpenInFacebook.link(shared("just words")));
        assertNull(OpenInFacebook.link(null));
    }

    @Test
    public void theSubjectAndTheDataAreReadWhenTheTextHasNoLink() {
        Intent subject = shared("A post").putExtra(Intent.EXTRA_SUBJECT, "https://www.facebook.com/groups/1/posts/2");
        assertEquals("https://www.facebook.com/groups/1/posts/2", OpenInFacebook.link(subject));
        Intent data = new Intent(Intent.ACTION_SEND).setData(Uri.parse("https://fb.me/xyz"));
        assertEquals("https://fb.me/xyz", OpenInFacebook.link(data));
    }

    @Test
    public void theLinkGoesToFacebooksOwnHandler() {
        Intent view = OpenInFacebook.view("com.facebook.katana.clone", "https://www.facebook.com/x");
        assertEquals(Intent.ACTION_VIEW, view.getAction());
        assertEquals(Uri.parse("https://www.facebook.com/x"), view.getData());
        assertEquals("com.facebook.katana.clone", view.getComponent().getPackageName());
        assertEquals(OpenInFacebook.URI_HANDLER, view.getComponent().getClassName());
    }

    @Test
    public void sharingALinkOpensItAndTheScreenCloses() {
        OpenInFacebook screen = Robolectric.buildActivity(OpenInFacebook.class,
                shared("https://www.facebook.com/reel/42 via Messenger")).create().get();
        Intent opened = Shadows.shadowOf(screen).getNextStartedActivity();
        assertEquals(Uri.parse("https://www.facebook.com/reel/42"), opened.getData());
        assertEquals(OpenInFacebook.URI_HANDLER, opened.getComponent().getClassName());
        assertTrue(screen.isFinishing());
    }

    @Test
    public void sharingTextWithoutALinkSaysSoAndCloses() {
        OpenInFacebook screen = Robolectric.buildActivity(OpenInFacebook.class, shared("hello")).create().get();
        assertNull(Shadows.shadowOf(screen).getNextStartedActivity());
        assertEquals("There's no Facebook link in what you shared.", ShadowToast.getTextOfLatestToast());
        assertTrue(screen.isFinishing());
    }
}
