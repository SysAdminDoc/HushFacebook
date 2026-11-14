/**
 * Decodes a Facebook Android 9+ build's Superpack store on a PC, with no phone or emulator: the
 * APK's own arm64 libsuperpack-jni.so runs under unidbg, an ARM emulator for Android native
 * libraries. The verification and the rewrite are the same files the adb route and Hush Unpacker
 * use, compiled in from scripts/superpack.
 */
plugins {
    application
}

dependencies {
    implementation("com.github.zhkl0228:unidbg-android:0.9.9")
    // The JIT backend, with its own natives for x86_64 Windows, Linux and macOS and arm64 Linux
    // and macOS. unidbg-android brings Unicorn, the slow fallback.
    implementation("com.github.zhkl0228:unidbg-dynarmic:0.9.9")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

sourceSets {
    main {
        java {
            // DecodeStore, StorelessApk and the class surface the library registers against. The
            // AssetManager stand-in comes too, so AssetDecompressor compiles; nothing loads it here.
            srcDir("../superpack")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("hushfacebook.superpack.desktop.DesktopDecode")
    // unidbg loads its emulator through System.loadLibrary, which JDK 24 and newer warn about.
    applicationDefaultJvmArgs = listOf("-Xmx1g", "--enable-native-access=ALL-UNNAMED")
}
