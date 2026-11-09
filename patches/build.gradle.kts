import app.morphe.patches.gradle.ExtensionExtension
import app.morphe.patches.gradle.ExtensionPlugin
import app.morphe.patches.gradle.PatchesExtension
import org.apache.tools.ant.DirectoryScanner
import org.apache.tools.ant.types.selectors.SelectorUtils
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.RootComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter

/** SOURCE_DATE_EPOCH, when the environment sets one. It wins over everything below. */
val sourceDateEpochFromEnvironment: Long? =
    providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.trim()?.toLongOrNull()

/**
 * What `git status --porcelain` says the working tree holds that HEAD doesn't, read once as the
 * build starts: a line for each change, none for a clean tree, and null when git couldn't say (no
 * git, no repository). Untracked files count, since a new source compiles in whether or not it's
 * committed; ignored ones don't. No optional locks, so reading the tree never refreshes the index
 * under a git command running in the same checkout.
 */
val uncommittedChanges: List<String>? = try {
    providers.exec {
        commandLine("git", "--no-optional-locks", "status", "--porcelain")
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().lines().filter { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/**
 * The moment the bundle says it was built.
 *
 * Two builds of one commit used to differ in exactly one field, the manifest's Timestamp, so a
 * third party could rebuild the bundle and never match the published hash: a checksum then
 * attests to one file rather than to the source it came from. This pins the field to
 * SOURCE_DATE_EPOCH when the environment sets one, otherwise to the commit being built,
 * otherwise to zero. Anything read from the clock would put the difference straight back.
 *
 * A tree with uncommitted changes isn't the commit, so it gets zero as well. Stamped with HEAD's
 * time whatever the tree held, a bundle built while someone else had uncommitted edits in a shared
 * checkout read as a clean build of HEAD, and once the edits were gone nothing could tell. The
 * release receipt holds the stamp to the commit it names, so it refuses that bundle now. Only the
 * tree as the build starts is seen here: scripts/build-release-receipt.ps1 also refuses a bundle
 * older than any of its sources, which is the trace an edit made and put back later leaves.
 */
val sourceDateEpoch: Long = run {
    sourceDateEpochFromEnvironment?.let { return@run it }
    // Only a tree git calls clean is the commit its time would name.
    if (uncommittedChanges?.isEmpty() != true) return@run 0L
    try {
        providers.exec {
            commandLine("git", "log", "-1", "--format=%ct")
            workingDir = rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.orNull?.trim()?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        // No git, or no repository. Zero is stable, which is the only property that matters.
        0L
    }
}

/** Actual producer inputs, including new untracked source files, rather than HEAD alone. */
val identityInputTrees = listOf(
    Triple("patches/src/main", emptyList<String>(), emptyList<String>()),
    Triple("patches/stub/src/main", emptyList<String>(), emptyList<String>()),
    Triple("extensions", listOf("**/src/main/**", "**/build.gradle.kts", "**/*.pro"), listOf("**/build/**")),
    Triple("patches", listOf("**/build.gradle.kts"), listOf("**/build/**")),
    Triple("gradle", emptyList<String>(), emptyList<String>()),
)
val identityInputFiles = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "NOTICE")
val buildIdentityInputs = files(
    identityInputTrees.map { (directory, includes, excludes) ->
        rootProject.fileTree(directory) {
            include(includes)
            exclude(excludes)
        }
    },
    rootProject.files(identityInputFiles),
)

// Match absent HEAD paths with the same patterns and Ant default exclusions as the file trees.
// Ant's path matcher needs native separators to handle a leading ** consistently on Windows.
fun isBuildIdentityInput(path: String): Boolean = path in identityInputFiles || identityInputTrees.any { (directory, includes, excludes) ->
    if (!path.startsWith("$directory/")) return@any false
    val relative = path.removePrefix("$directory/").replace('/', File.separatorChar)
    fun matches(pattern: String) = SelectorUtils.matchPath(pattern.replace('/', File.separatorChar), relative, true)
    (includes.isEmpty() || includes.any(::matches)) && excludes.none(::matches) &&
        DirectoryScanner.getDefaultExcludes().none(::matches)
}

data class BuildSourceSnapshot(val commit: String, val tree: String, val state: String, val inputs: String)

fun snapshotBuildIdentity(): BuildSourceSnapshot {
    val inputFiles = buildIdentityInputs.files.filter { it.isFile }.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("hushfacebook-source-inputs-1\n".toByteArray(Charsets.US_ASCII))
    for (file in inputFiles) {
        val name = file.relativeTo(rootDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8)
        val bytes = file.readBytes()
        digest.update(ByteBuffer.allocate(4).putInt(name.size).array())
        digest.update(name)
        digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
        digest.update(bytes)
    }
    val inputs = digest.digest().joinToString("") { "%02x".format(it) }
    try {
        val gitRoot = providers.exec {
            commandLine("git", "--no-optional-locks", "rev-parse", "--show-toplevel")
            workingDir = rootDir
        }.standardOutput.asText.get().trim()
        if (File(gitRoot).canonicalFile != rootDir.canonicalFile) {
            return BuildSourceSnapshot("unknown", "unknown", "unknown", inputs)
        }
        val source = providers.exec {
            commandLine("git", "--no-optional-locks", "rev-parse", "HEAD", "HEAD^{tree}")
            workingDir = rootDir
        }.standardOutput.asText.get().trim().lines()
        val changes = providers.exec {
            commandLine("git", "--no-optional-locks", "status", "--porcelain")
            workingDir = rootDir
        }.standardOutput.asText.get().trim()
        val blobs = providers.exec {
            commandLine("git", "--no-optional-locks", "ls-tree", "-rz", "HEAD")
            workingDir = rootDir
        }.standardOutput.asText.get().split('\u0000').filter { it.isNotEmpty() }.associate {
            it.substringAfter('\t') to it.substringBefore('\t').substringAfterLast(' ')
        }
        // Index flags can hide modified or deleted files from status. Require the same eligible
        // paths in both directions, then compare the bytes Git would store with immutable HEAD blobs.
        val headPaths = blobs.keys.filter(::isBuildIdentityInput).toSet()
        val paths = inputFiles.map { it.relativeTo(rootDir).invariantSeparatorsPath }
        // Chunk the arguments for Windows' command-line bound. Provider exec deliberately does
        // not accept a custom stdin stream, so --stdin-paths cannot be used here.
        val actualBlobs = paths.chunked(100).flatMap { chunk ->
            providers.exec {
                commandLine(listOf("git", "--no-optional-locks", "hash-object", "--") + chunk)
                workingDir = rootDir
            }.standardOutput.asText.get().trim().lines()
        }
        if (source.size == 2 && source.all { it.matches(Regex("[0-9a-f]{40}")) }) {
            val matchesHead = headPaths == paths.toSet() && actualBlobs.size == paths.size &&
                paths.zip(actualBlobs).all { (path, hash) -> blobs[path] == hash }
            return BuildSourceSnapshot(source[0], source[1], if (changes.isEmpty() && matchesHead) "clean" else "dirty", inputs)
        }
    } catch (_: Exception) {
        // Archives and unavailable Git still identify input bytes, without inventing a commit.
    }
    return BuildSourceSnapshot("unknown", "unknown", "unknown", inputs)
}

// Captured before compilation and checked again at the producer boundary. A changed input cannot
// stamp the outputs of an earlier compile as a clean build of its new bytes.
val buildSourceSnapshot = snapshotBuildIdentity()
fun snapshotInputTimes() = buildIdentityInputs.files.filter { it.isFile }.associate {
    it.relativeTo(rootDir).invariantSeparatorsPath to Files.getLastModifiedTime(it.toPath())
}
val buildIdentityInputTimes = snapshotInputTimes()

/**
 * Rewrites the bundle with the timestamp pinned, leaving everything else as it was.
 *
 * <p>Every entry is written again rather than copied compressed, because the zip API offers no
 * way to move compressed bytes across without decoding them. That makes the output differ from
 * what the plugin first wrote, which does not matter: what matters is that two runs of this
 * produce the same bytes, and they do, because nothing here reads a clock.
 */
fun pinBundleTimestamp(bundle: File, epochSeconds: Long, source: BuildSourceSnapshot) {
    val stampMillis = epochSeconds * 1000L
    val names = mutableListOf<String>()
    val contents = mutableMapOf<String, ByteArray>()
    val times = mutableMapOf<String, Long>()
    val methods = mutableMapOf<String, Int>()

    ZipFile(bundle).use { zip ->
        for (entry in zip.entries()) {
            if (entry.name in contents) throw GradleException("The bundle contains a duplicate entry.")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            names += entry.name
            contents[entry.name] = bytes
            times[entry.name] = entry.time
            methods[entry.name] = entry.method
        }
    }

    val manifestName = "META-INF/MANIFEST.MF"
    val manifest = contents[manifestName]
        ?: throw GradleException("The bundle has no $manifestName: $bundle")
    val pinned = String(manifest, Charsets.UTF_8)
        .replace(Regex("(?m)^Timestamp: [0-9]+"), "Timestamp: $stampMillis")
    if (!pinned.contains("Timestamp: $stampMillis")) {
        throw GradleException("The bundle manifest has no Timestamp line to pin: $bundle")
    }
    val metadata = Manifest(pinned.byteInputStream(Charsets.UTF_8))
    metadata.mainAttributes.putValue("Hushfacebook-Source-State", source.state)
    metadata.mainAttributes.putValue("Hushfacebook-Source-Commit", source.commit)
    metadata.mainAttributes.putValue("Hushfacebook-Source-Tree", source.tree)
    metadata.mainAttributes.putValue("Hushfacebook-Input-SHA256", source.inputs)
    contents[manifestName] = ByteArrayOutputStream().also { metadata.write(it) }.toByteArray()

    // Keep this framing in agreement with BundleIdentity.payloadSha256, which verifies the
    // actual loaded bundle at patch time. Source fields are covered, only the digest is excluded.
    val identityName = "META-INF/hushfacebook-build.identity"
    val schema = "hushfacebook-bundle-1"
    val payload = MessageDigest.getInstance("SHA-256")
    payload.update((schema + "\n").toByteArray(Charsets.US_ASCII))
    for (name in names.filterNot { it.endsWith('/') || it == identityName }.sorted()) {
        val bytes = contents.getValue(name)
        val path = name.toByteArray(Charsets.UTF_8)
        payload.update(ByteBuffer.allocate(4).putInt(path.size).array())
        payload.update(path)
        payload.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
        payload.update(bytes)
    }
    val payloadHash = payload.digest().joinToString("") { "%02x".format(it) }
    if (identityName !in names) names += identityName
    contents[identityName] = "$schema\n$payloadHash\n".toByteArray(Charsets.US_ASCII)
    times[identityName] = 0L
    methods[identityName] = ZipEntry.DEFLATED

    val rebuilt = ByteArrayOutputStream()
    ZipOutputStream(rebuilt).use { out ->
        for (name in names) {
            val bytes = contents.getValue(name)
            val entry = ZipEntry(name)
            entry.time = times.getValue(name)
            entry.method = methods.getValue(name)
            if (entry.method == ZipEntry.STORED) {
                entry.size = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
            }
            out.putNextEntry(entry)
            out.write(bytes)
            out.closeEntry()
        }
    }
    bundle.writeBytes(rebuilt.toByteArray())
}

/**
 * Signs the finished bundle the way Morphe's PatchBundleSignature.sign does, byte for byte: an
 * Ed25519 signature over "MorpheSig1" and the SHA-256 of the archive, kept in the zip comment as
 * `MorpheSig1 <public key> <signature>`. Manager 2.3.0 and newer check it against the public_key
 * the source's patches-bundle.json publishes and pin that key on the first signed install, so a
 * bundle signed by any other key would strand everyone who updates.
 *
 * Not the plugin's own signer: it signs build/libs inside buildAndroid, and pinBundleTimestamp
 * rewrites the zip afterwards without a comment. Not the patcher's class either, which isn't on
 * this script's classpath. JCA has had Ed25519 since Java 15, and the 32-byte seed behind the
 * fixed PKCS#8 header is the same key the patcher derives from it (Ed25519 is deterministic, so
 * both write the same comment).
 *
 * The key file holds the Base64 seed, the text Morphe's tooling writes to morphe-signing.key.
 * It has to sit outside the repository, and its public half has to be the key morphe-signing.pub
 * publishes, written the way the comment writes it, or nothing is signed.
 */
fun signBundle(bundle: File, keyFile: File, publishedKeyFile: File, repositoryRoot: File) {
    if (keyFile.canonicalFile.startsWith(repositoryRoot.canonicalFile)) {
        throw GradleException("The signing key $keyFile is inside the repository. Keep it outside, where git can't pick it up.")
    }
    if (!publishedKeyFile.isFile) {
        throw GradleException("A signing key is set (HUSHFACEBOOK_SIGNING_KEY_FILE or the hushfacebook.signingKeyFile " +
            "Gradle property) and ${publishedKeyFile.name} is missing, " +
            "so nothing would tell Manager which key to expect. Nothing was signed.")
    }
    val seed = runCatching { Base64.getDecoder().decode(keyFile.readText().trim()) }.getOrNull()
    if (seed == null || seed.size != 32) {
        throw GradleException("$keyFile doesn't hold a Base64 Ed25519 seed of 32 bytes. Nothing was signed.")
    }
    val published = publishedKeyFile.readText().trim()
    val publicKey = runCatching { Base64.getDecoder().decode(published) }.getOrNull()
    if (publicKey == null || publicKey.size != 32 || Base64.getEncoder().encodeToString(publicKey) != published) {
        throw GradleException("${publishedKeyFile.name} doesn't hold a Base64 Ed25519 public key of 32 bytes, " +
            "written as a MorpheSig1 comment writes it. Nothing was signed.")
    }

    val archive = bundle.readBytes()
    val end = archive.size - 22
    if (end < 0 || archive[end] != 0x50.toByte() || archive[end + 1] != 0x4b.toByte() ||
        archive[end + 2] != 5.toByte() || archive[end + 3] != 6.toByte() ||
        archive[archive.size - 2] != 0.toByte() || archive[archive.size - 1] != 0.toByte()) {
        throw GradleException("$bundle doesn't end in a zip directory record with no comment, so it can't be signed.")
    }
    val message = "MorpheSig1".toByteArray(Charsets.US_ASCII) + MessageDigest.getInstance("SHA-256").digest(archive)
    val keys = KeyFactory.getInstance("Ed25519")
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(keys.generatePrivate(PKCS8EncodedKeySpec(HexFormat.of().parseHex("302e020100300506032b657004220420") + seed)))
    signer.update(message)
    val signature = signer.sign()
    val check = Signature.getInstance("Ed25519")
    check.initVerify(keys.generatePublic(X509EncodedKeySpec(HexFormat.of().parseHex("302a300506032b6570032100") + publicKey)))
    check.update(message)
    if (!check.verify(signature)) {
        throw GradleException("The key in $keyFile isn't the one ${publishedKeyFile.name} publishes. Nothing was signed.")
    }

    val comment = "MorpheSig1 $published ${Base64.getEncoder().encodeToString(signature)}".toByteArray(Charsets.US_ASCII)
    archive[archive.size - 2] = (comment.size and 0xff).toByte()
    archive[archive.size - 1] = (comment.size shr 8).toByte()
    bundle.writeBytes(archive + comment)
}

/**
 * What the SBOM tasks below share. An object rather than functions of the script: a task class
 * that called into the script would capture it, and Gradle can't create a task of such a class.
 */
object Sbom {
    /** A component of a resolved graph, keyed the way the graph files name it. */
    class Node(val key: String, val kind: String, val group: String, val name: String, val version: String) {
        val dependsOn = sortedSetOf<String>()
    }

    /** One resolved graph as a graph file holds it: payload, root, components and artifacts. */
    class Graph(
        val payload: String,
        val root: String,
        val nodes: Map<String, Node>,
        /** Owner key to (file name, SHA-256) of each artifact the dex step or the jar is given. */
        val artifacts: Map<String, List<Pair<String, String>>>,
    )

    fun key(id: ComponentIdentifier): String = when (id) {
        is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
        is ProjectComponentIdentifier -> "project ${id.projectPath}"
        is RootComponentIdentifier -> "root"
        else -> throw GradleException("The SBOM can't name ${id.displayName}, a ${id::class.java.simpleName}.")
    }

    /**
     * Every component the graph reaches from its root, with the ones each depends on directly.
     * A constraint edge brings no component of its own, so it's not followed. A dependency that
     * didn't resolve stops the build: the SBOM can't say what it would have been.
     */
    fun walk(root: ResolvedComponentResult): Map<String, Node> {
        val nodes = sortedMapOf<String, Node>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            val key = key(component.id)
            if (nodes.containsKey(key)) continue
            val node = when (val id = component.id) {
                is ModuleComponentIdentifier -> Node(key, "module", id.group, id.module, id.version)
                is ProjectComponentIdentifier -> Node(key, "project", "", id.projectPath, "")
                is RootComponentIdentifier -> Node(key, "root", "", "root", "")
                else -> throw GradleException("The SBOM can't name ${id.displayName}, a ${id::class.java.simpleName}.")
            }
            nodes[key] = node
            for (dependency in component.dependencies) {
                if (dependency.isConstraint) continue
                if (dependency !is ResolvedDependencyResult) {
                    throw GradleException("${dependency.requested.displayName} did not resolve for $key, so the SBOM can't say what it is.")
                }
                node.dependsOn += key(dependency.selected.id)
                queue.addLast(dependency.selected)
            }
        }
        return nodes
    }

    /** The listed components a component reaches, looking through the ones left out of the SBOM. */
    fun listedDependsOn(nodes: Map<String, Node>, listed: Set<String>, from: String): Set<String> {
        val found = sortedSetOf<String>()
        val seen = mutableSetOf(from)
        val queue = ArrayDeque(nodes.getValue(from).dependsOn.toList())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            if (next in listed) found += next else queue.addAll(nodes[next]?.dependsOn ?: emptySet())
        }
        return found
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The files an artifact holds, by the path they'd have inside the bundle. */
    fun entryNames(artifact: File): Set<String> =
        if (artifact.isDirectory) {
            artifact.walkTopDown().filter { it.isFile }.map { it.relativeTo(artifact).invariantSeparatorsPath }.toSet()
        } else {
            ZipFile(artifact).use { zip -> zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet() }
        }

    /** A purl segment, percent-encoded outside the characters the purl spec leaves as they are. */
    fun purlSegment(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val plain = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '_'.code || c == '~'.code
            if (plain) append(c.toChar()) else append("%%%02X".format(c))
        }
    }

    fun purl(group: String, name: String, version: String) =
        "pkg:maven/${purlSegment(group)}/${purlSegment(name)}@${purlSegment(version)}"

    fun writeGraph(file: File, payload: String, root: String, nodes: Map<String, Node>, artifacts: List<Pair<String, File>>) {
        val lines = mutableListOf("payload\t$payload", "root\t$root")
        for (node in nodes.values) {
            lines += "node\t${node.key}\t${node.kind}\t${node.group}\t${node.name}\t${node.version}"
            for (target in node.dependsOn) lines += "edge\t${node.key}\t$target"
        }
        for ((owner, artifact) in artifacts.sortedWith(compareBy({ it.first }, { it.second.name }))) {
            lines += "artifact\t$owner\t${artifact.name}\t${sha256(artifact)}"
        }
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    fun readGraph(file: File): Graph {
        var payload: String? = null
        var root: String? = null
        val nodes = sortedMapOf<String, Node>()
        val edges = mutableListOf<Pair<String, String>>()
        val artifacts = sortedMapOf<String, MutableList<Pair<String, String>>>()
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.isEmpty()) continue
            val fields = line.split('\t')
            when (fields[0]) {
                "payload" -> payload = fields[1]
                "root" -> root = fields[1]
                "node" -> nodes[fields[1]] = Node(fields[1], fields[2], fields[3], fields[4], fields[5])
                "edge" -> edges += fields[1] to fields[2]
                "artifact" -> artifacts.getOrPut(fields[1]) { mutableListOf() } += fields[2] to fields[3]
                else -> throw GradleException("$file has a line the SBOM task doesn't read: $line")
            }
        }
        for ((from, to) in edges) nodes.getValue(from).dependsOn += to
        return Graph(payload ?: throw GradleException("$file names no payload."),
            root ?: throw GradleException("$file names no root."), nodes, artifacts)
    }

    fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch.code < 0x20 || ch.code > 0x7e -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    /** JSON in the order the maps were built, two spaces a level, ASCII only. */
    fun json(value: Any?, indent: String = ""): String {
        val inner = "$indent  "
        return when (value) {
            null -> "null"
            is String -> quote(value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$indent}") {
                inner + quote(it.key.toString()) + ": " + json(it.value, inner)
            }
            is Iterable<*> -> if (!value.iterator().hasNext()) "[]" else value.joinToString(",\n", "[\n", "\n$indent]") {
                inner + json(it, inner)
            }
            else -> throw GradleException("The SBOM can't hold a ${value::class.java.simpleName}.")
        }
    }
}

/**
 * What one extension payload is dexed from: the extension's releaseRuntimeClasspath, which is the
 * program input of the R8 step (D8 with minify off) whose output the Morphe plugin copies into the
 * bundle as its .mpe. R8 drops the classes the payload never reaches, so this is what it was given,
 * not what it kept. Registered on each extension by :patches, and resolved by a task of the
 * extension's own: Gradle refuses a :patches task reading another project's graph, with "Current
 * thread does not hold the state lock for project".
 */
abstract class PayloadGraph : DefaultTask() {
    @get:Input
    abstract val payload: Property<String>

    @get:Input
    abstract val graph: Property<ResolvedComponentResult>

    /** "<key>\t<path>" for each library artifact the dex step is given. */
    @get:Input
    abstract val artifacts: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifactFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val root = graph.get()
        Sbom.writeGraph(output.get().asFile, payload.get(), Sbom.key(root.id), Sbom.walk(root),
            artifacts.get().map { it.substringBefore('\t') to File(it.substringAfter('\t')) })
    }
}

/** Build/test provenance is separate from the libraries the bundle actually ships. */
abstract class WriteToolingReport : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val bundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val scopeManifest: RegularFileProperty

    @get:Input
    abstract val sourceCommit: Property<String>

    @get:Input
    abstract val sourceTree: Property<String>

    @get:Input
    abstract val bundleVersion: Property<String>

    @get:Input
    abstract val configuredScopes: ListProperty<String>

    /** Absent conditional scopes and their registered-task check, captured during configuration. */
    @get:Input
    abstract val absentScopes: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val graphs: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val resolved = graphs.files.map { Sbom.readGraph(it) }.sortedBy { it.payload }
        val expected = configuredScopes.get().toSortedSet()
        val actual = resolved.map { it.payload }
        if (actual.size != actual.toSet().size || actual.toSet() != expected) {
            throw GradleException("Tooling graphs do not cover the configured scopes. Missing: " +
                (expected - actual.toSet()).joinToString(", ") + "; unexpected: " +
                (actual.toSet() - expected).joinToString(", "))
        }
        val absent = absentScopes.get().associate { it.substringBefore('\t') to it.substringAfter('\t') }
        val manifestText = scopeManifest.get().asFile.readText(Charsets.UTF_8)
            .replace("\r\n", "\n").trimEnd('\n') + "\n"
        val manifestEntries = manifestText.lines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size !in 2..3 || parts[0] !in setOf("required", "conditional") ||
                    (parts[0] == "conditional") != (parts.size == 3)) {
                    throw GradleException("Invalid tooling scope declaration: $line")
                }
                if (parts[1] !in expected && (parts[0] == "required" || parts[1] !in absent)) {
                    throw GradleException("The tooling report is missing ${parts[1]}.")
                }
                linkedMapOf("id" to parts[1], "requirement" to parts[0],
                    "status" to if (parts[1] in expected) "resolved" else "notConfigured")
            }
        if (manifestEntries.map { it.getValue("id") }.toSet().size != manifestEntries.size) {
            throw GradleException("The tooling scope manifest contains a duplicate.")
        }
        val libraries = sortedMapOf<String, Sbom.Node>()
        val artifactHashes = sortedMapOf<String, MutableSet<Pair<String, String>>>()
        val carriers = sortedMapOf<String, MutableSet<String>>()
        val scopes = resolved.map { graph ->
            if (graph.root !in graph.nodes) throw GradleException("${graph.payload} has no graph root.")
            for (node in graph.nodes.values) {
                if (node.dependsOn.any { it !in graph.nodes }) {
                    throw GradleException("${graph.payload} contains an unresolved graph edge.")
                }
                if (node.kind == "module") {
                    libraries[node.key] = node
                    carriers.getOrPut(node.key) { sortedSetOf() } += graph.payload
                }
            }
            for ((owner, artifacts) in graph.artifacts) {
                if (owner !in graph.nodes) throw GradleException("${graph.payload} has an artifact with no component.")
                artifactHashes.getOrPut(owner) { mutableSetOf() }.addAll(artifacts)
            }
            linkedMapOf<String, Any>("id" to graph.payload, "status" to "resolved", "root" to graph.root,
                "nodes" to graph.nodes.keys.sorted(), "edges" to graph.nodes.values.map { node ->
                    linkedMapOf("from" to node.key, "to" to node.dependsOn.toList())
                })
        } + absent.entries.sortedBy { it.key }.map { (scope, reason) ->
            linkedMapOf("id" to scope, "status" to "notConfigured", "reason" to reason)
        }
        val components = libraries.values.map { node ->
            linkedMapOf("coordinate" to node.key, "group" to node.group, "name" to node.name,
                "version" to node.version, "purl" to Sbom.purl(node.group, node.name, node.version),
                "scopes" to carriers.getValue(node.key).toList(),
                "artifacts" to (artifactHashes[node.key] ?: emptySet()).sortedWith(compareBy({ it.first }, { it.second }))
                    .map { linkedMapOf("name" to it.first, "sha256" to it.second) })
        }
        val bundleFile = bundle.get().asFile
        val document = linkedMapOf("schemaVersion" to 1,
            "source" to linkedMapOf("commit" to sourceCommit.get(), "tree" to sourceTree.get()),
            "bundle" to linkedMapOf("file" to bundleFile.name, "version" to bundleVersion.get(),
                "sha256" to Sbom.sha256(bundleFile)),
            "scopeManifest" to linkedMapOf("file" to "gradle/tooling-scopes.txt",
                "sha256" to Sbom.sha256(manifestText.toByteArray(Charsets.UTF_8)), "entries" to manifestEntries),
            "scopes" to scopes.sortedBy { it["id"].toString() }, "components" to components)
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(Sbom.json(document) + "\n", Charsets.UTF_8)
        }
        logger.lifecycle("Tooling report: ${resolved.size} resolved scopes, ${absent.size} not configured, ${components.size} module coordinates")
    }
}

/**
 * The CycloneDX SBOM of the release bundle, written beside it in build/release: every resolved
 * component the bundle carries and the version it resolved to, with the graph between them.
 *
 * Two things go into a bundle. The Morphe plugin's jar task takes the runtime classpath's
 * artifacts less every module, by group and name, that the patcher-provided classpath holds,
 * since the patcher that loads the bundle brings those itself; buildAndroid dexes that jar. And
 * each extension's payload is dexed from its releaseRuntimeClasspath. So the patcher, smali and
 * everything they bring are left out, with compile-only libraries, test graphs and the build's
 * own plugins, and the SBOM says so in its metadata. An SBOM listing the patcher's graph would
 * hold the release to advisories in code it doesn't ship, and to versions the Manager that loads
 * it doesn't use either.
 *
 * Checked against the bundle before it's written. Every file the bundle carries has to come from
 * the patch module's own output or from a library listed here, every library listed here has to
 * have files in it, and the payloads have to be the ones the extension projects describe. A
 * plugin release that bundled differently would otherwise leave an SBOM that describes the
 * rules above and not the file. Nothing here reads the clock: the timestamp is the pinned one
 * the bundle carries, and the serial number is derived from the bundle's hash, so a rebuild of
 * the same commit writes the same bytes.
 */
abstract class WriteReleaseSbom : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val bundle: RegularFileProperty

    @get:Input
    abstract val bundleVersion: Property<String>

    @get:Input
    abstract val epochSeconds: Property<Long>

    @get:Input
    abstract val gradleVersion: Property<String>

    @get:Input
    abstract val sourceUrl: Property<String>

    @get:Input
    abstract val runtimeGraph: Property<ResolvedComponentResult>

    /** "<key>\t<path>" for each library artifact of the runtime classpath. */
    @get:Input
    abstract val runtimeArtifacts: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeArtifactFiles: ConfigurableFileCollection

    /** "<group>:<name>" of every module the patcher-provided classpath holds. */
    @get:Input
    abstract val providedModules: SetProperty<String>

    /** The patch module's own classes and resources, which the jar carries as they are. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ownOutput: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val payloadGraphs: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    private class Component(val ref: String, val type: String, val group: String?, val name: String, val version: String, val purl: String?) {
        val carriers = sortedSetOf<String>()
        val artifacts = sortedMapOf<String, String>()
        val dependsOn = sortedSetOf<String>()
        var hash: String? = null
    }

    private fun ref(node: Sbom.Node): String =
        if (node.kind == "module") Sbom.purl(node.group, node.name, node.version) else "project" + node.name

    @TaskAction
    fun write() {
        val bundleFile = bundle.get().asFile
        val bundleName = bundleFile.name
        val version = bundleVersion.get()
        val bundleHash = Sbom.sha256(bundleFile)

        val bundleFiles = sortedSetOf<String>()
        val payloadHashes = sortedMapOf<String, String>()
        ZipFile(bundleFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                bundleFiles += entry.name
                if (entry.name.startsWith("extensions/") && entry.name.endsWith(".mpe")) {
                    payloadHashes[entry.name] = Sbom.sha256(zip.getInputStream(entry).use { it.readBytes() })
                }
            }
        }

        // The jar's share, as the plugin's jar task picks it.
        val provided = providedModules.get()
        val runtimeRoot = runtimeGraph.get()
        val runtimeNodes = Sbom.walk(runtimeRoot)
        val bundled = runtimeArtifacts.get().map { it.substringBefore('\t') to File(it.substringAfter('\t')) }.filter { (key, _) ->
            val node = runtimeNodes[key]
                ?: throw GradleException("The runtime classpath has an artifact of $key, which its graph doesn't reach.")
            "${node.group}:${node.name}" !in provided
        }

        // Written by the plugin itself: the manifest by the jar task, which leaves every library's
        // own out, and classes.dex by buildAndroid. Every library jar has a manifest too, so a
        // library would otherwise count as carried on the strength of a file that isn't its.
        val generated = setOf("META-INF/MANIFEST.MF", "META-INF/hushfacebook-build.identity", "classes.dex")
        val own = ownOutput.files.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }.toSet()
        val fromLibraries = mutableSetOf<String>()
        val found = sortedMapOf<String, Int>()
        for ((key, artifact) in bundled) {
            val names = Sbom.entryNames(artifact) - generated
            fromLibraries += names
            found[key] = (found[key] ?: 0) + names.count { it in bundleFiles }
        }
        val unexplained = bundleFiles.filter { it !in generated && it !in payloadHashes && it !in own && it !in fromLibraries }
        if (unexplained.isNotEmpty()) {
            throw GradleException(
                "$bundleName carries ${unexplained.take(5).joinToString(", ")}" +
                    (if (unexplained.size > 5) " and ${unexplained.size - 5} more" else "") +
                    ", which neither the patch module's own output nor a library the SBOM lists provides. " +
                    "The Morphe plugin bundled something this SBOM would leave out."
            )
        }
        val absent = found.filterValues { it == 0 }.keys
        if (absent.isNotEmpty()) {
            throw GradleException(
                "The SBOM would list ${absent.joinToString(", ")} as carried by $bundleName, " +
                    "but the bundle holds none of their files."
            )
        }

        val graphs = payloadGraphs.files.sortedBy { it.path }.map { Sbom.readGraph(it) }
        val described = graphs.map { it.payload }.toSortedSet()
        if (described != payloadHashes.keys) {
            throw GradleException(
                "$bundleName carries the payloads ${payloadHashes.keys.joinToString(", ").ifEmpty { "(none)" }}, " +
                    "and the extension projects the Morphe plugin dexes describe " +
                    "${described.joinToString(", ").ifEmpty { "(none)" }}. The SBOM can't say what the difference is made of."
            )
        }

        val components = sortedMapOf<String, Component>()
        fun add(graphNodes: Map<String, Sbom.Node>, listed: Set<String>, carrier: String,
                hashes: (String) -> List<Pair<String, String>>) {
            for (key in listed) {
                val node = graphNodes.getValue(key)
                val ref = ref(node)
                val component = components.getOrPut(ref) {
                    if (node.kind == "module") {
                        Component(ref, "library", node.group, node.name, node.version, ref)
                    } else {
                        Component(ref, "library", null, node.name, version, null)
                    }
                }
                component.carriers += carrier
                for ((fileName, sha) in hashes(key)) component.artifacts[fileName] = sha
                for (target in Sbom.listedDependsOn(graphNodes, listed, key)) component.dependsOn += ref(graphNodes.getValue(target))
            }
        }

        val runtimeRootKey = Sbom.key(runtimeRoot.id)
        val bundledKeys = bundled.map { it.first }.toSortedSet()
        add(runtimeNodes, bundledKeys + runtimeRootKey, bundleName) { key ->
            bundled.filter { it.first == key }.map { it.second.name to Sbom.sha256(it.second) }
        }
        val bundleDependsOn = sortedSetOf(ref(runtimeNodes.getValue(runtimeRootKey)))
        for (graph in graphs) {
            val listed = graph.nodes.values.filter { it.kind == "project" || graph.artifacts.containsKey(it.key) }
                .map { it.key }.toSortedSet()
            add(graph.nodes, listed, graph.payload) { key -> graph.artifacts[key] ?: emptyList() }
            val payload = Component(graph.payload, "file", null, graph.payload, version, null)
            payload.hash = payloadHashes.getValue(graph.payload)
            payload.carriers += bundleName
            payload.dependsOn += ref(graph.nodes.getValue(graph.root))
            components[graph.payload] = payload
            bundleDependsOn += graph.payload
        }

        fun property(name: String, value: String) = linkedMapOf("name" to name, "value" to value)
        fun sha(content: String) = linkedMapOf("alg" to "SHA-256", "content" to content)
        val listedComponents = components.values.map { component ->
            val entry = linkedMapOf<String, Any?>("type" to component.type, "bom-ref" to component.ref)
            if (component.group != null) entry["group"] = component.group
            entry["name"] = component.name
            entry["version"] = component.version
            entry["scope"] = "required"
            val single = component.hash ?: component.artifacts.values.singleOrNull()
            if (single != null) entry["hashes"] = listOf(sha(single))
            if (component.purl != null) entry["purl"] = component.purl
            val properties = mutableListOf(property("hushfacebook:carried-by", component.carriers.joinToString(", ")))
            for ((fileName, content) in component.artifacts) properties += property("hushfacebook:artifact", "$fileName sha256:$content")
            if (component.purl == null && component.type == "library") {
                properties += property("hushfacebook:first-party", "built from this repository")
            }
            entry["properties"] = properties
            entry
        }
        val dependencies = listOf(linkedMapOf<String, Any?>("ref" to bundleName, "dependsOn" to bundleDependsOn.toList())) +
            components.values.map { linkedMapOf<String, Any?>("ref" to it.ref, "dependsOn" to it.dependsOn.toList()) }

        val document = linkedMapOf<String, Any?>(
            "bomFormat" to "CycloneDX",
            "specVersion" to "1.6",
            "serialNumber" to "urn:uuid:" + UUID.nameUUIDFromBytes("hushfacebook:$bundleName:$bundleHash".toByteArray(Charsets.UTF_8)),
            "version" to 1,
            "metadata" to linkedMapOf(
                "timestamp" to Instant.ofEpochSecond(epochSeconds.get()).toString(),
                "tools" to linkedMapOf("components" to listOf(
                    linkedMapOf("type" to "application", "name" to "Gradle", "version" to gradleVersion.get()))),
                "component" to linkedMapOf(
                    "type" to "file",
                    "bom-ref" to bundleName,
                    "name" to bundleName,
                    "version" to version,
                    "hashes" to listOf(sha(bundleHash)),
                    "externalReferences" to listOf(linkedMapOf("type" to "vcs", "url" to sourceUrl.get())),
                ),
                "properties" to listOf(
                    property("hushfacebook:covers",
                        "What $bundleName carries: the patch classes and the libraries the Morphe plugin bundles " +
                            "with them (:patches runtimeClasspath less every module :patches patcherProvidedClasspath " +
                            "holds), and what each extension payload is dexed from (its releaseRuntimeClasspath, " +
                            "which R8 is given as program input)."),
                    property("hushfacebook:leaves-out",
                        "The Morphe patcher, smali and everything they bring, which the Manager or CLI that loads " +
                            "the bundle supplies; compile-only libraries; test and lint graphs; and the build's own " +
                            "plugins and tools."),
                ),
            ),
            "components" to listedComponents,
            "dependencies" to dependencies,
        )
        val target = output.get().asFile
        target.parentFile.mkdirs()
        target.writeText(Sbom.json(document) + "\n", Charsets.UTF_8)
    }
}

// All configurations are now known. Each graph is resolved by a task on its owning project;
// only files reach the aggregate, so it cannot read another project's configuration at execution.
gradle.projectsEvaluated {
    val scopeFile = rootProject.file("gradle/tooling-scopes.txt")
    val required = scopeFile.readLines(Charsets.UTF_8).map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split(Regex("\\s+")) }
    val configured = mutableListOf<String>()
    val absent = mutableListOf<String>()
    val producers = mutableListOf<TaskProvider<PayloadGraph>>()

    fun record(owner: Project, scope: String, configuration: org.gradle.api.artifacts.Configuration) {
        if (!configuration.isCanBeResolved) throw GradleException("Tooling scope $scope cannot be resolved.")
        val index = producers.size
        configured += scope
        producers += owner.tasks.register<PayloadGraph>("toolingGraph$index") {
            payload.set(scope)
            graph.set(configuration.incoming.resolutionResult.rootComponent)
            val libraries = configuration.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }.artifacts
            artifacts.set(libraries.resolvedArtifacts.map { resolved ->
                resolved.map { Sbom.key(it.id.componentIdentifier) + "\t" + it.file.absolutePath }.sorted()
            })
            artifactFiles.from(libraries.artifactFiles)
            output.set(owner.layout.buildDirectory.file("tooling/graph-$index.tsv"))
        }
    }
    @Suppress("UNCHECKED_CAST")
    val settings = rootProject.extensions.extraProperties["hushSettingsTooling"] as Map<String, Any>
    configured += "settings/buildscript/classpath"
    producers += rootProject.tasks.register<PayloadGraph>("toolingSettingsGraph") {
        payload.set("settings/buildscript/classpath")
        @Suppress("UNCHECKED_CAST")
        graph.set(settings.getValue("graph") as org.gradle.api.provider.Provider<ResolvedComponentResult>)
        @Suppress("UNCHECKED_CAST")
        artifacts.set(settings.getValue("artifacts") as org.gradle.api.provider.Provider<List<String>>)
        artifactFiles.from(settings.getValue("files"))
        output.set(rootProject.layout.buildDirectory.file("tooling/settings-graph.tsv"))
    }
    for (owner in rootProject.allprojects.sortedBy { it.path }) {
        record(owner, "${owner.path}/buildscript/classpath", owner.buildscript.configurations.getByName("classpath"))
        for (configuration in owner.configurations.filter { it.isCanBeResolved }.sortedBy { it.name }) {
            record(owner, "${owner.path}/configuration/${configuration.name}", configuration)
        }
    }
    for (parts in required) {
        if (parts.size !in 2..3 || parts[0] !in setOf("required", "conditional") ||
            (parts[0] == "conditional") != (parts.size == 3)) {
            throw GradleException("Invalid tooling scope declaration: ${parts.joinToString(" ")}")
        }
        if (parts[1] in configured) continue
        if (parts[0] == "required") throw GradleException("Required tooling scope ${parts[1]} is missing.")
        val owner = rootProject.project(parts[1].substringBefore("/configuration/"))
        if (parts[2] in owner.tasks.names) {
            throw GradleException("${parts[1]} is absent, but its ${parts[2]} task is registered.")
        }
        absent += parts[1] + "\tNo registered " + parts[2] + " task or resolvable configuration"
    }
    val commit = providers.provider { buildSourceSnapshot.commit }
    val report = tasks.register<WriteToolingReport>("releaseTooling") {
        group = "build"
        description = "Records resolved settings, project build and test graphs separately from payload provenance"
        dependsOn("buildAndroid")
        bundle.set(layout.buildDirectory.file("release/patches-${project.version}.mpp"))
        bundleVersion.set(project.version.toString())
        scopeManifest.set(scopeFile)
        sourceCommit.set(commit)
        sourceTree.set(buildSourceSnapshot.state)
        configuredScopes.set(configured.sorted())
        absentScopes.set(absent.sorted())
        graphs.from(producers.map { it.flatMap { task -> task.output } })
        output.set(layout.buildDirectory.file("release/patches-${project.version}.tooling.json"))
    }
    tasks.named("buildAndroid") { finalizedBy(report) }
}

group = "app.morphe"

patches {
    about {
        name = "Hushfacebook"
        description = "Hushfacebook patches for the Facebook app, built for Morphe. Sponsored posts, Reels, Stories and suggested clutter out of your feed, and more control over what Facebook shows you."
        source = "https://github.com/SysAdminDoc/Hushfacebook"
        author = "SysAdminDoc"
        contact = "https://github.com/SysAdminDoc/Hushfacebook/issues"
        website = "https://github.com/SysAdminDoc/Hushfacebook"
        license = "GNU General Public License v3.0, with additional GPL section 7 requirements"
    }
}

// Morphe patcher asks for Bouncy Castle 1.77 and the Android build tools in this graph ask for
// 1.79, so this module's graph resolved at 1.79: gradle/libs.versions.toml lists the advisories,
// with 1.77 inside all twelve and 1.79 inside six. None of it reaches the payload, and the APK a
// user gets is
// signed by their own Manager with its own patcher, so this is the build and signing classpath
// here rather than anything shipped. The repository's rule is that a known-affected component
// does not stay in a reproducible graph either way. Every request is rewritten to the reviewed
// release the catalog pins, the same treatment :extensions:facebook gives its test graph.
val safeBouncyCastleVersion = libs.versions.bouncycastle.get()
// What the patcher and the Android build tools are known to ask for. A request for anything
// else is a version nobody has read the advisories for, and the gate below stops the build on
// it rather than rewriting it away in silence.
val reviewedBouncyCastleRequests = setOf("1.77", "1.79", safeBouncyCastleVersion)
// Guarded by hand rather than with a synchronized collection wrapper: in a Kotlin build script
// `java` is the Java extension, so the java.util package cannot be named here.
val requestedBouncyCastleVersions = sortedSetOf<String>()
// GHSA-xxph-c9ww-hj94 covers every Guava before 33.7.2. The implementation pin below only sets
// the graphs this module compiles and tests with: the patcher and smali ask for 33.3.1 and
// 33.5.0, and patcherProvidedClasspath, the graph of what the patcher brings, took those as
// asked. Every request is rewritten to the catalog's release, as Bouncy Castle's are.
val safeGuavaVersion = libs.versions.guava.get()

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.bouncycastle") {
            // Recorded before the rewrite, and recorded as a name rather than dropped when the
            // request carries no version of its own: a request arriving through a platform or
            // a constraint would otherwise be rewritten and never counted.
            // Blank as well as null: a declaration with no version reports "" rather than null,
            // and a plain ?: leaves the failure message with an empty name in it.
            val asked = requested.version?.takeIf { it.isNotBlank() } ?: "a request with no version of its own"
            synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.add(asked) }
            useVersion(safeBouncyCastleVersion)
            because("The build classpath must use the reviewed Bouncy Castle release.")
        }
        if (requested.group == "com.google.guava" && requested.name == "guava") {
            useVersion(safeGuavaVersion)
            because("GHSA-xxph-c9ww-hj94 covers every Guava before 33.7.2.")
        }
    }
}

val verifyBouncyCastleBuildGraph = tasks.register("verifyBouncyCastleBuildGraph") {
    group = "verification"
    description = "Checks this module's resolvable graphs for unreviewed Bouncy Castle requests."

    doLast {
        // Resolving is what runs the rewrite above, so the requests are collected here rather
        // than read out of a set nothing has filled yet. Checking the resolved version instead
        // would prove nothing: the rewrite guarantees that answer, so its failure branch could
        // never run. The request underneath it is the live fact.
        // Named rather than "every resolvable configuration": the ones the patcher's graph
        // actually arrives on. Resolution failures are not caught. A swallowed one is how this
        // gate first passed while reporting a single module, because dependency verification
        // was refusing the very artifacts the force had just introduced.
        val inspected = listOf("patcherProvidedClasspath", "compileClasspath", "runtimeClasspath",
            "testCompileClasspath", "testRuntimeClasspath")
        val seen = sortedMapOf<String, String>()
        val missing = mutableListOf<String>()
        for (name in inspected) {
            val configuration = configurations.findByName(name)
            if (configuration == null || !configuration.isCanBeResolved) {
                missing += name
                continue
            }
            val modules = configuration.incoming.resolutionResult.allComponents
                .mapNotNull { it.moduleVersion?.takeIf { module -> module.group == "org.bouncycastle" } }
            for (module in modules) seen[module.name] = module.version
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "These configurations were not there to inspect: " + missing.joinToString(", ") +
                    ". The plugin renamed them, so this gate is looking at the wrong graph."
            )
        }
        // Named, not merely non-empty. A module that fails to resolve is absent from
        // allComponents rather than raising, so a force at a version that does not publish a
        // module leaves this reporting the ones that did resolve and passing. That is exactly
        // what 1.85.2 did: it is a bcprov-only release, and bcpkix and bcutil read FAILED in
        // the dependency report while this gate said the graph was clean.
        val expected = setOf("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on")
        val absent = expected - seen.keys
        if (absent.isNotEmpty()) {
            throw GradleException(
                "Bouncy Castle " + absent.sorted().joinToString(", ") + " is not on the build " +
                    "graph. Either the force names a version that does not publish it, or the " +
                    "patcher stopped bringing it and this gate is now blind."
            )
        }
        val wrong = seen.filterValues { it != safeBouncyCastleVersion }
        if (wrong.isNotEmpty()) {
            throw GradleException(
                "Bouncy Castle resolved at " + wrong.entries.joinToString(", ") { "${it.key}:${it.value}" } +
                    " rather than the reviewed $safeBouncyCastleVersion."
            )
        }
        logger.lifecycle(
            "Bouncy Castle on the build graph: " + seen.entries.joinToString(", ") { "${it.key}:${it.value}" }
        )

        val requested = synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.toSet() }
        val unreviewed = requested - reviewedBouncyCastleRequests
        if (unreviewed.isNotEmpty()) {
            throw GradleException(
                "The build graph now asks for Bouncy Castle " + unreviewed.sorted().joinToString(", ") +
                    ", which nobody has reviewed. It is being rewritten to $safeBouncyCastleVersion. " +
                    "Check the advisory for the requested release, then add it to " +
                    "reviewedBouncyCastleRequests or move the pin."
            )
        }
    }
}

// By type rather than by the one name, so a second test task cannot start on a graph nothing
// has looked at. :patches:test is what scripts/pre-push.ps1 runs when a patch source changes.
tasks.withType<Test>().configureEach {
    dependsOn(verifyBouncyCastleBuildGraph)
}

dependencies {
    compileOnly(libs.morphe.patcher)

    // Used by JsonGenerator.
    implementation(libs.gson)

    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))

    // The register and instruction helpers under app.morphe.util are the only thing tested
    // here. They take dexlib2 methods, so the patcher has to be on the test runtime classpath
    // as well as the compile one.
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.morphe.patcher)
    // Reads the signing certificate of every retained fixture. The patcher already brings this
    // exact version at run time; this puts it on the test compile classpath as well.
    testImplementation("com.android.tools.build:apksig:9.1.1")
    // Reads the resource tables of the fixtures, for the Material You theme's colour table. This is
    // the module and commit the patcher brings at run time (2.3.0 pins 675bed2de3, 2.1.0 and 2.2.0
    // pinned 4695b583ef); it puts that copy on the test compile classpath as well. Gradle keeps
    // whichever of two commits sorts higher, so a stale pin here can silently replace the
    // patcher's. REAndroid's own arsclib is a different module that shares most of its
    // com.reandroid classes, so it would sit beside the patcher's copy instead of merging with it.
    testImplementation("com.github.MorpheApp:ARSCLib:675bed2de3")
}

// The patch tests run in two parts. fixtureTest takes the ones that read the Facebook fixtures,
// known by their source calling Fixtures or FixtureDex, the two helpers that open the fixture
// folder, and :patches:test runs the rest once fixtureTest is done. :patches:test runs without
// HUSHFACEBOOK_FIXTURE_DIR and without the fixtures among its inputs, so a fixture change reruns
// only fixtureTest, a run with -x :patches:fixtureTest is the quick pass scripts/pre-push.ps1 makes
// first, and a fixture reader that slipped into :patches:test skips there, which
// verifyPatchTestSelection refuses. Asked for by name, :patches:test still runs both.
val patchTestSources = file("src/test/kotlin")
val fixtureReader = Regex("""\b(Fixtures|FixtureDex)\.[A-Za-z]""")
val fixtureTestClasses = fileTree(patchTestSources) { include("**/*Test.kt") }.files
    .filter { fixtureReader.containsMatchIn(it.readText()) }
    .map { it.relativeTo(patchTestSources).invariantSeparatorsPath.removeSuffix(".kt") }
    .sorted()
val fixtureTestPatterns = fixtureTestClasses.flatMap { listOf("$it.class", "$it\$*.class") }

// The fixture tests skip when this is unset and read the folder when it is set. Blank counts as
// unset, as Fixtures.kt reads it; File("") would be the whole project.
val fixtureDirectory = providers.environmentVariable("HUSHFACEBOOK_FIXTURE_DIR")
val fixtureFiles = fixtureDirectory.map { configured ->
    if (configured.isBlank()) emptyList() else {
        val directory = File(configured)
        check(directory.isDirectory) { "HUSHFACEBOOK_FIXTURE_DIR names $directory, which is not a folder." }
        checkNotNull(directory.listFiles()) { "HUSHFACEBOOK_FIXTURE_DIR names $directory, which cannot be read." }
            .filter { it.isFile }.sortedBy { it.name }
    }
}.orElse(emptyList())
fun fixtureSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
fun hashFixtures(): Map<String, String> = fixtureFiles.get().associate { it.name to fixtureSha256(it) }
// Gradle's file-hash cache can reuse a digest after a file is replaced with the same size and
// timestamp, so the fixture bytes are hashed here too, once per build, as fixtureTest's input.
val fixtureDigests by lazy { hashFixtures() }
// The --tests selection given to :patches:test, which fixtureTest takes as well. The pinned
// Gradle's TestFilter has no public getter for it.
fun patchTestSelection(): Set<String> = (tasks.test.get().filter as DefaultTestFilter).commandLineIncludePatterns

val fixtureTest = tasks.register<Test>("fixtureTest") {
    group = "verification"
    description = "Runs the patch tests that read the Facebook fixtures in HUSHFACEBOOK_FIXTURE_DIR."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include(fixtureTestPatterns)
    // FixtureDex keeps each bundle's parsed dex for the length of the run (149 MB of it for 581's
    // arm64 build), held softly, so a heap that runs short drops a bundle and reads it again.
    // Gradle's default of 512 MB would do that between nearly every test.
    maxHeapSize = "2g"
    // What the folder holds is the input, not its name: a run whose APK was swapped, re-signed or
    // deleted under the same path has to run again, not come back up to date or out of the build
    // cache with the last folder's verdict. Relative, so where the folder sits on this machine
    // does not count, and an APK moved into or out of a subfolder does: the tests read only the
    // folder's top level, and name only would call that move no change. A configured empty
    // folder must run and fail, never reuse an unset folder's skip.
    inputs.property("fixturesConfigured", fixtureDirectory.map { it.isNotBlank() }.orElse(false))
    inputs.files(fixtureFiles).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("fixtureBytes", provider { fixtureDigests })
    inputs.property("suiteSelection", provider { patchTestSelection() })
    failOnNoDiscoveredTests.set(provider { patchTestSelection().isEmpty() })
    var startedWith = emptyMap<String, String>()
    doFirst {
        // setTestNameIncludePatterns replaces the --tests given to fixtureTest itself, so those join
        // :patches:test's. Dropped, a filter named after fixtureTest ran no fixture test at all.
        val selected = patchTestSelection() + (filter as DefaultTestFilter).commandLineIncludePatterns
        if (selected.isNotEmpty()) {
            setTestNameIncludePatterns(selected.toList())
            filter.isFailOnNoMatchingTests = false
        }
        startedWith = fixtureDigests
    }
    // A fixture replaced while the tests read it, even one that kept its size and time, which
    // FixtureDex no longer rehashes on every read.
    doLast {
        check(hashFixtures() == startedWith) { "The fixture folder changed while fixtureTest read it. Run it again." }
    }
}

// Both partitions' results, against every patch test class there is. It finalizes :patches:test,
// which runs after fixtureTest, and a finalizer also runs when a task is UP-TO-DATE or comes from
// the cache. fixtureTest run on its own is part of a run, and nothing here checks it.
val verifyPatchTestSelection = tasks.register("verifyPatchTestSelection") {
    group = "verification"
    description = "Checks that test and fixtureTest ran every patch test class between them."
    mustRunAfter(tasks.test, fixtureTest)
    val results = layout.buildDirectory.dir("test-results")
    doLast {
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        val suites = mutableMapOf<String, MutableSet<String>>()
        val tests = mutableMapOf<String, Int>()
        val skipped = mutableMapOf<String, MutableList<String>>()
        for (partition in listOf("test", "fixtureTest")) {
            suites[partition] = mutableSetOf()
            tests[partition] = 0
            skipped[partition] = mutableListOf()
            val reports = results.get().dir(partition).asFile
            for (report in reports.listFiles().orEmpty().filter { it.name.startsWith("TEST-") && it.extension == "xml" }) {
                val suite = parser.parse(report).documentElement
                val name = suite.getAttribute("name")
                check(suites.values.none { name in it }) { "The patch test class $name ran in both partitions." }
                suites.getValue(partition) += name
                tests[partition] = tests.getValue(partition) + suite.getElementsByTagName("testcase").length
                if ((suite.getAttribute("skipped").toIntOrNull() ?: 0) > 0) skipped.getValue(partition) += name
            }
        }
        val total = tests.values.sum()
        check(total > 0) { "No patch tests matched the requested selection." }
        check(skipped.getValue("test").isEmpty()) {
            "These patch tests skipped in :patches:test, which runs without the fixture folder: " +
                "${skipped.getValue("test")}. A test that reads the fixtures has to call Fixtures or " +
                "FixtureDex in its own source, which is how fixtureTest picks its classes."
        }
        // A selection given to either task, on the command line or in the build, makes this part
        // of a run, which the completeness checks below would refuse.
        val unfiltered = listOf(tasks.test.get(), fixtureTest.get()).all {
            (it.filter as DefaultTestFilter).commandLineIncludePatterns.isEmpty() && it.filter.includePatterns.isEmpty()
        }
        if (fixtureDirectory.map { it.isNotBlank() }.getOrElse(false)) {
            check(skipped.getValue("fixtureTest").isEmpty()) {
                "These fixture tests skipped with HUSHFACEBOOK_FIXTURE_DIR set: ${skipped.getValue("fixtureTest")}."
            }
            check(!unfiltered || tests.getValue("fixtureTest") > 0) { "fixtureTest ran no tests." }
        } else if (skipped.getValue("fixtureTest").isNotEmpty()) {
            logger.lifecycle("HUSHFACEBOOK_FIXTURE_DIR is unset, so ${skipped.getValue("fixtureTest").size} fixture test classes skipped.")
        }
        if (unfiltered) {
            val expected = fileTree(patchTestSources) { include("**/*Test.kt") }.files.map { it.nameWithoutExtension }.toSet()
            val ran = suites.values.flatten().map { it.substringAfterLast('.') }.toSet()
            check(ran == expected) { "Incomplete patch test run. Missing: ${expected - ran}; orphaned: ${ran - expected}" }
        }
        logger.lifecycle("Patch tests: ${tests.getValue("test")} in test, ${tests.getValue("fixtureTest")} in fixtureTest")
    }
}

// The raw files the patch tests read beyond their classpath, for both partitions.
tasks.withType<Test>().matching { it.name == "test" || it.name == "fixtureTest" }.configureEach {
    // The README tests read the README and the patch list, both outside this module. Declare
    // those inputs so Gradle reruns them when either changes.
    inputs.file(rootProject.file("README.md"))
        .withPropertyName("readme")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("patches-list.json"))
        .withPropertyName("patchList")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("provenance.json"))
        .withPropertyName("provenance")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // ProvenanceTest also reads NOTICE and every shipped source's header. A header-only edit
    // compiles to the same classes, so without these the task came back up to date and the
    // check never saw the change.
    inputs.file(rootProject.file("NOTICE"))
        .withPropertyName("notice")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // ShortcutCallsTest holds the settings patch's shortcut rewrite to the no-call rules there,
    // and LogoLongPressTest its Facebook logo hook to the next-call rule.
    inputs.file(rootProject.file("scripts/injected-mutation-contracts.txt"))
        .withPropertyName("mutationContracts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        rootProject.fileTree("patches/src/main"),
        rootProject.fileTree("patches/stub/src/main"),
        rootProject.fileTree("extensions") {
            include("**/src/main/**")
            exclude("**/build/**")
        },
    )
        .withPropertyName("shippedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks {
    test {
        dependsOn(fixtureTest)
        finalizedBy(verifyPatchTestSelection)
        exclude(fixtureTestPatterns)
        environment.remove("HUSHFACEBOOK_FIXTURE_DIR")
        // A --tests selection may belong to fixtureTest alone. The finalizer checks both result
        // sets, so a selection nothing matched still fails.
        filter.isFailOnNoMatchingTests = false
        failOnNoDiscoveredTests.set(false)
    }
    // The bundle a release publishes lives in build/release, not build/libs. The plugin's
    // buildAndroid merges the DEX payload into the jar task's own output in place, so any later
    // task that reruns jar (test does) put the plain jar back over the finished bundle under the
    // same name: v0.43.0 shipped with no classes.dex that way, and on 2026-09-21 the pre-push
    // test run did it again between the build and the index push. Nothing but buildAndroid
    // writes build/release. scripts/common.ps1 names the same path for every release script.
    val releaseBundleName = "patches-${project.version}.mpp"
    val verifyBundle = register<JavaExec>("verifyBundle") {
        group = "verification"
        description = "Check the Android bundle and its published patch list without rebuilding it"
        dependsOn(classes)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.BundleVerifier")
        args(
            providers.gradleProperty("patchBundle").getOrElse(
                layout.buildDirectory.file("release/$releaseBundleName").get().asFile.absolutePath
            ),
            rootProject.file("patches-list.json").absolutePath,
            project.version.toString(),
            layout.buildDirectory.file("release/bundle.sha256").get().asFile.absolutePath,
            // The key a signature has to name. Missing means no key is published yet.
            rootProject.file("morphe-signing.pub").absolutePath
        )
    }
    // The extensions the bundle carries, found the way the Morphe plugin's patches plugin finds
    // them: the subprojects of its extensions project that apply its extension plugin. Each one's
    // graph is read by a task of its own, for the lock reason PayloadGraph gives.
    val extensionsPath = project.extensions.getByType(PatchesExtension::class.java).extensionsProjectPath ?: ":extensions"
    val payloadGraphTasks = rootProject.project(extensionsPath).subprojects
        .filter { it.plugins.hasPlugin(ExtensionPlugin::class.java) }
        .map { extensionProject ->
            extensionProject.tasks.register<PayloadGraph>("sbomPayloadGraph") {
                description = "Records what this extension's payload is dexed from, for :patches:releaseSbom"
                // The payload's path in the bundle, as the plugin names it when the build doesn't.
                payload.set(extensionProject.extensions.getByType(ExtensionExtension::class.java).name
                    ?: extensionProject.projectDir.resolveSibling(extensionProject.name + ".mpe")
                        .relativeTo(extensionProject.rootDir).invariantSeparatorsPath)
                val runtime = extensionProject.configurations.named("releaseRuntimeClasspath")
                graph.set(runtime.flatMap { it.incoming.resolutionResult.rootComponent })
                val libraries = runtime.map { configuration ->
                    configuration.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }.artifacts
                }
                artifacts.set(libraries.flatMap { it.resolvedArtifacts }.map { resolved ->
                    resolved.map { Sbom.key(it.id.componentIdentifier) + "\t" + it.file.absolutePath }.sorted()
                })
                artifactFiles.from(libraries.map { it.artifactFiles })
                output.set(extensionProject.layout.buildDirectory.file("sbom/payload-graph.tsv"))
            }
        }
    // Beside the bundle, named for it, and written only after buildAndroid has finished it: a
    // finalizer that also depends on it runs when it succeeds and not otherwise, so a failed build
    // can't leave an SBOM describing the bundle before it. scripts/build-release-receipt.ps1 reads
    // it from there and records its hash in the receipt.
    val releaseSbomName = "patches-${project.version}.cdx.json"
    val releaseSbom = register<WriteReleaseSbom>("releaseSbom") {
        group = "build"
        description = "Writes the CycloneDX SBOM of the release bundle into build/release beside it"
        dependsOn("buildAndroid")
        bundle.set(layout.buildDirectory.file("release/$releaseBundleName"))
        bundleVersion.set(project.version.toString())
        epochSeconds.set(sourceDateEpoch)
        gradleVersion.set(gradle.gradleVersion)
        sourceUrl.set(project.extensions.getByType(PatchesExtension::class.java).about.source ?: "")
        val runtime = configurations.named("runtimeClasspath")
        runtimeGraph.set(runtime.flatMap { it.incoming.resolutionResult.rootComponent })
        val libraries = runtime.map { configuration ->
            configuration.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }.artifacts
        }
        runtimeArtifacts.set(libraries.flatMap { it.resolvedArtifacts }.map { resolved ->
            resolved.map { Sbom.key(it.id.componentIdentifier) + "\t" + it.file.absolutePath }.sorted()
        })
        runtimeArtifactFiles.from(libraries.map { it.artifactFiles })
        providedModules.set(configurations.named("patcherProvidedClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }
            .map { resolved ->
                resolved.mapNotNull { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.let { id -> "${id.group}:${id.module}" } }.toSet()
            })
        ownOutput.from(sourceSets["main"].output)
        payloadGraphs.from(payloadGraphTasks.map { graphTask -> graphTask.flatMap { it.output } })
        output.set(layout.buildDirectory.file("release/$releaseSbomName"))
    }
    named("buildAndroid") {
        inputs.files(buildIdentityInputs).withPropertyName("identityInputs").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("identitySource", listOf(buildSourceSnapshot.commit, buildSourceSnapshot.tree,
            buildSourceSnapshot.state, buildSourceSnapshot.inputs))
        // Resolved at configuration time. Reaching for project inside doLast is what the
        // configuration cache refuses, and Gradle 10 turns that refusal into an error.
        val bundleFile = layout.buildDirectory.file("libs/$releaseBundleName")
        val releaseDirectory = layout.buildDirectory.dir("release")
        val pinnedEpoch = sourceDateEpoch
        // Why the stamp is zero, when the tree is the reason, said where the stamp is written: the
        // release receipt refuses such a bundle, and only this build saw the tree it came from.
        val unheldChanges = if (sourceDateEpochFromEnvironment == null) uncommittedChanges else emptyList()
        // A path outside the repository, never MORPHE_SIGNING_KEY: the plugin signs build/libs
        // itself when that is set, and pinBundleTimestamp throws its comment away. See signBundle.
        // ~/.gradle/gradle.properties reaches every build, a shell opened before the variable was set included.
        // An empty variable (Git Bash's `export X=`) still counts as set, so it falls through to the property.
        val signingKeyPath = providers.environmentVariable("HUSHFACEBOOK_SIGNING_KEY_FILE").filter { it.isNotBlank() }
            .orElse(providers.gradleProperty("hushfacebook.signingKeyFile")).orNull?.takeIf { it.isNotBlank() }
        val publishedKeyFile = rootProject.file("morphe-signing.pub")
        val repositoryRoot = rootDir
        // A key set or changed is a different bundle, never an up-to-date one.
        inputs.property("signing", listOf(signingKeyPath.orEmpty(),
            if (publishedKeyFile.isFile) publishedKeyFile.readText().trim() else ""))
        doLast {
            if (snapshotBuildIdentity() != buildSourceSnapshot || snapshotInputTimes() != buildIdentityInputTimes) {
                throw GradleException("Build source inputs changed during compilation. Rebuild before using this bundle.")
            }
            // Emptied first, so the directory never holds a bundle of another version or a
            // checksum of another build: the release scripts take the one file they find.
            val directory = releaseDirectory.get().asFile
            directory.mkdirs()
            directory.listFiles()?.filter { it.isFile }?.forEach { stale ->
                if (!stale.delete()) throw GradleException("Could not clear the old release file $stale")
            }
            val releaseBundle = directory.resolve(releaseBundleName)
            bundleFile.get().asFile.copyTo(releaseBundle)
            // Before the checksum, so what is recorded is what a rebuild will produce.
            pinBundleTimestamp(releaseBundle, pinnedEpoch, buildSourceSnapshot)
            if (unheldChanges == null) {
                logger.warn("git couldn't say whether the working tree matches HEAD, so $releaseBundleName is " +
                    "stamped 0 rather than a commit's time, and no release receipt will take it.")
            } else if (unheldChanges.isNotEmpty()) {
                val shown = unheldChanges.take(5).joinToString("; ") { it.trim() } +
                    (if (unheldChanges.size > 5) "; and ${unheldChanges.size - 5} more" else "")
                logger.warn("The working tree had uncommitted changes as the build started ($shown), so " +
                    "$releaseBundleName is stamped 0 rather than HEAD's commit time, and no release receipt " +
                    "will take it. Commit or stash them and build again to release it.")
            }
            // After the timestamp, so the signature covers the bytes that ship; a rebuild compares
            // entries, which the comment doesn't touch.
            if (signingKeyPath != null) {
                signBundle(releaseBundle, File(signingKeyPath), publishedKeyFile, repositoryRoot)
            } else if (publishedKeyFile.isFile) {
                logger.warn("Neither HUSHFACEBOOK_SIGNING_KEY_FILE nor the hushfacebook.signingKeyFile Gradle " +
                    "property is set, so $releaseBundleName is unsigned while " +
                    "morphe-signing.pub publishes a key. Manager refuses it from this source, and no release " +
                    "receipt will take it.")
            }
            // Record only at the producer boundary. Standalone verification must not
            // bless a modified bundle by generating its own expected checksum.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(releaseBundle.readBytes())
                .joinToString("") { "%02x".format(it) }
            directory.resolve("bundle.sha256").writeText(digest)
        }
        finalizedBy(verifyBundle)
        finalizedBy(releaseSbom)
    }
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        // jar, not build. build runs check, which runs test, and ReadmePatchNamesTest asserts
        // that the checked-in patches-list.json names exactly the patches the Kotlin sources
        // declare. Adding or renaming a patch therefore failed the test before the task that
        // regenerates the list could run, and the only ways through were -x test or editing the
        // generated JSON by hand. The generator reads build/libs/patches-<version>.mpp, which
        // jar produces; the test and verifyBundle are still the gate afterwards, which is the
        // order that can actually pass.
        dependsOn(jar)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
        args(project.version.toString())
    }
    // The patch list has to be regenerated before anything publishes the bundle.
    publish {
        dependsOn("generatePatchesList")
    }
}

// Gradle's file snapshots can reuse a hash when size and mtime are unchanged. Bind the raw-byte
// digest before producer tasks execute, so a changed clean checkout cannot reuse stale classes.
gradle.taskGraph.whenReady {
    val producer = tasks.named("buildAndroid").get()
    if (hasTask(producer)) {
        val visited = mutableSetOf<Task>()
        fun bind(task: Task) {
            if (!visited.add(task)) return
            task.inputs.property("hushfacebookProducerInputsSha256", buildSourceSnapshot.inputs)
            getDependencies(task).forEach(::bind)
        }
        bind(producer)
    }
}
