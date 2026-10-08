package jp.local.imagepdf

import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.*

/** All destructive cases and migration fixtures use a separate temporary database. */
class SubfolderTest {
    private val context=Sandbox(InstrumentationRegistry.getInstrumentation().targetContext)
    private lateinit var library: Library
    private lateinit var source: File
    @Before fun setup() {
        library=Library(context)
        source=File(context.cacheDir,"source.pdf")
        val pdf=PdfDocument();try { repeat(6) { val p=pdf.startPage(PdfDocument.PageInfo.Builder(600,900,it+1).create());pdf.finishPage(p) };source.outputStream().use { pdf.writeTo(it) } } finally { pdf.close() }
    }
    @After fun close() { library.close();context.cleanup() }
    private fun pdf(work:String,name:String="2.pdf")=library.import(listOf(Library.Source(Uri.fromFile(source),name,source.length())),work,null,AtomicBoolean(false),{Library.Conflict.RENAME},{_,_,_->}).single()
    private fun rejected(action:()->Unit) { try { action();fail("Expected rejection") } catch(_:IllegalArgumentException) {} catch(_:android.database.sqlite.SQLiteException) {} }
    @Test fun additiveVersionTwoMigrationKeepsEveryExistingColumn() {
        library.close()
        val db=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("old.db"),null)
        db.execSQL("CREATE TABLE works(id TEXT PRIMARY KEY,name TEXT NOT NULL,rank INTEGER,updated INTEGER,read INTEGER,columns INTEGER,sort TEXT,descending INTEGER)")
        db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE,name TEXT NOT NULL,pages INTEGER,rank INTEGER,updated INTEGER,read INTEGER,page INTEGER,offset REAL,UNIQUE(work,name))")
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("INSERT INTO works VALUES('root','旧作品',9,123,456,3,'manual',1)")
        db.execSQL("INSERT INTO books VALUES('book','root','10.pdf',6,7,321,654,3,0.42)")
        db.execSQL("INSERT INTO metadata VALUES('reader','{\"reopen\":false,\"gap\":16}')")
        db.execSQL("INSERT INTO metadata VALUES('last','book')")
        db.version=2;db.close()
        context.getDatabasePath("library.db").delete();File(context.getDatabasePath("library.db").path+"-wal").delete();File(context.getDatabasePath("library.db").path+"-shm").delete()
        check(context.getDatabasePath("old.db").renameTo(context.getDatabasePath("library.db")))
        library=Library(context)
        assertEquals(Work("root","旧作品",9,123,456,3,"manual",true),library.works().single())
        assertEquals(Book("book","root","10.pdf",6,7,321,654,3,.42f),library.books().single())
        assertFalse(library.settings().reopen);assertEquals(16,library.settings().gap);assertEquals("book",library.last)
        assertEquals(3,library.readableDatabase.version)
        assertEquals(0,library.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use { it.count })
    }
    @Test fun depthAndRootMoveAreRejectedInApiAndDatabase() {
        val a=library.createWork("A");val sub=library.createWork("Sub",a);val b=library.createWork("B")
        rejected { library.createWork("Grandchild",sub) }
        rejected { library.move(listOf(sub),sub) };rejected { library.move(listOf(a),b) };rejected { library.move(listOf(sub),"missing") }
        rejected { library.writableDatabase.execSQL("UPDATE works SET parent=? WHERE id=?",arrayOf(b,a)) }
        rejected { library.writableDatabase.execSQL("UPDATE works SET parent=NULL WHERE id=?",arrayOf(sub)) }
        rejected { library.writableDatabase.execSQL("INSERT INTO works(id,name,parent) VALUES('bad','Bad',?)",arrayOf(sub)) }
        assertEquals(a,library.work(sub).parent);assertNull(library.work(a).parent)
    }
    @Test fun mixedSortManualRanksFixedSubfolderOrderAndRename() {
        val a=library.createWork("A");val p1=pdf(a,"1.pdf");val sub=library.createWork("2 folder",a);val p3=pdf(a,"3.pdf")
        assertEquals(listOf(p1,sub,p3),library.contents(a).map { it.id })
        library.workOptions(a,3,"name",true);assertEquals(listOf(p3,sub,p1),library.contents(a).map { it.id })
        library.workOptions(a,3,"manual",false);library.reorderContents(a,listOf(sub,p3,p1));assertEquals(listOf(sub,p3,p1),library.contents(a).map { it.id })
        val ten=pdf(sub,"10.pdf");val two=pdf(sub,"2.pdf");library.workOptions(sub,2,"manual",true)
        assertEquals(listOf(two,ten),library.orderedBooks(sub).map { it.id });library.rename(false,ten,"1.pdf")
        assertEquals(listOf(ten,two),library.orderedBooks(sub).map { it.id });library.rename(true,sub,"renamed")
        assertEquals("renamed",library.work(sub).name);assertEquals(2,library.books(a).size);assertEquals(2,library.books(sub).size)
        library.writableDatabase.execSQL("UPDATE books SET updated=10,read=10 WHERE id=?",arrayOf(p1))
        library.writableDatabase.execSQL("UPDATE books SET updated=30,read=30 WHERE id=?",arrayOf(p3))
        library.writableDatabase.execSQL("UPDATE works SET updated=20,read=20 WHERE id=?",arrayOf(sub))
        for(mode in listOf("updated","read")) { library.workOptions(a,2,mode,false);assertEquals(listOf(p1,sub,p3),library.contents(a).map { it.id }) }
    }
    @Test fun allPdfRoutesAndMixedMovePreserveIdentityBytesCoversAndMetadata() {
        val a=library.createWork("A");val b=library.createWork("B");val x=library.createWork("X",a);val y=library.createWork("Y",b)
        val key=pdf(a);library.position(key,3,.42f,false);library.markRead(library.book(key)!!);library.thumbnail(key)
        val before=library.book(key)!!;val bytes=library.file(key).readBytes();val cover=library.cover(key).readBytes()
        for(dest in listOf(b,x,a,y,x,b)) { library.move(listOf(key),dest);val moved=library.book(key)!!;assertEquals(before.copy(work=dest,order=moved.order),moved);assertArrayEquals(bytes,library.file(key).readBytes());assertArrayEquals(cover,library.cover(key).readBytes()) }
        val nested=pdf(x,"7.pdf");val nestedBefore=library.book(nested)!!;library.move(listOf(key,x),b)
        assertEquals(b,library.work(x).parent);assertEquals(nestedBefore,library.book(nested));assertEquals(key,library.last)
        assertEquals(2,library.books().size);assertEquals(setOf(a,b),library.orderedWorks().map { it.id }.toSet())
    }
    @Test fun batchRejectionAndSqlFailureRollBackEveryItem() {
        val a=library.createWork("A");val b=library.createWork("B");val x=library.createWork("X",a);val y=library.createWork("Y",b);val p=pdf(a)
        val before=library.book(p);rejected { library.move(listOf(p,x),y) };assertEquals(before,library.book(p));assertEquals(a,library.work(x).parent)
        library.writableDatabase.execSQL("CREATE TRIGGER fail_move BEFORE UPDATE OF parent ON works BEGIN SELECT RAISE(ABORT,'simulated failure'); END")
        rejected { library.move(listOf(p,x),b) };assertEquals(before,library.book(p));assertEquals(a,library.work(x).parent);assertTrue(library.file(p).exists())
    }
    @Test fun nameCollisionsKeepBothPdfsAndFolders() {
        val a=library.createWork("A");val b=library.createWork("B");val p=pdf(a,"1.pdf");val existing=pdf(b,"1.pdf");val x=library.createWork("X",a);val existingFolder=library.createWork("X",b)
        library.move(listOf(p,x,existing),b)
        assertEquals("1 (2).pdf",library.book(p)!!.name);assertEquals("1.pdf",library.book(existing)!!.name)
        assertEquals("X (2)",library.work(x).name);assertEquals("X",library.work(existingFolder).name)
    }
    @Test fun deletingRootOrSubfolderIncludesDescendantsButNotOtherWorks() {
        val a=library.createWork("A");val b=library.createWork("B");val x=library.createWork("X",a);val p=pdf(a);val nested=pdf(x);val keep=pdf(b)
        assertEquals(setOf(p,nested),library.selectedBooks(listOf(a)).map { it.id }.toSet());library.deleteItems(listOf(x))
        assertFalse(library.file(nested).exists());assertTrue(library.file(p).exists());assertTrue(library.file(keep).exists())
        val y=library.createWork("Y",a);val nested2=pdf(y);library.delete(true,listOf(a))
        assertEquals(listOf(keep),library.books().map { it.id });assertFalse(library.file(nested2).exists());assertEquals(listOf(b),library.works().map { it.id })
    }
    private fun rewriteBackup(file:File,edit:(JSONObject)->Unit) {
        val entries=linkedMapOf<String,ByteArray>();ZipInputStream(file.inputStream()).use { z -> while(true) { val e=z.nextEntry?:break;entries[e.name]=z.readBytes() } }
        val meta=JSONObject(String(entries.getValue("library.json"),Charsets.UTF_8));edit(meta);entries["library.json"]=meta.toString().toByteArray()
        ZipOutputStream(file.outputStream()).use { z -> entries.forEach { (name,data) -> z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry() } }
    }
    @Test fun hierarchyBackupMergeAndReplaceRetainParentsRanksAndPositions() {
        val a=library.createWork("A");val x=library.createWork("X",a);val p=pdf(x);library.position(p,3,.25f,false);library.last=p;library.reorderContents(a,listOf(x));val before=library.file(p).readBytes()
        val archive=File(context.cacheDir,"backup.zip");library.backup(Uri.fromFile(archive));library.restore(Uri.fromFile(archive),false)
        assertEquals(4,library.works().size);assertEquals(2,library.orderedWorks().size);assertEquals(2,library.books().size)
        library.restore(Uri.fromFile(archive),true);val root=library.orderedWorks().single();val sub=library.children(root.id).single();val restored=library.books().single()
        assertEquals(sub.id,restored.work);assertEquals("A",root.name);assertEquals("X",sub.name);assertEquals(3,restored.page);assertEquals(.25f,restored.offset,.001f);assertEquals(restored.id,library.last);assertArrayEquals(before,library.file(restored.id).readBytes())
    }
    @Test fun oldVersionOneBackupsRemainReadableAndInvalidHierarchyDoesNotReplaceData() {
        val a=library.createWork("A");val p=pdf(a);val archive=File(context.cacheDir,"old.zip");library.backup(Uri.fromFile(archive))
        rewriteBackup(archive) { j -> j.put("version",1);val works=j.getJSONArray("works");repeat(works.length()) { works.getJSONObject(it).remove("parent") } }
        library.restore(Uri.fromFile(archive),false);assertTrue(library.works().all { it.parent==null })
        library.backup(Uri.fromFile(archive));rewriteBackup(archive) { j -> val ws=j.getJSONArray("works");ws.getJSONObject(0).put("parent",ws.getJSONObject(0).getString("id")) }
        val before=library.works();val books=library.books();rejected { library.restore(Uri.fromFile(archive),true) };assertEquals(before,library.works());assertEquals(books,library.books());assertTrue(library.file(p).exists())
    }
    private class Sandbox(base:Context):ContextWrapper(base) {
        private val root=File(base.cacheDir,"subfolder-${System.nanoTime()}").apply { mkdirs() }
        override fun getFilesDir()=File(root,"files").apply { mkdirs() }
        override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
        override fun getDatabasePath(name:String)=File(root,name)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        fun cleanup() { root.deleteRecursively() }
    }
}
