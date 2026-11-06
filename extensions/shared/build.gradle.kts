dependencies {
    implementation(project(":extensions:shared:library"))
}

extension {
    name = "extensions/shared.mpe"
}

android {
    // Unique per extension to avoid install-time package collisions.
    namespace = "app.morphe.extension.shared"

    defaultConfig {
        // The library it carries runs only inside Facebook, whose Android 9+ build declares API 28 (#102).
        minSdk = 28
    }
}
