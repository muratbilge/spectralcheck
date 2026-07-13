package com.spectralcheck

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.spectralcheck.ui.BatchScreen
import com.spectralcheck.ui.DetailScreen
import com.spectralcheck.ui.HomeScreen
import com.spectralcheck.viewmodel.AnalysisViewModel
import com.spectralcheck.viewmodel.BatchScanViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold { padding ->
                    App(Modifier.fillMaxSize().padding(padding))
                }
            }
        }
    }
}

@Composable
private fun App(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val analysisViewModel: AnalysisViewModel = viewModel()
    val batchViewModel: BatchScanViewModel = viewModel()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            analysisViewModel.analyze(uri)
            navController.navigate("detail")
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            batchViewModel.scan(uri)
            navController.navigate("batch")
        }
    }

    androidx.compose.foundation.layout.Box(modifier) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onPickFile = {
                        filePicker.launch(arrayOf("audio/flac", "audio/x-flac", "audio/*"))
                    },
                    onPickFolder = { folderPicker.launch(null) },
                )
            }
            composable("detail") {
                DetailScreen(analysisViewModel)
            }
            composable("batch") {
                BatchScreen(batchViewModel) { item ->
                    analysisViewModel.analyze(item.uri)
                    navController.navigate("detail")
                }
            }
        }
    }
}
