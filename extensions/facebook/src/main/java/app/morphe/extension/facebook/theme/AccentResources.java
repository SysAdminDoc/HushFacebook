/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.theme.AccentColor.Preset;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Route five of the Accent color patch: the blues Facebook draws straight from its colour resources.
 *
 * <p>Route one sees a colour Facebook's code resolves. A view Facebook inflates from layout XML
 * reads its colours inside the framework instead: the profile's bio link takes {@code
 * textColorLink}, which points at the BLUE_LINK attribute and so at #0064D1, and the Add to story
 * button's fill is a drawable that names #0866FF's resource. No hook sees either. So when an
 * activity is created, its resources get a resource table ({@link ColourTable}) that answers for
 * those colour resources with the accent: the colour resources Facebook's FDS styles give the
 * accent's tokens ({@link AccentColor#TOKENS}) when they hold one of Facebook's blues, as the patch
 * lists them in {@link #resourceBlues}. Each takes the colour route one would give it for its token.
 *
 * <p>Those resources also stand behind tokens the accent leaves alone (the verified badge, story
 * rings, Facebook's logo), so route one turns a colour of this table back into Facebook's
 * ({@link #facebookColour}) before it decides. A colour Facebook's code resolves comes out as it did
 * before; only what the framework reads straight from the resources takes the accent, there.
 *
 * <p>Facebook blue, a paused Facebook and the Material You theme add nothing ({@link AccentColor#chosen}).
 */
public final class AccentResources {

    /** One colour resource of the table: Facebook's colours for it and the token it's drawn for. */
    static final class Blue {
        final int id;
        final int colour;
        final boolean hasNight;
        final int night;
        final String token;

        Blue(int id, int colour, boolean hasNight, int night, String token) {
            this.id = id;
            this.colour = colour;
            this.hasNight = hasNight;
            this.night = night;
            this.token = token;
        }
    }

    /** One preset's table: the colours it gives, and back from each to Facebook's. */
    static final class Answer {
        final Preset preset;
        final int[] ids;
        final int[] colours;
        final int[] nights;
        final boolean[] hasNight;
        /** Each colour of the table, sorted, and Facebook's colour for it at the same index. */
        final int[] given;
        final int[] facebook;

        Answer(Preset preset, int[] ids, int[] colours, int[] nights, boolean[] hasNight, int[] given, int[] facebook) {
            this.preset = preset;
            this.ids = ids;
            this.colours = colours;
            this.nights = nights;
            this.hasNight = hasNight;
            this.given = given;
            this.facebook = facebook;
        }
    }

    /** The patch's table, read once. */
    private static volatile List<Blue> blues = parse(resourceBlues());

    /** The last preset's answer. Route one reads it on every colour, from any thread. */
    @Nullable
    private static volatile Answer latest;

    /** A loader for each preset, made once. Main thread only. */
    private static final Map<Preset, ResourcesLoader> LOADERS = new EnumMap<>(Preset.class);

    /**
     * {@link #onAssets}' Resources for each set of assets an activity has had. A loader's callbacks
     * hold them while it's in, and Facebook makes a new set of assets only on a configuration change.
     */
    private static final Map<AssetManager, Resources> ON_ASSETS = new IdentityHashMap<>();

    private static boolean watching;

    private AccentResources() {}

    /**
     * Filled in by the patch: the colour resources Facebook's FDS styles give a token, where they
     * hold one of Facebook's blues, as {@code "id=colour[/night]:TOKEN,TOKEN;..."} with the id and
     * colours in hex, sorted by id, and the tokens sorted.
     */
    @Nullable
    public static String resourceBlues() {
        return null;
    }

    /**
     * The table's colours whose tokens include one of the accent's, each with the token it's drawn for.
     * An entry it can't read is left out: this runs as the class loads, where a throw would fail
     * every colour route one resolves.
     */
    static List<Blue> parse(@Nullable String table) {
        if (table == null || table.isEmpty()) return Collections.emptyList();
        List<Blue> blues = new ArrayList<>();
        for (String entry : table.split(";")) {
            int equals = entry.indexOf('=');
            int colon = entry.indexOf(':');
            if (equals < 0 || colon < equals) continue;
            String token = drawnFor(entry.substring(colon + 1).split(","));
            if (token == null) continue;
            try {
                String[] values = entry.substring(equals + 1, colon).split("/");
                int id = (int) Long.parseLong(entry.substring(0, equals), 16);
                boolean hasNight = values.length > 1;
                blues.add(new Blue(id, (int) Long.parseLong(values[0], 16), hasNight,
                        hasNight ? (int) Long.parseLong(values[1], 16) : 0, token));
            } catch (NumberFormatException unreadable) {
                Logger.printInfo(() -> "Accent color: left out an unreadable colour table entry", unreadable);
            }
        }
        return Collections.unmodifiableList(blues);
    }

    /**
     * The token a colour resource is drawn for: of its tokens that are the accent's, a text one when
     * all of them are text, so it keeps text's contrast, otherwise the first fill. Null when none is
     * the accent's.
     */
    @Nullable
    static String drawnFor(String[] tokens) {
        String text = null;
        for (String token : tokens) {
            if (!AccentColor.TOKEN_SET.contains(token)) continue;
            if (!AccentColor.TEXT_TOKEN_SET.contains(token)) return token;
            if (text == null) text = token;
        }
        return text;
    }

    /**
     * {@code preset}'s colours for {@code blues}: route one's colour for each one's token, before
     * Facebook has said whether dark mode is on. A colour that stays Facebook's is left out.
     */
    static Answer answer(List<Blue> blues, Preset preset) {
        int count = 0;
        int[] ids = new int[blues.size()];
        int[] colours = new int[blues.size()];
        int[] nights = new int[blues.size()];
        boolean[] hasNight = new boolean[blues.size()];
        long[] pairs = new long[blues.size() * 2];
        int paired = 0;
        for (Blue blue : blues) {
            int colour = AccentColor.fds(blue.colour, blue.token, preset, false, false);
            int night = blue.hasNight ? AccentColor.fds(blue.night, blue.token, preset, false, false) : 0;
            boolean nightChanged = blue.hasNight && night != blue.night;
            if (colour == blue.colour && !nightChanged) continue;
            ids[count] = blue.id;
            colours[count] = colour;
            nights[count] = night;
            hasNight[count] = nightChanged;
            count++;
            if (colour != blue.colour) pairs[paired++] = pair(colour, blue.colour);
            if (nightChanged) pairs[paired++] = pair(night, blue.night);
        }
        // Sorted by the colour given, so route one finds Facebook's with a binary search. A colour
        // two of Facebook's blues both become keeps the first.
        long[] sorted = Arrays.copyOf(pairs, paired);
        Arrays.sort(sorted);
        int[] given = new int[paired];
        int[] facebook = new int[paired];
        int kept = 0;
        for (long pair : sorted) {
            int key = (int) (pair >> 32);
            if (kept > 0 && given[kept - 1] == key) continue;
            given[kept] = key;
            facebook[kept] = (int) pair;
            kept++;
        }
        return new Answer(preset, Arrays.copyOf(ids, count), Arrays.copyOf(colours, count), Arrays.copyOf(nights, count),
                Arrays.copyOf(hasNight, count), Arrays.copyOf(given, kept), Arrays.copyOf(facebook, kept));
    }

    /** A colour and Facebook's for it, which sort by the colour as a signed int, as the binary search reads it. */
    private static long pair(int given, int facebook) {
        return ((long) given) << 32 | (facebook & 0xFFFFFFFFL);
    }

    /** Package-visible for tests: puts a table of {@link #resourceBlues}'s form in use. */
    static void useTable(@Nullable String table) {
        blues = parse(table);
        latest = null;
    }

    /** {@code preset}'s answer for the patch's table, kept for the next call. */
    static Answer answer(Preset preset) {
        Answer known = latest;
        if (known != null && known.preset == preset) return known;
        Answer made = answer(blues, preset);
        latest = made;
        return made;
    }

    /**
     * Route one's first step: Facebook's colour for one this table gives under {@code preset}, so
     * a token the accent leaves alone stays Facebook's and one it takes is decided by route one's
     * own rules. Any other colour comes back as it is.
     */
    static int facebookColour(int color, Preset preset) {
        return blues.isEmpty() ? color : facebookColour(color, answer(preset));
    }

    /** Facebook's colour for one {@code answer} gives, or {@code color} itself. */
    static int facebookColour(int color, Answer answer) {
        int at = Arrays.binarySearch(answer.given, color);
        return at >= 0 ? answer.facebook[at] : color;
    }

    /**
     * Called once the application is created, with Accent color in the build: from then on each
     * activity's resources take the accent's table as the activity is created, before its theme
     * or its views read a colour.
     */
    public static synchronized void watchActivities(Context context) {
        if (watching || blues.isEmpty() || !(context instanceof Application)) return;
        ((Application) context).registerActivityLifecycleCallbacks(new Activities());
        watching = true;
    }

    /**
     * Gives the assets behind {@code activity}'s resources the chosen accent's table, and takes
     * another preset's off.
     *
     * <p>Facebook's activities hand out their own Resources (582 {@code LX/4bk;}), which take the
     * implementation of the Resources they wrap once, as they're made (582 {@code LX/3ml;}), and
     * forward {@code addLoaders} to that one. Android answers a loader added to Resources it manages
     * with a new implementation on new assets for it alone, so the wrapper, and the activity's theme
     * it makes, would go on reading the old assets, which is where layouts and the framework's
     * drawables read their colours. A Resources made here on those same assets isn't one
     * Android manages, and Android puts a loader added to such Resources into its assets in place:
     * every Resources and theme on them takes the table, Facebook's wrapper and the one it wraps
     * included.
     */
    static void recolour(Activity activity) {
        try {
            Preset preset = Utils.settingsReady() ? AccentColor.chosen() : null;
            ResourcesLoader wanted = preset == null ? null : loader(activity.getPackageName(), preset);
            if (wanted == null && LOADERS.isEmpty()) return;
            Resources onAssets = onAssets(activity.getResources());
            for (ResourcesLoader other : LOADERS.values()) {
                if (other != wanted) onAssets.removeLoaders(other);
            }
            if (wanted != null) onAssets.addLoaders(wanted);
        } catch (Throwable failure) {
            // Throwable, as in every hook: this runs as each activity is created.
            Logger.printException(() -> "Accent color: could not give the resources the accent", failure);
        }
    }

    /**
     * Resources of this class's own on {@code resources}' assets, one for each set of assets, so a
     * loader's callbacks hold one Resources for them however many activities share them.
     */
    @SuppressWarnings("deprecation") // The constructor is the public way to Resources Android doesn't manage.
    private static synchronized Resources onAssets(Resources resources) {
        AssetManager assets = resources.getAssets();
        Resources known = ON_ASSETS.get(assets);
        if (known != null) return known;
        Resources made = new Resources(assets, resources.getDisplayMetrics(), resources.getConfiguration());
        ON_ASSETS.put(assets, made);
        return made;
    }

    /** {@code preset}'s loader, made the first time, or null when the table gives it nothing. */
    @Nullable
    private static ResourcesLoader loader(String packageName, Preset preset) throws Exception {
        ResourcesLoader known = LOADERS.get(preset);
        if (known != null) return known;
        Answer answer = answer(preset);
        if (answer.ids.length == 0) return null;
        byte[] table = ColourTable.write(packageName, answer.ids, answer.colours, answer.nights, answer.hasNight);
        FileDescriptor memory = Os.memfd_create("hushfacebook-accent.arsc", 0);
        try {
            // A stream on a descriptor it didn't open leaves the descriptor open when it closes.
            try (FileOutputStream out = new FileOutputStream(memory)) {
                out.write(table);
            }
            try (ParcelFileDescriptor copy = ParcelFileDescriptor.dup(memory)) {
                ResourcesLoader loader = new ResourcesLoader();
                loader.addProvider(ResourcesProvider.loadFromTable(copy, null));
                LOADERS.put(preset, loader);
                Logger.printDebug(() -> "Accent color: " + answer.ids.length + " colour resources take " + preset.fileValue);
                return loader;
            }
        } finally {
            Os.close(memory);
        }
    }

    private static final class Activities implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle state) {
            recolour(activity);
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
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
}
