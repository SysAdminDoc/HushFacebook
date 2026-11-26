/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.patcher.patch.PatchException
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/*
 * The Icon picture and Icon background options of Hushfacebook settings (#467): a picture of your
 * own as the home screen icon's foreground, and a color of your own behind it or behind the style's
 * logo. The picture checks are hushfeed's Custom launcher icon ones, file for file.
 */

/** The drawable a supplied picture is added as. */
internal const val PICTURE_NAME = "hushfacebook_launcher_picture"

/** An adaptive icon's layer is 108dp, which is 432 pixels on the densest (xxxhdpi) screens. */
internal const val MIN_PICTURE_SIDE = 432

/** The launcher decodes the file whole, so this keeps the picture to 4 MB of memory. */
internal const val MAX_PICTURE_SIDE = 1024

private const val MAX_PICTURE_MEGABYTES = 8

private const val PICTURE_OPTION = "Hushfacebook settings, Icon picture"

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

/** A supplied picture, read and checked before the patch reads or writes anything else. */
internal class LauncherPicture(val bytes: ByteArray, val side: Int)

/**
 * The background color option as `#ffrrggbb`, or null when it's empty. It takes `#RRGGBB`, with
 * or without the `#`, or the same with an opaque `FF` in front. A see-through color is refused:
 * the launcher would show whatever is behind the icon through it.
 */
internal fun pickedIconBackground(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val hex = text.removePrefix("#").let { if (it.length == 8 && it.startsWith("ff", ignoreCase = true)) it.substring(2) else it }
    if (!Regex("[0-9a-fA-F]{6}").matches(hex)) {
        throw PatchException(
            "Hushfacebook settings, Icon background: \"$text\" isn't a color. Write it as #RRGGBB, like #1E88E5. Nothing was changed.",
        )
    }
    return "#ff" + hex.lowercase(Locale.ROOT)
}

/**
 * The picture at [raw], or null when the option is empty. A file Android's PNG decoder would
 * refuse is refused here instead, and so is one that isn't square or is too small or too large
 * for an icon. The size comes off the PNG header, so no image library is needed: Morphe Manager
 * patches on the phone, where there is none.
 */
internal fun launcherPicture(raw: String?): LauncherPicture? {
    val path = raw?.trim().orEmpty()
    if (path.isEmpty()) return null
    fun refuse(why: String): Nothing = throw PatchException("$PICTURE_OPTION: $why Nothing was changed.")
    val named = File(path)
    // Morphe Manager's picker for this option hands back a folder, not a file.
    val file = if (named.isDirectory) pictureInFolder(named) { refuse(it) } else named
    if (!file.isFile) refuse("there's no file at \"$path\".")
    if (file.length() > MAX_PICTURE_MEGABYTES * 1024L * 1024L) {
        refuse("\"${file.name}\" is larger than $MAX_PICTURE_MEGABYTES MB, which is too big for an icon.")
    }
    val bytes = file.readBytes()
    val (width, height) = pngSize(bytes) ?: refuse("\"${file.name}\" isn't a PNG, or it's damaged or cut short.")
    if (width != height) refuse("the picture has to be square, and \"${file.name}\" is $width by $height pixels.")
    if (width !in MIN_PICTURE_SIDE..MAX_PICTURE_SIDE) {
        refuse(
            "the picture has to be from $MIN_PICTURE_SIDE to $MAX_PICTURE_SIDE pixels a side, " +
                "and \"${file.name}\" is $width.",
        )
    }
    return LauncherPicture(bytes, width)
}

/**
 * The picture in [folder]: its only PNG, or `icon.png` (any case) when it holds several. Anything
 * else goes to [refuse] with the PNGs it found, at most eight of them by name. Hidden files are
 * passed over: a Mac copying to a memory card leaves a `._name.png` beside every picture, and
 * that would make one picture look like two.
 */
internal fun pictureInFolder(folder: File, refuse: (String) -> Nothing): File {
    // Null, not empty, when the folder can't be read, which on a phone usually means the app
    // patching wasn't given access to it. Saying it holds no PNG would send someone looking for
    // a picture that's right there.
    val entries = folder.listFiles()
        ?: refuse("the folder \"${folder.path}\" couldn't be read. Give Icon picture the file's full path instead.")
    val pngs = entries
        .filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(".png", ignoreCase = true) }
        .sortedBy { it.name.lowercase() }
    if (pngs.isEmpty()) refuse("there's no PNG in the folder \"${folder.path}\".")
    if (pngs.size == 1) return pngs.single()
    pngs.firstOrNull { it.name.equals("icon.png", ignoreCase = true) }?.let { return it }
    val shown = pngs.take(8).joinToString { it.name } + if (pngs.size > 8) " and ${pngs.size - 8} more" else ""
    refuse(
        "the folder \"${folder.path}\" holds ${pngs.size} PNGs ($shown). Name the one to use icon.png, " +
            "or give Icon picture the file's full path.",
    )
}

/**
 * The width and height in a whole, well-formed PNG, or null. Every chunk has to fit the file and
 * match its checksum, the header has to come first with a bit depth, color type and method the
 * PNG spec allows, a palette has to be one the spec allows and come before the image data, which
 * a palette image needs, and the file needs image data in one run and an end chunk. The image data
 * also has to inflate to exactly the scanlines the header calls for ([scanlinesValid]). A file
 * the resource packager would copy as it is but Android couldn't draw is caught here, not on the
 * home screen.
 */
internal fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < PNG_SIGNATURE.size || !bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) return null
    var offset = PNG_SIGNATURE.size
    var size: Pair<Int, Int>? = null
    var depth = 0
    var color = 0
    var interlaced = false
    var palette = false
    val data = ByteArrayOutputStream()
    var images = 0
    var dataEnded = false
    while (offset + 12 <= bytes.size) {
        val length = bytes.bigEndianInt(offset)
        if (length < 0 || length > bytes.size - offset - 12) return null
        val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(bytes, offset + 4, 4 + length) }.value
        if (crc != (bytes.bigEndianInt(offset + 8 + length).toLong() and 0xffffffffL)) return null
        if (size == null) {
            if (type != "IHDR" || length != 13 || !pngHeaderValid(bytes, offset + 8)) return null
            size = bytes.bigEndianInt(offset + 8) to bytes.bigEndianInt(offset + 12)
            depth = bytes[offset + 16].toInt()
            color = bytes[offset + 17].toInt()
            interlaced = bytes[offset + 20].toInt() == 1
        } else {
            if (images > 0 && type != "IDAT") dataEnded = true
            when (type) {
                "IHDR" -> return null
                "PLTE" -> {
                    // Grayscale has no palette; one is a multiple of three bytes, at most 256
                    // colors, and no more than a palette image's bit depth can index.
                    val colors = length / 3
                    if (palette || images > 0 || color == 0 || color == 4 || length == 0 || length % 3 != 0 ||
                        colors > 256 || (color == 3 && colors > 1 shl depth)
                    ) return null
                    palette = true
                }
                "IDAT" -> {
                    if ((color == 3 && !palette) || dataEnded) return null
                    data.write(bytes, offset + 8, length)
                    images++
                }
                "IEND" -> return size.takeIf {
                    images > 0 && it.first > 0 && it.second > 0 &&
                        scanlinesValid(data.toByteArray(), it.first, it.second, depth, color, interlaced)
                }
            }
        }
        offset += 12 + length
    }
    return null
}

/** Adam7's passes: the first column and row each one reads, and its steps across and down. */
private val ADAM7 = listOf(
    intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
    intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2),
)

/**
 * Whether [compressed] inflates to exactly the scanlines a [width] by [height] image of this bit
 * depth and color type has, pass by pass when [interlaced], each row starting with a filter type
 * the spec has (0 to 4). It inflates in pieces and stops at the first byte past the last row, so
 * a header that claims a huge image costs no more than the data that's there. A zlib stream that
 * is damaged, cut short, needs a preset dictionary or has bytes after its end fails too.
 */
private fun scanlinesValid(compressed: ByteArray, width: Int, height: Int, depth: Int, color: Int, interlaced: Boolean): Boolean {
    val channels = when (color) {
        0, 3 -> 1
        4 -> 2
        2 -> 3
        else -> 4
    }
    val bitsPerPixel = channels.toLong() * depth
    val passes = if (interlaced) ADAM7 else listOf(intArrayOf(0, 0, 1, 1))
    var pass = -1
    var rowsLeft = 0L
    var rowLength = 0L
    var left = 0L
    // Moves to the next row of the image, false when there is none. A pass with no columns has
    // no rows in the data at all, not even their filter bytes.
    fun nextRow(): Boolean {
        while (rowsLeft == 0L) {
            if (++pass >= passes.size) return false
            val (x0, y0, dx, dy) = passes[pass]
            val columns = if (width > x0) (width - x0 + dx - 1L) / dx else 0L
            val rows = if (height > y0) (height - y0 + dy - 1L) / dy else 0L
            if (columns == 0L) continue
            rowsLeft = rows
            rowLength = 1 + (columns * bitsPerPixel + 7) / 8
        }
        rowsLeft--
        return true
    }

    val inflater = Inflater()
    try {
        inflater.setInput(compressed)
        val buffer = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            var at = 0
            while (at < count) {
                if (left == 0L) {
                    if (!nextRow() || buffer[at] !in 0..4) return false
                    left = rowLength - 1
                    at++
                } else {
                    val skip = minOf(left, (count - at).toLong()).toInt()
                    left -= skip
                    at += skip
                }
            }
            if (count == 0 && !inflater.finished()) return false
        }
        return inflater.remaining == 0 && left == 0L && !nextRow()
    } catch (damaged: DataFormatException) {
        return false
    } finally {
        inflater.end()
    }
}

/** Whether the IHDR body at [at] names a bit depth and color type pair, compression, filter and interlace method the PNG spec allows. */
private fun pngHeaderValid(bytes: ByteArray, at: Int): Boolean {
    val depth = bytes[at + 8].toInt()
    val depths = when (bytes[at + 9].toInt()) {
        0 -> setOf(1, 2, 4, 8, 16)
        3 -> setOf(1, 2, 4, 8)
        2, 4, 6 -> setOf(8, 16)
        else -> return false
    }
    return depth in depths && bytes[at + 10].toInt() == 0 && bytes[at + 11].toInt() == 0 && bytes[at + 12].toInt() in 0..1
}

private fun ByteArray.bigEndianInt(at: Int): Int =
    ((this[at].toInt() and 0xff) shl 24) or ((this[at + 1].toInt() and 0xff) shl 16) or
        ((this[at + 2].toInt() and 0xff) shl 8) or (this[at + 3].toInt() and 0xff)
