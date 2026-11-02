<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.25

- **Fix: Drone setup could not set the receiver on Betaflight 2025.12.** That Betaflight's CLI has no name for the MSP receiver (`feature RX_MSP` is an invalid name there, and its feature listing cannot show it), so Drone setup logged the change, saved, and left the board with **no receiver at all** while reporting the receiver as not set. The receiver is now read and set the way Betaflight Configurator's Receiver tab does it, over MSP, which works on 4.5 and 2025.12 alike; the CLI still writes the rest. If Drone setup already ran on a 2025.12 board, run it again: it switches the receiver to MSP RX input in one go.
- **Drone setup says what Betaflight refused.** A CLI command Betaflight does not take is logged ("Betaflight refused: ...") and shown on the USB row, instead of passing as success.
- **The backup stays honest:** the settings file saved before a change shows the receiver as it was.
- **Fix:** the setup guide's hint under the page title wraps; the Betaflight page's was cut off at the right edge.
- README: new pictures (the mark with the name, the map window on topo and satellite, DOWNLOAD AREA).

The phone app is unchanged: DRAUPNE Flight **2.3.0-pc.18**.

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
