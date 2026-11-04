package io.github.stickerbox.hook

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.util.ArrayDeque

/** Mount only into a positively identified keyboard area; never wrap the full-screen InputView.
 * Does not read text, content descriptions, or keyboard keys. Unknown structures are rejected.
 */
object KeyboardMount {
    class Mount internal constructor(private val strip:View,private val overlay:View,
        private val column:LinearLayout,private val listener:View.OnLayoutChangeListener) {
        fun remove() {
            column.removeOnLayoutChangeListener(listener)
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            (strip.parent as? ViewGroup)?.removeView(strip)
        }
    }
    fun attach(input:View,strip:View,overlay:View,stripHeight:Int):Mount? {
        val queue=ArrayDeque<Pair<View,Int>>()
        queue.add(input to 0)
        val candidates=mutableListOf<Pair<FrameLayout,LinearLayout>>()
        var visited=0
        while(queue.isNotEmpty() && visited++<2048) {
            val (view,depth)=queue.removeFirst()
            if(view is FrameLayout && view.tag is String &&
                (view.tag as String).contains("keyboard-base-area")) {
                val columns=(0 until view.childCount).map{view.getChildAt(it)}.filterIsInstance<LinearLayout>()
                    .filter{it.orientation==LinearLayout.VERTICAL && it.childCount>0}
                if(columns.size==1)candidates+=view to columns.single()
            }
            if(view is ViewGroup && depth<20) {
                for(i in 0 until view.childCount)queue.add(view.getChildAt(i) to depth+1)
            }
        }
        if(queue.isNotEmpty() || candidates.size!=1)return null
        val (area,column)=candidates.single()
        if(strip.parent!=null || overlay.parent!=null)return null
        try {
            // Original views and their parents stay intact. The opaque panel is a sibling overlay.
            overlay.isClickable=true // Consume empty-panel touches instead of hitting keys underneath.
            column.addView(strip,0,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,stripHeight))
            // MATCH_PARENT under a WRAP_CONTENT FrameLayout can expand to the full screen.
            // Size against the native column instead, so showing the panel never grows the IME.
            area.addView(overlay,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0)
                .apply{topMargin=stripHeight})
        } catch(t:Throwable) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            (strip.parent as? ViewGroup)?.removeView(strip)
            throw t
        }
        fun resize() {
            val usedStrip=if(strip.visibility==View.GONE)0 else stripHeight
            val height=(column.height-usedStrip).coerceAtLeast(0)
            val top=column.top+usedStrip
            val params=overlay.layoutParams as FrameLayout.LayoutParams
            if(params.height!=height || params.topMargin!=top) {
                params.height=height;params.topMargin=top;overlay.layoutParams=params
            }
        }
        val listener=View.OnLayoutChangeListener{_,_,_,_,_,_,_,_,_->resize()}
        column.addOnLayoutChangeListener(listener)
        resize()
        return Mount(strip,overlay,column,listener)
    }
}
