/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchor of Hide the Get Messenger card on every declared Facebook build: one Chats plugin
 * whose builder loads the banner's tag, with one show question to hook and a local to borrow; the
 * card it builds is the one with the "Get the %1$s app" text; it's one plugin among many; and the
 * reason the card shows on a re-signed Facebook, the same-key check, is where the anchors say.
 */
class MessengerCardFixtureTest {
    /**
     * The id of "Get the %1$s app for even more fun ways to connect with others" in each build.
     * Facebook keeps its English strings out of resources.arsc, in assets/strings/default.frsc.xz:
     * xz over a table of id ranges and offsets, where these ids were read. The card loads it. 582
     * ships that file as default.frsc.spo (SuperPack) instead and unpacks it on first launch into
     * app_strings/uncompressed_default.frsc.xz, the same table, where 582's id was read (0x7f1436ed).
     * 583 packs it the same way and the bundle holds nothing else with that text, so its id is 582's
     * moved by the shift between the two builds' tables: the locale packs both ship (which stay
     * plain FRSC) place the ids around it 867 higher, and the dex agrees, as 583's neighbouring
     * ids sit the same distance apart as 582's. 584 packs it the same way again, and its id is
     * 583's 184 higher: in 76 to 88 of the locale packs both builds ship, the ids on either side
     * of it (0x3a24 below, 0x3a86 to 0x3a9c above) all moved by exactly that. 586 packs it the same
     * way, and the card's render method (the one tagging its dismiss button) loads 0x7f143d45 where
     * 585's loaded 0x7f143ba6, with the two ids it loads next (585's 0x3ba3 and 0x3ba4) moved by the
     * same 0x19f. The test below only passes on the one card class that loads it, and an unpacked
     * table is the check still owed.
     */
    private val upsellText = mapOf(
        "586.0.0.52.53" to 0x7f143d45,
    )

    private val packageManager = "Landroid/content/pm/PackageManager;"

    private fun locals(method: Method): Int {
        val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        return method.implementation!!.registerCount - self - method.parameterTypes.sumOf {
            if (it.toString() == "J" || it.toString() == "D") 2 else 1
        }
    }

    private fun loadsLiteral(method: Method, value: Int): Boolean =
        method.implementation?.instructions?.any {
            it.opcode in setOf(Opcode.CONST, Opcode.CONST_HIGH16) && (it as NarrowLiteralInstruction).narrowLiteral == value
        } == true

    private fun calls(method: Method, owner: String, name: String): Boolean =
        method.implementation?.instructions?.any { instruction ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == owner && ref.name == name
        } == true

    @Test
    fun `each declared build has one Messenger card question, on a card with the Get Messenger text`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            val text = upsellText[version]
                ?: throw AssertionError("no card text id for $version: read it from that build's default.frsc.xz")
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, TOP_BANNER_TAG)
                assertEquals("${bundle.name}: classes loading the banner's tag", 1, holders.size)
                val plugin = holders.single()
                val questions = holders.mapNotNull(::cardQuestion)
                assertEquals("${bundle.name}: Messenger card questions", 1, questions.size)
                val question = questions.single()
                assertTrue("${bundle.name}: the question has no local register", locals(question) >= 1)

                // The builder hands back the card or its older design; the card is the one with the
                // dismiss button's tag and the "Get the %1$s app" text.
                val builder = plugin.methods.single(::isCardBuilder)
                val built = builder.implementation!!.instructions
                    .filter { it.opcode == Opcode.NEW_INSTANCE }
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                    .toSet()
                assertEquals("${bundle.name}: components the builder can hand back", 2, built.size)
                val components = FixtureDex.classes(bundle, built)
                assertEquals("${bundle.name}: a component class is missing", built, components.keys)
                val cards = components.values.filter { component ->
                    component.methods.any { holdsString(it, "$TOP_BANNER_TAG-dismiss-button") && loadsLiteral(it, text) }
                }
                assertEquals("${bundle.name}: the card with the Get Messenger text", 1, cards.size)

                // One plugin among many: the rest of Chats' rows answer the same question on
                // classes of their own, and none of them is touched.
                val group = plugin.interfaces.single()
                var siblings = 0
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.type != plugin.type && group in classDef.interfaces &&
                            classDef.methods.any(::isShowQuestion)
                        ) siblings++
                    }
                }
                assertTrue("${bundle.name}: only $siblings other Chats plugins", siblings >= 10)

                // Why the card shows on a re-signed build: Facebook's installed-app lookup keeps a
                // package only when the kept isSameSignature says it's signed with Facebook's key.
                val sameSignature = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.methodSection.any { it.name == "isSameSignature" }
                }) { method ->
                    method.name == "isSameSignature" && method.returnType == "Z" &&
                        method.parameterTypes.map { it.toString() } == listOf("Landroid/content/pm/ApplicationInfo;") &&
                        calls(method, packageManager, "checkSignatures")
                }
                assertEquals("${bundle.name}: the same-key check", 1, sameSignature.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
