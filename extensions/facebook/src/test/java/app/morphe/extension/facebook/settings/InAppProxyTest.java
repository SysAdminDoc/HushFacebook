/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * In-app proxy (#504): a saved host:port points the proxy properties Facebook's network stack reads
 * on each request at it, in every process, and puts them back after Android resets them on a change
 * of the phone's proxy. Blank, or an address it can't use, leaves the phone's own proxy alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class InAppProxyTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String[] KEYS = {
            "http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts"};
    private final Map<String, String> before = new HashMap<>();

    @Before
    public void noProxy() {
        for (String key : KEYS) {
            before.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        InAppProxy.resetForTests();
    }

    @After
    public void restore() {
        for (String key : KEYS) {
            String value = before.get(key);
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        }
        Settings.IN_APP_PROXY.resetToDefault();
        InAppProxy.resetForTests();
    }

    @Test
    public void aSavedProxyCarriesEveryConnection() {
        Settings.IN_APP_PROXY.save("127.0.0.1:10809");
        InAppProxy.start(RuntimeEnvironment.getApplication());
        assertEquals("127.0.0.1", System.getProperty("http.proxyHost"));
        assertEquals("10809", System.getProperty("http.proxyPort"));
        assertEquals("127.0.0.1", System.getProperty("https.proxyHost"));
        assertEquals("10809", System.getProperty("https.proxyPort"));
        assertEquals("a local server stays off the proxy", InAppProxy.LOCAL_HOSTS, System.getProperty("http.nonProxyHosts"));
    }

    @Test
    public void blankOrAnAddressItCantUseLeavesThePhonesProxyAlone() {
        for (String saved : new String[] {"", "socks5://127.0.0.1:1080", "vless://id@host:443", "127.0.0.1", "host:0"}) {
            System.setProperty("https.proxyHost", "10.1.1.1");
            System.setProperty("https.proxyPort", "3128");
            Settings.IN_APP_PROXY.save(saved);
            InAppProxy.start(RuntimeEnvironment.getApplication());
            assertEquals(saved, "10.1.1.1", System.getProperty("https.proxyHost"));
            assertEquals(saved, "3128", System.getProperty("https.proxyPort"));
            assertNull(saved, System.getProperty("http.proxyHost"));
        }
    }

    @Test
    public void theProxyComesBackAfterAndroidResetsItOnAProxyChange() {
        Application app = RuntimeEnvironment.getApplication();
        Settings.IN_APP_PROXY.save("10.0.2.2:8899");
        InAppProxy.start(app);
        // What ActivityThread.updateHttpProxy does in each process when the phone's proxy changes.
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.setProperty("https.proxyHost", "192.168.1.5");
        System.setProperty("https.proxyPort", "3128");
        app.sendBroadcast(new Intent(InAppProxy.PROXY_CHANGE));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals("10.0.2.2", System.getProperty("https.proxyHost"));
        assertEquals("8899", System.getProperty("https.proxyPort"));
        assertEquals("10.0.2.2", System.getProperty("http.proxyHost"));
        assertEquals("8899", System.getProperty("http.proxyPort"));
    }

    @Test
    public void aCopiedAddressIsReadTheWayItsMeant() {
        Map<String, String> read = new HashMap<>();
        read.put("127.0.0.1:10809", "127.0.0.1:10809");
        read.put("  http://LocalHost:2080/ ", "localhost:2080");
        read.put("HTTP://proxy.example.net:3128", "proxy.example.net:3128");
        read.put("[::1]:8080", "[::1]:8080");
        read.put("[2001:DB8::5]:65535", "[2001:db8::5]:65535");
        for (Map.Entry<String, String> entry : read.entrySet()) {
            InAppProxy.Address proxy = InAppProxy.parse(entry.getKey());
            assertEquals(entry.getKey(), entry.getValue(), proxy == null ? null : proxy.toString());
        }
        for (String unusable : new String[] {null, "", "   ", "127.0.0.1", ":8080", "host:", "host:65536", "host:-1",
                "host:80a", "::1:8080", "[::1]", "[host]:80", "socks5://127.0.0.1:1080", "https://host:443",
                "user:pass@host:80", "a host:80", ".host:80", "-host:80"}) {
            assertNull(unusable, InAppProxy.parse(unusable));
        }
    }

    @Test
    public void theRowSaysWhereFacebooksConnectionsGo() {
        assertTrue(HushfacebookPreferenceFragment.proxySummary("").startsWith("Facebook connects the way your phone does."));
        assertTrue(HushfacebookPreferenceFragment.proxySummary("socks5://h:1").startsWith("Facebook connects the way"));
        String set = HushfacebookPreferenceFragment.proxySummary("127.0.0.1:10809");
        assertTrue(set, set.startsWith("Facebook's connections go through the proxy at "));
        assertTrue(set, set.contains("127.0.0.1:10809"));
    }
}
