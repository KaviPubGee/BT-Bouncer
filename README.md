<div align="center">

<img src="docs/media/logo.png" width="128" alt="BT Bouncer Logo" />

# BT Bouncer 🥊

**VIP access only. Choose who gets in.**

An open-source, battery-optimized Android Bluetooth gatekeeper that gives you total VIP control over your connected devices. Block or drop unwanted Bluetooth connections automatically — based on customizable multi-device priorities, location geofences, or home screen widget toggles — without ever unpairing them.

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-15%20%2F%2016%20(API%2035%2B)-black?logo=android)](https://developer.android.com)
[![Kotlin 2.0](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![AMOLED](https://img.shields.io/badge/Theme-AMOLED%20Pure%20Black-000000?style=flat&logoColor=00BF63)](https://github.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-00BF63.svg)](LICENSE)

</div>

---

## 🧐 Why BT Bouncer?

Modern Bluetooth devices are stubborn. Your car stereo, desktop speakers, smartwatches, or gym headphones constantly compete to hijack your phone's audio output the second they enter range. 

Android only gives you two extremes:
1. **Stay connected** and let other devices hijack your audio or notifications.
2. **Unpair the device completely**, which forces you to go through the annoying pairing handshake every single time you want it back.

> **BT Bouncer gives you the third option: The VIP Velvet Rope.**  
> Toggle devices on or off like a switch. When turned off, incoming handshakes are dropped immediately across all Bluetooth profiles without removing the bond. Turn them back on anytime with a single tap.

---

## ✨ Features

- 🛡️ **VIP Bluetooth Filtering** — Block and drop connections on-demand without unpairing. Reconnect cleanly whenever you want.
- 🎧 **Universal Multi-Profile Support** — Drops handshakes across all major profiles:
  - `A2DP` (Media audio, headphones, cars, soundbars)
  - `HEADSET` / `HFP` (Handsfree calls)
  - `HID_HOST` (Keyboards, mice, gamepads, watch navigation)
  - `PAN` (Personal Area Networks / smartwatch data sync)
  - `LE Audio` & `Hearing Aid`
  - `GATT` / `BLE` (Smartwatches, fitness bands, and trackers)
- ⚡ **Interactive Home Screen Widget** — Control your top 5 Bluetooth devices with live battery / connection statuses right from your home screen.
- 🔄 **Conditional Routines** — Set automated device rules:
  - *When my headphones connect, automatically drop the car stereo and home speakers.*
  - *When my smartwatch connects, prioritize my wireless earbuds.*
- 📍 **Geofenced Locations** — Drop a pin on an interactive map. Automatically allow or silence selected devices when you arrive at work, home, or the gym.
- 🔋 **Zero-Drain Architecture** — Uses passive, cached system locations (`getLastKnownLocation`) and reactive Bluetooth broadcast receivers. Zero continuous polling loops or background battery drain.
- 🖤 **AMOLED Pure Black & Emerald UI** — Deep `#000000` AMOLED canvas accented with glowing `#00BF63` emerald green and Material 3 expressiveness.

---

## 📱 Screenshots

<div align="center">

| Devices View | Automated Routines | Interactive Geofence Map |
| :---: | :---: | :---: |
| <img src="docs/media/screen_devices.png" width="260" alt="Devices Screen" onerror="this.src='docs/media/logo.png';this.width=140" /> | <img src="docs/media/screen_routines.png" width="260" alt="Routines Screen" onerror="this.src='docs/media/logo.png';this.width=140" /> | <img src="docs/media/screen_map.png" width="260" alt="Map Picker" onerror="this.src='docs/media/logo.png';this.width=140" /> |

</div>

---

## 🛠️ Tech Stack & Architecture

```
BT Bouncer
 ├── Presentation (Jetpack Compose + Material 3)
 │    ├── MainActivity (AMOLED Theme, Device Cards, Live Scanning)
 │    ├── RoutinesScreen (Rule creation & trigger evaluation)
 │    └── MapPicker (Leaflet.js + WebView geocoding)
 ├── Background Engine (BroadcastReceivers + Coroutines)
 │    ├── BtConnectionReceiver (ACL & Profile state interception)
 │    └── BouncerAppWidgetProvider (RemoteViews home screen widget)
 ├── Core Bluetooth Engine
 │    ├── BluetoothProfileManager (Reflection proxies & multi-profile policy control)
 │    └── BluetoothHelper (Unified connection & disconnect API)
 └── Data Layer (Room + SharedPreferences)
      ├── AppDatabase (Routine rules & location triggers)
      └── DeviceBlockManager (Fast O(1) blocked MAC registry)
```

- **Language**: Kotlin 2.0+
- **UI Framework**: Jetpack Compose (BOM 2024.09.00) + Material 3
- **Local Database**: Room 2.6.1 (SQLite with KSP codegen)
- **Bluetooth Stack**: Android Bluetooth Adapter + Hidden Profile Manager APIs (Reflection-based multi-profile disconnects)
- **Map Engine**: Leaflet.js via Android WebView with OpenStreetMap tiles & Nominatim geocoder fallback
- **Min SDK**: API 26 (Android 8.0 Oreo)
- **Target SDK**: API 35 (Android 15) & Android 16 ready

---

## 🚀 Getting Started

### Prerequisites
- [Android Studio Ladybug | 2024.2+](https://developer.android.com/studio) or newer
- JDK 17 or higher
- Android SDK with Platform 35

### Clone & Build
```bash
# 1. Clone repository
git clone https://github.com/YOUR_USERNAME/BT-Bouncer.git
cd BT-Bouncer

# 2. Build Debug APK
./gradlew assembleDebug

# 3. Install directly to an attached device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔒 Permissions Breakdown

Privacy and battery efficiency come first. BT Bouncer never asks for unnecessary permissions:

| Permission | Purpose | Required? |
| :--- | :--- | :---: |
| `BLUETOOTH_CONNECT` | Query connection states and disconnect profiles | **Yes** |
| `BLUETOOTH_SCAN` | Discover nearby unpaired devices (`neverForLocation` flag active) | **Yes** |
| `ACCESS_FINE_LOCATION` | Required **only** if you enable Location Geofencing in Routines | *Optional* |
| `INTERNET` | Load OpenStreetMap tiles inside the Map Picker dialog | **Yes** |

> **Note on Location**: General Bluetooth scanning in BT Bouncer uses the `neverForLocation` attribute, meaning the OS does not treat Bluetooth scanning as location tracking.

---

## 🤝 Contributing

Contributions, bug reports, and suggestions are welcome!

1. **Fork** the repository
2. **Create** your feature branch (`git checkout -b feature/NewFeature`)
3. **Commit** your changes (`git commit -m 'feat: Add NewFeature'`)
4. **Push** to the branch (`git push origin feature/NewFeature`)
5. **Open** a Pull Request

---

## ⚖️ License

Distributed under the **MIT License**. See [`LICENSE`](LICENSE) for full details.
