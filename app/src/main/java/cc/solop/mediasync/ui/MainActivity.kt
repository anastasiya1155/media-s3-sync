package cc.solop.mediasync.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cc.solop.mediasync.data.api.LoginRequest
import cc.solop.mediasync.data.sync.MediaSyncStore
import cc.solop.mediasync.data.sync.MediaSyncState as DbMediaSyncState
import cc.solop.mediasync.di.ServiceLocator
import cc.solop.mediasync.workers.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
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
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
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
    data class WorkSummary(
        val running: Int = 0,
        val enqueued: Int = 0,
        val blocked: Int = 0,
    )

    data class LocalMediaStatus(
        val uri: String,
        val filename: String,
        val bucketName: String,
        val mimeType: String,
        val mediaId: Long,
        val dateAddedSec: Long,
        val syncState: MediaSyncState,
        val syncDetail: String? = null,
    )

    enum class MediaSyncState {
        PENDING,
        SYNCING,
        SYNCED,
        FAILED,
    }

    val authToken: StateFlow<String> = services.tokenStore.tokenFlow

    val uploadWorkSummary: StateFlow<WorkSummary> =
        workManager.getWorkInfosByTagFlow(WorkScheduler.TAG_UPLOAD)
            .map { infos ->
                WorkSummary(
                    running = infos.count { it.state == WorkInfo.State.RUNNING },
                    enqueued = infos.count { it.state == WorkInfo.State.ENQUEUED },
                    blocked = infos.count { it.state == WorkInfo.State.BLOCKED },
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = WorkSummary(),
            )

    val scanWorkSummary: StateFlow<WorkSummary> =
        workManager.getWorkInfosByTagFlow(WorkScheduler.TAG_SCAN)
            .map { infos ->
                WorkSummary(
                    running = infos.count { it.state == WorkInfo.State.RUNNING },
                    enqueued = infos.count { it.state == WorkInfo.State.ENQUEUED },
                    blocked = infos.count { it.state == WorkInfo.State.BLOCKED },
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = WorkSummary(),
            )

    private val _localMedia = MutableStateFlow<List<LocalMediaStatus>>(emptyList())
    val localMedia: StateFlow<List<LocalMediaStatus>> = _localMedia

    private val _isLoadingLocalMedia = MutableStateFlow(false)
    val isLoadingLocalMedia: StateFlow<Boolean> = _isLoadingLocalMedia
    private val _isSyncPaused = MutableStateFlow(false)
    val isSyncPaused: StateFlow<Boolean> = _isSyncPaused
    private val _syncOverview = MutableStateFlow(MediaSyncStore.SyncOverview())
    val syncOverview: StateFlow<MediaSyncStore.SyncOverview> = _syncOverview
    private var mediaStoreObserver: ContentObserver? = null
    private var mediaStoreDebounceJob: Job? = null
    private var reconcileJob: Job? = null
    private var watchdogJob: Job? = null
    private var foregroundRefreshJob: Job? = null

    fun syncNow() {
        WorkScheduler.enqueueScanNow(appContext)
        reconcileStuckUploads(includeFailed = true)
        loadLocalMedia()
    }

    fun onAppForegrounded() {
        loadLocalMedia()
        foregroundRefreshJob?.cancel()
        foregroundRefreshJob = viewModelScope.launch {
            // Camera apps may publish media slightly after returning to this app.
            delay(1_500)
            loadLocalMedia()
        }
        if (_isSyncPaused.value) return
        WorkScheduler.enqueueScanNow(appContext)
        reconcileStuckUploads(includeFailed = false)
    }

    fun refreshStatuses() {
        reconcileStuckUploads(includeFailed = true)
        loadLocalMedia()
    }

    fun retryFailedNow() {
        viewModelScope.launch {
            services.syncStatusRepository.resetScanWatermark()
            WorkScheduler.enqueueScanNow(appContext)
            loadLocalMedia()
        }
    }

    fun stopSync() {
        WorkScheduler.stopAllSync(appContext)
        _isSyncPaused.value = true
    }

    fun clearQueue() {
        viewModelScope.launch {
            WorkScheduler.clearQueue(appContext)
            services.syncStatusRepository.clearQueuedCount()
            refreshOverview()
        }
    }

    fun resumeSync() {
        WorkScheduler.resumeSync(appContext)
        reconcileStuckUploads(includeFailed = true)
        _isSyncPaused.value = false
        loadLocalMedia()
    }

    fun toggleSyncPause() {
        if (_isSyncPaused.value) {
            resumeSync()
        } else {
            stopSync()
        }
    }

    fun startObservingMediaStore() {
        if (mediaStoreObserver != null) return
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                onMediaStoreChanged()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onMediaStoreChanged()
            }
        }
        appContext.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"),
            true,
            observer,
        )
        mediaStoreObserver = observer
    }

    fun stopObservingMediaStore() {
        mediaStoreObserver?.let { appContext.contentResolver.unregisterContentObserver(it) }
        mediaStoreObserver = null
        mediaStoreDebounceJob?.cancel()
        mediaStoreDebounceJob = null
        foregroundRefreshJob?.cancel()
        foregroundRefreshJob = null
    }

    private fun onMediaStoreChanged() {
        mediaStoreDebounceJob?.cancel()
        mediaStoreDebounceJob = viewModelScope.launch {
            delay(700)
            WorkScheduler.enqueueScanNow(appContext)
            reconcileStuckUploads(includeFailed = false)
            loadLocalMedia()
        }
    }

    fun reconcileStuckUploads(includeFailed: Boolean) {
        if (reconcileJob?.isActive == true) return
        reconcileJob = viewModelScope.launch {
            val queuedUris = services.mediaSyncStore.getResumableUris(limit = 100)
            val retryUris = if (includeFailed) services.mediaSyncStore.getFailedUris(limit = 100) else emptyList()
            val targets = (queuedUris + retryUris).distinct().take(100)
            if (targets.isEmpty()) return@launch
            targets.forEach { uri ->
                val candidate = services.mediaStoreScanner.resolveCandidate(uri)
                if (candidate == null) {
                    // Media item removed from device; no further sync work required.
                    services.mediaSyncStore.markState(uri, DbMediaSyncState.SKIPPED, "Missing on device")
                } else {
                    services.mediaSyncStore.markPending(listOf(candidate))
                    WorkScheduler.enqueueUpload(appContext, candidate, replaceExisting = true)
                }
            }
            refreshOverview()
        }
    }

    fun startFreshSync() {
        viewModelScope.launch {
            WorkScheduler.stopAllSync(appContext)
            services.syncStatusRepository.resetAllStateForFreshStart()
            services.mediaSyncStore.clearAll()
            WorkScheduler.resumeSync(appContext)
            _isSyncPaused.value = false
            refreshOverview()
            loadLocalMedia()
        }
    }

    fun setWatchdogEnabled(enabled: Boolean) {
        if (!enabled) {
            watchdogJob?.cancel()
            watchdogJob = null
            return
        }
        if (watchdogJob?.isActive == true) return
        watchdogJob = viewModelScope.launch {
            while (true) {
                delay(10_000)
                val hasActiveWork = scanWorkSummary.value.running > 0 ||
                    scanWorkSummary.value.enqueued > 0 ||
                    uploadWorkSummary.value.running > 0 ||
                    uploadWorkSummary.value.enqueued > 0
                if (!hasActiveWork) {
                    reconcileStuckUploads(includeFailed = false)
                    refreshVisibleStatuses()
                }
                refreshOverview()
            }
        }
    }

    fun getSavedToken(): String {
        return services.tokenStore.getCachedToken()
    }

    fun setToken(token: String) {
        services.tokenStore.setToken(token)
    }

    fun validateTokenOnLoad() {
        viewModelScope.launch {
            val token = services.tokenStore.getCachedToken().trim()
            if (token.isBlank()) return@launch
            try {
                val response = services.authApi.me()
                if (response.code() == 401) {
                    services.tokenStore.clearToken()
                }
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    services.tokenStore.clearToken()
                }
            } catch (_: Exception) {
                // Keep existing session on transient network/API errors.
            }
        }
    }

    fun loadLocalMedia() {
        viewModelScope.launch {
            if (_isLoadingLocalMedia.value) return@launch
            _isLoadingLocalMedia.value = true
            try {
                val local = services.mediaStoreScanner.listRecentMedia(
                    limit = 300,
                )
                val uris = local.map { it.uri }
                val legacySyncedUris = services.syncStatusRepository.getSyncedUrisSet()
                val legacyFailedUris = services.syncStatusRepository.getFailedUrisSet()
                val statesByUriInitial = services.mediaSyncStore.getStateMap(uris)
                val missingUris = uris.filterNot { statesByUriInitial.containsKey(it) }
                if (missingUris.isNotEmpty()) {
                    // Backfill Room states from legacy status data so pre-Room synced items don't appear as pending.
                    local.filter { missingUris.contains(it.uri) }.forEach { item ->
                        when {
                            services.mediaStoreScanner.isBeforeSyncStart(item.dateAddedSec) ->
                                services.mediaSyncStore.markState(item.uri, DbMediaSyncState.SKIPPED, "Before 2026-04-04")
                            legacySyncedUris.contains(item.uri) ->
                                services.mediaSyncStore.markState(item.uri, DbMediaSyncState.SYNCED, "Migrated as synced")
                            legacyFailedUris.contains(item.uri) ->
                                services.mediaSyncStore.markState(item.uri, DbMediaSyncState.FAILED, "Migrated as failed")
                            else -> {
                                services.mediaStoreScanner.resolveCandidate(item.uri)?.let { candidate ->
                                    services.mediaSyncStore.markPending(listOf(candidate))
                                }
                            }
                        }
                    }
                }
                val statusesByUri = services.mediaSyncStore.getStatusMap(uris)
                _localMedia.value = local.map { item ->
                    val dbStatus = statusesByUri[item.uri]
                    val dbState = dbStatus?.state
                    val syncState = when {
                        services.mediaStoreScanner.isBeforeSyncStart(item.dateAddedSec) -> MediaSyncState.SYNCED
                        dbState == DbMediaSyncState.SYNCED || dbState == DbMediaSyncState.SKIPPED -> MediaSyncState.SYNCED
                        dbState == DbMediaSyncState.SYNCING -> MediaSyncState.SYNCING
                        dbState == DbMediaSyncState.FAILED -> MediaSyncState.FAILED
                        dbState == DbMediaSyncState.PENDING -> MediaSyncState.PENDING
                        else -> MediaSyncState.PENDING
                    }
                    LocalMediaStatus(
                        uri = item.uri,
                        filename = item.filename,
                        bucketName = item.bucketName,
                        mimeType = item.mimeType,
                        mediaId = item.mediaId,
                        dateAddedSec = item.dateAddedSec,
                        syncState = syncState,
                        syncDetail = when {
                            services.mediaStoreScanner.isBeforeSyncStart(item.dateAddedSec) -> "Before 2026-04-04"
                            dbStatus?.detail.isNullOrBlank() -> defaultDetailFor(syncState)
                            else -> dbStatus?.detail
                        },
                    )
                }
                refreshOverview()
            } finally {
                _isLoadingLocalMedia.value = false
            }
        }
    }

    private suspend fun refreshVisibleStatuses() {
        val current = _localMedia.value
        if (current.isEmpty()) return
        val statusesByUri = services.mediaSyncStore.getStatusMap(current.map { it.uri })
        _localMedia.value = current.map { item ->
            val dbStatus = statusesByUri[item.uri]
            val dbState = dbStatus?.state
            val mapped = when {
                services.mediaStoreScanner.isBeforeSyncStart(item.dateAddedSec) -> MediaSyncState.SYNCED
                dbState == DbMediaSyncState.SYNCED || dbState == DbMediaSyncState.SKIPPED -> MediaSyncState.SYNCED
                dbState == DbMediaSyncState.SYNCING -> MediaSyncState.SYNCING
                dbState == DbMediaSyncState.FAILED -> MediaSyncState.FAILED
                else -> MediaSyncState.PENDING
            }
            val detail = when {
                services.mediaStoreScanner.isBeforeSyncStart(item.dateAddedSec) -> "Before 2026-04-04"
                dbStatus?.detail.isNullOrBlank() -> defaultDetailFor(mapped)
                else -> dbStatus?.detail
            }
            if (mapped == item.syncState && detail == item.syncDetail) {
                item
            } else {
                item.copy(syncState = mapped, syncDetail = detail)
            }
        }
    }

    private fun defaultDetailFor(state: MediaSyncState): String {
        return when (state) {
            MediaSyncState.PENDING -> "Pending upload"
            MediaSyncState.SYNCING -> "Uploading"
            MediaSyncState.SYNCED -> "Synced"
            MediaSyncState.FAILED -> "Failed"
        }
    }

    private suspend fun refreshOverview() {
        _syncOverview.value = services.mediaSyncStore.getOverview()
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
    val uploadWorkSummary by vm.uploadWorkSummary.collectAsStateWithLifecycle()
    val scanWorkSummary by vm.scanWorkSummary.collectAsStateWithLifecycle()
    val syncOverview by vm.syncOverview.collectAsStateWithLifecycle()
    val authToken by vm.authToken.collectAsStateWithLifecycle()
    val localMedia by vm.localMedia.collectAsStateWithLifecycle()
    val isLoadingLocalMedia by vm.isLoadingLocalMedia.collectAsStateWithLifecycle()
    val isSyncPaused by vm.isSyncPaused.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isLoggedIn = authToken.isNotBlank()
    val lifecycleOwner = LocalLifecycleOwner.current
    var hadActiveWork by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val saveCredentialToManager: suspend (String, String) -> Unit = { userEmail, userPassword ->
        try {
            withContextSafeIo {
                val credentialManager = CredentialManager.create(context)
                val request = CreatePasswordRequest(
                    id = userEmail.trim(),
                    password = userPassword
                )
                // Note: saveCredential is called via the async API
                credentialManager.createCredential(context, request)
            }
        } catch (e: Exception) {
            // Silently ignore credential saving errors - it's a nice-to-have feature
        }
    }


    LaunchedEffect(authToken) {
        if (authToken.isNotBlank()) {
            vm.validateTokenOnLoad()
            vm.syncNow()
        }
    }

    LaunchedEffect(isLoggedIn, isSyncPaused) {
        vm.setWatchdogEnabled(enabled = isLoggedIn && !isSyncPaused)
    }

    DisposableEffect(lifecycleOwner, isLoggedIn) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && isLoggedIn) {
                vm.onAppForegrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(isLoggedIn) {
        if (isLoggedIn) {
            vm.startObservingMediaStore()
        }
        onDispose {
            vm.stopObservingMediaStore()
        }
    }

    val hasActiveWork = scanWorkSummary.running > 0 ||
        scanWorkSummary.enqueued > 0 ||
        uploadWorkSummary.running > 0 ||
        uploadWorkSummary.enqueued > 0
    val hasPendingLocal = localMedia.any { it.syncState == SyncStatusViewModel.MediaSyncState.PENDING }

    LaunchedEffect(hasActiveWork, hasPendingLocal, isSyncPaused, authToken) {
        if (authToken.isNotBlank() && !isSyncPaused && !hasActiveWork && hasPendingLocal) {
            vm.reconcileStuckUploads(includeFailed = false)
        }
    }

    LaunchedEffect(hasActiveWork) {
        if (hasActiveWork) {
            hadActiveWork = true
        } else if (hadActiveWork && authToken.isNotBlank()) {
            hadActiveWork = false
            // Let status datastore settle after worker completion, then refresh gallery badges.
            delay(350)
            vm.loadLocalMedia()
        }
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
                        saveCredentialToManager(email, password)
                        password = ""
                    } else {
                        loginError = error
                    }
                }
            },
        )
    } else {
        SyncDashboardScreen(
            syncOverview = syncOverview,
            uploadWorkSummary = uploadWorkSummary,
            scanWorkSummary = scanWorkSummary,
            localMedia = localMedia,
            isLoadingLocalMedia = isLoadingLocalMedia,
            isSyncPaused = isSyncPaused,
            onStartFreshSync = { vm.startFreshSync() },
            onToggleSyncPause = { vm.toggleSyncPause() },
            onRefreshMediaStatus = { vm.refreshStatuses() },
            onLogout = {
                vm.setToken("")
            },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
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
    var isPasswordVisible by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Media Sync", style = MaterialTheme.typography.titleLarge)
                        Text("Sign in with your Diary account", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        rememberEmailAutofillModifier(onFill = onEmailChange)
                    )
                    .then(
                        Modifier.autofillModifier(
                            autofillTypes = listOf(AutofillType.EmailAddress, AutofillType.Username)
                        )
                    ),
                value = email,
                onValueChange = onEmailChange,
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        rememberPasswordAutofillModifier(onFill = onPasswordChange)
                    )
                    .then(
                        Modifier.autofillModifier(
                            autofillTypes = listOf(AutofillType.Password)
                        )
                    ),
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                visualTransformation = if (isPasswordVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (isPasswordVisible) {
                                "Hide password"
                            } else {
                                "Show password"
                            },
                        )
                    }
                },
                singleLine = true,
            )
            if (!error.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Button(onClick = onLogin, enabled = !isLoggingIn, modifier = Modifier.fillMaxWidth()) {
                Text(if (isLoggingIn) "Logging in..." else "Log in")
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun rememberEmailAutofillModifier(onFill: (String) -> Unit): Modifier {
    return rememberAutofillModifier(
        autofillTypes = listOf(AutofillType.EmailAddress, AutofillType.Username),
        onFill = onFill,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun rememberPasswordAutofillModifier(onFill: (String) -> Unit): Modifier {
    return rememberAutofillModifier(
        autofillTypes = listOf(AutofillType.Password),
        onFill = onFill,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun rememberAutofillModifier(
    autofillTypes: List<AutofillType>,
    onFill: (String) -> Unit,
): Modifier {
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val onFillState = rememberUpdatedState(onFill)
    val autofillNode = remember(autofillTypes) {
        AutofillNode(
            autofillTypes = autofillTypes,
            onFill = { onFillState.value(it) },
        )
    }

    DisposableEffect(autofillTree, autofillNode) {
        autofillTree += autofillNode
        onDispose {
            autofillTree.children.remove(autofillNode.id)
        }
    }

    return Modifier
        .onGloballyPositioned { coordinates ->
            autofillNode.boundingBox = coordinates.boundsInWindow()
        }
        .onFocusChanged { focusState ->
            if (focusState.isFocused) {
                autofill?.requestAutofillForNode(autofillNode)
            } else {
                autofill?.cancelAutofillForNode(autofillNode)
            }
        }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.autofillModifier(autofillTypes: List<AutofillType>): Modifier {
    return this
}

private suspend fun <T> withContextSafeIo(block: suspend () -> T): T {
    return kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
}

@Composable
private fun SyncDashboardScreen(
    syncOverview: MediaSyncStore.SyncOverview,
    uploadWorkSummary: SyncStatusViewModel.WorkSummary,
    scanWorkSummary: SyncStatusViewModel.WorkSummary,
    localMedia: List<SyncStatusViewModel.LocalMediaStatus>,
    isLoadingLocalMedia: Boolean,
    isSyncPaused: Boolean,
    onStartFreshSync: () -> Unit,
    onToggleSyncPause: () -> Unit,
    onRefreshMediaStatus: () -> Unit,
    onLogout: () -> Unit,
) {
    val displayedMedia = localMedia.take(120)
    val syncedCount = syncOverview.synced + syncOverview.skipped

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Photos", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Backup",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "All: Pending ${syncOverview.pending} • Syncing ${syncOverview.syncing} • Synced $syncedCount • Failed ${syncOverview.failed}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (syncOverview.unknown > 0) {
                        Text(
                            "Recovering ${syncOverview.unknown} legacy items...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Scan r:${scanWorkSummary.running} q:${scanWorkSummary.enqueued} b:${scanWorkSummary.blocked} • Upload r:${uploadWorkSummary.running} q:${uploadWorkSummary.enqueued} b:${uploadWorkSummary.blocked}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onStartFreshSync, modifier = Modifier.weight(1f)) {
                    Text("Reset")
                }
                OutlinedButton(
                    onClick = onToggleSyncPause,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(
                        imageVector = if (isSyncPaused) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle,
                        contentDescription = if (isSyncPaused) "Resume sync" else "Pause sync",
                    )
                }
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Log out",
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Sync Status", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = onRefreshMediaStatus) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh gallery")
                        }
                    }
                    if (isLoadingLocalMedia) {
                        Text("Loading local media...", style = MaterialTheme.typography.bodySmall)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.heightIn(max = 520.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        items(displayedMedia, key = { it.uri }) { item ->
                            LocalMediaPreviewCard(item = item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalMediaPreviewCard(
    item: SyncStatusViewModel.LocalMediaStatus,
) {
    val context = LocalContext.current
    val isVideo = item.mimeType.startsWith("video/")
    val previewRequest = ImageRequest.Builder(context)
        .data(item.uri)
        .apply {
            if (isVideo) {
                videoFrameMillis(1_000)
            }
        }
        .build()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        AsyncImage(
            model = previewRequest,
            contentDescription = item.filename,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (isVideo) {
            Surface(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.Center)
                    .size(24.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
            ) {
                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Surface(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .padding(3.dp),
            shape = MaterialTheme.shapes.small,
            color = when (item.syncState) {
                SyncStatusViewModel.MediaSyncState.SYNCED -> MaterialTheme.colorScheme.primaryContainer
                SyncStatusViewModel.MediaSyncState.SYNCING -> MaterialTheme.colorScheme.secondaryContainer
                SyncStatusViewModel.MediaSyncState.FAILED -> MaterialTheme.colorScheme.errorContainer
                SyncStatusViewModel.MediaSyncState.PENDING -> MaterialTheme.colorScheme.tertiaryContainer
            },
        ) {
            Icon(
                imageVector = when (item.syncState) {
                    SyncStatusViewModel.MediaSyncState.SYNCED -> Icons.Filled.CheckCircle
                    SyncStatusViewModel.MediaSyncState.SYNCING -> Icons.Filled.Sync
                    SyncStatusViewModel.MediaSyncState.FAILED -> Icons.Filled.ErrorOutline
                    SyncStatusViewModel.MediaSyncState.PENDING -> Icons.Filled.Schedule
                },
                contentDescription = when (item.syncState) {
                    SyncStatusViewModel.MediaSyncState.SYNCED -> "Synced"
                    SyncStatusViewModel.MediaSyncState.SYNCING -> "Syncing"
                    SyncStatusViewModel.MediaSyncState.FAILED -> "Failed"
                    SyncStatusViewModel.MediaSyncState.PENDING -> "Pending"
                },
                modifier = Modifier
                    .padding(2.dp)
                    .size(11.dp),
            )
        }
        if (!item.syncDetail.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomStart)
                    .padding(3.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            ) {
                Text(
                    text = item.syncDetail,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.headlineSmall)
            }
            Surface(
                modifier = Modifier.size(30.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                    icon()
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(30.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                icon()
            }
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}
