package one.njk.sao.importing

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import one.njk.sao.source.MediaDocuments
import java.io.File
import java.util.UUID

/** Durable transfer state, not a search/media cache. Unchanged completed files produce no writes. */
internal class ImportStore(context: Context): SQLiteOpenHelper(context,File(context.noBackupFilesDir,"media-imports.db").path,null,1) {
    val changes=MutableStateFlow(0L)
    data class Route(val id: String,val source: String,val identity: String,val target: String,val label: String,val tree: String,val name: String,val checked: Long,val error: String?)
    data class Task(val id: String,val source: String,val identity: String,val target: String,val uri: String,val name: String,
        val mime: String,val size: Long,val modified: Long,val fingerprint: String,val staged: String?,val checksum: String?,val attempts: Int,val automatic: Boolean,val network: Boolean)
    data class Stats(val done: Int=0,val pending: Int=0,val blocked: Int=0,val error: String?=null,val paused: Boolean=false)
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE routes(id TEXT PRIMARY KEY,source TEXT NOT NULL,identity TEXT NOT NULL,target TEXT NOT NULL,label TEXT NOT NULL,tree TEXT NOT NULL,name TEXT NOT NULL,checked INTEGER NOT NULL DEFAULT 0,error TEXT,scanning INTEGER NOT NULL DEFAULT 0,UNIQUE(source,identity,target,tree))")
        db.execSQL("CREATE TABLE directories(route TEXT NOT NULL REFERENCES routes(id) ON DELETE CASCADE,document TEXT NOT NULL,position INTEGER NOT NULL DEFAULT 0,done INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(route,document))")
        db.execSQL("CREATE TABLE tasks(id TEXT PRIMARY KEY,source TEXT NOT NULL,identity TEXT NOT NULL,target TEXT NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,size INTEGER NOT NULL,modified INTEGER NOT NULL,fingerprint TEXT NOT NULL,route TEXT,status TEXT NOT NULL DEFAULT 'pending',staged TEXT,checksum TEXT,receipt TEXT,attempts INTEGER NOT NULL DEFAULT 0,next INTEGER NOT NULL DEFAULT 0,error TEXT,finalized INTEGER NOT NULL DEFAULT 0,automatic INTEGER NOT NULL DEFAULT 0,network INTEGER NOT NULL DEFAULT 0,UNIQUE(source,identity,target,uri))")
        db.execSQL("CREATE INDEX task_queue ON tasks(status,next)")
        db.execSQL("CREATE TABLE controls(source TEXT PRIMARY KEY,paused INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase,old: Int,new: Int)=Unit
    private fun signal() { synchronized(changes) { changes.value++ } }
    private fun Cursor.text(name: String)=getString(getColumnIndexOrThrow(name))
    private fun Cursor.number(name: String)=getLong(getColumnIndexOrThrow(name))
    private fun route(c: Cursor)=Route(c.text("id"),c.text("source"),c.text("identity"),c.text("target"),c.text("label"),c.text("tree"),c.text("name"),c.number("checked"),c.text("error"))
    private fun task(c: Cursor)=Task(c.text("id"),c.text("source"),c.text("identity"),c.text("target"),c.text("uri"),c.text("name"),c.text("mime"),c.number("size"),c.number("modified"),c.text("fingerprint"),c.text("staged"),c.text("checksum"),c.number("attempts").toInt(),c.number("automatic")!=0L,c.number("network")!=0L)
    fun routes(source: String?=null): List<Route> = readableDatabase.rawQuery("SELECT * FROM routes"+if(source==null) "" else " WHERE source=?",source?.let { arrayOf(it) }).use { c -> buildList { while(c.moveToNext()) add(route(c)) } }
    fun connect(source: String,identity: String,target: String,label: String,tree: String,name: String) {
        writableDatabase.insertWithOnConflict("routes",null,ContentValues().apply {
            put("id",UUID.randomUUID().toString()); put("source",source); put("identity",identity); put("target",target); put("label",label); put("tree",tree); put("name",name)
        },SQLiteDatabase.CONFLICT_IGNORE); signal()
    }
    @Synchronized fun add(source: String,identity: String,target: String,entry: MediaDocuments.Entry,route: String?=null,staged: File?=null,checksum: String?=null,network: Boolean=false): Boolean {
        val db=writableDatabase
        val prior=db.rawQuery("SELECT id,fingerprint,staged FROM tasks WHERE source=? AND identity=? AND target=? AND uri=?",arrayOf(source,identity,target,entry.uri.toString())).use {
            if(it.moveToFirst()) Triple(it.getString(0),it.getString(1),it.getString(2)) else null
        }
        if(prior?.second==entry.fingerprint) { staged?.delete(); return false }
        if(prior!=null) { db.delete("tasks","id=?",arrayOf(prior.first)); prior.third?.let { File(it).delete() } }
        db.insertOrThrow("tasks",null,ContentValues().apply {
            put("id",UUID.randomUUID().toString()); put("source",source); put("identity",identity); put("target",target); put("uri",entry.uri.toString())
            put("name",entry.name); put("mime",entry.mime); put("size",entry.size); put("modified",entry.modified); put("fingerprint",entry.fingerprint)
            put("route",route); put("automatic",if(route==null) 0 else 1); put("staged",staged?.absolutePath); put("checksum",checksum)
            put("network",if(network) 1 else 0)
        }); return true
    }
    fun added()=signal()
    fun next(automatic: Boolean, network: Boolean=true, now: Long=System.currentTimeMillis()): Task? = readableDatabase.rawQuery("SELECT t.* FROM tasks t WHERE status IN ('pending','retry') AND next<=? AND network=? AND (automatic=0 OR CAST(? AS INTEGER)=1) AND NOT EXISTS (SELECT 1 FROM controls c WHERE c.source=t.source AND c.paused=1) ORDER BY automatic,rowid LIMIT 1",arrayOf(now.toString(),if(network) "1" else "0",if(automatic) "1" else "0")).use { if(it.moveToFirst()) task(it) else null }
    fun task(id: String): Task? = readableDatabase.rawQuery("SELECT * FROM tasks WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) task(it) else null }
    fun snapshot(id: String,file: File,checksum: String,size: Long) { writableDatabase.update("tasks",ContentValues().apply { put("staged",file.absolutePath); put("checksum",checksum) },"id=?",arrayOf(id)); signal() }
    fun done(id: String,receipt: String) { writableDatabase.update("tasks",ContentValues().apply { put("status","done"); put("receipt",receipt); putNull("error"); putNull("staged") },"id=?",arrayOf(id)); signal() }
    fun failed(task: Task,error: String,retryAt: Long?) { writableDatabase.update("tasks",ContentValues().apply { put("status",if(retryAt==null) "blocked" else "retry"); put("error",error); put("attempts",task.attempts+1); put("next",retryAt ?: 0) },"id=?",arrayOf(task.id)); signal() }
    fun pending(): Boolean=readableDatabase.rawQuery("SELECT 1 FROM tasks t WHERE status IN ('pending','retry') AND NOT EXISTS (SELECT 1 FROM controls c WHERE c.source=t.source AND c.paused=1) LIMIT 1",null).use { it.moveToFirst() }
    fun nextDelay(automatic: Boolean,network: Boolean=true): Long?=readableDatabase.rawQuery("SELECT MIN(next) FROM tasks t WHERE status IN ('pending','retry') AND network=? AND (automatic=0 OR CAST(? AS INTEGER)=1) AND NOT EXISTS (SELECT 1 FROM controls c WHERE c.source=t.source AND c.paused=1)",arrayOf(if(network) "1" else "0",if(automatic) "1" else "0")).use {
        if(it.moveToFirst() && !it.isNull(0)) (it.getLong(0)-System.currentTimeMillis()).coerceAtLeast(0) else null
    }
    fun unfinalized(): List<Pair<String,String>> = readableDatabase.rawQuery("SELECT DISTINCT source,identity FROM tasks WHERE status='done' AND finalized=0",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    fun finalized(source: String,identity: String) { writableDatabase.execSQL("UPDATE tasks SET finalized=1 WHERE source=? AND identity=? AND status='done'",arrayOf(source,identity)) }
    fun stats(source: String): Stats {
        var done=0; var pending=0; var blocked=0; var error: String?=null
        readableDatabase.rawQuery("SELECT status,COUNT(*),MAX(error) FROM tasks WHERE source=? GROUP BY status",arrayOf(source)).use { while(it.moveToNext()) {
            when(it.getString(0)) { "done" -> done+=it.getInt(1); "blocked" -> blocked+=it.getInt(1); else -> pending+=it.getInt(1) }
            if(!it.isNull(2)) error=it.getString(2)
        } }
        val paused=readableDatabase.rawQuery("SELECT paused FROM controls WHERE source=?",arrayOf(source)).use { it.moveToFirst() && it.getInt(0)!=0 }
        return Stats(done,pending,blocked,error,paused)
    }
    fun pause(source: String,paused: Boolean) { writableDatabase.insertWithOnConflict("controls",null,ContentValues().apply { put("source",source); put("paused",if(paused) 1 else 0) },SQLiteDatabase.CONFLICT_REPLACE); signal() }
    fun retry(source: String) { writableDatabase.execSQL("UPDATE tasks SET status='pending',attempts=0,next=0,error=NULL WHERE source=? AND status IN ('blocked','retry')",arrayOf(source)); signal() }
    fun directory(route: Route): Pair<String,Int>? {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO directories(route,document) VALUES (?,?)",arrayOf(route.id,android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(route.tree))))
            db.execSQL("UPDATE routes SET scanning=1 WHERE id=?",arrayOf(route.id)); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return db.rawQuery("SELECT document,position FROM directories WHERE route=? AND done=0 ORDER BY rowid LIMIT 1",arrayOf(route.id)).use { if(it.moveToFirst()) it.getString(0) to it.getInt(1) else null }
    }
    fun child(route: String,document: String) { writableDatabase.execSQL("INSERT OR IGNORE INTO directories(route,document) VALUES (?,?)",arrayOf(route,document)) }
    fun checkpoint(route: String,document: String,position: Int) { writableDatabase.execSQL("UPDATE directories SET position=? WHERE route=? AND document=?",arrayOf<Any>(position,route,document)) }
    fun directoryDone(route: String,document: String) { writableDatabase.execSQL("UPDATE directories SET done=1 WHERE route=? AND document=?",arrayOf(route,document)) }
    fun checked(route: String,error: String?=null) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("directories","route=?",arrayOf(route))
            writableDatabase.update("routes",ContentValues().apply { put("checked",System.currentTimeMillis()); put("scanning",0); put("error",error) },"id=?",arrayOf(route))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }; signal()
    }
    fun remove(route: String) {
        writableDatabase.rawQuery("SELECT staged FROM tasks WHERE route=? AND status<>'done'",arrayOf(route)).use { while(it.moveToNext()) it.getString(0)?.let { path -> File(path).delete() } }
        writableDatabase.delete("tasks","route=? AND status<>'done'",arrayOf(route))
        writableDatabase.delete("routes","id=?",arrayOf(route)); signal()
    }
    fun rescan(source: String) { writableDatabase.execSQL("UPDATE routes SET checked=0,error=NULL WHERE source=?",arrayOf(source)); signal() }
    fun lastTarget(source: String): String?=readableDatabase.rawQuery("SELECT target FROM tasks WHERE source=? ORDER BY rowid DESC LIMIT 1",arrayOf(source)).use { if(it.moveToFirst()) it.getString(0) else null }
    fun clear(source: String,active: String?): List<String> {
        val where="source=? AND status<>'done'"+if(active==null) "" else " AND id<>?"
        val args=if(active==null) arrayOf(source) else arrayOf(source,active)
        val uris=mutableListOf<String>()
        writableDatabase.query("tasks",arrayOf("staged","uri"),where,args,null,null,null).use { while(it.moveToNext()) {
            it.getString(0)?.let { path -> File(path).delete() }; uris+=it.getString(1)
        } }
        writableDatabase.delete("tasks",where,args); signal(); return uris
    }
    fun needsUri(uri: String)=readableDatabase.rawQuery("SELECT 1 FROM tasks WHERE uri=? AND status<>'done' LIMIT 1",arrayOf(uri)).use { it.moveToFirst() }
    fun stagedPaths(): Set<String> = readableDatabase.rawQuery("SELECT staged FROM tasks WHERE staged IS NOT NULL",null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    fun unfinished(source: String,route: String?=null): List<Task> = readableDatabase.rawQuery("SELECT * FROM tasks WHERE source=? AND status<>'done'"+if(route==null) "" else " AND route=?",if(route==null) arrayOf(source) else arrayOf(source,route)).use { c -> buildList { while(c.moveToNext()) add(task(c)) } }
    fun sources(): Set<String> = readableDatabase.rawQuery("SELECT source FROM tasks UNION SELECT source FROM routes UNION SELECT source FROM controls",null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    fun forget(source: String): List<String> {
        val uris=mutableListOf<String>()
        writableDatabase.rawQuery("SELECT uri,staged FROM tasks WHERE source=?",arrayOf(source)).use { c -> while(c.moveToNext()) {
            uris+=c.getString(0); c.getString(1)?.let { File(it).delete() }
        } }
        writableDatabase.delete("routes","source=?",arrayOf(source)); writableDatabase.delete("tasks","source=?",arrayOf(source))
        writableDatabase.delete("controls","source=?",arrayOf(source)); signal(); return uris
    }
}
