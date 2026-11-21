<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.71

With the phone app **DRAUPNE Flight 2.3.0-pc.55** - update the phones (UPDATE PHONE APP, or SETUP > Set up a new drone). 1.0.70 was never released on its own: its parts are here too.

**Tested on the new test bench** (the real app with made-up drones, the real phone app in an Android emulator, nights of flying, sleeping and bad links) - **not yet flown on a drone, nor tried on a real phone.** Fly it props off first. Older phones and PCs still work with it.

**Before UPDATE PHONE APP:** on each drone's phone allow "Install unknown apps" for DRAUPNE Flight once (Android settings). The phone's status card asks for it until then; without it the update is refused and the PC says why.

### Flights are never lost
- Every armed flight is on disk from the start - track, recording, drone name - and found again after a crash or a restart. Only armed flights are kept.
- Video is recorded by itself, CONNECT to DISCONNECT. The last seconds before a lost link are on the disk too.
- PREVIOUS FLIGHTS: a debrief line (duration, distance, height, lowest cell, link losses, worst ping) and SEARCH by drone or date.

### Link
- A cleaner reconnect: after a loss the PC and the phone agree on one new connection, also on a slow or one-way link.
- Back about a second sooner after a short cut of the phone's way back.
- A PC whose own address changes in flight (without Tailscale) gets its drone back instead of watching it.
- A late "disarmed" no longer stops the sticks of a drone just armed.
- ROBUST and SURVIVAL: stronger picture repair in weak signal.
- G2G on the OSD (off by default): how old the picture is.

### Drones and phones
- OUT OF ACTION (was CRASHED): any drone, kept until cleared by hand; it keeps beeping to be found.
- LAST KNOWN: every phone's last grid, batteries and mode, kept on disk - on FLEET, the map and MAP TOOLS > DATA.
- Beeper: silent while resting on purpose (DISCONNECT, standby, sleeps); beeps when the link is lost.
- Li-ion limits: LOW BATTERY 3.2 V, EMPTY 2.8 V a cell; Drone setup writes them to Betaflight.
- Heading without a compass from the GPS track; bad GPS readings are dropped.
- One CHECK EVERY for DEEP SLEEP and OVERWATCH.
- DEEP SLEEP: the phone falls asleep on time, also on a jammed link.
- UPDATE PHONE APP installs without a question on the phone (Android 12 and later).
- Drone setup: ESCS row (reads the ESCs' firmware and beacon); motor direction uses DShot 7 / 8 - try it props off.

### Screen
- The OSD says CONNECTING until the first link; LINK LOST only after one was up. Warnings on a dark ground.
- MARK on the video (P) drops a pin where you click; pins stand on the live picture.
- Small windows: no squashed mini-map; buttons and words fit.

### Fixed
- A second copy of DRAUPNE Link no longer deletes the first one's flight.
- The map comes back after a lost internet, also on a very slow one.
- Saves that fail are said in the log (TRACK NOT SAVED, LOW DISK ...).
- An error in the phone app no longer ends its link for good.

Everything, in detail: [CHANGELOG.md](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
