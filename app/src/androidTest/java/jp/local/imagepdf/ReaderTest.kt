package jp.local.imagepdf

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.*
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File

class ReaderTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private lateinit var reader: ReaderView
    private var switched=0
    private var savedPage=-1;private var savedOffset=0f;private var completed=false
    @Before fun setup() {
        val file=File(context.cacheDir,"reader-test.pdf");val pdf=PdfDocument()
        try { for(i in 0..9) { val p=pdf.startPage(PdfDocument.PageInfo.Builder(600,900,i+1).create());p.canvas.drawColor(Color.rgb(i*20,30,80));pdf.finishPage(p) };file.outputStream().use { pdf.writeTo(it) } } finally { pdf.close() }
        ui { reader=ReaderView(context,{ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)},Preferences(),0,0f,{ p,o,f -> savedPage=p;savedOffset=o;completed=f },{}, {},{ switched=it },{ throw AssertionError(it) });reader.layout(0,0,1080,2200) }
        await { field<FloatArray>("starts").size==10 }
    }
    @After fun close() { ui { reader.close() } }
    private fun ui(action:()->Unit)=instrumentation.runOnMainSync(action)
    @Suppress("UNCHECKED_CAST") private fun <T> field(name: String): T = ReaderView::class.java.getDeclaredField(name).apply { isAccessible=true }.get(reader) as T
    private fun await(condition:()->Boolean) { val end=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<end) { var ok=false;ui { ok=condition() };if(ok) return;SystemClock.sleep(30) };fail("Timed out") }
    private fun touch(action: Int,x: Float,y: Float,time: Long=SystemClock.uptimeMillis(),down: Long=time) { ui { val e=MotionEvent.obtain(down,time,action,x,y,0);reader.onTouchEvent(e);e.recycle() } }
    private fun doubleTap(x: Float=540f,y: Float=1100f) { val t=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,x,y,t,t);touch(MotionEvent.ACTION_UP,x,y,t+30,t);touch(MotionEvent.ACTION_DOWN,x,y,t+90,t+90);touch(MotionEvent.ACTION_UP,x,y,t+120,t+90) }
    @Test fun fastScrollerTapDoesNotJumpAndDragFreezesThenJumps() {
        touch(MotionEvent.ACTION_DOWN,1079f,1700f);touch(MotionEvent.ACTION_UP,1079f,1700f);assertEquals(0f,field<Float>("y"),.01f)
        val t=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,1079f,500f,t,t);touch(MotionEvent.ACTION_MOVE,1079f,900f,t+40,t);assertTrue(field<Boolean>("fast"));val frozen=field<Float>("y")
        touch(MotionEvent.ACTION_MOVE,1079f,2090f,t+80,t);assertEquals(frozen,field<Float>("y"),.01f);touch(MotionEvent.ACTION_UP,1079f,2090f,t+120,t)
        assertEquals(field<FloatArray>("starts").last(),field<Float>("y"),.01f);ui { reader.persist() };assertTrue(completed);assertEquals(9,savedPage)
    }
    @Test fun doubleTapKeepsCenterAndVolumeReturnsToFit() {
        doubleTap();assertEquals(2f,reader.zoom,.001f)
        val center=(field<Float>("y")+1100f)/2
        SystemClock.sleep(400);doubleTap();assertEquals(1f,reader.zoom,.001f);assertEquals(center,field<Float>("y")+1100f,.01f)
        SystemClock.sleep(400);doubleTap();assertEquals(2f,reader.zoom,.001f)
        ui { assertTrue(reader.volume(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN)));assertEquals(1f,reader.zoom,.001f);reader.volume(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_DOWN)) }
        SystemClock.sleep(220);ui { reader.computeScroll();reader.persist() };assertTrue(savedOffset>0)
    }
    @Test fun longVolumePressStopsAtReleaseAndDisabledPassesThrough() {
        ui { reader.volume(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN)) };SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+350L);ui { reader.volume(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_DOWN)) };val y=field<Float>("y");assertTrue(y>0);SystemClock.sleep(120);assertEquals(y,field<Float>("y"),.01f)
    }
    private fun two(action: Int,x: Float,y: Float,t: Long,down: Long) { ui {
        val properties=arrayOf(MotionEvent.PointerProperties().apply { id=0;toolType=MotionEvent.TOOL_TYPE_FINGER },MotionEvent.PointerProperties().apply { id=1;toolType=MotionEvent.TOOL_TYPE_FINGER })
        val coords=arrayOf(MotionEvent.PointerCoords().apply { this.x=x;this.y=y;pressure=1f;size=1f },MotionEvent.PointerCoords().apply { this.x=x+80;this.y=y+30;pressure=1f;size=1f })
        val e=MotionEvent.obtain(down,t,action,2,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);reader.onTouchEvent(e);e.recycle()
    } }
    @Test fun twoFingerHorizontalSwitchRequiresThresholdAndIsDisabledWhenZoomed() {
        fun swipe(dx: Float,dy: Float) { val t=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,750f,900f,t,t);two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),750f,900f,t+20,t);two(MotionEvent.ACTION_MOVE,750f+dx,900f+dy,t+100,t);two(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),750f+dx,900f+dy,t+140,t);touch(MotionEvent.ACTION_UP,750f+dx,900f+dy,t+180,t) }
        swipe(-100f,0f);assertEquals(0,switched);swipe(-300f,500f);assertEquals(0,switched);swipe(-300f,0f);assertEquals(1,switched)
        switched=0;SystemClock.sleep(400);doubleTap();assertEquals(2f,reader.zoom,.001f);swipe(-350f,0f);assertEquals(0,switched)
    }
}
