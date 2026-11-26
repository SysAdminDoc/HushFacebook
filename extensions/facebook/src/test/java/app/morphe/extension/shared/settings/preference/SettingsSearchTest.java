/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The settings search's matching, over rows given as (title, summary, key) and their extra words. */
public class SettingsSearchTest {
    private static final List<SettingsSearch.Entry> ROWS = Arrays.asList(
            new SettingsSearch.Entry("hide_ads", "Hide ads", "Removes promoted posts from your news feed.", "Feed"),
            new SettingsSearch.Entry("strip", "Strip link tracking", "Removes tracking tags from copied links.", "Privacy"),
            new SettingsSearch.Entry("header", "Hide header buttons", "Hides the icon buttons at the end of the top bar.",
                    "Interface", "Toolbar icons"),
            new SettingsSearch.Entry("cafe", "Café mode", null, "Interface"),
            new SettingsSearch.Entry("bare", "Bare row", null));

    @Test
    public void everyWordHasToMatchInAnyOrderAndAnyText() {
        assertEquals(keys("strip"), keys(SettingsSearch.filter(ROWS, "tracking strip")));
        // One word in the title, one in the summary, one in the section's name.
        assertEquals(keys("hide_ads"), keys(SettingsSearch.filter(ROWS, "feed promoted ads")));
        assertEquals(keys("hide_ads", "strip"), keys(SettingsSearch.filter(ROWS, "removes")));
        assertEquals(keys(), keys(SettingsSearch.filter(ROWS, "tracking ads")));
    }

    @Test
    public void accentsCaseAndSpacesDoNotMatter() {
        assertEquals(keys("cafe"), keys(SettingsSearch.filter(ROWS, "  CAFE   Mode ")));
        assertEquals(keys("cafe"), keys(SettingsSearch.filter(ROWS, "café")));
        assertEquals("cafe", SettingsSearch.normalized("CAFÉ"));
        assertEquals(Arrays.asList("icons", "toolbar"), SettingsSearch.terms("  ÍCONS \t toolbar "));
    }

    /**
     * Plain ASCII skips the accent step (#601: each letter typed normalized every row's texts, with
     * the marks pattern compiled for each), and comes out as the accent step left it, while text
     * that isn't ASCII still loses its accents, whatever the script.
     */
    @Test
    public void plainTextComesOutAsTheAccentStepLeftIt() {
        assertEquals("hide reels in the feed (#599)!", SettingsSearch.normalized("Hide REELS in the Feed (#599)!"));
        assertEquals("", SettingsSearch.normalized(""));
        assertEquals("", SettingsSearch.normalized(null));
        assertEquals("ecole reseau", SettingsSearch.normalized("École RÉSEAU"));
        assertEquals("gizli bolum", SettingsSearch.normalized("Gizli Bölüm"));
        assertEquals("приложения", SettingsSearch.normalized("Приложения"));
        assertEquals("tieng viet", SettingsSearch.normalized("Tiếng Việt"));
        assertEquals(keys("cafe"), keys(SettingsSearch.filter(ROWS, "MODE")));
    }

    @Test
    public void extraWordsFindARowButTheKeyDoesNot() {
        assertEquals(keys("header"), keys(SettingsSearch.filter(ROWS, "toolbar")));
        assertEquals(keys(), keys(SettingsSearch.filter(ROWS, "hide_ads")));
    }

    @Test
    public void textARowDoesNotHaveAddsNothing() {
        // A row with no summary used to carry the word "null" into the search text.
        assertEquals(keys(), keys(SettingsSearch.filter(ROWS, "null")));
        assertTrue(SettingsSearch.matches("bare", "Bare row", null));
        assertFalse(SettingsSearch.matches("null", (CharSequence) null));
    }

    @Test
    public void aBlankQueryKeepsEveryRow() {
        assertEquals(ROWS.size(), SettingsSearch.filter(ROWS, "   ").size());
        assertEquals(ROWS.size(), SettingsSearch.filter(ROWS, null).size());
        assertEquals(Collections.emptyList(), SettingsSearch.terms(""));
        assertTrue(SettingsSearch.matches("", "anything"));
    }

    private static List<String> keys(String... keys) {
        return Arrays.asList(keys);
    }

    private static List<String> keys(List<SettingsSearch.Entry> entries) {
        List<String> keys = new ArrayList<>();
        for (SettingsSearch.Entry entry : entries) keys.add(entry.key);
        return keys;
    }
}
