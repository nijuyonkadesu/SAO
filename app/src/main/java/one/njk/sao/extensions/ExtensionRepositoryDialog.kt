package one.njk.sao.extensions

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import one.njk.sao.R

/** A repository only supplies catalog metadata; installation and source-access consent stay separate. */
class ExtensionRepositoryDialog: DialogFragment() {
    private val model: ExtensionCatalogViewModel by activityViewModels()
    private var input: TextInputEditText?=null
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val editor=TextInputEditText(requireContext()).apply {
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true); setText(savedInstanceState?.getString("url").orEmpty())
        }
        input=editor
        val field=TextInputLayout(requireContext()).apply {
            val padding=(24*resources.displayMetrics.density).toInt()
            setPadding(padding,padding/2,padding,0)
            hint=getString(R.string.extension_repository_url)
            helperText=getString(R.string.extension_repository_help)
            addView(editor)
        }
        return MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.extension_repository_add)
            .setView(field).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.extension_repository_add,null).create().apply {
                setOnShowListener { getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val url=runCatching { RepositoryUrl.normalize(editor.text.toString()) }.getOrNull()
                    when {
                        url==null -> field.error=getString(R.string.extension_repository_invalid)
                        url in model.state.value.repositories -> field.error=getString(R.string.extension_repository_duplicate)
                        else -> { model.addRepository(url); dismiss() }
                    }
                } }
            }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("url",input?.text?.toString()); super.onSaveInstanceState(outState) }
    override fun onDestroyView() { input=null; super.onDestroyView() }
    companion object { const val TAG="extension-repository" }
}
