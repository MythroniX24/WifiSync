# WaveDrop 🌊

WaveDrop is a robust, offline-first WiFi Mesh File Sharing application for Android, built with modern Kotlin and Jetpack Compose. It allows users to seamlessly and securely transfer files across local networks without relying on an active internet connection.

## 🚀 Features

- **Peer-to-Peer Discovery:** Uses NSD (Network Service Discovery) to automatically find and connect to other WaveDrop devices on the same local network.
- **Fast Local Transfers:** Send and receive files (Images, Audio, Texts) directly via high-speed LAN/WiFi using standard Socket communication.
- **Offline First:** Complete functionality over a local network. No internet required.
- **Cross-category Filtering:** Filter your shared space by file types (All, Images, Audio, Texts, Links).
- **Link Detection:** Automatically detects URLs shared in text files, providing a one-click "Open Link" and "Copy Text" actions.
- **Real-time Transfer Progress:** Live visual feedback of file transfer progress via progress bars.
- **Modern UI:** Built fully with Jetpack Compose featuring Material Design 3 guidelines for a polished and responsive user experience.

## 🛠️ Architecture & Tech Stack

- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose (Material 3)
- **Local Database:** Room Database for persistent storage of file metadata and device history.
- **Networking:** Custom TCP Server/Client architecture using `Socket` and `ServerSocket` for robust P2P transfers.
- **Image Loading:** Coil for asynchronous image loading and caching.
- **Coroutines & Flows:** For elegant multi-threading and reactive UI state management.

## 📦 Getting Started

### Prerequisites
- Android Studio Toolbar / Google AI Studio Applet Environment.

### Build and Run
```bash
# Build the application
./gradlew assembleDebug

# Run tests
./gradlew test
```

## 🔔 Usage

1. **Launch WaveDrop:** Open the application. Ensure your device is connected to a WiFi network.
2. **Discover:** The app will automatically discover other devices running WaveDrop on the same network.
3. **Share:** Select a peer and choose to send text snippets, links, or files directly.
4. **Accept & Track:** Incoming files will prompt for acceptance, and real-time progress indicators will appear on active transfers.
