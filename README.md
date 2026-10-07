# BT Bouncer 🥊

**BT Bouncer** is an open-source, battery-optimized Android utility designed to give you VIP control over your Bluetooth connections. Automatically disconnect conflicting or unwanted Bluetooth devices based on customizable multi-device rules, geofenced map locations, or quick home screen widget toggles — without ever unpairing them.

---

## ✨ Features

- **🛡️ VIP Bluetooth Filtering**: Block unwanted connections on-demand without unpairing devices from your system.
- **⚡ Quick Home Screen Widget**: Control your top 5 Bluetooth devices directly from a modern Android home screen widget.
- **🔄 Multi-Device Routines**: Create conditional rules (e.g. *When headphones connect, automatically disconnect car audio and desktop speakers*).
- **📍 Location-Based Geofencing**: Drop a pin on an interactive map to automatically silence or disconnect selected devices when arriving at work, home, or the gym.
- **🔋 Zero-Drain Location**: Uses cached system location (`getLastKnownLocation`) to prevent continuous GPS radio battery drain.
- **🖤 Sleek Pure Black Theme**: AMOLED pure black UI with `#00BF63` emerald accents and customizable Material 3 components.

---

## 🛠️ Tech Stack & Architecture

- **Language**: Kotlin 2.0+
- **UI Toolkit**: Jetpack Compose + Material 3
- **Local Persistence**: Room Database + SharedPreferences
- **Bluetooth Stack**: Android Bluetooth Adapter + Hidden Profile Manager APIs (Reflection-based multi-profile disconnects)
- **Maps**: Leaflet.js via Android WebView + Nominatim / Android Geocoder fallback
- **Min SDK**: Android 8.0 (API 26)
- **Target SDK**: Android 15 / 16 (API 35+)

---

## 🚀 Building & Running

### Prerequisites
- [Android Studio Ladybug | 2024.2+](https://developer.android.com/studio)
- JDK 17 or higher
- Android SDK with API 35 installed

### Clone & Build
```bash
git clone https://github.com/YOUR_USERNAME/BT-Bouncer.git
cd BT-Bouncer
./gradlew assembleDebug
```

You can install the debug APK directly to an attached device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 Permissions Used

- `BLUETOOTH_CONNECT` & `BLUETOOTH_SCAN`: Query and manage Bluetooth profiles (`neverForLocation` flag enabled).
- `ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION` *(Optional)*: Only requested when configuring location-based routines.
- `INTERNET`: For map geocoding and tile loading in the map picker.

---

## 🤝 Contributing

Contributions are welcome! Feel free to:
1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## ⚖️ License

Distributed under the MIT License. See `LICENSE` for more information.
