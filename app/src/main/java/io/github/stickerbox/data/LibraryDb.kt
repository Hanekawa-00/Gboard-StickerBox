package io.github.stickerbox.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Sticker(val id: String, val name: String, val group: String, val mime: String,
    val extension: String, val location: String, val favorite: Boolean, val recent: Long, val rank: Long) {
    fun json() = JSONObject().put("id", id).put("name", name).put("group", group)
        .put("mime", mime).put("ext", extension).put("favorite", favorite)
        .put("recent", recent).put("rank", rank)
}

class LibraryDb private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE stickers(id TEXT PRIMARY KEY,name TEXT NOT NULL,category TEXT NOT NULL,mime TEXT NOT NULL,ext TEXT NOT NULL,location TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,recent INTEGER NOT NULL DEFAULT 0,rank INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX category_order ON stickers(category,rank)")
        db.execSQL("CREATE INDEX recent_order ON stickers(recent DESC)")
        db.execSQL("CREATE TABLE events(seq INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,code TEXT NOT NULL)")
        db.execSQL("CREATE TABLE state(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun Cursor.sticker() = Sticker(getString(0),getString(1),getString(2),getString(3),getString(4),getString(5),getInt(6)!=0,getLong(7),getLong(8))
    fun list(search: String = "", filter: String = "all", category: String = "", offset: Int = 0, limit: Int = 120): List<Sticker> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (search.isNotBlank()) {
            clauses += "(name LIKE ? ESCAPE '\\' OR category LIKE ? ESCAPE '\\')"
            val escaped = search.take(200).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            args += "%$escaped%"; args += "%$escaped%"
        }
        if (filter == "favorites") clauses += "favorite=1"
        if (filter == "recent") clauses += "recent>0"
        if (category.isNotEmpty()) { clauses += "category=?"; args += category }
        val order = if (filter == "recent") "recent DESC,id" else "rank ASC,id"
        return readableDatabase.query("stickers", null, clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.toTypedArray(), null, null, order, "${offset.coerceAtLeast(0)},${limit.coerceIn(1,10000)}").use { c ->
            buildList { while(c.moveToNext()) add(c.sticker()) }
        }
    }
    fun get(id: String): Sticker? = readableDatabase.query("stickers", null, "id=?", arrayOf(id), null,null,null).use { if(it.moveToFirst()) it.sticker() else null }
    fun add(s: Sticker): Boolean {
        val v = ContentValues().apply {
            put("id",s.id);put("name",s.name.take(200));put("category",s.group.take(200));put("mime",s.mime)
            put("ext",s.extension);put("location",s.location);put("favorite",if(s.favorite)1 else 0)
            put("recent",s.recent);put("rank",s.rank)
        }
        return writableDatabase.insertWithOnConflict("stickers",null,v,SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun edit(id:String,name:String,category:String,rank:Long,favorite:Boolean) {
        writableDatabase.update("stickers", ContentValues().apply {
            put("name", name.take(200));put("category",category.take(200));put("rank",rank)
            put("favorite",if(favorite)1 else 0)
        },"id=?",arrayOf(id))
    }
    fun recent(id:String) { writableDatabase.update("stickers",ContentValues().apply { put("recent",System.currentTimeMillis()) },"id=?",arrayOf(id)) }
    fun remove(id:String) {writableDatabase.delete("stickers","id=?",arrayOf(id))}
    fun categories(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT category FROM stickers ORDER BY category",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun count() = readableDatabase.rawQuery("SELECT COUNT(*) FROM stickers",null).use { it.moveToFirst();it.getInt(0) }
    fun event(code:String) {
        // Only fixed operational codes: never exception messages, editor text, names or clipboard data.
        require(Regex("[A-Z0-9_]{1,64}").matches(code))
        writableDatabase.insert("events",null,ContentValues().apply { put("time",System.currentTimeMillis());put("code",code) })
        writableDatabase.execSQL("DELETE FROM events WHERE seq NOT IN (SELECT seq FROM events ORDER BY seq DESC LIMIT 100)")
    }
    fun events():String = readableDatabase.rawQuery("SELECT time,code FROM events ORDER BY seq DESC",null).use { c -> buildString { while(c.moveToNext()) append("${java.util.Date(c.getLong(0))}  ${c.getString(1)}\n") } }
    fun state(key:String,value:String) { writableDatabase.insertWithOnConflict("state",null,ContentValues().apply{put("key",key);put("value",value.take(500))},SQLiteDatabase.CONFLICT_REPLACE) }
    fun state(key:String):String = readableDatabase.query("state",arrayOf("value"),"key=?",arrayOf(key),null,null,null).use { if(it.moveToFirst()) it.getString(0) else "尚未收到 Hook 心跳" }
    fun backupJson():String = JSONObject().put("schema",1).put("stickers",JSONArray().apply { list(limit=10000).forEach{put(it.json())} }).toString()
    companion object {
        @Volatile private var instance:LibraryDb? = null
        fun get(c:Context):LibraryDb = instance ?: synchronized(this) { instance ?: LibraryDb(c).also {instance=it} }
        fun file(c:Context,s:Sticker):File {
            require(Regex("[a-f0-9]{64}").matches(s.id))
            require(s.extension in setOf("png","jpg","gif","webp"))
            val root=if(s.location=="external") c.getExternalFilesDir(null) ?: error("External storage unavailable") else c.filesDir
            return File(root,"stickers/${s.id}.${s.extension}")
        }
    }
}
