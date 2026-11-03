<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.34

Phone app **DRAUPNE Flight 2.3.0-pc.27** goes with it: update the phone (tap its yellow *Update available* line). The video fixes need both.

- **Why the picture was bad.** The phone's own emergency brakes counted the time its packet pacing takes on purpose, so at 2 Mbit/s and under nearly every key frame tripped them: a step down each time, raises locked for 2 s, and the bitrate sank to 150 kbit/s and stayed there on a perfect link (the 2026-11-03 log: 12 minutes at 45 ms ping). The brakes now count the socket's own time only. Bad coverage mode also never shrank the picture on a phone without a small 16:9 size (it took 1280x720 again); it takes 640x480 now, letterboxed on the PC.
- **SHARP means sharp.** A sharp tier above the normal picture: 1920x1080 at up to 8 Mbit/s (settings `sharpWidth`, `sharpHeight`, `sharpBitrateLimitMbps`). MENU > VIDEO > *Video*: **SHARP** is always there; **AUTO** goes there after 20 s of calm at the top bitrate and drops back to the normal picture at the first stall; **ROBUST** is always bad coverage mode. The log says "Video: sharp (calm at 4 Mbit/s for 20 s)". It costs about 2 ms of decoding per frame on a modern PC.
- A 720p picture on a 1080p screen is up-scaled with a sharper filter (0.4 ms more a frame).
- **Video mode:** the PC tells the phone its video profile with the first settings of every session, the normal one included, so a phone left in bad coverage mode by the session before no longer stays in it.
- **Tour:** the first step tells you every button can be pressed twice. EXIT TOUR is a small cross in the top right corner of the notice; NEXT spans the box.
- **Guide:** the *Connect DRAUPNE Flight* step no longer scrolls down by itself. The *Sleep before take-off* step is gone: the tour's SLEEP BUTTONS step covers it.

None of the video changes has flown: a bench test with the far phone on 4G first, in AUTO, with the link logs kept.
