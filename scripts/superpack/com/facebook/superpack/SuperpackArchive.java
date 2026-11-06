package com.facebook.superpack;

import java.io.InputStream;
import java.io.OutputStream;

/** Same native surface as the input's class, so the input's own library can register against it. */
public final class SuperpackArchive {
    private SuperpackArchive() {
    }

    public static native void appendAnonNative(long archive, long file);

    public static native void appendNative(long archive, long file);

    public static native void closeNative(long archive);

    public static native long createNative();

    public static native long extractNextNative(long archive, String[] names);

    public static native long getThreadNumOption(int threads);

    public static native boolean isEmptyNative(long archive);

    public static native long[] nextMemfdNative(long archive, String name);

    public static native long nextNative(long archive);

    public static native long readNative(InputStream in, String extension, long options);

    public static native long readNative(String path, String extension, long options);

    public static native void setPackingOptionsNative(long archive, boolean a, boolean b);

    public static native void setStorageNative(long archive, String path, int mode);

    public static native void writeNative(long archive, OutputStream out);
}
