/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.os.LocaleList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How the catalog picks a language: the phone's list in order, a region falling back to its
 * language, both codes Indonesian goes by, English for everything else, and the pseudo-locales.
 * And that none of it touches the language Facebook itself runs in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class L10nTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String KEY = "Hide sponsored posts";

    @Test
    public void everyLanguageTheBundleCarriesHasATableWithTheKey() {
        assertEquals(Arrays.asList("de", "es", "in", "pt-rbr", "ru", "tr", "vi", "zh-rcn", "zh-rtw"),
                Arrays.asList(L10nTranslations.LANGUAGES));
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nTranslations.of(language);
            assertNotNull(language, table);
            assertTrue(language + " has no row for " + KEY, table.containsKey(KEY));
        }
        assertNull("a language with no table has a table", L10nTranslations.of("fr"));
    }

    @Test
    public void aLanguageAnswersInItsOwnTable() {
        for (String language : L10nTranslations.LANGUAGES) {
            Locale locale = locale(language);
            assertEquals(language, table(language).get(KEY), L10n.t(in(locale), KEY));
        }
    }

    @Test
    public void aRegionFallsBackToItsLanguageAndBrazilianPortugueseStaysBrazilian() {
        assertEquals(table("de").get(KEY), L10n.t(in(new Locale("de", "AT")), KEY));
        assertEquals(table("es").get(KEY), L10n.t(in(new Locale("es", "MX")), KEY));
        assertEquals(table("tr").get(KEY), L10n.t(in(new Locale("tr", "CY")), KEY));
        // Only Brazil has a Portuguese table. Portugal's Portuguese isn't Brazil's, so a phone set
        // to it reads English rather than a neighbour's spelling it never asked for.
        assertEquals(table("pt-rbr").get(KEY), L10n.t(in(new Locale("pt", "BR")), KEY));
        assertEquals(KEY, L10n.t(in(new Locale("pt", "PT")), KEY));
    }

    /** With no explicit script, the region chooses the Chinese table. Plain zh stays Simplified. */
    @Test
    public void chineseRegionsChooseTheMatchingScript() {
        String simplified = table("zh-rcn").get(KEY);
        String traditional = table("zh-rtw").get(KEY);
        assertNotEquals(KEY, simplified);
        assertNotEquals(KEY, traditional);
        assertNotEquals(simplified, traditional);
        for (String region : new String[]{"CN", "SG", "MY", ""}) {
            assertEquals("zh-" + region, simplified, L10n.t(in(new Locale("zh", region)), KEY));
        }
        for (String region : new String[]{"TW", "HK", "MO"}) {
            assertEquals("zh-" + region, traditional, L10n.t(in(new Locale("zh", region)), KEY));
        }
    }

    /** A declared script takes priority even when the region normally reads the other one. */
    @Test
    public void anExplicitChineseScriptWinsOverItsRegion() {
        for (String region : new String[]{"CN", "SG", "MY", "TW", "HK", "MO", ""}) {
            Locale traditional = chinese("Hant", region);
            assertEquals(traditional.toLanguageTag(), table("zh-rtw").get(KEY), L10n.t(in(traditional), KEY));
            Locale simplified = chinese("Hans", region);
            assertEquals(simplified.toLanguageTag(), table("zh-rcn").get(KEY), L10n.t(in(simplified), KEY));
        }
    }

    @Test
    public void chineseChoicesKeepTheLanguageListsOrderAndEnglishStopsTheSearch() {
        Locale traditional = chinese("Hant", "CN");
        Locale simplified = chinese("Hans", "TW");
        assertEquals(table("zh-rtw").get(KEY), L10n.t(in(Locale.FRANCE, traditional, simplified), KEY));
        assertEquals(table("zh-rcn").get(KEY), L10n.t(in(Locale.FRANCE, simplified, traditional), KEY));
        assertEquals(table("zh-rtw").get(KEY), L10n.t(in(Locale.JAPAN, Locale.TAIWAN), KEY));
        assertEquals(table("de").get(KEY), L10n.t(in(Locale.FRANCE, Locale.GERMANY, traditional), KEY));
        assertEquals(KEY, L10n.t(in(Locale.US, traditional), KEY));
        assertEquals(KEY, L10n.t(in(Locale.FRANCE, Locale.US, traditional), KEY));
        assertEquals(table("zh-rtw").get(KEY), L10n.t(in(Locale.FRANCE, traditional, Locale.US), KEY));
    }

    @Test
    public void anUnrecognisedChineseScriptOrRegionTriesTheNextLanguage() {
        for (Locale unsupported : new Locale[]{chinese("Latn", "CN"), chinese("Latn", "TW"),
                new Locale("zh", "US")}) {
            assertEquals(unsupported.toLanguageTag(), KEY, L10n.t(in(unsupported), KEY));
            assertEquals(unsupported.toLanguageTag(), table("de").get(KEY),
                    L10n.t(in(unsupported, Locale.GERMANY), KEY));
        }
    }

    @Test
    public void theSelectedChineseTableSuppliesTheTextLocaleAndPluralRule() {
        String one = "Set when you patched, so Pause can't turn it off:\n%1$s\nTo rule it out, patch again and "
                + "leave out its patch.";
        String other = "Set when you patched, so Pause can't turn these off:\n%1$s\nTo rule one out, patch again "
                + "and leave out its patch.";
        Context traditional = in(Locale.FRANCE, chinese("Hant", "CN"));
        assertEquals(Locale.FRANCE, L10n.locale(traditional));
        assertEquals(Locale.TAIWAN, L10n.textLocale(traditional));
        assertEquals("other", L10n.pluralCategory(traditional, 1));
        assertEquals(String.format(Locale.FRANCE, table("zh-rtw").get(other), "x"),
                L10n.quantity(traditional, 1, one, other, "x"));
        Context simplified = in(Locale.FRANCE, chinese("Hans", "TW"));
        assertEquals(Locale.CHINA, L10n.textLocale(simplified));
        assertEquals("other", L10n.pluralCategory(simplified, 1));
        assertEquals(String.format(Locale.FRANCE, table("zh-rcn").get(other), "x"),
                L10n.quantity(simplified, 1, one, other, "x"));
        Context englishFirst = in(Locale.US, Locale.TAIWAN);
        assertEquals(Locale.ENGLISH, L10n.textLocale(englishFirst));
        assertEquals("one", L10n.pluralCategory(englishFirst, 1));
    }

    @Test
    public void indonesianAnswersToBothOfItsCodes() {
        // Android reports the old code "in", a desktop JVM the new "id".
        String indonesian = table("in").get(KEY);
        assertEquals(indonesian, L10nTranslations.of("id").get(KEY));
        assertEquals(indonesian, L10n.t(in(new Locale("in", "ID")), KEY));
        assertEquals(indonesian, L10n.t(in(Locale.forLanguageTag("id-ID")), KEY));
    }

    @Test
    public void aSecondLanguageAnswersWhenTheFirstHasNoTable() {
        // Android reads string resources down the whole list, and so does the catalog.
        assertEquals(table("de").get(KEY), L10n.t(in(Locale.FRANCE, Locale.GERMANY), KEY));
        assertEquals("the first language with a table wins", table("es").get(KEY),
                L10n.t(in(Locale.JAPAN, new Locale("es", "ES"), Locale.GERMANY), KEY));
        assertEquals(Arrays.asList("fr-rfr", "fr", "de-rde", "de"), L10n.tags(in(Locale.FRANCE, Locale.GERMANY)));
    }

    /**
     * English is the language the keys are written in, so it answers wherever the phone lists it.
     * An English-first phone with German second read every string in German, which Android's own
     * lookup never gives it.
     */
    @Test
    public void englishAnswersWhereThePhoneListsIt() {
        assertEquals(KEY, L10n.t(in(Locale.US, Locale.GERMANY), KEY));
        assertEquals(KEY, L10n.t(in(Locale.FRANCE, Locale.US, Locale.GERMANY), KEY));
        assertEquals("the English list word, not German's", "a, b, and c",
                L10n.join(in(Locale.US, Locale.GERMANY), Arrays.asList("a", "b", "c")));
        assertEquals(table("de").get(KEY), L10n.t(in(Locale.FRANCE, Locale.GERMANY, Locale.US), KEY));
    }

    /** Portugal's Portuguese first and Brazil's second: the Brazilian table answers. */
    @Test
    public void aSecondRegionOfTheSameLanguageIsStillTried() {
        assertEquals(table("pt-rbr").get(KEY), L10n.t(in(new Locale("pt", "PT"), new Locale("pt", "BR")), KEY));
        assertEquals(Arrays.asList("pt-rpt", "pt", "pt-rbr"),
                L10n.tags(in(new Locale("pt", "PT"), new Locale("pt", "BR"))));
    }

    /**
     * With no table for the phone's first language, the second one's text comes out, and so do
     * its list word and its plural rule. Tested only where the two languages were the same, a
     * rule read from the phone's first language passed.
     */
    @Test
    public void aSecondLanguagesTextTakesItsOwnListWordAndPluralRule() {
        assertEquals("a, b und c", L10n.join(in(Locale.FRANCE, Locale.GERMANY), Arrays.asList("a", "b", "c")));
        Context indonesianSecond = in(Locale.FRANCE, new Locale("in", "ID"));
        // French calls 1 "one"; Indonesian has no such form.
        assertEquals("other", L10n.pluralCategory(indonesianSecond, 1));
        assertEquals("one", L10n.pluralCategory(in(Locale.FRANCE, Locale.US), 1));
    }

    @Test
    public void aSentenceStartIsRaisedPastAQuoteOrAnIsolateMark() {
        char quote = (char) 0x201E;
        assertEquals(quote + "Speichern", L10n.capitalize(in(Locale.GERMANY), quote + "speichern"));
        char isolate = (char) 0x2068;
        assertEquals(isolate + "Photo", L10n.capitalize(in(Locale.US), isolate + "photo"));
        assertEquals("3 Things", L10n.capitalize(in(Locale.US), "3 things"));
        assertEquals("...", L10n.capitalize(in(Locale.US), "..."));
    }

    /** A format's numbers come out the phone's way even when its sentence is English. */
    @Test
    public void aFormatWritesItsNumbersThePhonesWay() {
        assertEquals("4,2 MB", L10n.f(in(Locale.FRANCE), "%1$.1f MB", 4.2));
        assertEquals("4.2 MB", L10n.f(in(Locale.US), "%1$.1f MB", 4.2));
    }

    @Test
    public void englishAndEveryLanguageWithNoTableReadTheEnglish() {
        assertEquals(KEY, L10n.t(in(Locale.US), KEY));
        assertEquals(KEY, L10n.t(in(Locale.FRANCE), KEY));
        assertEquals(KEY, L10n.t(in(Locale.JAPAN, Locale.FRANCE), KEY));
        // A string nobody translated is its own English, never an empty label.
        assertEquals("Not a key of any table", L10n.t(in(Locale.GERMANY), "Not a key of any table"));
        assertEquals("", L10n.t(in(Locale.GERMANY), ""));
        assertNull(L10n.t(in(Locale.GERMANY), (String) null));
    }

    @Test
    public void withNoContextTheDefaultLocaleAnswers() {
        SettingsContextRule.withoutContext(() -> assertEquals(KEY, L10n.t(KEY)));
    }

    @Test
    public void aFormatFillsTheTranslationAndFallsBackToTheEnglishWhenItCant() {
        String german = table("de").get("Saved to %1$s");
        assertNotNull(german);
        assertEquals(String.format(german, "Movies/Facebook"), L10n.f(in(Locale.GERMANY), "Saved to %1$s", "Movies/Facebook"));
        // Numbers are written the phone's way even in English: a French phone reads 4,2.
        assertEquals("4,2 MB of 9 MB", L10n.f(in(Locale.FRANCE), "%1$s of %2$s",
                String.format(L10n.locale(in(Locale.FRANCE)), "%.1f MB", 4.2), "9 MB"));
    }

    @Test
    public void aListIsJoinedInTheLanguageTheTextCameOutIn() {
        List<String> three = Arrays.asList("a", "b", "c");
        assertEquals("a", L10n.join(in(Locale.US), Arrays.asList("a")));
        assertEquals("a and b", L10n.join(in(Locale.US), Arrays.asList("a", "b")));
        assertEquals("a, b, and c", L10n.join(in(Locale.US), three));
        assertEquals("a, b und c", L10n.join(in(Locale.GERMANY), three));
        assertEquals("a, b y c", L10n.join(in(new Locale("es", "ES")), three));
        assertEquals("a, b ve c", L10n.join(in(new Locale("tr", "TR")), three));
        assertEquals("a, b e c", L10n.join(in(new Locale("pt", "BR")), three));
        // Android calls Indonesian "in", which ICU knows as "id".
        String indonesian = L10n.join(in(new Locale("in", "ID")), three);
        assertTrue(indonesian, indonesian.contains(" dan c"));
        // A French phone has no French table, so the sentence is English and so is its "and".
        assertEquals("a, b, and c", L10n.join(in(Locale.FRANCE), three));
    }

    @Test
    public void aSentenceStartIsRaisedTheWayTheLanguageDoesIt() {
        assertEquals("Indir", L10n.capitalize(in(Locale.US), "indir"));
        // Turkish raises i to a dotted capital.
        assertEquals((char) 0x0130 + "ndir", L10n.capitalize(in(new Locale("tr", "TR")), "indir"));
        assertEquals("", L10n.capitalize(in(Locale.US), ""));
    }

    @Test
    public void russianCountsUseOneFewAndManyForms() {
        Context russian = in(new Locale("ru", "RU"));
        String one = "%1$d switch will change.";
        String other = "%1$d switches will change.";
        assertEquals("Будет изменен 1 переключатель.", L10n.quantity(russian, 1, one, other, 1));
        assertEquals("Будут изменены 2 переключателя.", L10n.quantity(russian, 2, one, other, 2));
        assertEquals("Будет изменено 5 переключателей.", L10n.quantity(russian, 5, one, other, 5));
        assertEquals("Будет изменен 21 переключатель.", L10n.quantity(russian, 21, one, other, 21));
    }

    @Test
    public void aCountTakesTheFormItsLanguageGivesIt() {
        String one = "Set when you patched, so Pause can't turn it off:\n%1$s\nTo rule it out, patch again and "
                + "leave out its patch.";
        String other = "Set when you patched, so Pause can't turn these off:\n%1$s\nTo rule one out, patch again "
                + "and leave out its patch.";
        assertEquals(String.format(one, "x"), L10n.quantity(in(Locale.US), 1, one, other, "x"));
        assertEquals(String.format(other, "x"), L10n.quantity(in(Locale.US), 2, one, other, "x"));
        // Indonesian has no singular form, so even one item takes the other row.
        Context indonesian = in(new Locale("in", "ID"));
        assertEquals("other", L10n.pluralCategory(indonesian, 1));
        assertEquals(String.format(table("in").get(other), "x"), L10n.quantity(indonesian, 1, one, other, "x"));
        // The form follows the language the text is in: French calls 0 "one", English doesn't, and
        // a French phone reads English here.
        assertEquals("other", L10n.pluralCategory(in(Locale.FRANCE), 0));
        assertEquals(String.format(other, "x"), L10n.quantity(in(Locale.FRANCE), 0, one, other, "x"));
        // A language with a form English lacks keeps it as a |category row of the other form.
        Map<String, String> polish = new java.util.HashMap<>();
        polish.put(other, "o");
        polish.put(other + "|few", "f");
        assertEquals("f", L10n.pluralRow("few", one, other, polish));
        assertEquals("o", L10n.pluralRow("many", one, other, polish));
        assertEquals(one, L10n.pluralRow("one", one, other, polish));
    }

    @Test
    public void enXaDrawsEveryStringAccentedLongerAndBracketed() {
        Context accented = in(new Locale("en", "XA"));
        String shown = L10n.t(accented, KEY);
        assertTrue(shown, shown.startsWith("[") && shown.endsWith("]"));
        assertTrue("en-XA isn't longer: " + shown, shown.length() > KEY.length() * 4 / 3);
        assertFalse("en-XA left the letters plain: " + shown, shown.contains("sponsored"));
        // Placeholders come through whole, so the values put in them read as they are.
        String formatted = L10n.f(accented, "Saved to %1$s", "Movies/Facebook");
        assertTrue(formatted, formatted.contains("Movies/Facebook"));
        assertEquals(L10n.pseudolocalize(L10n.ACCENTED, "%1$s of %2$s"), L10n.t(accented, "%1$s of %2$s"));
        assertTrue(L10n.t(accented, "%1$s of %2$s").startsWith("[%1$s "));
        // Second in the list, a pseudo-locale is never reached.
        assertEquals(table("de").get(KEY), L10n.t(in(Locale.GERMANY, new Locale("en", "XA")), KEY));
    }

    @Test
    public void arXbDrawsEveryWordMirroredAndLeavesTheValuesAlone() {
        Context mirrored = in(new Locale("ar", "XB"));
        String shown = L10n.t(mirrored, KEY);
        char override = (char) 0x202E;
        char mark = (char) 0x200F;
        assertEquals("one override per word: " + shown, 3, count(shown, override));
        assertEquals("it has to open with a right-to-left mark, or the line runs left to right", mark, shown.charAt(0));
        String formatted = L10n.f(mirrored, "Saved to %1$s", "Movies/Facebook");
        assertTrue(formatted, formatted.contains("Movies/Facebook"));
        assertFalse(formatted, formatted.contains(override + "Movies"));
    }

    @Test
    public void anIsolatedValueIsWrappedInFirstStrongIsolates() {
        String isolated = L10n.isolate("584.0.0.51.56");
        assertEquals((char) 0x2068, isolated.charAt(0));
        assertEquals((char) 0x2069, isolated.charAt(isolated.length() - 1));
        assertEquals("584.0.0.51.56", isolated.substring(1, isolated.length() - 1));
        assertEquals("", L10n.isolate(""));
        assertEquals("", L10n.isolate(null));
    }

    /**
     * The catalog reads the phone's languages and never sets one: Facebook's own screens keep the
     * language Facebook chose, whatever Hushfacebook's text comes out in.
     */
    @Test
    public void readingTheCatalogLeavesFacebooksLanguageAlone() {
        Context application = RuntimeEnvironment.getApplication();
        Locale defaultBefore = Locale.getDefault();
        LocaleList appBefore = application.getResources().getConfiguration().getLocales();

        for (Locale locale : new Locale[]{Locale.GERMANY, new Locale("tr", "TR"), new Locale("en", "XA"),
                new Locale("ar", "XB"), new Locale("in", "ID"), Locale.FRANCE, Locale.TAIWAN,
                chinese("Hant", "CN"), chinese("Hans", "TW")}) {
            Context phone = in(locale);
            L10n.t(phone, KEY);
            L10n.f(phone, "Saved to %1$s", "x");
            L10n.join(phone, Arrays.asList("a", "b", "c"));
            L10n.capitalize(phone, "indir");
            L10n.quantity(phone, 2, "%1$d", "%1$d");
            assertEquals(locale, phone.getResources().getConfiguration().getLocales().get(0));
        }
        assertEquals(defaultBefore, Locale.getDefault());
        assertEquals(appBefore, application.getResources().getConfiguration().getLocales());
    }

    /**
     * Facebook sets its own language on the application, and an activity or a dialog can carry
     * another. Every lookup answers in the application's, whichever of them asks, or a Back label
     * and the title beside it come out in two languages.
     */
    @Test
    @Config(qualifiers = "de")
    public void anActivitysOwnLanguageNeverReachesTheText() {
        Context englishActivity = configured(Locale.US);
        assertEquals(Locale.US, englishActivity.getResources().getConfiguration().getLocales().get(0));
        assertEquals(table("de").get(KEY), L10n.t(englishActivity, KEY));
        assertEquals(table("de").get("Cancel"), L10n.t(englishActivity, "Cancel"));
        assertEquals("a, b und c", L10n.join(englishActivity, Arrays.asList("a", "b", "c")));
        assertEquals(Locale.GERMANY.getLanguage(), L10n.locale(englishActivity).getLanguage());

        Context germanActivity = configured(Locale.GERMANY);
        RuntimeEnvironment.setQualifiers("en-rUS");
        assertEquals(KEY, L10n.t(germanActivity, KEY));
        assertEquals("Cancel", L10n.t(germanActivity, "Cancel"));
    }

    /** The same English is looked up again after the phone's languages change. */
    @Test
    public void aChangedLanguageListIsNeverAnsweredFromTheLastOne() {
        assertEquals(table("de").get(KEY), L10n.t(in(Locale.GERMANY), KEY));
        assertEquals(table("tr").get(KEY), L10n.t(in(new Locale("tr", "TR")), KEY));
        assertEquals(table("zh-rtw").get(KEY), L10n.t(in(chinese("Hant", "CN")), KEY));
        assertEquals(table("zh-rcn").get(KEY), L10n.t(in(chinese("Hans", "TW")), KEY));
        assertEquals(KEY, L10n.t(in(Locale.US), KEY));
        assertNotEquals(table("de").get(KEY), KEY);
    }

    /** An existing caller must pick up the application's new script without retaining its last table. */
    @Test
    @SuppressWarnings("deprecation")
    public void changingTheApplicationsChineseScriptUpdatesAnExistingCaller() {
        Context caller = configured(Locale.US);
        android.content.res.Resources resources = RuntimeEnvironment.getApplication().getResources();
        Configuration before = new Configuration(resources.getConfiguration());
        Locale[] choices = {Locale.TAIWAN, Locale.CHINA, chinese("Hant", "CN"), chinese("Hans", "TW"),
                Locale.US, Locale.TAIWAN};
        String[] languages = {"zh-rtw", "zh-rcn", "zh-rtw", "zh-rcn", null, "zh-rtw"};
        try {
            for (int index = 0; index < choices.length; index++) {
                Configuration changed = new Configuration(before);
                changed.setLocales(new LocaleList(choices[index]));
                resources.updateConfiguration(changed, resources.getDisplayMetrics());
                assertEquals(choices[index].toLanguageTag(), languages[index] == null ? KEY
                        : table(languages[index]).get(KEY), L10n.t(caller, KEY));
            }
        } finally {
            resources.updateConfiguration(before, resources.getDisplayMetrics());
        }
    }

    private static Map<String, String> table(String language) {
        Map<String, String> table = L10nTranslations.of(language);
        assertNotNull(language, table);
        return table;
    }

    private static Locale locale(String tag) {
        switch (tag) {
            case "pt-rbr":
                return new Locale("pt", "BR");
            case "in":
                return new Locale("in", "ID");
            case "zh-rcn":
                return new Locale("zh", "CN");
            case "zh-rtw":
                return Locale.TAIWAN;
            default:
                return new Locale(tag);
        }
    }

    private static Locale chinese(String script, String region) {
        Locale.Builder builder = new Locale.Builder().setLanguage("zh").setScript(script);
        if (!region.isEmpty()) builder.setRegion(region);
        return builder.build();
    }

    /**
     * A context whose phone is set to these languages, in this order. L10n reads the application's
     * configuration whichever context it is given, so this one stands in as its own application.
     */
    static Context in(Locale... locales) {
        Context configured = configured(locales);
        return new ContextWrapper(configured) {
            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
    }

    /** A context in these languages that belongs to the test's application, as an activity's does. */
    private static Context configured(Locale... locales) {
        Context base = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locales));
        return base.createConfigurationContext(configuration);
    }

    private static int count(String text, char wanted) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == wanted) count++;
        }
        return count;
    }
}
