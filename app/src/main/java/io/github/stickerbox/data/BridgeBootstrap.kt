package io.github.stickerbox.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/** A non-private marker read grant makes this app visible to Gboard on Android 11+.
 * It exposes no library, preferences, inputs or user data. BridgeProvider still checks caller UID.
 */
object BridgeBootstrap {
    fun markerUri(context:Context):android.net.Uri {
        val marker=File(context.filesDir,"bridge-bootstrap/marker.txt")
        marker.parentFile!!.mkdirs()
        if(!marker.exists())marker.writeText("Gboard StickerBox bridge\n")
        return FileProvider.getUriForFile(context,"io.github.stickerbox.files",marker)
    }
    fun allowGboard(context:Context) {
        context.grantUriPermission(BridgeProvider.GBOARD,markerUri(context),Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

class BootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED)return
        val pending=goAsync()
        executor.execute {
            try{BridgeBootstrap.allowGboard(context.applicationContext)}catch(_:Exception){/* App open retries. */}
            finally{pending.finish()}
        }
    }
    companion object {private val executor=Executors.newSingleThreadExecutor()}
}
