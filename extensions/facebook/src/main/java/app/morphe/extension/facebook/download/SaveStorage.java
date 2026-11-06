/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * The storage saves go to (#274): the phone's own, or an SD card. A card is known by the name
 * MediaStore gives its volume, such as {@code 1a2b-3c4d}, and a save onto it goes into that
 * volume's collection with the same folder path as on the phone. Android 11 and up let an app add
 * its own media to a card's collections with no storage permission, as on the phone's storage.
 *
 * <p>Only a card that's in the phone and that MediaStore lists counts. While the picked one is out,
 * saves go to the phone's storage, so a save never fails for a missing card.
 */
public final class SaveStorage {

    private SaveStorage() {}

    private static final String SOURCE = "SaveStorage";

    /** The cards a test puts in the phone, since Robolectric's phone has none. Null outside tests. */
    public static List<Card> cardsForTests;

    /** One card a save can go to: its MediaStore volume name and the name Android shows for it. */
    public static final class Card {
        public final String volume;
        public final String label;

        public Card(String volume, String label) {
            this.volume = volume;
            this.label = label;
        }
    }

    /**
     * The cards in the phone now that saves can go to, in Android's order, never the phone's own
     * storage. Empty when there are none or Android won't say.
     */
    public static List<Card> cards(Context context) {
        if (cardsForTests != null) return cardsForTests;
        // Before Android 11 a card takes no app's media without a permission, so saves stay on the phone.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Collections.emptyList();
        try {
            StorageManager storage = context.getSystemService(StorageManager.class);
            if (storage == null) return Collections.emptyList();
            Set<String> listed = MediaStore.getExternalVolumeNames(context);
            List<Card> cards = new ArrayList<>();
            for (StorageVolume volume : storage.getStorageVolumes()) {
                if (volume.isPrimary() || !Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
                String name = volume.getMediaStoreVolumeName();
                if (name == null || MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(name) || !listed.contains(name)) continue;
                String label = volume.getDescription(context);
                cards.add(new Card(name, label == null || label.trim().isEmpty() ? name : label.trim()));
            }
            return cards;
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not list the phone's SD cards", t);
            return Collections.emptyList();
        }
    }

    /** The card in [cards] whose volume is [picked], or null for the phone's storage. */
    public static Card pick(String picked, List<Card> cards) {
        if (picked == null || picked.isEmpty()) return null;
        for (Card card : cards) {
            if (card.volume.equals(picked)) return card;
        }
        return null;
    }

    /**
     * The card the next save goes to, or null for the phone's storage: nothing picked, the picked
     * card out of the phone, or settings that can't be read. Never throws.
     */
    static Card current(Context context) {
        try {
            if (!Utils.settingsReady()) return null;
            String picked = Settings.SAVE_STORAGE.get();
            if (picked == null || picked.isEmpty()) return null;
            Card card = pick(picked, cards(context));
            if (card == null) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "the picked SD card isn't in the phone, so the save goes to the phone's storage");
            }
            return card;
        } catch (Throwable t) {
            return null;
        }
    }
}
