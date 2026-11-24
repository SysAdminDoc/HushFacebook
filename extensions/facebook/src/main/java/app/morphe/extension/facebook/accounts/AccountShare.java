/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.accounts;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Accounts as a piece of text, and back.
 *
 * <p>FacebookAMD shares an account by what signs it in: the cookies of its web session and the
 * access token behind its native one, never the password. This is the same idea, in a format
 * Hushfacebook checks before it writes anything. One file holds a list, so a phone with an account
 * switcher full of them exports all of them at once, each with its id, its name when the phone
 * knows one, its token and its cookies.
 *
 * <p>Version 2 is the array form. Version 1, one account as a single object, is still read, so a
 * file made before this build imports. Pasted text is read too: one cookie header
 * ({@code c_user=1000; xs=12%3Aabc; datr=...}) with or without the {@code Cookie:} prefix, on one
 * line or several, with the browser attributes (path, domain, expires, SameSite, HttpOnly, Secure)
 * dropped on the way in.
 *
 * <p>A file or paste is refused, with the reason, before anything is written: text past
 * {@link #MAX_TEXT} bytes, text that isn't an object or an array, a format or version this build
 * doesn't know, a missing c_user or xs, a cookie value with a control character or past the length
 * it could have, a token that isn't one, a name past {@link #MAX_NAME}, a preference entry past
 * {@link #MAX_SESSION_VALUE}, or the same account id twice.
 */
public final class AccountShare {
    /** What this file says it is, so another kind of file isn't read as one. */
    public static final String FORMAT = "hushfacebook-account";

    /** The version this build writes. Version 1, a bare account object, is still read. */
    public static final int VERSION = 2;

    /** More than a phone's accounts need: one is about a kilobyte of cookies and one of token. */
    static final int MAX_TEXT = 256 * 1024;

    /** More accounts than a Facebook install has, so a loop can't be talked into running long. */
    static final int MAX_ACCOUNTS = 32;

    /** One cookie's value: xs runs to a few hundred bytes, and fr to about one hundred. */
    static final int MAX_COOKIE_VALUE = 4096;

    /** One preference value: a session blob is a few kilobytes, and a bug isn't 64 of them. */
    static final int MAX_SESSION_VALUE = 64 * 1024;

    /** A display name: a Facebook name is far shorter, and a dialog shows it on one line. */
    static final int MAX_NAME = 80;

    /** Cookie names and preference keys: the names Facebook's own stores use, and nothing odd. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    /** A preference file's name, without the path or the .xml the folder adds. */
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");

    /** What a cookie value may hold. A percent sign is how xs keeps its colons, so it stays. */
    private static final Pattern VALUE = Pattern.compile("[\\x21-\\x7e]{1," + MAX_COOKIE_VALUE + "}");

    /** A Facebook app access token: EAAAA…, EAAB… as the login hands them out. */
    private static final Pattern TOKEN = Pattern.compile("EAA[A-Za-z0-9]{10,1024}");

    /** A display name, as it may appear in a file: printable, no control characters. */
    private static final Pattern DISPLAY_NAME = Pattern.compile("[^\\p{Cntrl}]{1," + MAX_NAME + "}");

    /** The cookie Facebook signs a web session in with, which is also the account's id. */
    public static final String UID_COOKIE = "c_user";

    public static final String SESSION_COOKIE = "xs";

    /** The cookies a Facebook login sets, in the order a header lists them. */
    public static final List<String> WANTED_COOKIES =
            Collections.unmodifiableList(java.util.Arrays.asList("c_user", "xs", "datr", "sb", "fr"));

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

        Account(String uid, String name, String token, Map<String, String> cookies, List<Entry> session) {
            this.uid = uid;
            this.name = name;
            this.token = token;
            this.cookies = Collections.unmodifiableMap(new LinkedHashMap<>(cookies));
            this.session = Collections.unmodifiableList(new ArrayList<>(session));
        }

        /** The cookies as the header a browser sends, which is what another mod imports. */
        public String cookieHeader() {
            StringBuilder header = new StringBuilder();
            for (Map.Entry<String, String> cookie : cookies.entrySet()) {
                if (header.length() > 0) header.append("; ");
                header.append(cookie.getKey()).append('=').append(cookie.getValue());
            }
            return header.toString();
        }

        /** What the dialogs say about this account: its name and id, and how much came with it. */
        public String describe() {
            StringBuilder line = new StringBuilder();
            if (!name.isEmpty()) line.append(name).append(", ");
            line.append("uid ").append(uid.isEmpty() ? "unknown" : uid);
            line.append(", ").append(cookies.size()).append(" cookie").append(cookies.size() == 1 ? "" : "s");
            if (!token.isEmpty()) line.append(", token");
            if (!session.isEmpty()) line.append(", ").append(session.size()).append(" session value")
                    .append(session.size() == 1 ? "" : "s");
            return line.toString();
        }
    }

    /** Text that can't be an account, with a line a dialog can show as it is. */
    public static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    /**
     * The accounts in [text]: the JSON this writes (version 2 or 1), or a cookie header.
     *
     * @throws Refused when [text] can't be one, before anything is written.
     */
    public static List<Account> parse(String text) throws Refused {
        if (text == null) throw new Refused("There's nothing to import.");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) throw new Refused("There's nothing to import.");
        int bytes = trimmed.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT) {
            throw new Refused("That's " + bytes + " bytes, past the " + MAX_TEXT + " an account file can be.");
        }
        if (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[') return parseJson(trimmed);
        String header = trimmed;
        if (header.regionMatches(true, 0, "cookie:", 0, 7)) header = header.substring(7);
        Map<String, String> cookies = parseCookies(header);
        String uid = uidOf(cookies);
        return Collections.singletonList(new Account(uid, "", "", cookies, Collections.<Entry>emptyList()));
    }

    /** The JSON this writes, or version 1 of it, read back. */
    private static List<Account> parseJson(String text) throws Refused {
        Object read;
        try {
            read = new JSONTokener(text).nextValue();
        } catch (JSONException broken) {
            throw new Refused("That isn't an account file: " + broken.getMessage());
        }
        JSONObject root;
        JSONArray array;
        if (read instanceof JSONArray) {
            array = (JSONArray) read;
            root = null;
        } else if (read instanceof JSONObject) {
            root = (JSONObject) read;
            array = null;
        } else {
            throw new Refused("That isn't an account file.");
        }
        String format = root != null ? root.optString("format", "") : FORMAT;
        if (!format.isEmpty() && !FORMAT.equals(format)) {
            throw new Refused("That file is a \"" + format + "\", not a Hushfacebook account.");
        }
        int version = root != null ? root.optInt("version", 1) : VERSION;
        if (version != 1 && version != VERSION) {
            throw new Refused("That file is version " + version + ", and this build reads versions 1 and " + VERSION + ".");
        }
        JSONArray accounts = array;
        if (accounts == null) {
            accounts = root.optJSONArray("accounts");
            if (accounts == null) accounts = new JSONArray().put(root);
        }
        if (accounts.length() == 0) throw new Refused("That account file has no accounts in it.");
        if (accounts.length() > MAX_ACCOUNTS) {
            throw new Refused("That holds " + accounts.length() + " accounts, past the " + MAX_ACCOUNTS + " one file can.");
        }
        List<Account> out = new ArrayList<>();
        java.util.Set<String> uids = new java.util.HashSet<>();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject one = accounts.optJSONObject(i);
            if (one == null) throw new Refused("An account in that file isn't an object.");
            Account account = account(one);
            if (!account.uid.isEmpty() && !uids.add(account.uid)) {
                throw new Refused("That file holds uid " + account.uid + " twice.");
            }
            out.add(account);
        }
        return out;
    }

    /** One account object, with what it carries checked. */
    private static Account account(JSONObject one) throws Refused {
        Map<String, String> cookies = new LinkedHashMap<>();
        JSONObject cookiesJson = one.optJSONObject("cookies");
        if (cookiesJson != null) {
            for (Iterator<String> names = cookiesJson.keys(); names.hasNext(); ) {
                String name = names.next();
                if (!NAME.matcher(name).matches()) {
                    throw new Refused("That isn't a cookie name: \"" + name + "\".");
                }
                Object value = cookiesJson.opt(name);
                if (!(value instanceof String)) throw new Refused("The cookie \"" + name + "\" isn't a string.");
                if (!VALUE.matcher((String) value).matches()) {
                    throw new Refused("The cookie \"" + name + "\" has a value that can't be one.");
                }
                cookies.put(name, (String) value);
            }
        }
        List<Entry> session = new ArrayList<>();
        JSONArray sessionJson = one.optJSONArray("session");
        if (sessionJson != null) {
            for (int i = 0; i < sessionJson.length(); i++) {
                JSONObject entry = sessionJson.optJSONObject(i);
                if (entry == null) throw new Refused("A session value in that file isn't an object.");
                String file = entry.optString("file", "");
                String key = entry.optString("key", "");
                if (!FILE.matcher(file).matches()) throw new Refused("A session value names the file \"" + file + "\".");
                if (!NAME.matcher(key).matches()) throw new Refused("A session value names the key \"" + key + "\".");
                session.add(new Entry(file, key, entry.optString("value", "")));
            }
        }
        String token = one.optString("token", "");
        if (!token.isEmpty() && !TOKEN.matcher(token).matches()) {
            throw new Refused("That token isn't one.");
        }
        String name = one.optString("name", "");
        if (!name.isEmpty() && !DISPLAY_NAME.matcher(name).matches()) {
            throw new Refused("That display name can't be one.");
        }
        String uid = one.optString("uid", "");
        if (uid.isEmpty()) uid = uidOf(cookies);
        if (!uid.isEmpty() && !uid.matches("[0-9]{1,32}")) {
            throw new Refused("That Facebook id isn't a number: \"" + uid + "\".");
        }
        if (uid.isEmpty() && token.isEmpty()) {
            throw new Refused("An account in that file has neither an id nor a token.");
        }
        if (!cookies.isEmpty() && !cookies.containsKey(SESSION_COOKIE)) {
            throw new Refused("The cookies for " + (uid.isEmpty() ? "an account" : "uid " + uid)
                    + " have no xs, so Facebook signs nothing in with them.");
        }
        if (session.size() > 64) throw new Refused("That has more session values than an account has.");
        for (Entry entry : session) {
            if (entry.value.length() > MAX_SESSION_VALUE) {
                throw new Refused("The session value \"" + entry.key + "\" is past the " + MAX_SESSION_VALUE + " bytes it can be.");
            }
            if (!entry.value.isEmpty() && !VALUE.matcher(entry.value).matches()) {
                throw new Refused("The session value \"" + entry.key + "\" has a value that can't be one.");
            }
        }
        return new Account(uid, name, token, cookies, session);
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

    /** The JSON a file or the clipboard holds: [accounts] as version {@link #VERSION}. */
    public static String json(List<Account> accounts) {
        return json(accounts, Collections.<String>emptyList());
    }

    /**
     * The JSON [accounts] as version {@link #VERSION}, with [stores] written beside them.
     *
     * <p>The store list is a report for finding where Facebook keeps a signed-in session: it goes
     * into the file an export writes so it can be read off a phone with no root, and an import
     * ignores it.
     */
    public static String json(List<Account> accounts, List<String> stores) {
        try {
            JSONArray array = new JSONArray();
            for (Account account : accounts) {
                JSONObject json = new JSONObject();
                json.put("uid", account.uid);
                if (!account.name.isEmpty()) json.put("name", account.name);
                if (!account.token.isEmpty()) json.put("token", account.token);
                JSONObject cookies = new JSONObject();
                for (Map.Entry<String, String> cookie : account.cookies.entrySet()) {
                    cookies.put(cookie.getKey(), cookie.getValue());
                }
                json.put("cookies", cookies);
                JSONArray session = new JSONArray();
                for (Entry entry : account.session) {
                    JSONObject value = new JSONObject();
                    value.put("file", entry.file);
                    value.put("key", entry.key);
                    value.put("value", entry.value);
                    session.put(value);
                }
                json.put("session", session);
                array.put(json);
            }
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("version", VERSION);
            root.put("accounts", array);
            if (!stores.isEmpty()) {
                JSONArray report = new JSONArray();
                for (String store : stores) report.put(store);
                root.put("stores", report);
            }
            return root.toString(2);
        } catch (JSONException unreachable) {
            // Every key above is a plain string and every value one of the types JSON has, so this
            // can't happen. A RuntimeException here would close Facebook.
            throw new IllegalStateException(unreachable);
        }
    }

    /** Whatever the token in [value] is, or "" when it holds none. */
    public static String tokenIn(@Nullable String value) {
        if (value == null) return "";
        java.util.regex.Matcher match = TOKEN.matcher(value);
        return match.find() ? match.group() : "";
    }

    /** [text] cut to something a dialog can show in one line. */
    private static String shorten(String text) {
        return text.length() <= 40 ? text : text.substring(0, 37) + "...";
    }

    /** Whether [value] is a cookie Facebook signs a session in with, for a scan looking for one. */
    static boolean isSignInValue(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_SESSION_VALUE) return false;
        return value.startsWith("EAAAA") || value.contains("c_user=") || value.contains("\"xs\"");
    }
}
