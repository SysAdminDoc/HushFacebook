import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

rootProject.name = "hushfacebook"

pluginManagement {
    val allowLocalPlugins = providers.gradleProperty("allowMavenLocal")
        .map(String::toBoolean)
        .getOrElse(false)
    repositories {
        // Local plugin artifacts are useful while developing the plugin, but they must never
        // silently override the reviewed repositories used for a release build.
        if (allowLocalPlugins) mavenLocal()
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR"))
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN"))
            }
        }
        // baksmali and smali come from a source build, resolved as com.github.MorpheApp.smali
        // rather than from Maven Central. The comment used to name iBotPeaches/smali, which the
        // build stopped using when the patches plugin switched forks at 1.3.2.
        //
        // google/smali 3.0.10 shipped officially on 2026-08-20, so "until official releases come
        // out again" is no longer the reason to keep this. The reason is that the pin has to be
        // whatever the patcher was built against: two copies of dexlib2 on the R8 classpath fail
        // buildAndroid with "Classpath type already present".
        maven { url = uri("https://jitpack.io") }
    }
}

// app.morphe:morphe-patches-gradle-plugin 2.1.0 brings kotlin-gradle-plugin 2.4.21.
// CVE-2026-53914 (GHSA-r937-wjx7-w2jp), a deserialisation flaw in the Kotlin build cache, covers
// every release before 2.4.20, so Kotlin needs no force here any more;
// gradle/verification-metadata.xml says which version actually resolved. kotlin-stdlib and
// kotlin-reflect on this classpath still resolve to 2.4.10, the copies Gradle 9.8.2 ships and
// pins; they aren't the build cache code.
//
// The same classpath brings Bouncy Castle 1.79, the version AGP's apkzlib, builder and
// sdk-common ask for (the plugin itself asks for 1.77), and 1.79 is inside six of the
// advisories gradle/libs.versions.toml lists.
// :patches and :extensions:facebook each force their own graphs to
// the reviewed release, but neither reaches this one: a force on a project configuration cannot
// touch the classpath the plugins themselves resolve on, and that classpath is where the
// signing code actually runs. 1.86 is spelled out here rather than read from
// gradle/libs.versions.toml because the version catalog does not exist yet at this point in the
// build; the catalog pins the same value and says why it is that one. Move both together.
buildscript {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle") {
                useVersion("1.86")
            }
            when ("${requested.group}:${requested.name}") {
                "com.google.guava:guava" -> useVersion("33.7.2-jre")
                "org.apache.commons:commons-lang3" -> useVersion("3.21.0")
                "org.bitbucket.b_c:jose4j" -> useVersion("0.9.6")
                // Jetifier introduces JDOM only through the settings-plugin classpath.
                "org.jdom:jdom2" -> useVersion("2.0.6.1")
            }
        }
    }
}

val allowMavenLocal = providers.gradleProperty("allowMavenLocal")
    .map(String::toBoolean)
    .getOrElse(false)

plugins {
    id("app.morphe.patches") version "2.1.0"
}

// The plugin is resolved here, before any project's build script. A project buildEnvironment
// report cannot see this graph. Hand its providers to typed task inputs during configuration,
// rather than reconstructing a second classpath or retaining Settings in a task action.
val settingsClasspath = buildscript.configurations.named("classpath")
val settingsLibraries = settingsClasspath.map { configuration ->
    configuration.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }.artifacts
}
gradle.rootProject {
    extensions.extraProperties["hushSettingsTooling"] = mapOf(
        "graph" to settingsClasspath.flatMap { it.incoming.resolutionResult.rootComponent },
        "artifacts" to settingsLibraries.flatMap { it.resolvedArtifacts }.map { resolved ->
            resolved.map { artifact ->
                val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                "${id.group}:${id.module}:${id.version}\t${artifact.file.absolutePath}"
            }.sorted()
        },
        "files" to settingsLibraries.map { it.artifactFiles },
    )
}

gradle.beforeProject {
    buildscript.configurations.configureEach {
        resolutionStrategy.eachDependency {
            when ("${requested.group}:${requested.name}") {
                "com.google.guava:guava" -> useVersion("33.7.2-jre")
                "org.apache.commons:commons-lang3" -> useVersion("3.21.0")
                "org.bitbucket.b_c:jose4j" -> useVersion("0.9.6")
            }
        }
    }
}

settings {
    extensions {
        defaultNamespace = "app.morphe.extension"

        // Must resolve to an absolute path (not relative),
        // otherwise the extensions in subfolders will fail to find the proguard config.
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}

if (!allowMavenLocal) {
    val localMavenUrl = file(System.getProperty("user.home"))
        .resolve(".m2/repository")
        .toURI()
        .toString()
        .removeSuffix("/")
    val localRepositories = dependencyResolutionManagement.repositories.filter { repository ->
        repository is MavenArtifactRepository
                && repository.url.toString().removeSuffix("/") == localMavenUrl
    }
    localRepositories.forEach { repository ->
        dependencyResolutionManagement.repositories.remove(repository)
    }
}

include(":patches:stub")
// Hush Unpacker, the app that unpacks Facebook's Android 9+ build on a phone (#102).
include(":helper")
