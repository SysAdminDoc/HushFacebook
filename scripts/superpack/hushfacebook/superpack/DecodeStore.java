package hushfacebook.superpack;

import com.facebook.superpack.SuperpackArchive;
import com.facebook.superpack.SuperpackFile;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decodes Facebook's Superpack dex store on a device, with the libsuperpack-jni.so the APK ships,
 * and checks every DEX against the APK's own metadata.txt.
 *
 * <p>Facebook's Android 9+ builds keep all but the first DEX in one compressed store
 * (assets/secondary-program-dex-jars/store-0.dex.spo) that only that native library reads. Runs
 * through app_process as the shell user, with the stubs in com.facebook.superpack giving the
 * library the class surface it registers against:
 *
 *   app_process -cp decoder.jar /system/bin hushfacebook.superpack.DecodeStore \
 *       &lt;libsuperpack-jni.so&gt; &lt;store-0.dex.spo&gt; &lt;metadata.txt&gt; &lt;outdir&gt;
 *
 * <p>Exits 0, with a last line starting VERIFIED, only when every listed DEX came out with its
 * listed SHA-1 and a dex header and nothing unlisted did. A damaged store can abort inside the
 * library, which no catch here sees, so callers treat anything without that last line as a
 * refusal.
 */
public final class DecodeStore {
    private DecodeStore() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable failure) {
            refuse(failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 4) {
            System.out.println("REFUSED usage: <libsuperpack-jni.so> <store.dex.spo> <metadata.txt> <outdir>");
            System.exit(2);
        }
        Map<String, String> want = readMetadata(new File(args[2]));
        if (want.isEmpty()) refuse("metadata lists no dex files");
        File out = new File(args[3]);
        if (!out.mkdirs() && !out.isDirectory()) refuse("can't make " + out);

        System.load(args[0]);
        long started = System.nanoTime();
        int good = 0;
        int bad = 0;
        byte[] chunk = new byte[1 << 20];
        try (InputStream in = new BufferedInputStream(new FileInputStream(args[1]), 1 << 20)) {
            long archive = SuperpackArchive.readNative(in, "spo", 0L);
            if (archive == 0) refuse("the library didn't open the store");
            try {
                while (!SuperpackArchive.isEmptyNative(archive)) {
                    long file = SuperpackArchive.nextNative(archive);
                    if (file == 0) refuse("the library returned no file before the end");
                    try {
                        String name = SuperpackFile.getNameNative(file);
                        int length = SuperpackFile.getLengthNative(file);
                        String expected = want.remove(name);
                        File target = new File(out, safeName(name) + ".part");
                        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                        try (OutputStream os = new FileOutputStream(target)) {
                            for (int at = 0; at < length; ) {
                                int count = Math.min(chunk.length, length - at);
                                SuperpackFile.readBytesNative(file, at, count, chunk, 0);
                                sha1.update(chunk, 0, count);
                                os.write(chunk, 0, count);
                                at += count;
                            }
                        }
                        String actual = hex(sha1.digest());
                        boolean dex = length >= 8 && isDex(target);
                        if (expected == null) {
                            bad++;
                            delete(target);
                            System.out.println("UNLISTED " + name + " " + length + " " + actual);
                        } else if (!expected.equals(actual) || !dex) {
                            bad++;
                            delete(target);
                            System.out.println("MISMATCH " + name + " " + length + " want " + expected + " got " + actual
                                    + (dex ? "" : " (no dex header)"));
                        } else if (!target.renameTo(new File(out, name))) {
                            bad++;
                            delete(target);
                            System.out.println("UNWRITTEN " + name);
                        } else {
                            good++;
                            System.out.println("OK " + name + " " + length + " " + actual);
                        }
                    } finally {
                        SuperpackFile.closeNative(file);
                    }
                }
            } finally {
                SuperpackArchive.closeNative(archive);
            }
        }
        for (String missing : want.keySet()) {
            bad++;
            System.out.println("MISSING " + missing);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println((bad == 0 ? "VERIFIED " : "REFUSED ") + good + " ok, " + bad + " bad, " + ms + " ms");
        System.exit(bad == 0 ? 0 : 1);
    }

    /** Name to SHA-1 for every dex line; the dot-lines say how the store is packed and are skipped. */
    private static Map<String, String> readMetadata(File metadata) throws Exception {
        Map<String, String> want = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(metadata), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(".")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 2 || !parts[1].matches("[0-9a-f]{40}")) refuse("metadata line not understood: " + line);
                if (want.put(parts[0], parts[1]) != null) refuse("metadata lists " + parts[0] + " twice");
            }
        }
        return want;
    }

    private static boolean isDex(File file) throws Exception {
        byte[] head = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(head) != 4) return false;
        }
        return head[0] == 'd' && head[1] == 'e' && head[2] == 'x' && head[3] == '\n';
    }

    /** The store names its files; one that could climb out of the output directory is refused. */
    private static String safeName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,64}") || name.startsWith(".")) {
            refuse("unsafe file name in store: " + name);
        }
        return name;
    }

    private static void delete(File file) {
        if (!file.delete() && file.exists()) System.out.println("WARN couldn't delete " + file);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static void refuse(String why) {
        System.out.println("REFUSED " + why);
        System.exit(1);
    }
}
