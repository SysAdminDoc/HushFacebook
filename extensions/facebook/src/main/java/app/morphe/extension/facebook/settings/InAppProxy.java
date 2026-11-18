/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;

/**
 * In-app proxy (#504): Facebook's connections go through an HTTP proxy the person names as
 * host:port, a VLESS or other app's local HTTP port included. Facebook's network stack (Tigon) reads
 * the Java proxy properties, {@code https.proxyHost} then {@code http.proxyHost} with their ports,
 * on every request and hands a change to its native side, and Java's own connections read the same
 * ones. So each of Facebook's processes sets them as it starts. Android puts its own values back
 * (or clears them) in every process when the phone's proxy changes, so the properties are set again
 * after each {@code PROXY_CHANGE}. A blank or unusable address leaves the phone's own proxy, if any,
 * alone. SOCKS isn't read by Tigon, so only an HTTP proxy is taken.
 */
public final class InAppProxy {
    /** Android's broadcast for a change of the phone's proxy ({@code android.net.Proxy#PROXY_CHANGE_ACTION}). */
    static final String PROXY_CHANGE = "android.intent.action.PROXY_CHANGE";
    /** Loopback addresses stay off the proxy: a local server of Facebook's isn't on the proxy's side. */
    static final String LOCAL_HOSTS = "localhost|127.*|[::1]";
    /** When the properties are set again after a proxy change, since Android's own reset may land later. */
    static final long[] REAPPLY_AFTER_MS = {0, 500, 2_000};

    private static final AtomicBoolean watching = new AtomicBoolean();

    private InAppProxy() {
    }

    /** A proxy's host and port. */
    static final class Address {
        final String host;
        final int port;

        Address(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /** How the row shows and saves it: host:port, an IPv6 host in brackets. */
        @Override
        public String toString() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }
    }

    /**
     * The proxy [text] names, or null when it's blank or isn't host:port. Takes {@code http://} in
     * front and a slash at the end, as a copied address may have them, and an IPv6 host in brackets.
     */
    @Nullable
    static Address parse(@Nullable String text) {
        if (text == null) return null;
        String rest = text.trim();
        if (rest.regionMatches(true, 0, "http://", 0, 7)) rest = rest.substring(7);
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        if (rest.isEmpty() || rest.contains("://")) return null;
        String host;
        String port;
        if (rest.startsWith("[")) {
            int close = rest.indexOf("]:");
            if (close < 0) return null;
            host = rest.substring(1, close);
            port = rest.substring(close + 2);
            if (host.isEmpty() || !host.matches("[0-9A-Fa-f:.]+") || host.indexOf(':') < 0) return null;
        } else {
            int colon = rest.indexOf(':');
            if (colon <= 0 || colon != rest.lastIndexOf(':')) return null;
            host = rest.substring(0, colon);
            port = rest.substring(colon + 1);
            if (!host.matches("[A-Za-z0-9._-]+") || host.startsWith(".") || host.startsWith("-")) return null;
        }
        if (!port.matches("[0-9]{1,5}")) return null;
        int number = Integer.parseInt(port);
        if (number < 1 || number > 65535) return null;
        return new Address(host.toLowerCase(Locale.ROOT), number);
    }

    /** The proxy the In-app proxy row names, or null for none. */
    @Nullable
    static Address saved() {
        return parse(Settings.IN_APP_PROXY.get());
    }

    /**
     * Called as each of Facebook's processes starts ({@link SettingsEntry#onApplicationCreate}).
     * With a proxy saved, sets the properties and keeps them through the phone's proxy changes.
     */
    public static void start(Context context) {
        try {
            Address proxy = saved();
            if (proxy == null) return;
            setProperties(proxy);
            watch(context);
            Logger.printInfo(() -> "In-app proxy " + proxy);
        } catch (Throwable t) {
            Logger.printException(() -> "In-app proxy failed to start", t);
        }
    }

    /** Points Java's and Tigon's proxy properties at [proxy]. */
    static void setProperties(Address proxy) {
        String port = String.valueOf(proxy.port);
        System.setProperty("http.proxyHost", proxy.host);
        System.setProperty("http.proxyPort", port);
        System.setProperty("https.proxyHost", proxy.host);
        System.setProperty("https.proxyPort", port);
        if (System.getProperty("http.nonProxyHosts") == null) System.setProperty("http.nonProxyHosts", LOCAL_HOSTS);
    }

    /** Whether the properties point at [proxy] now. */
    static boolean inPlace(Address proxy) {
        String port = String.valueOf(proxy.port);
        return proxy.host.equals(System.getProperty("https.proxyHost"))
                && port.equals(System.getProperty("https.proxyPort"))
                && proxy.host.equals(System.getProperty("http.proxyHost"))
                && port.equals(System.getProperty("http.proxyPort"));
    }

    /** Sets the properties again after each change of the phone's proxy, once per process. */
    private static void watch(Context context) {
        if (!watching.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Handler main = new Handler(Looper.getMainLooper());
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context received, Intent intent) {
                for (long delay : REAPPLY_AFTER_MS) main.postDelayed(InAppProxy::reapply, delay);
            }
        };
        IntentFilter filter = new IntentFilter(PROXY_CHANGE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            watching.set(false);
            Logger.printException(() -> "In-app proxy can't follow the phone's proxy changes", t);
        }
    }

    /** Puts the saved proxy back if Android's proxy change took it off. */
    static void reapply() {
        Address proxy = saved();
        if (proxy == null || inPlace(proxy)) return;
        setProperties(proxy);
        Logger.printInfo(() -> "In-app proxy set again after a proxy change");
    }

    /** For tests: forget the receiver, so the next start registers one on the test's context. */
    static void resetForTests() {
        watching.set(false);
    }
}
