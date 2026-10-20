package one.njk.sao.extension.local

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputEditText
import one.njk.sao.source.ExtensionProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MetadataAdditionsDeviceTest {
    @Test fun editorShowsOnlyAdditionsInEditableFieldsAndRetainsGeneratedTextAfterSave() {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val app=instrumentation.targetContext
        val store=LocalStore.get(app); val id=UUID.randomUUID().toString()
        val root=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root")
        val name="metadata-$id.png"; val file=File(app.filesDir,"test-documents/$name").apply { parentFile!!.mkdirs() }
        val bitmap=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.rgb(220,235,255)); drawText("HELLO WORLD",70f,200f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(30,60,100); textSize=54f }) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        try {
            val library=store.save(id,"Reactions",listOf(Folder(root.toString(),"Reactions"))); store.pause(id,true)
            val uri=DocumentsContract.buildDocumentUriUsingTree(root,"root/$name")
            store.discovered(id,"fixture",root.toString(),uri.toString(),name,name,"image/png",1,file.length(),library.revision)
            val asset=store.pending(id)!!
            store.analyzed(asset,Analysis(listOf("Greeting","Text"),"HELLO WORLD"),false,null)
            store.corrections(asset.id,id,"A greeting","Friendly reaction","Happy, 👋",ocr="Extra words")
            ActivityScenario.launch<MetadataEditorActivity>(Intent(app,MetadataEditorActivity::class.java)
                .putExtra(ExtensionProtocol.PROFILE,id).putExtra("asset",asset.id)).use { scenario ->
                val until=SystemClock.elapsedRealtime()+10000; var ready=false
                while(!ready && SystemClock.elapsedRealtime()<until) {
                    scenario.onActivity { activity -> ready=activity.findViewById<TextInputEditText>(R.id.tags).text.toString()=="Happy, 👋" }
                    SystemClock.sleep(40)
                }
                assertTrue(ready)
                scenario.onActivity { activity ->
                    assertEquals("Extra words",activity.findViewById<TextInputEditText>(R.id.ocr).text.toString())
                    assertTrue(activity.findViewById<android.widget.TextView>(R.id.auto_ocr).text.toString().contains("HELLO WORLD"))
                    assertTrue(activity.findViewById<android.widget.TextView>(R.id.auto_labels).text.toString().contains("Greeting"))
                }
                if(InstrumentationRegistry.getArguments().getString("visualPause")=="true") {
                    val resume=File(app.cacheDir,"metadata-visual-continue").apply { delete() }
                    val marker=File(app.cacheDir,"metadata-visual-ready").apply { writeText("ready") }
                    try { val deadline=SystemClock.elapsedRealtime()+50000
                        while(!resume.exists() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(40)
                        assertTrue(resume.exists())
                    } finally { marker.delete(); resume.delete() }
                }
                scenario.onActivity { activity ->
                    activity.findViewById<TextInputEditText>(R.id.tags).setText("Wave")
                    activity.findViewById<TextInputEditText>(R.id.ocr).setText("More words")
                    activity.findViewById<android.view.View>(R.id.save).performClick()
                }
                val deadline=SystemClock.elapsedRealtime()+10000
                while(store.asset(asset.id,id).tags!="Wave" && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(40)
                val current=store.asset(asset.id,id)
                assertEquals(listOf("Greeting","Text","Wave"),current.effectiveTags)
                assertEquals("HELLO WORLD\nMore words",current.effectiveOcr)
            }
        } finally { FolderIndexer.cancel(app,id); store.delete(id); file.delete() }
    }
}
