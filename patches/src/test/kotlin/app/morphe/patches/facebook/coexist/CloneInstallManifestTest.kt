/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.patch.OptionException
import app.morphe.patcher.patch.PatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

/**
 * Clone install under another package name (#417), on a hand-written manifest shaped like
 * Facebook's after Install beside Meta's apps renamed the shared permissions: everything named
 * after Facebook's package moves under the clone's, so the copy installs beside a preinstalled
 * Facebook, while class names, Facebook's ':' and empty task affinities, other apps' names and
 * the shared account type stay. Anything that would collide is refused with the manifest untouched.
 */
class CloneInstallManifestTest {
    private val facebook = "com.facebook.katana"
    private val clone = CLONE_DEFAULT_PACKAGE

    private fun alias(name: String, enabled: Boolean) = """
        <activity-alias android:name="com.facebook.katana.LoginActivity$name" android:enabled="$enabled"
                android:exported="true" android:targetActivity="com.facebook.katana.activity.FbMainTabActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity-alias>
    """

    private fun facebookLike(packageName: String = facebook, providers: String = DEFAULT_PROVIDERS, permissions: String = DEFAULT_PERMISSIONS) = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">
            $permissions
            <uses-permission android:name="android.permission.INTERNET"/>
            <queries>
                <package android:name="com.facebook.katana"/>
                <package android:name="com.facebook.orca"/>
                <provider android:authorities="com.facebook.orca.provider.PlatformProvider"/>
            </queries>
            <application android:label="@string/app_name" android:name="com.facebook.katana.app.FacebookApplication">
                <activity android:name="com.facebook.katana.activity.FbMainTabActivity" android:label="@string/app_name"
                        android:taskAffinity=":mhe" android:exported="true"/>
                <activity android:name="com.facebook.katana.CellularDataUsage" android:label="Cellular Data Usage"
                        android:taskAffinity=""/>
                <activity android:name="com.facebook.chatheads.ChatHeadsActivity"
                        android:taskAffinity="com.facebook.katana.tasks.chatheads"/>
                <activity android:name="com.facebook.messaging.MessengerBridge"
                        android:taskAffinity="com.facebook.orca.chatheads"/>
                ${alias(".katana", enabled = false)}
                ${alias("", enabled = true)}
                $providers
                <receiver android:name="com.facebook.push.fcm.FcmBroadcastReceiver" android:exported="true"
                        android:permission="com.google.android.c2dm.permission.SEND">
                    <intent-filter>
                        <action android:name="com.google.android.c2dm.intent.RECEIVE"/>
                        <category android:name="com.facebook.katana"/>
                    </intent-filter>
                </receiver>
                <receiver android:name="com.facebook.quicksilver.ShortcutMadeIntentReceiver" android:exported="true"
                        android:permission="com.facebook.katana.permission.CREATE_SHORTCUT"/>
                <receiver android:name="com.facebook.device_id.UniqueIdSupplier" android:exported="true"
                        android:permission="$RECEIVER_ACCESS"/>
                <service android:name="com.facebook.auth.login.AuthenticatorService" android:exported="true">
                    <meta-data android:name="android.accounts.AccountAuthenticator" android:resource="@xml/authenticator"/>
                </service>
                <meta-data android:name="com.facebook.sdk.ApplicationId" android:value="com.facebook.katana"/>
            </application>
        </manifest>
    """.trimIndent()

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))

    /** The manifest as Hushfacebook's default patches leave it: the two shared permissions renamed. */
    private fun patched(xml: String = facebookLike()): Document = parse(xml).also { it.renameSharedPermissions() }

    private fun Document.text(): String = StringWriter().also {
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(this), StreamResult(it))
    }.toString()

    private fun Document.all(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Document.named(tag: String, name: String) = all(tag).single { it.getAttribute("android:name") == name }

    private fun Document.declared() = all("permission").map { it.getAttribute("android:name") }.toSet()

    private fun refusal(document: Document, packageName: String = clone, label: String = CLONE_DEFAULT_LABEL): String {
        val before = document.text()
        val failure = assertThrows(PatchException::class.java) { document.renameForClone(packageName, label) }
        assertEquals("a refusal changed the manifest", before, document.text())
        return failure.message!!
    }

    @Test
    fun `everything named after Facebook moves under the clone`() {
        val document = patched()
        val renamed = document.renameForClone(clone, CLONE_DEFAULT_LABEL)

        assertEquals(clone, document.documentElement.getAttribute("package"))
        assertEquals(
            mapOf(
                "com.facebook.katana.provider.ACCESS" to "$clone.provider.ACCESS",
                "com.facebook.katana.permission.CREATE_SHORTCUT" to "$clone.permission.CREATE_SHORTCUT",
            ),
            renamed.permissions,
        )
        // The shared two keep the names every patched Meta app declares.
        assertEquals(
            setOf("$clone.provider.ACCESS", "$clone.permission.CREATE_SHORTCUT", renamed(APP_COMMUNICATION), renamed(RECEIVER_ACCESS)),
            document.declared(),
        )
        val stray = document.all("*").flatMap { element ->
            (0 until element.attributes.length).map { element.attributes.item(it) }
                .filter { it.nodeValue.startsWith("com.facebook.katana.provider.") || it.nodeValue.startsWith("com.facebook.katana.permission.") }
                .map { "<${element.tagName} ${it.nodeName}=${it.nodeValue}>" }
        }
        assertEquals("a permission or authority still under Facebook's name", emptyList<String>(), stray)
        assertEquals(
            "$clone.provider.ACCESS",
            document.all("path-permission").single().getAttribute("android:permission"),
        )
        assertEquals(
            "$clone.permission.CREATE_SHORTCUT",
            document.named("receiver", "com.facebook.quicksilver.ShortcutMadeIntentReceiver").getAttribute("android:permission"),
        )

        assertEquals(
            listOf(
                "$clone.provider.ContactsConnectionsProvider",
                "$clone.quicksilver.fileprovider;$clone.apkfileprovider",
            ),
            document.all("provider").filter { it.parentNode.nodeName == "application" }.map { it.getAttribute("android:authorities") },
        )
        assertEquals(3, renamed.authorities.size)
        assertEquals(
            "another app's provider it asks to see stays",
            "com.facebook.orca.provider.PlatformProvider",
            document.all("queries").single().getElementsByTagName("provider").let { (it.item(0) as Element).getAttribute("android:authorities") },
        )

        assertEquals("a ':' affinity, which Android completes with the package", ":mhe",
            document.named("activity", "com.facebook.katana.activity.FbMainTabActivity").getAttribute("android:taskAffinity"))
        assertEquals("no affinity", "", document.named("activity", "com.facebook.katana.CellularDataUsage").getAttribute("android:taskAffinity"))
        assertEquals("$clone.tasks.chatheads",
            document.named("activity", "com.facebook.chatheads.ChatHeadsActivity").getAttribute("android:taskAffinity"))
        assertEquals("$clone.com.facebook.orca.chatheads",
            document.named("activity", "com.facebook.messaging.MessengerBridge").getAttribute("android:taskAffinity"))
        assertEquals(2, renamed.affinities)

        assertEquals(1, renamed.pushCategories)
        val push = document.named("receiver", "com.facebook.push.fcm.FcmBroadcastReceiver").getElementsByTagName("category").item(0) as Element
        assertEquals(clone, push.getAttribute("android:name"))
        assertEquals(clone, document.named("meta-data", "com.facebook.sdk.ApplicationId").getAttribute("android:value"))
        assertEquals(
            listOf(clone, "com.facebook.orca"),
            document.all("package").map { it.getAttribute("android:name") },
        )

        val label = "@string/$CLONE_LABEL"
        assertEquals(label, document.all("application").single().getAttribute("android:label"))
        assertTrue("every launcher entry", document.all("activity-alias").all { it.getAttribute("android:label") == label })
        assertEquals(label, document.named("activity", "com.facebook.katana.activity.FbMainTabActivity").getAttribute("android:label"))
        assertEquals("Cellular Data Usage", document.named("activity", "com.facebook.katana.CellularDataUsage").getAttribute("android:label"))
        assertEquals(3, renamed.labels)

        // Class names name DEX classes, and the account type is how Meta's apps find each other's sign-in.
        assertTrue(document.all("activity-alias").all { it.getAttribute("android:name").startsWith("com.facebook.katana.LoginActivity") })
        assertEquals("com.facebook.katana.app.FacebookApplication", document.all("application").single().getAttribute("android:name"))
        assertEquals("@xml/authenticator", document.all("meta-data").first().getAttribute("android:resource"))
    }

    /**
     * The shared clone support runs after the rename on every build (finalize, reverse name order).
     * With the clone named under Facebook's package, its moved names start with Facebook's, and it
     * mustn't take them for stock ones and move them a second time.
     */
    @Test
    fun `following the renamed package afterwards changes nothing`() {
        val document = patched()
        val stockAuthorities = document.ownAuthorities(facebook)
        val stockPermissions = document.ownPermissions(facebook)
        document.renameForClone(clone, CLONE_DEFAULT_LABEL)
        val renamed = document.text()

        val followed = document.followRenamedPackage(facebook, stockAuthorities + document.ownAuthorities(facebook), stockPermissions)

        assertEquals(Followed(0, 0, 0), followed)
        assertEquals(renamed, document.text())
    }

    @Test
    fun `a package Clone app already renamed is refused`() {
        val message = refusal(patched(facebookLike(packageName = "com.facebook.katana.morphe")))
        assertTrue(message, message.contains("Pick either Clone app or this patch"))
    }

    @Test
    fun `an authority that isn't Facebook's is refused`() {
        val message = refusal(patched(facebookLike(providers = DEFAULT_PROVIDERS +
            """<provider android:name="x.Other" android:authorities="org.other.provider"/>""")))
        assertTrue(message, message.contains("org.other.provider isn't Facebook's own"))
    }

    @Test
    fun `authorities that would collide are refused`() {
        // A stock authority that already sits where another one would move.
        val crossing = """<provider android:name="x.A" android:authorities="com.facebook.katana.x"/>
            <provider android:name="x.B" android:authorities="com.facebook.katana.hush.x"/>"""
        assertTrue(refusal(patched(facebookLike(providers = crossing))).contains("would collide"))
        val twice = """<provider android:name="x.A" android:authorities="com.facebook.katana.x"/>
            <provider android:name="x.B" android:authorities="com.facebook.katana.x"/>"""
        assertTrue(refusal(patched(facebookLike(providers = twice))).contains("would collide"))
        assertTrue(refusal(patched(facebookLike(providers = """<provider android:name="x.A" android:authorities=" ; "/>"""))).contains("no authority"))
    }

    @Test
    fun `a shared permission left under Meta's name is refused`() {
        // Install beside Meta's apps hasn't run: FB_APP_COMMUNICATION is still Meta's own declaration.
        val message = refusal(parse(facebookLike()))
        assertTrue(message, message.contains(APP_COMMUNICATION))
    }

    @Test
    fun `a manifest without a permission of Facebook's own or a launcher is refused`() {
        val sharedOnly = """<permission android:name="$APP_COMMUNICATION" android:protectionLevel="signature"/>
            <permission android:name="$RECEIVER_ACCESS" android:protectionLevel="signature"/>"""
        assertTrue(refusal(patched(facebookLike(permissions = sharedOnly))).contains("no permissions of its own"))
        val document = patched()
        document.all("activity-alias").forEach { it.parentNode.removeChild(it) }
        assertTrue(refusal(document).contains("no launcher entry"))
    }

    @Test
    fun `an unusable name or label is refused before anything changes`() {
        assertTrue(refusal(patched(), packageName = "com.facebook.orca").contains("can't be used as a package name"))
        assertTrue(refusal(patched(), label = " Facebook").contains("can't be used as an app name"))
    }

    @Test
    fun `only usable package names pass`() {
        for (name in listOf(CLONE_DEFAULT_PACKAGE, "com.example.fb2", "com.facebook.katana.second", "a.b", "org.my_fb.App")) {
            assertTrue(name, isClonePackage(name))
        }
        val reserved = listOf(
            "com.facebook.katana", "com.facebook.orca", "com.facebook.lite", "com.facebook.mlite", "com.facebook.wakizashi",
            "com.instagram.android", "com.instagram.barcelona", "com.whatsapp", "com.whatsapp.w4b",
        )
        for (name in reserved + reserved.map { it.uppercase() } + listOf(
            null, "", "katana", "com.facebook.katana.", ".com.facebook", "com..facebook", "1com.facebook", "com.1facebook",
            "com.face book", "com.facebook-katana", "a." + "b".repeat(149),
        )) {
            assertFalse("$name", isClonePackage(name))
        }
    }

    @Test
    fun `only usable labels pass`() {
        for (label in listOf(CLONE_DEFAULT_LABEL, "FB", "Facebook (2)", "Work, Facebook!", "1st", "A".repeat(40), "Facebook Été")) {
            assertTrue(label, isCloneLabel(label))
        }
        for (label in listOf(null, "", " Facebook", "Facebook ", "-Facebook", "A".repeat(41), "Face<book>", "Face\"book", "Face\nbook")) {
            assertFalse("$label", isCloneLabel(label))
        }
    }

    @Test
    fun `the clone's name goes in a string resource of its own`() {
        val strings = parse("""<resources><string name="app_name">Facebook</string></resources>""")
        strings.putAppName(CLONE_DEFAULT_LABEL, CLONE_LABEL)
        val clone = strings.all("string").single { it.getAttribute("name") == CLONE_LABEL }
        assertEquals("\"$CLONE_DEFAULT_LABEL\"", clone.textContent)
        assertEquals("false", clone.getAttribute("translatable"))
        assertEquals("Facebook", strings.all("string").single { it.getAttribute("name") == "app_name" }.textContent)
    }

    @Test
    fun `the patch starts off and its options refuse what the rename refuses`() {
        assertEquals(CLONE_PATCH_NAME, cloneInstallPatch.name)
        assertFalse("selected by default", cloneInstallPatch.default)
        val packageName = cloneInstallPatch.options["clonePackageName"]
        val label = cloneInstallPatch.options["cloneAppName"]
        try {
            assertEquals(CLONE_DEFAULT_PACKAGE, packageName.default)
            assertEquals(CLONE_DEFAULT_LABEL, label.default)
            assertTrue(packageName.required && label.required)

            cloneInstallPatch.options["clonePackageName"] = "com.example.fb2"
            assertEquals("com.example.fb2", packageName.value)
            cloneInstallPatch.options["cloneAppName"] = "Work Facebook"
            assertEquals("Work Facebook", label.value)
            assertThrows(OptionException.ValueValidationException::class.java) {
                cloneInstallPatch.options["clonePackageName"] = "com.facebook.orca"
            }
            assertThrows(OptionException.ValueValidationException::class.java) {
                cloneInstallPatch.options["cloneAppName"] = " "
            }
        } finally {
            packageName.reset()
            label.reset()
        }
    }

    private companion object {
        /** Facebook's own two and the shared two, each requested, as 585 declares them. */
        val DEFAULT_PERMISSIONS = """
            <permission android:name="com.facebook.katana.provider.ACCESS" android:protectionLevel="signature"/>
            <uses-permission android:name="com.facebook.katana.provider.ACCESS"/>
            <permission android:name="com.facebook.katana.permission.CREATE_SHORTCUT" android:protectionLevel="signature"/>
            <uses-permission android:name="com.facebook.katana.permission.CREATE_SHORTCUT"/>
            <permission android:name="$APP_COMMUNICATION" android:protectionLevel="signature"/>
            <uses-permission android:name="$APP_COMMUNICATION"/>
            <uses-permission-sdk-23 android:name="$APP_COMMUNICATION"/>
            <permission android:name="$RECEIVER_ACCESS" android:protectionLevel="signature"/>
            <uses-permission android:name="$RECEIVER_ACCESS"/>
        """

        /** One guarded provider with a path permission, and one with two authorities. */
        val DEFAULT_PROVIDERS = """
            <provider android:name="com.facebook.contacts.provider.ContactsConnectionsProvider"
                    android:authorities="com.facebook.katana.provider.ContactsConnectionsProvider"
                    android:exported="true" android:permission="com.facebook.katana.provider.ACCESS"
                    android:readPermission="$APP_COMMUNICATION">
                <path-permission android:pathPrefix="/x" android:permission="com.facebook.katana.provider.ACCESS"/>
            </provider>
            <provider android:name="androidx.core.content.FileProvider"
                    android:authorities="com.facebook.katana.quicksilver.fileprovider; com.facebook.katana.apkfileprovider"
                    android:exported="false"/>
        """
    }
}
