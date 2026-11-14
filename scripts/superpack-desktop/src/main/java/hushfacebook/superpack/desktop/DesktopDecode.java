package hushfacebook.superpack.desktop;

import hushfacebook.superpack.DecodeStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Decodes Facebook's Superpack dex store on a PC, with no phone: the APK's own arm64
 * libsuperpack-jni.so runs under an ARM64 emulator, and {@link DecodeStore} makes the same
 * metadata.txt SHA-1 checks it makes on a phone.
 *
 *   java -cp "lib/*" hushfacebook.superpack.desktop.DesktopDecode \
 *       &lt;libsuperpack-jni.so (arm64-v8a)&gt; &lt;store-0.dex.spo&gt; &lt;metadata.txt&gt; &lt;outdir&gt;
 *
 * <p>Prints what DecodeStore prints on a phone and exits the same way: 0 with a last line starting
 * VERIFIED only when every listed DEX came out right, 1 otherwise, 2 on bad arguments.
 *
 * <p>For tracking a fault down: -Dhushfacebook.superpack.backend=unicorn runs unidbg's interpreter
 * in place of the dynarmic JIT (far slower), and -Dhushfacebook.superpack.log=debug turns up
 * unidbg's logging.
 */
public final class DesktopDecode {
    private DesktopDecode() {
    }

    public static void main(String[] args) {
        // unidbg logs through slf4j-simple; only its warnings and errors are worth a line here.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getProperty("hushfacebook.superpack.log", "warn"));
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
        // unidbg's ELF reader warns about the RELR tags it skips; Relr applies them, so the warning
        // would only mislead.
        if (System.getProperty("org.slf4j.simpleLogger.log.net.fornwall.jelf") == null) {
            System.setProperty("org.slf4j.simpleLogger.log.net.fornwall.jelf", "error");
        }
        if (args.length != 4) {
            System.out.println("REFUSED usage: <libsuperpack-jni.so> <store.dex.spo> <metadata.txt> <outdir>");
            System.exit(2);
        }
        boolean verified = false;
        Path root = null;
        EmulatedSuperpack natives = null;
        try {
            root = Files.createTempDirectory("hushfacebook-unidbg-");
            natives = new EmulatedSuperpack(new File(args[0]), root.toFile(),
                    System.getProperty("hushfacebook.superpack.backend", "dynarmic"));
            verified = DecodeStore.decode(natives, new File(args[1]), new File(args[2]), new File(args[3]), System.out::println);
        } catch (DecodeStore.Refusal refusal) {
            System.out.println("REFUSED " + refusal.getMessage());
        } catch (Throwable failure) {
            System.out.println("REFUSED " + failure.getClass().getName() + ": " + failure.getMessage());
        } finally {
            try {
                if (natives != null) natives.close();
            } catch (IOException | RuntimeException ignored) {
                // The decode already said how it went; a failed teardown changes nothing.
            }
            deleteTree(root);
        }
        System.exit(verified ? 0 : 1);
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // A leftover temp folder is the worst case.
        }
    }
}
