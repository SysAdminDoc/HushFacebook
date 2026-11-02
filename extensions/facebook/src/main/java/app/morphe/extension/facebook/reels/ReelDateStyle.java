/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.facebook.ads.MarketplaceListingAge;

/** How the post date under a reel's author reads ({@link ReelDeclutter#postDateText}, #357). */
public enum ReelDateStyle {
    /** How long ago it was posted, "3 weeks ago", in Facebook's words where it has them. */
    AGO("ago"),
    /** The day it was posted, "Oct 12", with the year when it isn't this one. */
    DATE("date");

    /** What a settings file holds for this choice. It never changes once written. */
    public final String fileValue;

    ReelDateStyle(String fileValue) {
        this.fileValue = fileValue;
    }

    /** How a reel posted three weeks before [nowMillis] reads with this choice, for the settings row. */
    public String example(long nowMillis, Locale locale, TimeZone zone) {
        long threeWeeks = 21L * 24 * 60 * 60;
        if (this == DATE) return ReelDeclutter.dayPosted(nowMillis - threeWeeks * 1000, nowMillis, locale, zone);
        return MarketplaceListingAge.ago(threeWeeks, locale);
    }

    /** The choice a settings file names, or null when it names none this build knows. */
    @Nullable
    public static ReelDateStyle fromFile(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        for (ReelDateStyle style : values()) {
            if (style.fileValue.equals(value)) return style;
        }
        return null;
    }
}
