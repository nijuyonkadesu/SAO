package one.njk.sao.floating.emoji

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.databinding.ItemEmojiSuggestionBinding
import kotlin.math.ceil

/** Telegram-style cursor shortcuts, hosted by the official Material autocomplete component. */
class EmojiSearchView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = androidx.appcompat.R.attr.autoCompleteTextViewStyle,
) : MaterialAutoCompleteTextView(context, attrs, defStyleAttr) {
    private data class Request(val text: String, val cursor: Int, val token: EmojiShortcode)
    private var requests: MutableStateFlow<Request?>? = null
    private var loaded: Request? = null
    private var dismissed: Request? = null
    private var owner: LifecycleOwner? = null
    private var allowed: (() -> Boolean)? = null
    private var anchor: View? = null
    private var inserting = false
    private var changingPopup = false
    private var consumedKey = KeyEvent.KEYCODE_UNKNOWN
    private val suggestions = EmojiSuggestionAdapter()

    fun bind(keywords: EmojiKeywords, owner: LifecycleOwner, anchor: View, allowed: () -> Boolean) {
        check(requests == null)
        this.owner = owner; this.anchor = anchor; this.allowed = allowed
        val changes = MutableStateFlow<Request?>(null).also { requests = it }
        setAdapter(suggestions)
        dropDownAnchor = anchor.id
        val density = resources.displayMetrics.density
        setDropDownBackgroundDrawable(MaterialShapeDrawable().apply {
            setCornerSize(16 * density)
            fillColor = ColorStateList.valueOf(MaterialColors.getColor(this@EmojiSearchView,
                com.google.android.material.R.attr.colorSurfaceContainerHigh))
        })
        setOnDismissListener {
            if (!changingPopup && !inserting) {
                dismissed = currentRequest(); changes.value = null
            }
        }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { dismissSuggestions() }
        })
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    changes.collectLatest { request ->
                        if (request == null) return@collectLatest
                        val matches = try { keywords.suggest(request.token.keyword) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { emptyList() }
                        if (currentRequest() != request || dismissed == request) return@collectLatest
                        loaded = request
                        configurePopup(matches)
                        changingPopup = true
                        try { suggestions.submit(matches) } finally { changingPopup = false }
                        if (matches.isEmpty()) hidePopup() else showDropDown()
                    }
                } finally { hidePopup() }
            }
        }
    }

    private fun currentRequest(): Request? {
        if (requests == null || !isFocused || allowed?.invoke() != true ||
            owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true) return null
        val value = text?.toString().orEmpty()
        val token = EmojiShortcode.at(value, selectionStart, selectionEnd) ?: return null
        return Request(value, selectionStart, token)
    }

    fun refreshSuggestions() {
        // TextView can deliver selection callbacks from its super constructor.
        if (requests == null || inserting) return
        val request = currentRequest()?.takeUnless { it == dismissed }
        requests?.value = request
        if (request == null) { loaded = null; hidePopup() }
    }

    fun dismissSuggestions(): Boolean {
        val active = isPopupShowing
        dismissed = currentRequest(); requests?.value = null; loaded = null
        hidePopup()
        return active
    }

    private fun hidePopup() {
        changingPopup = true
        try { dismissDropDown() } finally { changingPopup = false }
    }

    override fun showDropDown() {
        val request = currentRequest() ?: return
        if (request != loaded || request == dismissed || suggestions.isEmpty || !isAttachedToWindow) return
        super.showDropDown()
    }

    // Framework filtering is disabled: disk parsing uses IO, ranking uses CPU, and
    // collectLatest plus the cursor snapshot prevents obsolete suggestions from opening.
    override fun performFiltering(text: CharSequence?, keyCode: Int) { refreshSuggestions() }
    override fun enoughToFilter() = currentRequest()?.let { it != dismissed } == true
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        refreshSuggestions()
    }
    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        refreshSuggestions()
    }

    override fun replaceText(text: CharSequence?) { complete(text) }

    // Material's touch-exploration/switch-access popup commits through this overload
    // instead of replaceText. Preserve the rest of the query in that path as well.
    override fun setText(text: CharSequence?, filter: Boolean) {
        if (!filter && complete(text)) return
        super.setText(text, filter)
    }

    private fun complete(value: CharSequence?): Boolean {
        val emoji = value?.toString() ?: return false
        if (loaded == null || suggestions.suggestions.none { it.emoji == emoji }) return false
        val request = currentRequest()
        // A tap on a row from an older cursor/query may never overwrite new input.
        if (request == null || request != loaded || request == dismissed) return true
        val editable = text ?: return true
        inserting = true
        beginBatchEdit()
        try {
            clearComposingText()
            editable.replace(request.token.start, request.token.end, emoji)
            setSelection(request.token.start + emoji.length)
        } finally { endBatchEdit(); inserting = false }
        dismissSuggestions()
        return true
    }

    fun completeHighlightedSuggestion(): Boolean {
        if (!isPopupShowing || listSelection == AdapterView.INVALID_POSITION) return false
        performCompletion(); return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isPopupShowing && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
            // Material's exposed-dropdown shortcut clicks the TextInputLayout end icon.
            // This field has a Clear icon, so retain ordinary editor behavior instead.
            consumedKey = keyCode
            if (event.repeatCount == 0) {
                if (keyCode == KeyEvent.KEYCODE_ENTER) onEditorAction(EditorInfo.IME_ACTION_SEARCH) else performClick()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == consumedKey) { consumedKey = KeyEvent.KEYCODE_UNKNOWN; return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun configurePopup(matches: List<EmojiSuggestion>) {
        val width = anchor?.width?.takeIf { it > 0 } ?: width
        if (width <= 0) return
        dropDownWidth = width
        if (matches.isEmpty()) return
        val row = ItemEmojiSuggestionBinding.inflate(LayoutInflater.from(context))
        row.emoji.text = matches.first().emoji
        row.keyword.text = matches.first().keyword
        row.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        // Like Telegram, show 3½ rows as a scroll affordance. Android clips/positions
        // the dropdown within the available space above the existing IME.
        dropDownHeight = ceil(minOf(matches.size.toFloat(), 3.5f) * row.root.measuredHeight).toInt()
    }
}
