package one.njk.sao.floating

import android.app.Dialog
import android.graphics.Rect
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDialog
import androidx.core.os.bundleOf
import androidx.core.view.*
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import coil3.asDrawable
import coil3.memory.MemoryCache
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import one.njk.sao.R
import one.njk.sao.source.*
import org.json.JSONObject
import javax.inject.Inject

internal class MediaViewerState(private val saved: SavedStateHandle) : ViewModel() {
    var position: Long
        get()=saved["playhead"] ?: 0L
        set(value) { saved["playhead"]=value }
    var zoom: ZoomMediaView.Position
        get()=ZoomMediaView.Position(saved["zoom"] ?: 1f,saved["x"] ?: .5f,saved["y"] ?: .5f)
        set(value) { saved["zoom"]=value.zoom; saved["x"]=value.x; saved["y"]=value.y }
}

/** A window above home: no navigation replacement, carousel reconstruction or preference writes. */
@AndroidEntryPoint
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaViewerFragment : DialogFragment() {
    @Inject @HomeSources lateinit var source: MediaSource
    @Inject lateinit var loader: ImageLoader
    private val state: MediaViewerState by viewModels()
    private var image: ZoomMediaView?=null
    private var root: View?=null
    private var exiting=false
    private data class Views(val still: ZoomMediaView,val video: PlayerView,val poster: ImageView,val progress: View,val retry: View)
    private var views: Views?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setStyle(STYLE_NO_TITLE,R.style.Theme_SAO_Viewer)
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = AppCompatDialog(requireContext(),theme).also { dialog ->
        dialog.onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed()=close()
        })
    }
    override fun onCreateView(inflater: LayoutInflater,container: ViewGroup?,savedInstanceState: Bundle?): View {
        val context=requireContext()
        val item=SourceWire.item(JSONObject(requireArguments().getString("item")!!))
        val surface=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface)) }
        val toolbar=MaterialToolbar(context).apply {
            id=R.id.media_viewer_toolbar
            title=item.filename ?: item.title
            setNavigationIcon(R.drawable.ic_picker_back)
            setNavigationContentDescription(R.string.media_viewer_close)
            setNavigationOnClickListener { close() }
        }
        surface.addView(toolbar,LinearLayout.LayoutParams(-1,-2))
        val body=FrameLayout(context)
        surface.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        val still=ZoomMediaView(context).apply { id=R.id.media_viewer_image; position=state.zoom; contentDescription=item.title }
        image=still; body.addView(still,FrameLayout.LayoutParams(-1,-1))
        val poster=ImageView(context).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER
            // Animated drawables have a mutable callback; never steal the carousel's drawable.
            val cached=loader.memoryCache?.get(MemoryCache.Key(previewMemoryKey(item)))?.image?.asDrawable(resources)
            setImageDrawable(cached?.constantState?.newDrawable(resources)?.mutate())
        }
        val video=PlayerView(context).apply {
            id=R.id.media_viewer_player
            visibility=View.GONE; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        body.addView(video,FrameLayout.LayoutParams(-1,-1))
        body.addView(poster,FrameLayout.LayoutParams(-1,-1))
        val progress=CircularProgressIndicator(context).apply { id=R.id.media_viewer_progress }
        body.addView(progress,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        val retry=MaterialButton(context).apply { id=R.id.media_viewer_retry; setText(R.string.media_viewer_retry); visibility=View.GONE }
        body.addView(retry,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        ViewCompat.setOnApplyWindowInsetsListener(surface) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        views=Views(still,video,poster,progress,retry)
        root=surface
        return surface.also { view -> view.doOnAttach { ViewCompat.requestApplyInsets(it) }
            // Work belongs to the view lifecycle, not the application or the underlying home screen.
            view.doOnPreDraw {
                if(savedInstanceState==null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    val start=androidx.core.os.BundleCompat.getParcelable(requireArguments(),"bounds",Rect::class.java)
                    if(start!=null && !start.isEmpty) {
                        view.pivotX=view.width/2f; view.pivotY=view.height/2f
                        view.scaleX=(start.width().toFloat()/view.width).coerceIn(.25f,1f)
                        view.scaleY=(start.height().toFloat()/view.height).coerceIn(.25f,1f)
                        view.translationX=start.exactCenterX()-view.width/2f
                        view.translationY=start.exactCenterY()-view.height/2f
                        view.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                            .setDuration(170).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    }
                }
            }
        }
    }
    override fun onViewCreated(view: View,savedInstanceState: Bundle?) {
        val item=SourceWire.item(JSONObject(requireArguments().getString("item")!!))
        val (still,video,poster,progress,retry)=requireNotNull(views)
        fun ready() {
            progress.isVisible=false
            poster.animate().alpha(0f).setDuration(100).withEndAction { poster.isVisible=false }.start()
        }
        var attempt: Job?=null
        fun load() {
            attempt?.cancel()
            attempt=viewLifecycleOwner.lifecycleScope.launch {
                progress.isVisible=true; retry.isVisible=false
                try {
                    source.withSource(requireArguments().getString("source")!!) { scoped ->
                        if(item.hasMotion) {
                            still.isVisible=false; video.isVisible=true
                            MediaPlaybackRenderer.play(video,scoped,item,original=true,muted=false,startPosition=state.position,
                                position={ state.position=it }) { ready() }
                        } else scoped.withRendition(item,Rendition.ORIGINAL) { handle ->
                            MediaDecodeInputs.withLocalFile(handle,requireContext().cacheDir) { file -> still.show(file,loader,item.mimeType) { ready() } }
                        }
                    }
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { progress.isVisible=false; retry.isVisible=true }
            }
        }
        retry.setOnClickListener { load() }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            load(); try { awaitCancellation() } finally { attempt?.cancelAndJoin() }
        } }
    }
    override fun onStart() { super.onStart(); dialog?.window?.apply {
        setLayout(-1,-1); setBackgroundDrawableResource(android.R.color.transparent)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this,false)
        val surface=com.google.android.material.color.MaterialColors.getColor(requireView(),com.google.android.material.R.attr.colorSurface)
        androidx.core.view.WindowCompat.getInsetsController(this,decorView).apply {
            isAppearanceLightStatusBars=com.google.android.material.color.MaterialColors.isColorLight(surface)
            isAppearanceLightNavigationBars=isAppearanceLightStatusBars
        }
        setWindowAnimations(0)
    } }
    private fun close() {
        if(exiting) return
        exiting=true
        val view=root
        if(view!=null && android.animation.ValueAnimator.areAnimatorsEnabled()) view.animate().alpha(0f).scaleX(.97f).scaleY(.97f)
            .setDuration(120).withEndAction { dismiss() }.start()
        else dismiss()
    }
    override fun onStop() { image?.let { state.zoom=it.position }; super.onStop() }
    override fun onDestroyView() { root?.animate()?.cancel(); root=null; image=null; views=null; super.onDestroyView() }
    override fun onDismiss(dialog: android.content.DialogInterface) { super.onDismiss(dialog); parentFragmentManager.setFragmentResult(TAG,Bundle.EMPTY) }
    companion object {
        const val TAG="media-viewer"
        fun create(item: MediaItem,sourceId: String,bounds: Rect?)=MediaViewerFragment().apply {
            arguments=bundleOf("item" to SourceWire.item(item).toString(),"source" to sourceId,"bounds" to bounds)
        }
    }
}
