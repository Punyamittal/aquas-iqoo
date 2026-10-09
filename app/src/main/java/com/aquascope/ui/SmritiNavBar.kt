package com.aquascope.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aquascope.R
import com.aquascope.databinding.ViewSmritiNavBinding

class SmritiNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val binding: ViewSmritiNavBinding =
        ViewSmritiNavBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER
        setBackgroundResource(R.drawable.bg_nav_bar)
        val d = resources.displayMetrics.density
        setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (14 * d).toInt())
    }

    fun select(tab: Int) {
        style(binding.navHomeIcon, binding.navHomeLabel, tab == SmritiNav.TAB_HOME)
        style(binding.navScanIcon, binding.navScanLabel, tab == SmritiNav.TAB_SCAN)
        style(binding.navMemoryIcon, binding.navMemoryLabel, tab == SmritiNav.TAB_MEMORY)
        style(binding.navAskIcon, binding.navAskLabel, tab == SmritiNav.TAB_ASK)
        style(binding.navSystemIcon, binding.navSystemLabel, tab == SmritiNav.TAB_SYSTEM)
    }

    fun setOnTab(listener: (Int) -> Unit) {
        binding.navHome.setOnClickListener { listener(SmritiNav.TAB_HOME) }
        binding.navScan.setOnClickListener { listener(SmritiNav.TAB_SCAN) }
        binding.navMemory.setOnClickListener { listener(SmritiNav.TAB_MEMORY) }
        binding.navAsk.setOnClickListener { listener(SmritiNav.TAB_ASK) }
        binding.navSystem.setOnClickListener { listener(SmritiNav.TAB_SYSTEM) }
    }

    private fun style(icon: ImageView, label: TextView, selected: Boolean) {
        icon.setBackgroundResource(
            if (selected) R.drawable.bg_nav_selected else R.drawable.bg_nav_circle
        )
        val color = ContextCompat.getColor(
            context,
            if (selected) R.color.cyan else R.color.nav_idle
        )
        icon.setColorFilter(color)
        label.setTextColor(color)
    }
}
