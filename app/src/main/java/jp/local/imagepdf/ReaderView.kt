package jp.local.imagepdf

import android.content.Context
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.OverScroller
import java.io.File
import kotlin.math.*

class ReaderView(context: Context,open:()->ParcelFileDescriptor,private val settings: Preferences,private val initialPage: Int,private val initialOffset: Float,private val position:(Int,Float,Boolean)->Unit,private val toggleUi:()->Unit,private val fastStart:()->Unit,private val switchPdf:(Int)->Unit,private val error:(Throwable)->Unit) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val main=Handler(Looper.getMainLooper());private val scroller=OverScroller(context)
    private var starts=FloatArray(0);private var total=0f
    private var y=0f;private var x=0f;var zoom=1f; private set
    private var initialized=false;private var finished=false;private var leaving=false
    private var checkpointAt=SystemClock.uptimeMillis()
    private val session=PdfSession(open,File(context.cacheDir,"fast-${java.util.UUID.randomUUID()}"),{ geometry();invalidate() },error)
    private val dp=resources.displayMetrics.density
    private var fast=false;private var edge=false;private var target=0;private var thumbY=0f
    private var downX=0f;private var downY=0f;private var lastX=0f;private var lastY=0f;private var moved=false
    private var two=false;private var twoX=0f;private var twoY=0f;private var twoEndX=0f;private var twoEndY=0f
    private var velocity: VelocityTracker?=null
    private var keyDownAt=0L;private var keyCode=0;private var held=false;private var lastFrame=0L
    private val save=Runnable { persist() }
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop
    private var tapEligible=false;private var doubleTapped=false
    private var visiblePage=0
    var pageDisplay: (Int,Int)->Unit = { _,_ -> }
    private var fastOriginPage=0;private var fastOriginY=0f
    private var edgeDirection=0;private var edgeSince=0L;private var edgeFrameAt=0L;private var edgeFraction=0f
    private fun fastTop()=height*.3f
    private fun fastBottom()=height*.5f
    private fun fastHoldSpeed(elapsed: Long): Float { val t=(elapsed/3000f).coerceIn(0f,1f);return 5f+15f*t*t*(3-2*t) }
    private val fastFrame=object: Runnable { override fun run() {
        if(!fast||edgeDirection==0) return
        val now=SystemClock.uptimeMillis()
        edgeFraction+=fastHoldSpeed(now-edgeSince)*(now-edgeFrameAt)/1000f;edgeFrameAt=now
        val steps=edgeFraction.toInt();edgeFraction-=steps
        if(steps>0) { target=(target+edgeDirection*steps).coerceIn(starts.indices);invalidate() }
        main.postDelayed(this,16)
    } }
    private fun stopFastHold() { edgeDirection=0;main.removeCallbacks(fastFrame);edgeFraction=0f }
    private val detector=GestureDetector(context,object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent)=true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if(tapEligible) performClick();tapEligible=false;return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { doubleTapped=true;tapEligible=false;if(settings.zoom) { changeZoom(if(zoom==1f) 2f else 1f,e.x,e.y);return true };return false }
    })
    init { detector.setIsLongpressEnabled(false);setBackgroundColor(Color.rgb(40,40,43));isFocusable=true;contentDescription="PDF。縦スクロール、ダブルタップで拡大。右端をドラッグしてページ移動。" }
    private fun geometry() {
        if(width==0||session.ratios.isEmpty()) return
        if(starts.size==session.ratios.size) return
        starts=FloatArray(session.ratios.size);total=0f
        for(i in starts.indices) { starts[i]=total;total+=width*session.ratios[i]+settings.gap*dp }
        total-=settings.gap*dp
        if(!initialized) { initialized=true;y=starts[initialPage.coerceIn(starts.indices)]+initialOffset*width*session.ratios[initialPage.coerceIn(starts.indices)];clamp();notifyPosition() }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { starts=FloatArray(0);geometry() }
    private fun pageAt(offset: Float): Int { if(starts.isEmpty()) return 0;var lo=0;var hi=starts.lastIndex;while(lo<hi) { val mid=(lo+hi+1)/2;if(starts[mid]<=offset) lo=mid else hi=mid-1 };return lo }
    private fun maxY()=max(0f,max(total*zoom-height,(starts.lastOrNull()?:0f)*zoom))
    private fun clamp() { y=y.coerceIn(0f,maxY());x=x.coerceIn(0f,max(0f,width*zoom-width)) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);geometry()
        if(starts.isEmpty()) { spinner(canvas,width/2f,height/2f);postInvalidateDelayed(32);return }
        val first=pageAt(y/zoom);val end=pageAt((y+height)/zoom);val renderWidth=(width*zoom).toInt();val visible=mutableListOf<Pair<Int,Int>>()
        for(i in first..end) {
            val top=starts[i]*zoom-y;val h=width*session.ratios[i]*zoom
            val bitmap=session.bitmap(i,renderWidth)
            if(bitmap!=null) canvas.drawBitmap(bitmap,null,RectF(-x,top,width*zoom-x,top+h),paint)
            else { spinner(canvas,width/2f,(top+h/2).coerceIn(25*dp,height-25*dp));postInvalidateDelayed(32) }
            visible.add(i to renderWidth)
        }
        if(!fast) { if(end+1<starts.size) visible.add(end+1 to renderWidth);if(first>0) visible.add(first-1 to renderWidth) }
        session.request(visible,if(fast) target else null,fast)
        paint.color=Color.rgb(210,210,215);paint.strokeWidth=3*dp
        canvas.drawLine(width-3*dp,fastTop(),width-3*dp,fastBottom(),paint)
        if(fast) {
            paint.color=Color.WHITE;canvas.drawRect(width-7*dp,thumbY-16*dp,width.toFloat(),thumbY+16*dp,paint)
            val label="${target+1} / ${starts.size}";paint.textSize=16*dp;val tw=paint.measureText(label);paint.color=0xCC202024.toInt();canvas.drawRoundRect(RectF((width-tw)/2-10*dp,32*dp,(width+tw)/2+10*dp,66*dp),4*dp,4*dp,paint);paint.color=Color.WHITE;canvas.drawText(label,(width-tw)/2,55*dp,paint)
            val pw=width*.3f;val ph=min(pw*session.ratios[target],height*.65f);val right=width-24*dp;val top=(thumbY-ph/2).coerceIn(height*.05f,max(height*.05f,height*.95f-ph));val rect=RectF(right-pw,top,right,top+ph)
            paint.color=0xFF252529.toInt();canvas.drawRect(rect,paint);val p=session.preview(target)
            if(p!=null) { val scale=min(pw/p.width,ph/p.height);val bw=p.width*scale;val bh=p.height*scale;canvas.drawBitmap(p,null,RectF(rect.centerX()-bw/2,rect.centerY()-bh/2,rect.centerX()+bw/2,rect.centerY()+bh/2),paint) } else spinner(canvas,rect.centerX(),rect.centerY())
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp;paint.color=Color.LTGRAY;canvas.drawRect(rect,paint);paint.style=Paint.Style.FILL
        }
    }
    private fun spinner(c: Canvas,cx: Float,cy: Float) { paint.color=Color.LTGRAY;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*dp;val r=9*dp;c.drawArc(RectF(cx-r,cy-r,cx+r,cy+r),(SystemClock.uptimeMillis()%1000)*.36f,95f,false,paint);paint.style=Paint.Style.FILL }
    private fun changeZoom(value: Float,focusX: Float=width/2f,focusY: Float=height/2f) { scroller.forceFinished(true);val documentX=(x+focusX)/zoom;val documentY=(y+focusY)/zoom;zoom=value;x=documentX*zoom-focusX;y=documentY*zoom-focusY;clamp();invalidate();notifyPosition() }
    private fun updateFast(touchY: Float) {
        thumbY=touchY.coerceIn(fastTop(),fastBottom())
        val direction=if(touchY<=fastTop()) -1 else if(touchY>=fastBottom()) 1 else 0
        if(edgeDirection!=0) { if(direction==edgeDirection) { invalidate();return };stopFastHold();fastOriginPage=target;fastOriginY=touchY }
        val relative=(fastOriginPage+(touchY-fastOriginY)/(10*dp)).coerceIn(0f,starts.lastIndex.toFloat())
        if(abs(relative-target)>.6f) target=relative.roundToInt().coerceIn(starts.indices)
        if(direction!=0) { edgeDirection=direction;edgeSince=SystemClock.uptimeMillis();edgeFrameAt=edgeSince;edgeFraction=0f;main.postDelayed(fastFrame,16) }
        invalidate()
    }
    private fun updateVisiblePage() {
        if(starts.isEmpty()) return
        var best=visiblePage.coerceIn(starts.indices);var area=-1f;var previousArea=0f
        val horizontal=max(0f,min(width.toFloat(),width*zoom-x)-max(0f,-x))
        for(i in pageAt(y/zoom)..pageAt((y+height)/zoom)) {
            val top=starts[i]*zoom-y
            val a=max(0f,min(height.toFloat(),top+width*session.ratios[i]*zoom)-max(0f,top))*horizontal
            if(i==visiblePage) previousArea=a
            if(a>area) { area=a;best=i }
        }
        // Keep the previous page within a one-pixel intersection-area tie.
        if(previousArea>0f&&area-previousArea<=horizontal) best=visiblePage
        visiblePage=best;pageDisplay(best+1,starts.size)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { stopVolume();scroller.forceFinished(true);downX=e.x;lastX=e.x;downY=e.y;lastY=e.y;moved=false;doubleTapped=false;two=false;edge=e.x>=width-20*dp&&e.y>=fastTop()&&e.y<=fastBottom();velocity?.recycle();velocity=VelocityTracker.obtain();velocity?.addMovement(e);if(!edge) detector.onTouchEvent(e);return true }
            MotionEvent.ACTION_POINTER_DOWN -> { if(e.pointerCount==2&&!edge&&zoom==1f) { two=true;twoX=(e.getX(0)+e.getX(1))/2;twoY=(e.getY(0)+e.getY(1))/2;twoEndX=twoX;twoEndY=twoY;detector.onTouchEvent(MotionEvent.obtain(e).apply { action=MotionEvent.ACTION_CANCEL }) };return true }
            MotionEvent.ACTION_MOVE -> {
                if(edge) { if(!fast&&abs(e.y-downY)>touchSlop&&starts.isNotEmpty()) { updateVisiblePage();fastOriginPage=visiblePage;fastOriginY=downY;target=fastOriginPage;if(zoom!=1f) changeZoom(1f);fast=true;tapEligible=false;fastStart() };if(fast) updateFast(e.y);return true }
                if(two) { if(e.pointerCount>=2) { twoEndX=(e.getX(0)+e.getX(1))/2;twoEndY=(e.getY(0)+e.getY(1))/2 };return true }
                velocity?.addMovement(e);detector.onTouchEvent(e)
                if(hypot(e.x-downX,e.y-downY)>touchSlop) { moved=true;tapEligible=false }
                if(moved) { y+=lastY-e.y;if(zoom>1f) x+=lastX-e.x;clamp();invalidate();notifyPosition() };lastX=e.x;lastY=e.y;return true
            }
            MotionEvent.ACTION_POINTER_UP -> { if(two&&e.pointerCount==2) { twoEndX=(e.getX(0)+e.getX(1))/2;twoEndY=(e.getY(0)+e.getY(1))/2 };return true }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                if(edge) { stopFastHold();if(fast) { fast=false;if(e.actionMasked==MotionEvent.ACTION_UP) { y=starts[target];clamp();notifyPosition();persist() };invalidate() };edge=false }
                else if(two) { val dx=twoEndX-twoX;val dy=twoEndY-twoY;if(e.actionMasked==MotionEvent.ACTION_UP&&abs(dx)>=width*.2f&&abs(dx)>abs(dy)) switchPdf(if(dx<0) 1 else -1);two=false }
                else { tapEligible=e.actionMasked==MotionEvent.ACTION_UP&&!moved&&!doubleTapped&&hypot(e.x-downX,e.y-downY)<=touchSlop&&e.eventTime-e.downTime>=250;detector.onTouchEvent(e);if(moved&&e.actionMasked==MotionEvent.ACTION_UP) { velocity?.addMovement(e);velocity?.computeCurrentVelocity(1000);scroller.fling(x.toInt(),y.toInt(),if(zoom>1f) -(velocity?.xVelocity?:0f).toInt() else 0,-(velocity?.yVelocity?:0f).toInt(),0,max(0f,width*zoom-width).toInt(),0,maxY().toInt());postInvalidateOnAnimation() };notifyPosition() }
                velocity?.recycle();velocity=null;return true
            }
        };return true
    }
    override fun computeScroll() { if(scroller.computeScrollOffset()) { x=scroller.currX.toFloat();y=scroller.currY.toFloat();notifyPosition();postInvalidateOnAnimation() } }
    override fun performClick(): Boolean { super.performClick();toggleUi();return true }
    private fun notifyPosition() { if(starts.isEmpty()||leaving) return;updateVisiblePage();if(starts.last()<(y+height)/zoom) finished=true;if(SystemClock.uptimeMillis()-checkpointAt>=1000) persist();main.removeCallbacks(save);main.postDelayed(save,350) }
    fun persist() { if(starts.isEmpty()) return;checkpointAt=SystemClock.uptimeMillis();val page=pageAt(y/zoom);val offset=((y/zoom-starts[page])/(width*session.ratios[page])).coerceIn(0f,1f);position(page,offset,finished) }
    private val hold=Runnable { if(keyCode!=0) { held=true;keyDownAt=SystemClock.uptimeMillis();lastFrame=keyDownAt;main.post(volumeFrame) } }
    private val volumeFrame=object: Runnable { override fun run() { if(!held||keyCode==0) return;val now=SystemClock.uptimeMillis();val seconds=((now-keyDownAt)/3000f).coerceIn(0f,1f);val speed=.15f+(settings.speed-.15f)*seconds*seconds*(3-2*seconds);y+=(if(keyCode==KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)*height*speed*((now-lastFrame)/1000f);lastFrame=now;clamp();invalidate();notifyPosition();main.postDelayed(this,16) } }
    fun volume(e: KeyEvent): Boolean {
        if(!settings.volume || e.keyCode !in listOf(KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_VOLUME_DOWN)) return false
        if(e.action==KeyEvent.ACTION_DOWN) { if(e.repeatCount==0) { if(zoom>1) changeZoom(1f);scroller.forceFinished(true);keyCode=e.keyCode;held=false;main.postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong()) } }
        else if(e.action==KeyEvent.ACTION_UP) { val wasHeld=held;stopVolume();if(!wasHeld) { val delta=(height*settings.step/100f*(if(e.keyCode==KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)).toInt();val dest=(y+delta).coerceIn(0f,maxY());scroller.startScroll(0,y.toInt(),0,(dest-y).toInt(),180);postInvalidateOnAnimation() };notifyPosition() }
        return true
    }
    fun stopVolume() { keyCode=0;held=false;main.removeCallbacks(hold);main.removeCallbacks(volumeFrame);stopFastHold() }
    override fun onDetachedFromWindow() { stopFastHold();super.onDetachedFromWindow() }
    fun close() { leaving=true;stopFastHold();stopVolume();main.removeCallbacks(save);persist();session.close() }
}
