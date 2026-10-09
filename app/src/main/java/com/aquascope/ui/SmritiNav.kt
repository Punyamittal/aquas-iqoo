package com.aquascope.ui

import android.app.Activity
import android.content.Intent
import com.aquascope.R
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Shared bottom nav: Home · Scan · Memory · Ask · System */
object SmritiNav {

    const val TAB_HOME = 0
    const val TAB_SCAN = 1
    const val TAB_MEMORY = 2
    const val TAB_ASK = 3
    const val TAB_SYSTEM = 4
    const val TAB_NONE = -1

    fun bind(activity: Activity, nav: BottomNavigationView, selected: Int) {
        nav.selectedItemId = when (selected) {
            TAB_HOME -> R.id.nav_home
            TAB_SCAN -> R.id.nav_scan
            TAB_MEMORY -> R.id.nav_memory
            else -> R.id.nav_ask
        }
        nav.setOnItemSelectedListener { item ->
            val tab = when (item.itemId) {
                R.id.nav_home -> TAB_HOME
                R.id.nav_scan -> TAB_SCAN
                R.id.nav_memory -> TAB_MEMORY
                R.id.nav_ask -> TAB_ASK
                else -> return@setOnItemSelectedListener false
            }
            openTab(activity, selected, tab)
            true
        }
    }

    fun bind(activity: Activity, nav: SmritiNavBar, selected: Int) {
        nav.select(selected)
        nav.setOnTab { tab -> openTab(activity, selected, tab) }
    }

    fun goToTab(activity: Activity, from: Int, to: Int) = openTab(activity, from, to)

    fun openSmritiAqua(activity: Activity) {
        activity.startActivity(Intent(activity, com.smriti.aqua.MainActivity::class.java))
    }

    fun openScreenMind(activity: Activity) {
        activity.startActivity(Intent(activity, ScreenMindActivity::class.java))
    }

    private fun openTab(activity: Activity, from: Int, to: Int) {
        if (to == from) return
        val target = when (to) {
            TAB_HOME -> SmritiHomeActivity::class.java
            TAB_SCAN -> MainActivity::class.java
            TAB_MEMORY -> MemoryTimelineActivity::class.java
            TAB_ASK -> AskSmritiActivity::class.java
            else -> LocalModelActivity::class.java
        }
        activity.startActivity(
            Intent(activity, target)
                .addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                .putExtra(TabSlide.EXTRA_FROM, from)
                .putExtra(TabSlide.EXTRA_TO, to)
                .putExtra(TabSlide.EXTRA_ANIMATE, true)
        )
        if (!isCurrentTab(activity, to)) {
            activity.finish()
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    private fun isCurrentTab(activity: Activity, tab: Int): Boolean =
        (tab == TAB_HOME && activity is SmritiHomeActivity) ||
            (tab == TAB_SCAN && activity is MainActivity) ||
            (tab == TAB_MEMORY && activity is MemoryTimelineActivity) ||
            (tab == TAB_ASK && activity is AskSmritiActivity) ||
            (tab == TAB_SYSTEM && activity is LocalModelActivity)
}
