/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.emoji;

import android.content.Context;
import android.graphics.Typeface;

import androidx.annotation.Nullable;

import java.io.File;

import app.morphe.extension.facebook.font.FontFile;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * The emoji font file picked in the settings (issue #513), for {@link SystemEmoji} to hand out. The
 * settings screen copies the file into Facebook's files ({@link FontFile#copyEmoji}), and the
 * typeface is built from that copy once and kept. It keeps the system's fallback, so an emoji the
 * picked font doesn't have is drawn with the phone's.
 */
public final class OwnEmoji {
    /** What was read for one saved name. */
    private static final class Picked {
        final String source;
        @Nullable
        final Typeface typeface;

        Picked(String source, @Nullable Typeface typeface) {
            this.source = source;
            this.typeface = typeface;
        }
    }

    @Nullable
    private static volatile Picked picked;

    private OwnEmoji() {
    }

    /** The picked font, or null when none is picked or its copy can't be read. */
    @Nullable
    static Typeface typeface() {
        String source = Settings.EMOJI_FONT_SOURCE.get();
        if (source.isEmpty()) return null;
        Picked known = picked;
        if (known != null && known.source.equals(source)) return known.typeface;
        synchronized (OwnEmoji.class) {
            known = picked;
            if (known == null || !known.source.equals(source)) {
                known = new Picked(source, read());
                picked = known;
            }
            return known.typeface;
        }
    }

    @Nullable
    private static Typeface read() {
        Context context = Utils.getContext();
        File file = context == null ? null : FontFile.emojiFile(context);
        if (file == null || !file.isFile()) {
            Logger.diagnosticInfo(DiagnosticCategory.SETTINGS, "OwnEmoji", () -> "The picked emoji font's copy is missing");
            return null;
        }
        Typeface typeface = null;
        try {
            typeface = new Typeface.Builder(file).build();
        } catch (RuntimeException failure) {
            // Reported below with the null answer Android gives for the same thing.
        }
        if (typeface == null) {
            Logger.diagnosticInfo(DiagnosticCategory.SETTINGS, "OwnEmoji", () -> "Android built no typeface from the picked emoji font");
        }
        return typeface;
    }

    /** Forgets what was read. The settings screen calls this after it writes or removes the copy. */
    public static void fileChanged() {
        synchronized (OwnEmoji.class) {
            picked = null;
        }
    }
}
