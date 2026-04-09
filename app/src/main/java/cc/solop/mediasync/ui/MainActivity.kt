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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cc.solop.mediasync.data.api.LoginRequest
import cc.solop.mediasync.data.repo.SyncStatus
import cc.solop.mediasync.di.ServiceLocator
import cc.solop.mediasync.workers.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

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

    fun retryFailedNow() {
        viewModelScope.launch {
            services.syncStatusRepository.resetScanWatermark()
            WorkScheduler.enqueueScanNow(appContext)
        }
    }

    fun stopSync() {
        WorkScheduler.stopAllSync(appContext)
    }

    fun clearQueue() {
        WorkScheduler.clearQueue(appContext)
    }

    fun resumeSync() {
        WorkScheduler.resumeSync(appContext)
    }

    fun getSavedToken(): String {
        return services.tokenStore.getCachedToken()
    }

    fun setToken(token: String) {
        services.tokenStore.setToken(token)
    }

    suspend fun login(email: String, password: String): String? {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank() || password.isBlank()) {
            return "Email and password are required."
        }
        return try {
            val response = services.authApi.login(
                LoginRequest(
                    email = normalizedEmail,
                    password = password,
                )
            )
            val token = response.accessToken?.trim().orEmpty()
            if (token.isBlank()) {
                "Login succeeded but no access token was returned."
            } else {
                services.tokenStore.setToken(token)
                null
            }
        } catch (e: HttpException) {
            val body = withContextSafeIo { e.response()?.errorBody()?.string() }.orEmpty()
            val details = body.take(200).ifBlank { e.message() ?: "HTTP ${e.code()}" }
            "Login failed (${e.code()}): $details"
        } catch (e: Exception) {
            "Login failed: ${e.message ?: "Unknown error"}"
        }
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
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var isLoggedIn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        isLoggedIn = vm.getSavedToken().isNotBlank()
    }

    if (!isLoggedIn) {
        LoginScreen(
            email = email,
            password = password,
            error = loginError,
            isLoggingIn = isLoggingIn,
            onEmailChange = { email = it },
            onPasswordChange = { password = it },
            onLogin = {
                scope.launch {
                    isLoggingIn = true
                    loginError = null
                    val error = vm.login(email = email, password = password)
                    isLoggingIn = false
                    if (error == null) {
                        password = ""
                        isLoggedIn = true
                    } else {
                        loginError = error
                    }
                }
            },
        )
    } else {
        SyncDashboardScreen(
            status = status,
            runningUploads = runningUploads,
            onSyncNow = { vm.syncNow() },
            onRetryFailed = { vm.retryFailedNow() },
            onStopSync = { vm.stopSync() },
            onResumeSync = { vm.resumeSync() },
            onClearQueue = { vm.clearQueue() },
            onLogout = {
                vm.setToken("")
                isLoggedIn = false
            },
        )
    }
}

@Composable
private fun LoginScreen(
    email: String,
    password: String,
    error: String?,
    isLoggingIn: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Diary Media Sync MVP", style = MaterialTheme.typography.headlineSmall)
            Text("Sign in with the same account you use in Diary.")
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = email,
                onValueChange = onEmailChange,
                label = { Text("Email") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
            )
            if (!error.isNullOrBlank()) {
                Text(error)
            }
            Button(onClick = onLogin, enabled = !isLoggingIn) {
                Text(if (isLoggingIn) "Logging in..." else "Log in")
            }
        }
    }
}

private suspend fun <T> withContextSafeIo(block: suspend () -> T): T {
    return kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
}

@Composable
private fun SyncDashboardScreen(
    status: SyncStatus,
    runningUploads: Int,
    onSyncNow: () -> Unit,
    onRetryFailed: () -> Unit,
    onStopSync: () -> Unit,
    onResumeSync: () -> Unit,
    onClearQueue: () -> Unit,
    onLogout: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Diary Media Sync MVP", style = MaterialTheme.typography.headlineSmall)
            Text("Queued: ${status.queuedCount}")
            Text("Uploaded: ${status.uploadedCount}")
            Text("Duplicates: ${status.duplicateCount}")
            Text("Failed: ${status.failedCount}")
            Text("Running/Queued Jobs: $runningUploads")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSyncNow) {
                    Text("Sync Now")
                }
                Button(onClick = onRetryFailed) {
                    Text("Retry Failed")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStopSync) {
                    Text("Stop Sync")
                }
                Button(onClick = onResumeSync) {
                    Text("Resume Sync")
                }
                Button(onClick = onClearQueue) {
                    Text("Clear Queue")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLogout) {
                    Text("Log out")
                }
            }

            Text("Recent uploaded items", style = MaterialTheme.typography.titleMedium)
            Column(
                modifier = Modifier.heightIn(max = 160.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (status.uploadedItems.isEmpty() && status.uploadedCount > 0) {
                    Text(
                        "No recent uploaded records yet in this app version. " +
                            "Only uploads after this update are listed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                status.uploadedItems.forEach { uploaded ->
                    Text("UPLOADED ${uploaded.key}: ${uploaded.uri}")
                }
            }

            Text("Recent failed items", style = MaterialTheme.typography.titleMedium)
            Column(
                modifier = Modifier.heightIn(max = 160.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                status.failedItems.forEach { failed ->
                    Text("FAILED ${failed.reason}: ${failed.uri}")
                }
            }
        }
    }
}
