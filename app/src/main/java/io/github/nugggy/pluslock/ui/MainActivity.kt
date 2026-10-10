package io.github.nugggy.pluslock.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nugggy.pluslock.core.Prefs

sealed class Screen {
    data object Home : Screen()
    data object Gestures : Screen()
    data object Lighting : Screen()
    data object Ring : Screen()
    data object Vault : Screen()
    data object Tricks : Screen()
    data object Profiles : Screen()
    data class ProfileEdit(val pkg: String) : Screen()
    data object Deck : Screen()
    data object Battery : Screen()
    data object Wallpaper : Screen()
    data object Updates : Screen()
    data object Look : Screen()
    data object Adb : Screen()
    data object Routines : Screen()
    data class RoutineEdit(val id: String) : Screen()
    data object Panel : Screen()
    data object ScreenTime : Screen()
    data object Backup : Screen()

    companion object {
        fun fromExtra(s: String?): Screen? = when (s) {
            "vault" -> Vault
            "updates" -> Updates
            "battery" -> Battery
            "panel" -> Panel
            else -> null
        }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SCREEN = "screen"
        @Volatile var isVisible = false
            private set
    }

    private var stack by mutableStateOf(listOf<Screen>(Screen.Home))
    /** Bumped on every resume so permission rows re-check themselves. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            PlusTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    val nav = Nav(
                        go = { stack = stack + it },
                        back = { if (stack.size > 1) stack = stack.dropLast(1) },
                    )
                    BackHandler(enabled = stack.size > 1) { nav.back() }
                    AnimatedContent(
                        targetState = stack.last(),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> HomeScreen(nav, resumeTick)
                            Screen.Gestures -> GesturesScreen(nav)
                            Screen.Lighting -> LightingScreen(nav)
                            Screen.Ring -> RingScreen(nav)
                            Screen.Vault -> VaultScreen(nav)
                            Screen.Tricks -> TricksScreen(nav)
                            Screen.Profiles -> ProfilesScreen(nav)
                            is Screen.ProfileEdit -> ProfileEditScreen(nav, screen.pkg)
                            Screen.Deck -> DeckScreen(nav)
                            Screen.Battery -> BatteryScreen(nav)
                            Screen.Wallpaper -> WallpaperScreen(nav)
                            Screen.Updates -> UpdatesScreen(nav, resumeTick)
                            Screen.Look -> LookScreen(nav, resumeTick)
                            Screen.Adb -> AdbScreen(nav, resumeTick)
                            Screen.Routines -> RoutinesScreen(nav)
                            is Screen.RoutineEdit -> RoutineEditScreen(nav, screen.id)
                            Screen.Panel -> PanelScreen(nav)
                            Screen.ScreenTime -> ScreenTimeScreen(nav, resumeTick)
                            Screen.Backup -> BackupScreen(nav)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val target = Screen.fromExtra(i?.getStringExtra(EXTRA_SCREEN)) ?: return
        stack = listOf(Screen.Home, target)
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        resumeTick++
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }
}

class Nav(val go: (Screen) -> Unit, val back: () -> Unit)
