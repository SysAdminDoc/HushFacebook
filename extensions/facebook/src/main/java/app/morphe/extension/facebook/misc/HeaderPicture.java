/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.util.Size;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Home header picture (#230): a picture of your own at the top of Home, the other half of Home
 * header text. The picture is shrunk when it's picked, turned the way its camera says, and kept as
 * a PNG in Facebook's own files, so building Home reads one small file and never the picker's
 * address, which the app behind it can take back or move.
 *
 * <p>A picked file has to be at most {@link #MAX_MEGABYTES} and something Android decodes as a
 * picture. The copy it replaces stays until the new one is written and takes its place in one
 * rename, and a file turned down leaves nothing behind but, at worst, a partial file the next pick
 * writes over.
 */
public final class HeaderPicture {
    /** The largest file taken. A phone camera's photo is well under it. */
    public static final int MAX_MEGABYTES = 20;
    static final long MAX_BYTES = MAX_MEGABYTES * 1024L * 1024L;

    /**
     * The copy's largest size in pixels. The header draws it about 24 dp high, under 100 pixels on
     * any phone, so this stays sharp while keeping the file Home reads small.
     */
    static final int MAX_HEIGHT = 256;
    static final int MAX_WIDTH = 1024;

    /** The copy, in Facebook's files folder. */
    static final String NAME = "hushfacebook-header-picture.png";
    /** Where a copy is written until it's whole. */
    static final String PARTIAL = NAME + ".part";

    /** Why a picked file wasn't taken. Each reaches the person as a sentence of its own. */
    public enum Refusal {
        /** The picker's file couldn't be opened or read to the end. */
        UNREADABLE,
        /** Android doesn't decode it as a picture, an empty file included. */
        NOT_A_PICTURE,
        /** Larger than {@link #MAX_MEGABYTES}. */
        TOO_LARGE,
        /** The copy couldn't be written to Facebook's files or take the old one's place, or its name couldn't be saved. */
        NOT_SAVED
    }

    /** A file turned down. Nothing was written, and the copy it would have replaced is still there. */
    public static final class Refused extends Exception {
        public final Refusal reason;

        public Refused(Refusal reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    /**
     * What else a copy taking the old one's place has to change: the setting that names the
     * picture. [save] runs once the copy is written and before it moves in, so a name that won't save
     * leaves the copy before as it was. [undo] puts the name back when the copy then can't move in.
     * Either may throw; a throw from [save] counts as a name that didn't save.
     */
    public interface Choice {
        boolean save();

        void undo();
    }

    /** The copy last read for the header, with the file's time and size when it was read. */
    private static final class Read {
        final long modified;
        final long length;
        final Bitmap picture;

        Read(long modified, long length, Bitmap picture) {
            this.modified = modified;
            this.length = length;
            this.picture = picture;
        }
    }

    @Nullable
    private static volatile Read last;

    private HeaderPicture() {
    }

    /** The copy the header draws. */
    public static File file(Context context) {
        return new File(context.getFilesDir(), NAME);
    }

    /**
     * The picture for the header, or null to keep the words or the wordmark: nothing picked, Pause,
     * and a copy that's missing or won't decode. The bitmap read last is handed back while the file
     * hasn't changed. Never throws.
     */
    @Nullable
    public static Bitmap load(@Nullable Context context) {
        try {
            if (context == null || Settings.HEADER_PICTURE.get().isEmpty()) return null;
            File copy = file(context);
            if (!copy.isFile()) {
                Logger.printInfo(() -> "Header picture: the saved copy is missing, so Home keeps its header");
                return null;
            }
            long modified = copy.lastModified();
            long length = copy.length();
            Read held = last;
            if (held != null && held.modified == modified && held.length == length) return held.picture;
            Bitmap picture = BitmapFactory.decodeFile(copy.getPath());
            if (picture == null || picture.getWidth() <= 0 || picture.getHeight() <= 0) {
                Logger.printInfo(() -> "Header picture: the saved copy won't decode, so Home keeps its header");
                return null;
            }
            last = new Read(modified, length, picture);
            return picture;
        } catch (Throwable failure) {
            Logger.printException(() -> "Header picture: could not read the saved copy", failure);
            return null;
        }
    }

    /** The copy changed or went, so the next {@link #load} reads the file again. */
    public static void forget() {
        last = null;
    }

    /**
     * Copies [input] to [target] as a PNG at most {@link #MAX_WIDTH} by {@link #MAX_HEIGHT}, and
     * closes [input]. The PNG goes to a file beside [target] first and replaces it only once it's
     * whole and [choice] has saved, in one rename that swaps the whole file or leaves [target] as it
     * was. So a refused file, one that stops halfway, a name that won't save and a copy that can't
     * move in all leave [target] and the name as they were.
     *
     * <p>Reading stops one chunk past {@link #MAX_BYTES}, so a stream that never ends is turned
     * down too.
     */
    public static void copy(@Nullable InputStream input, File target, Choice choice) throws Refused {
        if (input == null) throw new Refused(Refusal.UNREADABLE, "No stream to read");
        File partial = new File(target.getParentFile(), PARTIAL);
        boolean kept = false;
        try {
            Bitmap picture = shrink(readAll(input));
            try {
                write(picture, partial);
            } finally {
                picture.recycle();
            }
            boolean saved;
            try {
                saved = choice.save();
            } catch (RuntimeException failure) {
                saved = false;
            }
            if (!saved) throw new Refused(Refusal.NOT_SAVED, "The picture's name couldn't be saved");
            try {
                moveIn(partial, target);
            } catch (Refused refused) {
                try {
                    choice.undo();
                } catch (RuntimeException failure) {
                    // The name stays the new one, and the copy the old one. Picking again mends it.
                }
                throw refused;
            }
            kept = true;
            forget();
        } finally {
            try {
                input.close();
            } catch (IOException | RuntimeException ignored) {
                // Every byte wanted was read, or the copy was already turned down.
            }
            // A leftover that won't go is written over by the next pick.
            if (!kept) partial.delete();
        }
    }

    /**
     * The size a [width] by [height] picture is kept at: the largest that fits in
     * {@link #MAX_WIDTH} by {@link #MAX_HEIGHT} with its shape, never larger than it came, and at
     * least one pixel each way.
     */
    static int[] fit(int width, int height) {
        float scale = Math.min(1f, Math.min(MAX_WIDTH / (float) width, MAX_HEIGHT / (float) height));
        return new int[]{Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))};
    }

    /**
     * [bytes] decoded at the size {@link #fit} gives, turned the way the camera wrote it. An
     * animated picture gives its first frame.
     */
    static Bitmap shrink(byte[] bytes) throws Refused {
        Bitmap picture;
        try {
            picture = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                    (decoder, info, source) -> {
                        // A software bitmap, so it can be written out as a PNG.
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        Size size = info.getSize();
                        int[] kept = fit(size.getWidth(), size.getHeight());
                        if (kept[0] != size.getWidth() || kept[1] != size.getHeight()) {
                            decoder.setTargetSize(kept[0], kept[1]);
                        }
                    });
        } catch (IOException | RuntimeException error) {
            // The class only: a decoder's message can carry the source's details.
            throw new Refused(Refusal.NOT_A_PICTURE, error.getClass().getSimpleName());
        }
        if (picture.getWidth() <= 0 || picture.getHeight() <= 0) {
            throw new Refused(Refusal.NOT_A_PICTURE, "Decoded to nothing");
        }
        return picture;
    }

    /** Every byte of [input], at most {@link #MAX_BYTES}. An empty file isn't a picture. */
    private static byte[] readAll(InputStream input) throws Refused {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = read(input, buffer)) != -1) {
            total += count;
            if (total > MAX_BYTES) throw new Refused(Refusal.TOO_LARGE, "Larger than " + MAX_BYTES + " bytes");
            bytes.write(buffer, 0, count);
        }
        if (total == 0) throw new Refused(Refusal.NOT_A_PICTURE, "Empty file");
        return bytes.toByteArray();
    }

    private static int read(InputStream input, byte[] buffer) throws Refused {
        try {
            return input.read(buffer);
        } catch (IOException | RuntimeException error) {
            throw new Refused(Refusal.UNREADABLE, error.getClass().getSimpleName());
        }
    }

    /** Writes [picture] to [partial] as a PNG, which keeps a logo's see-through parts. */
    private static void write(Bitmap picture, File partial) throws Refused {
        try (FileOutputStream output = new FileOutputStream(partial)) {
            if (!picture.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new Refused(Refusal.NOT_SAVED, "The PNG wasn't written");
            }
        } catch (IOException error) {
            // The class only: a message can carry a path.
            throw new Refused(Refusal.NOT_SAVED, error.getClass().getSimpleName());
        }
    }

    /**
     * Puts [partial] in [target]'s place in one rename, which replaces [target] whole or leaves it
     * as it was. The old copy is never deleted first, so a rename that fails can't lose both.
     */
    private static void moveIn(File partial, File target) throws Refused {
        try {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            // The class only: a message can carry a path.
            throw new Refused(Refusal.NOT_SAVED, failure.getClass().getSimpleName());
        }
    }
}
