package one.njk.sao.floating.search

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.util.AttributeSet
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
import one.njk.sao.floating.emoji.EmojiKeywords
import one.njk.sao.floating.emoji.EmojiShortcode
import one.njk.sao.source.MediaQuery
import one.njk.sao.source.SearchSuggestions
import kotlinx.coroutines.delay
import kotlin.math.ceil

data class SourceCompletionContext(val sourceId: String,val query: MediaQuery)

/** Lifecycle/cursor-safe local and provider completion, hosted by Material's autocomplete component. */
open class SearchCompletionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = androidx.appcompat.R.attr.autoCompleteTextViewStyle,
) : MaterialAutoCompleteTextView(context, attrs, defStyleAttr) {
    private data class Request(val text: String,val cursor: Int,val token: EmojiShortcode?,val source: SourceCompletionContext?)
    private var requests: MutableStateFlow<Request?>? = null
    private var loaded: Request? = null
    private var dismissed: Request? = null
    private var owner: LifecycleOwner? = null
    private var allowed: (() -> Boolean)? = null
    private var anchor: View? = null
    private var inserting = false
    private var changingPopup = false
    private var consumedKey = KeyEvent.KEYCODE_UNKNOWN
    private val suggestions = SearchCompletionAdapter()
    private var keywords: EmojiKeywords? = null
    private var sourceContext: (() -> SourceCompletionContext?)? = null
    private var sourceLookup: (suspend (SourceCompletionContext,Int) -> SearchSuggestions)? = null
    private var editRange: IntRange? = null

    fun sourceSuggestions(context: () -> SourceCompletionContext?,lookup: suspend (SourceCompletionContext,Int) -> SearchSuggestions) {
        sourceContext=context; sourceLookup=lookup; refreshSuggestions()
    }

    fun bind(keywords: EmojiKeywords?, owner: LifecycleOwner, anchor: View, allowed: () -> Boolean) {
        check(requests == null)
        this.owner = owner; this.anchor = anchor; this.allowed = allowed
        this.keywords = keywords
        val changes = MutableStateFlow<Request?>(null).also { requests = it }
        setAdapter(suggestions)
        dropDownAnchor = anchor.id
        val density = resources.displayMetrics.density
        setDropDownBackgroundDrawable(MaterialShapeDrawable().apply {
            setCornerSize(16 * density)
            fillColor = ColorStateList.valueOf(MaterialColors.getColor(this@SearchCompletionView,
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
                        val result = try {
                            if(request.token!=null) {
                                val matches=keywords!!.suggest(request.token.keyword)
                                SearchSuggestions(request.token.start,request.token.end,emptyList()) to matches
                            } else {
                                delay(300)
                                val value=sourceLookup!!(requireNotNull(request.source),request.cursor).validate(request.text,request.cursor)
                                value to null
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { SearchSuggestions.empty(request.cursor) to null }
                        if (currentRequest() != request || dismissed == request) return@collectLatest
                        loaded = request
                        editRange=result.first.start until result.first.end
                        changingPopup = true
                        try {
                            if(result.second!=null) suggestions.emoji(requireNotNull(result.second)) else suggestions.source(result.first.items)
                        } finally { changingPopup = false }
                        configurePopup()
                        if (suggestions.isEmpty) hidePopup() else showDropDown()
                    }
                } finally { hidePopup() }
            }
        }
    }

    private fun currentRequest(): Request? {
        if (requests == null || !isFocused || allowed?.invoke() != true ||
            owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true) return null
        val value = text?.toString().orEmpty()
        val token = if(keywords!=null) EmojiShortcode.at(value,selectionStart,selectionEnd) else null
        if(token!=null) return Request(value,selectionStart,token,null)
        if(selectionStart!=selectionEnd || selectionStart !in 1..value.length || value.length<2 || value[selectionStart-1].isWhitespace()) return null
        val source=sourceContext?.invoke()?.takeIf { sourceLookup!=null } ?: return null
        return Request(value,selectionStart,null,source.copy(query=source.query.copy(text=value)))
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
        val replacement = value?.toString() ?: return false
        if (loaded == null || suggestions.rows.none { suggestions.value(it) == replacement }) return false
        val request = currentRequest()
        // A tap on a row from an older cursor/query may never overwrite new input.
        if (request == null || request != loaded || request == dismissed) return true
        val editable = text ?: return true
        val range=editRange ?: return true
        inserting = true
        beginBatchEdit()
        try {
            clearComposingText()
            editable.replace(range.first,range.last+1,replacement)
            setSelection(range.first+replacement.length)
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
                if (keyCode == KeyEvent.KEYCODE_ENTER) onEditorAction((imeOptions and EditorInfo.IME_MASK_ACTION)
                    .takeIf { it!=EditorInfo.IME_ACTION_NONE && it!=EditorInfo.IME_ACTION_UNSPECIFIED } ?: EditorInfo.IME_ACTION_SEARCH) else performClick()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == consumedKey) { consumedKey = KeyEvent.KEYCODE_UNKNOWN; return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun configurePopup() {
        val width = anchor?.width?.takeIf { it > 0 } ?: width
        if (width <= 0) return
        dropDownWidth = width
        if (suggestions.isEmpty) return
        val row=suggestions.getView(0,null,android.widget.FrameLayout(context))
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        // Like Telegram, show 3½ rows as a scroll affordance. Android clips/positions
        // the dropdown within the available space above the existing IME.
        dropDownHeight = ceil(minOf(suggestions.count.toFloat(), 3.5f) * row.measuredHeight).toInt()
    }
}
