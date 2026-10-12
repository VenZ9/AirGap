# AirGap (Android 0.1.0)

AirGap transfers files between Android devices using animated sequential QR codes. It operates completely offline with zero network connectivity: no Internet, Wi-Fi, Bluetooth, local network, analytics, ads, accounts, or cloud dependencies.

## Key Features

- **Jetpack Compose & Material 3 Dark UI**: Responsive dark-first theme with clear status indicators, progress counters, transmission playback controls, and viewfinder overlay.
- **Offline Document Pickers**: Uses Android's Storage Access Framework (`OpenDocument` and `CreateDocument`) without broad storage permissions.
- **Flexible Security**:
  - **Authenticated Encryption**: AES-256-GCM per chunk with PBKDF2-HMAC-SHA-256 (210,000 iterations) and 128-bit salt derived from a user passphrase.
  - **Plaintext Transfer Mode**: Optional unencrypted transfer for public or non-sensitive files with SHA-256 verification.
- **Cryptographic Integrity**: SHA-256 hash verified over the reconstructed byte array before saving.
- **Adaptive QR Density & Chunk Sizing**: Choose Compact (240B), Standard (360B), or Dense (480B) chunks to optimize for different camera sensors and screen resolutions.
- **Looping Optical Transmission**: Continuous cyclic playback allows receiving devices to join mid-transfer; handles out-of-order, duplicate, or delayed frames seamlessly.
- **LRU Bitmap Caching**: Optimized QR rendering (`RGB_565` format with an LRU cache) minimizing CPU load, memory allocation, and battery consumption.
- **Privacy & Permissions**: `AndroidManifest.xml` only requests `android.permission.CAMERA` for the optical scanner. Zero network permissions exist.

## Architecture

```
app/
├── src/main/java/com/airgap/app/
│   ├── MainActivity.kt        # Jetpack Compose UI (Home, Send, and Receive screens)
│   ├── AirGapViewModel.kt     # Unidirectional StateFlow ViewModel & coroutine pipeline
│   ├── CameraScannerView.kt   # CameraX and ML Kit QR code barcode analyzer
│   ├── QrGenerator.kt         # ZXing QR matrix rendering with LRU bitmap cache
│   ├── TransferProtocol.kt    # Serialization, chunking, AES-GCM encryption & SHA-256
│   └── ui/theme/              # Material 3 colors, dark palette, and typography
└── src/test/java/com/airgap/app/
    └── TransferProtocolTest.kt # Automated unit tests for protocol, encryption, integrity
```

## Building and Testing

The project uses Gradle Wrapper 8.11.1, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and Java 17.

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
Automated builds and tests run on every push to `main` via GitHub Actions.
