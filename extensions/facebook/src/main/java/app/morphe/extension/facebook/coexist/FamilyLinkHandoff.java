/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.coexist;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Opens an Instagram or Threads link in the app it belongs to after Facebook's family intent scope
 * dropped it (#287).
 *
 * <p>Facebook sends an Instagram link from a post or a comment through its family scope, which keeps
 * only target apps whose signer Meta trusts. A patched Instagram or Threads carries the user's own
 * Manager key, so the scope keeps nothing, logs "Components matching the intent were found but none
 * match the given scope" and answers null, and the tap does nothing. The scope's null goes through
 * {@link #publicLink} first. For a plain web link to one of those two apps, it answers a new intent
 * that carries the link and nothing else, aimed at the app's own link activity the scope found. A
 * Threads card in the feed opens Threads with a {@code barcelona://} link by package rather than a web
 * link (#385), and that goes to Threads' link activity the same way.
 *
 * <p>The new intent holds only the action, the address and the browsable category: no extras, no
 * clip data, no permissions the dropped intent may have carried. A family intent can carry sign-in
 * tokens, and those never leave with it. The address itself is the public link the user tapped, the
 * same thing a browser would get. The target has to be an exported activity of the app's exact
 * package, so a lookalike that claims the same links is never picked. Any other link, an intent
 * Facebook meant for a service, and any error leave Facebook's null, as before.
 */
@SuppressWarnings("unused")
public final class FamilyLinkHandoff {
    private FamilyLinkHandoff() {
    }

    static final String INSTAGRAM = "com.instagram.android";
    static final String THREADS = "com.instagram.barcelona";
    /** Threads' own link scheme, which Facebook opens Threads with from a Threads card or bookmark. */
    static final String THREADS_SCHEME = "barcelona";

    /**
     * The intent to start instead of the one the scope dropped, or null to keep Facebook's refusal.
     * [components] is the list of targets the scope judged, before it filtered them.
     */
    @Nullable
    public static Intent publicLink(@Nullable Intent dropped, @Nullable List<?> components) {
        try {
            if (dropped == null || components == null || components.isEmpty()) return null;
            if (!Intent.ACTION_VIEW.equals(dropped.getAction())) return null;
            Uri link = dropped.getData();
            if (link == null) return null;
            String scheme = link.getScheme();
            String app;
            if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
                app = appFor(link.getHost());
            } else if (THREADS_SCHEME.equalsIgnoreCase(scheme) && dropped.getComponent() == null
                    && (dropped.getPackage() == null || THREADS.equals(dropped.getPackage()))) {
                // A Threads card or bookmark asks for Threads by package with Threads' own scheme
                // (#385). Facebook first tries Threads' main activity by name; that stays refused, so
                // Facebook falls back to this by-package intent, which Threads' link activity takes.
                app = THREADS;
            } else {
                return null;
            }
            if (app == null) return null;

            HookStatus.invoked(FamilyNames.RESTORE_TRUST);
            for (Object candidate : components) {
                if (!(candidate instanceof ActivityInfo)) continue;
                ActivityInfo activity = (ActivityInfo) candidate;
                if (!app.equals(activity.packageName) || activity.name == null || !activity.exported) continue;

                Intent handoff = new Intent(Intent.ACTION_VIEW, link);
                handoff.addCategory(Intent.CATEGORY_BROWSABLE);
                handoff.setClassName(activity.packageName, activity.name);
                handoff.addFlags(dropped.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
                HookStatus.bound(FamilyNames.RESTORE_TRUST, "link handoff to " + app);
                HookStatus.counted(FamilyNames.RESTORE_TRUST, "link handoff");
                Logger.printDebug(() -> "Coexist: opening a link Facebook's family scope dropped in " + app);
                return handoff;
            }
            HookStatus.counted(FamilyNames.RESTORE_TRUST, "link handoff: no exported activity of " + app);
            return null;
        } catch (Throwable failure) {
            // Fail closed: Facebook's own refusal stands.
            HookStatus.threw(FamilyNames.RESTORE_TRUST, "link handoff", failure);
            Logger.printException(() -> "Could not hand a dropped family link to its app", failure);
            return null;
        }
    }

    /**
     * The package whose links [host] serves: Instagram for instagram.com, its subdomains and instagr.am,
     * Threads for threads.net and threads.com and their subdomains. Null for anything else.
     */
    @Nullable
    static String appFor(@Nullable String host) {
        if (host == null) return null;
        String name = host.toLowerCase(Locale.ROOT);
        if (within(name, "instagram.com") || name.equals("instagr.am")) return INSTAGRAM;
        if (within(name, "threads.net") || within(name, "threads.com")) return THREADS;
        return null;
    }

    private static boolean within(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
