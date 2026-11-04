package io.github.stickerbox.core

import java.io.InputStream
import java.io.OutputStream

/** Content sniffing is deliberately independent of extensions (e.g. FunBox cache names). */
object MediaRules {
    const val MAX_FILE = 32L * 1024 * 1024
    const val MAX_ARCHIVE = 512L * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    enum class Kind(val mime: String, val extension: String) {
        PNG("image/png", "png"), JPEG("image/jpeg", "jpg"),
        GIF("image/gif", "gif"), WEBP("image/webp", "webp"), HEIF("image/heic", "heic")
    }

    fun detect(h: ByteArray): Kind? {
        fun at(offset: Int, value: String) = h.size >= offset + value.length &&
            value.indices.all { h[offset + it].toInt().and(255) == value[it].code }
        return when {
            h.size >= 8 && h.take(8).map { it.toInt().and(255) } == listOf(137,80,78,71,13,10,26,10) -> Kind.PNG
            h.size >= 3 && h[0].toInt().and(255) == 255 && h[1].toInt().and(255) == 216 && h[2].toInt().and(255) == 255 -> Kind.JPEG
            at(0, "GIF87a") || at(0, "GIF89a") -> Kind.GIF
            at(0, "RIFF") && at(8, "WEBP") -> Kind.WEBP
            at(4, "ftyp") && (8..(h.size - 4).coerceAtMost(60) step 4).any { p ->
                listOf("heic", "heix", "hevc", "hevx", "mif1", "msf1").any { at(p, it) }
            } -> Kind.HEIF
            else -> null
        }
    }

    fun safeEntry(path: String): Boolean {
        val s = path.replace('\\', '/')
        return s.isNotBlank() && s.length <= 1024 && !s.startsWith('/') &&
            !s.contains('\u0000') && !Regex("^[A-Za-z]:").containsMatchIn(s) &&
            s.split('/').none { it == ".." }
    }

    fun copyBounded(input: InputStream, output: OutputStream, max: Long): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= max) { "File exceeds byte limit" }
            output.write(buffer, 0, n)
        }
        return total
    }

    fun accepts(advertised: Array<String>, mime: String) = advertised.any {
        it == mime || it == "*/*" || it == mime.substringBefore('/') + "/*"
    }

    fun shouldConvertToPng(mime: String, advertised: Array<String>) =
        mime != "image/gif" && mime != "image/png" &&
            !accepts(advertised, mime) && accepts(advertised, "image/png")
}
