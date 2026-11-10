package hushfacebook.superpack;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites a Facebook Android 9+ APK, or the .apkm bundle that holds one, into the layout the
 * Android 11+ build uses, so the patcher can read every class in it.
 *
 * <p>The Android 9+ builds keep all but the first DEX in one Superpack store
 * (assets/secondary-program-dex-jars/store-0.dex.spo) that only Facebook's native library can
 * read, and Morphe's patcher only reads classes*.dex from the APK root, so every fingerprint
 * outside the first DEX missed. The Android 11+ build of the same version ships those DEX files in
 * the root with a {@code .root_relative} metadata.txt, and Facebook's loader takes either form.
 * This takes the DEX files superpack-unpack.ps1 decoded on a device, checks each against the
 * SHA-1 the APK's own metadata.txt lists, adds them as classes2.dex onward, writes metadata.txt
 * the Android 11+ way and drops the store. Everything else is copied as it is, except the old
 * signature files: the result is unsigned, and patching signs it.
 *
 * <p>Uncompressed entries are aligned the way zipalign aligns them (4 bytes, native libraries to
 * 16 KiB), so the APK installs once signed.
 *
 *   java StorelessApk.java &lt;input .apk or .apkm&gt; &lt;decoded dex directory&gt; &lt;output&gt;
 *
 * <p>The Hush Unpacker app (helper/) runs the same code on a phone through {@link #convert}, so
 * this file sticks to what Java 11 and Android 9 both have.
 */
public final class StorelessApk {
    private static final String DIR = "assets/secondary-program-dex-jars/";
    private static final String METADATA = DIR + "metadata.txt";
    private static final Pattern SECONDARY = Pattern.compile("secondary-(\\d+)\\.dex");
    private static final Pattern ROOT_DEX = Pattern.compile("classes(\\d*)\\.dex");
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    // Inside the DOS date range, so the writer adds no timestamp field and header sizes stay known.
    private static final long ENTRY_TIME = 1262347200000L;

    private StorelessApk() {
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("usage: StorelessApk <input .apk or .apkm> <decoded dex directory> <output>");
            System.exit(2);
        }
        File output = new File(args[2]);
        try {
            String summary = convert(new File(args[0]), new File(args[1]), output);
            System.out.println("[storeless] " + summary + " -> " + output.getName() + ", " + output.length() + " bytes");
        } catch (Refusal refusal) {
            System.err.println("[storeless] " + refusal.getMessage());
            System.exit(1);
        } catch (IOException failure) {
            System.err.println("[storeless] " + failure);
            System.exit(1);
        }
    }

    /**
     * Writes the store-less copy of {@code input} to {@code output}, from the DEX files in
     * {@code decoded}, and says what moved. Throws {@link Refusal} when the input or a DEX isn't
     * what metadata.txt says, and leaves no output behind then.
     */
    public static String convert(File input, File decoded, File output) throws IOException {
        if (!input.isFile()) fail("no input at " + input.getPath());
        if (!decoded.isDirectory()) fail("no decoded dex directory at " + decoded.getPath());
        if (output.getCanonicalFile().equals(input.getCanonicalFile())) fail("the output would overwrite the input");
        File parent = output.getAbsoluteFile().getParentFile();
        File partial = new File(parent, output.getName() + ".part");
        try {
            String summary = rewrite(input, decoded, partial, parent);
            Files.move(partial.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return summary;
        } finally {
            Files.deleteIfExists(partial.toPath());
        }
    }

    private static String rewrite(File input, File decoded, File partial, File workDir) throws IOException {
        try (ZipFile zip = new ZipFile(input)) {
            if (zip.getEntry(METADATA) != null) {
                try (AlignedZip out = new AlignedZip(partial)) {
                    return rewriteApk(zip, decoded, out);
                }
            }
            if (zip.getEntry("base.apk") == null) fail(input.getName() + " has no " + METADATA + " and no base.apk");
            File base = File.createTempFile("storeless-base", ".apk", workDir);
            try {
                String summary;
                try (ZipFile baseIn = openBase(zip, base); AlignedZip out = new AlignedZip(base(base))) {
                    summary = rewriteApk(baseIn, decoded, out);
                }
                try (AlignedZip out = new AlignedZip(partial)) {
                    for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                        ZipEntry entry = e.nextElement();
                        if (isSignatureFile(entry.getName())) continue;
                        if (entry.getName().equals("base.apk")) {
                            out.copyFile(entry, base(base));
                        } else {
                            try (InputStream in = zip.getInputStream(entry)) {
                                out.copy(entry, in);
                            }
                        }
                    }
                }
                return "base.apk: " + summary;
            } finally {
                Files.deleteIfExists(base.toPath());
                Files.deleteIfExists(base(base).toPath());
            }
        }
    }

    /** The rewritten base.apk, next to the extracted one. */
    private static File base(File extracted) {
        return new File(extracted.getPath() + ".storeless");
    }

    private static ZipFile openBase(ZipFile bundle, File into) throws IOException {
        try (InputStream in = bundle.getInputStream(bundle.getEntry("base.apk"))) {
            Files.copy(in, into.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return new ZipFile(into);
    }

    private static String rewriteApk(ZipFile apk, File decoded, AlignedZip out) throws IOException {
        Metadata metadata = Metadata.parse(new String(read(apk, METADATA), StandardCharsets.UTF_8));

        int rootDex = 0;
        for (Enumeration<? extends ZipEntry> e = apk.entries(); e.hasMoreElements(); ) {
            Matcher m = ROOT_DEX.matcher(e.nextElement().getName());
            if (m.matches()) rootDex = Math.max(rootDex, m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1)));
        }
        if (rootDex == 0) fail("the APK has no classes.dex");
        for (int i = 1; i <= rootDex; i++) {
            if (apk.getEntry(dexName(i)) == null) fail("the APK has " + dexName(rootDex) + " but no " + dexName(i));
        }
        String store = DIR + "store-0.dex." + metadata.extension;
        ZipEntry storeEntry = apk.getEntry(store);
        if (storeEntry == null) fail("metadata.txt names a store at " + store + " that the APK doesn't have");

        // Every decoded DEX is checked before anything is written.
        List<Dex> moved = new ArrayList<>();
        for (Metadata.Line line : metadata.lines) {
            File file = new File(decoded, line.name);
            if (!file.isFile()) fail("the decoded directory has no " + line.name);
            String sha1 = sha1(file);
            if (!sha1.equals(line.sha1)) fail(line.name + " has SHA-1 " + sha1 + ", metadata.txt lists " + line.sha1);
            if (!hasDexHeader(file)) fail(line.name + " has no dex header");
            moved.add(new Dex(file, dexName(rootDex + 1 + moved.size()), line));
        }

        StringBuilder rewritten = new StringBuilder(".root_relative\n");
        for (Dex dex : moved) {
            rewritten.append(dex.rootName).append(' ').append(dex.line.sha1).append(' ').append(dex.line.canary).append('\n');
        }

        String last = dexName(rootDex);
        for (Enumeration<? extends ZipEntry> e = apk.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            String name = entry.getName();
            if (name.equals(store) || isSignatureFile(name)) continue;
            if (name.equals(METADATA)) {
                out.deflated(METADATA, rewritten.toString().getBytes(StandardCharsets.UTF_8));
                continue;
            }
            try (InputStream in = apk.getInputStream(entry)) {
                out.copy(entry, in);
            }
            if (name.equals(last)) {
                // Stored like the Android 11+ build stores them, right after the DEX files already there.
                for (Dex dex : moved) out.storedFile(dex.rootName, dex.file);
            }
        }
        return moved.size() + " DEX files from " + store.substring(DIR.length()) + " (" + storeEntry.getSize()
                + " bytes) now " + moved.get(0).rootName + " to " + moved.get(moved.size() - 1).rootName;
    }

    private static String dexName(int index) {
        return index == 1 ? "classes.dex" : "classes" + index + ".dex";
    }

    /** A v1 signature over the old entries; the result is unsigned until patching signs it. */
    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/") || name.indexOf('/', 9) >= 0) return false;
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            return bytes.toByteArray();
        }
    }

    private static String sha1(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[1 << 20];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest.digest()) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean hasDexHeader(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[4];
            int got = 0;
            for (int n; got < 4 && (n = in.read(head, got, 4 - got)) > 0; ) got += n;
            return got == 4 && head[0] == 'd' && head[1] == 'e' && head[2] == 'x' && head[3] == '\n';
        }
    }

    private static void fail(String why) {
        throw new Refusal(why);
    }

    /** The input or a decoded DEX isn't what this rewrite expects; the message says what. */
    public static final class Refusal extends RuntimeException {
        Refusal(String message) {
            super(message);
        }
    }

    private static final class Dex {
        final File file;
        final String rootName;
        final Metadata.Line line;

        Dex(File file, String rootName, Metadata.Line line) {
            this.file = file;
            this.rootName = rootName;
            this.line = line;
        }
    }

    /** The Android 9+ metadata.txt: two dot-lines saying how the store is packed, then one line per DEX. */
    private static final class Metadata {
        static final class Line {
            final String name;
            final String sha1;
            final String canary;

            Line(String name, String sha1, String canary) {
                this.name = name;
                this.sha1 = sha1;
                this.canary = canary;
            }
        }

        final List<Line> lines = new ArrayList<>();
        String extension;

        static Metadata parse(String text) {
            Metadata metadata = new Metadata();
            int stores = -1;
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (line.equals(".root_relative")) fail("metadata.txt already lists its DEX files in the APK root; there's no store to unpack");
                if (parts[0].equals(".superpack_files") && parts.length == 2 && parts[1].matches("\\d{1,3}")) {
                    stores = Integer.parseInt(parts[1]);
                } else if (parts[0].equals(".superpack_extension") && parts.length == 2 && parts[1].matches("[a-z0-9]{1,8}")) {
                    metadata.extension = parts[1];
                } else if (parts[0].startsWith(".")) {
                    fail("metadata.txt has a line this rewrite doesn't know: " + line);
                } else if (parts.length == 3 && SECONDARY.matcher(parts[0]).matches() && SHA1.matcher(parts[1]).matches()) {
                    int expected = metadata.lines.size() + 1;
                    if (!parts[0].equals("secondary-" + expected + ".dex")) fail("metadata.txt lists " + parts[0] + " where secondary-" + expected + ".dex belongs");
                    metadata.lines.add(new Line(parts[0], parts[1], parts[2]));
                } else {
                    fail("metadata.txt has a line this rewrite doesn't understand: " + line);
                }
            }
            if (stores != 1) fail("metadata.txt names " + (stores < 0 ? "no" : String.valueOf(stores)) + " Superpack stores; this unpacks exactly one");
            if (metadata.extension == null) fail("metadata.txt names no store extension");
            if (metadata.lines.isEmpty()) fail("metadata.txt lists no DEX files");
            return metadata;
        }
    }

    /** A zip writer that pads uncompressed entries so their data starts aligned, as zipalign does. */
    private static final class AlignedZip implements AutoCloseable {
        private final Counting counter;
        private final ZipOutputStream zip;
        private final byte[] buffer = new byte[1 << 20];

        AlignedZip(File file) throws IOException {
            counter = new Counting(new BufferedOutputStream(new FileOutputStream(file), 1 << 20));
            zip = new ZipOutputStream(counter);
        }

        void copy(ZipEntry source, InputStream in) throws IOException {
            if (source.getMethod() == ZipEntry.STORED) {
                begin(stored(source.getName(), source.getSize(), source.getCrc()));
            } else {
                ZipEntry entry = new ZipEntry(source.getName());
                entry.setTime(ENTRY_TIME);
                zip.putNextEntry(entry);
            }
            for (int n; (n = in.read(buffer)) > 0; ) zip.write(buffer, 0, n);
            zip.closeEntry();
        }

        void copyFile(ZipEntry source, File file) throws IOException {
            try (InputStream in = new FileInputStream(file)) {
                if (source.getMethod() == ZipEntry.STORED) {
                    storedFile(source.getName(), file);
                } else {
                    copy(source, in);
                }
            }
        }

        void storedFile(String name, File file) throws IOException {
            CRC32 crc = new CRC32();
            try (InputStream in = new FileInputStream(file)) {
                for (int n; (n = in.read(buffer)) > 0; ) crc.update(buffer, 0, n);
            }
            begin(stored(name, file.length(), crc.getValue()));
            try (InputStream in = new FileInputStream(file)) {
                for (int n; (n = in.read(buffer)) > 0; ) zip.write(buffer, 0, n);
            }
            zip.closeEntry();
        }

        void deflated(String name, byte[] data) throws IOException {
            ZipEntry entry = new ZipEntry(name);
            entry.setTime(ENTRY_TIME);
            zip.putNextEntry(entry);
            zip.write(data);
            zip.closeEntry();
        }

        private static ZipEntry stored(String name, long size, long crc) {
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED);
            entry.setTime(ENTRY_TIME);
            entry.setSize(size);
            entry.setCompressedSize(size);
            entry.setCrc(crc);
            return entry;
        }

        /** Opens a stored entry with an alignment field (0xd935, the one zipalign and apksigner write) sized to land its data on the boundary. */
        private void begin(ZipEntry entry) throws IOException {
            int alignment = entry.getName().startsWith("lib/") && entry.getName().endsWith(".so") ? 16384 : 4;
            int nameLength = entry.getName().getBytes(StandardCharsets.UTF_8).length;
            long dataWithoutPad = counter.count + 30 + nameLength + 6;
            int pad = (int) ((alignment - dataWithoutPad % alignment) % alignment);
            ByteArrayOutputStream extra = new ByteArrayOutputStream(6 + pad);
            extra.write(0x35);
            extra.write(0xd9);
            extra.write((2 + pad) & 0xff);
            extra.write((2 + pad) >>> 8);
            extra.write(alignment & 0xff);
            extra.write(alignment >>> 8);
            extra.write(new byte[pad]);
            entry.setExtra(extra.toByteArray());
            zip.putNextEntry(entry);
            if ((counter.count) % alignment != 0) {
                throw new IOException("stored entry " + entry.getName() + " starts at " + counter.count + ", not on a " + alignment + " byte boundary");
            }
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    private static final class Counting extends FilterOutputStream {
        long count;

        Counting(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
