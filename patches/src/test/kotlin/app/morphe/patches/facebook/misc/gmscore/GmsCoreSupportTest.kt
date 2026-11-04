/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.gmscore

import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * GmsCore support on Facebook: which names move to microG-RE's vendor, the two checks and every text
 * it finds before changing anything, what a patch context holds after it runs, and the manifest.
 */
class GmsCoreSupportTest {
    private val availabilityOwner = "LX/0674;"
    private val providerOwner = "LX/0yJO;"
    private val registrationOwner = "LX/3Ua;"
    private val credentialManager = "Landroid/credentials/CredentialManager;"

    private fun load(register: Int, text: String): Instruction =
        ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(text))

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int,
                       vararg body: Instruction) = ImmutableMethod(owner, name,
        parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
        ImmutableMethodImplementation(registers, body.toList(), null, null))

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, methods)

    /** 585's availability check cut down to the three texts it's found by. */
    private fun availability(owner: String = availabilityOwner, version: String = "MetadataValueReader") = classDef(owner, listOf(
        method(owner, "A04", listOf("Landroid/content/Context;", "I"), "I", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, 4,
            load(0, "com.google.android.gms"), load(0, version), load(0, "This should never happen."),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1), ImmutableInstruction11x(Opcode.RETURN, 0))))

    /** androidx's framework provider, which holds Android's CredentialManager. */
    private fun provider(owner: String = providerOwner) = classDef(owner,
        listOf(method(owner, "isAvailableOnDevice", emptyList(), "Z", AccessFlags.PUBLIC.value, 2,
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1), ImmutableInstruction11x(Opcode.RETURN, 0))),
        listOf(ImmutableField(owner, "A00", credentialManager, AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null, null)))

    /** The two ways 585 asks for a Cloud Messaging token, one a jumbo load, and a static field naming Play services. */
    private fun registration(texts: List<String> = listOf("com.google.android.c2dm.intent.REGISTER", "com.google.iid.TOKEN_REQUEST")) =
        classDef(registrationOwner, listOf(method(registrationOwner, "A00", listOf("Landroid/content/Context;"), "V",
            AccessFlags.PUBLIC.value, 20,
            *texts.mapIndexed { index, text ->
                if (index == 1) ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, 17, ImmutableStringReference(text)) else load(0, text)
            }.toTypedArray(),
            load(1, "com.facebook.katana"),
            ImmutableInstruction10x(Opcode.RETURN_VOID))),
            listOf(ImmutableField(registrationOwner, "A01", "Ljava/lang/String;", AccessFlags.STATIC.value,
                ImmutableStringEncodedValue("com.google.android.gms"), null, null)))

    private fun supported(): List<ClassDef> = listOf(availability(), provider(), registration())

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.text() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun failure(classes: List<ClassDef>) = assertThrows(PatchException::class.java) { planGmsCore(classes) }.message!!

    @Test
    fun namesMoveToMicrogsVendorAndEverythingElseStays() {
        assertEquals("app.revanced.android.gms", gmsCoreText("com.google.android.gms"))
        assertEquals("app.revanced", gmsCoreText("com.google"))
        assertEquals("app.revanced.android.c2dm.intent.REGISTER", gmsCoreText("com.google.android.c2dm.intent.REGISTER"))
        assertEquals("app.revanced.iid.TOKEN_REQUEST", gmsCoreText("com.google.iid.TOKEN_REQUEST"))
        assertEquals("content://app.revanced.android.gsf.gservices/prefix", gmsCoreText("content://com.google.android.gsf.gservices/prefix"))
        assertEquals("content://app.revanced.settings", gmsCoreText("content://com.google.settings"))
        // The version key and the ad ID service keep Google's names: microG-RE answers to both as they are.
        for (kept in listOf("com.google.android.gms.version", "com.google.android.gms.ads.identifier.service.START",
            "content://com.google.settingsx", "com.google.android.gmsx", "com.facebook.katana")) assertNull(kept, gmsCoreText(kept))
    }

    @Test
    fun theReroutePlansBothChecksAndEveryTextAndRunsInAPatchContext() {
        val plan = planGmsCore(supported())
        assertEquals(availabilityOwner, plan.availability.definingClass)
        assertEquals(providerOwner, plan.frameworkProvider.definingClass)
        assertEquals(listOf("app.revanced.android.gms", "app.revanced.android.c2dm.intent.REGISTER", "app.revanced.iid.TOKEN_REQUEST"),
            plan.texts.map { it.text })
        assertEquals(listOf(GmsCoreField(registrationOwner, "A01", "app.revanced.android.gms")), plan.fields)

        val context = PatchContexts.of(supported())
        context.applyGmsCore(plan)
        val registered = context.mutableClassDefBy(registrationOwner)
        val loads = registered.methods.single().instructions()
        assertEquals(listOf("app.revanced.android.c2dm.intent.REGISTER", "app.revanced.iid.TOKEN_REQUEST", "com.facebook.katana"),
            loads.mapNotNull { it.text() })
        assertEquals("the jumbo load stays jumbo, in its register", Opcode.CONST_STRING_JUMBO to 17,
            loads[1].opcode to (loads[1] as OneRegisterInstruction).registerA)
        assertEquals("app.revanced.android.gms", (registered.staticFields.single().initialValue as StringEncodedValue).value)
        for (owner in listOf(availabilityOwner, providerOwner)) {
            val answered = context.mutableClassDefBy(owner).methods.single().instructions()
            // returnEarly loads an int with const and a boolean with const/4: either way a 0 the return hands back.
            assertTrue(owner, answered[0].opcode in setOf(Opcode.CONST_4, Opcode.CONST))
            assertEquals(owner, 0, (answered[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(owner, Opcode.RETURN, answered[1].opcode)
            assertEquals(owner, (answered[0] as OneRegisterInstruction).registerA, (answered[1] as OneRegisterInstruction).registerA)
        }
        assertEquals("the check's own texts move too", "app.revanced.android.gms",
            context.mutableClassDefBy(availabilityOwner).methods.single().instructions()[2].text())
    }

    @Test
    fun hushfacebooksOwnClassesKeepGooglesNames() {
        val probe = "Lapp/morphe/extension/facebook/Probe;"
        val extension = classDef(probe, listOf(method(probe, "A00", emptyList(), "V", AccessFlags.PUBLIC.value, 1,
            load(0, "com.google.android.gms"), ImmutableInstruction10x(Opcode.RETURN_VOID))),
            listOf(ImmutableField(probe, "A01", "Ljava/lang/String;", AccessFlags.STATIC.value,
                ImmutableStringEncodedValue("com.google.android.gms"), null, null)))
        val plan = planGmsCore(supported() + extension)
        assertTrue(plan.texts.none { it.method.definingClass == probe })
        assertTrue(plan.fields.none { it.type == probe })
    }

    @Test
    fun refusesWhenACheckIsMissingOrDoubled() {
        assertTrue(failure(listOf(provider(), registration())).contains("availability check has 0 matches"))
        assertTrue(failure(supported() + availability("LX/0675;")).contains("availability check has 2 matches"))
        // Without the version metadata read it's some other method that names Play services.
        assertTrue(failure(listOf(availability(version = "MetadataReader"), provider(), registration()))
            .contains("availability check has 0 matches"))
        assertTrue(failure(listOf(availability(), registration())).contains("framework provider has 0 matches"))
        assertTrue(failure(supported() + provider("LX/0yJP;")).contains("framework provider has 2 matches"))
    }

    @Test
    fun refusesWhenFacebookStopsAskingForATokenTheOldWay() {
        val both = listOf("com.google.android.c2dm.intent.REGISTER", "com.google.iid.TOKEN_REQUEST")
        for (gone in both) {
            val message = failure(listOf(availability(), provider(), registration(both - gone)))
            assertTrue(message, message.contains("Facebook no longer names $gone"))
            assertTrue(message, message.endsWith("Nothing was changed."))
        }
    }

    private fun xml(text: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(text.trimIndent())))

    private fun Document.text(): String = StringWriter().also {
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(this), StreamResult(it))
    }.toString()

    private fun Document.elements(tag: String) =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    /** 585's manifest cut down to the Cloud Messaging parts and its query block. */
    private val manifest = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.facebook.katana">
          <uses-permission android:name="com.google.android.c2dm.permission.RECEIVE"/>
          <uses-permission android:name="com.google.android.gms.permission.AD_ID"/>
          <application>
            <meta-data android:name="com.google.android.gms.version" android:value="@integer/google_play_services_version"/>
            <receiver android:name="com.google.firebase.iid.FirebaseInstanceIdReceiver" android:permission="com.google.android.c2dm.permission.SEND">
              <intent-filter><action android:name="com.google.android.c2dm.intent.RECEIVE"/></intent-filter>
            </receiver>
          </application>
          <queries><intent><action android:name="android.intent.action.VIEW"/></intent></queries>
        </manifest>
    """

    @Test
    fun manifestSendsCloudMessagingToMicrogAndNamesFacebooksCertificate() {
        val document = xml(manifest)
        rerouteManifestToGmsCore(document)
        val names = document.elements("*").flatMap { element ->
            (0 until element.attributes.length).map { element.attributes.item(it).nodeValue }
        }
        assertTrue(document.text(), names.none { it.startsWith("com.google.android.c2dm.") })
        for (moved in listOf("permission.RECEIVE", "permission.SEND", "intent.RECEIVE")) {
            assertTrue(moved, "app.revanced.android.c2dm.$moved" in names)
        }
        // Only Cloud Messaging's names move in the manifest: microG-RE reads the version key and AD_ID as Google's.
        assertTrue("com.google.android.gms.version" in names)
        assertTrue("com.google.android.gms.permission.AD_ID" in names)
        val queries = document.elements("queries").single()
        assertEquals("the package joins the query block Facebook already has, after the application", GMS_CORE_PACKAGE,
            (queries.lastChild as Element).getAttribute("android:name"))
        val spoof = document.elements("meta-data").single { it.getAttribute("android:name") == SPOOFED_SIGNATURE_METADATA }
        assertEquals("8a3c4b262d721acd49a4bf97d5213199c86fa2b9", spoof.getAttribute("android:value"))
        assertEquals("application", (spoof.parentNode as Element).tagName)
    }

    @Test
    fun aManifestWithoutQueriesGetsOneBeforeTheApplication() {
        val document = xml(manifest.replace("""<queries><intent><action android:name="android.intent.action.VIEW"/></intent></queries>""", ""))
        rerouteManifestToGmsCore(document)
        val queries = document.elements("queries").single()
        assertEquals("application", (queries.nextSibling as Element).tagName)
        assertEquals(GMS_CORE_PACKAGE, (queries.firstChild as Element).getAttribute("android:name"))
    }

    @Test
    fun manifestRefusesUnchangedWhenItCantBeRerouted() {
        val cases = mapOf(
            "doesn't ask for Cloud Messaging's receive permission" to manifest.replace(
                """<uses-permission android:name="com.google.android.c2dm.permission.RECEIVE"/>""", ""),
            "already names $GMS_CORE_PACKAGE" to manifest.replace("<queries>", """<queries><package android:name="$GMS_CORE_PACKAGE"/>"""),
            "already names $SPOOFED_SIGNATURE_METADATA" to manifest.replace("<application>",
                """<application><meta-data android:name="$SPOOFED_SIGNATURE_METADATA" android:value="00"/>"""),
            "already names app.revanced.android.c2dm.intent.RECEIVE" to manifest.replace(
                "</intent-filter>", """<action android:name="app.revanced.android.c2dm.intent.RECEIVE"/></intent-filter>"""),
        )
        for ((reason, text) in cases) {
            val document = xml(text)
            val before = document.text()
            val message = assertThrows(PatchException::class.java) { rerouteManifestToGmsCore(document) }.message!!
            assertTrue(message, message.contains(reason))
            assertEquals(reason, before, document.text())
        }
    }
}
