package io.github.stickerbox.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.github.stickerbox.core.MediaRules
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Importer(private val context:Context) {
    private val db=LibraryDb.get(context)
    private var entries=0
    private var bytes=0L
    data class Result(var added:Int=0,var skipped:Int=0,var failed:Int=0) {
        override fun toString()="新增 $added · 跳过 $skipped · 失败 $failed"
    }
    private val result=Result()
    private fun account(n:Long) { bytes+=n;require(bytes<=MediaRules.MAX_ARCHIVE){"Import exceeds 512 MiB"} }
    private fun next() { entries++;require(entries<=MediaRules.MAX_ENTRIES){"Too many entries"} }
    private fun name(uri:Uri):String = context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "sticker"

    fun documents(uris:List<Uri>):Result {
        for(uri in uris) {
            try {
                val display=name(uri)
                if(display.endsWith(".zip",true) || context.contentResolver.getType(uri) in setOf("application/zip","application/x-zip-compressed")) {
                    context.contentResolver.openInputStream(uri)!!.use{archive(it)}
                } else { next();context.contentResolver.openInputStream(uri)!!.use{single(it,display,"导入")} }
            } catch(_:Exception) {result.failed++;db.event("IMPORT_FILE_FAILED")}
        }
        return result
    }
    fun folder(uri:Uri):Result {
        val root=DocumentFile.fromTreeUri(context,uri) ?: error("Invalid tree")
        fun walk(doc:DocumentFile,group:String,depth:Int) {
            require(depth<=20){"Directory nesting limit"}
            for(child in doc.listFiles()) {
                next()
                if(child.isDirectory) walk(child,child.name ?: group,depth+1)
                else try {context.contentResolver.openInputStream(child.uri)!!.use {single(it,child.name ?: "sticker",group)}}
                catch(_:Exception){result.failed++;db.event("IMPORT_FILE_FAILED")}
            }
        }
        walk(root,root.name ?: "导入",0)
        return result
    }
    private fun archive(input:InputStream) {
        var manifest:String?=null
        ZipInputStream(input.buffered()).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                if(entry.name!="library.json")next()
                // Never extract by archive path; names are labels only. Read all bytes under budgets.
                require(MediaRules.safeEntry(entry.name)){"Unsafe ZIP entry"}
                if(entry.isDirectory){zip.closeEntry();continue}
                if(entry.name=="library.json") {
                    require(manifest==null){"Duplicate backup manifest"}
                    val out=java.io.ByteArrayOutputStream()
                    account(MediaRules.copyBounded(zip,out,16L*1024*1024))
                    manifest=out.toString("UTF-8")
                } else {
                    val normal=entry.name.replace('\\','/')
                    single(zip,normal.substringAfterLast('/'),normal.substringBeforeLast('/',"导入").substringAfterLast('/'))
                }
                zip.closeEntry()
            }
        }
        manifest?.let { restoreMetadata(it) }
    }
    private fun single(input:InputStream,originalName:String,category:String) {
        val stage=File(context.cacheDir,"import-${UUID.randomUUID()}")
        try {
            stage.outputStream().use { account(MediaRules.copyBounded(input,it,MediaRules.MAX_FILE)) }
            val head=stage.inputStream().use {val buffer=ByteArray(64);val n=it.read(buffer);buffer.copyOf(n.coerceAtLeast(0))}
            var kind=MediaRules.detect(head)
            if(kind==null){result.skipped++;return}
            if(kind==MediaRules.Kind.HEIF) {
                val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(stage)) {decoder,info,_ ->
                    require(info.size.width.toLong()*info.size.height<=100_000_000L)
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale=(2048.0/maxOf(info.size.width,info.size.height)).coerceAtMost(1.0)
                    decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
                }
                try {stage.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
                finally {bitmap.recycle()}
                require(stage.length()<=MediaRules.MAX_FILE)
                kind=MediaRules.Kind.PNG
            } else {
                // Reject truncated/corrupt files and absurd dimensions before indexing.
                val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeFile(stage.path,bounds)
                require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=100_000_000L)
            }
            val digest=MessageDigest.getInstance("SHA-256")
            stage.inputStream().use {stream -> val buffer=ByteArray(32768);while(true){val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            val id=digest.digest().joinToString(""){"%02x".format(it)}
            if(db.get(id)!=null){result.skipped++;return}
            require(db.count()<MediaRules.MAX_ENTRIES){"Library limit reached"}
            val external=context.getSharedPreferences("settings",0).getBoolean("external",false)
            val row=Sticker(id,originalName.substringBeforeLast('.',originalName).take(200),category.take(200),kind.mime,kind.extension,
                if(external)"external" else "internal",false,0,System.currentTimeMillis())
            val dest=LibraryDb.file(context,row)
            dest.parentFile!!.mkdirs()
            val partial=File(dest.parentFile,"${id}.tmp")
            try {
                stage.inputStream().use {i->partial.outputStream().use {o->i.copyTo(o)}}
                check(partial.renameTo(dest)){"Atomic move failed"}
                if(db.add(row)) result.added++ else result.skipped++
            } finally {partial.delete()}
        } finally {stage.delete()}
    }
    private fun restoreMetadata(text:String) {
        val root=JSONObject(text)
        require(root.getInt("schema")==1){"Unsupported backup schema"}
        val items=root.getJSONArray("stickers")
        require(items.length()<=MediaRules.MAX_ENTRIES)
        db.writableDatabase.beginTransaction()
        try {
            for(i in 0 until items.length()) {
                val item=items.getJSONObject(i)
                val id=item.getString("id")
                val existing=db.get(id) ?: continue
                db.edit(id,item.optString("name",existing.name),item.optString("group",existing.group),item.optLong("rank",existing.rank),item.optBoolean("favorite",false))
                db.restoreRecent(id,item.optLong("recent",0))
            }
            db.writableDatabase.setTransactionSuccessful()
        } finally {db.writableDatabase.endTransaction()}
    }
    fun backup(uri:Uri) {
        val items=db.list(limit=10000)
        context.contentResolver.openOutputStream(uri,"wt")!!.use {out -> ZipOutputStream(out.buffered()).use {zip ->
            zip.putNextEntry(ZipEntry("library.json"));zip.write(db.backupJson().toByteArray());zip.closeEntry()
            for(s in items) {
                zip.putNextEntry(ZipEntry("images/${s.id}.${s.extension}"))
                LibraryDb.file(context,s).inputStream().use{it.copyTo(zip)}
                zip.closeEntry()
            }
        }}
    }
}
