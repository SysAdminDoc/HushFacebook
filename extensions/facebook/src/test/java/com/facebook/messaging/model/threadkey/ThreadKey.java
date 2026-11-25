/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.messaging.model.threadkey;

/**
 * Stands in for Facebook's chat key in tests. The extension finds a chat screen's key by this kept
 * class name and reads it as text, the way Facebook's prints as {@code TYPE:id}.
 */
public final class ThreadKey {
    private final String text;

    public ThreadKey(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return text;
    }
}
