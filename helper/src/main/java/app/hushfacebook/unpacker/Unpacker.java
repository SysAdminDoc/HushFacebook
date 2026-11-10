package app.hushfacebook.unpacker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import hushfacebook.superpack.DecodeStore;
import hushfacebook.superpack.StorelessApk;

/**
 * The steps scripts/superpack-unpack.ps1 takes over adb, run on the phone itself: find the APK
 * that holds metadata.txt (the input, or a bundle's base.apk), take the Superpack store it names
 * and the libsuperpack-jni.so for this process's CPU, decode the store with {@link DecodeStore},
 * then have {@link StorelessApk} write the store-less copy.
 */
final class Unpacker {
    private static final String DIR = "assets/secondary-program-dex-jars/";
    private static final String METADATA = DIR + "metadata.txt";
    private static final Pattern ROOT_RELATIVE = Pattern.compile("(?m)^\\.root_relative\\s*$");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^\\.superpack_extension\\s+([a-z0-9]{1,8})\\s*$");

    interface Log {
        void line(String text);
    }

    interface Stage {
        void now(int status);
    }

    /** A reason the file can't be unpacked, as a string resource and its arguments. */
    static final class Refusal extends Exception {
        final int message;
        final Object[] arguments;

        Refusal(int message, Object... arguments) {
            super(String.valueOf(message));
            this.message = message;
            this.arguments = arguments;
        }
    }

    private final File work;
    private final File libraries;
    private final File marker;
    private final String abi;
    private final Log log;
    private final Stage stage;

    /**
     * @param work      emptied by the caller, holds every intermediate file
     * @param libraries keeps each library under its SHA-1, so a second run loads the same path
     * @param marker    exists only while the library runs, so a crash inside it shows next launch
     */
    Unpacker(File work, File libraries, File marker, String abi, Log log, Stage stage) {
        this.work = work;
        this.libraries = libraries;
        this.marker = marker;
        this.abi = abi;
        this.log = log;
        this.stage = stage;
    }

    String unpack(File input, File output) throws Exception {
        File apk = apkWithMetadata(input);
        File metadata = new File(work, "metadata.txt");
        try (ZipFile zip = new ZipFile(apk)) {
            copy(zip, zip.getEntry(METADATA), metadata);
            String text = new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8);
            if (ROOT_RELATIVE.matcher(text).find()) throw new Refusal(R.string.already_unpacked);
            Matcher extension = EXTENSION.matcher(text);
            if (!extension.find()) throw new Refusal(R.string.no_store);
            ZipEntry storeEntry = zip.getEntry(DIR + "store-0.dex." + extension.group(1));
            if (storeEntry == null) throw new Refusal(R.string.no_store);
            File store = new File(work, "store.dex.spo");
            copy(zip, storeEntry, store);
            log.line("store-0.dex." + extension.group(1) + ", " + megabytes(store) + " MB");
        }
        File library = findLibrary(input, apk);
        if (library == null) throw new Refusal(R.string.no_library, abi);
        log.line(library.getName() + " for " + abi);

        stage.now(R.string.status_unpacking);
        File decoded = new File(work, "decoded");
        boolean verified;
        Files.write(marker.toPath(), abi.getBytes(StandardCharsets.UTF_8));
        try {
            verified = DecodeStore.decode(library, new File(work, "store.dex.spo"), metadata, decoded, log::line);
        } finally {
            Files.deleteIfExists(marker.toPath());
        }
        if (!verified) throw new Refusal(R.string.not_verified);
        if (!apk.equals(input)) Files.deleteIfExists(apk.toPath());
        Files.deleteIfExists(new File(work, "store.dex.spo").toPath());

        stage.now(R.string.status_writing);
        String summary = StorelessApk.convert(input, decoded, output);
        log.line(summary);
        log.line(output.getName() + ", " + megabytes(output) + " MB");
        return summary;
    }

    /** The input when it's an APK, or its base.apk when it's a bundle. */
    private File apkWithMetadata(File input) throws IOException, Refusal {
        try (ZipFile zip = new ZipFile(input)) {
            if (zip.getEntry(METADATA) != null) return input;
            ZipEntry base = zip.getEntry("base.apk");
            if (base == null) throw new Refusal(R.string.not_facebook);
            File extracted = new File(work, "base.apk");
            copy(zip, base, extracted);
            try (ZipFile baseZip = new ZipFile(extracted)) {
                if (baseZip.getEntry(METADATA) == null) throw new Refusal(R.string.not_facebook);
            }
            return extracted;
        } catch (java.util.zip.ZipException notZip) {
            throw new Refusal(R.string.not_facebook);
        }
    }

    /** The library in the input, then in a bundle's base.apk, then in its split for this CPU. */
    private File findLibrary(File input, File apk) throws IOException {
        File found = libraryIn(input);
        if (found == null && !apk.equals(input)) found = libraryIn(apk);
        if (found != null) return found;
        try (ZipFile zip = new ZipFile(input)) {
            ZipEntry split = zip.getEntry("split_config." + abi.replace('-', '_') + ".apk");
            if (split == null) return null;
            File splitApk = new File(work, "abi-split.apk");
            copy(zip, split, splitApk);
            try {
                return libraryIn(splitApk);
            } finally {
                Files.deleteIfExists(splitApk.toPath());
            }
        }
    }

    private File libraryIn(File apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("lib/" + abi + "/libsuperpack-jni.so");
            if (entry == null) return null;
            File extracted = new File(work, "libsuperpack-jni.so");
            copy(zip, entry, extracted);
            if (!libraries.isDirectory() && !libraries.mkdirs()) throw new IOException("can't make " + libraries);
            File kept = new File(libraries, "libsuperpack-jni-" + sha1(extracted).substring(0, 16) + ".so");
            if (kept.isFile()) {
                Files.delete(extracted.toPath());
            } else {
                File[] older = libraries.listFiles();
                if (older != null) for (File file : older) Files.deleteIfExists(file.toPath());
                Files.move(extracted.toPath(), kept.toPath());
            }
            // Android 14 refuses to load code from a file the app could still write to.
            if (!kept.setReadOnly()) throw new IOException("can't make " + kept.getName() + " read-only");
            return kept;
        }
    }

    private static void copy(ZipFile zip, ZipEntry entry, File target) throws IOException {
        try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1 << 20];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
    }

    private static String sha1(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest.digest()) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static long megabytes(File file) {
        return (file.length() + (1 << 19)) >> 20;
    }
}
