# Focus Vault 🛡️

[![Download APK](https://img.shields.io/badge/Download-APK-2ea44f?style=for-the-badge&logo=android)](https://github.com/Prathap2349/Focus-Vault/raw/main/FocusVault.apk)

**Focus Vault** (formerly Stay Focused) is a premium, privacy-first Android application designed to help you reclaim your time, block distractions, and build better digital habits. Built natively for Android, it features unbypassable blocking mechanics, comprehensive analytics, and a beautiful dark-mode-first UI.

## ✨ Key Features

*   🔒 **Strict Mode:** The ultimate productivity lock. Once a session starts, there is no early exit, no pause button, and no emergency bypass. You are locked in until the timer reaches zero.
*   🛡️ **Deep Application Blocking:** Utilizes Android's Accessibility Services and background workers to instantly detect and overlay a block screen on distracting apps.
*   📊 **Offline Focus Analytics:** Tracks your daily, weekly, and monthly focus goals. All data is processed locally to determine your peak focus hours and recommend optimal session durations.
*   🎨 **Premium UI & AMOLED Dark Mode:** A sleek, minimal, and highly polished user interface optimized for OLED displays with True Black backgrounds and dynamic neon gradient accents (Aurora Teal & Midnight Violet).
*   ⏱️ **Quick Presets & Scheduling:** Set up recurring focus blocks (e.g., "Deep Work", "Sleep", "Reading") or use the quick-timer widget to start a session directly from your home screen.
*   🔋 **Performance & Battery Optimized:** Built using Kotlin, Coroutines, and Room Database to ensure a lightweight footprint with minimal battery drain, even while monitoring apps in the background.

## 🔒 Privacy First

Focus Vault operates **100% offline**. 
None of your focus sessions, app activity, or scheduling data are ever uploaded to any external cloud server. Your data stays on your device.

## 🛠️ Tech Stack & Architecture

*   **Platform:** Native Android (Kotlin)
*   **Architecture:** MVVM (Model-View-ViewModel)
*   **Database:** Room Database (SQLite)
*   **Background Processing:** WorkManager & Foreground Services
*   **System Integration:** AccessibilityService (App Blocking), Device Admin (Uninstall Protection)
*   **Testing:** JUnit4 Unit Test Suites (Schedule validation, State Machine verification)

## 🚀 Getting Started

### Prerequisites
*   Android Studio (Iguana or newer recommended)
*   JDK 21
*   Gradle 8.7+

### Building the Project
1. Clone this repository:
   \`\`\`bash
   git clone https://github.com/Prathap2349/Stay-Focused.git
   \`\`\`
2. Open the project in **Android Studio**.
3. Sync Gradle files.
4. Run the project on an emulator or physical device running Android 8.0 (API 26) or higher.

## 🧪 Running Tests
Focus Vault comes with a robust suite of unit tests to ensure timer reliability and state-machine stability.
To run the tests, execute:
\`\`\`bash
./gradlew test
\`\`\`

## 📝 License
Copyright (c) 2024 Prathap. All rights reserved.
