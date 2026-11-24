/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.accounts;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * The accounts Facebook is signed in with, read out of the running app and written back into it.
 *
 * <p>Three stores hold them. The cookie store behind {@link CookieManager} holds what a browser
 * would send for facebook.com, which is what an account pasted into another mod is made of. The
 * preference files hold session values under the names Facebook stores them, and the import writes
 * those back where they came from. The rest of the app's data folder holds the token itself, which
 * Facebook keeps out of XML: a bounded walk of the folder finds it as the EAAAA… string it is, and
 * the id and name it belongs to come from the bytes around it.
 *
 * <p>The walk is bounded and skips what can't hold a token: {@link #MAX_DEPTH} folders deep, at most
 * {@link #MAX_SCAN_FILES} files, each past {@link #MAX_SCAN_FILE} bytes left alone, at most
 * {@link #MAX_TOKENS} tokens taken, and at most {@link #MAX_WINDOW} bytes read around each one. An
 * export carries the file and the key each preference value came from, so an import writes it back
 * rather than into a store of Hushfacebook's own that Facebook never reads.
 *
 * <p>Both calls do file and provider work. Run them off the main thread: a walk reads a folder, and
 * an import commits several preference files.
 */
public final class AccountSession {
    /** The addresses Facebook's cookies are set for. A session has to be on all three. */
    private static final String[] URLS = {
            "https://www.facebook.com", "https://m.facebook.com", "https://web.facebook.com",
    };

    /** What a stored cookie is given beside its value, so Facebook's own pages read it. */
    private static final String COOKIE_ATTRIBUTES = "; domain=.facebook.com; path=/";

    /** How deep under the app's data folder the scan reaches: a session sits near the top. */
    private static final int MAX_DEPTH = 4;

    /** A preference file past this is a cache, not a session. */
    private static final long MAX_PREFERENCE_FILE = 8L * 1024 * 1024;

    /** A data file past this is a media store or a log, and a token is never that big. */
    private static final long MAX_SCAN_FILE = 16L * 1024 * 1024;

    /** The most files one export opens, so a folder tree can't stall it. */
    private static final int MAX_SCAN_FILES = 400;

    /** The most tokens one export takes, which is more than a phone's account switcher holds. */
    private static final int MAX_TOKENS = 16;

    /** How far either side of a token its id and name are looked for. */
    private static final int MAX_WINDOW = 2048;

    /** The most preference values an import can write back. */
    private static final int MAX_ENTRIES = 64;

    /** Keys Facebook's own stores use for a sign-in, in lower case, matched as a suffix or a part. */
    private static final String[] SESSION_KEYS = {
            "access_token", "session_info", "sessioninfo", "user_session", "user_session_info",
            "session_cookies", "cookies", "auth_data", "authdata", "logged_in_user",
    };

    /** A token as it sits in a store, for the raw scan. */
    private static final Pattern TOKEN = Pattern.compile("EAA[A-Za-z0-9]{10,1024}");

    /** The id, in the shapes a store writes it: a JSON field or a cookie. */
    private static final Pattern UID_NEAR = Pattern.compile(
            "(?:\"(?:uid|user_id|userId|fbid|account_id)\"\\s*:\\s*\"?|c_user=)([0-9]{5,20})");

    /** The name, in the shapes a store writes it, next to that id. */
    private static final Pattern NAME_NEAR = Pattern.compile(
            "\"(?:name|display_name|displayName|profile_name)\"\\s*:\\s*\"([^\"\\\\\\p{Cntrl}]{1,80})\"");

    private AccountSession() {
    }

    /**
     * Every account the app is signed in with: the one its cookies belong to first, then each other
     * token the scan found.
     *
     * @throws AccountShare.Refused when nothing signable was found.
     */
    /** Tag the store walk logs under, so the device log names what sits where. */
    private static final String STORE_TAG = "HushFacebookStore";

    /**
     * Names the files under the app's data folder that could hold a signed-in session, in the
     * device log. Facebook keeps its native session outside the preference files this class reads,
     * and the account picker lists accounts this build cannot yet restore, so the names are
     * collected rather than guessed at.
     */
    public static void dumpStores(Context context) {
        dumpFolder(new java.io.File(context.getApplicationInfo().dataDir), 0);
    }

    private static void dumpFolder(java.io.File folder, int depth) {
        if (depth > 3) return;
        java.io.File[] files;
        try {
            files = folder.listFiles();
        } catch (SecurityException denied) {
            return;
        }
        if (files == null) return;
        for (java.io.File file : files) {
            String name = file.getName();
            if (file.isDirectory()) {
                if (skipped(name)) continue;
                dumpFolder(file, depth + 1);
            } else if (name.matches("(?i).*(auth|session|account|dbl|login|user|profile).*")
                    || (file.length() > 4096 && name.endsWith(".db"))) {
                android.util.Log.i(STORE_TAG, file.getAbsolutePath() + " " + file.length());
            }
        }
    }

    /**
     * The app's databases and files, biggest first, as a report of where a signed-in session could
     * sit. It goes into the file an export writes so it can be read off a phone with no root.
     */
    public static List<String> storeReport(Context context) {
        List<String> found = new ArrayList<>();
        java.io.File data = new java.io.File(context.getApplicationInfo().dataDir);
        report(data, "databases", found);
        report(data, "files", found);
        reportPrefsDatabase(context, found);
        reportPreferenceKeys(context, found);
        return found;
    }

    /**
     * The tables of Facebook's own preference store, which is a SQLite database and not the XML
     * files this class reads: a session value would be a row in one of them.
     */
    private static void reportPrefsDatabase(Context context, List<String> found) {
        java.io.File db = new java.io.File(context.getApplicationInfo().dataDir, "databases/prefs_db");
        if (!db.isFile()) return;
        android.database.sqlite.SQLiteDatabase sql = null;
        android.database.Cursor cursor = null;
        try {
            sql = android.database.sqlite.SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            cursor = sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            while (cursor.moveToNext() && found.size() < 64) {
                found.add("table " + cursor.getString(0));
            }
        } catch (RuntimeException failed) {
            found.add("prefs_db unreadable: " + failed.getClass().getSimpleName());
        } finally {
            if (cursor != null) cursor.close();
            if (sql != null) sql.close();
        }
    }

    /**
     * The columns of the preference table, and the names of the rows that could be a sign-in, so
     * the next build knows the key a session is stored under.
     */
    private static void reportPreferenceKeys(Context context, List<String> found) {
        java.io.File db = new java.io.File(context.getApplicationInfo().dataDir, "databases/prefs_db");
        if (!db.isFile()) return;
        android.database.sqlite.SQLiteDatabase sql = null;
        try {
            sql = android.database.sqlite.SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            StringBuilder columns = new StringBuilder();
            android.database.Cursor info = sql.rawQuery("PRAGMA table_info(preferences)", null);
            while (info.moveToNext()) {
                if (columns.length() > 0) columns.append(", ");
                columns.append(info.getString(1));
            }
            info.close();
            found.add("columns preferences: " + columns);
            android.database.Cursor rows = sql.rawQuery(
                    "SELECT name FROM preferences WHERE name LIKE '%session%' OR name LIKE '%auth%'"
                            + " OR name LIKE '%token%' OR name LIKE '%user%' OR name LIKE '%name%'"
                            + " OR name LIKE '%account%' LIMIT 60", null);
            while (rows.moveToNext()) {
                found.add("key " + rows.getString(0));
            }
            rows.close();
        } catch (RuntimeException failed) {
            found.add("preferences unreadable: " + failed.getClass().getSimpleName());
        } finally {
            if (sql != null) sql.close();
        }
    }

    private static void report(java.io.File data, String folder, List<String> found) {
        java.io.File dir = new java.io.File(data, folder);
        java.io.File[] files;
        try {
            files = dir.listFiles();
        } catch (SecurityException denied) {
            return;
        }
        if (files == null) return;
        java.util.Arrays.sort(files, (left, right) -> Long.compare(right.length(), left.length()));
        int listed = 0;
        for (java.io.File file : files) {
            if (listed++ >= 24) break;
            found.add((file.isDirectory() ? "dir " : "file ") + file.getName() + " " + file.length());
        }
    }

    /** The first dozen store paths under the app's data folder, for a report read off the screen. */
    public static String storeList(Context context) {        java.util.List<String> found = new java.util.ArrayList<>();
        collectStores(new java.io.File(context.getApplicationInfo().dataDir), 0, found);
        StringBuilder text = new StringBuilder();
        for (String path : found) {
            if (text.length() > 0) text.append('\n');
            text.append(path);
        }
        return text.toString();
    }

    private static void collectStores(java.io.File folder, int depth, java.util.List<String> found) {
        if (depth > 3 || found.size() >= 12) return;
        java.io.File[] files;
        try {
            files = folder.listFiles();
        } catch (SecurityException denied) {
            return;
        }
        if (files == null) return;
        for (java.io.File file : files) {
            String name = file.getName();
            if (file.isDirectory()) {
                if (!skipped(name)) collectStores(file, depth + 1, found);
            } else if (found.size() < 12
                    && (name.matches("(?i).*(auth|session|account|dbl|login|user|profile).*")
                    || (file.length() > 4096 && name.endsWith(".db")))) {
                found.add(file.getAbsolutePath() + " " + file.length());
            }
        }
    }

    public static List<AccountShare.Account> captureAll(Context context) throws AccountShare.Refused {
        Map<String, String> cookies = cookies();
        // Every session Facebook built this run, one per account it is signed into. The cookies
        // belong to the account signed in now; the others carry their own token.
        Map<String, SessionCapture.Session> sessions = SessionCapture.sessions();
        String signedIn = SessionCapture.uid();
        String currentUid = signedIn.isEmpty() ? AccountShare.uidOf(cookies) : signedIn;
        Scan scan = scan(context, cookies);

        SessionCapture.Session current = sessions.get(currentUid);
        String currentToken = current != null ? current.token : scan.tokens.get(currentUid);
        if (currentToken == null && currentUid.isEmpty() && scan.tokens.size() == 1) {
            // One token and no cookie to match it to: the account signed in is the one it names.
            Map.Entry<String, String> only = scan.tokens.entrySet().iterator().next();
            currentUid = only.getKey();
            currentToken = only.getValue();
        }

        List<AccountShare.Account> accounts = new ArrayList<>();
        String sessionToken = currentToken == null ? "" : currentToken;
        String capturedName = current != null ? current.name : SessionCapture.name();
        if (!cookies.isEmpty() || !currentUid.isEmpty() || !sessionToken.isEmpty()) {
            String name = displayName(capturedName, nameFor(scan, currentUid));
            if (name.isEmpty()) name = nameNearUid(context, currentUid);
            accounts.add(new AccountShare.Account(currentUid, name, sessionToken, cookies, scan.entries));
        }
        // The other accounts Facebook is signed into, each from its own context: a phone with two
        // accounts in the switcher exports both, and the second one's cookies come with it when
        // Facebook has set them for the account that was last signed in.
        for (Map.Entry<String, SessionCapture.Session> entry : sessions.entrySet()) {
            if (entry.getKey().equals(currentUid)) continue;
            String name = displayName(entry.getValue().name, nameFor(scan, entry.getKey()));
            accounts.add(new AccountShare.Account(entry.getKey(), name, entry.getValue().token,
                    Collections.<String, String>emptyMap(), Collections.<AccountShare.Entry>emptyList()));
        }
        for (Map.Entry<String, String> token : scan.tokens.entrySet()) {
            if (token.getKey().equals(currentUid) || sessions.containsKey(token.getKey())) continue;
            // A token the scan found without cookies is a session Facebook no longer signs in with,
            // or one another app wrote. With sessions in hand, it isn't an account to share.
            if (!sessionToken.isEmpty() || !sessions.isEmpty()) continue;
            accounts.add(new AccountShare.Account(
                    token.getKey(), nameFor(scan, token.getKey()), token.getValue(),
                    Collections.<String, String>emptyMap(), Collections.<AccountShare.Entry>emptyList()));
        }
        if (accounts.isEmpty()
                || (accounts.get(0).cookies.isEmpty() && accounts.get(0).token.isEmpty() && accounts.get(0).uid.isEmpty())) {
            throw new AccountShare.Refused("This Facebook has no account to export: its cookie store is empty and no "
                    + "token was found in its data. Open a Facebook link in its browser once, then try again.");
        }
        return accounts;
    }

    /** The cookies Facebook's own store holds for facebook.com, merged across the three addresses. */
    private static Map<String, String> cookies() {
        Map<String, String> cookies = new LinkedHashMap<>();
        CookieManager manager = CookieManager.getInstance();
        for (String url : URLS) {
            String header = null;
            try {
                header = manager.getCookie(url);
            } catch (RuntimeException failed) {
                // A build whose cookie store isn't up yet answers null; one that throws has nothing
                // to give, and the scan below may still find a session.
                Logger.printInfo(() -> "No cookies for " + url + ": " + failed.getClass().getSimpleName());
            }
            if (header == null || header.isEmpty()) continue;
            try {
                cookies.putAll(AccountShare.parseCookies(header));
            } catch (AccountShare.Refused refused) {
                Logger.printInfo(() -> "A cookie Facebook set can't be one: " + refused.getMessage());
            }
        }
        return cookies;
    }

    /** What one scan of the app's data folder found. */
    private static final class Scan {
        /** Token to the id it belongs to, in the order the scan met them. */
        final Map<String, String> tokens = new LinkedHashMap<>();
        /** The display name each id was found under, when one was. */
        final Map<String, String> names = new LinkedHashMap<>();
        /** Preference values that look like a sign-in, which an import writes back. */
        final List<AccountShare.Entry> entries = new ArrayList<>();
    }

    private static String nameFor(Scan scan, String uid) {
        String name = scan.names.get(uid);
        return name == null ? "" : name;
    }

    /**
     * The name to show: Facebook's own when the session carries a person's, else what the data
     * folder said. The session's value is a username on some builds, so one with no space in it
     * and an @ is left out.
     */
    private static String displayName(String captured, String scanned) {
        if (captured != null && captured.length() > 1 && captured.length() <= 80
                && !captured.contains("@") && captured.indexOf(' ') > 0) {
            return captured;
        }
        return scanned;
    }

    /**
     * The name Facebook caches beside [uid] anywhere in its data folder, or "": the profile object
     * it keeps for the signed-in person carries the id and the name together.
     */
    private static String nameNearUid(Context context, String uid) {
        if (uid.isEmpty()) return "";
        File data = new File(context.getApplicationInfo().dataDir);
        String[] found = {""};
        walkForName(data, 0, new int[]{0}, uid, found);
        return found[0];
    }

    /** Looks for [uid] in one folder and below, and takes the name written beside it. */
    private static void walkForName(File folder, int depth, int[] opened, String uid, String[] found) {
        if (!found[0].isEmpty() || depth > MAX_DEPTH || opened[0] >= MAX_SCAN_FILES) return;
        File[] files;
        try {
            files = folder.listFiles();
        } catch (SecurityException denied) {
            return;
        }
        if (files == null) return;
        for (File file : files) {
            if (opened[0] >= MAX_SCAN_FILES || !found[0].isEmpty()) return;
            if (file.isDirectory()) {
                if (!skipped(file.getName())) walkForName(file, depth + 1, opened, uid, found);
                continue;
            }
            if (file.length() > MAX_PREFERENCE_FILE) continue;
            opened[0]++;
            String text = readText(file);
            if (text == null) continue;
            // The id is written under several names by the stores Facebook keeps: a profile object, a
            // GraphQL node, a session record, and the cookie itself. Any of them can carry the name.
            Matcher id = Pattern.compile("(?:\"(?:user_id|uid|userid|profile_id|fbid|id)\"\\s*:\\s*\"?)"
                    + Pattern.quote(uid)).matcher(text);
            while (id.find()) {
                int from = Math.max(0, id.start() - 1024);
                int to = Math.min(text.length(), id.end() + 1024);
                Matcher name = NAME_NEAR.matcher(text.substring(from, to));
                if (name.find()) {
                    found[0] = unescape(name.group(1));
                    return;
                }
            }
        }
    }

    /** The text of one file, or null when it can't be read. */    @Nullable
    private static String readText(File file) {
        RandomAccessFile reader = null;
        try {
            reader = new RandomAccessFile(file, "r");
            int size = (int) Math.min(reader.length(), MAX_PREFERENCE_FILE);
            byte[] bytes = new byte[size];
            reader.readFully(bytes);
            return new String(bytes, StandardCharsets.ISO_8859_1);
        } catch (Exception unreadable) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (java.io.IOException ignored) {
                    // Nothing was open for writing.
                }
            }
        }
    }

    /** A name Facebook wrote with JSON escapes, as the characters they stand for. */
    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                out.append(ch);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 'u' && i + 4 < value.length()) {
                try {
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                } catch (NumberFormatException notAnEscape) {
                    out.append(next);
                }
            } else if (next == 'n' || next == 'r' || next == 't') {
                out.append(' ');
            } else {
                out.append(next);
            }
        }
        return out.toString();
    }

    /**
     * The sign-in values anywhere in Facebook's data folder, added to [cookies] when one of them
     * holds a cookie header, and returned with the token each account uses.
     */
    private static Scan scan(Context context, Map<String, String> cookies) {
        Scan scan = new Scan();
        File data = new File(context.getApplicationInfo().dataDir);
        walk(data, 0, new int[]{0}, scan, cookies);
        return scan;
    }

    /**
     * One folder, then its subfolders to {@link #MAX_DEPTH}: a preference file's values, and the
     * tokens out of every other file that could hold one.
     */
    private static void walk(File folder, int depth, int[] opened, Scan scan, Map<String, String> cookies) {
        if (depth > MAX_DEPTH || opened[0] >= MAX_SCAN_FILES || scan.tokens.size() >= MAX_TOKENS) return;
        File[] files;
        try {
            files = folder.listFiles();
        } catch (SecurityException denied) {
            Logger.printInfo(() -> "The folder " + folder + " wasn't listed: " + denied.getClass().getSimpleName());
            return;
        }
        if (files == null) return;
        java.util.Arrays.sort(files, (left, right) -> left.getName().compareTo(right.getName()));
        for (File file : files) {
            if (opened[0] >= MAX_SCAN_FILES || scan.tokens.size() >= MAX_TOKENS) return;
            String name = file.getName();
            if (file.isDirectory()) {
                if (!skipped(name)) walk(file, depth + 1, opened, scan, cookies);
                continue;
            }
            boolean preferences = "shared_prefs".equals(folder.getName()) && name.endsWith(".xml");
            if (file.length() > (preferences ? MAX_PREFERENCE_FILE : MAX_SCAN_FILE)) continue;
            opened[0]++;
            if (preferences) {
                String key = name.substring(0, name.length() - ".xml".length());
                for (Map.Entry<String, String> value : readStrings(file).entrySet()) {
                    if (scan.entries.size() < MAX_ENTRIES && looksLikeSession(value.getKey(), value.getValue())) {
                        scan.entries.add(new AccountShare.Entry(key, value.getKey(), value.getValue()));
                    }
                    // A community mod writes the cookies into one preference value as a header.
                    if (value.getValue().contains("c_user=")) {
                        try {
                            for (Map.Entry<String, String> cookie : AccountShare.parseCookies(value.getValue()).entrySet()) {
                                if (!cookies.containsKey(cookie.getKey())) cookies.put(cookie.getKey(), cookie.getValue());
                            }
                        } catch (AccountShare.Refused ignored) {
                            // Not a header after all; the value stays as a session value.
                        }
                    }
                    token(value.getValue(), scan);
                }
            } else {
                tokenInFile(file, scan);
            }
        }
    }

    /** Folders whose contents are code, caches or graphics, which never hold a sign-in. */
    private static boolean skipped(String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        return lowered.equals("cache") || lowered.equals("code_cache") || lowered.equals("oat")
                || lowered.equals("lib") || lowered.equals("gpucache") || lowered.equals("app_textures")
                || lowered.equals("app_webview") || lowered.equals("files_cache");
    }

    /** Every token in one plain value, with the id found around it. */
    private static void token(String value, Scan scan) {
        if (value.length() > AccountShare.MAX_SESSION_VALUE) return;
        Matcher match = TOKEN.matcher(value);
        while (match.find() && scan.tokens.size() < MAX_TOKENS) {
            add(match.group(), value, scan);
        }
    }

    /** The tokens in a file the app keeps data in, with the id and name found around each. */
    private static void tokenInFile(File file, Scan scan) {
        RandomAccessFile reader = null;
        try {
            reader = new RandomAccessFile(file, "r");
            long length = reader.length();
            int size = (int) Math.min(length, MAX_SCAN_FILE);
            byte[] bytes = new byte[size];
            reader.readFully(bytes);
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            Matcher match = TOKEN.matcher(text);
            while (match.find() && scan.tokens.size() < MAX_TOKENS) {
                int from = Math.max(0, match.start() - MAX_WINDOW);
                int to = Math.min(text.length(), match.end() + MAX_WINDOW);
                add(match.group(), text.substring(from, to), scan);
            }
        } catch (FileNotFoundException | SecurityException unavailable) {
            Logger.printInfo(() -> "The file " + file.getName() + " wasn't read: "
                    + unavailable.getClass().getSimpleName());
        } catch (Exception broken) {
            // A store Facebook was writing as it was read. What came before it still counts.
            Logger.printInfo(() -> "The file " + file.getName() + " stopped halfway: "
                    + broken.getClass().getSimpleName());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (java.io.IOException ignored) {
                    // Nothing was open for writing.
                }
            }
        }
    }

    /** Records [token] under the id found near it, and the name beside that id when one is there. */
    private static void add(String token, String around, Scan scan) {
        Matcher uid = UID_NEAR.matcher(around);
        if (!uid.find()) return;
        String id = uid.group(1);
        if (scan.tokens.containsKey(id)) return;
        scan.tokens.put(id, token);
        Matcher name = NAME_NEAR.matcher(around);
        if (name.find()) scan.names.put(id, name.group(1));
    }

    /**
     * Writes [accounts] into the cookie store and the preference files, and says how many values
     * were written. Facebook has to start again to read them.
     */
    public static int restoreAll(Context context, List<AccountShare.Account> accounts) {
        int written = 0;
        for (AccountShare.Account account : accounts) {
            written += restore(context, account);
        }
        return written;
    }

    /** Writes one account's cookies and preference values back, and says how many were written. */
    public static int restore(Context context, AccountShare.Account account) {
        int written = 0;
        CookieManager manager = CookieManager.getInstance();
        for (Map.Entry<String, String> cookie : account.cookies.entrySet()) {
            String value = cookie.getKey() + "=" + cookie.getValue() + COOKIE_ATTRIBUTES;
            for (String url : URLS) {
                try {
                    manager.setCookie(url, value);
                } catch (RuntimeException failed) {
                    Logger.printInfo(() -> "The cookie " + cookie.getKey() + " wasn't set for " + url
                            + ": " + failed.getClass().getSimpleName());
                }
            }
            written++;
        }
        try {
            manager.flush();
        } catch (RuntimeException failed) {
            Logger.printInfo(() -> "The cookies weren't flushed: " + failed.getClass().getSimpleName());
        }

        // Grouped by file, so each preference file is opened once and committed once.
        Map<String, List<AccountShare.Entry>> byFile = new LinkedHashMap<>();
        for (AccountShare.Entry entry : account.session) {
            List<AccountShare.Entry> values = byFile.get(entry.file);
            if (values == null) {
                values = new ArrayList<>();
                byFile.put(entry.file, values);
            }
            values.add(entry);
        }
        for (Map.Entry<String, List<AccountShare.Entry>> file : byFile.entrySet()) {
            SharedPreferences preferences;
            try {
                preferences = context.getSharedPreferences(file.getKey(), Context.MODE_PRIVATE);
            } catch (RuntimeException failed) {
                Logger.printInfo(() -> "The preference file " + file.getKey() + " wasn't opened: "
                        + failed.getClass().getSimpleName());
                continue;
            }
            SharedPreferences.Editor editor = preferences.edit();
            for (AccountShare.Entry entry : file.getValue()) {
                editor.putString(entry.key, entry.value);
                written++;
            }
            // Committed, not applied: the caller shows a result once this returns, and a restart
            // right after it has to find the values on disk.
            editor.commit();
        }
        return written;
    }

    /** Whether [key] and [value] are a sign-in, by the names Facebook stores them under. */
    private static boolean looksLikeSession(String key, @Nullable String value) {
        if (value == null || value.isEmpty()) return false;
        String lowered = key.toLowerCase(Locale.ROOT);
        for (String sessionKey : SESSION_KEYS) {
            if (lowered.equals(sessionKey) || lowered.endsWith("_" + sessionKey)) return true;
        }
        return AccountShare.isSignInValue(value);
    }

    /** The {@code <string>} values in one preference file, or an empty map when it can't be read. */
    private static Map<String, String> readStrings(File file) {
        Map<String, String> values = new LinkedHashMap<>();
        FileInputStream stream = null;
        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);
            String name = null;
            StringBuilder text = null;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    name = "string".equals(parser.getName()) ? parser.getAttributeValue(null, "name") : null;
                    text = name == null ? null : new StringBuilder();
                } else if (event == XmlPullParser.TEXT && text != null) {
                    text.append(parser.getText());
                } else if (event == XmlPullParser.END_TAG && name != null && "string".equals(parser.getName())) {
                    values.put(name, text == null ? "" : text.toString());
                    name = null;
                    text = null;
                }
            }
        } catch (FileNotFoundException | SecurityException unavailable) {
            Logger.printInfo(() -> "The preference file " + file.getName() + " wasn't read: "
                    + unavailable.getClass().getSimpleName());
        } catch (Exception broken) {
            // A file Facebook was writing as it was read. What came before it is still usable.
            Logger.printInfo(() -> "The preference file " + file.getName() + " stopped halfway: "
                    + broken.getClass().getSimpleName());
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (java.io.IOException ignored) {
                    // Nothing was open for writing.
                }
            }
        }
        return values.isEmpty() ? Collections.<String, String>emptyMap() : values;
    }
}
