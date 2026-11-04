package io.github.stickerbox.hook

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.ImageDecoder
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.content.ClipDescription
import android.widget.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.stickerbox.core.MediaRules
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Only framework methods are hooked. No obfuscated Google classes or resource IDs. */
class StickerBoxHook:IXposedHookLoadPackage {
    private val panels=WeakHashMap<InputMethodService,WeakReference<Panel>>()
    private val inputs=WeakHashMap<InputMethodService,WeakReference<View>>()
    private val checked=mutableSetOf<String>()
    private val blocked=mutableSetOf<String>()
    private var failures=0
    private var pingRegistered=false
    override fun handleLoadPackage(param:XC_LoadPackage.LoadPackageParam) {
        if(param.packageName!=GBOARD)return
        XposedBridge.log("StickerBox: module entry loaded in ${param.processName}")
        fun hook(name:String,vararg parameters:Any) {
            try{XposedHelpers.findAndHookMethod(InputMethodService::class.java,name,*parameters)}
            catch(t:Throwable){XposedBridge.log("StickerBox: $name hook failed (${t.javaClass.simpleName})")}
        }
        hook("onCreate",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                (p.thisObject as? InputMethodService)?.let{initialize(it)}
            }
        })
        hook("setInputView",View::class.java,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable())return
                val service=p.thisObject as? InputMethodService ?: return
                val input=p.args[0] as? View ?: return
                initialize(service);inputs[service]=WeakReference(input);attempt(service,input)
            }
        })
        hook("showWindow",java.lang.Boolean.TYPE,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                val service=p.thisObject as? InputMethodService ?: return
                initialize(service)
                inputs[service]?.get()?.let{attempt(service,it)}
            }
        })
        hook("onWindowHidden",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){panels[p.thisObject as? InputMethodService]?.get()?.close()}
        })
        hook("onDestroy",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val service=p.thisObject as? InputMethodService ?: return
                panels.remove(service)?.get()?.destroy();inputs.remove(service)
            }
        })
    }
    private fun initialize(service:InputMethodService) {
        if(pingRegistered)return
        try {
            val receiver=object:android.content.BroadcastReceiver(){
                override fun onReceive(context:Context,intent:Intent){
                    if(intent.action==PING){resultCode=1;resultData=phase.take(500)}
                }
            }
            val filter=android.content.IntentFilter(PING)
            val context=service.applicationContext
            if(android.os.Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED)
            else registerLegacyPing(context,receiver,filter)
            pingRegistered=true
            status(service,"模块已加载；输入法服务已创建；等待注入")
        }catch(t:Throwable){phase="模块已加载；诊断接收器失败 (${t.javaClass.simpleName})";XposedBridge.log("StickerBox: $phase")}
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLegacyPing(context:Context,receiver:android.content.BroadcastReceiver,filter:android.content.IntentFilter) {
        // Export flags exist only on API 33+. This helper cannot run on those versions.
        check(android.os.Build.VERSION.SDK_INT<33)
        @Suppress("DEPRECATION") context.registerReceiver(receiver,filter)
    }
    private fun attempt(service:InputMethodService,original:View) {
        if(failures>=3){status(service,"安全降级：本进程连续注入失败，请重启 Gboard 后查看 LSPosed 日志");return}
        worker.execute {
            try {
                val config=call(service,"config") ?: error("No bridge response")
                val version=service.packageManager.getPackageInfo(GBOARD,0).let{"${it.versionName} (${it.longVersionCode})"}
                if(!config.getBoolean("enabled")) {status(service,"模块已加载；管理 App 中的面板开关关闭；Gboard $version");return@execute}
                main.post {
                    try {
                        if(panels[service]?.get()?.let{it.original===original && !it.disposed}==true) {
                            status(service,"已注入键盘内容区域；Gboard $version；Android ${android.os.Build.VERSION.SDK_INT}")
                            return@post
                        }
                        val guard=service.getSharedPreferences("stickerbox_guard",0)
                        val key="$version/${config.getLong("revision",0)}"
                        if(key in blocked)return@post
                        if(checked.add(key) && guard.getBoolean("pending",false) && guard.getString("key",null)==key) {
                            blocked.add(key);event(service,"HOOK_FAILED")
                            status(service,"安全降级：上次注入后进程提前结束。请在管理 App 重置。Gboard $version")
                            return@post
                        }
                        guard.edit().putString("key",key).putBoolean("pending",true).commit()
                        panels.remove(service)?.get()?.destroy()
                        val panel=Panel(service,original)
                        if(!panel.install()) {
                            guard.edit().putBoolean("pending",false).apply()
                            status(service,"模块已加载；尚未匹配可见键盘内容区域；Gboard $version")
                            return@post
                        }
                        panels[service]=WeakReference(panel)
                        status(service,"已注入键盘内容区域；Gboard $version；Android ${android.os.Build.VERSION.SDK_INT}")
                        main.postDelayed({guard.edit().putBoolean("pending",false).apply()},15_000)
                    }catch(t:Throwable){failures++;event(service,"HOOK_FAILED");status(service,"注入失败 (${t.javaClass.simpleName})；请查看 LSPosed 日志")}
                }
            }catch(t:Throwable){
                phase="模块已加载；管理 App 通信失败 (${t.javaClass.simpleName})；请先打开 StickerBox 后重试"
                XposedBridge.log("StickerBox: $phase")
            }
        }
    }
    private class Panel(val service:InputMethodService,val original:View) {
        private val root=LinearLayout(service).apply{orientation=LinearLayout.VERTICAL;tag=TAG;setBackgroundColor(Color.rgb(238,242,237))}
        private val toolbar=LinearLayout(service).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        private var mount:KeyboardMount.Mount?=null
        private val body=LinearLayout(service).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(238,242,237));visibility=View.GONE}
        private val grid=GridView(service).apply{numColumns=4;verticalSpacing=dp(4);horizontalSpacing=dp(4)}
        private val label=TextView(service).apply{setTextColor(Color.DKGRAY);textSize=12f}
        private var filter="all"
        private var category=""
        private var offset=0
        private var generation=0
        private var items=emptyList<JSONObject>()
        private var categories=emptyList<String>()
        private var popup:PopupWindow?=null
        private var observer:ContentObserver?=null
        var disposed=false
            private set
        private fun dp(n:Int)=(n*service.resources.displayMetrics.density).toInt()
        private fun button(text:String,action:()->Unit)=Button(service).apply{
            this.text=text;textSize=12f;isAllCaps=false;minWidth=0;minimumWidth=0
            setPadding(dp(6),0,dp(6),0);setOnClickListener{safe(action)}
        }
        private fun safe(action:()->Unit){try{action()}catch(_:Throwable){event(service,"HOOK_FAILED");close()}}
        fun install():Boolean {
            toolbar.addView(button("StickerBox"){if(body.visibility==View.VISIBLE)close()else{body.visibility=View.VISIBLE;load()}})
            toolbar.addView(button("键盘"){close()})
            toolbar.addView(button("管理 / 搜索"){service.startActivity(Intent().setClassName("io.github.stickerbox","io.github.stickerbox.ui.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))})
            root.addView(toolbar,LinearLayout.LayoutParams(-1,dp(40)))
            val filters=LinearLayout(service)
            filters.addView(button("全部"){filter="all";category="";offset=0;load()})
            filters.addView(button("收藏"){filter="favorites";offset=0;load()})
            filters.addView(button("最近"){filter="recent";offset=0;load()})
            filters.addView(button("分类"){choose(categories){category=it;filter="all";offset=0;load()}})
            body.addView(filters,LinearLayout.LayoutParams(-1,dp(40)))
            body.addView(label)
            body.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
            val pages=LinearLayout(service)
            pages.addView(button("上一页"){offset=(offset-120).coerceAtLeast(0);load()})
            pages.addView(button("下一页"){if(items.size==120){offset+=120;load()}})
            pages.addView(button("预览 / 收藏"){Toast.makeText(service,"长按贴纸预览；预览页可切换收藏",Toast.LENGTH_LONG).show()})
            body.addView(pages,LinearLayout.LayoutParams(-1,dp(40)))
            grid.adapter=object:BaseAdapter(){
                override fun getCount()=items.size
                override fun getItem(p:Int)=items[p]
                override fun getItemId(p:Int)=p.toLong()
                override fun getView(p:Int,convertView:View?,parent:ViewGroup?):View {
                    val cell=(convertView as? LinearLayout) ?: LinearLayout(service).apply {
                        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
                        addView(ImageView(service).apply{scaleType=ImageView.ScaleType.FIT_CENTER},LinearLayout.LayoutParams(-1,dp(66)))
                        addView(TextView(service).apply{setTextColor(Color.DKGRAY);textSize=10f;maxLines=1})
                        layoutParams=AbsListView.LayoutParams(-1,dp(88))
                    }
                    val row=items[p];val id=row.getString("id");val image=cell.getChildAt(0) as ImageView
                    image.tag=id;image.setImageDrawable(null)
                    (cell.getChildAt(1) as TextView).text=(if(row.optBoolean("favorite"))"★ " else "")+row.getString("name")
                    image.contentDescription=row.getString("name")
                    Thumbnails.bind(service,image,id)
                    cell.setOnClickListener{safe{send(row)}}
                    cell.setOnLongClickListener{safe{preview(row)};true}
                    return cell
                }
            }
            mount=KeyboardMount.attach(original,root,body,dp(40)) ?: return false
            observer=object:ContentObserver(main){override fun onChange(selfChange:Boolean){worker.execute{
                val enabled=try{call(service,"config")?.getBoolean("enabled")==true}catch(_:Throwable){false}
                main.post{if(!enabled){close();root.visibility=View.GONE}else root.visibility=View.VISIBLE}
            }}}
            runCatching{service.contentResolver.registerContentObserver(BASE,true,observer!!)}
            root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v:View)=Unit
                override fun onViewDetachedFromWindow(v:View){main.post{if(!root.isAttachedToWindow)destroy()}}
            })
            return true
        }
        private fun load() {
            val token=++generation
            val options=Bundle().apply{putString("filter",filter);putString("category",category);putInt("offset",offset)}
            worker.execute {
                try {
                    val result=call(service,"list",extras=options) ?: return@execute
                    val array=JSONArray(result.getString("items") ?: "[]")
                    val rows=(0 until array.length()).map{array.getJSONObject(it)}
                    main.post {
                        if(disposed || token!=generation)return@post
                        items=rows;categories=result.getStringArrayList("categories") ?: emptyList()
                        label.text="${category.ifEmpty{"所有分类"}} · 第 ${offset/120+1} 页 · 点击提交到输入框，发送由应用确认"
                        (grid.adapter as BaseAdapter).notifyDataSetChanged()
                        if(items.isEmpty())label.text="此页无贴纸。请打开管理 App 导入。"
                    }
                }catch(_:Throwable){main.post{label.text="无法读取贴纸库，请检查模块设置"}}
            }
        }
        private fun choose(values:List<String>,picked:(String)->Unit) {
            val list=ListView(service)
            list.adapter=object:BaseAdapter(){
                override fun getCount()=values.size
                override fun getItem(p:Int)=values[p]
                override fun getItemId(p:Int)=p.toLong()
                override fun getView(p:Int,reused:View?,parent:ViewGroup?):View = ((reused as? TextView) ?: TextView(service).apply{
                    setTextColor(Color.BLACK);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0)
                    layoutParams=AbsListView.LayoutParams(-1,dp(48))
                }).apply{text=values[p]}
            }
            val w=PopupWindow(list,dp(240),dp(260),false).apply{setBackgroundDrawable(ColorDrawable(Color.WHITE));isOutsideTouchable=true}
            list.setOnItemClickListener{_,_,position,_->w.dismiss();picked(values[position])}
            popup?.dismiss();popup=w;w.showAtLocation(root,Gravity.CENTER,0,0)
        }
        private fun preview(row:JSONObject) {
            val column=LinearLayout(service).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE)}
            val image=ImageView(service).apply{scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription=row.getString("name")}
            column.addView(image,LinearLayout.LayoutParams(dp(240),dp(200)))
            val w=PopupWindow(column,dp(240),dp(260),false).apply{setBackgroundDrawable(ColorDrawable(Color.WHITE));isOutsideTouchable=true}
            column.addView(button("切换收藏"){worker.execute{runCatching{call(service,"favorite",row.getString("id"))}};w.dismiss();main.postDelayed({load()},200)})
            w.setOnDismissListener{(image.drawable as? AnimatedImageDrawable)?.stop();image.setImageDrawable(null)}
            popup?.dismiss();popup=w;w.showAtLocation(root,Gravity.CENTER,0,0)
            worker.execute {
                val drawable=runCatching{ImageDecoder.decodeDrawable(ImageDecoder.createSource(service.contentResolver,media(row.getString("id")))){decoder,info,_->
                    val scale=(512.0/maxOf(info.size.width,info.size.height)).coerceAtMost(1.0)
                    decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
                }}.getOrNull()
                main.post{if(w.isShowing){image.setImageDrawable(drawable);(drawable as? AnimatedImageDrawable)?.start()}}
            }
        }
        private fun send(row:JSONObject) {
            val editor=service.currentInputEditorInfo ?: return
            val connection=service.currentInputConnection ?: return
            val types=editor.contentMimeTypes ?: emptyArray()
            val png=MediaRules.shouldConvertToPng(row.getString("mime"),types)
            val supported=MediaRules.accepts(types,if(png)"image/png" else row.getString("mime"))
            worker.execute {
                try {
                    val result=call(service,"prepare",row.getString("id"),Bundle().apply{putString("target",editor.packageName);putBoolean("png",png)}) ?: return@execute
                    main.post {
                        safe {
                            if(service.currentInputEditorInfo !== editor || service.currentInputConnection !== connection) {
                                Toast.makeText(service,"输入框已切换，请重新选择贴纸",Toast.LENGTH_LONG).show();return@safe
                            }
                            if(!supported){event(service,"MIME_UNSUPPORTED");fallback(result);return@safe}
                            val accepted=try {
                                connection.commitContent(InputContentInfo(Uri.parse(result.getString("uri")),ClipDescription("StickerBox",arrayOf(result.getString("mime")!!)),null),
                                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)
                            }catch(_:Throwable){event(service,"COMMIT_FAILED");false}
                            if(accepted) {
                                event(service,"COMMIT_ACCEPTED");worker.execute{runCatching{call(service,"recent",row.getString("id"))}}
                                Toast.makeText(service,"输入接口已接受；请在聊天应用确认图片和发送",Toast.LENGTH_LONG).show()
                            } else {event(service,"COMMIT_REJECTED");fallback(result)}
                        }
                    }
                }catch(_:Throwable){event(service,"PREPARE_FAILED");main.post{Toast.makeText(service,"无法准备图片，请在管理 App 分享",Toast.LENGTH_LONG).show()}}
            }
        }
        private fun fallback(result:Bundle) {
            val column=LinearLayout(service).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE)}
            column.addView(TextView(service).apply{text="应用未接受富媒体输入。请选择：";setTextColor(Color.BLACK)})
            val w=PopupWindow(column,dp(280),dp(200),false).apply{setBackgroundDrawable(ColorDrawable(Color.WHITE));isOutsideTouchable=true}
            column.addView(button("复制这张图片，然后手动粘贴"){
                val clip=ClipData.newUri(service.contentResolver,"StickerBox",Uri.parse(result.getString("uri")))
                (service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                event(service,"CLIPBOARD_SET");w.dismiss()
            })
            column.addView(button("打开系统分享（可保留 GIF 原格式）"){
                @Suppress("DEPRECATION") val pending=result.getParcelable<PendingIntent>("share")
                pending?.send();w.dismiss()
            })
            column.addView(button("取消"){w.dismiss()})
            popup?.dismiss();popup=w;w.showAtLocation(root,Gravity.CENTER,0,0)
        }
        fun close(){popup?.dismiss();popup=null;body.visibility=View.GONE}
        fun destroy(){if(disposed)return;disposed=true;generation++;close();observer?.let{runCatching{service.contentResolver.unregisterContentObserver(it)}};observer=null;mount?.remove();mount=null}
    }
    companion object {
        private const val GBOARD="com.google.android.inputmethod.latin"
        private const val TAG="stickerbox_framework_container"
        private const val PING="io.github.stickerbox.HOOK_PING"
        @Volatile private var phase="模块入口已加载；等待输入法服务"
        private val BASE=Uri.parse("content://io.github.stickerbox.bridge")
        private val worker=Executors.newSingleThreadExecutor()
        private val main=Handler(Looper.getMainLooper())
        private fun call(context:Context,method:String,arg:String?=null,extras:Bundle?=null)=context.contentResolver.call(BASE,method,arg,extras)
        private fun media(id:String)=BASE.buildUpon().appendPath("media").appendPath(id).build()
        private fun event(context:Context,code:String){worker.execute{runCatching{call(context,"event",code)}}}
        private fun status(context:Context,text:String){phase=text;XposedBridge.log("StickerBox: $text");worker.execute{runCatching{call(context,"heartbeat",text)}}}
    }
}

private object Thumbnails {
    private val executor=java.util.concurrent.ThreadPoolExecutor(2,2,10,java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(64),java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
    private val main=Handler(Looper.getMainLooper())
    private val cache=object:android.util.LruCache<String,android.graphics.Bitmap>(12*1024*1024){override fun sizeOf(k:String,v:android.graphics.Bitmap)=v.byteCount}
    fun bind(context:Context,view:ImageView,id:String) {
        cache.get(id)?.let{view.setImageBitmap(it);return}
        val ref=WeakReference(view)
        executor.execute {
            val bitmap=runCatching {
                val uri=Uri.parse("content://io.github.stickerbox.bridge/media/$id")
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)){decoder,info,_->
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale=(144.0/maxOf(info.size.width,info.size.height)).coerceAtMost(1.0)
                    decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
                }
            }.getOrNull() ?: return@execute
            cache.put(id,bitmap)
            main.post{ref.get()?.takeIf{it.tag==id}?.setImageBitmap(bitmap)}
        }
    }
}
