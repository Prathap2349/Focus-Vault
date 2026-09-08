# Focus Vault 🛡️

[![Android](https://img.shields.io/badge/Platform-Android_8.0%2B_%28API_26%2B%29-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin_1.9.22-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Download APK](https://img.shields.io/badge/Download-FocusVault.apk-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Prathap2349/Focus-Vault/raw/main/FocusVault.apk)
[![Privacy](https://img.shields.io/badge/Privacy-100%25_On--Device-0052CC?style=for-the-badge&logo=shield&logoColor=white)](#-privacy--security-guarantee)

---

**Focus Vault** (package `com.stayfocused.app`) is an ultra-reliable, privacy-first native Android application and website blocker built for extreme discipline and deep focus. Focus Vault integrates unbypassable session protection, local DNS packet filtering, 24/7 permanent Vault Lock, PBKDF2 cryptographic locks, custom multi-tier home screen widgets, and offline analytics into a clean, modern Material interface.

---

## 🌟 Key Capabilities & Features

### 1. 🔒 Triple-Tier Discipline Modes
Choose your exact level of discipline for every focus session:
- **Focus Mode (Lite)**: Standard session protection. Allows stopping early if priorities shift.
- **Lock Mode (Deep)**: Security PIN guarded. Stopping early or modifying blocklists requires your master PIN.
- **Strict Mode (Iron / Hardcore)**: Unbreakable lock. Zero early exits, no emergency stop, and no bypasses allowed until the session timer reaches zero.

### 2. 🌐 24/7 Permanent Website Blocking & Vault Lock Guard
- **24/7 Round-the-Clock Web Blocking**: Block distracting websites permanently without needing an active focus session timer. The local `FocusVpnService` stays active in lightweight DNS packet inspection mode automatically.
- **Vault PIN Guard**: Enable Vault Lock to require master App Lock PIN verification before removing or unblocking any 24/7 permanent domain.
- **Segmented Filter Tabs**: Effortlessly filter your website blocklist between **All**, **24/7 Permanent**, and **Session Only** tabs with live count indicators.
- **Local DNS Tunnel & Key Icon Controls**: Zero external server routing. Includes 1-tap `ACTION_STOP` VPN disconnect controls to immediately hide Android's status bar key icon (`🔑`) when needed.
- **Preset Packs & Smart Suggestions**: 1-tap preset packs (Social Media, Video & Streaming, Gaming, Shopping, News) and on-device domain recommendations based on local queries.

### 3. 📱 Application Blocking & Live Re-sorting
- **Instant App Overlay**: Monitors foreground application switches via `AppBlockAccessibilityService` with anti-flash timing guards.
- **Live Selection Sorting**: Blocked apps and active sites automatically float to the top of selection lists with real-time Material Switch toggles.
- **Search & Inline Feedback**: Instant domain normalizer and search bar with red inline validation alerts.

### 4. ⚡ 4-Tier Home Screen Widgets & Real-time State Sync
- **Stale Timer & Ghost Countdown Fix**: Solved stale countdown timers (e.g. `48:56`) displaying when no session is active. `WidgetDataProvider` rigorously verifies active time windows (`now < endTimeMillis`), and `PrefsManager.forceEndSession()` purges expired end timestamps immediately.
- **Instant Lifecycle Sync**: Widget snapshots re-render automatically across all size tiers (1x1, 2x1, 4x1, 2x2+) whenever a session starts, pauses, stops, or completes.
- **Wide Banner (4x1)**: Displays live session countdown and full control actions.
- **Large Bento (2x2)**: Expanded card showing active mode, target goals, progress ring, and controls.
- **Compact Tile (2x1)**: Essential countdown timer and quick start trigger.
- **Tiny Quick-Pick (1x1)**: 1-tap shortcut launching a centered 3-mode pop-up selection card (`WidgetModeQuickPickSheet`) with Bento design over translucent dim background to instantly start focus sessions.

### 5. 🔑 Modernized PIN Security & Recovery System
- **Pill-Shaped Input Card Dialogs**: App lock PIN verification, PIN creation/change, and security question recovery modals redesigned with clean Material cards and rounded pill inputs (`bg_search_pill`).
- **Custom Question Selection Modal**: Replaced outdated Android dialog spinners with clean, modern Material dialog pickers for security recovery questions.
- **Cryptographic Security**: PBKDF2 with 10,000 iterations + SHA256 HMAC and random salt generation for 100% offline PIN security.

### 6. 🎨 Expanded Theme Engine & Dark Mode
- **Vibrant Color Palettes**: Choose between 8 curated palettes:
  - 🌌 **Focus Indigo (Aurora)**
  - 🌆 **Midnight Violet**
  - 🌊 **Ocean Blue**
  - 🌲 **Forest Emerald**
  - 🌅 **Sunset Amber**
  - 🕶️ **Minimal Slate**
  - ⚡ **Cyberpunk Neon** (*Cyan `#00F2FE` & Neon Red `#FF0844`*)
  - 🌿 **Emerald Zen** (*Emerald Green `#00E676`*)
- **AMOLED True Black**: Native support for OLED pitch-black backgrounds.
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
