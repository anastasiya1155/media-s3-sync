package cc.solop.mediasync.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cc.solop.mediasync.data.repo.SyncStatus
import cc.solop.mediasync.di.ServiceLocator
import cc.solop.mediasync.workers.WorkScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureMediaPermissions()

        setContent {
            MaterialTheme {
                val appContext = applicationContext
                val services = ServiceLocator.from(appContext)
                val vm: SyncStatusViewModel = viewModel(
                    factory = SyncStatusViewModel.factory(
                        appContext = appContext,
                        workManager = WorkManager.getInstance(appContext),
                        services = services,
                    )
                )
                MainScreen(vm = vm)
            }
        }
    }

    private fun ensureMediaPermissions() {
        val required = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val denied = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, denied.toTypedArray(), 1001)
        }
    }
}

class SyncStatusViewModel(
    private val appContext: Context,
    private val workManager: WorkManager,
    private val services: ServiceLocator,
) : ViewModel() {

    val status: StateFlow<SyncStatus> = services.syncStatusRepository.statusFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SyncStatus(),
    )

    val runningUploads: StateFlow<Int> =
        workManager.getWorkInfosByTagFlow(WorkScheduler.TAG_UPLOAD)
            .map { infos -> infos.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0,
            )

    fun syncNow() {
        WorkScheduler.enqueueScanNow(appContext)
    }

    suspend fun setToken(token: String) {
        services.tokenStore.setToken(token)
    }

    companion object {
        fun factory(appContext: Context, workManager: WorkManager, services: ServiceLocator): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return SyncStatusViewModel(appContext, workManager, services) as T
                }
            }
        }
    }
}

@Composable
private fun MainScreen(vm: SyncStatusViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val runningUploads by vm.runningUploads.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tokenText by remember { mutableStateOf("") }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Diary Media Sync MVP", style = MaterialTheme.typography.headlineSmall)
            Text("Queued: ${status.queuedCount}")
            Text("Uploaded: ${status.uploadedCount}")
            Text("Duplicates: ${status.duplicateCount}")
            Text("Failed: ${status.failedCount}")
            Text("Running/Queued Jobs: $runningUploads")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.syncNow() }) {
                    Text("Sync Now")
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = tokenText,
                onValueChange = { tokenText = it },
                label = { Text("Auth token") },
                singleLine = true,
            )
            Button(onClick = {
                scope.launch {
                    vm.setToken(tokenText.trim())
                }
            }) {
                Text("Save token")
            }

            Text("Recent failed items", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(status.failedItems) { failed ->
                    Text("${failed.reason}: ${failed.uri}")
                }
            }
        }
    }
}
