package com.smriti.aqua

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.smriti.aqua.ui.AquaTheme
import com.smriti.aqua.ui.ChatScreen
import com.smriti.aqua.ui.Cyan
import com.smriti.aqua.ui.CyanSoft
import com.smriti.aqua.ui.NavyDeep
import com.smriti.aqua.ui.ScanScreen
import com.smriti.aqua.ui.ScanViewModel
import com.smriti.aqua.ui.TimelineScreen
import com.smriti.aqua.ui.WarmFaint
import com.smriti.aqua.ui.WarmWhite

/** Single activity: permission gate + bottom-nav host (Scan / Memory / Ask). */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            AquaTheme {
                val vm: ScanViewModel = viewModel()

                // Runtime permissions: mic always; notifications on API 33+.
                val permissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { /* grants are re-checked implicitly by the audio pipeline */ }
                LaunchedEffect(Unit) { launcher.launch(permissions) }

                AquaNavHost(vm, onBack = ::finish)
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("scan", "Scan", Icons.Filled.RadioButtonChecked),
    Tab("memory", "Memory", Icons.Filled.Notes),
    Tab("ask", "Ask", Icons.Filled.Lightbulb),
)

@Composable
private fun AquaNavHost(vm: ScanViewModel, onBack: () -> Unit) {
    val nav = rememberNavController()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = NavyDeep,
        topBar = { BackToHomeBar(onBack) },
        bottomBar = {
            NavigationBar(containerColor = NavyDeep, tonalElevation = 0.dp) {
                val backStackEntry by nav.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Cyan,
                            selectedTextColor = Cyan,
                            indicatorColor = CyanSoft,
                            unselectedIconColor = WarmWhite,
                            unselectedTextColor = WarmFaint,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "scan",
            modifier = Modifier.padding(padding),
        ) {
            composable("scan") { ScanScreen(vm) }
            composable("memory") { TimelineScreen(vm) }
            composable("ask") { ChatScreen(vm) }
        }
    }
}

@Composable
private fun BackToHomeBar(onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 8.dp, top = 4.dp),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = null,
                tint = WarmWhite,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text("Home", color = WarmWhite)
        }
    }
}
