/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Accent color" patch: one of Facebook's blues, on the links, buttons, switches and
 * the selected tab it draws in blue, becomes the accent the Accent color row picks. Facebook blue,
 * the row's first choice, is Facebook as it ships, and so is a paused Facebook.
 *
 * <p>It rides route one of the themes ({@link MaterialYouTheme}): the colours Facebook's FDS and Mig
 * resolvers answer. A colour changes only when it is one of Facebook's blues
 * ({@link MaterialYouTheme#isFacebookBlue}, or for FDS one of its pale {@link #FACEBOOK_TINTS}) and,
 * for FDS, comes with one of the {@link #TOKENS}, so a
 * chart, a badge or a colour somebody picked for a post keeps its own. It becomes the accent at the
 * lightness it had, in light mode and in dark, and keeps its alpha, so a tint stays a tint.
 *
 * <p>The accent's own tones are a tonal ramp of the preset's hue ({@link Preset}): CIE L* in steps
 * of ten at as much chroma as sRGB shows there. Text on a surface ({@link #TEXT_TOKENS}) is held to
 * 4.5:1 against the cards of the palette Facebook drew it from: lighter than
 * {@link #DARK_TEXT_LIGHTNESS} from the dark palette, darker than {@link #LIGHT_TEXT_LIGHTNESS} from
 * the light one. FDS picks the palette per screen, not app-wide (light mode's Video tab is dark), so
 * the palette comes from the blue Facebook resolved for the token ({@link #darkPalette}), and
 * Facebook's app-wide dark mode answer only settles the one blue both palettes give a text token.
 * Mig's dark scheme only answers for dark surfaces, so its text and glyph blues
 * ({@link #MIG_TEXT_TOKENS}) are held to the dark palette's. Fills keep Facebook's own lightness, so
 * the label on a button has the contrast Facebook gave it.
 *
 * <p>What Facebook inflates from layout XML reads its colour resources inside the framework, where
 * route one doesn't reach: {@link AccentResources} (route five) answers for those resources instead.
 * The fills of Bloks boxes and the colours of Bloks text ({@link #bloksFill}, {@link #bloksText},
 * route six) and of React Native text and views ({@link #reactText}, route seven) come from
 * Facebook's server or a screen's JavaScript, and get the same rules.
 *
 * <p>While the Material You theme is in the build it decides every colour this class would, so this
 * steps aside.
 */
public final class AccentColor {

    /** The accents on offer, each a hue in LCh and the chroma it's drawn at when sRGB has room for it. */
    public enum Preset {
        FACEBOOK("facebook", 0, 0),
        TEAL("teal", 200, 45),
        GREEN("green", 150, 50),
        PURPLE("purple", 315, 55),
        PINK("pink", 350, 55),
        ORANGE("orange", 55, 60),
        RED("red", 30, 65),
        INDIGO("indigo", 292, 55),
        AMBER("amber", 80, 60);

        /** What a settings file holds for this choice. It never changes once written. */
        public final String fileValue;

        final double hue;
        final double chroma;

        Preset(String fileValue, double hue, double chroma) {
            this.fileValue = fileValue;
            this.hue = hue;
            this.chroma = chroma;
        }

        /** The choice a settings file names, or null when it names none this build knows. */
        @Nullable
        public static Preset fromFile(@Nullable Object value) {
            if (!(value instanceof String)) return null;
            for (Preset preset : values()) {
                if (preset.fileValue.equals(value)) return preset;
            }
            return null;
        }
    }

    /**
     * FDS colour tokens Facebook draws in its blue: its links, buttons, switches, the active input
     * border and the selected tab. Each is a token of MaterialYouTheme's lists of tokens read from
     * Facebook 580 and 577, which AccentColorTest holds this to. The verified badge, the blue badge,
     * story rings and decorative icons are left in Facebook's blue on purpose.
     */
    static final String TOKENS = "ACCENT;ACCENT_DEEMPHASIZED;BLUE_LINK;CURSOR;DOT_BADGE_BLUE;FBLITE_ACCENT_ON_BACKGROUND;"
            + "HOSTED_VIEW_SELECTED_STATE;NEW_NOTIFICATION_BACKGROUND;PRIMARY_BUTTON_BACKGROUND;"
            + "PRIMARY_BUTTON_PRESSED_BACKGROUND;PRIMARY_DEEMPHASIZED_BUTTON_BACKGROUND;PRIMARY_DEEMPHASIZED_BUTTON_ICON;"
            + "PRIMARY_DEEMPHASIZED_BUTTON_TEXT;PROGRESS_RING_BLUE_BACKGROUND;PROGRESS_RING_BLUE_FOREGROUND;"
            + "REACTION_LIKE;STEPPER_ACTIVE;SWITCH_CHECKED_BACKGROUND_COLOR_ANDROID;SWITCH_CHECKED_BACKGROUND_COLOR_IOS;"
            + "SWITCH_CHECKED_HANDLE_FILL_COLOR_ANDROID;TAB_BAR_ACTIVE_ICON;TEXT_HIGHLIGHT;TEXT_INPUT_ACTIVE_INNER_BORDER;"
            + "TEXT_INPUT_ACTIVE_OUTER_BORDER;TEXT_INPUT_ACTIVE_TEXT;TOGGLE_ACTIVE_BACKGROUND";

    /** The tokens that draw text or a small icon over a surface, which {@link #withContrast} holds to 4.5:1. */
    static final String TEXT_TOKENS = "ACCENT;BLUE_LINK;PRIMARY_DEEMPHASIZED_BUTTON_ICON;PRIMARY_DEEMPHASIZED_BUTTON_TEXT;"
            + "REACTION_LIKE;TEXT_INPUT_ACTIVE_TEXT";

    /**
     * Two constants each of Mig's text colour enum and its glyph colour enum, the same on 577, 580
     * and 581. A Mig token whose enum has both of a pair is text or an icon; any other is a fill.
     */
    static final String MIG_TEXT_TOKENS = "LINK,SECONDARY_EMPHASIZED;DECORATIVE_BLUE,TERTIARY";

    static final Set<String> TOKEN_SET = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(TOKENS.split(";"))));
    static final Set<String> TEXT_TOKEN_SET =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(TEXT_TOKENS.split(";"))));
    /** Whether each Mig token enum is text or icons ({@link #MIG_TEXT_TOKENS}), as first seen. */
    private static final Map<Class<?>, Boolean> MIG_TEXT_ENUMS = new ConcurrentHashMap<>();

    /** The colours only Facebook's dark styles give each token, and the ones its light and dark styles share. */
    private static final Map<String, int[]> DARK_ONLY = MaterialYouTheme.parseTokens(MaterialYouTheme.FDS_DARK);
    private static final Map<String, int[]> BOTH = MaterialYouTheme.parseTokens(MaterialYouTheme.FDS_SHARED);

    /**
     * The L* between the blues Facebook gives text in its light palette (#0866FF, L* 48, at the most)
     * and in its dark one (#1D85FC, L* 56, at the least), for a blue neither list names.
     */
    static final double PALETTE_SPLIT = 52;

    /** The surfaces text is held against: light mode's page and white card, and dark mode's lightest card. */
    static final int LIGHT_SURFACE = 0xFFF0F2F5;
    static final int DARK_SURFACE = 0xFF333334;

    /**
     * How dark text over a light surface may be at most, and how light text over a dark one may be at
     * least, in CIE L*: the lightness that gives 4.5:1 against {@link #LIGHT_SURFACE} and
     * {@link #DARK_SURFACE}, with a point of L* to spare for the 8-bit rounding of the colour, which every other surface of the theme beats. AccentColorTest holds both.
     */
    static final double LIGHT_TEXT_LIGHTNESS = 45;
    static final double DARK_TEXT_LIGHTNESS = 65;

    /** The tonal ramp of each preset, built when it's first used. */
    private static final Map<Preset, TonePalette> PALETTES = new EnumMap<>(Preset.class);

    private AccentColor() {}

    /**
     * Route one, for FDS: a colour a resolver returns for {@code token}. A colour route five's
     * table gave the resource it came from is Facebook's again first ({@link AccentResources#facebookColour}).
     *
     * @return the accent for one of Facebook's blues on a token in {@link #TOKENS}, otherwise {@code color}
     */
    public static int fds(int color, Object token) {
        HookStatus.invoked(FamilyNames.ACCENT_COLOR);
        if (!Utils.settingsReady() || !(token instanceof Enum)) return color;
        Preset preset = chosen();
        if (preset == null) return color;
        return fds(AccentResources.facebookColour(color, preset), ((Enum<?>) token).name(), preset,
                DarkMode.hasAnswered(), DarkMode.on());
    }

    /**
     * Route one, for Mig: a colour the Mig dark scheme returns. That scheme only answers for a dark
     * surface, so one of Facebook's opaque blues becomes the accent whatever the token, and a text or
     * glyph one keeps 4.5:1 on the dark palette's cards.
     */
    public static int mig(int color, Object token) {
        HookStatus.invoked(FamilyNames.ACCENT_COLOR);
        if (!Utils.settingsReady() || (color >>> 24) != 0xFF) return color;
        Preset preset = chosen();
        if (preset == null) return color;
        return mig(AccentResources.facebookColour(color, preset), preset, token);
    }

    /** The FDS token a Bloks box's fill is themed as: a primary button's, which keeps the lightness Facebook gave it. */
    static final String BLOKS_FILL_TOKEN = "PRIMARY_BUTTON_BACKGROUND";

    /**
     * Route six: the fill of a Bloks box (582 {@code LX/4Nl;}), such as the profile's Add to story
     * button. Facebook's server sends those fills as hex colours, so no resolver or resource gives
     * them. One of Facebook's blues becomes the accent the way a primary button's fill does.
     */
    public static int bloksFill(int color) {
        HookStatus.invoked(FamilyNames.ACCENT_COLOR);
        if (!Utils.settingsReady()) return color;
        Preset preset = chosen();
        if (preset == null) return color;
        return bloksFill(AccentResources.facebookColour(color, preset), preset);
    }

    static int bloksFill(int color, Preset preset) {
        return fds(color, BLOKS_FILL_TOKEN, preset, false, false);
    }

    /**
     * The FDS token text from Bloks and React Native is themed as: a link's, held to 4.5:1 on the
     * palette it was drawn from.
     */
    static final String LINK_TEXT_TOKEN = "BLUE_LINK";

    /**
     * Route six for Bloks text: the colour of a span of Bloks text (582 {@code LX/4MC;}), such as a
     * profile's bio link, which Facebook's server sends as a hex colour for light mode and one for
     * dark. One of Facebook's blues becomes the accent the way a link does.
     */
    public static int bloksText(int color) {
        return linkText(color);
    }

    /**
     * Route seven, through {@link ReactColours}: a colour React Native text is drawn in, such as
     * Marketplace's. The screen's JavaScript sends it as an int, so no resolver or resource gives
     * it, and no token comes with it. One of Facebook's blues becomes the accent the way a link does.
     */
    static int reactText(int color) {
        return linkText(color);
    }

    private static int linkText(int color) {
        HookStatus.invoked(FamilyNames.ACCENT_COLOR);
        if (!Utils.settingsReady()) return color;
        Preset preset = chosen();
        if (preset == null) return color;
        return fds(color, LINK_TEXT_TOKEN, preset, DarkMode.hasAnswered(), DarkMode.on());
    }

    /** Route seven for a React Native view's background, such as a button's: themed as a Bloks box's fill is. */
    static int reactBackground(int color) {
        HookStatus.invoked(FamilyNames.ACCENT_COLOR);
        if (!Utils.settingsReady()) return color;
        Preset preset = chosen();
        if (preset == null) return color;
        return bloksFill(color, preset);
    }

    /** The accent in force, or null for Facebook's own blue: unset, paused, or the Material You theme in charge. */
    @Nullable
    static Preset chosen() {
        Preset preset = Settings.ACCENT_COLOR.get();
        if (preset == Preset.FACEBOOK || SettingsStatus.materialYouTheme()) return null;
        return preset;
    }

    /**
     * Facebook's own pale blues in light mode, too grey for {@link MaterialYouTheme#isFacebookBlue}:
     * a selected chip and Marketplace's pill (#DDEDFE on 582), the deemphasized accent (#EBF5FF) and a
     * new notification's row (#E7F3FF). Only these values, so any other pale blue keeps its colour.
     * AccentResourceBlues.kt's FACEBOOK_TINTS is the same list, which AccentTintParityTest holds.
     */
    static final int[] FACEBOOK_TINTS = {0xDDEDFE, 0xE7F3FF, 0xEBF5FF};

    /** One of Facebook's blues, or one of its {@link #FACEBOOK_TINTS} at any alpha. */
    static boolean isAccentBlue(int color) {
        if (MaterialYouTheme.isFacebookBlue(color)) return true;
        for (int tint : FACEBOOK_TINTS) {
            if ((color & 0xFFFFFF) == tint) return true;
        }
        return false;
    }

    static int fds(int color, String token, Preset preset, boolean answered, boolean dark) {
        if (preset == Preset.FACEBOOK || !TOKEN_SET.contains(token) || !isAccentBlue(color)) return color;
        int themed = palette(preset).sameLightness(TonePalette.ACCENT, color);
        if (!TEXT_TOKEN_SET.contains(token)) return themed;
        Boolean fromDark = darkPalette(token, color, answered, dark);
        return fromDark == null ? themed : withContrast(preset, themed, fromDark);
    }

    /**
     * Whether Facebook drew {@code token}'s {@code color} from its dark palette: a colour only its
     * dark styles give the token is, one its light and dark styles share follows Facebook's dark mode
     * answer ({@code answered}, {@code dark}), and any other blue goes by its lightness. Null for a
     * shared colour before Facebook has answered, which then keeps Facebook's own lightness.
     */
    @Nullable
    static Boolean darkPalette(String token, int color, boolean answered, boolean dark) {
        if (listed(DARK_ONLY.get(token), color)) return true;
        if (listed(BOTH.get(token), color)) return answered ? dark : null;
        return TonePalette.lstar(color) >= PALETTE_SPLIT;
    }

    private static boolean listed(@Nullable int[] colours, int color) {
        if (colours == null) return false;
        for (int value : colours) {
            if ((value & 0xFFFFFF) == (color & 0xFFFFFF)) return true;
        }
        return false;
    }

    static int mig(int color, Preset preset, @Nullable Object token) {
        if (preset == Preset.FACEBOOK || !MaterialYouTheme.isFacebookBlue(color)) return color;
        int themed = palette(preset).sameLightness(TonePalette.ACCENT, color);
        return migText(token) ? withContrast(preset, themed, true) : themed;
    }

    /**
     * Whether a Mig {@code token} is text or an icon: its enum holds both constants of one pair in
     * {@link #MIG_TEXT_TOKENS}. Facebook's build renames the enum's fields, so the constants are read
     * from the fields of the enum's own type and named by {@link Enum#name}.
     */
    static boolean migText(@Nullable Object token) {
        if (!(token instanceof Enum)) return false;
        Class<?> type = ((Enum<?>) token).getDeclaringClass();
        Boolean text = MIG_TEXT_ENUMS.get(type);
        if (text != null) return text;
        text = false;
        try {
            Set<String> names = new HashSet<>();
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
                field.setAccessible(true);
                Object constant = field.get(null);
                if (constant instanceof Enum) names.add(((Enum<?>) constant).name());
            }
            for (String pair : MIG_TEXT_TOKENS.split(";")) {
                if (names.containsAll(Arrays.asList(pair.split(",")))) text = true;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.printException(() -> "Accent color: could not read a Mig token's enum", failure);
        }
        MIG_TEXT_ENUMS.put(type, text);
        return text;
    }

    /** The accent {@code color}, with its lightness moved only as far as 4.5:1 against the cards of the palette needs. */
    static int withContrast(Preset preset, int color, boolean dark) {
        double lightness = TonePalette.lstar(color);
        double held = dark ? Math.max(lightness, DARK_TEXT_LIGHTNESS) : Math.min(lightness, LIGHT_TEXT_LIGHTNESS);
        if (held == lightness) return color;
        return palette(preset).atLightness(TonePalette.ACCENT, held, color & 0xFF000000);
    }

    /** The preset's tones, L* 0 to 100 in steps of ten, as a palette whose accent family is the ramp. */
    static synchronized TonePalette palette(Preset preset) {
        TonePalette palette = PALETTES.get(preset);
        if (palette == null) {
            int[] ramp = new int[TonePalette.TONES.length];
            for (int i = 0; i < ramp.length; i++) {
                ramp[i] = TonePalette.gamutColour(TonePalette.TONES[i], preset.chroma, preset.hue);
            }
            palette = new TonePalette(new int[][]{ramp, ramp, ramp}, false);
            PALETTES.put(preset, palette);
        }
        return palette;
    }

    /** WCAG's contrast ratio of two opaque colours. */
    static double contrast(int first, int second) {
        double a = TonePalette.luminance(first) + 0.05;
        double b = TonePalette.luminance(second) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }
}
