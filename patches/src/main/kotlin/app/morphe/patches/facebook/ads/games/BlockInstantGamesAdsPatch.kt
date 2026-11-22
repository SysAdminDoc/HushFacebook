/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.games

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf

/**
 * Answers every ad an Instant Game asks Facebook for with no ad, through the game bridge's own
 * rejected promise, or through GameBridge for a game that brings its own bridge. See
 * GameAdAnchors.kt for where the hooks go, and the extension's GameAds for which messages it
 * answers and with what code.
 */
@Suppress("unused")
val blockInstantGamesAdsPatch = patch(
    name = "Block Instant Games ads",
    description = "Games you play inside Facebook show no ads, so you can keep playing without breaks. A " +
        "rewarded ad gives no reward unless you also turn on the switch that counts it as watched. On by " +
        "default. Turn it off in Hushfacebook settings > Menu.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val bridges = classDefByStrings(AD_MESSAGES.first(), StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .filter { owner -> owner.methods.any(::isPostMessage) }
        val bridge = bridges.singleOrNull()
            ?: throw PatchException("$PATCH: expected one game bridge with a postMessage holding $AD_MESSAGES, found ${bridges.size}")
        val rejects = classDefByStrings(REJECT_LOG, StringComparisonType.EQUALS)
            .flatMap { owner -> owner.methods.filter(::isRejectPromise) }
        val reject = rejectOn(bridge, rejects).singleOrNull() ?: throw PatchException(
            "$PATCH: expected ${bridge.type} to reject a promise through one (String, String, String) method calling " +
                "the call holding \"$REJECT_LOG\" (${rejects.size} found)",
        )
        val resolves = classDefByStrings(RESOLVE_LOG, StringComparisonType.EQUALS)
            .flatMap { owner -> owner.methods.filter(::isResolvePromise) }
        val resolve = resolveOn(bridge, resolves).singleOrNull() ?: throw PatchException(
            "$PATCH: expected ${bridge.type} to resolve a promise through one (String, Object) method calling " +
                "the call holding \"$RESOLVE_LOG\" (${resolves.size} found)",
        )
        mutableClassDefBy(bridge.type).findMutableMethodOf(bridge.methods.single(::isPostMessage))
            .answerAdsWithNoAd(reject, resolve)

        // Games run from Facebook's downloaded code bring their own bridge, so wrap every game
        // bridge where it's registered on the game WebView (#168).
        val view = gameViewOf(bridge) ?: throw PatchException(
            "$PATCH: expected ${bridge.type} to have one constructor holding \"$BRIDGE_NAME\" that takes the game WebView",
        )
        val chain = webViewChain(view) { classDefByOrNull(it) }
            ?: throw PatchException("$PATCH: expected the game WebView $view to extend $WEB_VIEW")
        chain.firstOrNull(::declaresAddJavascriptInterface)?.let {
            throw PatchException("$PATCH: ${it.type} already has its own $ADD_JAVASCRIPT_INTERFACE")
        }
        mutableClassDefBy(chain.last().type).wrapGameBridges()
        enableStatus("gameAds")
    }
}
