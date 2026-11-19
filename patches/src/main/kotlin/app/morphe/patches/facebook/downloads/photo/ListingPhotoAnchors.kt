/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.photo

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.ads.sponsoredmarketplace.READABLE_ARRAY
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.Method

/** React Native's image manager on Fresco Vito, a kept name: it draws every photo on a Marketplace listing. */
internal const val VITO_IMAGE_MANAGER = "Lcom/facebook/fresco/vito/rn/ReactVitoImageManager;"
internal const val LISTING_PHOTO = "$EXTENSION_PACKAGE/download/ListingPhoto;"
internal const val LISTING_SOURCE = "$LISTING_PHOTO->source(Landroid/view/View;Ljava/lang/Object;)V"

private const val IMAGE_VIEW = "Landroid/widget/ImageView;"
private val SOURCE_SETTERS = listOf("setSource", "setSrc")

private fun Method.parameterShape() = parameterTypes.map(CharSequence::toString)

/** One of the manager's own setters: its view (not the bridge's plain View) and the array of sources. */
private fun isTypedSetter(method: Method): Boolean {
    val shape = method.parameterShape()
    return method.name in SOURCE_SETTERS && method.returnType == "V" && shape.size == 2 &&
        shape[1] == READABLE_ARRAY && shape[0] != "Landroid/view/View;" && method.implementation != null
}

/**
 * Finds React Native's image manager on Fresco Vito, which draws every photo on a Marketplace
 * listing (the listing's photo strip and the photos opened full screen), and returns the step that
 * hands the extension's ListingPhoto each photo's view and sources, for Long press to save
 * Marketplace photos (#515). Each of the manager's typed setSource and setSrc gets the call first
 * thing, one range call over its own two parameters. A bridge setter taking any View calls a typed
 * one, so it's left alone. The view has no touch handling of its own, so ListingPhoto listens for
 * presses itself and nothing else is hooked.
 *
 * Refuses, naming Download any photo, a build with no manager, no typed setter, two typed setters
 * under one name, a view that isn't an ImageView, or where something jumps back to a setter's first
 * instruction, which would skip the call. Nothing changes until the step runs.
 */
internal fun BytecodePatchContext.listingPhotoSave(): () -> Unit {
    val manager = classDefByOrNull(VITO_IMAGE_MANAGER)
        ?: throw PatchException("$PHOTO_PATCH: found no React Native image manager ($VITO_IMAGE_MANAGER)")
    val setters = manager.methods.filter(::isTypedSetter)
    if (setters.isEmpty()) {
        throw PatchException("$PHOTO_PATCH: found no setSource or setSrc on $VITO_IMAGE_MANAGER taking its own view and a ReadableArray")
    }
    for ((name, same) in setters.groupBy { it.name }) {
        if (same.size > 1) {
            throw PatchException("$PHOTO_PATCH: expected one typed $name on $VITO_IMAGE_MANAGER, found ${same.size}")
        }
    }
    for (setter in setters) {
        val viewType = setter.parameterTypes.first().toString()
        val view = classDefByOrNull(viewType)
            ?: throw PatchException("$PHOTO_PATCH: the image manager's view $viewType isn't in the app")
        if (view.superclass != IMAGE_VIEW) {
            throw PatchException("$PHOTO_PATCH: the image manager's view $viewType extends ${view.superclass}, not an ImageView")
        }
        if (0 in branchTargets(setter.implementation!!.instructions.toList())) {
            throw PatchException("$PHOTO_PATCH: $VITO_IMAGE_MANAGER->${setter.name} jumps back to its first instruction")
        }
    }
    return {
        val mutable = mutableClassDefBy(VITO_IMAGE_MANAGER)
        for (setter in setters) {
            mutable.findMutableMethodOf(setter).addInstructions(0, "invoke-static/range { p1 .. p2 }, $LISTING_SOURCE")
        }
    }
}
