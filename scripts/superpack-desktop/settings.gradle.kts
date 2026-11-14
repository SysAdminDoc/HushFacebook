// A build of its own, run with the repository's wrapper:
//   ./gradlew -p scripts/superpack-desktop installDist
// It isn't part of the patches build, so its dependencies never reach the bundle.
rootProject.name = "superpack-desktop"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
