package jp.local.imagepdf

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.*
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** One renderer, one worker. A queued request always reads the latest viewport. */
class PdfSession(private val open:()->ParcelFileDescriptor,private val cacheDir: File,private val changed:()->Unit,private val failed:(Throwable)->Unit) {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var renderer: PdfRenderer?=null
    @Volatile var ratios=FloatArray(0); private set
    private val closed=AtomicBoolean(false)
    private var scheduled=false
    private var generation=0
    private var wanted=listOf<Pair<Int,Int>>()
    private var previewWanted: Int?=null
    private var frozen=false
    private var idlePage=0
    private val memoryLimit=minOf(48*1024*1024,(Runtime.getRuntime().maxMemory()/8).toInt())
    // Accessed exclusively on UI thread; evictions are left to GC, never recycled while Canvas uses them.
    private val pages=object: LruCache<String,Bitmap>(memoryLimit) { override fun sizeOf(key: String,value: Bitmap)=value.allocationByteCount }
    private val previews=object: LruCache<Int,Bitmap>(4*1024*1024) { override fun sizeOf(key: Int,value: Bitmap)=value.allocationByteCount }
    init {
        cacheDir.mkdirs()
        worker.execute { try {
            val descriptor=open()
            try { renderer=PdfRenderer(descriptor) } catch(e: Throwable) { descriptor.close();throw e }
            val r=renderer!!;val values=FloatArray(r.pageCount)
            for(i in values.indices) { if(closed.get()) return@execute; r.openPage(i).use { values[i]=it.height.toFloat()/it.width } }
            ratios=values;main.post { if(!closed.get()) changed() }
        } catch(e: Throwable) { main.post { if(!closed.get()) failed(e) } } }
    }
    fun bitmap(page: Int,width: Int)=pages.get("$page:$width")
    fun preview(page: Int)=previews.get(page)
    fun request(visible: List<Pair<Int,Int>>,preview: Int?,freeze: Boolean) {
        if(closed.get()||ratios.isEmpty()) return
        var bytes=0L
        val budgeted=visible.takeWhile { (page,width) ->
            val pixels=min(width.toDouble()*width*ratios[page],min(4_000_000,memoryLimit/16).toDouble())
            bytes+=(pixels*4).toLong();bytes<=memoryLimit
        }
        if(wanted!=budgeted||previewWanted!=preview||frozen!=freeze) { generation++;wanted=budgeted;previewWanted=preview;frozen=freeze }
        pump()
    }
    private fun pump() {
        if(scheduled||closed.get()||ratios.isEmpty()) return
        val high=if(frozen) null else wanted.firstOrNull { bitmap(it.first,it.second)==null }
        val low=previewWanted?.takeIf { preview(it)==null } ?: wanted.firstOrNull()?.first?.let { current -> listOf(current,current-1,current+1).firstOrNull { it in ratios.indices && preview(it)==null } }
        var idle: Int?=null
        if(high==null&&low==null&&!frozen) {
            while(idlePage<ratios.size && File(cacheDir,"${idlePage}.png").exists()) idlePage++
            if(idlePage<ratios.size && (cacheDir.listFiles()?.sumOf { it.length() }?:0L)<128L*1024*1024) idle=idlePage++
        }
        val page=high?.first?:low?:idle?:return
        val width=high?.second?:240;val token=generation;val highQuality=high!=null;scheduled=true
        worker.execute {
            var result: Bitmap?=null
            try {
                if(!closed.get()) {
                    val cached=File(cacheDir,"$page.png")
                    result=if(!highQuality&&cached.exists()) BitmapFactory.decodeFile(cached.path) else render(page,width)
                    if(!highQuality&&result!=null&&!cached.exists()&&!closed.get()) cached.outputStream().use { result!!.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
            } catch(e: Throwable) { android.util.Log.w("PdfSession","Rendering page $page",e) }
            main.post {
                scheduled=false
                if(closed.get()) { result?.recycle();return@post }
                if(result!=null) {
                    if(highQuality) { if(token==generation) pages.put("$page:$width",result) else result!!.recycle() }
                    else previews.put(page,result)
                }
                changed()
                main.postDelayed({ pump() },if(highQuality||low!=null) 0 else 80)
            }
        }
    }
    private fun render(index: Int,width: Int): Bitmap {
        val r=renderer?:error("closed")
        return r.openPage(index).use { p ->
            val ratio=p.height.toFloat()/p.width
            val pixelBudget=min(4_000_000,memoryLimit/16).toFloat()
            val actualWidth=min(width.toFloat(),sqrt(pixelBudget/ratio)).toInt().coerceIn(1,4096)
            val h=(actualWidth*ratio).toInt().coerceIn(1,8192)
            val bitmap=Bitmap.createBitmap(actualWidth,h,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE)
            try { p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);bitmap } catch(e: Throwable) { bitmap.recycle();throw e }
        }
    }
    fun close() { if(!closed.compareAndSet(false,true)) return;pages.evictAll();previews.evictAll();worker.execute { renderer?.close();renderer=null;cacheDir.deleteRecursively() };worker.shutdown() }
}
