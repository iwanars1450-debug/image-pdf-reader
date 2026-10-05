package jp.local.imagepdf

import android.app.*
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import android.text.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity: Activity() {
    private lateinit var library: Library
    private val io=Executors.newSingleThreadExecutor()
    private val thumbnails=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private lateinit var host: FrameLayout
    private var page="home";private var workId: String?=null
    private var grid: GridView?=null
    private val gridPositions=mutableMapOf<String,Pair<Int,Int>>()
    private var homeGridState: Parcelable?=null
    private var query="";private var searchTab=0
    private val selected=linkedSetOf<String>()
    private var reader: ReaderView?=null;private var readerFrame: FrameLayout?=null;private var readerBar: View?=null
    private var reading: Book?=null;private var external: Uri?=null
    private var draft: Preferences?=null;private var operation: AtomicBoolean?=null
    private var importTarget: String?=null;private var exportBooks=listOf<Book>();private var exportFolders=false;private var replaceRestore=false
    private val hideBar=Runnable { hideReaderUi() }
    private val dp get()=resources.displayMetrics.density
    private fun d(n: Int)=(n*dp).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        library=Library(this);library.writableDatabase
        host=FrameLayout(this);setContentView(host)
        io.execute { library.collectOrphans() }
        if(!library.onboarding) onboarding() else startLibrary()
    }
    private fun startLibrary() {
        showLibrary()
        if(intent.action==Intent.ACTION_VIEW&&intent.data!=null) { openExternal(intent.data!!);return }
        if(library.settings().reopen) library.last?.let { id -> val b=library.book(id);if(b==null) message("前回のPDFが見つかりません") else { workId=b.work;page="work";showLibrary();openBook(b,false) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);if(intent.action==Intent.ACTION_VIEW) intent.data?.let { if(reader!=null) closeReader();openExternal(it) } }
    private fun message(text: String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    private fun text(value: String,size: Float=16f)=TextView(this).apply { this.text=value;textSize=size;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL }
    private fun button(value: String,action:()->Unit)=Button(this).apply { text=value;isAllCaps=false;setOnClickListener { action() } }
    private fun vertical()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFF18191D.toInt()) }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    private fun toolbar(title: String,back: Boolean=true): LinearLayout = row().apply {
        setPadding(d(4),d(28),d(4),0)
        if(back) addView(button("←") { onBackPressed() },LinearLayout.LayoutParams(d(52),d(52)))
        addView(text(title,20f),LinearLayout.LayoutParams(0,d(56),1f))
    }
    private fun rememberGrid() { grid?.let { gridPositions[gridKey()]=it.firstVisiblePosition to (it.getChildAt(0)?.top?:0);if(page=="home") homeGridState=it.onSaveInstanceState() } }
    private fun gridKey()=if(page=="work") "work:$workId" else if(page=="search") "search:$searchTab" else "home"
    private fun showLibrary() {
        if(isFinishing) return
        selected.clear();draft=null
        host.removeAllViews();val base=vertical();host.addView(base)
        val title=if(page=="work") library.works().find { it.id==workId }?.name?:"作品" else if(page=="search") "検索" else "Image PDF Reader"
        val bar=toolbar(title,page!="home");base.addView(bar)
        if(page=="home") { bar.addView(button("検索") { rememberGrid();page="search";showLibrary() });bar.addView(button("︙") { PopupMenu(this,bar).apply { menu.add("設定");setOnMenuItemClickListener { rememberGrid();showSettings();true };show() } }) }
        if(page=="search") {
            val edit=EditText(this).apply { hint="作品名・PDF名";setSingleLine();setText(query);setTextColor(Color.WHITE) };base.addView(edit)
            val tabs=row();listOf("すべて","作品","PDF").forEachIndexed { i,label -> tabs.addView(button((if(i==searchTab) "● " else "")+label) { rememberGrid();searchTab=i;showLibrary() },LinearLayout.LayoutParams(0,d(48),1f)) };base.addView(tabs)
            edit.addTextChangedListener(object: TextWatcher { override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {} ;override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { query=s.toString();gridPositions.remove(gridKey());fillGrid() };override fun afterTextChanged(e: Editable?) {} })
        } else {
            val controls=row()
            controls.addView(button("並び替え") { sortDialog() },LinearLayout.LayoutParams(0,d(48),1f))
            controls.addView(button("列数") { rememberGrid();if(page=="home") library.columns=if(library.columns==2) 3 else 2 else { val w=library.works().first { it.id==workId };library.workOptions(w.id,if(w.columns==2) 3 else 2,w.sort,w.descending) };showLibrary() })
            if(page=="work") controls.addView(button("PDFを追加") { importTarget=workId;addMenu(false) })
            base.addView(controls)
        }
        val selectionBar=row();selectionBar.visibility=View.GONE;base.addView(selectionBar)
        grid=GridView(this).apply { horizontalSpacing=d(8);verticalSpacing=d(12);setPadding(d(12),d(8),d(12),d(20));clipToPadding=false;stretchMode=GridView.STRETCH_COLUMN_WIDTH;numColumns=if(page=="work") library.works().find { it.id==workId }?.columns?:2 else library.columns }
        base.addView(grid,LinearLayout.LayoutParams(-1,0,1f));base.setPadding(0,0,0,d(24));fillGrid()
        grid?.setOnItemClickListener { _,_,position,_ -> val item=(grid!!.adapter as Cards).items[position];val id=item.first
            if(selected.isNotEmpty()) { if(!selected.add(id)) selected.remove(id);updateSelection(selectionBar);(grid!!.adapter as Cards).notifyDataSetChanged() }
            else { rememberGrid();val w=library.works().find { it.id==id };if(w!=null) { workId=id;page="work";showLibrary() } else library.book(id)?.let { openBook(it,false) } }
        }
        grid?.setOnItemLongClickListener { _,_,position,_ -> val item=(grid!!.adapter as Cards).items[position]
            if(currentSort()=="manual"&&page!="search") { AlertDialog.Builder(this).setItems(arrayOf("ドラッグして並べ替え","選択 / 名前変更 / 削除")) { _,which -> if(which==0) { val clip=ClipData.newPlainText("order",item.first);grid!!.getChildAt(position-grid!!.firstVisiblePosition)?.startDragAndDrop(clip,View.DragShadowBuilder(grid!!.getChildAt(position-grid!!.firstVisiblePosition)),item.first,0) } else { selected.add(item.first);updateSelection(selectionBar);(grid!!.adapter as Cards).notifyDataSetChanged() } }.show() }
            else { selected.add(item.first);updateSelection(selectionBar);(grid!!.adapter as Cards).notifyDataSetChanged() };true
        }
        grid?.setOnDragListener { v,event -> when(event.action) { DragEvent.ACTION_DRAG_STARTED -> true;DragEvent.ACTION_DRAG_LOCATION -> { val g=v as GridView;if(event.y<d(60)) g.smoothScrollBy(-d(50),100) else if(event.y>g.height-d(60)) g.smoothScrollBy(d(50),100);true };DragEvent.ACTION_DROP -> { val g=v as GridView;val to=g.pointToPosition(event.x.toInt(),event.y.toInt());val adapter=g.adapter as Cards;val from=adapter.items.indexOfFirst { it.first==event.localState as? String };if(from>=0&&to>=0) { val ids=adapter.items.map { it.first }.toMutableList();val key=ids.removeAt(from);ids.add(to.coerceAtMost(ids.size),key);library.reorder(page=="home",ids);rememberGrid();showLibrary() };true };else -> true } }
        if(page=="home") gridPositions["home"]?.let { (index,top) ->
            val homeGrid=grid!!
            homeGridState?.let { homeGrid.onRestoreInstanceState(it) }
            homeGrid.viewTreeObserver.addOnPreDrawListener(object: ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    homeGrid.viewTreeObserver.removeOnPreDrawListener(this)
                    if(grid!==homeGrid||page!="home"||homeGrid.count==0) return true
                    // GridView restores nonzero rows through its native state. Its first-row
                    // state omits a partial offset; correct that pixel delta before drawing.
                    val child=homeGrid.getChildAt(0)?:return true
                    if(homeGrid.firstVisiblePosition==index) homeGrid.scrollListBy(child.top-top)
                    return true
                }
            })
        }
        else gridPositions[gridKey()]?.let { (index,top) -> grid?.post { grid?.setSelectionFromTop(index,top) } }
        if(page=="home") host.addView(button("＋") { importTarget=null;addMenu(true) },FrameLayout.LayoutParams(d(64),d(64),Gravity.BOTTOM or Gravity.END).apply { bottomMargin=d(36);rightMargin=d(20) })
    }
    private fun fillGrid() {
        val items=mutableListOf<Pair<String,Boolean>>()
        if(page=="home") items.addAll(library.orderedWorks().map { it.id to true })
        else if(page=="work") items.addAll(library.orderedBooks(workId!!).map { it.id to false })
        else { if(searchTab!=2) items.addAll(library.orderedWorks().filter { it.name.contains(query,true) }.map { it.id to true });if(searchTab!=1) items.addAll(library.books().filter { it.name.contains(query,true) }.sortedWith { a,b -> NaturalOrder.compare(a.name,b.name) }.map { it.id to false }) }
        grid?.adapter=Cards(items)
    }
    private inner class Cards(val items: List<Pair<String,Boolean>>): BaseAdapter() {
        private val works=library.works().associateBy { it.id };private val books=library.books().associateBy { it.id }
        private val grouped=books.values.groupBy { it.work }
        override fun getCount()=items.size
        override fun getItem(position: Int)=items[position]
        override fun getItemId(position: Int)=position.toLong()
        override fun getView(position: Int,convert: View?,parent: ViewGroup): View {
            val (id,isWork)=items[position];val card=vertical().apply { setBackgroundColor(if(id in selected) 0xFF374264.toInt() else 0xFF23252B.toInt());setPadding(d(6),d(6),d(6),d(8)) }
            val image=ImageView(this@MainActivity).apply { scaleType=if(library.settings().crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER;setImageResource(R.drawable.ic_app) }
            val columns=grid?.numColumns?:2;val height=((resources.displayMetrics.widthPixels-d(36))/columns*1.35f).toInt();card.addView(image,LinearLayout.LayoutParams(-1,height))
            val book=if(isWork) grouped[id]?.minWithOrNull { a,b -> NaturalOrder.compare(a.name,b.name) } else books[id]
            val title=if(isWork) works[id]?.name?:"" else books[id]?.name?:""
            card.addView(text(title,14f).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END })
            card.addView(text(if(isWork) "${grouped[id]?.size?:0}冊" else "${books[id]?.pages?:0}ページ",12f).apply { setTextColor(Color.LTGRAY) })
            if(page=="search"&&!isWork) card.addView(text(works[books[id]?.work]?.name?:"",12f))
            if(book!=null) thumbnails.execute { try { val f=library.thumbnail(book.id);val bitmap=BitmapFactory.decodeFile(f.path);main.post { if(image.isAttachedToWindow) image.setImageBitmap(bitmap) } } catch(e: Exception) { android.util.Log.w("Covers","Thumbnail",e) } }
            return card
        }
    }
    private fun updateSelection(bar: LinearLayout) {
        bar.removeAllViews();bar.visibility=if(selected.isEmpty()) View.GONE else View.VISIBLE;if(selected.isEmpty()) return
        bar.addView(button("${selected.size}件 / 全選択") { selected.addAll((grid!!.adapter as Cards).items.map { it.first });updateSelection(bar);(grid!!.adapter as Cards).notifyDataSetChanged() })
        bar.addView(button("操作") {
            val allWorks=selected.all { id -> library.works().any { it.id==id } };val allBooks=selected.all { library.book(it)!=null };val actions=mutableListOf("削除","書き出し");if(allBooks) actions.add("共有");if(selected.size==1) actions.add("名前変更")
            AlertDialog.Builder(this).setItems(actions.toTypedArray()) { _,which -> when(actions[which]) {
                "削除" -> confirm("選択した${selected.size}件を完全に削除しますか？") { val ids=selected.toList();val ws=ids.filter { id -> library.works().any { it.id==id } };val bs=ids-ws.toSet();busy("削除しています") { library.delete(true,ws);library.delete(false,bs) } }
                "書き出し" -> { exportFolders=allWorks||!allBooks;exportBooks=library.books().filter { it.id in selected||it.work in selected };pickTree(14) }
                "共有" -> share(library.books().filter { it.id in selected })
                "名前変更" -> { val id=selected.first();val isWork=library.works().any { it.id==id };val name=if(isWork) library.works().first { it.id==id }.name else library.book(id)!!.name;input("名前変更",name) { value -> try { library.rename(isWork,id,value);rememberGrid();showLibrary() } catch(e: Exception) { message("名前を変更できませんでした") } } }
            } }.show()
        })
        bar.addView(button("解除") { selected.clear();updateSelection(bar);(grid!!.adapter as Cards).notifyDataSetChanged() })
    }
    private fun share(books: List<Book>) {
        val uris=ArrayList(books.map { Uri.Builder().scheme("content").authority("jp.local.imagepdf.share").appendPath(it.id).appendPath(it.name).build() })
        val intent=Intent(if(uris.size==1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply { type="application/pdf";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);if(uris.size==1) putExtra(Intent.EXTRA_STREAM,uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);clipData=ClipData.newUri(contentResolver,"PDF",uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } } };startActivity(Intent.createChooser(intent,"PDFを共有"))
    }
    private fun currentSort()=if(page=="work") library.works().first { it.id==workId }.sort else library.sort
    private fun sortDialog() {
        val labels=arrayOf("名前順","最近読んだ順","更新日時順","手動順");val modes=listOf("name","read","updated","manual")
        AlertDialog.Builder(this).setTitle("並び替え").setSingleChoiceItems(labels,modes.indexOf(currentSort())) { dialog,index ->
            dialog.dismiss();val apply:(Boolean)->Unit={ desc -> rememberGrid();if(page=="work") { val w=library.works().first { it.id==workId };library.workOptions(w.id,w.columns,modes[index],desc) } else { library.sort=modes[index];library.descending=desc };showLibrary() }
            if(index==3) apply(false) else AlertDialog.Builder(this).setItems(arrayOf("昇順","降順")) { _,which -> apply(which==1) }.show()
        }.setNegativeButton("閉じる",null).show()
    }
    private fun addMenu(home: Boolean) {
        val options=if(home) arrayOf("新しい作品フォルダ","PDFをインポート","端末のフォルダをインポート") else arrayOf("PDFを追加","端末フォルダ内PDFを追加")
        AlertDialog.Builder(this).setItems(options) { _,index -> when {
            home&&index==0 -> input("新しい作品フォルダ") { library.createWork(it);rememberGrid();showLibrary() }
            (home&&index==2)||(!home&&index==1) -> pickTree(11)
            else -> pickPdf()
        } }.show()
    }
    private fun pickPdf() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE);type="application/pdf";putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },10) }
    private fun pickTree(code: Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),code) }
    override fun onActivityResult(request: Int,result: Int,data: Intent?) {
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null) return
        val uri=data.data
        when(request) {
            10 -> { val uris=if(data.clipData!=null) (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri } else listOfNotNull(uri);chooseTarget(uris.map { library.source(it) },null) }
            11 -> if(uri!=null) { val dialog=ProgressDialog.show(this,"","フォルダを確認しています",true,false);io.execute { try { val (name,sources)=library.folder(uri);main.post { dialog.dismiss();if(sources.isEmpty()) message("PDFがありません") else if(importTarget!=null) startImport(sources,importTarget,null) else startImport(sources,null,name) } } catch(e: Exception) { main.post { dialog.dismiss();message("フォルダを開けませんでした") } } } }
            12 -> if(uri!=null) busy("バックアップを作成しています") { library.backup(uri) }
            13 -> if(uri!=null) { val target=uri;AlertDialog.Builder(this).setTitle("バックアップから復元").setItems(arrayOf("完全置換","統合")) { _,i -> replaceRestore=i==0;if(replaceRestore) confirm("現在のライブラリをバックアップで完全に置き換えますか？") { busy("復元しています") { library.restore(target,true) } } else busy("復元しています") { library.restore(target,false) } }.show() }
            14 -> if(uri!=null) busy("書き出しています") { library.export(uri,exportBooks,exportFolders) }
        }
    }
    private fun chooseTarget(sources: List<Library.Source>,externalAfter: Uri?) {
        importTarget?.let { startImport(sources,it,null,externalAfter);return }
        val works=library.orderedWorks();val names=works.map { it.name }+"新しい作品フォルダ"
        AlertDialog.Builder(this).setTitle("保存先の作品").setItems(names.toTypedArray()) { _,which -> if(which==works.size) input("新しい作品フォルダ") { startImport(sources,null,it,externalAfter) } else startImport(sources,works[which].id,null,externalAfter) }.setNegativeButton("キャンセル",null).show()
    }
    private fun startImport(sources: List<Library.Source>,target: String?,name: String?,externalAfter: Uri?=null) {
        val cancel=AtomicBoolean(false);operation=cancel
        val progress=ProgressDialog(this).apply { setTitle("PDFをインポート");setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);max=100;setCancelable(false);setButton(DialogInterface.BUTTON_NEGATIVE,"キャンセル") { _,_ -> cancel.set(true) };show() }
        var policy: Library.Conflict?=null
        io.execute { try {
            val ids=library.import(sources,target,name,cancel,{ duplicate ->
                policy?:run {
                    val latch=CountDownLatch(1);var answer=Library.Conflict.SKIP
                    main.post { if(cancel.get()||isFinishing) { latch.countDown();return@post };val applyAll=CheckBox(this).apply { text="以降の重複にも同じ処理を適用" }
                        val dialog=AlertDialog.Builder(this).setTitle("同名のPDFが存在します").setMessage(duplicate).setView(applyAll).setPositiveButton("置き換える") { _,_ -> answer=Library.Conflict.REPLACE;if(applyAll.isChecked) policy=answer;latch.countDown() }.setNeutralButton("別名で保存") { _,_ -> answer=Library.Conflict.RENAME;if(applyAll.isChecked) policy=answer;latch.countDown() }.setNegativeButton("キャンセル") { _,_ -> cancel.set(true);latch.countDown() }.setCancelable(false).create();dialog.show()
                    }
                    while(!latch.await(100,TimeUnit.MILLISECONDS)) { if(cancel.get()) throw java.io.InterruptedIOException() };answer
                }
            },{ current,total,percent -> main.post { progress.setMessage("$current / $total ファイル");progress.progress=(((current-1)*100+percent)/total).coerceIn(0,100) } })
            main.post { operation=null;progress.dismiss();if(externalAfter!=null&&ids.isNotEmpty()) { closeReader();val b=library.book(ids.last())!!;workId=b.work;page="work";showLibrary();openBook(b,true) } else { rememberGrid();showLibrary() };message("インポートしました") }
        } catch(e: Throwable) { android.util.Log.w("Import","Import rolled back",e);main.post { operation=null;progress.dismiss();message(if(cancel.get()) "インポートをキャンセルしました" else if(e is IllegalArgumentException&&e.message=="空き容量が不足しています") e.message!! else "インポートできませんでした") } } }
    }
    private fun busy(title: String,action:()->Unit) {
        val previous=page;val dialog=ProgressDialog.show(this,"",title,true,false)
        io.execute { try { action();main.post { dialog.dismiss();message("完了しました");if(previous=="settings") showSettings(title!="復元しています") else { rememberGrid();showLibrary() } } } catch(e: Throwable) { android.util.Log.w("Library",title,e);main.post { dialog.dismiss();message("処理を完了できませんでした") } } }
    }
    private fun confirm(title: String,action:()->Unit) { AlertDialog.Builder(this).setMessage(title).setPositiveButton("実行") { _,_ -> action() }.setNegativeButton("キャンセル",null).show() }
    private fun input(title: String,value: String="",action:(String)->Unit) { val edit=EditText(this).apply { setSingleLine();setText(value) };val dialog=AlertDialog.Builder(this).setTitle(title).setView(edit).setPositiveButton("保存",null).setNegativeButton("キャンセル",null).create();dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if(edit.text.toString().trim().isNotEmpty()) { action(edit.text.toString().trim());dialog.dismiss() } } };dialog.show() }
    private fun openBook(book: Book,automatic: Boolean) {
        if(!library.file(book.id).exists()) { message("PDFを開けませんでした");return }
        if(!automatic&&book.page>=2) AlertDialog.Builder(this).setMessage("前回の位置から再開しますか？").setPositiveButton("再開") { _,_ -> launchReader(book,null,book.page,book.offset) }.setNegativeButton("最初から") { _,_ -> launchReader(book,null,0,0f) }.show()
        else launchReader(book,null,if(automatic) book.page else 0,if(automatic) book.offset else 0f)
    }
    private fun openExternal(uri: Uri) { workId=null;page="home";showLibrary();launchReader(null,uri,0,0f) }
    private fun launchReader(book: Book?,uri: Uri?,startPage: Int,offset: Float) {
        reader?.close();reading=book;external=uri;book?.let { library.markRead(it) }
        val frame=FrameLayout(this);readerFrame=frame
        val view=ReaderView(this,{ if(book!=null) ParcelFileDescriptor.open(library.file(book.id),ParcelFileDescriptor.MODE_READ_ONLY) else requireNotNull(contentResolver.openFileDescriptor(uri!!,"r")) },library.settings(),startPage,offset,
            { p,o,done -> if(book!=null) io.execute { try { library.position(book.id,p,o,done) } catch(e: Exception) { android.util.Log.w("ReadingPosition","Cannot persist position",e) } } },{ toggleReaderUi() },{ hideReaderUi() },{ direction -> switchBook(direction) },{ e -> android.util.Log.w("Reader","Cannot open PDF",e);message("PDFを開けませんでした");closeReader() })
        reader=view;frame.addView(view,FrameLayout.LayoutParams(-1,-1))
        val title=book?.name?:runCatching { library.source(uri!!).name }.getOrDefault("document.pdf")
        val bar=toolbar(title,true).apply { setBackgroundColor(0xC0303034.toInt());isClickable=true }
        val pageLabel=text("",16f).apply { setTextColor(Color.WHITE);setPadding(d(8),0,d(12),0) }
        bar.addView(pageLabel,LinearLayout.LayoutParams(-2,d(56)))
        view.pageDisplay={ current,total -> pageLabel.text="$current / $total" }
        if(book==null) bar.addView(button("ライブラリに追加") { importTarget=null;chooseTarget(listOf(library.source(uri!!)),uri) })
        readerBar=bar;frame.addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.TOP));bar.visibility=View.GONE
        host.addView(frame,FrameLayout.LayoutParams(-1,-1));hideReaderUi()
    }
    private fun switchBook(direction: Int) { val current=reading?:return;val items=library.orderedBooks(current.work);val index=items.indexOfFirst { it.id==current.id }+direction;if(index !in items.indices) return
        reader?.persist();val nextId=items[index].id
        // Drain the outgoing position write before opening the adjacent PDF.
        io.execute { val next=library.book(nextId);main.post { if(next!=null&&reader!=null) { reader?.close();host.removeView(readerFrame);reader=null;launchReader(next,null,next.page,next.offset) } } }
    }
    private fun toggleReaderUi() { if(readerBar?.visibility==View.VISIBLE) hideReaderUi() else { readerBar?.visibility=View.VISIBLE;systemBars(true);main.removeCallbacks(hideBar);main.postDelayed(hideBar,2000) } }
    private fun hideReaderUi() { main.removeCallbacks(hideBar);readerBar?.visibility=View.GONE;if(reader!=null) systemBars(false) }
    private fun systemBars(show: Boolean) {
        if(Build.VERSION.SDK_INT>=30) window.insetsController?.apply { systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;if(show) show(WindowInsets.Type.systemBars()) else hide(WindowInsets.Type.systemBars()) }
        else window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or (if(show) 0 else View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
    private fun closeReader() { reader?.close();reader=null;reading=null;external=null;main.removeCallbacks(hideBar);host.removeView(readerFrame);readerFrame=null;readerBar=null;systemBars(true);showLibrary() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean { if(reader?.volume(event)==true) return true;return super.dispatchKeyEvent(event) }
    override fun onPause() { super.onPause();reader?.stopVolume();reader?.persist() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus);if(!hasFocus) reader?.stopVolume() else if(reader!=null&&readerBar?.visibility!=View.VISIBLE) systemBars(false) }
    override fun onBackPressed() {
        if(reader!=null) { closeReader();return }
        if(page=="settings") { if(draft!=library.settings()) confirm("未保存の変更を破棄しますか？") { page="home";showLibrary() } else { page="home";showLibrary() };return }
        if(selected.isNotEmpty()) { rememberGrid();showLibrary();return }
        if(page=="work"||page=="search") { rememberGrid();page="home";workId=null;showLibrary();return }
        if(page=="onboarding") return
        super.onBackPressed()
    }
    private fun showSettings(keepDraft: Boolean=false) {
        page="settings";if(!keepDraft||draft==null) draft=library.settings();host.removeAllViews();val base=vertical();host.addView(base);base.addView(toolbar("設定"));val scroll=ScrollView(this);val body=vertical().apply { setPadding(d(16),0,d(16),d(32)) };scroll.addView(body);base.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        fun toggle(label: String,value: Boolean,change:(Boolean)->Unit) { body.addView(Switch(this).apply { text=label;isChecked=value;setPadding(0,d(10),0,d(10));setOnCheckedChangeListener { _,checked -> change(checked) } }) }
        fun choices(label: String,values: List<Int>,current: Int,suffix: String,change:(Int)->Unit) { var chosen=current;val b=button("$label：$current$suffix") {};b.setOnClickListener { AlertDialog.Builder(this).setTitle(label).setSingleChoiceItems(values.map { "$it$suffix" }.toTypedArray(),values.indexOf(chosen)) { dialog,index -> chosen=values[index];change(chosen);b.text="$label：$chosen$suffix";dialog.dismiss() }.show() };body.addView(b) }
        val p=draft!!
        toggle("音量ボタンでスクロール",p.volume) { draft=draft!!.copy(volume=it) }
        choices("音量ボタン単押し距離",listOf(10,25,50,75,100),p.step,"%") { draft=draft!!.copy(step=it) }
        choices("音量長押し最大速度",listOf(1,2,3,4),p.speed,"画面/秒") { draft=draft!!.copy(speed=it) }
        choices("ページ間隔",listOf(0,4,8,16),p.gap,"dp") { draft=draft!!.copy(gap=it) }
        toggle("ダブルタップズーム",p.zoom) { draft=draft!!.copy(zoom=it) }
        toggle("起動時に最後に読んでいたPDFを開く",p.reopen) { draft=draft!!.copy(reopen=it) }
        toggle("サムネイル表示：Crop（OFFはFit）",p.crop) { draft=draft!!.copy(crop=it) }
        val capacity=text("容量を確認しています",14f);body.addView(capacity)
        io.execute { val lib=library.libraryBytes();val cache=library.cacheBytes();main.post { capacity.text="ライブラリ使用容量：${android.text.format.Formatter.formatFileSize(this,lib)}\nキャッシュ使用容量：${android.text.format.Formatter.formatFileSize(this,cache)}" } }
        body.addView(button("キャッシュを削除") { confirm("再生成可能なキャッシュを削除しますか？") { busy("キャッシュを削除しています") { library.clearCache() } } })
        body.addView(button("バックアップを作成") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE);type="application/zip";putExtra(Intent.EXTRA_TITLE,"ImagePdfReader-backup.zip") },12) })
        body.addView(button("バックアップから復元") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE);type="*/*" },13) })
        base.addView(button("保存") { library.saveSettings(draft!!);page="home";showLibrary() },LinearLayout.LayoutParams(-1,d(56)));base.setPadding(0,0,0,d(24))
    }
    private fun onboarding() {
        page="onboarding";val titles=listOf("ライブラリ","基本閲覧","音量ボタン","Fast Scroller","PDF切り替え");val descriptions=listOf("「＋」からPDFや作品フォルダを追加できます。PDFは原本のままアプリ内にコピーします。","縦スクロールで読み進めます。ダブルタップで2倍に拡大し、もう一度タップすると戻ります。","Volume + で上、Volume − で下へ。長押しすると徐々に速くスクロールします。","右端の細い線をドラッグ。本文を固定したままプレビューを選び、指を離して移動します。","二本指で左へ横スワイプすると次のPDF、右へスワイプすると前のPDFを開きます。")
        var index=0;val base=vertical();host.removeAllViews();host.addView(base);base.setPadding(d(28),d(80),d(28),d(40));val title=text("",28f);val description=text("",18f);val indicator=text("",14f);base.addView(title);base.addView(description,LinearLayout.LayoutParams(-1,0,1f));base.addView(indicator);val nav=row();base.addView(nav)
        fun update() { title.text=titles[index];description.text=descriptions[index];indicator.text="${index+1} / 5";nav.removeAllViews();if(index>0) nav.addView(button("前へ") { index--;update() });nav.addView(button(if(index==4) "アプリをはじめる" else "次へ") { if(index==4) { library.onboarding=true;page="home";startLibrary() } else { index++;update() } },LinearLayout.LayoutParams(0,d(56),1f)) }
        var down=0f;base.setOnTouchListener { _,e -> when(e.action) { MotionEvent.ACTION_DOWN -> down=e.x;MotionEvent.ACTION_UP -> { if(kotlin.math.abs(e.x-down)>d(60)) { index=(index+if(e.x<down) 1 else -1).coerceIn(0,4);update() } } };true };update()
    }
    override fun onDestroy() { operation?.set(true);reader?.close();main.removeCallbacks(hideBar);thumbnails.shutdown();io.execute { library.close() };io.shutdown();super.onDestroy() }
}
