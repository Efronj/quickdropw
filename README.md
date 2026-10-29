# QuickDrop

Fast, cross-platform file sharing between **Android** and **Desktop (Windows, macOS, Linux)** with an iOS-style glass UI. No cloud or internet required — transfers happen directly over your local Wi-Fi or mobile hotspot at maximum speeds (up to 50–100+ MB/s).

---

## 📱 Android App

### Get the APK
- **Ready-to-install APK:** `QuickDrop.apk` in the root of this folder. Copy it to your phone and install it (allow *"Install unknown apps"* if prompted).
- **Or build via GitHub Actions:** Push to GitHub → Actions tab → **Build APK** → download `QuickDrop-apk`.
- **Or build with Android Studio / CLI:** `./gradlew assembleRelease`

### Android Features
1. **Two-Way Sharing with Desktop & Phones:** Send and receive photos, 4K videos, APKs, docs, and notes directly with Windows/Mac/Linux PCs and other Android phones.
2. **Instant Local Discovery:** Automatically finds nearby devices using UDP broadcast on your local Wi-Fi or hotspot. Radar displays 💻 for computers and 📱 for phones.
3. **Gallery Auto-Integration:** Received photos are saved to `Pictures/QuickDrop` and videos to `Movies/QuickDrop`, immediately indexed into Google Photos & Gallery.
4. **System Share Sheet:** Appears in every Android app's **Share** menu (Files, Gallery, YouTube, browser links).
5. **Real-Time Progress & Speed:** Live progress percentage, transfer speed (MB/s), and time remaining.
6. **Trusted Devices:** Auto-accept connections from devices you trust.

---

## 💻 Desktop App (Windows / macOS / Linux)

### Instant 1-Click Launch (Windows)
Double-click **`QuickDrop-Desktop.bat`** in the root folder!
It starts the server and opens the native QuickDrop desktop window.

### Command Line Launch
```bash
cd desktop
node server.js
```
*(Or `npm start`)*

### Desktop Features
1. **Standalone App Window:** Runs as a frameless native desktop window with hardware acceleration and zero clutter.
2. **Animated Radar:** Discovers nearby Android phones and other PCs on the network in real-time.
3. **Drag & Drop:** Simply drag photos, 4K videos, or folders anywhere onto the window to send.
4. **Native File Browser:** Click *Browse Files* to open the Windows multi-file selector.
5. **Instant Clipboard & Notes:** Click *Send Clipboard* or *Write Note* to send links or text directly to your phone.
6. **Downloads Folder:** Files are saved directly to `Downloads\QuickDrop`. Click **📁 Open Folder** to view them in Windows Explorer.
7. **Incoming Transfers:** Auto-accepts files from your devices, or prompts with Accept/Decline.
8. **Web Portal & QR Code:** Includes a built-in web portal. Any device without the app installed can scan the QR code to upload or download files via their browser.

---

## 🚀 How to Share Between Android & Desktop

1. Connect both your phone and PC to the **same Wi-Fi network** (or connect your PC to your phone's Wi-Fi Hotspot).
2. Open **QuickDrop Desktop** on your computer (`QuickDrop-Desktop.bat`).
3. Open **QuickDrop** on your Android phone.
4. Each device will immediately appear on the other's radar:
   - **From Android to PC:** Tap your PC's bubble (💻) on the radar, select photos or files, and tap Send. They will appear in your PC's `Downloads\QuickDrop` folder.
   - **From PC to Android:** Drag & drop files onto the desktop app, click your phone's bubble (📱) on the radar, and click **Send**. The photos and videos will appear directly in your phone's Gallery!

---

## 🛠️ Architecture & Protocol

- **Discovery:** UDP Broadcast on port `52222`. Devices broadcast beacons and respond to pings.
- **Transfer Engine:** High-performance chunked HTTP streaming on port `52223`. Zero cloud dependencies.
- **Android Fallback:** Includes Google Nearby Connections P2P support for direct phone-to-phone transfers when Wi-Fi is unavailable.
- **Desktop Runtime:** Pure Node.js standard library — no heavy node_modules to install.
