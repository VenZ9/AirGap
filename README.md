# AirGap (Android MVP 0.1.0)

AirGap transfers files between Android screens through animated QR frames. It has no Internet, Wi-Fi, Bluetooth, network-state, or storage permissions. The sender uses Android's document picker; the receiver uses CameraX + bundled ML Kit barcode scanning and Android's save-document picker.

## MVP behavior

- Kotlin, Jetpack Compose, Material 3; min Android API 26.
- Any file type through the document picker, limited to **10 MiB per file** in this first build.
- Files are split into 360-byte plaintext chunks. Each chunk is encrypted independently with AES-256-GCM; the key is derived from the shared passphrase using PBKDF2-HMAC-SHA-256 (210,000 iterations) and a random 128-bit salt.
- Each QR frame contains a session ID, index/count, repeated file metadata, authenticated ciphertext, and SHA-256 of the original file. Frames repeat in a loop; the receiver deduplicates by index and can join mid-transfer.
- The receiver decrypts only after all frames arrive and checks the final size and SHA-256 before offering a save action.

## Run

Open this directory in Android Studio with JDK 17 and Android SDK 35 installed, allow Gradle to resolve dependencies, then run the `app` configuration on two Android devices. For the receive device, grant camera permission. Enter the same passphrase on both phones. On the sender, select a file and keep the QR screen visible; on the receiver, scan the sender's screen until all frames are collected, then decrypt and save.

To run JVM tests: `./gradlew test` (a Gradle wrapper is not included in this archive; use Android Studio's Gradle tooling or generate a wrapper in the project with a locally installed Gradle).

## Test status and limitations

The repository includes JVM unit tests for single- and multi-chunk round trips, out-of-order frames, duplicate frames, and wrong passphrases. The source project was statically checked in this environment, but it was **not built as an APK here** because this environment does not have Gradle or an Android SDK installed. Run the tests and a two-device camera test in Android Studio before distributing.

This is a prototype, not a security-audited release. The 10 MiB cap is deliberate: this version reads the selected file into memory and uses sequential QR frames rather than fountain codes. Transfer speed varies substantially by camera, display, distance, focus, and QR density. Do not use it for seed phrases, private keys, or other critical secrets until the implementation has received a security review.

## Privacy verification

`app/src/main/AndroidManifest.xml` declares only `android.permission.CAMERA`. There is no `INTERNET`, Wi-Fi, Bluetooth, network-state, or broad storage permission. Build dependencies are fetched by Gradle at build time; the app does not need network access at runtime.
