/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The one colour rule the resolver hooks and the parseColor reroute share. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AmoledThemeTest {
    /** Stands in for Facebook's colour token enums: only the constant names matter. */
    enum Token { CARD_BACKGROUND, COMMENT_BACKGROUND, POPOVER_BACKGROUND, WASH, DIVIDER, PRIMARY_TEXT,
        PRIMARY_UI, BACKGROUND_PRIMARY_UI, NEW_NOTIFICATION_BACKGROUND, ACCENT_DEEMPHASIZED }

    private static final int BLACK = 0xFF000000;

    @Test
    public void aDarkGreyBackgroundTurnsBlack() {
        assertEquals(BLACK, AmoledTheme.apply(0xFF252728, Token.CARD_BACKGROUND));
        assertEquals(BLACK, AmoledTheme.apply(0xFF101011, Token.WASH));
    }

    /** The mutation controls: a divider, a lighter grey, a tinted banner and text keep theirs. */
    @Test
    public void everythingElseKeepsItsColour() {
        assertEquals("a divider token", 0xFF252728, AmoledTheme.apply(0xFF252728, Token.DIVIDER));
        assertEquals("above the raised band", 0xFF46484B, AmoledTheme.apply(0xFF46484B, Token.CARD_BACKGROUND));
        assertEquals("a dark colour with a hue", 0xFF1A2A10, AmoledTheme.apply(0xFF1A2A10, Token.CARD_BACKGROUND));
        assertEquals("light mode's white card", 0xFFFFFFFF, AmoledTheme.apply(0xFFFFFFFF, Token.CARD_BACKGROUND));
        assertEquals("no token to go on", 0xFF252728, AmoledTheme.apply(0xFF252728, "CARD_BACKGROUND"));
    }

    /**
     * Issue #27: Facebook's dark cards are #333334 on the black page AMOLED leaves (a Page card, a
     * post, the profile's composer bar), its popovers #3B3C3E, or #3E4042 on the Video tab. They
     * turn near black, apart from the page and each still a step above the surface under it.
     */
    @Test
    public void aDarkCardTurnsNearBlack() {
        assertEquals("a card", 0xFF121213, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("a comment", 0xFF121213, AmoledTheme.apply(0xFF333334, Token.COMMENT_BACKGROUND));
        assertEquals("a popover", 0xFF1A1B1D, AmoledTheme.apply(0xFF3B3C3E, Token.POPOVER_BACKGROUND));
        assertEquals("the Video tab's popover", 0xFF1D1F21, AmoledTheme.apply(0xFF3E4042, Token.POPOVER_BACKGROUND));
        assertEquals("the older palette's card", 0xFF191A1B, AmoledTheme.apply(0xFF3A3B3C, Token.CARD_BACKGROUND));
        assertEquals("just above the black band, still not black", 0xFF0A0A0A,
                AmoledTheme.apply(0xFF2B2B2B, Token.CARD_BACKGROUND));
    }

    /**
     * PRIMARY_UI is Mig's fill for an input or a pill, such as a search field, not a card: it shows
     * only through this fill, so a card's near black would leave it at about 1.1:1 against the black
     * page. It goes down less than a card, to about #262627, near 1.5:1. BACKGROUND_PRIMARY_UI is
     * FDS's counterpart. A card stays at the near black #27 already gives it.
     */
    @Test
    public void anInputOrPillFillStaysVisibleOnTheBlackPage() {
        assertEquals("PRIMARY_UI", 0xFF262627, AmoledTheme.apply(0xFF333334, Token.PRIMARY_UI));
        assertEquals("BACKGROUND_PRIMARY_UI", 0xFF262627, AmoledTheme.apply(0xFF333334, Token.BACKGROUND_PRIMARY_UI));
        assertEquals("a card still goes near black", 0xFF121213, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
    }

    /** The mutation controls for #27: light mode, other tokens, lighter greys, a hue and no token. */
    @Test
    public void aCardOutsideTheRaisedBandKeepsItsColour() {
        assertEquals("a divider", 0xFF3A3B3C, AmoledTheme.apply(0xFF3A3B3C, Token.DIVIDER));
        assertEquals("text", 0xFF333334, AmoledTheme.apply(0xFF333334, Token.PRIMARY_TEXT));
        assertEquals("a button's grey, above the band", 0xFF46484B, AmoledTheme.apply(0xFF46484B, Token.POPOVER_BACKGROUND));
        assertEquals("a translucent card", 0x99333334, AmoledTheme.apply(0x99333334, Token.COMMENT_BACKGROUND));
        assertEquals("a card with a hue", 0xFF2E3A44, AmoledTheme.apply(0xFF2E3A44, Token.CARD_BACKGROUND));
        assertEquals("no token to go on", 0xFF333334, AmoledTheme.apply(0xFF333334, "CARD_BACKGROUND"));

        DarkMode.answer(false);
        assertEquals("light mode, the Video tab's dark card", 0xFF333334, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("light mode, its popover", 0xFF3E4042, AmoledTheme.apply(0xFF3E4042, Token.POPOVER_BACKGROUND));
    }

    @Test
    public void aServerColourStringIsJudgedByItsValue() {
        assertEquals(BLACK, AmoledTheme.parseColor("#FF252728"));
        assertEquals(0xFF3A3B3C, AmoledTheme.parseColor("#3A3B3C"));
        assertEquals("a translucent scrim stays", 0x80252728, AmoledTheme.parseColor("#80252728"));
    }

    /**
     * Issue #27, the Page card in search results: Facebook builds its search results from server
     * templates, so the card's dark grey comes as text, #333334 on a phone and #333333 in the report,
     * through the parser route four sends here. No token says it's a card, so the grey does, and it
     * goes to the near black route one gives a card.
     */
    @Test
    public void aServerCardTurnsNearBlack() {
        assertEquals("the Page card", 0xFF121213, AmoledTheme.parseColor("#FF333334"));
        assertEquals("the reporter's card", 0xFF121212, AmoledTheme.parseColor("#333333"));
        assertEquals("the top of the card band", 0xFF151515, AmoledTheme.parseColor("#363636"));
        assertEquals("just above the black band, still not black", 0xFF0A0A0A, AmoledTheme.parseColor("#2B2B2B"));
    }

    /**
     * The card's "Write a message" input measured (71,71,72) on the #333334 card: Facebook's 10%
     * white over it. That fill stays translucent, so on the near black card the input still comes
     * out lighter than the #262627 an input's own fill keeps under route one.
     */
    @Test
    public void theServerCardsInputStaysVisible() {
        int input = AmoledTheme.parseColor("#19FFFFFF");
        assertEquals("the input's fill stays translucent", 0x19FFFFFF, input);
        int card = AmoledTheme.parseColor("#FF333334");
        assertEquals("before, on Facebook's card", 0xFF474748, over(input, 0xFF333334));
        int onCard = over(input, card);
        assertEquals("on the near black card", 0xFF29292A, onCard);
        for (int shift : new int[]{16, 8, 0}) {
            assertTrue("each channel at least route one's input fill", ((onCard >> shift) & 0xFF) >= 0x26);
        }
    }

    /**
     * The controls: the next greys up a server sends (a button's, a popover's, a divider's) show
     * only through their own fill and keep it, and so does a translucent grey, one with a hue, and
     * every grey in light mode.
     */
    @Test
    public void aServerGreyAboveTheCardBandKeepsItsColour() {
        assertEquals("the older palette's button", 0xFF3A3B3C, AmoledTheme.parseColor("#3A3B3C"));
        assertEquals("a popover", 0xFF3B3C3E, AmoledTheme.parseColor("#3B3C3E"));
        assertEquals("a divider", 0xFF3E4042, AmoledTheme.parseColor("#3E4042"));
        assertEquals("just above the card band", 0xFF373737, AmoledTheme.parseColor("#373737"));
        assertEquals("a translucent card", 0x99333334, AmoledTheme.parseColor("#99333334"));
        assertEquals("a dark brown", 0xFF34302A, AmoledTheme.parseColor("#34302A"));

        DarkMode.answer(false);
        assertEquals("light mode, a server card", 0xFF333334, AmoledTheme.parseColor("#FF333334"));
        assertEquals("light mode, a server background", 0xFF252728, AmoledTheme.parseColor("#FF252728"));
    }

    /**
     * Marketplace home, a React Native screen, sets its backgrounds from its JavaScript with no token
     * (ReactColours): the strip behind its chips is #252728, which goes black like a server
     * background. The selected chip's see-through blue, a scrim and light mode keep theirs.
     */
    @Test
    public void aReactBackgroundIsJudgedByItsValue() {
        assertEquals("the strip behind Marketplace's chips", BLACK, AmoledTheme.react(0xFF252728));
        assertEquals("a card", 0xFF121213, AmoledTheme.react(0xFF333334));
        assertEquals("the selected chip", 0x331D85FC, AmoledTheme.react(0x331D85FC));
        assertEquals("a translucent scrim stays", 0x80252728, AmoledTheme.react(0x80252728));
        assertEquals("white", 0xFFFFFFFF, AmoledTheme.react(0xFFFFFFFF));

        DarkMode.answer(false);
        assertEquals("light mode", 0xFF252728, AmoledTheme.react(0xFF252728));
    }

    /**
     * Issue #72: Facebook's dark theme tints an unread notification's row with a 10% blue,
     * #192D88FF. Over its grey page that's a blue row, over AMOLED's black page it's #050E1A,
     * almost black. The tint goes to 25%, and the row then stands further from the black page than
     * Facebook's own row does from its grey one.
     */
    @Test
    public void anUnreadNotificationStandsOutOnTheBlackPage() {
        int facebook = 0x192D88FF;
        int drawn = AmoledTheme.apply(facebook, Token.NEW_NOTIFICATION_BACKGROUND);
        assertEquals("Facebook's blue at 25%", 0x402D88FF, drawn);
        assertEquals("over the black page", 0xFF0B2240, over(drawn, BLACK));

        int page = 0xFF252728;
        double facebookStep = TonePalette.lstar(over(facebook, page)) - TonePalette.lstar(page);
        double step = TonePalette.lstar(over(drawn, BLACK)) - TonePalette.lstar(BLACK);
        assertTrue("a step of " + step + " against Facebook's " + facebookStep, step >= 2 * facebookStep);
    }

    /** The mutation controls for issue #72: every other tint keeps its alpha. */
    @Test
    public void anyOtherTintKeepsItsAlpha() {
        assertEquals("another token's tint", 0x192D88FF, AmoledTheme.apply(0x192D88FF, Token.ACCENT_DEEMPHASIZED));
        assertEquals("no token to go on", 0x192D88FF, AmoledTheme.apply(0x192D88FF, "NEW_NOTIFICATION_BACKGROUND"));
        assertEquals("a tint already stronger", 0x662D88FF, AmoledTheme.apply(0x662D88FF, Token.NEW_NOTIFICATION_BACKGROUND));
        assertEquals("an opaque row", 0xFF0B2240, AmoledTheme.apply(0xFF0B2240, Token.NEW_NOTIFICATION_BACKGROUND));
        assertEquals("a clear row", 0x002D88FF, AmoledTheme.apply(0x002D88FF, Token.NEW_NOTIFICATION_BACKGROUND));

        DarkMode.answer(false);
        assertEquals("light mode", 0x192D88FF, AmoledTheme.apply(0x192D88FF, Token.NEW_NOTIFICATION_BACKGROUND));
    }

    /** {@code top}, a colour with alpha, drawn over the opaque {@code under}, rounded as a screen does. */
    private static int over(int top, int under) {
        int alpha = top >>> 24;
        int out = 0xFF000000;
        for (int shift : new int[]{16, 8, 0}) {
            int blended = (((top >> shift) & 0xFF) * alpha + ((under >> shift) & 0xFF) * (255 - alpha) + 127) / 255;
            out |= blended << shift;
        }
        return out;
    }

    @Test(expected = IllegalArgumentException.class)
    public void aStringThatIsNoColourThrowsAsBefore() {
        AmoledTheme.parseColor("not a colour");
    }

    /**
     * Issue #22: back from Recent Apps, the Video tab's bar is painted #333334, the colour its
     * CARD_BACKGROUND_DARK token resolves to, and Facebook's home bar #252728.
     */
    @Test
    public void aDarkThemeStatusBarTurnsBlack() {
        assertEquals("the Video tab's bar", BLACK, AmoledTheme.statusBar(0xFF333334, true));
        assertEquals("the home bar", BLACK, AmoledTheme.statusBar(0xFF252728, true));
        assertEquals("the lightest chrome grey", BLACK, AmoledTheme.statusBar(0xFF3A3B3C, true));
    }

    /** Light mode asks the same token for the same #333334, and its bars keep Facebook's colours. */
    @Test
    public void aLightThemeStatusBarKeepsItsColour() {
        assertEquals("the Video tab's bar", 0xFF333334, AmoledTheme.statusBar(0xFF333334, false));
        assertEquals("a white bar", 0xFFFFFFFF, AmoledTheme.statusBar(0xFFFFFFFF, false));
    }

    /** The mutation controls for the dark theme: only an opaque dark grey turns black. */
    @Test
    public void aDarkThemeStatusBarThatIsNoDarkGreyKeepsItsColour() {
        assertEquals("edge to edge", 0x00000000, AmoledTheme.statusBar(0x00000000, true));
        assertEquals("a translucent scrim", 0x80333334, AmoledTheme.statusBar(0x80333334, true));
        assertEquals("above the bar threshold", 0xFF4B4C4F, AmoledTheme.statusBar(0xFF4B4C4F, true));
        assertEquals("a dark colour with a hue", 0xFF1A2A10, AmoledTheme.statusBar(0xFF1A2A10, true));
        assertEquals("a white bar", 0xFFFFFFFF, AmoledTheme.statusBar(0xFFFFFFFF, true));
    }

    /**
     * The Video tab keeps a dark surface in light mode and writes its bars' #252728 into code for
     * both themes. Route three leaves that colour for the bar hooks, which keep it in light mode.
     */
    @Test
    public void lightModeKeepsTheVideoTabsWrittenBarColour() {
        assertEquals("status bar", 0xFF252728, AmoledTheme.statusBar(0xFF252728, false));
        assertEquals("navigation bar", 0xFF252728, AmoledTheme.navigationBar(0xFF252728, false));
        assertEquals("navigation bar, dark", BLACK, AmoledTheme.navigationBar(0xFF252728, true));
    }

    /** The navigation bar keeps route three's band: the lighter grey under a sheet keeps its colour. */
    @Test
    public void aDarkThemeNavigationBarThatIsNoDarkGreyKeepsItsColour() {
        assertEquals("a sheet's grey", 0xFF333334, AmoledTheme.navigationBar(0xFF333334, true));
        assertEquals("translucent", 0x26C9CCD1, AmoledTheme.navigationBar(0x26C9CCD1, true));
        assertEquals("a dark colour with a hue", 0xFF1A2A10, AmoledTheme.navigationBar(0xFF1A2A10, true));
        assertEquals("a white bar", 0xFFFFFFFF, AmoledTheme.navigationBar(0xFFFFFFFF, true));
    }

    /**
     * The bar's black stays the bar's: a resolver's #333334 card gets a card's near black, and so
     * does a server's (#27), neither the bar's black.
     */
    @Test
    public void theBarThresholdDoesNotReachTheOtherRoutes() {
        assertEquals(0xFF121213, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals(0xFF121213, AmoledTheme.parseColor("#FF333334"));
    }

    @After
    public void darkModeAsBefore() {
        DarkMode.answer(true);
        AmoledTheme.useRouteTwo(null);
        AmoledTheme.useBackground(BLACK);
        AmoledTheme.useLight(0);
    }

    /** Route two's black over the Video tab's #252728, as the patched resource table gives it. */
    private static Context routeTwo() {
        AmoledTheme.useRouteTwo(Integer.toHexString(ColourResources.VIDEO_BAR) + "=ff252728");
        return ColourResources.context(Collections.singletonMap(ColourResources.VIDEO_BAR, BLACK));
    }

    private static TypedArray blackAttribute(Context context) {
        AttributeSet set = Robolectric.buildAttributeSet()
                .addAttribute(android.R.attr.textColor, "@android:color/black").build();
        return context.obtainStyledAttributes(set, new int[]{android.R.attr.textColor});
    }

    /**
     * Light mode on the Video tab. The tab stays dark there: its bottom bar is Context.getColor of
     * the #252728 Facebook's dark style also uses, and its themed context asks that dark style. Route
     * two writes that resource black and route one sees the same grey as in dark mode, so with
     * Facebook's own dark mode off a colour from the forced-dark Video context keeps its value.
     */
    @Test
    public void lightModeKeepsAColourFromTheForcedDarkVideoContext() {
        Context context = routeTwo();
        DarkMode.answer(false);

        assertEquals("the bottom bar, Context.getColor", 0xFF252728, AmoledTheme.getColor(context, ColourResources.VIDEO_BAR));
        assertEquals("Resources.getColor", 0xFF252728,
                AmoledTheme.getColor(context.getResources(), ColourResources.VIDEO_BAR));
        assertEquals("Resources.getColor with a theme", 0xFF252728,
                AmoledTheme.getColor(context.getResources(), ColourResources.VIDEO_BAR, context.getTheme()));
        assertEquals("a resolver's colour", 0xFF252728, AmoledTheme.apply(0xFF252728, Token.CARD_BACKGROUND));
        assertEquals("a server colour", 0xFF252728, AmoledTheme.parseColor("#FF252728"));

        AmoledTheme.useRouteTwo(Integer.toHexString(android.R.color.black) + "=ff252728");
        TypedArray attribute = blackAttribute(context);
        assertEquals("a theme attribute, TypedArray.getColor", 0xFF252728, AmoledTheme.getColor(attribute, 0, 0));
        attribute.recycle();
    }

    /** Dark mode keeps route two's black on the Video tab, and every other route's. */
    @Test
    public void darkModeKeepsTheVideoTabBlack() {
        Context context = routeTwo();
        DarkMode.answer(true);

        assertEquals("the bottom bar", BLACK, AmoledTheme.getColor(context, ColourResources.VIDEO_BAR));
        assertEquals("Resources.getColor", BLACK, AmoledTheme.getColor(context.getResources(), ColourResources.VIDEO_BAR));
        assertEquals("a resolver's colour", BLACK, AmoledTheme.apply(0xFF252728, Token.CARD_BACKGROUND));
        assertEquals("a server colour", BLACK, AmoledTheme.parseColor("#FF252728"));

        AmoledTheme.useRouteTwo(Integer.toHexString(android.R.color.black) + "=ff252728");
        TypedArray attribute = blackAttribute(context);
        assertEquals("a theme attribute", BLACK, AmoledTheme.getColor(attribute, 0, 0));
        attribute.recycle();
    }

    /** The controls: in light mode only a black route two wrote goes back, and only to its own colour. */
    @Test
    public void lightModeRestoresOnlyWhatRouteTwoWrote() {
        AmoledTheme.useRouteTwo("7f0601f4=ff252728;7f060149=ff080809");
        DarkMode.answer(false);
        Map<Integer, Integer> colours = new HashMap<>();
        colours.put(0x7f0601f4, BLACK);
        colours.put(0x7f060149, BLACK);
        colours.put(0x7f060150, BLACK);
        colours.put(0x7f060151, 0xFF333334);
        Context context = ColourResources.context(colours);

        assertEquals("its own colour", 0xFF080809, AmoledTheme.getColor(context, 0x7f060149));
        assertEquals("a black route two didn't write", BLACK, AmoledTheme.getColor(context, 0x7f060150));
        assertEquals("a colour that isn't black", 0xFF333334, AmoledTheme.getColor(context, 0x7f060151));
        assertEquals("Facebook's own black", BLACK, AmoledTheme.getColor(context, android.R.color.black));

        AmoledTheme.useRouteTwo(null);
        assertEquals("no table from the patch", BLACK, AmoledTheme.getColor(context, 0x7f0601f4));
    }

    /** A dark navy with a hue, as the patch's Background colour option can ask for (issue #34). */
    private static final int NAVY = 0xFF0D1117;

    /**
     * Issue #34: every background AMOLED turned black takes the Background colour, on each route and
     * both bars, and a card, a popover and an input's fill sit the same step above it that they sat
     * above black.
     */
    @Test
    public void aBackgroundColourTakesBlacksPlace() {
        AmoledTheme.useBackground(NAVY);

        assertEquals("a page", NAVY, AmoledTheme.apply(0xFF101011, Token.WASH));
        assertEquals("a surface", NAVY, AmoledTheme.apply(0xFF252728, Token.CARD_BACKGROUND));
        assertEquals("a card, #121213 above it", 0xFF1F232A, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("a popover, #1A1B1D above it", 0xFF272C34, AmoledTheme.apply(0xFF3B3C3E, Token.POPOVER_BACKGROUND));
        assertEquals("an input's fill, #262627 above it", 0xFF33373E, AmoledTheme.apply(0xFF333334, Token.PRIMARY_UI));
        assertEquals("a server background", NAVY, AmoledTheme.parseColor("#FF252728"));
        assertEquals("a server card", 0xFF1F232A, AmoledTheme.parseColor("#FF333334"));
        assertEquals("the status bar", NAVY, AmoledTheme.statusBar(0xFF333334, true));
        assertEquals("the navigation bar", NAVY, AmoledTheme.navigationBar(0xFF252728, true));
    }

    /**
     * Issue #86: Data mode's banner wrapper asks for a card's colour, and takes the page's instead of
     * the near black route one gives a card, on black and on a Background colour with a hue.
     */
    @Test
    public void theDataModeBannerTakesThePageColour() {
        assertEquals("a card's near black", BLACK, AmoledTheme.flexBanner(AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND)));
        assertEquals("a dark grey", BLACK, AmoledTheme.flexBanner(0xFF242526));
        AmoledTheme.useBackground(NAVY);
        assertEquals("a card above navy", NAVY, AmoledTheme.flexBanner(AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND)));
    }

    /** The mutation controls for #86: a lighter grey, a hue, a translucent colour and light mode keep theirs. */
    @Test
    public void theDataModeBannerLeavesWhatIsNoCard() {
        assertEquals("a grey further up than a card goes", 0xFF333334, AmoledTheme.flexBanner(0xFF333334));
        assertEquals("a colour with a hue", 0xFF9A3BBE, AmoledTheme.flexBanner(0xFF9A3BBE));
        assertEquals("a translucent near black", 0x80121213, AmoledTheme.flexBanner(0x80121213));
        AmoledTheme.useBackground(NAVY);
        assertEquals("a grey further above navy than a card goes", 0xFF3A3B3C, AmoledTheme.flexBanner(0xFF3A3B3C));
        DarkMode.answer(false);
        assertEquals("light mode's white card", 0xFFFFFFFF, AmoledTheme.flexBanner(0xFFFFFFFF));
        assertEquals("light mode", 0xFF121213, AmoledTheme.flexBanner(0xFF121213));
    }

    /** The mutation controls for #34: Facebook's black, light mode and what AMOLED never touched keep theirs. */
    @Test
    public void aBackgroundColourLeavesTheRestAlone() {
        AmoledTheme.useBackground(NAVY);

        assertEquals("a resolver's black", BLACK, AmoledTheme.apply(BLACK, Token.WASH));
        assertEquals("a server's black", BLACK, AmoledTheme.parseColor("#FF000000"));
        assertEquals("a black status bar", BLACK, AmoledTheme.statusBar(BLACK, true));
        assertEquals("a black navigation bar", BLACK, AmoledTheme.navigationBar(BLACK, true));
        assertEquals("a divider token", 0xFF252728, AmoledTheme.apply(0xFF252728, Token.DIVIDER));
        assertEquals("above the raised band", 0xFF46484B, AmoledTheme.apply(0xFF46484B, Token.CARD_BACKGROUND));
        assertEquals("a light status bar", 0xFF333334, AmoledTheme.statusBar(0xFF333334, false));

        DarkMode.answer(false);
        assertEquals("light mode", 0xFF252728, AmoledTheme.apply(0xFF252728, Token.CARD_BACKGROUND));
        assertEquals("light mode's server colour", 0xFF252728, AmoledTheme.parseColor("#FF252728"));
    }

    /**
     * Route two writes the Background colour into Facebook's resources, and a resolver or a server
     * can hand it back. It comes back as it is: a grey in the raised band isn't raised a second time,
     * while a card still steps up from it.
     */
    @Test
    public void theBackgroundColourComesBackAsItIs() {
        int grey = 0xFF303030;
        AmoledTheme.useBackground(grey);

        assertEquals("a resolver", grey, AmoledTheme.apply(grey, Token.CARD_BACKGROUND));
        assertEquals("a server", grey, AmoledTheme.parseColor("#FF303030"));
        assertEquals("the status bar", grey, AmoledTheme.statusBar(grey, true));
        assertEquals("a card still steps up", 0xFF424243, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
    }

    /** Light mode reads the Background colour route two wrote as Facebook's colour, as it reads black. */
    @Test
    public void lightModeRestoresTheBackgroundColourRouteTwoWrote() {
        AmoledTheme.useBackground(NAVY);
        AmoledTheme.useRouteTwo(Integer.toHexString(ColourResources.VIDEO_BAR) + "=ff252728");
        Map<Integer, Integer> colours = new HashMap<>();
        colours.put(ColourResources.VIDEO_BAR, NAVY);
        colours.put(0x7f060150, BLACK);
        Context context = ColourResources.context(colours);

        DarkMode.answer(false);
        assertEquals("Facebook's colour", 0xFF252728, AmoledTheme.getColor(context, ColourResources.VIDEO_BAR));
        assertEquals("a black route two didn't write", BLACK, AmoledTheme.getColor(context, 0x7f060150));

        DarkMode.answer(true);
        assertEquals("dark mode keeps it", NAVY, AmoledTheme.getColor(context, ColourResources.VIDEO_BAR));
    }

    /** Black is the default, until the patch fills the option's colour in. */
    @Test
    public void theBackgroundIsBlackByDefault() {
        assertEquals(BLACK, AmoledTheme.backgroundColour());
        assertEquals(BLACK, AmoledTheme.apply(0xFF252728, Token.CARD_BACKGROUND));
        assertEquals("the step above black", 0xFF121213, AmoledTheme.raise(BLACK, 0xFF121213));
        assertEquals("as far as white", 0xFFFFFFFF, AmoledTheme.raise(0xFFF0F0F0, 0xFF353535));
    }

    /** Issue #34's lighter Background colour, which the patch lets through with a lightest step of 12. */
    private static final int SAND = 0xFF5B513F;
    private static final int SAND_STEP = 12;

    /** Every text and icon role AmoledTheme lifts: FDS's names, then Mig's. */
    enum Role { PRIMARY_TEXT, PRIMARY_ICON, SECONDARY_TEXT, SECONDARY_ICON, PLACEHOLDER_TEXT, PLACEHOLDER_ICON,
        META_TEXT, META_ICON, BLUE_LINK, NAV_BAR_TEXT, NAV_BAR_ICON, TAB_BAR_ACTIVE_ICON, TAB_BAR_INACTIVE_ICON,
        FBLITE_STRONG_SECONDARY, PRIMARY, SECONDARY, SECONDARY_EMPHASIZED, TERTIARY, PLACEHOLDER, LINK }

    /**
     * The colours the dark and darker FDS styles give each FDS text role in 577, 580 and 581.
     * MaterialYouTokenFixtureTest holds this table to each fixture.
     */
    static final String ROLE_COLOURS = "PRIMARY_TEXT=F2F4F7,FFFFFF;PRIMARY_ICON=F2F4F7,FFFFFF;"
            + "SECONDARY_TEXT=B0B3B8,A1A4A9;SECONDARY_ICON=B0B3B8,A1A4A9;PLACEHOLDER_TEXT=B0B3B8,84878B;"
            + "PLACEHOLDER_ICON=B0B3B8,84878B;META_TEXT=B0B3B8;META_ICON=B0B3B8;BLUE_LINK=5AA7FF,3E93F8;"
            + "NAV_BAR_TEXT=E8EAEE;NAV_BAR_ICON=E8EAEE;TAB_BAR_ACTIVE_ICON=F2F4F7;TAB_BAR_INACTIVE_ICON=F2F4F7;"
            + "FBLITE_STRONG_SECONDARY=D0D3D7";

    static double contrast(int first, int second) {
        double a = TonePalette.luminance(first);
        double b = TonePalette.luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /**
     * Everything text sits on over the Background colour in use, as AMOLED makes it: the page, a
     * card, both popovers, an input's fill, the lightest fill there is, a server's card, and an
     * unread notification's tinted row over the page.
     */
    private static int[] surfaces() {
        return new int[]{
                AmoledTheme.apply(0xFF101011, Token.WASH),
                AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND),
                AmoledTheme.apply(0xFF3B3C3E, Token.POPOVER_BACKGROUND),
                AmoledTheme.apply(0xFF3E4042, Token.POPOVER_BACKGROUND),
                AmoledTheme.apply(0xFF333334, Token.PRIMARY_UI),
                AmoledTheme.apply(0xFF424242, Token.PRIMARY_UI),
                AmoledTheme.parseColor("#FF333334"),
                AmoledTheme.over(AmoledTheme.apply(0xFF101011, Token.WASH),
                        AmoledTheme.apply(0x192D88FF, Token.NEW_NOTIFICATION_BACKGROUND)),
        };
    }

    @Test
    public void theRolesAreTheOnesItLifts() {
        Set<String> names = new HashSet<>();
        for (Role role : Role.values()) names.add(role.name());
        assertEquals(AmoledTheme.TEXT_TOKENS, names);
        for (String role : MaterialYouTheme.parseTokens(ROLE_COLOURS).keySet()) {
            assertTrue(role + " is no role", names.contains(role));
        }
    }

    /**
     * Issue #34: a lighter Background colour's surfaces step up less than they do over black, in the
     * same order and apart: page, card, popover, input fill, and the lightest fill 12 above the page.
     */
    @Test
    public void aLighterBackgroundStepsItsSurfacesLessInTheSameOrder() {
        AmoledTheme.useBackground(SAND, SAND_STEP);

        assertEquals("the page", SAND, AmoledTheme.apply(0xFF101011, Token.WASH));
        assertEquals("a card", 0xFF5F5543, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("a popover", 0xFF615746, AmoledTheme.apply(0xFF3B3C3E, Token.POPOVER_BACKGROUND));
        assertEquals("the Video tab's popover", 0xFF625846, AmoledTheme.apply(0xFF3E4042, Token.POPOVER_BACKGROUND));
        assertEquals("an input's fill", 0xFF645A48, AmoledTheme.apply(0xFF333334, Token.PRIMARY_UI));
        assertEquals("the lightest fill", 0xFF675D4B, AmoledTheme.apply(0xFF424242, Token.BACKGROUND_PRIMARY_UI));
        assertEquals("a server card", 0xFF5F5543, AmoledTheme.parseColor("#FF333334"));
        assertEquals("a React card", 0xFF5F5543, AmoledTheme.react(0xFF333334));
        assertEquals("the Data mode banner", SAND, AmoledTheme.flexBanner(AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND)));
        assertEquals("the bars", SAND, AmoledTheme.statusBar(0xFF333334, true));

        int[] ordered = {SAND, 0xFF5F5543, 0xFF615746, 0xFF645A48, 0xFF675D4B};
        for (int i = 1; i < ordered.length; i++) {
            assertTrue(Integer.toHexString(ordered[i]) + " is no lighter than the surface under it",
                    TonePalette.luminance(ordered[i]) > TonePalette.luminance(ordered[i - 1]));
        }
        assertEquals("an unread row keeps #72's 25% tint", 0x402D88FF,
                AmoledTheme.apply(0x192D88FF, Token.NEW_NOTIFICATION_BACKGROUND));
    }

    /**
     * Issue #34: on #5B513F Facebook's secondary grey #B0B3B8 has 3.71:1. Every colour Facebook's dark
     * styles give a text or icon role comes back at 4.5:1 or more on every surface AMOLED makes there,
     * and still apart from the primary text. The primary text already has it and keeps its colour.
     */
    @Test
    public void aLighterBackgroundLiftsEachTextRoleToAa() {
        AmoledTheme.useBackground(SAND, SAND_STEP);
        int[] surfaces = surfaces();
        assertTrue("#B0B3B8 starts under 4.5:1", contrast(0xFFB0B3B8, SAND) < 4.5);

        Map<String, int[]> roles = MaterialYouTheme.parseTokens(ROLE_COLOURS);
        assertEquals("every FDS role", 14, roles.size());
        for (Map.Entry<String, int[]> role : roles.entrySet()) {
            for (int colour : role.getValue()) {
                int lifted = AmoledTheme.apply(colour, Role.valueOf(role.getKey()));
                for (int surface : surfaces) {
                    assertTrue(role.getKey() + " " + Integer.toHexString(colour) + " as " + Integer.toHexString(lifted)
                                    + " on " + Integer.toHexString(surface),
                            contrast(lifted, surface) >= AmoledTheme.TEXT_CONTRAST);
                }
                assertEquals(role.getKey() + " lifts once", lifted, AmoledTheme.apply(lifted, Role.valueOf(role.getKey())));
            }
        }

        assertEquals("the primary text keeps its colour", 0xFFF2F4F7, AmoledTheme.apply(0xFFF2F4F7, Role.PRIMARY_TEXT));
        int secondary = AmoledTheme.apply(0xFFB0B3B8, Role.SECONDARY_TEXT);
        assertEquals("the secondary text", 0xFFD6D8DA, secondary);
        assertTrue("still apart from the primary text", contrast(0xFFF2F4F7, secondary) >= 1.29);
        assertEquals("a link stays blue", 0xFFBBDBFF, AmoledTheme.apply(0xFF5AA7FF, Role.BLUE_LINK));
        assertEquals("the darker placeholder", 0xFFD7D8D9, AmoledTheme.apply(0xFF84878B, Role.PLACEHOLDER_TEXT));

        // Mig's text and glyph roles come with no table, so any grey or blue it might give passes.
        for (Role role : new Role[]{Role.PRIMARY, Role.SECONDARY, Role.SECONDARY_EMPHASIZED, Role.TERTIARY,
                Role.PLACEHOLDER, Role.LINK}) {
            for (int colour : new int[]{0xFF8A8D91, 0xFFA8ABAF, 0xFFB0B3B8, 0xFFE4E6EB, 0xFF4599FF}) {
                int lifted = AmoledTheme.apply(colour, role);
                for (int surface : surfaces) {
                    assertTrue(role + " " + Integer.toHexString(colour), contrast(lifted, surface) >= AmoledTheme.TEXT_CONTRAST);
                }
            }
        }
    }

    /** The mutation controls for #34's text: other tokens, disabled text, a tint, darker text and light mode keep theirs. */
    @Test
    public void aLighterBackgroundLiftsNothingElse() {
        AmoledTheme.useBackground(SAND, SAND_STEP);

        assertEquals("a divider", 0xFF65686C, AmoledTheme.apply(0xFF65686C, Token.DIVIDER));
        assertEquals("a selected chip's tint", 0x331D85FC, AmoledTheme.apply(0x331D85FC, Token.ACCENT_DEEMPHASIZED));
        assertEquals("a translucent text colour", 0x80B0B3B8, AmoledTheme.apply(0x80B0B3B8, Role.SECONDARY_TEXT));
        assertEquals("text darker than the surfaces", 0xFF4A4A4A, AmoledTheme.apply(0xFF4A4A4A, Role.SECONDARY_TEXT));
        assertEquals("no token to go on", 0xFFB0B3B8, AmoledTheme.apply(0xFFB0B3B8, "SECONDARY_TEXT"));
        assertEquals("a server's text colour", 0xFFB0B3B8, AmoledTheme.parseColor("#FFB0B3B8"));

        DarkMode.answer(false);
        assertEquals("light mode's text", 0xFFB0B3B8, AmoledTheme.apply(0xFFB0B3B8, Role.SECONDARY_TEXT));
        assertEquals("light mode's card", 0xFF333334, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
    }

    /**
     * Issue #34's Menu profile card: "Create Facebook Page" is a Bloks colour pair's #B0B3B8, with no
     * token. On a lighter Background colour FDS's two secondary text greys lift like the secondary
     * text role, to 4.5:1 on every surface, and so does the palette's grey Material You hands over
     * for one. Black, light mode, the time before Facebook answers, the primary text and a blue
     * button's fill keep theirs.
     */
    @Test
    public void aLighterBackgroundLiftsABloksPairsSecondaryGrey() {
        DarkMode.answer(true);
        assertEquals("black keeps the grey", 0xFFB0B3B8, AmoledTheme.parseBloks("#B0B3B8"));

        AmoledTheme.useBackground(SAND, SAND_STEP);
        int[] surfaces = surfaces();
        assertEquals("the Menu card's grey, as the secondary text", 0xFFD6D8DA, AmoledTheme.parseBloks("#B0B3B8"));
        int tinted = PalettesForTests.palette(PalettesForTests.RED).sameLightness(TonePalette.NEUTRAL, 0xFFB0B3B8);
        int[] lifted = {
                AmoledTheme.parseBloks("#B0B3B8"),
                AmoledTheme.parseBloks("#FFA1A4A9"),
                AmoledTheme.bloksText(0xFFB0B3B8, tinted)};
        for (int colour : lifted) {
            for (int surface : surfaces) {
                assertTrue(Integer.toHexString(colour) + " on " + Integer.toHexString(surface),
                        contrast(colour, surface) >= AmoledTheme.TEXT_CONTRAST);
            }
        }
        assertEquals("the primary text", 0xFFF2F4F7, AmoledTheme.parseBloks("#F2F4F7"));
        assertEquals("a blue button's fill", 0xFF1D85FC, AmoledTheme.parseBloks("#1D85FC"));
        assertEquals("a card's grey takes route four's rule", AmoledTheme.parseColor("#333334"),
                AmoledTheme.parseBloks("#333334"));
        assertEquals("a palette colour from another grey", tinted, AmoledTheme.bloksText(0xFF333334, tinted));

        DarkMode.forget();
        assertEquals("before Facebook answers, since light mode has the grey too", 0xFFB0B3B8,
                AmoledTheme.parseBloks("#B0B3B8"));
        DarkMode.answer(false);
        assertEquals("light mode", 0xFFB0B3B8, AmoledTheme.parseBloks("#B0B3B8"));
    }

    /**
     * Black and every colour taken before #34's lighter ones keep exactly what they had: no text
     * lifts, and each surface steps up by Facebook's full step.
     */
    @Test
    public void blackAndTheColoursTakenBeforeKeepTheirOutput() {
        for (int background : new int[]{BLACK, NAVY, 0xFF3A3A3A}) {
            AmoledTheme.useBackground(background);
            for (Role role : Role.values()) {
                assertEquals(role + " on " + Integer.toHexString(background), 0xFFB0B3B8, AmoledTheme.apply(0xFFB0B3B8, role));
                assertEquals(role + " on " + Integer.toHexString(background), 0xFF84878B, AmoledTheme.foreground(0xFF84878B, role.name()));
            }
            for (int grey = 0x2B; grey <= 0x42; grey++) {
                int colour = 0xFF000000 | grey * 0x010101;
                if (colour == background) continue;
                assertEquals("a card " + Integer.toHexString(colour), AmoledTheme.raise(background, colour - 0x212121),
                        AmoledTheme.apply(colour, Token.CARD_BACKGROUND));
                assertEquals("a fill " + Integer.toHexString(colour), AmoledTheme.raise(background, colour - 0x0D0D0D),
                        AmoledTheme.apply(colour, Token.PRIMARY_UI));
            }
        }
    }

    /** An unread row's tint drawn over a page: #5B513F under 25% of Facebook's blue. */
    @Test
    public void aTintIsDrawnOverThePage() {
        assertEquals(0xFF4F5F6F, AmoledTheme.over(SAND, AmoledTheme.UNREAD_ROW));
        assertEquals("no tint", SAND, AmoledTheme.over(SAND, 0x002D88FF));
        assertEquals("all tint", 0xFF2D88FF, AmoledTheme.over(SAND, 0xFF2D88FF));
    }

    /** Issue #214's cream, which the Light background color row takes. */
    private static final int CREAM = 0xFFFFF8E7;

    /** Light mode's backgrounds and fills, as Facebook's light FDS style names them. */
    enum LightToken { CARD_BACKGROUND_FLAT, WEB_WASH, TEXT_INPUT_BAR_BACKGROUND, SECONDARY_BUTTON_BACKGROUND, FBLITE_WASH }

    /**
     * Issue #214: in light mode the Light background color takes white's place, and each of
     * Facebook's light greys sits as far below it as it sits below white, so cards, comments and the
     * composer keep their steps. The wash under the feed goes no lower than a grey button, the
     * darkest grey Facebook puts text on, so the text held for that button reads on it too.
     */
    @Test
    public void lightModesWhitesTakeTheLightBackgroundColour() {
        DarkMode.answer(false);
        AmoledTheme.useLight(CREAM);

        assertEquals("a card", CREAM, AmoledTheme.apply(0xFFFFFFFF, Token.CARD_BACKGROUND));
        assertEquals("a popover", CREAM, AmoledTheme.apply(0xFFFFFFFF, Token.POPOVER_BACKGROUND));
        assertEquals("a comment's bubble", 0xFFF0EBDD, AmoledTheme.apply(0xFFF0F2F5, Token.COMMENT_BACKGROUND));
        assertEquals("the composer's bar", 0xFFF0EBDD, AmoledTheme.apply(0xFFF0F2F5, LightToken.TEXT_INPUT_BAR_BACKGROUND));
        assertEquals("a flat card", 0xFFF8F2E3, AmoledTheme.apply(0xFFF8F9FB, LightToken.CARD_BACKGROUND_FLAT));
        assertEquals("the web page", 0xFFF2EDDF, AmoledTheme.apply(0xFFF2F4F7, LightToken.WEB_WASH));
        assertEquals("a grey button", 0xFFE2DED1, AmoledTheme.apply(0xFFE2E5E9, LightToken.SECONDARY_BUTTON_BACKGROUND));
        assertEquals("the wash, held at the grey button", 0xFFE2DED1, AmoledTheme.apply(0xFFC9CCD1, Token.WASH));
    }

    /**
     * The mutation controls for #214's backgrounds: what isn't a light grey background keeps its
     * colour, the Video tab's dark ones included, and dark mode keeps its rules.
     */
    @Test
    public void aLightBackgroundColourLeavesTheRestAlone() {
        DarkMode.answer(false);
        AmoledTheme.useLight(CREAM);

        assertEquals("a divider", 0xFFD0D3D7, AmoledTheme.apply(0xFFD0D3D7, Token.DIVIDER));
        assertEquals("an unread row's pale blue", 0xFFE7F3FF, AmoledTheme.apply(0xFFE7F3FF, Token.NEW_NOTIFICATION_BACKGROUND));
        assertEquals("a pale blue card", 0xFFDDEDFE, AmoledTheme.apply(0xFFDDEDFE, Token.CARD_BACKGROUND));
        assertEquals("FbLite's darker wash", 0xFFB0B3B8, AmoledTheme.apply(0xFFB0B3B8, LightToken.FBLITE_WASH));
        assertEquals("the Video tab's card", 0xFF333334, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("the Video tab's page", 0xFF252728, AmoledTheme.apply(0xFF252728, Token.WASH));
        assertEquals("a translucent white", 0x80FFFFFF, AmoledTheme.apply(0x80FFFFFF, Token.CARD_BACKGROUND));
        assertEquals("no token to go on", 0xFFFFFFFF, AmoledTheme.apply(0xFFFFFFFF, "CARD_BACKGROUND"));

        DarkMode.answer(true);
        assertEquals("dark mode's card", 0xFF121213, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("dark mode's white", 0xFFFFFFFF, AmoledTheme.apply(0xFFFFFFFF, Token.CARD_BACKGROUND));
        assertEquals("dark mode's text", 0xFFB0B3B8, AmoledTheme.apply(0xFFB0B3B8, Role.SECONDARY_TEXT));
    }

    /** A blank row changes nothing in light mode. */
    @Test
    public void withoutALightBackgroundColourLightModeKeepsFacebooksColours() {
        DarkMode.answer(false);
        AmoledTheme.useLight(0);

        assertEquals(0xFFFFFFFF, AmoledTheme.apply(0xFFFFFFFF, Token.CARD_BACKGROUND));
        assertEquals(0xFFF0F2F5, AmoledTheme.apply(0xFFF0F2F5, Token.COMMENT_BACKGROUND));
        assertEquals(0xFF65686C, AmoledTheme.apply(0xFF65686C, Role.SECONDARY_TEXT));
        assertEquals(0xFF0866FF, AmoledTheme.apply(0xFF0866FF, Role.TAB_BAR_ACTIVE_ICON));
    }

    /**
     * Issue #214: on any color the row takes, each colour Facebook's light style gives a text or icon
     * role comes back at 4.5:1 or more on every surface the row makes, darkened toward black so a link
     * stays blue, and still apart from the primary text. Text that already has it keeps its colour.
     */
    @Test
    public void aLightBackgroundColourHoldsLightModesTextToAa() {
        DarkMode.answer(false);
        Map<String, int[]> light = FdsTokens.parse(MaterialYouTheme.FDS_LIGHT);
        int darkest = AmoledLightBackground.colour(AmoledLightBackground.DARKEST_ADMITTED_GREY);
        for (int background : new int[]{CREAM, 0xFFD8D8D8, 0xFFFFC0C0, darkest}) {
            AmoledTheme.useLight(background);
            int[] surfaces = {
                    AmoledTheme.apply(0xFFFFFFFF, Token.CARD_BACKGROUND),
                    AmoledTheme.apply(0xFFF0F2F5, Token.COMMENT_BACKGROUND),
                    AmoledTheme.apply(0xFFF0F2F5, LightToken.TEXT_INPUT_BAR_BACKGROUND),
                    AmoledTheme.apply(0xFFF8F9FB, LightToken.CARD_BACKGROUND_FLAT),
                    AmoledTheme.apply(0xFFF2F4F7, LightToken.WEB_WASH),
                    AmoledTheme.apply(0xFFE2E5E9, LightToken.SECONDARY_BUTTON_BACKGROUND),
                    AmoledTheme.apply(0xFFC9CCD1, Token.WASH),
            };
            int roles = 0;
            for (Role role : Role.values()) {
                int[] colours = light.get(role.name());
                if (colours == null) continue;
                roles++;
                for (int colour : colours) {
                    int held = AmoledTheme.apply(colour, role);
                    for (int surface : surfaces) {
                        assertTrue(role + " " + Integer.toHexString(colour) + " as " + Integer.toHexString(held) + " on "
                                + Integer.toHexString(surface), contrast(held, surface) >= AmoledTheme.TEXT_CONTRAST);
                    }
                    assertEquals(role + " darkens once", held, AmoledTheme.apply(held, role));
                }
            }
            assertEquals("every FDS role light mode has", 12, roles);
            int secondary = AmoledTheme.apply(0xFF65686C, Role.SECONDARY_TEXT);
            assertTrue("still apart from the primary text on " + Integer.toHexString(background),
                    contrast(AmoledTheme.apply(0xFF080809, Role.PRIMARY_TEXT), secondary)
                            >= AmoledBackground.TEXT_HIERARCHY);
        }

        AmoledTheme.useLight(CREAM);
        assertEquals("the primary text", 0xFF080809, AmoledTheme.apply(0xFF080809, Role.PRIMARY_TEXT));
        assertEquals("the secondary text darkens a little for the grey buttons", 0xFF5F6266,
                AmoledTheme.apply(0xFF65686C, Role.SECONDARY_TEXT));
        assertEquals("a link darkens a little", 0xFF005FC7, AmoledTheme.apply(0xFF0064D1, Role.BLUE_LINK));
        assertEquals("the selected tab's blue darkens a little", 0xFF0758DD,
                AmoledTheme.apply(0xFF0866FF, Role.TAB_BAR_ACTIVE_ICON));

        AmoledTheme.useLight(darkest);
        assertEquals("the secondary text", 0xFF242627, AmoledTheme.apply(0xFF65686C, Role.SECONDARY_TEXT));
        assertEquals("a link stays blue", 0xFF00254E, AmoledTheme.apply(0xFF0064D1, Role.BLUE_LINK));
    }

    /**
     * The mutation controls for #214's text: the Video tab's light text, whether FDS's dark style or
     * Mig's dark scheme gave it, keeps its colour on its dark surface, as do a translucent colour,
     * another token's colour and dark mode's text.
     */
    @Test
    public void aLightBackgroundColourLeavesTheVideoTabsText() {
        DarkMode.answer(false);
        AmoledTheme.useLight(0xFFA6A6A6);

        assertEquals("FDS's dark primary text", 0xFFF2F4F7, AmoledTheme.apply(0xFFF2F4F7, Role.PRIMARY_TEXT));
        assertEquals("FDS's dark secondary text", 0xFFB0B3B8, AmoledTheme.apply(0xFFB0B3B8, Role.SECONDARY_TEXT));
        assertEquals("FDS's dark link", 0xFF5AA7FF, AmoledTheme.apply(0xFF5AA7FF, Role.BLUE_LINK));
        for (Role role : new Role[]{Role.PRIMARY, Role.SECONDARY, Role.SECONDARY_EMPHASIZED, Role.TERTIARY,
                Role.PLACEHOLDER, Role.LINK}) {
            for (int colour : new int[]{0xFF8A8D91, 0xFFB0B3B8, 0xFFE4E6EB, 0xFF4599FF, 0xFF65686C}) {
                assertEquals("Mig's dark scheme " + role + " " + Integer.toHexString(colour), colour,
                        AmoledTheme.apply(colour, role));
            }
        }
        assertEquals("the nav bar's text, which light mode doesn't list", 0xFFE8EAEE,
                AmoledTheme.apply(0xFFE8EAEE, Role.NAV_BAR_TEXT));
        assertEquals("a translucent text colour", 0x8065686C, AmoledTheme.apply(0x8065686C, Role.SECONDARY_TEXT));
        assertEquals("a divider's grey", 0xFF65686C, AmoledTheme.apply(0xFF65686C, Token.DIVIDER));
        assertEquals("no token to go on", 0xFF65686C, AmoledTheme.apply(0xFF65686C, "SECONDARY_TEXT"));
    }

    /** FDS's blue text roles past TEXT_TOKENS, and ACCENT, whose light blue the dark style shares. */
    enum LightTextToken { TEXT_INPUT_ACTIVE_TEXT, REACTION_LIKE, FBLITE_ACCENT_ON_BACKGROUND, ACCENT }

    /**
     * Issue #214's reviewer gap: a text field's typed text, the Like button's blue and FB Lite's
     * accent text are held to 4.5:1 on the moved greys like a link is, while the Video tab's dark
     * blues and ACCENT keep theirs.
     */
    @Test
    public void aLightBackgroundColourHoldsLightModesBlueTextToAa() {
        DarkMode.answer(false);
        int darkest = AmoledLightBackground.colour(AmoledLightBackground.DARKEST_ADMITTED_GREY);
        for (int background : new int[]{CREAM, 0xFFD8D8D8, darkest}) {
            AmoledTheme.useLight(background);
            int button = AmoledTheme.apply(0xFFE2E5E9, LightToken.SECONDARY_BUTTON_BACKGROUND);
            for (LightTextToken token : new LightTextToken[]{LightTextToken.TEXT_INPUT_ACTIVE_TEXT,
                    LightTextToken.REACTION_LIKE, LightTextToken.FBLITE_ACCENT_ON_BACKGROUND}) {
                int colour = token == LightTextToken.TEXT_INPUT_ACTIVE_TEXT ? 0xFF0064D1 : 0xFF0866FF;
                int held = AmoledTheme.apply(colour, token);
                assertTrue(token + " on " + Integer.toHexString(background),
                        contrast(held, button) >= AmoledTheme.TEXT_CONTRAST);
            }
        }

        AmoledTheme.useLight(CREAM);
        assertEquals("the typed text, as a link", 0xFF005FC7, AmoledTheme.apply(0xFF0064D1, LightTextToken.TEXT_INPUT_ACTIVE_TEXT));
        assertEquals("the Like button's blue", 0xFF0758DD, AmoledTheme.apply(0xFF0866FF, LightTextToken.REACTION_LIKE));
        assertEquals("the dark style's typed text", 0xFF3E93F8, AmoledTheme.apply(0xFF3E93F8, LightTextToken.TEXT_INPUT_ACTIVE_TEXT));
        assertEquals("the dark style's Like blue", 0xFF3E93F8, AmoledTheme.apply(0xFF3E93F8, LightTextToken.REACTION_LIKE));
        assertEquals("ACCENT, a button's fill as well", 0xFF0866FF, AmoledTheme.apply(0xFF0866FF, LightTextToken.ACCENT));

        AmoledTheme.useLight(0);
        assertEquals("a blank row", 0xFF0064D1, AmoledTheme.apply(0xFF0064D1, LightTextToken.TEXT_INPUT_ACTIVE_TEXT));
    }

    /** Mig's colour enums' names, as its light scheme hands them over. */
    enum Mig { SURFACE, WASH, CARD, FILL, SECONDARY_BUTTON_ENABLED, SECONDARY_BUTTON_ON_CARD, PRIMARY, SECONDARY, LINK,
        LINE, ACCENT_GREEN, WHITE }

    /**
     * Issue #214's Mig screens: in light mode its light scheme's whites take the Light background
     * color as FDS's do, its grey fills step under it, and its text is held to 4.5:1 on the moved
     * grey button.
     */
    @Test
    public void migsLightSchemeTakesTheLightBackgroundColour() {
        DarkMode.answer(false);
        AmoledTheme.useLight(CREAM);

        assertEquals("a surface", CREAM, AmoledTheme.applyLight(0xFFFFFFFF, Mig.SURFACE));
        assertEquals("a card", CREAM, AmoledTheme.applyLight(0xFFFFFFFF, Mig.CARD));
        assertEquals("the wash", 0xFFF0EBDD, AmoledTheme.applyLight(0xFFF0F2F5, Mig.WASH));
        assertEquals("a chip's fill", 0xFFF0EBDD, AmoledTheme.applyLight(0xFFF0F2F5, Mig.FILL));
        assertEquals("a grey button", 0xFFE2DED1, AmoledTheme.applyLight(0xFFE2E5E9, Mig.SECONDARY_BUTTON_ENABLED));
        assertEquals("a grey button on a card", 0xFFE2DED1, AmoledTheme.applyLight(0xFFE2E5E9, Mig.SECONDARY_BUTTON_ON_CARD));

        int button = AmoledTheme.applyLight(0xFFE2E5E9, Mig.SECONDARY_BUTTON_ENABLED);
        for (Mig role : new Mig[]{Mig.PRIMARY, Mig.SECONDARY, Mig.LINK}) {
            for (int colour : new int[]{0xFF080809, 0xFF65686C, 0xFF0064D1, 0xFF8A8D91}) {
                int held = AmoledTheme.applyLight(colour, role);
                assertTrue(role + " " + Integer.toHexString(colour), contrast(held, button) >= AmoledTheme.TEXT_CONTRAST);
            }
        }
        assertEquals("the primary text", 0xFF080809, AmoledTheme.applyLight(0xFF080809, Mig.PRIMARY));
        assertEquals("the secondary text, as FDS's", 0xFF5F6266, AmoledTheme.applyLight(0xFF65686C, Mig.SECONDARY));
        assertEquals("a link, as FDS's", 0xFF005FC7, AmoledTheme.applyLight(0xFF0064D1, Mig.LINK));
    }

    /** The mutation controls for Mig's light scheme: blank, dark mode and every other colour are stock. */
    @Test
    public void migsLightSchemeIsStockWithoutTheRowAndForTheRest() {
        DarkMode.answer(false);
        AmoledTheme.useLight(0);
        assertEquals("a blank row", 0xFFFFFFFF, AmoledTheme.applyLight(0xFFFFFFFF, Mig.SURFACE));
        assertEquals("a blank row's text", 0xFF65686C, AmoledTheme.applyLight(0xFF65686C, Mig.SECONDARY));

        AmoledTheme.useLight(CREAM);
        assertEquals("a divider", 0xFFCED0D4, AmoledTheme.applyLight(0xFFCED0D4, Mig.LINE));
        assertEquals("a green", 0xFF31A24C, AmoledTheme.applyLight(0xFF31A24C, Mig.ACCENT_GREEN));
        assertEquals("white text", 0xFFFFFFFF, AmoledTheme.applyLight(0xFFFFFFFF, Mig.WHITE));
        assertEquals("a pale blue surface", 0xFFE7F3FF, AmoledTheme.applyLight(0xFFE7F3FF, Mig.SURFACE));
        assertEquals("a translucent white", 0x80FFFFFF, AmoledTheme.applyLight(0x80FFFFFF, Mig.SURFACE));
        assertEquals("no enum to go on", 0xFFFFFFFF, AmoledTheme.applyLight(0xFFFFFFFF, "SURFACE"));
        assertEquals("no token at all", 0xFFFFFFFF, AmoledTheme.applyLight(0xFFFFFFFF, null));

        DarkMode.answer(true);
        assertEquals("dark mode", 0xFFFFFFFF, AmoledTheme.applyLight(0xFFFFFFFF, Mig.SURFACE));
        assertEquals("dark mode's text", 0xFF65686C, AmoledTheme.applyLight(0xFF65686C, Mig.SECONDARY));
    }

    /**
     * Issue #214's server screens: Settings' page and the Menu's profile card are Bloks boxes whose
     * white fill the server sends, and a React screen's backgrounds come from its JavaScript. Both
     * are backgrounds only, so in light mode their whites and light greys take the Light background
     * color as route one's surfaces do.
     */
    @Test
    public void serverBackgroundsTakeTheLightBackgroundColour() {
        DarkMode.answer(false);
        AmoledTheme.useLight(CREAM);

        assertEquals("Settings' page", CREAM, AmoledTheme.bloksFill(0xFFFFFFFF));
        assertEquals("the search box", 0xFFF0EBDD, AmoledTheme.bloksFill(0xFFF0F2F5));
        assertEquals("a grey button", 0xFFE2DED1, AmoledTheme.bloksFill(0xFFE2E5E9));
        assertEquals("a React page", CREAM, AmoledTheme.react(0xFFFFFFFF));
        assertEquals("a React chip", 0xFFF0EBDD, AmoledTheme.react(0xFFF0F2F5));
        assertEquals("a React page, as ReactColours paints it", CREAM, ReactColours.background(0xFFFFFFFF, true, false));
    }

    /** The mutation controls for server backgrounds: blank, dark mode and every other colour are as they came. */
    @Test
    public void serverBackgroundsAreStockWithoutTheRowAndForTheRest() {
        DarkMode.answer(false);
        AmoledTheme.useLight(0);
        assertEquals("a blank row", 0xFFFFFFFF, AmoledTheme.bloksFill(0xFFFFFFFF));
        assertEquals("a blank row's React page", 0xFFFFFFFF, AmoledTheme.react(0xFFFFFFFF));
        assertEquals("a blank row, as ReactColours paints it", 0xFFFFFFFF, ReactColours.background(0xFFFFFFFF, true, false));

        AmoledTheme.useLight(CREAM);
        assertEquals("a blue button", 0xFF0866FF, AmoledTheme.bloksFill(0xFF0866FF));
        assertEquals("the Video tab's dark grey", 0xFF252728, AmoledTheme.bloksFill(0xFF252728));
        assertEquals("the Video tab's React strip", 0xFF252728, AmoledTheme.react(0xFF252728));
        assertEquals("a pale blue box", 0xFFE7F3FF, AmoledTheme.bloksFill(0xFFE7F3FF));
        assertEquals("a translucent white", 0x80FFFFFF, AmoledTheme.bloksFill(0x80FFFFFF));
        assertEquals("a fill the row already gave", CREAM, AmoledTheme.bloksFill(CREAM));
        assertEquals("server text keeps its white", 0xFFFFFFFF, AmoledTheme.parseColor("#FFFFFF"));

        DarkMode.answer(true);
        assertEquals("dark mode's white box", 0xFFFFFFFF, AmoledTheme.bloksFill(0xFFFFFFFF));
        assertEquals("dark mode's grey box", 0xFF333334, AmoledTheme.bloksFill(0xFF333334));
        assertEquals("dark mode's React strip", BLACK, AmoledTheme.react(0xFF252728));
    }
}
