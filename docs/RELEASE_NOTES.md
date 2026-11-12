<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.65 + phone pc.52

With the phone app **DRAUPNE Flight 2.3.0-pc.52**. Update the phones to observe them after they have been flown.

### STANAG 4609: one stream per drone
- **Every drone has its own stream,** on its own port, from the start of the app (MENU > LINK, Advanced: *STANAG 4609 streams*). Made on the PC: the phones send nothing for it.
- **Piloted or observed here:** the video with the OSD.
- **Overwatch alert:** the live picture with the detection boxes (red bar), then the clip once it has arrived (amber bar, RECORDED). New motion, PILOT UAV or a change of mode ends the clip at once.
- **Otherwise a page:** the logo, the mode in big letters, what it means - and the batteries in per cent (phone, drone), last heard, next check.
- **Ports:** SEND TO is `host:first port`. MobileAlpha gets the first, MobileBravo the next, and so on. The settings list every drone's address and what it shows.
- **A drone's own address:** type over its address in that list to send it to another IP and port. Empty = automatic again.
- **Overwatch sectors** are in the metadata: ATAK and QGIS draw them as the map does. Every stream carries its drone's name and position.
- **Two PCs to one address:** a warning in the log and the settings. Switch one off.

### Fixed
- **Observation of a phone in standby** (phone pc.52): DISCONNECT leaves a phone in standby, and it then refused the OBSERVATION grid. It wakes for the grid now, and goes back to standby at STOP OBSERVING.
- **STANAG settings:** the boxes could not be typed in (they were 15 px wide).
- **Demo:** the scenario's drones no longer appear on top of the six demo drones.

### Mind
- A video stream uses about 2 Mbit/s of this PC's upload (`stanag.bitrateKbps`). On a weak link it can delay the sticks. 127.0.0.1 costs none.
- SEND TO is `host:port` (`udp://host:port` is read the same). An old `rtp://` or file address falls back to 127.0.0.1:15000.
