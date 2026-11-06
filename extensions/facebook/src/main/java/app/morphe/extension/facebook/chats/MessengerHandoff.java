/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.facebook.notifications.NotificationKinds;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Open Messenger from the top bar patch asks before Facebook rings for a call or posts a
 * chat notification, for the Leave calls and chats to Messenger switch (issues #39, #100 and
 * #157).
 *
 * <p>Facebook normally advertises the VOIP bit in the client capabilities of its MQTT connection.
 * With both apps installed, Meta often sends the call to both, but an account can have its usable
 * call routed only to Facebook. Dropping that push would then leave neither app ringing. The patch
 * runs {@link #clientCapabilities} before Facebook builds its MQTT connection so the switch removes
 * that VOIP claim and leaves server-side call ownership to Messenger. The setting restarts Facebook
 * when it changes so the connection is rebuilt with the new value.
 *
 * <p>The local hooks stay as a fallback for already-routed work: {@link #dropCallPush} runs first in
 * Facebook's push handlers for VOIP pushes, {@link #dropRing} runs first in the parser every
 * incoming ring goes through (from FCM, from Facebook's own MQTT connection and from its call
 * signaling), {@link #dropChat} runs first where Facebook hands a chat notification to its
 * notifiers, and {@link #dropChatPush} runs first where Facebook posts a push notification, which
 * is how a message in Facebook's own Chats arrives. While the switch is on and Meta's Messenger is
 * installed and enabled, a call push is dropped, a ring parses to nothing, and a new message,
 * reaction, message request or missed call isn't posted by Facebook.
 *
 * <p>Messenger is looked up afresh on every question, never kept. A missed call costs far more than
 * a package lookup, and a Messenger uninstalled or disabled a minute ago has to stop the local drop
 * hooks straight away. Restarting Facebook then restores its server registration. A copy under
 * another package name, as HushMessenger's install beside Meta's apps makes, counts like Meta's
 * own: it's the one the top bar's icon opens and the Get Messenger card counts, and it gets Meta's
 * pushes too (#193 showed a copy and Facebook each notifying for the same message, while this
 * counted no Messenger). Whether Messenger is signed in can't be seen from here, which the switch's
 * summary says.
 *
 * <p>It fails open: with the switch off, a pause, settings that aren't ready, no context yet, no
 * Messenger, a disabled one, or any failure in here, Facebook rings and notifies as it always did.
 * The local drop hooks read the switch on every question. The advertised MQTT capability changes on
 * the next Facebook start.
 */
public final class MessengerHandoff {
    /** The VOIP bit, ordinal 1 in Facebook's kept MQTT capability enum. */
    static final long VOIP_CAPABILITY = 1L << 1;

    /** Counted when Facebook leaves VOIP out of the next MQTT connection. */
    static final String CALL_REGISTRATION_LEFT = "call registration left to Messenger";

    /** Counted under the patch's name when a VOIP push was dropped. */
    static final String CALL_PUSH_DROPPED = "call push left to Messenger";

    /** Counted when an incoming ring was parsed to nothing. */
    static final String RING_DROPPED = "ring left to Messenger";

    /** Counted when a chat notification wasn't posted. */
    static final String CHAT_DROPPED = "chat notification left to Messenger";

    /** Counted when the switch was on but no enabled Messenger was there, so Facebook kept it. */
    static final String NO_MESSENGER = "no Messenger, Facebook rang or notified";

    /** Counted when Facebook's chats gate was told its chats open in Messenger (#392). */
    static final String CHATS_LEFT = "chats opened in Messenger";

    /** Counted when Open chats in Messenger was on but Meta's Messenger wasn't there. */
    static final String NO_MESSENGER_FOR_CHATS = "no Messenger, chats stayed in Facebook";

    /** Whether Meta's Messenger was installed and enabled when the gate first asked, or null before then. */
    @Nullable
    private static volatile Boolean metaMessenger;

    /**
     * The chat notifications Messenger posts itself, by their kept class names: a new message, a
     * reaction to one, a message request, a missed call, and the placeholder Facebook posts for an
     * end-to-end encrypted message it synced in the background. Facebook's own reminders, Page
     * inboxes and the rest still post.
     */
    static final Set<String> CHAT_KINDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.facebook.messaging.notify.type.NewMessageNotification",
            "com.facebook.messaging.notify.type.MessageReactionNotification",
            "com.facebook.messaging.notify.type.MessageRequestNotification",
            "com.facebook.messaging.rtc.callstatus.notification.MissedCallNotification",
            "com.facebook.messaging.tincan.tincanoverwamsys.backgroundsync.generic.notify.MessengerGenericTincanMessage")));

    /**
     * The same chats as {@link #CHAT_KINDS} where Facebook posts them as push notifications, by
     * NotificationType's constant names: a new message in Facebook's own Chats, the ones after it
     * and an edit to one, the older message pushes, a message request, a reaction, a missed call
     * and an end-to-end encrypted message. #193's report counted three MESSAGING_IN_BLUE_DIRECT_MESSAGE
     * pushes reaching Facebook's tray, none of them the dispatcher, while a copy of Messenger
     * notified for the same messages. Page inboxes, community chats and Facebook's message
     * reminders aren't among them.
     */
    static final Set<String> CHAT_PUSH_KINDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "MESSAGING_IN_BLUE_DIRECT_MESSAGE",
            "MESSAGING_IN_BLUE_SUBSEQUENT_MESSAGE",
            "MESSAGING_IN_BLUE_EDIT_MESSAGE",
            "MSG",
            "ORCA_MESSAGE",
            "ORCA_FRIEND_MSG",
            "MESSAGE_REQUEST",
            "MESSENGER_REACTIONS",
            "MESSENGER_E2EE_MISSED_CALL",
            "GENERIC_TINCAN_MESSAGE")));

    private MessengerHandoff() {
    }

    /**
     * Whether this build carries the handoff's hooks. The patch answers true here once they're all
     * in; a Facebook build missing one of their anchors keeps the Messenger icon and leaves this
     * false, so the settings page doesn't offer a switch that would do nothing.
     */
    public static boolean inBuild() {
        return false;
    }

    /**
     * Whether this build carries Open chats in Messenger's hook. The patch answers true here once
     * it found Facebook's chats gate, so the settings page offers the switch only where it works.
     */
    public static boolean chatsInBuild() {
        return false;
    }

    /**
     * Injection point, first thing in the gate Facebook asks before it opens a chat inside
     * Facebook (#392). True has the gate answer false, which sends the chat to Messenger the way
     * Facebook already does for accounts Meta hasn't moved into its own Chats. True only while the
     * switch is on and Meta's Messenger, {@code com.facebook.orca}, is installed and enabled: a copy
     * under another package name isn't one Facebook knows to open. Facebook asks the gate often
     * (the jewel count, chat pushes), so the lookup is kept for the process, and the switch restarts
     * Facebook. Never throws.
     */
    public static boolean chatsInMessenger() {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            if (!Utils.settingsReady() || !Settings.OPEN_CHATS_IN_MESSENGER.get()) return false;
            if (!metaMessengerInstalled()) {
                HookStatus.counted(FamilyNames.MESSENGER_ICON, NO_MESSENGER_FOR_CHATS);
                return false;
            }
            HookStatus.counted(FamilyNames.MESSENGER_ICON, CHATS_LEFT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger chats", failure);
            return false;
        }
    }

    /** Meta's Messenger, installed and enabled, looked up on the first call with a context and kept. */
    private static boolean metaMessengerInstalled() {
        Boolean known = metaMessenger;
        if (known != null) return known;
        Context context = Utils.getContext();
        if (context == null) return false;
        boolean found = MessengerCard.lookUp(context.getPackageManager());
        metaMessenger = found;
        return found;
    }

    /** Drops the kept Messenger answer, as a new Facebook process starts without it. For tests. */
    static void forgetMessenger() {
        metaMessenger = null;
    }

    /**
     * Injection point for the client capabilities passed to Facebook's MQTT connection. Removes
     * only VOIP while Messenger owns calls. A value that never claimed VOIP is returned untouched,
     * and any failure leaves Facebook's stock capabilities in place.
     */
    public static long clientCapabilities(long capabilities) {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            if ((capabilities & VOIP_CAPABILITY) == 0L) return capabilities;
            return handOff(CALL_REGISTRATION_LEFT) ? capabilities & ~VOIP_CAPABILITY : capabilities;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger call registration", failure);
            return capabilities;
        }
    }

    /**
     * Injection point, first thing in each push handler that takes VOIP pushes alone. True drops
     * the push. Never throws.
     */
    public static boolean dropCallPush() {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            return handOff(CALL_PUSH_DROPPED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger call pushes", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in the parser every incoming ring goes through. True answers
     * nothing to ring. Never throws.
     */
    public static boolean dropRing() {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            return handOff(RING_DROPPED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger rings", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing where Facebook hands a chat notification to its notifiers. True
     * when the notification is one of {@link #CHAT_KINDS} and Messenger takes it, so it isn't
     * posted. Never throws.
     *
     * @param notification the MessagingNotification Facebook is about to post
     */
    public static boolean dropChat(@Nullable Object notification) {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            if (notification == null || !isChatKind(notification.getClass().getName())) return false;
            return handOff(CHAT_DROPPED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger chat notifications", failure);
            return false;
        }
    }

    /** Whether a notification of the class named {@code className} is one Messenger posts itself. */
    static boolean isChatKind(@Nullable String className) {
        return className != null && CHAT_KINDS.contains(className);
    }

    /**
     * Injection point, first thing in the method that posts Facebook's push notifications, with the
     * push's type as its payload carried it. True when its kind is one of {@link #CHAT_PUSH_KINDS}
     * and Messenger takes it, so Facebook posts nothing. Every other kind isn't counted. Never
     * throws.
     */
    public static boolean dropChatPush(@Nullable String type) {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            String kind = NotificationKinds.kindOf(type);
            if (kind == null || !CHAT_PUSH_KINDS.contains(kind)) return false;
            return handOff(CHAT_DROPPED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger chat pushes", failure);
            return false;
        }
    }

    /**
     * True, counted under {@code what}, while the switch is on and Messenger is installed and enabled.
     * The switch is read first, so nothing is looked up while it's off.
     */
    private static boolean handOff(String what) {
        if (!Utils.settingsReady() || !Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.get()) return false;
        Context context = Utils.getContext();
        if (context == null || !messengerInstalled(context)) {
            HookStatus.counted(FamilyNames.MESSENGER_ICON, NO_MESSENGER);
            return false;
        }
        HookStatus.counted(FamilyNames.MESSENGER_ICON, what);
        return true;
    }

    /**
     * Whether Meta's Messenger, or a copy of it under another package name, is installed and
     * enabled right now. A disabled copy has no launcher entry to find, so it counts as none, like
     * a disabled Meta Messenger.
     */
    static boolean messengerInstalled(Context context) {
        PackageManager packages = context.getPackageManager();
        return MessengerCard.lookUp(packages) || MessengerIcon.copyLaunch(packages, context.getPackageName()) != null;
    }
}
