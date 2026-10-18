package one.njk.sao.extension.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import one.njk.sao.source.ExtensionFailure
import one.njk.sao.source.MediaQuery
import one.njk.sao.source.MediaSearch
import one.njk.sao.source.SearchMedia
import one.njk.sao.source.SearchMode
import one.njk.sao.source.digest
import java.util.UUID

/** Extension-private durable metadata. Callers perform all database work on IO. Originals stay in SAF. */
class LocalStore private constructor(private val context: android.app.Application) : SQLiteOpenHelper(context, "local-library.db", null, 1) {
    val changes = MutableStateFlow(0L)
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE profiles(id TEXT PRIMARY KEY, name TEXT NOT NULL, revision TEXT NOT NULL, paused INTEGER NOT NULL DEFAULT 0, last_scan INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE folders(profile TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE, uri TEXT NOT NULL, name TEXT NOT NULL, error TEXT, PRIMARY KEY(profile,uri))")
        db.execSQL("CREATE TABLE assets(id INTEGER PRIMARY KEY AUTOINCREMENT, profile TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE, root TEXT NOT NULL, uri TEXT NOT NULL, filename TEXT NOT NULL, filename_search TEXT NOT NULL, path TEXT NOT NULL, path_search TEXT NOT NULL, mime TEXT NOT NULL, modified INTEGER NOT NULL, size INTEGER NOT NULL, signature TEXT NOT NULL, animated INTEGER NOT NULL DEFAULT 0, labels TEXT NOT NULL DEFAULT '', ocr TEXT NOT NULL DEFAULT '', ocr_override TEXT, ocr_search TEXT NOT NULL DEFAULT '', title TEXT, description TEXT, tags TEXT, searchable TEXT NOT NULL DEFAULT '', pending INTEGER NOT NULL DEFAULT 1, failure TEXT, seen TEXT NOT NULL, UNIQUE(profile,uri))")
        db.execSQL("CREATE INDEX asset_order ON assets(profile,modified DESC,id DESC)")
        db.execSQL("CREATE INDEX asset_pending ON assets(profile,pending,id)")
        db.execSQL("CREATE VIRTUAL TABLE metadata USING fts4(description,ocr,tokenize=unicode61)")
        db.execSQL("CREATE TRIGGER asset_delete AFTER DELETE ON assets BEGIN DELETE FROM metadata WHERE rowid=old.id; END")
        db.execSQL("CREATE TABLE scans(profile TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE, revision TEXT NOT NULL, generation TEXT NOT NULL)")
        db.execSQL("CREATE TABLE directories(profile TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE, root TEXT NOT NULL, document TEXT NOT NULL, path TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(profile,root,document))")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    private var lastSignal = 0L
    @Synchronized private fun changed(profile: String?=null, force: Boolean=true) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(!force && now-lastSignal<500) return
        lastSignal=now; changes.value++
        if(profile!=null) rows("SELECT revision FROM profiles WHERE id=?",listOf(profile)).use { c ->
            if(c.moveToFirst()) context.contentResolver.notifyChange(changesUri(context,profile,c.getString(0)),null)
        }
    }
    fun signal(profile: String) = changed(profile)
    fun changesUri(profile: Library) = changesUri(context,profile.id,profile.revision)
    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db=writableDatabase; db.beginTransaction()
        try { val result=block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    private fun rows(sql: String, args: List<String> = emptyList()) = readableDatabase.rawQuery(sql,args.toTypedArray())
    fun libraries(): List<Library> = rows("SELECT * FROM profiles ORDER BY rowid").use { c -> buildList {
        while(c.moveToNext()) add(library(c))
    } }
    private fun library(c: Cursor): Library {
        val id=c.text("id")
        val folders=rows("SELECT uri,name FROM folders WHERE profile=? ORDER BY rowid",listOf(id)).use { f -> buildList {
            while(f.moveToNext()) add(Folder(f.text("uri"),f.text("name")))
        } }
        return Library(id,c.text("name"),c.text("revision"),folders,c.number("paused")!=0L,c.number("last_scan"))
    }
    fun library(id: String) = rows("SELECT * FROM profiles WHERE id=?",listOf(id)).use { c ->
        if(!c.moveToFirst()) throw ExtensionFailure("PROFILE_MISSING") else library(c)
    }
    fun active(id: String, revision: String) = rows("SELECT paused,revision FROM profiles WHERE id=?",listOf(id)).use {
        it.moveToFirst() && it.getInt(0)==0 && it.getString(1)==revision
    }
    fun save(id: String, name: String, folders: List<Folder>): Library {
        UUID.fromString(id); require(name.isNotBlank() && folders.isNotEmpty())
        require(folders.distinctBy { it.uri }.size==folders.size)
        transaction { db ->
            val prior=libraries().firstOrNull { it.id==id }
            val same=prior?.folders?.map { it.uri }?.toSet()==folders.map { it.uri }.toSet()
            val revision=prior?.revision?.takeIf { same } ?: UUID.randomUUID().toString()
            if(prior==null) db.insertOrThrow("profiles",null,ContentValues().apply { put("id",id); put("name",name.trim()); put("revision",revision) })
            else db.update("profiles",ContentValues().apply { put("name",name.trim()); put("revision",revision) },"id=?",arrayOf(id))
            val removed=prior?.folders.orEmpty().filter { old -> folders.none { it.uri==old.uri } }
            removed.forEach { db.delete("assets","profile=? AND root=?",arrayOf(id,it.uri)); db.delete("folders","profile=? AND uri=?",arrayOf(id,it.uri)) }
            folders.forEach { f ->
                db.insertWithOnConflict("folders",null,ContentValues().apply { put("profile",id); put("uri",f.uri); put("name",f.name) },SQLiteDatabase.CONFLICT_IGNORE)
                db.update("folders",ContentValues().apply { put("name",f.name) },"profile=? AND uri=?",arrayOf(id,f.uri))
            }
            if(!same) { db.delete("scans","profile=?",arrayOf(id)); db.delete("directories","profile=?",arrayOf(id)); db.update("profiles",ContentValues().apply { put("last_scan",0) },"id=?",arrayOf(id)) }
        }
        changed(id); return library(id)
    }
    fun delete(id: String) { writableDatabase.delete("profiles","id=?",arrayOf(id)); changed() }
    fun pause(id: String, paused: Boolean) { writableDatabase.update("profiles",ContentValues().apply { put("paused",if(paused) 1 else 0) },"id=?",arrayOf(id)); changed(id) }
    fun status(id: String): IndexStatus = rows("SELECT count(*),coalesce(sum(pending),0),sum(CASE WHEN failure IS NOT NULL THEN 1 ELSE 0 END) FROM assets WHERE profile=?",listOf(id)).use {
        it.moveToFirst(); IndexStatus(it.getInt(0),it.getInt(1),it.getInt(2),rows("SELECT 1 FROM scans WHERE profile=?",listOf(id)).use { s -> s.moveToFirst() },
            rows("SELECT count(*) FROM folders WHERE profile=? AND error IS NOT NULL",listOf(id)).use { e -> e.moveToFirst(); e.getInt(0) })
    }
    fun asset(id: Long, profile: String): LocalAsset = rows("SELECT * FROM assets WHERE id=? AND profile=?",listOf(id.toString(),profile)).use {
        if(!it.moveToFirst()) throw ExtensionFailure("HTTP_404") else asset(it)
    }
    private fun asset(c: Cursor) = LocalAsset(c.number("id"),c.text("profile"),c.text("root"),c.text("uri"),c.text("filename"),c.text("path"),c.text("mime"),
        c.number("modified"),c.number("size"),c.text("signature"),c.number("animated")!=0L,c.text("labels"),c.text("ocr"),c.optional("title"),c.optional("description"),c.optional("tags"),c.number("pending")!=0L,c.optional("failure"),c.optional("ocr_override"))
    fun pending(profile: String): LocalAsset? = rows("SELECT * FROM assets WHERE profile=? AND pending=1 ORDER BY id LIMIT 1",listOf(profile)).use { if(it.moveToFirst()) asset(it) else null }
    private fun index(db: SQLiteDatabase, a: LocalAsset) {
        val text=MediaSearch.normalize(listOf(a.filename,a.title.orEmpty(),a.description.orEmpty(),a.effectiveTags.joinToString("\n"),a.effectiveOcr).joinToString("\n"))
        db.update("assets",ContentValues().apply { put("searchable",text); put("ocr_search",MediaSearch.normalize(a.effectiveOcr)) },"id=?",arrayOf(a.id.toString()))
        db.execSQL("DELETE FROM metadata WHERE rowid=?",arrayOf(a.id))
        db.execSQL("INSERT INTO metadata(rowid,description,ocr) VALUES(?,?,?)",arrayOf<Any>(a.id,text,MediaSearch.normalize(a.effectiveOcr)))
    }
    fun corrections(id: Long, profile: String, title: String, description: String, tags: String, reset: Boolean=false, ocr: String?=null) {
        transaction { db ->
            val previous=asset(id,profile)
            db.update("assets",ContentValues().apply {
                if(reset) { putNull("title"); putNull("description"); putNull("tags"); putNull("ocr_override") }
                else { put("title",title.trim().take(512)); put("description",description.take(8192)); val normalized=LocalFormats.tags(tags)
                    if(previous.tags==null && normalized==LocalFormats.tags(previous.labels)) putNull("tags") else put("tags",normalized)
                    if(ocr!=null) { if(previous.ocrOverride==null && ocr==previous.ocr) putNull("ocr_override") else put("ocr_override",ocr.take(32768)) } }
            },"id=? AND profile=?",arrayOf(id.toString(),profile))
            index(db,asset(id,profile))
        }; changed(profile)
    }
    fun analyzed(a: LocalAsset, analysis: Analysis?, animated: Boolean, error: String?) {
        transaction { db ->
            // A rescan may have changed this file while analysis was running.
            val values=ContentValues().apply { put("pending",0); put("animated",if(animated) 1 else 0); put("failure",error)
                if(analysis!=null) { put("labels",analysis.labels.take(20).joinToString("\n") { it.take(80) }); put("ocr",analysis.text.take(32768)) } }
            if(db.update("assets",values,"id=? AND signature=?",arrayOf(a.id.toString(),a.signature))>0) index(db,asset(a.id,a.profile))
        }; changed(a.profile,false)
    }
    data class Directory(val root: String, val document: String, val path: String, val position: Int=0)
    fun restartScan(profile: String) = transaction { db -> db.delete("scans","profile=?",arrayOf(profile)); db.delete("directories","profile=?",arrayOf(profile)) }
    fun scan(profile: Library): String {
        var generation=""
        transaction { db ->
            generation=rows("SELECT generation FROM scans WHERE profile=? AND revision=?",listOf(profile.id,profile.revision)).use { if(it.moveToFirst()) it.getString(0) else "" }
            if(generation.isEmpty()) {
                generation=UUID.randomUUID().toString()
                db.delete("directories","profile=?",arrayOf(profile.id)); db.delete("scans","profile=?",arrayOf(profile.id))
                db.insertOrThrow("scans",null,ContentValues().apply { put("profile",profile.id); put("revision",profile.revision); put("generation",generation) })
                db.update("folders",ContentValues().apply { putNull("error") },"profile=?",arrayOf(profile.id))
                profile.folders.forEach { folder -> enqueue(profile.id,Directory(folder.uri,android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(folder.uri)),folder.name)) }
            }
        }; changed(profile.id); return generation
    }
    fun enqueue(profile: String, d: Directory) { writableDatabase.insertWithOnConflict("directories",null,ContentValues().apply {
        put("profile",profile); put("root",d.root); put("document",d.document); put("path",d.path)
    },SQLiteDatabase.CONFLICT_IGNORE) }
    fun directory(profile: String): Directory? = rows("SELECT root,document,path,position FROM directories WHERE profile=? AND done=0 ORDER BY rowid LIMIT 1",listOf(profile)).use {
        if(it.moveToFirst()) Directory(it.getString(0),it.getString(1),it.getString(2),it.getInt(3)) else null
    }
    fun checkpoint(profile: String, d: Directory, position: Int) { writableDatabase.update("directories",ContentValues().apply { put("position",position) },"profile=? AND root=? AND document=?",arrayOf(profile,d.root,d.document)) }
    fun directoryDone(profile: String, d: Directory) { writableDatabase.update("directories",ContentValues().apply { put("done",1) },"profile=? AND root=? AND document=?",arrayOf(profile,d.root,d.document)) }
    fun folderError(profile: String, root: String, error: String) { writableDatabase.update("folders",ContentValues().apply { put("error",error) },"profile=? AND uri=?",arrayOf(profile,root)); changed(profile) }
    fun discovered(profile: String, generation: String, root: String, uri: String, name: String, path: String, mime: String, modified: Long, size: Long, revision: String?=null) {
        val updated=transaction { db ->
            if(revision!=null) {
                val current=rows("SELECT revision FROM profiles WHERE id=?",listOf(profile)).use { if(it.moveToFirst()) it.getString(0) else null }
                if(current!=revision) throw kotlinx.coroutines.CancellationException("Profile changed")
            }
            val prior=rows("SELECT * FROM assets WHERE profile=? AND uri=?",listOf(profile,uri)).use { if(it.moveToFirst()) asset(it) else null }
            val signature=digest("$uri|$size|$modified")
            if(prior!=null && prior.signature==signature && prior.filename==name && prior.path==path && prior.mime==mime) {
                db.update("assets",ContentValues().apply { put("seen",generation); put("root",root) },"id=?",arrayOf(prior.id.toString()))
                return@transaction false
            }
            val values=ContentValues().apply { put("profile",profile); put("root",root); put("uri",uri); put("filename",name); put("filename_search",MediaSearch.normalize(name)); put("path",path); put("path_search",MediaSearch.normalize(path)); put("mime",mime); put("modified",modified); put("size",size); put("signature",signature); put("seen",generation) }
            if(prior==null) { values.put("animated",if(mime=="image/gif") 1 else 0); db.insertOrThrow("assets",null,values) }
            else {
                if(prior.signature!=signature) { values.put("pending",1); values.putNull("failure") }
                db.update("assets",values,"id=?",arrayOf(prior.id.toString()))
            }
            val current=rows("SELECT * FROM assets WHERE profile=? AND uri=?",listOf(profile,uri)).use { it.moveToFirst(); asset(it) }
            index(db,current)
            true
        }
        if(updated) changed(profile,false)
    }
    fun scanFinished(profile: String, generation: String) {
        transaction { db ->
            // Only successful, completely traversed roots can remove absent files.
            db.delete("assets","profile=? AND seen<>? AND root IN (SELECT uri FROM folders WHERE profile=? AND error IS NULL)",arrayOf(profile,generation,profile))
            db.delete("scans","profile=?",arrayOf(profile))
            db.update("profiles",ContentValues().apply { put("last_scan",System.currentTimeMillis()) },"id=?",arrayOf(profile))
        }; changed(profile)
    }
    fun retryFailures(profile: String) { writableDatabase.update("assets",ContentValues().apply { put("pending",1); putNull("failure") },"profile=? AND failure IS NOT NULL",arrayOf(profile)); changed(profile) }
    fun analysisUnavailable(library: Library) {
        writableDatabase.update("assets",ContentValues().apply { put("pending",0); put("failure","ANALYSIS_UNAVAILABLE") },
            "profile=? AND pending=1 AND EXISTS (SELECT 1 FROM profiles WHERE id=? AND revision=? AND paused=0)",arrayOf(library.id,library.id,library.revision))
        changed(library.id)
    }
    fun search(profile: String, query: MediaQuery, boundary: Pair<Long,Long>? = null, newer: Boolean=false, limit: Int=61): List<LocalAsset> {
        val args=mutableListOf(profile); val where=mutableListOf("a.profile=?")
        if(query.constraints.media!=SearchMedia.ALL) where += if(query.constraints.media==SearchMedia.VIDEOS) "a.mime LIKE 'video/%'" else "a.mime LIKE 'image/%'"
        query.filter?.let { where += "a.searchable LIKE ? ESCAPE '\\'"; args += LocalFormats.like(it) }
        if(query.text.isNotBlank()) {
            val field=when(query.mode) { SearchMode.FILENAME -> "a.filename_search"; SearchMode.PATH -> "a.path_search"; SearchMode.OCR -> "a.ocr_search"; else -> "a.searchable" }
            val terms=LocalFormats.terms(query.text)
            if(query.mode in setOf(SearchMode.DESCRIPTION,SearchMode.OCR)) {
                val words=terms.filter { term -> term.codePoints().allMatch(Character::isLetterOrDigit) }
                val symbols=terms-words.toSet()
                if(words.isNotEmpty()) {
                    where += "a.id IN (SELECT rowid FROM metadata WHERE metadata MATCH ?)"
                    // Only letters/digits reach FTS syntax; punctuation and emoji use literal LIKE.
                    // Implicit AND also works on Android builds without enhanced FTS3 syntax.
                    args += words.joinToString(" ") { (if(query.mode==SearchMode.OCR) "ocr:" else "description:") + "$it*" }
                }
                symbols.forEach { where += "$field LIKE ? ESCAPE '\\'"; args += LocalFormats.like(it) }
            } else { where += "lower($field) LIKE ? ESCAPE '\\'"; args += LocalFormats.like(query.text.trim()) }
        }
        if(boundary!=null) {
            val op=if(newer) ">" else "<"
            where += "(a.modified $op ? OR (a.modified=? AND a.id $op ?))"
            args += listOf(boundary.first.toString(),boundary.first.toString(),boundary.second.toString())
        }
        val order=if(query.randomRequest!=null) "RANDOM()" else if(newer) "a.modified ASC,a.id ASC" else "a.modified DESC,a.id DESC"
        return rows("SELECT a.* FROM assets a WHERE ${where.joinToString(" AND ")} ORDER BY $order LIMIT ${limit.coerceIn(1,101)}",args).use { c -> buildList { while(c.moveToNext()) add(asset(c)) } }
    }
    fun emojiFilters(profile: String): List<String> = rows("SELECT coalesce(tags,labels) FROM assets WHERE profile=? AND (tags IS NOT NULL OR labels<>'')",listOf(profile)).use { c ->
        val result=linkedSetOf<String>()
        while(c.moveToNext()) for(i in 0..0) c.getString(i).orEmpty().split('\n').filter { value -> value.codePoints().anyMatch { it in 0x1f000..0x1faff || it in 0x2600..0x27ff } }.forEach(result::add)
        result.toList().take(200)
    }
    companion object {
        fun changesUri(context: Context, profile: String, revision: String): android.net.Uri = android.net.Uri.Builder().scheme("content").authority("${context.packageName}.media")
            .appendPath("updates").appendPath(profile).appendPath(revision).build()
        @Volatile private var instance: LocalStore?=null
        fun get(context: Context): LocalStore = instance ?: synchronized(this) { instance ?: LocalStore(context.applicationContext as android.app.Application).also { instance=it } }
        private fun Cursor.text(key: String) = getString(getColumnIndexOrThrow(key))
        private fun Cursor.optional(key: String): String? = getColumnIndexOrThrow(key).let { if(isNull(it)) null else getString(it) }
        private fun Cursor.number(key: String) = getLong(getColumnIndexOrThrow(key))
    }
}
