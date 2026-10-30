package one.njk.sao.floating

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import one.njk.sao.R

/** Only this explicit positive action can authorize the certificate displayed to the user. */
class ExtensionTrustDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args=requireArguments()
        val fingerprint=args.getString(SIGNER).orEmpty().split(',').joinToString("\n") { it.chunked(2).joinToString(":") }
        return MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.source_trust_title)
            .setMessage(getString(R.string.source_trust_warning,args.getString(NAME),args.getString(PACKAGE),fingerprint))
            .setNegativeButton(android.R.string.cancel) { _,_->result(false) }
            .setPositiveButton(R.string.source_trust_allow) { _,_->result(true) }.create()
    }
    override fun onCancel(dialog: android.content.DialogInterface) { super.onCancel(dialog); result(false) }
    private fun result(allowed: Boolean) = parentFragmentManager.setFragmentResult(RESULT,Bundle(requireArguments()).apply { putBoolean(ALLOWED,allowed) })
    companion object {
        const val RESULT="source-extension-trust"
        const val PACKAGE="package"
        const val NAME="name"
        const val SIGNER="signer"
        const val ALLOWED="allowed"
        fun create(name: String,packageName: String,signer: String)=ExtensionTrustDialog().apply {
            arguments=Bundle().apply { putString(NAME,name); putString(PACKAGE,packageName); putString(SIGNER,signer) }
        }
    }
}
