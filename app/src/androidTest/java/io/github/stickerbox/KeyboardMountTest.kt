package io.github.stickerbox

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stickerbox.hook.KeyboardMount
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic hierarchy tests on real Android Views; not a Gboard/LSPosed test. */
@RunWith(AndroidJUnit4::class)
class KeyboardMountTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    @Test fun keepsControlsInBottomKeyboardAreaWithoutReparentingNativeViews()=main {
        val root=FrameLayout(context)
        val area=FrameLayout(context).apply{tag=".keyboard-base-area"}
        val column=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        val native=View(context)
        column.addView(native,LinearLayout.LayoutParams(-1,500))
        area.addView(column,FrameLayout.LayoutParams(-1,-2))
        root.addView(area,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        val strip=View(context)
        val overlay=View(context).apply{visibility=View.GONE}
        val mount=KeyboardMount.attach(root,strip,overlay,40)
        assertNotNull(mount)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
        root.layout(0,0,1080,2400)
        assertSame(column,native.parent);assertSame(area,column.parent);assertSame(root,area.parent)
        assertEquals(2400,area.bottom);assertEquals(540,area.height);assertEquals(40,strip.height)
        assertSame(column,strip.parent);assertSame(area,overlay.parent)
        assertEquals(40,(overlay.layoutParams as FrameLayout.LayoutParams).topMargin)
        var nativeTouches=0
        native.setOnTouchListener{_,_->nativeTouches++;true}
        overlay.visibility=View.VISIBLE
        root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
        root.layout(0,0,1080,2400)
        assertEquals("Opening the panel must not expand the keyboard to full screen",540,area.height)
        val now=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(now,now+action,action,300f,(area.top+200).toFloat(),0)
            try{assertTrue(root.dispatchTouchEvent(event))}finally{event.recycle()}
        }
        assertEquals("Panel blank space must not type into the real keyboard",0,nativeTouches)
        mount!!.remove()
        assertEquals(1,column.childCount);assertSame(native,column.getChildAt(0));assertEquals(1,area.childCount)
    }
    @Test fun rejectsUnknownHierarchyWithoutMutatingIt()=main {
        val root=FrameLayout(context)
        val child=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;addView(View(context))}
        root.addView(child)
        val strip=View(context);val overlay=View(context)
        assertNull(KeyboardMount.attach(root,strip,overlay,40))
        assertEquals(1,root.childCount);assertNull(strip.parent);assertNull(overlay.parent)
    }
    @Test fun rejectsAmbiguousKeyboardAreas()=main {
        val root=FrameLayout(context)
        repeat(2){
            root.addView(FrameLayout(context).apply{
                tag=".keyboard-base-area"
                addView(LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;addView(View(context))})
            })
        }
        assertNull(KeyboardMount.attach(root,View(context),View(context),40))
        assertEquals(2,root.childCount)
    }
}
