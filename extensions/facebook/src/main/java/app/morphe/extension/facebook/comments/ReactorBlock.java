/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Block button on reaction lists (#271): the button at the end of each row in the list of people
 * who reacted to a post (Add friend, Cancel request, Confirm, Message) becomes a Block button that
 * runs Facebook's own block flow for that person, confirmation included, the way
 * {@link CommentBlock} does under comments.
 *
 * <p>The row draws its own button unless its state built Facebook's shared friending button, so
 * {@link #sharedButton} drops that one while the switch is on. The own button's bind names its
 * component and view first ({@link #binding}) and says when it's done ({@link #bound}), and only
 * then is the view relabeled, so Facebook's colors, size and background for the relation stay.
 * Binds run one at a time on the main thread, so the pair waits in two fields; a bind anywhere
 * else is left to Facebook.
 *
 * <p>A row Facebook hid (the viewer's own, and people it offers no button for) stays hidden. Views
 * are reused from row to row, so a tap reads the person the view's last bind left, and a bind
 * that doesn't relabel the view takes that person away.
 *
 * <p>The person is read off the row's holder through the kept {@code getId} and {@code getName}
 * of the profile it holds. Nothing here logs an id or a name.
 */
public final class ReactorBlock {
    static final String SESSION_CLASS = "com.facebook.auth.usersession.FbUserSession";

    /** The member the report names once a row's button bind has asked. */
    static final String BLOCK_BIND = "reaction list block button";

    /** Counted each time a tap opens Facebook's block sheet. */
    static final String BLOCK_OPENED = "Reaction list Block sheet opened";

    /** Counted, with the reason in the log, each time Facebook's block sheet fails after a tap here. */
    static final String BLOCK_FAILED = "Reaction list Block sheet failed";

    private static final String FAMILY = FamilyNames.COMMENT_SHEET_OPTIONS;

    /** Who each relabeled view blocks, until a bind of that view leaves it to Facebook. */
    private static final Map<View, Target> TARGETS = new WeakHashMap<>();

    /** The bind in progress, held weakly so a bind that throws before its return can't keep a view alive. */
    private static WeakReference<Object> bindingButton = new WeakReference<>(null);

    private static WeakReference<Object> bindingView = new WeakReference<>(null);

    /** A relabeled view's person and the classes its tap hands Facebook's helper. */
    private static final class Target {
        final CommentBlock.Author person;
        final Class<?> helper;
        final Class<?> profile;
        final Class<?> source;

        Target(CommentBlock.Author person, Class<?> helper, Class<?> profile, Class<?> source) {
            this.person = person;
            this.helper = helper;
            this.profile = profile;
            this.source = source;
        }
    }

    private ReactorBlock() {
    }

    /** The hook where a reaction row's state stores Facebook's shared friending button: none while the switch is on. */
    @Nullable
    public static Object sharedButton(@Nullable Object facebookButton) {
        try {
            HookStatus.invoked(FAMILY);
            return on() ? null : facebookButton;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reaction list shared button", failure);
            return facebookButton;
        }
    }

    /** The hook, first thing in the row button's bind: the component being bound and its view. */
    public static void binding(@Nullable Object button, @Nullable Object view) {
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        bindingButton = new WeakReference<>(main ? button : null);
        bindingView = new WeakReference<>(main ? view : null);
    }

    /**
     * The hook at each return of the row button's bind. While the switch is on, the view Facebook
     * just bound says Block and its tap opens Facebook's block sheet for the row's person. A row
     * Facebook hid, the signed-in user's own row, a row drawn in a session that names no user and
     * a row whose person can't be read keep Facebook's button.
     *
     * @param holder  the class of the row's holder, the component field holding the person
     * @param helper  the class of Facebook's profile Block helper
     * @param profile the interface the helper reads the profile through
     * @param source  the enum of places the helper is opened from
     */
    public static void bound(Class<?> holder, Class<?> helper, Class<?> profile, Class<?> source) {
        Object button = bindingButton.get();
        Object view = bindingView.get();
        bindingButton = new WeakReference<>(null);
        bindingView = new WeakReference<>(null);
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, BLOCK_BIND);
            if (!(view instanceof TextView) || Looper.myLooper() != Looper.getMainLooper()) return;
            if (TARGETS.remove(view) != null) unlabel((TextView) view);
            if (button == null || !on() || ((TextView) view).getVisibility() != View.VISIBLE) return;
            CommentBlock.Author person = person(button, holder);
            if (person == null) return;
            long viewer = CommentBlock.viewerId(person.session);
            if (viewer == 0L || String.valueOf(viewer).equals(person.id)) return;
            relabel((TextView) view, new Target(person, helper, profile, source));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BLOCK_BIND, failure);
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.REACTOR_BLOCK_BUTTON.get();
    }

    /**
     * The person a row's [button] holds, and the session it's drawn in, or null when this build
     * can't say. The person is the holder's value answering both a numeric getId and a getName;
     * two such values naming different ids leave the row to Facebook, so the sheet can never open
     * for someone else on the row.
     */
    @Nullable
    static CommentBlock.Author person(Object button, Class<?> holder) throws ReflectiveOperationException {
        Class<?> sessionClass = Class.forName(SESSION_CLASS, false, button.getClass().getClassLoader());
        Object session = CommentBlock.fieldHolding(button, sessionClass);
        Object held = CommentBlock.fieldHolding(button, holder);
        if (session == null || held == null) return null;
        String id = null;
        String name = null;
        for (Class<?> at = held.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Object value = field.get(held);
                if (value == null) continue;
                String valueId = text(value, "getId");
                String valueName = text(value, "getName");
                if (valueId == null || valueName == null || !valueId.matches("\\d{1,19}")) continue;
                if (id != null && !id.equals(valueId)) return null;
                id = valueId;
                if (name == null || name.isEmpty()) name = valueName;
            }
        }
        if (id == null) return null;
        return new CommentBlock.Author(id, name == null || name.isEmpty() ? L10n.t("this person") : name, session);
    }

    /** What [value]'s public no-argument [name] method answers, when it answers a String. */
    @Nullable
    private static String text(Object value, String name) {
        try {
            Method method = value.getClass().getMethod(name);
            if (method.getReturnType() != String.class) return null;
            return (String) method.invoke(value);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    private static void relabel(TextView view, Target target) {
        String label = L10n.t("Block");
        view.setText(label);
        shortLabel(view, label);
        icon(view);
        view.setContentDescription(label);
        TARGETS.put(view, target);
        view.setOnClickListener(ReactorBlock::tap);
    }

    /**
     * The button view keeps a shorter label it swaps in when the long one doesn't fit, a
     * CharSequence field of its own class. It's set to Block too, so the swap can't bring back
     * Facebook's.
     */
    private static void shortLabel(TextView view, String label) {
        for (Class<?> at = view.getClass(); at != null && at != TextView.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != CharSequence.class) continue;
                try {
                    field.setAccessible(true);
                    field.set(view, label);
                } catch (ReflectiveOperationException | RuntimeException skipped) {
                    // The label still reads Block until a swap.
                }
            }
        }
    }

    /**
     * A view a bind left to Facebook after an earlier one said Block: whatever Facebook's bind
     * didn't write over still says Block, the description and the short label, so they go back to
     * Facebook's (the description to none, the short label to the label it just set).
     */
    private static void unlabel(TextView view) {
        String label = L10n.t("Block");
        CharSequence description = view.getContentDescription();
        if (description != null && label.contentEquals(description)) view.setContentDescription(null);
        for (Class<?> at = view.getClass(); at != null && at != TextView.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != CharSequence.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(view);
                    if (value instanceof CharSequence && label.contentEquals((CharSequence) value)) {
                        field.set(view, view.getText());
                    }
                } catch (ReflectiveOperationException | RuntimeException skipped) {
                    // The swap may bring Block back on this row until its next bind.
                }
            }
        }
    }

    /**
     * The relation's icon goes: the button view draws one it's handed through a public
     * (Drawable) method of its own, not one TextView has. With two such methods it can't tell
     * which one is the icon, so the icon stays.
     */
    private static void icon(TextView view) {
        List<Method> setters = new ArrayList<>();
        for (Class<?> at = view.getClass(); at != null && at != TextView.class; at = at.getSuperclass()) {
            for (Method method : at.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class || types.length != 1 || types[0] != Drawable.class) {
                    continue;
                }
                try {
                    TextView.class.getMethod(method.getName(), Drawable.class);
                    continue;
                } catch (NoSuchMethodException own) {
                    // Not one of TextView's.
                }
                setters.add(method);
            }
        }
        if (setters.size() != 1) return;
        try {
            setters.get(0).invoke(view, (Object) null);
        } catch (ReflectiveOperationException | RuntimeException skipped) {
            // The icon stays.
        }
    }

    private static void tap(View view) {
        try {
            HookStatus.invoked(FAMILY);
            Target target = TARGETS.get(view);
            if (target == null) return;
            Context context = view.getContext();
            if (CommentBlock.open(context, target.person, target.helper, target.profile, target.source,
                    "Reaction list block", BLOCK_FAILED)) {
                HookStatus.counted(FAMILY, BLOCK_OPENED);
            } else {
                Utils.showToastShort(L10n.t("Couldn't open Block for this person."));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reaction list block tap", failure);
            try {
                Utils.showToastShort(L10n.t("Couldn't open Block for this person."));
            } catch (Throwable ignored) {
                // The report has it.
            }
        }
    }
}
