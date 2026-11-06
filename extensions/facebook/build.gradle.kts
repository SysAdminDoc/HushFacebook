/**
 * Writes NOTICE into a Java constant, so the Licenses row shows exactly the file the repository
 * ships. Morphe's section 7b notice has to reach the person using the app, and a hand-kept copy
 * would drift from NOTICE the first time either changed.
 */
abstract class GenerateLicenseNotice : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notice: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val text = notice.get().asFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val literal = buildString {
            for (ch in text) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    else -> if (ch.code < 0x20 || ch.code > 0x7e) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }
        val directory = outputDir.get().asFile.resolve("app/morphe/extension/facebook/settings")
        directory.mkdirs()
        directory.resolve("LicenseNotice.java").writeText(
            "package app.morphe.extension.facebook.settings;\n\n" +
                "/** Generated from NOTICE by :extensions:facebook:generateLicenseNotice. Do not edit. */\n" +
                "public final class LicenseNotice {\n" +
                "    public static final String TEXT = \"$literal\";\n\n" +
                "    private LicenseNotice() {\n    }\n}\n",
            Charsets.UTF_8,
        )
    }
}

val generateLicenseNotice = tasks.register<GenerateLicenseNotice>("generateLicenseNotice") {
    notice.set(rootProject.layout.projectDirectory.file("NOTICE"))
    outputDir.set(layout.buildDirectory.dir("generated/source/licenseNotice"))
}

extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>().onVariants { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(generateLicenseNotice, GenerateLicenseNotice::outputDir)
}

// Robolectric 4.17 asks for Bouncy Castle 1.85 twice: by name, through the bc-jdk18on-bom it
// imports, and with no version of its own for the bcprov module that BOM governs. Every request
// in this module is rewritten to the reviewed release so related test libraries cannot resolve
// at mixed versions. No production configuration contains this group, so none of it reaches
// the MPE payload.
//
// Checking the resolved graph afterwards would prove nothing: the rewrite above guarantees the
// answer, so a "wrong resolved version" branch could never run. What the rewrite hides, and what
// is worth failing on, is the request underneath it. When Robolectric moves to a version nobody
// has looked at, this build stops instead of quietly rewriting it away.
val safeBouncyCastleVersion = libs.versions.bouncycastle.get()
// 1.85 was checked on 2026-09-15 against the six advisories the catalog names: CVE-2025-8916
// ends at 1.78, CVE-2026-5588 at 1.83, CVE-2025-14813 and CVE-2026-0636 at 1.84, and
// CVE-2026-8763 and CVE-2026-13506 at 1.85. It is rewritten
// all the same, because one reviewed release in the graph is easier to hold than two.
val reviewedBouncyCastleRequests = setOf("1.85", safeBouncyCastleVersion)
// Modules Robolectric declares with no version of its own, because the BOM it imports carries
// the version for them. Reviewing that BOM is what covers these, so they are named here rather
// than by a version: the BOM's own request is reviewed above, and a module that turns up here
// without one is a request nothing in this file chose the version for.
//
// Reading the constraint behind such a request instead was tried on 2026-09-15 and is worthless:
// the rewrite has already moved the BOM to the reviewed release by then, so every constraint it
// contributes names that release and the check can never fail. Same trap as the resolved-version
// check described above.
val reviewedVersionlessBouncyCastleModules = setOf("bcprov-jdk18on")
// Guarded by hand rather than by a synchronized wrapper: in a Kotlin build script `java` is the
// Java extension, so the java.util package cannot be named here.
val requestedBouncyCastleVersions = sortedSetOf<String>()
// Module names, kept apart from the versions above so a name can never end up in the reviewed
// version set by someone pasting it in.
val unversionedBouncyCastleRequests = sortedSetOf<String>()
// GHSA-xxph-c9ww-hj94 covers every Guava before 33.7.2, and Robolectric's test graph asks for
// 33.6.0. Rewritten to the catalog's release, as :patches does for the patcher's graph.
val safeGuavaVersion = libs.versions.guava.get()

configurations.configureEach {
    // Only the unit-test graphs are recorded for the gate below. Every graph is still rewritten,
    // but the Android plugin's own ones ask for releases of their own (its unified test platform
    // graph asks for 1.79), and whether one of those had resolved by the time the gate ran
    // depended on task order: a run with lint before the unit tests failed on a request no test
    // ever saw.
    val recordsForGate = name.endsWith("UnitTestRuntimeClasspath")
    resolutionStrategy.eachDependency {
        if (requested.group == "org.bouncycastle" && !recordsForGate) {
            useVersion(safeBouncyCastleVersion)
            because("Every graph in this module takes the reviewed security release.")
        } else if (requested.group == "org.bouncycastle") {
            // Recorded whatever it is, including a request that carries no version of its own.
            // A ?.let dropped those: one arriving through a platform or a BOM was rewritten to
            // the reviewed release like any other and then counted nowhere, so the unreviewed
            // set stayed empty for it and the gate below had nothing to fail on. A request with
            // no version is the one most worth reading, because nothing in this file chose
            // what it would otherwise have resolved to.
            // Blank as well as null. A declaration with no version at all reports "" rather
            // than null, so a plain null check counted it as a version and the failure read
            // "asks for Bouncy Castle , which nobody has reviewed": it stopped the build, which
            // is the point, but said nothing a reader could act on.
            val asked = requested.version?.takeIf { it.isNotBlank() }
            if (asked != null) {
                synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.add(asked) }
            } else {
                synchronized(unversionedBouncyCastleRequests) { unversionedBouncyCastleRequests.add(requested.name) }
            }
            useVersion(safeBouncyCastleVersion)
            because("The Robolectric test graph must use the reviewed security release.")
        }
        if (requested.group == "com.google.guava" && requested.name == "guava") {
            useVersion(safeGuavaVersion)
            because("GHSA-xxph-c9ww-hj94 covers every Guava before 33.7.2.")
        }
    }
}

val verifyBouncyCastleTestGraph = tasks.register("verifyBouncyCastleTestGraph") {
    group = "verification"
    description = "Checks the unit-test graphs for unreviewed Bouncy Castle requests."

    doLast {
        // Resolving is what runs the rewrite above, so the requests are collected here rather
        // than being read from a set that nothing has filled yet.
        val classpaths = configurations
            .filter { it.name.endsWith("UnitTestRuntimeClasspath") && it.isCanBeResolved }
            .sortedBy { it.name }
        if (classpaths.isEmpty()) {
            throw GradleException("This module has no unit-test runtime classpath to inspect.")
        }

        for (classpath in classpaths) {
            val modules = classpath.incoming.resolutionResult.allComponents
                .mapNotNull { component ->
                    component.moduleVersion?.takeIf { it.group == "org.bouncycastle" }
                }
                .distinctBy { "${it.group}:${it.name}:${it.version}" }
                .sortedBy { it.name }

            if (modules.isEmpty()) {
                throw GradleException("${classpath.name} contains no Bouncy Castle module.")
            }
            logger.lifecycle(
                "Bouncy Castle in ${classpath.name}: " +
                    modules.joinToString(", ") { "${it.name}:${it.version}" }
            )
        }

        val requested = synchronized(requestedBouncyCastleVersions) {
            requestedBouncyCastleVersions.toSet()
        }
        val unversioned = synchronized(unversionedBouncyCastleRequests) {
            unversionedBouncyCastleRequests.toSet()
        }
        val unreviewed = (requested - reviewedBouncyCastleRequests).sorted() +
            (unversioned - reviewedVersionlessBouncyCastleModules).sorted()
                .map { "$it with no version of its own" }
        if (unreviewed.isNotEmpty()) {
            throw GradleException(
                "The test graph now asks for Bouncy Castle " + unreviewed.joinToString(", ") +
                    ", which nobody has reviewed. It is being rewritten to $safeBouncyCastleVersion. " +
                    "Check the advisory for the requested release, then add a version to " +
                    "reviewedBouncyCastleRequests, or a module asking for no version of its own " +
                    "to reviewedVersionlessBouncyCastleModules once you have read what carries " +
                    "its version, or move the pin."
            )
        }
    }
}

// By type rather than by the one name. This module builds unit tests for debug only today, so
// testDebugUnitTest is the whole of it, but a second unit-test variant would otherwise start a
// test JVM on a graph nothing had looked at.
tasks.withType<Test>().configureEach {
    dependsOn(verifyBouncyCastleTestGraph)
    // Codec checks read these in their JVM. A different configuration must not reuse a prior
    // pass or skip just because its Java sources haven't changed.
    for (name in listOf("HUSHFACEBOOK_TEST_FFMPEG", "HUSHFACEBOOK_TEST_FFPROBE", "PATH")) {
        val value = providers.environmentVariable(name).orElse("")
        inputs.property("codecTool.$name", value)
        environment(name, value.get())
    }
    inputs.files(provider {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val path = providers.environmentVariable("PATH").orElse("").get()
        listOf("ffmpeg", "ffprobe").flatMap { name ->
            val configured = providers.environmentVariable("HUSHFACEBOOK_TEST_${name.uppercase()}")
                .orElse("").get()
            if (configured.isNotBlank()) listOf(File(configured)) else {
                path.split(File.pathSeparator).filter { it.isNotEmpty() }.map { directory ->
                    File(directory.removeSurrounding("\""), name + if (windows) ".exe" else "")
                }
            }
        }.filter { it.isFile }
    }).withPropertyName("codecExecutables").withPathSensitivity(PathSensitivity.NONE)
    testLogging.events("skipped")
    // Robolectric's AtomicFile at API 30 finishes a write by renaming the new file over the old
    // one. On Windows, JDKs before 25 won't rename over an existing file, so a second crash report
    // never lands and ClearLogBufferPreferenceTest fails although a phone replaces it. Say so up
    // front instead of leaving one unexplained failure.
    val testJdk = javaLauncher.map { it.metadata.languageVersion.asInt() }
    doFirst {
        val jdk = testJdk.orNull ?: JavaVersion.current().majorVersion.toInt()
        if (System.getProperty("os.name").startsWith("Windows") && jdk < 25) {
            logger.warn("Unit tests on Windows need JDK 25 or newer (Android Studio's bundled JBR works): " +
                "JDK $jdk can't rename a file over an existing one, which Robolectric's AtomicFile relies on, " +
                "so ClearLogBufferPreferenceTest will fail here.")
        }
    }
}

dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(libs.annotation)
    // The reel download handler implements kotlin.jvm.functions.Function1. Facebook carries it
    // under its real name, so the payload resolves it against Facebook's own copy and ships no
    // Kotlin standard library of its own. The reel patch refuses a build that renamed it.
    compileOnly(libs.kotlin.stdlib)
    testImplementation(project(":extensions:shared:library"))
    testImplementation(libs.kotlin.stdlib)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(libs.jna)
}

extension {
    name = "extensions/facebook.mpe"
}

android {
    namespace = "app.morphe.extension.facebook"

    defaultConfig {
        // Facebook's Android 9+ build declares minSdk 28 (#102), the lowest floor this code runs at.
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // The payload runs inside Facebook. javac and Robolectric run on a modern JDK, so a call above
    // Facebook's floor is green all the way to a phone, where it throws. NewApi reads the SDK_INT
    // guards; ObsoleteSdkInt catches a guard at or below the floor.
    lint {
        checkOnly += "NewApi"
        error += "NewApi"
        checkOnly += "ObsoleteSdkInt"
        error += "ObsoleteSdkInt"
        abortOnError = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1g"
            // LicenseNoticeTest holds NOTICE against the copy generated into the payload, and
            // NOTICE is outside this module, so without this Gradle calls the task up to date
            // after NOTICE changes and the comparison never runs.
            it.inputs.file(rootProject.layout.projectDirectory.file("NOTICE"))
                .withPropertyName("licenseNotice")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            // PatchFamilyTest holds every family to patches-list.json, which sits outside this module
            // as well, so a push of the catalog alone would find these tests up to date.
            it.inputs.file(rootProject.layout.projectDirectory.file("patches-list.json"))
                .withPropertyName("patchList")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            // PatchStatusWiringTest reads each patch's enableStatus call from the patch sources, so
            // a patch-only change (a misspelled status name) has to rerun these tests too.
            it.inputs.dir(rootProject.layout.projectDirectory.dir("patches/src/main/kotlin"))
                .withPropertyName("patchSources")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            it.jvmArgs(
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                // Robolectric's FileDescriptor interceptor reaches the fd through SharedSecrets.
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}
