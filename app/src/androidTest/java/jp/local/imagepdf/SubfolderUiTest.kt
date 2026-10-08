package jp.local.imagepdf

import android.app.AlertDialog
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
import java.util.concurrent.atomic.AtomicBoolean

/** Exercise the real activity against isolated fixtures, never the user's library. */
class SubfolderUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private lateinit var activity:MainActivity
    private lateinit var library:Library
    private lateinit var a:String;private lateinit var b:String;private lateinit var x:String;private lateinit var y:String;private lateinit var p:String;private lateinit var nested:String
    private lateinit var source:File
    private fun ui(block:()->Unit)=instrumentation.runOnMainSync(block)
    @Suppress("UNCHECKED_CAST") private fun <T> field(name:String):T=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity) as T
    private fun set(name:String,value:Any?) { MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(activity,value) }
    private fun invoke(name:String) { ui { MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(activity) } }
    private fun await(block:()->Boolean) { val end=SystemClock.uptimeMillis()+8000;while(SystemClock.uptimeMillis()<end) { var ok=false;ui { ok=block() };if(ok) return;SystemClock.sleep(40) };fail("Timed out") }
    private fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList())
    private fun hasButton(label:String)=descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text==label }
    private fun click(label:String) { ui { descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text==label }.performClick() };instrumentation.waitForIdleSync() }
    private fun open(id:String) { ui { val grid=field<GridView>("grid");val items=grid.adapter;val position=(0 until items.count).first { (items.getItem(it) as Pair<*,*>).first==id };grid.performItemClick(grid.getChildAt(position-grid.firstVisiblePosition),position,0) };instrumentation.waitForIdleSync() }
    private fun pdf(work:String,name:String)=library.import(listOf(Library.Source(Uri.fromFile(source),name,source.length())),work,null,AtomicBoolean(false),{Library.Conflict.RENAME},{_,_,_->}).single()
    private fun moveDialog(ids:List<String>):AlertDialog {
        ui { MainActivity::class.java.getDeclaredMethod("moveDialog",List::class.java,String::class.java).apply { isAccessible=true }.invoke(activity,ids,null) }
        return latestDialog()
    }
    // Activity's dialog windows are visible through the platform WindowManager roots.
    private fun latestDialog():AlertDialog {
        // Hook the dialog through its DecorView callback (Dialog implements Window.Callback).
        instrumentation.waitForIdleSync()
        val global=Class.forName("android.view.WindowManagerGlobal").getDeclaredMethod("getInstance").invoke(null)
        val views=global.javaClass.getDeclaredField("mViews").apply { isAccessible=true }.get(global) as List<*>
        return views.filterIsInstance<View>().asReversed().mapNotNull { view ->
            val window=runCatching { view.javaClass.getDeclaredField("mWindow").apply { isAccessible=true }.get(view) as Window }.getOrNull()
            window?.callback as? AlertDialog
        }.first()
    }
    private fun browse(dialog:AlertDialog,index:Int) { ui { val list=descendants(dialog.window!!.decorView).filterIsInstance<ListView>().first();list.performItemClick(list.getChildAt(index),index,index.toLong()) };instrumentation.waitForIdleSync() }
    @Before fun setup() {
        source=File(context.cacheDir,"subfolder-ui-fixture.pdf")
        val pdf=PdfDocument();try { repeat(6) { val page=pdf.startPage(PdfDocument.PageInfo.Builder(600,900,it+1).create());pdf.finishPage(page) };source.outputStream().use { pdf.writeTo(it) } } finally { pdf.close() }
        activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(source)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ui { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) }
        invoke("closeReader");instrumentation.waitForIdleSync()
        library=Library(Sandbox(context));library.onboarding=true;library.saveSettings(Preferences(reopen=false))
        a=library.createWork("A");b=library.createWork("B");x=library.createWork("X",a);y=library.createWork("Y",b);p=pdf(a,"1.pdf");nested=pdf(x,"2.pdf")
        ui { set("library",library) };invoke("showLibrary");await { field<GridView>("grid").childCount>0 }
    }
    @After fun close() { ui { activity.finish() };instrumentation.waitForIdleSync() }
    @Test fun cardsCountsRestrictedControlsAndParentBackNavigation() {
        assertEquals(2,field<GridView>("grid").count)
        open(a);assertEquals(2,field<GridView>("grid").count);assertTrue(hasButton("並び替え"));assertTrue(hasButton("追加"))
        ui { val grid=field<GridView>("grid");val labels=descendants(grid).filterIsInstance<TextView>().map { it.text.toString() };assertTrue(labels.contains("1冊"));assertTrue(labels.contains("6ページ")) }
        open(x);assertFalse(hasButton("並び替え"));assertTrue(hasButton("PDFを追加"));assertEquals(1,field<GridView>("grid").count)
        open(nested);await { field<ReaderView?>("reader")!=null };ui { activity.onBackPressed() };await { field<ReaderView?>("reader")==null }
        assertEquals(x,field<String>("workId"));click("←");assertEquals(a,field<String>("workId"));click("←");assertEquals("home",field<String>("page"))
    }
    @Test fun destinationTreeFinalConfirmationCancelAndMove() {
        browse(moveDialog(listOf(p)),1);val parentDialog=latestDialog();assertEquals("ここに移動",parentDialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        browse(parentDialog,0);val destination=latestDialog();ui { destination.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        val confirm=latestDialog();assertTrue(descendants(confirm.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("ここに移動しますか？") })
        assertEquals(a,library.book(p)!!.work);ui { confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() };assertEquals(a,library.book(p)!!.work)
        ui { destination.getButton(AlertDialog.BUTTON_POSITIVE).performClick() };val final=latestDialog();ui { final.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        await { library.book(p)!!.work==y }
    }
    @Test fun mixedSelectionDestinationHidesSubfoldersAndMoveActionExcludesRoots() {
        open(a);val dialog=moveDialog(listOf(p,x));browse(dialog,1);val root=latestDialog()
        ui { val list=descendants(root.window!!.decorView).filterIsInstance<ListView>().first();assertEquals(0,list.count);root.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
        assertEquals(a,library.work(x).parent);assertEquals(a,library.book(p)!!.work)
        // Actual long-press selection must expose the existing action menu plus Move.
        ui { val grid=field<GridView>("grid");grid.onItemLongClickListener!!.onItemLongClick(grid,grid.getChildAt(0),0,0) }
        click("操作");val menu=latestDialog();assertTrue((0 until menu.listView.count).map { menu.listView.getItemAtPosition(it).toString() }.contains("移動"));ui { menu.dismiss() }
        invoke("showLibrary");click("←")
        ui { val grid=field<GridView>("grid");grid.onItemLongClickListener!!.onItemLongClick(grid,grid.getChildAt(0),0,0) }
        click("操作");val homeMenu=latestDialog();assertFalse((0 until homeMenu.listView.count).map { homeMenu.listView.getItemAtPosition(it).toString() }.contains("移動"));ui { homeMenu.dismiss() }
        library.sort="manual";library.reorder(true,listOf(b,a));invoke("showLibrary");click("検索")
        val search=field<GridView>("grid").adapter;val roots=(0 until search.count).map { (search.getItem(it) as Pair<*,*>).first }.filter { it==a||it==b }
        assertEquals(listOf(b,a),roots)
    }
    @Test fun creationUiOnlyInParentAndDeleteExplicitlyNamesContainedPdfs() {
        open(a);click("追加");val menu=latestDialog();assertEquals("新しい作品フォルダ",menu.listView.getItemAtPosition(0));browse(menu,0)
        val input=latestDialog();ui { descendants(input.window!!.decorView).filterIsInstance<EditText>().single().setText("New");input.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        await { library.children(a).any { it.name=="New" } };open(x);click("PDFを追加");val subMenu=latestDialog();assertFalse((0 until subMenu.listView.count).map { subMenu.listView.getItemAtPosition(it).toString() }.any { it.contains("新しい") });ui { subMenu.dismiss() };click("←")
        ui { val grid=field<GridView>("grid");val index=(0 until grid.count).first { (grid.adapter.getItem(it) as Pair<*,*>).first==x };grid.onItemLongClickListener!!.onItemLongClick(grid,grid.getChildAt(index),index,0) };click("操作");browse(latestDialog(),0)
        val confirm=latestDialog();assertTrue(descendants(confirm.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("PDFも削除")&&it.text.contains("X") });ui { confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() };assertTrue(library.file(nested).exists())
    }
    @Test fun directImportSelectionRenameAndAdjacentPdfUseSubfolderNameOrder() {
        open(a);open(x)
        val src=listOf(Library.Source(Uri.fromFile(source),"10.pdf",source.length()))
        ui { set("importTarget",x);MainActivity::class.java.getDeclaredMethod("chooseTarget",List::class.java,Uri::class.java).apply { isAccessible=true }.invoke(activity,src,null) }
        await { library.books(x).size==2&&field<GridView>("grid").count==2 }
        val ten=library.books(x).first { it.name=="10.pdf" }.id;library.position(ten,3,.4f,false)
        open(nested);await { field<ReaderView?>("reader")!=null }
        ui { MainActivity::class.java.getDeclaredMethod("switchBook",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,1) }
        await { field<Book?>("reading")?.id==ten };assertEquals(3,field<Book>("reading").page)
        ui { activity.onBackPressed() };await { field<ReaderView?>("reader")==null }
        ui { val grid=field<GridView>("grid");grid.onItemLongClickListener!!.onItemLongClick(grid,grid.getChildAt(0),0,0);grid.performItemClick(grid.getChildAt(1),1,1) }
        assertEquals(2,field<Set<String>>("selected").size);invoke("showLibrary")
        ui { val grid=field<GridView>("grid");val index=(0 until grid.count).first { (grid.adapter.getItem(it) as Pair<*,*>).first==ten };grid.onItemLongClickListener!!.onItemLongClick(grid,grid.getChildAt(index),index,0) }
        click("操作");val menu=latestDialog();val renameIndex=(0 until menu.listView.count).first { menu.listView.getItemAtPosition(it)=="名前変更" };browse(menu,renameIndex)
        val input=latestDialog();ui { descendants(input.window!!.decorView).filterIsInstance<EditText>().single().setText("1.pdf");input.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        await { library.book(ten)!!.name=="1.pdf" };assertEquals(ten,(field<GridView>("grid").adapter.getItem(0) as Pair<*,*>).first)
        assertEquals(3,library.book(ten)!!.page);assertTrue(library.file(ten).exists())
    }
    private class Sandbox(base:Context):ContextWrapper(base) {
        private val root=File(base.cacheDir,"subfolder-ui-${System.nanoTime()}").apply { mkdirs() }
        override fun getFilesDir()=File(root,"files").apply { mkdirs() }
        override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
        override fun getDatabasePath(name:String)=File(root,name)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
    }
}
