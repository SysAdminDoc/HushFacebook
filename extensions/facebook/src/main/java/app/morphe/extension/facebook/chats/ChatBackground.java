/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.theme.MarkColors;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Chat background (#573): a color of your own behind the messages of a chat that opens inside
 * Facebook, drawn on this phone only. Nothing is sent, so nobody in the chat is told and the chat's
 * shared theme stays as it is.
 *
 * <p>Facebook's chat screen is MsysThreadViewFragment, and the patch hands its {@link #threadView}
 * the fragment and its root view first thing in onViewCreated. From then on, just before each frame
 * of that screen is drawn, the message list (the biggest RecyclerView under the root) gets the
 * chat's color as its background, and gets it again whenever Facebook puts its own back, so a theme
 * someone else picks never covers it. The list sits over whatever Facebook paints behind it, and the
 * messages sit over the list.
 *
 * <p>Which color: the chat's own from {@link Settings#CHAT_BACKGROUNDS}, keyed by the fragment's
 * thread key, or else {@link Settings#CHAT_BACKGROUND_COLOR}. One code is a solid color, two are a
 * fade from top to bottom. Blank everywhere, paused, or settings that aren't ready yet, and the list
 * keeps (or gets back) Facebook's own background.
 *
 * <p>The chat on screen is remembered in {@link Settings#CHAT_BACKGROUND_LAST} with the name on its
 * top bar, so the settings can give that chat a color of its own.
 */
public final class ChatBackground {
    /** What the diagnostic report counts each time a chat's list takes a picked color. */
    static final String PAINTED = "chat background painted";

    private static final String RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView";
    private static final String THREAD_KEY = "com.facebook.messaging.model.threadkey.ThreadKey";

    /** How far below the top of the chat its name can sit, in dp: a top bar's height and a little. */
    static final int TITLE_BAND_DP = 64;

    /**
     * The name spans most of the top bar, so a tappable view narrower than this share of it is a
     * button (Back, the call buttons), even while it's the only one with a label: Facebook mounts
     * the back arrow a few frames before the name.
     */
    private static final float TITLE_MIN_SHARE = 1 / 3f;

    /** How long a chat whose list isn't drawn yet waits before looking for it again, in ms. */
    private static final long LOOK_AGAIN_MS = 250;

    /** How long a chat keeps the list it found before checking that it's still the biggest, in ms. */
    private static final long RECHECK_MS = 1000;

    /** The longest chat name kept for the settings to show. */
    static final int NAME_MAX = 80;

    /** Whether each view class seen is a RecyclerView. Only the main thread asks. */
    private static final Map<Class<?>, Boolean> LISTS = new HashMap<>();

    /** The thread key field of each fragment class seen, or null when it has none. */
    private static final Map<Class<?>, Field> KEY_FIELDS = new HashMap<>();

    private static String chatsRead = "";
    private static Map<String, String> chatsParsed = Collections.emptyMap();

    private ChatBackground() {
    }

    /** Injection point, first in MsysThreadViewFragment.onViewCreated(View, Bundle). */
    public static void threadView(@Nullable Object fragment, @Nullable View root) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_BACKGROUND);
            if (fragment == null || root == null) return;
            Painter painter = new Painter(fragment, root);
            root.addOnAttachStateChangeListener(painter);
            if (root.isAttachedToWindow()) painter.onViewAttachedToWindow(root);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_BACKGROUND, PAINTED, failure);
        }
    }

    /** The code the chat with [key] takes: its own, or the one for every chat. Blank keeps Facebook's own. */
    static String codeFor(@Nullable String key) {
        if (key != null) {
            String own = chats(Settings.CHAT_BACKGROUNDS.get()).get(key);
            if (own != null && !own.isEmpty()) return own;
        }
        String every = Settings.CHAT_BACKGROUND_COLOR.get();
        return every == null ? "" : every;
    }

    /** The chats with their own color, by thread key, from the saved {@code key<TAB>code} lines. */
    static Map<String, String> chats(@Nullable String raw) {
        String text = raw == null ? "" : raw;
        synchronized (ChatBackground.class) {
            if (text.equals(chatsRead)) return chatsParsed;
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String code = stored(line.substring(tab + 1));
            if (code != null && !code.isEmpty()) parsed.put(line.substring(0, tab), code);
        }
        Map<String, String> result = Collections.unmodifiableMap(parsed);
        synchronized (ChatBackground.class) {
            chatsRead = text;
            chatsParsed = result;
        }
        return result;
    }

    /** [raw] with the chat [key] given [code], or taken out when [code] is blank. */
    static String withChat(@Nullable String raw, String key, @Nullable String code) {
        Map<String, String> chats = new LinkedHashMap<>(chats(raw));
        String clean = stored(code);
        if (clean == null || clean.isEmpty()) chats.remove(key);
        else chats.put(key, clean);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> chat : chats.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(chat.getKey()).append('\t').append(chat.getValue());
        }
        return out.toString();
    }

    /** The codes in [text], split on spaces or commas. Empty when it's blank. */
    static String[] codes(@Nullable String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) return new String[0];
        return trimmed.split("[\\s,;]+");
    }

    /** The one or two opaque colors [text] names, top first, or null when it's blank or names none. */
    @Nullable
    static int[] colours(@Nullable String text) {
        String[] parts = codes(text);
        if (parts.length == 0 || parts.length > 2) return null;
        int[] colours = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Integer colour = MarkColors.colour(parts[i]);
            if (colour == null) return null;
            colours[i] = colour;
        }
        return colours;
    }

    /** [text] as a row keeps it: blank, or one or two {@code #RRGGBB} codes in capitals. Null when it's refused. */
    @Nullable
    public static String stored(@Nullable String text) {
        String[] parts = codes(text);
        if (parts.length == 0) return "";
        if (parts.length > 2) return null;
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String one = MarkColors.stored(part);
            if (one == null || one.isEmpty()) return null;
            if (out.length() > 0) out.append(' ');
            out.append(one);
        }
        return out.toString();
    }

    /** Why a row refuses [text], in words the person typing can act on, or null when it's taken. Blank is taken. */
    @Nullable
    public static String problem(@Nullable String text) {
        String[] parts = codes(text);
        if (parts.length > 2) return L10n.t("Type one color code, or two for a fade from top to bottom.");
        for (String part : parts) {
            String why = MarkColors.problem(part);
            if (why != null) return why;
        }
        return null;
    }

    /** What the list is painted with for [colours]: the color, or a fade from the first down to the second. */
    static Drawable drawable(int[] colours) {
        if (colours.length == 1) return new ColorDrawable(colours[0]);
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colours);
    }

    /** The last chat seen, as {key, name}, or null when none has been. */
    @Nullable
    public static String[] lastChat() {
        String saved = Settings.CHAT_BACKGROUND_LAST.savedValue();
        if (saved == null) return null;
        int tab = saved.indexOf('\t');
        if (tab <= 0) return null;
        String key = saved.substring(0, tab);
        String name = saved.substring(tab + 1).trim();
        if (name.isEmpty()) name = key.substring(key.indexOf(':') + 1);
        return new String[]{key, name};
    }

    /** The code the chat with [key] has of its own, saved and paused alike. Blank when it has none. */
    public static String ownCode(String key) {
        String own = chats(Settings.CHAT_BACKGROUNDS.savedValue()).get(key);
        return own == null ? "" : own;
    }

    /** Gives the chat with [key] [code] of its own, or takes its own away when [code] is blank. */
    public static void saveOwnCode(String key, @Nullable String code) {
        Settings.CHAT_BACKGROUNDS.save(withChat(Settings.CHAT_BACKGROUNDS.savedValue(), key, code));
    }

    /** [name] as the settings keep it: one line, trimmed, at most {@link #NAME_MAX} characters. */
    static String cleanName(@Nullable CharSequence name) {
        String text = name == null ? "" : name.toString().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > NAME_MAX ? text.substring(0, NAME_MAX).trim() : text;
    }

    /** The thread key of [fragment] as text, or null when it has none yet. */
    @Nullable
    static Object threadKey(Object fragment) throws IllegalAccessException {
        Field field = keyField(fragment.getClass());
        return field == null ? null : field.get(fragment);
    }

    @Nullable
    private static Field keyField(Class<?> type) {
        synchronized (KEY_FIELDS) {
            if (KEY_FIELDS.containsKey(type)) return KEY_FIELDS.get(type);
        }
        Field found = null;
        for (Class<?> c = type; c != null && c != Object.class && found == null; c = c.getSuperclass()) {
            Field typed = null;
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !THREAD_KEY.equals(field.getType().getName())) continue;
                // pendingAuthoritativeThreadKey is a key too, so the one named threadKey goes first.
                if ("threadKey".equals(field.getName())) {
                    typed = field;
                    break;
                }
                if (typed == null) typed = field;
            }
            found = typed;
        }
        if (found != null) found.setAccessible(true);
        synchronized (KEY_FIELDS) {
            KEY_FIELDS.put(type, found);
        }
        return found;
    }

    /** Whether [view] is a RecyclerView. */
    static boolean isList(View view) {
        Class<?> type = view.getClass();
        Boolean known = LISTS.get(type);
        if (known != null) return known;
        boolean list = false;
        for (Class<?> c = type; c != null && c != View.class; c = c.getSuperclass()) {
            if (RECYCLER_VIEW.equals(c.getName())) {
                list = true;
                break;
            }
        }
        LISTS.put(type, list);
        return list;
    }

    /** The biggest RecyclerView on screen under [root], which in a chat is its messages. Null when none is. */
    @Nullable
    static View messageList(View root) {
        View best = null;
        long bestArea = 0;
        ArrayDeque<View> todo = new ArrayDeque<>();
        todo.add(root);
        while (!todo.isEmpty()) {
            View view = todo.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            if (isList(view)) {
                long area = (long) view.getWidth() * view.getHeight();
                if (area > bestArea) {
                    best = view;
                    bestArea = area;
                }
                // A list's rows are messages, never another chat's list.
                continue;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) todo.add(group.getChildAt(i));
            }
        }
        return best;
    }

    /**
     * The chat's name on its top bar: in the widest tappable view with a label near the top of
     * [root], the first label inside it (the name line, without the "Active now" under it), or the
     * tappable view's own. Null when the top bar has none, or only buttons too narrow to be a name.
     */
    @Nullable
    static String title(View root) {
        int[] rootAt = new int[2];
        root.getLocationInWindow(rootAt);
        float density = Resources.getSystem().getDisplayMetrics().density;
        int band = Math.round(TITLE_BAND_DP * (density > 0 ? density : 1f));
        int narrowest = Math.round(root.getWidth() * TITLE_MIN_SHARE);
        View widest = null;
        int[] at = new int[2];
        ArrayDeque<View> todo = new ArrayDeque<>();
        todo.add(root);
        while (!todo.isEmpty()) {
            View view = todo.poll();
            if (view.getVisibility() != View.VISIBLE || isList(view)) continue;
            view.getLocationInWindow(at);
            int top = at[1] - rootAt[1];
            if (top > band) continue;
            CharSequence label = view.getContentDescription();
            if (view.isClickable() && label != null && label.toString().trim().length() > 0
                    && top + view.getHeight() / 2 <= band && view.getWidth() >= narrowest
                    && (widest == null || view.getWidth() > widest.getWidth())) {
                widest = view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) todo.add(group.getChildAt(i));
            }
        }
        if (widest == null) return null;
        if (widest instanceof ViewGroup) {
            ArrayDeque<View> inside = new ArrayDeque<>();
            ViewGroup group = (ViewGroup) widest;
            for (int i = 0; i < group.getChildCount(); i++) inside.add(group.getChildAt(i));
            while (!inside.isEmpty()) {
                View view = inside.poll();
                CharSequence label = view.getContentDescription();
                if (label != null && label.toString().trim().length() > 0) return cleanName(label);
                if (view instanceof ViewGroup) {
                    ViewGroup child = (ViewGroup) view;
                    for (int i = 0; i < child.getChildCount(); i++) inside.add(child.getChildAt(i));
                }
            }
        }
        return cleanName(widest.getContentDescription());
    }

    /** One chat screen: paints its list before each frame while the screen is in a window. */
    static final class Painter implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final Object fragment;
        private final View root;
        @Nullable private ViewTreeObserver observer;
        private boolean broken;

        @Nullable private Object keyObject;
        @Nullable private String key;
        @Nullable private String remembered;
        private int nameTries;

        @Nullable private WeakReference<View> list;
        private long lookedAt;
        @Nullable private Drawable ours;
        @Nullable private String oursCode;
        @Nullable private Drawable facebooks;

        /** The code last read for this chat, as read, as kept, and its colors, so a frame parses nothing new. */
        @Nullable private String rawCode;
        @Nullable private String code;
        @Nullable private int[] colours;

        Painter(Object fragment, View root) {
            this.fragment = fragment;
            this.root = root;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (broken || observer != null) return;
            observer = view.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stop();
        }

        private void stop() {
            ViewTreeObserver attached = observer;
            observer = null;
            if (attached != null && attached.isAlive()) attached.removeOnPreDrawListener(this);
            else root.getViewTreeObserver().removeOnPreDrawListener(this);
        }

        @Override
        public boolean onPreDraw() {
            if (broken) return true;
            try {
                paint();
            } catch (Throwable failure) {
                // A failure each frame would fill the log, so this chat screen stops here.
                broken = true;
                stop();
                HookStatus.threw(FamilyNames.CHAT_BACKGROUND, PAINTED, failure);
            }
            return true;
        }

        void paint() throws IllegalAccessException {
            if (!Utils.settingsReady()) return;
            Object current = threadKey(fragment);
            if (current != keyObject) {
                keyObject = current;
                key = current == null ? null : current.toString();
            }
            if (root.isShown()) remember();
            View shown = list == null ? null : list.get();
            boolean gone = shown == null || !shown.isAttachedToWindow() || !shown.isShown();
            long time = System.currentTimeMillis();
            // A smaller list can be up before the messages are, so a found one is checked again now and then.
            if (gone ? time - lookedAt >= LOOK_AGAIN_MS : time - lookedAt >= RECHECK_MS) {
                lookedAt = time;
                View found = messageList(root);
                if (found != shown) {
                    if (shown != null && ours != null && shown.getBackground() == ours) shown.setBackground(facebooks);
                    list = found == null ? null : new WeakReference<>(found);
                    ours = null;
                    oursCode = null;
                    facebooks = null;
                    shown = found;
                    gone = found == null;
                }
            }
            if (gone) return;
            String raw = codeFor(key);
            if (!raw.equals(rawCode)) {
                rawCode = raw;
                code = stored(raw);
                colours = code == null ? null : colours(code);
            }
            Drawable now = shown.getBackground();
            if (colours == null) {
                if (ours != null && now == ours) shown.setBackground(facebooks);
                ours = null;
                oursCode = null;
                return;
            }
            if (ours != null && now == ours && code.equals(oursCode)) return;
            if (now != ours) facebooks = now;
            ours = drawable(colours);
            oursCode = code;
            shown.setBackground(ours);
            HookStatus.counted(FamilyNames.CHAT_BACKGROUND, PAINTED);
        }

        /** Saves this chat as the last one seen, with its name once the top bar shows it. */
        private void remember() {
            if (key == null) return;
            if (!key.equals(remembered)) {
                remembered = key;
                nameTries = 0;
            } else if (nameTries < 0) {
                return;
            }
            String name = title(root);
            // The top bar can be drawn a few frames after the list, so a missing name is asked for again,
            // and the chat counts as the last one meanwhile, keeping a name it was saved with before.
            if (name == null && ++nameTries < 60) {
                String saved = Settings.CHAT_BACKGROUND_LAST.savedValue();
                if (nameTries == 1 && (saved == null || !saved.startsWith(key + "\t"))) save("");
                return;
            }
            nameTries = -1;
            if (name != null) save(name);
        }

        private void save(String name) {
            String value = key + "\t" + name;
            if (!value.equals(Settings.CHAT_BACKGROUND_LAST.savedValue())) Settings.CHAT_BACKGROUND_LAST.save(value);
        }
    }
}
