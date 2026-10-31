/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextImpl;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.download.ReelDownload;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.shared.settings.preference.LogBufferManagerExportTest;

/**
 * Issues #16 and #18 came without a report: with nothing logged and no hook missing anything, the
 * export said there was nothing to report and told the reader to turn on Debug logging. A report
 * asked for now always carries the facts a maintainer asks for first: the app's package, version
 * code, Android API and profile, ABI, Hushfacebook's version, whether it's paused, which patches
 * the build carries and what each hook has counted. Debug logging stays off here, both exports
 * are read, and nothing is sent anywhere.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SupportReportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PatchFamily.registerDiagnostics();
        BaseSettings.DEBUG.save(false);
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    /** The clipboard copy and the saved file of one report, in that order. */
    private static String[] bothExports() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        LogBufferManager.exportToClipboard();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        String copied = String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText());

        LogBufferManagerExportTest.Downloads downloads = Robolectric.setupContentProvider(
                LogBufferManagerExportTest.Downloads.class, MediaStore.AUTHORITY);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, 1), body);
        LogBufferManager.exportToFile();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        return new String[]{copied, body.toString(StandardCharsets.UTF_8.name())};
    }

    /** What every report asked for carries, whatever went right or wrong. */
    private static void assertBuildFacts(String report) {
        String packageName = RuntimeEnvironment.getApplication().getPackageName();
        assertTrue(report, report.startsWith("MORPHE DIAGNOSTIC REPORT\n"));
        assertTrue("no package or version code: " + report,
                Pattern.compile("\napp: " + Pattern.quote(packageName) + " \\S+ \\(\\d+\\)\n").matcher(report).find());
        assertTrue("no API level or profile: " + report, report.contains(
                "\nandroid: API " + Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + "), user "));
        assertTrue("no ABI: " + report, report.contains("\nabi: app "));
        assertTrue("no Hushfacebook version: " + report, report.contains("\nmorphe: "));
        assertTrue("no honest build identity: " + report, report.contains("\npatch_build: unknown\n"));
        assertTrue("Debug logging's state is missing: " + report, report.contains("\ndebug_logging: off\n"));
        assertTrue("no patch list: " + report, report.contains("\n[PATCHES]\n"));
        assertTrue("no supported-link state: " + report, report.contains("\n[SUPPORTED LINKS]\n"));
        assertTrue("no network use: " + report, report.contains("\n[NETWORK]\n"));
        if (Build.VERSION.SDK_INT == 30) assertTrue(report, report.contains("availability: not_reported (API below 31)"));
        assertTrue(report, report.contains("\nDownload any reel: on (hushfacebook_download_reels=on, hushfacebook_show_reel_download_button=on, hushfacebook_reel_cast_button=off, hushfacebook_copy_reel_caption=on)\n"));
        assertFalse("events without Debug logging: " + report, report.contains("[SELECTED EVENTS]"));
    }

    @Test
    public void aHealthyRunStillGivesAReport() throws Exception {
        for (String report : bothExports()) {
            assertBuildFacts(report);
            assertTrue("the Pause state is missing: " + report, report.contains("\nhushfacebook: running\n"));
            assertFalse("a healthy run reported hook findings: " + report, report.contains("[HOOK STATUS]"));
        }
    }

    @Test @Config(sdk = 31)
    public void bothExportsContainSortedDomainsWithoutChangingTheAppsLinkOwnership() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Map<String, Integer> hosts = new LinkedHashMap<>();
        hosts.put("www.facebook.com", DomainVerificationUserState.DOMAIN_STATE_SELECTED);
        hosts.put("m.facebook.com", DomainVerificationUserState.DOMAIN_STATE_NONE);
        hosts.put("z\u0301.facebook.com", DomainVerificationUserState.DOMAIN_STATE_SELECTED);
        DomainVerificationUserState user = ReflectionHelpers.callConstructor(DomainVerificationUserState.class,
                ClassParameter.from(UUID.class, UUID.randomUUID()), ClassParameter.from(String.class, context.getPackageName()),
                ClassParameter.from(UserHandle.class, Process.myUserHandle()), ClassParameter.from(boolean.class, false),
                ClassParameter.from(Map.class, hosts));
        Class<?> binder = Class.forName("android.content.pm.verify.domain.IDomainVerificationManager");
        AtomicInteger reads = new AtomicInteger();
        Object service = Proxy.newProxyInstance(binder.getClassLoader(), new Class<?>[]{binder}, (proxy, method, args) -> {
            assertTrue("ownership-changing or unrelated API call", method.getName().equals("getDomainVerificationUserState"));
            assertTrue("another package was queried", context.getPackageName().equals(args[0]));
            reads.incrementAndGet();
            return user;
        });
        DomainVerificationManager manager = ReflectionHelpers.callConstructor(DomainVerificationManager.class,
                ClassParameter.from(Context.class, context), ClassParameter.from(binder, service));
        ShadowContextImpl shadow = Shadow.extract(RuntimeEnvironment.getApplication().getBaseContext());
        shadow.setSystemService(Context.DOMAIN_VERIFICATION_SERVICE, manager);
        PatchFamily.registerDiagnostics();
        for (String report : bothExports()) {
            assertBuildFacts(report);
            assertTrue(report, report.contains("\nlink_handling_allowed: false\nm.facebook.com -> none\nwww.facebook.com -> selected\n"));
            assertTrue("combining-mark domain omitted or redacted", report.contains("\nz\u0301.facebook.com -> selected\n"));
            assertTrue("duplicate supported-link section", report.indexOf("[SUPPORTED LINKS]") == report.lastIndexOf("[SUPPORTED LINKS]"));
            assertFalse(report.contains("http://") || report.contains("https://") || report.contains("certificate:"));
        }
        BaseSettings.DEBUG_LOG_FILTERS.save("downloads");
        LogBufferManager.persistCrashReport(context, "java.io.IOException: " + "long trace ".repeat(10_000));
        for (String report : bothExports()) {
            assertTrue("event filters or a long crash hid link state", report.contains(
                    "\nlink_handling_allowed: false\nm.facebook.com -> none\nwww.facebook.com -> selected\n"));
            assertFalse("patch sections ignored the selected filter", report.contains("[PATCHES]"));
            assertTrue("combining-mark domain omitted or redacted", report.contains("\nz\u0301.facebook.com -> selected\n"));
            assertTrue("link state follows a potentially truncated crash", report.indexOf("[SUPPORTED LINKS]") < report.indexOf("[LATEST JAVA CRASH]"));
        }
        assertTrue("both report paths must query the live state", reads.get() >= 2);
    }

    @Test
    public void android30KeepsTheNotReportedStateWithAnEventsOnlyFilter() throws Exception {
        BaseSettings.DEBUG_LOG_FILTERS.save("downloads");
        for (String report : bothExports()) {
            assertTrue(report.contains("[SUPPORTED LINKS]\navailability: not_reported (API below 31)"));
            assertFalse(report.contains("[PATCHES]"));
        }
    }

    /**
     * #18's case: the Reels viewer drew the sidebar the button can't go in, three times, and
     * nothing was logged. The report says so without Debug logging.
     */
    @Test
    public void aMissingButtonRunSaysWhichSidebarItWas() throws Exception {
        for (int i = 0; i < 3; i++) ReelDownload.otherSidebarBuilt();

        for (String report : bothExports()) {
            assertBuildFacts(report);
            assertTrue(report, report.contains("\n[HOOK STATUS]\nDownload any reel: invoked 0, 0 found, 0 missing. "
                    + "Counted: FbShorts sidebar, which gets no Download 3\n"));
        }
    }

    @Test
    public void aPausedRunSaysSo() throws Exception {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        for (String report : bothExports()) {
            assertTrue(report, report.contains("\nhushfacebook: paused (switch)"));
            assertTrue(report, report.contains("\nDownload any reel: disabled while paused (saved hushfacebook_download_reels=on, hushfacebook_show_reel_download_button=on, hushfacebook_reel_cast_button=off, hushfacebook_copy_reel_caption=on)\n"));
        }
    }
}
