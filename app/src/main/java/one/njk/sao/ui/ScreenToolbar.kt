package one.njk.sao.ui

import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import one.njk.sao.R

/** The bar belongs to the destination, so its title and content share the same transition. */
internal fun Fragment.screenToolbar(root: View,@StringRes title: Int,up: Boolean=true): MaterialToolbar =
    root.findViewById<MaterialToolbar>(R.id.topAppBar).apply {
        setTitle(title)
        if(up) {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { findNavController().navigateUp() }
        }
    }
