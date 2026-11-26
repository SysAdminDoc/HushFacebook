/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.util.TypedValue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.theme.AccentColor.Preset;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * D#527: the Material You theme has a switch, so turning it off needs no new patch. Off, every route
 * answers the colour it was given, route three's fields hold Facebook's surfaces, and the resources
 * take back what the patch changed, before a picked accent's table so the accent still wins.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MaterialYouSwitchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for FDS tokens: only the constant names matter. */
    enum Token {
        SURFACE_BACKGROUND, PRIMARY_TEXT, BLUE_LINK
    }

    private static final AccentResources.LoaderMaker MAKER = AccentResources.maker;
    private static final AccentResources.ListReader ORIGINALS = AccentResources.originalsList;
    private final ResourcesLoader off = new ResourcesLoader();
    private final ResourcesLoader accent = new ResourcesLoader();

    @Before
    public void dark() {
        MaterialYouSwitch.forgetForTests();
        MaterialYouTheme.use(TonePalette.fallback(), false);
        DarkMode.answer(true);
    }

    @After
    public void restore() {
        Resources assets = AccentResources.onAssets(application());
        assets.removeLoaders(off);
        assets.removeLoaders(accent);
        AccentResources.forgetLoadersForTests();
        AccentResources.maker = MAKER;
        AccentResources.originalsList = ORIGINALS;
        AccentResources.useTable(null);
        PauseForTests.resume();
        Settings.MATERIAL_YOU.resetToDefault();
        Settings.ACCENT_COLOR.resetToDefault();
        MaterialYouSwitch.forgetForTests();
        MaterialYouTheme.use(TonePalette.fallback(), false);
        DarkMode.answer(true);
    }

    private static void switchOff() {
        Settings.MATERIAL_YOU.save(false);
        MaterialYouSwitch.forgetForTests();
    }

    /** Each route with a colour the theme takes when it's on, so an answer left alone means the switch did it. */
    private static Map<String, IntSupplier> routes() {
        Map<String, IntSupplier> routes = new LinkedHashMap<>();
        routes.put("FDS surface", () -> MaterialYouTheme.fds(0xFF252728, Token.SURFACE_BACKGROUND));
        routes.put("FDS link", () -> MaterialYouTheme.fds(0xFF5AA7FF, Token.BLUE_LINK));
        routes.put("Mig", () -> MaterialYouTheme.mig(0xFF252728, Token.PRIMARY_TEXT));
        routes.put("server colour", () -> MaterialYouTheme.parseColor("#252728"));
        routes.put("link box", () -> MaterialYouTheme.parseLinkBox("#333334"));
        routes.put("Bloks pair", () -> MaterialYouTheme.parseBloks("#333334", false));
        routes.put("dark branch", () -> MaterialYouTheme.darkBranch(0xFF333334));
        routes.put("Bloks sheet", () -> MaterialYouTheme.sheetBackground(0xFF1F1F20, false));
        routes.put("Bloks box", () -> MaterialYouTheme.bloksFill(0xFF333334, false));
        routes.put("status bar", () -> MaterialYouTheme.statusBar(0xFF252728, true, false));
        routes.put("navigation bar", () -> MaterialYouTheme.navigationBar(0xFF252728, true, false));
        routes.put("selected tab", () -> MaterialYouTheme.tabBarSelected(0xFFF2F4F7));
        routes.put("React text", () -> MaterialYouTheme.react(0xFF5AA7FF));
        routes.put("React background", () -> MaterialYouTheme.reactBackground(0xFF333334));
        routes.put("theme value", () -> {
            TypedValue value = new TypedValue();
            value.type = TypedValue.TYPE_INT_COLOR_ARGB8;
            value.data = 0xFF252728;
            return MaterialYouTheme.colourData(value);
        });
        return routes;
    }

    private static final Map<String, Integer> GIVEN = new LinkedHashMap<>();

    static {
        GIVEN.put("FDS surface", 0xFF252728);
        GIVEN.put("FDS link", 0xFF5AA7FF);
        GIVEN.put("Mig", 0xFF252728);
        GIVEN.put("server colour", 0xFF252728);
        GIVEN.put("link box", 0xFF333334);
        GIVEN.put("Bloks pair", 0xFF333334);
        GIVEN.put("dark branch", 0xFF333334);
        GIVEN.put("Bloks sheet", 0xFF1F1F20);
        GIVEN.put("Bloks box", 0xFF333334);
        GIVEN.put("status bar", 0xFF252728);
        GIVEN.put("navigation bar", 0xFF252728);
        GIVEN.put("selected tab", 0xFFF2F4F7);
        GIVEN.put("React text", 0xFF5AA7FF);
        GIVEN.put("React background", 0xFF333334);
        GIVEN.put("theme value", 0xFF252728);
    }

    @Test
    public void switchedOffEveryRouteAnswersTheColourItWasGiven() {
        for (Map.Entry<String, IntSupplier> route : routes().entrySet()) {
            int given = GIVEN.get(route.getKey());
            assertNotEquals(route.getKey() + " is a colour the theme takes when it's on", given, route.getValue().getAsInt());
        }
        switchOff();
        for (Map.Entry<String, IntSupplier> route : routes().entrySet()) {
            assertEquals(route.getKey() + " kept the palette with the switch off", (long) GIVEN.get(route.getKey()),
                    route.getValue().getAsInt());
        }
    }

    /** With AMOLED in the build its rule goes first, and with the switch off its answer is the one drawn. */
    @Test
    public void switchedOffAmoledsAnswerIsTheOneDrawn() {
        switchOff();
        assertEquals(AmoledTheme.statusBar(0xFF252728, true), MaterialYouTheme.statusBar(0xFF252728, true, true));
        assertEquals(AmoledTheme.navigationBar(0xFF252728, true), MaterialYouTheme.navigationBar(0xFF252728, true, true));
        assertEquals(AmoledTheme.parseBloks("#B0B3B8"), MaterialYouTheme.parseBloks("#B0B3B8", true));
        assertEquals(AmoledTheme.parseBloks("#333334"), MaterialYouTheme.parseBloks("#333334", true));
    }

    /** Route three's fields: the palette's surfaces with the switch on, Facebook's own once it's off and Facebook starts. */
    @Test
    public void routeThreesFieldsHoldFacebooksSurfacesOnceItStartsSwitchedOff() {
        MaterialYouTheme.load();
        assertNotEquals("on, the palette's", 0xFF252728, MaterialYouTheme.DARK_252728);
        switchOff();
        MaterialYouTheme.load();
        int[] fields = {MaterialYouTheme.DARK_101011, MaterialYouTheme.DARK_18191A, MaterialYouTheme.DARK_1C1C1D,
                MaterialYouTheme.DARK_242526, MaterialYouTheme.DARK_252728, MaterialYouTheme.DARK_3E4042};
        int[] facebook = {0xFF101011, 0xFF18191A, 0xFF1C1C1D, 0xFF242526, 0xFF252728, 0xFF3E4042};
        assertEquals(Arrays.toString(facebook), Arrays.toString(fields));
    }

    /** One answer per start: the routes and the resources taken as Facebook started have to agree. */
    @Test
    public void theSwitchIsReadOncePerStart() {
        assertTrue(MaterialYouSwitch.switchedOn());
        Settings.MATERIAL_YOU.save(false);
        assertTrue("a change waits for the restart", MaterialYouSwitch.switchedOn());
        MaterialYouSwitch.forgetForTests();
        assertFalse(MaterialYouSwitch.switchedOn());
    }

    /** Pause reads every switch off, this one with them: Facebook as it ships. */
    @Test
    public void pausedTheThemeIsOff() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        MaterialYouSwitch.forgetForTests();
        assertFalse(MaterialYouSwitch.switchedOn());
        assertTrue("the saved value stays", Settings.MATERIAL_YOU.savedValue());
    }

    /**
     * Before the settings are ready every hook takes Facebook's own path, this theme's routes with
     * them, and the answer isn't kept: the start asks again once they're ready.
     */
    @Test
    public void untilTheSettingsAreReadyItReadsOffAndKeepsNoAnswer() {
        MaterialYouSwitch.forgetForTests();
        SettingsContextRule.withoutContext(() -> {
            assertFalse(MaterialYouSwitch.switchedOn());
            assertEquals("Facebook's own", 0xFF252728, MaterialYouTheme.parseColor("#252728"));
        });
        assertTrue("read once they're ready", MaterialYouSwitch.switchedOn());
    }

    /** Callers outside the theme ask whether it's in the build as well, and only an off theme in the build takes its colours back. */
    @Test
    public void runningNeedsTheBuild() {
        MaterialYouSwitch.inBuildForTests = false;
        assertFalse(MaterialYouSwitch.on());
        assertFalse(MaterialYouSwitch.off());
        MaterialYouSwitch.inBuildForTests = true;
        assertTrue(MaterialYouSwitch.on());
        assertFalse(MaterialYouSwitch.off());
        switchOff();
        MaterialYouSwitch.inBuildForTests = true;
        assertFalse(MaterialYouSwitch.on());
        assertTrue(MaterialYouSwitch.off());
    }

    /**
     * The patch's list: Facebook's night colours by id, its own colours that night style items point
     * at by name, each with the colour it had or the colour resource the style named.
     */
    @Test
    public void theTablePutsBackWhatTheMaterialYouPatchChanged() {
        List<AccentResources.Original> originals = AccentResources.parseOriginals(
                "7f06044f=#ff252728;hushfacebook_you_was_c_ff333334=#ff333334;hushfacebook_you_was_r_blue=@7f0601d5;"
                        + "hushfacebook_gone=#ff000000;7f060001=@0;7f0600zz=#ff000000;unreadable;7f060002=ff000000",
                name -> name.equals("hushfacebook_you_was_c_ff333334") ? 0x7f060900
                        : name.equals("hushfacebook_you_was_r_blue") ? 0x7f060901 : 0);
        assertEquals("a name with no id, no colour and what can't be read are left out", 3, originals.size());
        assertTrue(originals.get(0).night);
        assertEquals(0x7f06044f, originals.get(0).id);
        assertEquals(0xFF252728, originals.get(0).value);
        assertFalse(originals.get(1).night);
        assertFalse(originals.get(1).reference);
        assertTrue(originals.get(2).reference);
        assertEquals(0x7f0601d5, originals.get(2).value);
        assertTrue(AccentResources.parseOriginals(null, name -> 1).isEmpty());

        ColourTableReader table = new ColourTableReader(AccentResources.originalsTable("com.facebook.katana", originals));
        assertEquals("night, night-v8, night-v31 and default", 4, table.configs.size());
        int[][] configs = {{0x20, 0}, {0x20, 8}, {0x20, 31}, {0, 0}};
        for (int i = 0; i < configs.length; i++) {
            ColourTableReader.Config config = table.configs.get(i);
            assertEquals(configs[i][0], config.uiMode);
            assertEquals(configs[i][1], config.sdkVersion);
            Map<Integer, Integer> colours = new LinkedHashMap<>();
            Map<Integer, Integer> types = new LinkedHashMap<>();
            if (config.uiMode != 0) {
                colours.put(0x44f, 0xFF252728);
                types.put(0x44f, 0x1c);
            } else {
                colours.put(0x900, 0xFF333334);
                types.put(0x900, 0x1c);
                colours.put(0x901, 0x7f0601d5);
                types.put(0x901, 0x01);
            }
            assertEquals("config " + i, colours, config.colours);
            assertEquals("config " + i + " types", types, config.types);
        }
        assertNull(AccentResources.originalsTable("p", Collections.emptyList()));
    }

    /**
     * Switched off, the application's resources take the table as Facebook starts, before a picked
     * accent's, so the accent's night blue wins the tie; switched on they take neither.
     */
    @Test
    public void switchedOffTheResourcesTakeItBackBeforeAPickedAccent() throws Exception {
        MaterialYouSwitch.inBuildForTests = true;
        AccentResources.useTable("7f0601d5=ff0866ff:ACCENT,PRIMARY_BUTTON_BACKGROUND");
        AccentResources.originalsList = (resources, packageName) -> "7f06044f=#ff252728";
        AccentResources.maker = (name, table) -> name.equals("hushfacebook-you-off.arsc") ? off : accent;
        Settings.ACCENT_COLOR.save(Preset.TEAL);

        MaterialYouTheme.load();
        List<ResourcesLoader> loaders = loaders(application());
        assertFalse("on, the theme takes nothing back", loaders.contains(off));
        assertFalse("on, the theme decides the accent", loaders.contains(accent));

        switchOff();
        MaterialYouSwitch.inBuildForTests = true;
        MaterialYouTheme.load();
        loaders = loaders(application());
        assertTrue("off, the resources take Facebook's colours back", loaders.contains(off));
        assertTrue("off, the picked accent works as without the theme", loaders.contains(accent));
        assertTrue("the accent's table comes after, so it wins a tie", loaders.indexOf(off) < loaders.indexOf(accent));
    }

    /** A Material You build gives Facebook's night blues a night-v31 value, which only an entry of that configuration beats. */
    @Test
    public void anAccentTableInAMaterialYouBuildAnswersAtNightV31Too() {
        int[] ids = {0x7f0601d5};
        ColourTableReader table = new ColourTableReader(ColourTable.write("p", ids, new int[]{0xFF00796B},
                new int[]{0xFF4DB6AC}, new boolean[]{true}, true));
        assertEquals("default, night, night-v8 and night-v31", 4, table.configs.size());
        assertEquals(31, table.configs.get(3).sdkVersion);
        assertEquals(0x20, table.configs.get(3).uiMode);
        assertEquals(Integer.valueOf(0xFF4DB6AC), table.configs.get(3).colours.get(0x1d5));
        assertEquals("without it, three", 3,
                new ColourTableReader(ColourTable.write("p", ids, new int[]{1}, new int[]{2}, new boolean[]{true})).configs.size());
    }

    private static Resources application() {
        return RuntimeEnvironment.getApplication().getResources();
    }

    /** The loaders on {@code resources}' assets: hidden in the SDK, public in the framework Robolectric runs. */
    @SuppressWarnings("unchecked")
    private static List<ResourcesLoader> loaders(Resources resources) throws ReflectiveOperationException {
        return (List<ResourcesLoader>) Resources.class.getMethod("getLoaders").invoke(resources);
    }
}
