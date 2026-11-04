package io.github.stickerbox.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MediaRulesTest {
    @Test fun recognizesOriginalFormatsRegardlessOfExtension() {
        assertEquals(MediaRules.Kind.GIF,MediaRules.detect("GIF89a".toByteArray()))
        assertEquals(MediaRules.Kind.GIF,MediaRules.detect("GIF87a".toByteArray()))
        assertEquals(MediaRules.Kind.PNG,MediaRules.detect(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)))
        assertEquals(MediaRules.Kind.JPEG,MediaRules.detect(byteArrayOf(255.toByte(),216.toByte(),255.toByte())))
        assertEquals(MediaRules.Kind.WEBP,MediaRules.detect("RIFF0000WEBPVP8X".toByteArray()))
        assertEquals(MediaRules.Kind.HEIF,MediaRules.detect(byteArrayOf(0,0,0,24)+"ftypheic".toByteArray()))
        assertEquals(MediaRules.Kind.HEIF,MediaRules.detect(byteArrayOf(0,0,0,24)+"ftypmif1".toByteArray()))
    }
    @Test fun rejectsTruncatedAndUnknownHeaders() {
        for(n in 0..2)assertNull(MediaRules.detect(ByteArray(n)))
        assertNull(MediaRules.detect("RIFF0000WAVE".toByteArray()))
        assertNull(MediaRules.detect("<html>fake.gif</html>".toByteArray()))
        assertNull(MediaRules.detect(byteArrayOf(137.toByte(),80,78,71)))
    }
    @Test fun blocksZipTraversalAndAbsolutePaths() {
        for(p in listOf("../a.gif","x/../../b","/etc/file","C:\\a.gif","x\\..\\a.gif","x\u0000.gif",""))
            assertFalse(p,MediaRules.safeEntry(p))
        for(p in listOf("可爱/笑.gif","cache/ab23","images/a.webp"))assertTrue(MediaRules.safeEntry(p))
    }
    @Test fun boundedCopyCountsActualDecompressedBytes() {
        val bytes=ByteArray(32769){42}
        val out=ByteArrayOutputStream()
        assertEquals(32769L,MediaRules.copyBounded(ByteArrayInputStream(bytes),out,32769))
        assertArrayEquals(bytes,out.toByteArray())
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsExpansionOverBudget() {
        MediaRules.copyBounded(ByteArrayInputStream(ByteArray(1025)),ByteArrayOutputStream(),1024)
    }
    @Test fun matchesEditorMimeTypes() {
        assertTrue(MediaRules.accepts(arrayOf("image/*"),"image/gif"))
        assertTrue(MediaRules.accepts(arrayOf("*/*"),"image/webp"))
        assertTrue(MediaRules.accepts(arrayOf("image/png","image/gif"),"image/gif"))
        assertFalse(MediaRules.accepts(emptyArray(),"image/gif"))
        assertFalse(MediaRules.accepts(arrayOf("image/png"),"image/gif"))
        assertFalse(MediaRules.accepts(arrayOf("text/*"),"image/png"))
    }
    @Test fun neverSilentlyConvertsGifToStillImage() {
        assertFalse(MediaRules.shouldConvertToPng("image/gif",arrayOf("image/png")))
        assertTrue(MediaRules.shouldConvertToPng("image/webp",arrayOf("image/png")))
        assertFalse(MediaRules.shouldConvertToPng("image/webp",arrayOf("image/*")))
    }
}
