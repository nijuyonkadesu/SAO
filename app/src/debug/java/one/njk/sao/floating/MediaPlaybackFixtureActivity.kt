package one.njk.sao.floating

import android.app.Activity
import android.os.Bundle
import one.njk.sao.R
import one.njk.sao.databinding.ItemFloatingMediaBinding

/** Account-free rendering fixture, excluded from production builds. */
class MediaPlaybackFixtureActivity : Activity() {
    lateinit var binding: ItemFloatingMediaBinding
        private set
    override fun onCreate(state: Bundle?) {
        setTheme(R.style.Theme_SAO)
        super.onCreate(state)
        binding = ItemFloatingMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.videoPreview.visibility = android.view.View.VISIBLE
    }
}
