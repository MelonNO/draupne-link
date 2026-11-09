<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.53

With **DRAUPNE Flight 2.3.0-pc.41**. Update the PC app, then the phone (UPDATE PHONE APP, drone disarmed).

Not yet tried on a drone or real phone: bench-test with props off first.

### New
- **Motion detection finds someone walking straight at the camera**, also out of the woods. Brightness only, so grey cameras work too.

### Changed
- **No colour in motion detection.** Camouflage and grey cameras made it unreliable. A person inside swaying leaves is found once out of them.

### Fixed
- **Drone setup:** moving ARM off the PREARM switch could lose ARM. "Arm without GPS fix" is only written where Betaflight has it.
- **Link:** resends stopped if the clock was set back (both apps). AUTO no longer jumps to SHARP on a new link.
- **Phone:** in standby the drone's status was mostly not read.
- **Files:** a setting written twice lost its change; a line break in a pin name wiped all pins; a stray character wiped map drawings; a ’ in a path broke the updater.
- **Recording:** a recording that failed at its start stayed locked.
- **Window:** the warning beep queued up on a flapping link; windows came back off-screen after moving monitors.

All details: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md).
