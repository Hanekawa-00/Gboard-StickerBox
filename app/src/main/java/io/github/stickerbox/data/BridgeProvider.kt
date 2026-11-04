package io.github.stickerbox.data

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.content.FileProvider
import io.github.stickerbox.ui.ShareActivity
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Exported control bridge, with explicit caller-UID checks on EVERY entry point.
 * Files are shared exclusively by the non-exported FileProvider with temporary read grants.
 */
class BridgeProvider : ContentProvider() {
    private val ctx get()=requireNotNull(context)
    private val db get()=LibraryDb.get(ctx)
    override fun onCreate()=true
    private fun authorize() {
        val uid=Binder.getCallingUid()
        if(uid==Process.myUid())return
        val packages=ctx.packageManager.getPackagesForUid(uid) ?: emptyArray()
        if(GBOARD !in packages)throw SecurityException("Caller not allowed")
    }
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        authorize()
        return when(method) {
            "config" -> Bundle().apply {
                val prefs=ctx.getSharedPreferences("settings",0)
                putBoolean("enabled",prefs.getBoolean("enabled",false));putLong("revision",prefs.getLong("revision",0))
            }
            "list" -> Bundle().apply {
                putString("items",JSONArray().apply {
                    db.list(search=extras?.getString("search").orEmpty(),filter=extras?.getString("filter") ?: "all",
                        category=extras?.getString("category").orEmpty(),offset=extras?.getInt("offset",0) ?: 0).forEach {put(it.json())}
                }.toString())
                putStringArrayList("categories",ArrayList(db.categories()))
            }
            "heartbeat" -> Bundle().also {db.state("hook",arg?.take(500) ?: "loaded");db.state("heartbeat",System.currentTimeMillis().toString())}
            "event" -> Bundle().also {if(arg in EVENT_CODES)db.event(arg!!)}
            "recent" -> Bundle().also {arg?.let{db.recent(it)}}
            "favorite" -> Bundle().also {arg?.let{db.get(it)}?.let{db.edit(it.id,it.name,it.group,it.rank,!it.favorite)}}
            "prepare" -> {
                val row=db.get(arg ?: "") ?: error("Sticker not found")
                val target=extras?.getString("target").orEmpty()
                require(target.length<=200 && (target.isEmpty() || Regex("[A-Za-z0-9_.]+").matches(target)))
                // Clear Binder identity so grants and PendingIntents belong to the module UID.
                val identity=Binder.clearCallingIdentity()
                try {
                    val prepared=prepare(ctx,row,extras?.getBoolean("png",false) ?: false)
                    ctx.grantUriPermission(GBOARD,prepared.first,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if(target.isNotEmpty())ctx.grantUriPermission(target,prepared.first,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val shareIntent=Intent(ctx,ShareActivity::class.java).putExtra("id",row.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val pending=PendingIntent.getActivity(ctx,row.id.hashCode(),shareIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    Bundle().apply {putString("uri",prepared.first.toString());putString("mime",prepared.second);putParcelable("share",pending)}
                } finally {Binder.restoreCallingIdentity(identity)}
            }
            else -> throw IllegalArgumentException("Unsupported operation")
        }
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        authorize();require(mode=="r" && uri.pathSegments.size==2 && uri.pathSegments[0]=="media")
        val row=db.get(uri.lastPathSegment ?: "") ?: throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(LibraryDb.file(ctx,row),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri:Uri):String? {authorize();return db.get(uri.lastPathSegment ?: "")?.mime}
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor? {authorize();return null}
    override fun insert(uri:Uri,values:ContentValues?):Uri? {authorize();throw UnsupportedOperationException()}
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int {authorize();throw UnsupportedOperationException()}
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int {authorize();throw UnsupportedOperationException()}

    companion object {
        const val GBOARD="com.google.android.inputmethod.latin"
        val BASE:Uri=Uri.parse("content://io.github.stickerbox.bridge")
        val EVENT_CODES=setOf("COMMIT_ACCEPTED","COMMIT_REJECTED","MIME_UNSUPPORTED","COMMIT_FAILED","CLIPBOARD_SET","SHARE_OPENED","HOOK_FAILED","PREPARE_FAILED")
        private val handler=Handler(Looper.getMainLooper())
        @Synchronized fun prepare(context:Context,row:Sticker,png:Boolean=false):Pair<Uri,String> {
            val root=File(context.cacheDir,"outgoing").apply{mkdirs()}
            val now=System.currentTimeMillis()
            root.listFiles()?.filter{now-it.lastModified()>10*60*1000}?.forEach {old ->
                val u=FileProvider.getUriForFile(context,"io.github.stickerbox.files",old)
                context.revokeUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);old.delete()
            }
            require(!png || row.mime!="image/gif"){"Never silently flatten GIF"}
            val dest=File(root,"${UUID.randomUUID()}.${if(png)"png" else row.extension}")
            if(png) {
                val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(LibraryDb.file(context,row))) {decoder,info,_ ->
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale=(2048.0/maxOf(info.size.width,info.size.height)).coerceAtMost(1.0)
                    decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
                }
                try {dest.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
                finally {bitmap.recycle()}
            } else LibraryDb.file(context,row).inputStream().use{i->dest.outputStream().use{i.copyTo(it)}}
            val uri=FileProvider.getUriForFile(context,"io.github.stickerbox.files",dest)
            // Revocation bounds explicit grants while this process lives; next prepare also sweeps
            // stale exports after process death. See TESTING.md for the remaining lifetime limitation.
            handler.postDelayed({context.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);dest.delete()},10*60*1000L)
            return uri to if(png)"image/png" else row.mime
        }
    }
}
