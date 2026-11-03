# DroidDrone protocol notes (protocol version 9, app 2.3.0, upstream commit 473608d)

These notes were worked out from the upstream source. File references are relative to `upstream/`.

## Topology
- **Flight app** (phone on the drone) talks MSP or MAVLink to the FC over USB OTG or UART, and encodes camera video.
- **Control app** (phone with the radio) shows video and OSD and sends RC. **This project replaces it with the PC.**
- **Server** (`Server/`, optional Java relay) is for when neither side has a reachable IP. Viewers can also connect through it.
- **Direct mode:** the Flight phone listens on UDP 6286 and the controller connects to its IP. A phone on 4G has no reachable IP, so direct mode then needs a VPN.
- **Over-server mode:** both sides connect out to the relay (`Server/src/.../Udp.java`). The relay echoes each `Connect` itself and forwards packets between drone (client type 0) and controller (type 1), so the controller code is the same in both modes. The relay knows clients by IP:port.

## Transport (`Common/.../UdpCommon.java`, `UdpSender.java`, `ReceiverBuffer.java`)
- UDP, default port 6286, packets of at most 1100 bytes, big-endian (Java `DataOutputStream`). Strings use `writeUTF`.
- Byte 0 is the packet type (0–26).
- **Numbered packets** (`isPacketNumbered`) carry an int16 sequence number after the type. The receiver delivers them in order and asks for gaps with `RequestPackets`.
- Some numbered packets are **ACKed** with `PacketReceived` (`isSendPacketReceived`). The sender resends them about every 1.2 × ping until ACKed or expired (1 s).
- Video, audio and RC frames are unnumbered and fire-and-forget.
- The link is considered lost after about 4000 ticks of `processTimer()` (about 4 s at the 1 ms loop) with no packets.
- Ping/Pong: the controller sends `Ping(toEndPoint=true, timeMs)` every 500 ms, and the end point echoes it as `Pong`.

## Handshake (Control `MainActivity.startConnectionThread`)
1. Every 500 ms: `Connect` = `[8][int16 -1][clientType u8][key UTF][version int16]`. Client type: 0 = flight, 1 = controller, 2 = viewer.
   The Flight side (`ReceiverBuffer` with isServer = true) checks the key and echoes the `Connect` back, which connects the controller too.
2. Every 500 ms until answered: `Config` (layout in `Control/.../Udp.sendConfig` and `Flight/.../Config.processReceivedConfig`).
   The Flight app replies `ConfigReceived`, or `VersionMismatch` if the version differs from 9.
3. `StartVideo(isHevcSupported bool)` every 2.5 s until a `VideoInitialFrame` arrives.
4. Requests for `FcInfo`, `OsdConfig`, `BoxIds`, `BatteryConfig`. Each is an empty packet of that type, and the answer comes back with the same type.

## RC
- `RcFrame` = `[24][count u8][count × int16]`, with 4–18 channels at 1000–2000, sent at `rcRefreshRate` Hz (default 25).
- The Flight app's only use of `rcRefreshRate` is a minimum gap in `Msp.setRawRc` (and `Mavlink`): frames arriving within `1000 / rate / 2` ms of the previous one are **dropped**. DRAUPNE Link announces 255, the most the byte holds (a 1 ms window), and sends at its own rate.
- Channel order is **roll, pitch, yaw, throttle, then AUX1…** - Betaflight's rcmap index order (the order the FC's channel map maps *from*), not AETR. The Flight app applies the FC's `rxmap` (`Msp.processRxMap`) before `MSP_SET_RAW_RC`, and only when the frame has at least 8 channels, so DRAUPNE Link pads every frame to 8 (`ControlLink.padForRxMap`, missing AUX at 1000).
- The Flight RC thread only forwards frames it actually receives. If no frame arrives, nothing is sent to the FC, and Betaflight's RX-loss failsafe triggers.
- **Sequence byte (DRAUPNE Link + DRAUPNE Flight 2.3.0-pc.19 on):** the PC appends one byte to the frame, `[24][count u8][count × int16][seq u8]`, a sequence number counted up per frame and wrapping 0..255. The phone accepts both lengths and drops a frame whose sequence is not newer than the last one it wrote (frames arrive bunched and out of order over 4G; a frame late is a frame wrong). The PC adds the byte only once the phone has reported a version of pc.19 or newer (record 0x40F1): older Flight apps refuse a frame whose length is not `count × 2` (`Udp.processData`, case RcFrame). `ControlLink.rcFrame` builds it, `RC_SEQ_MIN_PC_BUILD` is the cut.

## Video (milestone 2)
- Android `MediaCodec` encoder: H.265, falling back to H.264. I-frame interval 0.5 s. Bitrate up to `bitrateLimit`, adapted by `ChangeBitRate(increase bool)` from the controller.
- `VideoInitialFrame` = `[0][int16 num][width i16][height i16][isHevc bool][isFrontCamera bool][codec config bytes (VPS/SPS/PPS)]`.
- `VideoFrame`/`KeyFrame` = `[1|2][frameNum i16][offset i32][frameSize i32 if offset==0][bytes]`. Frames are split across packets and put back together by offset (`Control/.../FrameFragments.java`).
- The controller lowers the bitrate when frames are lost or late, and raises it again after 10 s without losses (`Udp.processBitRateChange`).

## Telemetry / OSD (milestone 3)
- `TelemetryData` = a run of `[code int16][payload]` records with **no length field**. You must know every code's layout to read past it.
  The full decoder is `Control/.../Udp.processTelemetryData`, and the drawing code is `Control/.../Osd.java`.

## DRAUPNE Flight's own telemetry records (not in upstream; `FcTelemetry` reads them)
| Code | Since | Payload |
|---|---|---|
| `0x40F0` PHONE_LINK_STATS | pc.3 | 5 × int16: longest gap between stick frames in, between stick writes to the FC, slowest USB write (ms), write timeouts, skipped requests |
| `0x40F1` PHONE_APP_VERSION | pc.5 | `[len int16][UTF-8 bytes]`, e.g. `2.3.0-pc.19`, once a second |
| `0x40F2` PHONE_ENCODER | pc.13 | `[bitrate int32 bit/s][intra refresh u8]` |
| `0x40F3` PHONE_POSITION | pc.16 | `[lat i32 1e-7°][lon i32][alt i16 m][accuracy u16 m, 65535 unknown][speed u16 0.1 km/h][course u16][age u16 s]` |
| `0x40F4` PHONE_STATE | pc.19 | `[flags u8][lastRcSeq u8][rcWriteAgeMs u16][rcInToOutMs u16]` - flags: bit 0 the FC's channel map is known, bit 1 USB permission asked and pending, bit 2 USB permission denied, bit 3 camera open; `lastRcSeq` the sequence byte of the last stick frame written to the FC (255 = none); `rcWriteAgeMs` ms since that write (65535 = unknown); `rcInToOutMs` that frame's time from arriving at the phone to its USB write (65535 = unknown). The link log's `pc_to_fc_ms` = half the ping + `rcInToOutMs`. |

## Spectator mode packets (PC to PC, codes 0x60-0x69 and 0x6D)
**Power modes (DRAUPNE Link 1.0.28 + DRAUPNE Flight pc.21):** the PC sends `PowerMode [0x6E][mode u8][checkIntervalMin u8]` (unnumbered, like `StartVideo`; older apps drop it) with mode 0 = normal, 1 = STANDBY (the phone stops its camera and encoder, slows its GPS to one fix per 30 s and its telemetry to 1 Hz, dims its screen; the link stays and no sticks are sent), 2 = DEEP SLEEP (the phone reports it with a countdown, then closes the flight controller's port, the GPS, the video and its UDP socket, locks its screen when it may, and sleeps; every `checkIntervalMin` minutes it listens 20 s for the PC's `Connect` and wakes fully on one). The PC repeats `PowerMode` every 200 ms until the phone's `0x40F5` PHONE_POWER record (once a second, and three times at a change) says the mode: `[mode u8][checkIntervalMin u8][sleepInS u16][caps u8]`, always the last record of its packet; caps bit 0 = the app may lock the screen (device admin), bit 1 = exact alarms allowed, bit 2 = battery optimisation off. An interval of 0 keeps the phone's current one (default 5 min). A request refused because the flight controller is armed is reported as mode 0.

**Sleep check (DRAUPNE Link 1.0.29 + DRAUPNE Flight pc.22):** in each check window the sleeping phone sends `SleepHello [0x6F][battery % u8, 255 = unknown][charging u8][nextCheckMin u8][mV u16]` to the PC's last address, three times 500 ms apart; the PC answers `SleepAnswer [0x70][wake u8]` at once - 0 while it is dormant (the phone closes its window and sleeps on, instead of listening the whole 20 s), 1 after WAKE (the phone wakes fully, and the PC's `Connect`s resume). No answer: the window runs its 20 s as before. The LINK card shows the phone's battery and when it was last heard. While the phone sleeps the PC is *dormant*: it sends no `Connect` (one in a listening window would wake the phone) until WAKE. Both modes are refused, and left, while the flight controller reports armed.

See the table in `Spectate.java`. `PAUSE [0x6D][paused u8]` is sent by the pilot PC every 0.5 s while it holds the stream (its link to the drone is slow) and once with 0 when it goes on; while paused the sequence stands still and packets thrown away at the pause are never resent.
