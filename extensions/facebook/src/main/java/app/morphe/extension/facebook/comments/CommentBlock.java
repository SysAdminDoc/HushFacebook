/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.stories.StorySeen;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Block button on comments (#271): the thumbs-down under a comment becomes a Block button that
 * runs Facebook's own block flow for the person who wrote the comment, confirmation included.
 *
 * <p>The Dislike button is a component holding the comment. Its click handler is one case of a
 * shared lambda class, and the patch asks {@link #tap} first in that case. Off, or when the
 * handler isn't the Dislike component's, Facebook's own code runs as written. On, the tap never
 * dislikes: the comment's author is read off the component, and Facebook's profile Block helper
 * (the one the Block row of a profile's menu runs, which loads the block sheet and asks before it
 * blocks) is called with a stand-in profile that answers the name and the "use the sheet" flag.
 * Nothing here calls the block mutation itself, so Facebook's own sheet is the only way to a block.
 *
 * <p>The classes are handed over as class constants and the helper's method is found by its
 * parameter types, so no obfuscated name is written down. The author's id and name are read with
 * the kept {@code getCachedString}, by the hash of the GraphQL field name.
 *
 * <p>Nothing here logs an id or a name.
 */
public final class CommentBlock {
    static final String COMMENT_CLASS = "com.facebook.graphql.model.GraphQLComment";
    static final String ACTOR_CLASS = "com.facebook.graphql.model.GraphQLActor";
    static final String TREE_MODEL_CLASS = "com.facebook.graphql.modelutil.BaseModelWithTree";
    static final String SESSION_CLASS = "com.facebook.auth.usersession.FbUserSession";
    static final int ID_KEY = "id".hashCode();
    static final int NAME_KEY = "name".hashCode();
    static final int SHORT_NAME_KEY = "short_name".hashCode();

    /** The name of the source constant the profile's own Block row passes. */
    static final String SOURCE = "PROFILE";

    /** The member the report names once the Block button's click has asked. */
    static final String BLOCK_TAP = "comment block tap";

    /** Counted each time a tap opens Facebook's block sheet. */
    static final String BLOCK_OPENED = "Comment Block sheet opened";

    /** Counted, with the reason, each time Facebook's block sheet fails after a tap here. */
    static final String BLOCK_FAILED = "Comment Block sheet failed";

    /** How long after a tap a block sheet failure is taken to be that tap's. */
    static final long TAP_WINDOW_MS = 15_000;

    private static final String FAMILY = FamilyNames.COMMENT_SHEET_OPTIONS;

    /** When a tap last handed a comment's author to the helper, 0 once its failure was read. */
    static volatile long tappedAt;

    private CommentBlock() {
    }

    /** The hook where the icon of the Dislike button is picked: true draws a Block icon in place of the thumbs-down. */
    public static boolean blockIcon() {
        try {
            HookStatus.invoked(FAMILY);
            return Utils.settingsReady() && Settings.COMMENT_BLOCK_BUTTON.get();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment block icon", failure);
            return false;
        }
    }

    /** The hook where the Dislike button gets its spoken label: Block's, while the switch is on. */
    public static String blockLabel(String facebookLabel) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.COMMENT_BLOCK_BUTTON.get()) return facebookLabel;
            return L10n.t("Block");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment block label", failure);
            return facebookLabel;
        }
    }

    /**
     * The hook, first thing in the Dislike button's click. True means this class handled the tap
     * (the block sheet opened, or a toast said it couldn't) and Facebook's handler is skipped;
     * false leaves the tap to Facebook.
     *
     * @param lambda    the click handler object holding the Dislike component
     * @param scope     the click's scope, which can answer the View that was tapped
     * @param component the Dislike component's class
     * @param helper    the class of Facebook's profile Block helper
     * @param profile   the interface the helper reads the profile through
     * @param source    the enum of places the helper is opened from
     */
    public static boolean tap(@Nullable Object lambda, @Nullable Object scope, Class<?> component, Class<?> helper,
            Class<?> profile, Class<?> source) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, BLOCK_TAP);
            if (!Utils.settingsReady() || !Settings.COMMENT_BLOCK_BUTTON.get()) return false;
            Object button = fieldHolding(lambda, component);
            if (button == null) return false;
            Author author = author(button);
            Context context = context(scope);
            if (author == null || context == null) {
                Utils.showToastShort(L10n.t("Couldn't open Block for this comment."));
                return true;
            }
            if (open(context, author, helper, profile, source)) {
                HookStatus.counted(FAMILY, BLOCK_OPENED);
            } else {
                Utils.showToastShort(L10n.t("Couldn't open Block for this comment."));
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BLOCK_TAP, failure);
            try {
                Utils.showToastShort(L10n.t("Couldn't open Block for this comment."));
            } catch (Throwable ignored) {
                // The report has it.
            }
            return true;
        }
    }

    /** The person who wrote a comment, and the session the comment was drawn in. */
    static final class Author {
        final String id;
        final String name;
        final Object session;

        Author(String id, String name, Object session) {
            this.id = id;
            this.name = name;
            this.session = session;
        }
    }

    /** The first field of [holder] whose value is an instance of [type], or null. */
    @Nullable
    static Object fieldHolding(@Nullable Object holder, Class<?> type) {
        if (holder == null) return null;
        for (Class<?> at = holder.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (java.lang.reflect.Field field : at.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (type.isInstance(value)) return value;
                } catch (ReflectiveOperationException | RuntimeException skipped) {
                    // Try the next field.
                }
            }
        }
        return null;
    }

    /** The context of the View the click's [scope] answers, else the foreground activity. */
    @Nullable
    static Context context(@Nullable Object scope) {
        try {
            if (scope != null) {
                for (Method method : scope.getClass().getMethods()) {
                    if (method.getParameterTypes().length == 0 && View.class.isAssignableFrom(method.getReturnType())) {
                        Object view = method.invoke(scope);
                        if (view instanceof View) return ((View) view).getContext();
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall through to the foreground activity.
        }
        return Utils.getActivity();
    }

    /** Who wrote the comment the Dislike [button] sits under, or null when this build can't say. */
    @Nullable
    static Author author(Object button) throws ReflectiveOperationException {
        ClassLoader loader = button.getClass().getClassLoader();
        Class<?> commentClass = Class.forName(COMMENT_CLASS, false, loader);
        Class<?> actorClass = Class.forName(ACTOR_CLASS, false, loader);
        Class<?> sessionClass = Class.forName(SESSION_CLASS, false, loader);
        Object comment = fieldHolding(button, commentClass);
        Object session = fieldHolding(button, sessionClass);
        if (comment == null || session == null) return null;
        Object actor = null;
        for (Method method : commentClass.getMethods()) {
            if (method.getParameterTypes().length == 0 && method.getReturnType() == actorClass) {
                actor = method.invoke(comment);
                break;
            }
        }
        if (actor == null) return null;
        Method cachedString = Class.forName(TREE_MODEL_CLASS, false, loader).getMethod("getCachedString", int.class);
        String id = string(cachedString, actor, ID_KEY);
        if (id == null || id.isEmpty() || !id.matches("\\d{1,19}")) return null;
        String name = string(cachedString, actor, NAME_KEY);
        if (name == null || name.isEmpty()) name = string(cachedString, actor, SHORT_NAME_KEY);
        if (name == null || name.isEmpty()) name = L10n.t("this person");
        return new Author(id, name, session);
    }

    @Nullable
    private static String string(Method cachedString, Object model, int key) {
        try {
            Object value = cachedString.invoke(model, key);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Calls Facebook's profile Block helper for [author]. False when the helper isn't there to call. */
    static boolean open(Context context, Author author, Class<?> helper, Class<?> profile, Class<?> source)
            throws ReflectiveOperationException {
        Method call = helperMethod(helper, profile, source);
        if (call == null) return false;
        Object sourceConstant = null;
        for (Object constant : source.getEnumConstants()) {
            if (SOURCE.equals(((Enum<?>) constant).name())) sourceConstant = constant;
        }
        if (sourceConstant == null) return false;
        Object stand = profileFor(profile, author.name);
        java.lang.reflect.Constructor<?> constructor = helper.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        call.setAccessible(true);
        long viewer = viewerId(author.session);
        Logger.printDebug(() -> "Comment block: the block sheet opens for a comment's author, the session "
                + (viewer == 0L ? "names no user" : "names its user"));
        tappedAt = System.currentTimeMillis();
        call.invoke(instance, context, sourceConstant, author.session, stand, viewer, Long.parseLong(author.id), Boolean.FALSE);
        return true;
    }

    /** The signed-in user's id, as the profile's own Block row passes it first, or 0 when [session] doesn't say. */
    static long viewerId(@Nullable Object session) {
        String id = StorySeen.account(session);
        return id != null && id.matches("\\d{1,18}") ? Long.parseLong(id) : 0L;
    }

    /**
     * The hook, first thing in the failure handler of Facebook's block sheet (the one that shows
     * "There was a problem blocking this user"). Within {@link #TAP_WINDOW_MS} of a tap here it
     * counts the failure in the report and writes the reason Facebook got to the log, long digit
     * runs masked so no id is written. Facebook's handler runs as written either way.
     */
    public static void sheetFailed(@Nullable Throwable failure) {
        try {
            long at = tappedAt;
            if (at == 0L || System.currentTimeMillis() - at > TAP_WINDOW_MS) return;
            tappedAt = 0L;
            String reason = reason(failure);
            HookStatus.counted(FAMILY, BLOCK_FAILED);
            Logger.printInfo(() -> "Comment block: Facebook's block sheet failed: " + reason);
        } catch (Throwable unexpected) {
            HookStatus.threw(FAMILY, "comment block failure", unexpected);
        }
    }

    /**
     * [failure]'s chain as "Type: message" parts, each with the plain fields of a service result
     * or API error it carries, digit runs of six or more masked.
     */
    static String reason(@Nullable Throwable failure) {
        StringBuilder out = new StringBuilder();
        Throwable at = failure;
        for (int depth = 0; at != null && depth < 4; depth++) {
            if (out.length() > 0) out.append(" <- ");
            String type = at.getClass().getSimpleName();
            out.append(type.isEmpty() ? at.getClass().getName() : type);
            if (at.getMessage() != null) out.append(": ").append(at.getMessage());
            Throwable next = at.getCause();
            Object result = field(at, "result");
            if (result != null) {
                out.append(" {").append(fields(result)).append('}');
                Object inner = field(result, "errorThrowable");
                if (inner instanceof Throwable) next = (Throwable) inner;
            }
            Object api = apiError(at);
            if (api != null) out.append(" {").append(fields(api)).append('}');
            if (next == at) break;
            at = next;
        }
        String text = out.length() == 0 ? "no reason given" : out.toString().replaceAll("\\d{6,}", "#");
        return text.length() > 400 ? text.substring(0, 400) : text;
    }

    /** The value of [holder]'s instance field called [name], declared on it or a superclass, or null. */
    @Nullable
    private static Object field(Object holder, String name) {
        for (Class<?> at = holder.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            try {
                java.lang.reflect.Field field = at.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(holder);
            } catch (NoSuchFieldException absent) {
                // Try the superclass.
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    /** The ApiErrorResult [failure] answers through a no-argument method, or null. */
    @Nullable
    private static Object apiError(Throwable failure) {
        for (Method method : failure.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || !"ApiErrorResult".equals(method.getReturnType().getSimpleName())) continue;
            try {
                return method.invoke(failure);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    /** [holder]'s own number, flag, enum and text fields as "name=value". */
    static String fields(Object holder) {
        StringBuilder out = new StringBuilder();
        for (java.lang.reflect.Field field : holder.getClass().getDeclaredFields()) {
            Class<?> type = field.getType();
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || !(type.isPrimitive() || type == String.class || type.isEnum())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(holder);
                if (value == null) continue;
                if (out.length() > 0) out.append(' ');
                out.append(field.getName()).append('=').append(value);
            } catch (ReflectiveOperationException | RuntimeException skipped) {
                // Leave it out.
            }
        }
        return out.toString();
    }

    /** The helper's (Context, source, session, profile, long, long, boolean) method, found by those types. */
    @Nullable
    static Method helperMethod(Class<?> helper, Class<?> profile, Class<?> source) {
        Method found = null;
        for (Method method : helper.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 7 || types[0] != Context.class || types[1] != source || types[3] != profile
                    || types[4] != long.class || types[5] != long.class || types[6] != boolean.class) {
                continue;
            }
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    /**
     * A stand-in for the profile Facebook's helper reads: its name is the commenter's and
     * everything else is empty. Its one boolean answers false, as an ordinary person's profile
     * does, so the helper shows its own "Block name?" dialog. True asks the server for the block
     * sheet of profiles that have one, and for anyone else that answer has no block action and
     * Facebook shows "There was a problem blocking this user" (#271, "Null action" on 585). The
     * dialog reads a nested model off the profile, so each interface it asks for is an empty
     * stand-in too.
     */
    static Object profileFor(Class<?> profile, String name) {
        return standIn(profile, name);
    }

    private static Object standIn(Class<?> type, @Nullable String name) {
        InvocationHandler handler = (proxy, method, args) -> {
            Class<?> answer = method.getReturnType();
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return "BlockProfile";
                }
            }
            if (answer == boolean.class) return Boolean.FALSE;
            if (answer == String.class) return name != null && "getName".equals(method.getName()) ? name : null;
            if (answer == int.class) return 0;
            if (answer == long.class) return 0L;
            if (answer.isInterface()) return standIn(answer, null);
            return null;
        };
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
