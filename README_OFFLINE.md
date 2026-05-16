# Chad Player - 100% Offline Android APK

This project embeds Python (ytmusicapi + yt-dlp) directly in the Android app using Chaquopy. No server needed.

## Build Steps (Android Studio required)

1. Install Android Studio Hedgehog or newer
2. Open this folder in Android Studio
3. Let Gradle sync (downloads Chaquopy + Python packages)
4. Connect Android 14 device or emulator
5. Build > Build APK

APK will be ~45-60MB (Python + yt-dlp included).

## How it works
- MainActivity starts Python on launch
- server.py runs FastAPI on 127.0.0.1:8000 in background thread
- WebView loads local index.html which calls the local API
- yt-dlp extracts direct audio URLs (no ffmpeg needed for streaming)

## Android 14 notes
- INTERNET permission required
- First launch takes 5-10s to start Python
- Keep app in foreground while playing (Android 14 restricts background)

Tested targetSdk 34, minSdk 26.
