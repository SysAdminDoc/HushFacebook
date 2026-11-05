/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.coexist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * A plain Instagram or Threads web link the family scope dropped goes to that app's exported link
 * activity as a new intent holding the link and nothing else; every other refusal stays (#287).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FamilyLinkHandoffTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String LINK = "https://www.instagram.com/p/ABC123/";

    private static ActivityInfo activity(String pkg, String name, boolean exported) {
        ActivityInfo info = new ActivityInfo();
        info.packageName = pkg;
        info.name = name;
        info.exported = exported;
        return info;
    }

    private static Intent dropped(String link) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra("access_token", "private-token");
        intent.setClipData(ClipData.newRawUri("clip", Uri.parse("content://com.facebook.katana/private")));
        return intent;
    }

    private static final ActivityInfo INSTAGRAM_LINKS =
            activity(FamilyLinkHandoff.INSTAGRAM, "com.instagram.url.UrlHandlerActivity", true);

    @Test
    public void anInstagramLinkGoesToInstagramWithOnlyTheLink() {
        Intent handoff = FamilyLinkHandoff.publicLink(dropped(LINK), Collections.singletonList(INSTAGRAM_LINKS));

        assertEquals(Intent.ACTION_VIEW, handoff.getAction());
        assertEquals(Uri.parse(LINK), handoff.getData());
        assertEquals(FamilyLinkHandoff.INSTAGRAM, handoff.getComponent().getPackageName());
        assertEquals("com.instagram.url.UrlHandlerActivity", handoff.getComponent().getClassName());
        assertTrue(handoff.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertNull("no extras leave with it", handoff.getExtras());
        assertNull("no clip data leaves with it", handoff.getClipData());
        assertEquals("only the new-task flag carries over", Intent.FLAG_ACTIVITY_NEW_TASK, handoff.getFlags());
    }

    @Test
    public void aThreadsLinkGoesToThreads() {
        ActivityInfo threads = activity(FamilyLinkHandoff.THREADS, "com.instagram.barcelona.UrlHandler", true);
        List<?> targets = Arrays.asList(INSTAGRAM_LINKS, threads);

        Intent handoff = FamilyLinkHandoff.publicLink(dropped("https://www.threads.net/@someone/post/1"), targets);

        assertEquals(FamilyLinkHandoff.THREADS, handoff.getComponent().getPackageName());
    }

    private static final ActivityInfo THREADS_LINKS =
            activity(FamilyLinkHandoff.THREADS, "com.instagram.barcelona.url.BarcelonaUrlHandlerActivity", true);

    /** What Facebook's Threads card sends when Threads is installed: Threads' own scheme, by package (#385). */
    private static Intent threadsCard(String link) {
        return dropped(link).setPackage(FamilyLinkHandoff.THREADS);
    }

    @Test
    public void aThreadsCardGoesToThreadsLinkActivityWithOnlyTheLink() {
        // Threads 454 reads barcelona://media?id={media id}, the post's numeric id.
        String card = "barcelona://media?id=3435311686544693883";

        Intent handoff = FamilyLinkHandoff.publicLink(threadsCard(card), Arrays.asList(INSTAGRAM_LINKS, THREADS_LINKS));

        assertEquals(Intent.ACTION_VIEW, handoff.getAction());
        assertEquals(Uri.parse(card), handoff.getData());
        assertEquals(FamilyLinkHandoff.THREADS, handoff.getComponent().getPackageName());
        assertEquals("com.instagram.barcelona.url.BarcelonaUrlHandlerActivity", handoff.getComponent().getClassName());
        assertTrue(handoff.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertNull("no extras leave with it", handoff.getExtras());
        assertNull("no clip data leaves with it", handoff.getClipData());
        assertEquals("only the new-task flag carries over", Intent.FLAG_ACTIVITY_NEW_TASK, handoff.getFlags());

        Intent bookmark = FamilyLinkHandoff.publicLink(threadsCard("barcelona://mainfeed"), Collections.singletonList(THREADS_LINKS));
        assertEquals(Uri.parse("barcelona://mainfeed"), bookmark.getData());
    }

    @Test
    public void aThreadsLinkWithNoPackageStillOnlyGoesToThreads() {
        ActivityInfo lookalike = activity("com.example.lookalike", "com.example.lookalike.Links", true);

        Intent handoff = FamilyLinkHandoff.publicLink(dropped("barcelona://mainfeed"), Arrays.asList(lookalike, THREADS_LINKS));

        assertEquals(FamilyLinkHandoff.THREADS, handoff.getComponent().getPackageName());
        assertEquals("com.instagram.barcelona.url.BarcelonaUrlHandlerActivity", handoff.getComponent().getClassName());
        assertNull(FamilyLinkHandoff.publicLink(dropped("barcelona://mainfeed"), Collections.singletonList(lookalike)));
    }

    @Test
    public void facebookTryingThreadsMainActivityByNameKeepsTheRefusal() {
        // Refused, Facebook falls back to the by-package intent above, which Threads' link filter takes.
        Intent named = threadsCard("barcelona://media?id=3435311686544693883")
                .setClassName(FamilyLinkHandoff.THREADS, "com.instagram.barcelona.mainactivity.BarcelonaActivity");
        assertNull(FamilyLinkHandoff.publicLink(named, Collections.singletonList(THREADS_LINKS)));
    }

    @Test
    public void threadsSchemeOnlyEverGoesToThreads() {
        Intent elsewhere = dropped("barcelona://media?id=3435311686544693883").setPackage("com.example.lookalike");
        assertNull(FamilyLinkHandoff.publicLink(elsewhere, Collections.singletonList(THREADS_LINKS)));
        assertNull(FamilyLinkHandoff.publicLink(threadsCard("barcelona://mainfeed"), Collections.singletonList(INSTAGRAM_LINKS)));
        ActivityInfo hidden = activity(FamilyLinkHandoff.THREADS, "com.instagram.barcelona.Internal", false);
        assertNull(FamilyLinkHandoff.publicLink(threadsCard("barcelona://mainfeed"), Collections.singletonList(hidden)));
        assertNull(FamilyLinkHandoff.publicLink(threadsCard("barcelona://mainfeed").setAction(Intent.ACTION_SEND),
                Collections.singletonList(THREADS_LINKS)));
    }

    @Test
    public void theAppIsPickedByItsExactPackage() {
        ActivityInfo lookalike = activity("com.instagram.android.clone", "com.clone.Links", true);
        assertNull(FamilyLinkHandoff.publicLink(dropped(LINK), Collections.singletonList(lookalike)));
    }

    @Test
    public void aTargetFacebookCantStartIsNeverPicked() {
        ActivityInfo hidden = activity(FamilyLinkHandoff.INSTAGRAM, "com.instagram.Internal", false);
        assertNull(FamilyLinkHandoff.publicLink(dropped(LINK), Collections.singletonList(hidden)));

        ServiceInfo service = new ServiceInfo();
        service.packageName = FamilyLinkHandoff.INSTAGRAM;
        service.name = "com.instagram.Service";
        service.exported = true;
        assertNull(FamilyLinkHandoff.publicLink(dropped(LINK), Collections.singletonList(service)));
    }

    @Test
    public void anythingButAPlainWebLinkKeepsTheRefusal() {
        List<?> targets = Collections.singletonList(INSTAGRAM_LINKS);

        Intent send = dropped(LINK).setAction(Intent.ACTION_SEND);
        assertNull(FamilyLinkHandoff.publicLink(send, targets));
        assertNull(FamilyLinkHandoff.publicLink(dropped("instagram://user?username=someone"), targets));
        assertNull(FamilyLinkHandoff.publicLink(dropped("https://www.facebook.com/someone"), targets));
        assertNull(FamilyLinkHandoff.publicLink(dropped("https://evilinstagram.com/p/1"), targets));
        assertNull(FamilyLinkHandoff.publicLink(new Intent(Intent.ACTION_VIEW), targets));
        assertNull(FamilyLinkHandoff.publicLink(null, targets));
        assertNull(FamilyLinkHandoff.publicLink(dropped(LINK), null));
        assertNull(FamilyLinkHandoff.publicLink(dropped(LINK), Collections.emptyList()));
    }

    @Test
    public void eachHostBelongsToItsApp() {
        assertEquals(FamilyLinkHandoff.INSTAGRAM, FamilyLinkHandoff.appFor("instagram.com"));
        assertEquals(FamilyLinkHandoff.INSTAGRAM, FamilyLinkHandoff.appFor("WWW.Instagram.com"));
        assertEquals(FamilyLinkHandoff.INSTAGRAM, FamilyLinkHandoff.appFor("instagr.am"));
        assertEquals(FamilyLinkHandoff.THREADS, FamilyLinkHandoff.appFor("threads.com"));
        assertEquals(FamilyLinkHandoff.THREADS, FamilyLinkHandoff.appFor("www.threads.net"));
        assertNull(FamilyLinkHandoff.appFor("instagram.com.evil.example"));
        assertNull(FamilyLinkHandoff.appFor(null));
    }
}
