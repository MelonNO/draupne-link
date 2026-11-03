<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.33

Phone app **DRAUPNE Flight 2.3.0-pc.26** goes with it: update the phone (tap its yellow *Update available* line). Most of this release needs both.

**Weak reception.** A review of what happens when the 4G link fades, and the fixes:

- **The sticks never stop.** Once the link has been up, stick frames and pings go out whether or not the phone is heard. On 4G the phone's uplink is the weak direction: a drone whose sticks still arrive is under control while its video and telemetry are gone. Before, the sticks stopped 2 s into every silence from the phone, and only came back after a full handshake.
- **A loss costs a round trip, not seconds.** The phone takes the pilot's own re-Connect at once (before, it was dropped until the phone's own 4 s timer ran out); the PC sends Connect every 150 ms and the Config the moment the phone answers; the flight controller's state and the video config are kept, so nothing is read again and the stream is not restarted when it never stopped. A bug in the upstream transport (both apps) stopped every resend of acknowledged packets after the first reconnect: fixed.
- **Nobody takes the drone over during a fade.** A second PC gets the session only after 10 s of silence from the pilot (was 1 s), and never while the flight controller reports armed.
- **Video shows whenever it arrives.** Fragments are no longer thrown away while the link counts as lost; a backlog keeps its newest key frame; a key frame is asked for at once after a gap and never held more than 0.4 s; a restarted stream's missing config, or a key frame that gives no picture, asks the phone for the config instead of waiting; VIDEO FROZEN no longer goes quiet after a stream restart; the phone restarts its own camera and encoder when they give nothing for 2 s.
- **ROBUST works from the start.** The small picture size is in the very first config; the phone waits for a camera still opening before judging a size change.
- **A survival tier under bad coverage mode:** 480x270 at 15 fps, 150-250 kbit/s, when even bad coverage mode can't hold - a small picture rather than none. Bad coverage mode and under encode H.264 (its damaged frames are concealed, H.265's are shown) and send a parity packet per 8 packets, so a key frame survives one loss per group. Key frames leave at twice the bitrate instead of as one burst.
- **Bitrate control** judges spikes against the last second's median - jitter alone no longer pins the picture at the bottom - and goes to the floor in one packet when the uplink queue fills.
- **OSD:** NO STICKS AT FC (the phone's last stick write older than 300 ms, reported ten times a second), RELAY - MORE DELAY, STICKS STILL SENT under LINK LOST. 16-bit stick sequence (no dropped fresh frames after a cell handover).

None of this has flown on the far phone yet: a bench test with the phone on 4G first, and keep the link logs.
