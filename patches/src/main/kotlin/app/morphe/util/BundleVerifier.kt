/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.util

import app.morphe.patcher.patch.PatchBundleSignature
import app.morphe.patcher.patch.loadPatchesFromJar
import com.google.gson.JsonParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.jar.JarFile
import java.security.MessageDigest

/** Verifies the on-disk deliverable without running any task that can repair it. */
object BundleVerifier {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) { "Expected bundle, patch list, version, build checksum and published key file" }
        val bundle = File(args[0])
        require(bundle.isFile) { "Bundle not found: $bundle" }
        JarFile(bundle).use { jar ->
            for (name in listOf("classes.dex", "extensions/facebook.mpe", "extensions/shared.mpe")) {
                val entry = requireNotNull(jar.getJarEntry(name)) { "Bundle is missing $name" }
                require(entry.size > 0) { "Bundle entry is empty: $name" }
                jar.getInputStream(entry).use { stream ->
                    val header = stream.readNBytes(100)
                    require(header.size == 100
                            && header.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) {
                        "Bundle entry is not a DEX file: $name"
                    }
                    val classDefinitions = ByteBuffer.wrap(header, 96, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int
                    require(classDefinitions > 0) { "Bundle DEX has no classes: $name" }
                }
            }
            require(jar.manifest.mainAttributes.getValue("Version") == args[2]) {
                "Bundle version does not match ${args[2]}"
            }
            if (jar.getJarEntry("app/morphe/util/BundleIdentity.class") != null ||
                jar.getJarEntry(BundleIdentity.ENTRY) != null) {
                require(BundleIdentity.fromJar(jar).startsWith("sha256=")) {
                    "Bundle identity is missing or does not match its packaged inputs"
                }
            }
        }
        val expectedDigest = File(args[3]).readText().trim()
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(bundle.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(actualDigest == expectedDigest) {
            "Bundle differs from the Android build output; a DEX or JVM payload may be stale"
        }
        val publishedKey = File(args[4]).takeIf { it.isFile }?.readText()?.trim()
        val signer = requireSignature(bundle, publishedKey)
        val metadata = File(args[1]).reader().use { JsonParser.parseReader(it).asJsonObject }
        require(metadata["version"].asString == "v${args[2]}") { "Patch list version is stale" }
        val listed = metadata.getAsJsonArray("patches").map { it.asJsonObject["name"].asString }
        val bundled = loadPatchesFromJar(setOf(bundle)).map { it.name }
        require(listed.isNotEmpty() && listed.size == listed.toSet().size) {
            "Patch list is empty or contains duplicate names"
        }
        requireCanonicalDependencies(metadata)
        require(bundled.size == listed.size && bundled.toSet() == listed.toSet()) {
            "Patch list mismatch: bundle has ${bundled.size}, metadata has ${listed.size}; " +
                "missing=${listed.toSet() - bundled.toSet()}, extra=${bundled.toSet() - listed.toSet()}"
        }
        println("Verified ${bundle.name}: ${bundled.size} patches and all three non-empty DEX payloads, " +
            (signer?.let { "signed by ${PatchBundleSignature.fingerprint(it)}" } ?: "unsigned"))
    }

    /**
     * The key that signed [bundle], or null when it carries no MorpheSig1 comment.
     *
     * Manager 2.3.0 and newer refuse a bundle whose signature doesn't match its bytes, and once the
     * source's index publishes a key (morphe-signing.pub, [publishedKey]) or Manager has pinned one,
     * any bundle that key didn't sign. A bundle signed by another key would strand everyone who
     * updates, and one signed while no key is published would get pinned with nothing in the index
     * to say so, so both are refused here. An unsigned bundle passes: the release receipt is what
     * insists on a signature once a key is published.
     */
    fun requireSignature(bundle: File, publishedKey: String?): String? {
        val signer = try {
            PatchBundleSignature.verify(bundle)
        } catch (failure: SecurityException) {
            throw IllegalArgumentException(failure.message, failure)
        }
        if (signer != null) {
            require(signer == publishedKey) {
                if (publishedKey == null) "Bundle is signed by $signer, and morphe-signing.pub publishes no key"
                else "Bundle is signed by $signer, not by morphe-signing.pub's $publishedKey"
            }
        }
        return signer
    }

    /**
     * Every row's dependencies named once and in order.
     *
     * A dependency list is a set written down as a list, so a name appearing twice is a catalog
     * that has lost track of what it is recording: two rows said `["Settings", "BytecodePatch",
     * "BytecodePatch"]`, which reads as two dependencies and is one. Order matters for a
     * different reason, that an unsorted list makes an edit to one patch reorder rows it has
     * nothing to do with.
     *
     * The generator deduplicates and sorts. This is what stops a hand-edited or stale catalog
     * getting past on a day the generator did not run, and it is separate from main so it can be
     * put in front of a crafted catalog without building a bundle to go with it.
     */
    fun requireCanonicalDependencies(metadata: com.google.gson.JsonObject) {
        val patches = metadata["patches"]
        require(patches != null && patches.isJsonArray) { "Patch list has no patches array" }
        patches.asJsonArray.forEachIndexed { index, entry ->
            require(entry.isJsonObject) { "Patch list row $index is not an object: $entry" }
            val patch = entry.asJsonObject
            // A hand-edited catalog is what this exists for, so every shape it could be in has
            // to come back as a sentence rather than as a cast failure from inside Gson.
            val name = patch["name"]?.takeIf { it.isJsonPrimitive }?.asString ?: "(unnamed)"
            val declared = patch["dependencies"] ?: return@forEachIndexed
            require(declared.isJsonArray) {
                "Patch \"$name\" declares dependencies that are not a list: $declared"
            }
            val dependencies = declared.asJsonArray.map { dependency ->
                require(dependency.isJsonPrimitive && dependency.asJsonPrimitive.isString) {
                    "Patch \"$name\" names a dependency that is not a string: $dependency"
                }
                dependency.asString
            }
            require(dependencies.size == dependencies.toSet().size) {
                "Patch \"$name\" names a dependency twice: $dependencies"
            }
            require(dependencies == dependencies.sorted()) {
                "Patch \"$name\" lists its dependencies out of order: $dependencies"
            }
        }
    }
}
