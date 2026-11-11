/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.reels.PostTextSelect;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * Asks "Like or unlike this post?" before a tap on Like goes through (#437, #443). The hook sits
 * first in each of Facebook's Like handlers: the feed footer's four, the Reels sidebar's Like and
 * a comment's two (the comment list's and the one drawn inline under a feed post). It hands over the handler's own descriptor and arguments, and while the
 * switch for that kind is on, the handler returns without liking and a question goes up. Continue
 * runs the same handler again with the same arguments, which this time passes, so the like is
 * Facebook's own. Cancel, Back or a tap outside leaves it untouched.
 *
 * <p>One question is up at a time; a tap that comes while it's showing over the same screen is held
 * without a second one. Off, paused, before the settings are ready, with no screen to ask on, or
 * when anything here fails, the tap is Facebook's own.
 */
public final class AskBeforeLike {
    /** The feed footer built as a litho component. */
    public static final int POST_FOOTER = 0;
    /** The feed footer built from UFI plugins. */
    public static final int POST_PLUGIN = 1;
    /** The Like in a reel's sidebar. */
    public static final int REEL = 2;
    /** The Like under a comment. */
    public static final int COMMENT = 3;
    /** The UFI plugin footer's like sender, reached from its touch and click handlers. */
    public static final int POST_TOUCH = 4;
    /**
     * The litho feed footer's click lambda, which calls {@link #POST_FOOTER}'s handler and then
     * paints the button liked, so holding the handler alone left the button showing a like.
     */
    public static final int POST_CLICK = 5;
    /** The Like in the comment actions drawn inline under a feed post, a span that hands over the tapped view. */
    public static final int COMMENT_INLINE = 6;

    private static final String[] KINDS = {"post footer", "post plugin footer", "reel", "comment", "post footer touch", "post footer click",
            "inline comment"};
    private static final int CONTEXT_DEPTH = 10;
    static final String ASKED = "asked before a like";
    static final String HELD = "held a tap while asking";

    /** Each handler the hooks name, found once. */
    private static final Map<String, Method> HANDLERS = new ConcurrentHashMap<>();

    /** True while Continue runs a handler again, so its hook lets it through. Main thread only. */
    private static boolean replaying;

    @Nullable
    private static WeakReference<Dialog> open;

    private AskBeforeLike() {
    }

    /**
     * First thing in a Like handler of [kind], named by its [descriptor] (Lowner;->name(params)ret)
     * and called with [call]: the instance first, null for a static, then each argument. True holds
     * the tap back while the question is up; false lets Facebook like as it always does. Never
     * throws.
     */
    public static boolean hold(int kind, String descriptor, Object[] call) {
        try {
            HookStatus.invoked(FamilyNames.ASK_BEFORE_LIKE);
            if (replaying || !Utils.settingsReady()) return false;
            BooleanSetting setting = settingOf(kind);
            if (setting == null || !setting.get()) return false;
            Context screen = screenOf(call);
            Dialog up = open == null ? null : open.get();
            if (showingOver(up, screen)) {
                HookStatus.counted(FamilyNames.ASK_BEFORE_LIKE, HELD);
                return true;
            }
            Dialog question = ask(screen, kind, descriptor, call);
            if (question == null) {
                Logger.printDebug(() -> "Ask before a like: no screen to ask on, the " + KINDS[kind] + " like goes through");
                return false;
            }
            open = new WeakReference<>(question);
            HookStatus.counted(FamilyNames.ASK_BEFORE_LIKE, ASKED);
            Logger.printDebug(() -> "Ask before a like: asked before a " + KINDS[kind] + " like");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_LIKE, "ask before a like", failure);
            return false;
        }
    }

    @Nullable
    private static BooleanSetting settingOf(int kind) {
        switch (kind) {
            case POST_FOOTER:
            case POST_PLUGIN:
            case POST_TOUCH:
            case POST_CLICK:
                return Settings.ASK_BEFORE_LIKING_POSTS;
            case REEL:
                return Settings.ASK_BEFORE_LIKING_REELS;
            case COMMENT:
            case COMMENT_INLINE:
                return Settings.ASK_BEFORE_LIKING_COMMENTS;
            default:
                return null;
        }
    }

    private static String questionOf(Context context, int kind) {
        if (kind == REEL) return L10n.t(context, "Like or unlike this reel?");
        if (kind == COMMENT || kind == COMMENT_INLINE) return L10n.t(context, "Like or unlike this comment?");
        return L10n.t(context, "Like or unlike this post?");
    }

    /**
     * The screen the tap came from: the context of the first view in [call], else its first
     * context, else the first screen one of its values keeps in a field (the plugin footer's sender
     * takes no view, only a component context holding one, and the click lambda takes a click
     * event holding the view).
     */
    @Nullable
    static Context screenOf(Object[] call) throws IllegalAccessException {
        for (Object value : call) {
            if (value instanceof View) return ((View) value).getContext();
        }
        for (Object value : call) {
            if (value instanceof Context) return (Context) value;
        }
        for (Object value : call) {
            Context kept = screenKeptIn(value);
            if (kept != null) return kept;
        }
        return null;
    }

    /**
     * A context [value] keeps in an instance field, of its class or one above, that leads to an
     * activity: the field's own, or the context of a view it keeps.
     */
    @Nullable
    private static Context screenKeptIn(@Nullable Object value) throws IllegalAccessException {
        if (value == null) return null;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> kind = field.getType();
                if (!Context.class.isAssignableFrom(kind) && !View.class.isAssignableFrom(kind)) continue;
                field.setAccessible(true);
                Object kept = field.get(value);
                Context context = kept instanceof View ? ((View) kept).getContext() : kept instanceof Context ? (Context) kept : null;
                if (context != null && activityOf(context) != null) return context;
            }
        }
        return null;
    }

    @Nullable
    private static AlertDialog ask(@Nullable Context screen, int kind, String descriptor, Object[] call) {
        Activity activity = activityOf(screen);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        Object[] held = call.clone();
        AlertDialog question = new AlertDialog.Builder(activity, PostTextSelect.dark(activity)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setMessage(questionOf(activity, kind))
                .setPositiveButton(L10n.t(activity, "Continue"), (shown, which) -> again(kind, descriptor, held))
                .setNegativeButton(L10n.t(activity, "Cancel"), null)
                .create();
        question.setOnDismissListener(gone -> forget(question));
        question.show();
        return question;
    }

    /** Runs the held handler again, which its hook lets through this time. Never throws. */
    static void again(int kind, String descriptor, Object[] call) {
        replaying = true;
        try {
            handler(descriptor).invoke(call[0], Arrays.copyOfRange(call, 1, call.length));
            Logger.printDebug(() -> "Ask before a like: the " + KINDS[kind] + " like went through");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_LIKE, "like after asking", failure);
        } finally {
            replaying = false;
        }
    }

    private static void forget(Dialog gone) {
        Dialog up = open == null ? null : open.get();
        if (up == null || up == gone) open = null;
    }

    /** Whether [question] is still up over the screen [from] belongs to. */
    private static boolean showingOver(@Nullable Dialog question, @Nullable Context from) {
        if (question == null || !question.isShowing()) return false;
        Activity activity = activityOf(question.getContext());
        return activity != null && activity == activityOf(from) && !activity.isFinishing() && !activity.isDestroyed();
    }

    /** The handler [descriptor] names, as Lowner;->name(params)ret, found once and kept. */
    static Method handler(String descriptor) throws ReflectiveOperationException {
        Method known = HANDLERS.get(descriptor);
        if (known != null) return known;
        int arrow = descriptor.indexOf("->");
        int start = descriptor.indexOf('(', arrow);
        int end = descriptor.indexOf(')', start);
        if (arrow < 0 || start < 0 || end < 0) throw new NoSuchMethodException(descriptor);
        List<Class<?>> parameters = new ArrayList<>();
        for (int at = start + 1; at < end; ) {
            int last = at;
            while (descriptor.charAt(last) == '[') last++;
            if (descriptor.charAt(last) == 'L') last = descriptor.indexOf(';', last);
            parameters.add(type(descriptor.substring(at, last + 1)));
            at = last + 1;
        }
        Method found = type(descriptor.substring(0, arrow))
                .getDeclaredMethod(descriptor.substring(arrow + 2, start), parameters.toArray(new Class<?>[0]));
        found.setAccessible(true);
        HANDLERS.put(descriptor, found);
        return found;
    }

    /** The class a dex type descriptor names. */
    static Class<?> type(String name) throws ClassNotFoundException {
        switch (name) {
            case "Z": return boolean.class;
            case "B": return byte.class;
            case "C": return char.class;
            case "S": return short.class;
            case "I": return int.class;
            case "J": return long.class;
            case "F": return float.class;
            case "D": return double.class;
            default:
                String binary = name.startsWith("[") ? name : name.substring(1, name.length() - 1);
                return Class.forName(binary.replace('/', '.'), false, AskBeforeLike.class.getClassLoader());
        }
    }

    @Nullable
    private static Activity activityOf(@Nullable Context context) {
        for (int depth = 0; context != null && depth < CONTEXT_DEPTH; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
