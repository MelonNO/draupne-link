<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.64 + phone pc.50

With the phone app **DRAUPNE Flight 2.3.0-pc.50** (update the phones). Not tried on a drone yet.

### Changed
- **Overwatch uses less battery** (pc.50). The camera gives detection a small picture (320x240) at 15 fps instead of 1280x720 at 30. The detection is the same. Measured before: 440 mA by day, up to 1 A in the dark.
- **Resting phones wake less:** the link's loops slow down while nobody is connected; the microphone opens only when something needs it; a leftover recording encoder is closed.
- **Clips list scrolls**, and each alert card can be minimized to its drone's name (-/+).
- **Sleep notice:** a phone going to sleep tells the PCs its position, heading, battery, mode and next check at once.
- **Deep sleep "listening" lets the phone sleep** between its minute hellos (a CONNECT may take a few seconds).
- **AIRSPACE on the map** (MAP TOOLS > TOOLS): R-areas and D-areas (openAIP key) and NOTAMs pasted from IPPC.no.
- **Previous flights** are in MAP TOOLS > DATA.

### Fixed
- **UPDATE from any copy:** a copy outside Program Files updates the installed one and starts it.
- **Broken desktop shortcut** after an update ("error opening ...\app\DRAUPNE Link.cfg"): a shortcut to nothing or to another copy is pointed at the installed app.
