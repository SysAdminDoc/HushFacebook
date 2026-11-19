/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.widget.ImageView;

import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;

import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Long press to save Marketplace photos' hooks, for tests in any package: the sources React Native
 * hands an image view, as its image manager's setSource does.
 */
public final class ListingPhotoForTests {
    private ListingPhotoForTests() {
    }

    /** A listing photo's full size on Meta's CDN. */
    public static final String FULL = "https://scontent-mia3-1.xx.fbcdn.net/v/t45.5328-4/1_n.jpg?stp=dst-jpg_s2048x2048_tt6&oh=00_A&oe=6A1B2C3D";

    /** One source as React Native hands it over: its address and, when it's known, its size. */
    public static final class Source implements ReadableMap {
        final Map<String, Object> values = new HashMap<>();

        public Source(String uri, double width, double height) {
            this(uri);
            values.put("width", width);
            values.put("height", height);
        }

        public Source(String uri) {
            if (uri != null) values.put("uri", uri);
        }

        @Override
        public boolean hasKey(String name) {
            return values.containsKey(name);
        }

        @Override
        public String getString(String name) {
            Object value = values.get(name);
            return value instanceof String ? (String) value : null;
        }

        @Override
        public double getDouble(String name) {
            Object value = values.get(name);
            if (!(value instanceof Double)) throw new IllegalArgumentException("no number under " + name);
            return (Double) value;
        }
    }

    /** The array of sources setSource is handed. A null entry is one that isn't a map. */
    public static final class Sources implements ReadableArray {
        final List<Source> sources;

        public Sources(Source... sources) {
            this.sources = Arrays.asList(sources);
        }

        @Override
        public int size() {
            return sources.size();
        }

        @Override
        public ReadableMap getMap(int index) {
            return sources.get(index);
        }
    }

    /** A listing photo handed to the hook the way setSource hands it over: whether its address was kept. */
    public static boolean keepsTheAddress() {
        ImageView photo = new ImageView(RuntimeEnvironment.getApplication());
        ListingPhoto.source(photo, new Sources(new Source(FULL, 2048, 1536)));
        return ListingPhoto.addressOf(photo) != null;
    }
}
