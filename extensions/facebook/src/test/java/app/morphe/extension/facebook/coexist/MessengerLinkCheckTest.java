/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.coexist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The Messenger link test: it asks for the session, builds the flag reader from it and runs both
 * reads, and says for each whether it answered, failed or gave nothing. Neither the toast nor the
 * log ever carries what a read answered, or a failure's message.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MessengerLinkCheckTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** What a read answers here. Facebook's answer carries a flag and where it came from. */
    private static final Object ANSWER = new Object() {
        @Override
        public String toString() {
            return "Yc9(flag=private-value, source=private-source)";
        }
    };

    private static final class FakeReads implements MessengerLinkCheck.Reads {
        boolean filled = true;
        Object session = new Object();
        Object reader = new Object();
        Object optOut = ANSWER;
        RuntimeException optOutFailure;
        Object triggered = ANSWER;
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean filled() {
            return filled;
        }

        @Override
        public Object session(Context context) {
            calls.add("session");
            return session;
        }

        @Override
        public Object reader(Object forSession) {
            calls.add(forSession == session ? "reader for the session" : "reader for something else");
            return reader;
        }

        @Override
        public Object optOut(Object forReader) {
            calls.add(forReader == reader ? "opt-out" : "opt-out on something else");
            if (optOutFailure != null) throw optOutFailure;
            return optOut;
        }

        @Override
        public Object triggered(Object forReader) {
            calls.add(forReader == reader ? "triggered" : "triggered on something else");
            return triggered;
        }
    }

    private FakeReads reads;
    private Context context;

    @Before
    public void start() {
        reads = new FakeReads();
        MessengerLinkCheck.access = reads;
        context = RuntimeEnvironment.getApplication();
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() {
        MessengerLinkCheck.forget();
        BaseSettings.DEBUG.resetToDefault();
    }

    @Test
    public void theReportGoesToTheRowWholeAndNoToastShows() throws Exception {
        List<String> shown = new ArrayList<>();
        ShadowToast.reset();
        MessengerLinkCheck.start(context, shown::add);
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("Opt-out read: answered. Triggered read: answered."), shown);
        assertNull("the report went to a toast as well", ShadowToast.getLatestToast());
    }

    @Test
    public void bothReadsRunOnTheSessionsReaderAndSayTheyAnswered() {
        assertEquals("Opt-out read: answered. Triggered read: answered.", MessengerLinkCheck.run(context));
        assertEquals(List.of("session", "reader for the session", "opt-out", "triggered"), reads.calls);
    }

    @Test
    public void aReadWithNothingAndAFailingReadAreEachSaidAndTheOtherStillRuns() {
        reads.optOutFailure = new SecurityException("Permission denial: private-message");
        reads.triggered = null;
        assertEquals("Opt-out read: failed (" + L10n.isolate("SecurityException") + "). Triggered read: no answer.",
                MessengerLinkCheck.run(context));
        assertEquals(List.of("session", "reader for the session", "opt-out", "triggered"), reads.calls);
        String log = LogBufferManager.buildExportText();
        assertTrue(log, log.contains("Messenger link test: the opt-out read failed with java.lang.SecurityException"));
        assertFalse("a failure's message reached the log", log.contains("private-message"));
    }

    @Test
    public void withoutASignedInSessionNoReaderIsMade() {
        reads.session = null;
        assertEquals("No signed-in account to test with. Sign in to Facebook and try again.", MessengerLinkCheck.run(context));
        assertEquals(List.of("session"), reads.calls);
    }

    @Test
    public void aReaderThatCantBeMadeStopsTheTestBeforeAnyRead() {
        reads.reader = null;
        assertEquals("The Messenger link test couldn't start. Try again.", MessengerLinkCheck.run(context));
        assertEquals(List.of("session", "reader for the session"), reads.calls);
    }

    @Test
    public void noAnswerReachesTheLog() {
        String report = MessengerLinkCheck.run(context);
        String log = LogBufferManager.buildExportText();
        for (String text : List.of(report, log)) {
            assertFalse(text, text.contains("private-value"));
            assertFalse(text, text.contains("private-source"));
        }
    }

    /** Unpatched, or with the patch unable to fill the stubs, there's no test and no row. */
    @Test
    public void unfilledStubsRunNothing() {
        reads.filled = false;
        assertFalse(MessengerLinkCheck.available());
        assertEquals("This build can't run the Messenger link test.", MessengerLinkCheck.run(context));
        assertTrue(reads.calls.isEmpty());

        MessengerLinkCheck.forget();
        assertFalse("the unpatched stubs claim a filled test", MessengerLinkCheck.available());
    }
}
