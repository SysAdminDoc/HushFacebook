/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredmarketplace

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/*
 * Where a listing's price is drawn, for the date under it (#303).
 *
 * A listing's own page is React Native, and its JavaScript writes the price from the answer's
 * listing_price: no server string says it, and the line under it ("Just listed") only shows for a
 * day-old listing. The first place Java sees the price as text is React's text builder, the static
 * method that takes the text view manager's callback for built text and returns the Spannable
 * (585 arm64 `LX/082P;->A03`, Fabric's TextLayoutManager). Fabric's measure (prepareTextLayout) and
 * draw both use what it returns, so a line added there gets its room on the page.
 */
internal const val PRICE_LINE = "$EXTENSION_PACKAGE/ads/MarketplaceListingAge;->priceLine(Landroid/text/Spannable;)Landroid/text/Spannable;"

/**
 * Each return hands the built text to [PRICE_LINE] and returns its answer. The return becomes the
 * call, so whatever jumped to the return reaches the call; its answer and the return follow.
 */
internal fun MutableMethod.handBuiltTextOver() {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    returns.sortedDescending().forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        replaceInstruction(index, "invoke-static/range { v$register .. v$register }, $PRICE_LINE")
        addInstructions(
            index + 1,
            """
                move-result-object v$register
                return-object v$register
            """,
        )
    }
}
