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
import java.util.regex.Pattern;

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
 * <p>Threads opens a post's web address, and its own {@code barcelona://mainfeed?injected_media_ids=}
 * link, as its home feed with the post on top. Only its {@code barcelona://media?id=} link opens the
 * post itself, so either of those goes over as that link: the first injected id (Threads writes them as
 * a bracketed list, {@code [id1,id2]}), or the web address's short code read back into the post's number.
 * Facebook 588 hands a card over as {@code threads.com/inter_app/redirect} with the post in its
 * {@code destination_url}, which Threads also lands on its home feed, so the post is read from there.
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
    /** The letters of a Threads post's short code, each worth its index (base 64). */
    private static final String CODE_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    /** A post id Threads' media link takes: the number, or the number and its owner's. */
    private static final Pattern MEDIA_ID = Pattern.compile("\\d+(_\\d+)?");

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

                Uri post = THREADS.equals(app) ? threadsPost(link) : null;
                Intent handoff = new Intent(Intent.ACTION_VIEW, post != null ? post : link);
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

    /**
     * Threads' own link to the post [link] puts on top of its feed, or null when it names none:
     * threads.com/@name/post/CODE, threads.net/t/CODE, barcelona://mainfeed?injected_media_ids=ID, or
     * Facebook's threads.com/inter_app/redirect carrying one of those in its destination_url.
     */
    @Nullable
    static Uri threadsPost(Uri link) {
        Uri destination = redirectDestination(link);
        return post(destination != null ? destination : link);
    }

    /**
     * The Threads link inside Facebook's family-app redirect,
     * threads.com/inter_app/redirect?...&amp;destination_url=LINK, or null for any other link or a
     * destination that isn't a Threads link. Threads lands the redirect itself on its home feed (#385).
     */
    @Nullable
    private static Uri redirectDestination(Uri link) {
        String scheme = link.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return null;
        List<String> path = link.getPathSegments();
        if (path.size() != 2 || !"inter_app".equals(path.get(0)) || !"redirect".equals(path.get(1))) return null;
        String destination = link.getQueryParameter("destination_url");
        if (destination == null || destination.trim().isEmpty()) return null;
        Uri inner = Uri.parse(destination.trim());
        String innerScheme = inner.getScheme();
        if (THREADS_SCHEME.equalsIgnoreCase(innerScheme)) return inner;
        boolean web = "https".equalsIgnoreCase(innerScheme) || "http".equalsIgnoreCase(innerScheme);
        return web && THREADS.equals(appFor(inner.getHost())) ? inner : null;
    }

    /** The barcelona://media link for a post's web address or Threads' mainfeed link, or null. */
    @Nullable
    private static Uri post(Uri link) {
        String scheme = link.getScheme();
        String id = null;
        if (THREADS_SCHEME.equalsIgnoreCase(scheme)) {
            if (!"mainfeed".equalsIgnoreCase(link.getHost())) return null;
            String injected = link.getQueryParameter("injected_media_ids");
            if (injected == null) return null;
            // Threads writes the ids as a bracketed list, [id] or [id1,id2], and reads them the same
            // way: brackets off, split on the comma, the first one (#385). A bare id still works.
            String list = injected.trim();
            if (list.startsWith("[")) list = list.substring(1);
            if (list.endsWith("]")) list = list.substring(0, list.length() - 1);
            int comma = list.indexOf(',');
            String first = (comma < 0 ? list : list.substring(0, comma)).trim();
            if (MEDIA_ID.matcher(first).matches()) id = first;
        } else if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            List<String> path = link.getPathSegments();
            String code = null;
            if (path.size() >= 3 && path.get(0).length() > 1 && path.get(0).startsWith("@") && "post".equals(path.get(1))) {
                code = path.get(2);
            } else if (path.size() >= 2 && "t".equals(path.get(0))) {
                code = path.get(1);
            }
            long number = postId(code);
            if (number > 0) id = Long.toString(number);
        }
        if (id == null) return null;
        return new Uri.Builder().scheme(THREADS_SCHEME).authority("media").appendQueryParameter("id", id).build();
    }

    /** The post number a short code stands for, or 0 for no code, a letter outside the code's set, or a number too big. */
    static long postId(@Nullable String code) {
        if (code == null || code.isEmpty()) return 0;
        long id = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = CODE_LETTERS.indexOf(code.charAt(i));
            if (digit < 0 || id > (Long.MAX_VALUE - digit) / 64) return 0;
            id = id * 64 + digit;
        }
        return id;
    }
}
