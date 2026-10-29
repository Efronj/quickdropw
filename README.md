# QuickDrop

Fast, cross-platform file sharing between **Android** and **Desktop (Windows, macOS, Linux)** with an iOS-style glass UI. Supports both **maximum speed local Wi-Fi / Hotspot sharing** (up to 50–100+ MB/s) and **Anywhere / 5G sharing across different internet networks via a secure 6-digit code**!

🌐 **Website & Downloads:** [https://efronj.github.io/quickdropw/](https://efronj.github.io/quickdropw/)

---

## 📥 Downloads

| Platform | Download Link | Notes |
| :--- | :--- | :--- |
| 📱 **Android** | [**Download QuickDrop.apk**](https://github.com/Efronj/quickdropw/raw/main/QuickDrop.apk) | Android 10+, signed APK, auto-sorts to Gallery |
| 💻 **Windows PC** | [**Download QuickDrop.exe**](https://github.com/Efronj/quickdropw/raw/main/QuickDrop.exe) | Single `.exe` file, no extraction needed |

---

## ✨ Sharing Modes

1. **Local Wi-Fi / Hotspot Mode (Zero Internet Needed):**
   - Connect devices to the same Wi-Fi network or a phone's mobile hotspot.
   - AirDrop-style radar instantly discovers nearby computers (💻) and phones (📱).
   - Direct streaming over local network at full router bandwidth.

2. **Anywhere / 5G Mode (Cross-Network Sharing):**
   - Works across **different internet connections** (e.g. phone on 4G/5G mobile data, PC on home/office Wi-Fi, or different routers).
   - Generates a random **6-digit share code** on each device (e.g. `482-910`).
   - Enter the other device's code and connect in 1 tap via WebRTC P2P DataChannels.

---

## 📱 Android App

### Get the APK
- **Ready-to-install APK:** `QuickDrop.apk` in the root of this folder. Copy it to your phone and install it (allow *"Install unknown apps"* if prompted).
- **Or build via GitHub Actions:** Push to GitHub → Actions tab → **Build APK** → download `QuickDrop-apk`.
- **Or build with CLI:** `.\gradlew assembleRelease`

### Android Features
1. **Anywhere / 5G Sharing:** Connect across different internet networks via 6-digit code.
2. **Two-Way Sharing with Desktop & Phones:** Send and receive photos, 4K videos, APKs, docs, and notes directly with Windows PCs and other Android phones.
3. **Instant Local Discovery:** Automatically finds nearby devices using UDP broadcast on your local Wi-Fi or hotspot. Radar displays 💻 for computers and 📱 for phones.
4. **Gallery Auto-Integration:** Received photos are saved to `Pictures/QuickDrop` and videos to `Movies/QuickDrop`, immediately indexed into Google Photos & Gallery.
5. **System Share Sheet:** Appears in every Android app's **Share** menu (Files, Gallery, YouTube, browser links).
6. **Real-Time Progress & Speed:** Live progress percentage, transfer speed (MB/s), and time remaining.
7. **Trusted Devices:** Auto-accept connections from devices you trust.

---

## 💻 Desktop App (Windows / macOS / Linux)

### Instant 1-Click Launch (Windows)
Double-click **`QuickDrop.exe`** (or `QuickDrop-Desktop.bat`)!
It launches silently in the background and opens the native, borderless QuickDrop desktop window.

### Command Line Launch
```bash
cd desktop
node server.js
```

### Desktop Features
1. **Direct Windows Executable:** `QuickDrop.exe` runs directly without zip extraction.
2. **Anywhere / 5G Sharing:** Share files across the internet via 6-digit codes.
3. **Standalone App Window:** Runs as a frameless native desktop window with hardware acceleration and zero clutter.
4. **Animated Radar:** Discovers nearby Android phones and other PCs on the network in real-time.
5. **Drag & Drop:** Simply drag photos, 4K videos, or folders anywhere onto the window to send.
6. **Native File Browser:** Click *Browse Files* to open the Windows multi-file selector.
7. **Instant Clipboard & Notes:** Click *Send Clipboard* or *Write Note* to send links or text directly to your phone.
8. **Downloads Folder:** Files are saved directly to `Downloads\QuickDrop`. Click **📁 Open Folder** to view them in Windows Explorer.
9. **Incoming Transfers:** Auto-accepts files from your devices, or prompts with Accept/Decline.
10. **Web Portal & QR Code:** Includes a built-in web portal. Any device without the app installed can scan the QR code to upload or download files via their browser.

---

## 🚀 How to Share Between Android & Desktop

### Option A: On the Same Wi-Fi / Hotspot
1. Connect both your phone and PC to the same Wi-Fi network (or turn on Hotspot on one device and connect the other).
2. Open QuickDrop on Android and Windows PC.
3. Tap the device icon on the radar and pick your photos, videos, or files.

### Option B: Across Different Networks (e.g. Phone on 5G, PC on Wi-Fi)
1. Open QuickDrop on both devices.
2. Under **🌐 Anywhere / 5G Share**, look at your 6-digit code or enter the other device's 6-digit code.
3. Tap **Connect** — your devices will link across the internet.
4. Send any photos, 4K videos, documents, or notes instantly!
