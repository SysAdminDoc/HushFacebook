/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What Facebook's own browser asks, through the Open links in external browser patch, before it
 * builds either of its link history writers.
 *
 * <p>The browser has two. One records the links you open with when each page started, became usable
 * and finished loading. The other records each page's address and title. When Enhanced browsing is on
 * for your account, the second one's records are saved to Facebook as your link history and the
 * first one's go out as navigation events. When it's off, the browser still builds them whenever its
 * launch and Facebook's server config say so, and reports each write it turns away. Each writer's
 * factory already answers null when the feature is off for that browser, so the patch has it answer
 * null on a yes from {@link #hold} too, and the writer never exists.
 *
 * <p>The page data reaches Facebook through one upload, the link history save request, which other
 * parts of the browser can call too. It asks {@link #holdUpload} first, and a yes returns before
 * anything is sent. Nothing waits for that upload: its answer only goes to Facebook's own logging.
 *
 * <p>When you fill in a form on a page, the browser also logs two autofill reports to Facebook's
 * analytics: one with the page's address and host, the form's fields and the ad the page came from,
 * and a first-party data event. Each asks {@link #holdAutofillReport} first, and a yes returns before
 * the event is built. Autofill itself keeps what you declined in its own preferences and never reads
 * the reports, so it works the same either way.
 *
 * <p>Everything else the browser reports to Facebook's analytics goes through one event logger: the
 * pages you land on and how far you scroll, login and payment forms it finds, checkouts, the ad
 * pixels and structured data on a page, and more. Each of those events asks Facebook's sampling
 * whether to send, and then asks {@link #sendBrowserEvent}, whose no skips that one event. What the
 * logger does besides sending, handing page data to the link history writers and its on-device
 * signals among it, never asks, so the browser works the same either way.
 *
 * <p>An ad opened in the browser also reports through an ad event callback: how you took in the ad,
 * and its call button being shown and tapped, with the ad's and Page's ids and tracking codes. That
 * callback only logs (nothing in it opens the call or its dialog), and it asks {@link #holdAdEvent}
 * first, so a yes returns before any of it.
 *
 * <p>The browser also reports pages to Facebook's ad integrity checks, through a callback that
 * otherwise runs autofill, translation and the login dialog. Three of its events only report: the
 * page's data handed to the cloaking check, the redirect chain with its tracking codes, and each
 * hop's domain with the page's images, their sizes and hashes of its text and layout. That callback
 * asks {@link #holdIntegrityReport} with the event's name first, and a yes for one of those three
 * returns before it. Every other event runs as before.
 *
 * <p>It fails open: the switch off, a pause, settings that aren't ready yet, or a failure in here,
 * and the browser builds its writers and sends as it would have.
 */
public final class LinkHistory {
    /** The diagnostic counter route: each writer and upload the browser went to make, and the ones held back. */
    static final String ROUTE = "Link history";

    /** What a writer that wasn't built is counted under. */
    static final String WRITERS = "Link history writers";

    /** What an upload that wasn't sent is counted under. */
    static final String UPLOADS = "Link history uploads";

    /** What an autofill report that wasn't logged is counted under. */
    static final String AUTOFILL_REPORTS = "Autofill reports";

    /** What a browser analytics event that wasn't sent is counted under. */
    static final String BROWSER_EVENTS = "Browser events";

    /** What an ad event that wasn't logged is counted under. */
    static final String AD_EVENTS = "Browser ad events";

    /** What an integrity report that wasn't sent is counted under. */
    static final String INTEGRITY_REPORTS = "Browser integrity reports";

    private LinkHistory() {
    }

    /**
     * Injection point, first thing in each link history writer's factory. True makes the factory
     * answer null, its own answer when the feature is off. Never throws.
     */
    public static boolean hold() {
        return held(WRITERS, "link history writer", "a writer");
    }

    /**
     * Injection point, first thing in the link history upload. True makes it return without sending
     * the page's address, title and times. Never throws.
     */
    public static boolean holdUpload() {
        return held(UPLOADS, "link history upload", "an upload");
    }

    /**
     * Injection point, first thing in each of the browser's two autofill reports. True makes it
     * return before the page's address, the form's fields and the ad are logged. Never throws.
     */
    public static boolean holdAutofillReport() {
        return held(AUTOFILL_REPORTS, "autofill report", "an autofill report");
    }

    /**
     * Injection point, right after each sampling check in the browser's event logger, with what the
     * check answered. Answers whether the event goes out: never while held, otherwise what Facebook's
     * sampling said. Only an event sampling let through is asked about or counted. Never throws.
     */
    public static boolean sendBrowserEvent(int sampled) {
        return sampled != 0 && !held(BROWSER_EVENTS, "browser event", "a browser event");
    }

    /**
     * Injection point, first thing in the browser's ad event callback. True makes it return before
     * the ad's attention and call button events are logged. Never throws.
     */
    public static boolean holdAdEvent() {
        return held(AD_EVENTS, "ad event", "an ad event");
    }

    /**
     * Injection point, first thing in the browser's integrity callback, with the event's name. True
     * makes it return before an integrity report goes out. Any other event isn't asked about or
     * counted, and runs as before. Never throws.
     */
    public static boolean holdIntegrityReport(String event) {
        return isIntegrityReport(event) && held(INTEGRITY_REPORTS, "integrity report", "an integrity report");
    }

    /** The callback's events that only report the page to Facebook's ad integrity checks. */
    static boolean isIntegrityReport(String event) {
        return "INTEGRITY_LOGGER".equals(event) || "INTEGRITY_SIGNALS_EVENT".equals(event)
                || "INTEGRITY_OUTBOUND_CLICK_EVENT".equals(event);
    }

    private static boolean held(String counted, String site, String what) {
        try {
            HookStatus.invoked(FamilyNames.EXTERNAL_BROWSER);
            FeedFilterCounters.sawList(ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HOLD_LINK_HISTORY.get()) return false;
            FeedFilterCounters.removed(ROUTE, 1, counted);
            Logger.printDebug(() -> "Link history: held back " + what);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXTERNAL_BROWSER, site, failure);
            return false;
        }
    }
}
