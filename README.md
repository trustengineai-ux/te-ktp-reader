# TrustEngine KTP Reader 📱

Android app for reading Indonesian e-KTP (Kartu Tanda Penduduk) using NFC + Camera OCR.

## Features

### Opsi A: NFC Verify + OCR Read ✅ (Priority)
- 📷 Camera OCR scan KTP → extract nama, NIK, TTL, alamat, foto
- 📱 NFC tap → read chip UID to verify authentic e-KTP
- ✅ Compare results → KTP asli/palsu indicator
- 📤 Export data (JSON/share to other apps)

### Opsi B: Full NFC Chip Read 🔬 (R&D Parallel)
- Read personal data directly from e-KTP NFC chip
- Based on JMRTD library + ICAO 9303 standard
- BAC key derivation research for e-KTP (no MRZ)
- Read: nama, NIK, TTL, alamat, foto from chip

## Tech Stack
- **Language:** Kotlin
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)
- **OCR:** Google ML Kit Text Recognition
- **NFC:** Android NFC API + JMRTD (for Opsi B)
- **Camera:** CameraX API
- **UI:** Material Design 3

## Architecture
```
te-ktp-reader/
├── app/src/main/
│   ├── java/com/trustengine/ktpreader/
│   │   ├── MainActivity.kt          # Main activity with NFC dispatch
│   │   ├── ScanActivity.kt          # Camera OCR scanning
│   │   ├── NfcActivity.kt           # NFC chip reading
│   │   ├── ResultActivity.kt        # Display results
│   │   ├── ocr/
│   │   │   ├── KtpOcrProcessor.kt   # OCR text extraction + parsing
│   │   │   └── NikValidator.kt      # NIK validation logic
│   │   ├── nfc/
│   │   │   ├── NfcReader.kt         # NFC UID reader (Opsi A)
│   │   │   ├── EktpChipReader.kt    # Full chip reader (Opsi B)
│   │   │   └── BacKeyDerivation.kt  # BAC key research (Opsi B)
│   │   └── model/
│   │       └── KtpData.kt           # Data model
│   ├── res/
│   │   ├── layout/
│   │   ├── values/
│   │   └── xml/
│   └── AndroidManifest.xml
├── build.gradle.kts (project)
└── app/build.gradle.kts
```

## Build
```bash
# Open in Android Studio
# or build via command line:
./gradlew assembleDebug
```

## Status
- [x] Project structure
- [x] AndroidManifest with NFC permissions
- [x] Main activity with NFC dispatch
- [x] NFC UID reader (Opsi A)
- [x] Camera OCR scanner
- [x] KTP data parser from OCR text
- [x] NIK validator
- [x] Result display + export
- [x] NFC chip reader skeleton (Opsi B)
- [ ] Build APK (needs Android Studio / CI)
- [ ] Test on real device with e-KTP
