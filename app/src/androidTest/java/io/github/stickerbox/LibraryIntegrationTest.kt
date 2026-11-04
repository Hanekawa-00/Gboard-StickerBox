package io.github.stickerbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.stickerbox.data.BridgeProvider
import io.github.stickerbox.data.Importer
import io.github.stickerbox.data.LibraryDb
import io.github.stickerbox.ui.MainActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run only on a disposable emulator: setUp intentionally clears its test library. */
@RunWith(AndroidJUnit4::class)
class LibraryIntegrationTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val db get()=LibraryDb.get(context)
    @Before fun clearTestLibrary() {
        db.list(limit=10000).forEach{db.remove(it.id);LibraryDb.file(context,it).delete()}
    }
    private fun uri(name:String,bytes:ByteArray):Uri {
        val dir=File(context.cacheDir,"outgoing").apply{mkdirs()}
        val f=File(dir,name).apply{writeBytes(bytes)}
        return FileProvider.getUriForFile(context,"io.github.stickerbox.files",f)
    }
    private fun png():ByteArray {
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GREEN)}
        return java.io.ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle()}.toByteArray()
    }
    @Test fun importDeduplicateSearchAndBackupRoundTrip() {
        val original=png()
        val input=uri("wrong-extension.bin",original)
        assertEquals(1,Importer(context).documents(listOf(input)).added)
        assertEquals(1,Importer(context).documents(listOf(input)).skipped)
        val first=db.list().single()
        assertEquals("image/png",first.mime)
        assertArrayEquals(original,LibraryDb.file(context,first).readBytes())
        db.edit(first.id,"happy 猫","pets",7,true)
        db.recent(first.id)
        val recent=db.get(first.id)!!.recent
        assertEquals(1,db.list(search="猫",filter="favorites",category="pets").size)
        assertTrue(db.list(search="% OR 1=1").isEmpty())
        val backup=uri("roundtrip.zip",byteArrayOf())
        Importer(context).backup(backup)
        db.remove(first.id);assertTrue(LibraryDb.file(context,first).delete())
        assertEquals(1,Importer(context).documents(listOf(backup)).added)
        val restored=db.list().single()
        assertEquals("happy 猫",restored.name);assertEquals("pets",restored.group)
        assertEquals(7L,restored.rank);assertTrue(restored.favorite);assertEquals(recent,restored.recent)
        assertArrayEquals(original,LibraryDb.file(context,restored).readBytes())
    }
    @Test fun preservesGifAnimationAndOpensPreparedExport() {
        // A 1x1 two-frame GIF with a loop extension, constructed independently for this test.
        val hex="47494638396101000100800000000000ffffff21ff0b4e45545343415045322e300301000000"+
            "21f904000a0000002c0000000001000100000202440100"+
            "21f904000a0000002c00000000010001000002024c01003b"
        val bytes=hex.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
        assertEquals(1,Importer(context).documents(listOf(uri("opaque-cache",bytes))).added)
        val row=db.list().single()
        assertEquals("image/gif",row.mime)
        assertArrayEquals(bytes,LibraryDb.file(context,row).readBytes())
        val drawable=ImageDecoder.decodeDrawable(ImageDecoder.createSource(LibraryDb.file(context,row)))
        assertTrue(drawable is AnimatedImageDrawable)
        val prepared=BridgeProvider.prepare(context,row)
        assertEquals("image/gif",prepared.second)
        context.contentResolver.openInputStream(prepared.first)!!.use{assertArrayEquals(bytes,it.readBytes())}
        val config=context.contentResolver.call(BridgeProvider.BASE,"config",null,null)
        assertNotNull(config)
        val info=context.packageManager.resolveContentProvider("io.github.stickerbox.files",0)!!
        assertFalse(info.exported);assertTrue(info.grantUriPermissions)
    }
    @Test fun managerActivityStarts() {
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->assertFalse(activity.isFinishing);assertNotNull(activity.window.decorView)}
        }
    }
}
