package jp.local.imagepdf

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.*

class LibraryTest {
    private val context=IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private lateinit var library: Library
    private lateinit var source: File
    @Before fun setup() {
        library=Library(context);library.delete(true,library.works().map { it.id });library.onboarding=true;library.saveSettings(Preferences(reopen=false));library.last=null
        source=File(context.cacheDir,"source.pdf")
        val document=PdfDocument()
        try { for(i in 0..5) { val page=document.startPage(PdfDocument.PageInfo.Builder(600,900,i+1).create());page.canvas.drawColor(Color.rgb(20+i*25,30,80));page.canvas.drawText("Page ${i+1}",60f,100f,Paint().apply { color=Color.WHITE;textSize=32f });document.finishPage(page) };source.outputStream().use { document.writeTo(it) } } finally { document.close() }
    }
    @After fun close() { library.close() }
    private fun import(name: String="1.pdf",work: String?=null,policy: Library.Conflict=Library.Conflict.RENAME): String = library.import(listOf(Library.Source(Uri.fromFile(source),name,source.length())),work,"作品",AtomicBoolean(false),{policy},{_,_,_->}).single()
    private fun digest(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    @Test fun copyIsByteIdenticalAndIndependentOfOriginal() {
        val key=import();assertEquals(digest(source),digest(library.file(key)));assertEquals(6,library.book(key)!!.pages);source.delete();assertTrue(library.file(key).exists())
        library.position(key,3,.42f,false);library.close();library=Library(context);assertEquals(3,library.book(key)!!.page);assertEquals(.42f,library.book(key)!!.offset,.001f)
        library.position(key,5,0f,true);assertEquals(0,library.book(key)!!.page)
    }
    @Test fun failedBatchRestoresReplacedBookAndMetadata() {
        val key=import();val b=library.book(key)!!;library.position(key,3,.4f,false);val hash=digest(library.file(key));val invalid=File(context.cacheDir,"broken.pdf").apply { writeText("broken") }
        try { library.import(listOf(Library.Source(Uri.fromFile(source),b.name,source.length()),Library.Source(Uri.fromFile(invalid),"2.pdf",invalid.length())),b.work,null,AtomicBoolean(false),{Library.Conflict.REPLACE},{_,_,_->});fail("Expected rejected PDF") } catch(_: Exception) {}
        assertEquals(listOf(key),library.books().map { it.id });assertEquals(hash,digest(library.file(key)));assertEquals(3,library.book(key)!!.page);assertEquals(1,library.root.listFiles()!!.size)
    }
    @Test fun cancellationRollsBackWholeBatch() {
        val cancel=AtomicBoolean(false)
        try { library.import(listOf(Library.Source(Uri.fromFile(source),"1.pdf",source.length())),null,"作品",cancel,{Library.Conflict.RENAME},{_,_,percent->if(percent==100) cancel.set(true)});fail("Expected cancellation") } catch(_: java.io.InterruptedIOException) {}
        assertTrue(library.books().isEmpty());assertTrue(library.works().isEmpty());assertTrue(library.root.listFiles()!!.isEmpty())
    }
    @Test fun backupMergeAndReplacementPreserveBytesAndPosition() {
        val key=import();library.position(key,3,.25f,false);library.last=key;library.saveSettings(Preferences(gap=16,step=50))
        val backup=File(context.cacheDir,"backup.zip");library.backup(Uri.fromFile(backup));val hash=digest(library.file(key))
        library.restore(Uri.fromFile(backup),false);assertEquals(2,library.works().size);assertEquals(setOf("作品","作品 (2)"),library.works().map { it.name }.toSet());assertEquals(2,library.books().size)
        library.restore(Uri.fromFile(backup),true);assertEquals(1,library.books().size);val restored=library.books().single();assertEquals(hash,digest(library.file(restored.id)));assertEquals(3,restored.page);assertEquals(.25f,restored.offset,.001f);assertEquals(restored.id,library.last);assertEquals(16,library.settings().gap)
    }
    @Test fun hostileBackupNeverChangesLibrary() {
        val key=import();val archive=File(context.cacheDir,"hostile.zip");ZipOutputStream(archive.outputStream()).use { it.putNextEntry(ZipEntry("../../pdfs/escape.pdf"));it.write(byteArrayOf(1));it.closeEntry() }
        try { library.restore(Uri.fromFile(archive),true);fail("Expected rejected archive") } catch(_: Exception) {}
        assertEquals(listOf(key),library.books().map { it.id });assertTrue(library.file(key).exists())
    }
    private class IsolatedContext(base: Context): ContextWrapper(base) {
        private val sandbox=File(base.filesDir,"instrumentation-sandbox").apply { mkdirs() }
        override fun getFilesDir()=File(sandbox,"files").apply { mkdirs() }
        override fun getCacheDir()=File(sandbox,"cache").apply { mkdirs() }
        override fun getDatabasePath(name: String)=File(sandbox,name)
        override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
        override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,errorHandler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
        override fun getSharedPreferences(name: String,mode: Int)=baseContext.getSharedPreferences("instrumentation-$name",mode)
    }
}
