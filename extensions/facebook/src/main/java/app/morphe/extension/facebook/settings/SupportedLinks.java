/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Which of Facebook's web addresses Android sends to this app, read for this app alone.
 *
 * <p>Android verifies an app's web links against the signing key the site names, so a re-signed
 * Facebook loses Meta's verification and facebook.com links can stop opening in it (Morphe Manager
 * #1028). From Android 12 a person can select the addresses for the app in its Open by default
 * page, and Android says which are selected. That sends the links here again without making the
 * build verified. Android 11 says nothing about it, so there the row only opens the app's page.
 */
final class SupportedLinks {
    enum State { VERIFIED, SELECTED, SOME, NONE, DISABLED, UNKNOWN, NOT_REPORTED }

    /**
     * Meta App Manager, which comes on many phones (#30). Android verifies it for facebook.com with
     * Meta's key, and while it holds an address Android won't let a person select that address for
     * another app: the switch on this app's Open by default page turns itself back off. Facebook's
     * manifest queries it by name, so this app can see whether it's there.
     */
    static final String APP_MANAGER = "com.facebook.appmanager";

    /**
     * Messenger, verified for facebook.com, www.facebook.com, m.me and www.m.me (#78). Messenger
     * 582's manifest also lists fb.me and www.fb.me (paths /msg), but only in a filter without
     * autoVerify, so Android never verifies Messenger for them and they stay this app's to select.
     */
    static final String MESSENGER = "com.facebook.orca";

    /** Instagram, verified for facebook.com, www.facebook.com and m.facebook.com (#78). */
    static final String INSTAGRAM = "com.instagram.android";

    /**
     * Meta's apps that Android verifies for some of Facebook's addresses with Meta's key. While one
     * holds an address, its switch on this app's Open by default page turns itself back off, so the
     * app has to let go first: its own Open supported links off. With Messenger and Instagram that
     * was every switch that wouldn't stay on (#78). Facebook's manifest queries App Manager by name,
     * and its bare MAIN intent query covers Messenger and Instagram, so this app can see all three.
     */
    enum Holder {
        APP_MANAGER(SupportedLinks.APP_MANAGER, "meta_app_manager", "action_app_manager_links"),
        MESSENGER(SupportedLinks.MESSENGER, "messenger", "action_messenger_links",
                "facebook.com", "www.facebook.com", "m.me", "www.m.me"),
        INSTAGRAM(SupportedLinks.INSTAGRAM, "instagram", "action_instagram_links",
                "facebook.com", "www.facebook.com", "m.facebook.com");

        final String packageName;
        final String reportKey;
        final String rowKey;
        /** The addresses it holds that this app declares too; empty for App Manager, read as any (#30). */
        final Set<String> hosts;

        Holder(String packageName, String reportKey, String rowKey, String... hosts) {
            this.packageName = packageName;
            this.reportKey = reportKey;
            this.rowKey = rowKey;
            this.hosts = new HashSet<>(Arrays.asList(hosts));
        }

        String title() {
            switch (this) {
                case MESSENGER: return L10n.t("Messenger");
                case INSTAGRAM: return L10n.t("Instagram");
                default: return L10n.t("Meta App Manager");
            }
        }

        /** What the row says while an address it may hold doesn't open here. */
        String holding() {
            switch (this) {
                case MESSENGER:
                    return L10n.t("Messenger can claim facebook.com and m.me links for itself, which turns this app's link switches "
                            + "back off. Tap, turn off Open supported links there (on newer Android, pick In your browser), then "
                            + "recheck Supported links above.");
                case INSTAGRAM:
                    return L10n.t("Instagram can claim facebook.com links for itself, which turns this app's link switches back off. "
                            + "Tap, turn off Open supported links there (on newer Android, pick In your browser), then recheck "
                            + "Supported links above.");
                default:
                    return L10n.t("Meta App Manager can claim Facebook's web addresses for itself, so their links skip this app. Tap, "
                            + "turn off Open supported links there, then recheck Supported links above.");
            }
        }

        /** What the row says once the addresses it may hold open here, while others still don't. */
        String released() {
            switch (this) {
                case MESSENGER: return L10n.t("facebook.com and m.me links open here now.");
                case INSTAGRAM: return L10n.t("facebook.com links open here now.");
                default: return L10n.t("Facebook's web addresses open here now.");
            }
        }

        String notOpened() {
            switch (this) {
                case MESSENGER:
                    return L10n.t("Messenger's settings didn't open. Open App info from Messenger's icon, then Open by default.");
                case INSTAGRAM:
                    return L10n.t("Instagram's settings didn't open. Open App info from Instagram's icon, then Open by default.");
                default:
                    return L10n.t("Meta App Manager's settings didn't open. Find it in Android's app list with system apps "
                            + "shown, then Open by default.");
            }
        }
    }

    static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override public String title() { return "SUPPORTED LINKS"; }
        @Override public List<String> lines() { return reportLines(Utils.getContext()); }
        @Override public boolean isAppState() { return true; }
        @Override public Set<String> declaredDomainHosts(List<String> lines) {
            Set<String> hosts = new HashSet<>();
            for (String line : lines) {
                int arrow = line.lastIndexOf(" -> ");
                if (arrow >= 0) hosts.add(line.substring(0, arrow));
            }
            return hosts;
        }
    };

    private SupportedLinks() {}

    /** One Android answer for the main row and every retained holder row. */
    static final class Snapshot {
        final State state;
        private final Set<String> openHosts = new HashSet<>();

        private Snapshot(State state) {
            this.state = state;
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private Snapshot(boolean allowed, @Nullable Map<String, Integer> hosts) {
            state = state(allowed, hosts);
            if (allowed && hosts != null) {
                for (Map.Entry<String, Integer> host : hosts.entrySet()) {
                    Integer value = host.getValue();
                    if (value != null && (value == DomainVerificationUserState.DOMAIN_STATE_SELECTED
                            || value == DomainVerificationUserState.DOMAIN_STATE_VERIFIED)) {
                        openHosts.add(host.getKey());
                    }
                }
            }
        }
    }

    /** What Android says about this app's links, or UNKNOWN when it couldn't be read. */
    static Snapshot read(@Nullable Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return userState(context);
        return new Snapshot(State.NOT_REPORTED);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static Snapshot userState(@Nullable Context context) {
        try {
            DomainVerificationManager manager = context == null ? null : context.getSystemService(DomainVerificationManager.class);
            if (manager == null) return new Snapshot(State.UNKNOWN);
            DomainVerificationUserState user = manager.getDomainVerificationUserState(context.getPackageName());
            if (user == null) return new Snapshot(State.UNKNOWN);
            Map<String, Integer> hosts = user.getHostToStateMap();
            return new Snapshot(user.isLinkHandlingAllowed(), hosts == null ? null : new HashMap<>(hosts));
        } catch (PackageManager.NameNotFoundException | RuntimeException unreadable) {
            Logger.printInfo(() -> "Supported links unreadable: " + unreadable.getClass().getSimpleName());
            return new Snapshot(State.UNKNOWN);
        }
    }

    /**
     * Only manifest domains and their per-user selection, never visited links or verifier IDs, then
     * whether each of Meta's apps that can hold them is on the phone.
     */
    static List<String> reportLines(Context context) {
        List<String> lines = new ArrayList<>(domainLines(context));
        for (Holder holder : Holder.values()) lines.add(holder.reportKey + ": " + appState(context, holder.packageName));
        return lines;
    }

    private static List<String> domainLines(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Arrays.asList("availability: not_reported (API below 31)",
                    "link_handling_allowed: not_reported", "domains: not_reported");
        }
        try {
            DomainVerificationManager manager = context == null ? null : context.getSystemService(DomainVerificationManager.class);
            DomainVerificationUserState user = manager == null ? null : manager.getDomainVerificationUserState(context.getPackageName());
            if (user != null) {
                List<String> lines = new ArrayList<>();
                lines.add("availability: reported");
                lines.add("link_handling_allowed: " + user.isLinkHandlingAllowed());
                Map<String, Integer> hosts = user.getHostToStateMap();
                if (hosts == null) lines.add("domains: unknown");
                else if (hosts.isEmpty()) lines.add("domains: none_declared");
                else {
                    Map<String, String> sorted = new TreeMap<>();
                    boolean invalid = false;
                    for (Map.Entry<String, Integer> host : hosts.entrySet()) {
                        String name = host.getKey();
                        // Keep Unicode/wildcard manifest hosts, but refuse URLs, userinfo and line injection.
                        if (name == null || name.isEmpty() || !name.matches("[\\p{L}\\p{M}\\p{N}_.*-]+")) {
                            invalid = true;
                            continue;
                        }
                        Integer value = host.getValue();
                        String state = "unknown";
                        if (value != null) {
                            switch (value) {
                                case DomainVerificationUserState.DOMAIN_STATE_VERIFIED: state = "verified"; break;
                                case DomainVerificationUserState.DOMAIN_STATE_SELECTED: state = "selected"; break;
                                case DomainVerificationUserState.DOMAIN_STATE_NONE: state = "none"; break;
                            }
                        }
                        sorted.put(name, state);
                    }
                    if (invalid) lines.add("domains: unknown (invalid host data)");
                    for (Map.Entry<String, String> host : sorted.entrySet()) lines.add(host.getKey() + " -> " + host.getValue());
                }
                return lines;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException unreadable) {
            Logger.printInfo(() -> "Supported link report unreadable: " + unreadable.getClass().getSimpleName());
        }
        return Arrays.asList("availability: unknown", "link_handling_allowed: unknown", "domains: unknown");
    }

    /** The whole app's answer from each address's; a state this code doesn't know is UNKNOWN. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static State state(boolean allowed, Map<String, Integer> hosts) {
        if (!allowed) return State.DISABLED;
        if (hosts == null || hosts.isEmpty()) return State.UNKNOWN;
        int verified = 0;
        int open = 0;
        for (Integer state : hosts.values()) {
            if (state == null) return State.UNKNOWN;
            switch (state) {
                case DomainVerificationUserState.DOMAIN_STATE_VERIFIED:
                    verified++;
                    open++;
                    break;
                case DomainVerificationUserState.DOMAIN_STATE_SELECTED:
                    open++;
                    break;
                case DomainVerificationUserState.DOMAIN_STATE_NONE:
                    break;
                default:
                    return State.UNKNOWN;
            }
        }
        if (verified == hosts.size()) return State.VERIFIED;
        if (open == hosts.size()) return State.SELECTED;
        return open == 0 ? State.NONE : State.SOME;
    }

    static String summary(State state) {
        switch (state) {
            case VERIFIED:
                return L10n.t("Android verified this app for Facebook's web addresses, so their links open here.");
            case SELECTED:
                return L10n.t("Facebook's web addresses are selected for this app in Android's settings, so their links open here.");
            case SOME:
                return L10n.t("Only some of Facebook's web addresses are selected for this app, and links to the rest open "
                        + "elsewhere. Tap to select them in Android's settings.");
            case NONE:
                return L10n.t("None of Facebook's web addresses are selected for this app, so their links open elsewhere. "
                        + "Tap to select them in Android's settings.");
            case DISABLED:
                return L10n.t("Opening supported links is off for this app in Android's settings. Tap to turn it on.");
            case NOT_REPORTED:
                return L10n.t("Android 11 doesn't say which links open here. Tap to open this app's settings, then Open by default.");
            default:
                return L10n.t("Android didn't say which links open here. Tap to check in Android's settings.");
        }
    }

    /** Whether [holder] is installed and enabled for this user. Never throws. */
    static boolean isOn(@Nullable Context context, Holder holder) {
        return "enabled".equals(appState(context, holder.packageName));
    }

    /** An app for this user: enabled, disabled, absent or unknown. Never throws. */
    private static String appState(@Nullable Context context, String packageName) {
        if (context == null) return "unknown";
        try {
            return context.getPackageManager().getApplicationInfo(packageName, 0).enabled ? "enabled" : "disabled";
        } catch (PackageManager.NameNotFoundException absent) {
            return "absent";
        } catch (RuntimeException unreadable) {
            return "unknown";
        }
    }

    /**
     * Whether [holder]'s row belongs on the page: it's on the phone, and Facebook's addresses don't
     * all open here, or Android 11 doesn't say whether they do. Once its own addresses open here,
     * the row isn't needed, so Instagram isn't sent for once only m.me is left.
     */
    static boolean mayHoldLinks(Holder holder, Snapshot snapshot, boolean on) {
        State state = snapshot.state;
        if (!on || !(state == State.NONE || state == State.SOME || state == State.NOT_REPORTED)) return false;
        return holder.hosts.isEmpty() || !snapshot.openHosts.containsAll(holder.hosts);
    }

    /**
     * The row's summary, read again on the way back from Android's pages: the row stays until the
     * page is rebuilt, so once the addresses its app may hold open here it says so.
     */
    static String holderSummary(Holder holder, Snapshot snapshot) {
        boolean allOpen = snapshot.state == State.VERIFIED || snapshot.state == State.SELECTED;
        boolean holderOpen = holder.hosts.isEmpty() ? allOpen : snapshot.openHosts.containsAll(holder.hosts);
        if (holderOpen) {
            return allOpen ? L10n.t("Facebook's web addresses open here now.") : holder.released();
        }
        return holder.holding();
    }

    /**
     * Android's pages for this app's links, to try in order: Open by default from Android 12, and the
     * app's own page, which leads there, as the fallback and as all Android 11 has.
     */
    static List<Intent> settingsIntents(Context context) {
        return linkPages(context.getPackageName());
    }

    /** The same pages for [holder]; Meta App Manager is a system app and so hidden from most app lists. */
    static List<Intent> holderIntents(Holder holder) {
        return linkPages(holder.packageName);
    }

    private static List<Intent> linkPages(String packageName) {
        Uri app = Uri.fromParts("package", packageName, null);
        List<Intent> intents = new ArrayList<>(2);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            intents.add(new Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, app));
        }
        intents.add(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
        return intents;
    }
}
