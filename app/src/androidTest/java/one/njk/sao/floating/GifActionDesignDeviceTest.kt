package one.njk.sao.floating

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import one.njk.sao.R
import one.njk.sao.databinding.ActivityPickerBinding
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GifActionDesignDeviceTest {
    @Test fun selectedGifUsesM3TonesWithoutChangingItsSizeOrLabel() {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val app=instrumentation.targetContext
        instrumentation.runOnMainSync {
            for(night in listOf(Configuration.UI_MODE_NIGHT_NO,Configuration.UI_MODE_NIGHT_YES)) {
                val configuration=Configuration(app.resources.configuration).apply { uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
                val context=ContextThemeWrapper(app.createConfigurationContext(configuration),R.style.Theme_SAO_Picker)
                val binding=ActivityPickerBinding.inflate(LayoutInflater.from(context))
                val root=binding.pickerPanel
                val width=(400*context.resources.displayMetrics.density).toInt()
                val height=(520*context.resources.displayMetrics.density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); root.layout(0,0,width,height)
                binding.selectionTitle.text="Animation.mp4"; binding.stickerAction.isEnabled=true; binding.stickerAction.isCheckable=true
                val initial=binding.stickerAction.measuredWidth
                binding.stickerAction.bindCheckedAction(enabled=false,checked=false)
                binding.stickerAction.bindCheckedAction(enabled=true,checked=true)
                assertTrue("Re-enabling motion must restore the saved GIF choice",binding.stickerAction.isChecked)
                binding.stickerAction.bindCheckedAction(enabled=false,checked=false)
                assertFalse("Stills must clear the logical checked state even while disabled",binding.stickerAction.isChecked)
                for(checked in listOf(false,true)) {
                    binding.stickerAction.bindCheckedAction(enabled=true,checked=checked)
                    assertEquals("As GIF",binding.stickerAction.text.toString())
                    assertEquals(initial,binding.stickerAction.measuredWidth)
                    val attr=if(checked) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant
                    assertEquals(MaterialColors.getColor(binding.stickerAction,attr),binding.stickerAction.currentTextColor)
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(bitmap))
                        File(app.getExternalFilesDir("verification"),"gif-${if(night==Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-$checked.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
