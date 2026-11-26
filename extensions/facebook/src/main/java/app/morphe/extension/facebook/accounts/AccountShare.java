/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.accounts;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What signs a phone in, read from Facebook's stores and written out as text.
 *
 * <p>An account is what signs it in: the cookies of its web session and the access token behind
 * its native one, never the password. This holds the account as {@link Account} and the token list
 * an export writes, in the small JSON object another tool reads.
 *
 * <p>A cookie header is read in the shapes a browser copies it: {@code c_user=1000; xs=12%3Aabc;
 * datr=...}, with or without the {@code Cookie:} prefix, on one line or several, with the browser
 * attributes (path, domain, expires, SameSite, HttpOnly, Secure) dropped on the way in.
 */
public final class AccountShare {
    /** What a token file says it is, so another kind of file isn't read as one. */
    public static final String TOKENS_FORMAT = "hushfacebook-token";

    /** The version this build writes of that file. */
    public static final int TOKENS_VERSION = 1;

    /** One cookie's value: xs runs to a few hundred bytes, and fr to about one hundred. */
    static final int MAX_COOKIE_VALUE = 4096;

    /** One session value: a session blob is a few kilobytes, and a bug isn't 64 of them. */
    static final int MAX_SESSION_VALUE = 64 * 1024;

    /** Cookie names: the names Facebook's own stores use, and nothing odd. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    /**
     * What a cookie value may hold: printable ASCII, a space included, since a cookie Facebook sets
     * and a header pasted from a browser are both allowed one.
     */
    private static final Pattern VALUE = Pattern.compile("[\\x20-\\x7e]{1," + MAX_COOKIE_VALUE + "}");

    /** The cookie Facebook signs a web session in with, which is also the account's id. */
    public static final String UID_COOKIE = "c_user";

    public static final String SESSION_COOKIE = "xs";

    /** Cookie attributes a copied browser header carries, which are not cookies. */
    private static final List<String> ATTRIBUTES = Collections.unmodifiableList(java.util.Arrays.asList(
            "path", "domain", "expires", "max-age", "samesite", "httponly", "secure", "priority",
            "partitioned", "sameparty", "version", "comment"));

    private AccountShare() {
    }

    /** One session value Facebook keeps in a preference file, named where it came from. */
    public static final class Entry {
        public final String file;
        public final String key;
        public final String value;

        Entry(String file, String key, String value) {
            this.file = file;
            this.key = key;
            this.value = value;
        }
    }

    /** One account: its id, its name, its token, and what signs it in. */
    public static final class Account {
        /** The account's id, from the c_user cookie or the token it was found with. */
        public final String uid;
        /** The name Facebook shows for it, or "" when the phone doesn't say. */
        public final String name;
        /** The EAAAA… token behind the native session, or "" when only cookies were found. */
        public final String token;
        public final Map<String, String> cookies;
        public final List<Entry> session;
        /**
         * What Facebook keeps in AccountManager for this account, keyed by the name it stores it
         * under. A token export doesn't write it, and it stays here because it is where the token
         * behind the account switcher is read from.
         */
        public final Map<String, String> credentials;

        Account(String uid, String name, String token, Map<String, String> cookies, List<Entry> session) {
            this(uid, name, token, cookies, session, Collections.<String, String>emptyMap());
        }

        Account(String uid, String name, String token, Map<String, String> cookies, List<Entry> session,
                Map<String, String> credentials) {
            this.uid = uid;
            this.name = name;
            this.token = token;
            this.cookies = Collections.unmodifiableMap(new LinkedHashMap<>(cookies));
            this.session = Collections.unmodifiableList(new ArrayList<>(session));
            this.credentials = Collections.unmodifiableMap(new LinkedHashMap<>(credentials));
        }
    }

    /** An account store that holds nothing to export, with a line a dialog can show as it is. */
    public static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    /**
     * The token file: every account that carries a token, in the order [accounts] holds them.
     *
     * <p>Its shape is the whole message, and nothing around it says what to do with it:
     * {@code {"format":"hushfacebook-token","version":1,"tokens":[{"uid":"…","name":"…","token":"…"}]}}.
     * An account with no token is left out, so the list never holds a row a tool can't use.
     */
    public static String tokens(List<Account> accounts) {
        try {
            JSONArray array = new JSONArray();
            for (Account account : accounts) {
                if (account.token.isEmpty()) continue;
                JSONObject one = new JSONObject();
                one.put("uid", account.uid);
                if (!account.name.isEmpty()) one.put("name", account.name);
                one.put("token", account.token);
                array.put(one);
            }
            JSONObject root = new JSONObject();
            root.put("format", TOKENS_FORMAT);
            root.put("version", TOKENS_VERSION);
            root.put("tokens", array);
            return root.toString(2);
        } catch (JSONException unreachable) {
            // Every key above is a plain string and every value one of the types JSON has, so this
            // can't happen. A RuntimeException here would close Facebook.
            throw new IllegalStateException(unreachable);
        }
    }

    /** The cookies in one header, or across several lines, without the attributes a browser adds. */
    public static Map<String, String> parseCookies(String text) throws Refused {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String line : text.split("[\r\n]+")) {
            int start = 0;
            int length = line.length();
            while (start < length) {
                int end = line.indexOf(';', start);
                if (end < 0) end = length;
                String pair = line.substring(start, end).trim();
                start = end + 1;
                if (pair.isEmpty()) continue;
                int equals = pair.indexOf('=');
                if (equals < 0) throw new Refused("That isn't a cookie: \"" + shorten(pair) + "\".");
                String name = pair.substring(0, equals).trim();
                String value = pair.substring(equals + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (ATTRIBUTES.contains(name.toLowerCase(Locale.ROOT))) continue;
                if (!NAME.matcher(name).matches()) {
                    throw new Refused("That isn't a cookie name: \"" + shorten(name) + "\".");
                }
                if (!VALUE.matcher(value).matches()) {
                    throw new Refused("The cookie \"" + shorten(name) + "\" has a value that can't be one.");
                }
                cookies.put(name, value);
            }
        }
        if (cookies.isEmpty()) throw new Refused("That has no cookies in it.");
        return cookies;
    }

    /** The id the cookies name, or "" when they carry no c_user. */
    public static String uidOf(Map<String, String> cookies) {
        String uid = cookies.get(UID_COOKIE);
        return uid != null && uid.matches("[0-9]{1,32}") ? uid : "";
    }

    /** [text] cut to something a dialog can show in one line. */
    private static String shorten(String text) {
        return text.length() <= 40 ? text : text.substring(0, 37) + "...";
    }

    /** Whether [value] is something a preference file can hold. */
    static boolean isSessionValue(String value) {
        if (value.length() > MAX_SESSION_VALUE) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') return false;
            if (ch == 0x7f) return false;
        }
        return true;
    }

    /** Whether [value] is a cookie Facebook signs a session in with, for a scan looking for one. */
    static boolean isSignInValue(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_SESSION_VALUE) return false;
        return value.startsWith("EAAAA") || value.contains("c_user=") || value.contains("\"xs\"");
    }
}
