/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Locale;

import app.morphe.extension.shared.Logger;

/**
 * Route two for the Background color row (issue #214). The patch wrote its colour over Facebook's
 * dark greys in the colour resources, and a view Facebook inflates from layout XML reads those
 * inside the framework, where no hook sees it. So when the row asks for another colour, the
 * application's resources and each activity's get a resource table ({@link ColourTable}) that answers for those same
 * resources with the row's colour, as Accent color's route five does for its blues
 * ({@link AccentResources}, whose Resources on each set of assets this shares, so both tables sit
 * in the assets together).
 *
 * <p>The patch lists the resources in {@link #writtenColours}: each one route two wrote its colour
 * over, in the default configuration, the night one or both, with the value the other one keeps.
 * {@link AmoledTheme#PAGE_COLOUR}, the page's colour the patch adds, joins them here, since its id
 * is only known once the APK is built.
 *
 * <p>The Light background color row takes the same path in light mode. Facebook's light style
 * points its surfaces at white and light greys that its text, icons and dividers share, so the
 * patch gives each of those surfaces a colour resource of its own ({@link #LIGHT_SURFACE}) and
 * lists Facebook's colours in {@link #lightSurfaces}. With the row set, the table gives each the
 * row's fill of that colour ({@link AmoledLightBackground#fill}), in the default configuration only:
 * the dark style sets each of those surfaces itself, so dark mode never reads them.
 */
public final class AmoledResources {

    /** The patch's table, read once: ids sorted, with each one's default and night colours. */
    static final class Table {
        static final Table EMPTY = new Table(new int[0], new int[0], new boolean[0], new boolean[0]);

        final int[] ids;
        /** The default colour where {@link #defaultWritten} is false. */
        final int[] defaults;
        final boolean[] defaultWritten;
        /** Whether route two wrote the night colour too. A night colour the table gives is always the page's. */
        final boolean[] hasNight;

        Table(int[] ids, int[] defaults, boolean[] defaultWritten, boolean[] hasNight) {
            this.ids = ids;
            this.defaults = defaults;
            this.defaultWritten = defaultWritten;
            this.hasNight = hasNight;
        }
    }

    private static volatile Table table = parse(writtenColours());

    /**
     * The start of the name of each colour resource the patch adds for a light surface, before
     * Facebook's colour as six lowercase hex digits.
     */
    static final String LIGHT_SURFACE = "hushfacebook_amoled_light_";

    /** Facebook's colours that {@link #lightSurfaces} lists, opaque. */
    private static volatile int[] lightColours = parseLight(lightSurfaces());

    /** The loader for the colour in use, made once. Main thread only. */
    @Nullable
    private static ResourcesLoader loader;

    private static boolean watching;

    /** Makes a loader from a resource table's bytes. */
    interface LoaderMaker {
        ResourcesLoader make(byte[] table) throws Exception;
    }

    /** Package-visible for tests, which have no memory files ({@link AccentResources#loaderFor}) to read a table from. */
    static LoaderMaker maker = table -> AccentResources.loaderFor("hushfacebook-amoled.arsc", table);

    private AmoledResources() {}

    /**
     * Filled in by the patch: each colour resource route two wrote its colour over, as
     * {@code "id=D[/N];..."} with the id in hex, sorted by id. {@code D} is the default colour,
     * {@code p} where route two wrote the page's or eight hex digits where it left Facebook's, and
     * {@code /p} says route two wrote the night one too. A resource it wrote at night only has its
     * default colour given, so the table can carry it.
     */
    @Nullable
    public static String writtenColours() {
        return null;
    }

    /**
     * Filled in by the patch: each colour of Facebook's light style that a surface now reads from a
     * resource of its own, {@link #LIGHT_SURFACE} and the colour, as {@code "rrggbb;..."} in hex.
     */
    @Nullable
    public static String lightSurfaces() {
        return null;
    }

    /** {@link #lightSurfaces}'s colours, opaque. One it can't read is left out, as in {@link #parse}. */
    static int[] parseLight(@Nullable String text) {
        if (text == null || text.isEmpty()) return new int[0];
        String[] entries = text.split(";");
        int[] colours = new int[entries.length];
        int count = 0;
        for (String entry : entries) {
            if (entry.length() != 6) continue;
            try {
                int colour = Integer.parseInt(entry, 16);
                colours[count++] = 0xFF000000 | colour;
            } catch (NumberFormatException unreadable) {
                Logger.printInfo(() -> "AMOLED: left out an unreadable light surface entry", unreadable);
            }
        }
        return Arrays.copyOf(colours, count);
    }

    /** Package-visible for tests: puts a list of {@link #lightSurfaces}'s form in use. */
    static void useLightSurfaces(@Nullable String text) {
        lightColours = parseLight(text);
        loader = null;
    }

    /**
     * Whether {@code color} is the fill the row's colour {@code row} gives one of the light surfaces,
     * which is what a surface reads once the table is in place. Route one leaves those as they are.
     */
    static boolean givesLight(int color, int row) {
        for (int light : lightColours) {
            if (AmoledLightBackground.fill(row, light) == color) return true;
        }
        return false;
    }

    /**
     * The light surfaces' entries for the row's colour {@code row}: index 0 the ids, 1 their default
     * colours. {@code ids} holds each of {@code colours}' resource ids, or 0 where the build has none.
     */
    static int[][] lightEntries(int[] ids, int[] colours, int row) {
        int[] kept = new int[ids.length];
        int[] defaults = new int[ids.length];
        int count = 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] <= 0 || !AmoledTheme.isLightNeutral(colours[i])) continue;
            kept[count] = ids[i];
            defaults[count] = AmoledLightBackground.fill(row, colours[i]);
            count++;
        }
        return new int[][] {Arrays.copyOf(kept, count), Arrays.copyOf(defaults, count)};
    }

    /**
     * {@link #colours}' answer with the light surfaces' {@code light} ({@link #lightEntries}) added
     * after it, in the default configuration only. An id it lists already keeps its entry.
     */
    static int[][] joined(int[][] given, int[][] light) {
        int[] ids = Arrays.copyOf(given[0], given[0].length + light[0].length);
        int[] defaults = Arrays.copyOf(given[1], ids.length);
        int[] nights = Arrays.copyOf(given[2], ids.length);
        int[] hasNight = Arrays.copyOf(given[3], ids.length);
        int size = given[0].length;
        for (int i = 0; i < light[0].length; i++) {
            boolean listed = false;
            for (int j = 0; j < size && !listed; j++) listed = ids[j] == light[0][i];
            if (listed) continue;
            ids[size] = light[0][i];
            defaults[size] = light[1][i];
            size++;
        }
        return new int[][] {Arrays.copyOf(ids, size), Arrays.copyOf(defaults, size), Arrays.copyOf(nights, size),
                Arrays.copyOf(hasNight, size)};
    }

    /**
     * Whether a table is wanted: the Background color row has a colour in place of the patch's and
     * route two wrote resources for it, or the Light background color row has one and the build has
     * light surfaces for it.
     */
    static boolean inUse() {
        return (AmoledTheme.background() != AmoledTheme.written() && table.ids.length > 0)
                || (AmoledTheme.lightBackground() != 0 && lightColours.length > 0);
    }

    /**
     * The table's entries. One it can't read is left out: this runs as the class loads, where a
     * throw would fail every colour resource read.
     */
    static Table parse(@Nullable String text) {
        if (text == null || text.isEmpty()) return Table.EMPTY;
        String[] entries = text.split(";");
        long[] order = new long[entries.length];
        int[] ids = new int[entries.length];
        int[] defaults = new int[entries.length];
        boolean[] defaultWritten = new boolean[entries.length];
        boolean[] hasNight = new boolean[entries.length];
        int count = 0;
        for (String entry : entries) {
            int equals = entry.indexOf('=');
            if (equals <= 0) continue;
            try {
                int id = (int) Long.parseLong(entry.substring(0, equals), 16);
                String[] values = entry.substring(equals + 1).split("/", -1);
                if (values.length > 2 || (values.length == 2 && !"p".equals(values[1]))) continue;
                boolean written = "p".equals(values[0]);
                defaults[count] = written ? 0 : (int) Long.parseLong(values[0], 16);
                defaultWritten[count] = written;
                hasNight[count] = values.length == 2;
                // An app's resource ids are positive, so the id in the high half sorts by id.
                order[count] = ((long) id) << 32 | count;
                ids[count] = id;
                count++;
            } catch (NumberFormatException unreadable) {
                Logger.printInfo(() -> "AMOLED: left out an unreadable colour table entry", unreadable);
            }
        }
        long[] sorted = Arrays.copyOf(order, count);
        Arrays.sort(sorted);
        int[] sortedIds = new int[count];
        int[] sortedDefaults = new int[count];
        boolean[] sortedWritten = new boolean[count];
        boolean[] sortedNight = new boolean[count];
        int kept = 0;
        for (long pair : sorted) {
            int at = (int) pair;
            if (kept > 0 && sortedIds[kept - 1] == ids[at]) continue;
            sortedIds[kept] = ids[at];
            sortedDefaults[kept] = defaults[at];
            sortedWritten[kept] = defaultWritten[at];
            sortedNight[kept] = hasNight[at];
            kept++;
        }
        return new Table(Arrays.copyOf(sortedIds, kept), Arrays.copyOf(sortedDefaults, kept),
                Arrays.copyOf(sortedWritten, kept), Arrays.copyOf(sortedNight, kept));
    }

    /** Package-visible for tests: puts a table of {@link #writtenColours}'s form in use. */
    static void useTable(@Nullable String text) {
        table = parse(text);
        loader = null;
    }

    /** Whether route two wrote the page's colour into resource {@code id}, by default or at night. */
    static boolean wrote(int id) {
        return Arrays.binarySearch(table.ids, id) >= 0;
    }

    /**
     * The colours the loader gives for {@code colour}: the table's, with {@code page} added when the
     * build has the page's own resource and the table doesn't list it. Index 0 to 3 of the answer are
     * the ids, the default colours, the night colours and, as 0 or 1, whether each has one.
     */
    static int[][] colours(Table table, int colour, int page) {
        int extra = page > 0 && Arrays.binarySearch(table.ids, page) < 0 ? 1 : 0;
        int size = table.ids.length + extra;
        int[] ids = Arrays.copyOf(table.ids, size);
        int[] defaults = new int[size];
        int[] nights = new int[size];
        int[] hasNight = new int[size];
        for (int i = 0; i < table.ids.length; i++) {
            defaults[i] = table.defaultWritten[i] ? colour : table.defaults[i];
            nights[i] = table.hasNight[i] ? colour : 0;
            hasNight[i] = table.hasNight[i] ? 1 : 0;
        }
        if (extra == 1) {
            ids[size - 1] = page;
            defaults[size - 1] = colour;
        }
        return new int[][] {ids, defaults, nights, hasNight};
    }

    /**
     * Called once the application is created, with AMOLED in the build: from then on, while the
     * Background color row or the Light background color row has a colour in use
     * ({@link AmoledTheme#load}), each activity's resources take it as the activity is created,
     * before its theme or its views read a colour.
     */
    public static synchronized void watchActivities(Context context) {
        if (watching || !inUse() || !(context instanceof Application)) return;
        ((Application) context).registerActivityLifecycleCallbacks(new Activities());
        watching = true;
    }

    /**
     * Gives the assets behind {@code context}'s resources the row's colour, as
     * {@link AccentResources#recolour} does: each activity's as it's created, and the application's
     * as soon as {@link AmoledTheme#load} has the row's colour. Facebook builds Home's feed while it
     * starts, before its first activity, on the application's assets, and every activity shares
     * those, so the feed's page, its composer and the Reels row kept the patch's colour (#214).
     * Android 9 and 10 have no resource loaders, so there the row reaches only what its hooks see.
     */
    static void recolour(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            if (!inUse()) return;
            ResourcesLoader wanted = loader(context);
            if (wanted != null) AccentResources.onAssets(context.getResources()).addLoaders(wanted);
        } catch (Throwable failure) {
            // Throwable, as in every hook: this runs as Facebook starts and as each activity is created.
            Logger.printException(() -> "AMOLED: could not give the resources the Background color", failure);
        }
    }

    /**
     * The loader for the rows' colours, made the first time: route two's resources while the
     * Background color row has a colour of its own, and the light surfaces while the Light
     * background color row has one.
     */
    @Nullable
    private static ResourcesLoader loader(Context context) throws Exception {
        ResourcesLoader known = loader;
        if (known != null) return known;
        int colour = AmoledTheme.background();
        int[][] given = colour == AmoledTheme.written() || table.ids.length == 0
                ? new int[][] {new int[0], new int[0], new int[0], new int[0]}
                : colours(table, colour, AmoledTheme.pageColour(context));
        int row = AmoledTheme.lightBackground();
        int light = 0;
        if (row != 0 && lightColours.length > 0) {
            int[][] surfaces = lightEntries(lightIds(context, lightColours), lightColours, row);
            light = surfaces[0].length;
            given = joined(given, surfaces);
        }
        int[] ids = given[0];
        if (ids.length == 0) return null;
        boolean[] hasNight = new boolean[ids.length];
        for (int i = 0; i < ids.length; i++) hasNight[i] = given[3][i] == 1;
        byte[] bytes = ColourTable.write(context.getPackageName(), ids, given[1], given[2], hasNight);
        ResourcesLoader made = maker.make(bytes);
        loader = made;
        int lightCount = light;
        Logger.printDebug(() -> "AMOLED: " + (ids.length - lightCount) + " colour resources take the Background color, "
                + lightCount + " the Light background color");
        return made;
    }

    /** The resource id of each light surface's colour resource ({@link #LIGHT_SURFACE}), or 0 where the build has none. */
    static int[] lightIds(Context context, int[] colours) {
        int[] ids = new int[colours.length];
        for (int i = 0; i < colours.length; i++) {
            String name = LIGHT_SURFACE + String.format(Locale.ROOT, "%06x", colours[i] & 0xFFFFFF);
            ids[i] = context.getResources().getIdentifier(name, "color", context.getPackageName());
        }
        return ids;
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
