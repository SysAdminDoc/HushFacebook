/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The Material You theme's rules: what it recolours, that a colour keeps its lightness, and that
 * everything else, light mode included, is left as Facebook sent it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MaterialYouThemeTest {
    /**
     * Stands in for Facebook's FDS token enum: only the constant names matter. The light values in
     * {@link #LIGHT} are the ones Facebook 580's light theme gives these tokens.
     */
    enum Token {
        WASH, SURFACE_BACKGROUND, CARD_BACKGROUND, PRIMARY_TEXT, SECONDARY_TEXT, DIVIDER, BLUE_LINK,
        PRIMARY_ICON, TOGGLE_ACTIVE_BACKGROUND, REACTION_LIKE, ACCENT, PRIMARY_BUTTON_BACKGROUND,
        STORY_UNSEEN, VERIFIED_BADGE, ACCENT_DEEMPHASIZED, NEW_NOTIFICATION_BACKGROUND, FB_LOGO,
        MAP_HIGHLIGHT_BORDER, DATAVIZ_BLUE_PRIMARY
    }

    /** Dark values from Facebook 580's dark FDS style, one per token above. */
    private static final int[] DARK = {0xFF101011, 0xFF252728, 0xFF333334, 0xFFF2F4F7, 0xFFB0B3B8,
            0xFF65686C, 0xFF5AA7FF, 0xFFF2F4F7, 0xFF1D85FC, 0xFF3E93F8, 0xFF0866FF, 0xFF0866FF,
            0xFF0866FF, 0xFF0866FF, 0x331D85FC, 0x192D88FF, 0xFF0866FF, 0xFF0866FF, 0xFF1D85FC};

    /** Light values from Facebook 580's light FDS style, one per token above. */
    private static final int[] LIGHT = {0xFFC9CCD1, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF080809, 0xFF65686C,
            0xFFD0D3D7, 0xFF0064D1, 0xFF080809, 0xFFEBF5FF, 0xFF0866FF, 0xFF0866FF, 0xFF0866FF,
            0xFF0866FF, 0xFF0866FF, 0xFFEBF5FF, 0xFFE7F3FF, 0xFF0866FF, 0xFF0866FF, 0xFF1D85FC};

    /** The tokens Facebook gives one blue in both themes, which wait for its dark mode answer. */
    private static final Token[] SHARED = {Token.ACCENT, Token.PRIMARY_BUTTON_BACKGROUND, Token.STORY_UNSEEN,
            Token.VERIFIED_BADGE};

    /** Facebook's own blue on its logo, maps and charts, which no theme route changes. */
    private static final Token[] BRAND = {Token.FB_LOGO, Token.MAP_HIGHLIGHT_BORDER, Token.DATAVIZ_BLUE_PRIMARY};

    private TonePalette palette;

    @Before
    public void usePalette() {
        palette = TonePalette.fallback();
        MaterialYouTheme.use(palette, false);
    }

    @After
    public void restore() {
        DarkMode.answer(true);
        MaterialYouTheme.use(TonePalette.fallback(), false);
    }

    @Test
    public void aTokensDarkColourTakesThePaletteAtTheSameLightness() {
        for (Token token : new Token[]{Token.WASH, Token.SURFACE_BACKGROUND, Token.CARD_BACKGROUND,
                Token.PRIMARY_TEXT, Token.SECONDARY_TEXT, Token.DIVIDER, Token.PRIMARY_ICON}) {
            int dark = DARK[token.ordinal()];
            int drawn = MaterialYouTheme.fds(dark, token);
            assertNotEquals(token + " kept Facebook's grey", dark, drawn);
            assertSameLightness(token.name(), dark, drawn);
            assertEquals(token + " is the palette's neutral", palette.sameLightness(TonePalette.NEUTRAL, dark), drawn);
        }
        for (Token token : new Token[]{Token.BLUE_LINK, Token.TOGGLE_ACTIVE_BACKGROUND}) {
            int dark = DARK[token.ordinal()];
            int drawn = MaterialYouTheme.fds(dark, token);
            assertEquals(token + " is the palette's accent", palette.sameLightness(TonePalette.ACCENT, dark), drawn);
            assertSameLightness(token.name(), dark, drawn);
        }
    }

    /**
     * Light mode: every token keeps the colour Facebook's light theme gives it. Before Facebook first
     * answers, when {@link DarkMode#on} still says dark, a light colour keeps its value too: a
     * dark-only token's light colour is never its dark one, and a blue both themes share waits for
     * the answer.
     */
    @Test
    public void lightModeKeepsFacebooksColours() {
        DarkMode.forget();
        for (Token token : Token.values()) {
            int light = LIGHT[token.ordinal()];
            assertEquals(token + " changed before Facebook answered", light, MaterialYouTheme.fds(light, token));
        }
        DarkMode.answer(false);
        for (Token token : Token.values()) {
            int light = LIGHT[token.ordinal()];
            assertEquals(token + " changed in light mode", light, MaterialYouTheme.fds(light, token));
        }
    }

    /** The mutation controls: what isn't a token's own dark colour is left alone. */
    @Test
    public void anythingItDoesntKnowFailsOpen() {
        DarkMode.answer(true);
        for (Token token : BRAND) {
            assertEquals(token + " keeps Facebook's blue", DARK[token.ordinal()], MaterialYouTheme.fds(DARK[token.ordinal()], token));
        }
        assertEquals("another token's dark colour", 0xFF101011, MaterialYouTheme.fds(0xFF101011, Token.SURFACE_BACKGROUND));
        assertEquals("no enum to name the token", 0xFF252728, MaterialYouTheme.fds(0xFF252728, "SURFACE_BACKGROUND"));
        assertEquals("a translucent colour the token isn't given", 0x80252728, MaterialYouTheme.fds(0x80252728, Token.SURFACE_BACKGROUND));
        assertEquals("a listed tint at another alpha", 0x801D85FC, MaterialYouTheme.fds(0x801D85FC, Token.ACCENT_DEEMPHASIZED));
        assertEquals("a listed tint made opaque", 0xFF1D85FC, MaterialYouTheme.fds(0xFF1D85FC, Token.ACCENT_DEEMPHASIZED));
        assertEquals("a colour a server picked for this token", 0xFF123456, MaterialYouTheme.fds(0xFF123456, Token.WASH));
        assertEquals("a colour a server picked for a shared token", 0xFF1877F2,
                MaterialYouTheme.fds(0xFF1877F2, Token.PRIMARY_BUTTON_BACKGROUND));

        DarkMode.forget();
        assertEquals("the same blue in both themes can't say which one is on, before Facebook answers", 0xFF0866FF,
                MaterialYouTheme.fds(0xFF0866FF, Token.ACCENT));
    }

    /**
     * Issue #37: Add friend, Confirm, Add to story, story rings and the verified badge are one blue
     * in both of Facebook's themes. Once Facebook says its dark mode is on they take the palette's
     * accent at the same lightness, and in light mode they keep Facebook's blue.
     */
    @Test
    public void theBluesBothThemesShareTakeThePaletteOnceFacebookSaysDark() {
        DarkMode.answer(true);
        for (Token token : SHARED) {
            int blue = DARK[token.ordinal()];
            int drawn = MaterialYouTheme.fds(blue, token);
            assertEquals(token + " is the palette's accent", palette.sameLightness(TonePalette.ACCENT, blue), drawn);
            assertNotEquals(token + " kept Facebook's blue", blue, drawn);
            assertSameLightness(token.name(), blue, drawn);
        }
        DarkMode.answer(false);
        for (Token token : SHARED) {
            assertEquals(token + " in light mode", LIGHT[token.ordinal()], MaterialYouTheme.fds(LIGHT[token.ordinal()], token));
        }
    }

    /**
     * Issue #37: the translucent blue behind a selected chip ("All", "0 comments") and an unread
     * notification takes the palette's accent at the same lightness and keeps its alpha, so it tints
     * the page below as Facebook's did.
     */
    @Test
    public void aTranslucentBlueTakesThePaletteAndKeepsItsAlpha() {
        DarkMode.answer(true);
        for (Token token : new Token[]{Token.ACCENT_DEEMPHASIZED, Token.NEW_NOTIFICATION_BACKGROUND}) {
            int tint = DARK[token.ordinal()];
            int drawn = MaterialYouTheme.fds(tint, token);
            assertEquals(token + " keeps its alpha", tint >>> 24, drawn >>> 24);
            assertEquals(token + " is the palette's accent", palette.sameLightness(TonePalette.ACCENT, tint), drawn);
            assertNotEquals(token + " kept Facebook's blue", tint, drawn);
            assertSameLightness(token.name(), tint | 0xFF000000, drawn | 0xFF000000);
        }
    }

    /**
     * Issue #72: with AMOLED in the build its hook goes first, and an unread notification's row
     * reaches this one at AMOLED's 25%. It takes the palette's accent at that alpha. The same blue
     * at an alpha AMOLED doesn't give, or on another token, stays Facebook's.
     */
    @Test
    public void amoledsStrongerUnreadTintTakesThePalette() {
        DarkMode.answer(true);
        Token row = Token.NEW_NOTIFICATION_BACKGROUND;
        int amoled = AmoledTheme.apply(DARK[row.ordinal()], row);
        int drawn = MaterialYouTheme.fds(amoled, row);
        assertEquals("keeps AMOLED's alpha", AmoledTheme.NEW_NOTIFICATION_ALPHA, drawn >>> 24);
        assertEquals("the palette's accent", palette.sameLightness(TonePalette.ACCENT, amoled), drawn);
        assertNotEquals("kept Facebook's blue", amoled, drawn);

        assertEquals("an alpha AMOLED doesn't give", 0x332D88FF, MaterialYouTheme.fds(0x332D88FF, row));
        assertEquals("AMOLED's alpha on another token", 0x402D88FF,
                MaterialYouTheme.fds(0x402D88FF, Token.ACCENT_DEEMPHASIZED));
    }

    /**
     * Issue #34 with both themes: on AMOLED's lighter #5B513F, a text role reaches this theme lifted.
     * It takes the palette at the lifted lightness, and on every surface AMOLED makes there it keeps
     * 4.5:1, Mig's roles too. Over black the secondary grey takes the palette as it always did.
     */
    @Test
    public void amoledsLiftedTextTakesThePaletteAndKeepsItsContrast() {
        DarkMode.answer(true);
        int sand = 0xFF5B513F;
        int[] surfaces = {sand, 0xFF5F5543, 0xFF615746, 0xFF625846, 0xFF645A48, 0xFF675D4B,
                AmoledTheme.over(sand, AmoledTheme.UNREAD_ROW)};
        try {
            AmoledTheme.useBackground(sand, 12);
            for (Token token : new Token[]{Token.SECONDARY_TEXT, Token.PRIMARY_TEXT}) {
                int colour = DARK[token.ordinal()];
                int amoled = AmoledTheme.apply(colour, token);
                int drawn = MaterialYouTheme.fds(amoled, token);
                assertNotEquals("kept AMOLED's grey for " + Integer.toHexString(colour), amoled, drawn);
                for (int surface : surfaces) {
                    assertTrue(Integer.toHexString(drawn) + " on " + Integer.toHexString(surface),
                            AmoledThemeTest.contrast(drawn, surface) >= AmoledTheme.TEXT_CONTRAST);
                }
            }
            int link = MaterialYouTheme.fds(AmoledTheme.apply(0xFF5AA7FF, Token.BLUE_LINK), Token.BLUE_LINK);
            for (int surface : surfaces) assertTrue("the link", AmoledThemeTest.contrast(link, surface) >= AmoledTheme.TEXT_CONTRAST);
            for (int colour : new int[]{0xFFA8ABAF, 0xFFB0B3B8, 0xFFE4E6EB}) {
                int drawn = MaterialYouTheme.mig(AmoledTheme.apply(colour, AmoledThemeTest.Role.SECONDARY), AmoledThemeTest.Role.SECONDARY);
                for (int surface : surfaces) {
                    assertTrue("Mig's " + Integer.toHexString(colour), AmoledThemeTest.contrast(drawn, surface) >= AmoledTheme.TEXT_CONTRAST);
                }
            }
            assertEquals("a divider AMOLED leaves still takes the palette", palette.sameLightness(TonePalette.NEUTRAL, 0xFF65686C),
                    MaterialYouTheme.fds(0xFF65686C, Token.DIVIDER));
        } finally {
            AmoledTheme.useBackground(AmoledTheme.backgroundColour());
        }
        assertEquals("over black", palette.sameLightness(TonePalette.NEUTRAL, 0xFFB0B3B8),
                MaterialYouTheme.fds(AmoledTheme.apply(0xFFB0B3B8, Token.SECONDARY_TEXT), Token.SECONDARY_TEXT));
    }

    /** Issue #37: the Like button's blue after you like, a dark-only colour, takes the palette. */
    @Test
    public void theLikeButtonAfterYouLikeTakesThePalette() {
        DarkMode.answer(true);
        int liked = DARK[Token.REACTION_LIKE.ordinal()];
        assertEquals(palette.sameLightness(TonePalette.ACCENT, liked), MaterialYouTheme.fds(liked, Token.REACTION_LIKE));
        DarkMode.answer(false);
        assertEquals("light mode", liked, MaterialYouTheme.fds(liked, Token.REACTION_LIKE));
    }

    /**
     * A token's colour is dark-only or shared, never both, or a shared blue would skip Facebook's
     * answer. One token can have one of each: ACCENT is the shared #0866FF in the dark style and
     * the dark-only #1D85FC in the darker one.
     */
    @Test
    public void theTwoTablesNeverListOneColourTwice() {
        Map<String, int[]> dark = MaterialYouTheme.parseTokens(MaterialYouTheme.FDS_DARK);
        Map<String, int[]> shared = MaterialYouTheme.parseTokens(MaterialYouTheme.FDS_SHARED);
        assertTrue("the shared table lists almost nothing", shared.size() >= 10);
        for (Map.Entry<String, int[]> entry : shared.entrySet()) {
            int[] alsoDark = dark.get(entry.getKey());
            if (alsoDark == null) continue;
            for (int colour : entry.getValue()) {
                for (int other : alsoDark) {
                    assertNotEquals(entry.getKey() + " lists " + Integer.toHexString(colour) + " in both tables", colour, other);
                }
            }
        }
        assertEquals(0xFF1D85FC, dark.get("ACCENT")[0]);
        assertEquals(0xFF0866FF, shared.get("ACCENT")[0]);
        for (Token token : BRAND) {
            assertFalse(token + " is recoloured", dark.containsKey(token.name()) || shared.containsKey(token.name()));
        }
        for (int[] colours : shared.values()) {
            for (int colour : colours) assertTrue(Integer.toHexString(colour), MaterialYouTheme.isFacebookBlue(colour));
        }
    }

    /** Six hex digits are opaque, eight carry their alpha, and a name listed twice is a mistake. */
    @Test
    public void aTableReadsOpaqueAndTranslucentColours() {
        Map<String, int[]> tokens = MaterialYouTheme.parseTokens("A=0866FF;B=331D85FC,1D85FC");
        assertEquals(0xFF0866FF, tokens.get("A")[0]);
        assertEquals(0x331D85FC, tokens.get("B")[0]);
        assertEquals(0xFF1D85FC, tokens.get("B")[1]);
        try {
            MaterialYouTheme.parseTokens("A=0866FF;A=1D85FC");
            throw new AssertionError("a name listed twice was read");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("A=1D85FC"));
        }
    }

    /** The Mig dark scheme only answers dark mode, so its greys and blues follow the palette. */
    @Test
    public void theMigDarkSchemeRecoloursGreysAndBlues() {
        int grey = 0xFF3A3B3C;
        assertEquals(palette.sameLightness(TonePalette.NEUTRAL, grey), MaterialYouTheme.mig(grey, null));
        assertSameLightness("a Mig grey", grey, MaterialYouTheme.mig(grey, null));
        int blue = 0xFF2D88FF;
        assertEquals(palette.sameLightness(TonePalette.ACCENT, blue), MaterialYouTheme.mig(blue, null));
        assertEquals("red keeps its colour", 0xFFF02849, MaterialYouTheme.mig(0xFFF02849, null));
        assertEquals("green keeps its colour", 0xFF45BD62, MaterialYouTheme.mig(0xFF45BD62, null));
        assertEquals("a scrim keeps its colour", 0x66000000, MaterialYouTheme.mig(0x66000000, null));
        assertEquals("white stays white", 0xFFFFFFFF, MaterialYouTheme.mig(0xFFFFFFFF, null));
        assertEquals("black stays black", 0xFF000000, MaterialYouTheme.mig(0xFF000000, null));
    }

    /**
     * The status bar on Android 15 and newer. Facebook's dark chrome greys take the palette at the
     * same lightness, whichever token they came from, and only when Facebook's own check says the
     * theme is dark: light mode asks the Video tab's token for the same #333334.
     */
    @Test
    public void theStatusBarTakesThePaletteInTheDarkThemeOnly() {
        for (int grey : new int[]{0xFF252728, 0xFF333334, 0xFF3A3B3C, 0xFF18191A}) {
            int painted = MaterialYouTheme.statusBar(grey, true, false);
            assertNotEquals(Integer.toHexString(grey) + " kept Facebook's grey", grey, painted);
            assertEquals(Integer.toHexString(grey), palette.sameLightness(TonePalette.NEUTRAL, grey), painted);
            assertSameLightness(Integer.toHexString(grey), grey, painted);
            assertEquals(Integer.toHexString(grey) + " changed in light mode", grey, MaterialYouTheme.statusBar(grey, false, false));
        }
        assertEquals("black stays black", 0xFF000000, MaterialYouTheme.statusBar(0xFF000000, true, false));
    }

    /** The controls: what isn't one of Facebook's dark chrome greys keeps its colour in the dark theme too. */
    @Test
    public void aStatusBarColourItDoesntKnowFailsOpen() {
        assertEquals("edge to edge", 0x00000000, MaterialYouTheme.statusBar(0x00000000, true, false));
        assertEquals("a translucent scrim", 0x80333334, MaterialYouTheme.statusBar(0x80333334, true, false));
        assertEquals("above the bar threshold", 0xFF4B4C4F, MaterialYouTheme.statusBar(0xFF4B4C4F, true, false));
        assertEquals("a dark colour with a hue", 0xFF1A2A10, MaterialYouTheme.statusBar(0xFF1A2A10, true, false));
        assertEquals("a blue bar", 0xFF0866FF, MaterialYouTheme.statusBar(0xFF0866FF, true, false));
        assertEquals("a white bar", 0xFFFFFFFF, MaterialYouTheme.statusBar(0xFFFFFFFF, true, false));
    }

    /** With AMOLED in the build its rule goes first, and its black is no grey the palette takes. */
    @Test
    public void withAmoledTheStatusBarKeepsAmoledsBlack() {
        assertEquals("the Video tab's bar", 0xFF000000, MaterialYouTheme.statusBar(0xFF333334, true, true));
        assertEquals("the home bar", 0xFF000000, MaterialYouTheme.statusBar(0xFF252728, true, true));
        assertEquals("light mode", 0xFF333334, MaterialYouTheme.statusBar(0xFF333334, false, true));
        assertEquals("unpatched, the hook runs as without AMOLED", MaterialYouTheme.statusBar(0xFF333334, true, false),
                MaterialYouTheme.statusBar(0xFF333334, true));
    }

    /**
     * The Video tab writes #252728 for its bars in both themes, and route three leaves it for the bar
     * hooks: light mode keeps Facebook's colour, the dark theme takes the palette's.
     */
    @Test
    public void theVideoTabsWrittenBarColourKeepsFacebooksColourInLightMode() {
        assertEquals("status bar", 0xFF252728, MaterialYouTheme.statusBar(0xFF252728, false, false));
        assertEquals("navigation bar", 0xFF252728, MaterialYouTheme.navigationBar(0xFF252728, false, false));
        assertEquals("with AMOLED", 0xFF252728, MaterialYouTheme.navigationBar(0xFF252728, false, true));
        assertEquals("navigation bar, dark", palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728),
                MaterialYouTheme.navigationBar(0xFF252728, true, false));
    }

    /** The navigation bar keeps route three's band: a sheet's lighter grey and anything else keep theirs. */
    @Test
    public void aNavigationBarColourOutsideTheBandFailsOpen() {
        assertEquals("a sheet's grey", 0xFF333334, MaterialYouTheme.navigationBar(0xFF333334, true, false));
        assertEquals("translucent", 0x26C9CCD1, MaterialYouTheme.navigationBar(0x26C9CCD1, true, false));
        assertEquals("a dark colour with a hue", 0xFF1A2A10, MaterialYouTheme.navigationBar(0xFF1A2A10, true, false));
        assertEquals("a white bar", 0xFFFFFFFF, MaterialYouTheme.navigationBar(0xFFFFFFFF, true, false));
        assertEquals("black stays black", 0xFF000000, MaterialYouTheme.navigationBar(0xFF000000, true, false));
        assertEquals("with AMOLED, its black", 0xFF000000, MaterialYouTheme.navigationBar(0xFF252728, true, true));
    }

    @Test
    public void aServerColourIsRecolouredOnlyWhenItIsAKnownDarkSurface() {
        assertEquals(palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728), MaterialYouTheme.parseColor("#FF252728"));
        assertEquals(palette.sameLightness(TonePalette.NEUTRAL, 0xFF242526), MaterialYouTheme.parseColor("#242526"));
        assertEquals("an unknown colour", 0xFF123456, MaterialYouTheme.parseColor("#123456"));
        assertEquals("a grey that is also a light-mode colour", 0xFF333334, MaterialYouTheme.parseColor("#333334"));
        assertEquals("white", 0xFFFFFFFF, MaterialYouTheme.parseColor("#FFFFFF"));
        assertEquals("a translucent surface", 0x80252728, MaterialYouTheme.parseColor("#80252728"));
    }

    /**
     * Issue #37: the box under a link post's picture is #333334 from a colour MobileConfig sends in
     * dark mode. Its own parse takes the palette's neutral once Facebook says dark mode is on, as a
     * grey the dark theme lists. Light mode, the time before Facebook answers, and a colour that
     * isn't one of those greys keep what they parse to.
     */
    @Test
    public void theLinkBoxsGreyTakesThePaletteOnceFacebookSaysDark() {
        DarkMode.answer(true);
        assertEquals(palette.sameLightness(TonePalette.NEUTRAL, 0xFF333334), MaterialYouTheme.parseLinkBox("#333334"));
        assertEquals("the other grey a config can pick", palette.sameLightness(TonePalette.NEUTRAL, 0xFF3B3C3E),
                MaterialYouTheme.parseLinkBox("#3B3C3E"));
        assertEquals("a dark surface, as route four", palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728),
                MaterialYouTheme.parseLinkBox("#252728"));
        assertEquals("a colour that isn't Facebook's grey", 0xFF123456, MaterialYouTheme.parseLinkBox("#123456"));
        assertEquals("the plain parse still leaves it", 0xFF333334, MaterialYouTheme.parseColor("#333334"));

        DarkMode.answer(false);
        assertEquals("light mode", 0xFF333334, MaterialYouTheme.parseLinkBox("#333334"));
        DarkMode.forget();
        assertEquals("before Facebook answers", 0xFF333334, MaterialYouTheme.parseLinkBox("#333334"));
    }

    /**
     * Issue #37: the profile's Add to story button and Marketplace's chips come from Facebook's
     * server as "#0866FF", and some icons read a #3E93F8 colour resource. Once Facebook says dark
     * mode is on, those exact blues take the palette's accent at the same lightness and keep their
     * alpha, with AMOLED in the build too. A blue someone picked keeps its own, and light mode and
     * the time before Facebook answers keep Facebook's.
     */
    @Test
    public void facebooksBluesFromTheServerOrAResourceTakeThePaletteOnceFacebookSaysDark() {
        DarkMode.answer(true);
        int accent = palette.sameLightness(TonePalette.ACCENT, 0xFF0866FF);
        assertEquals("Add to story", accent, MaterialYouTheme.parseColor("#0866FF"));
        assertEquals("with its alpha written out", accent, MaterialYouTheme.parseColor("#FF0866FF"));
        int tint = MaterialYouTheme.parseColor("#330866FF");
        assertEquals("a chip's tint keeps its alpha", 0x33, tint >>> 24);
        assertEquals(palette.sameLightness(TonePalette.ACCENT, 0x330866FF), tint);

        int resource = 0x7f060100;
        Context context = ColourResources.context(Collections.singletonMap(resource, 0xFF3E93F8));
        int icon = palette.sameLightness(TonePalette.ACCENT, 0xFF3E93F8);
        assertEquals("Context.getColor", icon, MaterialYouTheme.getColor(context, resource, false));
        assertEquals("with AMOLED", icon, MaterialYouTheme.getColor(context, resource, true));
        assertEquals("Resources.getColor", icon, MaterialYouTheme.getColor(context.getResources(), resource, false));
        assertEquals("Resources.getColor with a theme", icon,
                MaterialYouTheme.getColor(context.getResources(), resource, context.getTheme(), false));

        assertEquals("a blue someone picked", 0xFF1877F2, MaterialYouTheme.parseColor("#1877F2"));
        assertEquals("a step off Facebook's blue", 0xFF0866FE, MaterialYouTheme.parseColor("#0866FE"));

        DarkMode.answer(false);
        assertEquals("light mode", 0xFF0866FF, MaterialYouTheme.parseColor("#0866FF"));
        assertEquals("light mode, a resource", 0xFF3E93F8, MaterialYouTheme.getColor(context, resource, false));
        DarkMode.forget();
        assertEquals("before Facebook answers", 0xFF0866FF, MaterialYouTheme.parseColor("#0866FF"));
        assertEquals("before Facebook answers, a resource", 0xFF3E93F8, MaterialYouTheme.getColor(context, resource, false));
    }

    /** The server blues are Facebook's blues, each listed once, and none of them is a dark surface. */
    @Test
    public void everyServerBlueIsOneOfFacebooksBlues() {
        String[] hex = MaterialYouTheme.SERVER_BLUES.split(" ");
        for (String each : hex) {
            int colour = 0xFF000000 | Integer.parseInt(each, 16);
            assertTrue(each, MaterialYouTheme.isFacebookBlue(colour));
            assertTrue(each, MaterialYouTheme.isServerBlue(colour));
            assertTrue(each + " at another alpha", MaterialYouTheme.isServerBlue(colour & 0x19FFFFFF));
            assertFalse(each, MaterialYouTheme.isSurface(colour));
        }
        assertEquals("each listed once", hex.length, new HashSet<>(Arrays.asList(hex)).size());
        assertFalse(MaterialYouTheme.isServerBlue(0xFF252728));
    }

    /**
     * Marketplace home, a React Native screen, in Facebook's dark mode (ReactColours): the selected
     * chip's #331D85FC, the location pin's #75B6FF and the location name's #5AA7FF take the palette's
     * accent at the same lightness, the chip staying as see-through, and the #252728 strip behind the
     * chips takes its neutral. A colour no list has, AMOLED's black, light mode and the blues before
     * Facebook answers keep Facebook's.
     */
    @Test
    public void marketplacesReactColoursTakeThePaletteInDarkMode() {
        DarkMode.answer(true);
        for (int blue : new int[]{0x331D85FC, 0xFF75B6FF, 0xFF5AA7FF}) {
            int drawn = MaterialYouTheme.react(blue);
            assertEquals(Integer.toHexString(blue), palette.sameLightness(TonePalette.ACCENT, blue), drawn);
            assertSameLightness(Integer.toHexString(blue), blue | 0xFF000000, drawn | 0xFF000000);
            assertEquals(Integer.toHexString(blue) + " keeps its alpha", blue >>> 24, drawn >>> 24);
        }
        assertNotEquals("the chip kept Facebook's blue", 0x331D85FC, MaterialYouTheme.react(0x331D85FC));
        assertEquals("the strip", palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728), MaterialYouTheme.react(0xFF252728));
        assertEquals("a colour no list has", 0xFF123456, MaterialYouTheme.react(0xFF123456));
        assertEquals("a blue someone picked", 0xFF1877F2, MaterialYouTheme.react(0xFF1877F2));
        assertEquals("AMOLED's black", 0xFF000000, MaterialYouTheme.react(0xFF000000));
        assertEquals("white text", 0xFFFFFFFF, MaterialYouTheme.react(0xFFFFFFFF));

        DarkMode.answer(false);
        for (int colour : new int[]{0x261D85FC, 0xFF0064D1, 0xFF75B6FF, 0xFF5AA7FF, 0xFF252728}) {
            assertEquals("light mode " + Integer.toHexString(colour), colour, MaterialYouTheme.react(colour));
        }
        DarkMode.forget();
        for (int blue : new int[]{0x331D85FC, 0xFF75B6FF, 0xFF5AA7FF}) {
            assertEquals("before Facebook answers " + Integer.toHexString(blue), blue, MaterialYouTheme.react(blue));
        }
    }

    /**
     * A Marketplace listing's page in dark mode: the Message seller card is #333334, a grey FDS_DARK
     * lists (CARD_BACKGROUND), and as a React background it takes the palette's neutral at its
     * lightness, as the token would. As text, in light mode, or a grey no table lists, it keeps
     * Facebook's colour, and AMOLED's card and black stay AMOLED's.
     */
    @Test
    public void aReactBackgroundTakesTheGreysTheDarkTableLists() {
        DarkMode.answer(true);
        int card = MaterialYouTheme.reactBackground(0xFF333334);
        assertEquals("the card", palette.sameLightness(TonePalette.NEUTRAL, 0xFF333334), card);
        assertNotEquals("the card kept Facebook's grey", 0xFF333334, card);
        assertSameLightness("the card", 0xFF333334, card);
        assertEquals("ReactColours paints a background with it", card, ReactColours.background(0xFF333334, false, true));
        assertEquals("a surface", palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728),
                MaterialYouTheme.reactBackground(0xFF252728));
        assertEquals("a server blue", palette.sameLightness(TonePalette.ACCENT, 0x331D85FC),
                MaterialYouTheme.reactBackground(0x331D85FC));
        assertEquals("text keeps the grey", 0xFF333334, MaterialYouTheme.react(0xFF333334));
        assertEquals("text through ReactColours", 0xFF333334, ReactColours.text(0xFF333334, true));
        assertEquals("a grey no table lists", 0xFF343435, MaterialYouTheme.reactBackground(0xFF343435));
        assertEquals("AMOLED's card", 0xFF121213, MaterialYouTheme.reactBackground(0xFF121213));
        assertEquals("AMOLED's black", 0xFF000000, MaterialYouTheme.reactBackground(0xFF000000));

        DarkMode.answer(false);
        assertEquals("light mode", 0xFF333334, MaterialYouTheme.reactBackground(0xFF333334));
        assertEquals("light mode through ReactColours", 0xFF333334, ReactColours.background(0xFF333334, false, true));
    }

    /**
     * The dark table's lighter greys, like #F2F4F7, are light mode's colours too, so a React
     * background keeps them until Facebook says dark mode is on. And with both themes, what AMOLED
     * turned a colour into is AMOLED's: under a #212121 Background colour its card is #333334, a
     * listed grey that Material You leaves, while a listed grey AMOLED didn't touch still takes the
     * palette.
     */
    @Test
    public void aReactBackgroundWaitsForFacebookAndLeavesAmoledsColours() {
        DarkMode.forget();
        assertEquals("a light grey before Facebook answers", 0xFFF2F4F7, MaterialYouTheme.reactBackground(0xFFF2F4F7));
        assertEquals("the card before Facebook answers", 0xFF333334, MaterialYouTheme.reactBackground(0xFF333334));

        DarkMode.answer(true);
        assertEquals("a light grey once dark mode is said on", palette.sameLightness(TonePalette.NEUTRAL, 0xFFF2F4F7),
                MaterialYouTheme.reactBackground(0xFFF2F4F7));
        assertEquals("AMOLED's card on black", 0xFF121213, ReactColours.background(0xFF333334, true, true));
        assertEquals("a listed grey AMOLED leaves", palette.sameLightness(TonePalette.NEUTRAL, 0xFFB0B3B8),
                ReactColours.background(0xFFB0B3B8, true, true));
        try {
            AmoledTheme.useBackground(0xFF212121);
            assertEquals("AMOLED's card on #212121", 0xFF333334, ReactColours.background(0xFF333334, true, true));
            assertEquals("AMOLED's page on #212121", 0xFF212121, ReactColours.background(0xFF252728, true, true));
        } finally {
            AmoledTheme.useBackground(AmoledTheme.backgroundColour());
        }
    }

    /**
     * With neither theme in the build (a test JVM's SettingsStatus says so), React's colours pass
     * through as they came, a border or tint React didn't set stays null, and one it did set comes
     * back as the same object.
     */
    @Test
    public void reactColoursPassThroughWithoutATheme() {
        DarkMode.answer(true);
        assertEquals(0xFF252728, ReactColours.background(0xFF252728));
        assertEquals(0xFF5AA7FF, ReactColours.text(0xFF5AA7FF));
        assertEquals(null, ReactColours.colour(null));
        Integer pin = 0xFF75B6FF;
        assertTrue(pin == ReactColours.colour(pin));
    }

    /**
     * The order ReactColours runs the themes in: AMOLED first for a background, so Material You
     * gets AMOLED's colour, as route four does. With AMOLED's background set to #18191A, one of the
     * dark surfaces, Marketplace's #252728 strip goes to AMOLED's colour and on to the palette's
     * neutral at its lightness; the other way round it would end on AMOLED's plain #18191A. Text,
     * borders and tints skip AMOLED, so a dark grey there keeps its colour with Material You out.
     */
    @Test
    public void reactColoursRunAmoledFirstForABackgroundOnly() {
        DarkMode.answer(true);
        MaterialYouTheme.use(PalettesForTests.palette(PalettesForTests.RED), false);
        AmoledTheme.useBackground(0xFF18191A);
        try {
            TonePalette red = PalettesForTests.palette(PalettesForTests.RED);
            int neutral = red.sameLightness(TonePalette.NEUTRAL, 0xFF18191A);
            assertNotEquals("the palette's neutral isn't AMOLED's colour", 0xFF18191A, neutral);
            assertEquals("both themes", neutral, ReactColours.background(0xFF252728, true, true));
            assertEquals("AMOLED alone", 0xFF18191A, ReactColours.background(0xFF252728, true, false));
            assertEquals("Material You alone", red.sameLightness(TonePalette.NEUTRAL, 0xFF252728),
                    ReactColours.background(0xFF252728, false, true));
            assertEquals("neither", 0xFF252728, ReactColours.background(0xFF252728, false, false));

            assertEquals("text skips AMOLED", 0xFF333334, ReactColours.text(0xFF333334, false));
            assertEquals("text with Material You", red.sameLightness(TonePalette.ACCENT, 0xFF5AA7FF),
                    ReactColours.text(0xFF5AA7FF, true));
            assertEquals("a tint with Material You", Integer.valueOf(red.sameLightness(TonePalette.ACCENT, 0xFF75B6FF)),
                    ReactColours.colour(0xFF75B6FF, true));
            Integer unlisted = 0xFF123456;
            assertTrue("an unlisted tint comes back as it was", unlisted == ReactColours.colour(unlisted, true));
            assertEquals("no tint set", null, ReactColours.colour(null, true));
        } finally {
            AmoledTheme.useBackground(0xFF000000);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void aServerStringThatIsNoColourThrowsAsBefore() {
        MaterialYouTheme.parseColor("not a colour");
    }

    /** Route three reads these fields where Facebook wrote the colours, so they hold the palette's. */
    @Test
    public void theLiteralFieldsHoldThePalettesSurfaces() {
        int[] written = {0xFF101011, 0xFF18191A, 0xFF1C1C1D, 0xFF242526, 0xFF252728, 0xFF3E4042};
        int[] read = {MaterialYouTheme.DARK_101011, MaterialYouTheme.DARK_18191A, MaterialYouTheme.DARK_1C1C1D,
                MaterialYouTheme.DARK_242526, MaterialYouTheme.DARK_252728, MaterialYouTheme.DARK_3E4042};
        for (int i = 0; i < written.length; i++) {
            assertEquals(palette.sameLightness(TonePalette.NEUTRAL, written[i]), read[i]);
            assertSameLightness(Integer.toHexString(written[i]), written[i], read[i]);
            assertTrue(MaterialYouTheme.isSurface(written[i]));
        }
        String[] listed = MaterialYouTheme.SURFACES.split(" ");
        assertEquals("a surface without a field, or a field without a surface", written.length, listed.length);

        TonePalette other = new TonePalette(new int[][]{shifted(0), shifted(1), shifted(2)}, true);
        MaterialYouTheme.use(other, false);
        assertEquals("the fields follow a new palette", other.sameLightness(TonePalette.NEUTRAL, 0xFF252728),
                MaterialYouTheme.DARK_252728);
    }

    /**
     * Facebook answers whether dark mode is on from whatever thread asks, a feed request or an app
     * job as well as the UI, and the answer's thread writes route three's fields that the UI thread
     * reads. So each field is volatile.
     */
    @Test
    public void routeThreesFieldsAreSafeToReadFromAnyThread() throws Exception {
        for (String field : MaterialYouTheme.SURFACES.split(" ")) {
            int modifiers = MaterialYouTheme.class.getField("DARK_" + field).getModifiers();
            assertTrue("DARK_" + field + " isn't volatile", Modifier.isVolatile(modifiers));
        }
    }

    /**
     * Answers that change dark mode at the same moment, from several threads: once they're done, all
     * six fields hold the colours of the answer that stands, none of them left from the other mode.
     */
    @Test
    public void routeThreesFieldsFollowTheAnswerThatStandsWhenAnswersRace() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 10000; round++) {
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<?>> answers = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    boolean first = t % 2 == 0;
                    answers.add(pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < 200; i++) DarkMode.answer(first == (i % 2 == 0));
                        return null;
                    }));
                }
                for (Future<?> answer : answers) answer.get();

                boolean dark = DarkMode.on();
                int[] read = {MaterialYouTheme.DARK_101011, MaterialYouTheme.DARK_18191A, MaterialYouTheme.DARK_1C1C1D,
                        MaterialYouTheme.DARK_242526, MaterialYouTheme.DARK_252728, MaterialYouTheme.DARK_3E4042};
                String[] surfaces = MaterialYouTheme.SURFACES.split(" ");
                for (int i = 0; i < read.length; i++) {
                    int facebook = 0xFF000000 | Integer.parseInt(surfaces[i], 16);
                    assertEquals("round " + round + ", " + (dark ? "dark" : "light") + " mode, #" + surfaces[i],
                            dark ? palette.sameLightness(TonePalette.NEUTRAL, facebook) : facebook, read[i]);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Light mode on the Video tab. The tab stays dark there: its themed context asks FDS's dark style,
     * whose surface is the #252728 of dark mode, its bottom bar reads that colour's resource, and its
     * top bar writes it into code. With Facebook's own dark mode off, a colour from the forced-dark
     * Video context keeps its value on every route.
     */
    @Test
    public void lightModeKeepsAColourFromTheForcedDarkVideoContext() {
        DarkMode.answer(false);
        Context context = ColourResources.context(Collections.singletonMap(ColourResources.VIDEO_BAR, 0xFF252728));

        assertEquals("a resolver's colour", 0xFF252728, MaterialYouTheme.fds(0xFF252728, Token.SURFACE_BACKGROUND));
        assertEquals("the Mig dark scheme", 0xFF3A3B3C, MaterialYouTheme.mig(0xFF3A3B3C, null));
        assertEquals("the bottom bar, Context.getColor", 0xFF252728,
                MaterialYouTheme.getColor(context, ColourResources.VIDEO_BAR, false));
        assertEquals("Resources.getColor", 0xFF252728,
                MaterialYouTheme.getColor(context.getResources(), ColourResources.VIDEO_BAR, false));
        assertEquals("a server colour", 0xFF252728, MaterialYouTheme.parseColor("#FF252728"));
        assertEquals("a colour written in code", 0xFF252728, MaterialYouTheme.DARK_252728);
        assertEquals("another written in code", 0xFF101011, MaterialYouTheme.DARK_101011);
    }

    /**
     * Dark mode on the Video tab: the bottom bar Facebook reads straight from its #252728 resource
     * takes the palette, as Home's bar does through a token. With AMOLED in the build its black stays.
     */
    @Test
    public void darkModeTintsTheVideoTabsBottomBar() {
        DarkMode.answer(false);
        DarkMode.answer(true);
        Context context = ColourResources.context(Collections.singletonMap(ColourResources.VIDEO_BAR, 0xFF252728));
        int tinted = palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728);

        assertEquals("the bottom bar", tinted, MaterialYouTheme.getColor(context, ColourResources.VIDEO_BAR, false));
        assertEquals("Resources.getColor with a theme", tinted,
                MaterialYouTheme.getColor(context.getResources(), ColourResources.VIDEO_BAR, context.getTheme(), false));
        assertEquals("the fields hold the palette's again", tinted, MaterialYouTheme.DARK_252728);
        assertEquals("a colour that is no dark surface", 0xFFFFFFFF,
                MaterialYouTheme.getColor(context, android.R.color.white, false));

        Context amoled = ColourResources.context(Collections.singletonMap(ColourResources.VIDEO_BAR, 0xFF000000));
        assertEquals("AMOLED's black", 0xFF000000, MaterialYouTheme.getColor(amoled, ColourResources.VIDEO_BAR, true));
    }

    /**
     * The feed's composer row (issue #37): Litho reads SURFACE_BACKGROUND's #252728 as a drawable of
     * the colour resource. In dark mode it takes the palette, on its own copy of the drawable's state,
     * so another drawable of the same resource keeps Facebook's grey. Light mode, another colour and
     * a drawable that is no plain colour come back as they were.
     */
    @Test
    public void theComposerRowsDrawableTakesThePaletteInDarkMode() {
        ColorDrawable composer = new ColorDrawable(0xFF252728);
        Drawable sibling = composer.getConstantState().newDrawable();

        DarkMode.answer(true);
        Drawable themed = MaterialYouTheme.recolour(composer);
        assertEquals("the composer row", palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728),
                ((ColorDrawable) themed).getColor());
        assertEquals("the resource's other drawables", 0xFF252728, ((ColorDrawable) sibling).getColor());
        ColorDrawable white = new ColorDrawable(0xFFFFFFFF);
        assertEquals("a colour that is no dark surface", 0xFFFFFFFF, ((ColorDrawable) MaterialYouTheme.recolour(white)).getColor());
        GradientDrawable shape = new GradientDrawable();
        assertEquals("a drawable that is no plain colour", shape, MaterialYouTheme.recolour(shape));
        assertEquals("nothing", null, MaterialYouTheme.recolour(null));

        DarkMode.answer(false);
        ColorDrawable light = new ColorDrawable(0xFF252728);
        assertEquals("light mode", 0xFF252728, ((ColorDrawable) MaterialYouTheme.recolour(light)).getColor());
    }

    /**
     * 582's comment rows (#37) load their background as a shape of #252728 through the rerouted
     * getDrawable and draw it from a drawable of their own, which no view walk reaches. As it loads,
     * the flat fill takes the palette in dark mode on its own copy of the state; light mode and a fill
     * that changes when pressed keep Facebook's.
     */
    @Test
    public void aCommentRowsShapeTakesThePaletteAsItLoads() {
        GradientDrawable row = filled(0xFF252728);
        Drawable sibling = row.getConstantState().newDrawable();

        DarkMode.answer(true);
        assertEquals("the same drawable", row, MaterialYouTheme.recolour(row));
        assertEquals("the row", palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728), fill(row));
        assertEquals("the resource's other drawables", 0xFF252728, fill(sibling));
        LayerDrawable layered = new LayerDrawable(new Drawable[] {filled(0xFF252728)});
        MaterialYouTheme.recolour(layered);
        assertEquals("a layer", palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728), fill(layered.getDrawable(0)));
        GradientDrawable pressable = new GradientDrawable();
        ColorStateList pressed = new ColorStateList(new int[][] {{android.R.attr.state_pressed}, {}},
                new int[] {0xFF3B3C3E, 0xFF252728});
        pressable.setColor(pressed);
        MaterialYouTheme.recolour(pressable);
        assertEquals("a fill that changes when pressed", pressed, pressable.getColor());

        DarkMode.answer(false);
        GradientDrawable light = filled(0xFF252728);
        MaterialYouTheme.recolour(light);
        assertEquals("light mode", 0xFF252728, fill(light));
    }

    /**
     * The comment sheet's rows (issue #37): code that resolves SURFACE_BACKGROUND into a TypedValue
     * and reads its data gets #252728 from the night item, which no colour call sees. A colour value
     * takes the palette in dark mode; a dimension, a flag or a reference keeps its bits, and so does
     * light mode.
     */
    @Test
    public void aThemeAttributeReadAsDataTakesThePaletteInDarkModeWhenItIsAColour() {
        TypedValue surface = value(TypedValue.TYPE_INT_COLOR_ARGB8, 0xFF252728);
        TypedValue sameBitsDimension = value(TypedValue.TYPE_DIMENSION, 0xFF252728);
        TypedValue reference = value(TypedValue.TYPE_REFERENCE, 0xFF252728);
        TypedValue white = value(TypedValue.TYPE_INT_COLOR_ARGB8, 0xFFFFFFFF);

        DarkMode.answer(true);
        assertEquals("a colour that is a dark surface", palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728),
                MaterialYouTheme.colourData(surface));
        assertEquals("a dimension with the same bits", 0xFF252728, MaterialYouTheme.colourData(sameBitsDimension));
        assertEquals("a reference with the same bits", 0xFF252728, MaterialYouTheme.colourData(reference));
        assertEquals("a colour that is no dark surface", 0xFFFFFFFF, MaterialYouTheme.colourData(white));
        assertEquals("the value is only read", 0xFF252728, surface.data);

        DarkMode.answer(false);
        assertEquals("light mode", 0xFF252728, MaterialYouTheme.colourData(surface));
    }

    /** The same drawable read through {@code Resources}: a colour that is no dark surface comes back as it was. */
    @Test
    public void aDrawableReadThroughResourcesGoesTheSameWay() {
        Context context = RuntimeEnvironment.getApplication();
        DarkMode.answer(true);
        Drawable plain = MaterialYouTheme.getDrawable(context.getResources(), android.R.color.white);
        assertEquals(0xFFFFFFFF, ((ColorDrawable) plain).getColor());
        Drawable themed = MaterialYouTheme.getDrawable(context.getResources(), android.R.color.white, context.getTheme());
        assertEquals(0xFFFFFFFF, ((ColorDrawable) themed).getColor());
    }

    private static TypedValue value(int type, int data) {
        TypedValue value = new TypedValue();
        value.type = type;
        value.data = data;
        return value;
    }

    /**
     * The page under the feed's last unit in dark mode is the window's own background, the #101011
     * the dark style gives WASH, and the framework draws it with no hook to see it. Once the activity
     * is created, and again as it resumes, it takes the palette's neutral at the same lightness, the
     * colour route three gives the rest of the page. Light mode, AMOLED, another colour and a
     * background that is no plain colour keep theirs.
     */
    @Test
    public void theWindowUnderTheFeedTakesThePaletteInDarkMode() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Window window = activity.getWindow();
        MaterialYouTheme.WindowBackgrounds callbacks = new MaterialYouTheme.WindowBackgrounds();
        int themed = palette.sameLightness(TonePalette.NEUTRAL, 0xFF101011);
        assertNotEquals("the palette moves #101011", 0xFF101011, themed);

        DarkMode.answer(true);
        window.setBackgroundDrawable(new ColorDrawable(0xFF101011));
        callbacks.onActivityPostCreated(activity, null);
        assertEquals("once the activity is created", themed, windowColour(window));
        assertFalse("the palette's colour is left as it is", MaterialYouTheme.recolourWindow(window, false));
        window.setBackgroundDrawable(new ColorDrawable(0xFF101011));
        callbacks.onActivityResumed(activity);
        assertEquals("as it resumes", themed, windowColour(window));

        window.setBackgroundDrawable(new ColorDrawable(0xFF101011));
        assertFalse("AMOLED", MaterialYouTheme.recolourWindow(window, true));
        assertEquals(0xFF101011, windowColour(window));
        window.setBackgroundDrawable(new ColorDrawable(0xFFC9CCD1));
        assertFalse("a colour that is no dark surface", MaterialYouTheme.recolourWindow(window, false));
        assertEquals(0xFFC9CCD1, windowColour(window));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF101011);
        window.setBackgroundDrawable(shape);
        assertFalse("a background that is no plain colour", MaterialYouTheme.recolourWindow(window, false));
        assertFalse("no window", MaterialYouTheme.recolourWindow(null, false));

        DarkMode.answer(false);
        window.setBackgroundDrawable(new ColorDrawable(0xFF101011));
        callbacks.onActivityResumed(activity);
        assertEquals("light mode", 0xFF101011, windowColour(window));
    }

    /**
     * On a cold start the feed's window had #101011 again after both callbacks ran, four starts of
     * four on 581. From the resume on, the window takes the palette before each frame too, and one
     * that has no decor yet as it resumes is watched once the framework has made it.
     */
    @Test
    public void aWindowGivenItsDarkBackgroundLaterTakesThePaletteBeforeTheNextFrame() {
        // Attached but not created, since Robolectric's create() makes the decor already.
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        Window window = activity.getWindow();
        MaterialYouTheme.WindowBackgrounds callbacks = new MaterialYouTheme.WindowBackgrounds();
        int themed = palette.sameLightness(TonePalette.NEUTRAL, 0xFF101011);
        DarkMode.answer(true);

        assertEquals("no decor before the resume", null, window.peekDecorView());
        callbacks.onActivityPostCreated(activity, null);
        callbacks.onActivityResumed(activity);
        // What the framework does right after the activity resumes, before the posted watch runs.
        window.getDecorView();
        shadowOf(Looper.getMainLooper()).idle();

        window.setBackgroundDrawable(new ColorDrawable(0xFF101011));
        window.peekDecorView().getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("before the next frame", themed, windowColour(window));
        callbacks.onActivityResumed(activity);
        window.setBackgroundDrawable(new ColorDrawable(0xFF101011));
        window.peekDecorView().getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("after another resume", themed, windowColour(window));

        DarkMode.answer(false);
        window.setBackgroundDrawable(new ColorDrawable(0xFF101011));
        window.peekDecorView().getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("light mode", 0xFF101011, windowColour(window));
    }

    /**
     * The comment sheet is a dialog fragment over the screen, and its comment list sits on a view
     * with a plain #252728 background (#37). A window the screen opens above its own takes the
     * palette on such views, then again after each of its layouts; other colours and light mode
     * keep theirs.
     */
    @Test
    public void aSheetOverTheScreenTakesThePaletteOnItsPlainDarkSurfaces() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int themed = palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728);
        DarkMode.answer(true);
        Dialog sheet = new Dialog(activity);
        FrameLayout content = new FrameLayout(activity);
        View list = plain(activity, 0xFF252728);
        View light = plain(activity, 0xFFC9CCD1);
        content.addView(list);
        content.addView(light);
        sheet.setContentView(content);
        sheet.show();
        View root = sheet.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1000, 2000);

        MaterialYouTheme.watchWindowsAbove(activity, activity.getWindow().getDecorView());
        assertEquals("the list's background", themed, ((ColorDrawable) list.getBackground()).getColor());
        assertEquals("a colour that is no dark surface", 0xFFC9CCD1, ((ColorDrawable) light.getBackground()).getColor());

        View later = plain(activity, 0xFF252728);
        content.addView(later);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("a view added later, after the next layout", themed, ((ColorDrawable) later.getBackground()).getColor());

        DarkMode.answer(false);
        View inLightMode = plain(activity, 0xFF252728);
        content.addView(inLightMode);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("light mode", 0xFF252728, ((ColorDrawable) inLightMode.getBackground()).getColor());
        sheet.dismiss();
    }

    /**
     * The comment sheet's bar (#37) is 581's sutro_top_border: a DIVIDER #65686C shape under a
     * SURFACE_BACKGROUND #252728 one inset at the top, both read from the theme by the framework.
     * Each flat fill takes the palette's neutral at its lightness, on a copy of the state the
     * resource's other drawables share. A gradient, a fill that changes when pressed and light mode
     * keep Facebook's colours.
     */
    @Test
    public void aSheetsShapeFillsTakeThePalette() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DarkMode.answer(true);
        LayerDrawable bar = new LayerDrawable(new Drawable[] {filled(0xFF65686C), filled(0xFF252728)});
        bar.setLayerInsetTop(1, 3);
        Drawable sameResource = bar.getConstantState().newDrawable();
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF252728, 0xFF101011});
        GradientDrawable pressable = new GradientDrawable();
        ColorStateList pressed = new ColorStateList(new int[][] {{android.R.attr.state_pressed}, {}},
                new int[] {0xFF3B3C3E, 0xFF252728});
        pressable.setColor(pressed);
        Dialog sheet = new Dialog(activity);
        FrameLayout content = new FrameLayout(activity);
        View barView = shaped(activity, bar);
        View card = shaped(activity, filled(0xFF333334));
        content.addView(barView);
        content.addView(card);
        content.addView(shaped(activity, gradient));
        content.addView(shaped(activity, pressable));
        sheet.setContentView(content);
        sheet.show();
        View root = sheet.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1000, 2000);

        MaterialYouTheme.watchWindowsAbove(activity, activity.getWindow().getDecorView());

        LayerDrawable drawn = (LayerDrawable) barView.getBackground();
        assertEquals("the bar's border", palette.sameLightness(TonePalette.NEUTRAL, 0xFF65686C), fill(drawn.getDrawable(0)));
        assertEquals("the bar", palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728), fill(drawn.getDrawable(1)));
        assertEquals("a card's shape", palette.sameLightness(TonePalette.NEUTRAL, 0xFF333334), fill(card.getBackground()));
        LayerDrawable shared = (LayerDrawable) sameResource;
        assertEquals("the resource's other drawables", 0xFF65686C, fill(shared.getDrawable(0)));
        assertEquals("the resource's other drawables", 0xFF252728, fill(shared.getDrawable(1)));
        assertArrayEquals("a gradient", new int[] {0xFF252728, 0xFF101011}, gradient.getColors());
        assertEquals("a fill that changes when pressed", pressed, pressable.getColor());

        DarkMode.answer(false);
        View inLightMode = shaped(activity, filled(0xFF252728));
        content.addView(inLightMode);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("light mode", 0xFF252728, fill(inLightMode.getBackground()));
        sheet.dismiss();
    }

    /**
     * Litho reads a token's colour from the theme with TypedArray.getColor (581 LX/1Mx;->A04), and
     * SURFACE_BACKGROUND's night style item keeps #252728, so the comment list's rows drew it (#37).
     * In dark mode a dark surface read that way takes the palette; light mode and other colours keep
     * Facebook's.
     */
    @Test
    public void aThemeAttributesDarkSurfaceTakesThePalette() {
        Context context = RuntimeEnvironment.getApplication();
        DarkMode.answer(true);
        TypedArray surface = attribute(context, "#FF252728");
        assertEquals("dark mode", palette.sameLightness(TonePalette.NEUTRAL, 0xFF252728),
                MaterialYouTheme.getColor(surface, 0, 0, false));
        TypedArray light = attribute(context, "#FFC9CCD1");
        assertEquals("no dark surface", 0xFFC9CCD1, MaterialYouTheme.getColor(light, 0, 0, false));

        DarkMode.answer(false);
        assertEquals("light mode", 0xFF252728, MaterialYouTheme.getColor(surface, 0, 0, false));
        surface.recycle();
        light.recycle();
    }

    private static TypedArray attribute(Context context, String colour) {
        AttributeSet set = Robolectric.buildAttributeSet().addAttribute(android.R.attr.textColor, colour).build();
        return context.obtainStyledAttributes(set, new int[] {android.R.attr.textColor});
    }

    private static GradientDrawable filled(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        return shape;
    }

    private static View shaped(Context context, Drawable background) {
        View view = new View(context);
        view.setBackground(background);
        return view;
    }

    private static int fill(Drawable shape) {
        return ((GradientDrawable) shape).getColor().getDefaultColor();
    }

    private static View plain(Context context, int color) {
        View view = new View(context);
        view.setBackgroundColor(color);
        return view;
    }

    private static int windowColour(Window window) {
        Drawable background = window.getDecorView().getBackground();
        if (background instanceof InsetDrawable) background = ((InsetDrawable) background).getDrawable();
        return ((ColorDrawable) background).getColor();
    }

    /** The tokens of the bars at the bottom of the screen. */
    enum Bar { TAB_BAR_BACKGROUND, NAV_BAR_BACKGROUND }

    /**
     * The navigation area on Android 15 and newer. In dark mode the tab bar sets the window's
     * navigation bar colour for every tab from the Video tab's #252728 resource, and with three-button
     * navigation the system draws that colour at 80% over Facebook's own navigation bar view. Both
     * layers take the palette colour the tab bar above them takes, so the strip is the tab bar's
     * colour. Light mode keeps Facebook's, and with AMOLED the black stays.
     */
    @Test
    public void theNavigationAreaTakesTheTabBarsColourInDarkMode() {
        Context context = ColourResources.context(Collections.singletonMap(ColourResources.VIDEO_BAR, 0xFF252728));
        int tabBar = MaterialYouTheme.fds(0xFF252728, Bar.TAB_BAR_BACKGROUND);
        int view = MaterialYouTheme.fds(0xFF252728, Bar.NAV_BAR_BACKGROUND);
        int window = MaterialYouTheme.getColor(context, ColourResources.VIDEO_BAR, false);

        assertNotEquals("the tab bar kept Facebook's grey", 0xFF252728, tabBar);
        assertEquals("Facebook's navigation bar view", tabBar, view);
        assertEquals("the window's navigation bar colour", tabBar, window);
        assertEquals("the strip", tabBar, overAt80(window, view));
        // The S22's reading with the read left alone, the tab bar at (37,38,44): (37,39,41).
        assertEquals(0xFF252729, overAt80(0xFF252728, 0xFF25262C));

        DarkMode.answer(false);
        assertEquals("light mode", 0xFF252728, MaterialYouTheme.getColor(context, ColourResources.VIDEO_BAR, false));
        DarkMode.answer(true);
        Context amoled = ColourResources.context(Collections.singletonMap(ColourResources.VIDEO_BAR, 0xFF000000));
        assertEquals("AMOLED's black", 0xFF000000, MaterialYouTheme.getColor(amoled, ColourResources.VIDEO_BAR, true));
    }

    /** DecorView's navigation bar scrim on Android 15 and newer: the bar's colour at alpha 0xCC over {@code below}. */
    private static int overAt80(int bar, int below) {
        int out = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int mixed = (((bar >> shift) & 0xFF) * 0xCC + ((below >> shift) & 0xFF) * (0xFF - 0xCC) + 127) / 0xFF;
            out |= mixed << shift;
        }
        return out;
    }

    @Test
    public void facebooksBluesAreBluesAndItsGreysGreys() {
        for (int blue : new int[]{0xFF0866FF, 0xFF1D85FC, 0xFF5AA7FF, 0xFF75B6FF, 0xFFADD5FF, 0xFF3E93F8, 0xFF0064D1, 0xFF00488C, 0xFF1877F2}) {
            assertTrue(Integer.toHexString(blue), MaterialYouTheme.isFacebookBlue(blue));
            assertFalse(Integer.toHexString(blue), MaterialYouTheme.isNeutral(blue));
        }
        for (int other : new int[]{0xFFF02849, 0xFF45BD62, 0xFF7D74FF, 0xFF14B898, 0xFF252728, 0xFFB0B3B8, 0xFF1C2B33}) {
            assertFalse(Integer.toHexString(other), MaterialYouTheme.isFacebookBlue(other));
        }
        for (int grey : new int[]{0xFF101011, 0xFF252728, 0xFF65686C, 0xFFB0B3B8, 0xFFF2F4F7, 0xFF46484B}) {
            assertTrue(Integer.toHexString(grey), MaterialYouTheme.isNeutral(grey));
        }
    }

    /** Android 11 has no wallpaper palette, so the fixed one is what it gets. */
    @Test
    public void android11GetsTheFixedPalette() {
        Context context = RuntimeEnvironment.getApplication();
        TonePalette phone = TonePalette.of(context);
        assertFalse(phone.dynamic);
        TonePalette fixed = TonePalette.fallback();
        for (int family = 0; family < 3; family++) {
            for (int tone : TonePalette.TONES) assertEquals(fixed.tone(family, tone), phone.tone(family, tone));
        }
        // Tones of Facebook's blue, as Material's colour utilities build them.
        assertEquals(0xFF1A1B21, fixed.tone(TonePalette.NEUTRAL, 10));
        assertEquals(0xFFE3E2E9, fixed.tone(TonePalette.NEUTRAL, 90));
        assertEquals(0xFFB3C5FF, fixed.tone(TonePalette.ACCENT, 80));
        assertEquals(0xFF4A5C92, fixed.tone(TonePalette.ACCENT, 40));
    }

    /** Android 12 and newer: the wallpaper palette, read from the framework's system colours. */
    @Test
    @Config(sdk = 34)
    public void android12AndNewerReadTheWallpaperPalette() {
        Context context = RuntimeEnvironment.getApplication();
        TonePalette phone = TonePalette.of(context);
        assertTrue(phone.dynamic);
        assertEquals(context.getColor(android.R.color.system_neutral1_900), phone.tone(TonePalette.NEUTRAL, 10));
        assertEquals(context.getColor(android.R.color.system_neutral2_200), phone.tone(TonePalette.NEUTRAL_VARIANT, 80));
        assertEquals(context.getColor(android.R.color.system_accent1_600), phone.tone(TonePalette.ACCENT, 40));
        assertEquals(context.getColor(android.R.color.system_accent1_0), phone.tone(TonePalette.ACCENT, 100));

        MaterialYouTheme.use(phone, false);
        int drawn = MaterialYouTheme.fds(0xFF252728, Token.SURFACE_BACKGROUND);
        assertEquals(phone.sameLightness(TonePalette.NEUTRAL, 0xFF252728), drawn);
        assertSameLightness("a surface in the wallpaper palette", 0xFF252728, drawn);
    }

    /** Lightness is what keeps contrast, so it holds for every colour the theme maps, in any palette. */
    @Test
    public void everyMappedColourKeepsItsLightness() {
        for (TonePalette each : new TonePalette[]{TonePalette.fallback(),
                new TonePalette(new int[][]{shifted(0), shifted(1), shifted(2)}, true)}) {
            for (String table : new String[]{MaterialYouTheme.FDS_DARK, MaterialYouTheme.FDS_SHARED}) {
                for (Map.Entry<String, int[]> entry : MaterialYouTheme.parseTokens(table).entrySet()) {
                    for (int dark : entry.getValue()) {
                        int drawn = MaterialYouTheme.recolour(each, dark);
                        assertEquals(entry.getKey() + " keeps its alpha", dark >>> 24, drawn >>> 24);
                        assertSameLightness(entry.getKey(), dark, drawn);
                    }
                }
            }
        }
    }

    /** Stands in for a story ring's tokens: unseen in Facebook's blue, seen in its disabled grey. */
    enum Ring { STORY_UNSEEN, DISABLED_ICON }

    private static final int UNSEEN_BLUE = 0xFF0866FF;
    private static final int SEEN_GREY = 0xFF6F7276;

    /**
     * Issue #67: under a near-grey wallpaper palette the unseen story ring took the accent at its
     * lightness and came out as grey as the seen ring beside it. Facebook's blue palette stands in
     * for an accent that grey, the greys stay the phone's, and the report says which it was.
     */
    @Test
    public void anAccentTooGreyToTellFromTheGreysGivesWayToFacebooksBlue() {
        DarkMode.answer(true);
        TonePalette grey = PalettesForTests.palette(PalettesForTests.NEAR_GREY);
        assertTrue("accent chroma " + grey.accentChroma, grey.accentReplaced);
        MaterialYouTheme.use(grey, false);
        int unseen = MaterialYouTheme.fds(UNSEEN_BLUE, Ring.STORY_UNSEEN);
        int seen = MaterialYouTheme.fds(SEEN_GREY, Ring.DISABLED_ICON);
        assertEquals("Facebook's blue palette", TonePalette.fallback().sameLightness(TonePalette.ACCENT, UNSEEN_BLUE), unseen);
        assertEquals("the phone's grey", grey.sameLightness(TonePalette.NEUTRAL, SEEN_GREY), seen);
        assertSameLightness("unseen ring", UNSEEN_BLUE, unseen);
        assertTrue("unseen and seen rings " + distance(unseen, seen) + " apart", distance(unseen, seen) > 25);
        String line = MaterialYouTheme.reportLine(grey);
        assertTrue(line, line.startsWith("wallpaper palette; its accent (chroma ") && line.endsWith("Facebook's blue stands in"));
    }

    /**
     * A palette with colour in its accent keeps it, today's colours: a Galaxy S25's muted blue-grey
     * as well as the red, yellow and green ones. Android 11's fixed palette is never replaced.
     */
    @Test
    public void anAccentWithColourInItStaysThePhones() {
        DarkMode.answer(true);
        for (int[][] families : new int[][][]{PalettesForTests.GALAXY_S25, PalettesForTests.RED,
                PalettesForTests.YELLOW, PalettesForTests.GREEN}) {
            TonePalette phone = PalettesForTests.palette(families);
            assertFalse("accent chroma " + phone.accentChroma, phone.accentReplaced);
            assertEquals(families[TonePalette.ACCENT][5], phone.tone(TonePalette.ACCENT, 50));
            MaterialYouTheme.use(phone, false);
            int unseen = MaterialYouTheme.fds(UNSEEN_BLUE, Ring.STORY_UNSEEN);
            assertEquals(phone.sameLightness(TonePalette.ACCENT, UNSEEN_BLUE), unseen);
            double apart = distance(unseen, MaterialYouTheme.fds(SEEN_GREY, Ring.DISABLED_ICON));
            assertTrue("unseen and seen rings " + apart + " apart", apart > 15);
        }
        assertEquals("wallpaper palette; accent chroma 22.0",
                MaterialYouTheme.reportLine(PalettesForTests.palette(PalettesForTests.GALAXY_S25)));
        assertFalse(TonePalette.fallback().accentReplaced);
        assertTrue(MaterialYouTheme.reportLine(TonePalette.fallback()).startsWith("Facebook's blue palette: "));
    }

    /** CIELAB distance between two colours. */
    private static double distance(int a, int b) {
        double[] x = TonePalette.lab(a);
        double[] y = TonePalette.lab(b);
        return Math.sqrt((x[0] - y[0]) * (x[0] - y[0]) + (x[1] - y[1]) * (x[1] - y[1]) + (x[2] - y[2]) * (x[2] - y[2]));
    }

    private static void assertSameLightness(String what, int before, int after) {
        double difference = Math.abs(TonePalette.lstar(before) - TonePalette.lstar(after));
        assertTrue(what + ": L* moved by " + difference, difference <= 0.6);
    }

    /**
     * A palette of another hue, standing in for a phone's: the tonal spot palette of #B3261E (a red
     * seed, hue 26), as Material's colour utilities build it.
     */
    private static int[] shifted(int family) {
        return PalettesForTests.RED[family];
    }
}
