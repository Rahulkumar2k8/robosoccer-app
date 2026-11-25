# ESP32 RC Wokwi Lab — Android APK (v4)

This repository builds the v4 Android project supplied on 25 November 2026, including the Capacitor app, USB-OTG device filters, and native USB serial bridge for ESP32 serial communication.

## Build the APK

1. Open the repository's **Actions** tab.
2. Select **Build ESP32 RC Wokwi Lab (Android APK)**.
3. Wait for the workflow to complete successfully.
4. Download the `ESP32_RC_Wokwi_Lab-Android-APK` artifact and extract `app-debug.apk`.

The workflow restores and validates the supplied v4 ZIP, installs Capacitor, configures the native Android USB serial bridge and USB-OTG support, builds the debug APK, and uploads the APK as a workflow artifact.

## v4 project scope

The APK includes the ESP32 Dev Module + Dual BTS7960 + FlySky FS-i6S simulator, multi-touch dual gimbals, RC Channel Mixer (CH2 - CH3 / CH2 + CH3 / ± Diff), Arduino editor, Dual Serial Monitor, and native USB serial bridge support.

Real `arduino-cli` ESP32 compiling/flashing, Boards Manager, and Library Manager require the Windows desktop Python app; these are not native Android toolchain features.
