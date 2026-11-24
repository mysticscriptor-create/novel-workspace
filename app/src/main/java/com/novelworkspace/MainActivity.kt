package com.novelworkspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.novelworkspace.ui.navigation.NovelWorkspaceApp

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val pickVaultLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    viewModel.onVaultTreeSelected(uri)
                }
            }

            NovelWorkspaceApp(
                viewModel = viewModel,
                onLaunchVaultPicker = { pickVaultLauncher.launch(null) }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }
}
