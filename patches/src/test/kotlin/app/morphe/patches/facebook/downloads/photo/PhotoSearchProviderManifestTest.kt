/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.photo

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.coexist.followRenamedPackage
import app.morphe.patches.facebook.coexist.ownAuthorities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Search photo's provider (#245, #248): declared once, unexported and granting reads, under the
 * package's own name; refused without an application; and moved under a clone's name with the
 * stock list the clone patch reads before any patch runs, as its finalize does.
 */
class PhotoSearchProviderManifestTest {
    private val facebook = "com.facebook.katana"
    private val clone = "com.facebook.katana.morphe"

    private fun manifest(body: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
        InputSource(
            StringReader(
                """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$facebook">""" +
                    body + "</manifest>",
            ),
        ),
    )

    private fun Document.providers(): List<Element> = getElementsByTagName("provider").let { list ->
        (0 until list.length).map { list.item(it) as Element }.filter { it.getAttribute("android:name") == PHOTO_SEARCH_PROVIDER }
    }

    private val stock = """
        <application>
            <provider android:name="com.facebook.katana.provider.AttributionIdProvider"
                android:authorities="com.facebook.katana.provider.AttributionIdProvider" android:exported="true"/>
        </application>
    """

    @Test
    fun `the provider is declared once, unexported, granting reads`() {
        val document = manifest(stock)
        document.declarePhotoSearchProvider(facebook)
        document.declarePhotoSearchProvider(facebook)
        val provider = document.providers().single()
        assertEquals("$facebook.hushfacebook.photosearch", provider.getAttribute("android:authorities"))
        assertEquals("false", provider.getAttribute("android:exported"))
        assertEquals("true", provider.getAttribute("android:grantUriPermissions"))
    }

    @Test
    fun `a manifest without an application is refused`() {
        val document = manifest("")
        assertThrows(PatchException::class.java) { document.declarePhotoSearchProvider(facebook) }
        assertEquals(0, document.providers().size)
    }

    @Test
    fun `a clone moves the provider added after the stock list was read`() {
        val document = manifest(stock)
        val stockAuthorities = document.ownAuthorities(facebook)
        document.declarePhotoSearchProvider(facebook)
        // Clone app renames the package after every patch executed.
        document.documentElement.setAttribute("package", clone)

        val followed = checkNotNull(
            document.followRenamedPackage(facebook, stockAuthorities + document.ownAuthorities(facebook), emptySet()),
        )
        assertEquals(2, followed.authorities)
        assertEquals("$clone.hushfacebook.photosearch", document.providers().single().getAttribute("android:authorities"))

        // With the stock list alone, as before, the provider stayed the stock app's.
        val before = manifest(stock)
        val beforeStock = before.ownAuthorities(facebook)
        before.declarePhotoSearchProvider(facebook)
        before.documentElement.setAttribute("package", clone)
        before.followRenamedPackage(facebook, beforeStock, emptySet())
        assertEquals("$facebook.hushfacebook.photosearch", before.providers().single().getAttribute("android:authorities"))
    }
}
