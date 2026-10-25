package one.njk.sao

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import one.njk.sao.databinding.FragmentSettingsBinding
import one.njk.sao.viewmodels.ArtsViewModel

@AndroidEntryPoint
class SettingsFragment: Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: ArtsViewModel by activityViewModels()
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            val chosen = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
            languageSelect.isActivated = chosen.isNotEmpty()
            languageSelect.text = if (chosen.isEmpty()) getString(R.string.language_system) else AppLanguages.label(chosen.substringBefore(','))
            languageSelect.contentDescription = getString(R.string.language_current, languageSelect.text)
            languageSelect.setOnClickListener {
                val tags = listOf("") + AppLanguages.choices(requireContext())
                val labels = tags.map { if (it.isEmpty()) getString(R.string.language_system) else AppLanguages.label(it) }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.language_choose)
                    .setSingleChoiceItems(labels.toTypedArray(), tags.indexOf(chosen)) { dialog, position ->
                        dialog.dismiss()
                        AppLanguages.select(requireContext(), tags[position].ifEmpty { null })
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
            viewModel.nsfwMode.observe(viewLifecycleOwner){
                nsfwToggle.isChecked = it
                nsfwToggle.text = when(it){
                    true -> getString(R.string.enabled)
                    false -> getString(R.string.disabled)
                }
            }
            nsfwToggle.setOnClickListener { viewModel.toggleNsfwMode() }
            exportingToggle.setOnClickListener { viewModel.exportFav() }
            homeCategories.setOnClickListener { startActivity(android.content.Intent(requireContext(),one.njk.sao.floating.HomeCategoriesActivity::class.java)) }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
