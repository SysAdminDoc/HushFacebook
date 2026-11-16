/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Report a problem link, built from a fixed install rather than the phone running the test. */
public class ReportProblemTest {
    private static final ReportProblem.Form FORM = new ReportProblem.Form("SysAdminDoc/Hushfacebook", "bug_report.yml",
            "versions", "device", "additional_context");

    private static ReportProblem.Facts facts(List<String> switchesOn) {
        return new ReportProblem.Facts("Hushfacebook", "0.11.1", "Facebook", "584.0.0.51.56", 475817393L,
                "samsung", "SM-S901U", "16", 36, switchesOn);
    }

    @Test
    public void theBodySaysTheVersionsThePhoneAndTheSwitchesThenAsks() {
        ReportProblem.Facts facts = facts(Arrays.asList("Hide ads", "Remove tracking from shared links"));
        assertEquals("Versions: Hushfacebook 0.11.1 on Facebook 584.0.0.51.56 (475817393)\n"
                + "Phone: samsung SM-S901U, Android 16 (SDK 36)\n"
                + "Switches on: Hide ads, Remove tracking from shared links\n"
                + "\n"
                + "What happened:\n\n\n"
                + "What I expected:\n\n", ReportProblem.body(facts));
        // A model that already names its maker isn't given the maker twice.
        assertEquals("Google Pixel 8, Android 15 (SDK 35)", ReportProblem.deviceLine(new ReportProblem.Facts(
                "Hushfacebook", "0.11.1", "Facebook", "584.0.0.51.56", 1, "Google", "Google Pixel 8", "15", 35, null)));
        // Nothing known reads as unknown rather than as a blank, and no switch on says so.
        ReportProblem.Facts bare = new ReportProblem.Facts("Hushfacebook", null, "Facebook", "", 0, null, null, null, 29, null);
        assertEquals("Hushfacebook unknown on Facebook unknown", ReportProblem.versionsLine(bare));
        assertEquals("unknown phone, Android unknown (SDK 29)", ReportProblem.deviceLine(bare));
        assertTrue(ReportProblem.body(bare).contains("Switches on: none\n"));
    }

    @Test
    public void theLinkFillsTheFormsFieldsByTheirIds() throws Exception {
        String url = ReportProblem.issueUrl(FORM, facts(Arrays.asList("Hide ads", "Remove tracking from shared links")));
        assertTrue(url, url.startsWith("https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=bug_report.yml&"));
        assertFalse("a space went out as a plus, which GitHub keeps", url.contains("+"));
        Map<String, String> fields = query(url);
        assertEquals(Arrays.asList("template", "versions", "device", "additional_context"),
                new ArrayList<>(fields.keySet()));
        assertEquals("Hushfacebook 0.11.1 on Facebook 584.0.0.51.56 (475817393)", fields.get("versions"));
        assertEquals("samsung SM-S901U, Android 16 (SDK 36)", fields.get("device"));
        assertEquals("Switches on: Hide ads, Remove tracking from shared links", fields.get("additional_context"));
        // The form's own "What went wrong?" box is left empty for the person to type in (#488): filled
        // with the two questions, it read as answered and most reports came with it blank.
        assertFalse(url, url.contains("bug_description"));
        assertFalse(url, url.contains("What%20happened"));

        // A repository without a form gets the whole text as the body.
        ReportProblem.Form plain = new ReportProblem.Form("owner/app", null, null, null, null);
        Map<String, String> body = query(ReportProblem.issueUrl(plain, facts(Arrays.asList("Hide ads"))));
        assertEquals(Arrays.asList("body"), new ArrayList<>(body.keySet()));
        assertEquals(ReportProblem.body(facts(Arrays.asList("Hide ads"))), body.get("body"));
    }

    @Test
    public void aTitleOrModelCannotAddLinesOrBreakTheQuery() throws Exception {
        ReportProblem.Facts facts = new ReportProblem.Facts("Hushfacebook", "0.11.1", "Facebook", "584.0.0.51.56", 1,
                "Acme", "Phone&x=1\nSecond line", "16", 36, Arrays.asList("Hide\nads", "A+B #1", "  "));
        Map<String, String> fields = query(ReportProblem.issueUrl(FORM, facts));
        assertEquals("Acme Phone&x=1 Second line, Android 16 (SDK 36)", fields.get("device"));
        assertEquals("Switches on: Hide ads, A+B #1", fields.get("additional_context"));
        assertFalse(fields.containsKey("x"));
        assertEquals("a%20b%2Bc%26d%3D%23", ReportProblem.encode("a b+c&d=#"));
    }

    @Test
    public void aLongListOfSwitchesIsCutSoTheLinkFits() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 600; i++) many.add("A switch with a fairly long title number " + i);
        String url = ReportProblem.issueUrl(FORM, facts(many));
        assertTrue("the link is " + url.length() + " characters", url.length() <= ReportProblem.MAX_LENGTH);
        Map<String, String> fields = query(url);
        assertFalse(fields.containsKey("bug_description"));
        String switches = fields.get("additional_context");
        assertTrue(switches, switches.startsWith("Switches on: A switch with a fairly long title number 0, "));
        assertTrue(switches, switches.matches(".*, and \\d+ more"));
        int kept = switches.split(", ").length - 1;
        int left = Integer.parseInt(switches.replaceAll(".*, and (\\d+) more", "$1"));
        assertEquals(many.size(), kept + left);
        // The list is cut no further than it has to be.
        assertTrue("only " + kept + " titles fit", kept > 50);

        // A phone whose names alone run past the limit still gets a link that fits.
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 7000; i++) huge.append('m');
        ReportProblem.Facts odd = new ReportProblem.Facts("Hushfacebook", "0.11.1", "Facebook", "584.0.0.51.56", 1,
                "Acme", huge.toString(), "16", 36, many);
        String cut = ReportProblem.issueUrl(FORM, odd);
        assertTrue(cut.length() <= ReportProblem.MAX_LENGTH);
        assertTrue(query(cut).get("versions").startsWith("Hushfacebook 0.11.1"));

        // A repository without a form cuts its list the same way, and its body keeps the questions.
        ReportProblem.Form plain = new ReportProblem.Form("owner/app", null, null, null, null);
        String body = query(ReportProblem.issueUrl(plain, facts(many))).get("body");
        assertTrue(body, body.matches("(?s).*, and \\d+ more\n\nWhat happened:\n\n\nWhat I expected:\n\n"));
    }

    /** The query's parameters in order, decoded. */
    private static Map<String, String> query(String url) throws UnsupportedEncodingException {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int equals = pair.indexOf('=');
            fields.put(URLDecoder.decode(pair.substring(0, equals), "UTF-8"), URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
        }
        return fields;
    }
}
