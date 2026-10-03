package jp.local.imagepdf

import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Only its own generated work is removed; existing user library is preserved. */
class MainFlowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private lateinit var library: Library;private lateinit var activity: MainActivity
    private lateinit var previous: Preferences;private var onboard=false;private var last: String?=null
    private lateinit var work: String;private lateinit var books: List<String>
    @Before fun setup() {
        library=Library(context);previous=library.settings();onboard=library.onboarding;last=library.last;library.saveSettings(previous.copy(reopen=false));library.onboarding=true
        work=library.createWork("動作検証 ${SystemClock.uptimeMillis()}");val file=File(context.cacheDir,"flow-test.pdf");val pdf=PdfDocument()
        try { for(i in 0..11) { val p=pdf.startPage(PdfDocument.PageInfo.Builder(600,900,i+1).create());p.canvas.drawColor(Color.rgb(i*18,40,75));pdf.finishPage(p) };file.outputStream().use { pdf.writeTo(it) } } finally { pdf.close() }
        books=library.import(listOf("1.pdf","2.pdf").map { Library.Source(Uri.fromFile(file),it,file.length()) },work,null,AtomicBoolean(false),{Library.Conflict.RENAME},{_,_,_->})
        activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    }
    @After fun close() { ui { activity.finish() };instrumentation.waitForIdleSync();library.delete(true,listOf(work));library.saveSettings(previous);library.onboarding=onboard;library.last=last;library.close() }
    private fun ui(action:()->Unit)=instrumentation.runOnMainSync(action)
    @Suppress("UNCHECKED_CAST") private fun <T> field(name: String): T=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity) as T
    private fun invoke(name: String) { ui { MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(activity) } }
    private fun await(condition:()->Boolean) { val end=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<end) { var ok=false;ui { ok=condition() };if(ok) return;SystemClock.sleep(30) };fail("Timed out") }
    private fun descendants(view: View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList())
    @Test fun overlayNeverResizesAndBackReturnsToWork() {
        ui { MainActivity::class.java.getDeclaredMethod("openBook",Book::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,library.book(books[0]),false) }
        await { field<ReaderView?>("reader")?.height?:0>0 };val reader=field<ReaderView>("reader");val w=reader.width;val h=reader.height
        invoke("toggleReaderUi");SystemClock.sleep(120);assertEquals(w,reader.width);assertEquals(h,reader.height)
        invoke("toggleReaderUi");assertEquals(w,reader.width);assertEquals(h,reader.height)
        ui { activity.onBackPressed() };assertNull(field<ReaderView?>("reader"))
    }
    @Test fun searchStateSurvivesOpeningPdfAndSwitchUsesSavedPosition() {
        ui { descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text=="検索" }.performClick();descendants(activity.window.decorView).filterIsInstance<EditText>().single().setText("1.pdf") }
        assertEquals("search",field<String>("page"));assertEquals("1.pdf",field<String>("query"))
        ui { MainActivity::class.java.getDeclaredMethod("openBook",Book::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,library.book(books[0]),false) }
        library.position(books[1],3,.4f,false)
        ui { MainActivity::class.java.getDeclaredMethod("switchBook",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,1) }
        await { field<Book?>("reading")?.id==books[1] }
        ui { activity.onBackPressed() };assertEquals("search",field<String>("page"));assertEquals("1.pdf",field<String>("query"));assertEquals("1.pdf",descendants(activity.window.decorView).filterIsInstance<EditText>().single().text.toString())
    }
}
