# Focus Vault 🛡️

[![Android](https://img.shields.io/badge/Platform-Android_8.0%2B_%28API_26%2B%29-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin_1.9.22-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Download APK](https://img.shields.io/badge/Download-FocusVault.apk-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Prathap2349/Focus-Vault/raw/main/FocusVault.apk)
[![Privacy](https://img.shields.io/badge/Privacy-100%25_On--Device-0052CC?style=for-the-badge&logo=shield&logoColor=white)](#-privacy--security-guarantee)

---

**Focus Vault** (package `com.stayfocused.app`) is an ultra-reliable, privacy-first native Android app and website blocker. Designed for high discipline and deep work, Focus Vault combines unbypassable session mechanics, local DNS packet filtering, PBKDF2 security lock, custom multi-tier home screen widgets, and offline analytics into a clean, modern interface.

---

## 🌟 Key Capabilities & Features

### 1. 🔒 Triple-Tier Discipline Modes
Choose your exact level of discipline for every focus session:
- **Focus Mode (Lite)**: Standard session protection. Allows stopping early if priorities shift.
- **Lock Mode (Deep)**: Security PIN guarded. Stopping early or modifying blocklists requires your master PIN.
- **Strict Mode (Iron / Hardcore)**: Unbreakable lock. Zero early exits, no emergency stop, and no bypasses allowed until the session timer reaches zero.

### 2. 🛡️ Application & Website Blocking Engine
- **Instant App Overlay**: Monitors foreground application switches via `AppBlockAccessibilityService` with anti-flash timing guards.
- **Local DNS VPN Tunnel**: Filters web requests locally via `FocusVpnService` with zero network overhead. Non-blocked queries are relayed via asynchronous non-blocking IO.
- **Preset Packs & Smart Suggestions**: 1-tap website preset packs (Social, Video & Streaming, Gaming, Shopping) and automatic on-device domain suggestions based on local queries.
- **Live Re-sorting & Domain Validation**: Blocked apps and active sites automatically float to the top of selection lists with real-time inline validation feedback.

### 3. ⚡ 4-Tier Home Screen Widgets
- **Wide Banner (4x1)**: Displays live session countdown and full control actions.
- **Large Bento (2x2)**: Expanded card showing active mode, target goals, progress ring, and controls.
- **Compact Tile (2x1)**: Essential countdown timer and quick start trigger.
- **Tiny Quick-Pick (1x1)**: 1-tap shortcut launching a transparent emergency/quick timer setup sheet (`WidgetQuickStartActivity`).

### 4. 📊 Local Analytics & Session History
- **Focus Goals**: Tracks Daily, Weekly, and Monthly focus achievements.
- **Adaptive Preset Tuning**: Automatically analyzes past sessions and suggests updates when sessions consistently end early or over-run.
- **Customizable History**: View recent session logs with options to delete individual entries or clear history.

### 5. 🎨 Polished Design System & Dark Mode
- **AMOLED True Black & Palettes**: Native support for OLED pitch-black backgrounds and vibrant color palettes (Aurora Teal & Midnight Violet).
- **Consistent Vector Iconography**: Pure vector drawables across all screens (`ic_shield`, `ic_lock`, `ic_key`, `ic_globe`, `ic_bell`, `ic_target`, `ic_palette`, etc.).
- **Haptics & Breathing Aura**: Tactile vibration feedback and pulsing focus aura animations during active focus blocks.

---

## 🔒 Privacy & Security Guarantee

Focus Vault is engineered to operate **100% offline**:

- **Zero Telemetry**: No user tracking, analytics SDKs, or cloud backends.
- **On-Device Database**: All session history, blocklists, and goal data remain exclusively on your device inside an encrypted local Room SQLite database.
- **PBKDF2 Cryptographic Lock**: PIN protection uses `PBKDF2WithHmacSHA256` key derivation with 10,000 iterations and random salt generation.
- **Anti-Uninstall Protection**: Device Administrator integration prevents quick app removal during active sessions.

---

## 🏗️ Architecture Overview

Focus Vault follows a modular, reactive Android architecture:

```
com.stayfocused.app/
├── adapter/          # RecyclerView Adapters (AppListAdapter, SiteListAdapter, HistoryAdapter)
├── appwidget/        # 4-Tier Home Screen Widgets & RemoteViews Provider
├── data/             # Room Database Entities, DAOs & Migrations (AppDatabase, BlockedApp, BlockedSite)
├── manager/          # Core Engines (SessionStateManager, SecurityManager, ThemeManager, FocusStatsManager)
├── service/          # System Services (AppBlockAccessibilityService, FocusVpnService, SessionTimerService)
├── ui/               # Activities & Bottom Sheets (MainActivity, AppSelectionActivity, WebsiteBlockActivity, SettingsActivity)
└── util/             # Utility Helpers (DnsPacketParser, HapticHelper, AppUtils, PrefsManager)
```

---

## 🛠️ Build & Installation

### Download Pre-built APK
Get the latest compiled binary directly:
👉 [**Download FocusVault.apk**](https://github.com/Prathap2349/Focus-Vault/raw/main/FocusVault.apk)

### Prerequisites
- **Android Studio**: Iguana (2023.2.1) or newer
- **JDK**: Version 17 or JDK 21
- **Target OS**: Android 8.0 (API 26) or higher

### Building from Source

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/Prathap2349/Focus-Vault.git
   cd Focus-Vault
   ```

2. **Execute Unit Tests**:
   ```bash
   ./gradlew testDebugUnitTest
   ```

3. **Build Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   The generated APK will be located at:
   `app/build/outputs/apk/debug/app-debug.apk`

---

## 📝 License

Copyright (c) 2026 Prathap. All rights reserved.
