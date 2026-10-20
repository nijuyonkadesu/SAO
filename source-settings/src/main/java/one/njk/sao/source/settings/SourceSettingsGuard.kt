package one.njk.sao.source.settings

import android.app.Dialog
import android.os.Bundle
import android.graphics.Rect
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.core.os.bundleOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Shared settings navigation. Only the extension can determine whether its private draft changed. */
class SourceSettingsGuard(private val activity: AppCompatActivity,private val saveButton: View,private val changed: ()->Boolean,
    private val working: ()->Boolean,private val save: ()->Unit) {
    init {
        activity.onBackPressedDispatcher.addCallback(activity,object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed()=leave()
        })
        activity.supportFragmentManager.setFragmentResultListener(UnsavedSourceDialog.RESULT,activity) { _,result ->
            when(result.getString("action")) {
                "save" -> if(!working()) {
                    // Keep the footer and any validation error in view when saving from the dialog.
                    saveButton.requestRectangleOnScreen(Rect(0,0,saveButton.width,saveButton.height),true)
                    save()
                }
                "discard" -> activity.finish()
            }
        }
    }
    fun leave() {
        if(!changed()) { activity.finish(); return }
        val fragments=activity.supportFragmentManager
        if(!fragments.isStateSaved && fragments.findFragmentByTag(UnsavedSourceDialog.RESULT)==null)
            UnsavedSourceDialog().apply { arguments=bundleOf("canSave" to !working()) }.show(fragments,UnsavedSourceDialog.RESULT)
    }
}

/** Fragment restoration keeps the warning intact across rotation without retaining an Activity. */
class UnsavedSourceDialog: DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.source_settings_unsaved_title).setMessage(R.string.source_settings_unsaved_message)
        .apply { if(requireArguments().getBoolean("canSave")) setPositiveButton(R.string.source_settings_save) { _,_->result("save") } }
        .setNegativeButton(R.string.source_settings_keep_editing,null)
        .setNeutralButton(R.string.source_settings_discard) { _,_->result("discard") }.create()
    private fun result(action: String) { parentFragmentManager.setFragmentResult(RESULT,bundleOf("action" to action)) }
    companion object { internal const val RESULT="source.settings.unsaved" }
}
