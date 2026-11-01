/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Asks Google Translate for a post's text, on the keyless address its own widget uses. The text
 * goes in the body of a POST, so a long post isn't limited by how long a URL can be. Blocks, so
 * callers stay off the main thread.
 */
final class GoogleTranslate {
    /** The most text one request carries. A longer post's translation stops about here. */
    static final int LONGEST = 5000;

    private static final String ADDRESS = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl=";
    private static final int CONNECT_MILLIS = 5000;
    private static final int READ_MILLIS = 8000;
    private static final int LARGEST_ANSWER = 1 << 20;
    private static final int TOO_MANY_REQUESTS = 429;

    private GoogleTranslate() {
    }

    /** What came back: the translated text, and the language Google took the text to be in. */
    static final class Answer {
        final String text;
        @Nullable
        final String from;

        Answer(String text, @Nullable String from) {
            this.text = text;
            this.from = from;
        }
    }

    /** Google asked for fewer requests for a while. */
    static final class Limited extends IOException {
        Limited() {
            super("Google Translate asked for fewer requests");
        }
    }

    static Answer translate(String text, String target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(ADDRESS
                + URLEncoder.encode(target, "UTF-8")).openConnection();
        try {
            byte[] body = ("q=" + URLEncoder.encode(text, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            connection.setConnectTimeout(CONNECT_MILLIS);
            connection.setReadTimeout(READ_MILLIS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int code = connection.getResponseCode();
            if (code == TOO_MANY_REQUESTS) throw new Limited();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("Google Translate answered " + code);
            try (InputStream in = connection.getInputStream()) {
                return parse(read(in));
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Reads an answer shaped {@code [[["Hello ","Hola ",...],["world","mundo",...]],null,"es",...]}. */
    static Answer parse(String answer) throws IOException {
        try {
            JSONArray whole = new JSONArray(answer);
            JSONArray pieces = whole.getJSONArray(0);
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < pieces.length(); index++) {
                JSONArray piece = pieces.optJSONArray(index);
                if (piece != null && !piece.isNull(0)) text.append(piece.getString(0));
            }
            if (text.length() == 0) throw new IOException("Google Translate sent no text");
            String from = whole.isNull(2) ? null : whole.optString(2);
            return new Answer(text.toString(), from);
        } catch (JSONException unreadable) {
            throw new IOException("Google Translate's answer couldn't be read", unreadable);
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (out.size() + read > LARGEST_ANSWER) throw new IOException("Google Translate's answer was too long");
            out.write(chunk, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
