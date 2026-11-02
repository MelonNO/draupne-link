<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.24

- **The map saved for offline use: DOWNLOAD AREA** in the map window (or hold **Alt** and drag a box on the map, or right-click > *Download this view*), like Mission Planner's prefetch. One START fetches every tile of the area for all four pictures (Kartverket topo, paper map, satellite, OpenStreetMap) and every zoom level, each picture to its deepest; the window says how many tiles and roughly how much first, shows a bar and the figures while it runs, and CANCEL keeps what is saved. One picture or fewer levels can be chosen instead. Tiles already saved are skipped, so an area can be run again for what is missing. OpenStreetMap is fetched one tile at a time, with a pause: keep its areas small.
- **The phone's GPS works indoors too (phone app 2.3.0-pc.18):** the phone asked Android's bare GPS only, so the map never got the phone's position indoors, where Google Maps has one. Now it asks the GPS, the network (Wi-Fi and cell) and, on Android 12 and up, the fused position; a fresh GPS fix wins. The phone's screen says what its GPS is doing: not allowed (tap to allow), no position yet, or the provider, accuracy and age of the position it sends.
- **The phone app updates itself (pc.18):** the *Update available* line downloads the new app, checks it against GitHub's checksum and installs it from inside the app, so nothing piles up in Downloads any more. The first time, Android asks to allow the app to install apps. It refuses while connected: an update closes the app, and the sticks with it. A one-time note opens Downloads for the installers earlier updates left there. (The update **to** pc.18 still goes through the browser, as before.)
- **Link log:** the phone's position (`phone_lat`, `phone_lon`, `phone_acc_m`, `phone_age_s`), blank when the phone sends none.
- **Fix:** an error in the boot animation when its first frame came late (an uncaught exception in the app log).

The phone app in this release is DRAUPNE Flight **2.3.0-pc.18**. Update it: in the app, tap *Update available ... Tap to install it* (this time it opens the browser; from pc.18 on it installs itself), or SETUP > Phone setup on the PC.

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
