package one.njk.sao

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import one.njk.sao.databinding.FragmentFirstBinding

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment(), MenuProvider {

    private var _binding: FragmentFirstBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        // Menu Provider method: https://stackoverflow.com/questions/71917856/sethasoptionsmenuboolean-unit-is-deprecated-deprecated-in-java
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider( this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentFirstBinding.inflate(inflater, container, false)

        _binding!!.bottomAppBar.setOnMenuItemClickListener {
            when(it.itemId){
                R.id.bookmark -> {
                    // TODO: Replace with viewmodel bookmark to fav list
                    Toast.makeText(context, "bookmark", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.download -> {
                    Toast.makeText(context, "download", Toast.LENGTH_SHORT).show()
                    // TODO: Replace with viewmodel download with URI intent
                    true
                }
                else -> false
            }
        }
        _binding!!.share.setOnClickListener {
            // TODO: Replace with viewmodel share intent
        }
        return binding.root

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.top_app_bar, menu)
    }
    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        // TopAppBar Menu redirector
            return when(menuItem.itemId){
                R.id.edit -> {
                    Toast.makeText(context, "edit", Toast.LENGTH_SHORT).show()
                    // TODO: Replace with viewmodel edit favourite list
                    true
                }
                R.id.settings -> {
                    Toast.makeText(context, "settings", Toast.LENGTH_SHORT).show()
                    // TODO: maybe, this has to be handled using Navigation listener
                    true
                }
                else -> false
            }
        }
}