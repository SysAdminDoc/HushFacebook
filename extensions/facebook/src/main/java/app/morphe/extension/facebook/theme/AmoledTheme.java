/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/AmoledTheme.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.theme;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "[General] AMOLED black theme" patch. It holds the rule for route one and route
 * four, the read back of route two in light mode, and the rules for the two system bars. The four
 * routes are described with the patch, in
 * patches/src/main/kotlin/app/morphe/patches/facebook/layout/theme/AmoledThemePatch.kt.
 *
 * <p>The decision needs the token, because a colour alone cannot show the difference between a card
 * and a dark divider. The names of the tokens carry that difference, and R8 cannot rename an enum
 * constant.
 *
 * <p>The decision also needs the colour, because the same tokens serve light mode, where a card is
 * white. It needs Facebook's own answer for dark mode as well ({@link DarkMode}): the Video tab stays
 * dark in light mode, from the same dark style and the same colour resources as dark mode, and in
 * light mode every route leaves Facebook's colour. The system bars ask the window's theme instead,
 * see {@link #statusBar}.
 *
 * <p>Black is the default. The patch's Background colour option can ask for another dark colour
 * (issue #34), which {@link #backgroundColour} then answers: every route and both bars put it where
 * they put black, and a raised surface sits the same step above it that it sits above black.
 * Facebook's own black stays black on every route. A lighter colour, one where those steps would
 * leave Facebook's text under 4.5:1, gets smaller steps in the same order ({@link #lightestStep},
 * {@link #scaled}), and its text and icon roles come back just light enough ({@link #foreground}).
 *
 * <p>The Background color row in Hushfacebook's settings swaps that colour once Facebook is running
 * (issue #214, {@link #load}). The patch wrote its colour into resources and code, so route three
 * reads {@link #PAGE} where the patch wrote one, {@link AmoledResources} gives the resources the
 * row's colour as each activity is created, and route one and the colour resource reads take the
 * patch's colour ({@link #written}) wherever it still comes through as the row's.
 *
 * <p>{@link #apply} runs for each colour on each layout pass. Thus it makes no object and writes no
 * log. The one thing it adds is a count in Hook status, a hash lookup and an increment once the
 * first call has made the family's entry, which is what shows a report that the theme ran at all.
 * The colour resource reads ({@link #getColor(Context, int)} and the rest) run as often and add no
 * count: in dark mode they cost two compares and a volatile read.
 */
public final class AmoledTheme {

    private AmoledTheme() {}

    /**
     * The largest value that a channel can have and still count as a background. Measured on a
     * device: a surface is {@code #252728}, but a divider is {@code #3A3B3C}.
     */
    static final int MAX_CHANNEL = 0x2A;

    /**
     * The largest value that a channel of a raised surface can have: a card, a comment or a popover
     * that sits on a background. FDS's dark styles in 577 and 580 give a card {@code #333334}, a
     * popover {@code #3B3C3E}, and the Video tab's popover {@code #3E4042}, the lightest of them.
     * Route one takes a background token in this band to near black ({@link #RAISED_SHIFT}), issue
     * #27.
     */
    static final int MAX_RAISED_CHANNEL = 0x42;

    /**
     * How far each channel of a raised surface goes down. A card's {@code #333334} becomes
     * {@code #121213}, near black but still apart from the black page under it, and a popover keeps
     * its step above the card. The darkest grey in the band ends at {@code #0A0A0A}, not black.
     *
     * <p>{@code CARD_BORDER} isn't a background token, so route one leaves it at Facebook's own
     * {@code #333334} rather than shifting it too. Most cards draw no border stroke at all in dark
     * mode, which is why the near black rather than pure black matters here in the first place; on
     * the few that do, the unshifted border now reads as a visible, lighter edge against the card's
     * own near black, which is the same edge this shift exists to preserve, not a fault to fix.
     */
    private static final int RAISED_SHIFT = 0x21;

    /**
     * The largest value that a channel of a card's grey sent as text can have (route four). Facebook
     * builds its search results from server templates, and a Page's card there comes as
     * {@code #333334} ({@code #333333} in the report), issue #27. A string comes with no token, and
     * the next greys up that a server sends, {@code #3A3B3C}, {@code #3B3C3E} and {@code #3E4042},
     * are a button's, a popover's or a divider's own fill, so they keep Facebook's grey.
     */
    static final int MAX_SERVER_CARD_CHANNEL = 0x36;

    /**
     * How far each channel of an input or a pill's fill goes down: less than {@link #RAISED_SHIFT}.
     * {@link #FILL_TOKENS} shows its shape only through this fill, not a border or the text on it,
     * so shifting it as far as a card would leave it at about 1.1:1 against the black page, close
     * enough to disappear. This keeps a fill at about {@code #262627} from {@code #333334}, near
     * 1.5:1, while a card still goes to the near black {@link #RAISED_SHIFT} gives it.
     */
    private static final int FILL_SHIFT = 0x0D;

    /**
     * Names of the tokens for an input or a pill's fill, rather than a card, comment or popover:
     * Mig's {@code PRIMARY_UI} (a search field, a pill) and its FDS counterpart. Both read
     * {@code #333334} in dark mode ({@code LX/Dol;->AP6}), the same as a card, but a card is a panel
     * with its own edge while these show only through the fill itself.
     */
    private static final Set<String> FILL_TOKENS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("PRIMARY_UI", "BACKGROUND_PRIMARY_UI")));

    /**
     * FDS's tokens for a secondary button's fill, such as the Menu's See more and Log out: #333334,
     * or #46484B for the opaque and floating ones, in dark mode. Route one leaves them as Facebook
     * drew them, unless Black cards is on ({@link #flatCards}): then the card under one takes the
     * page's colour, and the button takes an input's fill ({@link #buttonFill}) so it keeps a shape
     * on the page, issue #191.
     */
    private static final Set<String> BUTTON_TOKENS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SECONDARY_BUTTON_BACKGROUND", "SECONDARY_BUTTON_BACKGROUND_OPAQUE",
            "SECONDARY_BUTTON_BACKGROUND_FLOATING")));

    /** The largest value a channel of a secondary button's dark fill can have: the opaque one's #46484B. */
    static final int MAX_BUTTON_CHANNEL = 0x4B;

    /** FDS's dark card, the grey an input's fill is a {@link #FILL_SHIFT} below. */
    private static final int CARD_GREY = 0xFF333334;

    /**
     * FDS's token for the row of an unread notification. Facebook's dark theme gives it a 10% blue,
     * {@code #192D88FF}, which reads as a blue row over Facebook's grey page and as almost black over
     * AMOLED's black one ({@code #050E1A}), issue #72. The Notifications tab's row asks for it
     * ({@code NotificationsTetraComponent}, 581 {@code LX/9BT;->A1A}).
     */
    private static final String NEW_NOTIFICATION = "NEW_NOTIFICATION_BACKGROUND";

    /**
     * The alpha an unread notification's tint gets in dark mode: 25% where Facebook gives 10%. Over
     * black, Facebook's blue then shows as {@code #0B2240}, a row that stands out the way it does
     * on Facebook's grey page, with its text and blue dot still above 4:1.
     */
    static final int NEW_NOTIFICATION_ALPHA = 0x40;

    /**
     * The largest difference between the channels of a background. A grey has almost none. A dark
     * green or dark brown banner has much more, and it keeps its colour.
     */
    private static final int MAX_SPREAD = 8;

    /**
     * The smallest value each channel of a light grey route one moves in light mode has
     * ({@link #light}). Facebook's light WASH, #C9CCD1, is its darkest background grey, and no dark
     * style gives a background token one this light (MaterialYouTokenFixtureTest).
     */
    static final int LIGHT_MIN_CHANNEL = 0xC0;

    /**
     * The largest value that a channel of a status bar colour can have and still turn black. The
     * bar is chrome, so it goes further than {@link #MAX_CHANNEL}: the Video tab asks for
     * {@code #333334}, and Facebook's lightest dark chrome grey is {@code #3A3B3C}. The Material
     * You theme's status bar takes the same band.
     */
    static final int MAX_BAR_CHANNEL = 0x40;

    /**
     * The tokens that name a background area. The short names come from Mig and the long names
     * from FDS. One set holds both, because only the Mig surface enum declares the short names.
     */
    private static final Set<String> BACKGROUND_TOKENS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // Mig.
                    "WASH",
                    "SURFACE",
                    "CARD",
                    "ELEVATION",
                    "BANNER",
                    "PRIMARY_UI",
                    // FDS: the page.
                    "WEB_WASH",
                    "FBLITE_WASH",
                    "SURFACE_BACKGROUND",
                    "BACKGROUND_SURFACE",
                    "DEVICE_BACKGROUND",
                    "BACKGROUND_DEEMPHASIZED",
                    // FDS: panels on the page.
                    "CARD_BACKGROUND",
                    "CARD_BACKGROUND_FLAT",
                    "CARD_BACKGROUND_LEGACY_WEB",
                    "BACKGROUND_CARD",
                    "BACKGROUND_ELEVATION",
                    "LIST_CELL_BACKGROUND",
                    "ATTACHMENT_FOOTER_BACKGROUND",
                    "ENTITY_HEADER_BACKGROUND",
                    // FDS: comments.
                    "COMMENT_BACKGROUND",
                    "COMMENT_BACKGROUND_DEEMPHASIZED",
                    // FDS: sheets and popovers.
                    "BOTTOM_SHEET_BACKGROUND_DEEMPHASIZED",
                    "BOTTOM_SHEET_INSET_BACKGROUND",
                    "POPOVER_BACKGROUND",
                    "FADED_POPOVER_BACKGROUND",
                    // FDS: chrome.
                    "NAV_BAR_BACKGROUND",
                    "TAB_BAR_BACKGROUND",
                    "BACKGROUND_BANNER",
                    "BACKGROUND_PRIMARY_UI")));

    /**
     * Light grey fills that light mode's Light background color moves as well ({@link #light}): the
     * composer's bar and the grey buttons. Dark mode leaves them, so they aren't {@link #BACKGROUND_TOKENS}.
     */
    private static final Set<String> LIGHT_BACKGROUND_TOKENS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "TEXT_INPUT_BAR_BACKGROUND",
                    "SECONDARY_BUTTON_BACKGROUND",
                    "SECONDARY_BUTTON_BACKGROUND_OPAQUE")));

    /**
     * How far above the background colour the lightest surface route one makes sits: an input's
     * fill from the lightest raised grey, {@link #MAX_RAISED_CHANNEL} less {@link #FILL_SHIFT}.
     * Every step a surface takes is at most this.
     */
    static final int LIGHTEST_STEP = MAX_RAISED_CHANNEL - FILL_SHIFT;

    /** WCAG 2.2's AA contrast for normal text, which a lighter background's text roles keep. */
    static final double TEXT_CONTRAST = 4.5;

    /**
     * The colour tokens of text and icons that sit on the surfaces this class makes, which a
     * lighter Background colour lifts ({@link #foreground}). FDS's long names, and the short names
     * of Mig's text and glyph enums (PRIMARY, SECONDARY, TERTIARY and the rest), which no FDS token
     * uses. MaterialYouTokenFixtureTest holds the FDS names and their dark colours to each fixture.
     */
    static final Set<String> TEXT_TOKENS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // FDS.
                    "PRIMARY_TEXT",
                    "PRIMARY_ICON",
                    "SECONDARY_TEXT",
                    "SECONDARY_ICON",
                    "PLACEHOLDER_TEXT",
                    "PLACEHOLDER_ICON",
                    "META_TEXT",
                    "META_ICON",
                    "BLUE_LINK",
                    "NAV_BAR_TEXT",
                    "NAV_BAR_ICON",
                    "TAB_BAR_ACTIVE_ICON",
                    "TAB_BAR_INACTIVE_ICON",
                    "FBLITE_STRONG_SECONDARY",
                    // Mig.
                    "PRIMARY",
                    "SECONDARY",
                    "SECONDARY_EMPHASIZED",
                    "TERTIARY",
                    "PLACEHOLDER",
                    "LINK")));

    /**
     * FDS's blue text roles that light mode's Light background color holds too, past
     * {@link #TEXT_TOKENS} ({@link #light}): a text field's typed text, the Like button's blue and FB
     * Lite's accent text. Facebook's light theme gives each a blue ({@link MaterialYouTheme#FDS_LIGHT})
     * that no dark style gives the token, so the Video tab keeps its own (MaterialYouTokenFixtureTest).
     * ACCENT isn't one: its light blue is the dark style's too.
     */
    static final Set<String> LIGHT_TEXT_TOKENS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "TEXT_INPUT_ACTIVE_TEXT",
                    "REACTION_LIKE",
                    "FBLITE_ACCENT_ON_BACKGROUND")));

    /**
     * Mig's light grey fills that light mode's Light background color moves, past its surfaces in
     * {@link #BACKGROUND_TOKENS} ({@link #applyLight}): a pill or a chip's fill and the grey buttons.
     */
    static final Set<String> MIG_LIGHT_FILLS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "FILL",
                    "SECONDARY_BUTTON_ENABLED",
                    "SECONDARY_BUTTON_ON_CARD")));

    /**
     * Facebook's tint for an unread notification's row in dark mode (FDS's NEW_NOTIFICATION_BACKGROUND,
     * #192D88FF), at the alpha {@link #unreadRow} gives it. Over a lighter Background colour it can
     * be the lightest thing text sits on.
     */
    static final int UNREAD_ROW = NEW_NOTIFICATION_ALPHA << 24 | 0x2D88FF;

    /** Opaque black, what route two writes over a dark grey unless the patch asks for another colour. */
    private static final int BLACK = 0xFF000000;

    /**
     * The colour the patch wrote over Facebook's dark greys, in its resources (route two) and its
     * code (route three): {@link #backgroundColour} as the patch filled it in.
     */
    private static int written = backgroundColour();

    /**
     * What a background turns into: {@link #written}, unless the Background color row asked for
     * another colour ({@link #load}).
     */
    private static int background = written;

    /**
     * Route three's colour. Each dark grey Facebook writes in its code reads this field where the
     * patch once wrote its colour, so the Background color row reaches them too (issue #214). It's
     * {@link #written} until {@link #load} reads the row, so a class Facebook sets up before that
     * keeps the patch's colour.
     */
    public static volatile int PAGE = written;

    /** {@link #PAGE} as the long a {@code const-wide/32} gave, sign extended the same way. */
    public static volatile long PAGE_WIDE = written;

    /**
     * How far above the background its lightest surface sits: {@link #lightestStep} as the patch
     * filled it in. {@link #LIGHTEST_STEP}, unless a lighter Background colour asked for less.
     */
    private static int step = lightestStep();

    /**
     * With a lighter Background colour, the luminance of the lightest surface text sits on, which
     * the text roles keep {@link #TEXT_CONTRAST} against. Unused at {@link #LIGHTEST_STEP}.
     */
    private static double textSurface = textSurface(background, step);

    /**
     * The Light background color row's colour (issue #214), or 0 while it's blank: what route one
     * gives Facebook's white backgrounds in its light mode ({@link #light}). Volatile and written
     * after {@link #lightTextSurface}, so a thread that sees the colour sees its surface too.
     */
    private static volatile int lightBackground;

    /**
     * With {@link #lightBackground}, the luminance of the grey text sits on in light mode
     * ({@link AmoledLightBackground#textSurface}), which the light theme's text roles keep
     * {@link #TEXT_CONTRAST} against.
     */
    private static double lightTextSurface;

    /**
     * Route two's rewrites, sorted by resource id, and the colour Facebook had at each:
     * {@link #routeTwoColours} as the patch filled it in.
     */
    private static int[] rewrittenIds;
    private static int[] facebookColours;

    static {
        useRouteTwo(routeTwoColours());
    }

    /**
     * Route one: a colour that a resolver returns.
     *
     * <p>A background in Facebook's dark mode turns black. A raised surface on it, a card for one,
     * turns near black instead: FDS's dark card is {@code #333334}, and on AMOLED's black page it
     * stayed a grey slab (issue #27), while a black card would lose its edge, since FDS draws no
     * border round one in dark mode. The grey's steps between surfaces are kept, so what Facebook
     * draws on a card (a translucent input, a chip) still shows against it.
     *
     * @param token an enum constant. Only its name is used.
     * @return the background colour (black by default) for a background up to {@link #MAX_CHANNEL},
     * the near black of {@link #RAISED_SHIFT} for one up to {@link #MAX_RAISED_CHANNEL},
     * {@link #FILL_SHIFT} for an input or a pill's fill ({@link #FILL_TOKENS}), each that far above
     * the background colour, in Facebook's dark mode, or {@code color} unchanged. The background
     * colour itself comes back as it is, from route two's resources. An unread notification's tint
     * comes back stronger ({@link #unreadRow}). On a lighter Background colour the steps are
     * smaller ({@link #scaled}) and a text role comes back lifted ({@link #foreground}). With Black
     * cards on, a raised surface comes back as the background colour and a secondary button's fill
     * as {@link #buttonFill}. In light mode a Light background color takes Facebook's whites
     * ({@link #light}).
     */
    public static int apply(int color, Object token) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        // Every rule below waits for dark mode, so light mode has only the Light background color's.
        if (lightBackground != 0 && !DarkMode.on()) {
            return token instanceof Enum ? light(color, ((Enum<?>) token).name()) : color;
        }
        // A background whose resource still holds the patch's colour, read past AmoledResources'
        // table, takes the row's (#214). Before the grey test: the patch's colour needn't be grey.
        // Not when the patch wrote black: Facebook's own black reads the same, and stays black.
        if (color == written && written != BLACK && background != written && token instanceof Enum && DarkMode.on()
                && BACKGROUND_TOKENS.contains(((Enum<?>) token).name())) {
            return background;
        }
        if (!isDarkNeutral(color, MAX_RAISED_CHANNEL)) {
            if ((color >>> 24) < NEW_NOTIFICATION_ALPHA) {
                return token instanceof Enum && DarkMode.on() ? unreadRow(color, ((Enum<?>) token).name()) : color;
            }
            if (isDarkNeutral(color, MAX_BUTTON_CHANNEL) && token instanceof Enum && DarkMode.on()
                    && BUTTON_TOKENS.contains(((Enum<?>) token).name())) {
                return flatCards() ? buttonFill() : color;
            }
            return step < LIGHTEST_STEP && token instanceof Enum && DarkMode.on()
                    ? foreground(color, ((Enum<?>) token).name())
                    : color;
        }
        if (!(token instanceof Enum) || !DarkMode.on()) return color;
        String name = ((Enum<?>) token).name();
        if (BUTTON_TOKENS.contains(name)) {
            return !isDarkNeutral(color, MAX_CHANNEL) && flatCards() ? buttonFill() : color;
        }
        if (!BACKGROUND_TOKENS.contains(name)) return color;
        if (color == BLACK || color == background) return color;

        if (isDarkNeutral(color, MAX_CHANNEL)) return background;
        boolean fill = FILL_TOKENS.contains(name);
        if (!fill && flatCards()) return background;
        // Every channel of a grey above the black band is at least MAX_CHANNEL + 1 - MAX_SPREAD,
        // above either shift, so no channel borrows from the next.
        int shift = fill ? FILL_SHIFT : RAISED_SHIFT;
        return raise(background, scaled(color - shift * 0x010101));
    }

    /**
     * Whether Black cards is on (issue #191): a card, a comment or a popover takes the page's
     * colour rather than the near black of {@link #RAISED_SHIFT}. Off until the settings are ready,
     * and while Hushfacebook is paused.
     */
    static boolean flatCards() {
        return Utils.settingsReady() && Settings.AMOLED_FLAT_CARDS.get();
    }

    /**
     * A secondary button's fill with Black cards on: what an input's fill is ({@link #FILL_SHIFT}
     * below a card's grey, #262627 on black), so a button that sat on a card still shows once the
     * card is gone into the page.
     */
    static int buttonFill() {
        return raise(background, scaled(CARD_GREY - FILL_SHIFT * 0x010101));
    }

    /**
     * A text or icon colour on a lighter Background colour (issue #34): a text role
     * ({@link #TEXT_TOKENS}) that would fall under {@link #TEXT_CONTRAST} on the lightest surface
     * the theme makes from it comes back just light enough to keep it, moved toward white so a
     * blue link stays blue. Facebook's secondary grey #B0B3B8 has 3.71:1 on #5B513F, and comes
     * back as #D6D8DA.
     *
     * <p>Only a Background colour the patch let through with a smaller step ({@link #lightestStep})
     * gets this. On black and every colour that keeps Facebook's own text readable, each colour
     * comes back as it came, as it does for any other token, a translucent colour, and text darker
     * than the surface. Material You asks the same after it recolours a text role.
     */
    static int foreground(int color, String token) {
        if (step >= LIGHTEST_STEP || (color >>> 24) != 0xFF || !TEXT_TOKENS.contains(token)) return color;
        double needs = TEXT_CONTRAST * (textSurface + 0.05) - 0.05;
        double luminance = TonePalette.luminance(color);
        if (luminance >= needs || luminance <= textSurface) return color;
        return lifted(color, needs);
    }

    /**
     * {@code color}, or the first step from it toward white that reads at {@code ratio} or more on
     * the opaque {@code surface}: white when none does. The Hushfacebook settings page lifts its
     * text and switches this way over a lighter Background colour (issue #214).
     */
    public static int readable(int color, int surface, double ratio) {
        double needs = ratio * (TonePalette.luminance(surface) + 0.05) - 0.05;
        return TonePalette.luminance(color) >= needs ? color : lifted(color, needs);
    }

    /** The first step of {@code color} toward white whose luminance is {@code needs} or more. */
    private static int lifted(int color, double needs) {
        // White passes on every surface the patch lets through, and each step toward it is at least
        // as light as the one before, so the lightest passing step is the first.
        int low = 0;
        int high = 0xFF;
        while (high - low > 1) {
            int middle = (low + high) >>> 1;
            if (TonePalette.luminance(towardWhite(color, middle)) >= needs) high = middle;
            else low = middle;
        }
        return towardWhite(color, high);
    }

    /** {@code color} moved {@code amount}/255 of the way to white in each channel. */
    private static int towardWhite(int color, int amount) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        red += ((0xFF - red) * amount + 0x7F) / 0xFF;
        green += ((0xFF - green) * amount + 0x7F) / 0xFF;
        blue += ((0xFF - blue) * amount + 0x7F) / 0xFF;
        return (color & 0xFF000000) | red << 16 | green << 8 | blue;
    }

    /**
     * Route one in Facebook's light mode with a Light background color (issue #214). A background
     * token's white or light grey ({@link #isLightNeutral}) takes the row's colour as far below it
     * as the grey sits below white, no lower than a grey button ({@link AmoledLightBackground#fill}).
     * A text role in Facebook's light colours comes back darkened toward black where it would fall
     * under {@link #TEXT_CONTRAST} on that button ({@link AmoledLightBackground#held}), so a blue link
     * stays blue. The Video tab,
     * which stays dark in light mode, keeps its colours: its backgrounds are dark greys, and its
     * text comes with dark mode's colours, which no FDS token has in light mode
     * ({@link LightTheme}). The blue text roles of {@link #LIGHT_TEXT_TOKENS} are held the same way.
     */
    static int light(int color, String token) {
        if ((color >>> 24) != 0xFF) return color;
        if (BACKGROUND_TOKENS.contains(token) || LIGHT_BACKGROUND_TOKENS.contains(token)) {
            return lightSurface(color);
        }
        if (!(TEXT_TOKENS.contains(token) || LIGHT_TEXT_TOKENS.contains(token)) || !LightTheme.text(token, color)) {
            return color;
        }
        return AmoledLightBackground.held(color, lightTextSurface);
    }

    /**
     * Route one for Mig's light scheme, which only light mode asks (issue #214). With the Light
     * background color row set and Facebook in light mode, a Mig surface ({@link #BACKGROUND_TOKENS})
     * or light fill ({@link #MIG_LIGHT_FILLS}) in white or a light grey takes the row's colour the
     * way FDS's do ({@link #light}), and a text role ({@link #TEXT_TOKENS}) comes back darkened
     * toward black where it would fall under {@link #TEXT_CONTRAST} on the moved grey. The light
     * scheme answers light mode only, so no dark colour comes through here. Unset, or in dark mode,
     * each colour comes back as it came.
     *
     * @param token an enum constant. Only its name is used.
     */
    public static int applyLight(int color, Object token) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        if (lightBackground == 0 || (color >>> 24) != 0xFF || !(token instanceof Enum) || DarkMode.on()) return color;
        String name = ((Enum<?>) token).name();
        if (BACKGROUND_TOKENS.contains(name) || MIG_LIGHT_FILLS.contains(name)) return lightSurface(color);
        return TEXT_TOKENS.contains(name) ? AmoledLightBackground.held(color, lightTextSurface) : color;
    }

    /**
     * A background's white or light grey ({@link #isLightNeutral}) in light mode, as the Light
     * background color takes it ({@link AmoledLightBackground#fill}). A colour that's already one of
     * the row's fills comes back as it is: a surface resource AmoledResources gave the row's colour
     * reads that way, and filling it again would darken it.
     */
    static int lightSurface(int color) {
        int row = lightBackground;
        if (row == 0 || !isLightNeutral(color) || AmoledResources.givesLight(color, row)) return color;
        return AmoledLightBackground.fill(row, color);
    }

    /**
     * {@link #light} for a hook that knows its colour's role by name, such as the comment time
     * ({@link CommentTimeColour}): Facebook's colour while the Light background color row is blank.
     */
    static int lightNamed(int color, String token) {
        return lightBackground == 0 ? color : light(color, token);
    }

    /** The Light background color row's colour, or 0 while it's blank. */
    static int lightBackground() {
        return lightBackground;
    }

    /**
     * Which text colours are Facebook's light theme's, read from Material You's table the first
     * time light mode asks. Not through MaterialYouTheme, whose start binds the wallpaper palette.
     */
    private static final class LightTheme {
        private static final Map<String, int[]> LIGHT = FdsTokens.parse(MaterialYouTheme.FDS_LIGHT);

        /**
         * Whether {@code color} is the one {@link MaterialYouTheme#FDS_LIGHT} gives {@code token}.
         * Mig's names never are: Mig's light scheme goes through {@link #applyLight}, so a Mig colour
         * here comes from its dark scheme, a dark surface's such as the Video tab's.
         */
        static boolean text(String token, int color) {
            int[] light = LIGHT.get(token);
            if (light == null) return false;
            for (int value : light) {
                if (value == color) return true;
            }
            return false;
        }
    }

    /**
     * A surface's step above the background, each channel scaled from {@link #LIGHTEST_STEP} down
     * to {@link #step}, rounded. At {@link #LIGHTEST_STEP} it comes back as it is, so black and the
     * colours the theme always let through keep their surfaces exactly. A lighter Background colour
     * keeps its card, popover and input in the same order, closer together.
     */
    static int scaled(int raw) {
        if (step >= LIGHTEST_STEP) return raw;
        return (raw & 0xFF000000) | scaledChannel((raw >> 16) & 0xFF) << 16
                | scaledChannel((raw >> 8) & 0xFF) << 8 | scaledChannel(raw & 0xFF);
    }

    private static int scaledChannel(int channel) {
        return (channel * step + LIGHTEST_STEP / 2) / LIGHTEST_STEP;
    }

    /**
     * The luminance of the lightest surface text sits on over {@code colour}: its lightest raised
     * surface, {@code lightest} above it, or an unread notification's tinted row on it.
     */
    static double textSurface(int colour, int lightest) {
        double raised = TonePalette.luminance(raise(colour, lightest * 0x010101));
        return Math.max(raised, TonePalette.luminance(over(colour, UNREAD_ROW)));
    }

    /** {@code tint} drawn over the opaque {@code base}. */
    static int over(int base, int tint) {
        int alpha = tint >>> 24;
        int red = (((tint >> 16) & 0xFF) * alpha + ((base >> 16) & 0xFF) * (0xFF - alpha) + 0x7F) / 0xFF;
        int green = (((tint >> 8) & 0xFF) * alpha + ((base >> 8) & 0xFF) * (0xFF - alpha) + 0x7F) / 0xFF;
        int blue = ((tint & 0xFF) * alpha + (base & 0xFF) * (0xFF - alpha) + 0x7F) / 0xFF;
        return BLACK | red << 16 | green << 8 | blue;
    }

    /**
     * The colour route one gives an unread notification's row in dark mode, issue #72: a
     * translucent tint for {@link #NEW_NOTIFICATION} keeps its colour at
     * {@link #NEW_NOTIFICATION_ALPHA}. A clear colour, one already that strong, and any other token's
     * colour come back as they are. Material You asks the same, to know AMOLED's tint as
     * Facebook's.
     */
    static int unreadRow(int color, String token) {
        int alpha = color >>> 24;
        if (alpha == 0 || alpha >= NEW_NOTIFICATION_ALPHA || !NEW_NOTIFICATION.equals(token)) return color;
        return (NEW_NOTIFICATION_ALPHA << 24) | (color & 0x00FFFFFF);
    }

    /**
     * The Data mode banner of Facebook Flex, issue #86. Carriers with Flex (free Facebook in text
     * mode) show it as a strip across the page under the top bar, but its wrapper asks for
     * CARD_BACKGROUND, so route one gave it a card's near black ({@code #121213} on black) and it
     * read as a grey band on the black page. The patch sends the wrapper's colour here, after route
     * one.
     *
     * @return the background colour, in Facebook's dark mode, for a dark grey or for what route one
     * makes of a card: up to {@link #MAX_RAISED_CHANNEL} less {@link #RAISED_SHIFT} ({@link #scaled}) above the
     * background colour in each channel, which may have a hue of its own (issue #34). Otherwise, as
     * in light mode, {@code color} unchanged.
     */
    public static int flexBanner(int color) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        if (!DarkMode.on() || (color >>> 24) != 0xFF) return color;
        if (isDarkNeutral(color, MAX_CHANNEL)) return background;
        int red = ((color >> 16) & 0xFF) - ((background >> 16) & 0xFF);
        int green = ((color >> 8) & 0xFF) - ((background >> 8) & 0xFF);
        int blue = (color & 0xFF) - (background & 0xFF);
        int high = Math.max(red, Math.max(green, blue));
        int low = Math.min(red, Math.min(green, blue));
        return low >= 0 && high <= scaledChannel(MAX_RAISED_CHANNEL - RAISED_SHIFT) && high - low <= MAX_SPREAD
                ? background
                : color;
    }

    /**
     * Route three for a colour Facebook writes into its code for its dark theme only, issue #191:
     * the patch sends here each colour {@code const} that is the first thing Facebook does once its
     * own dark check said yes. The Create story tile at the start of the stories tray is #333334
     * written that way, a card's grey above the greys route three blackens, so it stayed grey with
     * every other card gone black.
     *
     * <p>With Material You in the build, its stand-in for the same {@code const} comes after this
     * one, so it gets the page's colour this gives, and black stays black there.
     *
     * @return the background colour for a card's grey, up to {@link #MAX_SERVER_CARD_CHANNEL} as
     * route four takes one with no token, with Black cards on, in Facebook's dark mode, otherwise
     * {@code color}. Black stays black, and so does a lighter grey such as a popover's #3B3C3E.
     */
    public static int darkBranch(int color) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        if (color == BLACK || !isDarkNeutral(color, MAX_SERVER_CARD_CHANNEL) || !DarkMode.on() || !flatCards()) return color;
        return background;
    }

    /** The colour resource the patch adds, which holds the Background colour, for {@link #tokenResource}. */
    static final String PAGE_COLOUR = "hushfacebook_amoled_page";

    /** {@link #PAGE_COLOUR}'s id: 0 until {@link #pageColour} looks it up, -1 when the build has none. */
    private static volatile int pageColourId;

    /**
     * Route one for a token's colour resource, issue #191. FDS's token lookup (582 {@code
     * LX/24i;->A02}) answers the colour resource a token's theme attribute points at, and the code
     * that asked reads the resource itself: the band behind the Menu's Log out button is a drawable
     * of CARD_BACKGROUND_FLAT's, past every resolver, so it stayed #333334 with Black cards on.
     * Facebook's dark style points a dozen tokens at that one #333334 resource, cards, buttons and
     * inputs alike, so a read of it can't tell which token it's for. The patch sends each answer here
     * with the lookup's context and token instead, and a card's answer becomes a resource of the
     * page's colour.
     *
     * @return {@link #PAGE_COLOUR}'s id for a background token whose colour {@link #apply} makes the
     * page's, with Black cards on in Facebook's dark mode, otherwise {@code id} as it came. An
     * input's fill and a button aren't the page's, so they keep theirs, and so does every resource
     * when the build has no {@link #PAGE_COLOUR}.
     */
    public static int tokenResource(int id, @Nullable Context context, Object token) {
        if (id == 0 || context == null || !(token instanceof Enum) || !DarkMode.on() || !flatCards()) return id;
        if (!BACKGROUND_TOKENS.contains(((Enum<?>) token).name())) return id;
        int color;
        try {
            color = context.getColor(id);
        } catch (Resources.NotFoundException notAColour) {
            return id;
        }
        if (color == background || apply(color, token) != background) return id;
        int page = pageColour(context);
        return page > 0 ? page : id;
    }

    /** {@link #PAGE_COLOUR}'s id, looked up once: -1 when the build has none. */
    static int pageColour(Context context) {
        int id = pageColourId;
        if (id != 0) return id;
        id = context.getResources().getIdentifier(PAGE_COLOUR, "color", context.getPackageName());
        pageColourId = id = id != 0 ? id : -1;
        return id;
    }

    static void forgetPageColourForTests() {
        pageColourId = 0;
    }

    /**
     * Route four: a colour that the server sends as text.
     *
     * <p>The patch replaces each call to {@link Color#parseColor} in the app with a call to this
     * method. A server-driven screen, such as Settings or the search results, gets its colours as
     * strings like {@code "#FF252728"}. No token comes with a string, so the colour alone decides,
     * as in route two and route three.
     *
     * <p>In Facebook's dark mode a background grey turns black, and a card's grey up to
     * {@link #MAX_SERVER_CARD_CHANNEL} goes to the near black route one gives a card: the Page
     * card in search results, issue #27. A translucent colour, such as the 10% white of the card's
     * message box, is left as it is and stays a step above the card.
     *
     * <p>A text that is not a colour throws the same exception as before, so the callers see no
     * change. In light mode the colour is left as parsed.
     */
    public static int parseColor(String text) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        return withoutToken(Color.parseColor(text));
    }

    /**
     * A background a React Native screen sets on a view (ReactColours), such as the strip behind
     * Marketplace home's chips. It comes from the screen's JavaScript with no token, so it takes
     * route four's rule. Only backgrounds come here, so in light mode with the Light background
     * color row set a white or light grey one takes the row's colour as route one's surfaces do
     * ({@link #lightSurface}, issue #214).
     */
    static int react(int color) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        if (lightBackground != 0 && !DarkMode.on()) return lightSurface(color);
        return withoutToken(color);
    }

    /**
     * Route four for the fill of a Bloks box (582 {@code LX/4Nl;}) or a native template's box (the
     * Menu's profile card), a server colour that comes with its role (issue #214).
     * {@link #parseColor} can't tell a white page from the white label on a blue button, so it
     * leaves light mode alone. A box's fill is always a background, so with the
     * Light background color row set and Facebook in light mode a white or light grey one takes the
     * row's colour as route one's surfaces do ({@link #lightSurface}). Unset, or in dark mode, where
     * {@link #parseColor} has already answered for it, the fill comes back as it came.
     */
    public static int bloksFill(int color) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        return lightBackground == 0 || DarkMode.on() ? color : lightSurface(color);
    }

    /**
     * FDS's dark greys for secondary text and icons, sorted: SECONDARY_TEXT's #A1A4A9 and #B0B3B8,
     * which META_TEXT and PLACEHOLDER_TEXT share.
     */
    private static final int[] BLOKS_TEXT_GREYS = {0xFFA1A4A9, 0xFFB0B3B8};

    /**
     * Route four for a Bloks colour pair, the colour a Bloks screen's model parses from the server's
     * light and dark strings (issue #34). It takes {@link #parseColor}'s rule, and in dark mode one of
     * FDS's secondary text greys ({@link #BLOKS_TEXT_GREYS}) gets a text role's lift on a lighter
     * Background colour ({@link #foreground}). The Menu's profile card draws "Create Facebook Page"
     * this way, #B0B3B8 at 3.5:1 on #5B513F's card. A pair carries no token, so only those greys are
     * lifted: none of them is a surface in FDS's dark table, and a blue button's fill keeps its colour.
     * Light mode's table has both greys too (disabled text, a switch's disabled handle, a feed gap), so
     * nothing is lifted until Facebook has said dark mode is on.
     */
    public static int parseBloks(String text) {
        int parsed = parseColor(text);
        return bloksText(parsed, parsed);
    }

    /**
     * {@code color}, which a Bloks colour pair parsed as {@code parsed} and Material You may have
     * recoloured since, lifted as secondary text when {@code parsed} is one of
     * {@link #BLOKS_TEXT_GREYS} once Facebook has said dark mode is on.
     */
    static int bloksText(int parsed, int color) {
        if (!DarkMode.saidOn() || Arrays.binarySearch(BLOKS_TEXT_GREYS, parsed) < 0) return color;
        return foreground(color, "SECONDARY_TEXT");
    }

    /**
     * Whether route four's rule takes [color] as one of AMOLED's, black and the Background colour
     * included, even where it gives the colour back as it came. With a Background colour of
     * #212121 a card's #333334 comes back as #333334, so only this says AMOLED decided it.
     */
    static boolean ownsWithoutToken(int color) {
        return isDarkNeutral(color, MAX_SERVER_CARD_CHANNEL) && DarkMode.on();
    }

    /** Route four's rule for a colour that comes with no token. With Black cards on, a card's grey takes the page's colour too. */
    private static int withoutToken(int color) {
        if (!ownsWithoutToken(color)) return color;
        if (color == BLACK || color == background) return color;
        return isDarkNeutral(color, MAX_CHANNEL) || flatCards()
                ? background
                : raise(background, scaled(color - RAISED_SHIFT * 0x010101));
    }

    /**
     * Route two, read back: Facebook reading a colour resource with {@code Context.getColor}. The
     * patch sends every such call here, and the three below get the other ways to read one.
     *
     * <p>Route two writes black over Facebook's dark greys in its colour resources, and Facebook reads
     * some of those in light mode too. Its dark palette, #252728 and the rest, is in the default
     * configuration and only its dark style uses it, except that the Video tab stays dark in light
     * mode: its bottom bar is {@code getColor} of the #252728 resource the dark style uses (580
     * {@code LX/4KA}, 577 {@code LX/4Bb}), and its themed context asks that dark style. So in
     * light mode, a black that route two wrote reads as the colour Facebook had there. In dark mode
     * the black stands.
     */
    public static int getColor(Context context, int id) {
        return resourceColour(context.getColor(id), id);
    }

    /** The same for {@code Resources.getColor(int)}. */
    @SuppressWarnings("deprecation")
    public static int getColor(Resources resources, int id) {
        return resourceColour(resources.getColor(id), id);
    }

    /** The same for {@code Resources.getColor(int, Theme)}. */
    public static int getColor(Resources resources, int id, @Nullable Resources.Theme theme) {
        return resourceColour(resources.getColor(id, theme), id);
    }

    /**
     * The same for {@code TypedArray.getColor}, how FDS's theme resolver reads a token's colour from
     * the Video tab's dark style: the attribute points at the colour resource route two rewrote.
     */
    public static int getColor(TypedArray array, int index, int fallback) {
        int color = array.getColor(index, fallback);
        if (color != background && color != written) return color;
        // In dark mode only the patch's colour under the row's needs the id.
        if (DarkMode.on() && (color != written || background == written)) return color;
        return resourceColour(color, array.getResourceId(index, 0));
    }

    /**
     * Resource {@code id}'s colour as it should read: Facebook's own in light mode where route two
     * wrote {@code color} there ({@link #lightMode}), and in dark mode the Background color row's
     * where the resource still holds the patch's colour, as resources AmoledResources' table hasn't
     * reached do (issue #214).
     */
    static int resourceColour(int color, int id) {
        if (color != background && color != written) return color;
        if (!DarkMode.on()) return lightMode(color, id);
        return color == written && background != written && (AmoledResources.wrote(id) || (id != 0 && id == pageColourId))
                ? background
                : color;
    }

    /**
     * Facebook's own colour for resource {@code id} when route two wrote {@code color} there in light
     * mode: the patch's colour, or the row's that AmoledResources gives the same resource.
     */
    static int lightMode(int color, int id) {
        if ((color != background && color != written) || DarkMode.on()) return color;
        int at = Arrays.binarySearch(rewrittenIds, id);
        return at >= 0 ? facebookColours[at] : color;
    }

    /**
     * Filled in by the patch: the colour resources route two wrote black over, as
     * {@code "id=colour;..."} in hex, with Facebook's colour for each. Only those
     * whose night value route two left alone, so the colour a black stands for is always this one.
     */
    @Nullable
    public static String routeTwoColours() {
        return null;
    }

    /** Filled in by the patch: the Background colour option's colour, black when it's blank. */
    public static int backgroundColour() {
        return BLACK;
    }

    /**
     * Filled in by the patch: how far above the Background colour its lightest surface sits. It's
     * {@link #LIGHTEST_STEP} for black and any colour that keeps Facebook's own text readable on
     * every surface, and less for a lighter colour the patch lets through with lifted text (issue
     * #34): there the surfaces step up less, and the text roles get {@link #foreground}.
     */
    public static int lightestStep() {
        return LIGHTEST_STEP;
    }

    /**
     * Reads the Background color row (issue #214), once, as Facebook's application starts: the patch
     * calls this right after the extension gets its context, before Facebook sets itself up, and the
     * application's resources take the row's colour from here ({@link AmoledResources}). A blank
     * row, a paused Facebook, a colour the row's rule refuses and a build with Material You keep the
     * patch's colour. Material You recolours the same greys, and its resources aren't this table's.
     * The Light background color row is read here too ({@link #loadLight}), and the application's
     * resources take either row's colour once both are read.
     */
    public static void load() {
        try {
            if (!Utils.settingsReady() || SettingsStatus.materialYouTheme()) return;
            try {
                loadLight();
            } catch (Throwable failure) {
                // Its own catch, so a failure here still leaves the Background color row read.
                Logger.printException(() -> "AMOLED: could not read the Light background color row", failure);
            }
            loadBackground();
            Context context = Utils.getContext();
            if (context != null) AmoledResources.recolour(context);
        } catch (Throwable failure) {
            // Throwable, as in every hook: this runs as the application starts.
            Logger.printException(() -> "AMOLED: could not read the Background color row", failure);
        }
    }

    /**
     * The Background color row's part of {@link #load}. A blank row and a colour its rule refuses
     * keep the patch's colour.
     */
    private static void loadBackground() {
        String text = Settings.AMOLED_BACKGROUND.get();
        String stored = AmoledBackground.stored(text);
        if (stored == null) {
            Logger.printInfo(() -> "AMOLED: Background color " + text + " can't be used, so the patch's colour stays");
            return;
        }
        Integer colour = AmoledBackground.colour(stored);
        if (stored.isEmpty() || colour == null || colour == written) return;
        use(colour, AmoledBackground.lightestStep(colour));
        Logger.printDebug(() -> "AMOLED: Background color " + stored + " in place of the patch's");
    }

    /**
     * The Light background color row's part of {@link #load} (issue #214). A blank row and a colour
     * its rule refuses leave Facebook's own light colours.
     */
    private static void loadLight() {
        String text = Settings.AMOLED_LIGHT_BACKGROUND.get();
        String stored = AmoledLightBackground.stored(text);
        if (stored == null) {
            Logger.printInfo(() -> "AMOLED: Light background color " + text + " can't be used, so Facebook's white stays");
            return;
        }
        Integer colour = AmoledLightBackground.colour(stored);
        if (stored.isEmpty() || colour == null) return;
        useLight(colour);
        Logger.printDebug(() -> "AMOLED: Light background color " + stored + " in light mode");
    }

    /**
     * Puts the Light background color row's {@code colour} in use for route one in light mode, or
     * none with 0. Package-visible for tests.
     */
    static void useLight(int colour) {
        lightTextSurface = colour == 0 ? 0 : AmoledLightBackground.textSurface(colour);
        lightBackground = colour;
    }

    /**
     * Puts the Background color row's {@code colour} in use for every route, with its
     * {@link #lightestStep}, over the patch's {@link #written}. Package-visible for tests.
     */
    static void use(int colour, int lightest) {
        background = colour;
        step = lightest;
        textSurface = textSurface(colour, lightest);
        PAGE = colour;
        PAGE_WIDE = colour;
    }

    /** The colour the patch wrote, which the Background color row can stand in for. */
    static int written() {
        return written;
    }

    /** The background colour in use: the Background color row's, or {@link #backgroundColour} as the patch filled it in. */
    public static int background() {
        return background;
    }

    /**
     * The background colour in use and the surfaces route one makes on it, darkest first: page,
     * card, popover and input fill ({@link AmoledBackground#surfaces}). The Hushfacebook settings
     * page takes its colours from these when the background isn't black (issue #214).
     */
    public static int[] surfaces() {
        return AmoledBackground.surfaces(background, step);
    }

    /** Package-visible for tests: puts a background colour of {@link #backgroundColour}'s kind in use. */
    static void useBackground(int colour) {
        useBackground(colour, LIGHTEST_STEP);
    }

    /**
     * Package-visible for tests: a background colour and its {@link #lightestStep}, as the patch fills
     * them, so it's {@link #written} too and no row stands in for it.
     */
    static void useBackground(int colour, int lightest) {
        written = colour;
        use(colour, lightest);
    }

    /** {@code base} with each channel of {@code step} added, as far as white. Both are opaque. */
    static int raise(int base, int step) {
        int red = Math.min(0xFF, ((base >> 16) & 0xFF) + ((step >> 16) & 0xFF));
        int green = Math.min(0xFF, ((base >> 8) & 0xFF) + ((step >> 8) & 0xFF));
        int blue = Math.min(0xFF, (base & 0xFF) + (step & 0xFF));
        return BLACK | red << 16 | green << 8 | blue;
    }

    /** Package-visible for tests: puts a table of {@link #routeTwoColours}'s form in use. */
    static void useRouteTwo(@Nullable String table) {
        String[] entries = table == null || table.isEmpty() ? new String[0] : table.split(";");
        // An app's resource ids are positive, so the id in the high half sorts the pairs by id.
        long[] pairs = new long[entries.length];
        for (int i = 0; i < entries.length; i++) {
            int equals = entries[i].indexOf('=');
            pairs[i] = Long.parseLong(entries[i].substring(0, equals), 16) << 32
                    | Long.parseLong(entries[i].substring(equals + 1), 16);
        }
        Arrays.sort(pairs);
        int[] ids = new int[pairs.length];
        int[] colours = new int[pairs.length];
        for (int i = 0; i < pairs.length; i++) {
            ids[i] = (int) (pairs[i] >>> 32);
            colours[i] = (int) pairs[i];
        }
        facebookColours = colours;
        rewrittenIds = ids;
    }

    /**
     * The status bar: the colour Facebook is about to paint it, and whether Facebook's theme is dark.
     *
     * <p>On Android 15 and newer Facebook paints the bar itself, through one method that also
     * remembers the last colour per window and skips a colour it already painted. The patch calls
     * this first thing in that method, so the colour painted and the colour remembered are the same.
     * A tab's bar colour often comes from a token resolver that route one doesn't reach. Back from
     * Recent Apps, the Video tab's {@code #333334} (its CARD_BACKGROUND_DARK token) was painted over
     * the black it had at a fresh launch, issue #22.
     *
     * <p>Light mode asks the same token for the same {@code #333334}, so here the colour can't tell
     * the themes apart, and the patch passes Facebook's own answer for the window.
     *
     * @return the background colour (black by default) for an opaque dark grey in the dark theme,
     * or {@code color} unchanged.
     */
    public static int statusBar(int color, boolean dark) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        return dark && color != BLACK && isDarkNeutral(color, MAX_BAR_CHANNEL) ? background : color;
    }

    /**
     * The navigation bar: the colour Facebook is about to paint it, and whether Facebook's theme is
     * dark. The patch calls this first thing in the method that paints it.
     *
     * <p>The Video tab keeps a dark surface in light mode too, and writes its bars' {@code #252728}
     * into code for both themes. Route three leaves a colour a method hands to a system bar alone,
     * so light mode keeps it, and this hook turns it black in the dark theme. The band is route
     * three's own: the lighter greys Facebook gives the bar under a sheet keep theirs.
     *
     * @return the background colour (black by default) for an opaque dark grey in the dark theme,
     * or {@code color} unchanged.
     */
    public static int navigationBar(int color, boolean dark) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        return dark && color != BLACK && isDarkNeutral(color, MAX_CHANNEL) ? background : color;
    }

    /** True for an opaque grey with each channel at or below {@code maxChannel}. */
    static boolean isDarkNeutral(int color, int maxChannel) {
        if ((color >>> 24) != 0xFF) return false;

        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        int high = Math.max(red, Math.max(green, blue));
        int low = Math.min(red, Math.min(green, blue));
        return high <= maxChannel && high - low <= MAX_SPREAD;
    }

    /** True for an opaque grey with each channel at or above {@link #LIGHT_MIN_CHANNEL}, white included. */
    static boolean isLightNeutral(int color) {
        if ((color >>> 24) != 0xFF) return false;

        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        int high = Math.max(red, Math.max(green, blue));
        int low = Math.min(red, Math.min(green, blue));
        return low >= LIGHT_MIN_CHANNEL && high - low <= MAX_SPREAD;
    }
}
