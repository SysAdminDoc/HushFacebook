/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Translate posts (#332): a post in another language gets a Translate line, or shows translated by
 * itself with See original under it, and a tap opens the box with both texts. A post in the wanted
 * language or one to leave alone stays Facebook's, and so does everything while off or paused.
 * Runs on a stand-in for Google and for the phone's language guess.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostTranslationTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String POST = "Hoy fue un buen dia en la playa con mi familia";
    private static final String TRANSLATED = "Today was a good day at the beach with my family";

    /** What Google was asked, in order. */
    private final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    private String language;
    private String googleFrom;
    private boolean googleFails;

    @Before
    public void setUp() {
        HookStatus.clear();
        PostTranslation.forgetForTests();
        language = "es";
        googleFrom = "es";
        googleFails = false;
        PostTranslation.detectorForTests(text -> language);
        PostTranslation.translatorForTests((text, target) -> {
            asked.add(target + ":" + text);
            if (googleFails) throw new IOException("no network");
            return new GoogleTranslate.Answer(TRANSLATED, googleFrom);
        });
    }

    @After
    public void restore() {
        PauseForTests.resume();
        PostTranslationBox.reset();
        Settings.TRANSLATE_POST_LINK.resetToDefault();
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.resetToDefault();
        Settings.TRANSLATE_TO.resetToDefault();
        Settings.TRANSLATE_LEAVE_ALONE.resetToDefault();
        PostTranslation.translatorForTests(null);
        PostTranslation.detectorForTests(null);
        PostTranslation.forgetForTests();
        HookStatus.clear();
    }

    /** One build of a post, as the two hooks see it. Off the main thread when [worker]. */
    private static Spannable built(String post, boolean worker) throws Exception {
        if (!worker) {
            Spannable text = PostTranslation.text(new SpannableString(post));
            return PostTranslation.shown(text);
        }
        AtomicReference<Spannable> out = new AtomicReference<>();
        Thread layout = new Thread(() -> {
            Spannable text = PostTranslation.text(new SpannableString(post));
            out.set(PostTranslation.shown(text));
        });
        layout.start();
        layout.join();
        return out.get();
    }

    /** Lets a request finish and its answer reach the main thread. */
    private static void requestsDone() throws Exception {
        for (int tries = 0; tries < 200 && PostTranslation.requestsOutForTests(); tries++) Thread.sleep(10);
        Thread.sleep(30);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static ClickableSpan link(Spannable shown) {
        ClickableSpan[] links = shown.getSpans(0, shown.length(), ClickableSpan.class);
        assertEquals("links on the post", 1, links.length);
        return links[0];
    }

    private static TextView body(AlertDialog dialog) {
        ScrollView scroll = (ScrollView) findScroll(dialog.findViewById(android.R.id.content));
        return (TextView) scroll.getChildAt(0);
    }

    private static android.view.View findScroll(android.view.View view) {
        if (view instanceof ScrollView && ((ScrollView) view).getChildAt(0) instanceof TextView
                && ((TextView) ((ScrollView) view).getChildAt(0)).isTextSelectable()) {
            return view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                android.view.View found = findScroll(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static FrameLayout hostIn(Activity activity) {
        FrameLayout host = new FrameLayout(activity);
        activity.setContentView(host);
        return host;
    }

    @Test
    public void offEveryPostIsFacebooks() throws Exception {
        assertFalse(Settings.TRANSLATE_POST_LINK.get());
        assertFalse(Settings.TRANSLATE_POSTS_BY_THEMSELVES.get());
        Spannable post = new SpannableString(POST);

        assertSame(post, PostTranslation.text(post));
        assertSame(post, PostTranslation.shown(post));
        assertTrue("Google heard about a post with the switches off", asked.isEmpty());
    }

    @Test
    public void pausedEveryPostIsFacebooks() throws Exception {
        Settings.TRANSLATE_POST_LINK.save(true);
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        Spannable post = new SpannableString(POST);

        assertSame(post, PostTranslation.text(post));
        assertSame(post, PostTranslation.shown(post));
        requestsDone();
        assertTrue("Google heard about a post while paused", asked.isEmpty());
    }

    @Test
    public void theLineGoesUnderAPostInAnotherLanguageAndGoogleHearsNothing() throws Exception {
        Settings.TRANSLATE_POST_LINK.save(true);

        Spannable shown = built(POST, true);

        assertEquals(POST + "\nTranslate", shown.toString());
        assertEquals("the line alone is tappable", POST.length() + 1, shown.getSpanStart(link(shown)));
        assertEquals(shown.length(), shown.getSpanEnd(link(shown)));
        assertTrue("Google heard about a post nobody tapped", asked.isEmpty());
        assertTrue(HookStatus.report().toString().contains(PostTranslation.LINK_ADDED + " 1"));
    }

    @Test
    public void theLineKeepsWhatFacebookBuilt() throws Exception {
        Settings.TRANSLATE_POST_LINK.save(true);
        SpannableString post = new SpannableString(POST);
        Object seeMore = new Object();
        post.setSpan(seeMore, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        PostTranslation.text(new SpannableString(POST));
        Spannable shown = PostTranslation.shown(post);

        assertEquals("Facebook's own span is gone", 0, shown.getSpanStart(seeMore));
        assertEquals(3, shown.getSpanEnd(seeMore));
    }

    @Test
    public void aPostInTheWantedLanguageGetsNoLine() throws Exception {
        Settings.TRANSLATE_POST_LINK.save(true);
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);
        Settings.TRANSLATE_TO.save("es");

        Spannable shown = built(POST, true);

        assertEquals(POST, shown.toString());
        assertTrue("Google heard about a post already in the language", asked.isEmpty());
    }

    @Test
    public void aLanguageToLeaveAloneGetsNoLine() throws Exception {
        Settings.TRANSLATE_POST_LINK.save(true);
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);
        Settings.TRANSLATE_TO.save("en");
        Settings.TRANSLATE_LEAVE_ALONE.save("fr,es");

        Spannable shown = built(POST, true);

        assertEquals(POST, shown.toString());
        assertTrue("Google heard about a language to leave alone", asked.isEmpty());
    }

    @Test
    public void aPostThePhoneCantPlaceStillGetsTheLine() throws Exception {
        Settings.TRANSLATE_POST_LINK.save(true);
        language = null;

        assertEquals(POST + "\nTranslate", built(POST, true).toString());
    }

    @Test
    public void aPostWithoutWordsGetsNothing() throws Exception {
        Settings.TRANSLATE_POST_LINK.save(true);
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);

        assertEquals("123 !!", built("123 !!", true).toString());
        assertTrue(asked.isEmpty());
    }

    @Test
    public void byThemselvesAPostShowsTranslatedWithSeeOriginal() throws Exception {
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);
        Settings.TRANSLATE_TO.save("en");

        Spannable shown = built(POST, true);

        assertEquals(TRANSLATED + "\nSee original", shown.toString());
        assertEquals("en:" + POST, asked.get(0));
        assertTrue(HookStatus.report().toString().contains(PostTranslation.SHOWN_TRANSLATED + " 1"));

        built(POST, true);
        assertEquals("a second build asks Google again", 1, asked.size());
    }

    @Test
    public void onTheMainThreadThePostGoesOutAsItIsAndShowsTranslatedNextBuild() throws Exception {
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);
        Settings.TRANSLATE_TO.save("en");

        assertEquals("the main thread waited", POST + "\nTranslate", built(POST, false).toString());
        requestsDone();

        assertEquals(TRANSLATED + "\nSee original", built(POST, false).toString());
    }

    @Test
    public void googleSayingThePostIsAlreadyInTheLanguageLeavesItAlone() throws Exception {
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);
        Settings.TRANSLATE_TO.save("en");
        language = null;
        googleFrom = "en";

        assertEquals(POST, built(POST, true).toString());
    }

    @Test
    public void aFailureKeepsThePostAndStopsAskingForAWhile() throws Exception {
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);
        Settings.TRANSLATE_TO.save("en");
        googleFails = true;

        assertEquals(POST + "\nTranslate", built(POST, true).toString());
        assertEquals(1, asked.size());
        assertTrue(HookStatus.report().toString().contains(PostTranslation.FAILED + " 1"));

        assertEquals(POST + "\nTranslate", built(POST, true).toString());
        assertEquals("it asked again straight after a failure", 1, asked.size());
    }

    @Test
    public void aPostTooLongForOneRequestIsNeverSwappedForAPartOfIt() throws Exception {
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);
        Settings.TRANSLATE_TO.save("en");
        String longPost = "palabra ".repeat(GoogleTranslate.LONGEST / 8 + 10);

        assertEquals(longPost + "\nTranslate", built(longPost, true).toString());
        assertTrue(asked.isEmpty());
    }

    @Test
    public void aChoiceLastsOneBuild() throws Exception {
        Settings.TRANSLATE_POST_LINK.save(true);
        PostTranslation.text(new SpannableString(POST));
        Spannable other = new SpannableString("Another one");
        assertEquals("Another one\nTranslate", PostTranslation.shown(other).toString());

        assertSame("the choice outlived its build", other, PostTranslation.shown(other));
    }

    @Test
    public void theLineOpensTheBoxWithBothTexts() throws Exception {
        Settings.TRANSLATE_POST_LINK.save(true);
        Settings.TRANSLATE_TO.save("en");
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Spannable shown = built(POST, false);

        link(shown).onClick(hostIn(activity));

        AlertDialog box = PostTranslationBox.shownDialog();
        assertNotNull("the tap opened nothing", box);
        Button toggle = box.getButton(DialogInterface.BUTTON_NEUTRAL);
        assertEquals("Translating...", body(box).getText().toString());
        assertFalse("the toggle works before there's a translation", toggle.isEnabled());

        requestsDone();
        assertEquals(TRANSLATED, body(box).getText().toString());
        assertEquals("Show original", toggle.getText().toString());
        assertTrue(toggle.isEnabled());
        assertEquals("en:" + POST, asked.get(0));

        toggle.performClick();
        assertEquals(POST, body(box).getText().toString());
        assertEquals("Show translation", toggle.getText().toString());
        assertTrue("the toggle closed the box", box.isShowing());

        toggle.performClick();
        assertEquals(TRANSLATED, body(box).getText().toString());
    }

    @Test
    public void seeOriginalOpensTheBoxOnThePostsOwnText() throws Exception {
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);
        Settings.TRANSLATE_TO.save("en");
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Spannable shown = built(POST, true);

        link(shown).onClick(hostIn(activity));

        AlertDialog box = PostTranslationBox.shownDialog();
        assertNotNull(box);
        assertEquals(POST, body(box).getText().toString());
        Button toggle = box.getButton(DialogInterface.BUTTON_NEUTRAL);
        assertEquals("Show translation", toggle.getText().toString());
        assertTrue("the answer was already here", toggle.isEnabled());
        toggle.performClick();
        assertEquals(TRANSLATED, body(box).getText().toString());
    }

    @Test
    public void aFailedTapSaysSoAndKeepsThePost() throws Exception {
        Settings.TRANSLATE_POST_LINK.save(true);
        googleFails = true;
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Spannable shown = built(POST, false);

        link(shown).onClick(hostIn(activity));
        requestsDone();

        AlertDialog box = PostTranslationBox.shownDialog();
        assertNotNull(box);
        assertEquals("Couldn't translate this. Check your connection and try again.\n\n" + POST, body(box).getText().toString());
        assertFalse(box.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled());
        assertEquals(1, asked.size());

        // A tap isn't held back by the quiet time that failure started.
        googleFails = false;
        PostTranslationBox.reset();
        link(built(POST, false)).onClick(hostIn(activity));
        requestsDone();
        assertEquals(TRANSLATED, body(PostTranslationBox.shownDialog()).getText().toString());
    }

    @Test
    public void googlesAnswerIsReadPieceByPiece() throws Exception {
        GoogleTranslate.Answer answer = GoogleTranslate.parse("[[[\"Hello world. \",\"Hola mundo. \",null,null,3],"
                + "[\"How are you?\",\"Como estas?\",null,null,3],[null,null,\"romanized\"]],null,\"es\",null,null,null,1]");

        assertEquals("Hello world. How are you?", answer.text);
        assertEquals("es", answer.from);
    }

    @Test
    public void anAnswerWithoutTextIsAFailure() {
        assertThrows(IOException.class, () -> GoogleTranslate.parse("[[],null,\"es\"]"));
        assertThrows(IOException.class, () -> GoogleTranslate.parse("<html>sorry</html>"));
    }

    @Test
    public void languagesCompareHoweverTheyreWritten() {
        assertTrue(TranslateLanguages.same("in", "id"));
        assertTrue(TranslateLanguages.same("iw-IL", "he"));
        assertTrue(TranslateLanguages.same("pt-BR", "pt"));
        assertTrue(TranslateLanguages.same("zh-Hant-TW", "zh-TW"));
        assertTrue(TranslateLanguages.same("zh", "zh-CN"));
        assertFalse(TranslateLanguages.same("zh-TW", "zh-CN"));
        assertFalse(TranslateLanguages.same("en", "es"));
        assertFalse(TranslateLanguages.same("", "en"));
        assertFalse(TranslateLanguages.same(null, "en"));
    }

    @Test
    public void everyCodeOffersAName() {
        List<String> codes = TranslateLanguages.codes();
        assertTrue(codes.size() > 90);
        for (String code : codes) assertFalse(code, TranslateLanguages.name(code).isEmpty());
        assertEquals("a code is listed twice", codes.size(), new java.util.HashSet<>(codes).size());
    }

    @Test
    public void theReportCountsThePostsNotTheirText() throws Exception {
        Settings.TRANSLATE_POSTS_BY_THEMSELVES.save(true);
        Settings.TRANSLATE_TO.save("en");

        built(POST, true);

        String report = HookStatus.report().toString();
        assertTrue(report.contains(FamilyNames.TRANSLATE_POSTS + ":"));
        assertFalse("the post's text is in the report", report.contains("playa"));
    }
}
