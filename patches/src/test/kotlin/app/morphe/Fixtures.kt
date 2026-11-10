/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe

import app.morphe.patcher.patch.SupportedAbi
import app.morphe.patches.shared.compat.AppCompatibilities
import java.io.File
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue

/**
 * The vendor Facebook builds the fixture tests read, from the folder `HUSHFACEBOOK_FIXTURE_DIR`
 * names.
 *
 * Unset, every fixture test skips and says which variable to set: the APKs are hundreds of
 * megabytes each and are not in the repository. Set, the folder has to hold what the test reads.
 * A variable pointing at a missing or empty folder is a broken setup, and skipping there would
 * read as a pass. There is no default folder: one used to be a path on the maintainer's machine,
 * which skipped quietly everywhere else and published that machine's layout.
 */
internal object Fixtures {
    const val VARIABLE = "HUSHFACEBOOK_FIXTURE_DIR"

    /** The files in the fixture folder that [accept] takes, sorted by name. */
    fun files(accept: (File) -> Boolean): List<File> {
        val configured = System.getenv(VARIABLE)
        assumeTrue(
            "$VARIABLE is not set, so the Facebook fixture tests skip. Point it at the folder " +
                "that holds the vendor Facebook APKs to run them.",
            !configured.isNullOrBlank(),
        )
        val directory = File(configured!!)
        if (!directory.isDirectory) fail("$VARIABLE names $directory, which is not a folder.")
        val found = directory.listFiles()?.filter { it.isFile && accept(it) }?.sortedBy { it.name }.orEmpty()
        if (found.isEmpty()) fail("$VARIABLE names $directory, which holds none of the Facebook files this test reads.")
        return found
    }

    /** Every Facebook APK in the fixture folder. */
    fun apks(): List<File> = files { it.extension == "apk" }

    /**
     * The ABIs the declared Facebook builds are for, spelled the way Android and APKMirror spell them:
     * arm64-v8a, armeabi-v7a, x86, x86_64. A test that has to see every ABI compares against this, so
     * declaring or dropping a build moves the check with it.
     */
    fun declaredAbis(): Set<String> =
        AppCompatibilities.facebook().single().targets.flatMap { it.versionCodes.orEmpty().keys }
            .map { if (it == SupportedAbi.X86_64) "x86_64" else it.name.lowercase().replace('_', '-') }.toSet()

    /**
     * The ABIs of the bundles a test checked, from their name suffixes, to compare against
     * [declaredAbis]. An Android 9 build ("arm64-v8a-minapi28-storeless") is another arm64-v8a bundle.
     */
    fun abisOf(suffixes: Collection<String>): Set<String> = suffixes.map { it.substringBefore("-minapi") }.toSet()
}
