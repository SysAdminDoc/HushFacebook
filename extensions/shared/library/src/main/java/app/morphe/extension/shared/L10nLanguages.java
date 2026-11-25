/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.settings.BaseSettings;

/**
 * Settings language (#582): the languages Hushfacebook's own text can be picked in, whatever
 * language Facebook runs in, each named the way its own speakers write it, so the way back reads
 * in any of them. English is the language the keys are written in, and the rest are the tables
 * the bundle carries.
 */
public final class L10nLanguages {
    private L10nLanguages() {
    }

    /** Each language in its own words. Every table needs one, which L10nTest checks. */
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        NAMES.put("en", "English");
        NAMES.put("ar", "\u0627\u0644\u0639\u0631\u0628\u064a\u0629");
        NAMES.put("bg", "\u0411\u044a\u043b\u0433\u0430\u0440\u0441\u043a\u0438");
        NAMES.put("de", "Deutsch");
        NAMES.put("es", "Espa\u00f1ol");
        NAMES.put("in", "Bahasa Indonesia");
        NAMES.put("it", "Italiano");
        NAMES.put("ko", "\ud55c\uad6d\uc5b4");
        NAMES.put("pt-rbr", "Portugu\u00eas (Brasil)");
        NAMES.put("ru", "\u0420\u0443\u0441\u0441\u043a\u0438\u0439");
        NAMES.put("th", "\u0e44\u0e17\u0e22");
        NAMES.put("tr", "T\u00fcrk\u00e7e");
        NAMES.put("vi", "Ti\u1ebfng Vi\u1ec7t");
        NAMES.put("zh-rcn", "\u7b80\u4f53\u4e2d\u6587");
        NAMES.put("zh-rtw", "\u7e41\u9ad4\u4e2d\u6587");
    }

    /** What a pick may name: English and every table. */
    private static final Set<String> PICKABLE = new HashSet<>(tags());

    /** English, then every table in {@link L10nTranslations#LANGUAGES}' order. */
    public static List<String> tags() {
        List<String> tags = new ArrayList<>(L10nTranslations.LANGUAGES.length + 1);
        tags.add("en");
        tags.addAll(Arrays.asList(L10nTranslations.LANGUAGES));
        return tags;
    }

    /** [tag]'s language in its own words, or the tag itself for one with no name here. */
    public static String name(String tag) {
        String name = NAMES.get(tag);
        return name == null ? tag : name;
    }

    /**
     * The language picked under Settings language, lower case as {@link L10n#tags} writes tags, or
     * null for the languages Facebook runs in. Null too before settings may be read, so a label built
     * that early follows Facebook, and for a pick this bundle has no table for anymore.
     */
    @Nullable
    static String picked() {
        if (!Utils.settingsReady()) return null;
        try {
            String saved = BaseSettings.SETTINGS_LANGUAGE.get();
            if (saved == null || saved.isEmpty()) return null;
            String tag = saved.toLowerCase(Locale.ROOT);
            return PICKABLE.contains(tag) ? tag : null;
        } catch (Throwable unreadable) {
            // A label is never worth a crash: an unreadable pick leaves the text in Facebook's languages.
            return null;
        }
    }
}
