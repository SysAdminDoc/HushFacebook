/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.patcher.patch.PatchException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Hushfacebook settings' Icon picture and Icon background options (#467): the picture is checked
 * as hushfeed's Custom launcher icon checks it, before the patch reads or writes anything.
 */
class LauncherPictureTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the background color takes RRGGBB and refuses anything else`() {
        assertNull(pickedIconBackground(null))
        assertNull(pickedIconBackground("  "))
        for (written in listOf("#1E88E5", "1e88e5", " #1e88e5 ", "#FF1E88E5", "ff1E88e5")) {
            assertEquals(written, "#ff1e88e5", pickedIconBackground(written))
        }
        for (wrong in listOf("blue", "#12345", "#1234567", "#GG88E5", "#801E88E5", "#1E88E5FF", "##1E88E5")) {
            val message = assertThrows(wrong, PatchException::class.java) { pickedIconBackground(wrong) }.message!!
            assertTrue(message, message.contains("Icon background: \"") && message.contains("isn't a color"))
            assertTrue(message, message.endsWith("Nothing was changed."))
        }
    }

    @Test
    fun `a real PNG's size is read off its header`() {
        assertEquals(432 to 432, pngSize(png(432, 432)))
        assertEquals(500 to 300, pngSize(png(500, 300)))
        val bytes = png(16, 16)
        assertEquals(16 to 16, pngSize(bytes + byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `a damaged or cut short PNG is refused`() {
        val bytes = png(16, 16)
        assertNull("cut short", pngSize(bytes.copyOf(bytes.size - 5)))
        assertNull("no end chunk", pngSize(bytes.copyOf(bytes.size - 12)))
        assertNull("not a PNG", pngSize("GIF89a not a png at all".toByteArray()))
        assertNull("empty", pngSize(ByteArray(0)))
        val flipped = bytes.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 0x40).toByte() }
        assertNull("checksum", pngSize(flipped))

        val header = ihdr(16, 16, 8, 6)
        val data = chunk("IDAT", deflate(scanlines(16, 16, 8, 6)))
        val end = chunk("IEND", ByteArray(0))
        assertEquals(16 to 16, pngSize(SIGNATURE + header + data + end))
        assertNull("header not first", pngSize(SIGNATURE + data + header + end))
        assertNull("no image data", pngSize(SIGNATURE + header + end))
        assertNull("two headers", pngSize(SIGNATURE + header + header + data + end))
        val huge = SIGNATURE + header.copyOf().also { ByteBuffer.wrap(it).putInt(0, Int.MAX_VALUE) } + data + end
        assertNull("a length past the end", pngSize(huge))
    }

    @Test
    fun `a PNG header the spec doesn't allow is refused`() {
        fun header(depth: Int, color: Int, compression: Int = 0, filter: Int = 0, interlace: Int = 0) =
            chunk("IHDR", ByteBuffer.allocate(13).putInt(16).putInt(16)
                .put(byteArrayOf(depth.toByte(), color.toByte(), compression.toByte(), filter.toByte(), interlace.toByte())).array())
        fun data(depth: Int, color: Int) = chunk("IDAT", deflate(scanlines(16, 16, depth, color)))
        val palette = chunk("PLTE", byteArrayOf(0, 0, 0, -1, -1, -1))
        val end = chunk("IEND", ByteArray(0))

        for ((depth, color) in listOf(1 to 0, 16 to 0, 8 to 2, 16 to 6, 8 to 4)) {
            assertEquals("depth $depth color $color", 16 to 16, pngSize(SIGNATURE + header(depth, color) + data(depth, color) + end))
        }
        assertEquals("interlaced", 16 to 16, pngSize(encode(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), interlaced = true)))
        assertEquals("palette first", 16 to 16, pngSize(SIGNATURE + header(8, 3) + palette + data(8, 3) + end))

        assertNull("16-bit palette", pngSize(SIGNATURE + header(16, 3) + palette + data(8, 3) + end))
        assertNull("4-bit color", pngSize(SIGNATURE + header(4, 2) + data(8, 2) + end))
        assertNull("3-bit gray", pngSize(SIGNATURE + header(3, 0) + data(8, 0) + end))
        assertNull("color type 5", pngSize(SIGNATURE + header(8, 5) + data(8, 6) + end))
        assertNull("compression", pngSize(SIGNATURE + header(8, 6, compression = 1) + data(8, 6) + end))
        assertNull("filter", pngSize(SIGNATURE + header(8, 6, filter = 1) + data(8, 6) + end))
        assertNull("interlace", pngSize(SIGNATURE + header(8, 6, interlace = 2) + data(8, 6) + end))
        assertNull("no palette", pngSize(SIGNATURE + header(8, 3) + data(8, 3) + end))
        assertNull("palette after the data", pngSize(SIGNATURE + header(8, 3) + data(8, 3) + palette + end))
    }

    @Test
    fun `image data that doesn't inflate to the header's scanlines is refused`() {
        val header = ihdr(16, 16, 8, 6)
        val end = chunk("IEND", ByteArray(0))
        fun image(vararg data: ByteArray) = SIGNATURE + header + data.fold(ByteArray(0)) { all, part -> all + chunk("IDAT", part) } + end
        val rows = scanlines(16, 16, 8, 6)
        val whole = deflate(rows)
        assertEquals(16 to 16, pngSize(image(whole)))
        assertEquals("over two chunks", 16 to 16, pngSize(image(whole.copyOfRange(0, 5), whole.copyOfRange(5, whole.size))))
        val everyFilter = rows.copyOf().also { for (row in 0 until 16) it[row * 65] = (row % 5).toByte() }
        assertEquals("filter types 0 to 4", 16 to 16, pngSize(image(deflate(everyFilter))))

        assertNull("no scanlines at all", pngSize(image(deflate(ByteArray(0)))))
        assertNull("a byte short", pngSize(image(deflate(rows.copyOf(rows.size - 1)))))
        assertNull("a byte over", pngSize(image(deflate(rows + byteArrayOf(0)))))
        assertNull("a row over", pngSize(image(deflate(rows + ByteArray(65)))))
        assertNull("rows for a narrower picture", pngSize(image(deflate(scanlines(15, 16, 8, 6)))))
        assertNull("plain rows under an interlaced header", pngSize(SIGNATURE + ihdr(16, 16, 8, 6, interlace = 1) + chunk("IDAT", whole) + end))
        for (filter in listOf(5, -1)) {
            assertNull("filter type $filter", pngSize(image(deflate(rows.copyOf().also { it[65] = filter.toByte() }))))
        }
        assertNull("not zlib", pngSize(image(whole.copyOf().also { it[0] = 0x79 })))
        assertNull("zlib checksum", pngSize(image(whole.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })))
        assertNull("zlib cut short", pngSize(image(whole.copyOf(whole.size - 4))))
        assertNull("bytes after the zlib end", pngSize(image(whole + byteArrayOf(0))))
        val text = chunk("tEXt", "a".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + "b".toByteArray(Charsets.ISO_8859_1))
        assertNull("data in two runs", pngSize(SIGNATURE + header + chunk("IDAT", whole.copyOfRange(0, 5)) + text +
            chunk("IDAT", whole.copyOfRange(5, whole.size)) + end))
    }

    @Test
    fun `a palette the spec doesn't allow is refused`() {
        val end = chunk("IEND", ByteArray(0))
        fun image(depth: Int, color: Int, vararg before: ByteArray) = SIGNATURE + ihdr(16, 16, depth, color) +
            before.fold(ByteArray(0)) { all, part -> all + part } + chunk("IDAT", deflate(scanlines(16, 16, depth, color))) + end
        fun palette(colors: Int) = chunk("PLTE", ByteArray(colors * 3))

        assertEquals("256 colors", 16 to 16, pngSize(image(8, 3, palette(256))))
        assertEquals("two colors at 1 bit", 16 to 16, pngSize(image(1, 3, palette(2))))
        assertEquals("a suggested palette for true color", 16 to 16, pngSize(image(8, 2, palette(16))))

        assertNull("257 colors", pngSize(image(8, 3, palette(257))))
        assertNull("three colors at 1 bit", pngSize(image(1, 3, palette(3))))
        assertNull("no colors", pngSize(image(8, 3, palette(0))))
        assertNull("part of a color", pngSize(image(8, 3, chunk("PLTE", ByteArray(4)))))
        assertNull("two palettes", pngSize(image(8, 3, palette(2), palette(2))))
        assertNull("a gray palette", pngSize(image(8, 0, palette(2))))
        assertNull("a gray and alpha palette", pngSize(image(8, 4, palette(2))))
    }

    @Test
    fun `PNGs a real encoder writes still pass, interlaced too`() {
        // ImageIO's own writer, so the row and Adam7 pass math is checked against an encoder and
        // not against a copy of itself. Odd sizes leave some interlace passes empty.
        val types = listOf(
            BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_BYTE_GRAY,
            BufferedImage.TYPE_USHORT_GRAY, BufferedImage.TYPE_BYTE_INDEXED, BufferedImage.TYPE_BYTE_BINARY,
        )
        for (type in types) for ((width, height) in listOf(1 to 1, 3 to 5, 9 to 9, 37 to 21)) for (interlaced in listOf(false, true)) {
            val image = BufferedImage(width, height, type)
            for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * 7919 + y * 104729) or 0xff000000.toInt())
            val bytes = encode(image, interlaced)
            val case = "type $type, $width by $height, interlaced $interlaced"
            assertEquals(case, if (interlaced) 1 else 0, bytes[28].toInt())
            assertEquals(case, width to height, pngSize(bytes))
        }
    }

    @Test
    fun `a missing, damaged, oblong, small or large picture stops patching with a reason`() {
        fun refusal(path: String): String = assertThrows(PatchException::class.java) { launcherPicture(path) }.message!!
        fun file(name: String, bytes: ByteArray) = folder.newFile(name).apply { writeBytes(bytes) }.path

        assertNull("empty keeps the logo", launcherPicture(" "))
        assertTrue(refusal(folder.root.resolve("gone.png").path).contains("there's no file at"))
        assertTrue(refusal(folder.root.path).contains("there's no PNG in the folder"))
        assertTrue(refusal(file("photo.png", "not a png".toByteArray())).contains("isn't a PNG"))
        val blank = SIGNATURE + ihdr(432, 432, 8, 6) + chunk("IDAT", deflate(ByteArray(0))) + chunk("IEND", ByteArray(0))
        assertTrue(refusal(file("blank.png", blank)).contains("isn't a PNG, or it's damaged or cut short"))
        assertTrue(refusal(file("wide.png", png(600, 432))).contains("has to be square, and \"wide.png\" is 600 by 432 pixels"))
        assertTrue(refusal(file("small.png", png(431, 431))).contains("from 432 to 1024 pixels a side, and \"small.png\" is 431"))
        assertTrue(refusal(file("large.png", png(1025, 1025))).contains("is 1025"))
        val big = folder.newFile("big.png").also { RandomAccessFile(it, "rw").use { raf -> raf.setLength(8L * 1024 * 1024 + 1) } }
        assertTrue(refusal(big.path).contains("larger than 8 MB"))
        for (message in listOf(refusal(big.path), refusal(file("tiny.png", png(16, 16))))) {
            assertTrue(message, message.startsWith("Hushfacebook settings, Icon picture: ") && message.endsWith(" Nothing was changed."))
        }

        for (side in listOf(MIN_PICTURE_SIDE, 512, MAX_PICTURE_SIDE)) {
            val bytes = png(side, side)
            val picture = launcherPicture(" " + file("icon-$side.png", bytes) + " ")!!
            assertEquals(side, picture.side)
            assertArrayEquals(bytes, picture.bytes)
        }
    }

    @Test
    fun `a folder from Manager's picker gives its only PNG, or icon png among several`() {
        fun refusal(path: String): String = assertThrows(PatchException::class.java) { launcherPicture(path) }.message!!

        val one = folder.newFolder("one")
        val only = png(512, 512)
        one.resolve("my picture.PNG").writeBytes(only)
        one.resolve("notes.txt").writeText("not a picture")
        assertArrayEquals(only, launcherPicture(one.path)!!.bytes)

        val several = folder.newFolder("several")
        val chosen = png(600, 600)
        several.resolve("a.png").writeBytes(png(432, 432))
        several.resolve("Icon.png").writeBytes(chosen)
        several.resolve("z.png").writeBytes(png(1024, 1024))
        val picked = launcherPicture(several.path)!!
        assertEquals(600, picked.side)
        assertArrayEquals(chosen, picked.bytes)

        val unnamed = folder.newFolder("unnamed")
        for (index in 1..10) unnamed.resolve("photo$index.png").writeBytes(png(432, 432))
        val message = refusal(unnamed.path)
        assertTrue(message, message.contains("holds 10 PNGs (photo1.png, photo10.png, photo2.png"))
        assertTrue(message, message.contains("photo7.png and 2 more). Name the one to use icon.png"))
        assertTrue(message, message.endsWith(" Nothing was changed."))

        assertTrue(refusal(folder.newFolder("empty").path).contains("there's no PNG in the folder"))
        assertArrayEquals(only, launcherPicture(one.resolve("my picture.PNG").path)!!.bytes)
    }

    @Test
    fun `a Mac's hidden copy beside the picture doesn't count as a second PNG`() {
        val copied = folder.newFolder("copied")
        val picture = png(512, 512)
        copied.resolve("logo.png").writeBytes(picture)
        copied.resolve("._logo.png").writeBytes(byteArrayOf(0, 5, 22, 7))
        copied.resolve(".hidden.png").writeBytes(png(432, 432))
        assertArrayEquals(picture, launcherPicture(copied.path)!!.bytes)

        val onlyHidden = folder.newFolder("only-hidden")
        onlyHidden.resolve("._logo.png").writeBytes(byteArrayOf(0, 5, 22, 7))
        val message = assertThrows(PatchException::class.java) { launcherPicture(onlyHidden.path) }.message!!
        assertTrue(message, message.contains("there's no PNG in the folder"))
    }

    @Test
    fun `a folder that can't be read says so instead of claiming it has no PNG`() {
        val locked = folder.newFolder("locked")
        locked.resolve("icon.png").writeBytes(png(512, 512))
        val unreadable = object : File(locked.path) {
            override fun listFiles(): Array<File>? = null
        }
        val message = assertThrows(IllegalStateException::class.java) {
            pictureInFolder(unreadable) { throw IllegalStateException(it) }
        }.message!!
        assertEquals(
            "the folder \"${locked.path}\" couldn't be read. Give Icon picture the file's full path instead.",
            message,
        )
    }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        assertTrue(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it))
    }.toByteArray()

    private fun encode(image: BufferedImage, interlaced: Boolean): ByteArray = ByteArrayOutputStream().also { out ->
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam
                param.progressiveMode = if (interlaced) ImageWriteParam.MODE_DEFAULT else ImageWriteParam.MODE_DISABLED
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
    }.toByteArray()

    private fun ihdr(width: Int, height: Int, depth: Int, color: Int, interlace: Int = 0) =
        chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(byteArrayOf(depth.toByte(), color.toByte(), 0, 0, interlace.toByte())).array())

    /** Plain (not interlaced) scanlines of zeros, filter type 0, for a picture of this size and format. */
    private fun scanlines(width: Int, height: Int, depth: Int, color: Int): ByteArray {
        val channels = mapOf(0 to 1, 2 to 3, 3 to 1, 4 to 2, 6 to 4).getValue(color)
        return ByteArray(height * (1 + (width * channels * depth + 7) / 8))
    }

    private fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DeflaterOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray(Charsets.ISO_8859_1) + data
        val crc = CRC32().apply { update(body) }.value.toInt()
        return ByteBuffer.allocate(4).putInt(data.size).array() + body + ByteBuffer.allocate(4).putInt(crc).array()
    }

    private companion object {
        val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
