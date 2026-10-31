/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.order

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.Method

/**
 * Plays the newest of the reels Facebook has loaded first in the Reels tab. See ReelOrder.kt for
 * how Facebook picks the next reel and where the two hooks go.
 *
 * The signal hook keeps each Reels tab reel's posting time by the signal Facebook ranks it with,
 * and the rerank hook puts the reels in the list with a time newest first, in the slots they had,
 * just before Facebook takes the head. Anything without a time keeps its slot. With the switch off
 * neither hook reads anything, and the list is Facebook's.
 *
 * In the default selection with its switch off: it trades Facebook's pick for posting order, which
 * is a choice to make, not a fix, and only reels already loaded can move.
 */
@Suppress("unused")
val newestReelsFirstPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Newest reels first",
    description = "Plays the newest posted of the reels Facebook has loaded for you first in the Reels tab, " +
        "instead of Facebook's own pick. It can't fetch newer reels. A second switch lists a profile's Reels tab with its most viewed " +
        "reels first. Starts off. Turn it on in Hushfacebook settings > Reels and Watch.",
) {
    category("Reels")
    dependsOn(settingsPatch, profileReelsSortPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("newestReelsFirst")

        val reranks = classDefByStrings(RANKER_TAG, StringComparisonType.EQUALS).flatMap { owner ->
            owner.methods.mapNotNull { method -> rerankList(method)?.let { method to it } }
        }
        val (rerank, list) = reranks.singleOrNull() ?: refuse(
            "expected one void (object, List, int) method loading \"$RANKER_TAG\" that never writes its List parameter " +
                "and sorts it in place with a (List, Comparator) call, found ${reranks.size}",
        )

        val makers = mutableListOf<Pair<Method, SignalReads>>()
        val unread = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
            for (method in classDef.methods) {
                if (!isSignalMaker(method)) continue
                val reads = signalReads(method) { classDefByOrNull(it) }
                if (reads == null) unread += "${method.definingClass}->${method.name}" else makers += method to reads
            }
        }
        if (makers.isEmpty()) {
            refuse(
                "no $SIGNAL_MAKER casts its parameter to a model with one $FEED_TYPE field and an edge getter" +
                    (if (unread.isEmpty()) "" else ", read none of ${unread.joinToString()}"),
            )
        }

        for ((maker, reads) in makers) {
            val owner = mutableClassDefBy(maker.definingClass)
            if (owner.methods.any { it.name == EDGE_HELPER }) refuse("${owner.type} already has $EDGE_HELPER")
            owner.methods.add(edgeHelper(owner.type, reads))
            owner.findMutableMethodOf(maker).recordAtReturns(reads)
        }
        mutableClassDefBy(rerank.definingClass).findMutableMethodOf(rerank).sortAtReturns(list)
        enableStatus("newestReelsFirst")
    }
}
