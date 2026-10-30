<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.14

- **Warning sounds:** a short beep when LINK LOST, LINK STALL, FAILSAFE or NO FLIGHT CONTROLLER starts, again every 3 s while it lasts (SETTINGS > Warning sounds, on by default).
- **RADIO FROZEN? and VIDEO FROZEN on the OSD:** armed with not one bit of the radio's input changing for 5 s (a hung USB radio still reports itself), and no new picture for 0.7 s while the stream runs.
- **The arm switch must be off before the sticks start,** as well as the throttle down: Drone setup reads where Betaflight's ARM mode is (channel and range) and shows it; until then only the throttle rule applies. The OSD says which: RC OFF - THROTTLE DOWN / ARM SWITCH OFF.
- **Drone setup backs up Betaflight first:** before it writes anything, Betaflight's whole "diff all" goes to Documents\DroidDrone PC\Betaflight backups (paste it into the CLI to go back); what changed is in the log. A **Port** list appears when more than one flight controller port is found.
- **Recording:** SETTINGS > Record video records every connection's video to Videos\DroidDrone PC, as it comes from the phone (an MKV file, no re-encoding: no extra delay or CPU, and a file cut short still plays); a red REC on the OSD while it records.
- **Hardware video decoding** (SETTINGS, off by default): decodes on the GPU (D3D11VA), software if it can't. Measured here at 720p H.264 it is slower (about 3.0 ms a frame against 1.3 ms: copying the picture back from the GPU costs more than decoding saves), so it is only worth trying with bigger pictures or a slow CPU.

### Fixed (from a full code review)

- **Motor test: a motor can no longer start again after Windows shuts down or logs off.** The shutdown hook now closes the test for good (STOP, then nothing more is sent); the motor thread always closes it, whatever ends it; the mouse wheel and dragging only move a slider in the active window; it uses the first port that answers as Betaflight, not just the first port.
- **The sticks never come from another device.** Once a radio is in use only that radio is read; if it disappears there are no sticks (no other joystick, gamepad or virtual device takes over), until it is back or you choose another one.
- **Sticks start only once the link is up and the throttle is low then,** and stop when the link goes down (throttle low again when it is back).
- **LINK STALL:** nothing from the phone for 0.3 s while connected shows **LINK STALL** and the red frame within a third of a second (before, nothing showed for 2-4 s); the ping shows "---" after 0.5 s; a stall is no longer reported as a lost flight controller.
- **Video keeps going after a bad frame** (a fresh decoder from the next key frame, instead of a frozen picture until reconnecting); reordered or doubled packets on 4G are no longer decoded out of turn or twice, and a late packet no longer lowers the bitrate; after a reconnect the new stream's first key frame is kept (no 0.5 s of garbage); the video figures go to 0 when the video stops; the decoder and the screen no longer wait for each other (three picture buffers).
- **Drone setup writes only to Betaflight:** each candidate port is asked over MSP first (no restart, nothing written) and only Betaflight gets the CLI; a CLI answer that was cut off is never read as settings; it leaves the port alone while the motor test is open.
- **Built-in relay: no viewers** (upstream let two in with its default key, with the video and GPS position).
- **Installer: always in Program Files** (no folder choice: it runs the app as administrator from there), and the "is DroidDrone PC running?" check is JScript instead of VBScript, which Windows is phasing out.
- **Updates: the installer is checked again right before it runs and held so it can't be swapped,** a half download is deleted, and nothing is installed while DroidDrone PC still runs; the Tailscale installer is held the same way.
- **Smaller:** the stick-send error no longer floods the log (and only real sends count); a socket is closed when connecting fails half way; a wrong rcRate setting shows a message instead of quitting at start; the phone-app download server stops when the setup step is left; OSD warnings placed low stay in the picture; long tool output no longer holds up the Tailscale check; settings are saved off the Swing thread; the link log shows the sticks only when they were sent.
- **Phone app 2.3.0-pc.9:** reconnects to the flight controller at once after a USB glitch (and looks for it every 150 ms for 5 s - before, 2-4 s, past Betaflight's 1.5 s landing); the touch lock can't open with one tap when Android's animations are off, nor with a second finger; sticks wait for Betaflight's channel map (never in the wrong order); failed USB writes no longer count as delivered sticks; the old USB connection is closed on reconnect; auto-connect at start checks the permissions; the service no longer crashes when Android restarts it.
- **Builds:** CI also builds the phone app; a release checks the phone app it ships matches the phone app source; GitHub Actions pinned to exact versions; the signing-key password no longer goes on a command line.

### Also

- **Phone setup, "Keep the phone connected": one box per setting,** like the checks on the other steps, with an empty ring (something to do on the phone, not a warning). New box: **OTG switched on (some phones)** - on phones with an OTG switch (e.g. OPPO, realme, OnePlus, vivo) the phone doesn't see the flight controller while it is off, and many of them switch it off again after about 10 minutes without a USB device.
- **The motor test is a normal window:** Windows' own minimise, maximise (full screen) and close, like the OSD editor. Opening it again brings the open one forward; it also works when opened from a setup guide (Drone setup > CHECK NOW).
- **Bigger text in the setup guides:** the hint under each title, the paragraphs and the check boxes are about two points larger.
- **Phone settings are a checklist:** click a box in "Keep the phone connected" when it's set on the phone; it gets a green check (click again to undo) and is remembered for the next time.
- **Arrows in setup texts show:** "Settings > Apps > ..." paths showed an empty box where the arrow should be; they use ">" now.
- **Phone app 2.3.0-pc.8: no emoji.** The LOCK SCREEN button is plain text (still the only yellow button).
- **Windows and setup pages fade in:** a window that opens (OSD editor, motor test, calibration, RC check, setup guides) fades in, and so does a setup guide's page on **Next** and **Back**. The banded wipe from 1.0.13 is gone; the SETUP menu keeps its own animation.
- **Drone setup: Next without a choice about the motors.** The motors and props are assumed checked, so the Betaflight step goes on as soon as Betaflight is set up; **ALREADY CHECKED** and **CHECK NOW** (the motor test) are still there.

## Files

- **`DroidDrone-PC-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DroidDrone-PC-*-windows.zip`**: the portable version. Unzip and run `DroidDrone PC.exe`.
- **`DroidDrone-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/droiddrone-pc/blob/main/docs/CHANGELOG.md)
