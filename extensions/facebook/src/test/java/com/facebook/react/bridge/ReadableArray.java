/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.react.bridge;

/**
 * A test stand-in for React Native's array interface, which Redex keeps. The photo viewer's setSrc
 * walks the sources it's handed through these two reads, and Long press to save Marketplace photos
 * reads them the same way.
 */
public interface ReadableArray {
    int size();

    /** The map at [index], or null when that entry isn't one. */
    ReadableMap getMap(int index);
}
