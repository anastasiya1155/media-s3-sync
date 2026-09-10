# Android Media Uploader MVP Architecture

## 1) Project structure and architecture

Single Android app module for MVP (keep setup simple), clean package boundaries:

- `app/`
  - `app/`:
    - `MediaSyncApp.kt`: App init and periodic sync schedule
  - `di/`:
    - `ServiceLocator.kt`: lightweight DI (replaceable with Hilt later)
  - `data/`
    - `api/`: Retrofit DTOs + API interface
    - `auth/`: token storage/provider (DataStore)
    - `media/`: MediaStore scanner + file metadata/sha helpers
    - `repo/`: upload repository + sync status repository
  - `domain/`
    - `UploadOrchestrator.kt`: init-upload -> optional PUT -> complete/fail workflow
  - `workers/`
    - `MediaScanWorker.kt`: discovers local media and enqueues upload jobs
    - `UploadWorker.kt`: handles one media item upload, retries on transient failures
    - `WorkScheduler.kt`: periodic + one-shot scheduling helpers
  - `ui/`
    - `MainActivity.kt`: MVP status screen + trigger sync + failed items list

This keeps a clear data/domain/worker split without over-engineering modules.

## 2) Minimal persistence strategy (no heavy DB)

- Use **WorkManager internal DB** as authoritative durable queue for in-flight/retry jobs.
- Use **Preferences DataStore** for lightweight app state:
  - auth token
  - last scan timestamp
  - recent failed uploads (bounded JSON list)
  - counters: queued/uploaded/duplicates/failed
- No Room DB in MVP.

Rationale:
- Survives process death/restart automatically via WorkManager.
- Satisfies retry + durability requirement with minimal custom state.
- Keeps implementation small and easy to evolve.

## 3) Workers/jobs and scheduling strategy

- `MediaScanWorker` (periodic, every 15 min, `ExistingPeriodicWorkPolicy.KEEP`)
  - Constraints: network connected, battery not low
  - Reads MediaStore items newer than `lastScanEpochMs`
  - Enqueues per-item `UploadWorker` as unique work (`UPLOAD_<stableId>`)
  - Updates last scan watermark after scheduling

- `UploadWorker` (one-time per media item)
  - Constraints: network connected
  - Retries with exponential backoff (`Result.retry()` on recoverable failures)
  - Steps:
    1. Build metadata + SHA-256
    2. `init-upload`
    3. If duplicate -> mark duplicate + success
    4. Else upload bytes to `uploadUrl` with HTTP PUT
    5. `complete-upload`
    6. On non-retryable failure call `fail-upload` when `key` is known

- Manual trigger
  - `OneTimeWorkRequest<MediaScanWorker>` from UI for “Sync now”.

## 4) API client interfaces and orchestrator

- Base URL: `https://pa2021.solop.cc/`
- Interface:
  - `POST /api/media` for `init-upload`, `complete-upload`, `fail-upload`
  - `GET /api/media?pageSize=100&date=YYYY-MM-DD&pageToken=...` for list (stubbed for MVP screen extension)
- Auth header via OkHttp interceptor: `authorization: <token>`
- `UploadOrchestrator` encapsulates backend contract and upload branching logic.

## 5) Phased implementation plan

### Phase 1 (this scaffold)
- App skeleton + package layout
- Retrofit API + auth interceptor
- MediaStore scanner
- WorkManager scan and upload workers
- SHA256 + PUT uploader
- DataStore status tracking
- Basic Compose status screen (counts, failed items, sync now)

### Phase 2 (hardening)
- ContentObserver-triggered near-real-time enqueue
- Better media filtering (camera folders, MIME policies, max size)
- Smarter retry taxonomy (HTTP class based)
- Foreground service mode for long-running uploads
- Paging of failed history + per-item retry actions

### Phase 3 (production polish)
- Hilt DI + testable interfaces everywhere
- End-to-end instrumentation tests with mock backend
- Optional local tiny index to avoid rehashing unchanged files
- Battery/network adaptive policies and user controls (Wi-Fi only)
- Telemetry/metrics and error reporting
