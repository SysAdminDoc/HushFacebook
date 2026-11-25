/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide hashtags in reel captions (#594): the runs of hashtags go, with the spaces they leave, and the
 * words, spans and single hashtags of a sentence stay. Off, paused or before the settings are ready,
 * the caption is Facebook's own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelHashtagsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_REEL_HASHTAGS.resetToDefault();
        HookStatus.clear();
    }

    private static String without(String caption) {
        return ReelHashtags.without(caption).toString();
    }

    /** The caption from the report: its block of tags at the end goes, and the line break before it. */
    @Test
    public void theTagsAtTheEndOfACaptionGo() {
        assertEquals("Money can't buy happiness?\nWell it can buy a 2027 Range Rover - Land Rover Edmonton.",
                without("Money can't buy happiness?\nWell it can buy a 2027 Range Rover - Land Rover Edmonton.\n"
                        + "#rangerover #luxurysuv #fyp"));
        assertEquals("Love this car", without("Love this car #fyp"));
        assertEquals("Love this car", without("Love this car  #fyp \n "));
        assertEquals("", without("#rangerover #fyp"));
    }

    @Test
    public void aRunInsideALineGoesWithTheSpaceBeforeIt() {
        assertEquals("Great day at the beach", without("Great day #sun #fun at the beach"));
        assertEquals("Check this out", without("#ad #sponsored Check this out"));
        assertEquals("Hi\nBye", without("Hi\n#a #b\nBye"));
        assertEquals("Hi\nBye", without("Hi\n#solo\nBye"));
        assertEquals("Text\nMore", without("Text #a\n#b #c\nMore"));
    }

    @Test
    public void aSingleTagThatIsPartOfASentenceStays() {
        String sentence = "I love #cars so much";
        assertSame(sentence, ReelHashtags.without(sentence));
        assertSame("#Throwback to summer", ReelHashtags.without("#Throwback to summer"));
        assertEquals(0, ReelHashtags.runsLeftOff(sentence));
    }

    @Test
    public void onlyRealTagsCount() {
        for (String kept : new String[]{"C# and F# are languages", "Call #", "Price: 5#", "a#b c", "## heading"}) {
            assertSame(kept, ReelHashtags.without(kept), ReelHashtags.without(kept));
        }
        // Thai vowel signs and Hindi ones are marks, and they stay part of the tag.
        assertEquals("สวยมาก", without("สวยมาก #ไทย #เที่ยว"));
        assertEquals("नमस्ते", without("नमस्ते #भारत #यात्रा"));
        // A zero-width space between tags still makes them one run.
        assertEquals("Nice", without("Nice " + (char) 0x200B + "#a" + (char) 0x200B + "#b"));
    }

    @Test
    public void spansOutsideTheTagsStayWhereTheyWere() {
        SpannableString caption = new SpannableString("Nice ride @Jo #a #b");
        caption.setSpan(new StyleSpan(1), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        caption.setSpan(new URLSpan("fb://profile/1"), 10, 13, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        caption.setSpan(new URLSpan("fb://hashtag/a"), 14, 16, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        CharSequence kept = ReelHashtags.without(caption);
        assertEquals("Nice ride @Jo", kept.toString());
        assertTrue("the caption lost its spans", kept instanceof Spanned);
        Spanned spans = (Spanned) kept;
        StyleSpan[] bold = spans.getSpans(0, kept.length(), StyleSpan.class);
        assertEquals(1, bold.length);
        assertEquals(0, spans.getSpanStart(bold[0]));
        assertEquals(4, spans.getSpanEnd(bold[0]));
        URLSpan[] links = spans.getSpans(0, kept.length(), URLSpan.class);
        assertEquals("only the mention keeps a link", 1, links.length);
        assertEquals("fb://profile/1", links[0].getURL());
        assertEquals(10, spans.getSpanStart(links[0]));
    }

    @Test
    public void theHookLeavesTheTagsOffOnlyWhileItsSwitchIsOn() {
        String caption = "Sunset drive #roadtrip #fyp";
        assertFalse(Settings.HIDE_REEL_HASHTAGS.defaultValue);
        assertSame("off, the caption changed", caption, ReelDeclutter.captionText(caption));

        Settings.HIDE_REEL_HASHTAGS.save(true);
        assertEquals("Sunset drive", ReelDeclutter.captionText(caption).toString());
        assertSame("a caption without tags was copied", "Just a drive", ReelDeclutter.captionText("Just a drive"));
        assertEquals(null, ReelDeclutter.captionText(null));
        assertEquals("", ReelDeclutter.captionText("").toString());

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame("paused, the caption changed", caption, ReelDeclutter.captionText(caption));
    }
}
