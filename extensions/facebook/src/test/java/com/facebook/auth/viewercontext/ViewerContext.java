/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.auth.viewercontext;

/** Stands in for Facebook's ViewerContext, which keeps the signed-in user's id in mUserId. */
public final class ViewerContext {
    public final String mUserId;

    public ViewerContext(String userId) {
        this.mUserId = userId;
    }
}
