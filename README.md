# WygOTA

**Live over-the-air TV from an HDHomeRun tuner.**

WygOTA is the antenna-only companion to [WygTV](https://github.com/wyglaf01/WygTV-AndroidTV).  
It does one thing well: free local channels from a SiliconDust HDHomeRun network tuner, with a proper channel list and TV guide.

No IPTV playlists, no movies, no series — just OTA.

---

## Platforms

| Platform | Location | Notes |
|----------|----------|-------|
| **Android TV / Fire TV / tablets** | `android/` | App ID `com.wygtv.ota` — installs next to WygTV |
| **Ubuntu / Linux desktop** | `desktop/` | Java + VLC (libVLC). Package name `wygota` |

---

## Features

- Automatic HDHomeRun discovery (UDP broadcast + SiliconDust cloud lookup)
- Channel lineup from the tuner (`/lineup.json`)
- Free ~12-hour program guide from SiliconDust (or your own XMLTV)
- Live preview + full TV guide
- Remote / D-pad friendly (Android TV) and touch-friendly (tablets)
- AC-3 / E-AC-3 audio via Jellyfin Media3 FFmpeg extension
- Tablo support (optional)
- Self-updating (Android)

---

## Build

### Android

```bash
cd android
# Debug APK
./gradlew :app:assembleDebug
# or without wrapper:
gradle :app:assembleDebug
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Ubuntu / Linux desktop

```bash
cd desktop
gradle installDist
./build/install/wygota/bin/wygota
```

Requires VLC:

```bash
sudo apt install vlc
```

---

## First run

1. Connect an HDHomeRun tuner to your network and antenna.
2. Open WygOTA.
3. Add the tuner (`hdhomerun.local`, *Find tuners*, or type an IP).
4. The app opens on the TV guide of the tuner’s channels.

---

## Relationship to WygTV

WygOTA was originally built from the same codebase as WygTV using a `WYGOTA=1` build flag.  
This repository is the extracted, standalone version focused only on antenna TV.

Full multi-source player (Xtream Codes, M3U, Movies, Series + antenna):  
→ https://github.com/wyglaf01/WygTV-AndroidTV

---

## License

GPL-3.0 (same as the original WygTV project).
