/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.misc.WindowsAbove;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Helper for the "Material You theme" patch: Facebook's dark mode in the colours of the phone's
 * wallpaper palette on Android 12 and newer, and of {@link TonePalette#FALLBACK} on Android 11.
 *
 * <p>Every colour it changes keeps its lightness ({@link TonePalette#sameLightness}). A grey becomes
 * the palette's neutral at the same L*, and one of Facebook's blues becomes the palette's accent at
 * the same L*, so text, icons and dividers keep the contrast Facebook gave them. Anything else,
 * and anything this class doesn't recognise, is left as Facebook sent it.
 *
 * <p>Light mode is left alone. A colour token is only recoloured when it arrives with the exact
 * colour Facebook's dark theme gives that token ({@link #FDS_DARK}), and a literal, a server colour
 * or a colour resource only when it is one of the dark surfaces no light screen uses
 * ({@link #SURFACES}). The Video tab is the exception that takes the rest: it stays dark in light
 * mode, built from the same dark style, so every route also asks Facebook's own answer for dark
 * mode ({@link DarkMode}). Facebook's buttons, badges and story rings are the same blue in both
 * themes, so for those ({@link #FDS_SHARED}) that answer is all there is, and they wait for it. The
 * system bars ask Facebook's dark check for the window ({@link #statusBar}, {@link #navigationBar}).
 *
 * <p>With the AMOLED black theme in the same build, AMOLED goes first. Its black backgrounds and
 * near-black cards reach this class in colours no dark-theme token has, so they stay as AMOLED made
 * them, and this class recolours text, icons, dividers and the buttons and inputs AMOLED leaves grey.
 */
public final class MaterialYouTheme {

    private MaterialYouTheme() {}

    /**
     * FDS colour tokens and the colours Facebook's dark theme gives them, read from the dark and
     * darker FDS styles of Facebook 580.0.0.51.74 and 577.0.0.50.72, where they are the same. Only
     * tokens whose dark colour differs from their light one are here, and only neutral greys and
     * Facebook's blues. Eight hex digits are a translucent colour, such as a selected chip's tint or
     * an unread notification's. The Like button's own blue after you like is here (issue #37), while
     * the other reactions, charts, code and anything drawn on media or on a colour keep Facebook's
     * colours. MaterialYouTokenFixtureTest holds this list to both fixtures.
     */
    static final String FDS_DARK = "ACCENT=1D85FC;ACCENT_DEEMPHASIZED=331D85FC,192D88FF;ACTIVE_DOT=E2E5E9;"
            + "ATTACHMENT_FOOTER_BACKGROUND=F2F4F7;BLUE_LINK=5AA7FF,3E93F8;"
            + "BOTTOM_SHEET_BACKGROUND_DEEMPHASIZED=252728;BOTTOM_SHEET_HANDLE=6F7276;"
            + "BOTTOM_SHEET_INSET_BACKGROUND=333334;CARD_BACKGROUND=333334;CARD_BACKGROUND_FLAT=333334;"
            + "CARD_BACKGROUND_LEGACY_WEB=252728;CARD_BORDER=333334;CLIENT_BOTTOM_SHEET_PRESSED=3B3C3E;"
            + "COMMENT_BACKGROUND=333334;COMMENT_THREADING_LINES=46484B;DISABLED_BUTTON_BACKGROUND=333334;"
            + "DISABLED_ICON=6F7276;DISABLED_TEXT=6F7276,505255;DIVIDER=65686C,505255;DOT_BADGE_BLUE=1D85FC;"
            + "ENTITY_HEADER_BACKGROUND=252728;FBLITE_ACCENT_ON_BACKGROUND=1D85FC;FBLITE_STRONG_SECONDARY=D0D3D7;"
            + "FBLITE_TEXT_INPUT_INACTIVE_INNER_BORDER=6F7276;FBLITE_WASH=080809;FEED_GAP_VERTICAL=101011;"
            + "HOSTED_VIEW_SELECTED_STATE=191D85FC;INACTIVE_DOT=84878B;LIST_CELL_BACKGROUND=252728;"
            + "META_ICON=B0B3B8;META_TEXT=B0B3B8;NAV_BAR_BACKGROUND=252728;NAV_BAR_ICON=E8EAEE;NAV_BAR_TEXT=E8EAEE;"
            + "NEW_NOTIFICATION_BACKGROUND=192D88FF;PLACEHOLDER_ICON=B0B3B8,84878B;PLACEHOLDER_TEXT=B0B3B8,84878B;"
            + "POPOVER_BACKGROUND=3B3C3E,3E4042;PRIMARY_BUTTON_TEXT=252728;"
            + "PRIMARY_DEEMPHASIZED_BUTTON_BACKGROUND=331D85FC,262D88FF;PRIMARY_DEEMPHASIZED_BUTTON_ICON=75B6FF;"
            + "PRIMARY_DEEMPHASIZED_BUTTON_TEXT=75B6FF,0866FF;PRIMARY_ICON=F2F4F7;PRIMARY_TEXT=F2F4F7;"
            + "PROGRESS_RING_DISABLED_FOREGROUND=6F7276;REACTION_LIKE=3E93F8;SECONDARY_BUTTON_BACKGROUND=333334;"
            + "SECONDARY_BUTTON_BACKGROUND_FLOATING=46484B;SECONDARY_BUTTON_BACKGROUND_OPAQUE=46484B;"
            + "SECONDARY_BUTTON_ICON=F2F4F7;SECONDARY_BUTTON_TEXT=F2F4F7;SECONDARY_ICON=B0B3B8,A1A4A9;"
            + "SECONDARY_TEXT=B0B3B8,A1A4A9;SURFACE_BACKGROUND=252728;"
            + "SWITCH_CHECKED_BACKGROUND_COLOR_ANDROID=ADD5FF;SWITCH_CHECKED_HANDLE_FILL_COLOR_ANDROID=0866FF;"
            + "SWITCH_DISABLED_HANDLE_FILL_COLOR=6F7276;SWITCH_UNCHECKED_BACKGROUND_COLOR=6F7276;"
            + "TAB_BAR_ACTIVE_ICON=F2F4F7;TAB_BAR_BACKGROUND=252728;TAB_BAR_INACTIVE_ICON=F2F4F7;"
            + "TEXT_HIGHLIGHT=721D85FC;TEXT_INPUT_ACTIVE_INNER_BORDER=1D85FC;"
            + "TEXT_INPUT_ACTIVE_OUTER_BORDER=331D85FC;TEXT_INPUT_ACTIVE_TEXT=3E93F8;"
            + "TEXT_INPUT_BAR_BACKGROUND=333334;TEXT_INPUT_BAR_BACKGROUND_ON_DEEMPHASIZED=333334;"
            + "TEXT_INPUT_INACTIVE_INNER_BORDER=5C5E62;TOGGLE_ACTIVE_BACKGROUND=1D85FC,0866FF;TOOLTIP_TEXT=080809;"
            + "UFI_TRAY_ICON_BUTTON_BACKGROUND=46484B;VOICE_SWITCHER_BACKGROUND=46484B;WASH=101011,1C1C1D;"
            + "WEB_WASH=1C1C1D";

    /**
     * FDS colour tokens Facebook gives the same blue in its light and dark themes, read from the
     * same styles: the blue buttons (Add friend, Confirm, Add to story), story rings, the verified
     * and notification badges, progress rings and the cursor (issue #37). The colour can't say which
     * theme is on, so these take the palette only once Facebook has said its dark mode is on
     * ({@link DarkMode#saidOn}). The logo, maps, charts and app icons keep Facebook's blue.
     * MaterialYouTokenFixtureTest holds this list to both fixtures too.
     */
    static final String FDS_SHARED = "ACCENT=0866FF;BLUE_BADGE=0866FF;CURSOR=0866FF;DECORATIVE_ICON_BLUE=0064D1;"
            + "DISABLED_BUTTON_BACKGROUND_GROWTH=5AA7FF;NOTIFICATION_CIRCLE_BLUE=1D85FC;"
            + "PRIMARY_BUTTON_BACKGROUND=0866FF;PRIMARY_BUTTON_PRESSED_BACKGROUND=3E93F8;"
            + "PROGRESS_RING_BLUE_BACKGROUND=330866FF;PROGRESS_RING_BLUE_FOREGROUND=0866FF;STEPPER_ACTIVE=0866FF;"
            + "STORY_UNSEEN=0866FF;SWITCH_CHECKED_BACKGROUND_COLOR_IOS=0866FF;VERIFIED_BADGE=0866FF";

    /**
     * The dark surfaces Facebook also writes into its code (route three) and sends as text (route
     * four). None of them is a light-theme colour in either fixture, so a value on its own says
     * dark mode. Route three reads the field of the same name for each, below.
     */
    static final String SURFACES = "101011 18191A 1C1C1D 242526 252728 3E4042";

    /**
     * Facebook's own blues as its server templates and colour resources carry them, with no token
     * (routes two and four): the profile's Add to story button and Marketplace's chips come as
     * "#0866FF" text, and some icons read a #3E93F8 resource (issue #37). Each is a colour both
     * themes use, so like {@link #FDS_SHARED} they take the palette only once Facebook has said its
     * dark mode is on. They match at any alpha, and only these exact colours, so a colour someone
     * picked for a post, a page or a chart keeps its own. MaterialYouTokenFixtureTest holds each to
     * a colour Facebook's FDS styles give some token.
     */
    static final String SERVER_BLUES = "00488C 0064D1 0866FF 1D85FC 2D88FF 3E93F8 5AA7FF 75B6FF ADD5FF";

    private static final Map<String, int[]> FDS = Collections.unmodifiableMap(parseTokens(FDS_DARK));
    private static final Map<String, int[]> SHARED = Collections.unmodifiableMap(parseTokens(FDS_SHARED));
    private static final int[] SURFACE_VALUES = parseSurfaces(SURFACES);

    /** Every grey {@link #FDS_DARK} lists, for any token, sorted. */
    private static final int[] DARK_GREYS = darkGreys(FDS);
    /** {@link #SERVER_BLUES} without alpha, sorted. */
    private static final int[] SERVER_BLUE_VALUES = parseRgb(SERVER_BLUES);

    // Route three: the patch replaces "const vX, 0xFF252728" with a read of DARK_252728, and so on.
    // In Facebook's dark mode they hold the palette's colours, and in light mode Facebook's own, for
    // the Video tab that stays dark there. Volatile: the thread that gets Facebook's answer writes
    // them, and the UI thread reads them.
    public static volatile int DARK_101011;
    public static volatile int DARK_18191A;
    public static volatile int DARK_1C1C1D;
    public static volatile int DARK_242526;
    public static volatile int DARK_252728;
    public static volatile int DARK_3E4042;

    /** The palette in use: {@link TonePalette#fallback()} until the context is there. */
    private static volatile TonePalette palette;

    /** Whether the palette follows the phone's, and is read again when its colours change. */
    private static volatile boolean bound;

    /** Held while route three's fields are written, so two writes can't interleave. */
    private static final Object PUBLISHING = new Object();

    static {
        palette = TonePalette.fallback();
        publish();
        DarkMode.changed = MaterialYouTheme::publish;
        bind();
    }

    /**
     * Route one, for FDS: a colour a resolver returns for {@code token}.
     *
     * @return the palette's colour if {@code color} is the one Facebook's dark theme gives this
     * token, or AMOLED's stronger tint of it, otherwise {@code color}
     */
    public static int fds(int color, Object token) {
        HookStatus.invoked(FamilyNames.MATERIAL_YOU_THEME);
        if (!(token instanceof Enum) || !DarkMode.on()) return color;
        String name = ((Enum<?>) token).name();
        if (listed(FDS.get(name), color) || madeByAmoled(FDS.get(name), color, name)
                || (listed(SHARED.get(name), color) && DarkMode.saidOn())) {
            return AmoledTheme.foreground(recolour(palette(), color), name);
        }
        if (isSurface(color)) noteUnlisted(name, color);
        return color;
    }

    /** The tokens {@link #noteUnlisted} has written to the log. */
    private static final Set<String> UNLISTED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** The most tokens written, so a screen full of them can't fill the log. */
    private static final int MAX_UNLISTED = 40;

    /**
     * With Debug logging on, writes each token that came with one of the {@link #SURFACES} and was
     * left alone, once per token: a token Facebook gives the same dark grey in both themes isn't in
     * {@link #FDS_DARK}, so a report names the token behind a gray the theme missed (#37). Only the
     * token's name and colour are written.
     */
    private static void noteUnlisted(String token, int color) {
        if (UNLISTED.size() >= MAX_UNLISTED || UNLISTED.contains(token)) return;
        if (!Utils.settingsReady() || !BaseSettings.DEBUG.get() || !UNLISTED.add(token)) return;
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, "Material You theme",
                () -> String.format(Locale.ROOT, "Material You left token %s at #%06X", token, color & 0xFFFFFF));
    }

    static void forgetUnlistedForTests() {
        UNLISTED.clear();
    }

    private static boolean listed(@Nullable int[] colours, int color) {
        if (colours == null) return false;
        for (int value : colours) {
            if (value == color) return true;
        }
        return false;
    }

    /**
     * Whether {@code color} is what AMOLED made of one of {@code colours}: with AMOLED in the build
     * its hook goes first. An unread notification's row reaches this one at
     * {@link AmoledTheme#NEW_NOTIFICATION_ALPHA}, issue #72, and the palette's accent keeps that
     * alpha. On a lighter Background colour a text role reaches it lifted
     * ({@link AmoledTheme#foreground}, issue #34), and takes the palette at the lifted lightness.
     */
    private static boolean madeByAmoled(@Nullable int[] colours, int color, String token) {
        if (colours == null) return false;
        for (int value : colours) {
            if (value != color && (AmoledTheme.unreadRow(value, token) == color
                    || AmoledTheme.foreground(value, token) == color)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Route one, for Mig: a colour the Mig dark scheme returns. That scheme only answers for a dark
     * surface, so its greys and blues are recoloured whatever the token, in Facebook's dark mode.
     * The palette's colour keeps the lightness it came with, and on a lighter AMOLED Background
     * colour a text role is held to AMOLED's contrast again ({@link AmoledTheme#foreground}), since
     * the palette's steps are only as close as its table.
     */
    public static int mig(int color, Object token) {
        HookStatus.invoked(FamilyNames.MATERIAL_YOU_THEME);
        if ((color >>> 24) != 0xFF || !DarkMode.on()) return color;
        int themed = recolour(palette(), color);
        return token instanceof Enum ? AmoledTheme.foreground(themed, ((Enum<?>) token).name()) : themed;
    }

    /**
     * Route four: a colour the server sends as text. The patch sends every call to
     * {@link Color#parseColor} here, including the ones AMOLED already sent to its own, and AMOLED
     * goes first. A colour this class doesn't know as a dark surface or one of Facebook's blues is
     * returned as parsed, and a text that is no colour throws as it did before.
     */
    public static int parseColor(String text) {
        HookStatus.invoked(FamilyNames.MATERIAL_YOU_THEME);
        return withoutToken(SettingsStatus.amoledTheme() ? AmoledTheme.parseColor(text) : Color.parseColor(text));
    }

    /**
     * Route four for the box under a link post's picture, its site and title (issue #37). In
     * Facebook's dark mode each kind of link attachment picks that colour by parsing one MobileConfig
     * sends before it looks at a token, and the box came out #333334, CARD_BACKGROUND's dark grey.
     * That grey is no {@link #SURFACES} colour, light mode drawing it too, so {@link #parseColor}
     * alone leaves it. The patch sends only those parses here, and the box is a background like a
     * React card's ({@link #reactBackground}): once Facebook has said dark mode is on, a grey
     * {@link #FDS_DARK} lists takes the palette's neutral at the same lightness. With AMOLED in the
     * build its near black stays.
     */
    public static int parseLinkBox(String text) {
        return darkBackground(parseColor(text));
    }

    /**
     * Route two's counterpart: Facebook reading a colour resource with {@code Context.getColor}. The
     * patch sends every such call here, including the ones AMOLED already sent to its own, and
     * AMOLED goes first.
     *
     * <p>Material You's resource half only recolours night colours, and Facebook keeps its dark
     * palette in the default configuration, where light mode reads it too. The Video tab's bottom
     * bar is {@code getColor} of that palette's #252728 (580 {@code LX/4KA}, 577 {@code LX/4Bb}),
     * in both modes. So one of the dark surfaces takes the palette here, in Facebook's dark mode only.
     * In dark mode the tab bar also sets the window's navigation bar colour from that read for every
     * tab (580 {@code LX/268;->A0F}, 577 {@code LX/29f;->A0F}), which Android 15 and newer draws at
     * 80% over the bottom edge with three-button navigation.
     */
    public static int getColor(Context context, int id) {
        return getColor(context, id, SettingsStatus.amoledTheme());
    }

    static int getColor(Context context, int id, boolean amoled) {
        return withoutToken(amoled ? AmoledTheme.getColor(context, id) : context.getColor(id));
    }

    /** The same for {@code Resources.getColor(int)}. */
    public static int getColor(Resources resources, int id) {
        return getColor(resources, id, SettingsStatus.amoledTheme());
    }

    @SuppressWarnings("deprecation")
    static int getColor(Resources resources, int id, boolean amoled) {
        return withoutToken(amoled ? AmoledTheme.getColor(resources, id) : resources.getColor(id));
    }

    /** The same for {@code Resources.getColor(int, Theme)}. */
    public static int getColor(Resources resources, int id, @Nullable Resources.Theme theme) {
        return getColor(resources, id, theme, SettingsStatus.amoledTheme());
    }

    static int getColor(Resources resources, int id, @Nullable Resources.Theme theme, boolean amoled) {
        return withoutToken(amoled ? AmoledTheme.getColor(resources, id, theme) : resources.getColor(id, theme));
    }

    /**
     * The same for {@code TypedArray.getColor}, how Litho reads a token's colour from the theme (581
     * {@code LX/1Mx;->A04}: obtainStyledAttributes, then getColor). Some code reads SURFACE_BACKGROUND
     * as a plain colour, so its night style item keeps #252728 when no system tone sits close, and
     * the comment list's rows drew it (issue #37).
     */
    public static int getColor(TypedArray array, int index, int fallback) {
        return getColor(array, index, fallback, SettingsStatus.amoledTheme());
    }

    static int getColor(TypedArray array, int index, int fallback, boolean amoled) {
        return withoutToken(amoled ? AmoledTheme.getColor(array, index, fallback) : array.getColor(index, fallback));
    }

    /**
     * A colour resource read as a drawable with {@code Context.getDrawable}. Litho resolves a
     * token's theme attribute to the resource it points at and asks for its drawable (581 {@code
     * LX/2b3;->A05}), so the feed's composer row is a plain drawable of SURFACE_BACKGROUND's
     * #252728 (issue #37). Facebook keeps that colour only in its default configuration, and the
     * night style can't move a token some code reads as a plain colour when no system tone sits
     * close to it. A colour drawable of one of the {@link #SURFACES} takes the palette here, as a
     * colour read with {@code getColor} does, and so does a shape's flat fill ({@link #recolour});
     * any other drawable comes back as it was.
     */
    public static Drawable getDrawable(Context context, int id) {
        return recolour(context.getDrawable(id));
    }

    /**
     * The same read through {@code Resources}: androidx's {@code ResourcesCompat} and a Litho lookup
     * that holds no {@code Context} ask a resource for its drawable this way, and a colour resource
     * comes back as a ColorDrawable of its colour. Same answer as {@link #getDrawable(Context, int)}.
     */
    @SuppressWarnings("deprecation")
    public static Drawable getDrawable(Resources resources, int id) {
        return recolour(resources.getDrawable(id));
    }

    /** The same for {@code Resources.getDrawable(int, Theme)}. */
    public static Drawable getDrawable(Resources resources, int id, @Nullable Resources.Theme theme) {
        return recolour(resources.getDrawable(id, theme));
    }

    /**
     * A theme attribute read as {@code TypedValue.data}, after {@code Theme.resolveAttribute}. Some
     * code takes the colour that way (581's {@code LX/2pM;->A00}, which the comment sheet's action
     * text and, it seems, its rows ask), and a night style item for a token can't move when no system
     * tone sits close to it: SURFACE_BACKGROUND keeps #252728 (issue #37). The patch sends each read
     * here with the whole value. Only a colour moves, so a dimension, a flag or a reference comes
     * back as it was, and the colour goes the way {@link #withoutToken} sends any other.
     */
    public static int colourData(TypedValue value) {
        int data = value.data;
        if (value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) return data;
        return withoutToken(data);
    }

    /**
     * The drawable with its colour from {@link #withoutToken}, on a copy of its state: drawables of
     * one resource share it, and light mode reads the same resource. A shape's flat fill, alone or as
     * a layer, takes the palette as a sheet's does ({@link #recolourShapes}), without AMOLED: on 582
     * each comment row loads a rectangle of SURFACE_BACKGROUND's #252728 this way, and a #313238 one
     * for a pressed row, and keeps them inside a drawable of its own, where no view's background walk
     * sees them (#37).
     */
    @Nullable
    static Drawable recolour(@Nullable Drawable drawable) {
        if (!(drawable instanceof ColorDrawable)) {
            if (!SettingsStatus.amoledTheme()) recolourShapes(drawable);
            return drawable;
        }
        ColorDrawable plain = (ColorDrawable) drawable;
        int color = plain.getColor();
        int themed = withoutToken(color);
        if (themed == color) return drawable;
        plain.mutate();
        plain.setColor(themed);
        return plain;
    }

    /** Whether {@link #watchWindows} has registered its callbacks. */
    private static boolean windowsWatched;

    /**
     * The page under the feed's last unit in dark mode. It's the window's own background, which the
     * framework draws from the activity theme's {@code android:windowBackground}: Facebook's main
     * theme points it at {@code ?attr/WASH} (581 attribute 0x7f040633), and the dark FDS style
     * gives WASH its #101011 colour resource, which has no night value. The framework reads it, so
     * no hook sees it, and some code reads WASH as a plain colour too, so the night style can't
     * move it when no system tone sits that close. Called once the application is created, with the
     * theme in the build: from then on each activity's window background takes the palette when the
     * activity is created, as it resumes and before each frame ({@link #recolourWindow}, {@link
     * #recolourBeforeEachFrame}).
     */
    public static synchronized void watchWindows(Context context) {
        if (windowsWatched || !(context instanceof Application)) return;
        ((Application) context).registerActivityLifecycleCallbacks(new WindowBackgrounds());
        windowsWatched = true;
    }

    /**
     * Gives a window background of one of the {@link #SURFACES} the palette's neutral at the same
     * lightness in dark mode, the colour route three gives the rest of the page. Another colour, a
     * background that is no plain colour, light mode and a window with no view yet keep theirs, and so
     * does AMOLED's, whose route two made it the background colour already.
     *
     * @return whether it changed the background
     */
    static boolean recolourWindow(@Nullable Window window, boolean amoled) {
        if (window == null || amoled) return false;
        View decor = window.peekDecorView();
        if (decor == null) return false;
        Drawable background = decor.getBackground();
        if (background instanceof InsetDrawable) background = ((InsetDrawable) background).getDrawable();
        if (!(background instanceof ColorDrawable)) return false;
        int color = ((ColorDrawable) background).getColor();
        if (!isSurface(color) || !DarkMode.on()) return false;
        window.setBackgroundDrawable(new ColorDrawable(palette().sameLightness(TonePalette.NEUTRAL, color)));
        return true;
    }

    /** The decor views that already run {@link #recolourWindow} before each frame. Main thread only. */
    private static final Set<View> FRAMED = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Runs {@link #recolourWindow} before each frame the window's decor draws, from the first one on.
     * On a cold start the feed's window kept #101011 after both callbacks ran (four of four on 581),
     * and only a later resume moved it: its background arrives after the activity resumes, and some
     * of Facebook's screens set a window's background again later. Until a dark surface shows up
     * again, each frame costs a type test and a colour compare.
     *
     * @return whether the window had a decor to watch
     */
    static boolean recolourBeforeEachFrame(Activity activity, Window window) {
        View decor = window.peekDecorView();
        if (decor == null) return false;
        if (FRAMED.add(decor)) {
            ViewTreeObserver observer = decor.getViewTreeObserver();
            observer.addOnPreDrawListener(() -> {
                recolourWindow(window, SettingsStatus.amoledTheme());
                return true;
            });
            // A sheet or dialog the screen opens takes the focus from its window.
            observer.addOnWindowFocusChangeListener(focused -> {
                if (!focused) Utils.runOnMainThread(() -> watchWindowsAbove(activity, decor));
            });
        }
        return true;
    }

    /** The windows of sheets and dialogs that already have {@link #recolourBackgrounds} after each layout. */
    private static final Set<View> SHEETS = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * The comment sheet is a dialog fragment, a window of its own over the screen's, and its comment
     * list sits on a view whose plain #252728 background no colour hook sees (#37, 581
     * SimpleUFIPopoverFragment: the list's container under {@code X.9z3}). Each window the activity
     * has open above its own gets {@link #recolourBackgrounds} now and after each of its layouts.
     */
    static void watchWindowsAbove(Activity activity, View decor) {
        for (View root : WindowsAbove.of(activity, decor, 3)) {
            if (!SHEETS.add(root)) continue;
            recolourBackgrounds(root);
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> recolourBackgrounds(root));
        }
    }

    /**
     * Gives each view under [root] whose background is a plain colour of the {@link #SURFACES} the
     * palette's neutral at the same lightness, in dark mode and without AMOLED, as
     * {@link #recolourWindow} does for a window. Litho's hosts are walked too: on 581 the comment
     * rows draw #252728 with no FDS token reaching {@link #fds}. A shape's flat fill, alone or as a
     * layer, takes the palette too ({@link #recolourShapes}).
     *
     * @return how many views it recoloured
     */
    static int recolourBackgrounds(View root) {
        if (SettingsStatus.amoledTheme() || !DarkMode.on()) return 0;
        return recolourBackgrounds(root, 0);
    }

    private static int recolourBackgrounds(View view, int depth) {
        int recoloured = 0;
        Drawable background = view.getBackground();
        if (background instanceof ColorDrawable && isSurface(((ColorDrawable) background).getColor())) {
            recolour(background);
            recoloured++;
        } else if (recolourShapes(background)) {
            recoloured++;
        }
        if (view instanceof ViewGroup && depth < MAX_SHEET_DEPTH) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != null) recoloured += recolourBackgrounds(child, depth + 1);
            }
        }
        return recoloured;
    }

    /** Deeper than the comment sheet's views go, so a broken tree can't hold a layout up. */
    private static final int MAX_SHEET_DEPTH = 40;

    /**
     * Gives a shape's flat fill, or each such fill of a layer list, the palette as a React background
     * takes it ({@link #darkBackground}): the sheet's comment bar is a 581
     * SingleLineCommentComposerView whose background is the drawable sutro_top_border, a DIVIDER
     * #65686C layer under a SURFACE_BACKGROUND #252728 one inset 1dp at the top (#37). The framework
     * reads both from the theme, so no hook sees them. A gradient or a fill that changes with the
     * view's state keeps its colours. The drawable's state is copied first, since every view drawn
     * from the resource shares it.
     *
     * @return whether a fill changed
     */
    private static boolean recolourShapes(@Nullable Drawable background) {
        if (background instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) background;
            Integer themed = themedFill(shape);
            if (themed == null) return false;
            shape.mutate();
            shape.setColor(themed);
            return true;
        }
        if (!(background instanceof LayerDrawable)) return false;
        LayerDrawable layers = (LayerDrawable) background;
        if (!hasThemedFill(layers)) return false;
        layers.mutate();
        for (int i = 0; i < layers.getNumberOfLayers(); i++) {
            Drawable layer = layers.getDrawable(i);
            if (!(layer instanceof GradientDrawable)) continue;
            Integer themed = themedFill((GradientDrawable) layer);
            if (themed != null) ((GradientDrawable) layer).setColor(themed);
        }
        return true;
    }

    private static boolean hasThemedFill(LayerDrawable layers) {
        for (int i = 0; i < layers.getNumberOfLayers(); i++) {
            Drawable layer = layers.getDrawable(i);
            if (layer instanceof GradientDrawable && themedFill((GradientDrawable) layer) != null) return true;
        }
        return false;
    }

    /** The palette's colour for a shape's flat fill, or null when it keeps its own. */
    @Nullable
    private static Integer themedFill(GradientDrawable shape) {
        ColorStateList fill = shape.getColor();
        if (fill == null || fill.isStateful()) return null;
        int color = fill.getDefaultColor();
        int themed = darkBackground(color);
        return themed == color ? null : themed;
    }

    /**
     * Runs {@link #recolourWindow} for each activity once it's created and each time it resumes, and
     * from then on before each frame ({@link #recolourBeforeEachFrame}). A window that has no decor
     * yet as it resumes gets one from the framework right after, so the watch waits a turn for it.
     */
    static final class WindowBackgrounds implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityPostCreated(@NonNull Activity activity, @Nullable Bundle state) {
            recolour(activity, false);
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            recolour(activity, true);
        }

        private static void recolour(Activity activity, boolean resumed) {
            HookStatus.invoked(FamilyNames.MATERIAL_YOU_THEME);
            try {
                Window window = activity.getWindow();
                recolourWindow(window, SettingsStatus.amoledTheme());
                if (window != null && !recolourBeforeEachFrame(activity, window) && resumed) {
                    Utils.runOnMainThread(() -> recolourBeforeEachFrame(activity, window));
                }
            } catch (RuntimeException failure) {
                Logger.printException(() -> "Material You theme: could not recolour the window", failure);
            }
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }

    /**
     * A colour a React Native screen sets on a view, a text or an image (ReactColours), after
     * AMOLED's rule for a background. It comes with no token, so the dark surfaces and Facebook's
     * blues take the palette as a colour from the server does: Marketplace home's selected chip is
     * #331D85FC over its strip, its location text #5AA7FF and its pin #75B6FF. Anything else, and
     * light mode, keeps the colour as it came.
     */
    static int react(int color) {
        HookStatus.invoked(FamilyNames.MATERIAL_YOU_THEME);
        return withoutToken(color);
    }

    /**
     * A background a React Native screen sets: as {@link #react}, and once Facebook has said dark
     * mode is on, a grey {@link #FDS_DARK} lists for any token takes the palette's neutral at the
     * same lightness too, as that token would. React's own dark theme paints its cards with them:
     * Marketplace's Message seller card on a listing is #333334 (CARD_BACKGROUND) on the #252728
     * page. The table's lighter greys, like #F2F4F7, are light mode's colours as well, so before
     * Facebook answers they stay, as {@link #FDS_SHARED}'s do. Text keeps {@link #react}, where a
     * grey stays as it came.
     */
    static int reactBackground(int color) {
        HookStatus.invoked(FamilyNames.MATERIAL_YOU_THEME);
        return darkBackground(color);
    }

    /**
     * {@link #withoutToken}, and once Facebook has said dark mode is on, the palette's neutral at the
     * same lightness for any grey {@link #FDS_DARK} lists.
     */
    private static int darkBackground(int color) {
        int themed = withoutToken(color);
        if (themed != color || !DarkMode.saidOn() || Arrays.binarySearch(DARK_GREYS, color) < 0) return themed;
        return palette().sameLightness(TonePalette.NEUTRAL, color);
    }

    /**
     * A colour that came with no token: the palette's neutral at the same lightness for one of the
     * {@link #SURFACES} in dark mode, and its accent for one of the {@link #SERVER_BLUES} once
     * Facebook has said dark mode is on.
     */
    private static int withoutToken(int color) {
        if (isSurface(color) && DarkMode.on()) return palette().sameLightness(TonePalette.NEUTRAL, color);
        if (isServerBlue(color) && DarkMode.saidOn()) return palette().sameLightness(TonePalette.ACCENT, color);
        return color;
    }

    /**
     * The status bar: the colour Facebook is about to paint it, and whether Facebook's theme is dark.
     *
     * <p>On Android 15 and newer Facebook paints the bar itself, and the patch calls this first thing
     * in that method. A tab's bar colour often comes from a token that isn't a dark-theme colour at
     * all: back from Recent Apps the Video tab asks for {@code #333334}, which it asks for in light
     * mode too. So the colour alone can't say which theme is on, and the patch passes Facebook's own
     * answer for the window. One of Facebook's dark chrome greys (the band AMOLED blackens) takes the
     * palette's neutral at the same lightness, as every grey this class recolours does.
     *
     * <p>With AMOLED in the build, the patch calls this in place of AMOLED's own hook and AMOLED's
     * rule goes first. Its black stays black, since black is the palette's darkest tone.
     *
     * @return the palette's colour for a dark chrome grey in the dark theme, otherwise {@code color}
     */
    public static int statusBar(int color, boolean dark) {
        return statusBar(color, dark, SettingsStatus.amoledTheme());
    }

    static int statusBar(int color, boolean dark, boolean amoled) {
        HookStatus.invoked(FamilyNames.MATERIAL_YOU_THEME);
        if (amoled) color = AmoledTheme.statusBar(color, dark);
        if (!dark || !AmoledTheme.isDarkNeutral(color, AmoledTheme.MAX_BAR_CHANNEL)) return color;
        return palette().sameLightness(TonePalette.NEUTRAL, color);
    }

    /**
     * The navigation bar, as {@link #statusBar} does it: the Video tab writes its bars'
     * {@code #252728} into code for both themes, and route three leaves a colour a method hands to
     * a system bar for this hook. A dark grey in AMOLED's band for the bar takes the palette's
     * neutral at the same lightness in the dark theme only. With AMOLED in the build its rule goes
     * first.
     *
     * @return the palette's colour for a dark grey in the dark theme, otherwise {@code color}
     */
    public static int navigationBar(int color, boolean dark) {
        return navigationBar(color, dark, SettingsStatus.amoledTheme());
    }

    static int navigationBar(int color, boolean dark, boolean amoled) {
        HookStatus.invoked(FamilyNames.MATERIAL_YOU_THEME);
        if (amoled) color = AmoledTheme.navigationBar(color, dark);
        if (!dark || !AmoledTheme.isDarkNeutral(color, AmoledTheme.MAX_CHANNEL)) return color;
        return palette().sameLightness(TonePalette.NEUTRAL, color);
    }

    /**
     * The tone of the palette's accent the selected tab takes in the dark theme: Material 3's
     * primary for a dark surface, the role HushMessenger's selected tab has.
     */
    static final int SELECTED_TAB_TONE = 80;

    /**
     * How light a selected tab's colour has to be to say the tab bar is dark. Facebook's dark theme
     * gives the selected tab near white (TAB_BAR_ACTIVE_ICON and PRIMARY_ICON, L* 96), and its light
     * theme near black or its blue (L* 3 and 48), so a light mode answer never passes, even before
     * Facebook has said which theme is on.
     */
    static final double MIN_DARK_BAR_SELECTED_LIGHTNESS = 60;

    /**
     * Facebook's tab bar asks one method for the selected tab's colour, which tints the selected
     * tab's icon and repaints the line over it when the theme is set (#65). The line's Paint first
     * gets its colour from a colour token as the bar inflates, before that method runs, so the patch
     * hands that colour here too. The patch hands each answer here. In Facebook's
     * dark mode a light answer becomes the palette's accent at {@link #SELECTED_TAB_TONE}, so the
     * selected tab stands out in the wallpaper's colour as HushMessenger's does, while the other tabs,
     * the bar and the badges keep the colours route one gives them. A selected tab Facebook already
     * draws in its blue takes the accent through route one. Light mode, and anything this doesn't
     * know, is left as Facebook sent it.
     *
     * @return the palette's accent for the selected tab in the dark theme, otherwise {@code color}
     */
    public static int tabBarSelected(int color) {
        HookStatus.invoked(FamilyNames.MATERIAL_YOU_THEME);
        return tabBarSelected(palette(), color, DarkMode.on());
    }

    static int tabBarSelected(TonePalette palette, int color, boolean dark) {
        if (!dark || (color >>> 24) == 0 || TonePalette.lstar(color) < MIN_DARK_BAR_SELECTED_LIGHTNESS) return color;
        return (color & 0xFF000000) | (palette.tone(TonePalette.ACCENT, SELECTED_TAB_TONE) & 0x00FFFFFF);
    }

    /** A grey becomes the palette's neutral, a Facebook blue its accent, both at the same lightness. */
    static int recolour(TonePalette palette, int color) {
        if (isNeutral(color)) return palette.sameLightness(TonePalette.NEUTRAL, color);
        if (isFacebookBlue(color)) return palette.sameLightness(TonePalette.ACCENT, color);
        return color;
    }

    /** A grey: its channels are within 10 of each other. Facebook's greys are within 8. */
    static boolean isNeutral(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 10;
    }

    /**
     * One of Facebook's blues, #00488C to #ADD5FF: an HSV hue from 200 to 225 degrees, saturation
     * of at least a quarter and value of at least 0.3. Worked in integers, since the Mig hook runs
     * on every layout pass and makes no object.
     */
    static boolean isFacebookBlue(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int delta = b - Math.min(r, g);
        if (b < r || b < g || b < 77 || delta * 4 < b) return false;
        // With blue the largest channel, hue = 240 + 60 * (r - g) / delta.
        int turn = 60 * (r - g);
        return turn >= -40 * delta && turn <= -15 * delta;
    }

    static boolean isSurface(int color) {
        return Arrays.binarySearch(SURFACE_VALUES, color) >= 0;
    }

    /** One of {@link #SERVER_BLUES}, at any alpha. */
    static boolean isServerBlue(int color) {
        return Arrays.binarySearch(SERVER_BLUE_VALUES, color & 0x00FFFFFF) >= 0;
    }

    /** The palette in use, binding it to the phone's once the context is there. */
    static TonePalette palette() {
        if (!bound) bind();
        return palette;
    }

    /** The [MATERIAL YOU] section of the diagnostic report, with the theme in the build. */
    public static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override public String title() { return "MATERIAL YOU"; }
        @Override public List<String> lines() {
            return SettingsStatus.materialYouTheme() ? Collections.singletonList(reportLine(palette())) : Collections.emptyList();
        }
        @Override public boolean isAppState() { return true; }
    };

    /** Where the palette came from and how colourful its accent is. No colours: they follow the wallpaper. */
    static String reportLine(@Nullable TonePalette p) {
        if (p == null || !p.dynamic) return "Facebook's blue palette: no wallpaper palette before Android 12, or it couldn't be read";
        String chroma = String.format(Locale.US, "%.1f", p.accentChroma);
        return p.accentReplaced
                ? "wallpaper palette; its accent (chroma " + chroma + ") is too grey to tell from its greys, so Facebook's blue stands in"
                : "wallpaper palette; accent chroma " + chroma;
    }

    /**
     * Reads the phone's palette once Hushfacebook has a context, and again whenever the
     * configuration changes. A new wallpaper palette reaches the app as a configuration change.
     */
    private static synchronized void bind() {
        if (bound || !Utils.settingsReady()) return;
        try {
            Context app = Utils.getContext();
            if (app == null) return;
            reload(app);
            app.registerComponentCallbacks(new ComponentCallbacks() {
                @Override
                public void onConfigurationChanged(@NonNull Configuration configuration) {
                    reload(app);
                }

                @Override
                public void onLowMemory() {
                }
            });
            bound = true;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Material You theme: could not read the wallpaper palette", failure);
        }
    }

    static void reload(Context context) {
        palette = TonePalette.of(context);
        publish();
    }

    /** Package-visible for tests: puts a palette in use and writes the route three fields from it. */
    static void use(TonePalette next, boolean followPhone) {
        palette = next;
        publish();
        bound = !followPhone;
    }

    /**
     * Writes route three's fields from the palette in use: its surfaces in Facebook's dark mode,
     * Facebook's own in light mode. It runs after every change of either, on the thread that made
     * it. One write at a time, each reading the answer and the palette once, so the last one leaves
     * all six fields in the mode and the palette that stand.
     */
    private static void publish() {
        synchronized (PUBLISHING) {
            TonePalette p = DarkMode.on() ? palette : null;
            DARK_101011 = surface(p, 0x101011);
            DARK_18191A = surface(p, 0x18191A);
            DARK_1C1C1D = surface(p, 0x1C1C1D);
            DARK_242526 = surface(p, 0x242526);
            DARK_252728 = surface(p, 0x252728);
            DARK_3E4042 = surface(p, 0x3E4042);
        }
    }

    /** The palette's neutral at the lightness of Facebook's surface {@code rgb}, or Facebook's own without a palette. */
    private static int surface(@Nullable TonePalette p, int rgb) {
        return p == null ? 0xFF000000 | rgb : p.sameLightness(TonePalette.NEUTRAL, 0xFF000000 | rgb);
    }

    /** A token table: six hex digits are an opaque colour, eight a colour with its alpha first. */
    static Map<String, int[]> parseTokens(String table) {
        Map<String, int[]> tokens = new HashMap<>();
        for (String entry : table.split(";")) {
            int equals = entry.indexOf('=');
            String[] hex = entry.substring(equals + 1).split(",");
            int[] values = new int[hex.length];
            for (int i = 0; i < hex.length; i++) {
                int rgb = Integer.parseUnsignedInt(hex[i], 16);
                values[i] = hex[i].length() == 8 ? rgb : 0xFF000000 | rgb;
            }
            if (tokens.put(entry.substring(0, equals), values) != null) {
                throw new IllegalStateException("token listed twice: " + entry);
            }
        }
        return tokens;
    }

    private static int[] parseSurfaces(String list) {
        String[] hex = list.split(" ");
        int[] values = new int[hex.length];
        for (int i = 0; i < hex.length; i++) values[i] = 0xFF000000 | Integer.parseInt(hex[i], 16);
        Arrays.sort(values);
        return values;
    }

    private static int[] darkGreys(Map<String, int[]> tokens) {
        TreeSet<Integer> greys = new TreeSet<>();
        for (int[] colours : tokens.values()) {
            for (int colour : colours) {
                if (isNeutral(colour)) greys.add(colour);
            }
        }
        int[] values = new int[greys.size()];
        int i = 0;
        for (int grey : greys) values[i++] = grey;
        return values;
    }

    private static int[] parseRgb(String list) {
        String[] hex = list.split(" ");
        int[] values = new int[hex.length];
        for (int i = 0; i < hex.length; i++) values[i] = Integer.parseInt(hex[i], 16);
        Arrays.sort(values);
        return values;
    }
}
