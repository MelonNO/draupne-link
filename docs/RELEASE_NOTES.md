<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.12

- **Phone app 2.3.0-pc.6: touch lock for flying.** A yellow **LOCK SCREEN** button at the top covers the whole screen so stray touches do nothing; only a dashed yellow **HOLD 2 S TO UNLOCK** box in the corner still works (hold it for two seconds). While locked the screen dims (the phone is the drone's radio), back does nothing, the system bars hide and the screen doesn't rotate; the link and the flight controller keep running. Android doesn't let an app block the home gesture or the notification shade.
- **Phone app 2.3.0-pc.6: portrait as well as landscape.** It turns with the phone (following Android's auto-rotate); in portrait the two cards are stacked.
- **SETTINGS is a menu, like SETUP:** a click opens a list under the button - *Camera upside down* (on / off), *OSD layout*, *Motor test* - each with what it does, and the versions at the bottom. The separate SETTINGS window is gone.
- **Motors and props in the drone setup:** the Betaflight step (SETUP > Drone setup, and the DRONE step of the first-time setup) asks whether the motors and props are checked: **ALREADY CHECKED**, or **CHECK NOW**, which opens the motor test there and then. One of the two is needed to go on; the answer is remembered.
- **Motor test: props in or props out.** A **PROPS IN | PROPS OUT** switch above the picture turns the arrows round; **props out** is the default and your choice is remembered. With the flight controller connected, the Motor layout line warns when Betaflight's *Motor direction is reversed* doesn't match the choice (yaw would turn the wrong way) and says where to change it.
- **The OSD editor opens full screen** for the biggest preview; **WINDOW** / **FULL SCREEN** at its top right (or F11) switches.
- **MINIMIZE / EXPAND fold the controller panel smoothly** (a quarter of a second) instead of jumping; the video grows or shrinks with it. A click while it folds turns it round.
- **CALIBRATE without a controller** now says so in the same kind of notice as a locked CALIBRATE: under the button, with a red bar running down until it goes, never over the video. Before, a Windows message box came up.
- **Updates are found at once:** DroidDrone PC asks GitHub the moment it opens (it waited 8 seconds), on its own thread so the phone check never waits for it.

## Files

- **`DroidDrone-PC-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DroidDrone-PC-*-windows.zip`**: the portable version. Unzip and run `DroidDrone PC.exe`.
- **`DroidDrone-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/droiddrone-pc/blob/main/docs/CHANGELOG.md)
