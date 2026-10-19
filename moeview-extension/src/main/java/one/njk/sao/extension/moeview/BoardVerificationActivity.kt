package one.njk.sao.extension.moeview

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import one.njk.sao.extension.moeview.databinding.ActivityBoardVerificationBinding
import one.njk.sao.moeview.data.ApiKind
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.SfwMode
import one.njk.sao.moeview.data.ratingClause

/** Visible, user-completed verification only. No JS bridge, automation or background WebView. */
class BoardVerificationActivity : AppCompatActivity() {
    private var web: WebView?=null
    @SuppressLint("SetJavaScriptEnabled") // Cloudflare's visible challenge requires JavaScript.
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this); super.onCreate(savedInstanceState); enableEdgeToEdge()
        val board=Board.entries.firstOrNull { it.name==intent.getStringExtra("board") } ?: run { finish(); return }
        val binding=ActivityBoardVerificationBinding.inflate(layoutInflater); setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        binding.cancel.setOnClickListener { finish() }
        val origin=board.directBase
        binding.finishVerification.setOnClickListener {
            val cookies=MoeviewAuthentication.cloudflareCookies(CookieManager.getInstance().getCookie(origin).orEmpty())
            if(!cookies.split(';').any { it.trim().startsWith("cf_clearance=") }) binding.status.setText(R.string.verification_missing)
            else {
                setResult(RESULT_OK,Intent().putExtra("board",board.name).putExtra("cookies",cookies).putExtra("agent",binding.web.settings.userAgentString))
                CookieManager.getInstance().flush(); finish()
            }
        }
        web=binding.web.apply {
            settings.javaScriptEnabled=true; settings.domStorageEnabled=true
            settings.allowFileAccess=false; settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false); settings.javaScriptCanOpenWindowsAutomatically=false
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient=object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean =
                    request.isForMainFrame && (request.url.scheme!="https" || request.url.host!=Uri.parse(origin).host || request.url.port !in setOf(-1,443))
                override fun onRenderProcessGone(view: WebView,detail: RenderProcessGoneDetail): Boolean { finish(); return true }
            }
            val safe=ratingClause(board.apiKind,SfwMode.SFW).orEmpty()
            val path=when(board.apiKind) { ApiKind.MOEBOORU -> "/post?tags="; ApiKind.DANBOORU -> "/posts?tags="; ApiKind.GELBOORU -> "/index.php?page=post&s=list&tags=" }
            loadUrl(origin+path+Uri.encode(safe))
        }
    }
    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() { web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }; web=null; super.onDestroy() }
}
