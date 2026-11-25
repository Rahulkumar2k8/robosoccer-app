# ESP32 RC Wokwi Lab — Android APK

This repository builds the Android application from the updated project uploaded on 25 November 2026: `esp32_rc_wokwi_lab_android_v2.zip`.

## Build the APK

1. Open the repository's **Actions** tab.
2. Select **Build ESP32 RC Wokwi Lab (Android APK)**.
3. Wait for the run to complete successfully.
4. Download the `ESP32_RC_Wokwi_Lab-Android-APK` artifact and extract `app-debug.apk`.

The workflow restores and verifies the supplied ZIP, installs the Capacitor dependencies, generates the Android project, builds the debug APK, and uploads it as a workflow artifact.

## Project scope

This version packages the updated ESP32 + Dual BTS7960 + FlySky FS-i6S simulator from the supplied ZIP.

The source README specifies that real `arduino-cli` ESP32 compiling/flashing and desktop Boards/Library Manager functions belong to the Windows desktop Python app. They should not be considered available as native Android firmware upload features in this APK.
