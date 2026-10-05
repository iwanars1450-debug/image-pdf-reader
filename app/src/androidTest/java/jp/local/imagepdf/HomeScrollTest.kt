package jp.local.imagepdf

import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Synthetic works live in a separate database; user works, PDFs and settings are never edited. */
class HomeScrollTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private lateinit var activity: MainActivity
    private lateinit var library: Library
    private fun ui(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun invoke(name:String) { ui { MainActivity::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(activity) } }
    @Suppress("UNCHECKED_CAST") private fun <T> field(name:String):T=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity) as T
    private fun await(block:()->Boolean) { val end=SystemClock.uptimeMillis()+8000;while(SystemClock.uptimeMillis()<end) { var ok=false;ui { ok=block() };if(ok) return;SystemClock.sleep(30) };fail("Timed out: page=${field<String>("page")}, position=${position()}") }
    @Before fun setup() {
        val file=File(context.cacheDir,"home-scroll-fixture.pdf");val pdf=PdfDocument()
        val p=pdf.startPage(PdfDocument.PageInfo.Builder(600,900,1).create());pdf.finishPage(p);file.outputStream().use{pdf.writeTo(it)};pdf.close()
        // An external PDF bypasses auto-reopening and marking a user's book as read.
        activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        invoke("closeReader");instrumentation.waitForIdleSync()
        library=Library(Sandbox(context));library.onboarding=true;library.saveSettings(Preferences(reopen=false));library.columns=2
        for(i in 0 until 120) library.createWork("作品 ${i.toString().padStart(3,'0')}")
        ui { MainActivity::class.java.getDeclaredField("library").apply{isAccessible=true}.set(activity,library) }
        invoke("showLibrary");await { field<GridView>("grid").childCount>0 }
    }
    @After fun close() { ui { activity.finish() };instrumentation.waitForIdleSync() }
    private fun position()=field<GridView>("grid").let{it.firstVisiblePosition to (it.getChildAt(0)?.top?:Int.MIN_VALUE)}
    private fun buttons(v:View):List<Button> = (if(v is Button) listOf(v) else emptyList())+(if(v is ViewGroup) (0 until v.childCount).flatMap{buttons(v.getChildAt(it))} else emptyList())
    private fun roundTrip(index:Int,offset:Int) {
        SystemClock.sleep(100)
        android.util.Log.i("HomeScrollTest","Round trip index=$index offset=$offset")
        ui { val g=field<GridView>("grid");repeat(80){g.scrollListBy(-g.height)}
            var remaining=(index/g.numColumns)*(g.getChildAt(0).height+g.verticalSpacing)-offset
            while(remaining>0) { val step=minOf(remaining,g.height/2);g.scrollListBy(step);remaining-=step }
        }
        await { val g=field<GridView>("grid");g.firstVisiblePosition==index&&g.getChildAt(0)?.top==g.paddingTop+offset }
        val before=position()
        ui { val g=field<GridView>("grid");g.performItemClick(g.getChildAt(g.childCount-1),g.firstVisiblePosition+g.childCount-1,0) }
        await { field<String>("page")=="work" }
        ui { buttons(activity.window.decorView).first{it.text=="←"}.performClick() }
        await { field<String>("page")=="home"&&field<GridView>("grid").childCount>0&&position()==before }
        instrumentation.waitForIdleSync();assertEquals("item at $index",before.first,position().first);assertEquals("pixel offset at $index",before.second,position().second)
    }
    @Test fun topMiddleBottomAndRepeatedReturnsKeepExactOffset() {
        for(index in listOf(0,36,96,44,20)) roundTrip(index,-42)
    }
    @Test fun threeColumnsAndSortKeepNavigationAndNewActivityStartsAtTop() {
        library.columns=3;library.sort="manual";invoke("showLibrary");await{field<GridView>("grid").numColumns==3&&field<GridView>("grid").childCount>0}
        for(index in listOf(0,36,96,60)) roundTrip(index,-63)
        assertEquals(3,library.columns);assertEquals("manual",library.sort)
        // A new activity owns fresh transient state, independent of the previous activity.
        ui { val fresh=MainActivity();val states=MainActivity::class.java.getDeclaredField("gridPositions").apply{isAccessible=true}.get(fresh) as Map<*,*>;assertTrue(states.isEmpty()) }
    }
    private class Sandbox(base:Context):ContextWrapper(base) {
        private val root=File(base.cacheDir,"home-scroll-${System.nanoTime()}").apply{mkdirs()}
        override fun getFilesDir()=File(root,"files").apply{mkdirs()}
        override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
        override fun getDatabasePath(name:String)=File(root,name)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
    }
}
