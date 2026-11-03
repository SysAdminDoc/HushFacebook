/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;

/**
 * Open in Facebook, a share target (#111). Messenger opens a facebook.com link it's sent in its own
 * browser, and a re-signed Facebook can't be verified for those addresses, so Android doesn't send
 * them here by itself. Sharing the page or the link to Open in Facebook hands the first Facebook
 * address in the shared text to Facebook's own link handler, the screen a tapped link opens.
 *
 * <p>The patch declares this screen with no window of its own; it finishes as soon as it has passed
 * the link on, or said there was none.
 */
public final class OpenInFacebook extends Activity {
    /** Facebook's own handler for the links it opens, a manifest component that keeps its name. */
    static final String URI_HANDLER = "com.facebook.katana.IntentUriHandler";

    /**
     * A Facebook address: facebook.com, fb.com and their mobile hosts, fb.watch or fb.me. One inside
     * another site's address (example.com/facebook.com, facebook.com.example.com) isn't.
     */
    private static final Pattern LINK = Pattern.compile(
            "(?<![\\w.@/-])(?:https?://)?(?:(?:www|m|web|mbasic|touch)\\.)?(?:facebook\\.com|fb\\.com|fb\\.watch|fb\\.me)"
                    + "(?![\\w-]|\\.\\w)(?:/[^\\s<>\"'`]*)?",
            Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            String link = link(getIntent());
            if (link == null) {
                Toast.makeText(this, L10n.t(this, "There's no Facebook link in what you shared."), Toast.LENGTH_LONG).show();
            } else {
                try {
                    startActivity(view(getPackageName(), link));
                } catch (ActivityNotFoundException handlerOff) {
                    // The manifest ships the handler turned off until Facebook's own startup turns it on.
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(getPackageName()));
                }
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Open in Facebook: could not open the shared link", failure);
        }
        finish();
    }

    /** The first Facebook address in what [shared] carries, as an https link, or null. */
    @Nullable
    static String link(@Nullable Intent shared) {
        if (shared == null) return null;
        CharSequence text = shared.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String found = text == null ? null : first(text.toString());
        if (found == null) found = first(shared.getStringExtra(Intent.EXTRA_SUBJECT));
        if (found == null && shared.getData() != null) found = first(shared.getData().toString());
        return found;
    }

    @Nullable
    private static String first(@Nullable String text) {
        if (text == null) return null;
        Matcher match = LINK.matcher(text);
        if (!match.find()) return null;
        // A sentence's closing punctuation isn't part of the address.
        String found = match.group().replaceFirst("[.,;:!?)\\]]+$", "");
        return "https://" + found.replaceFirst("(?i)^https?://", "");
    }

    /** The intent that opens [link] in the app [packageName], through Facebook's link handler. */
    static Intent view(String packageName, String link) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClassName(packageName, URI_HANDLER);
    }
}
