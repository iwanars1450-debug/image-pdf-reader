package jp.local.imagepdf

import android.content.*
import android.database.sqlite.*
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.*
import java.io.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.*

class Library(val context: Context) : SQLiteOpenHelper(context,"library.db",null,2) {
    val root=File(context.filesDir,"pdfs").apply { mkdirs() }
    val thumbs=File(context.cacheDir,"covers").apply { mkdirs() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE works(id TEXT PRIMARY KEY,name TEXT NOT NULL,rank INTEGER,updated INTEGER,read INTEGER,columns INTEGER,sort TEXT,descending INTEGER)")
        db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE,name TEXT NOT NULL,pages INTEGER,rank INTEGER,updated INTEGER,read INTEGER,page INTEGER,offset REAL,UNIQUE(work,name))")
        db.execSQL("CREATE INDEX books_work ON books(work)")
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onUpgrade(db: SQLiteDatabase,old: Int,new: Int) {
        if(old<2) {
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            context.getSharedPreferences("settings",Context.MODE_PRIVATE).all.forEach { (key,value) -> if(value!=null) db.insertOrThrow("metadata",null,ContentValues().apply { put("key",key);put("value",value.toString()) }) }
        }
    }
    private fun get(key: String): String?=readableDatabase.rawQuery("SELECT value FROM metadata WHERE key=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else null }
    private fun set(key: String,value: String?) { if(value==null) writableDatabase.delete("metadata","key=?",arrayOf(key)) else writableDatabase.insertWithOnConflict("metadata",null,ContentValues().apply { put("key",key);put("value",value) },SQLiteDatabase.CONFLICT_REPLACE) }
    fun settings()=Preferences.from(JSONObject(get("reader")?:"{}"))
    fun saveSettings(p: Preferences) { set("reader",p.json().toString()) }
    var onboarding: Boolean get()=get("onboarding")=="true"; set(v) { set("onboarding",v.toString()) }
    var last: String? get()=get("last"); set(v) { set("last",v) }
    var columns: Int get()=get("columns")?.toIntOrNull()?.coerceIn(2,3)?:2; set(v) { set("columns",v.toString()) }
    var sort: String get()=get("sort")?:"name"; set(v) { set("sort",v) }
    var descending: Boolean get()=get("descending")=="true"; set(v) { set("descending",v.toString()) }
    fun works(): List<Work> = readableDatabase.rawQuery("SELECT * FROM works",null).use { c -> buildList { while(c.moveToNext()) add(Work(c.getString(0),c.getString(1),c.getInt(2),c.getLong(3),c.getLong(4),c.getInt(5),c.getString(6),c.getInt(7)!=0)) } }
    fun books(work: String? = null): List<Book> = readableDatabase.rawQuery("SELECT * FROM books"+(if(work==null) "" else " WHERE work=?"),work?.let { arrayOf(it) }).use { c -> buildList { while(c.moveToNext()) add(Book(c.getString(0),c.getString(1),c.getString(2),c.getInt(3),c.getInt(4),c.getLong(5),c.getLong(6),c.getInt(7),c.getFloat(8))) } }
    fun book(id: String)=books().find { it.id==id }
    fun orderedWorks()=sortedItems(works(),sort,descending,{it.name},{it.order},{it.read},{it.updated})
    fun orderedBooks(id: String): List<Book> { val w=works().first { it.id==id }; return sortedItems(books(id),w.sort,w.descending,{it.name},{it.order},{it.read},{it.updated}) }
    fun file(id: String)=File(root,"$id.pdf")
    fun cover(id: String)=File(thumbs,"$id.png")
    private fun id()=UUID.randomUUID().toString()
    fun uniqueName(name: String, existing: Collection<String>, pdf: Boolean=false): String {
        val cleaned=name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").trim().take(180).ifBlank { if(pdf) "document.pdf" else "作品" }
        val base=if(pdf) cleaned.removeSuffix(".pdf") else cleaned
        val full=if(pdf) "$base.pdf" else base
        var n=2; var result=full
        while(existing.any { it.equals(result,true) }) { result="$base (${n++})"+(if(pdf) ".pdf" else "") }
        return result
    }
    fun createWork(name: String): String { val key=id(); insertWork(key,uniqueName(name,works().map { it.name })); return key }
    private fun insertWork(key: String,name: String) { writableDatabase.insertOrThrow("works",null,ContentValues().apply { put("id",key);put("name",name);put("rank",works().size);put("updated",System.currentTimeMillis());put("read",0);put("columns",2);put("sort","name");put("descending",0) }) }
    fun workOptions(id: String,columns: Int,sort: String,desc: Boolean) { writableDatabase.update("works",ContentValues().apply { put("columns",columns);put("sort",sort);put("descending",if(desc) 1 else 0) },"id=?",arrayOf(id)) }
    fun rename(work: Boolean,id: String,name: String) {
        val clean=name.trim(); require(clean.isNotEmpty() && !clean.contains('/') && !clean.contains('\\'))
        writableDatabase.update(if(work) "works" else "books",ContentValues().apply { put("name",clean);put("updated",System.currentTimeMillis()) },"id=?",arrayOf(id))
    }
    fun reorder(work: Boolean,ids: List<String>) { transaction { ids.forEachIndexed { i,key -> writableDatabase.update(if(work) "works" else "books",ContentValues().apply { put("rank",i) },"id=?",arrayOf(key)) } } }
    fun markRead(book: Book) { val now=System.currentTimeMillis(); transaction { writableDatabase.update("books",ContentValues().apply { put("read",now) },"id=?",arrayOf(book.id));writableDatabase.update("works",ContentValues().apply { put("read",now) },"id=?",arrayOf(book.work)) };last=book.id }
    fun position(id: String,page: Int,offset: Float,finished: Boolean) { writableDatabase.update("books",ContentValues().apply { put("page",if(finished) 0 else page);put("offset",if(finished) 0f else offset.coerceIn(0f,1f)) },"id=?",arrayOf(id)) }
    fun delete(work: Boolean,ids: List<String>) {
        val doomed=books().filter { if(work) it.work in ids else it.id in ids }
        transaction { ids.forEach { writableDatabase.delete(if(work) "works" else "books","id=?",arrayOf(it)) } }
        doomed.forEach { file(it.id).delete();cover(it.id).delete() }
    }
    fun <T> transaction(block: ()->T): T { val db=writableDatabase;db.beginTransaction();try { val result=block();db.setTransactionSuccessful();return result } finally { db.endTransaction() } }
    fun source(uri: Uri): Source {
        var name="document.pdf";var size: Long?=null
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { if(it.moveToFirst()) { name=it.getString(0)?:name;if(!it.isNull(1)) size=it.getLong(1) } }
        return Source(uri,name,size)
    }
    fun folder(uri: Uri): Pair<String,List<Source>> {
        val document=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri))
        val folderName=context.contentResolver.query(document,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else "作品" } ?: "作品"
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri))
        val items=mutableListOf<Source>()
        context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use { c -> while(c.moveToNext()) if(c.getString(1).endsWith(".pdf",true)||c.getString(3)=="application/pdf") items.add(Source(DocumentsContract.buildDocumentUriUsingTree(uri,c.getString(0)),c.getString(1),if(c.isNull(2)) null else c.getLong(2))) }
        return folderName to items.sortedWith { a,b -> NaturalOrder.compare(a.name,b.name) }
    }
    data class Source(val uri: Uri,val name: String,val size: Long?)
    enum class Conflict { REPLACE, RENAME, SKIP }
    private fun copy(input: InputStream,output: OutputStream,cancel: AtomicBoolean,tick:(Long)->Unit) { val buffer=ByteArray(128*1024);var total=0L;while(true) { if(cancel.get()) throw InterruptedIOException("cancelled");val n=input.read(buffer);if(n<0) break;output.write(buffer,0,n);total+=n;tick(total) } }
    /** Files are immutable UUID objects. DB commit is the only publication point. */
    @Synchronized fun import(sources: List<Source>,work: String?,newName: String?,cancel: AtomicBoolean,conflict:(String)->Conflict,progress:(Int,Int,Int)->Unit): List<String> {
        require(sources.isNotEmpty()) { "PDFがありません" }
        val available=StatFs(root.path).availableBytes
        require(sources.sumOf { it.size?:0L } + 16L*1024*1024 < available) { "空き容量が不足しています" }
        val target=work?:id(); val staged=mutableListOf<Book>();val replaced=mutableListOf<Book>();val files=mutableListOf<File>()
        val existing=if(work!=null) books(work).toMutableList() else mutableListOf()
        try {
            sources.forEachIndexed { index,src ->
                if(cancel.get()) throw InterruptedIOException()
                var name=src.name; val same=existing.find { it.name.equals(name,true) }
                if(same!=null) when(conflict(name)) { Conflict.SKIP -> { progress(index+1,sources.size,100);return@forEachIndexed };Conflict.RENAME -> name=uniqueName(name,existing.map { it.name },true);Conflict.REPLACE -> { replaced.add(same);existing.remove(same);staged.remove(same) } }
                val key=id();val output=file(key);files.add(output)
                context.contentResolver.openInputStream(src.uri).use { input -> requireNotNull(input);FileOutputStream(output).use { out -> copy(input,out,cancel) { bytes -> progress(index+1,sources.size,if(src.size!=null&&src.size>0) ((bytes*100/src.size).toInt()).coerceAtMost(100) else 0) };out.fd.sync() } }
                val pages=PdfRenderer(ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
                require(pages>0)
                val b=Book(key,target,name,pages,same?.order?:existing.size,System.currentTimeMillis(),0,0,0f);staged.add(b);existing.add(b)
                progress(index+1,sources.size,100)
            }
            if(cancel.get()) throw InterruptedIOException()
            transaction {
                if(work==null) insertWork(target,uniqueName(newName?:"作品",works().map { it.name }))
                replaced.forEach { writableDatabase.delete("books","id=?",arrayOf(it.id)) }
                staged.forEach { insertBook(it) }
                writableDatabase.update("works",ContentValues().apply { put("updated",System.currentTimeMillis()) },"id=?",arrayOf(target))
            }
            replaced.forEach { file(it.id).delete();cover(it.id).delete() }
            files.filter { f -> staged.none { file(it.id)==f } }.forEach { it.delete() }
            return staged.map { it.id }
        } catch(e: Throwable) { files.forEach { it.delete() };throw e }
    }
    private fun insertBook(b: Book) { writableDatabase.insertOrThrow("books",null,ContentValues().apply { put("id",b.id);put("work",b.work);put("name",b.name);put("pages",b.pages);put("rank",b.order);put("updated",b.updated);put("read",b.read);put("page",b.page);put("offset",b.offset) }) }
    @Synchronized fun thumbnail(id: String): File {
        val out=cover(id);if(out.exists()) return out
        PdfRenderer(ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.MODE_READ_ONLY)).use { r -> r.openPage(0).use { p ->
            val h=(320f*p.height/p.width).toInt().coerceIn(1,1600);val b=Bitmap.createBitmap(320,h,Bitmap.Config.ARGB_8888);b.eraseColor(android.graphics.Color.WHITE)
            try { p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);FileOutputStream(File(thumbs,"$id.tmp")).use { b.compress(Bitmap.CompressFormat.PNG,100,it) };check(File(thumbs,"$id.tmp").renameTo(out)) } finally { b.recycle() }
        } };return out
    }
    fun cacheBytes()=context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun libraryBytes()=root.listFiles()?.sumOf { it.length() } ?: 0L
    @Synchronized fun clearCache() { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() };thumbs.mkdirs() }
    @Synchronized fun collectOrphans() { val live=books().map { "${it.id}.pdf" }.toSet();root.listFiles()?.filter { it.name !in live }?.forEach { it.delete() };File(context.cacheDir,"external").deleteRecursively();File(context.cacheDir,"restore").deleteRecursively() }
    @Synchronized fun backup(uri: Uri) {
        val ws=works();val bs=books();val meta=JSONObject().put("version",1).put("preferences",settings().json()).put("columns",columns).put("sort",sort).put("descending",descending).put("last",last).put("onboarding",onboarding)
        meta.put("works",JSONArray(ws.map { JSONObject().put("id",it.id).put("name",it.name).put("rank",it.order).put("updated",it.updated).put("read",it.read).put("columns",it.columns).put("sort",it.sort).put("descending",it.descending) }))
        meta.put("books",JSONArray(bs.map { JSONObject().put("id",it.id).put("work",it.work).put("name",it.name).put("pages",it.pages).put("rank",it.order).put("updated",it.updated).put("read",it.read).put("page",it.page).put("offset",it.offset) }))
        ZipOutputStream(context.contentResolver.openOutputStream(uri)!!).use { z -> z.setLevel(0);z.putNextEntry(ZipEntry("library.json"));z.write(meta.toString().toByteArray(Charsets.UTF_8));z.closeEntry();bs.forEach { b -> z.putNextEntry(ZipEntry("pdfs/${b.id}.pdf"));file(b.id).inputStream().use { it.copyTo(z) };z.closeEntry() } }
    }
    @Synchronized fun restore(uri: Uri,replace: Boolean) {
        val stage=File(context.cacheDir,"restore").apply { deleteRecursively();mkdirs() };val added=mutableListOf<File>();val old=books();var committed=false
        try {
            var meta: JSONObject?=null;val seen=mutableSetOf<String>();var total=0L;val capacity=StatFs(root.path).availableBytes-16L*1024*1024
            ZipInputStream(context.contentResolver.openInputStream(uri)!!).use { z -> while(true) {
                val entry=z.nextEntry?:break;val name=entry.name;require(seen.add(name));require(name=="library.json" || Regex("pdfs/[a-fA-F0-9-]{36}\\.pdf").matches(name))
                val f=File(stage,if(name=="library.json") name else name.substringAfter('/'))
                f.outputStream().use { out -> val buffer=ByteArray(128*1024);var n: Int;while(z.read(buffer).also { n=it }>0) { total+=n;require(total<=capacity);if(name=="library.json") require(f.length()+n<=8*1024*1024);out.write(buffer,0,n) } }
                if(name=="library.json") meta=JSONObject(f.readText());z.closeEntry()
            } }
            val j=requireNotNull(meta);require(j.getInt("version")==1);val wa=j.getJSONArray("works");val ba=j.getJSONArray("books");val mapping=mutableMapOf<String,String>();val bookMap=mutableMapOf<String,String>();val names=if(replace) mutableListOf() else works().map { it.name }.toMutableList();val newWorks=mutableListOf<Work>();val newBooks=mutableListOf<Book>()
            for(i in 0 until wa.length()) { val w=wa.getJSONObject(i);val key=id();require(!mapping.containsKey(w.getString("id")));mapping[w.getString("id")]=key;val name=uniqueName(w.getString("name"),names);names.add(name);newWorks.add(Work(key,name,w.getInt("rank")+(if(replace) 0 else works().size),w.getLong("updated"),w.getLong("read"),w.getInt("columns").coerceIn(2,3),w.getString("sort"),w.getBoolean("descending"))) }
            for(i in 0 until ba.length()) { val b=ba.getJSONObject(i);val oldId=b.getString("id");require(Regex("[a-fA-F0-9-]{36}").matches(oldId));require(!bookMap.containsKey(oldId));val key=id();bookMap[oldId]=key;val source=File(stage,"$oldId.pdf");require(source.exists());val pages=PdfRenderer(ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount };require(pages==b.getInt("pages")&&pages>0);val dest=file(key);added.add(dest);check(source.renameTo(dest));newBooks.add(Book(key,requireNotNull(mapping[b.getString("work")]),b.getString("name"),pages,b.getInt("rank"),b.getLong("updated"),b.getLong("read"),b.getInt("page").coerceIn(0,pages-1),b.getDouble("offset").toFloat().coerceIn(0f,1f))) }
            transaction { if(replace) writableDatabase.delete("works",null,null);newWorks.forEach { w -> insertWork(w.id,w.name);writableDatabase.update("works",ContentValues().apply { put("rank",w.order);put("updated",w.updated);put("read",w.read);put("columns",w.columns);put("sort",w.sort);put("descending",if(w.descending) 1 else 0) },"id=?",arrayOf(w.id)) };newBooks.forEach { insertBook(it) }
                if(replace) { saveSettings(Preferences.from(j.getJSONObject("preferences")));columns=j.optInt("columns",2).coerceIn(2,3);sort=j.optString("sort","name");descending=j.optBoolean("descending");last=bookMap[j.optString("last")];onboarding=j.optBoolean("onboarding",true) }
            }
            committed=true
            if(replace) old.forEach { file(it.id).delete();cover(it.id).delete() }
        } finally { if(!committed) added.forEach { it.delete() };stage.deleteRecursively() }
    }
    fun export(uri: Uri,books: List<Book>,folders: Boolean) {
        val parent=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri));val works=works().associateBy { it.id };val dirs=mutableMapOf<String,Uri>()
        books.forEach { b -> val dest=if(folders) dirs.getOrPut(b.work) { requireNotNull(DocumentsContract.createDocument(context.contentResolver,parent,DocumentsContract.Document.MIME_TYPE_DIR,works[b.work]!!.name)) } else parent
            val output=requireNotNull(DocumentsContract.createDocument(context.contentResolver,dest,"application/pdf",b.name));context.contentResolver.openOutputStream(output)!!.use { out -> file(b.id).inputStream().use { it.copyTo(out) } }
        }
    }
}
