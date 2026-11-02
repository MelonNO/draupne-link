<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.26

The setup guide, reworked from a pilot's walk-through:

- **Shorter, 13 steps.** The phone overview, "Measure the delay" and "Ready" pages are gone. FINISH plays a short **SETUP COMPLETE** animation and, the first time, offers a **tour of the window**: MENU, the controller card's fold, pop-out video, the map's place, then the map window itself (moving around, pins, what the cursor shows, the drone, DOWNLOAD AREA), one lit control at a time with NEXT / SKIP TOUR. MENU > SETUP > *Tour* shows it again.
- **Betaflight step:** "Plug a USB data cable into the drone and this computer." The battery line says to enter the cell count. A short note explains the AUX channels (which switch is which is chosen later, in the radio step; in doubt leave them). **RETRY**, and **DISCONNECT / RECONNECT** to free the USB port for Betaflight Configurator. A board running INAV, ArduPilot or something else is named, with "flash Betaflight". When everything is set it says you can unplug the drone.
- **Radio step:** the controller list is always shown, so another controller can be picked at any time. In the calibration, an AUX nothing was chosen for is **"AUXn - reserve"**.
- **Phone steps:** "Install the mobile app" (the code is new and downloads DRAUPNE Flight; the row stays neutral, since this PC can't see the install), "Settings on the phone" (short, DRAUPNE Flight by name, "the names differ from phone to phone"), "Connect DRAUPNE Flight" with *Always allow* / *Use by default*, and a **camera check**: the live picture in a small preview and **FLIP** for a phone mounted upside down (it sets VIDEO > Camera upside down).
- No "USB OTG adapter" anywhere: a USB data cable.

Also:

- **Motor test:** moving a slider or clicking the picture without the box ticked shakes the risk line; without a battery a pop-up says to plug one in; **a click on a motor in the picture runs it for 3 seconds** at a low value.
- **Latency test:** "Hold still until a time shows below: that is your delay" and "Press ESC to end the latency test".
- **Download area:** PICTURE is now **MAP TYPE**; no hard limit on the size any more, a warning with a rough time instead; **HIDE** puts the window away while the download goes on (the map window shows DOWNLOADING n / m, a click brings it back); **SELECT WITH CURSOR** draws the box without holding Alt.
- **The window opens maximized.**

The phone app is unchanged: DRAUPNE Flight **2.3.0-pc.18**.

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
