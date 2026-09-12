# Focus Vault 🛡️

[![Android](https://img.shields.io/badge/Platform-Android_8.0%2B_%28API_26%2B%29-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin_1.9.22-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Download APK](https://img.shields.io/badge/Download-FocusVault-release.apk-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Prathap2349/Focus-Vault/raw/main/FocusVault-release.apk)
[![Privacy](https://img.shields.io/badge/Privacy-100%25_On--Device-0052CC?style=for-the-badge&logo=shield&logoColor=white)](#-privacy--security-guarantee)

---

**Focus Vault** (package `com.focusvault.app`) is an ultra-reliable, privacy-first native Android application and website blocker built for extreme discipline and deep focus. Focus Vault integrates unbypassable session protection, local DNS packet filtering, 24/7 permanent Vault Lock, PBKDF2 cryptographic locks, custom multi-tier home screen widgets, and offline analytics into a clean, modern Material interface.

---

## 🌟 Key Capabilities & Features

### 1. 🔒 Triple-Tier Discipline Modes
Choose your exact level of discipline for every focus session:
- **Focus Mode (Lite)**: Standard session protection. Allows stopping early if priorities shift.
- **Lock Mode (Deep)**: Security PIN guarded. Stopping early or modifying blocklists requires your master PIN.
- **Strict Mode (Iron / Hardcore)**: Unbreakable lock. Zero early exits, no emergency stop, and no bypasses allowed until the session timer reaches zero.

### 2. 🌐 24/7 Permanent Website Blocking & Vault Lock Guard
- **24/7 Round-the-Clock Web Blocking**: Block distracting websites permanently without needing an active focus session timer. The local `FocusVpnService` stays active in lightweight DNS packet inspection mode automatically — and **auto-reconnects after device reboot** via `BootReceiver`.
- **Smart VPN Auto-Start**: Adding a 24/7 domain always auto-starts the VPN tunnel. If VPN permission was revoked, the **"Enable 24/7 Blocking"** button re-prompts for permission in one tap.
- **Pause / Resume 24/7 Blocking**: Need a temporary break? Tap **"⏸ Pause 24/7 Blocking"** and choose 30 min, 1 hr, 2 hrs, or 3 hrs. A live countdown banner shows remaining pause time. Tap **"Resume Now →"** to re-enable blocking instantly — or let it expire automatically.
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
- **3-Mode Pop-Up Flow**: Tapping the widget always shows the discipline selection card (Focus / Lock / Strict) before the timer setup — the direct-to-focus bypass was removed.
- **Instant Lifecycle Sync**: Widget snapshots re-render automatically across all size tiers (1x1, 2x1, 4x1, 2x2+) whenever a session starts, pauses, stops, or completes.
- **Wide Banner (4x1)**: Displays live session countdown and full control actions.
- **Large Bento (2x2)**: Expanded card showing active mode, target goals, progress ring, and controls.
- **Compact Tile (2x1)**: Essential countdown timer and quick start trigger.
- **Tiny Quick-Pick (1x1)**: 1-tap shortcut launching a centered 3-mode pop-up selection card (`WidgetModeQuickPickSheet`) with Bento design over translucent dim background.
- **Fail-Safe Rendering**: Built-in resilient triple-layer fallback wrappers to eliminate blank widget states on launcher redraws.

### 5. ⏱️ Modernized Bento Timer Selector & Motion Controls
- **Bento Hero Card & Live End-Time**: Displays high-contrast duration hero text (`25 min`, `1h 30m`) with live dynamic end-time calculation (`Session ends at 5:30 PM`) and spring bounce scaling animations.
- **1-Tap Quick Steppers**: Sleek `-15m`, `-5m`, `+5m`, `+15m`, and `+30m` stepper adjustment buttons with light haptic tactile feedback. Button labels render correctly with proper minus/plus characters.
- **Spring-Animated Preset Chips**: 1-tap quick presets (15m, 25m, 45m, 1h, 90m, 2h, 3h) with fluid staggered cascade entrance.

### 6. 💬 App-Wide Focus Vault Dialog Engine (`DialogHelper`)
- **Card-Styled Modal Containers**: Replaced all stock Android OS alert dialogs across Settings, Goals Configuration, Presets, Schedules, and Backup/Restore with Focus Vault rounded Bento cards.
- **Pill Input Fields**: Clean rounded pill inputs for goal minute thresholds, schedule titles, preset names, and JSON recovery.
- **Custom Single-Choice Pickers**: Clean radio selection cards for Theme mode, Color Palettes, and Website Preset packs with color swatches and domain counts.

### 7. 🔑 Modernized PIN Security & Recovery System
- **Pill-Shaped Input Card Dialogs**: App lock PIN verification, PIN creation/change, and security question recovery modals redesigned with clean Material cards and number-only numeric keypad.
- **Custom Question Selection Modal**: Replaced outdated Android dialog spinners with clean, modern Material dialog pickers for security recovery questions.
- **Cryptographic Security**: PBKDF2 with 10,000 iterations + SHA256 HMAC and random salt generation for 100% offline PIN security.

### 8. 🎨 Expanded Theme Engine & Dark Mode
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
- **Consistent Vector Iconography**: Pure vector drawables across all screens.
- **Haptics & Breathing Aura**: Tactile vibration feedback and pulsing focus aura animations during active focus blocks.

---

## 🐛 Recent Bug Fixes (Latest Build)

| # | Bug | Fix |
|---|-----|-----|
| 1 | **24/7 blocking not working** — sites not blocked after VPN disconnected once | `syncFastCache()` now always resets `isVpnManuallyStopped=false` when permanent domains exist, so VPN always auto-restarts |
| 2 | **VPN doesn't restart after reboot** | `BootReceiver` now restarts `FocusVpnService` on boot if permanent domains exist and VPN permission is granted |
| 3 | **VPN permission silently fails if revoked** | Full `VpnService.prepare()` + `startActivityForResult()` flow added with user-facing "Enable 24/7 Blocking" button |
| 4 | **Badge text wrapping** — "POPULAR" split to "POPULA R", "HARDCORE" to "HARDCO RE" | Badge rows use `match_parent` width with title absorbing slack via `layout_weight=1`, badges get `singleLine=true` |
| 5 | **Stepper button characters & text cut off** — `-15m` rendered oddly and clipped vertically | Added `textAllCaps=false`, zeroed `insetTop`/`insetBottom`, and removed vertical padding to fit text inside 32dp bounds |
| 6 | **Widget bypasses mode selection** — tapping widget jumped directly to timer | `WidgetQuickStartActivity` now always calls `showCenteredModeSelection()` first |
| 7 | **Enable/Pause 24/7 VPN buttons missing on start** — Controls hidden on app open | `refreshVpnStatusCard()` now checks synchronous `PrefsManager` instead of async database load, instantly rendering controls |
| 8 | **24/7 Block causes ALL sites to fail/block** — Upstream DNS silently timing out | Dropped hardcoded `8.8.8.8` DNS which is blocked by some networks; now dynamically queries the system's `ConnectivityManager` to forward DNS to the active local router/ISP IPv4 DNS. |
| 9 | **Widget buttons unresponsive or hidden** — Start button pushed off-screen | Fixed widget responsive breakpoints and aggressively optimized `widget_compact.xml` margins/padding so the 'Start' button comfortably fits inside smaller 2x2 widget bounds on low-DPI devices like the Vivo Y9. |
| 10 | **All websites failing to load when VPN active** — DNS routing loop | Removed `UPSTREAM_DNS` from the VPN's intercept routing table. Previously, if the phone's native `protect()` method failed, forwarding DNS to a filtered IP caused an infinite VPN loop. It now safely bypasses the VPN tunnel natively. |

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
com.focusvault.app/
├── adapter/          # RecyclerView Adapters (AppListAdapter, SiteListAdapter, HistoryAdapter)
├── appwidget/        # 4-Tier Home Screen Widgets & RemoteViews Provider
├── data/             # Room Database Entities, DAOs & Migrations (AppDatabase, BlockedApp, BlockedSite)
├── manager/          # Core Engines (SessionStateManager, SecurityManager, ThemeManager, FocusStatsManager)
├── receiver/         # BootReceiver (VPN + session restore on reboot), ScheduleAlarmReceiver
├── service/          # System Services (AppBlockAccessibilityService, FocusVpnService, SessionTimerService)
├── ui/               # Activities & Bottom Sheets (MainActivity, AppSelectionActivity, WebsiteBlockActivity, SettingsActivity, DialogHelper)
└── util/             # Utility Helpers (DnsPacketParser, HapticHelper, AppUtils, PrefsManager)
```

---

## 🛠️ Build & Installation

### Download Pre-built APK
Get the latest compiled binary directly:
👉 [**Download FocusVault-release.apk**](https://github.com/Prathap2349/Focus-Vault/raw/main/FocusVault-release.apk)

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

