# Roadmap

## Milestone 1: RC link (built)
- [x] Project with upstream pinned as a submodule; upstream `Common` compiled as-is
- [x] Connect / Config / FcInfo handshake, ping, RC frames (`ControlLink`)
- [x] TX16S as a joystick through GLFW, configurable AETR mapping, `monitor` tool
- [x] Safety: throttle-low gate, fail closed on missing input, stale-value cutoff
- [x] Tests against a fake Flight phone built from upstream's own networking classes
- [x] **Bench test with the real phone, FC and TX16S:** RC control confirmed working on 2026-10-27. The failsafe items on the README checklist are still to be confirmed

## Milestone 1b: GUI, calibration, 4G (built)
- [x] Main window: link settings and status, live stick views, channel bars, log
- [x] Calibration wizard: range sweep, centers, detect each stick by moving it, AUX switches, live check with invert
- [x] Phone on 4G: VPN (Phone mode), external relay, or the upstream relay hosted in-process (tested end to end with a fake phone)
- [ ] Measure ping and failsafe behaviour over real 4G

## Milestone 1c: Low-latency RC path (built)
- [x] Read, map and send in one tick at 100 Hz (was a separate 25 Hz thread)
- [x] `Pacer`: accurate loop timing despite Windows' 15.6 ms timer tick
- [x] Announce 250 Hz to the Flight app so it doesn't drop bunched frames over 4G

## Milestone 2: Video, latency first (built - first version)
- [x] `StartVideo` (H.265 by default, `videoCodec=avc` for H.264), codec config from `VideoInitialFrame`
- [x] Fragments go straight from the socket thread to `FrameAssembler`, skipping the reliability queue. Frames are released
      the moment their first and last fragments are in; out-of-order fragments are kept (upstream dropped them)
- [x] FFmpeg software decode with LOW_DELAY, slice threads only (no frame threads), OUTPUT_CORRUPT and SHOW_ALL
      (keep a live, concealed picture after loss instead of freezing until the next key frame)
- [x] Show the newest frame only: every frame is decoded, but a picture is converted and drawn only if nothing newer is waiting
- [x] Colour conversion scales straight to the on-screen size, so drawing is a 1:1 copy; no Swing double buffering
- [x] Bitrate control ported from upstream (decrease on loss, late frames or ping over 300 ms; increase after 10 s calm)
- [x] Measured at 720p: decode ~0.9-1.0 ms, convert and draw ~0.4-0.8 ms per frame (H.264, textured test stream)
- [x] Tests: real FFmpeg encode then decode, plus end to end from fake phone over UDP to the screen buffer, with a lost fragment
- [ ] **With the real phone:** check H.265 from its encoder, the fps, and the bitrate it settles at over 4G
- [ ] Measure glass-to-glass latency: film a stopwatch through the camera next to the PC screen
- [ ] If measurements call for it: GPU drawing (OpenGL) and D3D11VA decode. CPU decoding is fast enough at 720p, so not needed yet

## Milestone 3: Telemetry and OSD (built)
- [x] Military-style HUD over the video: horizon and pitch ladder, heading tape with home marker, battery, link and 4G signal, GPS, timer, modes, warnings (F2 toggles it)
- [ ] Check the pitch direction on real hardware (osdInvertPitch=true flips it)

## Milestone 4: Easy setup and release (built)
- [x] One setup wizard: PC, radio, flight controller (Betaflight CLI and MSP over USB), phone (QR codes, APK served from the PC), link test
- [x] Windows installer and portable zip built by GitHub Actions on version tags
- [x] Telemetry parser for every record the Flight app sends (FcTelemetry), with BOXNAMES requested: armed, active modes, arming blockers, battery, camera fps
- [x] RC CHECK window and an FC status line on the main window
- [x] RC frames padded to 8 channels so the Flight app always applies Betaflight's channel map
- Port `processTelemetryData` and the parts of `Osd.java` that matter for Betaflight (battery, attitude, GPS, flight mode, warnings).
