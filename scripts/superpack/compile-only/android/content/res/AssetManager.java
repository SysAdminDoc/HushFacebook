package android.content.res;

/**
 * Compile-time stand-in so AssetDecompressor's native signatures name the framework class without
 * an android.jar. It never goes into the decoder's dex: on the device the framework's own class
 * resolves.
 */
public final class AssetManager {
    private AssetManager() {
    }
}
