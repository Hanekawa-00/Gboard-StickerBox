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
    class Mount internal constructor(private val strip:View,private val overlay:View) {
        fun remove() {
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
            area.addView(overlay,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT).apply{topMargin=stripHeight})
        } catch(t:Throwable) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            (strip.parent as? ViewGroup)?.removeView(strip)
            throw t
        }
        return Mount(strip,overlay)
    }
}
