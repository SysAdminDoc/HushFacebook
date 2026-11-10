import java.util.Properties
import javax.inject.Inject

/**
 * Hush Unpacker, the phone half of #102: it decodes a Facebook Android 9+ build's Superpack store
 * with the build's own library and saves the store-less copy Morphe Manager can patch. The decoder
 * and the rewrite are the same files scripts/superpack-unpack.ps1 runs over adb, copied in here
 * without the compile-only AssetManager stand-in, which the phone's framework provides.
 */
plugins {
    id("com.android.application")
}

abstract class DecoderSources : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun copy() {
        fileSystem.sync {
            from(sourceDir) {
                include("com/facebook/superpack/**", "hushfacebook/superpack/**")
            }
            into(outputDir)
        }
    }
}

val decoderSources = tasks.register<DecoderSources>("decoderSources") {
    sourceDir.set(rootProject.layout.projectDirectory.dir("scripts/superpack"))
    outputDir.set(layout.buildDirectory.dir("generated/source/decoder"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(decoderSources, DecoderSources::outputDir)
    }
}

// AGP 9 hands every app the Kotlin standard library. This one is plain Java, and the library
// alone would be 2.6 MB of its 2.8 MB.
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath")) exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

// The repository's version, so the app and the patches always say the same number.
val repoVersion = rootProject.version.toString()
val versionParts = repoVersion.split('.').map { it.toInt() }

// The release key stays out of git: helper/keystore.properties (gitignored) names it. Without that
// file the release APK comes out unsigned, and release.ps1 won't ship it.
val keystoreProps = Properties().apply {
    val propsFile = file("keystore.properties")
    if (propsFile.isFile) propsFile.inputStream().use { load(it) }
}

android {
    namespace = "app.hushfacebook.unpacker"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hushfacebook.unpacker"
        // Facebook's Android 9+ build is the one it unpacks, so Android 9 is the floor.
        minSdk = 28
        targetSdk = 36
        versionName = repoVersion
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    signingConfigs {
        keystoreProps.getProperty("RELEASE_STORE_FILE")?.let { storePath ->
            create("release") {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The library binds its natives to com.facebook.superpack by name, so nothing is renamed.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}
