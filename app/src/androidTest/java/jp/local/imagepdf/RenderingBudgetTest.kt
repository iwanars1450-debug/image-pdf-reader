package jp.local.imagepdf

import android.graphics.Bitmap
import android.os.*
import android.util.LruCache
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.util.zip.DeflaterOutputStream

class RenderingBudgetTest {
    @Test fun threeHundredImagePagesRemainWithinBitmapBudgetAfterJumps() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val file=File(context.cacheDir,"300-page-image-test.pdf");imagePdf(file,300)
        lateinit var session: PdfSession;var failure: Throwable?=null
        fun ui(action:()->Unit)=instrumentation.runOnMainSync(action)
        fun await(condition:()->Boolean) { val end=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<end) { var ready=false;ui { ready=condition() };if(ready) return;SystemClock.sleep(20) };fail("Render timeout: $failure") }
        ui { session=PdfSession({ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)},File(context.cacheDir,"budget-test-preview"),{}, { failure=it }) }
        try {
            await { session.ratios.size==300 };assertNull(failure)
            for(page in 0 until 300 step 20) {
                ui { session.request(listOf(page to 1080,(page+1) to 1080),page,false) }
                await { session.bitmap(page,1080)!=null }
            }
            ui {
                @Suppress("UNCHECKED_CAST") val pages=PdfSession::class.java.getDeclaredField("pages").apply { isAccessible=true }.get(session) as LruCache<String,Bitmap>
                @Suppress("UNCHECKED_CAST") val previews=PdfSession::class.java.getDeclaredField("previews").apply { isAccessible=true }.get(session) as LruCache<Int,Bitmap>
                assertTrue(pages.size()<=48*1024*1024);assertTrue(pages.size()<=pages.maxSize());assertTrue(pages.snapshot().size<10);assertTrue(previews.size()<=4*1024*1024)
                session.request(listOf(299 to 1080),299,true)
            }
            await { session.preview(299)!=null }
            ui { assertNull(session.bitmap(299,1080));session.request(listOf(299 to 1080),null,false) }
            await { session.bitmap(299,1080)!=null }
        } finally { ui { session.close() };file.delete() }
    }
    /** Many PDF pages refer to an embedded RGB image: raster output still costs ~7MB/page. */
    private fun imagePdf(file: File,count: Int) {
        val out=ByteArrayOutputStream();fun ascii(s: String) { out.write(s.toByteArray(Charsets.US_ASCII)) }
        val offsets=mutableListOf<Int>();fun obj(number: Int,body:()->Unit) { offsets.add(out.size());ascii("$number 0 obj\n");body();ascii("\nendobj\n") }
        val rgb=ByteArray(600*900*3);for(i in rgb.indices step 3) { val pixel=i/3;rgb[i]=(pixel%600/3).toByte();rgb[i+1]=(pixel/600/4).toByte();rgb[i+2]=80 }
        val compressed=ByteArrayOutputStream();DeflaterOutputStream(compressed).use { it.write(rgb) };val image=compressed.toByteArray()
        ascii("%PDF-1.4\n");obj(1) { ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj(2) { ascii("<< /Type /Pages /Count $count /Kids ["+(0 until count).joinToString(" ") { "${it+5} 0 R" }+"] >>") }
        obj(3) { ascii("<< /Type /XObject /Subtype /Image /Width 600 /Height 900 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${image.size} >>\nstream\n");out.write(image);ascii("\nendstream") }
        val commands="q 600 0 0 900 0 0 cm /Img Do Q\n";obj(4) { ascii("<< /Length ${commands.length} >>\nstream\n$commands\nendstream") }
        for(i in 0 until count) obj(i+5) { ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 900] /Resources << /XObject << /Img 3 0 R >> >> /Contents 4 0 R >>") }
        val xref=out.size();ascii("xref\n0 ${offsets.size+1}\n0000000000 65535 f \n");offsets.forEach { ascii("%010d 00000 n \n".format(java.util.Locale.ROOT,it)) };ascii("trailer\n<< /Size ${offsets.size+1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n");file.writeBytes(out.toByteArray())
    }
}
