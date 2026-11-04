package io.github.stickerbox.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.stickerbox.data.BridgeProvider
import io.github.stickerbox.data.LibraryDb
import java.util.concurrent.Executors

/** Private activity. Gboard can open it only via a module-created immutable PendingIntent. */
class ShareActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val id=intent.getStringExtra("id")
        if(id==null){finish();return}
        worker.execute {
            try {
                val db=LibraryDb.get(this)
                val row=db.get(id) ?: error("Missing sticker")
                val (uri,mime)=BridgeProvider.prepare(this,row)
                runOnUiThread {
                    try {
                        val share=Intent(Intent.ACTION_SEND).apply {
                            type=mime;putExtra(Intent.EXTRA_STREAM,uri)
                            clipData=ClipData.newRawUri("StickerBox",uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(share,"分享贴纸（由目标应用确认发送）"))
                        worker.execute{db.recent(id);db.event("SHARE_OPENED")}
                    } catch(_:Exception){Toast.makeText(this,"没有可用的分享应用",Toast.LENGTH_LONG).show()}
                    finally {finish()}
                }
            } catch(_:Exception){runOnUiThread{Toast.makeText(this,"无法读取贴纸",Toast.LENGTH_LONG).show();finish()}}
        }
    }
    companion object {private val worker=Executors.newSingleThreadExecutor()}
}
