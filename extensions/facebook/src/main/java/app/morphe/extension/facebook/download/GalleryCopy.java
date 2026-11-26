/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a photo or a video another app shared into the gallery, where Hushfacebook's saves go, so
 * Facebook's own pickers list it first (Share to Facebook chats, #568). The gallery entry is
 * published only once the last byte is in, like every other save ({@link MediaStoreWriter}).
 */
public final class GalleryCopy {
    private static final int BUFFER = 64 * 1024;

    private GalleryCopy() {
    }

    /**
     * Copies what [source] holds into the gallery, as a video when [mime] is one and a photo
     * otherwise, and answers the folder it went to. Blocks while it copies, so never on the main
     * thread. Nothing stays in the gallery when it fails.
     */
    @Nullable
    public static String save(Context context, Uri source, @Nullable String mime) throws IOException {
        boolean video = mime != null && mime.startsWith("video/");
        MediaStoreWriter writer = new MediaStoreWriter(context.getApplicationContext(), video);
        boolean committed = false;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("the app that shared it gave nothing to read");
            OutputStream out = writer.open(mime);
            byte[] buffer = new byte[BUFFER];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > Downloader.MAX_BYTES) throw new IOException("bigger than a save can be");
                out.write(buffer, 0, read);
            }
            out.flush();
            writer.commit();
            committed = true;
            return writer.savedLocation();
        } finally {
            if (!committed) {
                try {
                    writer.abandon();
                } catch (Throwable ignored) {
                    // Cleaning up must never replace the real failure.
                }
            }
        }
    }
}
