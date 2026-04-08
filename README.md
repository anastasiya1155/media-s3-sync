# Media S3 Sync (Android MVP)

Android auto-uploader for diary photos/videos using backend contract at `https://pa2021.solop.cc/api/media`.

## MVP scope
- Background scan + enqueue via WorkManager
- Per-item upload flow: `init-upload` -> optional `PUT uploadUrl` -> `complete-upload`
- Calls `fail-upload` when applicable
- Server-authoritative SHA-256 dedupe support
- Retry + restart durability via WorkManager
- Basic status UI (counts + failed items)

See architecture details in [ARCHITECTURE.md](./ARCHITECTURE.md).

## Setup
1. Open this folder in Android Studio.
2. Set your auth token in-app (MVP token field) or via `BuildConfig.DEFAULT_AUTH_TOKEN`.
3. Run on Android device/emulator API 26+.
4. Grant media read permissions.

## Current phase
Phase 1 scaffold implemented.
