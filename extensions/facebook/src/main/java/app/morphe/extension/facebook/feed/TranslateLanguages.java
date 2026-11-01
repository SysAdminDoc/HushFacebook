/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The languages Translate posts offers, by the codes Google Translate takes, and how a language
 * named some other way compares with them. The phone says {@code in} and Google says {@code id} for
 * Indonesian, and Google tells the two Chinese scripts apart where the phone may only say {@code zh}.
 */
public final class TranslateLanguages {
    private static final String[] CODES = {
            "af", "sq", "am", "ar", "hy", "az", "eu", "be", "bn", "bs", "bg", "ca", "ceb", "zh-CN", "zh-TW", "hr", "cs",
            "da", "nl", "en", "eo", "et", "fil", "fi", "fr", "gl", "ka", "de", "el", "gu", "ht", "ha", "he", "hi", "hu",
            "is", "ig", "id", "ga", "it", "ja", "jv", "kn", "kk", "km", "ko", "ku", "ky", "lo", "la", "lv", "lt", "lb",
            "mk", "mg", "ms", "ml", "mt", "mi", "mr", "mn", "my", "ne", "no", "ps", "fa", "pl", "pt", "pa", "ro", "ru",
            "sm", "gd", "sr", "sn", "sd", "si", "sk", "sl", "so", "es", "su", "sw", "sv", "tg", "ta", "te", "th", "tr",
            "uk", "ur", "uz", "vi", "cy", "xh", "yi", "yo", "zu",
    };

    private TranslateLanguages() {
    }

    /** Every code, sorted by the name it shows under in the phone's language. */
    public static List<String> codes() {
        List<String> codes = new ArrayList<>(Arrays.asList(CODES));
        Collator order = Collator.getInstance();
        Collections.sort(codes, (one, other) -> order.compare(name(one), name(other)));
        return codes;
    }

    /** The language's name in the phone's language. */
    public static String name(String code) {
        if (code.equals("zh-CN")) return Locale.forLanguageTag("zh-Hans").getDisplayName();
        if (code.equals("zh-TW")) return Locale.forLanguageTag("zh-Hant").getDisplayName();
        return Locale.forLanguageTag(code).getDisplayName();
    }

    /** The code Google Translate takes for the phone's language. */
    static String phone() {
        return normal(Locale.getDefault().toLanguageTag());
    }

    /** Whether two names mean one language, whichever way each was written. */
    static boolean same(@Nullable String one, @Nullable String other) {
        return one != null && other != null && !one.isEmpty() && !other.isEmpty() && normal(one).equals(normal(other));
    }

    /** [code] the way Google writes it: the bare language, and the script for Chinese. */
    static String normal(String code) {
        String tag = code.replace('_', '-').toLowerCase(Locale.ROOT);
        if (tag.equals("zh") || tag.startsWith("zh-")) {
            boolean traditional = tag.contains("hant") || tag.endsWith("-tw") || tag.endsWith("-hk") || tag.endsWith("-mo");
            return traditional ? "zh-TW" : "zh-CN";
        }
        int dash = tag.indexOf('-');
        String base = dash < 0 ? tag : tag.substring(0, dash);
        switch (base) {
            case "in":
                return "id";
            case "iw":
                return "he";
            case "ji":
                return "yi";
            case "jw":
                return "jv";
            case "tl":
                return "fil";
            case "nb":
            case "nn":
                return "no";
            default:
                return base;
        }
    }
}
