package io.github.stickerbox.ui

import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.load
import coil.dispose
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.stickerbox.data.BridgeProvider
import io.github.stickerbox.data.Importer
import io.github.stickerbox.data.LibraryDb
import io.github.stickerbox.data.Sticker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity:AppCompatActivity() {
    private val db get()=LibraryDb.get(this)
    private lateinit var status:TextView
    private lateinit var adapter:Stickers
    private lateinit var loader:ImageLoader
    private lateinit var root:LinearLayout
    private var filter="all"
    private var category=""
    private var search=""
    private var offset=0
    private var loadJob:Job?=null
    private var busy=false
    private val documents=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        if(uris.isNotEmpty())operation {Importer(this@MainActivity).documents(uris).toString()}
    }
    private val tree=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null)operation{Importer(this@MainActivity).folder(uri).toString()}
    }
    private val backup=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        if(uri!=null)operation{Importer(this@MainActivity).backup(uri);"备份完成（含分类、收藏和排序）"}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        loader=ImageLoader.Builder(this).components{add(ImageDecoderDecoder.Factory())}.crossfade(false).build()
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),0)}
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root){v,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left+dp(12),bars.top,bars.right+dp(12),bars.bottom);insets
        }
        root.addView(TextView(this).apply{text="Gboard StickerBox";textSize=24f;setPadding(0,dp(12),0,dp(4))})
        status=TextView(this).apply{text="本地贴纸库 · 点击分享，长按管理";textSize=12f}
        root.addView(status)
        actions(listOf("导入图片 / ZIP" to {documents.launch(arrayOf("*/*"))},"导入文件夹" to {tree.launch(null)},"设置 / 状态" to {settings()}))
        val field=EditText(this).apply{hint="搜索名称或分类";setSingleLine(true);contentDescription="搜索贴纸"}
        root.addView(field,LinearLayout.LayoutParams(-1,dp(52)))
        field.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){search=s?.toString().orEmpty();offset=0;refresh(true)}
            override fun afterTextChanged(s:android.text.Editable?)=Unit
        })
        actions(listOf("全部" to {filter="all";category="";offset=0;refresh()},"收藏" to {filter="favorites";offset=0;refresh()},
            "最近" to {filter="recent";offset=0;refresh()},"分类" to {categories()}))
        adapter=Stickers()
        val grid=RecyclerView(this).apply{
            layoutManager=GridLayoutManager(this@MainActivity,3)
            adapter=this@MainActivity.adapter
            setHasFixedSize(true)
        }
        root.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
        actions(listOf("上一页" to {offset=(offset-120).coerceAtLeast(0);refresh()},"下一页" to {if(adapter.currentList.size==120){offset+=120;refresh()}}))
        if(savedInstanceState==null)refresh()
    }
    override fun onResume(){super.onResume();if(::adapter.isInitialized)refresh()}
    override fun onDestroy(){super.onDestroy();if(::loader.isInitialized)loader.shutdown()}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun actions(items:List<Pair<String,()->Unit>>) {
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        items.forEach{(label,action)->row.addView(MaterialButton(this).apply{text=label;textSize=12f;isAllCaps=false;setOnClickListener{if(!busy)action()}})}
        root.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(row)})
    }
    private fun refresh(debounce:Boolean=false) {
        loadJob?.cancel()
        loadJob=lifecycleScope.launch {
            if(debounce)delay(200)
            try {
                val (rows,count)=withContext(Dispatchers.IO){db.list(search,filter,category,offset) to db.count()}
                adapter.submitList(rows)
                if(!busy)status.text="共 $count 张 · 第 ${offset/120+1} 页 · ${category.ifEmpty{"所有分类"}} · 长按管理"
            }catch(_:Exception){status.text="无法读取表情库；请查看设置中的日志"}
        }
    }
    private fun operation(block:()->String) {
        if(busy)return
        busy=true;status.text="正在处理，请保持管理 App 打开…"
        lifecycleScope.launch {
            val message=try {withContext(Dispatchers.IO){block()}}
            catch(_:Exception){"处理失败或部分导入，请查看日志；已入库文件保留"}
            finally{busy=false}
            refresh();MaterialAlertDialogBuilder(this@MainActivity).setMessage(message).setPositiveButton("确定",null).show()
        }
    }
    private fun categories() {
        lifecycleScope.launch {
            val values=withContext(Dispatchers.IO){listOf("所有分类")+db.categories()}
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("分类筛选")
                .setItems(values.toTypedArray()){_,i->category=if(i==0)"" else values[i];offset=0;refresh()}.show()
        }
    }
    private fun settings() {
        lifecycleScope.launch {
            val diagnostics=withContext(Dispatchers.IO){
                "${db.state("hook")}\n\n上次心跳：${db.state("heartbeat").toLongOrNull()?.let{java.util.Date(it).toString()} ?: "无"}\n\n${db.events()}"
            }
            val prefs=getSharedPreferences("settings",0)
            val column=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(8))}
            column.addView(SwitchMaterial(this@MainActivity).apply{
                text="启用 Gboard 面板（实验性兼容）";isChecked=prefs.getBoolean("enabled",false)
                setOnCheckedChangeListener{_,checked->prefs.edit().putBoolean("enabled",checked).putLong("revision",System.currentTimeMillis()).apply();contentResolver.notifyChange(BridgeProvider.BASE,null)}
            })
            column.addView(TextView(this@MainActivity).apply{text="首次启用后，请在系统设置强制停止并重新打开 Gboard。仅在 LSPosed 勾选 Gboard。未知版本会尝试 Framework 入口，异常时降级。"})
            column.addView(SwitchMaterial(this@MainActivity).apply{
                text="新导入存到外部应用专属目录";isChecked=prefs.getBoolean("external",false)
                setOnCheckedChangeListener{_,checked->prefs.edit().putBoolean("external",checked).apply()}
            })
            column.addView(TextView(this@MainActivity).apply{text="目录选择仅影响新导入：\n内部 ${filesDir}/stickers\n外部 ${getExternalFilesDir(null)}/stickers\n卸载会删除两处文件，请先备份。";textSize=12f})
            fun action(label:String,run:()->Unit){column.addView(MaterialButton(this@MainActivity).apply{text=label;setOnClickListener{run()}})}
            action("备份 ZIP"){backup.launch("StickerBox-backup.zip")}
            action("恢复备份 / 导入 ZIP"){documents.launch(arrayOf("application/zip","application/octet-stream"))}
            action("重置 Hook 安全降级标记"){
                prefs.edit().putLong("revision",System.currentTimeMillis()).apply()
                contentResolver.notifyChange(BridgeProvider.BASE,null)
                Toast.makeText(this@MainActivity,"已重置；请在系统设置重启 Gboard",Toast.LENGTH_LONG).show()
            }
            action("Gboard 应用信息"){
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:com.google.android.inputmethod.latin")))
            }
            column.addView(TextView(this@MainActivity).apply{text=diagnostics;textSize=11f;setTextIsSelectable(true)})
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("设置与诊断")
                .setView(ScrollView(this@MainActivity).apply{addView(column)}).setPositiveButton("关闭",null).show()
        }
    }
    private fun share(s:Sticker){startActivity(Intent(this,ShareActivity::class.java).putExtra("id",s.id))}
    private fun menu(s:Sticker) {
        MaterialAlertDialogBuilder(this).setTitle(s.name).setItems(arrayOf("预览动图",if(s.favorite)"取消收藏" else "收藏","编辑名称 / 分类 / 排序","分享原格式","删除")){_,index->
            when(index){
                0->preview(s)
                1->operation{db.edit(s.id,s.name,s.group,s.rank,!s.favorite);"收藏已更新"}
                2->edit(s)
                3->share(s)
                4->MaterialAlertDialogBuilder(this).setMessage("删除这张本地贴纸？").setNegativeButton("取消",null).setPositiveButton("删除"){_,_->operation{db.remove(s.id);LibraryDb.file(this,s).delete();"已删除"}}.show()
            }
        }.show()
    }
    private fun preview(s:Sticker) {
        val image=ImageView(this).apply{adjustViewBounds=true;contentDescription=s.name}
        image.load(LibraryDb.file(this,s),loader){size(768)}
        MaterialAlertDialogBuilder(this).setTitle(s.name).setView(image).setPositiveButton("分享"){_,_->share(s)}
            .setNegativeButton("关闭",null).setOnDismissListener{(image.drawable as? Animatable)?.stop();image.dispose()}.show()
    }
    private fun edit(s:Sticker) {
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),0)}
        fun field(label:String,value:String)=EditText(this).apply{hint=label;setText(value);setSingleLine(true);column.addView(this)}
        val name=field("名称",s.name)
        val categoryField=field("分类（输入新名称即可创建）",s.group)
        val rank=field("排序数字（越小越靠前）",s.rank.toString()).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED}
        MaterialAlertDialogBuilder(this).setTitle("编辑贴纸").setView(column).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->
            operation{db.edit(s.id,name.text.toString(),categoryField.text.toString().ifBlank{"未分类"},rank.text.toString().toLongOrNull() ?: s.rank,s.favorite);"已保存"}
        }.show()
    }
    private inner class Holder(val box:LinearLayout):RecyclerView.ViewHolder(box) {
        val image=ImageView(this@MainActivity).apply{scaleType=ImageView.ScaleType.FIT_CENTER}
        val title=TextView(this@MainActivity).apply{textSize=12f;maxLines=1;gravity=Gravity.CENTER}
        init {box.orientation=LinearLayout.VERTICAL;box.setPadding(dp(4),dp(4),dp(4),dp(4));box.addView(image,LinearLayout.LayoutParams(-1,dp(96)));box.addView(title);box.layoutParams=RecyclerView.LayoutParams(-1,dp(130))}
    }
    private inner class Stickers:ListAdapter<Sticker,Holder>(object:DiffUtil.ItemCallback<Sticker>(){
        override fun areItemsTheSame(a:Sticker,b:Sticker)=a.id==b.id
        override fun areContentsTheSame(a:Sticker,b:Sticker)=a==b
    }) {
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=Holder(LinearLayout(this@MainActivity))
        override fun onBindViewHolder(holder:Holder,position:Int) {
            val s=getItem(position)
            holder.title.text=(if(s.favorite)"★ " else "")+s.name
            holder.image.contentDescription=s.name
            holder.image.load(LibraryDb.file(this@MainActivity,s),loader){size(192)}
            holder.box.setOnClickListener{share(s)}
            holder.box.setOnLongClickListener{menu(s);true}
        }
        override fun onViewRecycled(holder:Holder){holder.image.dispose();holder.image.setImageDrawable(null);super.onViewRecycled(holder)}
    }
}
