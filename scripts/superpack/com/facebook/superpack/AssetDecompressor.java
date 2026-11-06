package com.facebook.superpack;

import android.content.res.AssetManager;
import java.io.InputStream;

/** Same native surface as the input's class; never called here, only registered. */
public final class AssetDecompressor {
    private AssetDecompressor() {
    }

    public static final native String[] decompress(AssetManager assets, String a, String b, String c);

    public static final native String[] decompress_from_so(String a, String b, String c);

    public static final native String[] decompress_legacy(InputStream in, String a, String b);

    public static final native String[] decompress_range(AssetManager assets, String a, String b, String c, int d, int e);

    public static final native String[] decompress_range_from_so(String a, String b, String c, int d, int e);

    public static final native String[] decompress_with_ref(String a, String b, String c, String d);

    public static final native int get_architecture();

    public static final native int sync_directory_to_disk(String path);

    public static final native int sync_file_to_disk(String path);

    public static final native void testDecompressorLibraryUsable(byte[] bytes);
}
