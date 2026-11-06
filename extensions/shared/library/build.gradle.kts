plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.morphe.extension"
    compileSdk = 35

    defaultConfig {
        // Facebook's Android 9+ build declares minSdk 28 (#102), and this library only runs inside Facebook.
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The payload is injected into Facebook, whose floor is API 28. javac compiles against a
    // modern JDK and Robolectric runs on one, so a library call or a type above that floor is
    // green all the way to a phone, where D8 has left it as a stub that throws. NewApi is the
    // only check here that reads the SDK_INT guards instead of flagging every guarded call.
    lint {
        checkOnly += "NewApi"
        error += "NewApi"
        // NewApi catches a call above the floor. ObsoleteSdkInt catches an SDK_INT guard at or
        // below it, which is dead code today and a wrong floor the next time minSdk moves.
        checkOnly += "ObsoleteSdkInt"
        error += "ObsoleteSdkInt"
        abortOnError = true
    }
}

dependencies {
    compileOnly(libs.annotation)
}

// GHSA-xxph-c9ww-hj94 covers every Guava before 33.7.2. Nothing here asks for Guava, but the
// Android plugin's device test graphs do, and every graph in the build takes the catalog's
// release, as the ones in :patches and :extensions:facebook do.
val safeGuavaVersion = libs.versions.guava.get()

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.google.guava" && requested.name == "guava") {
            useVersion(safeGuavaVersion)
            because("GHSA-xxph-c9ww-hj94 covers every Guava before 33.7.2.")
        }
    }
}
