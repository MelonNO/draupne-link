<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.49

With **DRAUPNE Flight 2.3.0-pc.38**, the phone app. Update the PC app on every PC, then the phone (UPDATE PHONE APP on the LINK card, with the drone disarmed).

This release carries everything since 1.0.43 (1.0.44 - 1.0.48 were never released on their own) and a fix round from a full review of all of it. **None of 1.0.44 - 1.0.49 has run on a drone yet**: bench-test with the props off before flying (Drone setup on your board, BEEPER, idle sticks with the arm switch, a link loss while armed).

### New since 1.0.43
- **Silent drone, BEEPER button:** Drone setup turns every Betaflight beeper off except receiver lost (the phone's USB pulled out), the failsafe's landing, BEEPER and ON_USB. The bell button under full screen makes the drone beep (buzzer and, disarmed, the motors) - Drone setup puts BEEPER alone on **AUX14**. Idle sticks from the phone keep Betaflight 2025.12 from beeping "receiver lost" while the drone waits disarmed.
- **GPS on one UART only**, with Betaflight 2025.12's port names too: moving the GPS no longer leaves it on the old port or wipes the old port's other functions (Tramp, ESC sensor).
- **4G COVERAGE** (MAP TOOLS > TOOLS): an estimate of 4G reception from the cell masts in OpenCelliD's open database (a free key needed).
- **Live sound** from the phone's microphone while disarmed, with MUTE (the M key); in recordings too, and for spectators.
- **Motion detection** while disarmed (VIDEO > Motion detection): people, vehicles and moving lights, not trees in the wind; boxes on the video and the direction on the map.
- **OVERWATCH SLEEP** (LINK card): the phone sleeps but watches; a detection calls this PC in the map window with a live preview, CONNECT and DISMISS, and the event's clip comes over afterwards (CLIPS in the map window).
- **OBSERVATION** (CONNECT AS > Observation): up to six idle drones at once in the video area, their sound mixed, each one's view on the map, CONNECT on a tile to fly it.

### Fixed in 1.0.49 (the review)
- **Arming:** BEEPER can't arm the drone (it never drives a channel ARM or the radio uses, beeps only while the drone reports disarmed, 10 s at most a press); stick frames carry only the radio's channels again; a channel the radio doesn't send counts as unknown, never as off. The phone checks idle sticks against Betaflight's own arm switch ranges and drops them at every new connection. A second DRAUPNE Link on the pilot's own PC can't take over a drone that isn't known disarmed. STANDBY, DEEP SLEEP and OVERWATCH ask first when the drone was armed moments ago.
- **Drone setup writes all or nothing:** a backup first, every line Betaflight takes compared with what was sent, and on any refusal or difference it leaves without saving - the flight controller stays as it was, and the page says why. CONFIRM belongs to the board it was pressed for. A GPS RESCUE or PREARM switch you set yourself is left alone. The motor test goes to 10 % at most, and every motor write is logged.
- **While a drone may be flying** (armed, or armed within 10 min before its link went quiet) nothing else uses the network: map tiles, cell data, overwatch clips, updates wait. Overwatch alerts in flight are silent. The drone can't be armed while its phone installs an update.
- **Video:** a frame lost whole now brings a fresh key frame instead of a smeared picture for seconds; long flights no longer make the video jittery (the maps redraw only on change); stream restarts no longer cost recording or freeze the picture.
- **And:** the LINK card turns red with LINK STALLED or FC LOST; two phones with the same name are caught before CONNECT; the settings file is saved safely (a folder path with `\u` in it stopped the app starting); overwatch clips are recorded in 1-minute pieces and survive a phone restart; UPDATE also checks the installer's Windows signature.

All details: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md).
