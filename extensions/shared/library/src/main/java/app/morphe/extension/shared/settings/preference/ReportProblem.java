/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.shared.settings.preference;

import android.os.Build;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Utils;

/**
 * The "Report a problem" row's GitHub link: a new bug report on the repository's issue form with
 * the versions, the phone and the switches that are on already filled in, and the two questions
 * left for the person to answer.
 *
 * <p>It carries only what a report needs to be read: version names, the phone's maker and model,
 * Android's version and the titles of the switches that are on. No account, serial number, phone
 * number, file path or log line goes in. The text is for the maintainer, so it stays English
 * whatever the phone's language, except for the switch titles, which are read off the screen.
 *
 * <p>GitHub fills an issue form's fields from query parameters named by each field's id, and
 * ignores a body parameter once a form is picked, so the text is split across the form's own
 * fields; {@link #body} is what a repository without a form gets instead. The whole address is held to {@link #MAX_LENGTH} characters: the list of switches is
 * shortened first, ending with how many were left out, so the questions always survive.
 */
public final class ReportProblem {
    /** The longest address handed to the browser. GitHub and browsers take more, but not much more. */
    public static final int MAX_LENGTH = 6000;

    static final String WHAT_HAPPENED = "What happened:";
    static final String WHAT_EXPECTED = "What I expected:";
    static final String SWITCHES_ON = "Switches on:";

    private ReportProblem() {
    }

    /** What the report says about this install. Built from the phone by {@link #facts}, or by hand in a test. */
    public static final class Facts {
        public final String hushName;
        public final String hushVersion;
        public final String appName;
        public final String appVersionName;
        public final long appVersionCode;
        public final String manufacturer;
        public final String model;
        public final String androidRelease;
        public final int sdk;
        public final List<String> switchesOn;

        public Facts(String hushName, String hushVersion, String appName, String appVersionName, long appVersionCode,
                     String manufacturer, String model, String androidRelease, int sdk, List<String> switchesOn) {
            this.hushName = clean(hushName);
            this.hushVersion = clean(hushVersion);
            this.appName = clean(appName);
            this.appVersionName = clean(appVersionName);
            this.appVersionCode = appVersionCode;
            this.manufacturer = clean(manufacturer);
            this.model = clean(model);
            this.androidRelease = clean(androidRelease);
            this.sdk = sdk;
            List<String> titles = new ArrayList<>();
            if (switchesOn != null) for (String title : switchesOn) if (!clean(title).isEmpty()) titles.add(clean(title));
            this.switchesOn = Collections.unmodifiableList(titles);
        }
    }

    /** Which field of the repository's issue form gets which part. A null id leaves that part out. */
    public static final class Form {
        final String repository;
        final String template;
        final String versionsField;
        final String deviceField;
        final String descriptionField;
        final String switchesField;

        /**
         * @param repository  owner/name on GitHub.
         * @param template    the issue form's file name in .github/ISSUE_TEMPLATE, or null for a
         *                    repository without one, whose report then gets the whole text as its body.
         * @param versionsField the id of the field for the versions line.
         * @param deviceField the id of the field for the phone line.
         * @param descriptionField the id of the field the two questions go in.
         * @param switchesField the id of the field for the switches that are on.
         */
        public Form(String repository, String template, String versionsField, String deviceField,
                    String descriptionField, String switchesField) {
            this.repository = repository;
            this.template = template;
            this.versionsField = versionsField;
            this.deviceField = deviceField;
            this.descriptionField = descriptionField;
            this.switchesField = switchesField;
        }
    }

    /** This install's facts, with [switchesOn] as the titles of the switches that are on. */
    public static Facts facts(String hushName, String appName, List<String> switchesOn) {
        return new Facts(hushName, Utils.getPatchesReleaseVersion(), appName, Utils.getAppVersionName(),
                Utils.getAppVersionCode(), Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT, switchesOn);
    }

    /** "Hushfacebook 0.11.1 on Facebook 586.0.0.52.53 (476217123)". */
    public static String versionsLine(Facts facts) {
        String code = facts.appVersionCode > 0 ? " (" + facts.appVersionCode + ")" : "";
        return facts.hushName + " " + orUnknown(facts.hushVersion) + " on " + facts.appName + " "
                + orUnknown(facts.appVersionName) + code;
    }

    /** "Samsung SM-S901U, Android 16 (SDK 36)". The maker is left out when the model already starts with it. */
    public static String deviceLine(Facts facts) {
        String model = facts.model;
        String maker = facts.manufacturer;
        String phone = maker.isEmpty() || model.toLowerCase(java.util.Locale.ROOT)
                .startsWith(maker.toLowerCase(java.util.Locale.ROOT)) ? model : maker + " " + model;
        if (phone.trim().isEmpty()) phone = "unknown phone";
        return phone.trim() + ", Android " + orUnknown(facts.androidRelease) + " (SDK " + facts.sdk + ")";
    }

    /** The switches line with the first [shown] titles, and how many more there are. */
    static String switchesLine(List<String> titles, int shown) {
        if (titles.isEmpty()) return SWITCHES_ON + " none";
        int kept = Math.max(0, Math.min(shown, titles.size()));
        StringBuilder line = new StringBuilder(SWITCHES_ON).append(' ');
        for (int i = 0; i < kept; i++) {
            if (i > 0) line.append(", ");
            line.append(titles.get(i));
        }
        int left = titles.size() - kept;
        if (left > 0) line.append(kept > 0 ? ", and " : "").append(left).append(" more");
        return line.toString();
    }

    /** The two questions, each on its own line with room under it. */
    public static String questions() {
        return WHAT_HAPPENED + "\n\n\n" + WHAT_EXPECTED + "\n\n";
    }

    /** Everything the report says, as one text: the versions, the phone, the switches, then the questions. */
    public static String body(Facts facts) {
        return body(facts, facts.switchesOn.size());
    }

    private static String body(Facts facts, int shown) {
        return "Versions: " + versionsLine(facts) + "\n"
                + "Phone: " + deviceLine(facts) + "\n"
                + switchesLine(facts.switchesOn, shown) + "\n\n"
                + questions();
    }

    /**
     * The form's fields and what goes in each, in the form's order, with the first [shown] switch
     * titles. A repository with no issue form gets the whole text as the issue's body.
     */
    static Map<String, String> fields(Form form, Facts facts, int shown) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (form.template == null || form.template.isEmpty()) {
            fields.put("body", body(facts, shown));
            return fields;
        }
        put(fields, form.versionsField, versionsLine(facts));
        put(fields, form.deviceField, deviceLine(facts));
        put(fields, form.descriptionField, questions());
        put(fields, form.switchesField, switchesLine(facts.switchesOn, shown));
        return fields;
    }

    /**
     * The new-issue address for [form] with [facts] filled in, at most {@link #MAX_LENGTH}
     * characters. Switch titles come off the end of the list first; a phone that still doesn't
     * fit, with names nobody has, gets each part cut short.
     */
    public static String issueUrl(Form form, Facts facts) {
        for (int shown = facts.switchesOn.size(); shown >= 0; shown--) {
            String url = url(form, fields(form, facts, shown));
            if (url.length() <= MAX_LENGTH) return url;
        }
        Map<String, String> cut = new LinkedHashMap<>();
        int share = Math.max(40, MAX_LENGTH / 3 / Math.max(1, fields(form, facts, 0).size()));
        for (Map.Entry<String, String> field : fields(form, facts, 0).entrySet()) {
            String value = field.getValue();
            cut.put(field.getKey(), value.length() > share ? value.substring(0, share) : value);
        }
        String url = url(form, cut);
        return url.length() <= MAX_LENGTH ? url : url(form, new LinkedHashMap<>());
    }

    private static String url(Form form, Map<String, String> fields) {
        StringBuilder url = new StringBuilder("https://github.com/").append(form.repository).append("/issues/new");
        char next = '?';
        if (form.template != null && !form.template.isEmpty()) {
            url.append("?template=").append(encode(form.template));
            next = '&';
        }
        for (Map.Entry<String, String> field : fields.entrySet()) {
            url.append(next).append(encode(field.getKey())).append('=').append(encode(field.getValue()));
            next = '&';
        }
        return url.toString();
    }

    /** Percent-encoded for a query, a space as %20 rather than the + a form would send. */
    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void put(Map<String, String> fields, String id, String value) {
        if (id != null && !id.isEmpty()) fields.put(id, value);
    }

    private static String orUnknown(String value) {
        return value.isEmpty() ? "unknown" : value;
    }

    /** One line, no control characters: a title or a model name can't add lines of its own to the report. */
    private static String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cc}\\p{Cf}]+", " ").replaceAll("\\s+", " ").trim();
    }
}
