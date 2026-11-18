/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.emoji;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import app.morphe.extension.facebook.font.FontFile;
import app.morphe.extension.facebook.font.FontFileTest;
import app.morphe.extension.facebook.font.OwnFont;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the emoji hook answers with a font file picked (issue #513). Android's own text code runs
 * here, so the picked font is measured rather than taken on trust.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OwnEmojiTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String SMILE = new String(Character.toChars(0x1F600));

    @Before
    public void turnTheSwitchOn() {
        Settings.USE_SYSTEM_EMOJI.save(true);
    }

    @After
    public void restore() {
        Settings.USE_SYSTEM_EMOJI.resetToDefault();
        Settings.EMOJI_FONT_SOURCE.resetToDefault();
        OwnEmoji.fileChanged();
        copy().delete();
        HookStatus.clear();
    }

    private static File copy() {
        Context app = RuntimeEnvironment.getApplication();
        return FontFile.emojiFile(app);
    }

    /** Picks [font] the way the settings row does: a checked copy, then its name saved. */
    private static void pick(byte[] font, String name) throws Exception {
        FontFile.copyEmoji(new ByteArrayInputStream(font), copy(), OwnFont::loads, new FontFile.Choice() {
            @Override
            public boolean save() {
                return Settings.EMOJI_FONT_SOURCE.save(name);
            }

            @Override
            public void undo() {
                Settings.EMOJI_FONT_SOURCE.resetToDefault();
            }
        });
        OwnEmoji.fileChanged();
    }

    private static float widthOfASmile(Typeface typeface) {
        Paint paint = new Paint();
        paint.setTextSize(100);
        paint.setTypeface(typeface);
        return paint.measureText(SMILE);
    }

    @Test
    public void aPickedFileDrawsTheEmojiWhileTheSwitchIsOn() throws Exception {
        pick(FontFileTest.withEmoji(FontFileTest.font(FontFileTest.STATIC_FONT)), "Smileys.ttf");
        Typeface picked = SystemEmoji.typeface();
        assertNotNull(picked);
        assertNotSame("the phone's emoji answered", Typeface.DEFAULT, picked);
        assertSame("the font was built again", picked, SystemEmoji.typeface());
        assertNotEquals("the smile didn't come from the picked font",
                widthOfASmile(Typeface.DEFAULT), widthOfASmile(picked), 0.01f);
    }

    @Test
    public void offItStillLeavesFacebooksEmojiFontToAnswer() throws Exception {
        pick(FontFileTest.withEmoji(FontFileTest.font(FontFileTest.STATIC_FONT)), "Smileys.ttf");
        Settings.USE_SYSTEM_EMOJI.save(false);
        assertNull(SystemEmoji.typeface());
    }

    @Test
    public void withNothingPickedThePhonesEmojiAnswer() {
        assertSame(Typeface.DEFAULT, SystemEmoji.typeface());
    }

    /** Facebook's files were cleared, or the copy never made it: the switch still does its job. */
    @Test
    public void aMissingCopyFallsBackToThePhonesEmoji() {
        Settings.EMOJI_FONT_SOURCE.save("Gone.ttf");
        assertSame(Typeface.DEFAULT, SystemEmoji.typeface());
    }

    @Test
    public void aCopyThatNoLongerBuildsFallsBackToThePhonesEmoji() throws Exception {
        Files.write(copy().toPath(), "not a font any more".getBytes(StandardCharsets.UTF_8));
        Settings.EMOJI_FONT_SOURCE.save("Broken.ttf");
        assertSame(Typeface.DEFAULT, SystemEmoji.typeface());
    }

    /** A replacement can keep the same name, so only the settings row's word drops the old one. */
    @Test
    public void aReplacedFileIsReadAgain() throws Exception {
        pick(FontFileTest.withEmoji(FontFileTest.font(FontFileTest.STATIC_FONT)), "Smileys.ttf");
        Typeface first = SystemEmoji.typeface();
        // Windows won't replace a file a loaded font still maps, so only the row's word is sent here.
        OwnEmoji.fileChanged();
        Typeface second = SystemEmoji.typeface();
        assertNotNull(second);
        assertNotSame(first, second);
    }

    @Test
    public void theTextFontsFileIsNeverTheEmojiFont() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        assertTrue("the two fonts share a file", !FontFile.file(app).equals(FontFile.emojiFile(app)));
        Settings.FONT_SOURCE.save("Rubik-Regular.ttf");
        try {
            Files.write(FontFile.file(app).toPath(), FontFileTest.font(FontFileTest.STATIC_FONT));
            assertSame("a picked text font answered for emoji", Typeface.DEFAULT, SystemEmoji.typeface());
        } finally {
            Settings.FONT_SOURCE.resetToDefault();
            FontFile.file(app).delete();
        }
    }
}
