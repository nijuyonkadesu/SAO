package one.njk.sao.source.settings

import com.google.android.material.chip.Chip

/** Resetting scope is immediate; an album becomes selected only after its chooser returns a value. */
class SourceScopeChips(private val all: Chip,private val album: Chip,onAll: ()->Unit,onAlbum: ()->Unit) {
    init {
        all.setOnClickListener { onAll(); all.isChecked=true; album.isActivated=false }
        album.setOnClickListener { onAlbum() }
    }
    fun render(albumSelected: Boolean,albumLabel: String,enabled: Boolean) {
        all.isChecked=!albumSelected; all.isEnabled=enabled
        album.isActivated=albumSelected; album.text=albumLabel; album.isEnabled=enabled
    }
}
