/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.font;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.BoringLayout;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * Draws Facebook's own text in a font of your choosing instead of Meta's Optimistic (issue #1):
 * the phone's own font, or a font file picked in Hushfacebook's settings and copied into
 * Facebook's files ({@link FontFile}).
 *
 * <p>Facebook ships Meta's Optimistic family under assets/fonts and hands every interface
 * typeface out of one repository. Its static resolver takes a font family constant, a style and a
 * weight, and answers from its cache, from a downloaded font file or from an asset. The FDS text
 * styles, the Bloks font name lookup, the story text tools and the font prefetcher all go through
 * it. {@link #replace} stands at its exit and, while the switch is on, answers the chosen font at
 * the same weight and slant for the families Meta draws its interface in. The other families the
 * repository knows (Montserrat, Courier Prime, Old Standard, the story sticker fonts) are a
 * person's own choice for a story or a post, so they pass through as they are.
 *
 * <p>Bloks text with variable-font settings takes a second road: the repository hands out a
 * typeface builder (Meta's com.meta.foa.typefacebuilder) that reads the asset or the downloaded
 * file itself, with the weight in a font variation string. {@link #rememberVariation} keeps the
 * string each builder was given and {@link #replaceBuilt} stands at the builder's exit, answering
 * the chosen font at the weight the string asked for. Only families with variable axes reach a
 * builder, and every one of those is Meta's.
 *
 * <p>React Native screens, parts of Marketplace among them, take a third: their script names a
 * family, and React Native's own font manager finds it. The font prefetcher registers Meta's
 * families there under names like "Optimistic VF App Lite 700", and the manager finds others among
 * the app's font assets by file name. {@link #replaceReactNative} stands at the exit of the
 * manager's resolver and swaps the families named for Meta's interface fonts.
 *
 * <p>Text that asks for none of Meta's fonts takes a fourth: Facebook's own text engine builds it
 * a Roboto, the phone's sans-serif at the weight its style names. That's where posts, comments and
 * menus get their font on accounts Facebook gives no Optimistic, and where Optimistic text lands
 * when the repository can't build it. It's the phone's font already, so {@link #replacePhoneFont}
 * swaps it only for a picked file.
 *
 * <p>The rest of Facebook's code reads Android's default typefaces itself, the spans that bold a
 * name in a post's header or a notification among it. Each read of Typeface.DEFAULT or
 * DEFAULT_BOLD, and each call of Typeface.defaultFromStyle, comes here instead
 * ({@link #defaultTypeface}, {@link #defaultBold}, {@link #defaultFromStyle}), and a picked file
 * takes those too.
 *
 * <p>The weight comes from what Facebook built, never from a register alone. A static asset
 * carries its own weight (Optimistic Text Bold is 700 whatever was asked for), and a variable
 * font carries its default instance's, so the larger of the built weight and the requested one
 * wins. A typeface already on screen keeps its font until the view is rebuilt, so the switch says
 * to restart Facebook.
 *
 * <p>A picked font is built once per weight and slant and kept. One with a 'wght' axis is built at
 * the weight asked for, and any other takes Android's bold and slant where it has none of its own.
 * When the copy won't load, the phone's font stands in, and while the switch is off, Facebook's
 * own does.
 *
 * <p>Facebook lays most of its text out without font padding, each line as tall as the font's own
 * ascent and descent, which fits Optimistic. Some fonts phones offer declare far less descent than
 * their letters use (#96), and there y, g and p lost their tails, or a Bengali caption its vowel
 * signs. So each text layout Facebook builds, and each text view, takes the padding while the
 * switch is on and its font is one of those ({@link #needsPad}). Facebook's line-height spans set
 * a layout's first top and last bottom to the edges of the line height they fit, which undid that
 * padding in Reels captions and other text with a set line height, and the tails stayed cut off.
 * So in a padded layout those edges stay at least the font's own ({@link #lineTop}).
 */
public final class OwnFont {
    /** The font variation strings each builder was given, by the builder, for as long as it lives. */
    private static final Map<Object, String> VARIATIONS = Collections.synchronizedMap(new WeakHashMap<>());

    /** The builders made for a family the switch leaves alone, for as long as each lives, whose builds keep their face. */
    private static final Set<Object> KEPT_BUILDERS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** A variation axis and its value, as Android writes them: 'wght' 700 or "wght" 700. */
    private static final Pattern AXIS = Pattern.compile("['\"]([a-zA-Z]{4})['\"]\\s*(-?\\d+(?:\\.\\d+)?)");

    private static final String REPOSITORY = "typeface repository";
    private static final String BUILDER = "variable font builder";
    private static final String REACT_NATIVE = "React Native font manager";
    private static final String ROBOTO = "Roboto builder";
    private static final String DEFAULTS = "Android's default typefaces";
    private static final String VIEWS = "text views";

    /**
     * What each family name a React Native screen asked for came to: the weight it ends in, 0 for
     * none, or -1 for a family that isn't one of Meta's interface fonts. A screen asks on every
     * measure and draw of a span, and a handful of names cover them all.
     */
    private static final ConcurrentHashMap<String, Integer> REACT_FAMILIES = new ConcurrentHashMap<>();
    /** Past this many names, a new one is worked out each time rather than kept. */
    private static final int MAX_REACT_FAMILIES = 256;
    /** The weights Android and Facebook name after "sans-serif-" and "roboto-". */
    private static final Set<String> SANS_WEIGHTS = new HashSet<>(Arrays.asList(
            "thin", "light", "regular", "medium", "bold", "black"));

    /** The phone's sans-serif and its named weights, the families {@link #isPhoneTypeface} knows by name. */
    private static final String[] PHONE_SANS = {
            "sans-serif", "sans-serif-thin", "sans-serif-light", "sans-serif-medium", "sans-serif-black"};

    /** What {@link #isPhoneTypeface} compares with, built the first time it's asked. */
    @Nullable
    private static volatile Set<Typeface> phoneTypefaces;

    /** The roads {@link #ready} has logged an ask before the settings loaded on. */
    private static final Set<String> ASKED_EARLY = Collections.newSetFromMap(new ConcurrentHashMap<>());

    static final String LAYOUTS = "text layouts";
    /* What Hook status counts for each typeface measured for the padding, once. */
    static final String PADDED = "Font drawn past its line box, padded";
    static final String FITS = "Font inside its line box";
    /* Counted each time a line-height span would have pulled a padded layout's edge inside the font's. */
    static final String EDGE_KEPT = "Line height kept the font padding";

    /** Letters reaching below the line, and above the x-height, for {@link #overflows}. */
    private static final String DESCENDERS = "gjpqy";
    private static final String ASCENDERS = "bdfhklt";
    /**
     * Bengali and Devanagari letters with the vowel signs that hang below them and rise above them,
     * for {@link #overflows}, one script to a line as Facebook lays them out.
     */
    static final String[] SCRIPTS = {
            "কুকূকৃকিকীকৈ",
            "कुकूकृकिकीकै"};
    static final float PROBE_SIZE = 100f;

    /** What {@link #overflows} measured for each typeface, for as long as it lives. */
    static final Map<Typeface, Boolean> OVERFLOWS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Each phone typeface {@link #typeface} handed out, at whatever weight, for {@link #needsPad}. */
    private static final Set<Typeface> HANDED_OUT = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** The builder this thread last obtained through {@link #obtain}, and the paint it was for. */
    private static final ThreadLocal<Object[]> OBTAINED = new ThreadLocal<Object[]>() {
        @Override
        protected Object[] initialValue() {
            return new Object[2];
        }
    };

    /** Whether the layout this thread is building through {@link #build} takes the font padding. */
    private static final ThreadLocal<boolean[]> BUILDING_PADDED = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[1];
        }
    };

    /** The builders {@link #sample} logged a Bengali layout for, each once. */
    static final Set<String> SAMPLED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Past this many builders {@link #sample} logs no more, so a long session can't fill the log. */
    private static final int MAX_SAMPLES = 24;
    /** The Bengali layouts {@link #sample} read a stack for, so a Bengali feed stops paying for it. */
    static final AtomicInteger LOOKS = new AtomicInteger();
    /** Past this many stacks {@link #sample} stops looking, even short of {@link #MAX_SAMPLES}. */
    private static final int MAX_LOOKS = 400;

    /** The picked font as last read, or null before the first read and after a change. */
    @Nullable
    private static volatile Picked picked;

    private OwnFont() {
    }

    /**
     * Whether [name], a constant of Facebook's font family enum, is one Meta draws its interface
     * in: the Optimistic families and the variable Facebook Sans, less the monospace ones
     * ({@link #isMonospaceFamily}), whose fixed width code and aligned text count on. The story
     * sticker fonts and the downloaded creative families keep their names off this list.
     */
    static boolean isInterfaceFamily(@Nullable String name) {
        if (name == null) return false;
        return (name.startsWith("OPTIMISTIC") || name.equals("FACEBOOK_SANS_VARIABLE")) && !isMonospaceFamily(name);
    }

    /** Whether [name], a family constant, is a monospace face: one of its words starts with MONO, as in OPTIMISTIC_MONO. */
    static boolean isMonospaceFamily(String name) {
        for (String word : name.split("_")) {
            if (word.startsWith("MONO")) return true;
        }
        return false;
    }

    /**
     * The chosen font at [original]'s weight and slant when the switch is on and [family] is one
     * of Meta's interface families, otherwise [original] as it came. [weight] is what the caller
     * asked the repository for, or a negative number when it asked for none.
     */
    public static Typeface replace(Typeface original, Enum<?> family, int weight) {
        try {
            HookStatus.invoked(FamilyNames.SYSTEM_FONT);
            if (original == null || family == null || !isInterfaceFamily(family.name())) return original;
            if (!ready(REPOSITORY) || !Settings.USE_SYSTEM_FONT.get()) return original;
            HookStatus.bound(FamilyNames.SYSTEM_FONT, REPOSITORY);
            return typeface(Math.max(weight, original.getWeight()), original.isItalic());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_FONT, REPOSITORY, failure);
            return original;
        }
    }

    /** Keeps the font variation string [builder] was handed, so its build can read the weight back. */
    public static void rememberVariation(Object builder, String settings) {
        try {
            if (builder == null) return;
            if (settings == null || settings.isEmpty()) VARIATIONS.remove(builder);
            else VARIATIONS.put(builder, settings);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_FONT, BUILDER, failure);
        }
    }

    /**
     * Notes [builder], made for [family], when that's a family the switch leaves alone, so its
     * build keeps what it built. Hands [builder] back either way.
     */
    public static Object rememberFamily(Object builder, Enum<?> family) {
        try {
            if (builder != null && family != null && !isInterfaceFamily(family.name())) KEPT_BUILDERS.add(builder);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_FONT, BUILDER, failure);
        }
        return builder;
    }

    /**
     * The chosen font at the weight and slant [builder]'s variation string asked for, or at
     * [built]'s own when it was given none, while the switch is on and [builder] wasn't made for a
     * family the switch leaves alone ({@link #rememberFamily}). Otherwise [built] as it came.
     */
    public static Typeface replaceBuilt(Typeface built, Object builder) {
        try {
            HookStatus.invoked(FamilyNames.SYSTEM_FONT);
            if (built == null) return null;
            if (builder != null && KEPT_BUILDERS.contains(builder)) return built;
            if (!ready(BUILDER) || !Settings.USE_SYSTEM_FONT.get()) return built;
            String settings = builder == null ? null : VARIATIONS.get(builder);
            HookStatus.bound(FamilyNames.SYSTEM_FONT, BUILDER);
            return typeface(weightOf(settings, built.getWeight()), italicOf(settings, built.isItalic()));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_FONT, BUILDER, failure);
            return built;
        }
    }

    /**
     * The chosen font for React Native text in [family], one of Meta's interface fonts by name,
     * while the switch is on, otherwise [answer], what React Native's font manager found. The
     * manager gives [answer] the weight and slant the text asked for, and a family name Facebook
     * registered ends in the weight it was built at, so the larger of the two wins, as it does at
     * the repository.
     */
    public static Typeface replaceReactNative(Typeface answer, String family) {
        try {
            HookStatus.invoked(FamilyNames.SYSTEM_FONT);
            if (answer == null || family == null) return answer;
            int named = reactNativeFamily(family);
            if (named < 0) return answer;
            if (!ready(REACT_NATIVE) || !Settings.USE_SYSTEM_FONT.get()) return answer;
            HookStatus.bound(FamilyNames.SYSTEM_FONT, REACT_NATIVE);
            return typeface(Math.max(named, answer.getWeight()), answer.isItalic());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_FONT, REACT_NATIVE, failure);
            return answer;
        }
    }

    /**
     * The picked font at [answer]'s weight and slant while the switch is on and a font file is
     * picked, otherwise [answer]: the Roboto Facebook's text engine built for text that asks for
     * none of Meta's fonts. With no file picked, or a copy that won't load, that Roboto is the
     * phone's font already, so it stands.
     */
    public static Typeface replacePhoneFont(Typeface answer) {
        return pickedInstead(answer, ROBOTO);
    }

    /** {@link Typeface#DEFAULT} for Facebook's own code: the picked font at 400 while one is picked. */
    public static Typeface defaultTypeface() {
        return pickedInstead(Typeface.DEFAULT, DEFAULTS);
    }

    /**
     * {@link Typeface#DEFAULT_BOLD} for Facebook's own code: the picked font at 700 while one is
     * picked. The spans that bold a name in a post's header or a notification read it.
     */
    public static Typeface defaultBold() {
        return pickedInstead(Typeface.DEFAULT_BOLD, DEFAULTS);
    }

    /** {@link Typeface#defaultFromStyle} for Facebook's own code: the picked font in [style] while one is picked. */
    public static Typeface defaultFromStyle(int style) {
        return pickedInstead(Typeface.defaultFromStyle(style), DEFAULTS);
    }

    /** {@link Typeface#SANS_SERIF} for Facebook's own code: the picked font at 400 while one is picked. */
    public static Typeface sansSerif() {
        return pickedInstead(Typeface.SANS_SERIF, DEFAULTS);
    }

    /**
     * {@link Typeface#create(String, int)} for Facebook's own code. Android answers first. For one
     * of the phone's sans-serif families, the picked font takes its place at the weight and slant
     * Android answered, so "sans-serif-medium" gets the file at 500. Any other family is Android's.
     */
    public static Typeface create(@Nullable String family, int style) {
        Typeface answer = Typeface.create(family, style);
        return isPhoneSans(family) ? pickedInstead(answer, DEFAULTS) : answer;
    }

    /**
     * {@link Typeface#create(Typeface, int)} for Facebook's own code: Android's answer, which the
     * picked font takes the place of when [family] is the phone's default, none at all, or the
     * picked font already. The spans that bold a word take the paint's typeface and a style.
     */
    public static Typeface create(@Nullable Typeface family, int style) {
        Typeface answer = Typeface.create(family, style);
        return isPhoneOrPicked(family) ? pickedInstead(answer, DEFAULTS) : answer;
    }

    /**
     * {@link Typeface#create(Typeface, int, boolean)} for Facebook's own code, the same way as the
     * style form, at the weight asked for.
     */
    public static Typeface create(@Nullable Typeface family, int weight, boolean italic) {
        Typeface answer = Typeface.create(family, weight, italic);
        return isPhoneOrPicked(family) ? pickedInstead(answer, DEFAULTS) : answer;
    }

    /**
     * Whether Android draws [family] in the phone's own sans-serif: no family, "sans-serif" and its
     * named weights, and Roboto under the names Facebook asks for it by. Android's condensed,
     * smallcaps and "sans-serif-monospace" are fonts of their own, and stay Android's.
     */
    static boolean isPhoneSans(@Nullable String family) {
        if (family == null || family.isEmpty()) return true;
        String name = family.toLowerCase(Locale.ROOT);
        for (String sans : new String[]{"sans-serif", "roboto"}) {
            if (name.equals(sans)) return true;
            if (name.startsWith(sans + "-")) return SANS_WEIGHTS.contains(name.substring(sans.length() + 1));
        }
        return false;
    }

    /** Whether [family] is none, one of the phone's sans-serif typefaces, or a typeface built from the picked file. */
    private static boolean isPhoneOrPicked(@Nullable Typeface family) {
        if (isPhoneTypeface(family)) return true;
        try {
            if (!Utils.settingsReady() || !Settings.USE_SYSTEM_FONT.get()) return false;
            Picked font = picked();
            return font != null && font.built(family);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_FONT, DEFAULTS, failure);
            return false;
        }
    }

    /**
     * Whether Android hands out [typeface] for the phone's sans-serif: none at all, the defaults,
     * defaultFromStyle's four, and "sans-serif" and each of its named weights in each style and at
     * each text weight, which is what a text view's layout gets for a style, one of those families
     * or a text weight. Compared by identity,
     * since Android keeps one typeface of each and hands the same one out every time.
     */
    static boolean isPhoneTypeface(@Nullable Typeface typeface) {
        if (typeface == null || typeface == Typeface.DEFAULT || typeface == Typeface.DEFAULT_BOLD
                || typeface == Typeface.SANS_SERIF) {
            return true;
        }
        Set<Typeface> known = phoneTypefaces;
        if (known == null) {
            Set<Typeface> built = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int style = Typeface.NORMAL; style <= Typeface.BOLD_ITALIC; style++) {
                built.add(Typeface.defaultFromStyle(style));
            }
            // A layout's text weight is a weight of its family, or of the default with none.
            for (String name : PHONE_SANS) {
                Typeface base = Typeface.create(name, Typeface.NORMAL);
                for (int style = Typeface.NORMAL; style <= Typeface.BOLD_ITALIC; style++) {
                    built.add(Typeface.create(base, style));
                }
                for (int weight = 100; weight <= 900; weight += 100) {
                    built.add(Typeface.create(base, weight, false));
                    built.add(Typeface.create(base, weight, true));
                }
            }
            for (int weight = 100; weight <= 900; weight += 100) {
                built.add(Typeface.create(Typeface.DEFAULT, weight, false));
                built.add(Typeface.create(Typeface.DEFAULT, weight, true));
            }
            // Published only once it's full, and never changed after, so readers need no lock.
            phoneTypefaces = known = built;
        }
        return known.contains(typeface);
    }

    /**
     * One of Android's text views Facebook just built, with its typeface from the layout or none.
     * While a font file is picked, a view with none or with one of the phone's sans-serif
     * typefaces takes the file at that weight and slant. Android draws a view with none in the
     * phone's default, in its own code, so this is the one place to reach it. A view with any other
     * typeface keeps it. Called right after Android's constructor, on whichever thread built the
     * view: for a view of Facebook's own that's its super call, so a typeface its constructor sets
     * afterwards still wins, and for a view an inflater made it's after the whole constructor.
     */
    public static void textView(@Nullable TextView view) {
        if (view == null) return;
        try {
            Typeface current = view.getTypeface();
            if (isPhoneTypeface(current)) {
                Typeface base = current == null ? Typeface.DEFAULT : current;
                Typeface instead = pickedInstead(base, VIEWS);
                if (instead != base) view.setTypeface(instead);
            }
            if (!view.getIncludeFontPadding() && needsPad(view.getPaint())) view.setIncludeFontPadding(true);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_FONT, VIEWS, failure);
        }
    }

    /**
     * A view one of Facebook's layout inflaters just made from a layout's tag, which goes through
     * {@link #textView} when it's a text view.
     */
    public static void inflated(@Nullable View view) {
        if (view instanceof TextView) textView((TextView) view);
    }

    /**
     * Android's StaticLayout.Builder.obtain for Facebook's own code (#96): Android's builder, kept
     * with [paint] for this thread so {@link #setIncludePad} knows the font it lays out. Facebook
     * sets a builder up right after obtaining it, on the same thread.
     */
    public static StaticLayout.Builder obtain(CharSequence source, int start, int end, TextPaint paint, int width) {
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(source, start, end, paint, width);
        Object[] last = OBTAINED.get();
        last[0] = builder;
        last[1] = paint;
        return builder;
    }

    /**
     * StaticLayout.Builder.setIncludePad for Facebook's own code (#96): Facebook's choice, or true
     * when [builder] lays out a font {@link #needsPad} pads. Facebook's text mostly turns the
     * padding off and sizes each line by the font's own ascent and descent, so a phone font whose
     * letters reach past those has their bottoms cut off: y, g and p lose their tails.
     */
    public static StaticLayout.Builder setIncludePad(StaticLayout.Builder builder, boolean includePad) {
        return builder.setIncludePad(includePad || padsFor(builder));
    }

    /** Whether {@link #needsPad} pads the paint [builder] was obtained for, when it's the one this thread obtained last. */
    static boolean padsFor(Object builder) {
        Object[] last = OBTAINED.get();
        return last[0] == builder && needsPad((Paint) last[1]);
    }

    /**
     * StaticLayout.Builder.build for Facebook's own code (#96): Android's build, with this thread
     * remembering for its length whether the layout takes the padding {@link #setIncludePad} gave
     * it, which is when Facebook's line-height spans lay out its lines ({@link #lineTop}).
     */
    public static StaticLayout build(StaticLayout.Builder builder) {
        boolean[] padded = BUILDING_PADDED.get();
        boolean before = padded[0];
        padded[0] = padsFor(builder);
        try {
            StaticLayout layout = builder.build();
            sample(layout, "StaticLayout.Builder");
            return layout;
        } finally {
            padded[0] = before;
        }
    }

    /**
     * A write of [top] to the top of a line by one of Facebook's line-height spans (#96). A span
     * fitting a line to a height of Facebook's design sets the first line's top, and the last line's
     * bottom, to the edges of that height, which takes back the font padding a padded layout uses
     * them for. So while a layout {@link #build} knows is padded is built, the top stays at least the
     * font's own; anywhere else, and for every font that fits its line box, it's Facebook's value.
     */
    public static void lineTop(int top, Paint.FontMetricsInt line) {
        if (line.top < top && BUILDING_PADDED.get()[0]) {
            HookStatus.counted(FamilyNames.SYSTEM_FONT, EDGE_KEPT);
            return;
        }
        line.top = top;
    }

    /** A write of [bottom] to the bottom of a line by one of Facebook's line-height spans, as {@link #lineTop}. */
    public static void lineBottom(int bottom, Paint.FontMetricsInt line) {
        if (line.bottom > bottom && BUILDING_PADDED.get()[0]) {
            HookStatus.counted(FamilyNames.SYSTEM_FONT, EDGE_KEPT);
            return;
        }
        line.bottom = bottom;
    }

    /** BoringLayout.make for Facebook's own code, its one-line text, padded the same way. */
    public static BoringLayout make(CharSequence source, TextPaint paint, int outerWidth, Layout.Alignment align,
            float spacingMult, float spacingAdd, BoringLayout.Metrics metrics, boolean includePad) {
        BoringLayout layout = BoringLayout.make(source, paint, outerWidth, align, spacingMult, spacingAdd, metrics,
                includePad || needsPad(paint));
        sample(layout, "BoringLayout.make");
        return layout;
    }

    /** BoringLayout.make with an ellipsis for Facebook's own code, padded the same way. */
    public static BoringLayout make(CharSequence source, TextPaint paint, int outerWidth, Layout.Alignment align,
            float spacingMult, float spacingAdd, BoringLayout.Metrics metrics, boolean includePad,
            TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
        BoringLayout layout = BoringLayout.make(source, paint, outerWidth, align, spacingMult, spacingAdd, metrics,
                includePad || needsPad(paint), ellipsize, ellipsizedWidth);
        sample(layout, "BoringLayout.make");
        return layout;
    }

    /**
     * With Debug logging on, one line for the first Bengali layout each of Facebook's builders makes
     * through {@link #build} or {@link #make} (#96): the font's line box at that size, where the
     * first line's letters really reach, and the room the layout gave them above and below the
     * baseline. Letters reaching higher than that room are what a view clipping to the layout cuts,
     * so a report shows which builder a cut caption came from and what it measured. The text stays
     * out of the log, since it can be anyone's post or message. Never throws.
     */
    static void sample(@Nullable Layout layout, String road) {
        if (layout == null || SAMPLED.size() >= MAX_SAMPLES || LOOKS.get() >= MAX_LOOKS) return;
        try {
            if (!Utils.settingsReady() || !BaseSettings.DEBUG.get()) return;
            CharSequence text = layout.getText();
            if (text == null || layout.getLineCount() == 0) return;
            // Only the first line is measured, so a post that opens in English doesn't spend the slot.
            CharSequence line = text.subSequence(layout.getLineStart(0), layout.getLineEnd(0));
            if (!hasBengali(line) || LOOKS.incrementAndGet() > MAX_LOOKS) return;
            String caller = caller();
            if (!SAMPLED.add(road + " " + caller)) return;
            TextPaint paint = layout.getPaint();
            Paint.FontMetricsInt font = paint.getFontMetricsInt();
            String first = line.toString();
            Rect letters = new Rect();
            paint.getTextBounds(first, 0, first.length(), letters);
            int above = layout.getLineBaseline(0) - layout.getLineTop(0);
            int below = layout.getLineBottom(0) - layout.getLineBaseline(0);
            Typeface face = paint.getTypeface();
            String kind = kind(face);
            int weight = face == null ? 400 : face.getWeight();
            boolean pads = needsPad(paint);
            Logger.printDebug(() -> String.format(Locale.ROOT, "Bengali layout from %s by %s: %.0f px, font top %d "
                    + "ascent %d descent %d bottom %d, letters reach %d above and %d below the baseline, "
                    + "line room %d above and %d below, padding %d and %d, %s typeface at weight %d, "
                    + "padded for the font %b",
                    road, caller, paint.getTextSize(), font.top, font.ascent, font.descent, font.bottom,
                    -letters.top, letters.bottom, above, below, layout.getTopPadding(), layout.getBottomPadding(),
                    kind, weight, pads));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_FONT, LAYOUTS, failure);
        }
    }

    /** Whether [text] has a Bengali letter or sign. */
    static boolean hasBengali(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0980 && c <= 0x09FF) return true;
        }
        return false;
    }

    /** The first three frames under OwnFont's own, which name the Facebook code that built the layout. */
    private static String caller() {
        StringBuilder frames = new StringBuilder();
        int kept = 0;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if (frame.getClassName().equals(OwnFont.class.getName())) continue;
            if (kept > 0) frames.append(" < ");
            frames.append(frame.getClassName()).append('.').append(frame.getMethodName());
            if (++kept == 3) break;
        }
        return frames.toString();
    }

    /** What [face] is to the patch, for {@link #sample}. */
    private static String kind(@Nullable Typeface face) {
        if (isPhoneTypeface(face)) return "the phone's";
        if (isPhoneOrPicked(face)) return "the picked file's";
        if (HANDED_OUT.contains(face)) return "a handed-out phone";
        return "another";
    }

    /**
     * Whether text drawn with [paint] needs the font padding: while the switch is on, for the
     * phone's font or the picked file, when its letters reach past its own line box. Meta's fonts and
     * the ones a story picks keep Facebook's layout. Never throws.
     */
    static boolean needsPad(@Nullable Paint paint) {
        if (paint == null) return false;
        try {
            if (!Utils.settingsReady() || !Settings.USE_SYSTEM_FONT.get()) return false;
            Typeface face = paint.getTypeface();
            if (!isPhoneOrPicked(face) && !HANDED_OUT.contains(face)) return false;
            return overflows(face == null ? Typeface.DEFAULT : face);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_FONT, LAYOUTS, failure);
            return false;
        }
    }

    /**
     * Whether [face]'s letters reach past the ascent and descent it declares, measured once. Some
     * fonts phones offer as the system font declare almost no descent and draw y, g and p well below
     * it, which Android's own views never show, since they lay out with the padding on.
     *
     * <p>Bengali and Devanagari are measured too, each against the line box Facebook's layouts give
     * it: the tallest ascent and descent of the font and of the fonts that draw the script, since
     * they lay lines out with the fallback fonts' spacing. A theme font made for those scripts can
     * hang its vowel signs below that box while its Latin letters fit (#96). A script the font
     * draws exactly as a serif paint does comes from the phone's fallback font, as it does in
     * Facebook's own font, and leaves the layout alone.
     */
    static boolean overflows(Typeface face) {
        Boolean known = OVERFLOWS.get(face);
        if (known != null) return known;
        Paint probe = new Paint();
        probe.setTypeface(face);
        probe.setTextSize(PROBE_SIZE);
        Paint.FontMetrics metrics = probe.getFontMetrics();
        Rect below = new Rect();
        probe.getTextBounds(DESCENDERS, 0, DESCENDERS.length(), below);
        Rect above = new Rect();
        probe.getTextBounds(ASCENDERS, 0, ASCENDERS.length(), above);
        boolean overflows = reachesPast(metrics.ascent, metrics.descent, above.top, below.bottom);
        if (overflows) {
            Logger.printInfo(() -> String.format(Locale.ROOT, "The font at weight %d draws past its line box at %.0f px "
                    + "(ascent %.1f, descent %.1f, letters %d to %d), so its text gets the font padding",
                    face.getWeight(), PROBE_SIZE, metrics.ascent, metrics.descent, above.top, below.bottom));
        }
        Paint.FontMetricsInt own = probe.getFontMetricsInt();
        Paint stockProbe = new Paint();
        stockProbe.setTypeface(Typeface.SERIF);
        stockProbe.setTextSize(PROBE_SIZE);
        for (int i = 0; i < SCRIPTS.length && !overflows; i++) {
            String script = SCRIPTS[i];
            Paint.FontMetricsInt drawing = fallbackLine(probe, script);
            Rect letters = new Rect();
            probe.getTextBounds(script, 0, script.length(), letters);
            Rect stock = new Rect();
            stockProbe.getTextBounds(script, 0, script.length(), stock);
            overflows = scriptReachesPast(own, drawing, letters, stock);
            if (overflows) {
                Logger.printInfo(() -> String.format(Locale.ROOT, "The font at weight %d draws %s past its line box at "
                        + "%.0f px (ascent %d, descent %d, letters %d to %d), so its text gets the font padding",
                        face.getWeight(), Character.UnicodeScript.of(script.codePointAt(0)), PROBE_SIZE,
                        Math.min(own.ascent, drawing.ascent), Math.max(own.descent, drawing.descent),
                        letters.top, letters.bottom));
            }
        }
        OVERFLOWS.put(face, overflows);
        HookStatus.counted(FamilyNames.SYSTEM_FONT, overflows ? PADDED : FITS);
        return overflows;
    }

    /**
     * The line box a layout spaced by its fallback fonts gives [script] in [paint], the way
     * Facebook's StaticLayouts build it. Paint's own per-text metrics call is Android 14 and later,
     * and Facebook runs from Android 11, so the line is read off a one-line layout instead.
     */
    static Paint.FontMetricsInt fallbackLine(Paint paint, String script) {
        StaticLayout layout = StaticLayout.Builder
                .obtain(script, 0, script.length(), new TextPaint(paint), 1 << 16)
                .setIncludePad(false)
                .setUseLineSpacingFromFallbacks(true)
                .build();
        Paint.FontMetricsInt line = new Paint.FontMetricsInt();
        line.ascent = layout.getLineAscent(0);
        line.descent = layout.getLineDescent(0);
        return line;
    }

    /**
     * Whether a script's [letters] reach past the line a fallback-spaced layout gives them, the
     * tallest of [own], the font's metrics, and [drawing], those of the fonts that draw the script.
     * Letters drawn exactly as [stock], the same text in a serif paint, come from the phone's
     * fallback font, and a script no font draws has no letters to reach with.
     */
    static boolean scriptReachesPast(Paint.FontMetricsInt own, Paint.FontMetricsInt drawing, Rect letters, Rect stock) {
        if (letters.isEmpty() || letters.equals(stock)) return false;
        return reachesPast(Math.min(own.ascent, drawing.ascent), Math.max(own.descent, drawing.descent),
                letters.top, letters.bottom);
    }

    /**
     * Whether letters from [top] to [bottom] reach past a line box from [ascent] to [descent], all
     * at {@link #PROBE_SIZE}, by more than 2% of it. Meta's Optimistic, Roboto and MiSans keep their
     * letters inside with room to spare.
     */
    static boolean reachesPast(float ascent, float descent, int top, int bottom) {
        float slack = PROBE_SIZE * 0.02f;
        return bottom > descent + slack || top < ascent - slack;
    }

    /**
     * Whether the settings can be read yet. When they can't, the typeface Facebook asked for through
     * [road] stays what it got, and the first such ask on each road is logged with the class that
     * made it. Facebook keeps some typefaces it gets while a class loads, and one kept that early
     * would never take the switch or the picked file. The context comes at the start of the
     * application's onCreate, and Facebook's text classes load after it.
     */
    private static boolean ready(String road) {
        if (Utils.settingsReady()) return true;
        if (!ASKED_EARLY.add(road)) return false;
        String caller = "";
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if (!frame.getClassName().equals(OwnFont.class.getName())) {
                caller = frame.getClassName() + "." + frame.getMethodName();
                break;
            }
        }
        String from = caller;
        Logger.printInfo(() -> "A typeface was asked for through the " + road + " before the settings loaded, by "
                + from + ", so it stays as it came");
        return false;
    }

    /**
     * The picked font at [answer]'s weight and slant while the switch is on and a font file is
     * picked, otherwise [answer], a typeface of the phone's font that Facebook got from [road].
     */
    private static Typeface pickedInstead(Typeface answer, String road) {
        try {
            HookStatus.invoked(FamilyNames.SYSTEM_FONT);
            if (answer == null) return null;
            if (!ready(road) || !Settings.USE_SYSTEM_FONT.get()) return answer;
            HookStatus.bound(FamilyNames.SYSTEM_FONT, road);
            Picked font = picked();
            Typeface styled = font == null ? null : font.styled(clamped(answer.getWeight()), answer.isItalic());
            return styled != null ? styled : answer;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_FONT, road, failure);
            return answer;
        }
    }

    /**
     * What React Native family [family] is: the weight its name ends in, 0 for a name with none, or
     * -1 when it isn't one of Meta's interface fonts. Those are the Optimistic families, under any
     * of the names Facebook gives them ("Optimistic VF App Lite 500", "Optimistic Display App", the
     * asset "Optimistic_Text_A_Bd"), and the variable Facebook Sans ("Facebook Sans VF App 700").
     * The heavy italic Facebook Sans and Facebook Narrow are display fonts for stories, and stay.
     */
    static int reactNativeFamily(String family) {
        Integer known = REACT_FAMILIES.get(family);
        if (known != null) return known;
        int answer = isReactNativeInterfaceFamily(family) ? weightSuffix(family) : -1;
        if (REACT_FAMILIES.size() < MAX_REACT_FAMILIES) REACT_FAMILIES.put(family, answer);
        return answer;
    }

    /** Whether [family], a name a React Native screen asks for, is one of Meta's interface fonts. */
    static boolean isReactNativeInterfaceFamily(@Nullable String family) {
        if (family == null) return false;
        StringBuilder letters = new StringBuilder(family.length());
        for (int index = 0; index < family.length(); index++) {
            char at = family.charAt(index);
            if (at >= 'A' && at <= 'Z') letters.append((char) (at + ('a' - 'A')));
            else if ((at >= 'a' && at <= 'z') || (at >= '0' && at <= '9')) letters.append(at);
        }
        String name = letters.toString();
        return name.startsWith("optimistic") || name.startsWith("facebooksansvf")
                || name.startsWith("facebooksansvariable");
    }

    /** The weight [family] ends in after a space, 1 to 1000, or 0 when it ends in none. */
    static int weightSuffix(String family) {
        int end = family.length();
        int start = end;
        while (start > 0 && start > end - 4 && family.charAt(start - 1) >= '0' && family.charAt(start - 1) <= '9') {
            start--;
        }
        if (start == end || start == 0 || family.charAt(start - 1) != ' ') return 0;
        int weight = Integer.parseInt(family.substring(start, end));
        return weight >= 1 && weight <= 1000 ? weight : 0;
    }

    /** The wght axis of [settings], or [fallback] when the string names none or one out of range. */
    static int weightOf(@Nullable String settings, int fallback) {
        if (settings == null) return fallback;
        Matcher axis = AXIS.matcher(settings);
        while (axis.find()) {
            if (!axis.group(1).equals("wght")) continue;
            int weight = Math.round(Float.parseFloat(axis.group(2)));
            return weight >= 1 && weight <= 1000 ? weight : fallback;
        }
        return fallback;
    }

    /** Whether [settings] asks for italics (an ital axis set, or a slant), else [fallback]. */
    static boolean italicOf(@Nullable String settings, boolean fallback) {
        if (settings == null) return fallback;
        Matcher axis = AXIS.matcher(settings);
        while (axis.find()) {
            String tag = axis.group(1);
            float value = Float.parseFloat(axis.group(2));
            if (tag.equals("ital")) return value >= 1;
            if (tag.equals("slnt")) return value != 0;
        }
        return fallback;
    }

    /**
     * The chosen font at [weight] (400 when out of range) and [italic]: the picked file's when
     * there is one and it loads, else the phone's default font.
     */
    static Typeface typeface(int weight, boolean italic) {
        int clamped = clamped(weight);
        Picked font = picked();
        if (font != null) {
            Typeface styled = font.styled(clamped, italic);
            if (styled != null) return styled;
        }
        Typeface phone = Typeface.create(Typeface.DEFAULT, clamped, italic);
        HANDED_OUT.add(phone);
        return phone;
    }

    /** [weight] when it's one Android takes, 1 to 1000, and 400 otherwise. */
    private static int clamped(int weight) {
        return weight >= 1 && weight <= 1000 ? weight : 400;
    }

    /**
     * Whether Android builds a typeface from [file], at its own weight and slant: the last check a
     * picked font passes before its copy is kept.
     */
    public static boolean loads(File file) {
        try {
            return new Typeface.Builder(file).build() != null;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Forgets the font read from the copy. The settings screen calls this after it writes a new
     * copy or removes one, so the next typeface is built from what's there now. It waits for a
     * read already under way, which could have begun on the old copy and would otherwise be kept
     * after this under a name that didn't change.
     */
    public static void fileChanged() {
        synchronized (OwnFont.class) {
            picked = null;
        }
    }

    /** The picked font, or null while the phone's font is chosen. Read from the copy the first time. */
    @Nullable
    private static Picked picked() {
        String source = Settings.FONT_SOURCE.get();
        if (source.isEmpty()) return null;
        Picked font = picked;
        if (font != null && font.source.equals(source)) return font;
        synchronized (OwnFont.class) {
            font = picked;
            if (font != null && font.source.equals(source)) return font;
            font = Picked.read(source);
            picked = font;
            return font;
        }
    }

    /** A picked font file, read once, and the typefaces built from it so far. */
    private static final class Picked {
        /** The name the file was picked under, which is what the saved setting holds. */
        final String source;
        /** The copy, or null when there's none to read. */
        @Nullable
        final File file;
        /** The copy at its own weight and slant, or null when it didn't load. */
        @Nullable
        final Typeface base;
        /** The least and greatest weight the copy's 'wght' axis takes, or null for a font without one. */
        @Nullable
        final float[] weights;
        /** Each weight and slant built so far, by weight times two plus one for italic. */
        final ConcurrentHashMap<Integer, Typeface> styles = new ConcurrentHashMap<>();

        private Picked(String source, @Nullable File file, @Nullable Typeface base, @Nullable float[] weights) {
            this.source = source;
            this.file = file;
            this.base = base;
            this.weights = weights;
        }

        /**
         * The copy as it is now. One that is missing or won't load reads as a font with nothing to
         * build, which draws in the phone's font, and says so once in the diagnostic log.
         */
        static Picked read(String source) {
            Context context = Utils.getContext();
            File file = context == null ? null : FontFile.file(context);
            if (file == null || !file.isFile()) {
                Logger.diagnosticInfo(DiagnosticCategory.SETTINGS, "OwnFont",
                        () -> "The picked font file's copy is missing, so Facebook's text is drawn in the phone's font");
                return new Picked(source, null, null, null);
            }
            Typeface base;
            try {
                base = new Typeface.Builder(file).build();
            } catch (RuntimeException failure) {
                base = null;
            }
            if (base == null) {
                Logger.diagnosticInfo(DiagnosticCategory.SETTINGS, "OwnFont",
                        () -> "The picked font file didn't load, so Facebook's text is drawn in the phone's font");
                return new Picked(source, file, null, null);
            }
            return new Picked(source, file, base, FontFile.weightAxis(file));
        }

        /** Whether [typeface] is the copy or one of the weights built from it. */
        boolean built(Typeface typeface) {
            return typeface == base || styles.containsValue(typeface);
        }

        /** The copy at [weight] and [italic], or null when it has nothing to build. */
        @Nullable
        Typeface styled(int weight, boolean italic) {
            if (base == null) return null;
            int key = weight * 2 + (italic ? 1 : 0);
            Typeface styled = styles.get(key);
            if (styled != null) return styled;
            styled = build(weight, italic);
            Typeface raced = styles.putIfAbsent(key, styled);
            return raced != null ? raced : styled;
        }

        /**
         * A variable font is built with its 'wght' axis at [weight], held inside the axis, and told
         * it is that weight, so Android doesn't embolden it again. A slant it has no axis for is
         * Android's. Any other font keeps its own outlines at the style asked for, and Android
         * draws a bold or a slant the font doesn't have, the way it does for a single-weight font
         * of its own.
         */
        private Typeface build(int weight, boolean italic) {
            if (weights != null && file != null) {
                int axis = Math.round(Math.max(weights[0], Math.min(weights[1], weight)));
                try {
                    Typeface built = new Typeface.Builder(file)
                            .setFontVariationSettings("'wght' " + axis)
                            .setWeight(weight)
                            .setItalic(false)
                            .build();
                    if (built != null) return italic ? Typeface.create(built, weight, true) : built;
                } catch (RuntimeException failure) {
                    // Falls back to the file at its own weight below.
                }
            }
            return Typeface.create(base, weight, italic);
        }
    }
}
