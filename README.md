# AirGap (Android 0.2.0)

AirGap transfers files between Android devices using animated sequential QR codes. It operates completely offline with zero network connectivity: no Internet, Wi-Fi, Bluetooth, local network, analytics, ads, accounts, or cloud dependencies.

## Key Features

- **50 MiB File Transfer Limit**: Increased default file size cap from 10 MiB to 50 MiB with streaming chunk ingestion and reconstruction directly to streams, preventing Out-Of-Memory errors.
- **Transfer History**: Local SQLite-backed transfer history logging file name, size, direction (Sent/Received), status (Completed, Verified, Failed, Incomplete), SHA-256 hash, and timestamps with search, filtering, and deletion.
- **Received Files Library**: Dedicated screen to manage, view, open, and share received files using Android's standard `FileProvider` mechanism without broad external storage permissions.
- **Share-to-AirGap Integration**: Registered `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intent filters allowing users to send files straight from their gallery, file manager, or browser.
- **Enhanced Transfer Reliability**: Safe pause/resume during camera scanning, duplicate frame immunity, out-of-order reassembly, and cryptographically verified SHA-256 integrity prior to saving.
- **Theming & Personalization**:
  - Themes: AirGap Dark, AMOLED Pure Black (#000000), AirGap Light, and Cyber Amber.
  - Accent Palettes: Neon Lime, Emerald, Electric Cyan, Solar Amber, Cyber Violet, and Hot Coral.
  - Haptic touch feedback toggle on frame capture.
  - Optional history filename masking and automated history retention cleanup.
- **Privacy & Permissions**: `AndroidManifest.xml` only requests `android.permission.CAMERA` (for scanning) and `android.permission.VIBRATE` (for haptics). Zero network permissions exist.

## Architecture

```
app/
├── src/main/java/com/airgap/app/
│   ├── MainActivity.kt        # Jetpack Compose UI with Bottom Navigation (Transfer, Files, History, Settings)
│   ├── AirGapViewModel.kt     # StateFlow ViewModel, history, library manager, and coroutine pipeline
│   ├── HistoryScreen.kt       # Transfer history UI with search, filter chips, and record deletion
│   ├── ReceivedFilesScreen.kt # Received files library with categorization, FileProvider share & view
│   ├── SettingsScreen.kt      # Themes, accents, chunk density, retention, and haptics
│   ├── CameraScannerView.kt   # CameraX and ML Kit barcode analyzer with pause/resume support
│   ├── QrGenerator.kt         # ZXing QR matrix rendering with LRU RGB_565 bitmap caching
│   ├── TransferProtocol.kt    # 50 MiB streaming protocol, AES-GCM encryption & SHA-256 verification
│   ├── data/
│   │   ├── HistoryRepository.kt    # SQLite database for transfer history records
│   │   └── ReceivedFilesManager.kt # Sandboxed received file storage & FileProvider intents
│   ├── model/
│   │   └── TransferRecord.kt       # History entity model & status enums
│   └── ui/theme/              # Material 3 colors, dynamic accents, and typography
└── src/test/java/com/airgap/app/
    └── TransferProtocolTest.kt # Automated unit tests for protocol, 50 MiB boundary, streaming, encryption
```

## Building and Testing

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
Automated builds and tests run on every push to `main` via GitHub Actions.
