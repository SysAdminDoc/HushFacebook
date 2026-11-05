plugins {
    alias(libs.plugins.android.library) apply false
}

// One Bouncy Castle version across every module, because the request arrives in more places
// than any single module can see. The patcher pins 1.77 and the Android build tools ask for
// 1.79, and both are inside advisories gradle/libs.versions.toml lists; the reviewed release is
// the one it pins, and it says why it is that one. None of this reaches the payload injected
// into Facebook. It is the build and signing classpath, and the repository's rule is that a
// known-affected component does not stay in a reproducible graph.
//
// Per module this was applied twice and still missed three graphs: :extensions:shared's release
// runtime classpath, which has no force of its own, and two configurations the Android plugin
// makes for itself. The settings classpath is forced separately in settings.gradle.kts, which
// resolves before this file exists.
val reviewedBouncyCastle = libs.versions.bouncycastle.get()
// These two AGP test-tool configurations carry gRPC 1.57.2 and 1.69.1. Keep their
// standard Netty modules aligned without changing native tcnative/incubator versions.
val utpNettyScopes = setOf(
    "_internal-unified-test-platform-core",
    "_internal-unified-test-platform-android-test-plugin-host-emulator-control",
)
val reviewedNettyModules = setOf(
    "netty-buffer", "netty-codec", "netty-codec-http", "netty-codec-http2",
    "netty-codec-socks", "netty-common", "netty-handler", "netty-handler-proxy",
    "netty-resolver", "netty-transport", "netty-transport-native-unix-common",
)
allprojects {
    configurations.configureEach {
        val alignUtpNetty = name in utpNettyScopes
        val alignUtpHttp = name == "_internal-unified-test-platform-android-test-plugin-result-listener-gradle"
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle") {
                useVersion(reviewedBouncyCastle)
                because("The build classpath must use the reviewed Bouncy Castle release.")
            }
            when ("${requested.group}:${requested.name}") {
                "com.google.guava:guava" -> useVersion("33.7.2-jre")
                "org.apache.commons:commons-lang3" -> useVersion("3.21.0")
                "org.bitbucket.b_c:jose4j" -> useVersion("0.9.6")
            }
            if (alignUtpNetty && requested.group == "io.netty" && requested.name in reviewedNettyModules) {
                useVersion("4.1.138.Final")
                because("AGP's internal gRPC tooling must use the reviewed Netty security release.")
            }
            if (alignUtpHttp && requested.group == "org.apache.httpcomponents" &&
                requested.name in setOf("httpclient", "httpmime")) {
                useVersion("4.5.14")
                because("UTP result-listener requests must not use HttpClient's affected URI authority parser.")
            }
        }
    }
}
