package com.sclastro.recorder.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.data.prefs.ThemeMode
import com.sclastro.recorder.ui.editor.EditorScreen
import com.sclastro.recorder.ui.library.LibraryScreen
import com.sclastro.recorder.ui.library.TrashScreen
import com.sclastro.recorder.ui.player.MiniPlayerBar
import com.sclastro.recorder.ui.player.PlayerScreen
import com.sclastro.recorder.ui.record.RecordScreen
import com.sclastro.recorder.ui.settings.SettingsScreen
import com.sclastro.recorder.ui.settings.SettingsViewModel
import com.sclastro.recorder.ui.theme.RecorderTheme

private object Routes {
    const val RECORD = "record"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val PLAYER = "player/{id}"
    const val EDITOR = "editor/{id}"

    fun player(id: Long) = "player/$id"
    fun editor(id: Long) = "editor/$id"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderAppRoot(settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    RecorderTheme(darkTheme = dark) {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val showChrome = route == Routes.RECORD || route == Routes.LIBRARY
        val context = LocalContext.current

        Scaffold(
            topBar = {
                if (showChrome) {
                    TopAppBar(
                        title = { Text(if (route == Routes.LIBRARY) "Recordings" else "Record") },
                        actions = {
                            IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(),
                    )
                }
            },
            bottomBar = {
                if (showChrome) {
                    Column {
                        // Background playback needs somewhere in-app to see and stop it.
                        MiniPlayerBar(onOpen = { navController.navigate(Routes.player(it)) })
                        NavigationBar {
                            NavigationBarItem(
                                selected = route == Routes.RECORD,
                                onClick = { navController.navigateTab(Routes.RECORD) },
                                icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                                label = { Text("Record") },
                            )
                            NavigationBarItem(
                                selected = route == Routes.LIBRARY,
                                onClick = { navController.navigateTab(Routes.LIBRARY) },
                                icon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                label = { Text("Files") },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                NavHost(navController = navController, startDestination = Routes.RECORD) {
                    composable(Routes.RECORD) { RecordScreen() }

                    composable(Routes.LIBRARY) {
                        LibraryScreen(
                            onOpen = { navController.navigate(Routes.player(it.id)) },
                            onEdit = { navController.navigate(Routes.editor(it.id)) },
                            onShare = { shareRecording(context, it) },
                            onShareMany = { shareRecordings(context, it) },
                            onOpenTrash = { navController.navigate(Routes.TRASH) },
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Routes.TRASH) {
                        TrashScreen(
                            onBack = { navController.popBackStack() },
                            retentionDays = settings.trashRetentionDays,
                        )
                    }

                    composable(
                        route = Routes.PLAYER,
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        PlayerScreen(
                            recordingId = entry.arguments?.getLong("id") ?: 0L,
                            onBack = { navController.popBackStack() },
                            onEdit = { navController.navigate(Routes.editor(it.id)) },
                            onShare = { shareRecording(context, it) },
                        )
                    }

                    composable(
                        route = Routes.EDITOR,
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        EditorScreen(
                            recordingId = entry.arguments?.getLong("id") ?: 0L,
                            onBack = { navController.popBackStack() },
                            onSaved = { },
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.navigation.NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun shareRecordings(context: Context, recordings: List<Recording>) {
    val existing = recordings.filter { it.exists }
    when {
        existing.isEmpty() -> return
        existing.size == 1 -> shareRecording(context, existing.first())
        else -> {
            val uris = ArrayList(existing.map { uriFor(context, it) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share recordings"))
        }
    }
}

private fun uriFor(context: Context, recording: Recording) = FileProvider.getUriForFile(
    context,
    "${'$'}{context.packageName}.fileprovider",
    recording.file,
)

private fun shareRecording(context: Context, recording: Recording) {
    if (!recording.exists) return
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        recording.file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = when (recording.extension.lowercase()) {
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> "audio/mp4"
        }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share recording"))
}
